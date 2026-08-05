from __future__ import annotations

from typing import Any

from .models import now_utc


EVIDENCE_FIELDS = (
    "data_quality",
    "baseline_parity",
    "qualified_count",
    "selected_candidate",
    "oos_opened",
    "selection_data_rows",
    "selection_data_sha256",
    "next_hypothesis",
    "contamination_status",
    "authorization",
    "java_phase",
    "design_checkpoint_parity",
    "validation_checkpoint_parity",
    "cross_language_event_parity",
    "cross_language_fill_parity",
    "cross_language_state_parity",
    "cross_language_lot_parity",
    "next_required",
    "one_slot_cap_usdt",
    "annual_total_wins",
    "annual_drawdown_nonworse",
    "mandatory_gate",
    "engine",
    "policy",
)


def build_learning(
    manifest: dict[str, Any],
    result: dict[str, Any],
    outcome: str,
) -> dict[str, Any]:
    if outcome.startswith("NO_CANDIDATE"):
        conclusion = "No candidate satisfied the frozen gates; retain the parent strategy."
        disposition = "DO_NOT_REPEAT_WITH_RELAXED_GATES"
    elif outcome in {"DATA_REJECT", "LEAKAGE_REJECT", "BASELINE_REJECT"}:
        conclusion = "Evidence integrity failed before a performance conclusion was allowed."
        disposition = "REPAIR_EVIDENCE_BEFORE_NEW_HYPOTHESIS"
    elif outcome == "OUT_OF_SAMPLE_FAIL":
        conclusion = "The frozen candidate failed sealed OOS and is not eligible for activation."
        disposition = "CLOSE_BRANCH"
    elif outcome == "OUT_OF_SAMPLE_PASS":
        conclusion = "The candidate passed research OOS but remains reported, not activated."
        disposition = "REPORTED_NOT_ACTIVATED"
    elif outcome == "NEXT_HYPOTHESIS_IDENTIFIED_POST_HOC":
        conclusion = "A diagnostic produced a new hypothesis, not a candidate or OOS result."
        disposition = "PREREGISTER_NEW_EXPERIMENT"
    elif outcome == "NO_NEXT_HYPOTHESIS":
        conclusion = "The diagnostic did not identify a hypothesis eligible for preregistration."
        disposition = "CLOSE_BRANCH"
    elif outcome == "HISTORICAL_GATE_PASS_NO_CLEAN_OOS":
        conclusion = "The frozen historical gates passed, but no uncontaminated OOS remains."
        disposition = "RETAIN_AS_HISTORICAL_LEARNING_NO_ACTIVATION"
    elif outcome == "JAVA_PARITY_PASS_RESEARCH_ONLY":
        conclusion = "Java reproduced every frozen Python DRA V1 Design and Validation checkpoint exactly."
        disposition = "ADVANCE_TO_PHASE_B_PARITY_NOT_MANDATORY"
    elif outcome == "JAVA_PARITY_REJECT":
        conclusion = "Java did not reproduce the frozen Python DRA V1 checkpoints exactly."
        disposition = "KEEP_PYTHON_AUTHORITY_AND_REPAIR_PARITY"
    elif outcome == "JAVA_LEDGER_PARITY_PASS_RESEARCH_ONLY":
        conclusion = (
            "Java and Python reproduced the same ordered DRA V1 events, fills, "
            "hourly economic states, and terminal lots in Design and Validation."
        )
        disposition = "ADVANCE_TO_PHASE_C_COMPLEX_OVERLAY_PARITY_NOT_MANDATORY"
    elif outcome == "JAVA_LEDGER_PARITY_REJECT":
        conclusion = (
            "Java and Python diverged on at least one frozen DRA V1 economic ledger."
        )
        disposition = "KEEP_PYTHON_AUTHORITY_AND_REPAIR_LEDGER_PARITY"
    else:
        conclusion = f"The experiment ended with outcome {outcome}."
        disposition = "REVIEW_EVIDENCE"
    evidence = {field: result.get(field) for field in EVIDENCE_FIELDS if field in result}
    selection_data = result.get("selection_data")
    if isinstance(selection_data, dict):
        evidence["selection_data"] = selection_data
    return {
        "schema_version": "1",
        "experiment_id": manifest["experiment_id"],
        "created_at": now_utc(),
        "outcome": outcome,
        "conclusion": conclusion,
        "disposition": disposition,
        "economic_rationale": manifest["economic_rationale"],
        "evidence": evidence,
        "authorization": "REPORTED_NOT_ACTIVATED",
    }
