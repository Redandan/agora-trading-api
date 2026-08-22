from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import unittest

from jsonschema import Draft202012Validator, FormatChecker


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_first_six_hour_direction_24h_state_long_cash_historical.cjs"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline/examples/btc-first-six-hour-direction-24h-state-long-cash-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline/hypothesis.schema.json"
RUN1 = REPO_ROOT / ".research-state/experiments/btc-first-six-hour-direction-24h-state-long-cash-historical-v1/artifacts/run1.json"
RUN2 = REPO_ROOT / ".research-state/experiments/btc-first-six-hour-direction-24h-state-long-cash-historical-v1/artifacts/run2.json"


class BtcFirstSixHourDirection24hStateLongCashHistoricalTest(unittest.TestCase):
    def test_frozen_hypothesis_manifest_and_feature_counts(self) -> None:
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(hypothesis)
        script = """
const r=require(process.argv[1]);
const e=require('./research/btc_treasury_term_spread_long_cash_historical.cjs');
const fs=require('fs');
r.validateManifest(JSON.parse(fs.readFileSync(process.argv[2],'utf8')));
const btc=e.parseBtcRows('.research-state/java-parity/selection-2019-2024.tsv');
const feature=r.targetsAndCounts(btc.bars);
process.stdout.write(JSON.stringify(feature.counts));
"""
        process = subprocess.run(
            ["node", "-e", script, str(RUNNER), str(MANIFEST)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, process.returncode, process.stderr)
        self.assertEqual(
            {
                "design": {"evaluations": 1461, "positive": 689, "nonpositive": 772, "transitions": 764},
                "validation": {"evaluations": 731, "positive": 370, "nonpositive": 361, "transitions": 378},
            },
            json.loads(process.stdout),
        )

    def test_sealed_runs_are_byte_identical_terminal_no_candidate(self) -> None:
        self.assertEqual(RUN1.read_bytes(), RUN2.read_bytes())
        self.assertEqual(
            "5dee9dd1d8b25afafbabdc1338ea508fa6dc7c6d76d9934119721322c61e7e5f",
            hashlib.sha256(RUN1.read_bytes()).hexdigest(),
        )
        result = json.loads(RUN1.read_text(encoding="utf-8"))
        self.assertEqual(
            "NO_CANDIDATE_CLOSE_BTC_FIRST_SIX_HOUR_DIRECTION_24H_STATE_LONG_CASH_FAMILY",
            result["status"],
        )
        self.assertFalse(result["oos_opened"])
        self.assertFalse(result["all_gates_pass"])


if __name__ == "__main__":
    unittest.main()
