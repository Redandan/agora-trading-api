Use `$autonomous-trading-research` in this repository. Treat the OAuth Research
MCP as the canonical status and queue path. Never run a local `.research-state`
heartbeat or write a local evidence review after server migration. The server
Research Worker owns the single deterministic heartbeat and canonical state;
routine `WAITING_FOR_EVIDENCE` remains silent.

Read `get_research_status` first. Call `request_research_heartbeat` only when
canonical `next_due` has arrived; early calls must remain `NOT_DUE`, and
concurrent calls must converge. Poll `get_research_run` briefly after a queued
request, otherwise inspect the durable result on the next cloud cycle. Do not
invent a candidate, change runtime code, widen permissions, or report routine
progress.

The fixed forward-source companion captures only the next complete UTC day.
When the final required day is canonically ingested, the deterministic pipeline
seals the mechanism-neutral dataset, diagnostic, typed evidence manifest, and
`READY_FOR_HYPOTHESIS` review without adding another MCP tool or timer. If the
review date arrives while the final day is still `CAPTURE_DUE`, capture remains
the next action; never skip it, backfill it, or write the review locally.

When canonical status becomes `EVIDENCE_READY_REQUIRES_CODEX_HYPOTHESIS`, use
the sealed `evidence_diagnostic` to formulate at most one deduplicated causal
hypothesis and its matching frozen experiment manifest. Submit exactly one
schema-bound bundle through `submit_research_candidate_bundle`. The discovery
window is not clean OOS. The server must reverify all sealed evidence and stop
at `PREREGISTERED`; do not execute or promote the experiment in the same step.

Notify the Coach task only when a sealed server heartbeat returns
`should_notify_coach=true`. Send the sealed structured event to task
`019fca63-4f8f-71e3-9d88-297bca468eb9` with at least `event_type`,
`artifact_path`, `sha256`, `research_status`, `material_conclusion`,
`pnl_drawdown_evidence`, `evidence_diagnostic`, `uncertainty`, `next_action`,
and `concept_to_teach`. Do not ask the Coach or sponsor to operate research or
alter frozen gates.
