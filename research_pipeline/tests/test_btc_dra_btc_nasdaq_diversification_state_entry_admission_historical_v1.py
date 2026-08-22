from __future__ import annotations

from datetime import date, timedelta
import hashlib
import json
from pathlib import Path
import sys
import unittest

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_btc_nasdaq_diversification_state_entry_admission_historical_v1 as runner


MANIFEST_PATH = ROOT / "research_pipeline/examples/dra-btc-nasdaq-diversification-state-entry-admission-historical.v1.manifest.json"
SCHEMA_PATH = ROOT / "research_pipeline/btc-dra-btc-nasdaq-diversification-state-entry-admission-manifest.v1.schema.json"
SELECTION_PATH = ROOT / ".research-state/java-parity/selection-2019-2024.tsv"
DECISION_PATH = ROOT / "research_pipeline/examples/dra-btc-nasdaq-diversification-state-entry-admission-historical.v1.decision.json"
RUN1_PATH = ROOT / ".research-state/experiments" / runner.EXPERIMENT_ID / "artifacts/run1.json"
RUN2_PATH = ROOT / ".research-state/experiments" / runner.EXPERIMENT_ID / "artifacts/run2.json"


def _business_dates(first: date, count: int) -> list[date]:
    values: list[date] = []
    day = first
    while len(values) < count:
        if day.weekday() <= 4:
            values.append(day)
        day += timedelta(days=1)
    return values


class BtcNasdaqDiversificationDraScreenTest(unittest.TestCase):
    def test_frozen_manifest_is_schema_valid_and_all_bindings_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        value = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        verified = runner.reused.verify_bindings(manifest)
        self.assertEqual(set(manifest["bindings"]), set(verified))

    def test_decimal_pearson_is_exact_for_identical_and_inverse_samples(self) -> None:
        sample = [runner.D("-2"), runner.D("-1"), runner.D("1"), runner.D("2")]
        self.assertEqual(runner.D("1"), runner.pearson(sample, sample))
        self.assertEqual(runner.D("-1"), runner.pearson(sample, [-value for value in sample]))

    def test_factor_uses_63_pairs_and_only_final_nasdaq_date_per_iso_week(self) -> None:
        nasdaq_dates = _business_dates(date(2023, 1, 2), 64)
        nasdaq = {day: runner.D(1000 + index * index + index) for index, day in enumerate(nasdaq_dates)}
        first_btc = nasdaq_dates[0] - timedelta(days=1)
        btc: dict[date, runner.D] = {}
        day = first_btc
        index = 0
        while day <= nasdaq_dates[-1]:
            btc[day] = runner.D(2000 + index * index + 3 * index)
            day += timedelta(days=1)
            index += 1
        points, exclusions = runner.build_factor_points(btc, nasdaq, runner.PRIMARY_THRESHOLD)
        self.assertEqual(1, len(points))
        self.assertEqual(nasdaq_dates[-1].isoformat(), points[0]["report_date"])
        self.assertEqual((nasdaq_dates[-1] + timedelta(days=1)).isoformat() + "T00:00:00", points[0]["eligible_at"])
        self.assertEqual(63, points[0]["window_pair_count"])
        self.assertEqual(nasdaq_dates[1].isoformat(), points[0]["window_first_pair_date"])
        self.assertEqual(62, exclusions["MISSING_INITIAL_63_PAIRED_RETURNS"])

    def test_sealed_sources_construct_complete_btc_days_and_weekly_factor_points(self) -> None:
        manifest, _ = runner.load_manifest(MANIFEST_PATH)
        bindings = runner.reused.verify_bindings(manifest)
        bars = runner.reused.load_selection(SELECTION_PATH, manifest)
        nasdaq, evidence = runner.load_nasdaq(bindings)
        btc = runner.btc_daily_closes(bars)
        points, exclusions = runner.build_factor_points(btc, nasdaq, runner.PRIMARY_THRESHOLD)
        self.assertEqual((1762, 2192), (len(nasdaq), len(btc)))
        self.assertGreater(len(points), 250)
        self.assertEqual(62, exclusions["MISSING_INITIAL_63_PAIRED_RETURNS"])
        self.assertEqual(1, exclusions["DECISION_AT_OR_AFTER_CUTOFF"])
        self.assertEqual(1762, evidence["rows"])

    def test_sealed_runs_are_byte_identical_and_decision_closes_exact_family(self) -> None:
        run1 = RUN1_PATH.read_bytes()
        run2 = RUN2_PATH.read_bytes()
        self.assertEqual(run1, run2)
        self.assertEqual("70a23501446795b9b1679579d2733bde1c6fed7cc218f5683b81e54edab5651b", hashlib.sha256(run1).hexdigest())
        result = json.loads(run1.decode("utf-8"))
        decision = json.loads(DECISION_PATH.read_text(encoding="utf-8"))
        self.assertEqual("NO_CANDIDATE_CLOSE_BTC_NASDAQ_DIVERSIFICATION_STATE_FAMILY", result["status"])
        self.assertEqual(result["status"], decision["status"])
        self.assertFalse(result["oos_opened"])
        self.assertFalse(decision["candidate_created"])
        self.assertEqual("5.82472139", result["variant_evidence"]["0.00"]["economic_evidence"]["validation"]["total_pnl_usdt"])
        self.assertEqual("-80.37826047", decision["performance_evidence"]["primary_000_validation"]["total_pnl_delta_usdt"])


if __name__ == "__main__":
    unittest.main()
