#!/usr/bin/env python3
"""Convert reviewed public tool calls to the app's native training format.

No downloads or execution of dataset code. Inputs may be JSONL, a JSON list, or
a Hugging Face /rows response. Default mappings cover only Google's flashlight
actions. Other functions require an explicit, human-reviewed --mapping JSON:
{"search_web": {"tool": "web_search", "args": {"query": "query"}}}.
Mapping keys are source function names; args map source keys to Jarvis keys.
"""

import argparse
import ast
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("jarvis_dataset", Path(__file__).with_name("make_dataset.py"))
dataset = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dataset)

SOURCES = {
    "mobile-actions": "google/mobile-actions",
    "glaive": "glaiveai/glaive-function-calling-v2",
    "hermes": "NousResearch/hermes-function-calling-v1",
    "xlam": "Salesforce/xlam-function-calling-60k",
}
FLASHLIGHT = {
    "turn_on_flashlight": {"tool": "toggle", "args": {}, "constants": {"target": "flashlight", "state": "on"}},
    "turn_off_flashlight": {"tool": "toggle", "args": {}, "constants": {"target": "flashlight", "state": "off"}},
}


class Rejected(ValueError):
    pass


def decode(value, python_literal=False):
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        if python_literal:
            return ast.literal_eval(value)
        raise


def extract(row, source):
    if source == "xlam":
        text, calls = row["query"], decode(row["answers"])
    elif source == "glaive":
        # Only the first direct user -> call pair; clarification/history is not discarded.
        match = re.match(r"\s*USER:\s*(.*?)\s*ASSISTANT:\s*<functioncall>\s*(.*?)\s*<\|endoftext\|>", row["chat"], re.S)
        if not match or re.search(r"\b(?:USER|ASSISTANT):", match[1]):
            raise Rejected("not_direct_single_turn")
        text, calls = match[1], [decode(match[2], python_literal=True)]
    else:
        messages = row["messages"] if source == "mobile-actions" else row["conversations"]
        messages = [m for m in messages if m.get("role", m.get("from")) not in ("system", "developer")]
        if len(messages) < 2 or messages[0].get("role", messages[0].get("from")) not in ("user", "human"):
            raise Rejected("not_direct_single_turn")
        user, assistant = messages[:2]
        if assistant.get("role", assistant.get("from")) not in ("assistant", "gpt"):
            raise Rejected("not_direct_single_turn")
        text = user.get("content", user.get("value"))
        if source == "mobile-actions":
            if len(messages) != 2 or assistant.get("content"):
                raise Rejected("not_direct_single_turn")
            calls = assistant.get("tool_calls") or []
        else:
            content = assistant.get("value", "")
            matches = re.findall(r"<tool_call>\s*(.*?)\s*</tool_call>", content, re.S)
            if re.sub(r"<tool_call>.*?</tool_call>", "", content, flags=re.S).strip():
                raise Rejected("mixed_text_and_call")
            calls = [decode(m) for m in matches]
    if not isinstance(calls, list) or len(calls) != 1:
        raise Rejected("not_one_call")
    call = calls[0].get("function", calls[0])
    args = decode(call.get("arguments", {}))
    if not isinstance(args, dict):
        raise Rejected("arguments_not_object")
    if source == "mobile-actions":
        # HF pads the union of all seven tools' argument columns with nulls.
        args = {k: v for k, v in args.items() if v is not None}
    if not isinstance(text, str) or not text.strip():
        raise Rejected("empty_user")
    return text.strip(), call["name"], args


def schema_from_prefix(prefix):
    if not prefix.startswith("<bos><start_of_turn>developer\n") or not prefix.endswith("<end_of_turn>\n"):
        raise ValueError("Use the exact ToolPromptDump prompt_prefix.txt, ending before the user turn")
    schemas = {}
    for name, body in re.findall(r"declaration:(\w+)\{(.*?)<end_function_declaration>", prefix, re.S):
        args = dict(dataset.tools_in_prompt(f"declaration:{name}{{{body}<end_function_declaration>"))[name]
        required = re.search(r"required:\[(.*?)\]", body, re.S)
        if required is None:
            raise ValueError(f"Missing required array in {name}")
        choices = {}
        for arg, declaration in re.findall(r"(\w+):\{description:(.*?)\}", body, re.S):
            enum = re.search(r"enum:\[(.*?)\]", declaration, re.S)
            if enum:
                choices[arg] = re.findall(r"<escape>(.*?)<escape>", enum[1])
        schemas[name] = (args, re.findall(r"<escape>(.*?)<escape>", required[1]), choices)
    if not schemas:
        raise ValueError("No tool declarations in prefix")
    return schemas


