#!/usr/bin/env python3
"""Verify an exact two-symbol Binance Spot archive inventory without value access."""

from __future__ import annotations

import argparse
from datetime import date
import json
from pathlib import Path
import re
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
EXPECTED_DOCUMENT_TYPE = "BINANCE_EXACT_SPOT_PAIR_ARCHIVE_INVENTORY_SPEC_V1"
SELF_PATH = REPO_ROOT / "research/binance_exact_spot_pair_archive_inventory_probe_v1.py"
SYMBOL = re.compile(r"^[A-Z0-9]{3,20}$")


def sha256_file(path: Path) -> str:
    return sha256(path.read_bytes())


def month_sequence(first: str, last: str) -> list[str]:
    try:
        first_date = date.fromisoformat(f"{first}-01")
        last_date = date.fromisoformat(f"{last}-01")
    except ValueError as error:
        raise SourceReject("SPEC_REJECT:MONTH_GRAMMAR") from error
    if first_date > last_date:
        raise SourceReject("SPEC_REJECT:MONTH_RANGE")
    year, month = first_date.year, first_date.month
    values: list[str] = []
    while (year, month) <= (last_date.year, last_date.month):
        values.append(f"{year:04d}-{month:02d}")
        if month == 12:
            year += 1
            month = 1
        else:
            month += 1
    return values


def load_spec(path: Path) -> dict[str, Any]:
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:JSON") from error
    if document.get("document_type") != EXPECTED_DOCUMENT_TYPE:
        raise SourceReject("SPEC_REJECT:DOCUMENT_TYPE")
    if not isinstance(document.get("family_id"), str):
        raise SourceReject("SPEC_REJECT:FAMILY_ID")
    source = document.get("source_contract", {})
    symbols = source.get("required_symbols")
    if (
        not isinstance(symbols, list)
        or len(symbols) != 2
        or len(set(symbols)) != 2
        or any(not isinstance(symbol, str) or SYMBOL.fullmatch(symbol) is None for symbol in symbols)
    ):
        raise SourceReject("SPEC_REJECT:SYMBOLS")
    if (
        source.get("root_prefix") != ROOT_PREFIX
        or source.get("interval") != "1d"
        or source.get("credentials") != "DENY"
        or source.get("redirect") != "DENY"
        or source.get("automatic_retry") != "DENY"
        or source.get("zip_body_access") != "DENY"
        or source.get("checksum_body_access") != "DENY"
        or source.get("price_value_access") != "DENY"
    ):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    months = month_sequence(
        str(source.get("required_first_month")),
        str(source.get("required_last_month")),
    )
    if source.get("required_month_count_per_symbol") != len(months):
        raise SourceReject("SPEC_REJECT:MONTH_COUNT")
    bindings = document.get("source_bindings", {})
    if bindings.get("probe_sha256") != sha256_file(SELF_PATH):
        raise SourceReject("SPEC_REJECT:PROBE_SHA256")
    prior_path = REPO_ROOT / str(bindings.get("primary_prior_path", ""))
    if (
        not prior_path.is_file()
        or bindings.get("primary_prior_sha256") != sha256_file(prior_path)
    ):
        raise SourceReject("SPEC_REJECT:PRIMARY_PRIOR_SHA256")
    return document


def inspect_symbol(
    symbol: str,
    *,
    required_months: list[str],
    fetcher: Callable[[dict[str, str]], bytes] = fetch,
) -> dict[str, Any]:
    prefix = f"{ROOT_PREFIX}{symbol}/1d/"
    raw = fetcher({"prefix": prefix, "max-keys": str(MAX_KEYS)})
    page = parse_bucket_page(raw, expected_prefix=prefix, expected_marker="")
    if page["is_truncated"]:
        raise SourceReject(f"SOURCE_REJECT:ARCHIVE_PAGE_TRUNCATED:{symbol}")
    zip_keys = [key for key in page["keys"] if key.endswith(".zip")]
    checksum_keys = {key for key in page["keys"] if key.endswith(".zip.CHECKSUM")}
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
    source = spec["source_contract"]
    months = month_sequence(
        source["required_first_month"], source["required_last_month"]
    )
    symbols = [
        inspect_symbol(symbol, required_months=months, fetcher=fetcher)
        for symbol in source["required_symbols"]
    ]
    return {
        "schema_version": "1",
        "document_type": "BINANCE_EXACT_SPOT_PAIR_ARCHIVE_INVENTORY_RESULT_V1",
        "family_id": spec["family_id"],
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SOURCE_METADATA_GATE_PASS_VALUE_ACCESS_STILL_DENIED",
        "source_bindings": {
            "spec_canonical_sha256": sha256(canonical_bytes(spec)),
            "probe_sha256": sha256_file(SELF_PATH),
            "primary_prior_sha256": spec["source_bindings"]["primary_prior_sha256"],
        },
        "inventory": {
            "required_first_month": months[0],
            "required_last_month": months[-1],
            "required_month_count_per_symbol": len(months),
            "symbols": symbols,
            "common_required_zip_count": len(months) * len(symbols),
            "common_required_checksum_count": len(months) * len(symbols),
        },
        "claim_boundary": "Every frozen monthly ZIP and sibling checksum object exists for both exact symbols. This proves metadata feasibility only, not row integrity, continuity, signal events, prediction or economic value.",
        "next_action": spec["next_action_on_pass"],
        "scope_note": "S3 object metadata only. No ZIP body, CHECKSUM body, kline row, price, volume, return, PnL, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    output = state_path(args.output)
    spec = load_spec(Path(args.spec).resolve())
    report = build_report(spec)
    raw = canonical_bytes(report)
    write_create_once(output, raw)
    print(
        json.dumps(
            {
                "status": report["status"],
                "family_id": report["family_id"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(raw),
                "required_month_count_per_symbol": report["inventory"][
                    "required_month_count_per_symbol"
                ],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
