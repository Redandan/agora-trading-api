#!/usr/bin/env python3
"""Pre-economic execution-survival probe for BTC spot-perpetual basis convergence."""

from __future__ import annotations

import argparse
from collections import Counter
import csv
from datetime import datetime, timezone
from decimal import Decimal, ROUND_HALF_UP
import gzip
import hashlib
import json
from pathlib import Path
from typing import Any


D = Decimal
REPO_ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
FAMILY_ID = "btc-binance-usdm-perpetual-basis-convergence-market-neutral"
SPEC_TYPE = "BTC_BINANCE_USDM_PERPETUAL_BASIS_CONVERGENCE_PREOUTCOME_SUPPORT_SPEC_V1"
RESULT_TYPE = "BTC_BINANCE_USDM_PERPETUAL_BASIS_CONVERGENCE_PREOUTCOME_SUPPORT_RESULT_V1"
RUNNER_RELATIVE = "research/btc_binance_usdm_perpetual_basis_convergence_support_v1.py"
NATIVE_SPOT = "BINANCE_SPOT_ARCHIVE"
EXPECTED_COLUMNS = [
    "open_time_ms",
    "spot_open",
    "spot_close",
    "spot_price_source",
    "perp_open",
    "perp_close",
    "mark_open",
    "mark_close",
    "funding_calc_time_ms",
    "funding_offset_ms",
    "funding_rate",
]
HOUR_MS = 3_600_000
QUANTUM = D("0.00000001")


class SupportReject(RuntimeError):
    pass


def sha256(path_or_bytes: Path | bytes) -> str:
    raw = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    return hashlib.sha256(raw).hexdigest()


def repository_path(relative: str) -> Path:
    candidate = (REPO_ROOT / relative).resolve()
    try:
        candidate.relative_to(REPO_ROOT.resolve())
    except ValueError as exc:
        raise SupportReject(f"PATH_REJECT:{relative}") from exc
    return candidate


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
        + "\n"
    ).encode("utf-8")


def load_spec(path: Path) -> dict[str, Any]:
    if not path.is_file() or path.is_symlink():
        raise SupportReject("SPEC_REJECT:PATH")
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("document_type") != SPEC_TYPE or value.get("family_id") != FAMILY_ID:
        raise SupportReject("SPEC_REJECT:IDENTITY")
    if value.get("authorization") != AUTHORIZATION:
        raise SupportReject("SPEC_REJECT:AUTHORIZATION")
    runner = value.get("runner_binding")
    if not isinstance(runner, dict) or runner.get("path") != RUNNER_RELATIVE:
        raise SupportReject("SPEC_REJECT:RUNNER_BINDING")
    if runner.get("sha256") != sha256(Path(__file__).resolve()):
        raise SupportReject("BINDING_REJECT:RUNNER")
    prior = value.get("prior_binding")
    if not isinstance(prior, dict):
        raise SupportReject("SPEC_REJECT:PRIOR_BINDING")
    prior_path = repository_path(str(prior.get("path", "")))
    if not prior_path.is_file() or prior_path.is_symlink() or sha256(prior_path) != prior.get("sha256"):
        raise SupportReject("BINDING_REJECT:PRIOR")
    data = value.get("dataset")
    if not isinstance(data, dict):
        raise SupportReject("SPEC_REJECT:DATASET")
    bundle_path = repository_path(str(data.get("bundle_path", "")))
    if not bundle_path.is_file() or bundle_path.is_symlink() or sha256(bundle_path) != data.get("bundle_sha256"):
        raise SupportReject("BINDING_REJECT:CORPUS_BUNDLE")
    bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    if bundle.get("status") != "SEALED_CHECKSUM_VERIFIED_PRE_2025_FINAL_SOURCE_CLOSURE_NO_STRATEGY_OUTCOME":
        raise SupportReject("DATA_REJECT:BUNDLE_STATUS")
    if bundle.get("integrity", {}).get("strategy_outcome_computed") is not False:
        raise SupportReject("DATA_REJECT:BUNDLE_OUTCOME_BOUNDARY")
    policy = value.get("feature_policy")
    if not isinstance(policy, dict):
        raise SupportReject("SPEC_REJECT:FEATURE_POLICY")
    if policy.get("primary_stress_cost_floor_bps") != "120" or policy.get("lower_normal_cost_floor_bps") != "60":
        raise SupportReject("SPEC_REJECT:COST_FLOORS")
    if policy.get("direction") != "POSITIVE_BASIS_ONLY_LONG_SPOT_SHORT_PERPETUAL":
        raise SupportReject("SPEC_REJECT:DIRECTION")
    return value


