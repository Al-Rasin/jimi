#!/usr/bin/env python3
"""Deterministic Round 1 corpus; no model, network, or training dependency."""
import argparse
from collections import Counter
import datetime as dt
import hashlib
import json
from pathlib import Path
import random
import re

import make_dataset as base
from r1_contract import load_contract, validate_call

DEFAULT_OUT = Path('/Users/alrasin/Development/Personal/jarvis/training/data/toolcall_r1')
QUOTAS = {'train': {'exact': 6000, 'chat': 2000, 'boundary': 1500, 'asr': 1500, 'public': 1000},
          'dev': {'exact': 500, 'chat': 170, 'boundary': 130, 'asr': 130, 'public': 70},
          'test': {'exact': 500, 'chat': 170, 'boundary': 130, 'asr': 130, 'public': 70}}
FINITE = {'toggle', 'media_control', 'set_volume', 'set_brightness', 'device_status', 'scroll'}
PREFIXES = ['', 'please ', 'could you ', 'would you ', 'can you ', 'jimi ', 'hey jimi ',
            'okay jimi ', 'please jimi ', 'right now ', 'when you can ', 'for me please ']
SUFFIXES = ['', ' please', ' for me', ' now', ' right now', ' on this phone']
NOARGS = {
 'cancel_alarm': ['dismiss the ringing alarm', 'stop this ringing alarm', 'dismiss the clock alarm', 'silence my ringing alarm', 'stop the alarm that is ringing', 'dismiss my current alarm', 'silence the clock alarm', 'dismiss the alarm currently sounding'],
 'read_messages': ['read out the newest SMS messages', 'show the latest texts in my inbox', 'tell me what my text inbox contains', 'read my incoming SMS aloud', 'check the recent SMS messages', 'read out my text inbox', 'show me the newest SMS', 'tell me about my incoming texts'],
 'take_screenshot': ['save a picture of this screen', 'capture what is displayed', 'make a screenshot of this page', 'take a screen capture', 'save this display as an image', 'snap a picture of the display', 'capture the current display', 'get an image of my screen'],
 'lock_phone': ['lock this handset', 'put this phone on the lock screen', 'secure the phone by locking it', 'switch off this display and lock', 'put my handset to sleep', 'lock the display of this phone', 'activate my lock screen', 'lock this device'],
 'unlock_phone': ['unlock this handset', 'dismiss this phone lock screen', 'open this phone lock screen', 'unlock this device', 'release the phone screen lock', 'unlock the display of this phone', 'get past my lock screen', 'unlock my handset screen'],
 'read_screen': ['read the text currently displayed', 'say the words visible on this screen', 'read aloud the page on my display', 'tell me the text shown here', 'read the visible text for me', 'read out this display text', 'say what is written on this page', 'read the current screen text aloud'],
 'go_back': ['return to the preceding screen', 'navigate one screen back', 'use the Android back button', 'take me to the screen before this one', 'move back one page in this app', 'press the system back key', 'return to the last screen', 'navigate back from this page'],
 'go_home': ['return to my Android home screen', 'show the launcher screen', 'take me to the phone launcher', 'press the system home key', 'leave this app for the home screen', 'display the Android launcher', 'bring up my launcher', 'return to the launcher page'],
 'open_recents': ['bring up the recent applications view', 'show the Android app switcher', 'display the list of recent apps', 'open the multitasking view', 'show the recently used applications', 'bring up the running apps view', 'press the recents button', 'display the app switching screen'],
 'read_notes': ['read out my saved notes', 'show the notes I have saved', 'tell me what my recent notes say', 'read my newest saved notes', 'display my stored notes', 'show what I wrote in my notes', 'read aloud the notes I saved', 'list my recent notes'],
 'list_reminders': ['show my pending reminders', 'read the reminders I have set', 'list the reminders still pending', 'tell me my scheduled reminders', 'show the reminders coming up', 'read out my reminder list', 'display the pending reminder list', 'tell me which reminders remain'],
 'recall_facts': ['list the facts you remember about me', 'read out your stored facts about me', 'tell me the personal facts I asked you to remember', 'show what you have remembered about me', 'recall the facts I gave you', 'list your saved facts about me', 'read my remembered information', 'tell me what is saved in your memory about me'],
 'read_notifications': ['read the alerts in my notification tray', 'show the notifications waiting on this phone', 'read out the latest phone alerts', 'tell me which notifications have arrived', 'read my notification tray aloud', 'show my incoming notifications', 'read the alerts currently visible', 'tell me what alerts are waiting'],
 'notification_summary': ['give a brief overview of my notifications', 'summarize the alerts I received', 'give me the highlights from my notifications', 'provide a short notification recap', 'brief me on my latest notifications', 'summarize the incoming alerts', 'give a quick recap of phone alerts', 'tell me the main points of my notifications'],
 'clear_notifications': ['dismiss every notification in the tray', 'clear out my notification tray', 'remove the alerts from my notification shade', 'dismiss all the phone alerts', 'clear the notification shade', 'remove every dismissible notification', 'wipe the notifications from the tray', 'dismiss the alerts on this phone'],
}
CHAT_TOPICS = (
 'friendship kindness patience curiosity courage gratitude honesty imagination creativity empathy '
 'rainbows clouds snowflakes butterflies oceans mountains deserts volcanoes rivers forests '
 'sleep dreams laughter boredom happiness sadness loneliness motivation confidence habits '
 'painting drawing poetry novels riddles jokes storytelling music rhythm harmony melody '
 'chess puzzles football cricket badminton swimming cycling hiking gardening cooking '
 'bread rice pasta tea coffee chocolate mangoes bananas oranges apples '
 'cats dogs elephants dolphins whales penguins owls eagles turtles rabbits '
 'stars planets galaxies comets gravity light sound electricity magnetism atoms '
 'numbers fractions geometry algebra probability infinity logic patterns symmetry sequences '
 'history languages culture traditions festivals architecture pottery weaving sculpture photography '
 'learning reading writing listening speaking memory attention concentration practice revision '
 'teamwork leadership cooperation trust fairness respect forgiveness generosity politeness humility '
 'seasons spring summer autumn winter sunrise sunset moonlight daylight starlight '
 'bicycles trains airplanes boats bridges tunnels roads buildings windows clocks '
 'leaves roots flowers seeds pollen mushrooms moss coral shells sand '
 'exercise stretching balance breathing relaxation meditation rest nutrition digestion hydration '
 'technology programming algorithms robotics computers batteries engines motors sensors circuits'
).split()
CHAT_TEMPLATES = [
 'explain why people enjoy {x}', 'tell me a fun fact about {x}', 'write a short poem about {x}',
 'make up a story involving {x}', 'what makes {x} interesting', 'explain {x} in simple words',
 'give me a metaphor for {x}', 'can we talk about {x}', 'tell me a joke about {x}',
 'write a riddle about {x}', 'give me a creative idea involving {x}', 'what can we learn from {x}',
 'describe {x} to a beginner', 'make up a fictional dialogue about {x}', 'I have been thinking about {x}',
 'why do children ask questions about {x}', 'tell me something surprising about {x}',
 'write a friendly sentence about {x}', 'suggest a title for a story about {x}',
 'what questions could I ask about {x}', 'explain how {x} can inspire people',
 'imagine a world without {x}', 'give me three words associated with {x}',
 'how would a poet describe {x}', 'invent a silly comparison involving {x}',
]


