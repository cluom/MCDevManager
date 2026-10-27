"""Inspect the built IPA and generate the public AltStore Classic source."""

import argparse
from copy import deepcopy
from datetime import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import plistlib
import re
import stat
import subprocess
import tempfile
from urllib.parse import quote
import zipfile

from generate_altstore_source import display_version
from altstore_release_notes import DEFAULT_NOTES, description_for, load_notes


REPOSITORY = "cluom/MCDevManager"
BUNDLE_ID = "com.lemon.mcdevmanagermp.MCDevManagerMPR"
GROUP_ID = "group.com.lemon.mcdevmanagermp.income"
SOURCE_BRANCH = "altstore-source"
RELEASE_BASE = f"https://github.com/{REPOSITORY}/releases/download"
SOURCE_BASE = f"https://raw.githubusercontent.com/{REPOSITORY}/{SOURCE_BRANCH}"
SOURCE_URL = f"{SOURCE_BASE}/source.json"
DESCRIPTION = (
    "网易我的世界开发者内容管理器，基于 BitterLemonn/MCDevManager 的扩展版本。"
    "支持收益与数据追踪、条件抽奖、赠送报告补抽和评论回复；"
    "包含今日/昨日收益及明细翻页的 iOS 小组件。"
    "使用自己的开发者账号登录；更新时使用原安装商店与原 Apple ID 覆盖安装，并保留收益小组件扩展。"
    "本源提供未正式签名的 IPA，需要通过 AltStore / SideStore 自行签名，不提供账号或证书。"
)


def version_key(version):
    """Compare numeric app versions and build numbers, never file names."""
    values = (version["version"], version["buildVersion"])
    for value in values:
        if not isinstance(value, str) or not re.fullmatch(r"\d+(?:\.\d+){0,2}", value):
            raise ValueError("Version and build number must be numeric")
    return tuple(tuple(map(int, value.split("."))) + (0,) * (3 - len(value.split(".")))
                 for value in values)


def read_entitlements(bundle):
    result = subprocess.run(
        ["codesign", "--display", "--entitlements", ":-", str(bundle)],
        check=True, capture_output=True,
    )
    return plistlib.loads(result.stdout)


def inspect_ipa(ipa, entitlement_reader=read_entitlements):
    with tempfile.TemporaryDirectory() as directory, zipfile.ZipFile(ipa) as archive:
        entries = archive.infolist()
        if sum(entry.file_size for entry in entries) > 1024 * 1024 * 1024:
            raise ValueError("Unexpectedly large IPA")
        for entry in entries:
            path = PurePosixPath(entry.filename)
            if (path.is_absolute() or ".." in path.parts or "\\" in entry.filename
                    or ":" in entry.filename or stat.S_ISLNK(entry.external_attr >> 16)):
                raise ValueError("Unsafe archive path")
            if (path.name == "embedded.mobileprovision" or path.name == ".env"
                    or path.suffix.lower() in {".sqlite", ".sqlite3", ".db", ".p12", ".log"}):
                raise ValueError("IPA contains a personal profile, database or private file")
        roots = [entry.filename for entry in entries
                 if re.fullmatch(r"Payload/[^/]+\.app/Info\.plist", entry.filename)]
        if len(roots) != 1:
            raise ValueError("Expected exactly one main application")
        archive.extractall(directory)
        app = Path(directory) / PurePosixPath(roots[0]).parent
        info = plistlib.loads((app / "Info.plist").read_bytes())
        if info.get("CFBundleIdentifier") != BUNDLE_ID:
            raise ValueError("Unexpected bundle identifier")
        version = {"version": info["CFBundleShortVersionString"],
                   "buildVersion": info["CFBundleVersion"]}
        version_key(version)
        minimum = info["MinimumOSVersion"]
        if not re.fullmatch(r"\d+(?:\.\d+){0,2}", minimum):
            raise ValueError("Invalid minimum OS version")
        bundles = [app, *sorted(app.glob("PlugIns/*.appex"))]
        widget = app / "PlugIns/IncomeWidget.appex"
        if widget not in bundles:
            raise ValueError("Income widget is missing")
        entitlements = set()
        privacy = {}
        for bundle in bundles:
            bundle_info = plistlib.loads((bundle / "Info.plist").read_bytes())
            identifier = bundle_info.get("CFBundleIdentifier", "")
            if bundle != app and not identifier.startswith(BUNDLE_ID + "."):
                raise ValueError("Unexpected extension identifier")
            for key in ("CFBundleShortVersionString", "CFBundleVersion"):
                if bundle_info.get(key) != info[key]:
                    raise ValueError("Extension version does not match application")
            if bundle == widget and bundle_info.get("NSExtension", {}).get(
                    "NSExtensionPointIdentifier") != "com.apple.widgetkit-extension":
                raise ValueError("Invalid widget extension")
            permissions = entitlement_reader(bundle)
            if GROUP_ID not in permissions.get("com.apple.security.application-groups", []):
                raise ValueError("Missing shared income widget app group")
            entitlements.update(permissions)
            for key, value in bundle_info.items():
                if key.startswith("NS") and "UsageDescription" in key:
                    if not isinstance(value, str) or not value.strip():
                        raise ValueError("Missing privacy description")
                    privacy.setdefault(key, value)
        with ipa.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return {**version, "marketingVersion": display_version(info),
                "minOSVersion": minimum, "size": ipa.stat().st_size,
                "sha256": digest,
                "appPermissions": {"entitlements": sorted(entitlements), "privacy": privacy}}


