#!/usr/bin/env python3
"""Correlated debug-receiver smoke tests; no third-party Python dependencies."""
import argparse
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import queue
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
import uuid

PACKAGE = "com.alrasin.jarvis"
COMPONENT = PACKAGE + "/.debug.SmokeCommandReceiver"


def command(argv, timeout=30):
    result = subprocess.run(argv, text=True, capture_output=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip() or "command failed")
    return result.stdout


def discover(adb, serial):
    def devices():
        return [line.split()[0] for line in command([adb, "devices"]).splitlines()
                if len(line.split()) >= 2 and line.split()[1] == "device"]
    available = devices()
    if not available:
        services = command([adb, "mdns", "services"])
        for line in services.splitlines():
            if "_adb-tls-connect._tcp" in line:
                address = line.split()[-1]
                command([adb, "connect", address], timeout=10)
        available = devices()
    if serial:
        if serial not in available:
            raise RuntimeError("Requested serial is not connected: " + serial)
        return serial
    if len(available) != 1:
        raise RuntimeError("No connected device; pair/connect adb first." if not available else
                           "Multiple devices; select one with --serial.")
    return available[0]


def load_cases(path, dry_run=False, all_cases=False, contact=None, ambiguous_contact=None, fuzzy_contact=None, skipped=None):
    cases = [json.loads(line) for line in path.read_text().splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    selected = []
    for case in cases:
        if case.get("requires_fuzzy_contact") and not fuzzy_contact:
            if skipped is not None:
                skipped.append(case["name"])
            continue
        if case.get("requires_ambiguous_contact") and not ambiguous_contact:
            if skipped is not None:
                skipped.append(case["name"])
            continue
        if not (dry_run or all_cases or case["readonly"]):
            continue
        def substitute(value):
            if isinstance(value, str):
                return value.replace("{{contact}}", contact or "smokeplaceholder").replace(
                    "{{ambiguous_contact}}", ambiguous_contact or "smokeplaceholder").replace(
                    "{{fuzzy_contact}}", fuzzy_contact or "smokeplaceholder")
            if isinstance(value, dict):
                return {key: substitute(item) for key, item in value.items()}
            if isinstance(value, list):
                return [substitute(item) for item in value]
            return value
        selected.append(substitute(case))
    return selected


def private_name(path, env_name):
    value = path.read_text().strip() if path else os.environ.get(env_name, "").strip()
    if value and (len(value) > 100 or "\n" in value or "\r" in value):
        raise ValueError("Contact input must be one line of at most 100 characters")
    return value.lower() or None


def routing_checks(case, record):
    errors = []
    args = record.get("args")
    args = args if isinstance(args, dict) else {}
    if record.get("dry_run") is not True or record.get("executed") is not False:
        errors.append("dry-run execution guard missing or unsafe")
    if "expectAnswer" not in record or not isinstance(record["expectAnswer"], bool):
        errors.append("missing boolean expectAnswer")
    elif "expectAnswer" in case and record["expectAnswer"] is not case["expectAnswer"]:
        errors.append("expectAnswer mismatch")
    if case.get("choice") and not re.search(r"^Which .+: .+\?", str(record.get("reply", "")), re.I):
        errors.append("expected multiple-contact choice, not confirmation or missing-contact prompt")
    if case.get("reply_not") and re.search(case["reply_not"], str(record.get("reply", "")), re.I):
        errors.append("uninformative reply")
    requested = record.get("requested_args")
    if not isinstance(requested, dict):
        errors.append("missing requested_args object")
    else:
        for key, value in case.get("requested_args", {}).items():
            if requested.get(key) != value:
                errors.append("requested argument mismatch: " + key)
    if case.get("resolved_contact") and not str(args.get("contact_name", "")).strip():
        errors.append("fuzzy contact did not resolve a named contact")
    if case.get("contact") and not str(args.get("contact", "")).strip():
        errors.append("contact argument missing")
    if "relative_minutes" in case:
        try:
            started = datetime.fromisoformat(record["device_time"])
            target = started + timedelta(minutes=case["relative_minutes"])
            actual = datetime.fromisoformat(args.get("date", started.date().isoformat()) + "T" + args["time"])
            if abs((actual - target).total_seconds()) > 90:
                errors.append("relative alarm time differs from requested delay")
        except (KeyError, TypeError, ValueError):
            errors.append("relative alarm requires device_time and valid time/date args")
    return errors


def validate(case, record, previous):
    errors = []
    required = {"id", "heard", "route", "tool", "args", "ok", "reply", "ms", "dry_run", "read_only"}
    if not required.issubset(record):
        return ["missing fields: " + ", ".join(sorted(required - record.keys()))]
    if record["heard"] != case["text"]:
        errors.append("heard mismatch")
    for key in ("route", "tool", "ok"):
        wanted = case[key]
        if record[key] not in (wanted if isinstance(wanted, list) else [wanted]):
            errors.append(f"{key}: {record[key]!r} != {wanted!r}")
    if not isinstance(record["args"], dict):
        errors.append("args is not an object")
    else:
        for key, value in case.get("args", {}).items():
            if record["args"].get(key) != value:
                errors.append("argument mismatch: " + key)
    if not isinstance(record["reply"], str) or not re.search(case.get("reply", r"\S"), record["reply"], re.I):
        errors.append("reply expectation failed")
    if case.get("repeat") and (not previous or record["reply"] != previous.get("reply")):
        errors.append("follow-up did not repeat previous reply")
    if not isinstance(record["ms"], (int, float)) or isinstance(record["ms"], bool) or record["ms"] < 0:
        errors.append("invalid elapsed time")
    if record["dry_run"] is not case.get("dry_run", False):
        errors.append("unexpected action simulation status")
    if record["read_only"] is not case["readonly_run"]:
        errors.append("read_only mode mismatch")
    if case.get("routing_run"):
        errors.extend(routing_checks(case, record))
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="install this debug APK before testing")
    parser.add_argument("--serial", help="select one connected adb device")
    parser.add_argument("--adb", default="adb", help="adb executable")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--all", action="store_true", help="include device changes; risky tools still dry-run")
    mode.add_argument("--dry-run", action="store_true", help="routing goals only; never execute tools")
    parser.add_argument("--contact-file", type=Path, help="private one-line contact name (or JIMI_SMOKE_CONTACT)")
    parser.add_argument("--fuzzy-contact-file", type=Path, help="private typo query expected to resolve (or JIMI_SMOKE_FUZZY_CONTACT)")
    parser.add_argument("--ambiguous-contact-file", type=Path, help="private ambiguous query (or JIMI_SMOKE_AMBIGUOUS_CONTACT)")
    parser.add_argument("--cases", type=Path, default=None)
    parser.add_argument("--timeout", type=float, default=135, help="per-case timeout in seconds")
    parser.add_argument("--list", action="store_true", help="list selected cases without adb")
    options = parser.parse_args()
    if options.timeout <= 0:
        parser.error("--timeout must be positive")
    case_path = options.cases or Path(__file__).with_name(
        "smoke_routing_cases.txt" if options.dry_run else "smoke_cases.txt")
    skipped = []
    try:
        cases = load_cases(case_path, options.dry_run, options.all,
                           private_name(options.contact_file, "JIMI_SMOKE_CONTACT"),
                           private_name(options.ambiguous_contact_file, "JIMI_SMOKE_AMBIGUOUS_CONTACT"),
                           private_name(options.fuzzy_contact_file, "JIMI_SMOKE_FUZZY_CONTACT"), skipped)
    except (OSError, ValueError) as exc:
        parser.error(str(exc))
    if not cases:
        parser.error("no cases selected")
    for name in skipped:
        print(f"SKIP   {name}: optional private contact query not supplied")
    if options.list:
        for case in cases:
            print(case["name"] + "\t" + case["text"])
        return 0
    os.umask(0o077)
    report_dir = Path(tempfile.mkdtemp(prefix="jarvis-smoke-", dir="/private/tmp"))
    report_path = report_dir / "report.json"
    results = []
    report = {"mode": "dry-run" if options.dry_run else "all" if options.all else "read-only", "results": results, "skipped": skipped}
    logs = None
    failure = None
    try:
        serial = discover(options.adb, options.serial)
        adb = [options.adb, "-s", serial]
        if options.apk:
            command(adb + ["install", "-r", str(options.apk.resolve())], timeout=180)
        events = queue.Queue()
        logs = subprocess.Popen(adb + ["logcat", "-v", "raw", "Jarvis:I", "*:S"],
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)

        def read_logs():
            for line in logs.stdout:
                marker = line.find("SMOKE ")
                if marker >= 0:
                    try:
                        record = json.loads(line[marker + 6:])
                        if isinstance(record, dict) and str(record.get("id", "")).startswith(run_id):
                            events.put(record)
                    except (ValueError, TypeError):
                        pass

        run_id = uuid.uuid4().hex
        threading.Thread(target=read_logs, daemon=True).start()
        print(f"{'RESULT':6} {'CASE':28} {'MS':>8} DETAIL")
        previous = None
        for index, case in enumerate(cases):
            case = dict(case, readonly_run=not options.all, routing_run=options.dry_run)
            if options.dry_run:
                case["dry_run"] = True
            request_id = f"{run_id}-{index}"
            shell = ["am", "broadcast", "--include-stopped-packages", "-n", COMPONENT,
                     "-a", PACKAGE + ".TEST_COMMAND", "--es", "id", request_id,
                     "--es", "text", case["text"], "--ez", "read_only", str(not options.all).lower()]
            if options.dry_run:
                shell += ["--ez", "dry_run", "true"]
            record = None
            errors = []
            try:
                command(adb + ["shell", shlex.join(shell)], timeout=min(options.timeout, 30))
                deadline = time.monotonic() + options.timeout
                while time.monotonic() < deadline:
                    try:
                        candidate = events.get(timeout=min(1, max(0.001, deadline - time.monotonic())))
                    except queue.Empty:
                        if logs.poll() is not None:
                            raise RuntimeError("logcat exited; device disconnected?")
                        continue
                    if candidate.get("id") == request_id:
                        record = candidate
                        break
                if record is None:
                    raise TimeoutError("no matching SMOKE line; install debug APK with smoke receiver")
                errors = validate(case, record, previous)
            except (RuntimeError, subprocess.TimeoutExpired, TimeoutError) as exc:
                errors = [str(exc)]
            soft = bool(errors and record is not None and case.get("soft") and
                        all(error.startswith(("route:", "tool:", "ok:", "reply expectation", "uninformative reply"))
                            for error in errors))
            note = ""
            if options.dry_run and case.get("contact") and record and not errors:
                note = ("routing only; contact not found" if not record.get("ok") else
                        "contact choice offered" if str(record.get("reply", "")).startswith("Which ") else
                        "contact resolved; execution blocked")
            results.append({"case": case["name"], "text": case["text"], "pass": not errors,
                            "soft": soft, "note": note, "errors": errors, "smoke": record})
            print(f"{'WARN' if soft else 'FAIL' if errors else 'PASS':6} {case['name']:28} {str(record.get('ms', '-')) if record else '-':>8} {'; '.join(errors) or note}", flush=True)
            previous = record
            if record is None:
                failure = "Aborted after transport failure/timeout; later commands could overlap a pending turn."
                break
    except (OSError, RuntimeError, subprocess.TimeoutExpired, KeyboardInterrupt) as exc:
        failure = str(exc) or "Interrupted"
    finally:
        if logs is not None:
            logs.terminate()
            try:
                logs.wait(timeout=3)
            except subprocess.TimeoutExpired:
                logs.kill()
                logs.wait()
        report["error"] = failure
        report["selected"] = len(cases)
        report_path.write_text(json.dumps(report, indent=2) + "\n")
    if failure:
        print(failure, file=sys.stderr)
    passed = sum(result["pass"] for result in results)
    warnings = sum(result["soft"] for result in results)
    if warnings:
        print(f"{warnings} soft check(s) need review")
    print(f"{passed}/{len(cases)} passed; report: {report_path}")
    return 0 if not failure and passed + warnings == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
