#!/usr/bin/env python3
"""Approved adapter for the offline Java DRA V1 Phase-A parity CLI."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import btc_dra_reversal_confirmed_exit_v2c as base
import export_java_dra_parity_input as exporter


ROOT = Path(__file__).resolve().parents[1]
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
EXPECTED_SOURCES = {
    "specification": (
        ROOT / "docs" / "java-dra-research-cli-parity-v1.md",
        "5ea376408463b583670983e511764976405537a573af9478f173a18164428d4a",
    ),
    "java_cli": (
        ROOT / "src" / "main" / "java" / "com" / "agora" / "research" / "BtcDraResearchCli.java",
        "0175f65fc277160461423d382da55d694866741e670518c54da9338e2590338f",
    ),
    "data_exporter": (
        ROOT / "research" / "export_java_dra_parity_input.py",
        "8b680c95bc98d5d4e0b532d8126c39a8f64de1b5490e37948ba3b4b91ec30c6c",
    ),
    "java_engine": (
        ROOT / "src" / "main" / "java" / "com" / "agora" / "service" / "trading" / "BtcDraShadowEngine.java",
        "a6b60d084cc6decb29e3640e851f7f2ef0579b92c05dd36ad70b2b41c2e62dde",
    ),
    "java_policy": (
        ROOT / "src" / "main" / "java" / "com" / "agora" / "service" / "trading" / "BtcDraPolicy.java",
        "bdc100c84306ac64826b601d01ff2b86e2741763067bffe9014fc1eaf6241463",
    ),
    "maven_project": (
        ROOT / "pom.xml",
        "36edea64057794ae49ba0c4cc47385afe2410fbdd9fa9d61b0c1c57f74104235",
    ),
}


class ParityReject(RuntimeError):
    def __init__(self, status: str, detail: object):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify_sources() -> dict[str, dict[str, str]]:
    evidence: dict[str, dict[str, str]] = {}
    mismatches = []
    for name, (path, expected) in EXPECTED_SOURCES.items():
        if not path.is_file():
            mismatches.append({"source": name, "reason": "MISSING", "path": str(path)})
            continue
        actual = sha256(path)
        evidence[name] = {"sha256": actual, "path": str(path.relative_to(ROOT))}
        if actual != expected:
            mismatches.append(
                {"source": name, "expected_sha256": expected, "actual_sha256": actual}
            )
    if mismatches:
        raise ParityReject("BASELINE_REJECT", {"source_mismatches": mismatches})
    return evidence


def run_logged(command: list[str], log_prefix: Path, timeout: int) -> subprocess.CompletedProcess:
    process = subprocess.run(
        command,
        cwd=ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        timeout=timeout,
        shell=False,
    )
    log_prefix.with_suffix(".stdout.log").write_text(process.stdout, encoding="utf-8")
    log_prefix.with_suffix(".stderr.log").write_text(process.stderr, encoding="utf-8")
    return process


def require_java_21(work_dir: Path) -> tuple[Path, str]:
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise RuntimeError("JAVA_HOME is required for the offline Java parity adapter")
    executable = Path(java_home) / "bin" / ("java.exe" if os.name == "nt" else "java")
    if not executable.is_file():
        raise RuntimeError(f"JAVA_HOME java executable not found: {executable}")
    version = run_logged([str(executable), "-version"], work_dir / "java-version", 30)
    version_text = (version.stdout + version.stderr).strip()
    if version.returncode != 0 or not re.search(r'(?:version ")?21(?:[.\s])', version_text):
        raise RuntimeError(f"Java 21 is required; observed: {version_text}")
    return executable, version_text.splitlines()[0]


def run(output: Path) -> dict:
    if output.exists():
        raise ParityReject("OUTPUT_SEAL_REJECT", str(output))
    work_dir = output.parent / "java-dra-v1-parity-work"
    if work_dir.exists():
        raise ParityReject("OUTPUT_SEAL_REJECT", str(work_dir))
    work_dir.mkdir(parents=True)

    source_evidence = verify_sources()
    canonical_input = work_dir / "canonical-input.tsv"
    export_result = exporter.export(canonical_input)

    maven = shutil.which("mvn")
    if not maven:
        raise RuntimeError("Maven executable not found")
    java, java_version = require_java_21(work_dir)
    package = run_logged(
        [maven, "-q", "-DskipTests", "package"],
        work_dir / "maven-package",
        1800,
    )
    if package.returncode != 0:
        raise RuntimeError("Maven package failed; inspect preserved adapter logs")

    classpath_file = work_dir / "dependency-classpath.txt"
    classpath = run_logged(
        [
            maven,
            "-q",
            "dependency:build-classpath",
            f"-Dmdep.outputFile={classpath_file.as_posix()}",
        ],
        work_dir / "maven-classpath",
        600,
    )
    if classpath.returncode != 0 or not classpath_file.is_file():
        raise RuntimeError("Maven dependency classpath generation failed")

    runtime_classpath = os.pathsep.join(
        [
            str((ROOT / "target" / "classes").resolve()),
            classpath_file.read_text(encoding="utf-8").strip(),
        ]
    )
    java_output = work_dir / "java-result.json"
    java_process = run_logged(
        [
            str(java),
            "-cp",
            runtime_classpath,
            "com.agora.research.BtcDraResearchCli",
            "--input",
            str(canonical_input),
            "--output",
            str(java_output),
        ],
        work_dir / "java-cli",
        1800,
    )
    if not java_output.is_file():
        raise RuntimeError(
            f"Java CLI produced no sealed result (exit={java_process.returncode})"
        )
    java_result = json.loads(java_output.read_text(encoding="utf-8"))
    status = str(java_result.get("status", "JAVA_PARITY_REJECT"))
    design_parity = bool(
        java_result.get("windows", {}).get("design", {}).get("checkpoint_parity")
    )
    validation_parity = bool(
        java_result.get("windows", {}).get("validation", {}).get("checkpoint_parity")
    )
    if java_process.returncode not in {0, 2}:
        raise RuntimeError(f"Java CLI infrastructure failure (exit={java_process.returncode})")

    result = {
        "schema_version": "JAVA_DRA_PARITY_ADAPTER_V1",
        "status": status,
        "authorization": AUTHORIZATION,
        "data_quality": "PASS",
        "baseline_parity": (
            "PASS_DESIGN_VALIDATION_EXACT"
            if design_parity and validation_parity
            else "JAVA_PARITY_REJECT"
        ),
        "java_phase": "PHASE_A_EXACT_CHECKPOINTS",
        "design_checkpoint_parity": design_parity,
        "validation_checkpoint_parity": validation_parity,
        "mandatory_gate": False,
        "oos_opened": False,
        "engine": java_result.get("engine"),
        "policy": java_result.get("policy"),
        "selection_data_rows": export_result["rows"],
        "selection_data_sha256": export_result["sha256"],
        "selection_data": {
            "source": "server-local md_kline OKX BTCUSDT 1h complete bars",
            "cutoff": "2025-01-01T00:00:00Z",
            "rows": export_result["rows"],
            "sha256": export_result["sha256"],
        },
        "java_version": java_version,
        "source_evidence": source_evidence,
        "adapter_sha256": sha256(Path(__file__)),
        "java_result_sha256": sha256(java_output),
        "java_result": java_result,
        "phase_b_required": java_result.get("phase_b_required", []),
    }
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    return result


def write_reject(output: Path, error: ParityReject) -> None:
    if output.exists():
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "schema_version": "JAVA_DRA_PARITY_ADAPTER_V1",
                "status": error.status,
                "authorization": AUTHORIZATION,
                "detail": error.detail,
            },
            ensure_ascii=False,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.output)
    except ParityReject as error:
        write_reject(args.output, error)
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    except base.ResearchReject as error:
        reject = ParityReject(error.status, error.detail)
        write_reject(args.output, reject)
        print(json.dumps({"status": error.status, "detail": error.detail}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "design_checkpoint_parity": result["design_checkpoint_parity"],
                "validation_checkpoint_parity": result["validation_checkpoint_parity"],
            },
            ensure_ascii=False,
        )
    )
    return 0 if result["status"] == "JAVA_PARITY_PASS_RESEARCH_ONLY" else 2


if __name__ == "__main__":
    raise SystemExit(main())
