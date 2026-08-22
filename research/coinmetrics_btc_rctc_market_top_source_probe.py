#!/usr/bin/env python3
"""Seal free Coin Metrics BTC RCTC history and test support before BTC outcomes."""

from __future__ import annotations

import argparse
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
import re
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-rctc-market-top-risk-veto-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-rctc-market-top-risk-veto-primary-prior.v1.json"
SOURCE_URL = "https://community-api.coinmetrics.io/v4/timeseries/asset-metrics?assets=btc&metrics=RCTC&frequency=1d&start_time=2018-01-01&end_time=2024-12-31&page_size=10000"
EXPECTED_ROWS = 2557
EXPECTED_FIRST = date(2018, 1, 1)
EXPECTED_LAST = date(2024, 12, 31)
MAX_RESPONSE_BYTES = 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
THRESHOLD = Decimal("10")
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES: dict[str, dict[str, int | Decimal]] = {
    "design": {
        "minimum_evaluations": 200,
        "minimum_risk_off_weeks": 20,
        "minimum_risk_on_weeks": 80,
        "minimum_transitions": 4,
        "minimum_calendar_years_risk_off": 2,
        "minimum_calendar_years_risk_on": 3,
        "maximum_single_calendar_year_risk_off_share": Decimal("0.70"),
    },
    "validation": {
        "minimum_evaluations": 100,
        "minimum_risk_off_weeks": 10,
        "minimum_risk_on_weeks": 40,
        "minimum_transitions": 2,
        "minimum_calendar_years_risk_off": 1,
        "minimum_calendar_years_risk_on": 1,
        "maximum_single_calendar_year_risk_off_share": Decimal("0.80"),
    },
}
DECIMAL_VALUE = re.compile(r"^(?:0|[1-9][0-9]*)(?:[.][0-9]+)?(?:[eE][+-]?[0-9]+)?$")
TIME_VALUE = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T00:00:00(?:[.]0+)?Z$")


class SourceReject(RuntimeError):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        raise SourceReject(f"SOURCE_REJECT:REDIRECT:{code}:{newurl}")


def sha256(raw_or_path: bytes | Path) -> str:
    raw = raw_or_path.read_bytes() if isinstance(raw_or_path, Path) else raw_or_path
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True) + "\n").encode("utf-8")


