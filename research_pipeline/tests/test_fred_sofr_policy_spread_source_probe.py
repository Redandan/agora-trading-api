from __future__ import annotations

from datetime import date, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "research/fred_sofr_policy_spread_source_probe.py"
SPEC = importlib.util.spec_from_file_location(
    "fred_sofr_policy_spread_source_probe", MODULE_PATH
)
assert SPEC is not None and SPEC.loader is not None
probe = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(probe)


def fixture_days() -> list[date]:
    days: list[date] = []
    current = probe.EXPECTED_FIRST
    while current <= probe.EXPECTED_LAST:
        if current.weekday() < 5:
            days.append(current)
        current += timedelta(days=1)
    removed = {20 + 24 * index for index in range(71)}
    selected = [day for index, day in enumerate(days) if index not in removed]
    assert len(selected) == 1690
    assert selected[0] == probe.EXPECTED_FIRST
    assert selected[-1] == probe.EXPECTED_LAST
    return selected


def fixture_csv() -> bytes:
    lines = ["observation_date,SOFR,IOER,IORB\n"]
    for index, day in enumerate(fixture_days()):
        sofr = "1.01" if index % 20 == 0 else "1.00"
        if day < probe.IORB_START:
            lines.append(f"{day.isoformat()},{sofr},1.00,\n")
        else:
            lines.append(f"{day.isoformat()},{sofr},,1.00\n")
    return "".join(lines).encode("utf-8")


class FredSofrPolicySpreadSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_matches_probe_contract_and_hashes(self) -> None:
        spec = probe.load_and_validate_spec()
        self.assertEqual(
            spec["source_contract"]["request_url"], probe.SOURCE_URL
        )
        self.assertEqual(spec["source_contract"]["minimum_rows"], 1680)
        self.assertEqual(spec["source_contract"]["maximum_rows"], 1720)
        self.assertEqual(
            spec["feature_contract"]["policy_splice"],
            "IOER_THROUGH_2021_07_28_IORB_FROM_2021_07_29",
        )

    def test_sparse_positive_spread_fixture_passes_frozen_support(self) -> None:
        rows = probe.parse_rows(fixture_csv())
        self.assertEqual(len(rows), 1690)
        self.assertEqual(rows[0]["policy_name"], "IOER")
        self.assertEqual(rows[-1]["policy_name"], "IORB")
        feasibility = probe.feature_feasibility(rows)
        self.assertTrue(feasibility["all_support_gates_pass"])
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])

    def test_equality_is_nonpositive_state(self) -> None:
        rows = probe.parse_rows(fixture_csv())
        equal = next(row for row in rows if row["spread_bps"] == Decimal("0"))
        gate = {
            "start": equal["date"],
            "end_exclusive": equal["date"] + timedelta(days=6),
            "minimum_evaluations": 1,
            "minimum_positive_spread_days": 0,
            "minimum_nonpositive_spread_days": 1,
            "minimum_transitions": 0,
            "minimum_positive_spread_years": 0,
            "minimum_nonpositive_spread_years": 1,
            "maximum_single_year_positive_share": Decimal("1"),
        }
        support = probe.window_support([equal], gate)
        self.assertEqual(support["positive_spread_days"], 0)
        self.assertEqual(support["nonpositive_spread_days"], 1)

    def test_wrong_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(probe.SourceReject, "SOURCE_REJECT:HEADER"):
            probe.parse_rows(b"DATE,SOFR,IOER,IORB\n2018-04-03,1.0,1.0,\n")

    def test_policy_transition_violation_is_rejected(self) -> None:
        with self.assertRaisesRegex(
            probe.SourceReject, "SOURCE_REJECT:IOER_IORB_TRANSITION"
        ):
            probe.parse_rows(
                b"observation_date,SOFR,IOER,IORB\n2018-04-03,1.0,1.0,1.0\n"
            )

    def test_business_day_gap_over_five_days_is_rejected(self) -> None:
        rows = probe.parse_rows(fixture_csv())
        broken = [dict(row) for row in rows]
        broken[1]["date"] = broken[0]["date"] + timedelta(days=11)
        with self.assertRaisesRegex(
            probe.SourceReject, "SOURCE_REJECT:BUSINESS_DAY_GAP"
        ):
            probe.validate_rows(broken)


if __name__ == "__main__":
    unittest.main()
