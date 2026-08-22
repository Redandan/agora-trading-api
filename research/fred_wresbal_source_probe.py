#!/usr/bin/env python3
"""Seal official Board H.4.1 WRESBAL-equivalent data without BTC outcomes."""

from __future__ import annotations

import argparse
import csv
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import html
import io
import json
from http.cookiejar import CookieJar
from pathlib import Path
import re
from typing import Any
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-reserve-balances-growth-source-feasibility.v1.spec.json"
ERRATUM_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-reserve-balances-growth-source-transport-erratum.v1.json"
REDIRECT_ERRATUM_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-reserve-balances-growth-source-transport-redirect-erratum.v1.json"
FORMAT_ERRATUM_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-reserve-balances-growth-source-transport-format-erratum.v1.json"
PACKAGE_ID = "bf254044496631c2a1c54617dd265a95"
TARGET_SERIES = "H41/H41/RESH4R_XAW_N.WW"
TARGET_DESCRIPTION = "Reserve balances with Federal Reserve Banks: week average"
REVIEW_URL = (
    "https://www.federalreserve.gov/datadownload/Review.aspx"
    "?filetype=csv&from=01%2F01%2F2018&label=include&lastobs="
    "&layout=seriescolumn&rel=H41&series=" + PACKAGE_ID
    + "&to=12%2F31%2F2024&type=package"
)
EXPECTED_ROWS = 365
EXPECTED_FIRST = date(2018, 1, 3)
EXPECTED_LAST = date(2024, 12, 25)
LOOKBACK_OBSERVATIONS = 4
AVAILABILITY_LAG_DAYS = 2
MAX_RESPONSE_BYTES = 32 * 1024 * 1024
MAX_UNCOMPRESSED_DATA_BYTES = 160 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 90
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
SUPPORT_GATES = {
    "design": {"minimum_evaluations": 180, "minimum_per_state": 40},
    "validation": {"minimum_evaluations": 90, "minimum_per_state": 20},
}
DECIMAL_VALUE = re.compile(r"^[1-9][0-9]*(?:\.0+)?$")
HIDDEN_FIELD = re.compile(
    r'<input[^>]+name="(__VIEWSTATE|__VIEWSTATEGENERATOR|__EVENTVALIDATION)"[^>]+value="([^"]*)"',
    re.IGNORECASE,
)
OUTPUT_LINK = re.compile(
    r'href="(Output\.aspx\?rel=H41&amp;filetype=zip)"', re.IGNORECASE
)


class SourceReject(RuntimeError):
    pass


class SameOriginRedirect(urllib.request.HTTPRedirectHandler):
    def __init__(self) -> None:
        super().__init__()
        self.redirect_count = 0

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # type: ignore[no-untyped-def]
        resolved = urllib.parse.urljoin(req.full_url, newurl)
        parsed = urllib.parse.urlsplit(resolved)
        if parsed.scheme != "https" or parsed.hostname != "www.federalreserve.gov":
            raise SourceReject(f"SOURCE_REJECT:REDIRECT_ORIGIN:{resolved}")
        if self.redirect_count >= 1:
            raise SourceReject("SOURCE_REJECT:REDIRECT_COUNT")
        self.redirect_count += 1
        return super().redirect_request(req, fp, code, msg, headers, resolved)


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
        + "\n"
    ).encode("utf-8")


def _load_json(path: Path, error_prefix: str) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw)
    except json.JSONDecodeError as error:
        raise SourceReject(f"{error_prefix}:JSON") from error
    return value, raw


