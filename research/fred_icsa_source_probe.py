#!/usr/bin/env python3
"""Seal official present-vintage FRED ICSA history without BTC outcome access."""

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
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-initial-claims-easing-labor-resilience-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-initial-claims-easing-labor-resilience-long-cash-primary-prior.v1.json"
YEARS = tuple(range(2017, 2025))
SOURCE_URLS = {
    year: f"https://fred.stlouisfed.org/graph/fredgraph.csv?id=ICSA&cosd={year}-01-01&coed={year}-12-31"
    for year in YEARS
}
EXPECTED_WEEKLY_ROWS = 417
EXPECTED_FIRST_WEEK = date(2017, 1, 7)
EXPECTED_LAST_WEEK = date(2024, 12, 28)
CHANGE_LAG_DAYS = 28
PUBLICATION_LAG_DAYS = 7
REQUIRED_CONSECUTIVE_OBSERVATIONS = 8
MAX_PART_BYTES = 64 * 1024
REQUEST_TIMEOUT_SECONDS = 30
INTEGER_VALUE = re.compile(r"^[0-9]+$")
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES: dict[str, dict[str, int | Decimal]] = {
    "design": {
        "minimum_evaluations": 200,
        "minimum_per_state": 40,
        "minimum_transitions": 20,
        "minimum_years_with_both_states": 4,
        "maximum_single_year_state_share": Decimal("0.40"),
    },
    "validation": {
        "minimum_evaluations": 100,
        "minimum_per_state": 20,
        "minimum_transitions": 8,
        "minimum_years_with_both_states": 2,
        "maximum_single_year_state_share": Decimal("0.70"),
    },
}


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
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    if spec.get("status") != "FROZEN_BEFORE_2017_2024_ICSA_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != "btc-fred-initial-claims-easing-labor-resilience-long-cash":
        raise SourceReject("SPEC_REJECT:FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "series": "ICSA",
        "years": list(YEARS),
        "expected_weekly_rows": EXPECTED_WEEKLY_ROWS,
        "expected_first_week": EXPECTED_FIRST_WEEK.isoformat(),
        "expected_last_week": EXPECTED_LAST_WEEK.isoformat(),
        "credentials": "DENY",
        "paid_api": "DENY",
        "redirect": "DENY",
        "automatic_retry": "DENY",
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    feature = spec.get("feature_contract", {})
    if (
        feature.get("lookback_calendar_days") != CHANGE_LAG_DAYS
        or feature.get("required_consecutive_observations")
        != REQUIRED_CONSECUTIVE_OBSERVATIONS
    ):
        raise SourceReject("SPEC_REJECT:FEATURE_CONTRACT")
    windows = spec.get("windows", {})
    for label, gate in SUPPORT_GATES.items():
        for key, expected in gate.items():
            observed = windows.get(label, {}).get(key)
            if isinstance(expected, Decimal):
                try:
                    observed = Decimal(str(observed))
                except InvalidOperation as error:
                    raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}:{key}") from error
            if observed != expected:
                raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}:{key}")
    return spec


def state_path(value: str, *, must_not_exist: bool) -> Path:
    resolved = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    try:
        resolved.relative_to(state_root)
    except ValueError as error:
        raise SourceReject(f"PATH_REJECT:{resolved}") from error
    if must_not_exist and resolved.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{resolved}")
    return resolved


def fetch_part(year: int) -> bytes:
    request = Request(
        SOURCE_URLS[year],
        method="GET",
        headers={
            "Accept": "text/csv",
            "User-Agent": "AgoraResearchFredIcsaSourceProbe/1.0",
        },
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{year}:{response.status}")
            raw = response.read(MAX_PART_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{year}:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_PART_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{year}:{len(raw)}")
    return raw


def parse_annual(raw: bytes, expected_year: int) -> list[tuple[date, int]]:
    try:
        rows = list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))
    except UnicodeDecodeError as error:
        raise SourceReject(f"SOURCE_REJECT:UTF8:{expected_year}") from error
    if not rows or rows[0] != ["observation_date", "ICSA"]:
        raise SourceReject(f"SOURCE_REJECT:HEADER:{expected_year}")
    parsed: list[tuple[date, int]] = []
    for index, row in enumerate(rows[1:]):
        if len(row) != 2:
            raise SourceReject(f"SOURCE_REJECT:ROW:{expected_year}:{index}")
        try:
            week_end = date.fromisoformat(row[0])
        except ValueError as error:
            raise SourceReject(f"SOURCE_REJECT:DATE:{expected_year}:{index}") from error
        if week_end.year != expected_year or week_end.weekday() != 5:
            raise SourceReject(f"SOURCE_REJECT:IDENTITY:{expected_year}:{index}")
        if INTEGER_VALUE.fullmatch(row[1]) is None:
            raise SourceReject(f"SOURCE_REJECT:INTEGER:{expected_year}:{index}")
        value = int(row[1])
        if value <= 0 or value > 10_000_000:
            raise SourceReject(f"SOURCE_REJECT:RANGE:{expected_year}:{index}")
        parsed.append((week_end, value))
    if len(parsed) not in {52, 53}:
        raise SourceReject(f"SOURCE_REJECT:ANNUAL_ROWS:{expected_year}:{len(parsed)}")
    if len({week_end for week_end, _ in parsed}) != len(parsed) or any(
        current[0] <= prior[0]
        for prior, current in zip(parsed, parsed[1:], strict=False)
    ):
        raise SourceReject(f"SOURCE_REJECT:ORDER:{expected_year}")
    return parsed


