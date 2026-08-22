#!/usr/bin/env python3
"""Seal public Deribit BTC DVOL daily history before BTC outcome access."""

from __future__ import annotations

import argparse
import base64
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import json
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-deribit-dvol-rising-half-risk-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-deribit-dvol-rising-half-risk-primary-prior.v1.json"
ENDPOINT = "https://www.deribit.com/api/v2/public/get_volatility_index_data"
START_TIMESTAMP_MS = 1617235200000
END_TIMESTAMP_MS = 1735689599999
DAY_MS = 86_400_000
MAX_PAGES = 4
MAX_RESPONSE_BYTES_PER_PAGE = 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2021, 4, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES: dict[str, dict[str, int | Decimal]] = {
    "design": {
        "minimum_evaluations": 80,
        "minimum_per_state": 25,
        "minimum_transitions": 20,
        "minimum_calendar_years_per_state": 2,
        "maximum_single_calendar_year_high_state_share": Decimal("0.75"),
    },
    "validation": {
        "minimum_evaluations": 100,
        "minimum_per_state": 30,
        "minimum_transitions": 25,
        "minimum_calendar_years_per_state": 2,
        "maximum_single_calendar_year_high_state_share": Decimal("0.75"),
    },
}


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
    family_id = "btc-deribit-dvol-rising-half-risk"
    if spec.get("status") != "FROZEN_BEFORE_2021_2024_DVOL_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != family_id or prior.get("family_id") != family_id:
        raise SourceReject("SPEC_REJECT:FAMILY")
    source = spec.get("source_contract", {})
    expected_source = {
        "method": "GET", "endpoint": ENDPOINT, "currency": "BTC", "resolution": "1D",
        "start_timestamp_ms": START_TIMESTAMP_MS, "end_timestamp_ms": END_TIMESTAMP_MS,
        "credentials": "DENY", "paid_api": "DENY", "redirect": "DENY", "automatic_retry": "DENY",
        "maximum_pages": MAX_PAGES, "maximum_response_bytes_per_page": MAX_RESPONSE_BYTES_PER_PAGE,
        "continuation_rule": "NEXT_END_TIMESTAMP_EQUALS_PRIOR_NON_NULL_CONTINUATION_AND_MUST_STRICTLY_DECREASE",
    }
    if any(source.get(key) != value for key, value in expected_source.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    feature = spec.get("feature_contract", {})
    if feature.get("comparison_lag_days") != 7 or feature.get("maximum_validity_hours") != 168:
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


def request_url(end_timestamp_ms: int) -> str:
    return ENDPOINT + "?" + urlencode({
        "currency": "BTC", "start_timestamp": START_TIMESTAMP_MS,
        "end_timestamp": end_timestamp_ms, "resolution": "1D",
    })


def fetch_page(end_timestamp_ms: int) -> tuple[str, bytes, dict[str, str | None]]:
    url = request_url(end_timestamp_ms)
    request = Request(url, method="GET", headers={"Accept": "application/json", "User-Agent": "AgoraResearchDeribitBtcDvolSourceProbe/1.0"})
    try:
        with build_opener(NoRedirect()).open(request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            raw = response.read(MAX_RESPONSE_BYTES_PER_PAGE + 1)
            metadata = {
                "content_type": response.headers.get("content-type"),
                "etag": response.headers.get("etag"),
                "last_modified": response.headers.get("last-modified"),
            }
    except SourceReject:
        raise
    except HTTPError as error:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{error.code}") from error
    except (URLError, TimeoutError, OSError) as error:
        raise SourceReject(f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}") from error
    if not raw or len(raw) > MAX_RESPONSE_BYTES_PER_PAGE:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return url, raw, metadata


def _decimal(value: Any, page: int, row: int, column: int) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (int, float, str)):
        raise SourceReject(f"SOURCE_REJECT:CANDLE_TYPE:{page}:{row}:{column}")
    try:
        parsed = Decimal(str(value))
    except InvalidOperation as error:
        raise SourceReject(f"SOURCE_REJECT:CANDLE_DECIMAL:{page}:{row}:{column}") from error
    if not parsed.is_finite() or parsed <= 0 or parsed > 1000:
        raise SourceReject(f"SOURCE_REJECT:CANDLE_RANGE:{page}:{row}:{column}")
    return parsed


def parse_page(raw: bytes, page_index: int) -> tuple[list[tuple[int, Decimal, Decimal, Decimal, Decimal]], int | None]:
    try:
        payload = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SourceReject(f"SOURCE_REJECT:JSON:{page_index}") from error
    if not isinstance(payload, dict) or payload.get("jsonrpc") != "2.0" or not isinstance(payload.get("result"), dict):
        raise SourceReject(f"SOURCE_REJECT:ENVELOPE:{page_index}")
    if "error" in payload:
        raise SourceReject(f"SOURCE_REJECT:RPC_ERROR:{page_index}")
    result = payload["result"]
    if set(result) != {"data", "continuation"} or not isinstance(result["data"], list):
        raise SourceReject(f"SOURCE_REJECT:RESULT:{page_index}")
    continuation = result["continuation"]
    if continuation is not None and (isinstance(continuation, bool) or not isinstance(continuation, int)):
        raise SourceReject(f"SOURCE_REJECT:CONTINUATION:{page_index}")
    rows: list[tuple[int, Decimal, Decimal, Decimal, Decimal]] = []
    for row_index, row in enumerate(result["data"]):
        if not isinstance(row, list) or len(row) != 5 or isinstance(row[0], bool) or not isinstance(row[0], int):
            raise SourceReject(f"SOURCE_REJECT:CANDLE_SHAPE:{page_index}:{row_index}")
        timestamp = row[0]
        if timestamp % DAY_MS != 0 or timestamp < START_TIMESTAMP_MS or timestamp > END_TIMESTAMP_MS:
            raise SourceReject(f"SOURCE_REJECT:CANDLE_TIME:{page_index}:{row_index}")
        open_value, high, low, close = (_decimal(row[column], page_index, row_index, column) for column in range(1, 5))
        if high < max(open_value, close) or low > min(open_value, close) or high < low:
            raise SourceReject(f"SOURCE_REJECT:CANDLE_OHLC:{page_index}:{row_index}")
        rows.append((timestamp, open_value, high, low, close))
    return rows, continuation


def fetch_all_pages() -> tuple[list[tuple[int, Decimal, Decimal, Decimal, Decimal]], list[dict[str, Any]]]:
    current_end = END_TIMESTAMP_MS
    pages: list[dict[str, Any]] = []
    by_timestamp: dict[int, tuple[int, Decimal, Decimal, Decimal, Decimal]] = {}
    for page_index in range(MAX_PAGES):
        url, raw, metadata = fetch_page(current_end)
        rows, continuation = parse_page(raw, page_index)
        if not rows:
            raise SourceReject(f"SOURCE_REJECT:EMPTY_PAGE:{page_index}")
        pages.append({
            "page": page_index + 1, "url": url, "bytes": len(raw), "sha256": sha256(raw),
            "raw_base64": base64.b64encode(raw).decode("ascii"), "rows": len(rows),
            "continuation": continuation, **metadata,
        })
        for row in rows:
            existing = by_timestamp.get(row[0])
            if existing is not None and existing != row:
                raise SourceReject(f"SOURCE_REJECT:CONFLICTING_DUPLICATE:{row[0]}")
            by_timestamp[row[0]] = row
        if continuation is None:
            return sorted(by_timestamp.values()), pages
        if continuation >= current_end or continuation < START_TIMESTAMP_MS:
            raise SourceReject(f"SOURCE_REJECT:CONTINUATION_ORDER:{page_index}")
        current_end = continuation
    raise SourceReject("SOURCE_REJECT:MAX_PAGES_EXCEEDED")


def validate_rows(rows: list[tuple[int, Decimal, Decimal, Decimal, Decimal]]) -> None:
    if not rows:
        raise SourceReject("SOURCE_REJECT:NO_ROWS")
    expected_first = int(datetime(2021, 4, 1, tzinfo=timezone.utc).timestamp() * 1000)
    expected_last = int(datetime(2024, 12, 31, tzinfo=timezone.utc).timestamp() * 1000)
    if rows[0][0] != expected_first or rows[-1][0] != expected_last:
        raise SourceReject(f"SOURCE_REJECT:BOUNDARY:{rows[0][0]}:{rows[-1][0]}")
    for index, (prior, current) in enumerate(zip(rows, rows[1:], strict=False), start=1):
        if current[0] - prior[0] != DAY_MS:
            raise SourceReject(f"SOURCE_REJECT:DAILY_CONTINUITY:{index}")


def _summarize(states: list[tuple[datetime, bool]], start: datetime, end: datetime, gate: dict[str, int | Decimal]) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    flags = [state[1] for state in selected]
    high = sum(flags); other = len(flags) - high
    transitions = sum(current != prior for prior, current in zip(flags, flags[1:], strict=False))
    annual: dict[str, dict[str, int]] = {}
    for effective, is_high in selected:
        counts = annual.setdefault(str(effective.year), {"rising_dvol": 0, "non_rising_dvol": 0})
        counts["rising_dvol" if is_high else "non_rising_dvol"] += 1
    high_years = sum(counts["rising_dvol"] > 0 for counts in annual.values())
    other_years = sum(counts["non_rising_dvol"] > 0 for counts in annual.values())
    top_high = max((counts["rising_dvol"] for counts in annual.values()), default=0)
    top_share = Decimal(top_high) / Decimal(high) if high else Decimal("1")
    results = {
        "minimum_evaluations": len(selected) >= int(gate["minimum_evaluations"]),
        "minimum_per_state": high >= int(gate["minimum_per_state"]) and other >= int(gate["minimum_per_state"]),
        "minimum_transitions": transitions >= int(gate["minimum_transitions"]),
        "minimum_calendar_years_per_state": high_years >= int(gate["minimum_calendar_years_per_state"]) and other_years >= int(gate["minimum_calendar_years_per_state"]),
        "maximum_single_calendar_year_high_state_share": top_share <= gate["maximum_single_calendar_year_high_state_share"],
    }
    return {
        "evaluations": len(selected), "rising_dvol_weeks": high, "non_rising_dvol_weeks": other,
        "transitions": transitions, "rising_dvol_calendar_years": high_years, "non_rising_dvol_calendar_years": other_years,
        "annual_state_counts": annual, "top_calendar_year_rising_count": top_high,
        "top_calendar_year_rising_share": format(top_share, ".8f"),
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z") if selected else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z") if selected else None,
        "support_gate": {key: str(value) if isinstance(value, Decimal) else value for key, value in gate.items()},
        "gate_results": results, "support_pass": all(results.values()),
    }


