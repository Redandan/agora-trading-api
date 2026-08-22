from __future__ import annotations

import unittest

from research.btc_eth_static_log_spread_convergence_v1 import (
    fit_ols,
    population_std,
    summarize,
)


class BtcEthStaticLogSpreadConvergenceV1Test(unittest.TestCase):
    def test_formation_ols_recovers_intercept_and_beta(self) -> None:
        alpha, beta = fit_ols(
            [1.0, 2.0, 3.0, 4.0],
            [2.5, 4.5, 6.5, 8.5],
        )

        self.assertAlmostEqual(0.5, alpha)
        self.assertAlmostEqual(2.0, beta)

    def test_population_scale_uses_frozen_center(self) -> None:
        self.assertAlmostEqual(
            1.0,
            population_std([-1.0, 1.0], center=0.0),
        )

    def test_summary_separates_event_direction_and_non_event_risk(self) -> None:
        report, raw = summarize(
            [
                {"event": True, "direction": "ETH_RICH", "convergence": 0.4},
                {"event": True, "direction": "ETH_RICH", "convergence": -0.1},
                {"event": True, "direction": "ETH_CHEAP", "convergence": 0.3},
                {"event": True, "direction": "ETH_CHEAP", "convergence": 0.2},
                {"event": False, "direction": "ETH_RICH", "convergence": 0.1},
                {"event": False, "direction": "ETH_CHEAP", "convergence": -0.2},
            ]
        )

        self.assertEqual(4, report["event_count"])
        self.assertEqual(2, report["eth_rich_event_count"])
        self.assertEqual(2, report["eth_cheap_event_count"])
        self.assertAlmostEqual(0.2, raw["event_mean"])
        self.assertAlmostEqual(0.15, raw["rich_mean"])
        self.assertAlmostEqual(0.25, raw["cheap_mean"])
        self.assertGreater(raw["event_mean_to_downside"], raw["non_event_mean_to_downside"])


if __name__ == "__main__":
    unittest.main()
