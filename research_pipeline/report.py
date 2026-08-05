from __future__ import annotations

from collections import Counter
from datetime import datetime, timedelta, timezone
from decimal import Decimal
import json
from pathlib import Path
from typing import Any

from .hypotheses import select_next
from .waiting import effective_trigger_status


def weekly_report(
    entries: list[tuple[dict[str, Any], dict[str, Any]]],
    *,
    days: int,
    policy_id: str,
    state_root: Path,
    hypotheses: list[dict[str, Any]],
    evidence_triggers: list[tuple[dict[str, Any], dict[str, Any]]],
    as_of: datetime | None = None,
    report_period: str | None = None,
) -> str:
    current = (as_of or datetime.now(timezone.utc)).astimezone(timezone.utc)
    cutoff = current - timedelta(days=days)
    recent: list[tuple[dict[str, Any], dict[str, Any]]] = []
    for manifest, state in entries:
        updated = datetime.fromisoformat(str(state["updated_at"]).replace("Z", "+00:00"))
        if updated >= cutoff:
            recent.append((manifest, state))
    counts = Counter(state["stage"] for _, state in entries)
    hypothesis_counts = Counter(record["status"] for record in hypotheses)
    selected = select_next(hypotheses)
    trigger_counts = Counter(
        effective_trigger_status(state) for _, state in evidence_triggers
    )
    lines = [
        "# Autonomous Trading Research Weekly Brief",
        "",
        f"- Policy: `{policy_id}`",
        *([f"- Report period: `{report_period}`"] if report_period else []),
        f"- Window: last `{days}` days",
        f"- Registered experiments: `{len(entries)}`",
        f"- Active/OOS-ready: `{counts.get('PREREGISTERED', 0) + counts.get('OOS_READY', 0)}`",
        f"- Closed: `{counts.get('CLOSED', 0)}`",
        f"- Blocked/failed: `{counts.get('BLOCKED', 0) + counts.get('FAILED', 0)}`",
        f"- Hypotheses ready: `{hypothesis_counts.get('READY', 0)}`",
        f"- Hypotheses blocked on capability/data: `"
        f"{hypothesis_counts.get('BLOCKED_CAPABILITY', 0) + hypothesis_counts.get('BLOCKED_DATA', 0)}`",
        f"- Evidence waits / due / ready: `{trigger_counts.get('WAITING', 0)} / "
        f"{trigger_counts.get('REVIEW_DUE', 0)} / "
        f"{trigger_counts.get('READY_FOR_HYPOTHESIS', 0)}`",
        "",
        "## Material learning",
        "",
    ]
    if not recent:
        lines.append("No experiment changed during this reporting window.")
    for manifest, state in sorted(recent, key=lambda pair: pair[1]["updated_at"], reverse=True):
        lines.extend([f"### {manifest['title']}", ""])
        learning = load_learning(state_root, state)
        if learning:
            lines.extend(
                [
                    f"- Conclusion: {learning['conclusion']}",
                    f"- Disposition: `{learning['disposition']}`",
                ]
            )
            evidence = learning.get("evidence", {})
            for key in (
                "data_quality",
                "baseline_parity",
                "qualified_count",
                "selected_candidate",
                "oos_opened",
                "next_hypothesis",
                "contamination_status",
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
            ):
                if key in evidence:
                    lines.append(f"- {key}: `{evidence[key]}`")
            performance = performance_lines(load_result(state_root, state))
            lines.extend(performance)
        else:
            lines.extend(
                [
                    f"- State: `{state['stage']}`",
                    f"- Outcome: `{state.get('outcome') or 'PENDING'}`",
                ]
            )
        lines.extend(
            [
                f"- Thesis: {manifest['thesis']}",
                f"- Economic rationale: {manifest['economic_rationale']}",
                f"- Authorization: `{manifest['authorization']}`",
                "",
            ]
        )
    lines.extend(["## Next autonomous research question", ""])
    if selected:
        lines.extend(
            [
                f"- Hypothesis: `{selected['hypothesis_id']}` — {selected['title']}",
                f"- Mechanism: {selected['mechanism']}",
                f"- Economic rationale: {selected['economic_rationale']}",
                f"- Required capability: `{selected['required_capability']}`",
                "",
            ]
        )
    else:
        trigger = next_evidence_trigger(evidence_triggers)
        if trigger:
            spec, state, status = trigger
            lines.extend(
                [
                    f"- Evidence trigger: `{spec['trigger_id']}` — `{status}`",
                    f"- Rationale: {spec['rationale']}",
                    f"- Next review: `{state.get('next_review_at') or 'READY_NOW'}`",
                    f"- Sealed forward days: `{state.get('evidence_observation_count', 0)}/"
                    f"{spec['minimum_observations']}`; chain head "
                    f"`{str(state.get('evidence_chain_head') or 'NONE')}`.",
                    "",
                ]
            )
        else:
            lines.extend(
                [
                    "No hypothesis is currently both evidence-ready and executable.",
                    "",
                ]
            )
    blocked = [
        record
        for record in hypotheses
        if record["status"] in {"BLOCKED_CAPABILITY", "BLOCKED_DATA"}
    ]
    if blocked:
        lines.extend(["### Blocked research backlog", ""])
        for record in sorted(blocked, key=lambda item: (-item["rank_score"], item["hypothesis_id"])):
            lines.append(
                f"- `{record['hypothesis_id']}` — `{record['status']}`; "
                f"requires `{record['required_capability']}`"
            )
        lines.append("")
    lines.extend(
        [
            "## Interpretation prompts",
            "",
            "- What changed in matched-capital total PnL, drawdown, and inventory risk?",
            "- Which plausible mechanism was rejected, and what should not be tried again?",
            "- Is any apparent gain concentrated in one year, regime, or terminal open lot?",
            "- What is the next smallest causal hypothesis worth testing?",
            "",
            "All candidates remain `REPORTED_NOT_ACTIVATED`.",
            "",
        ]
    )
    return "\n".join(lines)