def validate_weekly_lattice(rows: list[tuple[date, int]]) -> list[tuple[date, int]]:
    weekly = sorted(rows)
    if len(weekly) != EXPECTED_WEEKLY_ROWS:
        raise SourceReject(f"SOURCE_REJECT:WEEKLY_ROWS:{len(weekly)}")
    if weekly[0][0] != EXPECTED_FIRST_WEEK or weekly[-1][0] != EXPECTED_LAST_WEEK:
        raise SourceReject("SOURCE_REJECT:WEEKLY_BOUNDARY")
    if len({week_end for week_end, _ in weekly}) != len(weekly):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_WEEK")
    if any(
        current[0] - prior[0] != timedelta(days=7)
        for prior, current in zip(weekly, weekly[1:], strict=False)
    ):
        raise SourceReject("SOURCE_REJECT:WEEKLY_CONTINUITY")
    return weekly


def _ratio(numerator: int, denominator: int) -> Decimal:
    return Decimal(numerator) / Decimal(denominator) if denominator else Decimal("0")


def _summarize_window(
    states: list[tuple[datetime, bool]],
    start: datetime,
    end: datetime,
    gate: dict[str, int | Decimal],
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    supportive = sum(state[1] for state in selected)
    other = len(selected) - supportive
    transitions = sum(
        current[1] != prior[1]
        for prior, current in zip(selected, selected[1:], strict=False)
    )
    by_year: dict[int, dict[str, int]] = {}
    for effective, is_supportive in selected:
        counts = by_year.setdefault(effective.year, {"supportive": 0, "other": 0})
        counts["supportive" if is_supportive else "other"] += 1
    years_with_both_states = sum(
        counts["supportive"] > 0 and counts["other"] > 0
        for counts in by_year.values()
    )
    max_supportive_share = max(
        (_ratio(counts["supportive"], supportive) for counts in by_year.values()),
        default=Decimal("0"),
    )
    max_other_share = max(
        (_ratio(counts["other"], other) for counts in by_year.values()),
        default=Decimal("0"),
    )
    max_share = max(max_supportive_share, max_other_share)
    passed = (
        len(selected) >= int(gate["minimum_evaluations"])
        and supportive >= int(gate["minimum_per_state"])
        and other >= int(gate["minimum_per_state"])
        and transitions >= int(gate["minimum_transitions"])
        and years_with_both_states >= int(gate["minimum_years_with_both_states"])
        and max_share <= Decimal(gate["maximum_single_year_state_share"])
    )
    return {
        "evaluations": len(selected),
        "supportive_weeks": supportive,
        "other_weeks": other,
        "transitions": transitions,
        "years_with_both_states": years_with_both_states,
        "annual_state_counts": {str(year): counts for year, counts in by_year.items()},
        "maximum_single_year_supportive_share": format(max_supportive_share, ".8f"),
        "maximum_single_year_other_share": format(max_other_share, ".8f"),
        "support_gate": {
            key: format(value, "f") if isinstance(value, Decimal) else value
            for key, value in gate.items()
        },
        "support_pass": passed,
    }


def feature_feasibility(weekly: list[tuple[date, int]]) -> dict[str, Any]:
    states: list[tuple[datetime, bool]] = []
    equal_mean_weeks = 0
    for index in range(REQUIRED_CONSECUTIVE_OBSERVATIONS - 1, len(weekly)):
        current = weekly[index - 3 : index + 1]
        prior = weekly[index - 7 : index - 3]
        if any(
            current_row[0] - prior_row[0] != timedelta(days=CHANGE_LAG_DAYS)
            for prior_row, current_row in zip(prior, current, strict=True)
        ):
            raise SourceReject("SOURCE_REJECT:FEATURE_ALIGNMENT")
        current_sum = sum(value for _, value in current)
        prior_sum = sum(value for _, value in prior)
        equal_mean_weeks += current_sum == prior_sum
        effective = datetime.combine(
            current[-1][0] + timedelta(days=PUBLICATION_LAG_DAYS),
            time.min,
            tzinfo=timezone.utc,
        )
        states.append((effective, current_sum < prior_sum))
    if not states:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    design = _summarize_window(
        states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"]
    )
    validation = _summarize_window(
        states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"]
    )
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "weekly_observation_count": len(weekly),
        "evaluations": len(states),
        "supportive_weeks": sum(state[1] for state in states),
        "other_weeks": sum(not state[1] for state in states),
        "equal_four_week_mean_weeks": equal_mean_weeks,
        "transitions": sum(
            current[1] != prior[1]
            for prior, current in zip(states, states[1:], strict=False)
        ),
        "first_evaluable_week": weekly[REQUIRED_CONSECUTIVE_OBSERVATIONS - 1][0].isoformat(),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_evaluable_week": weekly[-1][0].isoformat(),
        "design": design,
        "validation": validation,
        "admission_status": "PASS_WEEKLY_COVERAGE_STATE_TRANSITIONS_AND_ANNUAL_BREADTH_BEFORE_BTC_OUTCOME_ACCESS"
        if passed
        else "DATA_REJECT_INADEQUATE_STATE_TRANSITIONS_OR_ANNUAL_BREADTH_BEFORE_BTC_OUTCOME_ACCESS",
    }


