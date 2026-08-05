from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from research_pipeline.storage import atomic_write_json, atomic_write_text


class DeterministicArtifactWriterTest(unittest.TestCase):
    def test_json_bytes_are_lf_only_and_stable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            atomic_write_json(path, {"b": 2, "a": 1})

            self.assertEqual(path.read_bytes(), b'{\n  "a": 1,\n  "b": 2\n}\n')

    def test_report_bytes_are_lf_only_and_stable(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.md"
            atomic_write_text(path, "# Report\nEvidence.\n")

            self.assertEqual(path.read_bytes(), b"# Report\nEvidence.\n")


if __name__ == "__main__":
    unittest.main()
