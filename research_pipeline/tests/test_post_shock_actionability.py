from __future__ import annotations

import copy
import hashlib
import json
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

try:
    from jsonschema import Draft202012Validator, FormatChecker
except ModuleNotFoundError:  # Manager runs this gate in the provisioned environment.
    Draft202012Validator = None  # type: ignore[assignment,misc]
    FormatChecker = None  # type: ignore[assignment,misc]

from research_pipeline.post_shock_actionability import (
    actionability_sha256,
    build_actionability_record,
    canonical_bytes,
    first_complete_utc_hour_after,
    sha256_bytes,
    validate_actionability_record,
    validate_actionability_source_bindings,
)


HEX_A = "a" * 64
HEX_B = "b" * 64
HEX_C = "c" * 64


class PostShockActionabilityTest(unittest.TestCase):
    def setUp(self) -> None:
        self.schema_path = Path(__file__).parents[1] / (
            "btc-utc-day-3pct-post-shock-actionability.v1.schema.json"
        )
        self.schema = json.loads(self.schema_path.read_text(encoding="utf-8"))
        self.result_bytes = self._terminal_result_bytes()
        self.diagnostic_bytes = self._diagnostic_bytes()
        self.fill_bytes = self._fill_bytes()
        self.record = self._build()

    @unittest.skipIf(Draft202012Validator is None, "jsonschema is unavailable")
    def test_schema_meta_validation_and_recursive_closure(self) -> None:
        assert Draft202012Validator is not None
        assert FormatChecker is not None
        Draft202012Validator.check_schema(self.schema)
        validator = Draft202012Validator(self.schema, format_checker=FormatChecker())
        self.assertEqual([], list(validator.iter_errors(self.record)))
        for path in (
            ("manager_review",),
            ("evidence_contract",),
            ("shock_diagnostic",),
            ("decision",),
            ("fill_observation",),
        ):
            mutated = copy.deepcopy(self.record)
            mutated[path[0]]["unexpected"] = True
            self.assertTrue(list(validator.iter_errors(mutated)))

    def test_exact_max_clock_and_strict_next_complete_hour(self) -> None:
        self.assertEqual(
            "2026-08-11T01:05:00Z",
            self.record["decision"]["decision_available_at"],
        )
        self.assertEqual(
            "2026-08-11T02:00:00Z",
            self.record["fill_observation"]["interval_start"],
        )
        self.assertEqual(
            datetime(2026, 8, 11, 2, tzinfo=timezone.utc),
            first_complete_utc_hour_after("2026-08-11T01:00:00Z"),
        )

    def test_terminal_result_hash_wait_and_shape_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "byte hash mismatch"):
            self._build(terminal_result_sha256=HEX_A)
        wait = json.loads(self.result_bytes)
        wait["disposition"] = "WAIT_FOR_MORE_UNTOUCHED_EVIDENCE"
        wait["terminal"] = False
        with self.assertRaisesRegex(ValueError, "WAIT or nonterminal"):
            self._build(terminal_result_bytes=self._canonical(wait))
        malformed = json.loads(self.result_bytes)
        malformed["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "fields are not exact"):
            self._build(terminal_result_bytes=self._canonical(malformed))

    def test_semantic_review_hash_and_clock_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "Manager review terminal-result hash"):
            self._build(manager_terminal_result_sha256=HEX_A)
        with self.assertRaisesRegex(ValueError, "predates terminal result"):
            self._build(manager_reviewed_at="2026-08-08T01:59:59Z")

    def test_activation_future_start_and_discovery_overlap_fail_closed(self) -> None:
        with self.assertRaisesRegex(ValueError, "predates Manager review"):
            self._build(contract_activated_at="2026-08-08T02:59:59Z")
        with self.assertRaisesRegex(ValueError, "after activation"):
            self._build(untouched_future_evidence_start="2026-08-08T04:00:00Z")
        with self.assertRaisesRegex(ValueError, "overlaps the terminal discovery day"):
            self._build(
                contract_activated_at="2026-08-07T23:00:00Z",
                manager_reviewed_at="2026-08-07T23:00:00Z",
                untouched_future_evidence_start="2026-08-07T23:30:00Z",
                terminal_result_bytes=self._terminal_result_bytes(
                    sealed_at="2026-08-07T22:00:00Z"
                ),
            )

    def test_diagnostic_receipt_seal_and_future_t0_fail_closed(self) -> None:
        diagnostic = json.loads(self.diagnostic_bytes)
        diagnostic["sealed_at"] = "2026-08-11T00:59:59Z"
        with self.assertRaisesRegex(ValueError, "predates target evidence receipt"):
            self._build(shock_diagnostic_bytes=self._canonical(diagnostic))
        with self.assertRaisesRegex(ValueError, "predates untouched future evidence"):
            self._build(untouched_future_evidence_start="2026-08-12T00:00:00Z")

    def test_decision_fill_and_record_clock_drift_fail_closed(self) -> None:
        mutated = copy.deepcopy(self.record)
        mutated["decision"]["decision_available_at"] = "2026-08-11T01:04:59Z"
        with self.assertRaisesRegex(ValueError, "maximum artifact clock"):
            validate_actionability_record(mutated)
        mutated = copy.deepcopy(self.record)
        mutated["fill_observation"]["interval_start"] = mutated["decision"][
            "decision_available_at"
        ]
        mutated["fill_observation"]["observed_at"] = mutated["decision"][
            "decision_available_at"
        ]
        with self.assertRaisesRegex(ValueError, "first complete UTC-hour"):
            validate_actionability_record(mutated)
        mutated = copy.deepcopy(self.record)
        mutated["sealed_at"] = "2026-08-11T02:00:59Z"
        with self.assertRaisesRegex(ValueError, "predates fill receipt"):
            validate_actionability_record(mutated)

    def test_price_source_and_artifact_hash_drift_fail_closed(self) -> None:
        changed = json.loads(self.fill_bytes)
        changed["open"] = "101"
        changed_bytes = self._canonical(changed)
        with self.assertRaisesRegex(ValueError, "source bytes drifted"):
            validate_actionability_source_bindings(
                self.record,
                terminal_result_bytes=self.result_bytes,
                shock_diagnostic_bytes=self.diagnostic_bytes,
                fill_observation_bytes=changed_bytes,
            )
        with self.assertRaisesRegex(ValueError, "byte hash mismatch"):
            self._build(
                fill_observation_bytes=changed_bytes,
                fill_observation_sha256=sha256_bytes(self.fill_bytes),
            )
        changed["open"] = "0"
        with self.assertRaisesRegex(ValueError, "positive and finite"):
            self._build(
                fill_observation_bytes=self._canonical(changed),
                fill_observation_sha256=None,
            )

    def test_unknown_strategy_and_economic_fields_are_rejected(self) -> None:
        for key, value in (
            ("parent", "DRA"),
            ("direction", "REVERSAL"),
            ("side", "BUY"),
            ("fee", "0.001"),
            ("holding_horizon", "H24"),
        ):
            mutated = copy.deepcopy(self.record)
            mutated[key] = value
            with self.assertRaisesRegex(ValueError, "fields are not exact"):
                validate_actionability_record(mutated)

    def test_paths_are_contained_relative_references(self) -> None:
        for path in ("../escape.json", "/absolute.json", "C:/absolute.json", "a\\b.json"):
            with self.assertRaisesRegex(ValueError, "contained relative path"):
                self._build(terminal_result_path=path)

    def test_canonical_determinism_and_zero_side_effects(self) -> None:
        first = canonical_bytes(self.record)
        second = canonical_bytes(copy.deepcopy(self.record))
        self.assertEqual(first, second)
        self.assertEqual(hashlib.sha256(first).hexdigest(), actionability_sha256(self.record))
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            before = list(root.iterdir())
            self._build()
            self.assertEqual(before, list(root.iterdir()))

    def _build(self, **overrides: object) -> dict[str, object]:
        values: dict[str, object] = {
            "terminal_result_path": "post-shock-factor/terminal.json",
            "terminal_result_sha256": sha256_bytes(self.result_bytes),
            "terminal_result_bytes": self.result_bytes,
            "manager_review_id": "manager-review-2026q4-r1",
            "manager_reviewed_at": "2026-08-08T03:00:00Z",
            "manager_terminal_result_sha256": sha256_bytes(self.result_bytes),
            "contract_activated_at": "2026-08-08T04:00:00Z",
            "untouched_future_evidence_start": "2026-08-09T00:00:00Z",
            "shock_diagnostic_path": "shock-diagnostics/2026-08-10.json",
            "shock_diagnostic_sha256": sha256_bytes(self.diagnostic_bytes),
            "shock_diagnostic_bytes": self.diagnostic_bytes,
            "fill_observation_path": "point-in-time/hour-open-2026-08-11T02.json",
            "fill_observation_sha256": sha256_bytes(self.fill_bytes),
            "fill_observation_bytes": self.fill_bytes,
            "sealed_at": "2026-08-11T02:02:00Z",
        }
        values.update(overrides)
        for content_key, hash_key in (
            ("terminal_result_bytes", "terminal_result_sha256"),
            ("shock_diagnostic_bytes", "shock_diagnostic_sha256"),
            ("fill_observation_bytes", "fill_observation_sha256"),
        ):
            if hash_key not in overrides or overrides.get(hash_key) is None:
                values[hash_key] = sha256_bytes(values[content_key])  # type: ignore[arg-type]
        if "terminal_result_bytes" in overrides and "manager_terminal_result_sha256" not in overrides:
            values["manager_terminal_result_sha256"] = sha256_bytes(
                values["terminal_result_bytes"]  # type: ignore[arg-type]
            )
        return build_actionability_record(**values)  # type: ignore[arg-type]

    def _terminal_result_bytes(self, *, sealed_at: str = "2026-08-08T02:00:00Z") -> bytes:
        result = {
            "schema_version": "1",
            "document_type": "BTC_UTC_DAY_3PCT_POST_SHOCK_FACTOR_RESULT_V1",
            "trigger_id": "synthetic-trigger",
            "trigger_fingerprint": HEX_A,
            "snapshot_key": f"2026-08-07:{HEX_B}",
            "latest_outcome_day": "2026-08-07",
            "cumulative_chain_binding": HEX_B,
            "sealed_at": sealed_at,
            "disposition": "REVERSAL_FACTOR_READY_FOR_MANAGER_REVIEW",
            "terminal": True,
            "episodes": [
                {
                    "outcome_day_reference": {
                        "day": "2026-08-07",
                        "chain_head": HEX_B,
                    }
                }
            ],
            "gate_evidence": {},
            "statistics": {},
            "guardrails": {},
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        }
        return self._canonical(result)

    def _diagnostic_bytes(self) -> bytes:
        diagnostic = {
            "schema_version": "1",
            "diagnostic_type": "BTC_UTC_DAY_3PCT_SHOCK_DIAGNOSTIC_V1",
            "trigger_id": "synthetic-trigger",
            "trigger_fingerprint": HEX_A,
            "source": "synthetic-complete-utc-days",
            "observation_unit": "COMPLETE_UTC_DAY",
            "threshold_return": "0.0300",
            "prior_day": {},
            "target_day": {
                "day": "2026-08-10",
                "artifact_path": "evidence/2026-08-10.json",
                "artifact_sha256": HEX_A,
                "chain_head": HEX_B,
                "received_at": "2026-08-11T01:00:00Z",
            },
            "contract_activated_at": "2026-08-09T00:00:00Z",
            "sealed_at": "2026-08-11T01:05:00Z",
            "eligibility": "FORWARD_FACTOR_ELIGIBLE",
            "path": {"qualifies": True},
            "guardrails": {},
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        }
        return self._canonical(diagnostic)

    def _fill_bytes(self) -> bytes:
        return self._canonical(
            {
                "source_contract_id": "synthetic-utc-hour-open-v1",
                "source_contract_sha256": HEX_C,
                "interval_start": "2026-08-11T02:00:00Z",
                "observed_at": "2026-08-11T02:00:00Z",
                "received_at": "2026-08-11T02:01:00Z",
                "open": "100.25",
            }
        )

    @staticmethod
    def _canonical(value: dict[str, object]) -> bytes:
        return (
            json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            + "\n"
        ).encode("utf-8")


if __name__ == "__main__":
    unittest.main()