def load_and_validate_spec() -> tuple[
    dict[str, Any], bytes, dict[str, Any], bytes, dict[str, Any], bytes,
    dict[str, Any], bytes
]:
    spec, spec_raw = _load_json(SPEC_PATH, "SPEC_REJECT")
    erratum, erratum_raw = _load_json(ERRATUM_PATH, "ERRATUM_REJECT")
    redirect_erratum, redirect_erratum_raw = _load_json(
        REDIRECT_ERRATUM_PATH, "REDIRECT_ERRATUM_REJECT"
    )
    format_erratum, format_erratum_raw = _load_json(
        FORMAT_ERRATUM_PATH, "FORMAT_ERRATUM_REJECT"
    )
    if spec.get("status") != "FROZEN_BEFORE_2018_2024_WRESBAL_FACTOR_ACCESS":
        raise SourceReject("SPEC_REJECT:STATUS")
    if spec.get("family_id") != "btc-fred-reserve-balances-growth-liquidity-support-long-cash":
        raise SourceReject("SPEC_REJECT:FAMILY")
    source = spec.get("source_contract", {})
    feature = spec.get("feature_contract", {})
    windows = spec.get("windows", {})
    expected = {
        "series": "WRESBAL",
        "expected_rows": EXPECTED_ROWS,
        "expected_first_date": EXPECTED_FIRST.isoformat(),
        "expected_last_date": EXPECTED_LAST.isoformat(),
    }
    if any(source.get(key) != value for key, value in expected.items()):
        raise SourceReject("SPEC_REJECT:SOURCE_CONTRACT")
    if feature.get("lookback_observations") != LOOKBACK_OBSERVATIONS:
        raise SourceReject("SPEC_REJECT:LOOKBACK")
    for name, gate in SUPPORT_GATES.items():
        if any(windows.get(name, {}).get(key) != value for key, value in gate.items()):
            raise SourceReject(f"SPEC_REJECT:SUPPORT_GATE:{name}")
    replacement = erratum.get("replacement_transport", {})
    if erratum.get("status") != "FROZEN_BEFORE_FEDERAL_RESERVE_DDP_FACTOR_ACCESS":
        raise SourceReject("ERRATUM_REJECT:STATUS")
    if replacement.get("package_identity") != PACKAGE_ID:
        raise SourceReject("ERRATUM_REJECT:PACKAGE")
    if replacement.get("target_series_code") != TARGET_SERIES:
        raise SourceReject("ERRATUM_REJECT:SERIES")
    correction = redirect_erratum.get("minimal_transport_correction", {})
    if redirect_erratum.get("status") != "FROZEN_BEFORE_DDP_OUTPUT_ZIP_ACCESS":
        raise SourceReject("REDIRECT_ERRATUM_REJECT:STATUS")
    if correction.get("redirect_count") != 1:
        raise SourceReject("REDIRECT_ERRATUM_REJECT:COUNT")
    if correction.get("allowed_host") != "www.federalreserve.gov":
        raise SourceReject("REDIRECT_ERRATUM_REJECT:HOST")
    if format_erratum.get("status") != "FROZEN_BEFORE_TARGET_OBSERVATION_VALUE_PARSING":
        raise SourceReject("FORMAT_ERRATUM_REJECT:STATUS")
    if (
        format_erratum.get("observed_pre_value_format", {}).get("target_series_name")
        != "RESH4R_XAW_N.WW"
    ):
        raise SourceReject("FORMAT_ERRATUM_REJECT:SERIES")
    return (
        spec,
        spec_raw,
        erratum,
        erratum_raw,
        redirect_erratum,
        redirect_erratum_raw,
        format_erratum,
        format_erratum_raw,
    )


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_rows(stream) -> tuple[list[tuple[date, int]], dict[str, str]]:  # type: ignore[no-untyped-def]
    expected_identity = {
        "SERIES_NAME": "RESH4R_XAW_N.WW",
        "FREQ": "19",
        "CATEGORY": "LIABCAP",
        "SUBCATEGORY": "OFDRB",
        "COMPONENT": "RBFRB",
        "DISTRIBUTION": "TOT",
        "SERIESTYPE": "A",
        "UNIT": "Currency",
        "UNIT_MULT": "1000000",
        "CURRENCY": "USD",
    }
    rows: list[tuple[date, int]] = []
    target_count = 0
    in_target = False
    identity: dict[str, str] = {}
    try:
        for event, element in ET.iterparse(stream, events=("start", "end")):
            name = _local_name(element.tag)
            if event == "start" and name == "Series":
                if element.attrib.get("SERIES_NAME") == expected_identity["SERIES_NAME"]:
                    target_count += 1
                    if target_count > 1:
                        raise SourceReject("SOURCE_REJECT:DUPLICATE_TARGET_SERIES")
                    identity = dict(element.attrib)
                    if any(identity.get(key) != value for key, value in expected_identity.items()):
                        raise SourceReject(f"SOURCE_REJECT:SERIES_IDENTITY:{identity}")
                    in_target = True
            elif event == "start" and name == "Obs" and in_target:
                raw_day = element.attrib.get("TIME_PERIOD", "")
                raw_value = element.attrib.get("OBS_VALUE", "")
                status = element.attrib.get("OBS_STATUS", "")
                try:
                    day = date.fromisoformat(raw_day)
                except ValueError as error:
                    raise SourceReject(f"SOURCE_REJECT:DATE:{raw_day}") from error
                if status != "A":
                    raise SourceReject(f"SOURCE_REJECT:OBS_STATUS:{status}")
                if not DECIMAL_VALUE.fullmatch(raw_value):
                    raise SourceReject("SOURCE_REJECT:VALUE_FORMAT")
                try:
                    decimal_value = Decimal(raw_value)
                except InvalidOperation as error:
                    raise SourceReject("SOURCE_REJECT:VALUE_DECIMAL") from error
                if decimal_value != decimal_value.to_integral_value():
                    raise SourceReject("SOURCE_REJECT:VALUE_NON_INTEGRAL")
                if date(2018, 1, 1) <= day <= date(2024, 12, 31):
                    rows.append((day, int(decimal_value)))
            elif event == "end" and name == "Series" and in_target:
                in_target = False
            if event == "end":
                element.clear()
    except ET.ParseError as error:
        raise SourceReject("SOURCE_REJECT:XML") from error
    if target_count != 1:
        raise SourceReject(f"SOURCE_REJECT:TARGET_SERIES_COUNT:{target_count}")
    return rows, identity


