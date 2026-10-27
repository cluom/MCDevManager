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

    def ipa(self, app_version, build="1", display=None):
        path = self.root / "test.ipa"
        info = {"CFBundleShortVersionString": app_version, "CFBundleVersion": build}
        if display is not None:
            info["MCDevManagerDisplayVersion"] = display
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("Payload/Test.app/Info.plist", plistlib.dumps(info, fmt=plistlib.FMT_BINARY))
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

    def test_custom_revision_maps_to_compatible_ios_metadata(self):
        version.bump_revision(self.root)
        self.assertEqual(version.project_version(self.root), "1.2.5.1")
        self.assertEqual(version.sync(self.root, "13"), "1.2.5.1")
        text = self.config.read_text()
        self.assertIn("MARKETING_VERSION=1.2.5", text)
        self.assertIn("MCDEV_DISPLAY_VERSION=1.2.5.1", text)
        self.assertEqual(version.verify(self.ipa("1.2.5", "13", "1.2.5.1"), self.root)[
            "MCDevManagerDisplayVersion"], "1.2.5.1")

    def test_custom_revision_requires_matching_embedded_display_version(self):
        version.bump_revision(self.root)
        for display in (None, "1.2.5", "1.2.5.2"):
            with self.subTest(display=display), self.assertRaisesRegex(ValueError, "DisplayVersion"):
                version.verify(self.ipa("1.2.5", display=display), self.root)

    def test_revision_bumps_without_changing_upstream_base(self):
        self.assertEqual(version.bump_revision(self.root), "1.2.5.1")
        self.assertEqual(version.bump_revision(self.root), "1.2.5.2")
        (self.root / "gradle/libs.versions.toml").write_text(
            '[versions]\nversions-name = "1.2.6"\n', encoding="utf-8")
        self.assertEqual(version.bump_revision(self.root), "1.2.6.1")

    def test_rejects_zero_revision_and_five_part_version(self):
        for value in ("1.2.5.0", "1.2.5.1.1"):
            (self.root / "gradle/libs.versions.toml").write_text(
                f'[versions]\nversions-name = "{value}"\n', encoding="utf-8")
            with self.subTest(value=value), self.assertRaises(ValueError):
                version.project_version(self.root)


if __name__ == "__main__":
    unittest.main()
