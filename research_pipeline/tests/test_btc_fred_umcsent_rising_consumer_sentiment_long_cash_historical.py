from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest

from research import btc_fred_umcsent_rising_consumer_sentiment_long_cash_historical as runner


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / "research_pipeline/examples/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical.v3.manifest.json"
UMCSENT_PATH = REPO_ROOT / ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/inputs/umcsent-monthly-2017-2024.csv"
ARTIFACT_DIR = REPO_ROOT / ".research-state/experiments/btc-fred-umcsent-rising-consumer-sentiment-long-cash-historical-v1/artifacts"


class FredUmcsentRisingConsumerSentimentHistoricalTest(unittest.TestCase):
    def test_manifest_and_factor_inventory_validate(self) -> None:
        manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        runner.validate_manifest(manifest)
        rows = runner.load_umcsent(UMCSENT_PATH)
        targets, feature = runner.targets_by_execution_time(rows)
        self.assertEqual(len(rows), 96)
        self.assertEqual(len(targets), 95)
        self.assertEqual(feature["rising_count"], 50)
        self.assertEqual(feature["non_rising_count"], 45)

    def test_sealed_runs_are_byte_identical_and_closed(self) -> None:
        raw1 = (ARTIFACT_DIR / "run1.json").read_bytes()
        raw2 = (ARTIFACT_DIR / "run2.json").read_bytes()
        self.assertEqual(raw1, raw2)
        self.assertEqual(
            hashlib.sha256(raw1).hexdigest(),
            "fe98bcfb2437bda8a4404a3d04df1e81ae3149919485b99f3b64c9777c7582a1",
        )
        result = json.loads(raw1)
        self.assertEqual(
            result["status"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_FAMILY",
        )
        self.assertFalse(result["candidate_created"])
        self.assertFalse(result["oos_opened"])
        self.assertEqual(len(result["failed_gates"]), 7)
        self.assertEqual(
            result["validation"]["scenarios"]["NORMAL"]["comparison"]["upside_capture_ratio"],
            "0.54596838",
        )


if __name__ == "__main__":
    unittest.main()
