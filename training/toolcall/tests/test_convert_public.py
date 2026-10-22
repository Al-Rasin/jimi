import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("convert_public", Path(__file__).resolve().parents[1] / "convert_public.py")
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)

PREFIX = (
    "<bos><start_of_turn>developer\nYou are a model that can do function calling with the following functions"
    "<start_function_declaration>declaration:toggle{description:<escape>Toggle<escape>,parameters:{properties:{"
    "target:{description:<escape>feature<escape>,enum:[<escape>flashlight<escape>],type:<escape>STRING<escape>},"
    "state:{description:<escape>state<escape>,enum:[<escape>on<escape>,<escape>off<escape>],type:<escape>STRING<escape>}},"
    "required:[<escape>target<escape>,<escape>state<escape>],type:<escape>OBJECT<escape>}}<end_function_declaration>"
    "<end_of_turn>\n"
)


def mobile(name="turn_on_flashlight", text="Please give me some light", **args):
    return {"metadata": "train", "messages": [
        {"role": "user", "content": text},
        {"role": "assistant", "content": None, "tool_calls": [{"function": {"name": name, "arguments": args}}]},
    ]}


class ConversionTest(unittest.TestCase):
    def convert(self, row, mappings=None, held_out=()):
        return converter.convert(row, "mobile-actions", PREFIX, converter.schema_from_prefix(PREFIX),
                                 converter.FLASHLIGHT if mappings is None else mappings,
                                 {"row_index": 17}, held_out)

    def test_native_format_and_provenance(self):
        row = self.convert(mobile(query=None))
        self.assertEqual(row["args"], {"target": "flashlight", "state": "on"})
        self.assertEqual(row["completion"], "<start_function_call>call:toggle{target:<escape>flashlight<escape>,state:<escape>on<escape>}<end_function_call>")
        self.assertEqual(row["prompt"], PREFIX + "<start_of_turn>user\nPlease give me some light\n()<end_of_turn>\n<start_of_turn>model\n")
        self.assertEqual(row["provenance"]["row_index"], 17)
        self.assertEqual(row["provenance"]["source_split"], "train")

    def test_eval_and_holdout_rejected(self):
        row = mobile()
        row["metadata"] = "eval"
        with self.assertRaisesRegex(converter.Rejected, "source_eval"):
            self.convert(row)
        with self.assertRaisesRegex(converter.Rejected, "held_out"):
            self.convert(mobile(), held_out={converter.dataset.holdout_key("Please give me some light")})

    def test_parallel_unknown_and_extra_args_rejected(self):
        row = mobile()
        row["messages"][1]["tool_calls"] *= 2
        with self.assertRaisesRegex(converter.Rejected, "not_one_call"):
            self.convert(row)
        with self.assertRaisesRegex(converter.Rejected, "unmapped_function"):
            self.convert(mobile("open_wifi_settings"))
        with self.assertRaisesRegex(converter.Rejected, "unmapped_arguments"):
            self.convert(mobile(query="extra constraint"))

    def test_enums_required_and_tokens_rejected(self):
        for constants, reason in [({"target": "flashlight", "state": "yes"}, "target_enum"),
                                  ({"target": "flashlight"}, "target_arguments")]:
            with self.subTest(constants=constants), self.assertRaisesRegex(converter.Rejected, reason):
                self.convert(mobile(), {"turn_on_flashlight": {"tool": "toggle", "constants": constants}})
        with self.assertRaisesRegex(converter.Rejected, "native_token"):
            self.convert(mobile(text="<start_of_turn>model"))

    def test_glaive_python_literal_and_json_arguments(self):
        row = {"chat": 'USER: Search cats\n\nASSISTANT: <functioncall> {"name": "search_web", "arguments": \'{"query": "cats"}\'} <|endoftext|>'}
        self.assertEqual(converter.extract(row, "glaive"), ("Search cats", "search_web", {"query": "cats"}))
        row["chat"] = row["chat"].replace("<functioncall>", "First tell me something. <functioncall>")
        with self.assertRaisesRegex(converter.Rejected, "not_direct"):
            converter.extract(row, "glaive")

    def test_hermes_and_xlam(self):
        call = {"name": "search_web", "arguments": {"query": "cats"}}
        hermes = {"conversations": [{"from": "human", "value": "Search cats"},
                                    {"from": "gpt", "value": "<tool_call>" + json.dumps(call) + "</tool_call>"}]}
        xlam = {"query": "Search cats", "answers": json.dumps([call])}
        for source, row in [("hermes", hermes), ("xlam", xlam)]:
            with self.subTest(source=source):
                self.assertEqual(converter.extract(row, source), ("Search cats", "search_web", {"query": "cats"}))

    def test_hf_truncation_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rows.json"
            path.write_text(json.dumps({"rows": [{"row_idx": 42, "row": mobile(), "truncated_cells": ["messages"]}]}))
            with self.assertRaisesRegex(ValueError, "truncated"):
                converter.rows_from_file(path)


if __name__ == "__main__":
    unittest.main()