def deterministic_zip(parts: list[tuple[int, bytes]]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for year, raw in parts:
            info = zipfile.ZipInfo(
                f"fred-icsa-{year}.csv", date_time=(1980, 1, 1, 0, 0, 0)
            )
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, raw)
    return output.getvalue()


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw-archive", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    load_and_validate_spec()
    bundle_path = state_path(args.bundle, must_not_exist=True)
    archive_path = state_path(args.raw_archive, must_not_exist=True)
    normalized_path = state_path(args.normalized, must_not_exist=True)
    if len({bundle_path, archive_path, normalized_path}) != 3:
        raise SourceReject("PATH_REJECT:DUPLICATE")

    all_rows: list[tuple[date, int]] = []
    parts: list[tuple[int, bytes]] = []
    response_parts: list[dict[str, Any]] = []
    for year in YEARS:
        raw = fetch_part(year)
        parsed = parse_annual(raw, year)
        parts.append((year, raw))
        all_rows.extend(parsed)
        response_parts.append(
            {
                "year": year,
                "url": SOURCE_URLS[year],
                "bytes": len(raw),
                "sha256": sha256(raw),
                "published_rows": len(parsed),
            }
        )
    weekly = validate_weekly_lattice(all_rows)
    feasibility = feature_feasibility(weekly)
    raw_archive = deterministic_zip(parts)
    normalized = (
        "week_ending_saturday,icsa_claims\n"
        + "".join(
            f"{week_end.isoformat()},{value}\n" for week_end, value in weekly
        )
    ).encode("utf-8")
    admitted = feasibility["admission_status"].startswith("PASS_")
    bundle = {
        "schema_version": "1",
        "document_type": "FRED_ICSA_WEEKLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if admitted
        else "SEALED_SOURCE_FEASIBILITY_DATA_REJECT_NO_BTC_OUTCOME_ACCESS",
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of St. Louis FRED",
        "source_agency": "U.S. Employment and Training Administration",
        "series": "ICSA",
        "frozen_bindings": {
            "source_feasibility_spec_path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(),
            "source_feasibility_spec_sha256": sha256(SPEC_PATH),
            "primary_prior_path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(),
            "primary_prior_sha256": sha256(PRIOR_PATH),
            "source_probe_path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(),
            "source_probe_sha256": sha256(Path(__file__).resolve()),
        },
        "request_contract": {
            "method": "GET",
            "urls": [SOURCE_URLS[year] for year in YEARS],
            "credentials": "DENY",
            "redirect": "DENY",
            "automatic_retry": "DENY",
            "maximum_response_bytes_per_request": MAX_PART_BYTES,
        },
        "raw_response_archive": {
            "path": archive_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw_archive),
            "sha256": sha256(raw_archive),
            "format": "DETERMINISTIC_ZIP_STORED_EXACT_RESPONSE_BODIES",
            "parts": response_parts,
        },
        "normalized_weekly_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(weekly),
            "first_week": weekly[0][0].isoformat(),
            "last_week": weekly[-1][0].isoformat(),
            "columns": ["week_ending_saturday", "icsa_claims"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Week ending Saturday D is usable only at D plus seven calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "aggregation_boundary": "Each factor comparison uses exactly eight consecutive published weekly observations: the current four-week arithmetic mean versus the four-week mean ending 28 calendar days earlier.",
        "measurement_boundary": "ICSA is a volatile seasonally adjusted administrative leading indicator; declining claims do not establish Bitcoin return causality.",
        "revision_boundary": "The exact annual FRED responses are a sealed present-vintage snapshot. Original advance estimates and complete revision vintages remain MISSING_PROOF.",
        "scope_note": "No BTC outcome, hypothesis, strategy economics, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    write_create_once(archive_path, raw_archive)
    try:
        write_create_once(normalized_path, normalized)
        write_create_once(bundle_path, bundle_raw)
    except Exception:
        archive_path.unlink(missing_ok=True)
        normalized_path.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256(bundle_raw),
                "raw_archive_sha256": sha256(raw_archive),
                "normalized_sha256": sha256(normalized),
                "weekly_rows": len(weekly),
                "feasibility": feasibility,
            },
            separators=(",", ":"),
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
