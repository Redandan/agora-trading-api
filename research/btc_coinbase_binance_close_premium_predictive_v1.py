#!/usr/bin/env python3
"""Evaluate the frozen Coinbase/Binance BTC close-premium predictive gate."""

from __future__ import annotations

import argparse
import csv
from datetime import date, timedelta
from decimal import Decimal, getcontext
import gzip
import hashlib
import json
from pathlib import Path
from typing import Any

from research import btc_coinbase_binance_relative_volume_predictive_v1 as support
from research import btc_dra_reversal_confirmed_exit_v2c as h1_parser


getcontext().prec = 34
D = Decimal
ZERO = D("0")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_COINBASE_BINANCE_CLOSE_PREMIUM_PREDICTIVE_MANIFEST_V1"
EXPECTED_FAMILY_ID = "btc-coinbase-binance-close-premium-long-cash"
EXPECTED_CONTRACT = {
    "daily_premium": "COINBASE_BTCUSD_CLOSE_DIVIDED_BY_BINANCE_BTCUSDT_CLOSE_MINUS_ONE_SAME_COMPLETE_UTC_DAY",
    "state": "DAILY_PREMIUM_STRICTLY_GREATER_THAN_ZERO",
    "signal_lag": "ONE_COMPLETE_UTC_DAY",
    "outcome": "NEXT_COMPLETE_UTC_DAY_BTCUSDT_OPEN_TO_NEXT_OPEN_RETURN",
    "downside_semideviation": "SQUARE_ROOT_OF_MEAN_SQUARED_MINIMUM_RETURN_AND_ZERO_OVER_ALL_STATE_OBSERVATIONS",
    "design_outcome_first_day": "2020-01-02",
    "design_outcome_last_day": "2022-12-31",
    "validation_outcome_first_day": "2023-01-01",
    "validation_outcome_last_day": "2024-12-31",
    "annual_years": [2020, 2021, 2022, 2023, 2024],
    "minimum_state_count_per_window": 120,
    "minimum_positive_annual_delta_years": 3,
    "maximum_top_positive_annual_delta_concentration": "0.60",
    "variants": 1,
    "economics": "DENY_UNTIL_ALL_PREDICTIVE_GATES_PASS",
    "oos": "DENY",
}


class PredictiveReject(RuntimeError):
    pass