def family_key(user, tool, args):
    if tool:
        normalized = {k: base.normalize(v) for k, v in args.items()}
        if tool == 'call':
            normalized.setdefault('app', 'phone')
        if tool == 'play_media':
            normalized.setdefault('app', 'youtube')
        if tool == 'weather':
            normalized.setdefault('day', 'today')
        if tool == 'create_reminder':
            normalized.setdefault('repeat', 'none')
        aliases = {'mum': 'mom', 'mummy': 'mom', 'my mother': 'mom', 'mother': 'mom',
                   'my father': 'dad', 'father': 'dad'}
        if 'contact' in normalized:
            normalized['contact'] = aliases.get(normalized['contact'], normalized['contact'])
        if not args:
            return 'train-only:noargs:' + tool
        if tool == 'toggle' and args.get('target') == 'flashlight':
            return 'train-only:flashlight:' + args.get('state', 'toggle')
        payload = [tool, sorted(normalized.items())]
    else:
        payload = [tool or 'chat', base.holdout_key(user), sorted(args.items())]
    return 'r1:' + hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()[:24]


def split_for_family(family, seed=1):
    if family.startswith('train-only:'):
        return 'train'
    bucket = int(hashlib.sha256(f'{seed}:{family}'.encode()).hexdigest()[:8], 16) % 100
    return 'train' if bucket < 80 else 'dev' if bucket < 90 else 'test'


