from __future__ import annotations

from datetime import date
from decimal import Decimal
import importlib.util
import io
from pathlib import Path
import unittest
import zipfile


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/nyfed_gscpi_source_probe.py"
SPEC = importlib.util.spec_from_file_location("nyfed_gscpi_source_probe", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def workbook_bytes(*, sheets: int = 1, header: str = "GSCPI") -> bytes:
    workbook_sheets = "".join(
        f'<sheet name="GSCPI{i}" sheetId="{i}" r:id="rId{i}"/>'
        for i in range(1, sheets + 1)
    )
    relationships = "".join(
        f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>'
        for i in range(1, sheets + 1)
    )
    pattern = ["1.0", "0.5", "0.0", "-0.5", "-1.0", "-0.5", "0.0", "0.5"]
    rows = [
        '<row r="1"><c r="A1" t="inlineStr"><is><t>Date</t></is></c>'
        f'<c r="B1" t="inlineStr"><is><t>{header}</t></is></c></row>'
    ]
    current = probe.EXPECTED_FIRST
    for index in range(probe.EXPECTED_ROWS):
        row_number = index + 2
        rows.append(
            f'<row r="{row_number}"><c r="A{row_number}" t="inlineStr"><is><t>{current.isoformat()}</t></is></c>'
            f'<c r="B{row_number}"><v>{pattern[index % len(pattern)]}</v></c></row>'
        )
        current = probe.add_months(current, 1)
    worksheet = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
        + "".join(rows)
        + "</sheetData></worksheet>"
    )
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(
            "xl/workbook.xml",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
            'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
            f"<sheets>{workbook_sheets}</sheets></workbook>",
        )
        archive.writestr(
            "xl/_rels/workbook.xml.rels",
            '<?xml version="1.0" encoding="UTF-8"?>'
            '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
            f"{relationships}</Relationships>",
        )
        for index in range(1, sheets + 1):
            archive.writestr(f"xl/worksheets/sheet{index}.xml", worksheet)
    return output.getvalue()


class NyFedGscpiSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(
            spec["source_contract"]["expected_unique_ordered_month_rows"], 84
        )
        self.assertEqual(spec["factor_contract"]["warmup_months"], 3)
        self.assertEqual(
            spec["factor_contract"]["effective_time"],
            "OBSERVATION_MONTH_START_PLUS_45_CALENDAR_DAYS_AT_0000_UTC",
        )

    def test_fixture_passes_workbook_lattice_and_expected_windows(self) -> None:
        rows, metadata = probe.parse_workbook(workbook_bytes())
        probe.validate_rows(rows)
        feasibility = probe.feature_feasibility(rows)
        self.assertEqual(metadata["header_row"], 1)
        self.assertEqual(feasibility["evaluations"], 81)
        self.assertEqual(feasibility["design"]["evaluations"], 48)
        self.assertEqual(feasibility["validation"]["evaluations"], 24)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_multiple_worksheets_are_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "XLSX_SHEET_COUNT:2"):
            probe.parse_workbook(workbook_bytes(sheets=2))

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "XLSX_HEADER_COUNT:0"):
            probe.parse_workbook(workbook_bytes(header="INDEX"))

    def test_missing_month_is_rejected(self) -> None:
        rows, _ = probe.parse_workbook(workbook_bytes())
        rows.pop(10)
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:ROWS"):
            probe.validate_rows(rows)


if __name__ == "__main__":
    unittest.main()
