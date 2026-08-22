#!/usr/bin/env python3
"""Evaluate the frozen BTC-ETH static log-spread one-day convergence gate."""

from __future__ import annotations

import argparse
import csv
from datetime import date, timedelta
import gzip
import hashlib
import json
import math
from pathlib import Path
from statistics import median
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DOCUMENT_TYPE = "BTC_ETH_STATIC_LOG_SPREAD_ONE_DAY_CONVERGENCE_MANIFEST_V1"
EXPECTED_FAMILY_ID = "btc-eth-static-log-spread-one-day-convergence"
EXPECTED_CONTRACT = {
    "pair": ["BTCUSDT", "ETHUSDT"],
    "formation": ["2020-01-01", "2020-12-31"],
    "design_signal_days": ["2021-01-01", "2022-12-31"],
    "validation_signal_days": ["2023-01-01", "2024-12-30"],
    "relation": "OLS_LOG_ETH_CLOSE_ON_LOG_BTC_CLOSE_WITH_INTERCEPT_FORMATION_ONLY",
    "residual_scale": "FORMATION_POPULATION_STANDARD_DEVIATION",
    "event": "ABSOLUTE_FROZEN_RELATION_RESIDUAL_Z_SCORE_AT_LEAST_ONE",
    "outcome": "NEXT_COMPLETE_UTC_DAY_SIGN_NORMALIZED_FROZEN_SPREAD_CONVERGENCE_DIVIDED_BY_FORMATION_SCALE",
    "event_directions": ["ETH_RICH", "ETH_CHEAP"],
    "minimum_design_events_per_direction": 30,
    "minimum_validation_events_per_direction": 20,
    "minimum_positive_annual_mean_years": 3,
    "maximum_top_positive_annual_contribution": 0.60,
    "maximum_validation_absolute_mean_shift_in_formation_sigma": 1.0,
    "maximum_validation_scale_ratio": 2.0,
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


def q(value: float) -> str:
    if not math.isfinite(value):
        raise PredictiveReject("METRIC_REJECT:NONFINITE")
    return f"{value:.10f}"


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
        != "HISTORICAL_PREDICTIVE_SUPPORT_ONLY_NO_ECONOMICS_NO_OOS"
        or manifest.get("predictive_contract") != EXPECTED_CONTRACT
    ):
        raise PredictiveReject("MANIFEST_REJECT:CONTRACT")
    for binding in manifest.get("bindings", []):
        bound = REPO_ROOT / str(binding.get("path", ""))
        if not bound.is_file() or sha256_path(bound) != binding.get("sha256"):
            raise PredictiveReject(f"BINDING_REJECT:{binding.get('role')}")
    return manifest


def load_closes(path: Path) -> dict[str, dict[str, float]]:
    expected_header = ["symbol", "date", "open", "high", "low", "close", "quote_volume"]
    try:
        with gzip.open(path, mode="rt", encoding="ascii", newline="") as stream:
            reader = csv.DictReader(stream)
            if reader.fieldnames != expected_header:
                raise PredictiveReject("DATA_REJECT:COLUMNS")
            rows = list(reader)
    except (OSError, UnicodeError, csv.Error) as error:
        raise PredictiveReject("DATA_REJECT:GZIP") from error
    closes: dict[str, dict[str, float]] = {"BTCUSDT": {}, "ETHUSDT": {}}
    for index, row in enumerate(rows):
        symbol = row["symbol"]
        day = row["date"]
        if symbol not in closes:
            raise PredictiveReject(f"DATA_REJECT:SYMBOL:{index}")
        try:
            parsed = date.fromisoformat(day)
            close = float(row["close"])
        except (ValueError, TypeError) as error:
            raise PredictiveReject(f"DATA_REJECT:PARSE:{index}") from error
        if parsed.isoformat() != day or not math.isfinite(close) or close <= 0:
            raise PredictiveReject(f"DATA_REJECT:VALUE:{index}")
        if day in closes[symbol]:
            raise PredictiveReject(f"DATA_REJECT:DUPLICATE:{symbol}:{day}")
        closes[symbol][day] = close
    if len(rows) != 3_654:
        raise PredictiveReject(f"DATA_REJECT:ROW_COUNT:{len(rows)}")
    current = date(2020, 1, 1)
    last = date(2024, 12, 31)
    expected_days = 0
    while current <= last:
        day = current.isoformat()
        if any(day not in closes[symbol] for symbol in closes):
            raise PredictiveReject(f"DATA_REJECT:COMMON_DAY:{day}")
        expected_days += 1
        current += timedelta(days=1)
    if expected_days != 1_827 or any(len(values) != 1_827 for values in closes.values()):
        raise PredictiveReject("DATA_REJECT:DAY_COUNTS")
    return closes


