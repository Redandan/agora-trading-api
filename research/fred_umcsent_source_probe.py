#!/usr/bin/env python3
"""Seal official present-vintage FRED UMCSENT history without BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timezone
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
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-primary-prior.v1.json"
SOURCE_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=UMCSENT&cosd=2017-01-01&coed=2024-12-31"
EXPECTED_ROWS = 96
EXPECTED_FIRST = date(2017, 1, 1)
EXPECTED_LAST = date(2024, 12, 1)
MAX_RESPONSE_BYTES = 32 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES = {
    "design": {
        "minimum_evaluations": 48,
        "minimum_per_state": 12,
        "minimum_transitions": 10,
    },
    "validation": {
        "minimum_evaluations": 24,
        "minimum_per_state": 6,
        "minimum_transitions": 4,
    },
}
DECIMAL_VALUE = re.compile(r"^(?:0|[1-9][0-9]*)(?:[.][0-9]+)?$")


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


def add_months(day: date, months: int) -> date:
    absolute_month = day.year * 12 + day.month - 1 + months
    return date(absolute_month // 12, absolute_month % 12 + 1, 1)


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    if spec.get("status") != "FROZEN_BEFORE_2017_2024_UMCSENT_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != "btc-fred-umcsent-rising-consumer-sentiment-long-cash":
        raise SourceReject("SPEC_REJECT:FAMILY")
    if prior.get("family_id") != spec.get("family_id"):
        raise SourceReject("SPEC_REJECT:PRIOR_FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "series": "UMCSENT",
        "frequency": "MONTHLY",
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
    if feature.get("lookback_observations") != 1 or feature.get("availability") != "FIRST_DAY_OF_SECOND_FOLLOWING_CALENDAR_MONTH_AT_00_00_UTC":
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
            "User-Agent": "AgoraResearchFredUmcsentSourceProbe/1.0",
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
    if not rows or rows[0] != ["observation_date", "UMCSENT"]:
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
        if day.day != 1 or not value.is_finite() or value < 0 or value > 200:
            raise SourceReject(f"SOURCE_REJECT:IDENTITY_OR_RANGE:{index}")
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
        if current_day != add_months(prior_day, 1):
            raise SourceReject(f"SOURCE_REJECT:MONTHLY_LATTICE:{index}")


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
    rising = sum(flags)
    non_rising = len(flags) - rising
    transitions = sum(
        current != prior for prior, current in zip(flags, flags[1:], strict=False)
    )
    annual: dict[str, dict[str, int]] = {}
    for effective, is_rising in selected:
        counts = annual.setdefault(str(effective.year), {"rising": 0, "non_rising": 0})
        counts["rising" if is_rising else "non_rising"] += 1
    passed = (
        len(selected) >= gate["minimum_evaluations"]
        and rising >= gate["minimum_per_state"]
        and non_rising >= gate["minimum_per_state"]
        and transitions >= gate["minimum_transitions"]
    )
    return {
        "evaluations": len(selected),
        "rising_months": rising,
        "non_rising_months": non_rising,
        "transitions": transitions,
        "longest_rising_run_months": _longest_run(flags, True),
        "longest_non_rising_run_months": _longest_run(flags, False),
        "annual_state_counts": annual,
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z") if selected else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z") if selected else None,
        "support_gate": gate,
        "support_pass": passed,
    }


def feature_feasibility(rows: list[tuple[date, Decimal, str]]) -> dict[str, Any]:
    states = [
        (
            datetime.combine(add_months(day, 2), time.min, tzinfo=timezone.utc),
            value > rows[index - 1][1],
        )
        for index, (day, value, _) in enumerate(rows)
        if index > 0
    ]
    design = _summarize_window(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"])
    validation = _summarize_window(states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"])
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "monthly_observation_count": len(rows),
        "evaluations": len(states),
        "rising_months": sum(state[1] for state in states),
        "non_rising_months": sum(not state[1] for state in states),
        "transitions": sum(current[1] != prior[1] for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design,
        "validation": validation,
        "admission_status": "PASS_MONTHLY_COVERAGE_BOTH_STATE_SUPPORT_AND_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS" if passed else "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_OR_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS",
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
        "observation_date,umcsent\n"
        + "".join(f"{day.isoformat()},{raw_value}\n" for day, _, raw_value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS" if feasibility["admission_status"].startswith("PASS_") else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_UMCSENT_MONTHLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "University of Michigan Surveys of Consumers via Federal Reserve Bank of St. Louis FRED",
        "series": "UMCSENT",
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
            "columns": ["observation_date", "umcsent"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "FRED states that UMCSENT is delayed by one month. Each monthly observation is therefore conservatively usable only on the first day of the second following calendar month at 00:00 UTC and remains valid for one calendar month.",
        "revision_boundary": "The exact FRED CSV is sealed present-vintage history. Preliminary values, original publication-time finals and revisions remain MISSING_PROOF; any historical pass still requires untouched independent OOS.",
        "interpretation_boundary": "The survey measures household sentiment, not crypto flows. A rising reading can reflect politics or inflation perceptions without transmitting to BTC demand.",
        "license_boundary": "The University of Michigan series is copyrighted and citation is required. Exact sealed bytes are retained only as internal reproducibility evidence.",
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
