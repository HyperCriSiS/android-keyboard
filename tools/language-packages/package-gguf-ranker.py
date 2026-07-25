#!/usr/bin/env python3
"""Wrap an unchanged standard GGUF as a FUTO context-ranker package.

The tool uses only the Python standard library and is intended as the reference
implementation for the future Model Studio package export path.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path
from typing import BinaryIO, Iterable

FORMAT_VERSION = "0.1"
RUNTIME_ID = "gguf-causal-ranker"
RUNTIME_API_VERSION = 1
MODEL_MEDIA_TYPE = "application/vnd.futo.keyboard.model+gguf"
FIXED_ZIP_TIMESTAMP = (1980, 1, 1, 0, 0, 0)
CHUNK_SIZE = 1024 * 1024

PACKAGE_ID_PATTERN = re.compile(r"^[a-z0-9]+(?:[.-][a-z0-9]+)+$")
LOCAL_ID_PATTERN = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)*$")
SEMVER_PATTERN = re.compile(
    r"^(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)\."
    r"(0|[1-9][0-9]*)"
    r"(?:-[0-9A-Za-z.-]+)?"
    r"(?:\+[0-9A-Za-z.-]+)?$"
)
LANGUAGE_TAG_PATTERN = re.compile(r"^[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*$")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Wrap an unchanged causal GGUF model in a .futolanguage package "
            "using the generic FUTO candidate-ranker adapter."
        )
    )
    parser.add_argument("model", type=Path, help="Input GGUF model")
    parser.add_argument("output", type=Path, help="Output .futolanguage file")
    parser.add_argument("--package-id", required=True)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--package-version", default="0.1.0")
    parser.add_argument("--component-id", default="ranker")
    parser.add_argument("--component-version", default="0.1.0")
    parser.add_argument("--model-name", help="Displayed component/model name")
    parser.add_argument("--author", required=True)
    parser.add_argument("--license", required=True)
    parser.add_argument(
        "--language",
        action="append",
        required=True,
        dest="languages",
        help="BCP-47 language tag; repeat for multiple tags",
    )
    parser.add_argument(
        "--layout",
        action="append",
        default=[],
        dest="layouts",
        help="Supported layout identifier; repeat for multiple layouts",
    )
    parser.add_argument(
        "--boundary-mode",
        choices=("exact-text", "leading-separator", "trailing-separator"),
        required=True,
    )
    parser.add_argument("--max-context-tokens", type=int, default=256)
    parser.add_argument("--max-batch-size", type=int, default=16)
    parser.add_argument(
        "--bos-policy",
        choices=("model-default", "always", "never"),
        default="model-default",
    )
    parser.add_argument(
        "--context-truncation",
        choices=("keep-last", "reject"),
        default="keep-last",
    )
    parser.add_argument(
        "--disable-right-context",
        action="store_true",
        help="Disable optional right-context scoring",
    )
    parser.add_argument(
        "--add-eos",
        action="store_true",
        help="Score EOS after every candidate; incompatible with right-context scoring",
    )
    parser.add_argument("--min-ram-mb", type=int)
    parser.add_argument("--source-url")
    parser.add_argument("--description")
    return parser.parse_args(argv)


def validate_args(args: argparse.Namespace) -> None:
    if not args.model.is_file():
        raise ValueError(f"Input model does not exist: {args.model}")
    if args.model.suffix.lower() != ".gguf":
        raise ValueError("Input model must use the .gguf extension.")
    if not PACKAGE_ID_PATTERN.fullmatch(args.package_id):
        raise ValueError("--package-id must use lower-case reverse-domain notation.")
    if not LOCAL_ID_PATTERN.fullmatch(args.component_id):
        raise ValueError("--component-id contains unsupported characters.")
    if not SEMVER_PATTERN.fullmatch(args.package_version):
        raise ValueError("--package-version must be a semantic version.")
    if not SEMVER_PATTERN.fullmatch(args.component_version):
        raise ValueError("--component-version must be a semantic version.")
    if not args.package_name.strip():
        raise ValueError("--package-name must not be blank.")
    if not args.author.strip():
        raise ValueError("--author must not be blank.")
    if not args.license.strip():
        raise ValueError("--license must not be blank.")
    if not args.languages:
        raise ValueError("At least one --language is required.")
    invalid_languages = [
        language for language in args.languages if not LANGUAGE_TAG_PATTERN.fullmatch(language)
    ]
    if invalid_languages:
        raise ValueError(f"Invalid language tags: {', '.join(invalid_languages)}")
    if len({language.lower() for language in args.languages}) != len(args.languages):
        raise ValueError("Duplicate --language values are not allowed.")
    if len({layout.lower() for layout in args.layouts}) != len(args.layouts):
        raise ValueError("Duplicate --layout values are not allowed.")
    if any(not layout.strip() or len(layout) > 80 for layout in args.layouts):
        raise ValueError("Layout identifiers must contain 1 to 80 characters.")
    if not 16 <= args.max_context_tokens <= 32768:
        raise ValueError("--max-context-tokens must be between 16 and 32768.")
    if not 1 <= args.max_batch_size <= 64:
        raise ValueError("--max-batch-size must be between 1 and 64.")
    if args.min_ram_mb is not None and args.min_ram_mb < 0:
        raise ValueError("--min-ram-mb must not be negative.")
    if args.add_eos and not args.disable_right_context:
        raise ValueError("--add-eos requires --disable-right-context.")
    if args.output.resolve() == args.model.resolve():
        raise ValueError("Output path must differ from the input model path.")


def hash_and_size(path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with path.open("rb") as source:
        while chunk := source.read(CHUNK_SIZE):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def create_manifest(
    args: argparse.Namespace,
    model_sha256: str,
    model_size: int,
) -> dict[str, object]:
    model_name = (args.model_name or args.package_name).strip()
    package: dict[str, object] = {
        "id": args.package_id,
        "name": args.package_name.strip(),
        "version": args.package_version,
        "kind": "component",
        "authors": [{"name": args.author.strip()}],
        "license": args.license.strip(),
        "languages": args.languages,
    }
    if args.description:
        package["description"] = args.description.strip()
    if args.source_url:
        package["sourceUrl"] = args.source_url

    compatibility: dict[str, object] = {
        "minKeyboardApi": 2,
        "runtimeFeatures": ["gguf-causal-lm-v1"],
    }
    if args.min_ram_mb is not None:
        compatibility["minRamMb"] = args.min_ram_mb

    component = {
        "id": args.component_id,
        "name": model_name,
        "version": args.component_version,
        "kind": "context-ranker",
        "activation": "exclusive",
        "languages": args.languages,
        "layouts": args.layouts,
        "tasks": ["candidate-ranking-v1"],
        "capabilities": {
            "required": [
                "full-candidate-logprob",
                "unicode-graphemes",
                "offline-only",
            ]
        },
        "compatibility": compatibility,
        "runtime": {
            "id": RUNTIME_ID,
            "apiVersion": RUNTIME_API_VERSION,
            "parameters": {
                "boundaryMode": args.boundary_mode,
                "maxContextTokens": args.max_context_tokens,
                "maxBatchSize": args.max_batch_size,
                "supportsRightContext": not args.disable_right_context,
                "bosPolicy": args.bos_policy,
                "addEos": args.add_eos,
                "contextTruncation": args.context_truncation,
            },
        },
        "payload": {
            "path": "components/ranker.gguf",
            "mediaType": MODEL_MEDIA_TYPE,
            "sha256": model_sha256,
            "sizeBytes": model_size,
        },
    }

    return {
        "formatVersion": FORMAT_VERSION,
        "package": package,
        "components": [component],
    }


def canonical_json_bytes(value: dict[str, object]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def deterministic_zip_info(path: str, compress_type: int) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(path, FIXED_ZIP_TIMESTAMP)
    info.compress_type = compress_type
    info.create_system = 0
    info.external_attr = 0
    return info


def copy_stream(source: BinaryIO, destination: BinaryIO) -> None:
    while chunk := source.read(CHUNK_SIZE):
        destination.write(chunk)


def write_package(
    output: Path,
    manifest_bytes: bytes,
    model: Path,
) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(
        tempfile.mkstemp(
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
        )[1]
    )
    try:
        with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
            archive.writestr(
                deterministic_zip_info("manifest.json", zipfile.ZIP_DEFLATED),
                manifest_bytes,
                compresslevel=9,
            )
            model_info = deterministic_zip_info(
                "components/ranker.gguf",
                zipfile.ZIP_STORED,
            )
            with model.open("rb") as source, archive.open(model_info, "w", force_zip64=True) as target:
                copy_stream(source, target)
        temporary.replace(output)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise


def verify_package(
    package_path: Path,
    expected_manifest: bytes,
    expected_model_hash: str,
    expected_model_size: int,
) -> None:
    with zipfile.ZipFile(package_path, "r") as archive:
        names = archive.namelist()
        if names != ["manifest.json", "components/ranker.gguf"]:
            raise RuntimeError(f"Unexpected package entries: {names}")
        if archive.read("manifest.json") != expected_manifest:
            raise RuntimeError("Manifest changed while writing the package.")
        digest = hashlib.sha256()
        size = 0
        with archive.open("components/ranker.gguf", "r") as model:
            while chunk := model.read(CHUNK_SIZE):
                digest.update(chunk)
                size += len(chunk)
        if digest.hexdigest() != expected_model_hash or size != expected_model_size:
            raise RuntimeError("Packaged model does not match the input GGUF.")


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    try:
        validate_args(args)
        model_hash, model_size = hash_and_size(args.model)
        manifest = create_manifest(args, model_hash, model_size)
        manifest_bytes = canonical_json_bytes(manifest)
        write_package(args.output, manifest_bytes, args.model)
        verify_package(args.output, manifest_bytes, model_hash, model_size)
    except (OSError, ValueError, RuntimeError, zipfile.BadZipFile) as exception:
        print(f"error: {exception}", file=sys.stderr)
        return 2

    print(f"Created {args.output}")
    print(f"Model SHA-256: {model_hash}")
    print(f"Model bytes: {model_size}")
    print(f"Runtime: {RUNTIME_ID} API {RUNTIME_API_VERSION}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