def mean(values: list[float]) -> float:
    if not values:
        raise PredictiveReject("METRIC_REJECT:EMPTY")
    return math.fsum(values) / len(values)


def population_std(values: list[float], center: float | None = None) -> float:
    if not values:
        raise PredictiveReject("METRIC_REJECT:EMPTY_STD")
    actual_center = mean(values) if center is None else center
    return math.sqrt(math.fsum((value - actual_center) ** 2 for value in values) / len(values))


def fit_ols(x_values: list[float], y_values: list[float]) -> tuple[float, float]:
    if len(x_values) != len(y_values) or len(x_values) < 2:
        raise PredictiveReject("METRIC_REJECT:OLS_INPUT")
    x_mean = mean(x_values)
    y_mean = mean(y_values)
    denominator = math.fsum((value - x_mean) ** 2 for value in x_values)
    if denominator <= 0:
        raise PredictiveReject("METRIC_REJECT:OLS_DENOMINATOR")
    beta = math.fsum(
        (x_value - x_mean) * (y_value - y_mean)
        for x_value, y_value in zip(x_values, y_values)
    ) / denominator
    return y_mean - beta * x_mean, beta


def downside_semideviation(values: list[float]) -> float:
    return math.sqrt(math.fsum(min(value, 0.0) ** 2 for value in values) / len(values))


def summarize(observations: list[dict[str, Any]]) -> tuple[dict[str, Any], dict[str, float]]:
    events = [row for row in observations if row["event"]]
    non_events = [row for row in observations if not row["event"]]
    if not events or not non_events:
        raise PredictiveReject("METRIC_REJECT:STATE_SUPPORT")
    event_values = [float(row["convergence"]) for row in events]
    non_event_values = [float(row["convergence"]) for row in non_events]
    rich_values = [float(row["convergence"]) for row in events if row["direction"] == "ETH_RICH"]
    cheap_values = [float(row["convergence"]) for row in events if row["direction"] == "ETH_CHEAP"]
    event_mean = mean(event_values)
    non_event_mean = mean(non_event_values)
    event_downside = downside_semideviation(event_values)
    non_event_downside = downside_semideviation(non_event_values)
    event_ratio = event_mean / event_downside if event_downside > 0 else math.inf
    non_event_ratio = non_event_mean / non_event_downside if non_event_downside > 0 else math.inf
    raw = {
        "event_count": float(len(events)),
        "non_event_count": float(len(non_events)),
        "rich_count": float(len(rich_values)),
        "cheap_count": float(len(cheap_values)),
        "event_mean": event_mean,
        "non_event_mean": non_event_mean,
        "event_downside": event_downside,
        "non_event_downside": non_event_downside,
        "event_mean_to_downside": event_ratio,
        "non_event_mean_to_downside": non_event_ratio,
        "rich_mean": mean(rich_values) if rich_values else -math.inf,
        "cheap_mean": mean(cheap_values) if cheap_values else -math.inf,
    }
    report = {
        "event_count": len(events),
        "non_event_count": len(non_events),
        "eth_rich_event_count": len(rich_values),
        "eth_cheap_event_count": len(cheap_values),
        "event_mean_convergence": q(event_mean),
        "event_median_convergence": q(float(median(event_values))),
        "event_positive_rate": q(sum(value > 0 for value in event_values) / len(event_values)),
        "event_downside_semideviation": q(event_downside),
        "event_mean_to_downside": q(event_ratio),
        "non_event_mean_convergence": q(non_event_mean),
        "non_event_downside_semideviation": q(non_event_downside),
        "non_event_mean_to_downside": q(non_event_ratio),
        "eth_rich_mean_convergence": q(raw["rich_mean"]),
        "eth_cheap_mean_convergence": q(raw["cheap_mean"]),
    }
    return report, raw


