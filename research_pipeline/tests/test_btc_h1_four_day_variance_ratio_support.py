from __future__ import annotations

from datetime import datetime, timedelta
from decimal import Decimal, getcontext
import importlib.util
from pathlib import Path
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[2]
PROBE = REPO_ROOT / "research/btc_h1_four_day_variance_ratio_support.py"
SPEC = importlib.util.spec_from_file_location("btc_h1_variance_ratio_support", PROBE)
assert SPEC is not None and SPEC.loader is not None
support = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = support
SPEC.loader.exec_module(support)
V2_PROBE = REPO_ROOT / "research/btc_h1_four_day_variance_ratio_support_v2.py"
V2_SPEC = importlib.util.spec_from_file_location("btc_h1_variance_ratio_support_v2", V2_PROBE)
assert V2_SPEC is not None and V2_SPEC.loader is not None
support_v2 = importlib.util.module_from_spec(V2_SPEC)
sys.modules[V2_SPEC.name] = support_v2
V2_SPEC.loader.exec_module(support_v2)
D = Decimal


def closes(multipliers: list[D]) -> list[object]:
    values = [D("100")]
    for multiplier in multipliers:
        values.append(values[-1] * multiplier)
    start = datetime(2020, 1, 1)
    return [support.DailyClose(start + timedelta(days=index), value) for index, value in enumerate(values)]


class BtcH1FourDayVarianceRatioSupportTest(unittest.TestCase):
    def test_frozen_spec_and_hash_bindings_validate(self) -> None:
        document = support.load_and_validate_spec()
        self.assertEqual(document["factor_contract"]["expected_total_feature_evaluations"], 2164)
        self.assertEqual(document["execution_contract"]["maximum_support_runs"], 1)

    def test_alternating_path_is_not_persistent(self) -> None:
        window = closes([D("1.01"), D("0.99")] * 14)
        ratio, _ = support.calculate_state(window)
        self.assertLess(ratio, D("1"))

    def test_clustered_positive_path_is_persistent_and_directional(self) -> None:
        window = closes([D("1.02")] * 18 + [D("0.99")] * 10)
        ratio, direction = support.calculate_state(window)
        self.assertGreater(ratio, D("1"))
        self.assertTrue(direction)

    def test_later_close_cannot_change_prior_effective_state(self) -> None:
        base = closes([D("1.02")] * 18 + [D("0.99")] * 10)
        first = support.build_feature_states(base, expected_evaluations=1)[0]
        extended = base + [support.DailyClose(base[-1].close_time + timedelta(days=1), base[-1].close * D("0.5"))]
        states = support.build_feature_states(extended, expected_evaluations=2)
        self.assertEqual(states[0], first)
        self.assertEqual(len(states), 2)

    def test_v2_local_precision_is_independent_of_process_context(self) -> None:
        window = closes([D("1.02")] * 18 + [D("0.99")] * 10)
        original_precision = getcontext().prec
        try:
            getcontext().prec = 12
            low_context = support_v2.calculate_state(window)
            getcontext().prec = 34
            parser_context = support_v2.calculate_state(window)
        finally:
            getcontext().prec = original_precision
        self.assertEqual(low_context, parser_context)
        self.assertEqual(support_v2.validate_v2_spec()["support_gates"]["no_gate_change"], True)


if __name__ == "__main__":
    unittest.main()
