#!/usr/bin/env python3
"""Deterministic historical screen for a lagged Cboe VIX/VIX3M term-structure policy."""

from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import io
import json
import sys
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta
from decimal import Decimal, getcontext
from pathlib import Path
from types import ModuleType


getcontext().prec = 50

D = Decimal
ZERO = D("0")

REPO_ROOT = Path(__file__).resolve().parents[1]
VIX_RUNNER_SOURCE = REPO_ROOT / "research" / "btc_vix_risk_state_long_cash_historical.py"
VIX3M_SOURCE = (
    REPO_ROOT
    / ".research-state"
    / "experiments"
    / "btc-vix-term-structure-long-cash-historical-v1"
    / "inputs"
    / "cboe-vix3m-history-raw.csv"
)
VIX3M_SOURCE_METADATA = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "cboe-vix3m-daily-2018-2024.v1.source.json"
)
PRIOR_SOURCE = (
    REPO_ROOT
    / "research_pipeline"
    / "examples"
    / "btc-vix-term-structure-primary-prior.v1.json"
)

VIX3M_DOWNLOAD_URL = (
    "https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX3M_History.csv"
)
EXPERIMENT_ID = "btc-vix-term-structure-long-cash-historical-v1"
EXPECTED_MANIFEST_TYPE = "BTC_VIX_TERM_STRUCTURE_LONG_CASH_HISTORICAL_MANIFEST_V1"
EXPECTED_VIX_RUNNER_SHA256 = "83563163d35d6a3fd2cf12785aa4f923235dba7b833ad4d1f2b932b431b43ad7"
EXPECTED_VIX3M_RAW_SHA256 = "a9705859b8d9c64e1d6b349c34e4bdb724528fab4ec0c833c6a32b8e6c5a84e6"
EXPECTED_VIX3M_METADATA_SHA256 = "eb57c841d3607799a198a831ac12a91498f0795dcaa359d8205c3b83330b5d3c"
EXPECTED_PRIOR_SHA256 = "09dc6c5c964ef920c16a1eacdaeb771567046185d827891bd4325ac24960410c"
EXPECTED_SUBSET_ROWS = 1_761
EXPECTED_MATCHED_ROWS = 1_761
EXPECTED_MISSING_VIX3M_DATES = (
    date(2022, 5, 30),
    date(2022, 6, 20),
    date(2022, 7, 4),
    date(2022, 9, 5),
    date(2022, 11, 24),
    date(2023, 1, 16),
    date(2023, 2, 20),
    date(2023, 5, 29),
    date(2023, 6, 19),
    date(2023, 7, 4),
    date(2023, 9, 4),
    date(2023, 11, 23),
    date(2024, 1, 15),
    date(2024, 2, 19),
    date(2024, 5, 27),
    date(2024, 6, 19),
    date(2024, 7, 4),
    date(2024, 9, 2),
    date(2024, 11, 28),
)
MAX_SOURCE_BYTES = 1_048_576

VARIANTS = (
    ("vix-vix3m-ratio-0_95-v1", "lower_neighbor", D("0.95")),
    ("vix-vix3m-ratio-1_00-v1", "primary", D("1.00")),
    ("vix-vix3m-ratio-1_05-v1", "upper_neighbor", D("1.05")),
)


class ResearchReject(RuntimeError):
    pass


