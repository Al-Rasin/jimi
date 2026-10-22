import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from tools.summarize_reports import summarize, suspected_words


class SummarizeReportsTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        (self.folder / "commands").mkdir()

    def report(self):
        return summarize(self.folder).read_text(encoding="utf-8")

    def test_groups_and_markdown(self):
        records = [
            {"time": "2026-10-22", "turnId": 1, "tool": "torch", "heard": "flushlight",
             "reply": "a|b\n<script>", "note": "turn it on", "log": ["debug"]},
            {"tool": "volume", "heard": "quiet", "reply": "ok", "note": "lower"},
            {"tool": "torch", "heard": "off"},
            {"tool": None, "heard": "hello"},
        ]
        (self.folder / "wrong_answers.jsonl").write_text(
            "\n".join(json.dumps(record) for record in records), encoding="utf-8")
        report = self.report()
        self.assertIn("### torch (2)", report)
        self.assertIn("### volume (1)", report)
        self.assertIn("Heard: flushlight", report)
        self.assertIn("Reply: a\\|b<br>&lt;script&gt;", report)
        self.assertIn("Note: turn it on", report)
        self.assertNotIn("debug", report)

    def test_empty_stt_and_latest_twenty(self):
        for second in range(25):
            (self.folder / "commands" / f"20261022_1724{second:02}.txt").write_text(
                " \n\t" if second < 2 else f"command {second}", encoding="utf-8")
        report = self.report()
        self.assertIn("Empty STT results: 2 / 25", report)
        latest = report.split("## 20 latest commands")[1]
        self.assertEqual(latest.count("- 20261022"), 20)
        self.assertNotIn("command 4\n", latest)
        self.assertLess(latest.index("command 24"), latest.index("command 5"))

    def test_mishears_counted_without_exact_words(self):
        counts = suspected_words([("a.txt", "Flushlight flushlight flash light volme jimmy turn on off")])
        self.assertEqual(counts[("flushlight", "flashlight")], 2)
        self.assertEqual(counts[("flash light", "flashlight")], 1)
        self.assertEqual(counts[("volme", "volume")], 1)
        self.assertEqual(counts[("jimmy", "jimi")], 1)
        self.assertFalse(any(heard == expected for heard, expected in counts))

    def test_missing_files(self):
        report = self.report()
        self.assertIn("No wrong answers found", report)
        self.assertIn("Empty STT results: 0 / 0", report)
        self.assertIn("No command transcripts found", report)
        with self.assertRaises(ValueError):
            summarize(self.folder / "missing")

    def test_bad_lines_do_not_hide_valid_records(self):
        (self.folder / "wrong_answers.jsonl").write_text(
            '\n{broken\n[]\n{"tool": []}\n{"tool": "torch", "heard": "on"}\n',
            encoding="utf-8")
        report = self.report()
        self.assertIn("### torch (1)", report)
        self.assertIn("## Input warnings", report)
        self.assertIn("line 2", report)
        self.assertIn("line 3", report)
        self.assertIn("line 4", report)

    def test_cli(self):
        script = Path(__file__).resolve().parents[1] / "summarize_reports.py"
        result = subprocess.run([sys.executable, str(script), str(self.folder)],
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(Path(result.stdout.strip()), self.folder / "report.md")


if __name__ == "__main__":
    unittest.main()
