from __future__ import annotations

from copy import deepcopy
from datetime import date, timedelta
import hashlib
import json
import os
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

from research_pipeline.microstructure_diagnostic import (
    AUTHORIZATION,
    CANONICALIZATION as DIAGNOSTIC_CANONICALIZATION,
    CONTRACT_ID,
    TIER_KEYS,
    payload_sha256 as diagnostic_payload_sha256,
)
from research_pipeline.microstructure_handoff import (
    HANDOFF_CANONICALIZATION,
    INFERENCE_BOUNDARIES,
    MANIFEST_NAME,
    MANIFEST_TYPE,
    RESULT_NAME,
    RESULT_TYPE,
    HandoffContractError,
    HandoffContext,
    create_result_once,
    validate_handoff_package,
    validate_handoff_result_bytes,
)
from research_pipeline.microstructure_intake import canonical_v3_state_bytes
from research_pipeline.microstructure_source_contract import (
    V3_DAY_SCHEMA_SHA256,
    V3_DIAGNOSTIC_CONTRACT_SHA256,
    V3_DROP_ENVELOPE_SCHEMA_SHA256,
    V3_INTAKE_STATE_SCHEMA_SHA256,
    V3_SOURCE_CONTRACT_SHA256,
    accept_v3_intake_day,
    canonical_json_bytes,
    initial_v3_intake_state,
)
from research_pipeline.tests.test_microstructure_source_contract import (
    _day_bundle as _v2_day_bundle,
)
from research_pipeline.tests.test_microstructure_v3_intake_isolation import (
    _accepted_at,
    _v3_day_bundle,
    _v3_envelope,
)


TASK_ID = "local-node-microstructure-v3-handoff-contract-freeze-v1"
TASK_SHA256 = "ce44dd571b11d24ed23f8b81939074b25fb2374e884c491b9e93124ac3e1cb11"
DIAGNOSTIC_ID = "microstructure-v3-isolation"
START_DAY = date(2026, 9, 1)
PRODUCER_RELEASE_ID = "deterministic-v3-fixture-release"
PRODUCER_MANIFEST_SHA256 = "b" * 64


def _seal(value: dict[str, object]) -> None:
    value["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": hashlib.sha256(
            canonical_json_bytes(value, exclude_key="seal")
        ).hexdigest(),
        "canonicalization": HANDOFF_CANONICALIZATION,
    }


def _names(day_text: str) -> tuple[str, str]:
    base = f"okx-btc-usdt-microstructure-{day_text}"
    return f"days/{day_text}/{base}.json", f"days/{day_text}/{base}.envelope.json"


