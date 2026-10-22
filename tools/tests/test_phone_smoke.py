import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

RUNNER = Path(__file__).resolve().parents[1] / "phone_smoke.py"
spec = importlib.util.spec_from_file_location("phone_smoke", RUNNER)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

FAKE_ADB = r'''#!/usr/bin/env python3
import json, pathlib, shlex, sys, time
args = sys.argv[1:]
root = pathlib.Path(__file__).parent
if args == ["devices"]:
    print("List of devices attached\nFAKE:1234\tdevice")
elif "logcat" in args:
    seen = 0
    while True:
        path = root / "lines"
        lines = path.read_text().splitlines() if path.exists() else []
        for line in lines[seen:]:
            print(line, flush=True)
        seen = len(lines)
        time.sleep(.01)
elif "shell" in args:
    parts = shlex.split(args[-1])
    assert parts[parts.index("-n") + 1] == "com.alrasin.jarvis/.debug.SmokeCommandReceiver"
    extras = {}
    for index, part in enumerate(parts):
        if part in ("--es", "--ez"):
            extras[parts[index + 1]] = parts[index + 2]
    if extras["text"] != "timeout":
        record = dict(id=extras["id"], heard=extras["text"], route="rule", tool="device_status",
                      args={"what":"time"}, ok=True, reply="It's 10:30 AM.", ms=4,
                      dry_run=False, read_only=extras["read_only"] == "true")
        with (root / "lines").open("a") as stream:
            stale = dict(record, id="stale-unrelated-id", reply="wrong")
            stream.write("SMOKE " + json.dumps(stale) + "\n")
            stream.write("SMOKE " + json.dumps(record) + "\n")
'''


class PhoneSmokeTest(unittest.TestCase):
    def case(self, text):
        return dict(name="test", text=text, readonly=True, route="rule", tool="device_status",
                    args={"what": "time"}, ok=True, reply=r"\d+:\d+")

    def run_fake(self, text):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o700)
            cases = root / "cases.txt"
            cases.write_text(json.dumps(self.case(text)) + "\n")
            result = subprocess.run([sys.executable, str(RUNNER), "--adb", str(adb),
                                     "--cases", str(cases), "--timeout", "0.5"],
                                    capture_output=True, text=True, timeout=5)
            report_path = Path(result.stdout.split("report: ")[-1].strip())
            return result, json.loads(report_path.read_text()), report_path.stat().st_mode & 0o777

    def test_shell_quoting_and_stale_id(self):
        result, report, mode = self.run_fake("what's the time; $(false) `false` \"hello\"")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(report["results"][0]["smoke"]["heard"], report["results"][0]["text"])
        self.assertEqual(mode, 0o600)

    def test_timeout_fails_and_writes_report(self):
        result, report, _ = self.run_fake("timeout")
        self.assertEqual(result.returncode, 1)
        self.assertIn("timeout", report["error"])
        self.assertFalse(report["results"][0]["pass"])

    def test_wrong_route_tool_and_args_fail_even_when_ok(self):
        case = dict(self.case("time"), readonly_run=True)
        record = dict(id="id", heard="time", route="llm", tool="call", args={"what": "battery"},
                      ok=True, reply="10:30", ms=1, dry_run=False, read_only=True)
        errors = smoke.validate(case, record, None)
        self.assertTrue(any("route" in error for error in errors))
        self.assertTrue(any("tool" in error for error in errors))
        self.assertTrue(any("argument" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
