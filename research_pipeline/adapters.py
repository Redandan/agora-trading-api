from __future__ import annotations

import json
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .corpus import SELECTION_CORPUS_ID, SELECTION_CORPUS_RELATIVE_PATH


@dataclass(frozen=True)
class AdapterSpec:
    key: str
    runner: str
    initial_action: str
    supports_oos: bool
    selection_cutoff: str
    candidate_variants: int
    description: str
    forward_candidate_eligible: bool = False
    required_corpus_id: str | None = None


ADAPTERS = {
    "legacy-v12": AdapterSpec(
        key="legacy-v12",
        runner="btc_dra_v7_profit_peak_delayed_failed_reclaim_exit_v12.py",
        initial_action="preselect",
        supports_oos=True,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=2,
        description="V12 preregistered preselection and sealed OOS runner",
    ),
    "legacy-v3g-diagnostic": AdapterSpec(
        key="legacy-v3g-diagnostic",
        runner="btc_dra_flat_range_cooldown_preserving_upper_touch_feasibility_v3g.py",
        initial_action="diagnostic",
        supports_oos=False,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=3,
        description="V3G post-hoc diagnostic; never a candidate or OOS",
    ),
    "flat-veto-cooldown-v2b": AdapterSpec(
        key="flat-veto-cooldown-v2b",
        runner="btc_dra_flat_regime_stale_inventory_veto_cooldown_v2b.py",
        initial_action="diagnostic",
        supports_oos=False,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=1,
        description="Frozen V2B cooldown-preserving post-hoc historical diagnostic",
    ),
    "java-dra-v1-parity": AdapterSpec(
        key="java-dra-v1-parity",
        runner="java_dra_v1_parity_adapter.py",
        initial_action="diagnostic",
        supports_oos=False,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=1,
        description="Offline Java DRA V1 Phase-A exact checkpoint parity",
    ),
    "java-dra-v1-economic-ledger": AdapterSpec(
        key="java-dra-v1-economic-ledger",
        runner="java_dra_v1_economic_ledger_adapter.py",
        initial_action="diagnostic",
        supports_oos=False,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=1,
        description="Offline Java/Python DRA V1 Phase-B economic-ledger parity",
    ),
    "one-slot-signal-rotation-v1": AdapterSpec(
        key="one-slot-signal-rotation-v1",
        runner="btc_dra_one_slot_profitable_incumbent_signal_rotation_v1.py",
        initial_action="diagnostic",
        supports_oos=False,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=1,
        description="Frozen one-slot profitable-incumbent fresh-signal rotation diagnostic",
    ),
    "dra-forward-entry-admission-v1": AdapterSpec(
        key="dra-forward-entry-admission-v1",
        runner="btc_dra_forward_entry_admission_v1.py",
        initial_action="preselect",
        supports_oos=True,
        selection_cutoff="2025-01-01T00:00:00Z",
        candidate_variants=3,
        description=(
            "Evidence-bound DRA V1 volume/range entry-admission candidate with "
            "one primary threshold, two frozen neighbors, and sealed future OOS"
        ),
        forward_candidate_eligible=True,
        required_corpus_id=SELECTION_CORPUS_ID,
    ),
}


@dataclass(frozen=True)
class AdapterRun:
    command: list[str]
    artifact_path: Path
    stdout_path: Path
    stderr_path: Path


def require_adapter(key: str) -> AdapterSpec:
    try:
        return ADAPTERS[key]
    except KeyError as error:
        raise ValueError(f"unknown research adapter: {key}") from error


def validate_adapter_manifest(manifest: dict[str, Any]) -> None:
    spec = require_adapter(str(manifest["adapter"]))
    actual_cutoff = datetime.fromisoformat(
        str(manifest["selection_cutoff"]).replace("Z", "+00:00")
    ).astimezone(timezone.utc)
    expected_cutoff = datetime.fromisoformat(
        spec.selection_cutoff.replace("Z", "+00:00")
    ).astimezone(timezone.utc)
    if actual_cutoff != expected_cutoff:
        raise ValueError(
            f"{spec.key} selection_cutoff is frozen at {spec.selection_cutoff}"
        )
    if int(manifest["max_variants"]) != spec.candidate_variants:
        raise ValueError(
            f"{spec.key} has exactly {spec.candidate_variants} frozen variants"
        )
    oos_cutoff = manifest.get("oos_cutoff")
    if not spec.supports_oos and oos_cutoff is not None:
        raise ValueError(f"{spec.key} is diagnostic-only and cannot define OOS")
    if oos_cutoff is not None:
        parsed_oos = datetime.fromisoformat(str(oos_cutoff).replace("Z", "+00:00"))
        if parsed_oos <= actual_cutoff:
            raise ValueError("oos_cutoff must be after selection_cutoff")


def next_action(manifest: dict[str, Any], state: dict[str, Any]) -> str | None:
    spec = require_adapter(manifest["adapter"])
    if state["stage"] == "PREREGISTERED":
        return spec.initial_action
    if state["stage"] == "OOS_READY":
        if not spec.supports_oos or not manifest.get("oos_cutoff"):
            return None
        return "oos"
    return None