def fixture_keys():
    return {base.holdout_key(json.loads(line)['text'])
            for path in (base.EVAL, base.EVAL.with_name('cases_v2.jsonl'))
            for line in path.read_text().splitlines() if line.strip()}


def is_fixture(text, keys):
    repaired = re.sub(r'\b(flushlight|flash lite)\b', 'flashlight', text.lower())
    repaired = re.sub(r'\bdown on\b', 'turn on', repaired)
    repaired = re.sub(r'\bsound of\b', 'turn off', repaired)
    return base.holdout_key(text) in keys or base.holdout_key(repaired) in keys


def wrap(text, rng, tool=None):
    prefixes = PREFIXES
    if re.match(r"(?:what|who|why|how|when|which|where|is |are |do |did |will |can |could |i |let|any |too |no )", text.lower()):
        prefixes = ['', 'jimi ', 'hey jimi ', 'okay jimi ', 'hi jimi ', 'um ']
    payload_tools = {'send_sms', 'send_whatsapp', 'type_text', 'create_note', 'remember_fact',
                     'forget_fact', 'delete_reminder', 'play_media', 'web_search', 'wiki_lookup', 'compose_email'}
    suffix = '' if tool in payload_tools else rng.choice(SUFFIXES)
    return rng.choice(prefixes) + text + suffix


def noisy(text, tool, rng):
    # Unlike the older augmenter, do not lowercase literal slot payloads.
    if tool == 'toggle':
        text = re.sub(r'\bflashlight\b', 'flushlight', text, flags=re.I)
        if rng.random() < .3:
            text = re.sub(r'\bturn off\b', 'sound of', text)
        text = re.sub(r'\boff\b', 'of', text)
    if tool == 'set_volume':
        text = re.sub(r'\bvolume\b', 'sound', text, flags=re.I)
    return rng.choice(['izumi ', 'hayzumi ', 'hey jimmy ', 'hi jimmy ', 'um ', '']) + text