def make_source(metadata, run_id, date, previous=None, *, release_notes):
    version_key(metadata)
    if not re.fullmatch(r"[1-9]\d*", run_id):
        raise ValueError("Invalid build run ID")
    if datetime.fromisoformat(date.replace("Z", "+00:00")).tzinfo is None:
        raise ValueError("Release date must include a time zone")
    tag = f"altstore-ios-{run_id}"
    display = metadata.get("marketingVersion", metadata["version"])
    filename = f"MCDevManager-{display}-{metadata['buildVersion']}.ipa"
    entry = {key: metadata[key] for key in
             ("version", "buildVersion", "minOSVersion", "size")}
    entry.update({"date": date, "downloadURL": f"{RELEASE_BASE}/{tag}/{quote(filename)}",
                  "localizedDescription": description_for(release_notes, display)})
    entry["marketingVersion"] = display
    permissions = deepcopy(metadata["appPermissions"])
    versions = []
    if previous:
        apps = previous.get("apps", [])
        if len(apps) != 1 or apps[0].get("bundleIdentifier") != BUNDLE_ID:
            raise ValueError("Unexpected previous source")
        versions = deepcopy(apps[0]["versions"])
        if not versions:
            raise ValueError("Previous source has no versions")
        if version_key(entry) < max(map(version_key, versions)):
            raise ValueError("Refusing to roll back the public source")
        for old in versions:
            if version_key(old) == version_key(entry):
                if old["downloadURL"] != entry["downloadURL"] or old["size"] != entry["size"]:
                    raise ValueError("Version/build already published by another artifact")
        versions = [old for old in versions if version_key(old) != version_key(entry)]
        old_permissions = apps[0]["appPermissions"]
        permissions["entitlements"] = sorted(set(permissions["entitlements"])
                                             | set(old_permissions["entitlements"]))
        permissions["privacy"] = {**old_permissions["privacy"], **permissions["privacy"]}
    source = {
        "name": "MCDevManager",
        "identifier": "cn.cluom.mcdevmanager.altstore",
        "sourceURL": SOURCE_URL,
        "subtitle": "MCDevManager iOS 定制版与收益小组件",
        "website": f"https://github.com/{REPOSITORY}",
        "iconURL": f"{SOURCE_BASE}/icon.png",
        "tintColor": "9D85C4",
        "apps": [{"name": "MCDevManager", "bundleIdentifier": BUNDLE_ID,
                  "developerName": "BitterLemonn / cluom",
                  "localizedDescription": DESCRIPTION,
                  "iconURL": f"{SOURCE_BASE}/icon.png",
                  "tintColor": "9D85C4", "category": "developer",
                  "versions": [entry, *versions], "appPermissions": permissions}],
        "news": [],
    }
    manifest = {"tag": tag, "filename": filename, "sha256": metadata["sha256"],
                "size": metadata["size"], "version": metadata["version"],
                "buildVersion": metadata["buildVersion"], "sourceURL": SOURCE_URL}
    return source, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ipa-dir", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--date", required=True)
    parser.add_argument("--previous", type=Path)
    parser.add_argument("--release-notes", type=Path, default=DEFAULT_NOTES)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    packages = list(args.ipa_dir.rglob("*.ipa"))
    if len(packages) != 1 or not packages[0].name.endswith("-Release-ios-arm64.ipa"):
        raise ValueError("Expected one Release IPA")
    metadata = inspect_ipa(packages[0])
    previous = json.loads(args.previous.read_text(encoding="utf-8")) if args.previous else None
    source, manifest = make_source(metadata, args.run_id, args.date, previous,
                                   release_notes=load_notes(args.release_notes))
    args.output.mkdir(parents=True, exist_ok=True)
    manifest["ipaPath"] = str(packages[0].resolve())
    for filename, value in (("source.json", source), ("manifest.json", manifest)):
        (args.output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2)
                                          + "\n", encoding="utf-8")
    (args.output / "SHA256SUMS").write_text(
        f"{manifest['sha256']}  {manifest['filename']}\n", encoding="utf-8")
    print(f"Validated AltStore source: {metadata['version']} ({metadata['buildVersion']})")


if __name__ == "__main__":
    main()
