---
name: autonomous-trading-research
description: Autonomously advance research-only trading experiments in agora-trading-api, including hypothesis registration, frozen manifest validation, deterministic runner execution, fail-closed gates, sealed OOS handling, learning capture, and periodic performance briefings. Use when the user asks Codex to improve trading performance, continue research without user task participation, investigate a testable strategy question, review the autonomous research queue, or produce weekly/monthly research learning reports. Never use it to activate SHADOW/PAPER/LIVE, mutate Production or a database, place orders, change Trading runtime schedulers, or restore retired AI/Autopilot systems. One Codex cloud Ops schedule is the sole research clock; the server path unit only dispatches its durable request.
---

# Autonomous Trading Research

Operate the repository's research-only control plane while preserving the
strategy-driven Production boundary. Optimize for credible knowledge and
matched-capital risk-adjusted performance, not run count or candidate count.

## Start

1. Read `AGENTS.md`, `SERVICE_BOUNDARY.md`,
   `docs/strategy-driven-minimal-runtime.md`, and
   `docs/autonomous-research-charter.md` completely.
2. Run `git status --short --branch` and preserve all unrelated or untracked
   research artifacts.
3. Read canonical server status through the OAuth Research MCP first. Use
   `python -m research_pipeline status`, `hypotheses`, `catalog`, and
   `evidence-triggers` only for an explicitly labelled local development state
   or read-only replica. An evidence wait is intentional, not an empty queue.
4. Treat the charter and `research_pipeline/policy.v3.json` as hard policy.

## Decide the action

- For status or a briefing, do not advance research. Run
  `python -m research_pipeline weekly-report` and follow
  [references/report-format.md](references/report-format.md).
- For the monthly program review, do not advance research. Run
  `python -m research_pipeline monthly-report`; explain the hypothesis tree,
  repeated failure modes, evidence gaps, and whether learning capture is
  keeping pace with experiment count.
- For one autonomous research step, run
  `python -m research_pipeline tick --dry-run` first. Execute
  `python -m research_pipeline tick` only when the preview uses an approved
  adapter and stays inside `.research-state`.
- For Java DRA parity, use only the approved `java-dra-v1-parity` or
  `java-dra-v1-economic-ledger` adapter. Require Java 21 and direct classpath
  launch. Never use Maven exec because the repository plugin targets
  `TradingApiApplication` and starts Spring.
- For a new hypothesis, inspect prior closed research first. Write a proposal
  matching `research_pipeline/hypothesis.schema.json`, then run
  `propose-hypothesis`. Do not exceed the policy's one-new-hypothesis cycle
  budget or submit a duplicate fingerprint.
- Run `next-hypothesis` before creating a manifest. If it is blocked on
  capability, freeze the research contract before adding the smallest runner
  adapter, then run `refresh-hypotheses`. A diagnostic capability cannot claim
  a candidate or OOS result.
- If no experiment is actionable, report `IDLE_NO_ACTIONABLE_EXPERIMENT` or
  formulate the next smallest causal hypothesis. When the preview reports
  `READY_HYPOTHESIS_REQUIRES_FROZEN_MANIFEST`, freeze that hypothesis's
  manifest instead of calling the queue idle. Do not invent work merely to
  keep the pipeline busy.
- When the preview reports `WAITING_FOR_EVIDENCE`, do not formulate a strategy
  from the same closed history. When it reports `EVIDENCE_REVIEW_DUE`, perform
  only the frozen read-only integrity/diagnostic review and record exactly one
  `WAIT`, `READY_FOR_HYPOTHESIS`, or `CLOSE` review. A review-ready window is
  discovery evidence, not OOS for a hypothesis derived from it.
- Before dispatching any new countable Local task, freeze a strategy-path
  admission matching
  `research_pipeline/local-research-strategy-path.v1.schema.json` and run
  `local-research-strategy-preflight`. Require a feature known before the
  decision, an existing parent/comparator and runner, full economic/path-risk
  visibility, and at most one further step to a frozen hypothesis or matched-
  capital experiment. Bind the feature, parent, comparator, and runner to exact
  frozen task inputs. After result validation, include source-commit-verifiable
  `strategy_path_evidence` in the Manager acceptance; otherwise the mechanism
  label remains a legacy proxy and must not enter candidate-delivery KPI.
  Capability, infrastructure, and repair work may use the
  original Manager preflight but is support work, not direct candidate delivery.
  Do not rewrite already sealed tasks or the current active chain.
- Before dispatching the next Local task, inspect the latest validated throughput
  KPI `next_dispatch_policy`. When support work is
  `DEFER_UNLESS_ACTIVE_EVIDENCE_INTEGRITY`, select a verified direct strategy-
  path task or wait for new evidence. Use the support exception only for a
  separately proven active evidence-integrity risk, keep it `NON_COUNTING`, and
  never invent a mechanism merely to improve the ratio.
- Enforce that decision with `local-research-allocation-preflight`, passing the
  exact rolling acceptance allowlist and the strategy-path sidecar for direct
  work. Do not dispatch when the allocation preflight fails.

## Register a hypothesis and experiment

Require the proposal's thesis, single causal mechanism, economic rationale,
matched parent, expected metrics, evidence readiness, capability, ranking, and
cycle ID. Use the exact authorization
`RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`.

Propose and select with:

