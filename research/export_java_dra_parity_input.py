#!/usr/bin/env python3
"""Export the frozen canonical OKX input for the offline Java DRA parity CLI."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import btc_dra_reversal_confirmed_exit_v2c as base


EXPECTED_ROWS = 52_608
EXPECTED_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"


def export(output: Path) -> dict:
    if output.exists():
        raise base.ResearchReject("OUTPUT_SEAL_REJECT", str(output))
    bars = base.parse_rows(base.fetch_rows(base.SELECTION_CUTOFF))
    selection = [bar for bar in bars if bar.close_time <= base.SELECTION_CUTOFF]
    canonical = "".join(bar.canonical() + "\n" for bar in selection)
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    if len(selection) != EXPECTED_ROWS or digest != EXPECTED_SHA256:
        raise base.ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": EXPECTED_ROWS,
                "actual_rows": len(selection),
                "expected_sha256": EXPECTED_SHA256,
                "actual_sha256": digest,
            },
        )
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(canonical, encoding="utf-8", newline="\n")
    return {
        "status": "EXPORTED",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "output": str(output.resolve()),
        "rows": len(selection),
        "sha256": digest,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = export(args.output)
    except base.ResearchReject as error:
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
