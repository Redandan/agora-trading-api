from __future__ import annotations

import copy
import hashlib
import json
import unittest

from jsonschema import Draft202012Validator, FormatChecker

from research_pipeline.forward_volatility_persistence import (
    CLOSE as VOLATILITY_CLOSE,
    RETAIN as VOLATILITY_RETAIN,
    _canonical_bytes as volatility_bytes,
    build_forward_volatility_snapshot,
)
from research_pipeline.post_shock_decision_route import (
    CLOSE_ROUTE,
    DIRECTIONAL_ROUTE,
    DOCUMENT_TYPE,
    SCHEMA_PATH,
    VOLATILITY_ROUTE,
    build_post_shock_decision_route,
    canonical_bytes,
    validate_post_shock_decision_route,
    validate_post_shock_decision_source_bindings,
)
from research_pipeline.post_shock_factor import (
    CONTINUATION,
    NO_FACTOR,
    REVERSAL,
    _build_post_shock_snapshot_v2,
    _canonical_bytes as directional_bytes,
    build_post_shock_snapshot,
)
from research_pipeline.tests.test_forward_volatility_persistence import (
    _base_episode,
    _breadth_episodes,
    _lineage,
)
from research_pipeline.tests import test_post_shock_factor as factor_fixture


class PostShockDecisionRouteTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(cls.schema)
        cls.validator = Draft202012Validator(
            cls.schema, format_checker=FormatChecker()
        )

    def test_schema_is_recursively_closed_and_rejects_contradictory_route(self) -> None:
        record, _, _ = self._record("0.01", "1.2")
        self.validator.validate(record)
        self.assertEqual(DOCUMENT_TYPE, record["document_type"])
        invalid = copy.deepcopy(record)
        invalid["unexpected"] = True
        self.assertTrue(list(self.validator.iter_errors(invalid)))
        invalid = copy.deepcopy(record)
        invalid["directional_diagnostic"]["unexpected"] = True
        self.assertTrue(list(self.validator.iter_errors(invalid)))
        contradictory = copy.deepcopy(record)
        contradictory["route_disposition"] = CLOSE_ROUTE
        self.assertTrue(list(self.validator.iter_errors(contradictory)))

    def test_frozen_direction_first_route_table(self) -> None:
        cases = [
            ("0.01", "1.2", CONTINUATION, VOLATILITY_RETAIN, DIRECTIONAL_ROUTE),
            ("-0.01", "0.8", REVERSAL, VOLATILITY_CLOSE, DIRECTIONAL_ROUTE),
            ("0", "1.2", NO_FACTOR, VOLATILITY_RETAIN, VOLATILITY_ROUTE),
            ("0", "0.8", NO_FACTOR, VOLATILITY_CLOSE, CLOSE_ROUTE),
        ]
        for directional_response, volatility_ratio, direction, volatility, route in cases:
            with self.subTest(route=route):
                record, _, _ = self._record(directional_response, volatility_ratio)
                self.assertEqual(direction, record["directional_diagnostic"]["disposition"])
                self.assertEqual(volatility, record["volatility_diagnostic"]["disposition"])
                self.assertEqual(route, record["route_disposition"])
                validate_post_shock_decision_route(record)
                self.validator.validate(record)

    def test_rollover_v2_sources_bind_the_same_leaf(self) -> None:
        lineage = _lineage(rolled_over=True)
        directional = self._directional("-0.01", lineage=lineage)
        volatility = self._volatility("1.2", lineage=lineage)
        record = self._build(directional, volatility)
        self.assertEqual(DIRECTIONAL_ROUTE, record["route_disposition"])
        self.assertEqual(lineage.leaf_trigger["trigger_id"], record["leaf_trigger_id"])
        self.assertEqual(
            "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_RESULT_V2",
            record["directional_diagnostic"]["document_type"],
        )

    def test_wait_cross_lineage_hash_and_noncanonical_sources_fail_closed(self) -> None:
        lineage = _lineage()
        directional = self._directional("0.01", lineage=lineage)
        volatility = self._volatility("1.2", lineage=lineage)
        waiting = build_post_shock_snapshot(
            [factor_fixture.PostShockFactorTest()._episode(0, "2026-08-08", "UP", "0.01")],
            sealed_at="2026-08-09T02:00:00Z",
        )
        with self.assertRaisesRegex(ValueError, "not terminal"):
            self._build(waiting, volatility)

        wrong_lineage = copy.deepcopy(volatility)
        wrong_lineage["leaf_trigger_id"] = "different-leaf"
        with self.assertRaises(ValueError):
            self._build(directional, wrong_lineage)

        directional_raw = directional_bytes(directional)
        volatility_raw = volatility_bytes(volatility)
        with self.assertRaisesRegex(ValueError, "directional artifact SHA-256"):
            build_post_shock_decision_route(
                directional_artifact_path="directional/result.json",
                directional_artifact_sha256="0" * 64,
                directional_artifact_bytes=directional_raw,
                volatility_artifact_path="volatility/result.json",
                volatility_artifact_sha256=hashlib.sha256(volatility_raw).hexdigest(),
                volatility_artifact_bytes=volatility_raw,
                sealed_at="2026-12-01T00:00:00Z",
            )
        with self.assertRaisesRegex(ValueError, "not canonical"):
            build_post_shock_decision_route(
                directional_artifact_path="directional/result.json",
                directional_artifact_sha256=hashlib.sha256(
                    directional_raw.rstrip()
                ).hexdigest(),
                directional_artifact_bytes=directional_raw.rstrip(),
                volatility_artifact_path="volatility/result.json",
                volatility_artifact_sha256=hashlib.sha256(volatility_raw).hexdigest(),
                volatility_artifact_bytes=volatility_raw,
                sealed_at="2026-12-01T00:00:00Z",
            )

    def test_source_binding_and_guardrail_drift_fail_closed(self) -> None:
        record, directional, volatility = self._record("0", "1.2")
        validate_post_shock_decision_source_bindings(
            record,
            directional_artifact_bytes=directional_bytes(directional),
            volatility_artifact_bytes=volatility_bytes(volatility),
        )
        changed = copy.deepcopy(record)
        changed["guardrails"]["candidate_created"] = True
        with self.assertRaisesRegex(ValueError, "guardrails"):
            validate_post_shock_decision_route(changed)
        changed = copy.deepcopy(record)
        changed["directional_diagnostic"]["latest_outcome_chain_head"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "source binding"):
            validate_post_shock_decision_source_bindings(
                changed,
                directional_artifact_bytes=directional_bytes(directional),
                volatility_artifact_bytes=volatility_bytes(volatility),
            )
        self.assertEqual(canonical_bytes(record), canonical_bytes(record))

    def test_standalone_route_rejects_lineage_and_date_drift(self) -> None:
        record, _, _ = self._record("0", "1.2")
        invalid_leaf = copy.deepcopy(record)
        invalid_leaf["leaf_trigger_id"] = "different-leaf"
        with self.assertRaisesRegex(ValueError, "V1 must bind the root leaf"):
            validate_post_shock_decision_route(invalid_leaf)
        self.assertTrue(list(self.validator.iter_errors(invalid_leaf)))

        invalid_date = copy.deepcopy(record)
        invalid_date["directional_diagnostic"]["latest_outcome_day"] = "2026-02-30"
        with self.assertRaisesRegex(ValueError, "latest outcome day is invalid"):
            validate_post_shock_decision_route(invalid_date)

        lineage = _lineage(rolled_over=True)
        rollover = self._build(
            self._directional("-0.01", lineage=lineage),
            self._volatility("1.2", lineage=lineage),
        )
        rollover["leaf_trigger_id"] = rollover["root_trigger_id"]
        rollover["leaf_trigger_fingerprint"] = rollover["root_trigger_fingerprint"]
        with self.assertRaisesRegex(ValueError, "V2 requires a rolled-over leaf"):
            validate_post_shock_decision_route(rollover)
        self.assertTrue(list(self.validator.iter_errors(rollover)))

    def test_source_binding_rejects_top_level_rollover_lineage_drift(self) -> None:
        lineage = _lineage(rolled_over=True)
        directional = self._directional("-0.01", lineage=lineage)
        volatility = self._volatility("1.2", lineage=lineage)
        record = self._build(directional, volatility)
        record["leaf_trigger_id"] = "different-valid-leaf"
        record["leaf_trigger_fingerprint"] = "d" * 64
        with self.assertRaisesRegex(ValueError, "source lineage binding drift"):
            validate_post_shock_decision_source_bindings(
                record,
                directional_artifact_bytes=directional_bytes(directional),
                volatility_artifact_bytes=volatility_bytes(volatility),
            )

    def _record(self, directional_response: str, volatility_ratio: str):
        lineage = _lineage()
        directional = self._directional(directional_response, lineage=lineage)
        volatility = self._volatility(volatility_ratio, lineage=lineage)
        return self._build(directional, volatility), directional, volatility

    def _build(self, directional, volatility):
        directional_raw = directional_bytes(directional)
        volatility_raw = volatility_bytes(volatility)
        return build_post_shock_decision_route(
            directional_artifact_path="post-shock-factor/terminal.json",
            directional_artifact_sha256=hashlib.sha256(directional_raw).hexdigest(),
            directional_artifact_bytes=directional_raw,
            volatility_artifact_path="forward-volatility-persistence/terminal.json",
            volatility_artifact_sha256=hashlib.sha256(volatility_raw).hexdigest(),
            volatility_artifact_bytes=volatility_raw,
            sealed_at="2026-12-01T00:00:00Z",
        )

    @staticmethod
    def _directional(response: str, *, lineage):
        fixture = factor_fixture.PostShockFactorTest()
        episodes = fixture._broad_episodes(response)
        if not lineage.rolled_over:
            return build_post_shock_snapshot(
                episodes, sealed_at="2026-11-01T00:00:00Z"
            )
        for episode in episodes:
            episode.update(
                {
                    "root_trigger_id": lineage.root_trigger["trigger_id"],
                    "root_trigger_fingerprint": lineage.root_trigger["fingerprint"],
                    "leaf_trigger_id": lineage.leaf_trigger["trigger_id"],
                    "leaf_trigger_fingerprint": lineage.leaf_trigger["fingerprint"],
                }
            )
        return _build_post_shock_snapshot_v2(
            episodes,
            lineage=lineage,
            sealed_at="2026-11-01T00:00:00Z",
        )

    @staticmethod
    def _volatility(ratio: str, *, lineage):
        episodes = _breadth_episodes(_base_episode(lineage), ratio=ratio)
        return build_forward_volatility_snapshot(
            episodes,
            lineage=lineage,
            activation_receipt_sha256="a" * 64,
            evaluator_schema_sha256="b" * 64,
            evaluator_module_sha256="c" * 64,
            sealed_at="2026-11-02T00:00:00Z",
        )


if __name__ == "__main__":
    unittest.main()
