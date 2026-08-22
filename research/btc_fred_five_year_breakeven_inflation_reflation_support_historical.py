#!/usr/bin/env python3
"""Deterministic pre-economic screen for the frozen T5YIE-rise hypothesis."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from datetime import date, datetime, timedelta
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_fred_on_rrp_drawdown_liquidity_support_historical as shared


D = shared.D
ZERO = shared.ZERO
DESIGN = shared.DESIGN
VALIDATION = shared.VALIDATION
HORIZON_HOURS = shared.HORIZON_HOURS
CHANGE_LAG_DAYS = 28
AVAILABILITY_LAG_DAYS = 5
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52608
EXPECTED_T5YIE_SHA256 = "5080eee237a67dc286f51bfb968a29c92e49c2477c06bbd9f5f88cc77a1c8cfe"
EXPECTED_T5YIE_ROWS = 417
EXPECTED_SOURCE_BUNDLE_SHA256 = "e65b4f0f06077469f7e796e4470ec4a05e8bf95891dec1c8feb4239e4cd29ffc"
EXPECTED_PRIOR_SHA256 = "4da21176e950dfb8ae2f8b983e81c0e8240b46a8a38a45d4b3a83f7327e9785a"
EXPECTED_SPEC_SHA256 = "992baad87e0a48c3810919361a1838ee584ae7e84452fab8674d0c2dc61359bc"
EXPECTED_PROBE_SHA256 = "86262c3411ef47ffc9891fd0bc0e6ae83f8fdb2ba7e4fc22f78d595c363a6442"
EXPECTED_HYPOTHESIS_SHA256 = "6a82afc1671ff9aed0484be20362c5b44d7699134a29205f8822e8a8044a1939"
EXPERIMENT_ID = "btc-fred-five-year-breakeven-inflation-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_FRED_FIVE_YEAR_BREAKEVEN_INFLATION_REFLATION_SUPPORT_HISTORICAL_MANIFEST_V1"

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_PRE_ECONOMIC_RUNNER": "research/btc_fred_five_year_breakeven_inflation_reflation_support_historical.py",
    "FROZEN_PREDICTIVE_STATISTICS_KERNEL": "research/btc_fred_on_rrp_drawdown_liquidity_support_historical.py",
    "FROZEN_H1_PARSER_AND_DETERMINISTIC_STATISTICS": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_FAIL_CLOSED_T5YIE_SOURCE_PROBE": "research/fred_t5yie_source_probe.py",
    "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-fred-five-year-breakeven-inflation-reflation-support-long-cash-primary-prior.v1.json",
    "FROZEN_PRE_FACTOR_SOURCE_FEASIBILITY_SPEC": "research_pipeline/examples/btc-fred-five-year-breakeven-inflation-source-feasibility.v1.spec.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-fred-five-year-breakeven-inflation-reflation-support-long-cash-v1.hypothesis.json",
    "SEALED_OFFICIAL_T5YIE_SOURCE_BUNDLE": ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/inputs/fred-t5yie-source-bundle.json",
    "SEALED_NORMALIZED_T5YIE_CORPUS": ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/inputs/fred-t5yie-2017-2024.normalized.csv",
}

PREDICTIVE_GATE_NAMES = shared.PREDICTIVE_GATE_NAMES
EXPECTED_GATES = (
    "btc_sha256_and_52608_rows_match",
    "t5yie_sha256_rows_weekly_lattice_and_friday_plus5d_availability_match",
    "source_bundle_runner_prior_hypothesis_probe_and_spec_sha256_match",
    *(f"design_{name}" for name in PREDICTIVE_GATE_NAMES),
    *(f"validation_{name}" for name in PREDICTIVE_GATE_NAMES),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class FactorPoint:
    observation_date: date
    eligible_at: datetime
    current_value: D
    prior_four_week_value: D

    @property
    def supportive(self) -> bool:
        return self.current_value > self.prior_four_week_value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_t5yie(path: Path) -> list[tuple[date, D]]:
    if not path.is_file() or sha256(path) != EXPECTED_T5YIE_SHA256:
        raise ResearchReject("DATA_REJECT:T5YIE_SHA256")
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if not rows or rows[0] != ["week_ending_friday", "t5yie_last_pct"]:
        raise ResearchReject("DATA_REJECT:T5YIE_HEADER")
    parsed: list[tuple[date, D]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise ResearchReject(f"DATA_REJECT:T5YIE_ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1])
        except Exception as error:
            raise ResearchReject(f"DATA_REJECT:T5YIE_PARSE:{index}") from error
        if not value.is_finite() or value < D("-5") or value > D("20"):
            raise ResearchReject(f"DATA_REJECT:T5YIE_VALUE:{index}")
        parsed.append((day, value))
    if len(parsed) != EXPECTED_T5YIE_ROWS:
        raise ResearchReject(f"DATA_REJECT:T5YIE_ROWS:{len(parsed)}")
    if parsed[0][0] != date(2017, 1, 6) or parsed[-1][0] != date(2024, 12, 27):
        raise ResearchReject("DATA_REJECT:T5YIE_BOUNDARY")
    if len({day for day, _ in parsed}) != len(parsed):
        raise ResearchReject("DATA_REJECT:T5YIE_DUPLICATE")
    for prior, current in zip(parsed, parsed[1:], strict=False):
        if current[0] - prior[0] != timedelta(days=7) or current[0].weekday() != 4:
            raise ResearchReject("DATA_REJECT:T5YIE_WEEKLY_LATTICE")
    return parsed


def build_factor_points(rows: list[tuple[date, D]]) -> list[FactorPoint]:
    by_day = dict(rows)
    points: list[FactorPoint] = []
    for current_day, current_value in rows:
        prior_day = current_day - timedelta(days=CHANGE_LAG_DAYS)
        if prior_day not in by_day:
            continue
        points.append(
            FactorPoint(
                observation_date=current_day,
                eligible_at=datetime.combine(
                    current_day + timedelta(days=AVAILABILITY_LAG_DAYS),
                    datetime.min.time(),
                ),
                current_value=current_value,
                prior_four_week_value=by_day[prior_day],
            )
        )
    if len(points) != EXPECTED_T5YIE_ROWS - 4:
        raise ResearchReject("DATA_REJECT:T5YIE_FACTOR_COUNT")
    return points


def predictive_evidence(
    bars: list[Any],
    points: list[FactorPoint],
    window: tuple[datetime, datetime],
    *,
    label: str,
) -> dict[str, Any]:
    result = shared.predictive_evidence(bars, points, window, label=label)
    for episode in result["episodes"]:
        episode["current_t5yie_pct"] = episode.pop(
            "current_rrpontsyd_billions_usd"
        )
        episode["prior_four_week_t5yie_pct"] = episode.pop(
            "prior_four_week_rrpontsyd_billions_usd"
        )
    return result


def expected_policy() -> dict[str, Any]:
    return {
        "factor_identity": "FRED_T5YIE_COMPLETE_WEEK_LAST_AVAILABLE_BUSINESS_DAY_STRICTLY_ABOVE_VALUE_28_CALENDAR_DAYS_EARLIER_V1",
        "supportive_condition": "CURRENT_COMPLETE_WEEK_LAST_T5YIE_STRICTLY_ABOVE_EXACT_COMPLETE_WEEK_VALUE_28_CALENDAR_DAYS_EARLIER",
        "other_condition": "OTHERWISE_INCLUDING_EQUAL",
        "availability": "WEEK_ENDING_FRIDAY_PLUS_5_CALENDAR_DAYS_AT_00_00_UTC",
        "execution_anchor": "FIRST_BTC_H1_OPEN_AT_OR_AFTER_FACTOR_AVAILABILITY",
        "predictive_horizon_hours": HORIZON_HOURS,
        "variants": 1,
        "strategy_economics": "DENY_UNLESS_ALL_PRE_ECONOMIC_GATES_PASS",
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if (
        manifest.get("document_type") != EXPECTED_MANIFEST_TYPE
        or manifest.get("experiment_id") != EXPERIMENT_ID
    ):
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if (
        manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("oos_access") != "DENY"
    ):
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    if manifest.get("hypothesis_id") != "btc-fred-five-year-breakeven-inflation-reflation-support-long-cash-v1":
        raise ResearchReject("MANIFEST_REJECT:HYPOTHESIS")
    if manifest.get("datasets") != {
        "btc": {
            "path": ".research-state/java-parity/selection-2019-2024.tsv",
            "sha256": EXPECTED_BTC_SHA256,
            "hourly_rows": EXPECTED_BTC_ROWS,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "t5yie": {
            "path": ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/inputs/fred-t5yie-2017-2024.normalized.csv",
            "sha256": EXPECTED_T5YIE_SHA256,
            "rows": EXPECTED_T5YIE_ROWS,
            "first_week": "2017-01-06",
            "last_week": "2024-12-27",
            "present_vintage": True,
        },
        "source_bundle": {
            "path": ".research-state/experiments/btc-fred-five-year-breakeven-inflation-historical-v1/inputs/fred-t5yie-source-bundle.json",
            "sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
        },
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASETS")
    if manifest.get("predictive_policy") != expected_policy():
        raise ResearchReject("MANIFEST_REJECT:POLICY")
    if manifest.get("windows") != {
        "design": {
            "start": "2019-01-01T00:00:00",
            "end_exclusive": "2023-01-01T00:00:00",
        },
        "validation": {
            "start": "2023-01-01T00:00:00",
            "end_exclusive": "2025-01-01T00:00:00",
        },
        "predictive_horizon_hours": HORIZON_HOURS,
        "predictive_overlap_rule": "WEEKLY_168H_EPISODES_NON_OVERLAPPING_BY_CONSTRUCTION",
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    if manifest.get("gate_set") != {
        "required": list(EXPECTED_GATES),
        "pass": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE",
        "failure": "PERMANENTLY_CLOSE_WITHOUT_STRATEGY_ECONOMIC_ACCESS_OR_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or {
        binding.get("role") for binding in bindings
    } != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        path = REPO_ROOT / binding["path"]
        if not path.is_file() or sha256(path) != binding.get("sha256"):
            raise ResearchReject(f"BINDING_REJECT:{role}:SHA256")


def evaluate_gates(
    predictive: dict[str, dict[str, Any]],
) -> tuple[dict[str, bool], list[str]]:
    gates: dict[str, bool] = {
        "btc_sha256_and_52608_rows_match": True,
        "t5yie_sha256_rows_weekly_lattice_and_friday_plus5d_availability_match": True,
        "source_bundle_runner_prior_hypothesis_probe_and_spec_sha256_match": True,
    }
    for label in ("design", "validation"):
        for name, passed in predictive[label]["gates"].items():
            gates[f"{label}_{name}"] = passed
    if tuple(gates) != EXPECTED_GATES:
        raise ResearchReject("MANIFEST_REJECT:GATE_DRIFT")
    failed = [name for name, passed in gates.items() if not passed]
    return gates, failed


def build_output(
    btc_input: Path, t5yie_input: Path, manifest_path: Path
) -> dict[str, Any]:
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    if sha256(btc_input) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    bars = shared.shared.cftc_reused.base.parse_rows(
        btc_input.read_text(encoding="utf-8")
    )
    if (
        len(bars) != EXPECTED_BTC_ROWS
        or shared.shared.cftc_reused.base.data_hash(bars) != EXPECTED_BTC_SHA256
    ):
        raise ResearchReject("DATA_REJECT:BTC_ROWS_OR_CANONICAL_SHA256")
    if bars[-1].close_time > VALIDATION[1]:
        raise ResearchReject("OOS_REJECT:BTC_CUTOFF")
    t5yie_rows = load_t5yie(t5yie_input)
    points = build_factor_points(t5yie_rows)
    predictive = {
        "design": predictive_evidence(bars, points, DESIGN, label="design"),
        "validation": predictive_evidence(
            bars, points, VALIDATION, label="validation"
        ),
    }
    gates, failed = evaluate_gates(predictive)
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": "BTC_FRED_FIVE_YEAR_BREAKEVEN_INFLATION_REFLATION_SUPPORT_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE"
        if passed
        else "NO_CANDIDATE_CLOSE_BTC_FRED_FIVE_YEAR_BREAKEVEN_INFLATION_REFLATION_SUPPORT_FAMILY_PRE_ECONOMIC",
        "decision": "FREEZE_SEPARATE_MATCHED_CAPITAL_ECONOMIC_EXPERIMENT_WITHOUT_OOS"
        if passed
        else "PERMANENTLY_CLOSE_EXACT_T5YIE_FOUR_WEEK_RISE_FAMILY_WITHOUT_ECONOMIC_ACCESS_OR_TUNING",
        "candidate_created": False,
        "economic_evidence_accessed": False,
        "oos_opened": False,
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
        },
        "datasets": {
            "btc": {
                "path": btc_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(btc_input),
                "hourly_rows": len(bars),
            },
            "t5yie": {
                "path": t5yie_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(t5yie_input),
                "rows": len(t5yie_rows),
            },
            "source_bundle_sha256": EXPECTED_SOURCE_BUNDLE_SHA256,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "factor_inventory": {
            "eligible_points": len(points),
            "supportive_points": sum(point.supportive for point in points),
            "other_points": sum(not point.supportive for point in points),
            "first_eligible_at": points[0].eligible_at.isoformat(),
            "last_eligible_at": points[-1].eligible_at.isoformat(),
        },
        "predictive_evidence": predictive,
        "pre_economic_gates": gates,
        "failed_pre_economic_gates": failed,
        "economic_evidence": {
            metric: "MISSING_PROOF_NOT_ACCESSED_BY_FROZEN_PRE_ECONOMIC_SCREEN"
            for metric in (
                "fees",
                "adverse_slippage",
                "realized_pnl",
                "unrealized_pnl",
                "total_pnl",
                "maximum_drawdown",
                "holding_age",
                "terminal_inventory",
                "breadth_and_path_risk",
            )
        },
        "claim_boundary": "Historical present-vintage FRED T5YIE and pre-2025 BTC evidence only. Rising inflation compensation is not pure expected inflation, does not establish independent BTC alpha, and this screen does not create a strategy candidate or authorize activation.",
        "scope_note": "No strategy economics, paid API, second timer, second writer, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--t5yie-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [
        args.btc_input.resolve(),
        args.t5yie_input.resolve(),
        args.manifest.resolve(),
    ]
    output_path = args.output.resolve()
    if not all(path.is_relative_to(REPO_ROOT) for path in inputs):
        raise ResearchReject("PATH_REJECT:INPUT_OR_MANIFEST")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(*inputs)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(
            result,
            stream,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_pre_economic_gates": result["failed_pre_economic_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
