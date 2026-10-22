import importlib.util
import pathlib
import random
import unittest

MODULE = pathlib.Path(__file__).resolve().parents[1] / "make_dataset.py"
spec = importlib.util.spec_from_file_location("make_dataset", MODULE)
dataset = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dataset)


class AugmentationTest(unittest.TestCase):
    def test_deterministic_noise_and_command_survival(self):
        def variants():
            rng = random.Random(1)
            return [dataset.augment_tool("turn on flashlight", "toggle", rng) for _ in range(200)]

        samples = variants()
        self.assertEqual(samples, variants())
        for word in ("flushlight", "flash lite", "down on", "um", "please", "can you"):
            self.assertTrue(any(word in text for text in samples), word)
        for prefix in ("hey jimmy", "hi jimmy", "izumi", "hayzumi", "a"):
            self.assertTrue(any(text.startswith(prefix + " ") for text in samples), prefix)
        self.assertIn("on flashlight", samples)
        self.assertTrue(all("on " in text for text in samples))

    def test_payload_and_other_mishears(self):
        for seed in range(50):
            text = dataset.augment_tool("type turn on flashlight off volume", "type_text", random.Random(seed))
            self.assertIn("type turn on flashlight off volume", text)
        self.assertTrue(any("sound up" in dataset.augment_tool("volume up", "set_volume", random.Random(s))
                            for s in range(20)))
        self.assertTrue(any("wifi of" in dataset.augment_tool("turn wifi off", "toggle", random.Random(s))
                            for s in range(20)))

    def test_holdout_wrappers_and_chat_diversity(self):
        self.assertEqual(dataset.holdout_key("Hi Jimmy, um read my messages please!"),
                         dataset.holdout_key("read my messages"))
        self.assertGreaterEqual(len(set(dataset.CHATS)), 300)
        for text in ("do you love me", "what can you do", "tell me a fun fact about the moon",
                     "tell me a joke about cats"):
            self.assertIn(text, dataset.CHATS)


if __name__ == "__main__":
    unittest.main()
