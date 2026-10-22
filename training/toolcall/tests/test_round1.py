import copy
import hashlib
import json
from pathlib import Path
import random
import re
import sys
import tempfile
import unittest


DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DIRECTORY))
import r1_contract as contract
import convert_public as converter
import make_dataset as dataset
import make_round1 as round1


class ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prefix, cls.schemas = contract.load_contract()

    def test_kotlin_declarations_round_trip_through_public_parser(self):
        self.assertEqual(converter.schema_from_prefix(self.prefix), self.schemas)
        self.assertEqual(len(self.schemas), 43)
        self.assertEqual(self.schemas['call'][:2], (['contact', 'app'], ['contact']))
        self.assertNotIn('chat', self.schemas)
        self.assertNotIn('ask_user', self.schemas)
        self.assertNotIn('search_contacts', self.schemas)

    def test_required_unknown_and_nonstring_arguments_rejected(self):
        invalid = [
            ('imaginary_tool', {}), ('call', {}),
            ('call', {'contact': 'mom', 'number': '0123456789'}),
            ('call', {'contact': 123}), ('call', {'contact': ''}),
            ('call', {'contact': '   '}), ('chat', {'contact': 'mom'}),
            ('call', {'contact': '<escape>mom'}),
            ('call', {'contact': 'mom\x00'}),
        ]
        for tool, args in invalid:
            with self.subTest(tool=tool, args=args), self.assertRaises(ValueError):
                contract.validate_call(tool, args, self.schemas)

    def test_enum_choices_and_optional_args_follow_registry(self):
        contract.validate_call('call', {'contact': 'mom'}, self.schemas)
        contract.validate_call('chat', {}, self.schemas)
        contract.validate_call('toggle', {'target': 'flashlight', 'state': 'off'}, self.schemas)
        for args in ({'target': 'flashlight', 'state': 'yes'},
                     {'target': 'coffee_machine', 'state': 'on'}):
            with self.subTest(args=args), self.assertRaises(ValueError):
                contract.validate_call('toggle', args, self.schemas)

    def test_native_serialization_preserves_literal_payload_and_arg_order(self):
        args = {'text': 'Meet "Jo", bring {the book} and don\'t wait.', 'contact': 'Anne-Marie'}
        contract.validate_call('send_sms', args, self.schemas)
        completion = dataset.target('send_sms', args, self.schemas['send_sms'][0])
        self.assertTrue(completion.startswith('<start_function_call>call:send_sms{contact:'))
        # Mirror FunctionGemma.parse, including the terminator after the closing brace.
        match = re.search(r'<start_function_call>call:([a-z_]+)\{(.*?)\}(?:<end_function_call>|$)', completion, re.S)
        parsed = dict(re.findall(r'([a-z_]+):<escape>(.*?)<escape>', match[2], re.S))
        self.assertEqual(parsed, args)


class CorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.prefix, cls.schemas = contract.load_contract()

    def row(self, text='phone Nadia Farouk', split='train', family=None):
        args = {'contact': 'Nadia Farouk'}
        family = family or round1.family_key(text, 'call', args)
        context = 'time 2026-10-22 10:00:00'
        return {'text': text, 'tool': 'call', 'args': args, 'family': family,
                'slice': 'exact', 'split': split, 'context': context,
                'prompt': self.prefix + f'<start_of_turn>user\n{text}\n({context})<end_of_turn>\n<start_of_turn>model\n',
                'completion': dataset.target('call', args, self.schemas['call'][0]),
                'provenance': {'source': 'local_synthetic'},
                'review': {'status': 'automated_contract_and_template_review'},
                'call_only_training': True}

    def test_payload_family_and_split_are_independent_of_paraphrase(self):
        args = {'contact': 'Nadia Farouk', 'app': 'phone'}
        one = round1.family_key('phone Nadia Farouk', 'call', args)
        two = round1.family_key('hayzumi please call Nadia Farouk', 'call', dict(reversed(list(args.items()))))
        self.assertEqual(one, two)
        self.assertEqual(round1.split_for_family(one), round1.split_for_family(two))
        self.assertEqual({round1.split_for_family(f'family:{i}') for i in range(200)}, {'train', 'dev', 'test'})

    def test_fixture_exclusion_includes_wake_fillers_and_noisy_flashlight(self):
        keys = round1.fixture_keys()
        for text in ['open youtube', 'izumi please open youtube now',
                     'hey jimmy um down on the flushlight please']:
            with self.subTest(text=text):
                self.assertTrue(round1.is_fixture(text, keys))
        self.assertFalse(round1.is_fixture('phone Nadia Farouk', keys))

    def test_finite_and_no_argument_semantics_cannot_leak_by_wording(self):
        args = {'target': 'flashlight', 'state': 'on'}
        self.assertEqual(round1.family_key('enable the torch', 'toggle', args),
                         round1.family_key('hayzumi turn on the flushlight', 'toggle', args))
        self.assertEqual(round1.family_key('lock this handset', 'lock_phone', {}),
                         round1.family_key('put my phone on its lock screen', 'lock_phone', {}))

    def test_contact_aliases_and_default_carrier_app_share_family(self):
        expected = round1.family_key('call mom', 'call', {'contact': 'mom'})
        for alias in ['mum', 'my mother']:
            actual = round1.family_key('phone ' + alias, 'call', {'contact': alias, 'app': 'phone'})
            self.assertEqual(actual, expected)

    def test_noise_preserves_literal_message_case_and_punctuation(self):
        text = 'send Nadia a text saying Meet Dr. Lee at A&B!'
        for seed in range(12):
            self.assertIn('Meet Dr. Lee at A&B!', round1.noisy(text, 'send_sms', random.Random(seed)))

    def test_validator_rejects_cross_split_family_and_normalized_duplicates(self):
        original = self.row()
        round1.validate_rows({'train': [original]}, self.schemas)
        alternate = self.row('ring Nadia Farouk', 'test')
        with self.assertRaisesRegex(ValueError, 'family'):
            round1.validate_rows({'train': [original], 'test': [alternate]}, self.schemas)
        duplicate = self.row('PHONE Nadia Farouk!')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            round1.validate_rows({'train': [original, duplicate]}, self.schemas)

    def test_validator_rejects_fixture_and_incorrect_targets(self):
        with self.assertRaisesRegex(ValueError, 'fixture'):
            round1.validate_rows({'train': [self.row('call mom')]}, self.schemas)
        for field, value in [('completion', 'chat<end_of_turn>'),
                             ('prompt', self.prefix + 'wrong user turn'),
                             ('split', 'test'), ('provenance', {}), ('family', 'invented-key')]:
            row = self.row()
            row[field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                round1.validate_rows({'train': [row]}, self.schemas)

    def test_wrappers_preserve_payload_boundaries_and_question_grammar(self):
        payload = 'send Nadia a text saying Meet Dr. Lee at A&B!'
        question = 'what does example.org say'
        for seed in range(60):
            self.assertTrue(round1.wrap(payload, random.Random(seed), 'send_sms').endswith(payload))
            wrapped = round1.wrap(question, random.Random(seed), 'read_page')
            self.assertNotRegex(wrapped, r'(could|would|can) you what')

    def test_write_once_corpus_preserves_locked_bytes_and_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            rows = {'train': [self.row()], 'dev': [], 'test': [self.row('ring Omar', 'test', 'contact:omar')]}
            stats = {}
            round1.write_outputs(rows, directory, stats)
            test_path = Path(directory) / 'test.jsonl'
            before = test_path.read_bytes()
            manifest = json.loads((Path(directory) / 'stats.json').read_text())
            self.assertEqual(manifest['sha256']['test'], hashlib.sha256(before).hexdigest())
            with self.assertRaises(FileExistsError):
                round1.write_outputs({'train': [], 'dev': [], 'test': []}, directory, {})
            self.assertEqual(test_path.read_bytes(), before)

    def test_public_rows_require_attribution_revision_license_and_train_source(self):
        row = self.row()
        row['slice'] = 'public'
        row['provenance'] = {'dataset': 'google/mobile-actions', 'license': 'CC-BY-4.0',
                             'revision': '0123456789abcdef', 'source_url': 'https://huggingface.co/datasets/google/mobile-actions',
                             'source_split': 'train', 'source_file': 'dataset.jsonl', 'input_sha256': 'abc',
                             'row_index': 0, 'attribution': 'Google'}
        round1.validate_rows({'train': [row]}, self.schemas)
        for field in ['license', 'revision', 'source_url', 'input_sha256', 'row_index', 'attribution']:
            invalid = copy.deepcopy(row)
            del invalid['provenance'][field]
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, 'provenance'):
                round1.validate_rows({'train': [invalid]}, self.schemas)
        row['provenance']['source_split'] = 'eval'
        with self.assertRaisesRegex(ValueError, 'evaluation'):
            round1.validate_rows({'train': [row]}, self.schemas)

    def test_router_metadata_cannot_dispatch_a_tool(self):
        row = self.row()
        row['router'] = {'kind': 'missing_slots'}
        row['call_only_training'] = False
        with self.assertRaisesRegex(ValueError, 'Router'):
            round1.validate_rows({'train': [row]}, self.schemas)


if __name__ == '__main__':
    unittest.main()
