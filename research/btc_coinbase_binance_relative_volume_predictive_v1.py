#!/usr/bin/env python3
"""Evaluate the frozen Coinbase/Binance BTC relative-volume predictive gate."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, timedelta
from decimal import Decimal, getcontext
import gzip
import hashlib
import json
from pathlib import Path
from typing import Any

from research import btc_dra_reversal_confirmed_exit_v2c as h1_parser


getcontext().prec = 34
D = Decimal
ZERO = D("0")
REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_COINBASE_BINANCE_RELATIVE_VOLUME_PREDICTIVE_MANIFEST_V1"
EXPECTED_FAMILY_ID = "btc-coinbase-binance-relative-volume-share-long-cash"
EXPECTED_CONTRACT = {
    "daily_share": "COINBASE_BASE_VOLUME_DIVIDED_BY_COINBASE_PLUS_BINANCE_BASE_VOLUME_SAME_COMPLETE_UTC_DAY",
    "smooth_days": 28,
    "reference_available_smoothed_days": 365,
    "state": "SMOOTHED_SHARE_STRICTLY_GREATER_THAN_PRIOR_REFERENCE_MEDIAN",
    "signal_lag": "ONE_COMPLETE_UTC_DAY",
    "outcome": "NEXT_COMPLETE_UTC_DAY_BTCUSDT_OPEN_TO_NEXT_OPEN_RETURN",
    "downside_semideviation": "SQUARE_ROOT_OF_MEAN_SQUARED_MINIMUM_RETURN_AND_ZERO_OVER_ALL_STATE_OBSERVATIONS",
    "design_outcome_first_day": "2021-02-01",
    "design_outcome_last_day": "2022-12-31",
    "validation_outcome_first_day": "2023-01-01",
    "validation_outcome_last_day": "2024-12-31",
    "annual_years": [2021, 2022, 2023, 2024],
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


def median(values: list[D]) -> D:
    if not values:
        raise PredictiveReject("DATA_REJECT:MEDIAN_EMPTY")
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / D(2)


def mean(values: list[D]) -> D:
    if not values:
        raise PredictiveReject("DATA_REJECT:MEAN_EMPTY")
    return sum(values, ZERO) / D(len(values))


def downside_semideviation(values: list[D]) -> D:
    if not values:
        raise PredictiveReject("DATA_REJECT:SEMIDEVIATION_EMPTY")
    mean_square = sum((min(value, ZERO) ** 2 for value in values), ZERO) / D(
        len(values)
    )
    return mean_square.sqrt()


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


def load_volume_rows(path: Path) -> dict[str, dict[str, D]]:
    try:
        with gzip.open(path, mode="rt", encoding="ascii", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != ["venue", "symbol", "date", "base_volume_btc"]:
                raise PredictiveReject("DATA_REJECT:VOLUME_COLUMNS")
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as error:
        raise PredictiveReject("DATA_REJECT:VOLUME_GZIP") from error
    values: dict[str, dict[str, D]] = {}
    identities: set[tuple[str, str]] = set()
    for index, row in enumerate(rows):
        venue = row["venue"]
        symbol = row["symbol"]
        day = row["date"]
        if (venue, symbol) not in {("COINBASE", "BTC-USD"), ("BINANCE", "BTCUSDT")}:
            raise PredictiveReject(f"DATA_REJECT:VOLUME_IDENTITY:{index}")
        try:
            parsed_day = date.fromisoformat(day)
            volume = D(row["base_volume_btc"])
        except Exception as error:
            raise PredictiveReject(f"DATA_REJECT:VOLUME_PARSE:{index}") from error
        if parsed_day.isoformat() != day or not volume.is_finite() or volume <= ZERO:
            raise PredictiveReject(f"DATA_REJECT:VOLUME_VALUE:{index}")
        identity = (venue, day)
        if identity in identities:
            raise PredictiveReject(f"DATA_REJECT:VOLUME_DUPLICATE:{identity}")
        identities.add(identity)
        values.setdefault(day, {})[venue] = volume
    expected_first = date(2020, 1, 1)
    expected_last = date(2024, 12, 31)
    current = expected_first
    while current <= expected_last:
        day = current.isoformat()
        if set(values.get(day, {})) != {"COINBASE", "BINANCE"}:
            raise PredictiveReject(f"DATA_REJECT:VOLUME_COMMON_DAY:{day}")
        current += timedelta(days=1)
    if len(values) != 1827 or len(rows) != 3654:
        raise PredictiveReject(
            f"DATA_REJECT:VOLUME_COUNTS:days={len(values)}:rows={len(rows)}"
        )
    return values


def calculate_states(
    volumes: dict[str, dict[str, D]]
) -> tuple[dict[str, bool], list[dict[str, Any]]]:
    days = sorted(volumes)
    shares = [
        volumes[day]["COINBASE"]
        / (volumes[day]["COINBASE"] + volumes[day]["BINANCE"])
        for day in days
    ]
    smoothed: list[tuple[str, D]] = []
    for index in range(27, len(days)):
        smoothed.append((days[index], mean(shares[index - 27 : index + 1])))
    states: dict[str, bool] = {}
    diagnostics: list[dict[str, Any]] = []
    for index in range(365, len(smoothed)):
        day, value = smoothed[index]
        reference = median([item[1] for item in smoothed[index - 365 : index]])
        state = value > reference
        states[day] = state
        diagnostics.append(
            {
                "day": day,
                "smoothed_share": value,
                "reference_median": reference,
                "high_state": state,
            }
        )
    return states, diagnostics


def daily_open_prices(bars: list[h1_parser.Bar]) -> dict[str, D]:
    opens = {
        bar.open_time.date().isoformat(): bar.open
        for bar in bars
        if bar.open_time.hour == 0
    }
    terminal = bars[-1]
    if terminal.close_time.hour != 0 or terminal.close_time.minute != 0:
        raise PredictiveReject("DATA_REJECT:H1_TERMINAL_CLOCK")
    terminal_day = terminal.close_time.date().isoformat()
    if terminal_day in opens:
        raise PredictiveReject("DATA_REJECT:H1_TERMINAL_DUPLICATE")
    opens[terminal_day] = terminal.close
    return opens


def build_observations(
    states: dict[str, bool], opens: dict[str, D]
) -> list[dict[str, Any]]:
    observations: list[dict[str, Any]] = []
    outcome_day = date(2021, 2, 1)
    last_day = date(2024, 12, 31)
    while outcome_day <= last_day:
        signal_day = (outcome_day - timedelta(days=1)).isoformat()
        next_day = (outcome_day + timedelta(days=1)).isoformat()
        day = outcome_day.isoformat()
        if signal_day not in states or day not in opens or next_day not in opens:
            raise PredictiveReject(f"DATA_REJECT:OUTCOME_ALIGNMENT:{day}")
        outcome_return = opens[next_day] / opens[day] - D(1)
        observations.append(
            {
                "outcome_day": day,
                "year": outcome_day.year,
                "high_state": states[signal_day],
                "return": outcome_return,
            }
        )
        outcome_day += timedelta(days=1)
    return observations


def summarize(values: list[dict[str, Any]]) -> tuple[dict[str, Any], dict[str, D]]:
    high = [value["return"] for value in values if value["high_state"]]
    low = [value["return"] for value in values if not value["high_state"]]
    high_mean = mean(high)
    low_mean = mean(low)
    high_downside = downside_semideviation(high)
    low_downside = downside_semideviation(low)
    return {
        "observation_count": len(values),
        "high_state_count": len(high),
        "low_state_count": len(low),
        "high_state_mean_return": q(high_mean),
        "low_state_mean_return": q(low_mean),
        "high_minus_low_mean_return": q(high_mean - low_mean),
        "high_state_downside_semideviation": q(high_downside),
        "low_state_downside_semideviation": q(low_downside),
    }, {
        "high_mean": high_mean,
        "low_mean": low_mean,
        "high_downside": high_downside,
        "low_downside": low_downside,
        "high_count": D(len(high)),
        "low_count": D(len(low)),
    }


def build_result(
    manifest_path: Path, volume_path: Path, h1_path: Path
) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    volume_binding = manifest["data_bindings"]["volume_corpus"]
    h1_binding = manifest["data_bindings"]["btc_h1_outcomes"]
    if sha256_path(volume_path) != volume_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:VOLUME_SHA256")
    if sha256_path(h1_path) != h1_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:H1_SHA256")
    volumes = load_volume_rows(volume_path)
    try:
        bars = h1_parser.parse_rows(h1_path.read_text(encoding="utf-8"))
    except Exception as error:
        raise PredictiveReject("DATA_REJECT:H1_PARSE") from error
    if len(bars) != 52_608 or h1_parser.data_hash(bars) != h1_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:H1_ROWS_OR_CANONICAL_HASH")
    states, feature_rows = calculate_states(volumes)
    observations = build_observations(states, daily_open_prices(bars))
    design_values = [
        value for value in observations if value["outcome_day"] <= "2022-12-31"
    ]
    validation_values = [
        value for value in observations if value["outcome_day"] >= "2023-01-01"
    ]
    design, design_raw = summarize(design_values)
    validation, validation_raw = summarize(validation_values)
    annual: dict[str, Any] = {}
    annual_raw: dict[str, dict[str, D]] = {}
    for year in range(2021, 2025):
        values = [value for value in observations if value["year"] == year]
        annual[str(year)], annual_raw[str(year)] = summarize(values)
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
        "DESIGN_HIGH_MEAN_RETURN_EXCEEDS_LOW": design_raw["high_mean"] > design_raw["low_mean"],
        "VALIDATION_HIGH_MEAN_RETURN_EXCEEDS_LOW": validation_raw["high_mean"] > validation_raw["low_mean"],
        "VALIDATION_HIGH_DOWNSIDE_SEMIDEVIATION_NONWORSE": validation_raw["high_downside"] <= validation_raw["low_downside"],
        "POSITIVE_ANNUAL_HIGH_MINUS_LOW_MEAN_RETURN_AT_LEAST_3_OF_4": sum(value > ZERO for value in annual_deltas.values()) >= 3,
        "TOP_POSITIVE_ANNUAL_DELTA_CONCENTRATION_AT_MOST_60_PERCENT": top_concentration <= D("0.60"),
    }
    failed = [name for name, passed in gates.items() if not passed]
    all_pass = not failed
    smoothed_values = [row["smoothed_share"] for row in feature_rows]
    return {
        "schema_version": "1",
        "document_type": "BTC_COINBASE_BINANCE_RELATIVE_VOLUME_PREDICTIVE_RESULT_V1",
        "family_id": EXPECTED_FAMILY_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_GATE_PASS_ECONOMIC_PREREGISTRATION_REQUIRED" if all_pass else "NO_CANDIDATE_CLOSE_COINBASE_BINANCE_RELATIVE_VOLUME_SHARE_PRE_ECONOMIC",
        "decision": "ADVANCE_TO_ONE_SEPARATELY_FROZEN_MATCHED_CAPITAL_ECONOMIC_SCREEN" if all_pass else "PERMANENTLY_CLOSE_EXACT_28D_365D_CROSS_VENUE_RELATIVE_VOLUME_SHARE_FAMILY_WITHOUT_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256_path(manifest_path)},
        "data": {
            "volume_corpus": {"path": volume_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256_path(volume_path), "rows": 3654, "common_days": 1827},
            "btc_h1_outcomes": {"path": h1_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256_path(h1_path), "rows": len(bars)},
        },
        "policy": EXPECTED_CONTRACT,
        "feature_diagnostic": {
            "state_available_first_signal_day": feature_rows[0]["day"],
            "state_available_last_signal_day": feature_rows[-1]["day"],
            "state_day_count": len(feature_rows),
            "smoothed_share_minimum": q(min(smoothed_values)),
            "smoothed_share_median": q(median(smoothed_values)),
            "smoothed_share_maximum": q(max(smoothed_values)),
        },
        "windows": {"design": design, "validation": validation},
        "annual": annual,
        "annual_high_minus_low_mean_return": {year: q(value) for year, value in annual_deltas.items()},
        "positive_annual_delta_year_count": sum(value > ZERO for value in annual_deltas.values()),
        "top_positive_annual_delta_concentration": q(top_concentration),
        "gates": gates,
        "failed_gates": failed,
        "all_predictive_gates_pass": all_pass,
        "economics_opened": False,
        "oos_opened": False,
        "claim_boundary": "Historical pre-2025 predictive evidence only. A pass would authorize one separately frozen matched-capital economic screen, not a candidate, OOS or Trading action.",
        "scope_note": "No matched-capital strategy ledger, fees, slippage, PnL, drawdown, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True)
    parser.add_argument("--volume-corpus", required=True)
    parser.add_argument("--btc-h1", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    manifest_path = Path(args.manifest).resolve()
    volume_path = Path(args.volume_corpus).resolve()
    h1_path = Path(args.btc_h1).resolve()
    output_path = Path(args.output).resolve()
    for path in (manifest_path, volume_path, h1_path):
        if not path.is_relative_to(REPO_ROOT):
            raise PredictiveReject(f"PATH_REJECT:{path}")
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not output_path.is_relative_to(state_root) or output_path.exists():
        raise PredictiveReject(f"OUTPUT_PATH_REJECT:{output_path}")
    result = build_result(manifest_path, volume_path, h1_path)
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
