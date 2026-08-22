#!/usr/bin/env python3
"""Seal official present-vintage FRED STLFSI4 history without BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path
import re
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-stlfsi4-below-normal-stress-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-stlfsi4-below-normal-stress-long-cash-primary-prior.v1.json"
SOURCE_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=STLFSI4&cosd=2018-01-01&coed=2024-12-31"
EXPECTED_ROWS = 365
EXPECTED_FIRST = date(2018, 1, 5)
EXPECTED_LAST = date(2024, 12, 27)
AVAILABILITY_LAG_DAYS = 6
MAX_RESPONSE_BYTES = 64 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES = {
    "design": {
        "minimum_evaluations": 200,
        "minimum_per_state": 20,
        "minimum_transitions": 8,
    },
    "validation": {
        "minimum_evaluations": 100,
        "minimum_per_state": 8,
        "minimum_transitions": 4,
    },
}
DECIMAL_VALUE = re.compile(r"^-?[0-9]+(?:[.][0-9]+)?$")


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(
        self,
        req: Any,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
        newurl: str,
    ) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


def sha256(raw_or_path: bytes | Path) -> str:
    raw = raw_or_path.read_bytes() if isinstance(raw_or_path, Path) else raw_or_path
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    if spec.get("status") != "FROZEN_BEFORE_2018_2024_STLFSI4_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != "btc-fred-stlfsi4-below-normal-stress-long-cash":
        raise SourceReject("SPEC_REJECT:FAMILY")
    if prior.get("family_id") != spec.get("family_id"):
        raise SourceReject("SPEC_REJECT:PRIOR_FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "series": "STLFSI4",
        "frequency": "WEEKLY_ENDING_FRIDAY",
        "request_url": SOURCE_URL,
        "expected_rows": EXPECTED_ROWS,
        "expected_first_date": EXPECTED_FIRST.isoformat(),
        "expected_last_date": EXPECTED_LAST.isoformat(),
        "credentials": "DENY",
        "paid_api": "DENY",
        "redirect": "DENY",
        "automatic_retry": "DENY",
        "maximum_response_bytes": MAX_RESPONSE_BYTES,
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    feature = spec.get("feature_contract", {})
    if feature.get("threshold") != "0" or feature.get("availability") != "OBSERVATION_FRIDAY_PLUS_6_CALENDAR_DAYS_AT_00_00_UTC":
        raise SourceReject("SPEC_REJECT:FEATURE_CONTRACT")
    for label, gate in SUPPORT_GATES.items():
        if any(spec.get("windows", {}).get(label, {}).get(key) != value for key, value in gate.items()):
            raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}")
    return spec


def output_path(value: str) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"OUTPUT_PATH_REJECT:{resolved}") from error
    if resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def fetch() -> bytes:
    request = Request(
        SOURCE_URL,
        method="GET",
        headers={
            "Accept": "text/csv",
            "User-Agent": "AgoraResearchFredStlfsi4SourceProbe/1.0",
        },
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw


def parse_rows(raw: bytes) -> list[tuple[date, Decimal, str]]:
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    if not rows or rows[0] != ["observation_date", "STLFSI4"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, Decimal, str]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2 or DECIMAL_VALUE.fullmatch(row[1]) is None:
            raise SourceReject(f"SOURCE_REJECT:ROW:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = Decimal(row[1])
        except (ValueError, InvalidOperation) as error:
            raise SourceReject(f"SOURCE_REJECT:VALUE_OR_DATE:{index}") from error
        if not value.is_finite() or value < Decimal("-20") or value > Decimal("20"):
            raise SourceReject(f"SOURCE_REJECT:VALUE_RANGE:{index}")
        parsed.append((day, value, row[1]))
    return parsed


def validate_rows(rows: list[tuple[date, Decimal, str]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if rows[0][0] != EXPECTED_FIRST or rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, ((prior_day, _, _), (current_day, _, _)) in enumerate(
        zip(rows, rows[1:], strict=False), start=1
    ):
        if prior_day.weekday() != 4 or current_day - prior_day != timedelta(days=7):
            raise SourceReject(f"SOURCE_REJECT:WEEKLY_LATTICE:{index}")
    if rows[-1][0].weekday() != 4:
        raise SourceReject("SOURCE_REJECT:LAST_WEEKDAY")


def _longest_run(states: list[bool], target: bool) -> int:
    longest = current = 0
    for state in states:
        current = current + 1 if state is target else 0
        longest = max(longest, current)
    return longest


def _summarize_window(
    states: list[tuple[datetime, bool]],
    start: datetime,
    end: datetime,
    gate: dict[str, int],
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    flags = [state[1] for state in selected]
    below_zero = sum(flags)
    at_or_above_zero = len(flags) - below_zero
    transitions = sum(
        current != prior for prior, current in zip(flags, flags[1:], strict=False)
    )
    annual: dict[str, dict[str, int]] = {}
    for effective, is_below_zero in selected:
        counts = annual.setdefault(str(effective.year), {"below_zero": 0, "at_or_above_zero": 0})
        counts["below_zero" if is_below_zero else "at_or_above_zero"] += 1
    passed = (
        len(selected) >= gate["minimum_evaluations"]
        and below_zero >= gate["minimum_per_state"]
        and at_or_above_zero >= gate["minimum_per_state"]
        and transitions >= gate["minimum_transitions"]
    )
    return {
        "evaluations": len(selected),
        "below_zero_weeks": below_zero,
        "at_or_above_zero_weeks": at_or_above_zero,
        "transitions": transitions,
        "longest_below_zero_run_weeks": _longest_run(flags, True),
        "longest_at_or_above_zero_run_weeks": _longest_run(flags, False),
        "annual_state_counts": annual,
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z") if selected else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z") if selected else None,
        "support_gate": gate,
        "support_pass": passed,
    }


def feature_feasibility(rows: list[tuple[date, Decimal, str]]) -> dict[str, Any]:
    states = [
        (
            datetime.combine(day + timedelta(days=AVAILABILITY_LAG_DAYS), time.min, tzinfo=timezone.utc),
            value < 0,
        )
        for day, value, _ in rows
    ]
    design = _summarize_window(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"])
    validation = _summarize_window(states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"])
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "weekly_observation_count": len(rows),
        "below_zero_weeks": sum(state[1] for state in states),
        "at_or_above_zero_weeks": sum(not state[1] for state in states),
        "transitions": sum(current[1] != prior[1] for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design,
        "validation": validation,
        "admission_status": "PASS_WEEKLY_COVERAGE_BOTH_STATE_SUPPORT_AND_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS" if passed else "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_OR_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS",
    }


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    load_and_validate_spec()
    bundle_path = output_path(args.bundle)
    raw_path = output_path(args.raw)
    normalized_path = output_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    raw = fetch()
    rows = parse_rows(raw)
    validate_rows(rows)
    normalized = (
        "observation_date,stlfsi4\n"
        + "".join(f"{day.isoformat()},{raw_value}\n" for day, _, raw_value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS" if feasibility["admission_status"].startswith("PASS_") else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_STLFSI4_WEEKLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "series": "STLFSI4",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "transport": "Python urllib",
            "redirect": "DENY",
            "retry": "DENY",
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
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "rows": len(rows),
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "columns": ["observation_date", "stlfsi4"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Each Friday observation is conservatively usable only on the following Thursday at 00:00 UTC and remains valid for 168 hours.",
        "revision_boundary": "The exact FRED CSV is sealed present-vintage history. Original publication-time values, constituent vintages and revisions remain MISSING_PROOF; any historical pass still requires untouched independent OOS.",
        "interpretation_boundary": "The official zero threshold describes broad U.S. financial stress, not BTC predictability. A below-zero state can be nearly passive and opportunity cost remains an economic question.",
        "license_boundary": "FRED identifies the source and requires citation. Exact sealed bytes are retained only as internal reproducibility evidence.",
        "scope_note": "No BTC outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    created: list[Path] = []
    try:
        write_create_once(raw_path, raw)
        created.append(raw_path)
        write_create_once(normalized_path, normalized)
        created.append(normalized_path)
        write_create_once(bundle_path, bundle_raw)
        created.append(bundle_path)
    except Exception:
        for target in created:
            target.unlink(missing_ok=True)
        raise
    print(json.dumps({
        "status": status,
        "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
        "bundle_sha256": sha256(bundle_raw),
        "raw_sha256": sha256(raw),
        "normalized_sha256": sha256(normalized),
        "feasibility": feasibility,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
