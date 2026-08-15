# OKX Microstructure V3R1 Hypothesis Design Runner V1

Status: frozen offline Local Research capability. The runner is prepared before
the terminal V3R1 interpretation is known so that one later evidence-bound task
can either create one frozen hypothesis design or close the mechanism family.

## Fixed zero-argument paths

- source: `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3r1-interpretation-runner-v1/interpretation-result.json`;
- Coach proposal: `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-v3r1-hypothesis-design-runner-v1/coach-proposal.json`;
- output: `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3r1-hypothesis-design-runner-v1/hypothesis-design-result.json`.

The roots must already exist, remain distinct, and contain only the expected
regular non-link files. The CLI accepts no arguments, environment-selected
paths, task, tier, mechanism, disposition, or output.

## One-step terminal behavior

The runner reuses the unchanged interpretation validator, Coach proposal
envelope, pure hypothesis-design builder, design contract and result schema.
`READY_FOR_ONE_HYPOTHESIS_DESIGN` requires exactly one proposal bound to the
exact source bytes and can end only at `DESIGN_ONLY_NOT_REGISTERED`. The three
non-positive dispositions require an empty proposal root and end at
`CLOSED_NO_HYPOTHESIS_DESIGN` without inventing a thesis.

Output is exclusive create-once. An exact retry is idempotent. Partial, extra,
linked, conflicting, noncanonical, unsealed, source-mismatched, or concurrently
changed input or output fails closed without repair or cleanup.

The active V3R1 chain still stops after terminal interpretation; this runner is
not scheduled and is not automatically active. After a real interpretation is
sealed, Manager/Coach must still freeze and validate one exact task, dispatch,
classification intent, and direct strategy-path admission bound to that
artifact before execution.

## Evidence and safety boundary

Tests use synthetic temporary directories only. Preparing this capability has
zero immediate PnL or drawdown effect and does not establish a positive
interpretation, alpha, fees, slippage, capacity, candidate readiness, or OOS
value. It cannot write canonical or `.research-state` state, call Research MCP,
add a timer or writer, register or execute an experiment, open OOS, use network
or paid APIs, or touch Trading, databases, orders, funds, SHADOW, PAPER, or
LIVE.
