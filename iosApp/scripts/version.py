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
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:\.[1-9][0-9]*)?", version):
        raise ValueError(f"Expected major.minor.patch with an optional positive revision: {version!r}")
    return version


def bundle_version(root=ROOT):
    """Apple bundle version stays three-part; the app displays the full revision."""
    return ".".join(project_version(root).split(".")[:3])


def bump_revision(root=ROOT):
    """Prepare one custom release; do not call for a retry of the same release."""
    current = project_version(root)
    parts = current.split(".")
    revision = int(parts[3]) + 1 if len(parts) == 4 else 1
    updated = ".".join(parts[:3] + [str(revision)])
    path = root / "gradle/libs.versions.toml"
    text, count = re.subn(r'(?m)^(versions-name\s*=\s*)"[^"]+"',
                         lambda match: match[1] + '"' + updated + '"', path.read_text(encoding="utf-8"))
    if count != 1:
        raise ValueError("Expected exactly one versions-name setting")
    path.write_text(text, encoding="utf-8")
    return updated


def setting(text, key):
    values = re.findall(rf"^{key}\s*=\s*([^\r\n]+)$", text, re.MULTILINE)
    if len(values) != 1:
        raise ValueError(f"Expected exactly one {key} in Config.xcconfig")
    return values[0].strip()


def sync(root=ROOT, build_number=None):
    path = root / "iosApp/Configuration/Config.xcconfig"
    text = path.read_text(encoding="utf-8")
    replacements = {"MARKETING_VERSION": bundle_version(root)}
    if build_number is not None:
        if not re.fullmatch(r"[1-9][0-9]*", build_number):
            raise ValueError("Build number must be a positive integer")
        replacements["CURRENT_PROJECT_VERSION"] = build_number
    for key, value in replacements.items():
        setting(text, key)
        text = re.sub(rf"^{key}\s*=\s*[^\r\n]+$", f"{key}={value}", text, flags=re.MULTILINE)
    display = project_version(root)
    if re.search(r"^MCDEV_DISPLAY_VERSION\s*=", text, flags=re.MULTILINE):
        setting(text, "MCDEV_DISPLAY_VERSION")
        text = re.sub(r"^MCDEV_DISPLAY_VERSION\s*=\s*[^\r\n]+$",
                      f"MCDEV_DISPLAY_VERSION={display}", text, flags=re.MULTILINE)
    else:
        text = text.rstrip() + f"\nMCDEV_DISPLAY_VERSION={display}\n"
    path.write_text(text, encoding="utf-8")
    return display


def verify(ipa, root=ROOT):
    with zipfile.ZipFile(ipa) as archive:
        paths = [name for name in archive.namelist()
                 if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", name)]
        if len(paths) != 1:
            raise ValueError("Expected exactly one app Info.plist in the IPA")
        info = plistlib.loads(archive.read(paths[0]))
    config = (root / "iosApp/Configuration/Config.xcconfig").read_text(encoding="utf-8")
    expected = {
        "CFBundleShortVersionString": bundle_version(root),
        "CFBundleVersion": setting(config, "CURRENT_PROJECT_VERSION"),
    }
    if project_version(root) != bundle_version(root) or "MCDevManagerDisplayVersion" in info:
        expected["MCDevManagerDisplayVersion"] = project_version(root)
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
    commands.add_parser("bump-revision", help="Increment the custom revision before a new release")
    args = parser.parse_args()
    if args.command == "sync":
        print(f"Synced iOS version: {sync(build_number=args.build_number)}")
    elif args.command == "verify":
        print(f"Verified IPA version: {verify(args.ipa)}")
    else:
        print(f"Prepared custom version: {bump_revision()}")
