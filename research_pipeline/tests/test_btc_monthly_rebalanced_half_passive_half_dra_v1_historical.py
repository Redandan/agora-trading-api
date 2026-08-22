from __future__ import annotations

from decimal import Decimal
import unittest

from research.btc_monthly_rebalanced_half_passive_half_dra_v1_historical import (
    RebalancedComponents,
)


D = Decimal


class MonthlyRebalancedHalfPassiveHalfDraV1Test(unittest.TestCase):
    def test_two_tradable_components_rebalance_to_equal_after_both_side_cost(self) -> None:
        components = RebalancedComponents(D("0.8"), D("0.2"))
        components.rebalance(D("0.0015"))
        self.assertEqual(components.first_value, components.second_value)
        self.assertEqual(D("0.6"), components.turnover)
        self.assertEqual(D("0.00090"), components.cost)
        self.assertEqual(D("0.8"), components.maximum_pre_rebalance_weight)

    def test_cash_comparator_charges_only_passive_component_trade(self) -> None:
        components = RebalancedComponents(D("0.8"), D("0.2"), True, False)
        components.rebalance(D("0.0015"))
        self.assertEqual(D("0.3"), components.turnover)
        self.assertEqual(D("0.00045"), components.cost)
        self.assertEqual(components.first_value, components.second_value)

    def test_component_returns_preserve_separate_sleeve_paths(self) -> None:
        components = RebalancedComponents(D("0.5"), D("0.5"))
        components.apply_returns(D("1.10"), D("0.90"))
        self.assertEqual(D("1.000"), components.equity)
        self.assertEqual(D("0.550"), components.first_value)
        self.assertEqual(D("0.450"), components.second_value)

    def test_terminal_weight_observation_captures_drift_without_a_rebalance(self) -> None:
        components = RebalancedComponents(D("0.5"), D("0.5"))
        components.apply_returns(D("1.8"), D("0.2"))
        components.observe_weight()
        self.assertEqual(D("0.9"), components.maximum_pre_rebalance_weight)
        self.assertEqual(0, components.rebalance_count)
        self.assertEqual(D("0"), components.cost)


if __name__ == "__main__":
    unittest.main()
