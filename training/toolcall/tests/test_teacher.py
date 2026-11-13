import importlib.util
import pathlib
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


t = load("teacher")
ALARM = ["time", "label", "repeat", "date"]


class TeacherTest(unittest.TestCase):
    def test_parse_tools(self):
        tools = t.parse_tools("set_alarm(time, label, repeat, date) SAFE: Set an alarm\n"
                              "flashlight_on() SAFE: Turn on torch\njunk line\n")
        self.assertEqual((ALARM, "Set an alarm"), tools["set_alarm"])
        self.assertEqual(([], "Turn on torch"), tools["flashlight_on"])
        self.assertEqual(2, len(tools))

    def test_parse_json_list(self):
        self.assertEqual([{"a": 1}], t.parse_json_list('```json\n[{"a": 1}]\n```'))
        self.assertEqual([], t.parse_json_list("not json"))
        self.assertEqual([], t.parse_json_list('{"a": 1}'))

    def test_check_tool_rows(self):
        rows = [
            {"text": "Alarm dao 6 tay!", "args": {"time": "6:00", "label": ""}},
            {"text": "alarm dao 6 tay", "args": {"time": "6:00"}},          # dupe
            {"text": "wake me at 7", "args": {"volume": "5"}},              # unknown arg
            {"text": "held out phrase", "args": {}},                        # in eval set
            {"text": "alarm to pay bkash bill", "args": {"time": "9"}},     # money (D-072)
            {"text": "alarm for 01711223344", "args": {"time": "8"}},       # private-looking
            "not a dict",
            {"text": "", "args": {}},
        ]
        holdout = {t.holdout_key("held out phrase")}
        seen = set()
        kept = t.check_tool_rows(rows, "set_alarm", ALARM, holdout, seen)
        self.assertEqual([{"text": "alarm dao 6 tay", "tool": "set_alarm", "args": {"time": "6:00"}}], kept)
        self.assertEqual([], t.check_tool_rows(rows[:1], "set_alarm", ALARM, holdout, seen))  # seen across calls
        self.assertEqual([], t.check_tool_rows({"text": "x"}, "set_alarm", ALARM, holdout, set()))

    def test_check_chat_rows(self):
        rows = [
            {"question": "What is the capital of Bangladesh?", "answer": "Dhaka is the capital of Bangladesh."},
            {"question": "what is the capital of bangladesh", "answer": "Dhaka."},          # dupe
            {"question": "List fruits", "answer": "1. Mango 2. Banana"},                     # list
            {"question": "Bold?", "answer": "**Yes**"},                                     # markdown
            {"question": "Long?", "answer": "word " * 100},                                  # too long
            {"question": "", "answer": "x"},
        ]
        kept = t.check_chat_rows(rows, set())
        self.assertEqual(1, len(kept))
        self.assertEqual("Dhaka is the capital of Bangladesh.", kept[0]["answer"])

    def test_prompts_mention_tool_and_style(self):
        p = t.tool_prompt("set_alarm", ALARM, "Set an alarm", 10)
        self.assertIn("set_alarm(time, label, repeat, date)", p)
        self.assertIn("Bangla", p)
        self.assertIn("10", t.chat_prompt("cooking", 10))


if __name__ == "__main__":
    unittest.main()
