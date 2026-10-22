#!/usr/bin/env python3
import argparse
from collections import Counter, defaultdict
from difflib import get_close_matches
import html
import json
from pathlib import Path
import re


EXPECTED_WORDS = (
    "flashlight", "volume", "turn", "on", "off", "open", "call", "message", "jimi"
)


def markdown(value):
    text = html.escape(str(value if value is not None else ""))
    text = re.sub(r"([\\`*_{}\[\]()#+.!|>~-])", r"\\\1", text)
    return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "<br>")


def suspected_words(commands):
    counts = Counter()
    for _, transcript in commands:
        words = re.findall(r"[a-z]+", transcript.lower())
        index = 0
        while index < len(words):
            word = words[index]
            if index + 1 < len(words) and word + words[index + 1] in EXPECTED_WORDS:
                expected = word + words[index + 1]
                counts[(word + " " + words[index + 1], expected)] += 1
                index += 2
                continue
            if word not in EXPECTED_WORDS:
                matches = get_close_matches(word, EXPECTED_WORDS, n=1, cutoff=0.75)
                if word == "jimmy":
                    matches = ["jimi"]
                if matches:
                    counts[(word, matches[0])] += 1
            index += 1
    return counts


def summarize(folder):
    folder = Path(folder)
    if not folder.is_dir():
        raise ValueError(f"Input folder does not exist: {folder}")
    groups = defaultdict(list)
    warnings = []
    source = folder / "wrong_answers.jsonl"
    if source.exists():
        with source.open(encoding="utf-8") as handle:
            for number, line in enumerate(handle, 1):
                if not line.strip():
                    continue
                try:
                    record = json.loads(line)
                    if not isinstance(record, dict):
                        raise ValueError("expected a JSON object")
                    tool = record.get("tool")
                    if tool is not None and not isinstance(tool, str):
                        raise ValueError("tool must be text or null")
                    groups[tool or "(no tool)"].append(record)
                except ValueError as error:
                    warnings.append(f"wrong_answers.jsonl line {number}: {error}")
    commands = [
        (path.name, path.read_text(encoding="utf-8").strip())
        for path in sorted((folder / "commands").glob("*.txt"))
        if path.is_file()
    ]
    lines = ["# Jimi diagnostic report", "", "## Wrong answers by tool", ""]
    if not groups:
        lines.extend(["No wrong answers found.", ""])
    for tool, records in sorted(groups.items()):
        lines.extend([f"### {markdown(tool)} ({len(records)})", ""])
        for record in records:
            lines.extend([
                f"- Time: {markdown(record.get('time'))}; turn: {markdown(record.get('turnId'))}",
                f"  - Heard: {markdown(record.get('heard'))}",
                f"  - Reply: {markdown(record.get('reply'))}",
                f"  - Note: {markdown(record.get('note'))}",
                "",
            ])
    empty = sum(not transcript for _, transcript in commands)
    lines.extend([
        "## STT results", "",
        f"Empty STT results: {empty} / {len(commands)} command transcripts.", "",
        "## Suspected misheard words", "",
        "Spelling-based guesses from command transcripts only, not confirmed errors. "
        "Counts are occurrences; wrong-answer records are not counted again. "
        "Exact expected words are excluded; adjacent words that join into an expected "
        "word are included, as is jimmy → jimi. Other matches use difflib similarity ≥ 0.75.", "",
        "Expected words: " + ", ".join(EXPECTED_WORDS) + ".", "",
    ])
    counts = suspected_words(commands)
    if counts:
        lines.extend(["| Heard | Possible intended word | Count |", "| --- | --- | ---: |"])
        for (heard, expected), count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
            lines.append(f"| {markdown(heard)} | {expected} | {count} |")
    else:
        lines.append("No suspected misheard words found.")
    lines.extend(["", "## 20 latest commands", "", "Newest first, ordered by timestamp filename.", ""])
    for name, transcript in reversed(commands[-20:]):
        lines.append(f"- {markdown(name)}: {markdown(transcript) if transcript else '(empty STT)'}")
    if not commands:
        lines.append("No command transcripts found.")
    if warnings:
        lines.extend(["", "## Input warnings", ""])
        lines.extend(f"- {markdown(warning)}" for warning in warnings)
    output = folder / "report.md"
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return output


def main():
    parser = argparse.ArgumentParser(description="Summarize a folder pulled from Jimi.")
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    try:
        print(summarize(args.folder))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Error: {error}\n")


if __name__ == "__main__":
    main()