def load_rows(path: Path, expected_sha256: str, expected_rows: int) -> list[dict[str, Any]]:
    if not path.is_file() or path.is_symlink() or sha256(path) != expected_sha256:
        raise SupportReject("DATA_REJECT:NORMALIZED_GZIP_HASH")
    rows: list[dict[str, Any]] = []
    with gzip.open(path, mode="rt", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if reader.fieldnames != EXPECTED_COLUMNS:
            raise SupportReject("DATA_REJECT:COLUMNS")
        previous_time: int | None = None
        for index, raw in enumerate(reader):
            open_time_ms = int(raw["open_time_ms"])
            if previous_time is not None and open_time_ms != previous_time + HOUR_MS:
                raise SupportReject(f"DATA_REJECT:HOURLY_LATTICE:{index}")
            previous_time = open_time_ms
            values = {
                "open_time_ms": open_time_ms,
                "spot_open": D(raw["spot_open"]),
                "spot_close": D(raw["spot_close"]),
                "spot_price_source": raw["spot_price_source"],
                "perp_open": D(raw["perp_open"]),
                "perp_close": D(raw["perp_close"]),
            }
            if min(values["spot_open"], values["spot_close"], values["perp_open"], values["perp_close"]) <= 0:
                raise SupportReject(f"DATA_REJECT:NONPOSITIVE_PRICE:{index}")
            if values["spot_price_source"] not in {
                NATIVE_SPOT,
                "BINANCE_USDM_INDEX_PROXY_FOR_PUBLISHER_GAP",
            }:
                raise SupportReject(f"DATA_REJECT:SPOT_SOURCE:{index}")
            rows.append(values)
    if len(rows) != expected_rows:
        raise SupportReject("DATA_REJECT:ROW_COUNT")
    if rows[-1]["open_time_ms"] >= 1_735_689_600_000 + HOUR_MS:
        raise SupportReject("OOS_REJECT:POST_2024")
    return rows


def basis_bps(perpetual: D, spot: D) -> D:
    return ((perpetual / spot) - D("1")) * D("10000")


def utc_label(open_time_ms: int) -> str:
    return datetime.fromtimestamp(open_time_ms / 1000, tz=timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def share(numerator: int, denominator: int) -> str:
    if denominator == 0:
        return "0.00000000"
    return str((D(numerator) / D(denominator)).quantize(QUANTUM, rounding=ROUND_HALF_UP))


def event_hash(times: list[str]) -> str:
    return sha256("".join(f"{value}\n" for value in times).encode("utf-8"))


def classify_window(open_time_ms: int) -> str:
    year = datetime.fromtimestamp(open_time_ms / 1000, tz=timezone.utc).year
    return "design" if year <= 2022 else "validation"


def probe_threshold(rows: list[dict[str, Any]], threshold_bps: D) -> dict[str, Any]:
    decisions: list[dict[str, Any]] = []
    previous_state = False
    for index, current in enumerate(rows[:-1]):
        native_decision = current["spot_price_source"] == NATIVE_SPOT
        decision_basis = basis_bps(current["perp_close"], current["spot_close"])
        state = native_decision and decision_basis >= threshold_bps
        if state and not previous_state:
            execution = rows[index + 1]
            native_execution = execution["spot_price_source"] == NATIVE_SPOT
            execution_basis = basis_bps(execution["perp_open"], execution["spot_open"])
            survived = native_execution and execution_basis >= threshold_bps
            decisions.append(
                {
                    "decision_time": utc_label(current["open_time_ms"] + HOUR_MS),
                    "execution_time": utc_label(execution["open_time_ms"]),
                    "execution_open_time_ms": execution["open_time_ms"],
                    "survived": survived,
                }
            )
        previous_state = state

    windows: dict[str, Any] = {}
    for window in ("design", "validation"):
        selected = [event for event in decisions if classify_window(event["execution_open_time_ms"]) == window]
        survived = [event for event in selected if event["survived"]]
        windows[window] = {
            "decision_episode_count": len(selected),
            "next_open_survived_episode_count": len(survived),
            "next_open_survival_share": share(len(survived), len(selected)),
            "decision_episode_times_sha256": event_hash([event["decision_time"] for event in selected]),
            "survived_execution_times_sha256": event_hash([event["execution_time"] for event in survived]),
        }

    annual: dict[str, Any] = {}
    for year in range(2020, 2025):
        selected = [
            event
            for event in decisions
            if datetime.fromtimestamp(event["execution_open_time_ms"] / 1000, tz=timezone.utc).year == year
        ]
        survived = [event for event in selected if event["survived"]]
        annual[str(year)] = {
            "decision_episode_count": len(selected),
            "next_open_survived_episode_count": len(survived),
        }
    survived_by_year = {year: value["next_open_survived_episode_count"] for year, value in annual.items()}
    total_survived = sum(survived_by_year.values())
    active_years = sum(value > 0 for value in survived_by_year.values())
    top_year_share = share(max(survived_by_year.values()), total_survived) if total_survived else "1.00000000"
    return {
        "threshold_bps": str(threshold_bps),
        "windows": windows,
        "annual": annual,
        "active_years": active_years,
        "top_year_survived_share": top_year_share,
    }


def build_result(spec: dict[str, Any], spec_path: Path, rows: list[dict[str, Any]]) -> dict[str, Any]:
    lower = probe_threshold(rows, D("60"))
    primary = probe_threshold(rows, D("120"))
    gates = spec["gates"]
    checks = {
        "native_spot_coverage_at_least_99_9_percent": D(share(sum(row["spot_price_source"] == NATIVE_SPOT for row in rows), len(rows))) >= D(gates["minimum_native_spot_share"]),
        "normal_design_minimum_decision_episodes": lower["windows"]["design"]["decision_episode_count"] >= gates["normal_design_minimum_decision_episodes"],
        "normal_validation_minimum_decision_episodes": lower["windows"]["validation"]["decision_episode_count"] >= gates["normal_validation_minimum_decision_episodes"],
        "primary_design_minimum_decision_episodes": primary["windows"]["design"]["decision_episode_count"] >= gates["primary_design_minimum_decision_episodes"],
        "primary_validation_minimum_decision_episodes": primary["windows"]["validation"]["decision_episode_count"] >= gates["primary_validation_minimum_decision_episodes"],
        "primary_design_minimum_survived_episodes": primary["windows"]["design"]["next_open_survived_episode_count"] >= gates["primary_design_minimum_survived_episodes"],
        "primary_validation_minimum_survived_episodes": primary["windows"]["validation"]["next_open_survived_episode_count"] >= gates["primary_validation_minimum_survived_episodes"],
        "primary_design_minimum_survival_share": D(primary["windows"]["design"]["next_open_survival_share"]) >= D(gates["primary_minimum_survival_share"]),
        "primary_validation_minimum_survival_share": D(primary["windows"]["validation"]["next_open_survival_share"]) >= D(gates["primary_minimum_survival_share"]),
        "primary_minimum_active_years": primary["active_years"] >= gates["primary_minimum_active_years"],
        "primary_maximum_top_year_survived_share": D(primary["top_year_survived_share"]) <= D(gates["primary_maximum_top_year_survived_share"]),
    }
    failed = [name for name, passed in checks.items() if not passed]
    passed = not failed
    return {
        "schema_version": "1",
        "document_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "family_id": FAMILY_ID,
        "research_classification": "PREOUTCOME_EXECUTION_SURVIVAL_ONLY_NO_STRATEGY_ECONOMICS_NO_OOS",
        "status": (
            "PREOUTCOME_EXECUTION_SURVIVAL_PASS_READY_FOR_ONE_ECONOMIC_HYPOTHESIS"
            if passed
            else "NO_HYPOTHESIS_CLOSE_BTC_BINANCE_USDM_BASIS_CONVERGENCE_AT_EXECUTION_SURVIVAL_GATE"
        ),
        "decision": spec["decision_rule"]["pass" if passed else "fail"],
        "input_bindings": {
            "spec_path": spec_path.relative_to(REPO_ROOT).as_posix(),
            "spec_sha256": sha256(spec_path),
            "runner_path": RUNNER_RELATIVE,
            "runner_sha256": sha256(Path(__file__).resolve()),
            "corpus_bundle_sha256": spec["dataset"]["bundle_sha256"],
            "normalized_gzip_sha256": spec["dataset"]["normalized_gzip_sha256"],
        },
        "source_integrity": {
            "hourly_rows": len(rows),
            "native_spot_rows": sum(row["spot_price_source"] == NATIVE_SPOT for row in rows),
            "proxy_spot_rows": sum(row["spot_price_source"] != NATIVE_SPOT for row in rows),
            "native_spot_share": share(sum(row["spot_price_source"] == NATIVE_SPOT for row in rows), len(rows)),
            "first_open_time": utc_label(rows[0]["open_time_ms"]),
            "last_open_time": utc_label(rows[-1]["open_time_ms"]),
        },
        "lower_normal_cost_floor_diagnostic": lower,
        "primary_stress_cost_floor": primary,
        "gate_checks": checks,
        "failed_gates": failed,
        "strategy_economics": {
            "holding_return": "NOT_ACCESSED",
            "funding_pnl": "NOT_ACCESSED",
            "fees": "NOT_ACCESSED",
            "adverse_slippage": "NOT_ACCESSED",
            "realized_pnl": "NOT_ACCESSED",
            "unrealized_pnl": "NOT_ACCESSED",
            "total_pnl": "NOT_ACCESSED",
            "maximum_drawdown": "NOT_ACCESSED",
            "terminal_inventory": "NOT_ACCESSED",
        },
        "material_learning": (
            "The cost-derived positive basis state has enough independent hourly episodes, next-open survival and annual breadth to justify one frozen economic screen."
            if passed
            else "The cost-derived positive basis state lacks the preregistered episode support, next-open persistence or annual breadth needed for a credible hourly executable convergence strategy."
        ),
        "hypothesis_created": False,
        "candidate_created": False,
        "oos_opened": False,
        "scope_note": "No holding return, exit policy, PnL, drawdown, candidate, OOS, paid API, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--spec", required=True)
    parser.add_argument("--data", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    spec_path = repository_path(args.spec)
    data_path = repository_path(args.data)
    output_path = repository_path(args.output)
    expected_root = repository_path(
        ".research-state/experiments/btc-binance-usdm-perpetual-basis-convergence-support-v1/inputs"
    )
    try:
        output_path.relative_to(expected_root)
    except ValueError as exc:
        raise SupportReject("OUTPUT_REJECT:PATH") from exc
    if output_path.exists() or output_path.is_symlink():
        raise SupportReject("OUTPUT_REJECT:CREATE_ONCE")
    spec = load_spec(spec_path)
    dataset = spec["dataset"]
    if args.data != dataset["normalized_gzip_path"]:
        raise SupportReject("DATA_REJECT:PATH")
    rows = load_rows(data_path, dataset["normalized_gzip_sha256"], dataset["hourly_rows"])
    result = build_result(spec, spec_path, rows)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("xb") as handle:
        handle.write(canonical_bytes(result))
    print(json.dumps({"output": args.output, "sha256": sha256(output_path), "status": result["status"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
