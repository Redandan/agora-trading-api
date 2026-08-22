#!/usr/bin/env python3
"""Verify BTCUSDT/PAXGUSDT daily archive metadata without opening price files."""

from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path
from typing import Any, Callable

from research.binance_spot_usdt_archive_inventory_probe import (
    MAX_KEYS,
    MONTHLY_ZIP,
    ROOT_PREFIX,
    SourceReject,
    canonical_bytes,
    fetch,
    parse_bucket_page,
    sha256,
    state_path,
    write_create_once,
)


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SPEC = REPO_ROOT / (
    "research_pipeline/examples/"
    "btc-paxg-monthly-relative-momentum-rotation-source-inventory.v1.spec.json"
)
EXPECTED_DOCUMENT_TYPE = (
    "BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_ROTATION_SOURCE_INVENTORY_SPEC_V1"
)
EXPECTED_FAMILY_ID = "btc-paxg-monthly-relative-momentum-rotation"
EXPECTED_SYMBOLS = ("BTCUSDT", "PAXGUSDT")
EXPECTED_FIRST_MONTH = "2020-09"
EXPECTED_LAST_MONTH = "2024-12"


def sha256_file(path: Path) -> str:
    return sha256(path.read_bytes())


def month_sequence(first: str, last: str) -> list[str]:
    first_date = date.fromisoformat(f"{first}-01")
    last_date = date.fromisoformat(f"{last}-01")
    if first_date > last_date:
        raise SourceReject("SPEC_REJECT:MONTH_RANGE")
    current_year = first_date.year
    current_month = first_date.month
    months: list[str] = []
    while (current_year, current_month) <= (last_date.year, last_date.month):
        months.append(f"{current_year:04d}-{current_month:02d}")
        if current_month == 12:
            current_year += 1
            current_month = 1
        else:
            current_month += 1
    return months


