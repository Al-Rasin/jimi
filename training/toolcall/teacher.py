"""Gemini as teacher (ROADMAP #25, B §A.2): write many varied training rows for the small offline models.

  --mode tools  commands → tool calls, rows {"text", "tool", "args"} → training/data/toolcall/teacher.jsonl
                (add with make_dataset.py --learned training/data/toolcall/teacher.jsonl)
  --mode chat   questions → short spoken answers, rows {"question", "answer"} → training/data/chat/teacher_chat.jsonl

Only the tool list and made-up examples go to Gemini; no phone data, notes, contacts or logs.
Key: env GEMINI_API_KEY (never written to disk by this script). Needs internet; run on the Mac.
Rows are checked before saving: known tool, known args, no eval phrase (cases*.jsonl), no private-looking text, no dupes.
"""
import argparse
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
import urllib.request

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(ROOT / "training" / "screen"))
sys.path.insert(0, str(HERE))
from privacy import looks_private  # noqa: E402
from make_dataset import holdout_key  # noqa: E402

TOOLS_TXT = ROOT / "app" / "build" / "llm" / "tools.txt"
OUT_TOOLS = ROOT / "training" / "data" / "toolcall" / "teacher.jsonl"
OUT_CHAT = ROOT / "training" / "data" / "chat" / "teacher_chat.jsonl"
API = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
# D-072: no money features, so no money examples either.
MONEY = re.compile(r"\b(bkash|nagad|rocket|bank|pay|payment|send money|cash ?out|recharge|uber|pathao)\b", re.I)

STYLE = """Speakers are from Bangladesh: Bangla-accented English, sometimes mixed with Bangla words written in English
letters (e.g. "ammu ke call koro", "alarm dao 6 tay"). Some lines must look like speech-to-text mistakes
("flash lite", "what's up" for WhatsApp, missing words). Use made-up common names (Rahim, Karim, Nusrat, Ammu, Abbu,
Bhaiya), never real phone numbers or emails. Short, spoken commands, 2-12 words, all lowercase, no punctuation."""


def parse_tools(text):
    """tools.txt lines 'name(a, b) RISK: description' → {name: (args, description)}."""
    tools = {}
    for line in text.splitlines():
        m = re.match(r"(\w+)\(([^)]*)\)\s+\w+:\s*(.*)", line.strip())
        if m:
            args = [a.strip() for a in m.group(2).split(",") if a.strip()]
            tools[m.group(1)] = (args, m.group(3))
    return tools


def tool_prompt(name, args, description, n):
    return f"""You write training data for a phone voice assistant's tool picker.
Tool: {name}({", ".join(args)}) — {description}
Write {n} different things a user might say that should run this tool, with the arguments taken from what they said.
Leave out arguments they did not say. Argument values are short strings in English.
{STYLE}
Answer only a JSON list: [{{"text": "...", "args": {{...}}}}, ...]"""


def chat_prompt(topic, n):
    return f"""You write training data for an offline phone assistant named Jimi that answers out loud.
Topic: {topic}
Write {n} different questions a user might ask, each with Jimi's answer: correct, friendly, 1-2 short sentences,
easy to say aloud (no lists, no markdown, no emoji). If the answer needs live data (news, prices, weather now),
the answer says it needs internet. {STYLE}
Answer only a JSON list: [{{"question": "...", "answer": "..."}}, ...]"""


CHAT_TOPICS = ["general knowledge", "science for everyday life", "Bangladesh history and places", "cooking and food",
               "health basics (no diagnosis, suggest a doctor when serious)", "maths and unit conversion",
               "word meanings and spelling", "small talk and jokes", "about Jimi itself (offline assistant)",
               "study and work tips", "technology and phones", "geography of the world"]


def clean_text(text):
    return " ".join(re.sub(r"[^\w\s']", " ", str(text).lower()).split())


def check_tool_rows(rows, name, args, holdout, seen):
    """Keep good rows only: dict with text + args subset of the tool's args, string values, not held out, not private."""
    kept = []
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict):
            continue
        text = clean_text(row.get("text", ""))
        got = row.get("args") or {}
        if not text or not isinstance(got, dict) or set(got) - set(args):
            continue
        got = {k: str(v).strip() for k, v in got.items() if str(v).strip()}
        blob = " ".join([text, *got.values()])
        key = holdout_key(text)
        if key in holdout or key in seen or looks_private(blob) or MONEY.search(blob):
            continue
        seen.add(key)
        kept.append({"text": text, "tool": name, "args": got})
    return kept


