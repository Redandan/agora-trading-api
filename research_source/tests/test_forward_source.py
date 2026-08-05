from __future__ import annotations

import copy
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import tempfile
import unittest
from urllib.error import URLError

from research_pipeline.evidence import evidence_progress, register_evidence_source_contract
from research_pipeline.models import RESEARCH_AUTHORIZATION
from research_pipeline.storage import ResearchStore
from research_pipeline.waiting import build_evidence_trigger
from research_source.contract import (
    PRODUCER,
    SOURCE,
    TRANSPORT,
    CaptureContractError,
    build_capture_request,
    expected_request_id,
    validate_capture_request,
)
from research_source.okx import (
    SourceIntegrityError,
    TemporarySourceError,
    build_day_bundle,
    fetch_okx_rows,
    selected_complete_rows,
)
from research_source.worker import process_ingest_request, process_source_request


def rows_for(day: str) -> list[list[str]]:
    start = datetime.fromisoformat(day).replace(tzinfo=timezone.utc)
    result = []
    for hour in range(24):
        timestamp = int((start + timedelta(hours=hour)).timestamp() * 1000)
        result.append(
            [str(timestamp), "100.0", "102", "99", "101.00", "10.500", "1050", "1050", "1"]
        )
    return list(reversed(result))


class ForwardSourceContractTest(unittest.TestCase):
    def request(self) -> dict[str, object]:
        value: dict[str, object] = {
            "schema_version": "1",
            "requested_at": "2026-01-02T01:00:00Z",
            "source_actor": "CODEX_CLOUD_OPS_HEARTBEAT_COMPANION",
            "operation": "CAPTURE_FORWARD_EVIDENCE",
            "trigger_id": "forward-source-test",
            "trigger_sha256": "1" * 64,
            "trigger_fingerprint": "2" * 64,
            "source": SOURCE,
            "source_contract_sha256": "3" * 64,
            "producer": PRODUCER,
            "transport": TRANSPORT,
            "day": "2026-01-01",
            "capture_deadline": "2026-01-02T06:00:00Z",
            "authorization": RESEARCH_AUTHORIZATION,
        }
        value["request_id"] = expected_request_id(value)
        return value

    def test_request_is_fixed_and_only_open_after_day_close(self) -> None:
        value = self.request()
        validated = validate_capture_request(
            value, now=datetime(2026, 1, 2, 1, tzinfo=timezone.utc)
        )
        self.assertEqual(validated["request_id"], value["request_id"])
        with self.assertRaisesRegex(CaptureContractError, "has not closed"):
            validate_capture_request(
                value, now=datetime(2026, 1, 1, 23, 59, tzinfo=timezone.utc)
            )
        with self.assertRaisesRegex(CaptureContractError, "backfill is prohibited"):
            validate_capture_request(
                value, now=datetime(2026, 1, 2, 6, 0, 1, tzinfo=timezone.utc)
            )

    def test_request_rejects_caller_controlled_fields(self) -> None:
        value = self.request()
        value["url"] = "https://example.invalid"
        with self.assertRaisesRegex(CaptureContractError, "unknown fields"):
            validate_capture_request(
                value, now=datetime(2026, 1, 2, 1, tzinfo=timezone.utc)
            )

    def test_r1_first_day_request_identity_is_frozen(self) -> None:
        trigger_path = (
            Path(__file__).resolve().parents[2]
            / "research_pipeline"
            / "examples"
            / "prospective-mechanism-neutral-evidence-refresh-2026q4-r1.trigger.json"
        )
        trigger = build_evidence_trigger(json.loads(trigger_path.read_text(encoding="utf-8")))
        state = {
            "trigger_sha256": (
                "e8e9af37368c64d88d95f9739c6927ece462a2413dc923874abd488812cfc6a7"
            )
        }
        request = build_capture_request(
            trigger=trigger,
            state=state,
            progress={
                "status": "CAPTURE_DUE",
                "next_observation_day": "2026-08-06",
                "next_capture_deadline": "2026-08-07T06:00:00Z",
                "source_contract": {
                    "producer": PRODUCER,
                    "transport": TRANSPORT,
                    "sha256": (
                        "5473210ab96b5f102a63c989a8dcab609b70c093e3e16804272b2be3de0a426e"
                    ),
                },
            },
            requested_at=datetime(2026, 8, 7, 1, tzinfo=timezone.utc),
        )

        self.assertEqual(
            trigger["fingerprint"],
            "0e5a4675e937613202f0a4a243360a405e9ace1823c4b999edb5d479849d2589",
        )
        self.assertEqual(
            state["trigger_sha256"],
            "e8e9af37368c64d88d95f9739c6927ece462a2413dc923874abd488812cfc6a7",
        )
        self.assertEqual(request["request_id"], "cd3076eebd380bdd59c8be742659797a")
        self.assertEqual(request["day"], "2026-08-06")
        self.assertEqual(request["capture_deadline"], "2026-08-07T06:00:00Z")

    def test_complete_rows_are_normalized_and_hash_stable(self) -> None:
        value = self.request()
        bundle, selected = build_day_bundle(value, rows_for("2026-01-01"))
        repeated, repeated_rows = build_day_bundle(value, list(reversed(rows_for("2026-01-01"))))
        self.assertEqual(bundle, repeated)
        self.assertEqual(selected, repeated_rows)
        self.assertEqual(len(bundle["bars"]), 24)
        self.assertEqual(bundle["bars"][0]["open"], "100")
        self.assertEqual(bundle["bars"][0]["volume"], "10.5")

    def test_missing_duplicate_and_incomplete_rows_fail_closed(self) -> None:
        missing = rows_for("2026-01-01")[1:]
        with self.assertRaisesRegex(TemporarySourceError, "24-hour grid"):
            selected_complete_rows(missing, "2026-01-01")
        duplicate = rows_for("2026-01-01")
        duplicate.append(copy.deepcopy(duplicate[0]))
        with self.assertRaisesRegex(SourceIntegrityError, "duplicate"):
            selected_complete_rows(duplicate, "2026-01-01")
        incomplete = rows_for("2026-01-01")
        incomplete[0][8] = "0"
        with self.assertRaisesRegex(TemporarySourceError, "incomplete"):
            selected_complete_rows(incomplete, "2026-01-01")

    def test_public_api_failure_and_invalid_json_fail_closed(self) -> None:
        class Response:
            def __init__(self, body: bytes) -> None:
                self.body = body
                self.headers = {"Content-Length": str(len(body))}

            def __enter__(self):
                return self

            def __exit__(self, *_args):
                return False

            def read(self, _limit: int) -> bytes:
                return self.body

        with self.assertRaisesRegex(TemporarySourceError, "success code"):
            fetch_okx_rows(opener=lambda *_args, **_kwargs: Response(b'{"code":"500","data":[]}'))
        with self.assertRaisesRegex(SourceIntegrityError, "valid UTF-8 JSON"):
            fetch_okx_rows(opener=lambda *_args, **_kwargs: Response(b"not-json"))
        with self.assertRaisesRegex(TemporarySourceError, "unavailable"):
            fetch_okx_rows(opener=lambda *_args, **_kwargs: (_ for _ in ()).throw(URLError("tls")))


class ForwardSourceEndToEndTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.requests = self.root / "source-requests"
        self.drop = self.root / "source-drop"
        self.policy = Path(__file__).resolve().parents[2] / "research_pipeline" / "policy.v3.json"
        self.store = ResearchStore(self.state, lock_stale_seconds=60)
        self.trigger = build_evidence_trigger(
            {
                "schema_version": "1",
                "trigger_id": "forward-source-e2e",
                "title": "Forward source end-to-end",
                "rationale": "Verify the isolated one-way forward evidence path.",
                "source": SOURCE,
                "evidence_start": "2026-01-01T00:00:00Z",
                "review_not_before": "2026-01-03T00:00:00Z",
                "minimum_observations": 2,
                "observation_unit": "COMPLETE_UTC_DAY",
                "required_integrity_checks": ["closed_bar_causality"],
                "prohibited_inferences": ["no performance selection"],
                "excluded_branches": ["closed branch"],
                "created_at": "2025-12-31T00:00:00Z",
                "authorization": RESEARCH_AUTHORIZATION,
            }
        )
        self.store.register_evidence_trigger(self.trigger)
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        register_evidence_source_contract(
            self.store,
            self.trigger,
            state,
            {
                "schema_version": "1",
                "contract_type": "FORWARD_EVIDENCE_SOURCE_CONTRACT",
                "trigger_id": self.trigger["trigger_id"],
                "trigger_fingerprint": self.trigger["fingerprint"],
                "source": SOURCE,
                "producer": PRODUCER,
                "transport": TRANSPORT,
                "artifact_format": "FORWARD_EVIDENCE_DAY_V1",
                "worker_network_access": "DENY",
                "worker_database_access": "DENY",
                "backfill": "DENY",
                "authorization": RESEARCH_AUTHORIZATION,
            },
            registered_at=datetime(2025, 12, 31, 12, tzinfo=timezone.utc),
        )

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_capture_drop_ingest_extends_canonical_hash_chain_once(self) -> None:
        now = datetime(2026, 1, 2, 1, tzinfo=timezone.utc)
        state = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        progress = evidence_progress(self.store, self.trigger, state, now=now)
        request = build_capture_request(
            trigger=self.trigger,
            state=state,
            progress=progress,
            requested_at=now,
        )
        self.requests.mkdir(parents=True)
        (self.requests / "pending.json").write_text(json.dumps(request), encoding="utf-8")
        source_result = process_source_request(
            now=now,
            fetcher=lambda: rows_for("2026-01-01"),
            request_dir=self.requests,
            drop_dir=self.drop,
        )
        self.assertEqual(source_result["status"], "COMPLETED")
        self.assertFalse((self.requests / "pending.json").exists())
        self.assertTrue((self.drop / "pending.json").is_file())
        ingest_result = process_ingest_request(
            now=now,
            request_dir=self.requests,
            drop_dir=self.drop,
            state_dir=self.state,
            policy_file=self.policy,
        )
        self.assertEqual(ingest_result["result"]["status"], "EVIDENCE_DAY_SEALED")
        sealed = self.store.load_evidence_trigger_state(self.trigger["trigger_id"])
        self.assertEqual(sealed["evidence_observation_count"], 1)
        self.assertRegex(sealed["evidence_chain_head"], r"^[a-f0-9]{64}$")
        self.assertFalse((self.drop / "pending.json").exists())


if __name__ == "__main__":
    unittest.main()
