"""Maintain version-specific source descriptions without rebuilding or changing IPA metadata."""

import argparse
from copy import deepcopy
import json
from pathlib import Path
import re


DEFAULT_NOTES = Path(__file__).resolve().parents[2] / "altstore" / "release-notes.json"


def load_notes(path):
    notes = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(notes, dict) or not notes:
        raise ValueError("Release notes must be a non-empty version-to-description object")
    for version, description in notes.items():
        if not re.fullmatch(r"\d+\.\d+\.\d+(?:\.[1-9]\d*)?", version):
            raise ValueError(f"Invalid release notes version: {version}")
        if not isinstance(description, str) or not description.strip():
            raise ValueError(f"Empty release notes for {version}")
    return notes


def description_for(notes, version):
    description = notes.get(version)
    if not isinstance(description, str) or not description.strip():
        raise ValueError(f"Missing release notes for {version}; add its actual changes before publishing")
    return description.strip()


def update_descriptions(source, notes):
    """Explicit notes-only correction; preserve all other source fields exactly."""
    result = deepcopy(source)
    apps = result.get("apps", [])
    if len(apps) != 1 or not apps[0].get("versions"):
        raise ValueError("Expected one app with published versions")
    for entry in apps[0]["versions"]:
        display = entry.get("marketingVersion", entry["version"])
        entry["localizedDescription"] = description_for(notes, display)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--notes", type=Path, default=DEFAULT_NOTES)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = json.loads(args.source.read_text(encoding="utf-8"))
    updated = update_descriptions(source, load_notes(args.notes))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(updated, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Updated descriptions for {len(updated['apps'][0]['versions'])} versions; IPA metadata unchanged")


if __name__ == "__main__":
    main()
