from __future__ import annotations

import hashlib
import json
import tempfile
from datetime import datetime, timezone
from pathlib import Path
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator

from research_pipeline.candidate_funnel import (
    build_candidate_funnel,
    candidate_funnel_status,
    load_candidate_pool_catalog,
)
from research_pipeline.forward_volatility_persistence import (
    CLOSE as VOLATILITY_CLOSE,
    RETAIN as VOLATILITY_RETAIN,
    _canonical_bytes as _volatility_canonical_bytes,
)
from research_pipeline.forward_volatility_persistence_activation import (
    ACTIVATION_STATE_KEY,
    ActivationDecision,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = REPO_ROOT / "research_pipeline" / "pre-candidate-pool.v1.json"
SCHEMA_PATH = REPO_ROOT / "research_pipeline" / "pre-candidate-pool.v1.schema.json"
VOLUME = "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
RANGE = "DRA_ENTRY_RANGE_CONFIRMATION_20D"


def _registry(*, eligible: list[str] | None = None) -> dict[str, object]:
    return {
        "research_status": "WAITING_FOR_EVIDENCE",
        "forward_candidate_readiness": {
            "status": "READY",
            "diagnostic_contract": {"mechanisms": [VOLUME, RANGE]},
        },
        "evidence_triggers": [
            {
                "trigger_id": "prospective-mechanism-neutral-evidence-refresh",
                "purpose": "HYPOTHESIS_DISCOVERY",
                "status": "OPEN",
                "progress": {
                    "status": "COLLECTING",
                    "observation_count": 1,
                    "minimum_observations": 90,
                },
                "candidate_context": {"eligible_mechanisms": eligible or []},
                "next_review_at": "2026-11-15T00:00:00Z",
            }
        ],
        "experiments": [
            {
                "experiment_id": "sealed-example",
                "title": "Sealed example",
                "stage": "CLOSED",
                "outcome": "NO_CANDIDATE",
                "adapter": "example-adapter",
                "updated_at": "2026-08-18T00:00:00Z",
            }
        ],
    }


def _microstructure(status: str = "CAPTURE_OVERDUE") -> dict[str, object]:
    return {
        "diagnostic_id": "okx-btcusdt-microstructure-v3r1",
        "status": status,
        "complete_day_count": 2,
        "required_day_count": 42,
        "next_calendar_day": "2026-08-17",
    }


class CandidateFunnelTest(unittest.TestCase):
    def test_catalog_is_schema_valid_and_all_evidence_hashes_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        catalog_document = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(catalog_document)

        catalog = load_candidate_pool_catalog(REPO_ROOT, CATALOG_PATH)
        self.assertEqual(len(catalog["families"]), 5)
        self.assertEqual(len(catalog["closed_families"]), 18)
        monthly_momentum = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-monthly-12m-time-series-momentum-long-cash"
        )
        self.assertEqual(
            monthly_momentum["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_MONTHLY_12M_TIME_SERIES_MOMENTUM_FAMILY",
        )
        self.assertTrue(monthly_momentum["prohibited_reopen"])
        static_allocation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-static-half-passive-half-dra-v1"
        )
        self.assertEqual(
            static_allocation["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_STATIC_HALF_PASSIVE_HALF_DRA_V1_FAMILY",
        )
        self.assertTrue(static_allocation["prohibited_reopen"])
        donchian = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-donchian-20d-10d-standalone"
        )
        self.assertEqual(
            donchian["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DONCHIAN_20D_10D_STANDALONE_FAMILY",
        )
        self.assertTrue(donchian["prohibited_reopen"])
        microstructure = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-microstructure-dra-entry-admission-v3r1"
        )
        self.assertEqual(
            microstructure["disposition"],
            "NO_EVIDENCE_CLOSE_MICROSTRUCTURE_SOURCE_INTEGRITY_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in microstructure["evidence_bindings"]],
            [
                "SOURCE_INTEGRITY_OPPORTUNITY_COST_CLOSURE",
                "FROZEN_SOURCE_RECOVERY_CONTRACT",
                "FROZEN_PREOUTCOME_ADMISSION_CONTRACT",
            ],
        )
        self.assertTrue(microstructure["prohibited_reopen"])
        cftc = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-cftc-leveraged-money-positioning-delta"
        )
        self.assertEqual(
            cftc["disposition"],
            "NO_CANDIDATE_CLOSE_CFTC_TFF_FACTOR_FAMILY",
        )
        self.assertTrue(cftc["prohibited_reopen"])
        close_location = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-close-location-entry-admission"
        )
        self.assertEqual(
            close_location["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_CLOSE_LOCATION_FACTOR_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in close_location["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(close_location["prohibited_reopen"])
        h1_volume_weighted_close_location = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-h1-volume-weighted-close-location-entry-admission"
        )
        self.assertEqual(
            h1_volume_weighted_close_location["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_H1_VOLUME_WEIGHTED_CLOSE_LOCATION_FACTOR_FAMILY",
        )
        self.assertTrue(h1_volume_weighted_close_location["prohibited_reopen"])
        amihud = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-amihud-illiquidity-entry-admission"
        )
        self.assertEqual(
            amihud["disposition"],
            "DRA_AMIHUD_ILLIQUIDITY_EVIDENCE_INSUFFICIENT",
        )
        self.assertEqual(
            [binding["role"] for binding in amihud["evidence_bindings"]],
            ["SEALED_DATA_REJECT_RESULT", "MANAGER_EXCLUDE_ACCEPTANCE"],
        )
        self.assertTrue(amihud["prohibited_reopen"])
        lagged_volatility = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-lagged-realized-volatility-entry-admission"
        )
        self.assertEqual(
            lagged_volatility["disposition"],
            "CLOSE_FEATURE_FAMILY_WITHOUT_TUNING",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in lagged_volatility["evidence_bindings"]
            ],
            [
                "LEGACY_SEALED_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(lagged_volatility["prohibited_reopen"])
        self.assertTrue(
            all(
                binding["verified"]
                for family in catalog["families"] + catalog["closed_families"]
                for binding in family["evidence_bindings"]
            )
        )

    def test_closed_microstructure_source_does_not_block_open_family_ranking(self) -> None:
        snapshot = build_candidate_funnel(
            _registry(),
            microstructure=_microstructure(),
            repo_root=REPO_ROOT,
        )

        self.assertEqual(snapshot["status"], "READY")
        self.assertEqual(snapshot["summary"]["open_family_count"], 5)
        self.assertEqual(snapshot["summary"]["closed_family_count"], 19)
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 0)
        self.assertEqual(snapshot["summary"]["active_experiment_count"], 0)
        self.assertEqual(snapshot["summary"]["candidate_oos_count"], 0)
        self.assertNotIn(
            "microstructure-dra-entry-admission",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        self.assertEqual(snapshot["summary"]["integrity_blocked_family_count"], 0)
        self.assertNotIn(
            "btc-donchian-20d-10d-standalone",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        forward = {
            family["family_id"]: family
            for family in snapshot["ranked_families"]
            if family["canonical_binding"]["kind"] == "FORWARD_MECHANISM"
        }
        self.assertEqual(forward["dra-entry-volume-confirmation-20d"]["stage"], "FORWARD_EVIDENCE")
        self.assertEqual(forward["dra-entry-volume-confirmation-20d"]["estimated_days_to_next_gate"], 89)
        self.assertTrue(snapshot["safety"]["read_only_derived_view"])
        self.assertFalse(snapshot["safety"]["canonical_state_write"])
        self.assertFalse(snapshot["safety"]["second_timer_or_writer"])
        self.assertFalse(snapshot["safety"]["shadow_paper_live"])

    def test_evidence_ready_mechanism_is_not_counted_as_formal_candidate(self) -> None:
        snapshot = build_candidate_funnel(
            _registry(eligible=[VOLUME]),
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        volume = next(
            family
            for family in snapshot["ranked_families"]
            if family["family_id"] == "dra-entry-volume-confirmation-20d"
        )
        self.assertEqual(snapshot["status"], "READY")
        self.assertEqual(volume["stage"], "READY_FOR_HYPOTHESIS")
        self.assertEqual(volume["rank"], 1)
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 0)

    def test_frozen_candidate_is_linked_to_its_pool_family(self) -> None:
        registry = _registry()
        registry["experiments"] = [
            {
                "experiment_id": "volume-candidate",
                "title": "Volume candidate",
                "adapter": "dra-forward-entry-admission-v1",
                "stage": "PREREGISTERED",
                "outcome": None,
                "candidate_mechanism_key": VOLUME,
                "candidate_frozen_at": "2026-11-16T00:00:00Z",
                "oos_evidence_trigger_id": "volume-candidate-oos",
                "updated_at": "2026-11-16T00:00:00Z",
            }
        ]
        registry["evidence_triggers"].append(
            {
                "trigger_id": "volume-candidate-oos",
                "purpose": "CANDIDATE_OOS",
                "status": "WAITING",
            }
        )

        snapshot = build_candidate_funnel(
            registry,
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        volume = next(
            family
            for family in snapshot["ranked_families"]
            if family["family_id"] == "dra-entry-volume-confirmation-20d"
        )
        self.assertEqual(volume["stage"], "CANDIDATE_FROZEN")
        self.assertEqual(volume["progress"]["experiment_id"], "volume-candidate")
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 1)
        self.assertEqual(snapshot["summary"]["active_experiment_count"], 1)
        self.assertEqual(snapshot["summary"]["candidate_oos_count"], 1)

    def test_single_lane_constraints_fail_closed(self) -> None:
        registry = _registry()
        registry["experiments"] = [
            {"experiment_id": "active-a", "stage": "DESIGN"},
            {"experiment_id": "active-b", "stage": "VALIDATION"},
        ]
        registry["evidence_triggers"].extend(
            [
                {"trigger_id": "oos-a", "purpose": "CANDIDATE_OOS", "status": "OPEN"},
                {"trigger_id": "oos-b", "purpose": "CANDIDATE_OOS", "status": "OPEN"},
            ]
        )

        snapshot = build_candidate_funnel(
            registry,
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        self.assertEqual(snapshot["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(
            snapshot["constraint_violations"],
            ["MAXIMUM_ACTIVE_EXPERIMENTS_EXCEEDED", "MAXIMUM_CANDIDATE_OOS_EXCEEDED"],
        )

    def test_volatility_activation_promotes_only_to_forward_evidence(self) -> None:
        receipt = _volatility_receipt()
        with tempfile.TemporaryDirectory() as directory, patch(
            "research_pipeline.candidate_funnel."
            "prepare_forward_volatility_persistence_activation",
            return_value=ActivationDecision(
                receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
            ),
        ), patch(
            "research_pipeline.candidate_funnel.resolve_active_forward_trigger_lineage",
            return_value=object(),
        ), patch(
            "research_pipeline.candidate_funnel._load_volatility_snapshots",
            return_value=[],
        ):
            snapshot = build_candidate_funnel(
                _registry(),
                microstructure=_microstructure("WAITING_FOR_DAY"),
                heartbeat_state={
                    "last_success": "2026-08-18T01:05:00Z",
                    ACTIVATION_STATE_KEY: receipt,
                },
                state_root=Path(directory),
                as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                repo_root=REPO_ROOT,
            )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("FORWARD_EVIDENCE", volatility["stage"])
        self.assertEqual(
            "FORWARD_VOLATILITY_PERSISTENCE",
            volatility["canonical_binding"]["kind"],
        )
        self.assertEqual(0, volatility["progress"]["episode_count"])
        self.assertFalse(volatility["progress"]["terminal"])
        self.assertEqual(0, snapshot["summary"]["formal_candidate_count"])

    def test_volatility_terminal_retain_is_not_a_formal_candidate(self) -> None:
        receipt = _volatility_receipt()
        terminal = _volatility_terminal(receipt, VOLATILITY_RETAIN)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "terminal-retain.json"
            path.write_text("{}\n", encoding="utf-8")
            with patch(
                "research_pipeline.candidate_funnel."
                "prepare_forward_volatility_persistence_activation",
                return_value=ActivationDecision(
                    receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
                ),
            ), patch(
                "research_pipeline.candidate_funnel."
                "resolve_active_forward_trigger_lineage",
                return_value=object(),
            ), patch(
                "research_pipeline.candidate_funnel._load_volatility_snapshots",
                return_value=[(path, terminal)],
            ):
                snapshot = build_candidate_funnel(
                    _registry(),
                    microstructure=_microstructure("WAITING_FOR_DAY"),
                    heartbeat_state={ACTIVATION_STATE_KEY: receipt},
                    state_root=Path(directory),
                    as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                    repo_root=REPO_ROOT,
                )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("READY_FOR_HYPOTHESIS", volatility["stage"])
        self.assertTrue(volatility["progress"]["terminal"])
        self.assertEqual(VOLATILITY_RETAIN, volatility["progress"]["disposition"])
        self.assertEqual(0, snapshot["summary"]["formal_candidate_count"])

    def test_volatility_terminal_close_becomes_dynamic_tombstone(self) -> None:
        receipt = _volatility_receipt()
        terminal = _volatility_terminal(receipt, VOLATILITY_CLOSE)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "terminal-close.json"
            path.write_text("{}\n", encoding="utf-8")
            with patch(
                "research_pipeline.candidate_funnel."
                "prepare_forward_volatility_persistence_activation",
                return_value=ActivationDecision(
                    receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
                ),
            ), patch(
                "research_pipeline.candidate_funnel."
                "resolve_active_forward_trigger_lineage",
                return_value=object(),
            ), patch(
                "research_pipeline.candidate_funnel._load_volatility_snapshots",
                return_value=[(path, terminal)],
            ):
                snapshot = build_candidate_funnel(
                    _registry(),
                    microstructure=_microstructure("WAITING_FOR_DAY"),
                    heartbeat_state={ACTIVATION_STATE_KEY: receipt},
                    state_root=Path(directory),
                    as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                    repo_root=REPO_ROOT,
                )

        self.assertNotIn(
            "btc-3pct-post-shock-volatility-persistence",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        closed = _family(
            {"ranked_families": snapshot["closed_families"]},
            "btc-3pct-post-shock-volatility-persistence",
        )
        self.assertEqual("CLOSED", closed["stage"])
        self.assertEqual(VOLATILITY_CLOSE, closed["disposition"])
        self.assertTrue(closed["prohibited_reopen"])
        self.assertEqual(4, snapshot["summary"]["open_family_count"])

    def test_volatility_receipt_conflict_blocks_only_that_family(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch(
            "research_pipeline.candidate_funnel."
            "prepare_forward_volatility_persistence_activation",
            side_effect=ValueError("synthetic receipt conflict"),
        ):
            snapshot = build_candidate_funnel(
                _registry(),
                microstructure=_microstructure("WAITING_FOR_DAY"),
                heartbeat_state={ACTIVATION_STATE_KEY: _volatility_receipt()},
                state_root=Path(directory),
                as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                repo_root=REPO_ROOT,
            )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("INTEGRITY_BLOCKED", volatility["stage"])
        self.assertIn("synthetic receipt conflict", volatility["integrity_status"])
        self.assertEqual(
            ["btc-3pct-post-shock-volatility-persistence"],
            snapshot["summary"]["integrity_blocked_families"],
        )

    def test_status_wrapper_fails_closed_when_catalog_is_outside_repo_root(self) -> None:
        result = candidate_funnel_status(
            _registry(),
            microstructure=_microstructure(),
            repo_root=REPO_ROOT / "research_pipeline" / "tests",
        )

        self.assertEqual(result["status"], "INTEGRITY_BLOCKED")
        self.assertFalse(result["safety"]["canonical_state_write"])
        self.assertFalse(result["safety"]["second_timer_or_writer"])


def _family(snapshot: dict[str, object], family_id: str) -> dict[str, object]:
    return next(
        family
        for family in snapshot["ranked_families"]
        if family["family_id"] == family_id
    )


def _volatility_receipt() -> dict[str, object]:
    return {
        "activated_at": "2026-08-18T01:05:00Z",
        "worker_release_id": "20260818T010000Z",
        "worker_source_commit": "a" * 40,
        "leaf_trigger_id": "prospective-mechanism-neutral-evidence-refresh-rollover",
        "leaf_trigger_fingerprint": "b" * 64,
    }


def _volatility_terminal(
    receipt: dict[str, object], disposition: str
) -> dict[str, object]:
    return {
        "episodes": [{} for _ in range(12)],
        "terminal": True,
        "disposition": disposition,
        "activation_receipt_sha256": hashlib.sha256(
            _volatility_canonical_bytes(receipt)
        ).hexdigest(),
    }


if __name__ == "__main__":
    unittest.main()
