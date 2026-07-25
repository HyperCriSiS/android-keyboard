from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


class PortableGgufRankerPackagerTest(unittest.TestCase):
    def test_package_preserves_model_and_writes_runtime_binding(self) -> None:
        tool = Path(__file__).with_name("wrap-gguf-ranker.py")
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            model = temporary / "model.gguf"
            output = temporary / "german.futolanguage"
            model_bytes = b"GGUF" + bytes(range(256)) * 8
            model.write_bytes(model_bytes)

            process = subprocess.run(
                [
                    sys.executable,
                    str(tool),
                    str(model),
                    str(output),
                    "--package-id",
                    "org.futo.test.german.ranker",
                    "--package-name",
                    "German Test Ranker",
                    "--package-version",
                    "1.0.0",
                    "--component-version",
                    "1.2.0",
                    "--author",
                    "FUTO Test",
                    "--license",
                    "Apache-2.0",
                    "--language",
                    "de",
                    "--language",
                    "de-DE",
                    "--layout",
                    "qwertz",
                    "--boundary-mode",
                    "leading-separator",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(0, process.returncode, process.stderr)
            self.assertTrue(output.is_file())
            with zipfile.ZipFile(output, "r") as archive:
                self.assertEqual(
                    ["manifest.json", "components/ranker.gguf"],
                    archive.namelist(),
                )
                self.assertEqual(model_bytes, archive.read("components/ranker.gguf"))
                manifest = json.loads(archive.read("manifest.json"))

            component = manifest["components"][0]
            self.assertEqual("context-ranker", component["kind"])
            self.assertEqual("candidate-ranking-v1", component["tasks"][0])
            self.assertEqual("gguf-causal-ranker", component["runtime"]["id"])
            self.assertEqual(1, component["runtime"]["apiVersion"])
            self.assertEqual(
                hashlib.sha256(model_bytes).hexdigest(),
                component["payload"]["sha256"],
            )
            self.assertEqual(len(model_bytes), component["payload"]["sizeBytes"])

    def test_invalid_configuration_does_not_create_output(self) -> None:
        tool = Path(__file__).with_name("wrap-gguf-ranker.py")
        with tempfile.TemporaryDirectory() as temporary_directory:
            temporary = Path(temporary_directory)
            model = temporary / "model.gguf"
            output = temporary / "invalid.futolanguage"
            model.write_bytes(b"GGUF")

            process = subprocess.run(
                [
                    sys.executable,
                    str(tool),
                    str(model),
                    str(output),
                    "--package-id",
                    "Invalid Package ID",
                    "--package-name",
                    "Invalid",
                    "--author",
                    "Test",
                    "--license",
                    "Apache-2.0",
                    "--language",
                    "de",
                    "--boundary-mode",
                    "leading-separator",
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual(2, process.returncode)
            self.assertFalse(output.exists())
            self.assertIn("package-id", process.stderr)


if __name__ == "__main__":
    unittest.main()
