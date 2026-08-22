from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import unittest

from research.btc_deribit_last_friday_monthly_options_expiry_risk_veto_historical import (
    DESIGN,
    VALIDATION,
    expected_transition_times,
    is_last_friday,
    target_long,
    validate_manifest,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-deribit-last-friday-monthly-options-expiry-risk-veto-historical.v1.manifest.json"


class DeribitMonthlyOptionsExpiryRiskVetoHistoricalTest(unittest.TestCase):
    def test_calendar_identity_transition_inventory_and_manifest_are_frozen(self) -> None:
        self.assertTrue(is_last_friday(datetime(2024, 1, 26)))
        self.assertFalse(is_last_friday(datetime(2024, 1, 19)))
        self.assertFalse(target_long(datetime(2024, 1, 26, 12)))
        self.assertTrue(target_long(datetime(2024, 1, 27)))

        design = expected_transition_times(*DESIGN)
        validation = expected_transition_times(*VALIDATION)
        self.assertEqual(len(design), 73)
        self.assertEqual(len(validation), 49)
        self.assertEqual(sum(not target_long(value) for value in design), 36)
        self.assertEqual(sum(not target_long(value) for value in validation), 24)

        validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")))

    def test_every_month_has_exactly_one_last_friday(self) -> None:
        for year in range(2019, 2025):
            for month in range(1, 13):
                days = [
                    datetime(year, month, day)
                    for day in range(1, 32)
                    if _valid_day(year, month, day)
                    and is_last_friday(datetime(year, month, day))
                ]
                self.assertEqual(len(days), 1, (year, month, days))


def _valid_day(year: int, month: int, day: int) -> bool:
    try:
        datetime(year, month, day)
        return True
    except ValueError:
        return False


if __name__ == "__main__":
    unittest.main()