def extract_target_rows(raw_zip: bytes) -> tuple[list[tuple[date, int]], dict[str, Any]]:
    try:
        with zipfile.ZipFile(io.BytesIO(raw_zip), "r") as archive:
            names = archive.namelist()
            if any(name.startswith("/") or ".." in Path(name).parts for name in names):
                raise SourceReject("SOURCE_REJECT:ZIP_PATH")
            required = {
                "H41_H41.xsd",
                "H41_data.xml",
                "H41_struct.xml",
                "frb_common.xsd",
            }
            if set(names) != required:
                raise SourceReject(f"SOURCE_REJECT:ZIP_INVENTORY:{sorted(names)}")
            info = archive.getinfo("H41_data.xml")
            if info.file_size > MAX_UNCOMPRESSED_DATA_BYTES:
                raise SourceReject("SOURCE_REJECT:XML_BYTES")
            with archive.open("H41_data.xml", "r") as stream:
                rows, identity = parse_rows(stream)
    except (zipfile.BadZipFile, RuntimeError) as error:
        raise SourceReject("SOURCE_REJECT:ZIP") from error
    return rows, {
        "entry": "H41_data.xml",
        "bytes": info.file_size,
        "crc32": f"{info.CRC:08x}",
        "series_identity": identity,
    }


