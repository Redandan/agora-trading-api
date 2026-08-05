from __future__ import annotations

import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from research_pipeline import corpus


class CanonicalCorpusContractTest(unittest.TestCase):
    def test_selection_corpus_requires_exact_rows_and_hash(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            relative = Path("sealed") / "selection.tsv"
            path = root / relative
            path.parent.mkdir(parents=True)
            payload = b"row-1\nrow-2\n"
            path.write_bytes(payload)
            with (
                patch.object(corpus, "SELECTION_CORPUS_RELATIVE_PATH", relative),
                patch.object(
                    corpus,
                    "SELECTION_CORPUS_SHA256",
                    hashlib.sha256(payload).hexdigest(),
                ),
                patch.object(corpus, "SELECTION_CORPUS_ROWS", 2),
            ):
                ready = corpus.selection_corpus_status(root)
                self.assertEqual(ready["status"], "READY")
                self.assertEqual(ready["rows"], 2)

                path.write_bytes(payload + b"row-3\n")
                changed = corpus.selection_corpus_status(root)
                self.assertEqual(changed["status"], "HASH_MISMATCH")

    def test_missing_selection_corpus_fails_closed_without_absolute_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = corpus.selection_corpus_status(root)
        self.assertEqual(result["status"], "MISSING")
        self.assertNotIn(str(root), str(result))


if __name__ == "__main__":
    unittest.main()
