# OKX Microstructure V3R1 Positive-Route Hypothesis-Design Runner V1

Status: `LOCAL_RESEARCH_TOOLING_ONLY`

This runner closes the post-evidence tooling gap between the active V3R1
terminal interpretation and the already frozen standalone intraday economic
route. It does not activate that route or claim that the route is profitable.

## Fixed zero-argument interface

- Source:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3r1-interpretation-runner-v1/interpretation-result.json`
- Proposal:
  `C:/Users/Redan/.codex/local-research-node/inbox/local-node-microstructure-v3r1-positive-route-design-runner-v1/coach-proposal.json`
- Output:
  `C:/Users/Redan/.codex/local-research-node/outbox/local-node-microstructure-v3r1-positive-route-design-runner-v1/positive-route-hypothesis-design-result.json`

Production accepts zero arguments. Source, proposal and output roots are fixed,
separate and fail closed on missing, linked, reparse, extra, noncanonical,
drifted or conflicting content.

## Decision path

For `READY_FOR_ONE_HYPOTHESIS_DESIGN`, the runner requires exactly one Coach
proposal sealed to the terminal interpretation and its selected tier. The
result is bound to the sole-primary
`OKX_MICROSTRUCTURE_INTRADAY_ECONOMIC_ROUTE_V1` route and stops at
`DESIGN_ONLY_NOT_REGISTERED`.

The route contract already fixes a one-hour long-only 30 USDT route, explicit
fees and slippage, an equal-capital matched control, a cash benchmark and
separate Design, Validation and OOS windows. It forbids DRA fallback, multiple
routes and route switching. A non-positive interpretation requires an empty
proposal root and closes as `CLOSED_NO_HYPOTHESIS_DESIGN` with no route or
design.

The previous generic V3R1 design runner remains unchanged. Its DRA entry
admission path still requires an unimplemented adapter, so it is not the
preferred direct-strategy path for a positive V3R1 outcome. Historical V3
positive-route runners and roots also remain unchanged.

## Authority and remaining proof

The self-task freezes the V3R1 interpretation source task and contracts, the
positive-route builder and schemas, the standalone economic route contract and
the reused fail-closed runner core. Inputs are checked before source access and
again before exclusive create-once publication. Tests use synthetic temporary
roots only; this implementation does not inspect or execute the real fixed
roots.

The active evidence chain still stops at terminal interpretation. A future
positive result must first receive an evidence-grounded Coach proposal before
this runner may be dispatched. Hypothesis registration, an executable 42-day
economic evaluator, matched-control PnL and drawdown, candidate readiness, OOS
and activation remain `MISSING_PROOF`.

Immediate PnL and drawdown effect are zero. The runner cannot write canonical
or `.research-state` state, call Research MCP, add a timer or writer, use a
paid API, open OOS, or touch Trading, databases, orders, funds, SHADOW, PAPER
or LIVE.
