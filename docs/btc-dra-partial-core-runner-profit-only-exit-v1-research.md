# BTC DRA Partial Core / Residual Runner Profit-Only Exit V1 Research

Date frozen: 2026-08-20

Research identity:
`BTC_DRA_PARTIAL_CORE_RESIDUAL_RUNNER_PROFIT_ONLY_EXIT_V1_RESEARCH`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is an offline, read-only historical study. It does not change DRA V1,
Production, Trading, a database, orders, funds, Grid/OCO, a scheduler, SHADOW,
PAPER, or LIVE. Post-2024 OOS remains sealed unless the frozen primary and
stability gates all pass.

## Research question and independence

The sealed full-lot structural study showed a binary frontier: the all-core
`1.0 entry ATR` path recycled capital but produced only `61.85809397 USDT` in
Validation, while the full-lot V2A `1.50 ATR` runner produced
`113.25094608 USDT` but worsened median and P90 holding to
`401.0h / 1,846.6h`. The global-one-runner candidate nearly reproduced V1 but
could not jointly retain trend value and holding breadth.

This study changes exactly one contract dimension: every newly filled
`30 USDT` parent lot is split immediately into a fast core sublot and a
residual runner sublot. Both inherit the same signal, fill time, entry ATR,
fee, adverse slippage, and next-open clock. The study does not select which
historical lots receive runner status, add a price factor, change entries, or
force a loss/time exit.

This is a new within-lot quantity-separation family. It does not reopen any
closed full-lot V2A-V2E rule, entry-admission factor, V7 exit-management branch,
or one-slot rotation family.

## Frozen common contract

- Data: immutable OKX `BTCUSDT` H1 selection corpus, exactly `52,608` rows,
  ending at `2025-01-01T00:00:00`, SHA-256
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Windows: Design `2019-01-01` through `2022-12-31`; Validation `2023-01-01`
  through `2024-12-31`; fair-reset annual folds `2020` through `2024`.
- Entry: exact DRA V1 arm, 30-day expiry, seven-day cooldown, complete-day
  signal, and next-H1-open fill.
- Parent lot cost: `30 USDT`; reference capital: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Indicators: causal complete-day EMA/ATR state from the sealed V2A runner.
- No stop loss, time exit, forced loss, final liquidation, averaging, leverage,
  added funds, future-aware assignment, or post-outcome rescue.
- Every queued sale fills at the next H1 open only when that sublot remains
  strictly net profitable after its allocated entry cost, sell fee, and
  adverse slippage. A failed safety check clears the queue and records a
  deferred exit.

## Frozen split and quantity accounting

The entry first computes the same total BTC quantity as the sealed parent:

```text
entry fee    = round8(30 * 0.0010)
adverse buy  = round8(next open * 1.0005)
parent qty   = floor12((30 - entry fee) / adverse buy)
```

For a core fraction `f`:

```text
core qty     = floor12(parent qty * f)
runner qty   = parent qty - core qty
core cost    = round8(30 * f)
runner cost  = 30 - core cost
```

Both quantities must be positive and reconcile exactly to parent quantity;
both allocated costs must be at least `10 USDT`. The runner receives the
rounding residual, so no BTC dust is discarded. Capacity and utilization use
the sum of still-open sublot costs. A later parent entry is allowed only when
that sum plus `30 USDT` does not exceed the unchanged capital cap.

The `10 USDT` floor is a conservative research feasibility screen, not proof
of current exchange instrument rules. Current OKX lot/minimum-order metadata
was unavailable from this environment before freeze and remains
`MISSING_PROOF`; even a historical pass cannot authorize runtime use.

## Frozen exits

### Fast core

Each core sublot stores the causal ATR14 at the parent signal. It queues when:

```text
estimated net liquidation profit >= 1.0 * entry ATR14 * core quantity
```

This is the sealed V2E fast core, scaled only by sublot quantity.

### Residual runner

