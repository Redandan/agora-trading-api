from __future__ import annotations

from dataclasses import replace
from datetime import datetime, timedelta, timezone
from decimal import Decimal, localcontext
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from research_pipeline.microstructure_diagnostic import MinuteRecord, TIER_KEYS
from research_pipeline.microstructure_discovery_economic_veto import (
    CONTRACT_SHA256,
    EconomicVetoError,
    TOTAL_MINUTES,
    _Trade,
    _candidate_signals,
    _friction_trade,
    _match_controls,
    _max_drawdown,
    _validate_frozen_file,
    evaluate_economic_veto,
    evaluate_records,
)
from research_pipeline.microstructure_handoff import HandoffContext
from research_pipeline.tests.test_microstructure_discovery_economic_veto_contract import (
    SchemaValidationError,
    _validate_schema_instance,
)
from research_pipeline.microstructure_discovery_economic_veto_runner import (
    RESULT_NAME,
    EconomicVetoRunnerBlocked,
    RuntimePaths,
    _create_once,
    _expected_handoff_names,
    _validate_runner_task,
)


START = datetime(2026, 1, 1, tzinfo=timezone.utc)


def _record(index: int) -> MinuteRecord:
    return MinuteRecord(
        minute=START + timedelta(minutes=index),
        total_quote_notional=Decimal("2"),
        net_taker_quote_notional=Decimal("0"),
        above_mid_buy_quote_notional=Decimal("1"),
        below_mid_sell_quote_notional=Decimal("1"),
        average_book_imbalance=Decimal("0"),
        bid_replenishment_quote_proxy=Decimal("0"),
        trade_open_price=Decimal("100"),
        trade_high_price=Decimal("100"),
        trade_low_price=Decimal("100"),
        trade_close_price=Decimal("100"),
    )


def _signal(record: MinuteRecord) -> MinuteRecord:
    return replace(
        record,
        above_mid_buy_quote_notional=Decimal("3"),
        below_mid_sell_quote_notional=Decimal("1"),
        net_taker_quote_notional=Decimal("1"),
        average_book_imbalance=Decimal("0.1"),
        bid_replenishment_quote_proxy=Decimal("1"),
    )


def _price(record: MinuteRecord, value: str) -> MinuteRecord:
    price = Decimal(value)
    return replace(
        record,
        trade_open_price=price,
        trade_high_price=price,
        trade_low_price=price,
        trade_close_price=price,
    )


def _positive_records(*, spacing: int = 70, exit_price: str = "110") -> tuple[list[MinuteRecord], list[int]]:
    records = [_record(index) for index in range(TOTAL_MINUTES)]
    indices: list[int] = []
    for day in (1, 8):
        for offset in range(15):
            index = day * 1440 + 100 + offset * spacing
            indices.append(index)
            records[index] = _signal(records[index])
            records[index + 62] = _price(records[index + 62], exit_price)
    return records, indices