def monthly_report(
    entries: list[tuple[dict[str, Any], dict[str, Any]]],
    *,
    days: int,
    policy_id: str,
    state_root: Path,
    hypotheses: list[dict[str, Any]],
    evidence_triggers: list[tuple[dict[str, Any], dict[str, Any]]],
    as_of: datetime | None = None,
    report_period: str | None = None,
) -> str:
    """Render a program-level learning audit without aggregating unlike PnL ledgers."""
    current = (as_of or datetime.now(timezone.utc)).astimezone(timezone.utc)
    cutoff = current - timedelta(days=days)
    recent = [
        (manifest, state)
        for manifest, state in entries
        if datetime.fromisoformat(str(state["updated_at"]).replace("Z", "+00:00"))
        >= cutoff
    ]
    terminal = [
        (manifest, state)
        for manifest, state in entries
        if state.get("stage") in {"CLOSED", "BLOCKED", "FAILED"}
    ]
    learned = [
        (manifest, state, load_learning(state_root, state))
        for manifest, state in terminal
    ]
    captured = [item for item in learned if item[2] is not None]
    outcome_counts = Counter(
        str(state.get("outcome") or "PENDING") for _, state in recent
    )
    disposition_counts = Counter(
        str(learning.get("disposition"))
        for _, _, learning in captured
        if learning and learning.get("disposition")
    )
    post_hoc_count = sum(
        learning is not None
        and learning.get("evidence", {}).get("contamination_status")
        == "POST_HOC_HISTORICAL_NO_CLEAN_OOS"
        for _, _, learning in captured
    )
    oos_opened_count = sum(
        learning is not None
        and learning.get("evidence", {}).get("oos_opened") is True
        for _, _, learning in captured
    )
    selected = select_next(hypotheses)
    hypothesis_counts = Counter(str(record.get("status")) for record in hypotheses)
    trigger_counts = Counter(
        effective_trigger_status(state) for _, state in evidence_triggers
    )

    lines = [
        "# Autonomous Trading Research Monthly Learning Review",
        "",
        f"- Policy: `{policy_id}`",
        *([f"- Report period: `{report_period}`"] if report_period else []),
        f"- Window: last `{days}` days",
        f"- Experiments changed: `{len(recent)}`",
        f"- Program-wide terminal experiments: `{len(terminal)}`",
        f"- Sealed learning coverage: `{len(captured)}/{len(terminal)}`",
        f"- Ready hypotheses: `{hypothesis_counts.get('READY', 0)}`",
        f"- Blocked hypotheses: `"
        f"{hypothesis_counts.get('BLOCKED_CAPABILITY', 0) + hypothesis_counts.get('BLOCKED_DATA', 0)}`",
        f"- Evidence waits / due / ready: `{trigger_counts.get('WAITING', 0)} / "
        f"{trigger_counts.get('REVIEW_DUE', 0)} / "
        f"{trigger_counts.get('READY_FOR_HYPOTHESIS', 0)}`",
        "",
        "## Knowledge accumulated",
        "",
    ]
    if not recent:
        lines.append("No experiment changed during this reporting window.")
    else:
        for outcome, count in sorted(outcome_counts.items()):
            lines.append(f"- `{outcome}`: `{count}` experiment(s)")
    lines.extend(["", "## Hypothesis tree", ""])
    if not hypotheses:
        lines.append("No hypothesis has been registered.")
    else:
        by_parent: dict[str, list[dict[str, Any]]] = {}
        for record in hypotheses:
            by_parent.setdefault(str(record.get("parent") or "UNSPECIFIED"), []).append(record)
        for parent in sorted(by_parent):
            lines.append(f"### Parent: `{parent}`")
            lines.append("")
            for record in sorted(
                by_parent[parent],
                key=lambda item: (str(item.get("created_at", "")), str(item["hypothesis_id"])),
            ):
                outcome = record.get("outcome") or "PENDING"
                lines.append(
                    f"- `{record['hypothesis_id']}` — `{record['status']}` / `{outcome}`; "
                    f"{record['title']}"
                )
            lines.append("")
    lines.extend(["## Repeated failure modes and stop rules", ""])
    no_candidate_count = sum(
        str(state.get("outcome") or "").startswith("NO_CANDIDATE")
        for _, state in terminal
    )
    if no_candidate_count:
        lines.append(
            f"- `{no_candidate_count}` closed branch(es) produced `NO_CANDIDATE`; "
            "their frozen gates must not be relaxed or retuned post hoc."
        )
    if disposition_counts.get("DO_NOT_REPEAT_WITH_RELAXED_GATES"):
        lines.append(
            f"- `{disposition_counts['DO_NOT_REPEAT_WITH_RELAXED_GATES']}` learning record(s) "
            "explicitly prohibit repeating the same mechanism with relaxed gates."
        )
    if post_hoc_count:
        lines.append(
            f"- `{post_hoc_count}` branch(es) are post-hoc historical screens without clean OOS; "
            "they cannot support activation claims."
        )
    if not no_candidate_count and not post_hoc_count:
        lines.append("No repeated scientific rejection pattern was recorded yet.")
    lines.extend(["", "## Evidence and architecture convergence", ""])
    lines.append(f"- OOS opened by sealed learning records: `{oos_opened_count}`.")
    next_requirements = sorted(
        {
            str(learning["evidence"]["next_required"])
            for _, _, learning in captured
            if learning
            and isinstance(learning.get("evidence"), dict)
            and learning["evidence"].get("next_required")
        }
    )
    if next_requirements:
        for requirement in next_requirements:
            lines.append(f"- Outstanding convergence gate: `{requirement}`.")
    else:
        lines.append("- No explicit cross-language convergence gate is recorded.")
    if evidence_triggers:
        lines.extend(["", "### Prospective evidence waits", ""])
        for trigger, state in sorted(
            evidence_triggers,
            key=lambda pair: (str(pair[1].get("next_review_at") or ""), pair[0]["trigger_id"]),
        ):
            status = effective_trigger_status(state)
            next_review = state.get("next_review_at") or (
                "READY_NOW" if status == "READY_FOR_HYPOTHESIS" else "NONE"
            )
            lines.append(
                f"- `{trigger['trigger_id']}` — `{status}`; "
                f"next review `{next_review}`; "
                f"sealed `{state.get('evidence_observation_count', 0)}/"
                f"{trigger['minimum_observations']} {trigger['observation_unit']}`; "
                f"chain `{str(state.get('evidence_chain_head') or 'NONE')}`."
            )
    lines.extend(["", "## Next autonomous research question", ""])
    if selected:
        lines.extend(
            [
                f"- Hypothesis: `{selected['hypothesis_id']}` — {selected['title']}",
                f"- Mechanism: {selected['mechanism']}",
                f"- Economic rationale: {selected['economic_rationale']}",
            ]
        )
    else:
        trigger = next_evidence_trigger(evidence_triggers)
        if trigger:
            spec, state, status = trigger
            lines.append(
                f"Evidence trigger `{spec['trigger_id']}` is `{status}`; "
                f"next review is `{state.get('next_review_at') or 'READY_NOW'}`."
            )
        else:
            lines.append(
                "No hypothesis is currently both evidence-ready and executable; Codex must either "
                "formulate one new causal mechanism or record the external evidence trigger worth waiting for."
            )
    lines.extend(
        [
            "",
            "## Sponsor learning prompt",
            "",
            "- Which rejected mechanism most changed our view of opportunity cost or inventory risk?",
            "- Is the program learning from independent evidence, or only producing more historical runs?",
            "- What new evidence would justify the next hypothesis without reopening a closed branch?",
            "",
            "Results from unlike parents are not summed into a portfolio-performance claim.",
            "All candidates remain `REPORTED_NOT_ACTIVATED`.",
            "",
        ]
    )
    return "\n".join(lines)


