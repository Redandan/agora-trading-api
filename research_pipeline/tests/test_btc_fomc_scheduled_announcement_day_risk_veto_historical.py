from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator, FormatChecker

from research.btc_fomc_scheduled_announcement_day_risk_veto_historical import (
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
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-fomc-scheduled-announcement-day-risk-veto-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline/examples/btc-fomc-scheduled-announcement-day-risk-veto-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline/hypothesis.schema.json"


class FomcScheduledAnnouncementDayRiskVetoHistoricalTest(unittest.TestCase):
    def test_calendar_hypothesis_transition_inventory_and_manifest_are_frozen(self) -> None:
        calendar = validate_calendar()
        self.assertEqual(39, len(calendar["events"]))
        self.assertEqual(23, sum(item["window"] == "DESIGN" for item in calendar["events"]))
        self.assertEqual(16, sum(item["window"] == "VALIDATION" for item in calendar["events"]))
        self.assertEqual(EXPECTED_EVENT_DATES, tuple(item["date"] for item in calendar["events"]))

        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(hypothesis)
        validate_manifest(json.loads(MANIFEST.read_text(encoding="utf-8")))

        design = expected_transition_times(*DESIGN)
        validation = expected_transition_times(*VALIDATION)
        self.assertEqual(47, len(design))
        self.assertEqual(33, len(validation))
        self.assertEqual(23, sum(not target_long(value) for value in design))
        self.assertEqual(16, sum(not target_long(value) for value in validation))

    def test_only_frozen_final_meeting_dates_are_event_days(self) -> None:
        self.assertTrue(is_event_day(datetime(2020, 1, 29, 12)))
        self.assertFalse(is_event_day(datetime(2020, 3, 18, 12)))
        self.assertFalse(is_event_day(datetime(2020, 3, 15, 12)))
        self.assertTrue(is_event_day(datetime(2024, 11, 7, 23)))
        self.assertFalse(is_event_day(datetime(2024, 11, 8)))


if __name__ == "__main__":
    unittest.main()
