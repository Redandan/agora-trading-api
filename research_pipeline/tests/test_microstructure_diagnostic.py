from __future__ import annotations

import copy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
from pathlib import Path
import tempfile
import unittest

from research_pipeline.microstructure_diagnostic import (
    AUTHORIZATION,
    CANONICALIZATION,
    MinuteRecord,
    TIER_KEYS,
    analyze_files,
    analyze_records,
    payload_sha256,
    validate_day_bundle,
    write_new_json,
)


def _record(
    minute: datetime,
    *,
    total: str = "100",
    net: str = "0",
    above_mid_buy: str = "50",
    below_mid_sell: str = "50",
    imbalance: str = "0",
    replenishment: str = "0",
    open_price: str = "100",
    high_price: str = "100",
    low_price: str = "100",
    close_price: str = "100",
) -> MinuteRecord:
    return MinuteRecord(
        minute=minute,
        total_quote_notional=Decimal(total),
        net_taker_quote_notional=Decimal(net),
        above_mid_buy_quote_notional=Decimal(above_mid_buy),
        below_mid_sell_quote_notional=Decimal(below_mid_sell),
        average_book_imbalance=Decimal(imbalance),
        bid_replenishment_quote_proxy=Decimal(replenishment),
        trade_open_price=Decimal(open_price),
        trade_high_price=Decimal(high_price),
        trade_low_price=Decimal(low_price),
        trade_close_price=Decimal(close_price),
    )


def _minute_json(minute: datetime) -> dict[str, object]:
    timestamp = minute.isoformat().replace("+00:00", "Z")
    return {
        "minute": timestamp,
        "trade_record_count": 1,
        "match_count": 1,
        "midline_reference_count": 1,
        "buy_quote_notional": "60",
        "sell_quote_notional": "40",
        "total_quote_notional": "100",
        "net_taker_quote_notional": "20",
        "above_mid_buy_quote_notional": "60",
        "below_mid_sell_quote_notional": "40",
        "midline_other_quote_notional": "0",
        "trade_open_price": "100",
        "trade_high_price": "101",
        "trade_low_price": "99",
        "trade_close_price": "100",
        "trade_vwap_price": "100",
        "first_trade_at": (minute + timedelta(seconds=5))
        .isoformat()
        .replace("+00:00", "Z"),
        "last_trade_at": (minute + timedelta(seconds=55))
        .isoformat()
        .replace("+00:00", "Z"),
        "book_sample_count": 1,
        "average_top5_bid_quote_depth": "1000",
        "average_top5_ask_quote_depth": "900",
        "average_book_imbalance": "0.0526315789",
        "average_spread_bps": "1",
        "bid_replenishment_quote_proxy": "10",
        "mid_price_start": "100",
        "mid_price_high": "101",
        "mid_price_low": "99",
        "mid_price_end": "100",
        "first_book_at": (minute + timedelta(seconds=1))
        .isoformat()
        .replace("+00:00", "Z"),
        "last_book_at": (minute + timedelta(seconds=59))
        .isoformat()
        .replace("+00:00", "Z"),
    }


def _sealed_day(bundle_day: date = date(2026, 1, 1)) -> dict[str, object]:
    start = datetime.combine(bundle_day, datetime.min.time(), tzinfo=timezone.utc)
    end = start + timedelta(days=1)
    bundle: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_FORWARD_DAY_V3",
        "bundle_type": "FORWARD_MICROSTRUCTURE_DAY_RESEARCH_ONLY",
        "authorization": AUTHORIZATION,
        "source": {
            "venue": "OKX",
            "instrument": "BTC-USDT",
            "channels": ["trades", "books5"],
            "mode": "FORWARD_ONLY",
            "historical_backfill": False,
            "raw_messages_persisted": False,
            "aggregation_timezone": "UTC",
            "midline_formula": "BEST_BID_1_PLUS_BEST_ASK_1_DIVIDED_BY_2",
            "midline_reference": "LATEST_BOOKS5_AT_OR_BEFORE_TRADE",
            "unreferenced_trade_disposition": "INTEGRITY_ANOMALY",
        },
        "day": bundle_day.isoformat(),
        "capture": {
            "started_at": start.isoformat().replace("+00:00", "Z"),
            "ended_at": end.isoformat().replace("+00:00", "Z"),
            "acknowledged_channels": ["books5", "trades"],
        },
        "integrity": {
            "status": "CLEAN",
            "anomaly_count": 0,
            "raw_message_count": 2880,
            "arrival_chain_sha256": "a" * 64,
            "midline_unreferenced_trade_count": 0,
            "crossed_book_count": 0,
        },
        "minutes": [_minute_json(start + timedelta(minutes=index)) for index in range(1440)],
    }
    bundle["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": payload_sha256(bundle),
        "canonicalization": CANONICALIZATION,
        "sealed_at": end.isoformat().replace("+00:00", "Z"),
    }
    return bundle


def _reseal(bundle: dict[str, object]) -> None:
    seal = bundle["seal"]
    assert isinstance(seal, dict)
    seal["payload_sha256"] = payload_sha256(bundle)