def next_evidence_trigger(
    entries: list[tuple[dict[str, Any], dict[str, Any]]],
) -> tuple[dict[str, Any], dict[str, Any], str] | None:
    priority = {"READY_FOR_HYPOTHESIS": 0, "REVIEW_DUE": 1, "WAITING": 2}
    candidates = []
    for trigger, state in entries:
        status = effective_trigger_status(state)
        if status in priority:
            candidates.append((trigger, state, status))
    if not candidates:
        return None
    candidates.sort(
        key=lambda item: (
            priority[item[2]],
            str(item[1].get("next_review_at") or ""),
            item[0]["trigger_id"],
        )
    )
    return candidates[0]


def load_learning(state_root: Path, state: dict[str, Any]) -> dict[str, Any] | None:
    relative = state.get("artifacts", {}).get("learning")
    if not relative:
        return None
    path = state_root / relative
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else None


def load_result(state_root: Path, state: dict[str, Any]) -> dict[str, Any] | None:
    artifacts = state.get("artifacts", {})
    relative = next(
        (artifacts.get(key) for key in ("oos", "diagnostic", "preselect") if artifacts.get(key)),
        None,
    )
    if not relative:
        return None
    path = state_root / relative
    if not path.is_file():
        return None
    value = json.loads(path.read_text(encoding="utf-8"))
    return value if isinstance(value, dict) else None