def convert(row, source, prefix, schemas, mappings, provenance, held_out=()):
    if source == "mobile-actions" and row.get("metadata") != "train":
        raise Rejected("source_eval_or_unknown_split")
    text, name, original = extract(row, source)
    if dataset.holdout_key(text) in held_out:
        raise Rejected("held_out_phrase")
    if name not in mappings:
        raise Rejected("unmapped_function:" + name)
    mapping = mappings[name]
    rename = mapping.get("args", {})
    if set(original) - set(rename):
        raise Rejected("unmapped_arguments")
    args = dict(mapping.get("constants", {}))
    for key, value in original.items():
        dest = rename[key]
        if dest in args:
            raise Rejected("argument_collision")
        if not isinstance(value, str) or not value.strip():
            raise Rejected("argument_not_nonempty_string")
        args[dest] = value
    tool = mapping["tool"]
    if tool not in schemas:
        raise Rejected("target_tool_missing")
    order, required, choices = schemas[tool]
    if set(args) - set(order) or set(required) - set(args):
        raise Rejected("target_arguments_invalid")
    if any(not isinstance(v, str) or not v.strip() for v in args.values()):
        raise Rejected("argument_not_nonempty_string")
    if any(k in choices and v not in choices[k] for k, v in args.items()):
        raise Rejected("target_enum_invalid")
    if any("<" in s or "\x00" in s for s in [text, *args.values()]):
        raise Rejected("native_token_or_grammar_character")
    return {
        "prompt": prefix + f"<start_of_turn>user\n{text}\n()<end_of_turn>\n<start_of_turn>model\n",
        "completion": dataset.target(tool, args, order),
        "text": text, "tool": tool, "args": args,
        "provenance": {**provenance, "dataset": SOURCES[source], "source_function": name,
                       "source_split": row.get("metadata", "unspecified"), "mapping": mapping},
    }


def rows_from_file(path):
    raw = path.read_text(encoding="utf-8")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        data = [json.loads(line) for line in raw.splitlines() if line.strip()]
    if isinstance(data, dict) and "rows" in data:
        if any(r.get("truncated_cells") for r in data["rows"]):
            raise ValueError("HF sample contains truncated cells; fetch intact rows")
        return [(r["row_idx"], r["row"]) for r in data["rows"]]
    if not isinstance(data, list):
        raise ValueError("Expected JSON list, JSONL, or HF /rows response")
    return list(enumerate(data))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input", type=Path)
    ap.add_argument("--source", required=True, choices=SOURCES)
    ap.add_argument("--prefix", type=Path, default=ROOT / "app/build/llm/prompt_prefix.txt")
    ap.add_argument("--mapping", type=Path, help="Reviewed exact function/argument mappings; replaces defaults")
    ap.add_argument("--out", type=Path, help="New JSONL file (never overwrites); omit for dry run")
    a = ap.parse_args()
    prefix = a.prefix.read_text()
    schemas = schema_from_prefix(prefix)
    mappings = json.loads(a.mapping.read_text()) if a.mapping else FLASHLIGHT if a.source == "mobile-actions" else {}
    held_out = {dataset.holdout_key(json.loads(line)["text"])
                for filename in ("cases.jsonl", "cases_v2.jsonl")
                for line in Path(__file__).with_name(filename).read_text().splitlines() if line.strip()}
    counts, accepted, seen = Counter(), [], set()
    digest = hashlib.sha256(a.input.read_bytes()).hexdigest()
    for index, row in rows_from_file(a.input):
        try:
            result = convert(row, a.source, prefix, schemas, mappings,
                             {"row_index": index, "input_sha256": digest}, held_out)
            key = dataset.holdout_key(result["text"])
            if key in seen:
                raise Rejected("duplicate_user")
            seen.add(key)
            accepted.append(result)
        except Rejected as error:
            counts[str(error)] += 1
        except (ValueError, TypeError, KeyError, AttributeError, SyntaxError) as error:
            counts["malformed:" + type(error).__name__] += 1
    if a.out:
        with a.out.open("x", encoding="utf-8") as out:
            for row in accepted:
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(json.dumps({"accepted": len(accepted), "rejected": sum(counts.values()),
                      "reasons": dict(sorted(counts.items())), "dry_run": a.out is None}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        sys.exit(str(error))