def check_chat_rows(rows, seen):
    kept = []
    for row in rows if isinstance(rows, list) else []:
        if not isinstance(row, dict):
            continue
        q = " ".join(str(row.get("question", "")).split())
        a = " ".join(str(row.get("answer", "")).split())
        key = clean_text(q)
        if not q or not a or len(a) > 300 or key in seen or looks_private(q + " " + a) or MONEY.search(q + " " + a):
            continue
        if re.search(r"[*#`]|^\s*[-•]|\d\.\s", a):   # spoken answers: no markdown or lists
            continue
        seen.add(key)
        kept.append({"question": q, "answer": a})
    return kept


def parse_json_list(text):
    """Model reply → list; tolerates a ```json fence."""
    text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip())
    try:
        out = json.loads(text)
    except json.JSONDecodeError:
        return []
    return out if isinstance(out, list) else []


def ask_gemini(prompt, key, model, retries=4):
    body = json.dumps({
        "contents": [{"parts": [{"text": prompt}]}],
        "generationConfig": {"responseMimeType": "application/json", "temperature": 1.0},
    }).encode()
    req = urllib.request.Request(API.format(model=model), body,
                                 {"Content-Type": "application/json", "x-goog-api-key": key})
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=120) as r:
                data = json.load(r)
            return data["candidates"][0]["content"]["parts"][0]["text"]
        except urllib.error.HTTPError as e:
            if e.code not in (429, 500, 503) or attempt == retries - 1:
                raise
            time.sleep(20 * (attempt + 1))   # free tier rate limit
        except (KeyError, IndexError):
            return ""
    return ""


def load_holdout():
    keys = set()
    for path in HERE.glob("cases*.jsonl"):
        for line in path.read_text().splitlines():
            if line.strip():
                keys.add(holdout_key(json.loads(line)["text"]))
    return keys


def existing(path, field):
    if not path.is_file():
        return [], set()
    rows = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    return rows, {holdout_key(r[field]) if field == "text" else clean_text(r[field]) for r in rows}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["tools", "chat"], default="tools")
    ap.add_argument("--rounds", type=int, default=3, help="Gemini calls per tool (or topic)")
    ap.add_argument("--per-call", type=int, default=40)
    ap.add_argument("--model", default="gemini-2.5-flash")
    ap.add_argument("--only", nargs="*", help="only these tools / topics")
    ap.add_argument("--tools", type=pathlib.Path, default=TOOLS_TXT)
    ap.add_argument("--dry-run", action="store_true", help="print the first prompt, call nothing")
    a = ap.parse_args()

    if a.mode == "tools":
        if not a.tools.is_file():
            sys.exit(f"{a.tools} missing: run ./gradlew testDebugUnitTest first")
        tools = parse_tools(a.tools.read_text())
        jobs = [(n, *tools[n]) for n in (a.only or tools) if n in tools]
        out, field = OUT_TOOLS, "text"
    else:
        jobs = [(t,) for t in (a.only or CHAT_TOPICS)]
        out, field = OUT_CHAT, "question"
    if a.dry_run:
        print(tool_prompt(*jobs[0], a.per_call) if a.mode == "tools" else chat_prompt(jobs[0][0], a.per_call))
        return
    key = os.environ.get("GEMINI_API_KEY") or sys.exit("set GEMINI_API_KEY")

    rows, seen = existing(out, field)
    total = len(rows)
    holdout = load_holdout()
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("a") as f:
        for job in jobs:
            made = 0
            for _ in range(a.rounds):
                prompt = tool_prompt(*job, a.per_call) if a.mode == "tools" else chat_prompt(job[0], a.per_call)
                reply = parse_json_list(ask_gemini(prompt, key, a.model))
                good = check_tool_rows(reply, job[0], job[1], holdout, seen) if a.mode == "tools" \
                    else check_chat_rows(reply, seen)
                for row in good:
                    f.write(json.dumps(row, ensure_ascii=False) + "\n")
                f.flush()
                made += len(good)
            print(f"{job[0]:28} +{made}")
            total += made
    print(f"{out.relative_to(ROOT)}: {total} rows")


if __name__ == "__main__":
    main()
