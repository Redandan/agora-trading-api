#!/usr/bin/env python3
"""Seal one present-vintage FRED SOFR-minus-IOER/IORB source without BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, timedelta
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import subprocess
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-sofr-policy-spread-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-sofr-policy-spread-passive-core-primary-prior.v1.json"
TEST_PATH = REPO_ROOT / "research_pipeline/tests/test_fred_sofr_policy_spread_source_probe.py"
SOURCE_URL = (
    "https://fred.stlouisfed.org/graph/fredgraph.csv?"
    "id=SOFR%2CIOER%2CIORB&cosd=2018-01-01&coed=2024-12-31"
)
MAX_RESPONSE_BYTES = 256 * 1024
REQUEST_TIMEOUT_SECONDS = 60
AVAILABILITY_LAG_DAYS = 5
EXPECTED_FIRST = date(2018, 4, 3)
EXPECTED_LAST = date(2024, 12, 31)
MINIMUM_ROWS = 1680
MAXIMUM_ROWS = 1720
IORB_START = date(2021, 7, 29)
SUPPORT_GATES = {
    "DESIGN": {
        "start": date(2020, 1, 1),
        "end_exclusive": date(2023, 1, 1),
        "minimum_evaluations": 700,
        "minimum_positive_spread_days": 15,
        "minimum_nonpositive_spread_days": 400,
        "minimum_transitions": 6,
        "minimum_positive_spread_years": 2,
        "minimum_nonpositive_spread_years": 3,
        "maximum_single_year_positive_share": Decimal("0.75"),
    },
    "VALIDATION": {
        "start": date(2023, 1, 1),
        "end_exclusive": date(2025, 1, 1),
        "minimum_evaluations": 480,
        "minimum_positive_spread_days": 10,
        "minimum_nonpositive_spread_days": 250,
        "minimum_transitions": 4,
        "minimum_positive_spread_years": 2,
        "minimum_nonpositive_spread_years": 2,
        "maximum_single_year_positive_share": Decimal("0.85"),
    },
}


class SourceReject(RuntimeError):
    pass


def sha256(raw_or_path: bytes | Path) -> str:
    raw = raw_or_path.read_bytes() if isinstance(raw_or_path, Path) else raw_or_path
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n"
    ).encode("utf-8")


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    family_id = "btc-fred-sofr-policy-spread-passive-core-risk-overlay"
    if spec.get("status") != "FROZEN_BEFORE_2018_2024_SOFR_POLICY_SPREAD_VALUE_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != family_id or prior.get("family_id") != family_id:
        raise SourceReject("SPEC_REJECT:FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "series": ["SOFR", "IOER", "IORB"],
        "frequency": "SOFR_OBSERVATION_DAYS",
        "request_url": SOURCE_URL,
        "expected_first_date": EXPECTED_FIRST.isoformat(),
        "expected_last_date": EXPECTED_LAST.isoformat(),
        "minimum_rows": MINIMUM_ROWS,
        "maximum_rows": MAXIMUM_ROWS,
        "credentials": "DENY",
        "paid_api": "DENY",
        "redirect": "DENY",
        "automatic_retry": "DENY",
        "maximum_response_bytes": MAX_RESPONSE_BYTES,
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    feature = spec.get("feature_contract", {})
    expected_feature = {
        "policy_splice": "IOER_THROUGH_2021_07_28_IORB_FROM_2021_07_29",
        "high_funding_pressure_relation": "SOFR_MINUS_APPLICABLE_POLICY_RATE_STRICTLY_GREATER_THAN_ZERO",
        "availability": "OBSERVATION_DAY_PLUS_5_CALENDAR_DAYS_AT_00_00_UTC",
        "availability_lag_days": AVAILABILITY_LAG_DAYS,
        "maximum_validity_hours": 120,
        "direction_inversion": "DENY",
        "threshold_smoothing_magnitude_or_lag_scan": "DENY",
    }
    if any(feature.get(key) != value for key, value in expected_feature.items()):
        raise SourceReject("SPEC_REJECT:FEATURE_CONTRACT")
    windows = spec.get("pre_outcome_support_gates", {})
    for label, gate in SUPPORT_GATES.items():
        frozen = windows.get(label.lower(), {})
        expected_gate = {
            "start": gate["start"].isoformat(),
            "end_exclusive": gate["end_exclusive"].isoformat(),
            "minimum_evaluations": gate["minimum_evaluations"],
            "minimum_positive_spread_days": gate["minimum_positive_spread_days"],
            "minimum_nonpositive_spread_days": gate["minimum_nonpositive_spread_days"],
            "minimum_transitions": gate["minimum_transitions"],
            "minimum_positive_spread_years": gate["minimum_positive_spread_years"],
            "minimum_nonpositive_spread_years": gate["minimum_nonpositive_spread_years"],
            "maximum_single_year_positive_share": str(
                gate["maximum_single_year_positive_share"]
            ),
        }
        if any(frozen.get(key) != value for key, value in expected_gate.items()):
            raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}")
    bindings = spec.get("implementation_bindings", {})
    expected_bindings = {
        "primary_prior": (PRIOR_PATH, "PRIMARY_PRIOR"),
        "source_probe": (Path(__file__).resolve(), "SOURCE_PROBE"),
        "source_probe_test": (TEST_PATH, "SOURCE_PROBE_TEST"),
    }
    for name, (path, role) in expected_bindings.items():
        binding = bindings.get(name, {})
        if (
            binding.get("path") != path.relative_to(REPO_ROOT).as_posix()
            or binding.get("role") != role
            or binding.get("sha256") != sha256(path)
        ):
            raise SourceReject(f"SPEC_REJECT:IMPLEMENTATION_BINDING:{name}")
    return spec


def _decimal(raw: str, *, name: str, index: int) -> Decimal:
    try:
        value = Decimal(raw)
    except InvalidOperation as error:
        raise SourceReject(f"SOURCE_REJECT:{name}_DECIMAL:{index}") from error
    if not value.is_finite() or value < 0 or value > 25:
        raise SourceReject(f"SOURCE_REJECT:{name}_RANGE:{index}")
    return value


def parse_rows(raw: bytes) -> list[dict[str, Any]]:
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    table = list(csv.reader(io.StringIO(text, newline="")))
    if not table or table[0] != ["observation_date", "SOFR", "IOER", "IORB"]:
        raise SourceReject(f"SOURCE_REJECT:HEADER:{table[0] if table else 'EMPTY'}")
    parsed: list[dict[str, Any]] = []
    prior_calendar_day: date | None = None
    for index, row in enumerate(table[1:]):
        if len(row) != 4:
            raise SourceReject(f"SOURCE_REJECT:ROW_WIDTH:{index}")
        try:
            day = date.fromisoformat(row[0])
        except ValueError as error:
            raise SourceReject(f"SOURCE_REJECT:DATE:{index}") from error
        if prior_calendar_day is not None and day <= prior_calendar_day:
            raise SourceReject(f"SOURCE_REJECT:CALENDAR_ORDER:{index}")
        prior_calendar_day = day
        if not date(2018, 1, 1) <= day <= EXPECTED_LAST:
            raise SourceReject(f"SOURCE_REJECT:DATE_RANGE:{index}")
        raw_sofr, raw_ioer, raw_iorb = row[1:]
        if raw_sofr in {"", "."}:
            continue
        sofr = _decimal(raw_sofr, name="SOFR", index=index)
        if day < IORB_START:
            if raw_ioer in {"", "."} or raw_iorb not in {"", "."}:
                raise SourceReject(f"SOURCE_REJECT:IOER_IORB_TRANSITION:{index}")
            policy_name = "IOER"
            policy = _decimal(raw_ioer, name="IOER", index=index)
            raw_policy = raw_ioer
        else:
            if raw_iorb in {"", "."} or raw_ioer not in {"", "."}:
                raise SourceReject(f"SOURCE_REJECT:IOER_IORB_TRANSITION:{index}")
            policy_name = "IORB"
            policy = _decimal(raw_iorb, name="IORB", index=index)
            raw_policy = raw_iorb
        spread_bps = (sofr - policy) * Decimal("100")
        parsed.append(
            {
                "date": day,
                "sofr": sofr,
                "raw_sofr": raw_sofr,
                "policy_name": policy_name,
                "policy_rate": policy,
                "raw_policy_rate": raw_policy,
                "spread_bps": spread_bps,
            }
        )
    validate_rows(parsed)
    return parsed


def validate_rows(rows: list[dict[str, Any]]) -> None:
    if not MINIMUM_ROWS <= len(rows) <= MAXIMUM_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if rows[0]["date"] != EXPECTED_FIRST or rows[-1]["date"] != EXPECTED_LAST:
        raise SourceReject(
            f"SOURCE_REJECT:BOUNDARY:{rows[0]['date']}:{rows[-1]['date']}"
        )
    if len({row["date"] for row in rows}) != len(rows):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DATE")
    for index, (prior, current) in enumerate(zip(rows, rows[1:], strict=False), start=1):
        gap = (current["date"] - prior["date"]).days
        if gap < 1 or gap > 5:
            raise SourceReject(f"SOURCE_REJECT:BUSINESS_DAY_GAP:{index}:{gap}")
    if not any(row["policy_name"] == "IOER" for row in rows):
        raise SourceReject("SOURCE_REJECT:NO_IOER_ROWS")
    if not any(row["policy_name"] == "IORB" for row in rows):
        raise SourceReject("SOURCE_REJECT:NO_IORB_ROWS")


def window_support(rows: list[dict[str, Any]], gate: dict[str, Any]) -> dict[str, Any]:
    observations = []
    for row in rows:
        effective_day = row["date"] + timedelta(days=AVAILABILITY_LAG_DAYS)
        if gate["start"] <= effective_day < gate["end_exclusive"]:
            observations.append(
                {
                    **row,
                    "effective_day": effective_day,
                    "positive_spread": row["spread_bps"] > 0,
                }
            )
    transitions = sum(
        current["positive_spread"] != prior["positive_spread"]
        for prior, current in zip(observations, observations[1:], strict=False)
    )
    positive = [row for row in observations if row["positive_spread"]]
    nonpositive = [row for row in observations if not row["positive_spread"]]
    positive_by_year: dict[str, int] = {}
    nonpositive_years: set[str] = set()
    for row in observations:
        year = str(row["effective_day"].year)
        if row["positive_spread"]:
            positive_by_year[year] = positive_by_year.get(year, 0) + 1
        else:
            nonpositive_years.add(year)
    concentration = (
        Decimal(max(positive_by_year.values())) / Decimal(len(positive))
        if positive
        else None
    )
    checks = {
        "evaluations": len(observations) >= gate["minimum_evaluations"],
        "positive_spread_days": len(positive) >= gate["minimum_positive_spread_days"],
        "nonpositive_spread_days": len(nonpositive)
        >= gate["minimum_nonpositive_spread_days"],
        "transitions": transitions >= gate["minimum_transitions"],
        "positive_spread_years": len(positive_by_year)
        >= gate["minimum_positive_spread_years"],
        "nonpositive_spread_years": len(nonpositive_years)
        >= gate["minimum_nonpositive_spread_years"],
        "single_year_positive_concentration": concentration is not None
        and concentration <= gate["maximum_single_year_positive_share"],
    }
    return {
        "start": gate["start"].isoformat(),
        "end_exclusive": gate["end_exclusive"].isoformat(),
        "evaluations": len(observations),
        "positive_spread_days": len(positive),
        "nonpositive_spread_days": len(nonpositive),
        "transitions": transitions,
        "positive_spread_years": sorted(positive_by_year),
        "nonpositive_spread_years": sorted(nonpositive_years),
        "positive_spread_days_by_effective_year": positive_by_year,
        "maximum_single_year_positive_share": (
            str(concentration) if concentration is not None else None
        ),
        "checks": checks,
        "support_pass": all(checks.values()),
    }


def feature_feasibility(rows: list[dict[str, Any]]) -> dict[str, Any]:
    design = window_support(rows, SUPPORT_GATES["DESIGN"])
    validation = window_support(rows, SUPPORT_GATES["VALIDATION"])
    return {
        "spread_formula": "(SOFR_MINUS_IOER_OR_IORB)_TIMES_100_BASIS_POINTS",
        "high_funding_pressure_relation": "STRICTLY_POSITIVE_SPREAD",
        "availability_lag_days": AVAILABILITY_LAG_DAYS,
        "design": design,
        "validation": validation,
        "all_support_gates_pass": design["support_pass"]
        and validation["support_pass"],
    }


def state_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"OUTPUT_PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def fetch() -> bytes:
    try:
        completed = subprocess.run(
            [
                "curl.exe",
                "--proto",
                "=https",
                "--max-redirs",
                "0",
                "--max-time",
                str(REQUEST_TIMEOUT_SECONDS),
                "--fail",
                "--silent",
                "--show-error",
                "--user-agent",
                "AgoraResearchFredSofrPolicySpreadSourceProbe/1.0",
                "--url",
                SOURCE_URL,
            ],
            check=False,
            capture_output=True,
            timeout=REQUEST_TIMEOUT_SECONDS + 5,
        )
    except subprocess.TimeoutExpired as error:
        raise SourceReject("SOURCE_REJECT:TIMEOUT") from error
    if completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()[:160]
        raise SourceReject(f"SOURCE_REJECT:CURL:{completed.returncode}:{detail}")
    raw = completed.stdout
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    load_and_validate_spec()
    bundle_path = state_path(args.bundle)
    raw_path = state_path(args.raw)
    normalized_path = state_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    raw = fetch()
    rows = parse_rows(raw)
    feasibility = feature_feasibility(rows)
    if not feasibility["all_support_gates_pass"]:
        raise SourceReject(
            "SOURCE_REJECT:STATE_SUPPORT:"
            + json.dumps(feasibility, separators=(",", ":"), sort_keys=True)
        )
    normalized = (
        "observation_date,sofr,policy_rate,policy_name,sofr_minus_policy_bps\n"
        + "".join(
            f"{row['date'].isoformat()},{row['raw_sofr']},{row['raw_policy_rate']},"
            f"{row['policy_name']},{row['spread_bps']}\n"
            for row in rows
        )
    ).encode("utf-8")
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_SOFR_POLICY_SPREAD_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_AND_SUPPORT_PASS_NO_BTC_OUTCOME_ACCESS",
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agencies": [
            "Federal Reserve Bank of New York",
            "Board of Governors of the Federal Reserve System",
        ],
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "frozen_bindings": {
            "source_feasibility_spec": {
                "path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(SPEC_PATH),
            },
            "primary_prior": {
                "path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(PRIOR_PATH),
            },
            "source_probe": {
                "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(Path(__file__).resolve()),
            },
            "source_probe_test": {
                "path": TEST_PATH.relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(TEST_PATH),
            },
        },
        "raw_response": {
            "bytes": len(raw),
            "sha256": sha256(raw),
        },
        "normalized_subset": {
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0]["date"].isoformat(),
            "last_date": rows[-1]["date"].isoformat(),
            "columns": [
                "observation_date",
                "sofr",
                "policy_rate",
                "policy_name",
                "sofr_minus_policy_bps",
            ],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "SOFR day D is usable only at D plus five calendar days 00:00 UTC. The policy rate is joined on D using IOER before 2021-07-29 and IORB from that date onward.",
        "feature_boundary": "High funding pressure means SOFR minus the applicable IOER or IORB policy rate is strictly positive. Equality and negative spreads are the non-high state. No smoothing, magnitude threshold or inversion is allowed.",
        "revision_boundary": "The exact FRED CSV is a sealed present-vintage historical input. Original release and revision vintages remain MISSING_PROOF; historical success would require untouched prospective evidence.",
        "license_boundary": "FRED marks SOFR as copyrighted with citation required. Exact raw bytes remain private internal research state and are not claimed as redistributable.",
        "scope_note": "Free source and state-support gate only. No BTC outcome, strategy, PnL, drawdown, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(raw_path, raw)
    try:
        write_create_once(normalized_path, normalized)
        write_create_once(bundle_path, bundle_raw)
    except Exception:
        raw_path.unlink(missing_ok=True)
        normalized_path.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "raw": {
                    "path": raw_path.relative_to(REPO_ROOT).as_posix(),
                    "sha256": sha256(raw),
                    "bytes": len(raw),
                },
                "normalized": {
                    "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
                    "sha256": sha256(normalized),
                    "bytes": len(normalized),
                    "rows": len(rows),
                },
                "feasibility": feasibility,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
