#!/usr/bin/env python3
"""Deterministic pre-economic screen for the frozen BTC native-issuance hypothesis."""

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
WINDOW_DAYS = 28
COMPARISON_END_LAG_DAYS = 364
AVAILABILITY_LAG_DAYS = 3
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52608
EXPECTED_ISSUANCE_SHA256 = "7e443a284b5df553b5bb0c442126a1a00d752e6cadf5afd847de9b3c039f2ee6"
EXPECTED_ISSUANCE_ROWS = 2922
EXPECTED_SOURCE_BUNDLE_SHA256 = "05964036ded83834019078b7ea631fc4d7ffd9a746942bde4a545bb557c01a7e"
EXPECTED_PRIOR_SHA256 = "ce344daa3269a6b0c0b9bd3ab275f66a34c765d10ec03607647e2f551912ea46"
EXPECTED_SPEC_SHA256 = "3df2de648b0ac27b11239e50e81d88a8af3cd60b6aa7bf0e04635b9115ca5806"
EXPECTED_HYPOTHESIS_SHA256 = "514239b179cdec15eaba4e60a18c3bc7b485de43078f20c7af5ef0081c875d8a"
EXPERIMENT_ID = "btc-coinmetrics-native-issuance-contraction-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_COINMETRICS_NATIVE_ISSUANCE_CONTRACTION_SUPPLY_PRESSURE_HISTORICAL_MANIFEST_V1"

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_PRE_ECONOMIC_RUNNER": "research/btc_coinmetrics_native_issuance_contraction_supply_pressure_historical.py",
    "FROZEN_PREDICTIVE_STATISTICS_KERNEL": "research/btc_fred_on_rrp_drawdown_liquidity_support_historical.py",
    "FROZEN_H1_PARSER_AND_DETERMINISTIC_STATISTICS": "research/btc_cftc_dealer_net_position_change_long_cash_historical.py",
    "FROZEN_FAIL_CLOSED_ISS_TOT_NTV_SOURCE_PROBE": "research/coinmetrics_btc_native_issuance_contraction_source_probe.cjs",
    "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-coinmetrics-native-issuance-contraction-supply-pressure-primary-prior.v1.json",
    "FROZEN_PRE_FACTOR_SOURCE_FEASIBILITY_SPEC": "research_pipeline/examples/btc-coinmetrics-native-issuance-contraction-source-feasibility.v1.spec.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-coinmetrics-native-issuance-contraction-supply-pressure-long-cash-v1.hypothesis.json",
    "SEALED_COINMETRICS_SOURCE_BUNDLE": ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/inputs/coinmetrics-btc-native-issuance-source-bundle.json",
    "SEALED_NORMALIZED_ISS_TOT_NTV_CORPUS": ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/inputs/coinmetrics-btc-native-issuance-2017-2024.normalized.csv",
}

