from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker

from research.btc_bls_employment_situation_scheduled_release_day_risk_veto_historical import (
    DESIGN,
    EXPECTED_EVENT_DATES,
    VALIDATION,
    expected_transition_times,
    is_event_day,
    target_long,
    validate_calendar,
    validate_manifest,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-bls-employment-situation-scheduled-release-day-risk-veto-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline/examples/btc-bls-employment-situation-scheduled-release-day-risk-veto-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline/hypothesis.schema.json"


class BlsEmploymentSituationScheduledReleaseDayRiskVetoHistoricalTest(unittest.TestCase):
    def test_calendar_hypothesis_transition_inventory_and_manifest_are_frozen(self) -> None:
        calendar = validate_calendar()
        self.assertEqual(60, len(calendar["events"]))
        self.assertEqual(36, sum(item["window"] == "DESIGN" for item in calendar["events"]))
        self.assertEqual(24, sum(item["window"] == "VALIDATION" for item in calendar["events"]))
        self.assertEqual(EXPECTED_EVENT_DATES, tuple(item["date"] for item in calendar["events"]))

        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(hypothesis)
        validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")))

        design = expected_transition_times(*DESIGN)
        validation = expected_transition_times(*VALIDATION)
        self.assertEqual(73, len(design))
        self.assertEqual(49, len(validation))
        self.assertEqual(36, sum(not target_long(value) for value in design))
        self.assertEqual(24, sum(not target_long(value) for value in validation))

    def test_only_frozen_official_release_dates_are_event_days(self) -> None:
        self.assertTrue(is_event_day(datetime(2020, 1, 10, 12)))
        self.assertFalse(is_event_day(datetime(2020, 1, 11, 12)))
        self.assertTrue(is_event_day(datetime(2020, 7, 2, 23)))
        self.assertTrue(is_event_day(datetime(2024, 11, 1, 23)))
        self.assertFalse(is_event_day(datetime(2024, 11, 2)))


if __name__ == "__main__":
    unittest.main()
