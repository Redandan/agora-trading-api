from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from research_pipeline.storage import (
    atomic_write_json,
    atomic_write_text,
    resolve_store_reference,
    store_relative_reference,
)


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


class StoreReferenceTest(unittest.TestCase):
    def test_posix_and_legacy_backslash_references_resolve_identically(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "evidence-triggers" / "trigger" / "reviews" / "001.json"
            artifact.parent.mkdir(parents=True)
            artifact.write_text("{}\n", encoding="utf-8")

            self.assertEqual(
                resolve_store_reference(
                    root, "evidence-triggers/trigger/reviews/001.json"
                ),
                artifact.resolve(),
            )
            self.assertEqual(
                resolve_store_reference(
                    root, r"evidence-triggers\trigger\reviews\001.json"
                ),
                artifact.resolve(),
            )

    def test_writer_always_emits_posix_store_reference(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "experiments" / "portable" / "artifacts" / "result.json"

            reference = store_relative_reference(root, artifact)

            self.assertEqual(
                reference, "experiments/portable/artifacts/result.json"
            )
            self.assertNotIn("\\", reference)

    def test_resolver_rejects_unsafe_or_ambiguous_references(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            unsafe = (
                None,
                1,
                "",
                "/absolute.json",
                r"\absolute.json",
                r"C:\state\artifact.json",
                "C:/state/artifact.json",
                "folder:name/artifact.json",
                "./artifact.json",
                "folder/./artifact.json",
                "../artifact.json",
                "folder/../artifact.json",
                "folder//artifact.json",
                "folder/artifact.json/",
                r"\\server\share\artifact.json",
                r"folder\child/artifact.json",
                "folder/child\\artifact.json",
                "folder/\x00artifact.json",
                "folder/\x1fartifact.json",
                "folder/\x7fartifact.json",
            )
            for reference in unsafe:
                with self.subTest(reference=repr(reference)):
                    with self.assertRaises(ValueError):
                        resolve_store_reference(root, reference)

    def test_writer_and_resolver_reject_paths_outside_store(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            parent = Path(directory)
            root = parent / "state"
            root.mkdir()
            outside = parent / "outside.json"
            outside.write_text("{}\n", encoding="utf-8")

            with self.assertRaisesRegex(ValueError, "escapes research state"):
                store_relative_reference(root, outside)
            with self.assertRaises(ValueError):
                resolve_store_reference(root, "../outside.json")


if __name__ == "__main__":
    unittest.main()
