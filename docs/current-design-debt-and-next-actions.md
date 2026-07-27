# Current Design Debt and Next Actions

Status date: 2026-07-27

This is the current decision document for maintenance and strategy scaling.
Selected strategy research and rollout evidence remains where it is required
to reproduce 509, DRA, Donchian, schema, or retained developer tooling.
Superseded proposals and review packets remain recoverable from Git history;
none overrides this document or the versioned strategy contracts.

## Product decision

The product is a small strategy-driven BTC spot runtime:

1. owner 509 is the TradingView-parity LIVE baseline;
2. DRA V1 is an independent one-lot, 30 USDT LIVE canary;
3. Donchian remains evidence-only SHADOW;
4. OKX Native Spot Grid remains provider-managed and read-only from this
   service;
5. the platform supplies market data, ownership accounting, idempotent order
   submission, reconciliation, observability, and deployment safety.

Do not restore AI/ML ensemble voting, TQS/Autopilot promotion, shared
strategy-quality gates, generic exits, or a second strategy orchestration
framework without a new versioned requirement and causal evidence. Those
systems increased maintenance cost and blocked strategy intent without proving
economic value.

## Economic comparison contract

Every comparison must use the same initial capital, market window, source,
fees, adverse slippage, and final valuation time. Report these fields
separately:

| Field | Required meaning |
| --- | --- |
| Realized PnL | Net PnL from completed lots only |
| Unrealized PnL | Estimated net liquidation value minus remaining cost |
| Total PnL | Realized plus unrealized PnL |
| Maximum drawdown | Drawdown of total marked-to-market equity |
| Capital utilization | Average and maximum open cost divided by the common capital |
| Blocked entries | Signals skipped because the strategy had no available capital or lot capacity |
| Holding age | Oldest and average age of open lots |

Realized PnL alone is never the ranking metric. A profit-only strategy can make
realized PnL look good by leaving losing inventory open. The primary ranking is
fee-adjusted total PnL under equal capital; realized and unrealized PnL remain
visible as separate diagnostic fields.

Provider receipts are the final source of truth for fills and fees. A database
value derived before the provider fee is available is provisional and must not
be described as exact-net performance.

## Current strategy interpretation

### Owner 509

Owner 509 proves that the runtime can reproduce the chosen TradingView entry
logic and execute it with bounded notional. It is a LIVE baseline, not a
profitable benchmark:

- the 1,095-day parity run produced 72 intents, `-8.50%` total return, and
  `23.38%` maximum drawdown;
- the later fair-reset comparison also left materially negative unrealized
  inventory;
- therefore 509 should remain bounded and must not be scaled on parity evidence
  alone.

### DRA V1

DRA V1 has better historical and out-of-sample economics under its 250 USDT
multi-lot reference model. Production does not run that model. Production is
limited to one 30 USDT lot, so its fair LIVE comparison is the one-lot overlay,
not the headline 250 USDT reference result.

The frozen V1 choice is:

- daily reversal/trend confirmation for entry;
- one 30 USDT LIVE lot;
- `+5%` estimated net profit exit;
- no stop-loss, time exit, drawdown gate, or forced end liquidation.

Changing the exit target, adding partial profit-taking, or adding an intrabar
exit creates DRA V2 and requires a new causal/OOS comparison. It must not be
silently inserted into V1.

## Accepted choices, not defects

The following are deliberate boundaries:

- no AI/ML, ensemble, TQS, or shared strategy risk veto;
- DRA limited to a 30 USDT single-lot canary;
- no automatic loss sale or time exit in 509 and DRA V1;
- historical strategy and evidence rows remain in the shared database;
- OKX Native Grid is provider-managed;
- aliases `508` and `509` remain display lineage while canonical strategy keys
  own runtime behavior.

The absence of a stop-loss is high risk, but it is a strategy choice rather
than an execution-platform defect. It is acceptable only while unrealized PnL,
holding age, utilization, and blocked opportunity cost remain visible.

## Actual design debt

### Completed code reduction

On 2026-07-27, Batches 4A through 4G removed 106 unreferenced legacy classes
and 11,891 source lines. The removed roots covered old counterfactual/adoption
services, standalone simulations, unused provider adapters, inactive
diagnostics, retired risk helpers, evidence utilities, Telegram presentation
helpers, AI DTOs, configuration bindings, and repository interfaces with no
remaining consumer. Compilation and direct source assertions preserved the
three runtime strategy implementations, the fixed 10-tool MCP surface, OCO
safety, read-only Grid monitoring, active runtime environment values, all JPA
entities, tables, historical rows, migrations, and deployment scripts.

