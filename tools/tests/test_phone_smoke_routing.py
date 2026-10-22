import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from test_phone_smoke import smoke, RUNNER


FAKE_ADB = r'''#!/usr/bin/env python3
import json, pathlib, shlex, sys, time
root = pathlib.Path(__file__).parent
args = sys.argv[1:]
if args == ["devices"]:
    print("List of devices attached\nFAKE\tdevice")
elif "logcat" in args:
    seen = 0
    while True:
        lines = (root / "lines").read_text().splitlines() if (root / "lines").exists() else []
        for line in lines[seen:]: print(line, flush=True)
        seen = len(lines)
        time.sleep(.01)
elif "shell" in args:
    parts = shlex.split(args[-1])
    extras = {parts[i+1]: parts[i+2] for i, part in enumerate(parts) if part in ("--es", "--ez")}
    assert extras["dry_run"] == "true" and extras["read_only"] == "true"
    record = json.loads((root / "record").read_text())
    record.update(id=extras["id"], heard=extras["text"])
    with (root / "lines").open("a") as stream: stream.write("SMOKE " + json.dumps(record) + "\n")
'''


class RoutingSmokeTest(unittest.TestCase):
    def case(self, **extra):
        return dict(name="gravity", text="what is gravity", readonly=True, readonly_run=True,
                    routing_run=True, route="chat", tool="chat", args={}, ok=True,
                    dry_run=True, soft=True, reply=r"\S",
                    reply_not=r"^\s*I am not sure[.!]?\s*$", **extra)

    def record(self, **extra):
        record = dict(id="test", heard="what is gravity", route="chat", tool="chat", args={},
                      requested_args={}, ok=True, reply="Gravity attracts masses.", ms=2,
                      dry_run=True, read_only=True, executed=False, expectAnswer=False,
                      device_time="2026-10-22T23:50:00")
        record.update(extra)
        return record

    def run_fake(self, record):
        with tempfile.TemporaryDirectory(dir="/private/tmp") as directory:
            root = Path(directory)
            adb = root / "adb"
            adb.write_text(FAKE_ADB)
            adb.chmod(0o700)
            (root / "record").write_text(json.dumps(record))
            cases = root / "cases"
            cases.write_text(json.dumps(self.case()))
            env = {key: value for key, value in os.environ.items() if not key.startswith("JIMI_SMOKE_")}
            result = subprocess.run([sys.executable, str(RUNNER), "--dry-run", "--adb", str(adb),
                                     "--cases", str(cases), "--timeout", "1"],
                                    capture_output=True, text=True, timeout=5, env=env)
            report = json.loads(Path(result.stdout.split("report: ")[-1].strip()).read_text())
            return result, report

    def test_dry_run_extra_and_soft_failure(self):
        result, report = self.run_fake(self.record(reply="I am not sure."))
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("WARN", result.stdout)
        self.assertTrue(report["results"][0]["soft"])
        self.assertFalse(report["results"][0]["pass"])

    def test_soft_check_never_softens_missing_execution_guard(self):
        result, report = self.run_fake(self.record(executed=True))
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertFalse(report["results"][0]["soft"])

    def test_choice_rejects_confirmation_and_missing_contact(self):
        case = self.case(choice=True, expectAnswer=True)
        for reply in ("Call smokeplaceholder?", "Which contact did you mean?", "Who did you mean?"):
            errors = smoke.routing_checks(case, self.record(reply=reply, expectAnswer=True))
            self.assertTrue(any("multiple-contact choice" in error for error in errors))
        self.assertEqual(smoke.routing_checks(case, self.record(
            reply="Which smokeplaceholder: fixture one or fixture two?", expectAnswer=True)), [])

    def test_relative_alarm_checks_phone_date_across_midnight(self):
        case = self.case(relative_minutes=20)
        self.assertEqual(smoke.routing_checks(case, self.record(args={"time": "00:10", "date": "2026-10-23"})), [])
        self.assertTrue(smoke.routing_checks(case, self.record(args={"time": "00:10"})))

    def test_requested_contact_is_checked_before_resolution(self):
        case = self.case(requested_args={"contact": "smokeplaceholder"}, resolved_contact=True)
        good = self.record(requested_args={"contact": "smokeplaceholder"},
                           args={"contact": "fixture-number", "contact_name": "fixture-contact"})
        self.assertEqual(smoke.routing_checks(case, good), [])
        self.assertTrue(smoke.routing_checks(case, self.record()))

    def test_private_substitution_and_optional_case_selection(self):
        path = RUNNER.with_name("smoke_routing_cases.txt")
        skipped = []
        default = smoke.load_cases(path, dry_run=True, skipped=skipped)
        self.assertIn("ambiguous_contact", skipped)
        self.assertIn("fuzzy_contact", skipped)
        self.assertTrue(any("smokeplaceholder" in case["text"] for case in default))
        private = "fixture ' quote; $(false)"
        expanded = smoke.load_cases(path, dry_run=True, contact=private,
                                    ambiguous_contact="fixture", fuzzy_contact="fixtur")
        call = next(case for case in expanded if case["name"] == "whatsapp_call")
        self.assertEqual(call["text"], "whatsapp call to " + private)
        self.assertEqual(call["requested_args"]["contact"], private)
        self.assertTrue(all("{{" not in json.dumps(case) for case in expanded))


if __name__ == "__main__":
    unittest.main()