def feature_feasibility(rows: list[tuple[int, Decimal, Decimal, Decimal, Decimal]]) -> dict[str, Any]:
    closes = {timestamp: close for timestamp, _, _, _, close in rows}
    states: list[tuple[datetime, bool]] = []
    for timestamp, _, _, _, close in rows:
        current = datetime.fromtimestamp(timestamp / 1000, tz=timezone.utc)
        if current.weekday() != 6:
            continue
        prior = closes.get(timestamp - 7 * DAY_MS)
        if prior is None:
            continue
        states.append((current + timedelta(days=1), close > prior))
    if not states:
        raise SourceReject("SOURCE_REJECT:NO_WEEKLY_STATES")
    design = _summarize(states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"])
    validation = _summarize(states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"])
    passed = design["support_pass"] and validation["support_pass"]
    return {
        "daily_observation_count": len(rows), "weekly_evaluations": len(states),
        "rising_dvol_weeks": sum(state[1] for state in states), "non_rising_dvol_weeks": sum(not state[1] for state in states),
        "transitions": sum(current[1] != prior[1] for prior, current in zip(states, states[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design, "validation": validation,
        "admission_status": "PASS_DAILY_DVOL_COVERAGE_BOTH_WEEKLY_STATE_SUPPORT_TRANSITIONS_AND_BREADTH_BEFORE_BTC_OUTCOME_ACCESS" if passed else "DATA_REJECT_DAILY_DVOL_COVERAGE_STATE_SUPPORT_TRANSITIONS_OR_BREADTH_BEFORE_BTC_OUTCOME_ACCESS",
    }


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True); parser.add_argument("--raw", required=True); parser.add_argument("--normalized", required=True)
    args = parser.parse_args(); load_and_validate_spec()
    bundle_path = output_path(args.bundle); raw_path = output_path(args.raw); normalized_path = output_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")
    rows, pages = fetch_all_pages(); validate_rows(rows)
    raw_envelope = canonical_bytes({"schema_version": "1", "document_type": "DERIBIT_BTC_DVOL_RAW_PAGE_ENVELOPE_V1", "pages": pages})
    normalized = ("date,dvol_open,dvol_high,dvol_low,dvol_close\n" + "".join(
        f"{datetime.fromtimestamp(timestamp / 1000, tz=timezone.utc).date().isoformat()},{open_value},{high},{low},{close}\n"
        for timestamp, open_value, high, low, close in rows
    )).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS" if feasibility["admission_status"].startswith("PASS_") else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    bundle = {
        "schema_version": "1", "document_type": "DERIBIT_BTC_DVOL_DAILY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE", "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"), "publisher": "Deribit", "metric": "BTC_DVOL",
        "request_contract": {"method": "GET", "endpoint": ENDPOINT, "credentials": "DENY", "redirect": "DENY", "retry": "DENY", "resolution": "1D", "maximum_pages": MAX_PAGES},
        "frozen_bindings": {"source_feasibility_spec": {"path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(SPEC_PATH)}, "primary_prior": {"path": PRIOR_PATH.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(PRIOR_PATH)}},
        "raw_response": {"path": raw_path.relative_to(REPO_ROOT).as_posix(), "bytes": len(raw_envelope), "sha256": sha256(raw_envelope), "pages": len(pages), "page_response_hashes": [page["sha256"] for page in pages]},
        "normalized_subset": {"path": normalized_path.relative_to(REPO_ROOT).as_posix(), "bytes": len(normalized), "sha256": sha256(normalized), "rows": len(rows), "first_date": datetime.fromtimestamp(rows[0][0] / 1000, tz=timezone.utc).date().isoformat(), "last_date": datetime.fromtimestamp(rows[-1][0] / 1000, tz=timezone.utc).date().isoformat(), "columns": ["date", "dvol_open", "dvol_high", "dvol_low", "dvol_close"]},
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Use only a complete Sunday UTC 1D close, compare it with the exact prior Sunday, and treat the state as usable Monday 00:00 UTC for at most 168 hours.",
        "revision_boundary": "The exact public API pages are sealed present-vintage history. Original DVOL methodology parameter vintages, fallback-price provenance and revisions remain MISSING_PROOF; any historical pass requires untouched independent OOS.",
        "interpretation_boundary": "Rising DVOL indicates rising expected movement, not bearish direction. The later economic policy, if admitted, can test risk sizing only.",
        "license_boundary": "The public endpoint is used only for internal personal research reproducibility. Commercial redistribution or reuse rights remain MISSING_PROOF.",
        "scope_note": "No BTC outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_raw = canonical_bytes(bundle); created: list[Path] = []
    try:
        write_create_once(raw_path, raw_envelope); created.append(raw_path)
        write_create_once(normalized_path, normalized); created.append(normalized_path)
        write_create_once(bundle_path, bundle_raw); created.append(bundle_path)
    except Exception:
        for target in created:
            target.unlink(missing_ok=True)
        raise
    print(json.dumps({"status": status, "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(), "bundle_sha256": sha256(bundle_raw), "raw_sha256": sha256(raw_envelope), "normalized_sha256": sha256(normalized), "pages": len(pages), "feasibility": feasibility}, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
