from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_binance_usdm_endpoint_oi_confirmation_v2 as runner
from research_pipeline.tests.test_binance_usdm_archive import metrics_rows, zipped


D = Decimal


def observation(day: date, oi_return: str) -> runner.shared.ExternalDay:
    return runner.shared.ExternalDay(
        day=day,
        available_at=datetime.combine(day + timedelta(days=1), datetime.min.time()),
        price_return=D("0"),
        oi_value_return=D(oi_return),
        top_trader_long_short_ratio=None,
        global_long_short_ratio=None,
        taker_long_short_ratio=None,
        source_normalized_sha256="1" * 64,
    )


def signal_bar(day: date) -> runner.base.Bar:
    opened = datetime.combine(day, datetime.min.time()).replace(hour=23)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=D("100"),
        high=D("101"),
        low=D("99"),
        close=D("100"),
        volume=D("1"),
    )


class EndpointOiConfirmationV2Test(unittest.TestCase):
    def test_endpoint_representation_accepts_an_unrelated_intraday_gap(self) -> None:
        rows = metrics_rows()
        del rows[100]
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "AVAILABLE_23_55")
        self.assertEqual(audit.sum_open_interest, "1287")

    def test_endpoint_representation_ignores_unrelated_off_grid_timestamp(self) -> None:
        rows = metrics_rows()
        rows[100] = rows[100].replace("00,", "01,", 1)
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "AVAILABLE_23_55")
        self.assertEqual(audit.sum_open_interest, "1287")

    def test_endpoint_representation_ignores_unrelated_out_of_day_row(self) -> None:
        rows = metrics_rows()
        rows.append(rows[1].replace("2024-01-02 00:00:00", "2024-01-03 00:00:00"))
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "AVAILABLE_23_55")
        self.assertEqual(audit.sum_open_interest, "1287")

    def test_endpoint_representation_ignores_nonpositive_unrelated_intraday_oi(self) -> None:
        rows = metrics_rows()
        values = rows[100].split(",")
        values[2] = "0E-8"
        values[3] = "0E-8"
        values[4] = ""
        values[5] = ""
        values[6] = ""
        rows[100] = ",".join(values)
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "AVAILABLE_23_55")
        self.assertEqual(audit.sum_open_interest, "1287")

    def test_missing_2355_is_explicit_pass_through_not_imputed(self) -> None:
        rows = metrics_rows()
        rows = [row for row in rows if not row.startswith("2024-01-02 23:55:00,")]
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "MISSING_23_55_PASS_THROUGH")
        self.assertIsNone(audit.sum_open_interest)
        self.assertIsNone(audit.endpoint_row_sha256)

    def test_unusable_2355_is_explicit_pass_through_not_imputed(self) -> None:
        rows = metrics_rows()
        endpoint_index = next(
            index
            for index, row in enumerate(rows)
            if row.startswith("2024-01-02 23:55:00,")
        )
        values = rows[endpoint_index].split(",")
        values[2] = "0E-8"
        values[3] = "0E-8"
        rows[endpoint_index] = ",".join(values)
        raw, checksum = zipped(rows)

        audit = runner._read_endpoint_archive(
            "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
        )

        self.assertEqual(audit.endpoint_status, "UNUSABLE_23_55_PASS_THROUGH")
        self.assertIsNone(audit.sum_open_interest)
        self.assertIsNotNone(audit.endpoint_row_sha256)

    def test_conflicting_endpoint_duplicate_still_fails_closed(self) -> None:
        rows = metrics_rows()
        endpoint = next(row for row in rows if row.startswith("2024-01-02 23:55:00,"))
        rows.append(endpoint.replace(",1287,", ",9999,"))
        raw, checksum = zipped(rows)

        with self.assertRaisesRegex(runner.ResearchReject, "conflicting duplicate"):
            runner._read_endpoint_archive(
                "BTCUSDT-metrics-2024-01-02.zip", raw, checksum
            )

    def test_signal_predicate_confirms_nondecreasing_and_vetoes_contraction(self) -> None:
        day = date(2024, 1, 2)
        bar = signal_bar(day)
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine, "_signal", return_value=True
        ):
            confirmed = runner.EndpointOiConfirmationEngine([observation(day, "0")])
            self.assertTrue(confirmed._signal(bar))
            self.assertEqual(confirmed.confirmed_signal_count, 1)

            vetoed = runner.EndpointOiConfirmationEngine([observation(day, "-0.01")])
            self.assertFalse(vetoed._signal(bar))
            self.assertEqual(vetoed.vetoed_signal_count, 1)

    def test_missing_feature_preserves_parent_signal_and_is_counted(self) -> None:
        day = date(2024, 1, 2)
        with patch.object(
            runner.capacity.EqualCapitalCapacityEngine, "_signal", return_value=True
        ):
            engine = runner.EndpointOiConfirmationEngine([])
            self.assertTrue(engine._signal(signal_bar(day)))
        self.assertEqual(engine.feature_unavailable_signal_count, 1)
        self.assertEqual(engine.vetoed_signal_count, 0)


if __name__ == "__main__":
    unittest.main()