def validate_combined_rows(rows: list[tuple[date, int]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    if len({day for day, _ in rows}) != len(rows):
        raise SourceReject("SOURCE_REJECT:DUPLICATE_DATE")
    if rows[0][0] != EXPECTED_FIRST or rows[-1][0] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, ((prior_day, _), (current_day, _)) in enumerate(
        zip(rows, rows[1:], strict=False), start=1
    ):
        if current_day - prior_day != timedelta(days=7) or current_day.weekday() != 2:
            raise SourceReject(f"SOURCE_REJECT:WEEKLY_CONTINUITY:{index}")


def _summarize_window(
    states: list[tuple[datetime, bool]],
    start: datetime,
    end: datetime,
    gate: dict[str, int],
) -> dict[str, Any]:
    selected = [state for state in states if start <= state[0] < end]
    supportive = sum(state[1] for state in selected)
    other = len(selected) - supportive
    return {
        "evaluations": len(selected),
        "supportive_weeks": supportive,
        "other_weeks": other,
        "first_effective_time": selected[0][0].isoformat().replace("+00:00", "Z")
        if selected
        else None,
        "last_effective_time": selected[-1][0].isoformat().replace("+00:00", "Z")
        if selected
        else None,
        "support_gate": gate,
        "support_pass": len(selected) >= gate["minimum_evaluations"]
        and supportive >= gate["minimum_per_state"]
        and other >= gate["minimum_per_state"],
    }


def feature_feasibility(rows: list[tuple[date, int]]) -> dict[str, Any]:
    states: list[tuple[datetime, bool]] = []
    for index in range(LOOKBACK_OBSERVATIONS, len(rows)):
        day, value = rows[index]
        effective = datetime.combine(
            day + timedelta(days=AVAILABILITY_LAG_DAYS),
            time.min,
            tzinfo=timezone.utc,
        )
        states.append((effective, value > rows[index - LOOKBACK_OBSERVATIONS][1]))
    if not states:
        raise SourceReject("SOURCE_REJECT:NO_EVALUABLE_FEATURE")
    transitions = sum(
        current[1] != prior[1]
        for prior, current in zip(states, states[1:], strict=False)
    )
    design = _summarize_window(
        states, DESIGN_START, VALIDATION_START, SUPPORT_GATES["design"]
    )
    validation = _summarize_window(
        states, VALIDATION_START, STUDY_END, SUPPORT_GATES["validation"]
    )
    return {
        "weekly_observation_count": len(rows),
        "evaluations": len(states),
        "supportive_weeks": sum(state[1] for state in states),
        "other_weeks": sum(not state[1] for state in states),
        "transitions": transitions,
        "first_evaluable_observation_day": rows[LOOKBACK_OBSERVATIONS][0].isoformat(),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_evaluable_observation_day": rows[-1][0].isoformat(),
        "design": design,
        "validation": validation,
        "admission_status": "PASS_WEEKLY_COVERAGE_AND_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS"
        if design["support_pass"] and validation["support_pass"]
        else "DATA_REJECT_INADEQUATE_BOTH_STATE_SUPPORT_BEFORE_BTC_OUTCOME_ACCESS",
    }


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


def write_create_once(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as target:
        target.write(raw)


def _read_bounded(response) -> bytes:  # type: ignore[no-untyped-def]
    body = response.read(MAX_RESPONSE_BYTES + 1)
    if not body or len(body) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(body)}")
    return body


def fetch() -> tuple[bytes, str]:
    jar = CookieJar()
    opener = urllib.request.build_opener(
        urllib.request.HTTPCookieProcessor(jar), SameOriginRedirect()
    )
    headers = {"User-Agent": "AgoraResearchFederalReserveH41WresbalProbe/1.0"}
    try:
        review_request = urllib.request.Request(REVIEW_URL, headers=headers)
        with opener.open(review_request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            review = _read_bounded(response).decode("utf-8")
        hidden = {
            name: html.unescape(value)
            for name, value in HIDDEN_FIELD.findall(review)
        }
        required = {"__VIEWSTATE", "__VIEWSTATEGENERATOR", "__EVENTVALIDATION"}
        if set(hidden) != required:
            raise SourceReject("SOURCE_REJECT:WEBFORM_STATE")
        hidden["btnToDownload"] = "Go to download"
        post_request = urllib.request.Request(
            REVIEW_URL,
            data=urllib.parse.urlencode(hidden).encode("ascii"),
            headers={
                **headers,
                "Content-Type": "application/x-www-form-urlencoded",
            },
            method="POST",
        )
        with opener.open(post_request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            download_page = _read_bounded(response).decode("utf-8")
        link = OUTPUT_LINK.findall(download_page)
        if link != ["Output.aspx?rel=H41&amp;filetype=zip"]:
            raise SourceReject(f"SOURCE_REJECT:OUTPUT_LINK:{len(link)}")
        output_url = urllib.parse.urljoin(REVIEW_URL, html.unescape(link[0]))
        output_request = urllib.request.Request(output_url, headers=headers)
        with opener.open(output_request, timeout=REQUEST_TIMEOUT_SECONDS) as response:
            raw = _read_bounded(response)
    except urllib.error.HTTPError as error:
        raise SourceReject(f"SOURCE_REJECT:HTTP:{error.code}") from error
    except urllib.error.URLError as error:
        raise SourceReject(f"SOURCE_REJECT:URL:{error.reason}") from error
    if not raw.startswith(b"PK"):
        raise SourceReject("SOURCE_REJECT:NOT_ZIP")
    return raw, output_url


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True)
    parser.add_argument("--raw", required=True)
    parser.add_argument("--normalized", required=True)
    args = parser.parse_args()
    (
        _, spec_raw, _, erratum_raw, _, redirect_erratum_raw, _, format_erratum_raw
    ) = load_and_validate_spec()
    bundle_path = output_path(args.bundle)
    raw_path = output_path(args.raw)
    normalized_path = output_path(args.normalized)
    if len({bundle_path, raw_path, normalized_path}) != 3:
        raise SourceReject("OUTPUT_PATH_REJECT:DUPLICATE")

    raw, output_url = fetch()
    rows, target_csv = extract_target_rows(raw)
    rows.sort(key=lambda row: row[0])
    validate_combined_rows(rows)
    normalized = (
        "observation_date,wresbal_millions_usd\n"
        + "".join(f"{day.isoformat()},{value}\n" for day, value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = (
        "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if feasibility["admission_status"].startswith("PASS_")
        else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    )
    bundle = {
        "schema_version": "1",
        "document_type": "FEDERAL_RESERVE_H41_WRESBAL_EQUIVALENT_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Board of Governors of the Federal Reserve System",
        "program": "Data Download Program",
        "release": "H.4.1 Factors Affecting Reserve Balances",
        "series": TARGET_SERIES,
        "fred_identity": "WRESBAL",
        "frozen_source_feasibility_spec": {
            "path": SPEC_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(spec_raw),
        },
        "frozen_transport_erratum": {
            "path": ERRATUM_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(erratum_raw),
        },
        "frozen_transport_redirect_erratum": {
            "path": REDIRECT_ERRATUM_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(redirect_erratum_raw),
        },
        "frozen_transport_format_erratum": {
            "path": FORMAT_ERRATUM_PATH.relative_to(REPO_ROOT).as_posix(),
            "sha256": sha256(format_erratum_raw),
        },
        "request_contract": {
            "method": "GET_REVIEW_POST_WEBFORM_GET_OUTPUT",
            "review_url": REVIEW_URL,
            "output_url": output_url,
            "package_identity": PACKAGE_ID,
            "target_series": TARGET_SERIES,
            "credentials": "DENY",
            "redirect": "ALLOW_EXACT_ONE_HTTPS_WWW_FEDERALRESERVE_GOV_FORM_POST_REDIRECT_ONLY",
            "retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
        },
        "raw_response_archive": {
            "path": raw_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(raw),
            "sha256": sha256(raw),
            "format": "OFFICIAL_FEDERAL_RESERVE_DDP_ZIP",
            "target_xml": target_csv,
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_date": rows[0][0].isoformat(),
            "last_date": rows[-1][0].isoformat(),
            "columns": ["observation_date", "wresbal_millions_usd"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Each Wednesday-ending observation becomes usable only on Friday 00:00 UTC and remains valid for at most 168 hours.",
        "revision_boundary": "The exact official Board package is a sealed present-vintage history. Original weekly release values and revision vintages remain MISSING_PROOF.",
        "interpretation_boundary": "Higher reserve balances do not prove bank credit expansion, crypto inflow or BTC return predictability. Fed assets, Treasury deposits, currency, ON RRP and reserve demand can dominate the balance.",
        "scope_note": "No BTC outcome, hypothesis, manifest, strategy economics, candidate, OOS, paid API, key, second timer, second writer, canonical write, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action was accessed.",
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
