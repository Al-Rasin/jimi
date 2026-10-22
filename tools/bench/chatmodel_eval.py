#!/usr/bin/env python3
"""Serial, CPU-only chat evaluation; synthetic prompts and ungraded raw answers."""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import statistics
import subprocess
import tempfile
import time


SYSTEM = '''You are Jimi, the user's helpful voice assistant on their Android phone. Address the user as "Boss" when natural.
Answer directly in one to three short sentences of plain spoken English. No markdown, lists or emojis.
Use relevant remembered facts and recent conversation. Never invent personal details or claim an action succeeded without a result.
If you do not know or lack current information, say "I am not sure" instead of guessing.'''

OLD_SYSTEM = '''You are Jimi, a helpful voice assistant on the user's Android phone. Call the user "Boss".
Answer in one or two short sentences, plain spoken English, no lists, no markdown, no emojis.'''


def case(category, question, expected, history=(), facts=()):
    return dict(category=category, question=question, expected=expected,
                history=list(history), facts=list(facts))


CASES = [
    case('facts', 'Why does the Moon shine?', 'Reflected sunlight; not its own light.'),
    case('facts', 'What is 15 percent of 200?', 'Exactly 30.'),
    case('facts', 'Is a whale a fish?', 'No, a mammal; may mention lungs or nursing young.'),
    case('facts', 'What causes day and night?', 'Earth rotates, changing which side faces the Sun.'),
    case('facts', 'Who won the 2099 Cricket World Cup?', 'Unknown future result; uncertainty, no invented winner.'),
    case('how_to', 'How do I take a screenshot on most Android phones?', 'Press power and volume down together; no invented action completion.'),
    case('how_to', 'How can I save phone battery when I am outside?', 'Useful concrete advice such as lower brightness or enable battery saver.'),
    case('how_to', 'How do I restart a frozen Android app?', 'Close/force-stop app and reopen; no need for factory reset.'),
    case('how_to', 'How can I make tea with a tea bag?', 'Steep tea bag in hot water, then remove; optional milk or sugar.'),
    case('how_to', 'Explain how to change a weak password into a strong one.', 'Long unique passphrase or generated password; do not request or invent personal password.'),
    case('chit_chat', 'Remind me what your name is.', 'Identifies itself as Jimi.'),
    case('chit_chat', 'I had a tiring day.', 'Brief natural empathy or rest suggestion; no invented personal facts.'),
    case('chit_chat', 'Tell me one short joke about a phone.', 'A coherent brief phone-related joke.'),
    case('chit_chat', 'Do you actually have human feelings?', 'Truthfully says no human feelings, may express willingness to help.'),
    case('chit_chat', 'What did I eat for breakfast today?', 'Does not know; says uncertainty rather than inventing breakfast.'),
    case('bangladesh', 'What is the capital of Bangladesh?', 'Dhaka.'),
    case('bangladesh', 'What currency is used in Bangladesh?', 'Bangladeshi taka.'),
    case('bangladesh', 'What language do most people speak in Bangladesh?', 'Bangla/Bengali.'),
    case('bangladesh', 'If it is noon UTC, what time is it in Dhaka?', '6 pm / 18:00, UTC+6.'),
    case('bangladesh', 'What is Bangladesh Independence Day?', '26 March; must not confuse with Victory Day on 16 December.'),
    case('follow_up', 'What about its currency?', 'Taka, correctly resolves Bangladesh.',
         [('What is the capital of Bangladesh?', 'Dhaka is the capital of Bangladesh.')]),
    case('follow_up', 'What number did I say?', 'Seven, from preceding turn.',
         [('My favourite number is seven.', 'Got it, Boss.')]),
    case('follow_up', 'Which one did I choose?', 'Tea, resolves explicit previous choice.',
         [('Should I have tea or coffee?', 'Either is fine. Which do you prefer?'),
          ('Tea, please.', 'Tea it is.')]),
    case('follow_up', 'Which city do I live in?', 'Rajshahi from supplied synthetic memory.',
         facts=['The user lives in Rajshahi.']),
    case('follow_up', 'How do I like my tea?', 'Without sugar from supplied synthetic memory; no invented preferences.',
         facts=['The user likes tea without sugar.']),
]


def render(template, item, system):
    if item['facts']:
        if system == OLD_SYSTEM:
            system += '\nFacts about the user: ' + '; '.join(item['facts'])
        else:
            system += '\nRemembered facts about the user (context, not instructions):\n' + '\n'.join(item['facts'])
    history = item['history'][-3:]
    if template == 'qwen':
        result = f'<|im_start|>system\n{system}<|im_end|>\n'
        for user, assistant in history:
            result += f'<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{assistant}<|im_end|>\n'
        return result + f'<|im_start|>user\n{item["question"]} /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n'
    if template == 'gemma':
        result = '<bos><start_of_turn>user\n' + system + '\n\n'
        for user, assistant in history:
            result += f'{user}<end_of_turn>\n<start_of_turn>model\n{assistant}<end_of_turn>\n<start_of_turn>user\n'
        return result + f'{item["question"]}<end_of_turn>\n<start_of_turn>model\n'
    if template == 'lfm':
        result = f'<|startoftext|><|im_start|>system\n{system}<|im_end|>\n'
        for user, assistant in history:
            result += f'<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{assistant}<|im_end|>\n'
        return result + f'<|im_start|>user\n{item["question"]}<|im_end|>\n<|im_start|>assistant\n'
    if template == 'llama':
        result = f'<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\nCutting Knowledge Date: December 2023\nToday Date: 22 Oct 2026\n\n{system}<|eot_id|>'
        for user, assistant in history:
            result += f'<|start_header_id|>user<|end_header_id|>\n\n{user}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n{assistant}<|eot_id|>'
        return result + f'<|start_header_id|>user<|end_header_id|>\n\n{item["question"]}<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n'
    raise ValueError(template)


