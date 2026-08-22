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

import btc_dra_cftc_tff_asset_manager_contrarian_entry_admission_historical_v1 as runner


class AssetManagerContrarianRunnerTest(unittest.TestCase):
    def row(self, long_pct: str, short_pct: str) -> list[str]:
        row = ["0"] * len(runner.cftc_source.ORDERED_FIELDS)
        row[runner.ASSET_LONG_INDEX] = long_pct
        row[runner.ASSET_SHORT_INDEX] = short_pct
        return row

    def test_contraction_is_positive_contrarian_score(self) -> None:
        rows = {
            date(2020, 1, 7): self.row("20", "5"),
            date(2020, 1, 14): self.row("14", "6"),
        }
        points, exclusions = runner.build_factor_points(rows)
        self.assertEqual(1, len(points))
        self.assertEqual("7", points[0]["factor_delta"])
        self.assertEqual(1, points[0]["factor_sign"])
        self.assertEqual(1, exclusions["MISSING_EXACT_PREDECESSOR"])

    def test_expansion_is_negative_and_zero_is_neutral(self) -> None:
        rows = {
            date(2020, 1, 7): self.row("10", "5"),
            date(2020, 1, 14): self.row("12", "4"),
            date(2020, 1, 21): self.row("12", "4"),
        }
        points, _ = runner.build_factor_points(rows)
        self.assertEqual([-1, 0], [point["factor_sign"] for point in points])

    def test_prior_freezes_one_variant_and_denies_oos(self) -> None:
        prior = json.loads((ROOT / "research_pipeline/examples/dra-cftc-asset-manager-contrarian-primary-prior.v1.json").read_text(encoding="utf-8"))
        self.assertTrue(prior["frozen_before_factor_value_or_outcome_access"])
        self.assertEqual(1, prior["variant_count"])
        self.assertEqual("DENY", prior["oos_access"])

    def test_manifest_schema_is_closed(self) -> None:
        schema = json.loads((ROOT / "research_pipeline/btc-dra-cftc-tff-asset-manager-contrarian-entry-admission-manifest.v1.schema.json").read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        self.assertFalse(schema["additionalProperties"])


if __name__ == "__main__":
    unittest.main()
