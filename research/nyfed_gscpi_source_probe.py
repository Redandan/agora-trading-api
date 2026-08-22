#!/usr/bin/env python3
"""Seal official present-vintage NY Fed GSCPI history without BTC outcomes."""

from __future__ import annotations

import argparse
from datetime import date, datetime, time, timedelta, timezone
from decimal import Decimal, InvalidOperation
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener
import xml.etree.ElementTree as ET
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = REPO_ROOT / "research_pipeline/examples/btc-nyfed-gscpi-3m-easing-source-feasibility.v1.spec.json"
PRIOR_PATH = REPO_ROOT / "research_pipeline/examples/btc-nyfed-gscpi-3m-easing-long-cash-primary-prior.v1.json"
SOURCE_URL = "https://www.newyorkfed.org/medialibrary/research/interactives/gscpi/downloads/gscpi_data.xlsx"
EXPECTED_ROWS = 84
EXPECTED_FIRST = date(2018, 1, 1)
EXPECTED_LAST = date(2024, 12, 1)
LOOKBACK = 3
AVAILABILITY_DAYS = 45
MAX_RESPONSE_BYTES = 2 * 1024 * 1024
MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024
REQUEST_TIMEOUT_SECONDS = 30
DESIGN_START = datetime(2019, 1, 1, tzinfo=timezone.utc)
VALIDATION_START = datetime(2023, 1, 1, tzinfo=timezone.utc)
STUDY_END = datetime(2025, 1, 1, tzinfo=timezone.utc)
ALLOWED_CONTENT_TYPES = {
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
}
SUPPORT_GATES = {
    "design": {"minimum_easing": 12, "minimum_other": 12, "minimum_transitions": 6},
    "validation": {"minimum_easing": 6, "minimum_other": 6, "minimum_transitions": 3},
}
MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CELL_REFERENCE = re.compile(r"^([A-Z]{1,3})([1-9][0-9]*)$")


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
    family_id = "btc-nyfed-gscpi-3m-easing-long-cash"
    if (
        spec.get("document_type")
        != "BTC_NYFED_GSCPI_3M_EASING_SOURCE_FEASIBILITY_SPEC_V1"
        or prior.get("document_type")
        != "BTC_NYFED_GSCPI_3M_EASING_LONG_CASH_PRIMARY_PRIOR_V1"
        or spec.get("family_id") != family_id
        or prior.get("family_id") != family_id
        or spec.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or prior.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
    ):
        raise SourceReject("SPEC_REJECT:IDENTITY_OR_AUTHORIZATION")
    source = spec.get("source_contract", {})
    expected_source = {
        "provider": "FEDERAL_RESERVE_BANK_OF_NEW_YORK",
        "series": "GLOBAL_SUPPLY_CHAIN_PRESSURE_INDEX",
        "request_url": SOURCE_URL,
        "transport": "HTTPS_GET_NO_REDIRECT_NO_RETRY_NO_CREDENTIAL",
        "maximum_response_bytes": MAX_RESPONSE_BYTES,
        "allowed_content_types": sorted(ALLOWED_CONTENT_TYPES),
        "workbook_contract": "VALID_OOXML_WORKBOOK_WITH_EXACTLY_ONE_WORKSHEET_AND_HEADER_ROW_IN_FIRST_20_ROWS_CONTAINING_UNIQUE_CASE_INSENSITIVE_TRIMMED_DATE_AND_GSCPI_CELLS",
        "row_contract": "SELECT_ONLY_ROWS_WHOSE_DATE_NORMALIZES_TO_2018-01_THROUGH_2024-12_AND_REQUIRE_EXACTLY_ONE_FINITE_GSCPI_VALUE_PER_CALENDAR_MONTH",
        "expected_unique_ordered_month_rows": EXPECTED_ROWS,
        "required_first_month": "2018-01",
        "required_last_month": "2024-12",
        "required_calendar_step": "EVERY_CONSECUTIVE_CALENDAR_MONTH",
        "required_finite_values": EXPECTED_ROWS,
        "revision_boundary": "PRESENT_VINTAGE_ONLY_NO_REAL_TIME_VINTAGE_CLAIM",
    }
    for key, value in expected_source.items():
        actual = source.get(key)
        if key == "allowed_content_types":
            actual = sorted(actual or [])
        if actual != value:
            raise SourceReject(f"SPEC_REJECT:SOURCE_CONTRACT:{key}")
    factor = spec.get("factor_contract", {})
    if (
        factor.get("formula") != "GSCPI_MONTH_t-GSCPI_MONTH_t_minus_3"
        or factor.get("comparison")
        != "STRICTLY_LESS_THAN_ZERO_WITH_EXACT_DECIMAL_COMPARISON"
        or factor.get("effective_time")
        != "OBSERVATION_MONTH_START_PLUS_45_CALENDAR_DAYS_AT_0000_UTC"
        or factor.get("warmup_months") != LOOKBACK
        or factor.get("expected_evaluations") != EXPECTED_ROWS - LOOKBACK
    ):
        raise SourceReject("SPEC_REJECT:FACTOR_CONTRACT")
    frozen_gates = spec.get("pre_outcome_support_gates", {})
    for label, gate in SUPPORT_GATES.items():
        frozen_keys = {
            "minimum_easing": f"minimum_{label}_easing_states",
            "minimum_other": f"minimum_{label}_other_states",
            "minimum_transitions": f"minimum_{label}_state_transitions",
        }
        for key, value in gate.items():
            spec_key = frozen_keys[key]
            if frozen_gates.get(spec_key) != value:
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


