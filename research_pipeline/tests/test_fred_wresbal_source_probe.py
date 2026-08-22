from __future__ import annotations

from datetime import date, timedelta
import io
import unittest

from research.fred_wresbal_source_probe import (
    SourceReject,
    feature_feasibility,
    load_and_validate_spec,
    parse_rows,
    validate_combined_rows,
)


def _rows(*, alternating: bool = True) -> list[tuple[date, int]]:
    result = []
    day = date(2018, 1, 3)
    for index in range(365):
        block = 100_000 if alternating and (index // 4) % 2 else 0
        result.append((day, 2_000_000 + block + index))
        day += timedelta(days=7)
    return result


class FredWresbalSourceProbeTest(unittest.TestCase):
    def test_frozen_spec_and_balanced_fixture_pass_before_btc_outcomes(self) -> None:
        (
            spec,
            raw,
            erratum,
            erratum_raw,
            redirect_erratum,
            redirect_erratum_raw,
            format_erratum,
            format_erratum_raw,
        ) = load_and_validate_spec()
        self.assertEqual(spec["source_contract"]["series"], "WRESBAL")
        self.assertGreater(len(raw), 0)
        self.assertEqual(
            erratum["replacement_transport"]["target_series_code"],
            "H41/H41/RESH4R_XAW_N.WW",
        )
        self.assertGreater(len(erratum_raw), 0)
        self.assertEqual(
            redirect_erratum["minimal_transport_correction"]["redirect_count"], 1
        )
        self.assertGreater(len(redirect_erratum_raw), 0)
        self.assertEqual(
            format_erratum["observed_pre_value_format"]["target_series_name"],
            "RESH4R_XAW_N.WW",
        )
        self.assertGreater(len(format_erratum_raw), 0)
        rows = _rows()
        validate_combined_rows(rows)
        feasibility = feature_feasibility(rows)
        self.assertEqual(feasibility["weekly_observation_count"], 365)
        self.assertEqual(feasibility["evaluations"], 361)
        self.assertEqual(feasibility["first_evaluable_observation_day"], "2018-01-31")
        self.assertEqual(feasibility["first_effective_time"], "2018-02-02T00:00:00Z")
        self.assertEqual(feasibility["design"]["evaluations"], 209)
        self.assertEqual(feasibility["validation"]["evaluations"], 104)
        self.assertTrue(feasibility["design"]["support_pass"])
        self.assertTrue(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("PASS_"))

    def test_one_sided_state_fails_before_btc_outcomes(self) -> None:
        feasibility = feature_feasibility(_rows(alternating=False))
        self.assertEqual(feasibility["supportive_weeks"], 361)
        self.assertFalse(feasibility["design"]["support_pass"])
        self.assertFalse(feasibility["validation"]["support_pass"])
        self.assertTrue(feasibility["admission_status"].startswith("DATA_REJECT_"))

    def test_parser_and_weekly_lattice_fail_closed(self) -> None:
        fixture = (
            '<kf:DataSet xmlns:kf="http://www.federalreserve.gov/structure/compact/common">'
            '<kf:Series SERIES_NAME="RESH4R_XAW_N.WW" FREQ="19" '
            'CATEGORY="LIABCAP" SUBCATEGORY="OFDRB" COMPONENT="RBFRB" '
            'DISTRIBUTION="TOT" SERIESTYPE="A" UNIT="Currency" '
            'UNIT_MULT="1000000" CURRENCY="USD">'
            '<kf:Obs TIME_PERIOD="2018-01-03" OBS_VALUE="2000000" OBS_STATUS="A"/>'
            '</kf:Series></kf:DataSet>'
        ).encode("utf-8")
        parsed, identity = parse_rows(io.BytesIO(fixture))
        self.assertEqual(parsed, [(date(2018, 1, 3), 2_000_000)])
        self.assertEqual(identity["COMPONENT"], "RBFRB")
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:TARGET_SERIES_COUNT"):
            parse_rows(io.BytesIO(fixture.replace(b"RESH4R_XAW", b"RESH4S_XAW")))
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:VALUE_FORMAT"):
            parse_rows(io.BytesIO(fixture.replace(b"2000000", b".")))
        rows = _rows()
        rows[100] = (rows[99][0] + timedelta(days=8), rows[100][1])
        with self.assertRaisesRegex(SourceReject, "SOURCE_REJECT:WEEKLY_CONTINUITY"):
            validate_combined_rows(rows)


if __name__ == "__main__":
    unittest.main()
