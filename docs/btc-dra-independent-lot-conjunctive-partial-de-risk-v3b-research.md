# BTC DRA Independent-Lot Conjunctive Partial De-Risk V3B Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_CONJUNCTIVE_PARTIAL_DE_RISK_V3B_RESEARCH`

Candidate:
`ENTRY_ACCELERATION_AND_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_24_6`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It cannot modify or deploy Production,
runtime, configuration, database, DRA V1, position `263`, owner `509`,
Grid/OCO, funds, schedules, Telegram, or orders, and it does not authorize
SHADOW or LIVE. The single candidate below is frozen before its performance is
queried.

## V3 diagnosis and one remaining hypothesis

V3 established that conditional partial realization can preserve profit. Its
7-day acceleration candidate produced Validation realized/total
`117.52474516 / 114.31654395`, drawdown `7.506541%`, and annual total wins
`4/5`. Its partially realized lots reached their first `20 USDT` tranche exit
at a `143h` median, already better than V1. The candidate nevertheless had a
`338h` cost-weighted median because `28/51` entries stayed full V2A and only
`22` entries realized a `20 USDT` tranche. All V3 candidates failed holding,
not profitability or accounting.

The two V3 entry conditions selected `28` and `27` full-V2A lots; their union
selected `37`, so their causal intersection contains `18`. The complement is
large enough for early realized capital to control the holding distribution,
but a `20/10` split is not: even `32` partial fills release only `640 USDT`,
below half of `50 * 30 = 1,500 USDT` completed entry capital. A fixed `24/6`
split releases `768 USDT`, just over half, without removing the remainder's
trend exposure.

V3B therefore tests exactly one structural correction: full V2A requires both
entry-day continuation conditions, and partial-eligible lots use an `80/20`
cost and quantity split. It does not test another factor, ratio, arm, EMA, or
ATR value. Failure terminates this partial-de-risk branch as `NO_CANDIDATE`.

## Unchanged shared contract

- Server-local read-only `md_kline`, OKX `BTCUSDT`, complete `1h` candles only.
- Exact DRA V1 entry, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill. Exit-route labels never reject a qualifying buy.
- Original lot `30 USDT`; reference capacity `250 USDT`.
- Fee `0.10%` per side; adverse slippage `0.05%` per side.
- Independent lots; no runner quota, entitlement, epoch slot, or average-cost
  exit.
- No stop loss, time exit, forced loss, or final liquidation.
- Every sale fills next open only when that exact tranche remains strictly net
  positive after all costs.
- Any hard-data-inception lot missing 14 complete days or entry ATR is kept as
  full V2A and is never skipped or partially sold. Validation and all annual
  folds must have zero missing inputs.

## Single frozen candidate

At the DRA entry-signal complete daily close:

```text
recent7d = close[t] / close[t-7 complete days] - 1
prior7d  = close[t-7] / close[t-14 complete days] - 1

acceleration = recent7d > 0 and recent7d > prior7d
rangeExpansion = signalDayTrueRange > priorCompleteDayATR14

FULL_V2A = acceleration and rangeExpansion
PARTIAL_ELIGIBLE = not FULL_V2A
```

A `FULL_V2A` lot keeps all original quantity and the unchanged V2A causal
`1.50 ATR` ratchet.

A `PARTIAL_ELIGIBLE` lot stores:

```text
entryRisk1R = entryATR14 * originalFilledQuantity
partialCost = 24 USDT
remainderCost = 6 USDT
partialQuantity = floor(originalQuantity * 4 / 5, existing quantity precision)
remainderQuantity = originalQuantity - partialQuantity
```

It becomes permanently armed only when peak estimated full-lot net PnL reaches
`1R`. Profit alone does not sell. It queues the `24 USDT` tranche only when the
same complete hourly close is below its causal hourly EMA5 and the tranche's
estimated net PnL is positive. Hourly EMA5 and the next-open/deferred logic are
identical to V3.

After a successful partial fill, the remaining `6 USDT` cost and exact residual
quantity are rebased at the observed fill open, prior ratchet state is cleared,
and the remainder uses unchanged V2A `1.50 ATR`. Only one partial fill is
allowed per original lot.

This is not a fixed percentage-return exit: `1R` only arms the path; a causal
hourly trend deterioration and strict current/next-open net-positive checks are
also mandatory.

## Accounting and holding

Capacity uses actual allocated open cost. Each exit slice contributes its
allocated cost (`30`, `24`, or `6 USDT`) to the cost-weighted holding
distribution. Equal-size V1 lots make its ordinary holding median/P90 exactly
equivalent, so the comparison remains like-for-like. Report unweighted slice
holding, time to first realization, time to final completion, capital-hours,
utilization, turnover, blocked entries, deferred exits, route counts, route
PnL, and open cost as diagnostics.

Original quantity and allocated cost must reconcile exactly. Every queue must
meet its arm, EMA, and net-positive conditions; every realized slice must be
strictly positive; no lot may fill two partial tranches.

## Frozen data and checkpoint gate

Preselection is physically capped at close time
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- defects: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V3B performance is accepted, reproduce V1 through V2E, the structural
diagnostic, both independent full-lot profit locks, and all three V3 Validation
checkpoints plus their annual win counts. Specification/dependency drift,
data drift, or numeric/operational mismatch is a hard rejection.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`
through `2024`. Indicator warm-up is causal and all trading/accounting state
resets at the window boundary.

The sole candidate passes only if every gate holds:

- Validation total PnL is at least V1 and at least `90%` V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation drawdown is at most `9.121498%`;
- Validation cost-weighted median/P90 are at most `182.5h / 1,418.3h`;
- fair-reset total wins and cost-weighted median wins are each at least `3/5`;
- Validation and fold route/ATR inputs are complete;
- quantity, cost, trigger, single-partial, and positive-fill audits all pass.

No gate may be relaxed or rounded into a pass.

## OOS and terminal boundary

If every gate passes, freeze this exact candidate and only then allow one
explicit 2025+ OOS query plus the independent `30 USDT` one-slot overlay. If
any gate fails, emit:

```text
NO_CANDIDATE
PARTIAL_DE_RISK_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

A pass remains research evidence and requires a separate SHADOW/runtime
proposal and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_independent_lot_conjunctive_partial_de_risk_v3b.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_independent_lot_conjunctive_partial_de_risk_v3b.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
