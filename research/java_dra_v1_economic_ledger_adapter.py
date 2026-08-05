#!/usr/bin/env python3
"""Approved adapter for Java/Python DRA V1 Phase B economic-ledger parity."""

from __future__ import annotations

import argparse
import hashlib
import itertools
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
SUCCESS = "JAVA_LEDGER_PARITY_PASS_RESEARCH_ONLY"
REJECT = "JAVA_LEDGER_PARITY_REJECT"
EXPECTED_SOURCES = {
    "specification": (
        ROOT / "docs" / "java-dra-cross-language-economic-ledger-v2.md",
        "ad625fba367ecb96b109aaf74fd34a451f8ea039d55dc09668e2649689f1f314",
    ),
    "python_ledger": (
        ROOT / "research" / "python_dra_v1_economic_ledger.py",
        "764caf1b1a21fe4419267c7e6d1ca8c57158422260d6ab7c238fb58f506b6c59",
    ),
    "java_ledger_cli": (
        ROOT
        / "src"
        / "main"
        / "java"
        / "com"
        / "agora"
        / "research"
        / "BtcDraEconomicLedgerParityCli.java",
        "f9d25ebb18ef0809d087d926360fa7b90368d1281523794865c69fc08dac4375",
    ),
    "data_exporter": (
        ROOT / "research" / "export_java_dra_parity_input.py",
        "8b680c95bc98d5d4e0b532d8126c39a8f64de1b5490e37948ba3b4b91ec30c6c",
    ),
    "python_reference_engine": (
        ROOT / "research" / "btc_dra_reversal_confirmed_exit_v2c.py",
        "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
    ),
    "java_engine": (
        ROOT
        / "src"
        / "main"
        / "java"
        / "com"
        / "agora"
        / "service"
        / "trading"
        / "BtcDraShadowEngine.java",
        "a6b60d084cc6decb29e3640e851f7f2ef0579b92c05dd36ad70b2b41c2e62dde",
    ),
    "java_policy": (
        ROOT
        / "src"
        / "main"
        / "java"
        / "com"
        / "agora"
        / "service"
        / "trading"
        / "BtcDraPolicy.java",
        "bdc100c84306ac64826b601d01ff2b86e2741763067bffe9014fc1eaf6241463",
    ),
    "maven_project": (
        ROOT / "pom.xml",
        "36edea64057794ae49ba0c4cc47385afe2410fbdd9fa9d61b0c1c57f74104235",
    ),
}
LEDGERS = {
    "events": "events.tsv",
    "fills": "fills.tsv",
    "states": "states.tsv",
    "terminal_lots": "lots.tsv",
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
    mismatches: list[dict[str, str]] = []
    for name, (path, expected) in EXPECTED_SOURCES.items():
        if not path.is_file():
            mismatches.append({"source": name, "reason": "MISSING", "path": str(path)})
            continue
        actual = sha256(path)
        evidence[name] = {"path": str(path.relative_to(ROOT)), "sha256": actual}
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


def first_difference(python_path: Path, java_path: Path) -> dict[str, object] | None:
    with python_path.open(encoding="utf-8") as python_file, java_path.open(
        encoding="utf-8"
    ) as java_file:
        for line_number, (python_line, java_line) in enumerate(
            itertools.zip_longest(python_file, java_file), start=1
        ):
            if python_line != java_line:
                return {
                    "line": line_number,
                    "python": None if python_line is None else python_line.rstrip("\n")[:1000],
                    "java": None if java_line is None else java_line.rstrip("\n")[:1000],
                }
    return None


def compare_results(
    python_result: dict,
    java_result: dict,
    python_dir: Path,
    java_dir: Path,
) -> tuple[dict[str, object], list[dict[str, object]]]:
    comparison: dict[str, object] = {}
    mismatches: list[dict[str, object]] = []
    for window in ("design", "validation"):
        python_window = python_result["windows"][window]
        java_window = java_result["windows"][window]
        window_comparison: dict[str, object] = {
            "checkpoint_parity": bool(
                python_window.get("checkpoint_parity")
                and java_window.get("checkpoint_parity")
            ),
            "event_counts_parity": (
                python_window.get("event_counts") == java_window.get("event_counts")
            ),
        }
        if not window_comparison["checkpoint_parity"]:
            mismatches.append({"window": window, "surface": "checkpoint"})
        if not window_comparison["event_counts_parity"]:
            mismatches.append(
                {
                    "window": window,
                    "surface": "event_counts",
                    "python": python_window.get("event_counts"),
                    "java": java_window.get("event_counts"),
                }
            )
        for ledger, file_name in LEDGERS.items():
            parity = python_window.get(ledger) == java_window.get(ledger)
            window_comparison[f"{ledger}_parity"] = parity
            window_comparison[ledger] = python_window.get(ledger)
            if not parity:
                mismatches.append(
                    {
                        "window": window,
                        "surface": ledger,
                        "python": python_window.get(ledger),
                        "java": java_window.get(ledger),
                        "first_difference": first_difference(
                            python_dir / window / file_name,
                            java_dir / window / file_name,
                        ),
                    }
                )
        comparison[window] = window_comparison
    return comparison, mismatches


def run(output: Path) -> dict[str, object]:
    if output.exists():
        raise ParityReject("OUTPUT_SEAL_REJECT", str(output))
    work_dir = output.parent / "java-dra-v1-phase-b-work"
    if work_dir.exists():
        raise ParityReject("OUTPUT_SEAL_REJECT", str(work_dir))
    work_dir.mkdir(parents=True)

    source_evidence = verify_sources()
    canonical_input = work_dir / "canonical-input.tsv"
    export_result = exporter.export(canonical_input)

    python_dir = work_dir / "python-ledger"
    python_process = run_logged(
        [
            sys.executable,
            str(ROOT / "research" / "python_dra_v1_economic_ledger.py"),
            "--input",
            str(canonical_input),
            "--output-dir",
            str(python_dir),
        ],
        work_dir / "python-ledger",
        1800,
    )
    python_result_path = python_dir / "result.json"
    if python_process.returncode != 0 or not python_result_path.is_file():
        raise RuntimeError("Python ledger generation failed; inspect preserved logs")

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
        raise RuntimeError("Maven package failed; inspect preserved logs")

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

    java_dir = work_dir / "java-ledger"
    java_process = run_logged(
        [
            str(java),
            "-cp",
            runtime_classpath,
            "com.agora.research.BtcDraEconomicLedgerParityCli",
            "--input",
            str(canonical_input),
            "--output-dir",
            str(java_dir),
        ],
        work_dir / "java-ledger",
        1800,
    )
    java_result_path = java_dir / "result.json"
    if java_process.returncode != 0 or not java_result_path.is_file():
        raise RuntimeError("Java ledger generation failed; inspect preserved logs")

    python_result = json.loads(python_result_path.read_text(encoding="utf-8"))
    java_result = json.loads(java_result_path.read_text(encoding="utf-8"))
    comparison, mismatches = compare_results(
        python_result, java_result, python_dir, java_dir
    )
    status = SUCCESS if not mismatches else REJECT
    result: dict[str, object] = {
        "schema_version": "JAVA_DRA_ECONOMIC_LEDGER_ADAPTER_V2",
        "status": status,
        "authorization": AUTHORIZATION,
        "data_quality": "PASS",
        "baseline_parity": (
            "PASS_DESIGN_VALIDATION_EXACT" if not mismatches else REJECT
        ),
        "java_phase": "PHASE_B_BASELINE_ECONOMIC_LEDGER",
        "cross_language_event_parity": not any(
            item["surface"] in {"events", "event_counts"} for item in mismatches
        ),
        "cross_language_fill_parity": not any(
            item["surface"] == "fills" for item in mismatches
        ),
        "cross_language_state_parity": not any(
            item["surface"] == "states" for item in mismatches
        ),
        "cross_language_lot_parity": not any(
            item["surface"] == "terminal_lots" for item in mismatches
        ),
        "mandatory_gate": False,
        "oos_opened": False,
        "engine": java_result.get("engine"),
        "policy": java_result.get("policy"),
        "selection_data_rows": export_result["rows"],
        "selection_data_sha256": export_result["sha256"],
        "java_version": java_version,
        "source_evidence": source_evidence,
        "adapter_sha256": sha256(Path(__file__)),
        "python_result_sha256": sha256(python_result_path),
        "java_result_sha256": sha256(java_result_path),
        "windows": comparison,
        "mismatches": mismatches,
        "next_required": "PHASE_C_REPRESENTATIVE_COMPLEX_OVERLAY_PARITY",
    }
    output.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return result


def write_reject(output: Path, error: ParityReject) -> None:
    if output.exists():
        return
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(
            {
                "schema_version": "JAVA_DRA_ECONOMIC_LEDGER_ADAPTER_V2",
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
        newline="\n",
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.output)
    except ParityReject as error:
        write_reject(args.output, error)
        print(json.dumps({"status": error.status, "detail": error.detail}))
        return 2
    except base.ResearchReject as error:
        reject = ParityReject(error.status, error.detail)
        write_reject(args.output, reject)
        print(json.dumps({"status": error.status, "detail": error.detail}))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output": str(args.output.resolve()),
                "event_parity": result["cross_language_event_parity"],
                "fill_parity": result["cross_language_fill_parity"],
                "state_parity": result["cross_language_state_parity"],
                "lot_parity": result["cross_language_lot_parity"],
            }
        )
    )
    return 0 if result["status"] == SUCCESS else 2


if __name__ == "__main__":
    raise SystemExit(main())