def build_run(
    repo_root: Path,
    artifact_dir: Path,
    manifest: dict[str, Any],
    state: dict[str, Any],
) -> AdapterRun:
    spec = require_adapter(manifest["adapter"])
    runner = repo_root / "research" / spec.runner
    if not runner.is_file():
        raise ValueError(f"runner not found: {runner}")
    action = next_action(manifest, state)
    if action is None:
        raise ValueError(f"no executable action for stage {state['stage']}")
    artifact_path = artifact_dir / f"{action}.json"
    if artifact_path.exists():
        raise ValueError(f"sealed artifact already exists: {artifact_path}")
    command = [sys.executable, str(runner)]
    if spec.key == "legacy-v12" and action == "preselect":
        command.extend(["preselect", "--output", str(artifact_path)])
    elif spec.key == "legacy-v12" and action == "oos":
        preselect = artifact_dir / "preselect.json"
        if not preselect.is_file():
            raise ValueError("sealed preselect artifact is missing")
        command.extend(
            [
                "oos",
                "--preselect",
                str(preselect),
                "--cutoff",
                str(manifest["oos_cutoff"]),
                "--output",
                str(artifact_path),
            ]
        )
    elif spec.key == "legacy-v3g-diagnostic" and action == "diagnostic":
        command.extend(["--output", str(artifact_path)])
    elif spec.key == "flat-veto-cooldown-v2b" and action == "diagnostic":
        command.extend(["--output", str(artifact_path)])
    elif spec.key == "java-dra-v1-parity" and action == "diagnostic":
        command.extend(["--output", str(artifact_path)])
    elif spec.key == "java-dra-v1-economic-ledger" and action == "diagnostic":
        command.extend(["--output", str(artifact_path)])
    elif spec.key == "one-slot-signal-rotation-v1" and action == "diagnostic":
        command.extend(["--output", str(artifact_path)])
    elif spec.key == "dra-forward-entry-admission-v1" and action == "preselect":
        state_root = artifact_dir.parents[2]
        manifest_path = artifact_dir.parent / "manifest.json"
        command.extend(
            [
                "preselect",
                "--manifest",
                str(manifest_path),
                "--input",
                str(state_root / SELECTION_CORPUS_RELATIVE_PATH),
                "--output",
                str(artifact_path),
            ]
        )
    elif spec.key == "dra-forward-entry-admission-v1" and action == "oos":
        state_root = artifact_dir.parents[2]
        preselect = artifact_dir / "preselect.json"
        if not preselect.is_file():
            raise ValueError("sealed preselect artifact is missing")
        oos_dataset = Path(str(state.get("oos_dataset_path", ""))).resolve()
        try:
            oos_dataset.relative_to(state_root.resolve())
        except ValueError as error:
            raise ValueError("sealed OOS dataset path escapes research state") from error
        if not oos_dataset.is_file():
            raise ValueError("sealed OOS dataset is not ready")
        command.extend(
            [
                "oos",
                "--manifest",
                str(artifact_dir.parent / "manifest.json"),
                "--preselect",
                str(preselect),
                "--input",
                str(oos_dataset),
                "--output",
                str(artifact_path),
            ]
        )
    else:
        raise ValueError(f"unsupported adapter action: {spec.key}/{action}")
    return AdapterRun(
        command=command,
        artifact_path=artifact_path,
        stdout_path=artifact_dir / f"{action}.stdout.log",
        stderr_path=artifact_dir / f"{action}.stderr.log",
    )


def execute(run: AdapterRun, repo_root: Path, timeout_seconds: int) -> tuple[int, dict[str, Any]]:
    process = subprocess.run(
        run.command,
        cwd=repo_root / "research",
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout_seconds,
        shell=False,
    )
    run.stdout_path.write_text(process.stdout, encoding="utf-8")
    run.stderr_path.write_text(process.stderr, encoding="utf-8")
    if not run.artifact_path.is_file():
        raise RuntimeError(
            f"runner produced no artifact (exit={process.returncode}); see {run.stderr_path}"
        )
    result = json.loads(run.artifact_path.read_text(encoding="utf-8"))
    if not isinstance(result, dict):
        raise RuntimeError("runner artifact must be a JSON object")
    return process.returncode, result


def classify_result(manifest: dict[str, Any], result: dict[str, Any]) -> tuple[str, str]:
    status = str(result.get("status", "UNKNOWN"))
    if status == "CANDIDATE_FROZEN":
        if not manifest.get("oos_cutoff"):
            return "BLOCKED", "CANDIDATE_FROZEN_WITHOUT_OOS_CUTOFF"
        return "OOS_READY", status
    if status in {"OUT_OF_SAMPLE_PASS", "OUT_OF_SAMPLE_FAIL"}:
        return "CLOSED", status
    if status == "HISTORICAL_GATE_PASS_NO_CLEAN_OOS":
        return "CLOSED", status
    if status in {
        "JAVA_PARITY_PASS_RESEARCH_ONLY",
        "JAVA_PARITY_REJECT",
        "JAVA_LEDGER_PARITY_PASS_RESEARCH_ONLY",
        "JAVA_LEDGER_PARITY_REJECT",
    }:
        return "CLOSED", status
    if status.startswith("NO_CANDIDATE") or status in {
        "NO_NEXT_HYPOTHESIS",
        "NEXT_HYPOTHESIS_IDENTIFIED_POST_HOC",
        "DATA_REJECT",
        "LEAKAGE_REJECT",
        "BASELINE_REJECT",
        "ACCOUNTING_REJECT",
        "OUTPUT_SEAL_REJECT",
        "OOS_SEAL_REJECT",
    }:
        return "CLOSED", status
    return "FAILED", status