def fetch() -> tuple[bytes, str]:
    request = Request(
        SOURCE_URL,
        method="GET",
        headers={
            "Accept": ", ".join(sorted(ALLOWED_CONTENT_TYPES)),
            "User-Agent": "AgoraResearchNyFedGscpiSourceProbe/1.0",
        },
    )
    try:
        with build_opener(NoRedirect()).open(
            request, timeout=REQUEST_TIMEOUT_SECONDS
        ) as response:
            if response.status != 200:
                raise SourceReject(f"SOURCE_REJECT:HTTP:{response.status}")
            content_type = response.headers.get_content_type().lower()
            if content_type not in ALLOWED_CONTENT_TYPES:
                raise SourceReject(f"SOURCE_REJECT:CONTENT_TYPE:{content_type}")
            raw = response.read(MAX_RESPONSE_BYTES + 1)
    except SourceReject:
        raise
    except (HTTPError, URLError, TimeoutError, OSError) as error:
        raise SourceReject(
            f"SOURCE_REJECT:TRANSPORT:{type(error).__name__}"
        ) from error
    if not raw or len(raw) > MAX_RESPONSE_BYTES:
        raise SourceReject(f"SOURCE_REJECT:BYTES:{len(raw)}")
    return raw, content_type


def _safe_member_name(name: str) -> bool:
    pure = PurePosixPath(name)
    return not pure.is_absolute() and ".." not in pure.parts and "\\" not in name


def _read_xml(archive: zipfile.ZipFile, name: str) -> ET.Element:
    try:
        info = archive.getinfo(name)
    except KeyError as error:
        raise SourceReject(f"SOURCE_REJECT:XLSX_MEMBER_MISSING:{name}") from error
    if info.file_size > MAX_UNCOMPRESSED_BYTES:
        raise SourceReject(f"SOURCE_REJECT:XLSX_MEMBER_SIZE:{name}")
    try:
        return ET.fromstring(archive.read(info))
    except (ET.ParseError, RuntimeError, OSError) as error:
        raise SourceReject(f"SOURCE_REJECT:XLSX_XML:{name}") from error