class _Fixture:
    def __init__(self) -> None:
        self.files: dict[str, bytes] = {}
        state = initial_v3_intake_state(
            DIAGNOSTIC_ID,
            START_DAY,
            as_of_day=START_DAY - timedelta(days=1),
        )
        day_material: list[tuple[date, bytes, bytes, dict[str, object]]] = []
        predecessor_day: date | None = None
        predecessor_hash: str | None = None
        for index in range(14):
            bundle_day = START_DAY + timedelta(days=index)
            bundle = _v3_day_bundle(bundle_day)
            envelope = _v3_envelope(
                bundle,
                predecessor_day=predecessor_day,
                predecessor_bundle_sha256=predecessor_hash,
            )
            bundle_raw = canonical_json_bytes(bundle)
            envelope_raw = canonical_json_bytes(envelope)
            state = accept_v3_intake_day(
                state,
                envelope,
                bundle,
                raw_envelope_bytes=envelope_raw,
                raw_bundle_bytes=bundle_raw,
                accepted_at=_accepted_at(bundle_day),
                observed_producer_identity="agora-evidence-source",
                delivered_via_atomic_rename=True,
                source_path_is_symlink=False,
                overwrite_attempted=False,
            )
            day_material.append((bundle_day, bundle_raw, envelope_raw, bundle))
            predecessor_day = bundle_day
            predecessor_hash = hashlib.sha256(bundle_raw).hexdigest()

        state_raw = canonical_v3_state_bytes(state)
        state_name = f"canonical/{DIAGNOSTIC_ID}.json"
        self.files[state_name] = state_raw
        days: list[dict[str, object]] = []
        for (bundle_day, bundle_raw, envelope_raw, _bundle), record in zip(
            day_material, state["accepted_days"]
        ):
            bundle_name, envelope_name = _names(bundle_day.isoformat())
            self.files[bundle_name] = bundle_raw
            self.files[envelope_name] = envelope_raw
            days.append(
                {
                    "day": bundle_day.isoformat(),
                    "bundle_relative_name": bundle_name,
                    "bundle_sha256": hashlib.sha256(bundle_raw).hexdigest(),
                    "envelope_relative_name": envelope_name,
                    "envelope_sha256": hashlib.sha256(envelope_raw).hexdigest(),
                    "predecessor_day": (
                        None
                        if bundle_day == START_DAY
                        else (bundle_day - timedelta(days=1)).isoformat()
                    ),
                    "predecessor_bundle_sha256": record[
                        "predecessor_bundle_sha256"
                    ],
                    "accepted_at": record["accepted_at"],
                    "cumulative_chain_sha256": record["cumulative_chain_sha256"],
                }
            )
        self.manifest: dict[str, object] = {
            "schema_version": "1",
            "manifest_type": MANIFEST_TYPE,
            "authorization": AUTHORIZATION,
            "task_id": TASK_ID,
            "task_sha256": TASK_SHA256,
            "canonical_state": {
                "relative_name": state_name,
                "sha256": hashlib.sha256(state_raw).hexdigest(),
                "intake_state_schema_sha256": V3_INTAKE_STATE_SCHEMA_SHA256,
                "state_type": "SERVER_CANONICAL_MICROSTRUCTURE_V3_INTAKE",
                "state_authority": "SERVER_CANONICAL",
                "diagnostic_id": DIAGNOSTIC_ID,
                "status": "DIAGNOSTIC_READY",
                "start_day": START_DAY.isoformat(),
                "last_day": (START_DAY + timedelta(days=13)).isoformat(),
                "required_day_count": 14,
                "accepted_day_count": 14,
                "chain_head_sha256": state["chain_head_sha256"],
                "source_contract_sha256": V3_SOURCE_CONTRACT_SHA256,
                "drop_envelope_schema_sha256": V3_DROP_ENVELOPE_SCHEMA_SHA256,
                "day_schema_sha256": V3_DAY_SCHEMA_SHA256,
                "diagnostic_contract_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
            },
            "source_release": {
                "producer_identity": "agora-evidence-source",
                "producer_release_id": PRODUCER_RELEASE_ID,
                "producer_manifest_sha256": PRODUCER_MANIFEST_SHA256,
            },
            "days": days,
            "inference_boundaries": deepcopy(INFERENCE_BOUNDARIES),
        }
        _seal(self.manifest)
        self.files[MANIFEST_NAME] = canonical_json_bytes(self.manifest)

    def materialize(
        self,
        root: Path,
        *,
        files: dict[str, bytes] | None = None,
    ) -> list[tuple[str, Path]]:
        selected = self.files if files is None else files
        inventory: list[tuple[str, Path]] = []
        for relative_name, raw in selected.items():
            target = root.joinpath(*relative_name.split("/"))
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            inventory.append((relative_name, target))
        return inventory

    def files_with_manifest(self, manifest: dict[str, object]) -> dict[str, bytes]:
        changed = dict(self.files)
        changed[MANIFEST_NAME] = canonical_json_bytes(manifest)
        return changed


def _empty_metrics() -> dict[str, object]:
    return {
        horizon: {
            "median_return_bps": None,
            "median_mfe_bps": None,
            "median_mae_bps": None,
            "positive_return_share_pct": None,
            "matched_median_return_delta_bps": None,
        }
        for horizon in ("5", "15", "60", "240", "1440")
    }