def validate_rows(splits, schemas):
    owners, texts = {}, set()
    keys = fixture_keys()
    prefix, actual_schemas = load_contract()
    if schemas != actual_schemas:
        raise ValueError('Schema differs from Kotlin registry')
    for split, rows in splits.items():
        if split not in QUOTAS:
            raise ValueError('Unknown dataset split')
        for row in rows:
            if row.get('split') != split:
                raise ValueError('Row split differs from output split')
            validate_call(row['tool'], row['args'], schemas)
            family = row['family']
            if row['tool'] and family != family_key(row['text'], row['tool'], row['args']):
                raise ValueError('Stored family differs from semantic tool family')
            if family in owners and owners[family] != split:
                raise ValueError('Request family crosses splits')
            owners[family] = split
            key = base.normalize(row['text'])
            if key in texts or is_fixture(row['text'], keys):
                raise ValueError('Duplicate or fixture overlap')
            texts.add(key)
            if not row.get('provenance') or not row.get('review'):
                raise ValueError('Missing provenance or review status')
            provenance = row['provenance']
            if provenance.get('dataset'):
                required = ('license', 'revision', 'source_url', 'source_split', 'source_file', 'input_sha256', 'attribution')
                if any(not provenance.get(field) for field in required) or provenance.get('row_index') is None:
                    raise ValueError('Missing public licence or provenance')
                if provenance['source_split'] != 'train':
                    raise ValueError('Public evaluation source is forbidden')
            elif row['slice'] == 'public' and provenance.get('source') != 'local_synthetic_public_shortfall':
                raise ValueError('Public slice lacks source provenance')
            expected_prompt = prefix + f"<start_of_turn>user\n{row['text']}\n({row['context']})<end_of_turn>\n<start_of_turn>model\n"
            if row['prompt'] != expected_prompt:
                raise ValueError('Prompt differs from exact Kotlin contract')
            if row.get('router') and (row['tool'] is not None or row.get('call_only_training') is not False):
                raise ValueError('Router-only row must abstain and remain separate')
            expected = base.target(row['tool'], row['args'], schemas[row['tool']][0]) if row['tool'] else 'chat<end_of_turn>'
            if row['completion'] != expected:
                raise ValueError('Completion differs from exact Kotlin contract')


def write_outputs(splits, out, stats):
    out = Path(out)
    targets = [out / f'{name}.jsonl' for name in ('train', 'dev', 'test')] + [out / 'stats.json']
    if any(path.exists() for path in targets):
        raise FileExistsError('Refusing to overwrite existing corpus or locked test; use a fresh output directory')
    out.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for split, rows in splits.items():
        content = ''.join(json.dumps(row, ensure_ascii=False, sort_keys=True) + '\n' for row in rows)
        (out / f'{split}.jsonl').write_text(content)
        hashes[split] = hashlib.sha256(content.encode()).hexdigest()
    stats['sha256'] = hashes
    (out / 'stats.json').write_text(json.dumps(stats, indent=2, sort_keys=True) + '\n')


def boundary_seeds():
    missing = [
        ('call', 'call someone', 'contact'), ('call', 'make a phone call', 'contact'),
        ('call', 'call on whatsapp', 'contact'), ('open_app', 'open an app', 'app'),
        ('open_app', 'launch the app', 'app'), ('close_app', 'close an app', 'app'),
        ('set_alarm', 'set an alarm sometime', 'time'), ('set_timer', 'start a timer', 'seconds'),
        ('create_reminder', 'remind me later', 'text,time'), ('play_media', 'play a song', 'query'),
        ('send_sms', 'send a text message', 'contact,text'), ('send_whatsapp', 'send a whatsapp message', 'contact,text'),
        ('create_note', 'save a note', 'text'), ('remember_fact', 'remember something for me', 'text'),
        ('forget_fact', 'forget a fact about me', 'text'), ('delete_reminder', 'delete a reminder', 'text'),
        ('web_search', 'search the internet', 'query'), ('wiki_lookup', 'look something up on wikipedia', 'topic'),
        ('read_page', 'read a web page', 'url'), ('open_url', 'open a website', 'url'),
        ('tap', 'tap the button', 'text'), ('type_text', 'type something', 'text'),
        ('set_volume', 'set my volume', 'level'), ('toggle', 'turn it on', 'target'),
        ('set_brightness', 'set my brightness', 'level'), ('scroll', 'scroll the page', 'direction'),
    ]
    for tool, text, slot in missing:
        yield text, f'missing:{tool}:{slot}', {'kind': 'missing_slots', 'requested_tool': tool, 'missing_slots': slot.split(',')}
    for contact in base.CONTACTS + ['mum', 'mummy', 'amma', 'abbu', 'my mother', 'my aunt', 'my uncle']:
        canonical_contact = {'mum': 'mom', 'mummy': 'mom', 'my mother': 'mom', 'mother': 'mom',
                             'my father': 'dad', 'father': 'dad'}.get(contact, contact)
        for tool, verb in [('send_sms', 'send a text to'), ('send_whatsapp', 'send a whatsapp message to')]:
            yield f'{verb} {contact}', f'missing:{tool}:text:{canonical_contact}', {'kind': 'missing_slots', 'requested_tool': tool, 'missing_slots': ['text']}
    unsupported = [
        'book a flight', 'order a taxi', 'buy a train ticket', 'transfer money', 'pay my electricity bill',
        'delete my account', 'post a photo to instagram', 'upload a video to youtube', 'change my banking password',
        'install a new operating system', 'send a messenger message', 'make a messenger call', 'make a telegram call',
        'turn on my living room lights', 'unlock my front door', 'start my washing machine', 'print a document',
        'schedule a social media post', 'subscribe to a youtube channel', 'like a facebook post',
        'create a calendar event', 'record a voice memo', 'record my screen', 'take a selfie',
        'edit a photo', 'crop a video', 'uninstall an app', 'change the wallpaper', 'pair a new bluetooth device',
        'connect to a new wifi network', 'set a new screen lock PIN', 'forward an email', 'download an attachment',
        'share my live location', 'translate the entire screen', 'move files to my SD card', 'empty my trash',
        'send a voice message', 'accept a video call', 'block a caller', 'add a contact', 'merge duplicate contacts',
    ]
    for request in unsupported:
        yield request, f'unsupported:{request}', {'kind': 'unsupported'}
    for request in ['cancel that request', 'never mind that action', 'do not do anything', 'stop what I just asked',
                    'I changed my mind stop', 'leave it alone', 'forget my last request', 'do not proceed',
                    'abort the request', 'no action please', 'cancel the previous command', 'do nothing for now']:
        yield request, 'train-only:cancel', {'kind': 'cancel'}


