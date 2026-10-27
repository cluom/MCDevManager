from copy import deepcopy
from pathlib import Path
import plistlib
import tempfile
import unittest
import zipfile

import altstore_source as source


class SourceTests(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.info = {"CFBundleIdentifier": source.BUNDLE_ID,
                     "CFBundleShortVersionString": "1.2.9", "CFBundleVersion": "10",
                     "MinimumOSVersion": "17.0"}
        self.permissions = {"com.apple.security.application-groups": [source.GROUP_ID]}
        self.notes = {"1.2.9": "本版新增条件抽奖。", "1.2.9.1": "新增两种商店更新入口。"}

    def ipa(self, widget=True, widget_changes=None, extra=None):
        path = self.root / "app.ipa"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Payload/App.app/Info.plist", plistlib.dumps(self.info))
            if widget:
                info = {**self.info, "CFBundleIdentifier": source.BUNDLE_ID + ".IncomeWidget",
                        "NSExtension": {"NSExtensionPointIdentifier": "com.apple.widgetkit-extension"},
                        **(widget_changes or {})}
                archive.writestr("Payload/App.app/PlugIns/IncomeWidget.appex/Info.plist",
                                 plistlib.dumps(info))
            for name, contents in (extra or {}).items():
                archive.writestr(name, contents)
        return path

    def inspect(self, path):
        return source.inspect_ipa(path, lambda _bundle: self.permissions)

    def metadata(self):
        return self.inspect(self.ipa())

    def make(self, metadata=None, run="100", previous=None):
        return source.make_source(metadata or self.metadata(), run,
                                  "2026-10-26T09:14:23Z", previous, release_notes=self.notes)

    def test_reads_real_version_permissions_size_hash(self):
        path = self.ipa()
        metadata = self.inspect(path)
        self.assertEqual(metadata["version"], "1.2.9")
        self.assertEqual(metadata["buildVersion"], "10")
        self.assertEqual(metadata["size"], path.stat().st_size)
        self.assertEqual(len(metadata["sha256"]), 64)
        self.assertEqual(metadata["appPermissions"]["entitlements"], list(self.permissions))

    def test_rejects_missing_widget(self):
        with self.assertRaisesRegex(ValueError, "widget is missing"):
            self.inspect(self.ipa(widget=False))

    def test_revision_is_read_from_ipa_and_displayed_in_source(self):
        self.info["MCDevManagerDisplayVersion"] = "1.2.9.1"
        feed, manifest = self.make()
        entry = feed["apps"][0]["versions"][0]
        self.assertEqual(entry["marketingVersion"], "1.2.9.1")
        self.assertEqual(entry["version"], "1.2.9")
        self.assertEqual(manifest["filename"], "MCDevManager-1.2.9.1-10.ipa")
        self.assertEqual(entry["localizedDescription"], self.notes["1.2.9.1"])

    def test_rejects_extension_version_mismatch(self):
        with self.assertRaisesRegex(ValueError, "version does not match"):
            self.inspect(self.ipa(widget_changes={"CFBundleVersion": "9"}))

    def test_rejects_wrong_app(self):
        self.info["CFBundleIdentifier"] = "com.example.other"
        with self.assertRaisesRegex(ValueError, "bundle identifier"):
            self.metadata()

    def test_rejects_missing_app_group(self):
        self.permissions = {}
        with self.assertRaisesRegex(ValueError, "shared income widget"):
            self.metadata()

    def test_rejects_private_data_and_profiles(self):
        for name in ("account.db", ".env", "embedded.mobileprovision", "key.p12"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "private file"):
                self.inspect(self.ipa(extra={"Payload/App.app/" + name: b"private"}))

    def test_rejects_path_traversal(self):
        with self.assertRaisesRegex(ValueError, "Unsafe archive"):
            self.inspect(self.ipa(extra={"../../outside": b"no"}))

    def test_collects_extension_privacy(self):
        metadata = self.inspect(self.ipa(widget_changes={
            "NSLocalNetworkUsageDescription": "Connect to local services"}))
        self.assertIn("NSLocalNetworkUsageDescription", metadata["appPermissions"]["privacy"])

    def test_source_urls_are_public_ipa_and_stable_feed(self):
        feed, manifest = self.make()
        app = feed["apps"][0]
        self.assertEqual(app["bundleIdentifier"], source.BUNDLE_ID)
        self.assertEqual(feed["sourceURL"], source.SOURCE_URL)
        self.assertEqual(app["versions"][0]["downloadURL"],
                         source.RELEASE_BASE + "/altstore-ios-100/MCDevManager-1.2.9-10.ipa")
        self.assertEqual(manifest["filename"], "MCDevManager-1.2.9-10.ipa")

    def test_preserves_history_and_sorts_new_build_first(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        metadata["buildVersion"] = "11"
        feed, _ = self.make(metadata, "101", previous)
        self.assertEqual([v["buildVersion"] for v in feed["apps"][0]["versions"]], ["11", "10"])
        self.assertEqual(previous["apps"][0]["versions"][0]["buildVersion"], "10")

    def test_new_revision_does_not_overwrite_any_history_fields(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        previous["apps"][0]["versions"][0]["localizedDescription"] = "历史版本独立说明"
        original = deepcopy(previous)
        metadata.update(buildVersion="12", marketingVersion="1.2.9.1")
        feed, _ = self.make(metadata, "101", previous)
        self.assertEqual(feed["apps"][0]["versions"][1:], original["apps"][0]["versions"])
        self.assertEqual(previous, original)
        self.assertEqual(feed["apps"][0]["versions"][0]["localizedDescription"], self.notes["1.2.9.1"])

    def test_missing_version_notes_rejected_instead_of_installation_fallback(self):
        self.notes = {}
        with self.assertRaisesRegex(ValueError, "Missing release notes for 1.2.9"):
            self.make()

    def test_republication_is_idempotent(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        feed, _ = self.make(metadata, previous=previous)
        self.assertEqual(feed, previous)

    def test_duplicate_build_with_another_artifact_rejected(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        with self.assertRaisesRegex(ValueError, "already published"):
            self.make(metadata, "101", previous)

    def test_rollback_is_rejected(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        metadata["buildVersion"] = "9"
        with self.assertRaisesRegex(ValueError, "roll back"):
            self.make(metadata, "99", previous)

    def test_keeps_permissions_used_by_previous_versions(self):
        metadata = self.metadata()
        previous, _ = self.make(metadata)
        modified = deepcopy(metadata)
        modified["buildVersion"] = "11"
        modified["appPermissions"]["entitlements"] = ["new.permission"]
        feed, _ = self.make(modified, "101", previous)
        self.assertEqual(feed["apps"][0]["appPermissions"]["entitlements"],
                         ["com.apple.security.application-groups", "new.permission"])

    def test_rejects_invalid_date_run_and_version(self):
        metadata = self.metadata()
        with self.assertRaisesRegex(ValueError, "run ID"):
            self.make(metadata, "../1")
        with self.assertRaisesRegex(ValueError, "time zone"):
            source.make_source(metadata, "100", "2026-10-26", release_notes=self.notes)
        metadata["version"] = "1.2.9-custom"
        with self.assertRaisesRegex(ValueError, "numeric"):
            self.make(metadata)


if __name__ == "__main__":
    unittest.main()
