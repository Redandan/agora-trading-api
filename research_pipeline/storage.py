from __future__ import annotations

import hashlib
import json
import os
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

from .models import ExperimentManifest, Stage, now_utc


class ResearchStore:
    def __init__(self, root: Path, *, lock_stale_seconds: int) -> None:
        self.root = root.resolve()
        self.experiments = self.root / "experiments"
        self.hypotheses = self.root / "hypotheses"
        self.evidence_triggers = self.root / "evidence-triggers"
        self.lock_path = self.root / "pipeline.lock"
        self.lock_stale_seconds = lock_stale_seconds

    def bootstrap(self) -> None:
        self.experiments.mkdir(parents=True, exist_ok=True)
        self.hypotheses.mkdir(parents=True, exist_ok=True)
        self.evidence_triggers.mkdir(parents=True, exist_ok=True)
        (self.root / "reports").mkdir(parents=True, exist_ok=True)

    def register(self, manifest: ExperimentManifest, policy_hash: str) -> dict[str, Any]:
        self.bootstrap()
        directory = self.experiment_dir(manifest.experiment_id)
        if directory.exists():
            raise ValueError(f"experiment already registered: {manifest.experiment_id}")
        directory.mkdir(parents=True)
        (directory / "artifacts").mkdir()
        manifest_path = directory / "manifest.json"
        atomic_write_json(manifest_path, manifest.to_dict())
        state = {
            "schema_version": "1",
            "experiment_id": manifest.experiment_id,
            "stage": Stage.PREREGISTERED.value,
            "outcome": None,
            "policy_sha256": policy_hash,
            "manifest_sha256": sha256_file(manifest_path),
            "created_at": now_utc(),
            "updated_at": now_utc(),
            "run_count": 0,
            "artifacts": {},
            "detail": None,
        }
        atomic_write_json(directory / "state.json", state)
        self.append_event(manifest.experiment_id, "REGISTERED", {"stage": state["stage"]})
        return state

    def experiment_dir(self, experiment_id: str) -> Path:
        return self.experiments / experiment_id

    def artifact_dir(self, experiment_id: str) -> Path:
        return self.experiment_dir(experiment_id) / "artifacts"

    def load_manifest(self, experiment_id: str) -> dict[str, Any]:
        return read_json(self.experiment_dir(experiment_id) / "manifest.json")

    def load_state(self, experiment_id: str) -> dict[str, Any]:
        return read_json(self.experiment_dir(experiment_id) / "state.json")

    def save_state(self, state: dict[str, Any]) -> None:
        state["updated_at"] = now_utc()
        atomic_write_json(self.experiment_dir(state["experiment_id"]) / "state.json", state)

    def entries(self) -> list[tuple[dict[str, Any], dict[str, Any]]]:
        self.bootstrap()
        result: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for directory in sorted(self.experiments.iterdir()):
            if not directory.is_dir():
                continue
            manifest_path = directory / "manifest.json"
            state_path = directory / "state.json"
            if manifest_path.exists() and state_path.exists():
                result.append((read_json(manifest_path), read_json(state_path)))
        return result

    def append_event(self, experiment_id: str, event_type: str, detail: dict[str, Any]) -> None:
        record = {
            "timestamp": now_utc(),
            "event_type": event_type,
            "detail": detail,
        }
        path = self.experiment_dir(experiment_id) / "events.jsonl"
        with path.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    def register_hypothesis(
        self,
        record: dict[str, Any],
        *,
        max_new_per_cycle: int,
        enforce_cycle_budget: bool = True,
    ) -> dict[str, Any]:
        self.bootstrap()
        path = self.hypothesis_path(str(record["hypothesis_id"]))
        if path.exists():
            raise ValueError(f"hypothesis already registered: {record['hypothesis_id']}")
        existing = self.hypothesis_entries()
        if any(item.get("fingerprint") == record.get("fingerprint") for item in existing):
            raise ValueError("duplicate hypothesis fingerprint")
        cycle_id = record["research_cycle_id"]
        if enforce_cycle_budget:
            cycle_count = sum(item.get("research_cycle_id") == cycle_id for item in existing)
            if cycle_count >= max_new_per_cycle:
                raise ValueError(
                    f"new hypothesis limit reached for cycle {cycle_id}: "
                    f"{cycle_count}/{max_new_per_cycle}"
                )
        atomic_write_json(path, record)
        self.append_hypothesis_event(
            str(record["hypothesis_id"]),
            "HYPOTHESIS_PROPOSED",
            {"status": record["status"], "fingerprint": record["fingerprint"]},
        )
        return record

    def hypothesis_path(self, hypothesis_id: str) -> Path:
        return self.hypotheses / f"{hypothesis_id}.json"

    def load_hypothesis(self, hypothesis_id: str) -> dict[str, Any]:
        return read_json(self.hypothesis_path(hypothesis_id))

    def save_hypothesis(self, record: dict[str, Any]) -> None:
        record["updated_at"] = now_utc()
        atomic_write_json(self.hypothesis_path(str(record["hypothesis_id"])), record)

    def hypothesis_entries(self) -> list[dict[str, Any]]:
        self.bootstrap()
        return [read_json(path) for path in sorted(self.hypotheses.glob("*.json"))]

    def append_hypothesis_event(
        self,
        hypothesis_id: str,
        event_type: str,
        detail: dict[str, Any],
    ) -> None:
        record = {
            "timestamp": now_utc(),
            "hypothesis_id": hypothesis_id,
            "event_type": event_type,
            "detail": detail,
        }
        path = self.root / "hypothesis-events.jsonl"
        with path.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    def register_evidence_trigger(self, record: dict[str, Any]) -> dict[str, Any]:
        self.bootstrap()
        directory = self.evidence_trigger_dir(str(record["trigger_id"]))
        if directory.exists():
            raise ValueError(f"evidence trigger already registered: {record['trigger_id']}")
        existing = self.evidence_trigger_entries()
        if any(spec.get("fingerprint") == record.get("fingerprint") for spec, _ in existing):
            raise ValueError("duplicate evidence trigger fingerprint")
        directory.mkdir(parents=True)
        (directory / "reviews").mkdir()
        trigger_path = directory / "trigger.json"
        atomic_write_json(trigger_path, record)
        state = {
            "schema_version": "1",
            "trigger_id": record["trigger_id"],
            "status": "WAITING",
            "next_review_at": record["review_not_before"],
            "review_count": 0,
            "reviews": [],
            "trigger_sha256": sha256_file(trigger_path),
            "created_at": now_utc(),
            "updated_at": now_utc(),
            "detail": None,
        }
        atomic_write_json(directory / "state.json", state)
        self.append_evidence_trigger_event(
            str(record["trigger_id"]),
            "EVIDENCE_TRIGGER_REGISTERED",
            {"status": "WAITING", "next_review_at": state["next_review_at"]},
        )
        return state

    def evidence_trigger_dir(self, trigger_id: str) -> Path:
        return self.evidence_triggers / trigger_id

    def evidence_review_dir(self, trigger_id: str) -> Path:
        return self.evidence_trigger_dir(trigger_id) / "reviews"

    def load_evidence_trigger(self, trigger_id: str) -> dict[str, Any]:
        return read_json(self.evidence_trigger_dir(trigger_id) / "trigger.json")

    def load_evidence_trigger_state(self, trigger_id: str) -> dict[str, Any]:
        return read_json(self.evidence_trigger_dir(trigger_id) / "state.json")

    def save_evidence_trigger_state(self, state: dict[str, Any]) -> None:
        state["updated_at"] = now_utc()
        atomic_write_json(
            self.evidence_trigger_dir(str(state["trigger_id"])) / "state.json",
            state,
        )

    def evidence_trigger_entries(self) -> list[tuple[dict[str, Any], dict[str, Any]]]:
        self.bootstrap()
        result: list[tuple[dict[str, Any], dict[str, Any]]] = []
        for directory in sorted(self.evidence_triggers.iterdir()):
            if not directory.is_dir():
                continue
            trigger_path = directory / "trigger.json"
            state_path = directory / "state.json"
            if trigger_path.exists() and state_path.exists():
                result.append((read_json(trigger_path), read_json(state_path)))
        return result

    def append_evidence_trigger_event(
        self,
        trigger_id: str,
        event_type: str,
        detail: dict[str, Any],
    ) -> None:
        record = {
            "timestamp": now_utc(),
            "trigger_id": trigger_id,
            "event_type": event_type,
            "detail": detail,
        }
        path = self.evidence_trigger_dir(trigger_id) / "events.jsonl"
        with path.open("a", encoding="utf-8", newline="\n") as stream:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")

    def ensure_evidence_trigger_event(
        self,
        trigger_id: str,
        event_type: str,
        detail: dict[str, Any],
        *,
        identity_fields: set[str] | None = None,
    ) -> dict[str, Any]:
        """Append one lifecycle event atomically, or verify its exact prior append."""
        path = self.evidence_trigger_dir(trigger_id) / "events.jsonl"
        existing = path.read_text(encoding="utf-8") if path.exists() else ""
        if existing and not existing.endswith("\n"):
            raise ValueError("evidence trigger event log has an incomplete final record")

        matching: list[dict[str, Any]] = []
        for line_number, line in enumerate(existing.splitlines(), start=1):
            try:
                record = json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(
                    f"evidence trigger event log record {line_number} is invalid"
                ) from error
            if not isinstance(record, dict) or record.get("trigger_id") != trigger_id:
                raise ValueError(
                    f"evidence trigger event log record {line_number} has invalid identity"
                )
            if record.get("event_type") == event_type:
                matching.append(record)

        if matching:
            stored_detail = matching[0].get("detail") if len(matching) == 1 else None
            if not isinstance(stored_detail, dict) or set(stored_detail) != set(detail):
                raise ValueError(
                    f"evidence trigger event {event_type} is duplicated or changed"
                )
            compared_fields = set(detail) if identity_fields is None else identity_fields
            if not compared_fields.issubset(detail) or any(
                stored_detail.get(field) != detail.get(field)
                for field in compared_fields
            ):
                raise ValueError(
                    f"evidence trigger event {event_type} is duplicated or changed"
                )
            return dict(stored_detail)

        record = {
            "timestamp": now_utc(),
            "trigger_id": trigger_id,
            "event_type": event_type,
            "detail": detail,
        }
        atomic_write_text(
            path,
            existing + json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n",
        )
        return dict(detail)

    @contextmanager
    def lock(self) -> Iterator[None]:
        self.bootstrap()
        self._assert_writable_authority()
        if self.lock_path.exists():
            age = time.time() - self.lock_path.stat().st_mtime
            if age <= self.lock_stale_seconds:
                raise RuntimeError(f"research pipeline is locked: {self.lock_path}")
            self.lock_path.unlink()
        descriptor = os.open(self.lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        try:
            os.write(descriptor, f"pid={os.getpid()} created_at={now_utc()}\n".encode())
            os.close(descriptor)
            yield
        finally:
            if self.lock_path.exists():
                self.lock_path.unlink()

    def _assert_writable_authority(self) -> None:
        authority_path = self.root / "authority.json"
        if not authority_path.exists():
            return
        authority = read_json(authority_path)
        mode = authority.get("mode")
        if mode == "REMOTE_READ_ONLY_REPLICA":
            canonical = authority.get("canonical_state") or "the server research worker"
            raise RuntimeError(
                "local research state is a read-only replica; "
                f"canonical writer is {canonical}"
            )
        if mode != "SERVER_CANONICAL":
            raise RuntimeError(f"unknown research state authority mode: {mode}")


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        json.dump(value, stream, ensure_ascii=False, indent=2, sort_keys=True)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def atomic_write_text(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + f".{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(content)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