PREDICTIVE_GATE_NAMES = shared.PREDICTIVE_GATE_NAMES
EXPECTED_GATES = (
    "btc_sha256_and_52608_rows_match",
    "issuance_sha_rows_daily_lattice_weekly_feature_and_day3_availability_match",
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
        return self.current_value < self.prior_four_week_value


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_issuance(path: Path) -> list[tuple[date, D]]:
    if not path.is_file() or sha256(path) != EXPECTED_ISSUANCE_SHA256:
        raise ResearchReject("DATA_REJECT:ISSUANCE_SHA256")
    with path.open("r", encoding="utf-8", newline="") as stream:
        rows = list(csv.reader(stream))
    if not rows or rows[0] != ["date", "issuance_total_native_units"]:
        raise ResearchReject("DATA_REJECT:ISSUANCE_HEADER")
    parsed: list[tuple[date, D]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise ResearchReject(f"DATA_REJECT:ISSUANCE_ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = D(row[1])
        except Exception as error:
            raise ResearchReject(f"DATA_REJECT:ISSUANCE_PARSE:{index}") from error
        if value <= ZERO or value > D("5000"):
            raise ResearchReject(f"DATA_REJECT:ISSUANCE_VALUE:{index}")
        parsed.append((day, value))
    if len(parsed) != EXPECTED_ISSUANCE_ROWS:
        raise ResearchReject(f"DATA_REJECT:ISSUANCE_ROWS:{len(parsed)}")
    if parsed[0][0] != date(2017, 1, 1) or parsed[-1][0] != date(2024, 12, 31):
        raise ResearchReject("DATA_REJECT:ISSUANCE_BOUNDARY")
    if len({day for day, _ in parsed}) != len(parsed):
        raise ResearchReject("DATA_REJECT:ISSUANCE_DUPLICATE")
    for prior, current in zip(parsed, parsed[1:], strict=False):
        if current[0] - prior[0] != timedelta(days=1):
            raise ResearchReject("DATA_REJECT:ISSUANCE_DAILY_LATTICE")
    return parsed


def build_factor_points(rows: list[tuple[date, D]]) -> list[FactorPoint]:
    points = []
    first_eligible_index = COMPARISON_END_LAG_DAYS + WINDOW_DAYS - 1
    for index in range(first_eligible_index, len(rows)):
        if rows[index][0].weekday() != 6:
            continue
        current_sum = sum(
            (value for _, value in rows[index - WINDOW_DAYS + 1 : index + 1]),
            ZERO,
        )
        prior_end = index - COMPARISON_END_LAG_DAYS
        prior_sum = sum(
            (
                value
                for _, value in rows[
                    prior_end - WINDOW_DAYS + 1 : prior_end + 1
                ]
            ),
            ZERO,
        )
        points.append(
            FactorPoint(
                observation_date=rows[index][0],
                eligible_at=datetime.combine(
                    rows[index][0] + timedelta(days=AVAILABILITY_LAG_DAYS),
                    datetime.min.time(),
                ),
                current_value=current_sum,
                prior_four_week_value=prior_sum,
            )
        )
    if len(points) != 362:
        raise ResearchReject(f"DATA_REJECT:ISSUANCE_FACTOR_COUNT:{len(points)}")
    if (
        points[0].observation_date != date(2018, 1, 28)
        or points[0].eligible_at != datetime(2018, 1, 31)
        or points[-1].observation_date != date(2024, 12, 29)
        or points[-1].eligible_at != datetime(2025, 1, 1)
    ):
        raise ResearchReject("DATA_REJECT:ISSUANCE_FACTOR_BOUNDARY")
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
        episode["current_trailing_28d_issuance_total_native_units"] = episode.pop(
            "current_rrpontsyd_billions_usd"
        )
        episode[
            "prior_year_paired_trailing_28d_issuance_total_native_units"
        ] = episode.pop("prior_four_week_rrpontsyd_billions_usd")
    return result


def expected_policy() -> dict[str, Any]:
    return {
        "factor_identity": "COINMETRICS_BTC_ISS_TOT_NTV_COMPLETE_SUNDAY_TRAILING_28D_SUM_STRICTLY_BELOW_TRAILING_28D_SUM_ENDING_364D_EARLIER_V1",
        "supportive_condition": "CURRENT_COMPLETE_SUNDAY_TRAILING_28D_NATIVE_ISSUANCE_SUM_STRICTLY_BELOW_SAME_WEEKDAY_TRAILING_28D_SUM_ENDING_364_DAYS_EARLIER",
        "other_condition": "OTHERWISE",
        "availability": "COMPLETE_SUNDAY_PLUS_3_CALENDAR_DAYS_AT_WEDNESDAY_00_00_UTC",
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
    if manifest.get("hypothesis_id") != "btc-coinmetrics-native-issuance-contraction-supply-pressure-long-cash-v1":
        raise ResearchReject("MANIFEST_REJECT:HYPOTHESIS")
    if manifest.get("datasets") != {
        "btc": {
            "path": ".research-state/java-parity/selection-2019-2024.tsv",
            "sha256": EXPECTED_BTC_SHA256,
            "hourly_rows": EXPECTED_BTC_ROWS,
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "issuance": {
            "path": ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/inputs/coinmetrics-btc-native-issuance-2017-2024.normalized.csv",
            "sha256": EXPECTED_ISSUANCE_SHA256,
            "rows": EXPECTED_ISSUANCE_ROWS,
            "first_date": "2017-01-01",
            "last_date": "2024-12-31",
            "present_vintage": True,
        },
        "source_bundle": {
            "path": ".research-state/experiments/btc-coinmetrics-native-issuance-contraction-historical-v1/inputs/coinmetrics-btc-native-issuance-source-bundle.json",
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
        "issuance_sha_rows_daily_lattice_weekly_feature_and_day3_availability_match": True,
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
    btc_input: Path, issuance_input: Path, manifest_path: Path
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
    issuance_rows = load_issuance(issuance_input)
    points = build_factor_points(issuance_rows)
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
        "document_type": "BTC_COINMETRICS_NATIVE_ISSUANCE_CONTRACTION_SUPPLY_PRESSURE_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "PREDICTIVE_SCREEN_PASS_ECONOMIC_MANIFEST_REQUIRED_NO_CANDIDATE"
        if passed
        else "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_NATIVE_ISSUANCE_CONTRACTION_SUPPLY_PRESSURE_FAMILY_PRE_ECONOMIC",
        "decision": "FREEZE_SEPARATE_MATCHED_CAPITAL_ECONOMIC_EXPERIMENT_WITHOUT_OOS"
        if passed
        else "PERMANENTLY_CLOSE_EXACT_YEAR_PAIRED_NATIVE_ISSUANCE_CONTRACTION_FAMILY_WITHOUT_ECONOMIC_ACCESS_OR_TUNING",
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
            "issuance": {
                "path": issuance_input.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(issuance_input),
                "rows": len(issuance_rows),
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
        "claim_boundary": "Historical present-vintage Coin Metrics IssTotNtv and pre-2025 BTC evidence only. Protocol issuance is not observed miner selling, halvings are anticipated, and this screen does not create a strategy candidate or authorize activation.",
        "scope_note": "No strategy economics, paid API, second timer, second writer, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--btc-input", type=Path, required=True)
    parser.add_argument("--issuance-input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    inputs = [
        args.btc_input.resolve(),
        args.issuance_input.resolve(),
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