def performance_lines(result: dict[str, Any] | None) -> list[str]:
    if not result:
        return []
    if result.get("schema_version") == "DRA_FORWARD_ENTRY_ADMISSION_RUNNER_V1":
        if isinstance(result.get("baseline"), dict):
            parent = result["baseline"].get("validation")
            primary_variant = next(
                (
                    item
                    for item in result.get("variants", [])
                    if isinstance(item, dict) and item.get("role") == "primary"
                ),
                None,
            )
            candidate = (
                primary_variant.get("validation")
                if isinstance(primary_variant, dict)
                else None
            )
            label = "Historical Validation"
        else:
            parent = result.get("parent")
            primary_variant = next(
                (
                    item
                    for item in result.get("variants", [])
                    if isinstance(item, dict) and item.get("role") == "primary"
                ),
                None,
            )
            candidate = (
                primary_variant.get("result")
                if isinstance(primary_variant, dict)
                else None
            )
            label = "Sealed OOS"
        if isinstance(parent, dict) and isinstance(candidate, dict):
            candidate_total = Decimal(candidate["total_pnl_usdt"])
            parent_total = Decimal(parent["total_pnl_usdt"])
            candidate_dd = Decimal(candidate["max_drawdown_pct"])
            parent_dd = Decimal(parent["max_drawdown_pct"])
            return [
                f"- {label} total PnL: `{candidate_total}` USDT "
                f"(parent `{parent_total}`; delta `{candidate_total - parent_total:+}`)",
                f"- {label} drawdown: `{candidate_dd}%` "
                f"(parent `{parent_dd}%`; delta `{candidate_dd - parent_dd:+}%`)",
                f"- Realized / unrealized: `{candidate['realized_usdt']}` / "
                f"`{candidate['unrealized_usdt']}` USDT",
                f"- Median / P90 hold: `{candidate['median_hold_hours']}` / "
                f"`{candidate['p90_hold_hours']}` hours",
                f"- Frozen mechanism: `{result.get('mechanism_key')}`; "
                f"status `{result.get('status')}`",
            ]
    if result.get("schema_version") == "BTC_DRA_ONE_SLOT_SIGNAL_ROTATION_V1_RESULT":
        parent_windows = result.get("parent")
        candidate_windows = result.get("candidate")
        if isinstance(parent_windows, dict) and isinstance(candidate_windows, dict):
            parent = parent_windows.get("validation")
            candidate = candidate_windows.get("validation")
            if isinstance(parent, dict) and isinstance(candidate, dict):
                candidate_total = Decimal(candidate["total_pnl_usdt"])
                parent_total = Decimal(parent["total_pnl_usdt"])
                candidate_dd = Decimal(candidate["max_drawdown_pct"])
                parent_dd = Decimal(parent["max_drawdown_pct"])
                return [
                    f"- Validation total PnL: `{candidate_total}` USDT "
                    f"(parent `{parent_total}`; delta `{candidate_total - parent_total:+}`)",
                    f"- Validation drawdown: `{candidate_dd}%` "
                    f"(parent `{parent_dd}%`; delta `{candidate_dd - parent_dd:+}%`)",
                    f"- Realized / unrealized: `{candidate['realized_usdt']}` / "
                    f"`{candidate['unrealized_usdt']}` USDT",
                    f"- Median / P90 hold: `{candidate['median_hold_hours']}` / "
                    f"`{candidate['p90_hold_hours']}` hours",
                    f"- Capacity blocks: `{parent['blocked_entries']} -> "
                    f"{candidate['blocked_entries']}`; successful rotations: "
                    f"`{candidate['rotation_replacement_buy_count']}`",
                ]
    historical = result.get("historical")
    if not isinstance(historical, dict):
        return []
    candidate_lane = historical.get("router_v2b")
    parent_lane = historical.get("router_v2")
    baseline_lane = historical.get("dra_v1")
    if not all(isinstance(lane, dict) for lane in (candidate_lane, parent_lane, baseline_lane)):
        return []
    candidate = candidate_lane.get("validation")
    parent = parent_lane.get("validation")
    baseline = baseline_lane.get("validation")
    if not all(isinstance(row, dict) for row in (candidate, parent, baseline)):
        return []
    candidate_total = Decimal(candidate["total_pnl_usdt"])
    parent_total = Decimal(parent["total_pnl_usdt"])
    baseline_total = Decimal(baseline["total_pnl_usdt"])
    candidate_dd = Decimal(candidate["max_drawdown_pct"])
    parent_dd = Decimal(parent["max_drawdown_pct"])
    baseline_dd = Decimal(baseline["max_drawdown_pct"])
    lines = [
        f"- Validation total PnL: `{candidate_total}` USDT "
        f"(vs parent `{candidate_total - parent_total:+}`; vs DRA V1 `{candidate_total - baseline_total:+}`)",
        f"- Validation drawdown: `{candidate_dd}%` "
        f"(vs parent `{candidate_dd - parent_dd:+}%`; vs DRA V1 `{candidate_dd - baseline_dd:+}%`)",
        f"- Realized / unrealized: `{candidate['realized_usdt']}` / `{candidate['unrealized_usdt']}` USDT",
        f"- Median / P90 hold: `{candidate['median_hold_hours']}` / `{candidate['p90_hold_hours']}` hours",
    ]
    candidate_2022 = candidate_lane.get("2022")
    parent_2022 = parent_lane.get("2022")
    if isinstance(candidate_2022, dict) and isinstance(parent_2022, dict):
        lines.append(
            "- 2022 mechanism check: non-flat entries "
            f"`{parent_2022['route_audit']['entry_counts']['DRA_V1_NONFLAT']} -> "
            f"{candidate_2022['route_audit']['entry_counts']['DRA_V1_NONFLAT']}`, "
            f"terminal open lots `{parent_2022['open_lots']} -> {candidate_2022['open_lots']}`"
        )
    return lines