def build_result(manifest_path: Path, corpus_path: Path) -> dict[str, Any]:
    manifest = load_manifest(manifest_path)
    corpus_binding = manifest["data_binding"]
    if sha256_path(corpus_path) != corpus_binding["sha256"]:
        raise PredictiveReject("DATA_REJECT:CORPUS_SHA256")
    closes = load_closes(corpus_path)
    days = sorted(closes["BTCUSDT"])
    log_btc = {day: math.log(closes["BTCUSDT"][day]) for day in days}
    log_eth = {day: math.log(closes["ETHUSDT"][day]) for day in days}
    formation_days = [day for day in days if "2020-01-01" <= day <= "2020-12-31"]
    alpha, beta = fit_ols(
        [log_btc[day] for day in formation_days],
        [log_eth[day] for day in formation_days],
    )
    residuals = {
        day: log_eth[day] - alpha - beta * log_btc[day]
        for day in days
    }
    formation_residuals = [residuals[day] for day in formation_days]
    formation_mean = mean(formation_residuals)
    formation_scale = population_std(formation_residuals, formation_mean)
    if beta <= 0 or formation_scale <= 0:
        raise PredictiveReject("METRIC_REJECT:FORMATION_RELATION")
    observations: list[dict[str, Any]] = []
    for index, day in enumerate(days[:-1]):
        if day < "2021-01-01" or day > "2024-12-30":
            continue
        next_day = days[index + 1]
        if date.fromisoformat(next_day) - date.fromisoformat(day) != timedelta(days=1):
            raise PredictiveReject(f"DATA_REJECT:NEXT_DAY:{day}")
        residual = residuals[day]
        z_score = (residual - formation_mean) / formation_scale
        direction = "ETH_RICH" if residual >= formation_mean else "ETH_CHEAP"
        convergence = (
            -(residuals[next_day] - residual) / formation_scale
            if direction == "ETH_RICH"
            else (residuals[next_day] - residual) / formation_scale
        )
        observations.append(
            {
                "signal_day": day,
                "year": int(day[:4]),
                "direction": direction,
                "event": abs(z_score) >= 1.0,
                "convergence": convergence,
            }
        )
    design_rows = [row for row in observations if row["signal_day"] <= "2022-12-31"]
    validation_rows = [row for row in observations if row["signal_day"] >= "2023-01-01"]
    design, design_raw = summarize(design_rows)
    validation, validation_raw = summarize(validation_rows)
    annual: dict[str, Any] = {}
    annual_raw: dict[str, float] = {}
    for year in range(2021, 2025):
        report, raw = summarize([row for row in observations if row["year"] == year])
        annual[str(year)] = report
        annual_raw[str(year)] = raw["event_mean"]
    positive_annual = [value for value in annual_raw.values() if value > 0]
    top_concentration = max(positive_annual) / math.fsum(positive_annual) if positive_annual else 1.0
    validation_residuals = [
        residuals[day] for day in days if "2023-01-01" <= day <= "2024-12-31"
    ]
    validation_mean_shift = abs(mean(validation_residuals) - formation_mean) / formation_scale
    validation_scale_ratio = population_std(validation_residuals) / formation_scale
    gates = {
        "FORMATION_BETA_POSITIVE": beta > 0,
        "DESIGN_EACH_DIRECTION_AT_LEAST_30": design_raw["rich_count"] >= 30 and design_raw["cheap_count"] >= 30,
        "VALIDATION_EACH_DIRECTION_AT_LEAST_20": validation_raw["rich_count"] >= 20 and validation_raw["cheap_count"] >= 20,
        "DESIGN_EVENT_MEAN_CONVERGENCE_POSITIVE": design_raw["event_mean"] > 0,
        "VALIDATION_EVENT_MEAN_CONVERGENCE_POSITIVE": validation_raw["event_mean"] > 0,
        "DESIGN_BOTH_DIRECTIONS_MEAN_CONVERGENCE_POSITIVE": design_raw["rich_mean"] > 0 and design_raw["cheap_mean"] > 0,
        "VALIDATION_BOTH_DIRECTIONS_MEAN_CONVERGENCE_POSITIVE": validation_raw["rich_mean"] > 0 and validation_raw["cheap_mean"] > 0,
        "DESIGN_EVENT_MEAN_EXCEEDS_NON_EVENT": design_raw["event_mean"] > design_raw["non_event_mean"],
        "VALIDATION_EVENT_MEAN_EXCEEDS_NON_EVENT": validation_raw["event_mean"] > validation_raw["non_event_mean"],
        "DESIGN_EVENT_MEAN_TO_DOWNSIDE_EXCEEDS_NON_EVENT": design_raw["event_mean_to_downside"] > design_raw["non_event_mean_to_downside"],
        "VALIDATION_EVENT_MEAN_TO_DOWNSIDE_EXCEEDS_NON_EVENT": validation_raw["event_mean_to_downside"] > validation_raw["non_event_mean_to_downside"],
        "ANNUAL_POSITIVE_MEAN_AT_LEAST_3_OF_4": len(positive_annual) >= 3,
        "TOP_POSITIVE_ANNUAL_CONTRIBUTION_AT_MOST_60_PERCENT": top_concentration <= 0.60,
        "VALIDATION_ABSOLUTE_MEAN_SHIFT_AT_MOST_ONE_FORMATION_SIGMA": validation_mean_shift <= 1.0,
        "VALIDATION_RESIDUAL_SCALE_AT_MOST_TWO_TIMES_FORMATION": validation_scale_ratio <= 2.0,
    }
    all_pass = all(gates.values())
    return {
        "schema_version": "1",
        "document_type": "BTC_ETH_STATIC_LOG_SPREAD_ONE_DAY_CONVERGENCE_RESULT_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "family_id": EXPECTED_FAMILY_ID,
        "experiment_id": manifest["experiment_id"],
        "status": (
            "PREDICTIVE_SUPPORT_PASS_READY_FOR_ONE_ECONOMIC_PAIRS_LEDGER_NO_OOS"
            if all_pass
            else "NO_CANDIDATE_CLOSE_FIXED_BTC_ETH_STATIC_LOG_SPREAD_ONE_DAY_CONVERGENCE_PRE_ECONOMIC"
        ),
        "inputs": {
            "manifest_path": manifest_path.as_posix(),
            "manifest_sha256": sha256_path(manifest_path),
            "corpus_path": corpus_path.as_posix(),
            "corpus_sha256": sha256_path(corpus_path),
            "rows": 3654,
            "common_days": 1827,
        },
        "formation": {
            "days": len(formation_days),
            "alpha": q(alpha),
            "beta": q(beta),
            "residual_mean": q(formation_mean),
            "residual_population_std": q(formation_scale),
        },
        "design": design,
        "validation": validation,
        "annual": annual,
        "relation_drift": {
            "validation_absolute_mean_shift_in_formation_sigma": q(validation_mean_shift),
            "validation_residual_scale_ratio": q(validation_scale_ratio),
        },
        "annual_positive_mean_years": len(positive_annual),
        "top_positive_annual_contribution": q(top_concentration),
        "gates": gates,
        "all_gates_pass": all_pass,
        "economics": "NOT_OPENED",
        "oos": "NOT_OPENED",
        "scope_note": "Historical predictive support only. No strategy ledger, fees, slippage, funding, borrow, margin, PnL, drawdown, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--corpus", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        result = build_result(args.manifest, args.corpus)
        payload = canonical_bytes(result)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open("xb") as stream:
            stream.write(payload)
    except (PredictiveReject, FileExistsError) as error:
        print(str(error))
        return 2
    print(sha256_path(args.output))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
