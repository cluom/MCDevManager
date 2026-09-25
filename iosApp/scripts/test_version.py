import plistlib
from pathlib import Path
import tempfile
import unittest
import zipfile

import version


class VersionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "gradle").mkdir()
        (self.root / "gradle/libs.versions.toml").write_text(
            '[versions]\nversions-name = "1.2.5"\n', encoding="utf-8")
        self.config = self.root / "iosApp/Configuration/Config.xcconfig"
        self.config.parent.mkdir(parents=True)
        self.config.write_text(
            'PRODUCT_NAME=Test\nCURRENT_PROJECT_VERSION=1\nMARKETING_VERSION=1.0\n',
            encoding="utf-8")

    def ipa(self, app_version, build="1"):
        path = self.root / "test.ipa"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Payload/Test.app/Info.plist", plistlib.dumps({
                "CFBundleShortVersionString": app_version, "CFBundleVersion": build,
            }, fmt=plistlib.FMT_BINARY))
        return path

    def test_sync_uses_catalog_and_preserves_unrelated_settings(self):
        version.sync(self.root, "12")
        text = self.config.read_text(encoding="utf-8")
        self.assertIn("MARKETING_VERSION=1.2.5", text)
        self.assertIn("CURRENT_PROJECT_VERSION=12", text)
        self.assertIn("PRODUCT_NAME=Test", text)

    def test_sync_preserves_build_number_when_not_supplied(self):
        version.sync(self.root)
        self.assertIn("CURRENT_PROJECT_VERSION=1", self.config.read_text())

    def test_sync_rejects_missing_setting_without_writing(self):
        self.config.write_text("CURRENT_PROJECT_VERSION=1\n", encoding="utf-8")
        before = self.config.read_bytes()
        with self.assertRaises(ValueError):
            version.sync(self.root)
        self.assertEqual(self.config.read_bytes(), before)

    def test_sync_rejects_invalid_build_number(self):
        with self.assertRaises(ValueError):
            version.sync(self.root, "ci-12")

    def test_verify_accepts_matching_bundle(self):
        self.assertEqual(version.verify(self.ipa("1.2.5"), self.root)[
            "CFBundleShortVersionString"], "1.2.5")

    def test_verify_rejects_old_bundle_even_with_new_filename(self):
        with self.assertRaisesRegex(ValueError, "CFBundleShortVersionString"):
            version.verify(self.ipa("1.0"), self.root)

    def test_verify_rejects_wrong_build_number(self):
        with self.assertRaisesRegex(ValueError, "CFBundleVersion"):
            version.verify(self.ipa("1.2.5", "2"), self.root)

    def test_rejects_non_numeric_project_version(self):
        (self.root / "gradle/libs.versions.toml").write_text(
            '[versions]\nversions-name = "1.2.5-yohu"\n', encoding="utf-8")
        with self.assertRaises(ValueError):
            version.project_version(self.root)


if __name__ == "__main__":
    unittest.main()
