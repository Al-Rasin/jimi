#!/usr/bin/env python3
"""Tool-call accuracy check for FunctionGemma (PLAN 13).

Uses the exact prompt prefix + grammar the app builds (written by the ToolPromptDump unit test to
app/build/llm/) and a Mac build of llama.cpp `llama-completion`.

    ./gradlew testDebugUnitTest                         # writes app/build/llm/*
    python3 training/toolcall/eval.py --bin <llama-completion> --model models/functiongemma-270m-it-Q8_0.gguf

Exit code is 0 always; read the score line at the end.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLM = ROOT / "app/build/llm"


def run(binary, model, prefix, grammar, text, context):
    user = f"{text}\n({context})" if context else text
    prompt = prefix.removeprefix("<bos>") + f"<start_of_turn>user\n{user}<end_of_turn>\n<start_of_turn>model\n"
    tmp = ROOT / "app/build/llm/eval_prompt.txt"
    tmp.write_text(prompt)
    cmd = [binary, "-m", model, "-f", str(tmp), "-n", "80", "--temp", "0", "-no-cnv", "--no-display-prompt", "-sp", "-t", "6"]
    if grammar:
        cmd += ["--grammar-file", str(LLM / "grammar.gbnf")]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    return out


def parse(out):
    m = re.search(r"<start_function_call>call:([a-z_]+)\{(.*?)\}<end_function_call>", out, re.S)
    if not m:
        return None, {}
    args = {k: (a or b).strip() for k, a, b in re.findall(r"([a-z_]+):(?:<escape>(.*?)<escape>|([^,}]*))", m.group(2))}
    return m.group(1), {k: v for k, v in args.items() if v}


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--bin", required=True)
    p.add_argument("--model", required=True)
    p.add_argument("--cases", default=str(pathlib.Path(__file__).with_name("cases.jsonl")))
    p.add_argument("--no-grammar", action="store_true")
    p.add_argument("--context", default="", help="text the app adds after the request, e.g. the time")
    p.add_argument("-v", action="store_true")
    a = p.parse_args()

    prefix = (LLM / "prompt_prefix.txt").read_text()
    cases = [json.loads(l) for l in open(a.cases) if l.strip()]
    ok_tool = ok_all = 0
    for c in cases:
        out = run(a.bin, a.model, prefix, not a.no_grammar, c["text"], a.context)
        tool, args = parse(out)
        want = c.get("tool")  # null = should answer in text (chat)
        good_tool = tool == want
        good_args = good_tool and all(args.get(k, "").lower() == str(v).lower() for k, v in c.get("args", {}).items())
        ok_tool += good_tool
        ok_all += good_args
        mark = "✓" if good_args else ("~" if good_tool else "✗")
        print(f"{mark} {c['text']!r:45} → {tool} {args}" + ("" if good_args else f"   (want {want} {c.get('args', {})})"))
        if a.v and not good_args:
            print("   raw:", out.strip()[:200])
    n = len(cases)
    print(f"\nSCORE tool {ok_tool}/{n} = {ok_tool / n:.0%}   tool+args {ok_all}/{n} = {ok_all / n:.0%}")


if __name__ == "__main__":
    sys.exit(main())
