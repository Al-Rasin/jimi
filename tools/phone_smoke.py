#!/usr/bin/env python3
"""Correlated debug-receiver smoke tests; no third-party Python dependencies."""
import argparse
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
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, help="install this debug APK before testing")
    parser.add_argument("--serial", help="select one connected adb device")
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--all", action="store_true", help="include device changes; risky tools still dry-run")
    parser.add_argument("--cases", type=Path, default=Path(__file__).with_name("smoke_cases.txt"))
    parser.add_argument("--timeout", type=float, default=135, help="per-case timeout in seconds")
    parser.add_argument("--list", action="store_true", help="list selected cases without adb")
    options = parser.parse_args()
    if options.timeout <= 0:
        parser.error("--timeout must be positive")
    cases = [json.loads(line) for line in options.cases.read_text().splitlines()
             if line.strip() and not line.lstrip().startswith("#")]
    cases = [case for case in cases if options.all or case["readonly"]]
    if not cases:
        parser.error("no cases selected")
    if options.list:
        for case in cases:
            print(case["name"] + "\t" + case["text"])
        return 0
    os.umask(0o077)
    report_dir = Path(tempfile.mkdtemp(prefix="jarvis-smoke-", dir="/private/tmp"))
    report_path = report_dir / "report.json"
    results = []
    report = {"mode": "all" if options.all else "read-only", "results": results}
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
            case = dict(case, readonly_run=not options.all)
            request_id = f"{run_id}-{index}"
            shell = ["am", "broadcast", "--include-stopped-packages", "-n", COMPONENT,
                     "-a", PACKAGE + ".TEST_COMMAND", "--es", "id", request_id,
                     "--es", "text", case["text"], "--ez", "read_only", str(not options.all).lower()]
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
            results.append({"case": case["name"], "text": case["text"], "pass": not errors,
                            "errors": errors, "smoke": record})
            print(f"{'FAIL' if errors else 'PASS':6} {case['name']:28} {str(record.get('ms', '-')) if record else '-':>8} {'; '.join(errors)}", flush=True)
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
    print(f"{passed}/{len(cases)} passed; report: {report_path}")
    return 0 if not failure and passed == len(cases) else 1


if __name__ == "__main__":
    sys.exit(main())