def _shared_strings(archive: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in archive.namelist():
        return []
    root = _read_xml(archive, "xl/sharedStrings.xml")
    return [
        "".join(node.text or "" for node in item.findall(f".//{{{MAIN_NS}}}t"))
        for item in root.findall(f"{{{MAIN_NS}}}si")
    ]


def _cell_text(cell: ET.Element, shared: list[str]) -> str:
    cell_type = cell.attrib.get("t", "n")
    if cell_type == "inlineStr":
        inline = cell.find(f"{{{MAIN_NS}}}is")
        return "" if inline is None else "".join(
            node.text or "" for node in inline.findall(f".//{{{MAIN_NS}}}t")
        )
    value = cell.find(f"{{{MAIN_NS}}}v")
    raw = "" if value is None or value.text is None else value.text
    if cell_type == "s":
        try:
            return shared[int(raw)]
        except (ValueError, IndexError) as error:
            raise SourceReject("SOURCE_REJECT:XLSX_SHARED_STRING") from error
    if cell_type in {"n", "str"}:
        return raw
    raise SourceReject(f"SOURCE_REJECT:XLSX_CELL_TYPE:{cell_type}")


def _excel_date(raw: str, *, date_1904: bool) -> date:
    stripped = raw.strip()
    for pattern in ("%Y-%m-%d", "%Y/%m/%d", "%m/%d/%Y", "%Y-%m"):
        try:
            parsed = datetime.strptime(stripped, pattern).date()
            return date(parsed.year, parsed.month, 1)
        except ValueError:
            pass
    try:
        serial = Decimal(stripped)
    except InvalidOperation as error:
        raise SourceReject(f"SOURCE_REJECT:XLSX_DATE:{stripped}") from error
    if not serial.is_finite() or serial != serial.to_integral_value():
        raise SourceReject(f"SOURCE_REJECT:XLSX_DATE:{stripped}")
    epoch = date(1904, 1, 1) if date_1904 else date(1899, 12, 30)
    parsed = epoch + timedelta(days=int(serial))
    return date(parsed.year, parsed.month, 1)


def parse_workbook(raw: bytes) -> tuple[list[tuple[date, Decimal, str]], dict[str, Any]]:
    try:
        archive = zipfile.ZipFile(io.BytesIO(raw), "r")
    except (zipfile.BadZipFile, OSError) as error:
        raise SourceReject("SOURCE_REJECT:NOT_OOXML") from error
    with archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or any(not _safe_member_name(name) for name in names):
            raise SourceReject("SOURCE_REJECT:XLSX_MEMBER_INVENTORY")
        total_uncompressed = sum(info.file_size for info in archive.infolist())
        if total_uncompressed > MAX_UNCOMPRESSED_BYTES:
            raise SourceReject("SOURCE_REJECT:XLSX_UNCOMPRESSED_SIZE")
        workbook = _read_xml(archive, "xl/workbook.xml")
        sheets = workbook.findall(f"{{{MAIN_NS}}}sheets/{{{MAIN_NS}}}sheet")
        if len(sheets) != 1:
            raise SourceReject(f"SOURCE_REJECT:XLSX_SHEET_COUNT:{len(sheets)}")
        workbook_properties = workbook.find(f"{{{MAIN_NS}}}workbookPr")
        date_1904 = (
            workbook_properties is not None
            and workbook_properties.attrib.get("date1904", "0").lower() in {"1", "true"}
        )
        relation_id = sheets[0].attrib.get(f"{{{OFFICE_REL_NS}}}id")
        relationships = _read_xml(archive, "xl/_rels/workbook.xml.rels")
        relation = next(
            (
                item
                for item in relationships.findall(f"{{{PACKAGE_REL_NS}}}Relationship")
                if item.attrib.get("Id") == relation_id
            ),
            None,
        )
        if relation is None or relation.attrib.get("TargetMode") == "External":
            raise SourceReject("SOURCE_REJECT:XLSX_SHEET_RELATIONSHIP")
        target = relation.attrib.get("Target", "")
        worksheet_name = (
            target.lstrip("/")
            if target.startswith("/")
            else (PurePosixPath("xl") / PurePosixPath(target)).as_posix()
        )
        if not _safe_member_name(worksheet_name) or worksheet_name not in names:
            raise SourceReject("SOURCE_REJECT:XLSX_WORKSHEET_TARGET")
        worksheet = _read_xml(archive, worksheet_name)
        shared = _shared_strings(archive)
        sheet_rows: list[tuple[int, dict[int, str]]] = []
        for row in worksheet.findall(f".//{{{MAIN_NS}}}sheetData/{{{MAIN_NS}}}row"):
            try:
                row_number = int(row.attrib.get("r", "0"))
            except ValueError as error:
                raise SourceReject("SOURCE_REJECT:XLSX_ROW_REFERENCE") from error
            cells: dict[int, str] = {}
            for cell in row.findall(f"{{{MAIN_NS}}}c"):
                match = CELL_REFERENCE.fullmatch(cell.attrib.get("r", ""))
                if match is None or int(match.group(2)) != row_number:
                    raise SourceReject("SOURCE_REJECT:XLSX_CELL_REFERENCE")
                column = 0
                for character in match.group(1):
                    column = column * 26 + ord(character) - 64
                if column in cells:
                    raise SourceReject("SOURCE_REJECT:XLSX_DUPLICATE_CELL")
                cells[column] = _cell_text(cell, shared)
            sheet_rows.append((row_number, cells))
        header_candidates: list[tuple[int, int, int]] = []
        for row_number, cells in sheet_rows:
            if row_number > 20:
                continue
            normalized: dict[str, list[int]] = {}
            for column, value in cells.items():
                normalized.setdefault(value.strip().casefold(), []).append(column)
            if len(normalized.get("date", [])) == 1 and len(normalized.get("gscpi", [])) == 1:
                header_candidates.append(
                    (row_number, normalized["date"][0], normalized["gscpi"][0])
                )
        if len(header_candidates) != 1:
            raise SourceReject(f"SOURCE_REJECT:XLSX_HEADER_COUNT:{len(header_candidates)}")
        header_row, date_column, value_column = header_candidates[0]
        selected: list[tuple[date, Decimal, str]] = []
        for row_number, cells in sheet_rows:
            if row_number <= header_row:
                continue
            raw_date = cells.get(date_column, "").strip()
            raw_value = cells.get(value_column, "").strip()
            if not raw_date and not raw_value:
                continue
            if not raw_date or not raw_value:
                if raw_value:
                    raise SourceReject(f"SOURCE_REJECT:XLSX_PARTIAL_ROW:{row_number}")
                continue
            try:
                month = _excel_date(raw_date, date_1904=date_1904)
            except SourceReject:
                continue
            if month < EXPECTED_FIRST or month > EXPECTED_LAST:
                continue
            try:
                value = Decimal(raw_value)
            except InvalidOperation as error:
                raise SourceReject(f"SOURCE_REJECT:XLSX_VALUE:{row_number}") from error
            if not value.is_finite():
                raise SourceReject(f"SOURCE_REJECT:XLSX_VALUE:{row_number}")
            selected.append((month, value, format(value, "f")))
        metadata = {
            "worksheet_name": sheets[0].attrib.get("name"),
            "worksheet_path": worksheet_name,
            "header_row": header_row,
            "date_column": date_column,
            "gscpi_column": value_column,
            "date_system": "1904" if date_1904 else "1900",
            "workbook_member_count": len(names),
            "workbook_uncompressed_bytes": total_uncompressed,
        }
        return selected, metadata


def validate_rows(rows: list[tuple[date, Decimal, str]]) -> None:
    if len(rows) != EXPECTED_ROWS:
        raise SourceReject(f"SOURCE_REJECT:ROWS:{len(rows)}")
    months = [row[0] for row in rows]
    if len(set(months)) != len(months) or months != sorted(months):
        raise SourceReject("SOURCE_REJECT:UNIQUE_ORDERED_MONTHS")
    if months[0] != EXPECTED_FIRST or months[-1] != EXPECTED_LAST:
        raise SourceReject("SOURCE_REJECT:BOUNDARY")
    for index, (prior, current) in enumerate(
        zip(months, months[1:], strict=False), start=1
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
    easing = sum(flags)
    other = len(flags) - easing
    transitions = sum(a != b for a, b in zip(flags, flags[1:], strict=False))
    annual: dict[str, dict[str, int]] = {}
    for effective, state in selected:
        counts = annual.setdefault(str(effective.year), {"easing": 0, "other": 0})
        counts["easing" if state else "other"] += 1
    passed = (
        easing >= gate["minimum_easing"]
        and other >= gate["minimum_other"]
        and transitions >= gate["minimum_transitions"]
    )
    return {
        "evaluations": len(selected),
        "easing_months": easing,
        "other_months": other,
        "transitions": transitions,
        "longest_easing_run_months": _longest_run(flags, True),
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
            datetime.combine(month, time.min, tzinfo=timezone.utc)
            + timedelta(days=AVAILABILITY_DAYS),
            value < rows[index - LOOKBACK][1],
        )
        for index, (month, value, _) in enumerate(rows)
        if index >= LOOKBACK
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
        "easing_months": sum(flags),
        "other_months": sum(not flag for flag in flags),
        "transitions": sum(a != b for a, b in zip(flags, flags[1:], strict=False)),
        "first_effective_time": states[0][0].isoformat().replace("+00:00", "Z"),
        "last_effective_time": states[-1][0].isoformat().replace("+00:00", "Z"),
        "design": design,
        "validation": validation,
        "admission_status": (
            "PASS_EXACT_MONTHLY_LATTICE_BOTH_GSCPI_DIRECTION_STATES_AND_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS"
            if passed
            else "DATA_REJECT_INADEQUATE_GSCPI_DIRECTION_STATE_SUPPORT_OR_TRANSITIONS_BEFORE_BTC_OUTCOME_ACCESS"
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
    raw, content_type = fetch()
    rows, workbook_metadata = parse_workbook(raw)
    validate_rows(rows)
    normalized = (
        "observation_month,gscpi_present_vintage\n"
        + "".join(f"{month.isoformat()},{raw_value}\n" for month, _, raw_value in rows)
    ).encode("utf-8")
    feasibility = feature_feasibility(rows)
    status = (
        "SEALED_SOURCE_FEASIBILITY_PASS_NO_BTC_OUTCOME_ACCESS"
        if feasibility["admission_status"].startswith("PASS_")
        else "SEALED_SOURCE_FEASIBILITY_REJECT_NO_BTC_OUTCOME_ACCESS"
    )
    bundle = {
        "schema_version": "1",
        "document_type": "NYFED_GSCPI_MONTHLY_SOURCE_BUNDLE_V1",
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": status,
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "publisher": "Federal Reserve Bank of New York",
        "series": "GLOBAL_SUPPLY_CHAIN_PRESSURE_INDEX",
        "request_contract": {
            "method": "GET",
            "url": SOURCE_URL,
            "credentials": "DENY",
            "transport": "Python urllib",
            "redirect": "DENY",
            "retry": "DENY",
            "maximum_response_bytes": MAX_RESPONSE_BYTES,
            "content_type": content_type,
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
            "content_type": content_type,
            "workbook": workbook_metadata,
        },
        "normalized_subset": {
            "path": normalized_path.relative_to(REPO_ROOT).as_posix(),
            "bytes": len(normalized),
            "sha256": sha256(normalized),
            "rows": len(rows),
            "first_month": rows[0][0].isoformat(),
            "last_month": rows[-1][0].isoformat(),
            "columns": ["observation_month", "gscpi_present_vintage"],
        },
        "pre_outcome_feature_feasibility": feasibility,
        "publication_timing_boundary": "Each monthly observation is conservatively usable only from observation-month start plus 45 calendar days at 00:00 UTC and remains effective until the next state becomes available.",
        "revision_boundary": "The exact New York Fed workbook is sealed present-vintage history. Original release vintages, later revisions and exact historical publication timestamps remain MISSING_PROOF; a historical pass would still require untouched independent OOS.",
        "interpretation_boundary": "GSCPI easing can reflect supply normalization or weak demand; it is not proof of independent Bitcoin causality.",
        "license_boundary": "The official workbook is retained as internal reproducibility evidence with source attribution; redistribution rights are not claimed.",
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
