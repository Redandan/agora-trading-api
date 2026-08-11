from __future__ import annotations

import hashlib
import json
import unittest
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

from research_pipeline import cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1 as evaluator


def _h(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def _ts(value: datetime) -> str:
    return value.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class CftcTffLevMoneyEvaluatorV1Test(unittest.TestCase):
    def setUp(self) -> None:
        evaluator.frozen_package.cache_clear()
        self.package = evaluator.frozen_package()

    def _row(self, report_date: date, long_value: str, short_value: str) -> dict[str, str]:
        row = {field: "1" for field in self.package["ordered_fields"]}
        row.update(
            {
                "Market_and_Exchange_Names": "BITCOIN - CHICAGO MERCANTILE EXCHANGE",
                "As_of_Date_In_Form_YYMMDD": report_date.strftime("%y%m%d"),
                "Report_Date_as_MM_DD_YYYY": report_date.isoformat(),
                "CFTC_Contract_Market_Code": "133741",
                "CFTC_Contract_Market_Code_Quotes": "133741",
                "FutOnly_or_Combined": "FutOnly",
                evaluator.LONG_FIELD: long_value,
                evaluator.SHORT_FIELD: short_value,
            }
        )
        return row

    def _observation(
        self,
        report_date: date,
        row: dict[str, str],
        predecessor: str,
        chain: str,
        suffix: str,
    ) -> dict[str, object]:
        received_at = datetime.combine(report_date + timedelta(days=3), datetime.min.time(), tzinfo=timezone.utc) + timedelta(hours=1)
        decision_at = received_at + timedelta(hours=1)
        return {
            "schema_version": "CFTC_CME_BITCOIN_TFF_OBSERVATION_V2",
            "document_type": "OFFLINE_CFTC_TFF_HEADERLESS_SOURCE_EVALUATION_V2",
            "authorization": evaluator.AUTHORIZATION,
            "source_label": evaluator.SOURCE_LABEL,
            "source_contract_sha256": evaluator.SOURCE_CONTRACT_SHA256,
            "source_contract_schema_sha256": evaluator.SOURCE_CONTRACT_SCHEMA_SHA256,
            "observation_schema_sha256": evaluator.OBSERVATION_SCHEMA_SHA256,
            "state": "NEW_REPORT_SEALED",
            "expected_report_date": report_date.isoformat(),
            "release_proof": {
                "release_schedule_version": "SYNTHETIC_V1",
                "release_schedule_sha256": _h("release"),
                "coverage_start": "2025-01-01",
                "coverage_end": "2027-12-31",
                "expected_tuesday": report_date.isoformat(),
                "release_at": f"{(report_date + timedelta(days=3)).isoformat()}T15:30:00-05:00",
                "release_timezone": "America/New_York",
            },
            "scheduled_cycle_at": _ts(received_at),
            "evaluated_at": _ts(decision_at),
            "state_evidence": {
                "report_date": report_date.isoformat(),
                "received_at": _ts(received_at),
                "field_count": 87,
                "raw_seal": {"raw_response_size_bytes": 1000, "raw_response_sha256": _h(f"raw-{suffix}")},
                "record_seal": {
                    "selected_record_size_bytes": 500,
                    "selected_record_sha256": _h(f"record-{suffix}"),
                    "selected_record_terminator": "LF",
                    "canonical_row_sha256": evaluator.sha256_bytes(evaluator.canonical_json_bytes(row)),
                },
                "row_identity": {
                    "market_and_exchange_names": "BITCOIN - CHICAGO MERCANTILE EXCHANGE",
                    "cftc_contract_market_code": "133741",
                    "cftc_market_code": "CME",
                    "cftc_commodity_code": "BTC",
                    "contract_units": "FROZEN_SYNTHETIC_UNIT",
                    "cftc_contract_market_code_quotes": "133741",
                    "cftc_market_code_quotes": "CME",
                    "cftc_commodity_code_quotes": "BTC",
                    "cftc_subgroup_code": "F",
                    "futonly_or_combined": "FutOnly",
                },
                "predecessor_sha256": predecessor,
                "decision_schedule": {
                    "schedule_id": "SYNTHETIC_SCHEDULE",
                    "schedule_version": "V1",
                    "schedule_sha256": _h("schedule"),
                    "decision_at": _ts(decision_at),
                },
                "chain_sha256": chain,
            },
        }

    def _anchor_and_outcome(
        self,
        observation: dict[str, object],
        suffix: str,
        terminal_close: str,
        anchor_offset_hours: int = 1,
        count: int = 168,
    ) -> tuple[dict[str, object], list[dict[str, str]]]:
        evidence = observation["state_evidence"]
        assert isinstance(evidence, dict)
        decision_at = datetime.strptime(evidence["decision_schedule"]["decision_at"], "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
        anchor_at = decision_at + timedelta(hours=anchor_offset_hours)
        anchor = {
            "symbol": "BTCUSDT",
            "interval": "1h",
            "anchor_at": _ts(anchor_at),
            "anchor_close": "100",
            "anchor_artifact_sha256": _h(f"anchor-{suffix}"),
            "first_complete_close_strictly_after_decision": True,
        }
        intervals = []
        for index in range(1, count + 1):
            close = terminal_close if index == count else "100"
            intervals.append(
                {
                    "closed_at": _ts(anchor_at + timedelta(hours=index)),
                    "close": close,
                    "artifact_sha256": _h(f"bar-{suffix}-{index}"),
                    "chain_sha256": _h(f"bar-chain-{suffix}-{index}"),
                }
            )
        return anchor, intervals

    def _transition(
        self,
        index: int,
        sign: int = 1,
        aligned: bool = True,
        anchor_shift_hours: int = 0,
    ) -> dict[str, object]:
        prior_date = date(2025, 12, 30) + timedelta(days=7 * index)
        current_date = prior_date + timedelta(days=7)
        prior_chain = _h(f"prior-chain-{index}")
        current_chain = _h(f"current-chain-{index}")
        prior_row = self._row(prior_date, "50", "50")
        current_long = "51" if sign > 0 else "49"
        current_row = self._row(current_date, current_long, "50")
        prior = self._observation(prior_date, prior_row, _h(f"before-{index}"), prior_chain, f"p-{index}")
        current = self._observation(current_date, current_row, prior_chain, current_chain, f"c-{index}")
        terminal = "101" if sign > 0 else "99"
        if not aligned:
            terminal = "99" if sign > 0 else "101"
        anchor, outcome = self._anchor_and_outcome(current, str(index), terminal, 1 + anchor_shift_hours)
        return evaluator.build_transition(prior, prior_row, current, current_row, anchor, outcome)

    def test_frozen_package_binds_contract_and_closed_schemas(self) -> None:
        self.assertEqual(87, len(self.package["ordered_fields"]))
        self.assertEqual(evaluator.ORDERED_FIELDS_SHA256, evaluator.sha256_bytes(evaluator.canonical_json_bytes(list(self.package["ordered_fields"]))))
        self.assertEqual([evaluator.LONG_FIELD, evaluator.SHORT_FIELD], self.package["contract"]["formula"]["fields"])
        self.assertRegex(self.package["contract_sha256"], r"^[0-9a-f]{64}$")
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            self.skipTest("jsonschema is not installed in this Local runtime")
        contract_schema = json.loads(evaluator._CONTRACT_SCHEMA_PATH.read_text(encoding="utf-8"))
        evaluation_schema = json.loads(evaluator._EVALUATION_SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(contract_schema)
        Draft202012Validator.check_schema(evaluation_schema)
        Draft202012Validator(contract_schema).validate(self.package["contract"])
        bad = dict(self.package["contract"])
        bad["unexpected"] = True
        self.assertTrue(list(Draft202012Validator(contract_schema).iter_errors(bad)))

    def test_exact_formula_positive_negative_and_zero_tie(self) -> None:
        positive = self._transition(0, 1)
        negative = self._transition(1, -1)
        self.assertEqual(("1", 1, "0.01"), (positive["factor_delta"], positive["factor_sign"], positive["signed_response_168h"]))
        self.assertEqual(("-1", -1, "0.01"), (negative["factor_delta"], negative["factor_sign"], negative["signed_response_168h"]))
        prior_date = date(2026, 1, 6)
        current_date = prior_date + timedelta(days=7)
        chain = _h("zero-prior")
        prior_row = self._row(prior_date, "50", "50")
        current_row = self._row(current_date, "50", "50")
        prior = self._observation(prior_date, prior_row, _h("genesis"), chain, "zero-p")
        current = self._observation(current_date, current_row, chain, _h("zero-current"), "zero-c")
        zero = evaluator.build_transition(prior, prior_row, current, current_row)
        self.assertEqual("NO_FACTOR_ACTION", zero["transition_state"])
        self.assertIsNone(zero["anchor_at"])
        with self.assertRaises(ValueError):
            evaluator.build_transition(prior, prior_row, current, current_row, {}, [])

    def test_decimal_grammar_rejects_invalid_and_accepts_outer_ascii_space(self) -> None:
        self.assertEqual(Decimal(".5"), evaluator.parse_factor_decimal(" .5 "))
        for invalid in ("", " ", "1,2", "1e2", ".", "+", "-", "--1", "NaN", "Infinity", "1_0", "\t1", "1\t", "101", "-1"):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                evaluator.parse_factor_decimal(invalid)

    def test_row_and_observation_hash_drift_fail_closed(self) -> None:
        prior_date = date(2026, 1, 6)
        current_date = prior_date + timedelta(days=7)
        prior_chain = _h("row-prior")
        prior_row = self._row(prior_date, "50", "50")
        current_row = self._row(current_date, "51", "50")
        prior = self._observation(prior_date, prior_row, _h("before-row"), prior_chain, "row-p")
        current = self._observation(current_date, current_row, prior_chain, _h("row-current"), "row-c")
        anchor, outcome = self._anchor_and_outcome(current, "row", "101")
        bad_row = dict(current_row)
        bad_row["Traders_Tot_All"] = "changed"
        with self.assertRaisesRegex(ValueError, "canonical row hash drift"):
            evaluator.build_transition(prior, prior_row, current, bad_row, anchor, outcome)
        bad_observation = deepcopy(current)
        bad_observation["source_contract_sha256"] = _h("wrong-contract")
        with self.assertRaisesRegex(ValueError, "schema hash binding drift"):
            evaluator.build_transition(prior, prior_row, bad_observation, current_row, anchor, outcome)
        malformed_release = deepcopy(current)
        malformed_release["release_proof"]["release_at"] = "not-a-timestamp-04:00"
        with self.assertRaisesRegex(ValueError, "offset timestamp"):
            evaluator.build_transition(prior, prior_row, malformed_release, current_row, anchor, outcome)
        boolean_size = deepcopy(current)
        boolean_size["state_evidence"]["raw_seal"]["raw_response_size_bytes"] = True
        with self.assertRaisesRegex(ValueError, "raw size"):
            evaluator.build_transition(prior, prior_row, boolean_size, current_row, anchor, outcome)
        empty_schedule = deepcopy(current)
        empty_schedule["state_evidence"]["decision_schedule"]["schedule_id"] = ""
        with self.assertRaisesRegex(ValueError, "identifier grammar"):
            evaluator.build_transition(prior, prior_row, empty_schedule, current_row, anchor, outcome)

    def test_date_predecessor_and_decision_time_fail_closed(self) -> None:
        transition = self._transition(0)
        self.assertEqual("EVALUABLE", transition["transition_state"])
        prior_date = date(2026, 1, 6)
        current_date = prior_date + timedelta(days=14)
        prior_chain = _h("date-prior")
        prior_row = self._row(prior_date, "50", "50")
        current_row = self._row(current_date, "51", "50")
        prior = self._observation(prior_date, prior_row, _h("date-before"), prior_chain, "date-p")
        current = self._observation(current_date, current_row, prior_chain, _h("date-current"), "date-c")
        anchor, outcome = self._anchor_and_outcome(current, "date", "101")
        with self.assertRaisesRegex(ValueError, "seven days"):
            evaluator.build_transition(prior, prior_row, current, current_row, anchor, outcome)
        current["state_evidence"]["predecessor_sha256"] = _h("wrong-predecessor")
        current["expected_report_date"] = (prior_date + timedelta(days=7)).isoformat()
        current["state_evidence"]["report_date"] = current["expected_report_date"]
        current_row = self._row(prior_date + timedelta(days=7), "51", "50")
        current["state_evidence"]["record_seal"]["canonical_row_sha256"] = evaluator.sha256_bytes(evaluator.canonical_json_bytes(current_row))
        current["release_proof"]["expected_tuesday"] = current["expected_report_date"]
        with self.assertRaisesRegex(ValueError, "predecessor"):
            evaluator.build_transition(prior, prior_row, current, current_row, anchor, outcome)
        bad = deepcopy(prior)
        bad["state_evidence"]["decision_schedule"]["decision_at"] = bad["state_evidence"]["received_at"]
        with self.assertRaisesRegex(ValueError, "strictly after"):
            evaluator.build_transition(bad, prior_row, current, current_row, anchor, outcome)

    def test_anchor_and_outcome_length_gap_and_positive_close_fail_closed(self) -> None:
        prior_date = date(2026, 1, 6)
        current_date = prior_date + timedelta(days=7)
        prior_chain = _h("outcome-prior")
        prior_row = self._row(prior_date, "50", "50")
        current_row = self._row(current_date, "51", "50")
        prior = self._observation(prior_date, prior_row, _h("outcome-before"), prior_chain, "outcome-p")
        current = self._observation(current_date, current_row, prior_chain, _h("outcome-current"), "outcome-c")
        anchor, outcome = self._anchor_and_outcome(current, "outcome", "101")
        bad_anchor = dict(anchor)
        bad_anchor["anchor_at"] = current["state_evidence"]["decision_schedule"]["decision_at"]
        with self.assertRaisesRegex(ValueError, "strictly after"):
            evaluator.build_transition(prior, prior_row, current, current_row, bad_anchor, outcome)
        with self.assertRaisesRegex(ValueError, "168"):
            evaluator.build_transition(prior, prior_row, current, current_row, anchor, outcome[:-1])
        gap = deepcopy(outcome)
        gap[80]["closed_at"] = gap[81]["closed_at"]
        with self.assertRaisesRegex(ValueError, "contiguous"):
            evaluator.build_transition(prior, prior_row, current, current_row, anchor, gap)
        nonpositive = deepcopy(outcome)
        nonpositive[1]["close"] = "0"
        with self.assertRaisesRegex(ValueError, "positive"):
            evaluator.build_transition(prior, prior_row, current, current_row, anchor, nonpositive)

    def test_overlap_exclusion_and_duplicate_identity(self) -> None:
        first = self._transition(0, anchor_shift_hours=1)
        later = self._transition(1)
        result = evaluator.evaluate_transitions([first, later])
        self.assertEqual(1, result["sample_statistics"]["nonoverlap_count"])
        self.assertEqual(1, result["sample_statistics"]["overlap_excluded_count"])
        self.assertEqual("OVERLAPPING_WINDOW_EXCLUDED", result["transitions"][1]["review_status"])
        with self.assertRaisesRegex(ValueError, "duplicate"):
            evaluator.evaluate_transitions([first, first])

    def test_wait_disposition_exposes_incomplete_breadth_gates(self) -> None:
        result = evaluator.evaluate_transitions([self._transition(index, 1 if index % 2 == 0 else -1) for index in range(8)])
        self.assertEqual("WAIT_FOR_MORE_UNTOUCHED_EVIDENCE", result["disposition"])
        self.assertFalse(result["gates"]["minimum_episodes"])
        self.assertFalse(result["gates"]["quartile_breadth"])
        self.assertFalse(result["gates"]["month_breadth"])
        self.assertFalse(result["gates"]["month_concentration"])

    def test_exact_one_sided_sign_test_excludes_zero(self) -> None:
        self.assertEqual(Decimal("0.3125"), evaluator._sign_test(3, 1))
        self.assertIsNone(evaluator._sign_test(0, 0))

    def test_positive_result_passes_every_frozen_gate_and_path_statistics(self) -> None:
        transitions = [self._transition(index, 1 if index % 2 == 0 else -1) for index in range(26)]
        result = evaluator.evaluate_transitions(transitions)
        self.assertEqual("CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_POSITIVE_FOR_MANAGER_REVIEW", result["disposition"])
        self.assertTrue(all(result["gates"].values()))
        statistics = result["sample_statistics"]
        self.assertEqual((26, 13, 13), (statistics["nonoverlap_count"], statistics["positive_factor_count"], statistics["negative_factor_count"]))
        self.assertEqual([7, 6, 7, 6], statistics["quartile_counts"])
        self.assertEqual("0.01", statistics["median_signed_response"])
        self.assertEqual("0", statistics["median_absolute_sign_adjusted_mae"])
        self.assertEqual("0", statistics["p90_absolute_sign_adjusted_mae"])
        try:
            from jsonschema import Draft202012Validator
        except ImportError:
            return
        schema = json.loads(evaluator._EVALUATION_SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER).validate(result)

    def test_predictive_failure_routes_close_without_inversion(self) -> None:
        transitions = [self._transition(index, 1 if index % 2 == 0 else -1, aligned=False) for index in range(26)]
        result = evaluator.evaluate_transitions(transitions)
        self.assertTrue(result["gates"]["breadth_complete"])
        self.assertFalse(result["gates"]["predictive_complete"])
        self.assertEqual("CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_ROUTE_CLOSE", result["disposition"])

    def test_concentration_failure_routes_close(self) -> None:
        transitions = [self._transition(index, 1 if index % 2 == 0 else -1) for index in range(26)]
        dominant = deepcopy(transitions[0])
        dominant["raw_return_168h"] = "10"
        dominant["signed_response_168h"] = "10"
        dominant = evaluator._seal_transition(dominant)
        transitions[0] = dominant
        result = evaluator.evaluate_transitions(transitions)
        self.assertTrue(result["gates"]["breadth_complete"])
        self.assertFalse(result["gates"]["concentration_complete"])
        self.assertEqual("CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_ROUTE_CLOSE", result["disposition"])

    def test_episode_and_package_hash_tamper_fail_closed(self) -> None:
        transition = self._transition(0)
        transition["factor_delta"] = "2"
        with self.assertRaisesRegex(ValueError, "episode SHA"):
            evaluator.evaluate_transitions([transition])
        valid = evaluator.evaluate_transitions([self._transition(0)])
        valid["factor_contract_sha256"] = _h("wrong-factor-contract")
        with self.assertRaisesRegex(ValueError, "package hash"):
            evaluator.validate_evaluation_document(valid)
        valid = evaluator.evaluate_transitions([self._transition(0)])
        valid["sample_statistics"]["nonoverlap_count"] = 999
        with self.assertRaisesRegex(ValueError, "deterministic recomputation"):
            evaluator.validate_evaluation_document(valid)
        valid = evaluator.evaluate_transitions([self._transition(0)])
        valid["gates"]["minimum_episodes"] = True
        with self.assertRaisesRegex(ValueError, "deterministic recomputation"):
            evaluator.validate_evaluation_document(valid)
        valid = evaluator.evaluate_transitions([self._transition(0)])
        valid["schema_version"] = "2"
        with self.assertRaisesRegex(ValueError, "identity drift"):
            evaluator.validate_evaluation_document(valid)


if __name__ == "__main__":
    unittest.main()
