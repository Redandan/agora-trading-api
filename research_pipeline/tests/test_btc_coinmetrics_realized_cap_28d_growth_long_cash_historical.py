from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import unittest

from jsonschema import Draft202012Validator, FormatChecker


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research/btc_coinmetrics_realized_cap_28d_growth_long_cash_historical.cjs"
MANIFEST = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline/examples/btc-coinmetrics-realized-cap-28d-growth-long-cash-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline/hypothesis.schema.json"
SOURCE = REPO_ROOT / ".research-state/experiments/btc-coinmetrics-realized-cap-28d-growth-long-cash-historical-v1/inputs/coinmetrics-btc-realized-cap-2018-2024.csv"


class BtcCoinmetricsRealizedCap28dGrowthLongCashHistoricalTest(unittest.TestCase):
    def test_hypothesis_manifest_and_single_variant_are_frozen(self) -> None:
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(hypothesis)

        self.assertEqual(1, manifest["strategy_policy"]["variants"])
        self.assertEqual("SUNDAY_0000_UTC_DAILY_ROW", manifest["strategy_policy"]["observation_clock"])
        self.assertEqual(28, manifest["strategy_policy"]["growth_lag_days"])
        self.assertEqual("OBSERVATION_PLUS_THREE_CALENDAR_DAYS_0000_UTC", manifest["strategy_policy"]["decision_clock"])
        self.assertEqual(168, manifest["strategy_policy"]["signal_validity_hours"])
        self.assertEqual("DENY", manifest["oos_access"])

        process = subprocess.run(
            ["node", "-e", "const r=require(process.argv[1]);const fs=require('fs');r.validateManifest(JSON.parse(fs.readFileSync(process.argv[2],'utf8')));", str(RUNNER), str(MANIFEST)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, process.returncode, process.stderr)

    def test_sealed_source_formula_and_pre_outcome_counts_match(self) -> None:
        self.assertEqual(
            "d24ab2b1445226894feb8fef0f7843485f9fc520d79992b2ecb14a825c96056a",
            hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
        )
        script = """
const r=require(process.argv[1]);
const source=r.parseRealizedCapRows(process.argv[2]);
const targets=r.targetsByEffectiveTime(source.rows);
const design=r.summarizeTargets(targets,['2019-01-01T00:00:00','2023-01-01T00:00:00']);
const validation=r.summarizeTargets(targets,['2023-01-01T00:00:00','2025-01-01T00:00:00']);
process.stdout.write(JSON.stringify({rows:source.rows.length,design,validation}));
"""
        process = subprocess.run(
            ["node", "-e", script, str(RUNNER), str(SOURCE)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, process.returncode, process.stderr)
        actual = json.loads(process.stdout)
        self.assertEqual(2557, actual["rows"])
        self.assertEqual({"evaluations": 209, "positive": 128, "nonpositive": 81, "transitions": 12}, actual["design"])
        self.assertEqual({"evaluations": 104, "positive": 89, "nonpositive": 15, "transitions": 9}, actual["validation"])


if __name__ == "__main__":
    unittest.main()