Each runner sublot independently tracks its highest completed H1 close. Its
unchanged V2A ratchet is:

```text
candidate stop = highest close - current complete-day ATR14 * 1.50
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The ratchet never moves down. It queues only after a complete H1 close is at or
below the ratchet and estimated net PnL is strictly positive. Core and runner
may queue or fill on different bars; neither waits for nor changes the other.

## Frozen variants

Exactly three variants are allowed:

| Role | Core | Runner | Allocated costs |
| --- | ---: | ---: | --- |
| lower neighbor | `0.40` | `0.60` | `12 / 18 USDT` |
| primary | `0.50` | `0.50` | `15 / 15 USDT` |
| upper neighbor | `0.60` | `0.40` | `18 / 12 USDT` |

No other split, target, ATR multiplier, trigger, lookback, or parent is
permitted after performance access.

## Required ledgers and path evidence

Every window and fold must report the ordinary matched-capital metrics plus:

- one parent buy count and separate core/runner sell counts;
- allocated and ending open cost by path;
- realized, unrealized, total PnL, fees, turnover, drawdown, underwater
  duration, utilization, blocked entries, deferred exits, and terminal
  inventory by sublot;
- capital-weighted median/P90 realized holding: because every frozen allocated
  cost is an integer USDT amount (`12`, `15`, or `18`), repeat each realized
  sublot holding observation once per allocated USDT and apply the existing
  linear-interpolation percentile function to that expanded list;
- final-parent median/P90 holding, measured when the last sublot of a parent
  exits, and final open-parent ages;
- quantity-step reconciliation, minimum allocated cost, and any feasibility
  failure;
- annual total-PnL and capital-weighted-holding breadth versus V1; and
- primary and both-neighbor deltas versus V1, with V2A opportunity cost shown
  separately rather than made invisible.

## Frozen gates

The primary `0.50 / 0.50` variant passes only when every condition holds in
the frozen pre-2025 selection evidence:

- Design and Validation total PnL are each at least matched V1;
- Design and Validation realized PnL are each at least matched V1;
- Validation ending unrealized PnL is no worse than V1;
- Design and Validation drawdown are no more than `2.0` percentage points
  worse than matched V1;
- Validation capital-weighted median and P90 holding are no worse than V1
  `182.5h / 1,418.3h`;
- Validation final-parent median and P90 holding are no worse than V2A
  `401.0h / 1,846.6h`;
- Validation ending open cost and open-parent count are no worse than V1;
- total PnL beats V1 in at least three of five annual folds;
- capital-weighted median holding beats V1 in at least three of five annual
  folds;
- all split quantities reconcile and every sublot passes the frozen `10 USDT`
  allocated-cost screen; and
- at least one neighboring split retains at least `95%` of V1 Validation total
  and realized PnL while staying within V1 plus `2.0` drawdown points and
  passing quantity feasibility. This is stability evidence only; a neighbor
  cannot become the formal candidate.

V2A total-PnL retention is reported as opportunity cost but is not a gate:
V2A already failed the frozen V1 holding and annual-holding gates, so treating
its higher total as an independently promotable comparator would hide its
known path cost.

If any primary or stability gate fails, emit
`NO_CANDIDATE_CLOSE_PARTIAL_CORE_RUNNER_EXIT_FAMILY`, keep OOS sealed, and do
not tune the split or exits. If all pass, freeze only the primary as
`CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED`; one independent post-2024 OOS may
then be opened once under a separately sealed artifact path.

## Reproducibility and safety boundary

Before accepting performance, the runner must verify this specification hash,
the exact input hash/row count, and frozen V1/V2A checkpoints. Two preselection
runs must be byte-identical. A parity, quantity, accounting, data, or binding
failure closes or rejects before OOS.

No result from this research can create a runtime strategy, catalog entry,
schedule, canonical writer, database mutation, order, fund movement, Grid/OCO,
SHADOW, PAPER, or LIVE action.
