from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import unittest

import jsonschema


REPO_ROOT = Path(__file__).resolve().parents[2]
RUNNER = REPO_ROOT / "research" / "btc_daily_trailing365d_drawdown20_passive_core_risk_overlay_historical.py"
MANIFEST = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-trailing365d-drawdown20-passive-core-risk-overlay-historical.v1.manifest.json"
HYPOTHESIS = REPO_ROOT / "research_pipeline" / "examples" / "btc-daily-trailing365d-drawdown20-passive-core-v1.hypothesis.json"
HYPOTHESIS_SCHEMA = REPO_ROOT / "research_pipeline" / "hypothesis.schema.json"


def load_runner():
    spec = importlib.util.spec_from_file_location("trailing365d_drawdown_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class Trailing365dDrawdownRunnerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.runner = load_runner()

    def test_hypothesis_is_schema_complete_and_research_only(self) -> None:
        hypothesis = json.loads(HYPOTHESIS.read_text(encoding="utf-8"))
        schema = json.loads(HYPOTHESIS_SCHEMA.read_text(encoding="utf-8"))
        jsonschema.validate(hypothesis, schema)
        self.assertEqual(
            hypothesis["authorization"],
            "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        )

    def test_manifest_accepts_exact_state_change_only_contract(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.runner.validate_manifest(manifest)
        self.assertEqual(
            manifest["strategy_policy"]["primary"]["high_risk_drawdown_threshold_pct"],
            -20,
        )

    def test_manifest_rejects_threshold_substitution(self) -> None:
        manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        manifest["strategy_policy"]["primary"]["high_risk_drawdown_threshold_pct"] = -18
        with self.assertRaisesRegex(self.runner.ResearchReject, "PRIMARY"):
            self.runner.validate_manifest(manifest)

    def test_trailing_drawdown_uses_current_close_and_trailing_peak(self) -> None:
        closes = [Decimal("100")] * 364 + [Decimal("80")]
        self.assertEqual(
            self.runner.trailing_close_drawdown_pct(closes), Decimal("-20")
        )
        at_peak = [Decimal("80")] * 364 + [Decimal("100")]
        self.assertEqual(
            self.runner.trailing_close_drawdown_pct(at_peak), Decimal("0")
        )

    def test_first_feature_is_available_at_first_design_open(self) -> None:
        point_type = type("Point", (), {})
        start = datetime(2019, 1, 1)
        daily = []
        for index in range(365):
            point = point_type()
            point.close = Decimal("100")
            point.close_time = start + timedelta(days=index + 1)
            daily.append(point)
        points = self.runner.build_drawdown_points(daily)
        self.assertEqual(len(points), 1)
        self.assertEqual(points[0].effective_time, datetime(2020, 1, 1))

    def test_target_relation_includes_threshold_equality(self) -> None:
        start = datetime(2020, 1, 1)
        points = [
            self.runner.DrawdownPoint(start, Decimal("-19.9999")),
            self.runner.DrawdownPoint(start + timedelta(days=1), Decimal("-20")),
            self.runner.DrawdownPoint(start + timedelta(days=2), Decimal("-20.0001")),
        ]
        targets = self.runner.build_targets(points, Decimal("20"))
        self.assertEqual(
            list(targets.values()),
            [Decimal("1"), Decimal("0.5"), Decimal("0.5")],
        )

    def test_all_frozen_source_hashes_match(self) -> None:
        for path, expected in self.runner.EXPECTED_SOURCE_HASHES.items():
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(actual, expected, str(path))


if __name__ == "__main__":
    unittest.main()
