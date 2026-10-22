import sys
import tempfile
import unittest
import wave
from pathlib import Path


WAKEWORD_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(WAKEWORD_DIR))
from retrain import build_dataset, classify_clip, load_label_fixes


class FeedbackDatasetTest(unittest.TestCase):
    def test_known_mistaken_rejections_are_positive(self):
        fixes = load_label_fixes(WAKEWORD_DIR / "label_fixes.json")
        timestamps = {
            43: "20261022_025905",
            44: "20261022_025924",
            47: "20261022_030012",
            48: "20261022_030019",
            50: "20261022_030059",
        }
        for event_id, timestamp in timestamps.items():
            with self.subTest(event_id=event_id):
                self.assertEqual(
                    classify_clip(Path(f"{timestamp}_s99_no.wav"), fixes),
                    "positive",
                )
        self.assertEqual(
            classify_clip(Path("20261023_025905_s99_no.wav"), fixes), "negative"
        )

    def test_verdicts_and_explicit_near_miss_review(self):
        expected = {
            "yes": "positive", "missed": "positive", "no": "negative",
            "near": None, "none": None,
        }
        for verdict, label in expected.items():
            with self.subTest(verdict=verdict):
                path = Path(f"20261022_120000_s40_{verdict}.wav")
                self.assertEqual(classify_clip(path, {}), label)
        near = Path("20261022_120000_s40_near.wav")
        self.assertEqual(classify_clip(near, {near.name: "no"}), "negative")
        self.assertEqual(classify_clip(near, {near.name: "yes"}), "positive")

    def test_build_splits_only_labeled_clips_and_preserves_sources(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            feedback, output = root / "feedback", root / "dataset"
            clips = [
                "20261022_025905_s99_no.wav",
                "20261022_120001_s90_yes.wav",
                "20261022_120002_s20_missed.wav",
                "20261022_120003_s90_no.wav",
                "20261022_120004_s40_near.wav",
                "20261022_120005_s40_near.wav",
                "20261022_120006_s50_none.wav",
            ]
            for name in clips:
                source = feedback / name.rsplit("_", 1)[1][:-4] / name
                source.parent.mkdir(parents=True, exist_ok=True)
                with wave.open(str(source), "wb") as audio:
                    audio.setnchannels(1)
                    audio.setsampwidth(2)
                    audio.setframerate(16000)
                    audio.writeframes(b"\x00\x00" * 160)
            fixes = load_label_fixes(WAKEWORD_DIR / "label_fixes.json")
            fixes[clips[4]] = "no"
            counts = build_dataset(feedback, output, fixes)
            self.assertEqual(counts["positive"], 3)
            self.assertEqual(counts["negative"], 2)
            self.assertEqual(counts["skipped"], 2)
            self.assertEqual(counts["conflicts"], 0)
            self.assertEqual(len(list((output / "pos").glob("*.wav"))), 3)
            self.assertEqual(len(list((output / "neg").glob("*.wav"))), 2)
            self.assertEqual(len(list(feedback.rglob("*.wav"))), len(clips))
            self.assertTrue((output / "manifest.json").is_file())

    def test_stale_verdicts_do_not_duplicate_or_cross_labels(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            feedback = root / "feedback"
            feedback.mkdir()
            for stamp, verdicts in (("120000", ("none", "yes")),
                                    ("120001", ("yes", "no")),
                                    ("025905", ("yes", "no"))):
                for verdict in verdicts:
                    (feedback / f"20261022_{stamp}_s99_{verdict}.wav").write_bytes(b"fixture")
            counts = build_dataset(feedback, root / "dataset",
                                   load_label_fixes(WAKEWORD_DIR / "label_fixes.json"))
            self.assertEqual(counts, dict(positive=2, negative=0, skipped=0, conflicts=1))


if __name__ == "__main__":
    unittest.main()