def load_spec(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:JSON") from error
    if document.get("document_type") != EXPECTED_DOCUMENT_TYPE:
        raise SourceReject("SPEC_REJECT:DOCUMENT_TYPE")
    if document.get("family_id") != EXPECTED_FAMILY_ID:
        raise SourceReject("SPEC_REJECT:FAMILY_ID")
    source = document.get("source_contract", {})
    if (
        source.get("root_prefix") != ROOT_PREFIX
        or tuple(source.get("required_symbols", [])) != EXPECTED_SYMBOLS
        or source.get("interval") != "1d"
        or source.get("required_first_month") != EXPECTED_FIRST_MONTH
        or source.get("required_last_month") != EXPECTED_LAST_MONTH
        or source.get("credentials") != "DENY"
        or source.get("redirect") != "DENY"
        or source.get("automatic_retry") != "DENY"
        or source.get("zip_body_access") != "DENY"
        or source.get("checksum_body_access") != "DENY"
        or source.get("price_value_access") != "DENY"
    ):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    expected_months = month_sequence(EXPECTED_FIRST_MONTH, EXPECTED_LAST_MONTH)
    if source.get("required_month_count_per_symbol") != len(expected_months):
        raise SourceReject("SPEC_REJECT:MONTH_COUNT")
    bindings = document.get("source_bindings", {})
    probe_path = REPO_ROOT / "research/binance_btc_paxg_archive_inventory_probe_v1.py"
    prior_path = REPO_ROOT / (
        "research_pipeline/examples/"
        "btc-paxg-monthly-relative-momentum-rotation-primary-prior.v1.json"
    )
    if bindings.get("probe_sha256") != sha256_file(probe_path):
        raise SourceReject("SPEC_REJECT:PROBE_SHA256")
    if bindings.get("primary_prior_sha256") != sha256_file(prior_path):
        raise SourceReject("SPEC_REJECT:PRIMARY_PRIOR_SHA256")
    return document


def inspect_symbol(
    symbol: str,
    *,
    required_months: list[str],
    fetcher: Callable[[dict[str, str]], bytes] = fetch,
) -> dict[str, Any]:
    if symbol not in EXPECTED_SYMBOLS:
        raise SourceReject(f"SPEC_REJECT:SYMBOL:{symbol}")
    prefix = f"{ROOT_PREFIX}{symbol}/1d/"
    raw = fetcher({"prefix": prefix, "max-keys": str(MAX_KEYS)})
    page = parse_bucket_page(raw, expected_prefix=prefix, expected_marker="")
    if page["is_truncated"]:
        raise SourceReject(f"SOURCE_REJECT:ARCHIVE_PAGE_TRUNCATED:{symbol}")
    keys = page["keys"]
    zip_keys = [key for key in keys if key.endswith(".zip")]
    checksum_keys = {key for key in keys if key.endswith(".zip.CHECKSUM")}
    months: list[str] = []
    for key in zip_keys:
        match = MONTHLY_ZIP.fullmatch(key)
        if match is None or match.group("symbol") != symbol:
            raise SourceReject(f"SOURCE_REJECT:ARCHIVE_KEY:{symbol}:{key}")
        if f"{key}.CHECKSUM" not in checksum_keys:
            raise SourceReject(f"SOURCE_REJECT:MISSING_CHECKSUM:{symbol}:{key}")
        months.append(match.group("month"))
    if not months or months != sorted(months) or len(months) != len(set(months)):
        raise SourceReject(f"SOURCE_REJECT:ARCHIVE_MONTHS:{symbol}")
    if len(checksum_keys) != len(zip_keys):
        raise SourceReject(f"SOURCE_REJECT:ORPHAN_CHECKSUM:{symbol}")
    missing = sorted(set(required_months) - set(months))
    if missing:
        raise SourceReject(f"SOURCE_REJECT:REQUIRED_MONTHS:{symbol}:{','.join(missing)}")
    required_bytes = ("\n".join(required_months) + "\n").encode("ascii")
    return {
        "symbol": symbol,
        "all_monthly_archive_count": len(months),
        "all_checksum_count": len(checksum_keys),
        "all_first_month": months[0],
        "all_last_month": months[-1],
        "required_first_month": required_months[0],
        "required_last_month": required_months[-1],
        "required_month_count": len(required_months),
        "required_months_sha256": sha256(required_bytes),
        "required_zip_and_checksum_objects_present": True,
        "listing_raw_bytes": page["raw_bytes"],
        "listing_raw_sha256": page["raw_sha256"],
    }


def build_report(
    spec: dict[str, Any],
    *,
    fetcher: Callable[[dict[str, str]], bytes] = fetch,
) -> dict[str, Any]:
    required_months = month_sequence(EXPECTED_FIRST_MONTH, EXPECTED_LAST_MONTH)
    symbols = [
        inspect_symbol(symbol, required_months=required_months, fetcher=fetcher)
        for symbol in EXPECTED_SYMBOLS
    ]
    return {
        "schema_version": "1",
        "document_type": "BTC_PAXG_MONTHLY_RELATIVE_MOMENTUM_ROTATION_SOURCE_INVENTORY_RESULT_V1",
        "family_id": EXPECTED_FAMILY_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SOURCE_METADATA_GATE_PASS_PRICE_ACCESS_STILL_DENIED",
        "source_bindings": {
            "spec_canonical_sha256": sha256(canonical_bytes(spec)),
            "probe_sha256": sha256_file(
                REPO_ROOT / "research/binance_btc_paxg_archive_inventory_probe_v1.py"
            ),
            "primary_prior_sha256": spec["source_bindings"]["primary_prior_sha256"],
        },
        "inventory": {
            "required_first_month": EXPECTED_FIRST_MONTH,
            "required_last_month": EXPECTED_LAST_MONTH,
            "required_month_count_per_symbol": len(required_months),
            "symbols": symbols,
            "common_required_zip_count": len(required_months) * len(EXPECTED_SYMBOLS),
            "common_required_checksum_count": len(required_months) * len(EXPECTED_SYMBOLS),
        },
        "feasibility_claim": "The official public bucket metadata exposes every frozen BTCUSDT and PAXGUSDT monthly 1d ZIP plus sibling checksum from 2020-09 through 2024-12. This supports a later checksum-bound corpus gate only; it does not prove row completeness, liquidity, tracking, momentum or economic value.",
        "remaining_gates": [
            "Freeze exact Design and Validation windows, full checksum verification, complete daily row lattice and PAXG tracking diagnostics before price access.",
            "Freeze one six-month formation calculation, next-month execution clock, static 50/50 comparator, fees, slippage, turnover, drawdown, breadth, concentration and terminal-inventory gates.",
            "Run the checksum-bound historical audit twice and close the family on any frozen source or economic failure before independent OOS.",
        ],
        "scope_note": "S3 object metadata only. No ZIP body, CHECKSUM body, kline row, price, volume, return, PnL, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", default=str(DEFAULT_SPEC))
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    spec_path = Path(args.spec).resolve()
    output = state_path(args.output)
    spec = load_spec(spec_path)
    report = build_report(spec)
    raw = canonical_bytes(report)
    write_create_once(output, raw)
    print(
        json.dumps(
            {
                "status": report["status"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(raw),
                "required_month_count_per_symbol": report["inventory"][
                    "required_month_count_per_symbol"
                ],
                "common_required_zip_count": report["inventory"][
                    "common_required_zip_count"
                ],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
