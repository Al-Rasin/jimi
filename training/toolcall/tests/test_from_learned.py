import importlib.util
import json
import pathlib
import random
import sqlite3
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, HERE / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


fl = load("from_learned")
md = load("make_dataset")

APPS = [{"pkg": "com.whatsapp", "label": "WhatsApp"}, {"pkg": "com.x", "label": "X"}]
SCREENS = [{"id": 1, "pkg": "com.whatsapp", "name": "Chats"}, {"id": 2, "pkg": "com.whatsapp", "name": "Settings"}]
ELEMENTS = [
    {"screen": 1, "role": "tab", "label": "Calls", "risk": "low"},
    {"screen": 1, "role": "tab", "label": "Updates", "risk": "low"},
    {"screen": 1, "role": "button", "label": "Delete chat", "risk": "high"},
    {"screen": 1, "role": "list", "label": "Rahim Uddin", "risk": "low"},
    {"screen": 2, "role": "menu", "label": "+880 1711 223344", "risk": "low"},
    {"screen": 2, "role": "field", "label": "Search", "risk": "low"},
]


class FromLearnedTest(unittest.TestCase):
    def rows(self, **extra):
        src = {"app_info": APPS, "app_screen": SCREENS, "app_element": ELEMENTS, **extra}
        return fl.build([src])

    def test_app_go_targets_and_privacy(self):
        rows = [r for r in self.rows() if r["tool"] == "app_go"]
        targets = {r["args"]["target"] for r in rows}
        self.assertEqual(targets, {"chats", "settings", "calls", "updates"})
        self.assertTrue(all(r["args"]["app"] == "whatsapp" for r in rows))
        self.assertTrue(all("whatsapp" in r["text"] and r["args"]["target"] in r["text"] for r in rows))
        text = " ".join(r["text"] for r in rows)
        for bad in ("delete", "rahim", "880", "search"):
            self.assertNotIn(bad, text)

    def test_recipes(self):
        recipes = [{"pkg": "com.whatsapp", "name": "status post", "say": "post {text} as status\nwhatsapp status",
                    "risky": 1},
                   {"pkg": "com.x", "name": "a@b.com", "say": "mail it", "risky": 0}]
        rows = [r for r in self.rows(recipes=recipes) if r["tool"] == "run_recipe"]
        self.assertTrue(rows)
        self.assertTrue(all(r["args"] == {"name": "status post", "app": "whatsapp", "risky": "true"} for r in rows))
        self.assertTrue(all("{" not in r["text"] for r in rows))
        self.assertTrue(any(r["text"].endswith("whatsapp status") for r in rows))

    def test_fixes(self):
        turns = [{"heard": "turn on flesh light", "textOk": 0, "correction": "turn on flashlight"},
                 {"heard": "call rahim 01711223344", "textOk": 0, "correction": "call rahim"},
                 {"heard": "fine", "textOk": 1, "correction": "turn on flashlight"}]
        calls = [{"heard": "turn on flashlight", "tool": "toggle", "argsJson": '{"setting":"flashlight","on":true}',
                  "source": "fix", "ok": 1},
                 {"heard": "call rahim", "tool": "call", "argsJson": '{"contact":"rahim"}', "source": "fix", "ok": 0}]
        rows = [r for r in self.rows(turns=turns, calls=calls) if r["tool"] not in ("app_go", "run_recipe")]
        self.assertEqual(rows, [{"text": "turn on flesh light", "tool": "toggle",
                                 "args": {"setting": "flashlight", "on": "True"}}])

    def test_read_db(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "jarvis.db"
            con = sqlite3.connect(path)
            con.execute("CREATE TABLE app_info (pkg TEXT, label TEXT)")
            con.execute("CREATE TABLE app_screen (id INTEGER, pkg TEXT, name TEXT)")
            con.execute("INSERT INTO app_info VALUES ('com.whatsapp', 'WhatsApp')")
            con.execute("INSERT INTO app_screen VALUES (1, 'com.whatsapp', 'Chats')")
            con.commit()
            con.close()
            src = fl.read_db(path)
            self.assertEqual(src["recipes"], [])
            rows = fl.build([src])
            self.assertTrue(rows and all(r["args"] == {"app": "whatsapp", "target": "chats"} for r in rows))

    def test_make_dataset_filters_unknown(self):
        with tempfile.TemporaryDirectory() as d:
            path = pathlib.Path(d) / "learned.jsonl"
            path.write_text("\n".join(json.dumps(r) for r in [
                {"text": "open calls in whatsapp", "tool": "app_go", "args": {"app": "whatsapp", "target": "calls"}},
                {"text": "x", "tool": "nope", "args": {}},
                {"text": "y", "tool": "app_go", "args": {"bad": "1"}},
            ]))
            got = list(md.load_learned(path, {"app_go": ["app", "target"]}))
            self.assertEqual(got[0]["tool"], "app_go")
            self.assertEqual(got[1:], [None, None])


if __name__ == "__main__":
    unittest.main()