def _diagnostic_result(context: HandoffContext) -> dict[str, object]:
    tier = {
        "event_count": 0,
        "first_seven_day_event_count": 0,
        "second_seven_day_event_count": 0,
        "matched_control_count": 0,
        "matched_control_coverage_pct": "0",
        "overlapping_1440m_event_pair_count": 0,
        "gates": {
            "minimum_30_events": False,
            "minimum_10_events_first_seven_days": False,
            "minimum_10_events_second_seven_days": False,
            "minimum_80_pct_matched_controls": False,
        },
        "gate_status": "INSUFFICIENT_FORWARD_EVIDENCE",
        "metrics_by_horizon_minutes": _empty_metrics(),
        "events": [],
    }
    result: dict[str, object] = {
        "schema_version": "OKX_MICROSTRUCTURE_FORWARD_DIAGNOSTIC_RESULT_V3",
        "contract_id": CONTRACT_ID,
        "contract_file_sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        "authorization": AUTHORIZATION,
        "status": "INSUFFICIENT_FORWARD_EVIDENCE",
        "input": {
            "first_day": context.days[0]["day"],
            "last_day": context.days[-1]["day"],
            "complete_utc_days": 14,
            "complete_minutes": 20160,
            "files": [
                {
                    "path": item["bundle_relative_name"],
                    "day": item["day"],
                    "payload_sha256": item["payload_sha256"],
                    "file_sha256": item["bundle_sha256"],
                }
                for item in context.days
            ],
        },
        "entry_reference": "NEXT_COMPLETE_MINUTE_OPEN",
        "fees_and_slippage": "NOT_APPLIED_DIAGNOSTIC_NOT_PNL",
        "tiers": {name: deepcopy(tier) for name in TIER_KEYS},
        "inference_boundary": [
            "result_is_hypothesis_discovery_only",
            "result_is_not_candidate_or_oos_evidence",
            "result_is_not_a_trading_strategy_or_order_instruction",
            "no_tier_selection_or_threshold_change_after_outcome_access",
            "insufficient_evidence_is_a_valid_result",
        ],
    }
    result["seal"] = {
        "algorithm": "SHA-256",
        "payload_sha256": diagnostic_payload_sha256(result),
        "canonicalization": DIAGNOSTIC_CANONICALIZATION,
    }
    return result


def _result_bytes(context: HandoffContext) -> bytes:
    diagnostic = _diagnostic_result(context)
    result: dict[str, object] = {
        "schema_version": "1",
        "result_type": RESULT_TYPE,
        "authorization": AUTHORIZATION,
        "task_id": context.task_id,
        "task_sha256": context.task_sha256,
        "input_manifest": {
            "relative_name": MANIFEST_NAME,
            "sha256": context.manifest_sha256,
            "payload_sha256": context.manifest_payload_sha256,
        },
        "canonical_state": {
            "relative_name": context.state_relative_name,
            "sha256": context.state_sha256,
            "diagnostic_id": context.diagnostic_id,
            "chain_head_sha256": context.chain_head_sha256,
        },
        "diagnostic_contract": {
            "contract_id": CONTRACT_ID,
            "sha256": V3_DIAGNOSTIC_CONTRACT_SHA256,
        },
        "diagnostic_payload_hashes": {
            "payload_sha256": diagnostic["seal"]["payload_sha256"],
            "canonical_document_sha256": hashlib.sha256(
                canonical_json_bytes(diagnostic)
            ).hexdigest(),
        },
        "diagnostic_result": diagnostic,
        "inference_boundaries": deepcopy(INFERENCE_BOUNDARIES),
    }
    _seal(result)
    return canonical_json_bytes(result)


class MicrostructureHandoffContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = _Fixture()

    def _validate(
        self,
        root: Path,
        files: dict[str, bytes] | None = None,
    ) -> HandoffContext:
        inventory = self.fixture.materialize(root, files=files)
        return validate_handoff_package(
            root,
            inventory,
            expected_task_id=TASK_ID,
            expected_task_sha256=TASK_SHA256,
        )

    def test_valid_canonical_thirty_file_closure_and_result(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            self.assertEqual(30, len(self.fixture.files))
            self.assertEqual(14, len(context.days))
            result = validate_handoff_result_bytes(_result_bytes(context), context)
            self.assertEqual(RESULT_TYPE, result["result_type"])
            self.assertTrue(result["inference_boundaries"]["discovery_only"])
            self.assertFalse(result["inference_boundaries"]["candidate_authorized"])

    def test_manifest_rejects_thirteen_fifteen_gap_order_and_swap(self) -> None:
        mutations = []
        for mode in ("thirteen", "fifteen", "gap", "order", "swap"):
            manifest = deepcopy(self.fixture.manifest)
            days = manifest["days"]
            assert isinstance(days, list)
            if mode == "thirteen":
                del days[-1]
            elif mode == "fifteen":
                days.append(deepcopy(days[-1]))
            elif mode == "gap":
                days[6] = deepcopy(days[7])
            elif mode == "order":
                days.reverse()
            else:
                days[4], days[5] = days[5], days[4]
            _seal(manifest)
            mutations.append((mode, self.fixture.files_with_manifest(manifest)))
        for mode, files in mutations:
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                with self.assertRaises(HandoffContractError):
                    self._validate(Path(directory), files)

    def test_state_chain_task_contract_and_source_release_mismatch(self) -> None:
        mutations: list[tuple[str, dict[str, bytes]]] = []
        for mode in ("state", "chain", "task", "contract", "source"):
            manifest = deepcopy(self.fixture.manifest)
            if mode == "state":
                manifest["canonical_state"]["sha256"] = "0" * 64
            elif mode == "chain":
                manifest["days"][3]["cumulative_chain_sha256"] = "0" * 64
            elif mode == "task":
                manifest["task_sha256"] = "0" * 64
            elif mode == "contract":
                manifest["canonical_state"]["day_schema_sha256"] = "0" * 64
            else:
                manifest["source_release"]["producer_release_id"] = "wrong-release"
            _seal(manifest)
            mutations.append((mode, self.fixture.files_with_manifest(manifest)))
        for mode, files in mutations:
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                with self.assertRaises(HandoffContractError):
                    self._validate(Path(directory), files)

    def test_inventory_rejects_traversal_absolute_and_alternate_separator(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = self.fixture.materialize(root)
            bad_names = ("../handoff-manifest.json", str(root / MANIFEST_NAME), "days\\bad.json")
            for name in bad_names:
                with self.subTest(name=name):
                    changed = list(inventory)
                    changed[0] = (name, changed[0][1])
                    with self.assertRaises(HandoffContractError):
                        validate_handoff_package(
                            root,
                            changed,
                            expected_task_id=TASK_ID,
                            expected_task_sha256=TASK_SHA256,
                        )

    def test_inventory_rejects_duplicate_extra_missing_and_partial(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = self.fixture.materialize(root)
            duplicate = list(inventory)
            duplicate[-1] = duplicate[0]
            extra_path = root / "extra.json"
            extra_path.write_bytes(b"{}")
            scenarios = {
                "duplicate": duplicate,
                "extra": inventory + [("extra.json", extra_path)],
                "missing": inventory[:-1],
            }
            for mode, changed in scenarios.items():
                with self.subTest(mode=mode):
                    with self.assertRaises(HandoffContractError):
                        validate_handoff_package(
                            root,
                            changed,
                            expected_task_id=TASK_ID,
                            expected_task_sha256=TASK_SHA256,
                        )
            partial = dict(self.fixture.files)
            first_bundle = self.fixture.manifest["days"][0]["bundle_relative_name"]
            partial[first_bundle] = partial[first_bundle][:-1]
        with TemporaryDirectory() as directory:
            with self.assertRaises(HandoffContractError):
                self._validate(Path(directory), partial)

    def test_manifest_rejects_noncanonical_duplicate_key_and_non_utf8(self) -> None:
        variants = {
            "pretty": json.dumps(self.fixture.manifest, indent=2, sort_keys=True).encode(),
            "duplicate": self.fixture.files[MANIFEST_NAME].replace(
                b"{", b'{"schema_version":"1",', 1
            ),
            "non_utf8": b"\xff",
        }
        for mode, raw in variants.items():
            files = dict(self.fixture.files)
            files[MANIFEST_NAME] = raw
            with self.subTest(mode=mode), TemporaryDirectory() as directory:
                with self.assertRaises(HandoffContractError):
                    self._validate(Path(directory), files)

    def test_v2_bundle_is_rejected(self) -> None:
        files = dict(self.fixture.files)
        first_name = self.fixture.manifest["days"][0]["bundle_relative_name"]
        files[first_name] = canonical_json_bytes(_v2_day_bundle(START_DAY))
        with TemporaryDirectory() as directory:
            with self.assertRaises(HandoffContractError):
                self._validate(Path(directory), files)

    def test_symlink_file_and_parent_are_rejected_when_supported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = self.fixture.materialize(root)
            name, target = inventory[-1]
            raw = target.read_bytes()
            target.unlink()
            elsewhere = root / "elsewhere.json"
            elsewhere.write_bytes(raw)
            try:
                target.symlink_to(elsewhere)
            except OSError as error:
                self.skipTest(f"symlinks unavailable: {error}")
            with self.assertRaises(HandoffContractError):
                validate_handoff_package(
                    root,
                    inventory,
                    expected_task_id=TASK_ID,
                    expected_task_sha256=TASK_SHA256,
                )
        with TemporaryDirectory() as directory:
            root = Path(directory)
            inventory = self.fixture.materialize(root)
            day_dir = root / "days" / START_DAY.isoformat()
            moved = root / "real-day"
            day_dir.rename(moved)
            try:
                day_dir.symlink_to(moved, target_is_directory=True)
            except OSError as error:
                self.skipTest(f"directory symlinks unavailable: {error}")
            with self.assertRaises(HandoffContractError):
                validate_handoff_package(
                    root,
                    inventory,
                    expected_task_id=TASK_ID,
                    expected_task_sha256=TASK_SHA256,
                )

    def test_result_create_is_exact_retry_without_rewrite(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            raw = _result_bytes(context)
            target, disposition = create_result_once(root, raw, context)
            self.assertEqual(RESULT_NAME, target.name)
            self.assertEqual("CREATED", disposition)
            first_stat = target.stat()
            target2, disposition2 = create_result_once(root, raw, context)
            self.assertEqual(target, target2)
            self.assertEqual("IDEMPOTENT_IDENTICAL", disposition2)
            self.assertEqual(first_stat.st_mtime_ns, target.stat().st_mtime_ns)

    def test_result_rejects_conflict_partial_noncanonical_and_duplicate_json(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            raw = _result_bytes(context)
            target = root / RESULT_NAME
            target.write_bytes(raw[:-1])
            with self.assertRaises(HandoffContractError):
                create_result_once(root, raw, context)
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            raw = _result_bytes(context)
            parsed = json.loads(raw)
            variants = (
                json.dumps(parsed, indent=2, sort_keys=True).encode(),
                raw.replace(b"{", b'{"schema_version":"1",', 1),
            )
            for changed in variants:
                with self.subTest():
                    with self.assertRaises(HandoffContractError):
                        validate_handoff_result_bytes(changed, context)

    def test_result_rejects_task_state_contract_hash_and_inference_drift(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            original = json.loads(_result_bytes(context))
            for mode in ("task", "state", "contract", "payload", "inference"):
                changed = deepcopy(original)
                if mode == "task":
                    changed["task_sha256"] = "0" * 64
                elif mode == "state":
                    changed["canonical_state"]["sha256"] = "0" * 64
                elif mode == "contract":
                    changed["diagnostic_contract"]["sha256"] = "0" * 64
                elif mode == "payload":
                    changed["diagnostic_payload_hashes"]["payload_sha256"] = "0" * 64
                else:
                    changed["inference_boundaries"]["candidate_authorized"] = True
                _seal(changed)
                with self.subTest(mode=mode):
                    with self.assertRaises(HandoffContractError):
                        validate_handoff_result_bytes(canonical_json_bytes(changed), context)

    def test_result_link_target_is_rejected_when_supported(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            context = self._validate(root)
            raw = _result_bytes(context)
            elsewhere = root / "elsewhere-result.json"
            elsewhere.write_bytes(raw)
            target = root / RESULT_NAME
            try:
                target.symlink_to(elsewhere)
            except OSError as error:
                self.skipTest(f"symlinks unavailable: {error}")
            with self.assertRaises(HandoffContractError):
                create_result_once(root, raw, context)

    def test_schema_documents_are_local_strict_json(self) -> None:
        package = Path(__file__).resolve().parents[1]
        for name in (
            "microstructure-handoff-manifest.v1.schema.json",
            "microstructure-handoff-result.v1.schema.json",
        ):
            value = json.loads((package / name).read_text(encoding="utf-8"))
            self.assertEqual("object", value["type"])
            self.assertFalse(value["additionalProperties"])
            self.assertTrue(str(value["$id"]).startswith("urn:"))
            self.assertNotIn("http", json.dumps(value.get("properties", {})))


if __name__ == "__main__":
    unittest.main()
