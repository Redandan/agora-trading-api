from __future__ import annotations

import hashlib
from pathlib import Path
from typing import Any


SELECTION_CORPUS_ID = "okx-btcusdt-h1-selection-pre2025-v1"
SELECTION_CORPUS_RELATIVE_PATH = Path("java-parity") / "selection-2019-2024.tsv"
SELECTION_CORPUS_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
SELECTION_CORPUS_ROWS = 52_608


def selection_corpus_status(state_root: Path) -> dict[str, Any]:
    path = state_root / SELECTION_CORPUS_RELATIVE_PATH
    base = {
        "corpus_id": SELECTION_CORPUS_ID,
        "relative_path": SELECTION_CORPUS_RELATIVE_PATH.as_posix(),
        "expected_sha256": SELECTION_CORPUS_SHA256,
        "expected_rows": SELECTION_CORPUS_ROWS,
    }
    if not path.is_file():
        return {"status": "MISSING", **base}

    digest = hashlib.sha256()
    row_count = 0
    try:
        with path.open("rb") as stream:
            for line in stream:
                digest.update(line)
                row_count += 1
    except OSError as error:
        return {
            "status": "READ_FAILED",
            **base,
            "reason": type(error).__name__,
        }

    actual_hash = digest.hexdigest()
    result = {
        **base,
        "sha256": actual_hash,
        "rows": row_count,
    }
    if actual_hash != SELECTION_CORPUS_SHA256:
        return {"status": "HASH_MISMATCH", **result}
    if row_count != SELECTION_CORPUS_ROWS:
        return {"status": "ROW_COUNT_MISMATCH", **result}
    return {"status": "READY", **result}
