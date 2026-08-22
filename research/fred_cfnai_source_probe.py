#!/usr/bin/env python3
"""Seal official present-vintage FRED CFNAI history without BTC outcomes."""

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
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-cfnai-above-trend-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-cfnai-above-trend-long-cash-primary-prior.v1.json"
SOURCE_URL = "https://fred.stlouisfed.org/graph/fredgraph.csv?id=CFNAI&cosd=2018-01-01&coed=2024-12-31"
EXPECTED_ROWS = 84
EXPECTED_FIRST = date(2018, 1, 1)
EXPECTED_LAST = date(2024, 12, 1)
AVAILABILITY_DAYS = 60
MAX_RESPONSE_BYTES = 64 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES = {
    "design": {"minimum_above_trend": 12, "minimum_other": 12, "minimum_transitions": 6},
    "validation": {"minimum_above_trend": 6, "minimum_other": 6, "minimum_transitions": 3},
}
DECIMAL_VALUE = re.compile(r"^-?(?:0|[1-9][0-9]*)(?:[.][0-9]+)?$")


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
    absolute = day.year * 12 + day.month - 1 + months
    return date(absolute // 12, absolute % 12 + 1, 1)


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    family_id = "btc-fred-cfnai-above-trend-long-cash"
    if (
        spec.get("document_type")
        != "BTC_FRED_CFNAI_ABOVE_TREND_SOURCE_FEASIBILITY_SPEC_V1"
        or prior.get("document_type")
        != "BTC_FRED_CFNAI_ABOVE_TREND_LONG_CASH_PRIMARY_PRIOR_V1"
        or spec.get("family_id") != family_id
        or prior.get("family_id") != family_id
        or spec.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or prior.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise SourceReject("SPEC_REJECT:IDENTITY_OR_AUTHORIZATION")
    source = spec.get("source_contract", {})
    expected_source = {
        "provider": "FRED",
        "upstream_source": "FEDERAL_RESERVE_BANK_OF_CHICAGO",
        "series": "CFNAI",
        "request_url": SOURCE_URL,
        "transport": "HTTPS_GET_NO_REDIRECT_NO_RETRY_NO_CREDENTIAL",
        "maximum_response_bytes": MAX_RESPONSE_BYTES,
        "expected_header": "observation_date,CFNAI",
        "expected_unique_ordered_month_rows": EXPECTED_ROWS,
        "required_first_date": EXPECTED_FIRST.isoformat(),
        "required_last_date": EXPECTED_LAST.isoformat(),
        "required_calendar_step": "EVERY_CONSECUTIVE_CALENDAR_MONTH_EXACTLY_ON_DAY_1",
        "missing_markers": ["", "."],
        "required_finite_values": EXPECTED_ROWS,
        "revision_boundary": "PRESENT_VINTAGE_ONLY_NO_REAL_TIME_VINTAGE_CLAIM",
        "license_boundary": "COPYRIGHTED_CITATION_REQUIRED_INTERNAL_REPRODUCIBILITY_ONLY",
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    factor = spec.get("factor_contract", {})
    if (
        factor.get("formula") != "CFNAI_MONTH_t"
        or factor.get("comparison")
        != "STRICTLY_GREATER_THAN_ZERO_WITH_EXACT_DECIMAL_COMPARISON"
        or factor.get("effective_time")
        != "OBSERVATION_MONTH_START_PLUS_60_CALENDAR_DAYS_AT_0000_UTC"
        or factor.get("warmup_months") != 0
        or factor.get("expected_evaluations") != EXPECTED_ROWS
    ):
        raise SourceReject("SPEC_REJECT:FACTOR_CONTRACT")
    frozen_gates = spec.get("pre_outcome_support_gates", {})
    for label, gate in SUPPORT_GATES.items():
        frozen_keys = {
            "minimum_above_trend": f"minimum_{label}_above_trend_states",
            "minimum_other": f"minimum_{label}_other_states",
            "minimum_transitions": f"minimum_{label}_state_transitions",
        }
        for key, value in gate.items():
            if frozen_gates.get(frozen_keys[key]) != value:
                raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}:{key}")
    execution = spec.get("execution_contract", {})
    if (
        execution.get("maximum_source_attempts") != 1
        or execution.get("output_mode") != "CREATE_ONCE_UNDER_RESEARCH_STATE"
        or execution.get("oos") != "DENY"
    ):
        raise SourceReject("SPEC_REJECT:EXECUTION_CONTRACT")
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
            "User-Agent": "AgoraResearchFredCfnaiSourceProbe/1.0",
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
    if not rows or rows[0] != ["observation_date", "CFNAI"]:
        raise SourceReject("SOURCE_REJECT:HEADER")
    parsed: list[tuple[date, Decimal, str]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2 or row[1] in {"", "."}:
            raise SourceReject(f"SOURCE_REJECT:MISSING_OR_ROW:{index}")
        if DECIMAL_VALUE.fullmatch(row[1]) is None:
            raise SourceReject(f"SOURCE_REJECT:VALUE_FORMAT:{index}")
        try:
            day = date.fromisoformat(row[0])
            value = Decimal(row[1])
        except (ValueError, InvalidOperation) as error:
            raise SourceReject(f"SOURCE_REJECT:VALUE_OR_DATE:{index}") from error
        if day.day != 1 or not value.is_finite():
            raise SourceReject(f"SOURCE_REJECT:IDENTITY:{index}")
        parsed.append((day, value, row[1]))
    return parsed


def validate_rows(rows: list[tuple[date, Decimal, str]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    days = [row[0] for row in rows]
    if len(set(days)) != len(days) or days != sorted(days):
        raise SourceReject("SOURCE_REJECT:UNIQUE_ORDERED_MONTHS")
    if days[0] != EXPECTED_FIRST or days[-1] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, (prior, current) in enumerate(
        zip(days, days[1:], strict=False), start=1
    ):
        if current != add_months(prior, 1):
            raise SourceReject(f"SOURCE_REJECT:MONTHLY_LATTICE:{index}")


def _longest_run(flags: list[bool], target: bool) -> int:
    longest = current = 0
    for flag in flags:
        current = current + 1 if flag is target else 0
        longest = max(longest, current)
    return longest


def _summarize(
    states: list[tuple[datetime, bool]],
    start: datetime,
    end: datetime,
    gate: dict[str, int],
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    flags = [state[1] for state in selected]
    above_trend = sum(flags)
    other = len(flags) - above_trend
    transitions = sum(a != b for a, b in zip(flags, flags[1:], strict=False))
    annual: dict[str, dict[str, int]] = {}
    for effective, state in selected:
        counts = annual.setdefault(str(effective.year), {"above_trend": 0, "other": 0})
        counts["above_trend" if state else "other"] += 1
    passed = (
        above_trend >= gate["minimum_above_trend"]
        and other >= gate["minimum_other"]
        and transitions >= gate["minimum_transitions"]
    )
    return {
        "evaluations": len(selected),
        "above_trend_months": above_trend,
        "other_months": other,
        "transitions": transitions,
        "longest_above_trend_run_months": _longest_run(flags, True),
        "longest_other_run_months": _longest_run(flags, False),
        "annual_state_counts": annual,
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z") if selected else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z") if selected else None,
        "support_gate": gate,
        "support_pass": passed,
    }


def feature_feasibility(rows: list[tuple[date, Decimal, str]]) -> dict[str, Any]:
    states = [
        (
            datetime.combine(day, time.min, tzinfo=timezone.utc)
            + timedelta(days=AVAILABILITY_DAYS),
            value > 0,
        )
        for day, value, _ in rows
    ]
    design = _summarize(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"])
    validation = _summarize(
        states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"]
    )
    flags = [state[1] for state in states]
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "monthly_observation_count": len(rows),
        "evaluations": len(states),
        "above_trend_months": sum(flags),
        "other_months": sum(not flag for flag in flags),
        "transitions": sum(a != b for a, b in zip(flags, flags[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design,
        "validation": validation,
        "admission_status": (
            "PASS_EXACT_MONTHLY_LATTICE_BOTH_CFNAI_LEVEL_STATES_AND_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS"
            if passed
            else "DATA_REJECT_INADEQUATE_CFNAI_LEVEL_STATE_SUPPORT_OR_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS"
        ),
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
        "observation_date,cfnai_present_vintage\n"
        + "".join(f"{day.isoformat()},{raw_value}\n" for day, _, raw_value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = (
        "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if feasibility["admission_status"].startswith("PASS_")
        else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    )
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_CFNAI_MONTHLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of Chicago via Federal Reserve Bank of St. Louis FRED",
        "series": "CFNAI",
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
            "source_probe": {
                "path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
                "sha256": sha256(Path(__file__).resolve()),
            },
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "rows": len(rows),
            "valid_values": len(rows),
            "missing_values": 0,
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "columns": ["observation_date", "cfnai_present_vintage"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Each monthly observation is conservatively usable only from observation-month start plus 60 calendar days at 00:00 UTC and remains effective until the next state becomes available.",
        "revision_boundary": "The exact FRED CSV is sealed present-vintage history. Original release vintages, estimated unavailable inputs, later revisions and exact publication timestamps remain MISSING_PROOF; a historical pass would still require untouched independent OOS.",
        "interpretation_boundary": "A positive CFNAI means above-trend U.S. activity under the publisher definition, not proof of independent Bitcoin causality.",
        "license_boundary": "FRED marks CFNAI copyrighted with citation required. Exact bytes are retained only as internal reproducibility evidence; redistribution rights are not claimed.",
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
    print(
        json.dumps(
            {
                "status": status,
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "raw_sha256": sha256(raw),
                "normalized_sha256": sha256(normalized),
                "feasibility": feasibility,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