def sha256_path(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=True, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("ascii")


def q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def load_manifest(path: Path) -> dict[str, Any]:
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise PredictiveReject("MANIFEST_REJECT:JSON") from error
    if (
        manifest.get("document_type") != EXPECTED_DOCUMENT_TYPE
        or manifest.get("family_id") != EXPECTED_FAMILY_ID
        or manifest.get("authorization")
        != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("research_classification")
        != "HISTORICAL_PREDICTIVE_ONLY_NO_ECONOMICS_NO_OOS"
        or manifest.get("predictive_contract") != EXPECTED_CONTRACT
    ):
        raise PredictiveReject("MANIFEST_REJECT:CONTRACT")
    for binding in manifest.get("bindings", []):
        bound = REPO_ROOT / str(binding.get("path", ""))
        if not bound.is_file() or sha256_path(bound) != binding.get("sha256"):
            raise PredictiveReject(f"BINDING_REJECT:{binding.get('role')}")
    return manifest


def load_close_rows(path: Path) -> dict[str, dict[str, D]]:
    try:
        with gzip.open(path, mode="rt", encoding="ascii", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["venue", "symbol", "date", "close"]:
                raise PredictiveReject("DATA_REJECT:CLOSE_COLUMNS")
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as error:
        raise PredictiveReject("DATA_REJECT:CLOSE_GZIP") from error
    values: dict[str, dict[str, D]] = {}
    identities: set[tuple[str, str]] = set()
    for index, row in enumerate(rows):
        venue = row["venue"]
        symbol = row["symbol"]
        day = row["date"]
        if (venue, symbol) not in {
            ("COINBASE", "BTC-USD"),
            ("BINANCE", "BTCUSDT"),
        }:
            raise PredictiveReject(f"DATA_REJECT:CLOSE_IDENTITY:{index}")
        try:
            parsed_day = date.fromisoformat(day)
            close = D(row["close"])
        except Exception as error:
            raise PredictiveReject(f"DATA_REJECT:CLOSE_PARSE:{index}") from error
        if parsed_day.isoformat() != day or not close.is_finite() or close <= ZERO:
            raise PredictiveReject(f"DATA_REJECT:CLOSE_VALUE:{index}")
        identity = (venue, day)
        if identity in identities:
            raise PredictiveReject(f"DATA_REJECT:CLOSE_DUPLICATE:{identity}")
        identities.add(identity)
        values.setdefault(day, {})[venue] = close
    current = date(2020, 1, 1)
    last = date(2024, 12, 31)
    while current <= last:
        day = current.isoformat()
        if set(values.get(day, {})) != {"COINBASE", "BINANCE"}:
            raise PredictiveReject(f"DATA_REJECT:CLOSE_COMMON_DAY:{day}")
        current += timedelta(days=1)
    if len(values) != 1_827 or len(rows) != 3_654:
        raise PredictiveReject(
            f"DATA_REJECT:CLOSE_COUNTS:days={len(values)}:rows={len(rows)}"
        )
    return values


def calculate_states(
    closes: dict[str, dict[str, D]]
) -> tuple[dict[str, bool], list[dict[str, Any]]]:
    states: dict[str, bool] = {}
    diagnostics: list[dict[str, Any]] = []
    for day in sorted(closes):
        premium = closes[day]["COINBASE"] / closes[day]["BINANCE"] - D(1)
        state = premium > ZERO
        states[day] = state
        diagnostics.append(
            {"day": day, "premium": premium, "positive_state": state}
        )
    return states, diagnostics


def build_observations(
    states: dict[str, bool], opens: dict[str, D]
) -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    outcome_day = date(2020, 1, 2)
    last_day = date(2024, 12, 31)
    while outcome_day <= last_day:
        signal_day = (outcome_day - timedelta(days=1)).isoformat()
        next_day = (outcome_day + timedelta(days=1)).isoformat()
        day = outcome_day.isoformat()
        if signal_day not in states or day not in opens or next_day not in opens:
            raise PredictiveReject(f"DATA_REJECT:OUTCOME_ALIGNMENT:{day}")
        observations.append(
            {
                "outcome_day": day,
                "year": outcome_day.year,
                "high_state": states[signal_day],
                "return": opens[next_day] / opens[day] - D(1),
            }
        )
        outcome_day += timedelta(days=1)
    return observations


def build_result(
    manifest_path: Path, close_path: Path, h1_path: Path
) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    close_binding = manifest["data_bindings"]["close_corpus"]
    h1_binding = manifest["data_bindings"]["btc_h1_outcomes"]
    if sha256_path(close_path) != close_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:CLOSE_SHA256")
    if sha256_path(h1_path) != h1_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:H1_SHA256")
    closes = load_close_rows(close_path)
    try:
        bars = h1_parser.parse_rows(h1_path.read_text(encoding="utf-8"))
    except Exception as error:
        raise PredictiveReject("DATA_REJECT:H1_PARSE") from error
    if len(bars) != 52_608 or h1_parser.data_hash(bars) != h1_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:H1_ROWS_OR_CANONICAL_HASH")
    states, feature_rows = calculate_states(closes)
    observations = build_observations(states, support.daily_open_prices(bars))
    design_values = [
        value for value in observations if value["outcome_day"] <= "2022-12-31"
    ]
    validation_values = [
        value for value in observations if value["outcome_day"] >= "2023-01-01"
    ]
    design, design_raw = support.summarize(design_values)
    validation, validation_raw = support.summarize(validation_values)
    annual: dict[str, Any] = {}
    annual_raw: dict[str, dict[str, D]] = {}
    for year in range(2020, 2025):
        values = [value for value in observations if value["year"] == year]
        annual[str(year)], annual_raw[str(year)] = support.summarize(values)
    annual_deltas = {
        year: value["high_mean"] - value["low_mean"]
        for year, value in annual_raw.items()
    }
    positive_deltas = [value for value in annual_deltas.values() if value > ZERO]
    top_concentration = (
        max(positive_deltas) / sum(positive_deltas, ZERO)
        if positive_deltas
        else D(1)
    )
    minimum_count = int(EXPECTED_CONTRACT["minimum_state_count_per_window"])
    gates = {
        "DESIGN_BOTH_STATE_COUNTS_AT_LEAST_120": int(design_raw["high_count"]) >= minimum_count and int(design_raw["low_count"]) >= minimum_count,
        "VALIDATION_BOTH_STATE_COUNTS_AT_LEAST_120": int(validation_raw["high_count"]) >= minimum_count and int(validation_raw["low_count"]) >= minimum_count,
        "DESIGN_POSITIVE_MEAN_RETURN_EXCEEDS_NEGATIVE_OR_ZERO": design_raw["high_mean"] > design_raw["low_mean"],
        "VALIDATION_POSITIVE_MEAN_RETURN_EXCEEDS_NEGATIVE_OR_ZERO": validation_raw["high_mean"] > validation_raw["low_mean"],
        "VALIDATION_POSITIVE_DOWNSIDE_SEMIDEVIATION_NONWORSE": validation_raw["high_downside"] <= validation_raw["low_downside"],
        "POSITIVE_ANNUAL_DELTA_AT_LEAST_3_OF_5": sum(value > ZERO for value in annual_deltas.values()) >= 3,
        "TOP_POSITIVE_ANNUAL_DELTA_CONCENTRATION_AT_MOST_60_PERCENT": top_concentration <= D("0.60"),
    }
    failed = [name for name, passed in gates.items() if not passed]
    all_pass = not failed
    premiums = [row["premium"] for row in feature_rows]
    return {
        "schema_version": "1",
        "document_type": "BTC_COINBASE_BINANCE_CLOSE_PREMIUM_PREDICTIVE_RESULT_V1",
        "family_id": EXPECTED_FAMILY_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_GATE_PASS_ECONOMIC_PREREGISTRATION_REQUIRED" if all_pass else "NO_CANDIDATE_CLOSE_COINBASE_BINANCE_CLOSE_PREMIUM_PRE_ECONOMIC",
        "decision": "ADVANCE_TO_ONE_SEPARATELY_FROZEN_MATCHED_CAPITAL_ECONOMIC_SCREEN" if all_pass else "PERMANENTLY_CLOSE_EXACT_ZERO_THRESHOLD_CROSS_VENUE_CLOSE_PREMIUM_FAMILY_WITHOUT_TUNING_OR_REVERSE_MAPPING",
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256_path(manifest_path),
        },
        "data": {
            "close_corpus": {
                "path": close_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256_path(close_path),
                "rows": 3_654,
                "common_days": 1_827,
            },
            "btc_h1_outcomes": {
                "path": h1_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256_path(h1_path),
                "rows": len(bars),
            },
        },
        "policy": EXPECTED_CONTRACT,
        "feature_diagnostic": {
            "first_signal_day": feature_rows[0]["day"],
            "last_signal_day": feature_rows[-1]["day"],
            "state_day_count": len(feature_rows),
            "premium_minimum": q(min(premiums)),
            "premium_median": q(support.median(premiums)),
            "premium_maximum": q(max(premiums)),
            "positive_state_days": sum(row["positive_state"] for row in feature_rows),
            "negative_or_zero_state_days": sum(
                not row["positive_state"] for row in feature_rows
            ),
        },
        "windows": {"design": design, "validation": validation},
        "annual": annual,
        "annual_positive_minus_negative_or_zero_mean_return": {
            year: q(value) for year, value in annual_deltas.items()
        },
        "positive_annual_delta_year_count": sum(
            value > ZERO for value in annual_deltas.values()
        ),
        "top_positive_annual_delta_concentration": q(top_concentration),
        "gates": gates,
        "failed_gates": failed,
        "all_predictive_gates_pass": all_pass,
        "economics_opened": False,
        "oos_opened": False,
        "interpretation_risk": "The observed premium combines venue demand, segmentation, last-trade timing and USD-versus-USDT quote effects; predictive passage would not identify a pure U.S.-demand causal channel.",
        "claim_boundary": "Historical pre-2025 predictive evidence only. A pass authorizes one separately frozen matched-capital economic screen, not a candidate, OOS or Trading action.",
        "scope_note": "No matched-capital strategy ledger, fees, slippage, PnL, drawdown, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--close-corpus", required=True)
    parser.add_argument("--btc-h1", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    close_path = Path(args.close_corpus).resolve()
    h1_path = Path(args.btc_h1).resolve()
    output_path = Path(args.output).resolve()
    for path in (manifest_path, close_path, h1_path):
        if not path.is_relative_to(REPO_ROOT):
            raise PredictiveReject(f"PATH_REJECT:{path}")
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not output_path.is_relative_to(state_root) or output_path.exists():
        raise PredictiveReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_result(manifest_path, close_path, h1_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("xb") as stream:
        stream.write(canonical_bytes(result))
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256_path(output_path),
                "failed_gates": result["failed_gates"],
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
