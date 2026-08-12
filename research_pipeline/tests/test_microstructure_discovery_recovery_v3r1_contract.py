from __future__ import annotations

import hashlib
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
CONTRACT_PATH = (
    ROOT
    / "research_pipeline"
    / "okx-microstructure-discovery-recovery-contract.v3r1.json"
)
DOCUMENT_PATH = ROOT / "docs" / "okx-microstructure-discovery-recovery-v3r1.md"
SERVER_DOCUMENT_PATH = ROOT / "docs" / "server-research-worker-v2.md"
V3_DAY_PATH = (
    ROOT / "research_pipeline" / "okx-microstructure-forward-day.v3.schema.json"
)
V3_DIAGNOSTIC_PATH = (
    ROOT
    / "research_pipeline"
    / "okx-microstructure-forward-diagnostic-contract.v3.json"
)

V3_DAY_SHA256 = "205c1da492e9e463f2d06e38b38697232fffd6117c8dead54d036e3dbd849709"
V3_DIAGNOSTIC_SHA256 = (
    "7f9bad3a2165cdde653e3a2d0ecd64c56ade520e7327353e9339a441c9bfee1a"
)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


class MicrostructureDiscoveryRecoveryV3R1ContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.contract = json.loads(CONTRACT_PATH.read_text(encoding="utf-8"))
        cls.document = DOCUMENT_PATH.read_text(encoding="utf-8")
        cls.server_document = SERVER_DOCUMENT_PATH.read_text(encoding="utf-8")

    def test_identity_preserves_future_v4_economic_route(self) -> None:
        self.assertEqual(self.contract["schema_version"], "3R1")
        self.assertEqual(
            self.contract["contract_id"],
            "OKX_MICROSTRUCTURE_DISCOVERY_RECOVERY_V3R1",
        )
        self.assertEqual(
            self.contract["deployment_status"],
            "FROZEN_PRE_IMPLEMENTATION_NOT_DEPLOYED",
        )
        self.assertIn("previously sealed future V4 design remains", self.document)
        self.assertIn("V3R1 is not the previously reserved future V4", self.server_document)

    def test_r2_is_closed_without_evidence_reuse(self) -> None:
        r2 = self.contract["r2_disposition"]
        self.assertEqual(r2["accepted_day_count"], 0)
        self.assertEqual(
            r2["status"], "NO_EVIDENCE_CLOSE_INTERRUPTED_GENERATION"
        )
        self.assertEqual(r2["exact_control_event"], "MISSING_PROOF")
        self.assertEqual(r2["restart"], "DENY")
        self.assertEqual(r2["backfill"], "DENY")
        self.assertEqual(r2["reuse"], "DENY")

    def test_calendar_selects_first_complete_contiguous_streak(self) -> None:
        calendar = self.contract["calendar"]
        self.assertEqual(calendar["calendar_day_budget"], 42)
        self.assertEqual(calendar["required_consecutive_complete_days"], 14)
        self.assertEqual(
            calendar["selection_rule"],
            "FIRST_SOURCE_LIVENESS_DEFINED_FOURTEEN_DAY_STREAK",
        )
        self.assertEqual(calendar["market_outcome_selection"], "DENY")
        self.assertEqual(calendar["cross_gap_stitching"], "DENY")
        self.assertEqual(calendar["broken_streak_prefix_reuse"], "DENY")
        self.assertEqual(calendar["deadline_extension"], "DENY")

    def test_v3_market_and_diagnostic_bytes_remain_exact(self) -> None:
        accepted = self.contract["accepted_day"]
        diagnostic = self.contract["diagnostic"]
        self.assertEqual(_sha256(V3_DAY_PATH), V3_DAY_SHA256)
        self.assertEqual(_sha256(V3_DIAGNOSTIC_PATH), V3_DIAGNOSTIC_SHA256)
        self.assertEqual(accepted["schema_sha256"], V3_DAY_SHA256)
        self.assertEqual(diagnostic["contract_sha256"], V3_DIAGNOSTIC_SHA256)
        self.assertEqual(accepted["required_minutes"], 1440)
        self.assertEqual(diagnostic["required_complete_days"], 14)
        self.assertEqual(diagnostic["required_contiguous_minutes"], 20160)
        self.assertEqual(
            accepted["required_minutes"] * diagnostic["required_complete_days"],
            diagnostic["required_contiguous_minutes"],
        )

    def test_transport_rejections_are_narrow_and_never_market_inputs(self) -> None:
        rejected = self.contract["rejected_day"]
        self.assertEqual(
            rejected["allowed_reasons"],
            [
                "SERVICE_UPGRADE_NOTICE_64008",
                "TRANSPORT_DISCONNECT_UNPROVED_GAP",
                "PROCESS_RESTART_BEFORE_DAY_COMPLETE",
                "HOST_REBOOT_BEFORE_DAY_COMPLETE",
                "DUAL_CHANNEL_NOT_READY_AT_DAY_START",
            ],
        )
        self.assertEqual(rejected["partial_market_aggregates"], "DENY")
        self.assertEqual(rejected["feature_or_response_values"], "DENY")
        self.assertEqual(rejected["repair_retry_stitch_backfill"], "DENY")
        self.assertEqual(
            self.contract["diagnostic"]["rejected_day_market_input"], "DENY"
        )
        self.assertEqual(
            self.contract["diagnostic"]["nonselected_complete_prefix_market_input"],
            "DENY",
        )

    def test_unknown_events_and_integrity_defects_still_block(self) -> None:
        events = self.contract["websocket_events"]
        for key in (
            "error",
            "channel-conn-count-error",
            "unsubscribe",
            "unknown_notice",
            "unknown_event",
            "changed_channel_or_instrument",
        ):
            self.assertEqual(events[key], "BLOCK_GENERATION")
        self.assertFalse(events["lossless_cross_session_continuity_claim"])

    def test_single_clock_writer_and_research_only_boundary(self) -> None:
        authority = self.contract["authority"]
        claims = self.contract["claim_boundary"]
        self.assertEqual(authority["timer_authority"], "CODEX_CLOUD_OPS_ONLY")
        self.assertEqual(authority["cloud_schedule_count"], 1)
        self.assertEqual(authority["state_authority"], "SERVER_CANONICAL")
        self.assertEqual(authority["second_timer"], "DENY")
        self.assertEqual(authority["second_path_unit"], "DENY")
        self.assertEqual(authority["second_writer"], "DENY")
        self.assertEqual(
            authority["trading_database_order_fund_shadow_paper_live"], "DENY"
        )
        self.assertEqual(claims["immediate_fee_adjusted_pnl_effect"], "ZERO")
        self.assertEqual(claims["immediate_drawdown_effect"], "ZERO")
        self.assertFalse(claims["candidate_authorized"])
        self.assertFalse(claims["oos_authorized"])
        self.assertFalse(claims["promotion_authorized"])

    def test_terminal_dispositions_stop_before_candidate_or_oos(self) -> None:
        self.assertEqual(
            self.contract["terminal_dispositions"],
            [
                "V3R1_SOURCE_OR_INTAKE_INTEGRITY_CLOSE",
                "V3R1_NO_COMPLETE_STREAK_CLOSE",
                "V3R1_DIAGNOSTIC_NO_MECHANISM_CLOSE",
                "V3R1_DIAGNOSTIC_WAIT_MISSING_PROOF",
                "V3R1_ONE_HYPOTHESIS_DESIGN_READY",
            ],
        )
        self.assertIn(
            "No V3R1 disposition directly registers a candidate", self.document
        )


if __name__ == "__main__":
    unittest.main()