```text
python -m research_pipeline propose-hypothesis <hypothesis.json>
python -m research_pipeline next-hypothesis --json
```

Then write the frozen experiment manifest. Require its point-in-time selection
cutoff, optional sealed OOS cutoff, primary metric, constraints, and no more
than three variants.

Validate and register with:

```text
python -m research_pipeline validate-manifest <manifest.json>
python -m research_pipeline register <manifest.json> --hypothesis-id <hypothesis-id>
```

Never edit a registered manifest. Register a new experiment ID when the thesis
changes before outcome access. A historical experiment may be linked with
`import-experiment-hypothesis`; importing old learning does not consume the new
hypothesis budget. Close the branch when a gate fails.

## Wait for independent evidence

When the only honest next step is new evidence, register a frozen trigger that
matches `research_pipeline/evidence-trigger.schema.json`:

```text
python -m research_pipeline register-evidence-trigger <trigger.json>
python -m research_pipeline evidence-triggers
```

The trigger must specify an untouched evidence start, a not-before review time,
minimum observations, integrity checks, prohibited inferences, and excluded
closed branches. At review time, write a review matching
`research_pipeline/evidence-review.schema.json`, then run:

```text
python -m research_pipeline review-evidence-trigger <trigger-id> <review.json>
```

For each `COMPLETE_UTC_DAY`, use only a source-produced bundle matching
`research_pipeline/evidence-day.schema.json` and seal it through:

```text
python -m research_pipeline ingest-evidence-day <trigger-id> <day-bundle.json>
```

Before `evidence_start`, seal the named producer and one-way transport with
`register-evidence-source-contract` using
`research_pipeline/evidence-source-contract.schema.json`. The source contract
must deny Worker network access, Worker database access, and backfill. Treat
`EVIDENCE_SOURCE_UNBOUND` as an actionable integrity/readiness defect rather
than a normal wait.

The deterministic intake requires 24 contiguous closed hours, valid OHLC and
volume, exact frozen source/trigger identity, source provenance, strict day
order, a six-hour capture deadline, and the cumulative SHA-256 chain. Never
repair `EVIDENCE_CAPTURE_MISSED` with backfill; close the trigger or create a
new untouched start after a lawful source exists.

`READY_FOR_HYPOTHESIS` requires exactly one typed forward-evidence manifest
matching `research_pipeline/evidence-manifest.schema.json`. Validate it with
`validate-evidence-manifest`; the gate derives observation count, verifies
coverage, trigger/source binding, dataset and diagnostic hashes, and all frozen
integrity checks. Propose at most one hypothesis with source
`EVIDENCE_TRIGGER:<trigger-id>`, then close the evidence lifecycle with
`link-evidence-trigger`. Never reinterpret the evidence accumulation window as
clean OOS for the hypothesis it generated.

A `CLOSE` review may be recorded before the not-before time only to fail closed
on a trigger integrity defect. Preserve the invalid trigger and review; never
edit or delete the registered specification.

## Interpret outcomes

- Treat `DATA_REJECT`, `LEAKAGE_REJECT`, `BASELINE_REJECT`,
  `NO_CANDIDATE`, and `OOS_FAIL` as valid scientific outcomes.
- Never relax a gate, scan neighboring parameters, reopen OOS, or hide
  terminal unrealized inventory after seeing results.
- Keep realized, unrealized, total PnL, drawdown, utilization, blocked entries,
  and holding age together.
- A candidate remains `REPORTED_NOT_ACTIVATED` regardless of result.
- A diagnostic adapter may propose a new hypothesis but cannot become a
  candidate or OOS result.

## Handle failures

- Record unavailable dependencies or invalid evidence as fail-closed status.
- Do not ask the user to operate ordinary research steps or approve already
  authorized read-only commands.
- Do not guess around missing access, data, or artifacts.
- Do not call write-capable Trading MCP tools, Trading deployment scripts,
  Trading runtime schedulers, authenticated/trading exchange APIs, or database
  mutations. The only market-API exception is the deployed
  `agora-evidence-source` identity calling its fixed public OKX candle endpoint
  under `docs/server-forward-evidence-source-v1.md`; Codex and the network-denied
  Research Worker may only trigger and verify that schema-bound path.
- Preserve an existing sealed artifact; never overwrite it.

## Keep the architecture narrow

Keep Codex as the control plane, `research_pipeline` as deterministic lifecycle
control, legacy runners as temporary adapters, and Java DRA code as the future
shared economic kernel. Never introduce a second Spring runtime orchestration
framework. Phase A checkpoint parity and Phase B baseline event/fill/lot/state
parity are learning evidence only; do not make Java a mandatory candidate gate
until one representative complex overlay passes the same ledgers in Phase C.

After the V3 cutover, one Codex cloud Ops schedule reads the OAuth Research MCP,
enqueues the routine heartbeat, and interprets sealed results. If canonical
evidence reaches `READY_FOR_HYPOTHESIS`, the same schedule may submit exactly
one bounded `candidate-bundle.schema.json` hypothesis/manifest pair. The Worker
must re-verify the sealed evidence and may end only at `PREREGISTERED`; it must
not execute or promote the experiment. Canonical mutable state remains in the
independent server Research Worker. Do not reactivate the desktop heartbeat,
the legacy server timer, or local-replica writes.