def load_and_validate_spec() -> dict[str, Any]:
    try:
        spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
        prior = json.loads(PRIOR_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise SourceReject("SPEC_REJECT:READ_OR_JSON") from error
    family_id = "btc-coinmetrics-rctc-market-top-risk-veto-long-cash"
    if spec.get("status") != "FROZEN_BEFORE_2018_2024_RCTC_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != family_id or prior.get("family_id") != family_id:
        raise SourceReject("SPEC_REJECT:FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "metric": "RCTC",
        "asset": "btc",
        "frequency": "1d",
        "start_date": EXPECTED_FIRST.isoformat(),
        "end_date": EXPECTED_LAST.isoformat(),
        "expected_rows": EXPECTED_ROWS,
        "expected_first_date": EXPECTED_FIRST.isoformat(),
        "expected_last_date": EXPECTED_LAST.isoformat(),
        "credentials": "DENY",
        "paid_api": "DENY",
        "redirect": "DENY",
        "retry": "DENY",
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    feature = spec.get("feature_contract", {})
    if feature.get("threshold") != "10_EXACT_FROM_PRIMARY_SOURCE" or feature.get("maximum_validity_hours") != 168:
        raise SourceReject("SPEC_REJECT:FEATURE_CONTRACT")
    for label, gate in SUPPORT_GATES.items():
        frozen = spec.get("pre_outcome_support_gates", {}).get(label, {})
        for key, value in gate.items():
            expected = str(value) if isinstance(value, Decimal) else value
            if frozen.get(key) != expected:
                raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{label}:{key}")
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


def fetch() -> tuple[bytes, dict[str, str | None]]:
    request = Request(
        SOURCE_URL,
        method="GET",
        headers={"Accept": "application/json", "User-Agent": "AgoraResearchCoinMetricsBtcRctcSourceProbe/1.0"},
    )
    try:
        with build_opener(NoRedirect()).open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
            metadata = {
                "content_type": response.headers.get("content-type"),
                "etag": response.headers.get("etag"),
                "last_modified": response.headers.get("last-modified"),
            }
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}") from error
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw, metadata


def parse_rows(raw: bytes) -> list[tuple[date, Decimal, str]]:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceReject("SOURCE_REJECT:JSON") from error
    if not isinstance(payload, dict) or set(payload) != {"data"} or not isinstance(payload["data"], list):
        raise SourceReject("SOURCE_REJECT:ENVELOPE_OR_PAGINATION")
    rows: list[tuple[date, Decimal, str]] = []
    for index, row in enumerate(payload["data"]):
        if not isinstance(row, dict) or set(row) != {"asset", "time", "RCTC"}:
            raise SourceReject(f"SOURCE_REJECT:ROW_KEYS:{index}")
        raw_value = row["RCTC"]
        if row["asset"] != "btc" or not isinstance(row["time"], str) or TIME_VALUE.fullmatch(row["time"]) is None:
            raise SourceReject(f"SOURCE_REJECT:IDENTITY:{index}")
        if not isinstance(raw_value, str) or DECIMAL_VALUE.fullmatch(raw_value) is None:
            raise SourceReject(f"SOURCE_REJECT:RCTC_DECIMAL:{index}")
        try:
            day = date.fromisoformat(row["time"][:10])
            value = Decimal(raw_value)
        except (ValueError, InvalidOperation) as error:
            raise SourceReject(f"SOURCE_REJECT:RCTC_VALUE:{index}") from error
        if not value.is_finite() or value <= 0 or value > 1000:
            raise SourceReject(f"SOURCE_REJECT:RCTC_RANGE:{index}")
        rows.append((day, value, raw_value))
    return rows


def validate_rows(rows: list[tuple[date, Decimal, str]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if rows[0][0] != EXPECTED_FIRST or rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, (prior, current) in enumerate(zip(rows, rows[1:], strict=False), start=1):
        if current[0] - prior[0] != timedelta(days=1):
            raise SourceReject(f"SOURCE_REJECT:DAILY_CONTINUITY:{index}")


def _longest_run(flags: list[bool], target: bool) -> int:
    longest = current = 0
    for flag in flags:
        current = current + 1 if flag is target else 0
        longest = max(longest, current)
    return longest


def _summarize(
    states: list[tuple[datetime, bool]], start: datetime, end: datetime, gate: dict[str, int | Decimal]
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    flags = [state[1] for state in selected]
    risk_off = sum(flags)
    risk_on = len(flags) - risk_off
    transitions = sum(current != prior for prior, current in zip(flags, flags[1:], strict=False))
    annual: dict[str, dict[str, int]] = {}
    for effective, is_risk_off in selected:
        counts = annual.setdefault(str(effective.year), {"risk_off": 0, "risk_on": 0})
        counts["risk_off" if is_risk_off else "risk_on"] += 1
    risk_off_years = sum(counts["risk_off"] > 0 for counts in annual.values())
    risk_on_years = sum(counts["risk_on"] > 0 for counts in annual.values())
    top_risk_off_year = max((counts["risk_off"] for counts in annual.values()), default=0)
    top_risk_off_share = Decimal(top_risk_off_year) / Decimal(risk_off) if risk_off else Decimal("1")
    gate_results = {
        "minimum_evaluations": len(selected) >= int(gate["minimum_evaluations"]),
        "minimum_risk_off_weeks": risk_off >= int(gate["minimum_risk_off_weeks"]),
        "minimum_risk_on_weeks": risk_on >= int(gate["minimum_risk_on_weeks"]),
        "minimum_transitions": transitions >= int(gate["minimum_transitions"]),
        "minimum_calendar_years_risk_off": risk_off_years >= int(gate["minimum_calendar_years_risk_off"]),
        "minimum_calendar_years_risk_on": risk_on_years >= int(gate["minimum_calendar_years_risk_on"]),
        "maximum_single_calendar_year_risk_off_share": top_risk_off_share <= gate["maximum_single_calendar_year_risk_off_share"],
    }
    return {
        "evaluations": len(selected),
        "risk_off_weeks": risk_off,
        "risk_on_weeks": risk_on,
        "transitions": transitions,
        "risk_off_calendar_years": risk_off_years,
        "risk_on_calendar_years": risk_on_years,
        "longest_risk_off_run_weeks": _longest_run(flags, True),
        "longest_risk_on_run_weeks": _longest_run(flags, False),
        "annual_state_counts": annual,
        "top_calendar_year_risk_off_count": top_risk_off_year,
        "top_calendar_year_risk_off_share": format(top_risk_off_share, ".8f"),
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z") if selected else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z") if selected else None,
        "support_gate": {key: str(value) if isinstance(value, Decimal) else value for key, value in gate.items()},
        "gate_results": gate_results,
        "support_pass": all(gate_results.values()),
    }


def feature_feasibility(rows: list[tuple[date, Decimal, str]]) -> dict[str, Any]:
    states = [
        (datetime.combine(day + timedelta(days=3), datetime.min.time(), tzinfo=timezone.utc), value >= THRESHOLD)
        for day, value, _ in rows
        if day.weekday() == 6
    ]
    design = _summarize(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"])
    validation = _summarize(states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"])
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "daily_observation_count": len(rows),
        "weekly_evaluations": len(states),
        "risk_off_weeks": sum(state[1] for state in states),
        "risk_on_weeks": sum(not state[1] for state in states),
        "transitions": sum(current[1] != prior[1] for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design,
        "validation": validation,
        "admission_status": "PASS_DAILY_COVERAGE_THRESHOLD_TEN_BOTH_STATE_SUPPORT_TRANSITIONS_AND_BREADTH_BEFORE_BTC_OUTCOME_ACCESS" if passed else "DATA_REJECT_THRESHOLD_TEN_STATE_SUPPORT_TRANSITIONS_OR_BREADTH_BEFORE_BTC_OUTCOME_ACCESS",
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
    raw, metadata = fetch()
    rows = parse_rows(raw)
    validate_rows(rows)
    normalized = ("date,rctc\n" + "".join(f"{day.isoformat()},{raw_value}\n" for day, _, raw_value in rows)).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS" if feasibility["admission_status"].startswith("PASS_") else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    bundle = {
        "schema_version": "1",
        "document_type": "COIN_METRICS_BTC_RCTC_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Coin Metrics Community API",
        "metric": "RCTC",
        "request_contract": {
            "method": "GET", "url": SOURCE_URL, "credentials": "DENY", "transport": "Python urllib",
            "redirect": "DENY", "retry": "DENY", "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "frozen_bindings": {
            "source_feasibility_spec": {"path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(SPEC_PATH)},
            "primary_prior": {"path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(PRIOR_PATH)},
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(), "bytes": len(raw), "sha256": sha256(raw),
            "rows": len(rows), **metadata,
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(), "bytes": len(normalized),
            "sha256": sha256(normalized), "rows": len(rows), "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(), "columns": ["date", "rctc"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Use only complete Sunday RCTC and treat it as usable no earlier than Wednesday 00:00 UTC for at most 168 hours.",
        "revision_boundary": "The exact Community API response is a sealed present-vintage history. Original daily publication timestamps, reviews and revisions remain MISSING_PROOF; any historical pass remains discovery and requires untouched independent OOS.",
        "interpretation_boundary": "RCTC compares realized holder cost basis with cumulative miner revenue. It is not observed miner selling, liquidity or an independent causal market-top label.",
        "license_boundary": "Coin Metrics documents Community API data as no-key and free for non-commercial use under a Creative Commons license. Exact bytes remain internal under untracked .research-state; commercial reuse or redistribution is not authorized by this audit.",
        "scope_note": "Free source and pre-outcome state-support check only. No BTC outcome, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle)
    created: list[Path] = []
    try:
        write_create_once(raw_path, raw); created.append(raw_path)
        write_create_once(normalized_path, normalized); created.append(normalized_path)
        write_create_once(bundle_path, bundle_raw); created.append(bundle_path)
    except Exception:
        for target in created:
            target.unlink(missing_ok=True)
        raise
    print(json.dumps({
        "status": status, "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
        "bundle_sha256": sha256(bundle_raw), "raw_sha256": sha256(raw),
        "normalized_sha256": sha256(normalized), "feasibility": feasibility,
    }, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
