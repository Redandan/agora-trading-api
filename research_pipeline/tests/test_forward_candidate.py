from __future__ import annotations

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal
import hashlib
import json
import unittest

from research_pipeline.evidence import _forward_mechanism_results
from research_pipeline.forward_candidate import (
    FORWARD_ADAPTER_CONTRACT_ID,
    FORWARD_PARENT,
    FORWARD_SELECTION_CUTOFF,
    candidate_oos_window,
    diagnostic_contract_status,
    load_diagnostic_contract,
    validate_forward_adapter_config,
)
from research_pipeline.report import performance_lines


def canonical_sha256(value: object) -> str:
    return hashlib.sha256(
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


class ForwardCandidateContractTest(unittest.TestCase):
    def test_volume_mechanism_requires_breadth_and_independent_response(self) -> None:
        contract = load_diagnostic_contract()
        start = date(2026, 1, 1)
        close = Decimal("100")
        daily = []
        for index in range(90):
            prior_event = index > 0 and (index - 1) >= 20 and (index - 1) % 5 == 0
            close *= Decimal("1.01") if prior_event else Decimal("0.999")
            current_event = index >= 20 and index % 5 == 0
            daily.append(
                {
                    "day": (start + timedelta(days=index)).isoformat(),
                    "open": close,
                    "high": close * Decimal("1.01"),
                    "low": close * Decimal("0.99"),
                    "close": close,
                    "volume": Decimal("200") if current_event else Decimal("100"),
                }
            )

        results = _forward_mechanism_results(daily, contract)
        volume = next(
            item
            for item in results
            if item["mechanism_key"] == "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
        )
        range_result = next(
            item
            for item in results
            if item["mechanism_key"] == "DRA_ENTRY_RANGE_CONFIRMATION_20D"
        )

        self.assertTrue(volume["all_predictive_gates_pass"])
        self.assertGreaterEqual(volume["statistics"]["labeled_event_count"], 8)
        self.assertFalse(range_result["all_predictive_gates_pass"])

    def test_candidate_config_copies_only_canonical_evidence_and_oos_window(self) -> None:
        excluded = ["closed branch a", "closed branch b"]
        window = {
            "observation_days": 90,
            "start_at": "2026-11-07T00:00:00Z",
            "end_at": "2027-02-05T00:00:00Z",
        }
        binding = {
            "trigger_id": "forward-trigger-test",
            "trigger_fingerprint": "1" * 64,
            "evidence_manifest_sha256": "2" * 64,
            "discovery_dataset_sha256": "3" * 64,
            "diagnostic_sha256": "4" * 64,
            "diagnostic_contract_sha256": "5" * 64,
            "coverage_end": "2026-11-04T00:00:00Z",
            "excluded_branches_sha256": canonical_sha256(excluded),
        }
        context = {
            "trigger_id": binding["trigger_id"],
            "trigger_fingerprint": binding["trigger_fingerprint"],
            "evidence_manifest_sha256": binding["evidence_manifest_sha256"],
            "discovery_dataset_sha256": binding["discovery_dataset_sha256"],
            "diagnostic_sha256": binding["diagnostic_sha256"],
            "diagnostic_contract": {
                "sha256": binding["diagnostic_contract_sha256"]
            },
            "coverage_end": binding["coverage_end"],
            "excluded_branches": excluded,
            "eligible_mechanisms": [
                {
                    "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
                    "all_predictive_gates_pass": True,
                }
            ],
            "oos_window": window,
        }
        config = {
            "schema_version": "1",
            "contract_id": FORWARD_ADAPTER_CONTRACT_ID,
            "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
            "evidence_binding": binding,
            "oos_window": window,
        }
        manifest = {
            "parent": FORWARD_PARENT,
            "selection_cutoff": FORWARD_SELECTION_CUTOFF,
            "oos_cutoff": window["end_at"],
            "max_variants": 3,
            "adapter_config": config,
        }
        self.assertEqual(validate_forward_adapter_config(manifest, context), config)

        changed = json.loads(json.dumps(manifest))
        changed["adapter_config"]["mechanism_key"] = "UNSUPPORTED_POST_HOC_MECHANISM"
        with self.assertRaisesRegex(ValueError, "did not pass"):
            validate_forward_adapter_config(changed, context)

    def test_oos_window_is_stable_inside_sla_and_moves_after_it(self) -> None:
        ready = datetime(2026, 11, 4, 1, tzinfo=timezone.utc)
        first = candidate_oos_window(
            ready,
            now=datetime(2026, 11, 4, 2, tzinfo=timezone.utc),
        )
        within_sla = candidate_oos_window(
            ready,
            now=datetime(2026, 11, 5, 0, 59, tzinfo=timezone.utc),
        )
        after_start = candidate_oos_window(
            ready,
            now=datetime(2026, 11, 6, 1, tzinfo=timezone.utc),
        )
        self.assertEqual(first, within_sla)
        self.assertEqual(first["start_at"], "2026-11-06T00:00:00Z")
        self.assertEqual(after_start["start_at"], "2026-11-07T00:00:00Z")
        self.assertEqual(diagnostic_contract_status()["status"], "READY")

    def test_forward_runner_metrics_are_visible_to_coach_reports(self) -> None:
        parent = {
            "total_pnl_usdt": "10",
            "max_drawdown_pct": "4",
            "realized_usdt": "8",
            "unrealized_usdt": "2",
            "median_hold_hours": "48",
            "p90_hold_hours": "120",
        }
        candidate = {
            "total_pnl_usdt": "12.5",
            "max_drawdown_pct": "3.8",
            "realized_usdt": "9",
            "unrealized_usdt": "3.5",
            "median_hold_hours": "44",
            "p90_hold_hours": "110",
        }
        lines = performance_lines(
            {
                "schema_version": "DRA_FORWARD_ENTRY_ADMISSION_RUNNER_V1",
                "status": "CANDIDATE_FROZEN",
                "mechanism_key": "DRA_ENTRY_VOLUME_CONFIRMATION_20D",
                "baseline": {"validation": parent},
                "variants": [{"role": "primary", "validation": candidate}],
            }
        )
        self.assertIn("delta `+2.5`", lines[0])
        self.assertIn("delta `-0.2%`", lines[1])
        self.assertIn("DRA_ENTRY_VOLUME_CONFIRMATION_20D", lines[-1])


if __name__ == "__main__":
    unittest.main()
