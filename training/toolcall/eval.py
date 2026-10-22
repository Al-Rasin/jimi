#!/usr/bin/env python3
"""Tool-call accuracy check for FunctionGemma (PLAN 13).

Uses the exact prompt prefix + grammar the app builds (written by the ToolPromptDump unit test to
app/build/llm/) and a Mac build of llama.cpp `llama-completion`.

    ./gradlew testDebugUnitTest                         # writes app/build/llm/*
    python3 training/toolcall/eval.py --bin <llama-completion> --model models/functiongemma-270m-it-Q8_0.gguf

Model errors exit nonzero; read the score line for completed evaluations.
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[2]
LLM = ROOT / "app/build/llm"


def run(binary, model, prefix, grammar, text, context, prompt_dir=LLM, cpu=False, prompt_cache=None, server_url=None):
    user = f"{text}\n({context})"
    prompt = prefix.removeprefix("<bos>") + f"<start_of_turn>user\n{user}<end_of_turn>\n<start_of_turn>model\n"
    if server_url:
        body = {"prompt": prompt, "temperature": 0, "n_predict": 80,
                "repeat_penalty": 1.0, "cache_prompt": True, "id_slot": 0,
                "stream": False, "stop": []}
        if grammar:
            body["grammar"] = (prompt_dir / "grammar.gbnf").read_text()
        request = urllib.request.Request(server_url.rstrip("/") + "/completion",
                                         data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=120) as response:
            result = json.load(response)
        if not isinstance(result.get("content"), str):
            raise RuntimeError(f"invalid server response for {text!r}: {result}")
        return result["content"]
    with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", encoding="utf-8") as tmp:
        tmp.write(prompt)
        tmp.flush()
        # --file strips the final model-turn newline, changing the learned prompt boundary.
        cmd = [binary, "-m", model, "--binary-file", tmp.name, "--no-escape", "-n", "80", "--temp", "0", "-no-cnv", "--no-display-prompt", "-sp", "-t", "4", "-c", "4096"]
        if grammar:
            cmd += ["--grammar-file", str(prompt_dir / "grammar.gbnf")]
        if cpu:
            cmd += ["--device", "none", "-ngl", "0", "--no-op-offload", "--no-kv-offload", "--fit", "off", "-b", "256", "-ub", "128"]
        if prompt_cache:
            cmd += ["--prompt-cache", str(prompt_cache)]
        result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(f"llama-completion failed ({result.returncode}) for {text!r}:\n{result.stderr[-4000:]}")
    return result.stdout


def parse(out):
    m = re.search(r"<start_function_call>call:([a-z_]+)\{(.*?)\}<end_function_call>", out, re.S)
    if not m:
        return None, {}
    args = {k: (a or b).strip() for k, a, b in re.findall(r"([a-z_]+):(?:<escape>(.*?)<escape>|([^,}]*))", m.group(2))}
    return m.group(1), {k: v for k, v in args.items() if v}


def main():
    p = argparse.ArgumentParser()
    backend = p.add_mutually_exclusive_group(required=True)
    backend.add_argument("--bin")
    backend.add_argument("--server-url", help="local llama-server started with -sp and matching CPU/context flags")
    p.add_argument("--model", required=True)
    p.add_argument("--cases", default=str(pathlib.Path(__file__).with_name("cases.jsonl")))
    p.add_argument("--no-grammar", action="store_true")
    p.add_argument("--context", default="", help="text the app adds after the request, e.g. the time")
    p.add_argument("--prompt-dir", type=pathlib.Path, default=LLM)
    p.add_argument("--cpu", action="store_true", help="disable GPU and Metal offloading")
    p.add_argument("--prompt-cache", type=pathlib.Path, help="cache the common tool prompt between cases")
    p.add_argument("--output-jsonl", type=pathlib.Path, help="new file for raw output and per-case scores")
    p.add_argument("-v", action="store_true")
    a = p.parse_args()
    if a.server_url:
        url = urllib.parse.urlparse(a.server_url)
        if url.scheme != "http" or url.hostname not in ("localhost", "127.0.0.1", "::1"):
            p.error("--server-url must be a local HTTP endpoint")
        with urllib.request.urlopen(a.server_url.rstrip("/") + "/props", timeout=30) as response:
            props = json.load(response)
        if pathlib.Path(props.get("model_path", "")).resolve() != pathlib.Path(a.model).resolve():
            p.error("server loaded model does not match --model")

    prefix = (a.prompt_dir / "prompt_prefix.txt").read_text()
    cases = [json.loads(l) for l in open(a.cases) if l.strip()]
    if a.output_jsonl:
        a.output_jsonl.touch(exist_ok=False)
    ok_tool = ok_all = 0
    for c in cases:
        out = run(a.bin, a.model, prefix, not a.no_grammar, c["text"], c.get("context", a.context), a.prompt_dir, a.cpu, a.prompt_cache, a.server_url)
        tool, args = parse(out)
        want = c.get("tool")  # null = should answer in text (chat)
        good_tool = tool == want
        good_args = good_tool and all(args.get(k, "").lower() == str(v).lower() for k, v in c.get("args", {}).items())
        ok_tool += good_tool
        ok_all += good_args
        if a.output_jsonl:
            with a.output_jsonl.open("a") as results:
                results.write(json.dumps({"case": c, "raw": out, "tool": tool, "args": args,
                                          "correct_tool": good_tool, "correct_args": good_args}) + "\n")
        mark = "✓" if good_args else ("~" if good_tool else "✗")
        print(f"{mark} {c['text']!r:45} → {tool} {args}" + ("" if good_args else f"   (want {want} {c.get('args', {})})"))
        if a.v and not good_args:
            print("   raw:", out.strip()[:200])
    n = len(cases)
    print(f"\nSCORE tool {ok_tool}/{n} = {ok_tool / n:.0%}   tool+args {ok_all}/{n} = {ok_all / n:.0%}")


if __name__ == "__main__":
    sys.exit(main())
