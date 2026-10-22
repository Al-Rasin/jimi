import json
from contextlib import closing
import math
from pathlib import Path
import sqlite3
import tempfile
import unittest

from training.notifications.features import features, label
from training.notifications.synthetic import generate
from training.notifications.train import evaluate, predict, read_rows, sigmoid, split_rows, train


class FeatureTests(unittest.TestCase):
    def test_shared_vectors(self):
        path = Path(__file__).resolve().parents[3] / "app/src/test/resources/notification_feature_vectors.json"
        fixture = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(fixture["feature_version"], 1)
        for vector in fixture["vectors"]:
            with self.subTest(vector["name"]):
                self.assertEqual(features(vector["input"]), vector["features"])
                self.assertAlmostEqual(predict(fixture["model"], vector["input"]), vector["probability"], places=12)

    def test_feedback_overrides_status(self):
        self.assertEqual(label({"label": "important", "status": "dismissed"}), 1)
        self.assertEqual(label({"label": "noise", "status": "kept"}), 0)
        self.assertEqual(label({"label": "important", "status": "cleared"}), 1)

    def test_weak_and_automatic_labels(self):
        self.assertIsNone(label({"status": "kept"}))
        self.assertEqual(label({"status": "kept"}, include_kept=True), 1)
        self.assertEqual(label({"status": "dismissed"}), 0)
        self.assertIsNone(label({"status": "cleared"}, include_kept=True))
        self.assertIsNone(label({"label": "unexpected", "status": "dismissed"}))

    def test_flags_default_to_absent_and_require_booleans(self):
        self.assertNotIn("is_conversation", features({"isConversation": "false"}))
        self.assertEqual(features({}), ["hour:0", "pkg:"])


class TrainingTests(unittest.TestCase):
    def test_learns_and_exports_portable_model(self):
        training, evaluation = split_rows(list(generate()))
        self.assertFalse({r["id"] for r in training} & {r["id"] for r in evaluation})
        model = train(training)
        self.assertEqual(evaluate(model, evaluation)["accuracy"], 1.0)
        self.assertLessEqual(len(model["weights"]), 4096)
        self.assertEqual(json.loads(json.dumps(model, allow_nan=False)), model)
        self.assertEqual(model, train(training))

    def test_missing_classes_and_small_evaluation_rejected(self):
        with self.assertRaises(ValueError):
            train([{"label": "important"}])
        with self.assertRaises(ValueError):
            split_rows([{"label": "important"}, {"label": "noise"}])

    def test_stable_extreme_sigmoid_and_unknown_features(self):
        self.assertEqual(sigmoid(-10000), 0)
        self.assertEqual(sigmoid(10000), 1)
        self.assertEqual(predict({"bias": 0, "weights": {}}, {"text": "unseen"}), 0.5)

    def test_training_parameters(self):
        for options in [{"epochs": 0}, {"max_features": 0}, {"learning_rate": math.nan}, {"l2": -1}]:
            with self.subTest(options), self.assertRaises(ValueError):
                train(list(generate(4)), **options)

    def test_jsonl_and_sqlite_inputs(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            rows = list(generate(4))
            path = root / "sample.jsonl"
            path.write_text("\n".join(json.dumps(row) for row in rows))
            self.assertEqual(read_rows(path), rows)
            path = root / "sample.db"
            with closing(sqlite3.connect(path)) as db:
                db.execute("CREATE TABLE notification_log (id INTEGER, timeMs INTEGER, pkg TEXT, label TEXT)")
                db.execute("INSERT INTO notification_log VALUES (1, 0, 'example', 'important')")
                db.commit()
            self.assertEqual(read_rows(path), [{"id": 1, "timeMs": 0, "pkg": "example", "label": "important"}])


if __name__ == "__main__":
    unittest.main()