class PureEconomicVetoTests(unittest.TestCase):
    def test_all_three_tiers_use_the_same_selected_signal_set(self) -> None:
        records, _ = _positive_records()
        counts = []
        for tier in TIER_KEYS:
            result = evaluate_records(records, tier)
            counts.append(result["integrity_metrics"]["selected_tier_trade_count"])
            self.assertTrue(result["all_required_gates_passed"])
        self.assertEqual(counts, [30, 30, 30])

    def test_positive_fixture_permits_later_v4(self) -> None:
        records, _ = _positive_records()
        result = evaluate_records(records, TIER_KEYS[2])
        self.assertTrue(result["integrity_gates"]["all_required_integrity_gates_passed"])
        self.assertTrue(result["economic_gates"]["all_required_economic_gates_passed"])
        self.assertEqual(result["integrity_metrics"]["matched_control_coverage_pct"], "100")
        self.assertEqual(result["integrity_metrics"]["candidate_terminal_inventory"], "0")
        self.assertEqual(result["integrity_metrics"]["first_seven_day_trade_count"], 15)
        self.assertEqual(result["integrity_metrics"]["second_seven_day_trade_count"], 15)

    def test_negative_fixture_vetoes_before_v4(self) -> None:
        records, _ = _positive_records(exit_price="95")
        result = evaluate_records(records, TIER_KEYS[0])
        self.assertFalse(result["economic_gates"]["positive_candidate_net_total_pnl"])
        self.assertFalse(result["all_required_gates_passed"])

    def test_concentration_gate_fails_without_tuning(self) -> None:
        records, indices = _positive_records(exit_price="101")
        records[indices[0] + 62] = _price(records[indices[0] + 62], "200")
        result = evaluate_records(records, TIER_KEYS[0])
        self.assertFalse(result["economic_gates"]["top_one_contribution_at_most_40_pct"])
        self.assertFalse(result["all_required_gates_passed"])

    def test_m_plus_one_is_not_used_for_fill_or_exit(self) -> None:
        records, indices = _positive_records()
        baseline = evaluate_records(records, TIER_KEYS[0])["economic_metrics"]
        for index in indices:
            records[index + 1] = _price(records[index + 1], "999999")
        changed = evaluate_records(records, TIER_KEYS[0])["economic_metrics"]
        self.assertEqual(baseline, changed)

    def test_m_plus_2_entry_and_m_plus_62_exit_apply_exact_friction(self) -> None:
        records = [_record(index) for index in range(TOTAL_MINUTES)]
        records[12] = _price(records[12], "100")
        records[72] = _price(records[72], "110")
        trade = _friction_trade(records, 10, 0)
        with localcontext() as context:
            context.prec = 50
            gross_base = Decimal("30") / (Decimal("100") * Decimal("1.0005"))
            net_base = gross_base * Decimal("0.9990")
            net_quote = net_base * Decimal("110") * Decimal("0.9995") * Decimal("0.9990")
            expected = net_quote - Decimal("30")
        self.assertEqual(trade.pnl_usdt, expected)
        self.assertEqual(trade.entry_index, 12)
        self.assertEqual(trade.exit_index, 72)

    def test_flat_raw_price_applies_nearly_thirty_bps_friction(self) -> None:
        records = [_record(index) for index in range(TOTAL_MINUTES)]
        trade = _friction_trade(records, 10, 0)
        self.assertLess(trade.return_bps, Decimal("-29.9"))

    def test_drawdown_is_peak_to_trough_on_exit_order(self) -> None:
        trades = [
            _Trade(0, 2, 62, 0, Decimal("2"), Decimal("0")),
            _Trade(70, 72, 132, 0, Decimal("-5"), Decimal("0")),
            _Trade(140, 142, 202, 0, Decimal("1"), Decimal("0")),
        ]
        self.assertEqual(_max_drawdown(trades), Decimal("5"))

    def test_exit_before_entry_allows_exact_sixty_minute_boundary(self) -> None:
        records = [_record(index) for index in range(TOTAL_MINUTES)]
        first = 1440 + 100
        second = first + 60
        records[first] = _signal(records[first])
        records[second] = _signal(records[second])
        signals, excluded = _candidate_signals(records, TIER_KEYS[2])
        self.assertEqual(excluded, 0)
        self.assertEqual([value.index for value in signals], [first, second])
        pairs = _match_controls(records, signals)
        self.assertEqual(len(pairs), 2)
        first_control_exit = pairs[0][1].index + 62
        second_control_entry = pairs[1][1].index + 2
        self.assertEqual(first_control_exit, second_control_entry)

    def test_signal_without_full_exit_is_excluded_and_reported(self) -> None:
        records, _ = _positive_records()
        records[-10] = _signal(records[-10])
        result = evaluate_records(records, TIER_KEYS[0])
        self.assertEqual(result["integrity_metrics"]["excluded_without_full_exit_count"], 1)
        self.assertEqual(result["integrity_metrics"]["selected_tier_trade_count"], 30)

    def test_cooldown_rejects_second_signal_inside_sixty_minutes(self) -> None:
        records = [_record(index) for index in range(TOTAL_MINUTES)]
        first = 1440 + 100
        records[first] = _signal(records[first])
        records[first + 59] = _signal(records[first + 59])
        signals, _ = _candidate_signals(records, TIER_KEYS[0])
        self.assertEqual([value.index for value in signals], [first])

    def test_controls_are_unique_prior_same_fold_and_same_minute(self) -> None:
        records = [_record(index) for index in range(TOTAL_MINUTES)]
        minute = 100
        for day in (2, 3):
            records[day * 1440 + minute] = _signal(records[day * 1440 + minute])
        signals, _ = _candidate_signals(records, TIER_KEYS[0])
        pairs = _match_controls(records, signals)
        controls = [control.index for _, control in pairs]
        self.assertEqual(len(controls), len(set(controls)))
        self.assertTrue(all(control // 1440 < candidate.index // 1440 for (candidate, _), control in zip(pairs, controls)))
        self.assertTrue(all(control % 1440 == minute for control in controls))
        self.assertTrue(all(control // (7 * 1440) == candidate.fold for candidate, control_signal in pairs for control in [control_signal.index]))

    def test_control_attrition_is_reported_as_coverage_not_filled_by_fallback(self) -> None:
        records, indices = _positive_records()
        for index in indices[:10]:
            control = index - 1440
            records[control] = replace(
                records[control],
                above_mid_buy_quote_notional=Decimal("0"),
                below_mid_sell_quote_notional=Decimal("0"),
            )
        result = evaluate_records(records, TIER_KEYS[0])
        self.assertLess(Decimal(result["integrity_metrics"]["matched_control_coverage_pct"]), Decimal("80"))
        self.assertFalse(result["integrity_gates"]["minimum_80_pct_matched_control_coverage"])

    def test_noncontiguous_minutes_fail_closed(self) -> None:
        records, _ = _positive_records()
        records[100] = replace(records[100], minute=records[100].minute + timedelta(minutes=1))
        with self.assertRaises(EconomicVetoError):
            evaluate_records(records, TIER_KEYS[0])

    def test_unknown_tier_fails_closed(self) -> None:
        records, _ = _positive_records()
        with self.assertRaises(EconomicVetoError):
            evaluate_records(records, "CALLER_SELECTED_TIER")

    def test_source_hash_drift_fails_closed(self) -> None:
        with self.assertRaises(EconomicVetoError):
            _validate_frozen_file(b"{}", CONTRACT_SHA256, "contract")

    def test_non_positive_interpretation_fails_before_day_access(self) -> None:
        context = HandoffContext(
            task_id="task",
            task_sha256="0" * 64,
            manifest_sha256="1" * 64,
            manifest_payload_sha256="2" * 64,
            state_relative_name="canonical/state.json",
            state_sha256="3" * 64,
            diagnostic_id="diagnostic",
            chain_head_sha256="4" * 64,
            days=(),
        )
        handoff = {
            "seal": {"payload_sha256": "5" * 64},
            "input_manifest": {"sha256": "1" * 64, "payload_sha256": "2" * 64},
        }
        interpretation = {
            "disposition": "INSUFFICIENT_EVIDENCE",
            "source_handoff_result": {
                "document_sha256": "6" * 64,
                "payload_sha256": "5" * 64,
            },
        }
        with (
            patch(
                "research_pipeline.microstructure_discovery_economic_veto._validate_contract_sources"
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto.validate_handoff_result_bytes",
                return_value=handoff,
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto.validate_interpretation_result_bytes",
                return_value=interpretation,
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto._sha256",
                return_value="6" * 64,
            ),
        ):
            with self.assertRaises(EconomicVetoError):
                evaluate_economic_veto(
                    handoff_context=context,
                    handoff_result_raw=b"handoff",
                    interpretation_result_raw=b"interpretation",
                    days=(),
                    contract_raw=b"contract",
                    result_schema_raw=b"schema",
                    route_contract_raw=b"route",
                )


class CreateOncePublicationTests(unittest.TestCase):
    def test_identical_result_is_idempotent(self) -> None:
        raw = b'{"fixed":true}'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "output"
            with patch(
                "research_pipeline.microstructure_discovery_economic_veto_runner.validate_economic_veto_result_bytes",
                return_value={"fixed": True},
            ):
                self.assertEqual(_create_once(root, raw), raw)
                self.assertEqual(_create_once(root, raw), raw)
            self.assertEqual((root / RESULT_NAME).read_bytes(), raw)

    def test_conflicting_result_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "output"
            root.mkdir()
            (root / RESULT_NAME).write_bytes(b'{"fixed":false}')
            with patch(
                "research_pipeline.microstructure_discovery_economic_veto_runner.validate_economic_veto_result_bytes",
                return_value={"fixed": False},
            ):
                with self.assertRaises(EconomicVetoRunnerBlocked):
                    _create_once(root, b'{"fixed":true}')

    def test_extra_output_entry_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "output"
            root.mkdir()
            (root / RESULT_NAME).write_bytes(b"{}")
            (root / "extra.json").write_bytes(b"{}")
            with self.assertRaises(EconomicVetoRunnerBlocked):
                _create_once(root, b"{}")

    def test_link_or_reparse_output_is_rejected_before_read(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary) / "output"
            root.mkdir()
            (root / RESULT_NAME).write_bytes(b"{}")
            with patch(
                "research_pipeline.microstructure_discovery_economic_veto_runner._require_regular",
                side_effect=EconomicVetoRunnerBlocked("link rejected"),
            ):
                with self.assertRaises(EconomicVetoRunnerBlocked):
                    _create_once(root, b"{}")

    def test_runner_task_hash_drift_blocks_before_source_access(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            task = root / "research_pipeline/examples/local-research-task.microstructure-discovery-economic-veto-runner.v1.json"
            task.parent.mkdir(parents=True)
            task.write_text("{}", encoding="utf-8")
            paths = RuntimePaths(
                repository_root=root,
                handoff_root=root / "never-read-handoff",
                interpretation_path=root / "never-read-interpretation.json",
                output_root=root / "never-written-output",
            )
            with self.assertRaises(EconomicVetoRunnerBlocked):
                _validate_runner_task(paths)

    def test_manifest_inventory_requires_exact_thirty_one_names(self) -> None:
        days = []
        for number in range(14):
            day = f"2026-01-{number + 1:02d}"
            days.append(
                {
                    "bundle_relative_name": f"days/{day}.json",
                    "envelope_relative_name": f"envelopes/{day}.json",
                }
            )
        names = _expected_handoff_names(
            {"canonical_state": {"relative_name": "canonical/state.json"}, "days": days}
        )
        self.assertEqual(len(names), 31)


class FrozenSchemaTests(unittest.TestCase):
    def test_result_schema_is_closed_draft_2020_12(self) -> None:
        root = Path(__file__).resolve().parents[2]
        schema = json.loads(
            (root / "research_pipeline/microstructure-discovery-economic-veto-result.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(schema["$schema"], "https://json-schema.org/draft/2020-12/schema")
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual(schema["properties"]["disposition"]["enum"], ["VETO_BEFORE_V4", "PERMIT_LATER_V4"])

    def test_full_evaluator_emits_closed_schema_valid_sealed_permit(self) -> None:
        records, _ = _positive_records()
        handoff_raw = b"synthetic handoff"
        handoff_document_hash = hashlib.sha256(handoff_raw).hexdigest()
        handoff_payload_hash = "5" * 64
        context = HandoffContext(
            task_id="task",
            task_sha256="0" * 64,
            manifest_sha256="1" * 64,
            manifest_payload_sha256="2" * 64,
            state_relative_name="canonical/state.json",
            state_sha256="3" * 64,
            diagnostic_id="diagnostic",
            chain_head_sha256="4" * 64,
            days=(),
        )
        handoff = {
            "seal": {"payload_sha256": handoff_payload_hash},
            "input_manifest": {"sha256": "1" * 64, "payload_sha256": "2" * 64},
        }
        interpretation = {
            "disposition": "READY_FOR_ONE_HYPOTHESIS_DESIGN",
            "source_handoff_result": {
                "document_sha256": handoff_document_hash,
                "payload_sha256": handoff_payload_hash,
            },
            "screen": {"selected_tier": TIER_KEYS[2]},
            "seal": {"payload_sha256": "6" * 64},
        }
        inventory = [
            {
                "day": f"2026-01-{index + 1:02d}",
                "integrity_status": "CLEAN",
                "valid_minute_count": 1440,
                "bundle_document_sha256": "7" * 64,
                "bundle_payload_sha256": "8" * 64,
                "envelope_document_sha256": "9" * 64,
                "envelope_payload_sha256": "a" * 64,
                "chain_sha256": "b" * 64,
                "anomaly_count": 0,
            }
            for index in range(14)
        ]
        with (
            patch(
                "research_pipeline.microstructure_discovery_economic_veto._validate_contract_sources"
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto.validate_handoff_result_bytes",
                return_value=handoff,
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto.validate_interpretation_result_bytes",
                return_value=interpretation,
            ),
            patch(
                "research_pipeline.microstructure_discovery_economic_veto._validate_day_evidence",
                return_value=(records, inventory),
            ),
        ):
            raw = evaluate_economic_veto(
                handoff_context=context,
                handoff_result_raw=handoff_raw,
                interpretation_result_raw=b"synthetic interpretation",
                days=(),
                contract_raw=b"contract",
                result_schema_raw=b"schema",
                route_contract_raw=b"route",
            )
        value = json.loads(raw)
        root = Path(__file__).resolve().parents[2]
        schema = json.loads(
            (root / "research_pipeline/microstructure-discovery-economic-veto-result.v1.schema.json").read_text(
                encoding="utf-8"
            )
        )
        _validate_schema_instance(value, schema, schema)
        self.assertEqual(value["disposition"], "PERMIT_LATER_V4")
        payload = {key: item for key, item in value.items() if key != "seal"}
        payload_raw = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
        self.assertEqual(value["seal"]["payload_sha256"], hashlib.sha256(payload_raw).hexdigest())
        value["unexpected"] = True
        with self.assertRaises(SchemaValidationError):
            _validate_schema_instance(value, schema, schema)


if __name__ == "__main__":
    unittest.main()