def build(out=DEFAULT_OUT, public_path=None, seed=1):
    prefix, schemas = load_contract()
    rng = random.Random(seed)
    base.r.seed(seed)
    keys, seen, key_owners = fixture_keys(), set(), {}
    splits = {name: [] for name in QUOTAS}
    counts = {name: Counter() for name in QUOTAS}
    tool_counts = {name: Counter() for name in QUOTAS}
    public = [json.loads(line) for line in Path(public_path).read_text().splitlines() if line.strip()] if public_path else []
    # Any mappable public utterance owns its normalized surface before local expansion.
    public_keys = {base.holdout_key(row['text']) for row in public}

    def add(text, tool, args, family, slice_name, now, provenance=None, routing=None, force_split=None):
        split = force_split or split_for_family(family, seed)
        if counts[split][slice_name] >= QUOTAS[split][slice_name]:
            return False
        key, held_key = base.normalize(text), base.holdout_key(text)
        if key in seen or is_fixture(text, keys) or (slice_name != 'public' and held_key in public_keys):
            return False
        if held_key in key_owners and key_owners[held_key] != split:
            return False
        validate_call(tool, args, schemas)
        completion = base.target(tool, args, schemas[tool][0]) if tool else 'chat<end_of_turn>'
        context = f'time {now:%Y-%m-%d %H:%M:%S}'
        row = {'text': text, 'tool': tool, 'args': args, 'family': family, 'slice': slice_name,
               'split': split, 'context': context,
               'slice_detail': 'public_fallback' if provenance and provenance.get('source') == 'local_synthetic_public_shortfall' else slice_name,
               'prompt': prefix + f'<start_of_turn>user\n{text}\n({context})<end_of_turn>\n<start_of_turn>model\n',
               'completion': completion,
               'provenance': provenance or {'source': 'local_synthetic', 'generator': 'make_round1.py', 'seed': seed},
               'review': {'status': 'automated_contract_and_template_review', 'human_review': 'pending',
                          'checks': ['kotlin_schema', 'exact_completion', 'fixture_exclusion', 'family_partition', 'deduplication']}}
        if routing:
            row['router'] = routing
            row['call_only_training'] = False
        else:
            row['call_only_training'] = True
        seen.add(key)
        key_owners[held_key] = split
        splits[split].append(row)
        counts[split][slice_name] += 1
        if slice_name == 'exact':
            tool_counts[split][tool] += 1
        return True

    now = dt.datetime(2026, 10, 22, 10, 0)
    for row in public:
        family = family_key(row['text'], row['tool'], row['args'])
        add(row['text'], row['tool'], row['args'], family, 'public', now, row['provenance'])

    # Keep aliases as user-supplied spans; never invent contact IDs or numbers.
    base.CONTACTS = list(dict.fromkeys(base.CONTACTS + ['mum', 'mummy', 'mom mobile', 'mom work', 'amma', 'abbu', 'my mother', 'my father', 'Amina', 'Rafi', 'Nadia', 'Farhan', 'Shreya', 'Omar', 'Rupa', 'Tariq', 'Lina', 'Joya']))
    base.SONGS = list(dict.fromkeys(base.SONGS + ['acoustic guitar music', 'rain sounds', 'jazz piano', 'morning ragas', 'folk songs', 'old Bengali songs', 'instrumental flute', 'nature sounds', 'study music', 'classical violin', 'ocean sounds', 'soft piano music']))
    targets = {name: 120 for name in schemas}
    for name, extra in [('call', 240), ('open_app', 180), ('play_media', 240), ('send_whatsapp', 120), ('media_control', 60)]:
        targets[name] += extra
    if sum(targets.values()) != 6000:
        raise ValueError('Tool registry changed; review balanced quotas')
    attempts = 0
    while any(counts[s]['exact'] < QUOTAS[s]['exact'] or counts[s]['asr'] < QUOTAS[s]['asr'] for s in splits):
        attempts += 1
        if attempts > 350000:
            raise RuntimeError(f'Insufficient distinct tool families: {counts}, exact per tool {tool_counts}')
        tool = rng.choice(list(schemas))
        when = now + dt.timedelta(days=rng.randrange(180), minutes=rng.randrange(600))
        if tool in NOARGS and (tool == 'cancel_alarm' or rng.random() < .8):
            text, args = rng.choice(NOARGS[tool]), {}
        else:
            text, _, args = base.gen_tool(tool, when)
        # The old generator includes video calls and ambiguous alarm shorthand.
        if 'video call' in text or 'open koro' in text:
            continue
        needs_clock = tool == 'set_alarm' or (tool == 'create_reminder' and re.search(r'\bat \d', text))
        if needs_clock and not re.search(r'am\b|pm\b|a\.m\.|p\.m\.|morning|evening|night', text):
            continue
        if tool == 'wiki_lookup' and 'wikipedia' not in text:
            text = rng.choice(['look up {x} on wikipedia', 'read a wikipedia summary about {x}', 'search wikipedia for {x}',
                               'find the wikipedia article about {x}']).format(x=args['topic'])
        family = family_key(text, tool, args)
        split = split_for_family(family, seed)
        if is_fixture(text, keys):
            continue
        if counts[split]['exact'] < QUOTAS[split]['exact'] and (split != 'train' or tool_counts[split][tool] < targets[tool]):
            add(wrap(text, rng, tool), tool, args, family, 'exact', when)
        if counts[split]['asr'] < QUOTAS[split]['asr']:
            add(noisy(text, tool, rng), tool, args, family, 'asr', when)

    # Conversational requests must not dispatch a matching action keyword.
    for topic in ['calling mom', 'opening YouTube', 'WhatsApp', 'phone calls', 'playing music', 'text messages', 'screenshots', 'notifications']:
        for template in CHAT_TEMPLATES:
            add(template.format(x=topic), None, {}, 'chat-topic:' + topic, 'chat', now)
    topics = CHAT_TOPICS[:]
    rng.shuffle(topics)
    for topic in topics:
        family = 'chat-topic:' + topic
        for template in CHAT_TEMPLATES:
            add(template.format(x=topic), None, {}, family, 'chat', now)

    boundary = list(boundary_seeds())
    rng.shuffle(boundary)
    for text, family, routing in boundary:
        for pre in ['', 'jimi ', 'hey jimi ', 'okay ', 'um ', 'hey jimmy ']:
            for suffix in ['', ' please', ' now', ' right now']:
                add(pre + text + suffix, None, {}, family, 'boundary', now, routing=routing)

    # Research plan explicitly allows a shortfall in trustworthy public mappings.
    public_actual = {s: counts[s]['public'] for s in splits}
    for split in splits:
        shortfall = QUOTAS[split]['public'] - counts[split]['public']
        if shortfall:
            made = 0
            for topic in CHAT_TOPICS:
                family = 'chat-topic:' + topic
                if split_for_family(family, seed) != split:
                    continue
                for template in ['write a two line fable about {x}', 'give an analogy that explains {x}', 'tell a bedtime story about {x}',
                                 'describe an imaginary museum of {x}', 'write a cheerful slogan about {x}', 'invent a proverb about {x}',
                                 'what would a child wonder about {x}', 'write a haiku inspired by {x}', 'explain a misconception about {x}',
                                 'give a simple example involving {x}', 'make a playful rhyme about {x}', 'imagine a conversation about {x}']:
                    if add(template.format(x=topic), None, {}, family, 'public', now,
                           {'source': 'local_synthetic_public_shortfall', 'generator': 'make_round1.py', 'seed': seed}):
                        made += 1
                    if made == shortfall:
                        break
                if made == shortfall:
                    break
    for split in splits:
        if dict(counts[split]) != QUOTAS[split]:
            raise RuntimeError(f'Quota shortfall in {split}: {dict(counts[split])}')
        rng.shuffle(splits[split])
    validate_rows(splits, schemas)
    stats = {'version': 'round1', 'seed': seed, 'contract_sha256': hashlib.sha256(prefix.encode()).hexdigest(),
             'fixture_sha256': {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (base.EVAL, base.EVAL.with_name('cases_v2.jsonl'))},
             'counts': {s: len(rows) for s, rows in splits.items()},
             'per_slice': {s: dict(counts[s]) for s in splits},
             'per_slice_detail': {s: dict(Counter(row['slice_detail'] for row in rows)) for s, rows in splits.items()},
             'per_tool': {s: dict(sorted(Counter(r['tool'] or 'chat' for r in rows).items())) for s, rows in splits.items()},
             'exact_per_tool': {s: dict(sorted(tool_counts[s].items())) for s in splits},
             'public_mappings': public_actual, 'public_shortfall_fallback': {s: QUOTAS[s]['public'] - public_actual[s] for s in splits},
             'review': {'human_reviewed_rows': 0, 'automated_reviewed_rows': sum(map(len, splits.values())), 'human_audit_pending': True},
             'families': {s: len({r['family'] for r in rows}) for s, rows in splits.items()},
             'test_locked': True, 'test_lock': 'write-once corpus plus SHA256 manifest',
             'family_definition': 'tool plus normalized slot tuple, including finite enums and contact aliases/default phone; noarg and flashlight families train-only; chat topic; missing-slot/action family; split before wrappers and ASR',
             'limitations': ['Synthetic template review and automatic validation are not human row review.',
                            'No-argument and flashlight families are train-only to preserve strict semantic family separation; evaluation does not cover those tools.',
                            'call app=whatsapp is declared but current executor still uses carrier ACTION_CALL.',
                            'Boundary rows target chat; router metadata is separate and call_only_training=false.',
                            'Public shortfalls use explicitly identified local synthetic fallback per research plan.']}
    write_outputs(splits, out, stats)
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT)
    parser.add_argument('--public', type=Path)
    parser.add_argument('--seed', type=int, default=1)
    args = parser.parse_args()
    stats = build(args.out, args.public, args.seed)
    print(json.dumps({'counts': stats['counts'], 'public_mappings': stats['public_mappings'], 'out': str(args.out)}, sort_keys=True))


if __name__ == '__main__':
    main()
