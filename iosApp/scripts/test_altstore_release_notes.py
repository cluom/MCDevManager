from copy import deepcopy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import altstore_release_notes as notes_tool


class ReleaseNotesTests(unittest.TestCase):
    def setUp(self):
        self.source = {"apps": [{"localizedDescription": "应用介绍与安装提示",
                                 "appPermissions": {"entitlements": ["app-group"]},
                                 "versions": [
                                     {"version": "1.2.9", "marketingVersion": "1.2.9.1",
                                      "buildVersion": "12", "size": 35842096,
                                      "downloadURL": "https://example.com/revision.ipa",
                                      "date": "2026-10-27T15:42:54Z", "localizedDescription": "旧提示"},
                                     {"version": "1.2.9", "buildVersion": "10", "size": 35809380,
                                      "downloadURL": "https://example.com/base.ipa",
                                      "date": "2026-10-26T15:42:54Z", "localizedDescription": "旧提示"}]}],
                       "news": [], "other": {"preserve": True}}
        self.notes = {"1.2.9": "条件抽奖", "1.2.9.1": "商店更新入口"}

    def test_changes_only_each_versions_description_without_mutating_input(self):
        original = deepcopy(self.source)
        updated = notes_tool.update_descriptions(self.source, self.notes)
        entries = updated["apps"][0]["versions"]
        self.assertEqual([entry["localizedDescription"] for entry in entries],
                         ["商店更新入口", "条件抽奖"])
        for old, new in zip(original["apps"][0]["versions"], entries):
            new["localizedDescription"] = old["localizedDescription"]
        self.assertEqual(updated, original)
        self.assertEqual(self.source, original)

    def test_missing_notes_never_fall_back_to_another_versions_text(self):
        with self.assertRaisesRegex(ValueError, "Missing release notes for 1.2.9.1"):
            notes_tool.update_descriptions(self.source, {"1.2.9": "条件抽奖"})

    def test_rejects_empty_or_multi_app_source(self):
        for source in ({}, {"apps": [{"versions": []}]}, {"apps": [{}, {}]}):
            with self.subTest(source=source), self.assertRaises(ValueError):
                notes_tool.update_descriptions(source, self.notes)

    def test_notes_file_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "notes.json"
            for invalid in ([], {}, {"1.2.9": " "}, {"1.2.9": None}, {"latest": "changes"}):
                path.write_text(json.dumps(invalid), encoding="utf-8")
                with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                    notes_tool.load_notes(path)
            path.write_text(json.dumps(self.notes), encoding="utf-8")
            self.assertEqual(notes_tool.load_notes(path), self.notes)

    def test_checked_in_notes_cover_current_published_versions(self):
        notes = notes_tool.load_notes(notes_tool.DEFAULT_NOTES)
        self.assertTrue({"1.2.7", "1.2.9", "1.2.9.1"}.issubset(notes))
        self.assertNotEqual(notes["1.2.9"], notes["1.2.9.1"])

    def test_notes_only_cli_is_idempotent_without_any_ipa(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "source.json"
            notes = Path(directory) / "notes.json"
            path.write_text(json.dumps(self.source), encoding="utf-8")
            notes.write_text(json.dumps(self.notes), encoding="utf-8")
            args = ["notes", "--source", str(path), "--notes", str(notes), "--output", str(path)]
            with patch("sys.argv", args):
                notes_tool.main()
                first = path.read_bytes()
                notes_tool.main()
            self.assertEqual(path.read_bytes(), first)
            self.assertEqual(json.loads(first), notes_tool.update_descriptions(self.source, self.notes))

    def test_missing_notes_leave_existing_output_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source, output, notes = root / "source.json", root / "output.json", root / "notes.json"
            source.write_text(json.dumps(self.source), encoding="utf-8")
            output.write_text("existing source", encoding="utf-8")
            notes.write_text('{"1.2.9": "changes"}', encoding="utf-8")
            args = ["notes", "--source", str(source), "--notes", str(notes), "--output", str(output)]
            with patch("sys.argv", args), self.assertRaises(ValueError):
                notes_tool.main()
            self.assertEqual(output.read_text(encoding="utf-8"), "existing source")


if __name__ == "__main__":
    unittest.main()
