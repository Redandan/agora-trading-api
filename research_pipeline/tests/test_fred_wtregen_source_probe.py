from __future__ import annotations

from datetime import date, timedelta
import unittest

from research.fred_wtregen_source_probe import (
    EXPECTED_FIRST,
    EXPECTED_LAST,
    EXPECTED_ROWS,
    SourceReject,
    feature_feasibility,
    parse_rows,
    validate_combined_rows,
)


def _fixture() -> bytes:
    lines = ["observation_date,WTREGEN"]
    day = EXPECTED_FIRST
    index = 0
    while day <= EXPECTED_LAST:
        cycle = index % 16
        value = 500_000 - cycle * 10_000 if cycle < 8 else 420_000 + (cycle - 8) * 10_000
        lines.append(f"{day.isoformat()},{value}")
        day += timedelta(days=7)
        index += 1
    return ("\n".join(lines) + "\n").encode("utf-8")


class FredWtregenSourceProbeTest(unittest.TestCase):
    def test_fixture_satisfies_frozen_weekly_and_feature_contracts(self) -> None:
        all_lines = _fixture().decode("utf-8").splitlines()[1:]
        first_year_raw = (
            "observation_date,WTREGEN\n"
            + "\n".join(line for line in all_lines if line.startswith("2018-"))
            + "\n"
        ).encode("utf-8")
        rows = parse_rows(first_year_raw, EXPECTED_FIRST.year)
        # parse_rows deliberately enforces one year, so validate the seven-year
        # lattice with the same deterministic values directly.
        combined = []
        for line in all_lines:
            day_text, value_text = line.split(",")
            combined.append((date.fromisoformat(day_text), int(value_text)))
        self.assertGreater(len(rows), 0)
        self.assertEqual(len(combined), EXPECTED_ROWS)
        validate_combined_rows(combined)

        feasibility = feature_feasibility(combined)
        self.assertEqual(feasibility["evaluations"], EXPECTED_ROWS - 4)
        self.assertEqual(feasibility["first_evaluable_observation_day"], "2018-01-31")
        self.assertEqual(feasibility["first_effective_time"], "2018-02-02T00:00:00Z")
        self.assertEqual(
            feasibility["supportive_weeks"] + feasibility["drain_or_neutral_weeks"],
            feasibility["evaluations"],
        )
        self.assertGreater(feasibility["transitions"], 0)

    def test_bad_header_is_rejected(self) -> None:
        raw = _fixture().replace(b"WTREGEN", b"WALCL", 1)
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:HEADER"):
            parse_rows(raw, EXPECTED_FIRST.year)

    def test_nonpositive_value_is_rejected(self) -> None:
        lines = _fixture().decode("utf-8").splitlines()
        lines[1] = f"{EXPECTED_FIRST.isoformat()},0"
        raw = ("\n".join(lines) + "\n").encode("utf-8")
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:VALUE"):
            parse_rows(raw, EXPECTED_FIRST.year)


if __name__ == "__main__":
    unittest.main()
