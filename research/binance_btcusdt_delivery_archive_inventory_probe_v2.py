#!/usr/bin/env python3
"""Run the corrected through-expiry BTCUSDT delivery metadata gate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Callable

try:
    from research import binance_btcusdt_delivery_archive_inventory_probe_v1 as v1
except ModuleNotFoundError:  # Direct script launch from the research directory.
    import binance_btcusdt_delivery_archive_inventory_probe_v1 as v1


REPO_ROOT = v1.REPO_ROOT
FUTURES_KLINE_ROOT = v1.FUTURES_KLINE_ROOT
FUTURES_MARK_ROOT = v1.FUTURES_MARK_ROOT
SPOT_PREFIX = v1.SPOT_PREFIX
INDEX_PREFIX = v1.INDEX_PREFIX
InventoryReject = v1.InventoryReject


def load_spec(value: str) -> tuple[dict[str, Any], Path, str]:
    path = v1.repo_file(value)
    raw = path.read_bytes()
    try:
        spec = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise InventoryReject("SPEC_REJECT:JSON") from error
    if (
        spec.get("document_type")
        != "BTC_BINANCE_FIXED_MATURITY_DELIVERY_SOURCE_INVENTORY_SPEC_V2"
    ):
        raise InventoryReject("SPEC_REJECT:DOCUMENT_TYPE")
    expected_probe = spec.get("source_bindings", {}).get("probe_sha256")
    actual_probe = v1.bucket.sha256(Path(__file__).resolve().read_bytes())
    if expected_probe != actual_probe:
        raise InventoryReject("SPEC_REJECT:PROBE_SHA256")
    return spec, path, v1.bucket.sha256(raw)


def build_inventory(
    spec: dict[str, Any],
    fetcher: Callable[[dict[str, str]], bytes] = v1.bucket.fetch,
) -> dict[str, Any]:
    selection = spec["selection_boundary"]
    expected = list(selection["exact_contract_symbols"])
    if expected != sorted(expected) or len(expected) != len(set(expected)):
        raise InventoryReject("SPEC_REJECT:EXPECTED_CONTRACT_ORDER_OR_DUPLICATE")
    if any(
        v1.contract_expiry(symbol).strftime("%Y-%m-%d")
        > selection["cutoff_date"]
        for symbol in expected
    ):
        raise InventoryReject("SPEC_REJECT:CONTRACT_AFTER_CUTOFF")

    roots: dict[str, dict[str, Any]] = {}
    for label, root in (
        ("contract_klines", FUTURES_KLINE_ROOT),
        ("mark_price_klines", FUTURES_MARK_ROOT),
    ):
        prefixes, pages = v1.enumerate_prefixes(root, fetcher)
        symbols = v1.contract_symbols(prefixes, root)
        selected = [symbol for symbol in symbols if symbol in expected]
        if selected != expected:
            raise InventoryReject(
                f"SOURCE_REJECT:EXPECTED_CONTRACT_SET:{label}:{','.join(selected)}"
            )
        roots[label] = {
            "root": root,
            "pages": pages,
            "all_delivery_contract_count": len(symbols),
            "selected_contract_count": len(selected),
        }

    contracts: list[dict[str, Any]] = []
    for symbol in expected:
        expiry = v1.contract_expiry(symbol)
        expiry_month = expiry.strftime("%Y-%m")
        trade = v1.archive_inventory(
            prefix=f"{FUTURES_KLINE_ROOT}{symbol}/{v1.INTERVAL}/",
            stem=symbol,
            fetcher=fetcher,
        )
        mark = v1.archive_inventory(
            prefix=f"{FUTURES_MARK_ROOT}{symbol}/{v1.INTERVAL}/",
            stem=symbol,
            fetcher=fetcher,
        )
        if expiry_month not in trade["months"] or expiry_month not in mark["months"]:
            raise InventoryReject(f"SOURCE_REJECT:EXPIRY_MONTH_ABSENT:{symbol}")
        pre_expiry_intersection = sorted(
            month
            for month in set(trade["months"]) & set(mark["months"])
            if month <= expiry_month
        )
        if (
            not pre_expiry_intersection
            or pre_expiry_intersection[-1] != expiry_month
            or pre_expiry_intersection
            != v1.month_range(pre_expiry_intersection[0], expiry_month)
        ):
            raise InventoryReject(
                f"SOURCE_REJECT:CONTRACT_MARK_PRE_EXPIRY_INTERSECTION:{symbol}"
            )
        contracts.append(
            {
                "symbol": symbol,
                "expiry_code_date": expiry.strftime("%Y-%m-%d"),
                "expiry_month_present_in_both_archives": True,
                "contract_klines": trade,
                "mark_price_klines": mark,
                "eligible_pre_expiry_overlap_first_month": pre_expiry_intersection[0],
                "eligible_pre_expiry_overlap_last_month": pre_expiry_intersection[-1],
                "eligible_pre_expiry_overlap_month_count": len(
                    pre_expiry_intersection
                ),
                "post_expiry_contract_archive_month_count": len(
                    [month for month in trade["months"] if month > expiry_month]
                ),
                "post_expiry_mark_archive_month_count": len(
                    [month for month in mark["months"] if month > expiry_month]
                ),
                "post_expiry_archive_values_eligible": False,
            }
        )

    references = {
        "spot_btcusdt": v1.reference_inventory(
            prefix=SPOT_PREFIX,
            stem="BTCUSDT",
            required_first=selection["reference_first_month"],
            required_last=selection["reference_last_month"],
            fetcher=fetcher,
        ),
        "index_btcusdt": v1.reference_inventory(
            prefix=INDEX_PREFIX,
            stem="BTCUSDT",
            required_first=selection["reference_first_month"],
            required_last=selection["reference_last_month"],
            fetcher=fetcher,
        ),
    }
    return {
        "root_inventories": roots,
        "contracts": contracts,
        "reference_archives": references,
        "exact_contract_count": len(contracts),
        "all_required_archive_checksum_pairs_present": True,
        "expiry_month_present_for_every_contract_and_mark_archive": True,
        "post_expiry_archive_objects_excluded_from_future_value_access": True,
        "market_data_rows_opened": False,
        "price_or_basis_values_opened": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    spec, spec_path, spec_sha256 = load_spec(args.spec)
    output = v1.bucket.state_path(args.output)
    inventory = build_inventory(spec)
    report = {
        "schema_version": "1",
        "document_type": "BTC_BINANCE_FIXED_MATURITY_DELIVERY_ARCHIVE_INVENTORY_V2",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SOURCE_METADATA_GATE_PASS_VALUE_ACCESS_STILL_DENIED",
        "family_id": spec["family_id"],
        "spec": {
            "path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": spec_sha256,
        },
        "request_contract": {
            "method": "GET",
            "bucket_url": v1.bucket.BUCKET_URL,
            "operation": "S3_LIST_OBJECTS_V1_METADATA_ONLY",
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes_per_request": v1.bucket.MAX_RESPONSE_BYTES,
            "archive_zip_body_downloaded": False,
            "checksum_body_downloaded": False,
            "delivery_price_endpoint_called": False,
            "current_exchange_info_called": False,
        },
        "inventory": inventory,
        "implementation_erratum": {
            "path": "research_pipeline/examples/btc-binance-fixed-maturity-delivery-source-inventory.v1.decision.json",
            "sha256": spec["source_bindings"]["v1_decision_sha256"],
            "scientific_gate_changed": False,
        },
        "decision": "FREEZE_VALUE_ACCESS_AND_LEDGER_CONTRACT_BEFORE_ANY_ZIP_OR_SETTLEMENT_RESPONSE",
        "missing_proof": [
            "Object-name and checksum-sidecar coverage does not prove row continuity, payload checksum validity or correct timestamp units.",
            "Post-expiry archive object presence is explicitly ineligible and no object body was opened to classify it.",
            "Historical listing time, contract specification, delivery-price response coverage and settlement reconciliation remain unproven.",
            "No contract-selection rule, roll clock, maturity band, collateral policy, costs, ledger, hypothesis, manifest, runner, PnL, drawdown, candidate or OOS exists.",
        ],
        "scope_note": "S3 object metadata only. No ZIP body, CHECKSUM body, market-data row, price, basis, delivery-price response, return, PnL, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    raw = v1.bucket.canonical_bytes(report)
    v1.bucket.write_create_once(output, raw)
    print(
        json.dumps(
            {
                "status": report["status"],
                "output": output.relative_to(REPO_ROOT).as_posix(),
                "sha256": v1.bucket.sha256(raw),
                "exact_contract_count": inventory["exact_contract_count"],
                "market_data_rows_opened": inventory["market_data_rows_opened"],
                "price_or_basis_values_opened": inventory[
                    "price_or_basis_values_opened"
                ],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
