#!/usr/bin/env python3
"""Create a small, valid .futolanguage package for manual importer testing."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import zipfile
from pathlib import Path
from typing import Any

FORMAT_VERSION = "0.1"
PAYLOAD_PATH = "components/dictionary.txt"
FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def build_manifest(payload: bytes) -> dict[str, Any]:
    return {
        "formatVersion": FORMAT_VERSION,
        "package": {
            "id": "org.futo.example.german.dictionary",
            "name": "German Example Dictionary",
            "description": "Minimal package used to test the debug language package inspector.",
            "version": "0.1.0",
            "kind": "component",
            "authors": [{"name": "FUTO Keyboard development example"}],
            "license": "CC0-1.0",
            "languages": ["de"],
        },
        "components": [
            {
                "id": "example-dictionary",
                "name": "German Example Dictionary",
                "description": "A tiny text payload; not a runtime-compatible binary dictionary.",
                "version": "0.1.0",
                "kind": "dictionary",
                "activation": "stackable",
                "priority": 0,
                "languages": ["de"],
                "layouts": ["qwertz"],
                "tasks": ["dictionary-lookup-v1"],
                "capabilities": {
                    "required": ["offline-only"],
                    "optional": ["example-payload"],
                },
                "payload": {
                    "path": PAYLOAD_PATH,
                    "mediaType": "text/plain",
                    "sha256": sha256_hex(payload),
                    "sizeBytes": len(payload),
                },
            }
        ],
        "profiles": [
            {
                "id": "standard",
                "name": "Standard",
                "selections": [
                    {
                        "slot": "dictionary",
                        "strategy": "append",
                        "components": [
                            {
                                "componentId": "example-dictionary",
                                "required": True,
                            }
                        ],
                    }
                ],
            }
        ],
        "defaultProfile": "standard",
    }


def zip_info(path: str) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, date_time=FIXED_ZIP_TIMESTAMP)
    info.compress_type = zipfile.ZIP_DEFLATED
    info.create_system = 0
    info.external_attr = 0
    return info


def create_package(output_path: Path) -> None:
    payload = (
        "wahrscheinlich\n"
        "Tastatur\n"
        "Sprachmodell\n"
        "Wörterbuch\n"
    ).encode("utf-8")
    manifest = build_manifest(payload)
    manifest_bytes = (
        json.dumps(manifest, ensure_ascii=False, indent=2, sort_keys=False) + "\n"
    ).encode("utf-8")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = output_path.with_suffix(output_path.suffix + ".tmp")

    try:
        with zipfile.ZipFile(temporary_path, mode="w") as archive:
            archive.writestr(zip_info("manifest.json"), manifest_bytes)
            archive.writestr(zip_info(PAYLOAD_PATH), payload)
        temporary_path.replace(output_path)
    finally:
        temporary_path.unlink(missing_ok=True)


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        default=Path("build/language-packages/german-example.futolanguage"),
        help="Output package path",
    )
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    create_package(args.output)
    print(args.output.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