@dataclass(frozen=True)
class CboeRow:
    day: date
    open: D
    high: D
    low: D
    close: D


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_module(name: str, path: Path) -> ModuleType:
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise ResearchReject(f"SOURCE_REJECT:IMPORT_SPEC:{path.name}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def parse_cboe_csv(payload: bytes, *, label: str) -> list[CboeRow]:
    try:
        text = payload.decode("utf-8-sig")
    except UnicodeDecodeError as exc:
        raise ResearchReject(f"{label}_DATA_REJECT:UTF8") from exc
    reader = csv.DictReader(io.StringIO(text, newline=""))
    if reader.fieldnames != ["DATE", "OPEN", "HIGH", "LOW", "CLOSE"]:
        raise ResearchReject(f"{label}_DATA_REJECT:COLUMNS")
    rows: list[CboeRow] = []
    for index, item in enumerate(reader):
        try:
            row = CboeRow(
                day=datetime.strptime(item["DATE"].strip(), "%m/%d/%Y").date(),
                open=D(item["OPEN"].strip()),
                high=D(item["HIGH"].strip()),
                low=D(item["LOW"].strip()),
                close=D(item["CLOSE"].strip()),
            )
        except (ValueError, KeyError, AttributeError, ArithmeticError) as exc:
            raise ResearchReject(f"{label}_DATA_REJECT:PARSE:{index}") from exc
        if not (
            row.low > ZERO
            and row.low <= row.open <= row.high
            and row.low <= row.close <= row.high
        ):
            raise ResearchReject(f"{label}_DATA_REJECT:OHLC:{index}")
        if rows and row.day <= rows[-1].day:
            raise ResearchReject(f"{label}_DATA_REJECT:ORDER:{index}")
        rows.append(row)
    if not rows:
        raise ResearchReject(f"{label}_DATA_REJECT:EMPTY")
    return rows


def bounded_subset(rows: list[CboeRow], *, label: str) -> list[CboeRow]:
    subset = [row for row in rows if date(2018, 1, 1) <= row.day <= date(2024, 12, 31)]
    if len(subset) != EXPECTED_SUBSET_ROWS:
        raise ResearchReject(f"{label}_DATA_REJECT:SUBSET_ROWS:{len(subset)}")
    if subset[0].day != date(2018, 1, 2) or subset[-1].day != date(2024, 12, 31):
        raise ResearchReject(f"{label}_DATA_REJECT:SUBSET_BOUNDARY")
    return subset


def capture_vix3m_source(destination: Path) -> dict[str, object]:
    expected = VIX3M_SOURCE.resolve()
    if destination.resolve() != expected:
        raise ResearchReject("SOURCE_CAPTURE_REJECT:DESTINATION")
    if destination.exists():
        raise ResearchReject(f"SEALED_SOURCE_EXISTS:{destination}")
    request = urllib.request.Request(
        VIX3M_DOWNLOAD_URL,
        headers={"User-Agent": "AgoraResearch/1.0 research-only"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read(MAX_SOURCE_BYTES + 1)
        status = getattr(response, "status", None)
        content_type = response.headers.get("Content-Type", "")
    if status != 200:
        raise ResearchReject(f"SOURCE_CAPTURE_REJECT:HTTP:{status}")
    if len(payload) > MAX_SOURCE_BYTES:
        raise ResearchReject("SOURCE_CAPTURE_REJECT:MAX_BYTES")
    if "csv" not in content_type.lower():
        raise ResearchReject(f"SOURCE_CAPTURE_REJECT:CONTENT_TYPE:{content_type}")
    rows = parse_cboe_csv(payload, label="VIX3M")
    subset = bounded_subset(rows, label="VIX3M")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("xb") as stream:
        stream.write(payload)
    return {
        "status": "SEALED_SOURCE_CAPTURED_NO_OUTCOME_ACCESS",
        "path": destination.relative_to(REPO_ROOT).as_posix(),
        "sha256": hashlib.sha256(payload).hexdigest(),
        "bytes": len(payload),
        "raw_rows": len(rows),
        "subset_rows": len(subset),
        "subset_first_date": subset[0].day.isoformat(),
        "subset_last_date": subset[-1].day.isoformat(),
        "url": VIX3M_DOWNLOAD_URL,
    }


def build_ratio_signals(vix_rows: list[object], vix3m_rows: list[CboeRow]) -> dict[datetime, D]:
    vix = {row.day: row.close for row in vix_rows}
    vix3m = {row.day: row.close for row in vix3m_rows}
    missing_vix = sorted(set(vix3m) - set(vix))
    missing_vix3m = sorted(set(vix) - set(vix3m))
    if missing_vix or tuple(missing_vix3m) != EXPECTED_MISSING_VIX3M_DATES:
        raise ResearchReject(
            "TERM_STRUCTURE_DATA_REJECT:DATE_ALIGNMENT:"
            f"VIX={len(missing_vix)}:VIX3M={len(missing_vix3m)}"
        )
    signals: dict[datetime, D] = {}
    for day in sorted(set(vix) & set(vix3m)):
        if vix3m[day] <= ZERO:
            raise ResearchReject(f"TERM_STRUCTURE_DATA_REJECT:NONPOSITIVE_VIX3M:{day}")
        effective_at = datetime.combine(day + timedelta(days=1), time())
        if effective_at in signals:
            raise ResearchReject("POLICY_REJECT:DUPLICATE_EFFECTIVE_TIME")
        signals[effective_at] = vix[day] / vix3m[day]
    if len(signals) != EXPECTED_MATCHED_ROWS:
        raise ResearchReject(f"TERM_STRUCTURE_DATA_REJECT:MATCHED_ROWS:{len(signals)}")
    return signals


def validate_manifest(manifest: dict[str, object]) -> None:
    if manifest.get("document_type") != EXPECTED_MANIFEST_TYPE:
        raise ResearchReject("MANIFEST_REJECT:DOCUMENT_TYPE")
    if manifest.get("experiment_id") != EXPERIMENT_ID:
        raise ResearchReject("MANIFEST_REJECT:EXPERIMENT_ID")
    if manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE":
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION")
    expected = [
        {"variant_id": variant_id, "role": role, "threshold": str(threshold)}
        for variant_id, role, threshold in VARIANTS
    ]
    policy = manifest.get("strategy_policy", {})
    if policy.get("variants") != expected:
        raise ResearchReject("MANIFEST_REJECT:VARIANTS")
    if policy.get("decision_feature") != "LATEST_MATCHED_VIX_CLOSE_DIVIDED_BY_VIX3M_CLOSE":
        raise ResearchReject("MANIFEST_REJECT:FEATURE")
    if policy.get("decision_clock") != "NEXT_CALENDAR_DAY_0000_UTC_AFTER_EACH_MATCHED_CBOE_CLOSE":
        raise ResearchReject("MANIFEST_REJECT:DECISION_CLOCK")
    if policy.get("relation") != "AT_OR_BELOW":
        raise ResearchReject("MANIFEST_REJECT:RELATION")
    if policy.get("missing_date_policy") != "NO_FILL_NO_INTERPOLATION_NO_SUBSTITUTION":
        raise ResearchReject("MANIFEST_REJECT:MISSING_DATE_POLICY")
    if manifest.get("oos_access") != "DENY":
        raise ResearchReject("MANIFEST_REJECT:OOS_ACCESS")
    expected_dataset = {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd",
        "rows": 52608,
        "first_open_time": "2019-01-01T00:00:00",
        "last_close_time": "2025-01-01T00:00:00",
        "selection_cutoff": "2025-01-01T00:00:00",
    }
    if manifest.get("dataset") != expected_dataset:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    expected_bindings = {
        "research_pipeline/examples/cboe-vix-daily-2018-2024.v1.csv": "b31effdd0af01e12baf3631772e4b00136b2d62f8b18fc8dd8ef246f1076a8bf",
        "research_pipeline/examples/cboe-vix-daily-2018-2024.v1.source.json": "cc4cc1edc7d387594a9c82ecfea19e98cd8fbf0219837ba939ec0cb2bdf66fc8",
        ".research-state/experiments/btc-vix-term-structure-long-cash-historical-v1/inputs/cboe-vix3m-history-raw.csv": EXPECTED_VIX3M_RAW_SHA256,
        "research_pipeline/examples/cboe-vix3m-daily-2018-2024.v1.source.json": EXPECTED_VIX3M_METADATA_SHA256,
        "research_pipeline/examples/btc-vix-term-structure-primary-prior.v1.json": EXPECTED_PRIOR_SHA256,
        "research/btc_vix_term_structure_long_cash_historical.py": sha256(Path(__file__).resolve()),
        "research/btc_vix_risk_state_long_cash_historical.py": EXPECTED_VIX_RUNNER_SHA256,
        "research/btc_dra_reversal_confirmed_exit_v2c.py": "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
        "research/btc_monthly_12m_time_series_momentum_historical.py": "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b",
        "src/main/java/com/agora/research/BtcDonchianStandaloneHistoricalCli.java": "4ce8133148e691793c2d21419e11b9c2afaf70f9c2442b83d3b9c67e0fc68760",
    }
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    actual_bindings = {
        item.get("path"): item.get("sha256")
        for item in bindings
        if isinstance(item, dict)
    }
    if len(actual_bindings) != len(bindings) or actual_bindings != expected_bindings:
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    if manifest.get("cost_scenarios") != {
        "NORMAL": {
            "fee_rate_per_side": "0.0010",
            "adverse_slippage_rate_per_side": "0.0005",
        },
        "STRESS": {
            "fee_rate_per_side": "0.0020",
            "adverse_slippage_rate_per_side": "0.0010",
        },
    }:
        raise ResearchReject("MANIFEST_REJECT:COST_SCENARIOS")


def build_output(input_path: Path, manifest_path: Path) -> dict[str, object]:
    if "PENDING_CAPTURE" in {
        EXPECTED_VIX3M_RAW_SHA256,
        EXPECTED_VIX3M_METADATA_SHA256,
        EXPECTED_PRIOR_SHA256,
    }:
        raise ResearchReject("SOURCE_REJECT:UNFROZEN_BINDINGS")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    base = load_module("vix_term_structure_base", VIX_RUNNER_SOURCE)
    engine = load_module("vix_term_structure_long_cash_engine", base.BASE_RUNNER_SOURCE)
    bindings = {
        "dataset": (input_path, base.EXPECTED_DATA_SHA256),
        "vix_runner": (VIX_RUNNER_SOURCE, EXPECTED_VIX_RUNNER_SHA256),
        "parser": (base.PARSER_SOURCE, base.EXPECTED_PARSER_SHA256),
        "passive_reference": (
            base.PASSIVE_REFERENCE,
            base.EXPECTED_PASSIVE_REFERENCE_SHA256,
        ),
        "long_cash_base": (base.BASE_RUNNER_SOURCE, base.EXPECTED_BASE_RUNNER_SHA256),
        "vix_source": (base.VIX_SOURCE, base.EXPECTED_VIX_SHA256),
        "vix_source_metadata": (
            base.VIX_SOURCE_METADATA,
            base.EXPECTED_VIX_METADATA_SHA256,
        ),
        "vix3m_source": (VIX3M_SOURCE, EXPECTED_VIX3M_RAW_SHA256),
        "vix3m_source_metadata": (
            VIX3M_SOURCE_METADATA,
            EXPECTED_VIX3M_METADATA_SHA256,
        ),
        "prior": (PRIOR_SOURCE, EXPECTED_PRIOR_SHA256),
    }
    for label, (path, expected) in bindings.items():
        if sha256(path) != expected:
            raise ResearchReject(f"SOURCE_REJECT:{label.upper()}_SHA256:{sha256(path)}")

    parser = base.load_module("vix_term_structure_h1_parser", base.PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != base.EXPECTED_DATA_ROWS or parser.data_hash(bars) != base.EXPECTED_DATA_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    vix_rows = base.parse_vix_rows(base.VIX_SOURCE)
    vix3m_rows = bounded_subset(
        parse_cboe_csv(VIX3M_SOURCE.read_bytes(), label="VIX3M"),
        label="VIX3M",
    )
    signals = build_ratio_signals(vix_rows, vix3m_rows)

    variants: list[dict[str, object]] = []
    primary: dict[str, object] | None = None
    neighbor_results: dict[str, dict[str, bool]] = {}
    for variant_id, role, threshold in VARIANTS:
        design_output, design_raw = base.simulate_window(
            bars, signals, base.DESIGN, threshold, engine
        )
        validation_output, validation_raw = base.simulate_window(
            bars, signals, base.VALIDATION, threshold, engine
        )
        annual = {
            year: base.simulate_window(bars, signals, window, threshold, engine)
            for year, window in base.ANNUAL.items()
        }
        gate_breadth = base.breadth(
            {year: value[1] for year, value in annual.items()}, engine
        )
        visible_breadth = dict(gate_breadth)
        visible_breadth.pop("top_year_raw")
        variant: dict[str, object] = {
            "variant_id": variant_id,
            "role": role,
            "threshold": str(threshold),
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {year: value[0] for year, value in annual.items()},
            "breadth_and_concentration": visible_breadth,
        }
        if role == "primary":
            gates = base.primary_gates(design_raw, validation_raw, gate_breadth)
            variant["primary_gates"] = gates
            primary = variant
        else:
            gates = base.neighbor_gates(design_raw, validation_raw, gate_breadth)
            variant["neighbor_gates"] = gates
            neighbor_results[variant_id] = gates
        variants.append(variant)

    if primary is None:
        raise ResearchReject("POLICY_REJECT:NO_PRIMARY")
    primary_gates = primary["primary_gates"]
    all_pass = all(primary_gates.values()) and all(
        all(gates.values()) for gates in neighbor_results.values()
    )
    failed_primary = [name for name, passed in primary_gates.items() if not passed]
    failed_neighbors = {
        variant_id: [name for name, passed in gates.items() if not passed]
        for variant_id, gates in neighbor_results.items()
        if not all(gates.values())
    }
    return {
        "schema_version": "1",
        "document_type": "BTC_VIX_TERM_STRUCTURE_LONG_CASH_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": (
            "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED"
            if all_pass
            else "NO_CANDIDATE_CLOSE_BTC_VIX_TERM_STRUCTURE_LONG_CASH_FAMILY"
        ),
        "decision": (
            "DESIGN_VALIDATION_AND_NEIGHBOR_GATES_PASS_SEALED_OOS_REQUIRED"
            if all_pass
            else "PERMANENTLY_CLOSE_EXACT_VIX_VIX3M_TERM_STRUCTURE_LONG_CASH_FAMILY_WITHOUT_TUNING"
        ),
        "manifest": {
            "path": manifest_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(manifest_path),
        },
        "runner": {
            "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(Path(__file__).resolve()),
            "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE",
        },
        "dataset": {
            "path": input_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": base.EXPECTED_DATA_SHA256,
            "rows": len(bars),
            "selection_cutoff": "2025-01-01T00:00:00",
        },
        "term_structure_source": {
            "vix_path": base.VIX_SOURCE.relative_to(REPO_ROOT).as_posix(),
            "vix_sha256": base.EXPECTED_VIX_SHA256,
            "vix3m_path": VIX3M_SOURCE.relative_to(REPO_ROOT).as_posix(),
            "vix3m_sha256": EXPECTED_VIX3M_RAW_SHA256,
            "matched_rows": len(signals),
            "first_date": vix_rows[0].day.isoformat(),
            "last_date": vix_rows[-1].day.isoformat(),
        },
        "source_bindings": {
            label: expected for label, (_, expected) in bindings.items()
        },
        "policy": {
            "feature": "LATEST_MATCHED_VIX_CLOSE_DIVIDED_BY_VIX3M_CLOSE",
            "effective_time": "NEXT_CALENDAR_DAY_0000_UTC",
            "long_relation": "AT_OR_BELOW",
            "long_target": "BTC_100_PERCENT",
            "risk_off_target": "CASH_100_PERCENT",
            "variants": 3,
        },
        "variants": variants,
        "primary_gates": primary_gates,
        "failed_primary_gates": failed_primary,
        "failed_neighbor_gates": failed_neighbors,
        "all_gates_pass": all_pass,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only; the cited prior supports a risk-state mechanism, not lagged BTC alpha. A pass requires separately sealed independent OOS and never authorizes activation.",
        "scope_note": "No paid API, second timer, second writer, backfill replacement, canonical state write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture-vix3m-only", action="store_true")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.capture_vix3m_only:
        if args.input is not None or args.manifest is not None or args.output is not None:
            raise ResearchReject("SOURCE_CAPTURE_REJECT:EXTRA_ARGUMENTS")
        print(json.dumps(capture_vix3m_source(VIX3M_SOURCE), sort_keys=True))
        return 0
    if args.input is None or args.manifest is None or args.output is None:
        raise ResearchReject("ARGUMENT_REJECT:INPUT_MANIFEST_OUTPUT_REQUIRED")
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": output_path.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(output_path),
                "failed_primary_gates": result["failed_primary_gates"],
                "failed_neighbor_gates": result["failed_neighbor_gates"],
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