class MicrostructureDiagnosticTest(unittest.TestCase):
    def test_next_minute_open_and_nested_tiers(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [_record(start + timedelta(minutes=index)) for index in range(1461)]
        records[20] = _record(
            records[20].minute,
            total="250",
            net="10",
            above_mid_buy="150",
            below_mid_sell="100",
            imbalance="0.1",
            replenishment="1",
            open_price="1000",
            high_price="1000",
            low_price="1000",
            close_price="1000",
        )
        records[21] = _record(
            records[21].minute,
            open_price="101",
            high_price="101",
            low_price="101",
            close_price="101",
        )
        records[25] = _record(
            records[25].minute,
            open_price="102",
            high_price="102",
            low_price="102",
            close_price="102",
        )

        result = analyze_records(records)

        for tier in TIER_KEYS:
            self.assertEqual(1, result[tier]["event_count"])
            event = result[tier]["events"][0]
            self.assertEqual("2026-01-01T00:21:00Z", event["entry_at"])
            self.assertEqual("101", event["entry_open_price"])
            self.assertEqual("1.5", event["midline_buy_sell_ratio"])
            self.assertEqual("99.00990099", event["response"]["5"]["return_bps"])

    def test_cooldown_is_applied_independently_per_tier(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [_record(start + timedelta(minutes=index)) for index in range(1521)]
        for index in (20, 30, 80):
            records[index] = _record(
                records[index].minute,
                total="250",
                net="10",
                above_mid_buy="150",
                below_mid_sell="100",
                imbalance="0.1",
                replenishment="1",
            )

        result = analyze_records(records)

        for tier in TIER_KEYS:
            self.assertEqual(2, result[tier]["event_count"])
            self.assertEqual(
                ["2026-01-01T00:20:00Z", "2026-01-01T01:20:00Z"],
                [event["signal_at"] for event in result[tier]["events"]],
            )

    def test_control_is_same_minute_of_day_and_strictly_earlier(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [_record(start + timedelta(minutes=index)) for index in range(2901)]
        event_index = 1440 + 20
        records[event_index] = _record(
            records[event_index].minute,
            total="250",
            net="10",
            above_mid_buy="150",
            below_mid_sell="100",
            imbalance="0.1",
            replenishment="1",
        )

        result = analyze_records(records)

        for tier in TIER_KEYS:
            event = result[tier]["events"][0]
            self.assertEqual("2026-01-02T00:20:00Z", event["signal_at"])
            self.assertEqual("2026-01-01T00:20:00Z", event["matched_control"]["signal_at"])
            self.assertEqual("100", result[tier]["matched_control_coverage_pct"])

    def test_zero_below_mid_sell_denominator_is_not_an_event(self) -> None:
        start = datetime(2026, 1, 1, tzinfo=timezone.utc)
        records = [_record(start + timedelta(minutes=index)) for index in range(1461)]
        records[20] = _record(
            records[20].minute,
            total="100",
            net="100",
            above_mid_buy="100",
            below_mid_sell="0",
            imbalance="0.1",
            replenishment="1",
        )

        result = analyze_records(records)

        for tier in TIER_KEYS:
            self.assertEqual(0, result[tier]["event_count"])

    def test_strict_day_validation_and_payload_seal(self) -> None:
        bundle = _sealed_day()

        validated = validate_day_bundle(bundle)

        self.assertEqual(date(2026, 1, 1), validated.day)
        self.assertEqual(1440, len(validated.records))
        self.assertEqual(bundle["seal"]["payload_sha256"], validated.payload_sha256)

        tampered = copy.deepcopy(bundle)
        tampered["minutes"][0]["trade_close_price"] = "100.5"
        with self.assertRaisesRegex(ValueError, "seal.payload_sha256"):
            validate_day_bundle(tampered)

    def test_day_contract_rejects_semantic_drift_even_when_resealed(self) -> None:
        bundle = _sealed_day()
        cases: list[tuple[str, dict[str, object], str]] = []

        bad_total = copy.deepcopy(bundle)
        bad_total["minutes"][0]["total_quote_notional"] = "101"
        _reseal(bad_total)
        cases.append(("notional identity", bad_total, "total_quote_notional"))

        bad_midline_buckets = copy.deepcopy(bundle)
        bad_midline_buckets["minutes"][0]["midline_other_quote_notional"] = "1"
        _reseal(bad_midline_buckets)
        cases.append(
            (
                "midline notional identity",
                bad_midline_buckets,
                "midline notional buckets",
            )
        )

        unreferenced_trade = copy.deepcopy(bundle)
        unreferenced_trade["integrity"]["midline_unreferenced_trade_count"] = 1
        _reseal(unreferenced_trade)
        cases.append(
            (
                "unreferenced trade",
                unreferenced_trade,
                "zero unreferenced trades",
            )
        )

        backfill = copy.deepcopy(bundle)
        backfill["source"]["historical_backfill"] = True
        _reseal(backfill)
        cases.append(("historical backfill", backfill, "forward-only"))

        unknown_field = copy.deepcopy(bundle)
        unknown_field["minutes"][0]["unknown"] = "forbidden"
        _reseal(unknown_field)
        cases.append(("unknown field", unknown_field, "keys mismatch"))

        gap = copy.deepcopy(bundle)
        gap["minutes"][1]["minute"] = gap["minutes"][0]["minute"]
        _reseal(gap)
        cases.append(("minute gap", gap, "contiguous UTC minute"))

        for label, candidate, message in cases:
            with self.subTest(label=label):
                with self.assertRaisesRegex(ValueError, message):
                    validate_day_bundle(candidate)

    def test_file_analysis_requires_exactly_fourteen_days(self) -> None:
        with self.assertRaisesRegex(ValueError, "exactly 14 input day files"):
            analyze_files([])

    def test_output_is_create_only(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "result.json"
            write_new_json(output, {"status": "FIRST"})
            with self.assertRaisesRegex(ValueError, "refusing to overwrite"):
                write_new_json(output, {"status": "SECOND"})


if __name__ == "__main__":
    unittest.main()
