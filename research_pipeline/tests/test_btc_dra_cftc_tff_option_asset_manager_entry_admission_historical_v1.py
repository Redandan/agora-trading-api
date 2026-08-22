from __future__ import annotations

from datetime import date
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_cftc_tff_option_asset_manager_entry_admission_historical_v1 as runner


class OptionAssetManagerRunnerTest(unittest.TestCase):
    def futures_row(self, open_interest: str, asset_long: str, asset_short: str) -> list[str]:
        row = ["0"] * len(runner.reused.cftc_source.ORDERED_FIELDS)
        row[runner.FUTURES_OPEN_INTEREST_INDEX] = open_interest
        row[runner.FUTURES_ASSET_LONG_INDEX] = asset_long
        row[runner.FUTURES_ASSET_SHORT_INDEX] = asset_short
        return row

    @staticmethod
    def combined_row(open_interest: str, asset_long: str, asset_short: str) -> dict[str, runner.D]:
        return {
            "open_interest": runner.D(open_interest),
            "asset_long": runner.D(asset_long),
            "asset_short": runner.D(asset_short),
        }

    def test_option_net_pct_uses_combined_minus_futures(self) -> None:
        value = runner.option_asset_manager_net_pct_oi(
            self.futures_row("    100", "     20", "     10"),
            self.combined_row("120", "27", "13"),
        )
        self.assertEqual(runner.D("20"), value)

    def test_weekly_contraction_is_positive_at_second_week_boundary(self) -> None:
        futures = {
            date(2020, 1, 7): self.futures_row("100", "20", "10"),
            date(2020, 1, 14): self.futures_row("100", "20", "10"),
        }
        combined = {
            date(2020, 1, 7): self.combined_row("120", "28", "12"),
            date(2020, 1, 14): self.combined_row("120", "26", "14"),
        }
        points, exclusions = runner.build_factor_points(futures, combined)
        self.assertEqual(1, len(points))
        self.assertEqual("20", points[0]["factor_delta"])
        self.assertEqual(1, points[0]["factor_sign"])
        self.assertEqual("2020-01-21T00:00:00", points[0]["eligible_at"])
        self.assertEqual(1, exclusions["MISSING_EXACT_PREDECESSOR"])

    def test_nonpositive_option_open_interest_fails_closed_for_point(self) -> None:
        futures = {
            date(2020, 1, 7): self.futures_row("100", "20", "10"),
            date(2020, 1, 14): self.futures_row("100", "20", "10"),
        }
        combined = {
            date(2020, 1, 7): self.combined_row("100", "20", "10"),
            date(2020, 1, 14): self.combined_row("120", "25", "15"),
        }
        points, exclusions = runner.build_factor_points(futures, combined)
        self.assertEqual([], points)
        self.assertEqual(1, exclusions["NON_POSITIVE_OPTION_OPEN_INTEREST"])

    def test_off_tuesday_holiday_report_is_loaded_then_excluded(self) -> None:
        raw = json.dumps(
            [
                {
                    "report_date_as_yyyy_mm_dd": "2020-12-21T00:00:00.000",
                    "open_interest_all": "120",
                    "asset_mgr_positions_long": "27",
                    "asset_mgr_positions_short": "13",
                }
            ],
            separators=(",", ":"),
        ).encode("utf-8")
        combined = runner.load_combined_rows(raw)
        futures = {
            date(2020, 12, 21): self.futures_row("100", "20", "10")
        }
        points, exclusions = runner.build_factor_points(futures, combined)
        self.assertEqual([], points)
        self.assertEqual(1, exclusions["NON_TUESDAY"])

    def test_prior_freezes_one_raw_change_variant_and_denies_oos(self) -> None:
        prior = json.loads(
            (ROOT / "research_pipeline/examples/dra-cftc-option-asset-manager-primary-prior.v1.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertTrue(prior["frozen_before_factor_value_or_outcome_access"])
        self.assertEqual(1, prior["variant_count"])
        self.assertEqual("DENY", prior["oos_access"])
        self.assertIn("NOT_NON_MOMENTUM_RESIDUAL", prior["scientific_claim"])

    def test_manifest_schema_is_closed(self) -> None:
        schema = json.loads(
            (ROOT / "research_pipeline/btc-dra-cftc-tff-option-asset-manager-entry-admission-manifest.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        Draft202012Validator.check_schema(schema)
        self.assertFalse(schema["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