A low reference count alone is not sufficient evidence for further deletion.
Spring interface implementations, event listeners, schedulers, configuration
binding, JPA entities/repositories, and provider warning classifications need
their own dependency closure. Batch 4D completed that closure for the unused
Etherscan and Pyth adapters together with their obsolete runtime-log
classifications.

### P0 — Observe the first complete DRA lifecycle

Do not change DRA V1 while its first live lot remains open. A complete provider
buy-to-sell cycle is needed to validate entry ownership, exit eligibility,
actual fee reconciliation, realized PnL, and duplicate prevention. Deployment
and continuity evidence are functional evidence, not profitability evidence.

### P1 — Reconcile delayed provider fees

OKX can publish the buy fee after the initial fill response. The current
adapter retries briefly and, if the fee is still unavailable, reduces the
sellable quantity by a conservative `0.2%` buffer. This protects unrelated BTC
but means the initial database quantity is not the exact provider-net quantity.

Required correction before scaling:

1. persist the immutable provider gross fill and observed fee status;
2. reconcile the final provider fee/order receipt asynchronously;
3. derive the strategy-owned net quantity from that receipt;
4. retain a visible reconciliation state instead of treating a conservative
   buffer as final accounting.

### P1 — Make partial sell retries idempotent

DRA V1 marks a partial sell as `OPEN_PARTIAL`, but the current deterministic
sell client-order ID is derived from the original signal bar. Another sell
attempt can therefore reuse the same client-order ID.

Required correction before scaling:

1. reconcile the existing provider order before any retry;
2. allocate a durable sell sequence such as `S01`, `S02`;
3. generate the next deterministic ID from strategy, lot, and sell sequence;
4. never submit a new sell while the previous outcome is unresolved.

### P1 — Restore a small contract-test boundary

The old broad automated test tree was intentionally removed. That does not
justify changing LIVE execution code without focused tests. Before the next
Java change to order, fill, ownership, or state handling, add a small suite
covering:

- DRA entry and exit net-return math;
- delayed buy fee reconciliation;
- partial buy and partial sell handling;
- duplicate closed-bar delivery;
- restart/state restore and corrupt-state fail-closed behavior;
- deterministic buy/sell client-order IDs;
- owner-509 same-bar weight aggregation and caps;
- strategy-owned quantity isolation.

This is a narrow LIVE-contract suite, not a restoration of the deleted generic
backtest and AI test infrastructure.

### P2 — Separate current state from evidence history

DRA currently restores state by scanning recent evidence JSON rows. This is
acceptable for the single-instance canary because it fails closed, but
append-only evidence and mutable current state serve different purposes.
Before adding more LIVE strategies or running multiple instances, introduce
one typed current-state record per canonical strategy and keep evidence
append-only.

### P2 — Enforce strategy-and-bar uniqueness durably

The closed-bar listener is asynchronous and the in-process DRA bar guard is
JVM-local. Order reservation has durable uniqueness, but evidence/state
advancement is not yet protected by a database uniqueness contract for
canonical strategy plus bar. Add that constraint before multi-instance
evaluation.

### P2 — Add one generic read-only strategy status

DRA has no dedicated MCP tool. Do not add one tool per strategy. If operator
visibility becomes necessary, add one generic read-only status surface keyed
by canonical strategy key. It should expose mode, armed state, latest closed
bar, state hash, open owned quantity, reconciliation status, realized and
unrealized PnL, and current blocker.

## Ownership invariant

BTC is isolated by logical ledgers, not by separate exchange wallets. A sell
must reconcile the account balance against all strategy-owned, legacy, manual,
OCO-reserved, and Grid-owned quantities, then sell no more than the current
strategy's provider-verified owned quantity. Existing positions
`260/261/262`, owner-509 lots, DRA lots, manual BTC, and Grid BTC must never be
adopted across ownership boundaries.

## Scaling gate

The 30 USDT DRA canary may continue under the frozen V1 contract. Increasing
notional, allowing multiple live lots, or enabling another LIVE strategy
requires all of the following:

1. at least one complete real DRA buy-to-sell lifecycle with provider receipts;
2. exact fee and net-quantity reconciliation;
3. no duplicate or ambiguous order outcome;
4. the P1 contract tests passing;
5. equal-capital realized, unrealized, total, drawdown, utilization, blocked
   entry, and holding-age comparison against owner 509;
6. a new explicit operator authorization.

## Recommended order of work

1. Keep Production unchanged and finish read-only DRA/509 continuity
   observation.
2. After the first DRA exit, reconcile provider receipts and publish the first
   real economic result.
3. Fix delayed-fee accounting and partial-sell idempotency together with the
   narrow contract tests.
4. Re-run the equal-capital comparison.
5. Decide whether DRA V2 or a larger/multi-lot DRA deployment is justified.
6. Only then consider typed state, database bar uniqueness, or targeted class
   extraction needed by the approved scale.
