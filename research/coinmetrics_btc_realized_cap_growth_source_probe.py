#!/usr/bin/env python3
"""Freeze a point-in-time Coin Metrics BTC realized-cap source subset without outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_HALF_EVEN, getcontext
import hashlib
import io
import json
from pathlib import Path
from typing import Any
import urllib.error
import urllib.request


REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_COMMIT = "f1a36afb962731c387bb03982758ab0103063da5"
SOURCE_URL = f"https://raw.githubusercontent.com/coinmetrics/data/{SOURCE_COMMIT}/csv/btc.csv"
EXPECTED_RAW_SHA256 = "06495ff8e643432e6948b7b4686ce44fc106217287dabdc1b38351d9ddec46c3"
EXPECTED_HEADER_SHA256 = "6043a8da88d16cdf622309b58a92d05034b044a991f2c24b1abbc85e4b7415f8"
EXPECTED_SUBSET_ROWS = 2_557
MAX_RESPONSE_BYTES = 4 * 1024 * 1024
TIMEOUT_SECONDS = 30
D = Decimal
ZERO = D("0")
REALIZED_QUANTUM = D("0.00000001")
getcontext().prec = 50


class SourceReject(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request: Any, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> None:
        return None


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def canonical(value: Any) -> Any:
    if isinstance(value, dict):
        return {key: canonical(value[key]) for key in sorted(value)}
    if isinstance(value, list):
        return [canonical(item) for item in value]
    return value


def decimal_text(value: D) -> str:
    return format(value, "f")


def fetch_source() -> tuple[bytes, dict[str, str | int | None]]:
    request = urllib.request.Request(
        SOURCE_URL,
        method="GET",
        headers={
            "Accept": "text/csv",
            "User-Agent": "AgoraResearchCoinMetricsRealizedCapSourceProbe/1.0",
        },
    )
    opener = urllib.request.build_opener(NoRedirect())
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            status = response.status
            value = response.read(MAX_RESPONSE_BYTES + 1)
            metadata = {
                "status": status,
                "content_type": response.headers.get("content-type"),
                "etag": response.headers.get("etag"),
                "last_modified": response.headers.get("last-modified"),
            }
    except urllib.error.HTTPError as error:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{error.code}") from error
    if status != 200:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{status}")
    if not value or len(value) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(value)}")
    if sha256_bytes(value) != EXPECTED_RAW_SHA256:
        raise SourceReject("SOURCE_REJECT:PINNED_RAW_SHA256")
    return value, metadata


def parse_subset(raw: bytes) -> list[dict[str, str]]:
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        raise SourceReject("SOURCE_REJECT:UTF8") from error
    header = text.splitlines()[0].strip()
    if sha256_bytes(header.encode("utf-8")) != EXPECTED_HEADER_SHA256:
        raise SourceReject("SOURCE_REJECT:HEADER_SHA256")
    reader = csv.DictReader(io.StringIO(text))
    required = {"time", "CapMrktCurUSD", "CapMVRVCur"}
    if reader.fieldnames is None or not required.issubset(reader.fieldnames):
        raise SourceReject("SOURCE_REJECT:REQUIRED_COLUMNS")
    rows: list[dict[str, str]] = []
    for source_index, row in enumerate(reader, start=2):
        date = row.get("time", "")
        if not ("2018-01-01" <= date <= "2024-12-31"):
            continue
        try:
            market_cap = D(row.get("CapMrktCurUSD", ""))
            mvrv = D(row.get("CapMVRVCur", ""))
        except Exception as error:
            raise SourceReject(f"SOURCE_REJECT:DECIMAL:{source_index}") from error
        if not market_cap.is_finite() or not mvrv.is_finite() or market_cap <= ZERO or mvrv <= ZERO:
            raise SourceReject(f"SOURCE_REJECT:NONPOSITIVE:{source_index}")
        realized_cap = (market_cap / mvrv).quantize(REALIZED_QUANTUM, rounding=ROUND_HALF_EVEN)
        rows.append(
            {
                "date": date,
                "cap_mrkt_cur_usd": decimal_text(market_cap),
                "mvrv": decimal_text(mvrv),
                "cap_real_usd": decimal_text(realized_cap),
            }
        )
    if len(rows) != EXPECTED_SUBSET_ROWS:
        raise SourceReject(f"SOURCE_REJECT:SUBSET_ROWS:{len(rows)}")
    if rows[0]["date"] != "2018-01-01" or rows[-1]["date"] != "2024-12-31":
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index in range(1, len(rows)):
        previous = datetime.fromisoformat(rows[index - 1]["date"])
        current = datetime.fromisoformat(rows[index]["date"])
        if current - previous != timedelta(days=1):
            raise SourceReject(f"SOURCE_REJECT:DAILY_CONTINUITY:{index}")
    return rows


def normalized_bytes(rows: list[dict[str, str]]) -> bytes:
    output = io.StringIO(newline="")
    writer = csv.DictWriter(
        output,
        fieldnames=["date", "cap_mrkt_cur_usd", "mvrv", "cap_real_usd"],
        lineterminator="\n",
    )
    writer.writeheader()
    writer.writerows(rows)
    return output.getvalue().encode("utf-8")


def feature_feasibility(rows: list[dict[str, str]]) -> dict[str, Any]:
    values = [D(row["cap_real_usd"]) for row in rows]
    states: list[dict[str, Any]] = []
    for index in range(28, len(rows)):
        observed = datetime.fromisoformat(rows[index]["date"])
        if observed.weekday() != 6:
            continue
        effective = observed + timedelta(days=3)
        if not (datetime(2019, 1, 1) <= effective < datetime(2025, 1, 1)):
            continue
        growth = values[index] / values[index - 28] - D("1")
        states.append(
            {
                "window": "DESIGN" if effective < datetime(2023, 1, 1) else "VALIDATION",
                "positive": growth > ZERO,
                "observed": observed,
                "effective": effective,
            }
        )
    result: dict[str, Any] = {}
    for label in ("DESIGN", "VALIDATION"):
        selected = [item for item in states if item["window"] == label]
        transitions = sum(
            selected[index]["positive"] != selected[index - 1]["positive"]
            for index in range(1, len(selected))
        )
        result[label.lower()] = {
            "evaluations": len(selected),
            "positive_growth_weeks": sum(item["positive"] for item in selected),
            "nonpositive_growth_weeks": sum(not item["positive"] for item in selected),
            "transitions": transitions,
            "first_observation": selected[0]["observed"].date().isoformat(),
            "first_effective_at": selected[0]["effective"].isoformat(),
            "last_observation": selected[-1]["observed"].date().isoformat(),
            "last_effective_at": selected[-1]["effective"].isoformat(),
        }
    return result


def require_output_path(value: str) -> Path:
    path = Path(value).resolve()
    state_root = (REPO_ROOT / ".research-state").resolve()
    if not path.is_relative_to(state_root):
        raise SourceReject(f"OUTPUT_PATH_REJECT:{path}")
    if path.exists():
        raise SourceReject(f"SEALED_OUTPUT_EXISTS:{path}")
    return path


def write_create_once(path: Path, value: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(value)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    bundle_path = require_output_path(args.bundle)
    raw_path = require_output_path(args.raw)
    normalized_path = require_output_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")
    raw, response = fetch_source()
    rows = parse_subset(raw)
    normalized = normalized_bytes(rows)
    feasibility = feature_feasibility(rows)
    bundle = {
        "schema_version": "1",
        "document_type": "COIN_METRICS_BTC_REALIZED_CAP_28D_GROWTH_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "SEALED_SOURCE_ONLY_NO_BTC_OUTCOME_ACCESS",
        "publisher": "Coin Metrics",
        "captured_at": datetime.utcnow().isoformat(timespec="seconds") + "Z",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "source_commit": SOURCE_COMMIT,
            "credentials": "DENY",
            "redirect": "DENY",
            "retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "raw_response": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            **response,
            "bytes": len(raw),
            "sha256": sha256_bytes(raw),
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256_bytes(normalized),
            "bytes": len(normalized),
            "rows": len(rows),
            "first_date": rows[0]["date"],
            "last_date": rows[-1]["date"],
            "columns": ["date", "cap_mrkt_cur_usd", "mvrv", "cap_real_usd"],
            "derivation": "cap_real_usd=CapMrktCurUSD/CapMVRVCur_quantized_to_0.00000001_USD",
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Use only Sunday D after exactly 28 prior calendar-day observations. D becomes usable at D plus three calendar days 00:00 UTC and remains valid for at most 168 hours.",
        "revision_boundary": "The exact official GitHub commit and raw bytes are sealed present-vintage Community data. Original daily review timestamps and original vintages remain MISSING_PROOF; a historical pass requires untouched independent OOS.",
        "license_boundary": "Coin Metrics publishes the archive under CC BY-NC 4.0 without warranty. Raw and normalized bytes remain internal research inputs; commercial reuse or redistribution is not authorized by this audit.",
        "scope_note": "Free source only. No BTC strategy outcome, paid API, key, second timer, second writer, canonical write, OOS, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
    }
    bundle_bytes = (json.dumps(canonical(bundle), ensure_ascii=True, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    created: list[Path] = []
    try:
        for path, value in ((raw_path, raw), (normalized_path, normalized), (bundle_path, bundle_bytes)):
            write_create_once(path, value)
            created.append(path)
    except Exception:
        for path in reversed(created):
            path.unlink(missing_ok=True)
        raise
    print(
        json.dumps(
            {
                "status": bundle["status"],
                "bundle": bundle_path.relative_to(REPO_ROOT).as_posix(),
                "bundle_sha256": sha256_bytes(bundle_bytes),
                "raw_sha256": sha256_bytes(raw),
                "normalized_sha256": sha256_bytes(normalized),
                "feasibility": feasibility,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
