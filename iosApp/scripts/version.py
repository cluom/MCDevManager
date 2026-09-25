"""Keep the iOS bundle version aligned with the shared version catalog."""

import argparse
from pathlib import Path
import plistlib
import re
import tomllib
import zipfile


ROOT = Path(__file__).resolve().parents[2]


def project_version(root=ROOT):
    with (root / "gradle/libs.versions.toml").open("rb") as source:
        version = tomllib.load(source)["versions"]["versions-name"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError(f"iOS requires a numeric major.minor.patch version: {version!r}")
    return version


def setting(text, key):
    values = re.findall(rf"^{key}\s*=\s*([^\r\n]+)$", text, re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {key} in Config.xcconfig")
    return values[0].strip()


def sync(root=ROOT, build_number=None):
    path = root / "iosApp/Configuration/Config.xcconfig"
    text = path.read_text(encoding="utf-8")
    replacements = {"MARKETING_VERSION": project_version(root)}
    if build_number is not None:
        if not re.fullmatch(r"[1-9][0-9]*", build_number):
            raise ValueError("Build number must be a positive integer")
        replacements["CURRENT_PROJECT_VERSION"] = build_number
    for key, value in replacements.items():
        setting(text, key)
        text = re.sub(rf"^{key}\s*=\s*[^\r\n]+$", f"{key}={value}", text, flags=re.MULTILINE)
    path.write_text(text, encoding="utf-8")
    return replacements["MARKETING_VERSION"]


def verify(ipa, root=ROOT):
    with zipfile.ZipFile(ipa) as archive:
        paths = [name for name in archive.namelist()
                 if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(paths) != 1:
            raise ValueError("Expected exactly one app Info.plist in the IPA")
        info = plistlib.loads(archive.read(paths[0]))
    config = (root / "iosApp/Configuration/Config.xcconfig").read_text(encoding="utf-8")
    expected = {
        "CFBundleShortVersionString": project_version(root),
        "CFBundleVersion": setting(config, "CURRENT_PROJECT_VERSION"),
    }
    for key, value in expected.items():
        if info.get(key) != value:
            raise ValueError(f"{key}: expected {value!r}, got {info.get(key)!r}")
    return expected


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    sync_parser = commands.add_parser("sync")
    sync_parser.add_argument("--build-number")
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("ipa", type=Path)
    args = parser.parse_args()
    if args.command == "sync":
        print(f"Synced iOS version: {sync(build_number=args.build_number)}")
    else:
        print(f"Verified IPA version: {verify(args.ipa)}")