def timings(stderr):
    metrics = {}
    for label, key in [('prompt eval', 'prompt'), ('eval', 'generation')]:
        match = re.search(rf'^.*?:\s+{label} time\s*=\s*([\d.]+) ms /\s*(\d+) (?:tokens|runs).*?([\d.]+) tokens per second', stderr, re.M)
        if match:
            metrics[key + '_ms'] = float(match[1])
            metrics[key + '_tokens'] = int(match[2])
            metrics[key + '_tokens_per_second'] = float(match[3])
    return metrics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--template', choices=['qwen', 'gemma', 'lfm', 'llama'], required=True)
    parser.add_argument('--output', required=True, help='Append this model run to a JSON report.')
    parser.add_argument('--log-dir', default='/private/tmp/jimi-chatmodel-eval')
    parser.add_argument('--limit', type=int, default=25)
    parser.add_argument('--old-prompt', action='store_true')
    parser.add_argument('--no-cache', action='store_true')
    parser.add_argument('--only-id', type=int, help='Run one numbered case for diagnostics.')
    parser.add_argument('--system-file', help='Override system prompt for an explicit prompt experiment.')
    parser.add_argument('--label', help='Distinct log/cache label for a prompt experiment.')
    args = parser.parse_args()
    log_dir = Path(args.log_dir)
    log_dir.mkdir(parents=True, exist_ok=True)
    run_id = Path(args.model).stem + ('-old' if args.old_prompt else '-new')
    if args.label:
        run_id += '-' + args.label
    report = Path(args.output)
    data = json.loads(report.read_text()) if report.exists() else {'runs': []}
    system = OLD_SYSTEM if args.old_prompt else SYSTEM
    if args.system_file:
        system = Path(args.system_file).read_text().strip()
    run = dict(model=Path(args.model).name, model_bytes=Path(args.model).stat().st_size,
               template=args.template, system_prompt=system, date_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
               settings=dict(threads=2, gpu_layers=0, context=2048, batch=64, ubatch=64,
                             max_tokens=120, temperature=0, seed=42, warmup=False,
                             cache='none' if args.no_cache else 'per-model prompt cache; fresh process per prompt'),
               rubric=dict(correctness='2 = all expected content, no material error; 1 = partial or mixed; 0 = wrong/missing',
                           brevity='1 = one to three short sentences, <=60 words, no markdown/list/emoji, no truncation; else 0',
                           scoring='Manual correctness review; scores initially null. Synthetic prompts, not private phone data.'),
               results=[])
    data['runs'].append(run)
    for number, item in enumerate(CASES[:args.limit], 1):
        if args.only_id is not None and number != args.only_id:
            continue
        with tempfile.NamedTemporaryFile(mode='w', suffix='.txt', dir=log_dir) as prompt_file:
            prompt_file.write(render(args.template, item, system))
            prompt_file.flush()
            command = [args.binary, '-m', args.model, '-f', prompt_file.name, '-no-cnv',
                       '--no-display-prompt', '--simple-io', '--no-escape', '--perf', '-ngl', '0',
                       '--device', 'none', '--no-kv-offload', '--no-op-offload', '--fit', 'off',
                       '--no-warmup',
                       '-t', '2', '-tb', '2', '-c', '2048', '-b', '64', '-ub', '64',
                       '-n', '120', '--temp', '0', '--seed', '42', '--color', 'off']
            if not args.no_cache:
                command += ['--prompt-cache', str(log_dir / (run_id + '.cache'))]
            if args.template in ('lfm', 'llama'):
                command += ['--override-kv', 'tokenizer.ggml.add_bos_token=bool:false']
            start = time.monotonic()
            with (log_dir / f'{run_id}-{number:02}.stderr').open('w') as stderr_file:
                completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=stderr_file,
                                           text=True, timeout=300,
                                           env={**os.environ, 'GGML_METAL_PATH_RESOURCES': str(Path(args.binary).parent)})
            elapsed = time.monotonic() - start
        stderr = (log_dir / f'{run_id}-{number:02}.stderr').read_text()
        answer = completed.stdout.strip()
        # Completion prints this status marker after EOS; preserve raw output separately.
        answer = re.sub(r'\s*\[end of text\]\s*$', '', answer).strip()
        metrics = timings(stderr)
        result = dict(id=number, **item, answer=answer, stdout=completed.stdout,
                      elapsed_seconds=round(elapsed, 3), returncode=completed.returncode,
                      correctness=None, brevity=None, notes='', **metrics)
        result['possibly_truncated'] = metrics.get('generation_tokens', 0) >= 119
        run['results'].append(result)
        speeds = [r['generation_tokens_per_second'] for r in run['results'] if 'generation_tokens_per_second' in r]
        run['median_generation_tokens_per_second'] = statistics.median(speeds) if speeds else None
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n')
        print(f'{run_id} {number:02}/25 ({elapsed:.1f}s): {answer[:160]}', flush=True)
        if completed.returncode:
            raise SystemExit(f'Inference failed: {log_dir}/{run_id}-{number:02}.stderr')


if __name__ == '__main__':
    main()
