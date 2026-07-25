#!/usr/bin/env python3
"""Windows-safe entry point for packaging an unchanged GGUF ranker.

This imports the reference implementation from package-gguf-ranker.py and replaces
only its temporary-file writer. The split is temporary until the reference file can
be folded into the future Model Studio core library.
"""

from __future__ import annotations

import importlib.util
import os
import sys
import tempfile
import zipfile
from pathlib import Path
from types import ModuleType


def load_reference_module() -> ModuleType:
    module_path = Path(__file__).with_name("package-gguf-ranker.py")
    spec = importlib.util.spec_from_file_location("futo_package_gguf_ranker", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"Could not load reference packager: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def install_windows_safe_writer(module: ModuleType) -> None:
    def write_package(output: Path, manifest_bytes: bytes, model: Path) -> None:
        output.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
        )
        os.close(descriptor)
        temporary = Path(temporary_name)
        try:
            with zipfile.ZipFile(temporary, "w", allowZip64=True) as archive:
                archive.writestr(
                    module.deterministic_zip_info("manifest.json", zipfile.ZIP_DEFLATED),
                    manifest_bytes,
                    compresslevel=9,
                )
                model_info = module.deterministic_zip_info(
                    "components/ranker.gguf",
                    zipfile.ZIP_STORED,
                )
                with model.open("rb") as source, archive.open(
                    model_info,
                    "w",
                    force_zip64=True,
                ) as target:
                    module.copy_stream(source, target)
            temporary.replace(output)
        except Exception:
            temporary.unlink(missing_ok=True)
            raise

    module.write_package = write_package


def main(argv: list[str] | None = None) -> int:
    module = load_reference_module()
    install_windows_safe_writer(module)
    return module.main(sys.argv[1:] if argv is None else argv)


if __name__ == "__main__":
    raise SystemExit(main())
