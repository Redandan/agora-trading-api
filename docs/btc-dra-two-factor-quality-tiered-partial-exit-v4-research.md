# BTC DRA Two-Factor Quality-Tiered Partial Exit V4 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_TWO_FACTOR_QUALITY_TIERED_PARTIAL_EXIT_V4_RESEARCH`

Candidate:
`ENTRY_TWO_FACTOR_THREE_TIER_FULL_V2A_OR_1R_OR_NET_POSITIVE_PARTIAL_24_6`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It cannot modify or deploy Production,
runtime, configuration, database, DRA V1, position `263`, owner `509`,
Grid/OCO, funds, schedules, Telegram, or orders, and it does not authorize
SHADOW or LIVE. The single candidate and all gates are frozen before its
performance is queried.

## Problem isolated by V3B and V3C

V3B routed both-factor entries to full V2A and every other entry to a `1R`
armed `24/6` partial path. It retained profit—Validation total
`104.99570948`, drawdown `6.388173%`, annual total wins `4/5`—but its
cost-weighted median remained `317h` and annual holding wins were `0/5`.

V3C kept the same entry routing and allocation but removed `1R` from all
partial-eligible entries. Median fell to `184h`, P90 to `836h`, and annual
holding wins rose to `4/5`; total fell to `89.81822423`, only `79.31%` V2A,
and annual total wins fell to `2/5`.

The two causal entry-day conditions naturally identify three states, but V3B
and V3C collapsed the last two states into one. V4 tests the missing structural
ablation: keep the `1R` profit buffer for medium-quality entries and remove it
only for entries with neither continuation condition. This is not a threshold
interpolation or parameter sweep.

## Unchanged shared contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- Exact DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and
  next-open entry fill. Every qualifying entry remains eligible; the quality
  tier cannot block or resize a buy.
- Original lot: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Independent lots; no runner quota, entitlement, epoch slot, or average-cost
  exit.
- No stop loss, time exit, forced loss, or final liquidation.
- Every sale fills at the next complete-hour open only when the exact adverse
  fill remains strictly net positive after all costs.
- Hard-data-inception inputs retain the conservative full-V2A fallback.
  Validation and annual folds must have zero missing route inputs.

## Frozen causal entry features

At the DRA entry-signal complete UTC-day close:

```text
recent7d = close[t] / close[t-7 complete days] - 1
prior7d  = close[t-7] / close[t-14 complete days] - 1

acceleration = recent7d > 0 and recent7d > prior7d
rangeExpansion = signalDayTrueRange > priorCompleteDayATR14
```

True range and Wilder ATR14 use only complete causal daily bars. No current or
future intrabar high/low, percentile, volume factor, external indicator, or
post-entry outcome is permitted.

## Single three-tier route

Every bought lot is assigned exactly once at fill:

1. `TIER_STRONG_FULL_V2A`
   - `acceleration == true` and `rangeExpansion == true`;
   - full `30 USDT` quantity uses unchanged causal V2A `1.50 ATR`.
2. `TIER_MEDIUM_1R_PARTIAL`
   - exactly one of the two conditions is true;
   - partial path becomes permanently armed only when peak estimated full-lot
     net PnL reaches `entryATR14 * originalFilledQuantity` (`1R`);
   - after arming, it may queue only when complete-hour close is below causal
     hourly EMA5 and the `24 USDT` tranche is estimated net-positive.
3. `TIER_WEAK_NET_POSITIVE_PARTIAL`
   - both conditions are false;
   - no `1R` maturity target;
   - it may queue only when complete-hour close is below causal hourly EMA5
     and the `24 USDT` tranche is estimated net-positive.

Hourly EMA5 is the frozen recursive `alpha = 2 / (5 + 1)` series. Profit is
necessary but not sufficient in both partial tiers: the same causal trend
deterioration is also required.

Both partial tiers use exactly:

```text
partialCost = 24 USDT
remainderCost = 6 USDT
partialQuantity = floor(originalQuantity * 4 / 5, existing quantity precision)
remainderQuantity = originalQuantity - partialQuantity
```

After a successful partial fill, the residual is rebased at the observed fill
open, prior ratchet state is cleared, and the exact `6 USDT` remainder uses
unchanged V2A `1.50 ATR`. At most one partial fill is allowed per original lot.
A failed next-open positive check clears the queue but preserves tier and arm
state.

There is no alternate tier formula, new factor, ratio, arm, EMA, ATR
multiplier, or parameter scan.

## Accounting and holding

Capacity uses actual allocated open cost. Selection uses cost-weighted holding
with `30`, `24`, and `6 USDT` exit-slice weights; V1's equal `30 USDT` lots
make its ordinary median/P90 exactly equivalent. Report unweighted slice
holding, first realization, final completion, capital-hours, utilization,
turnover, blocked entries, deferred fills, tier counts/PnL, and ending open
cost as diagnostics.

Original quantity and `30 USDT` cost must reconcile exactly. Every entry must
have exactly one tier, every medium queue must be armed at `1R`, every weak
queue must be classified neither-factor, every partial queue must be below
causal EMA5 and estimated net-positive, every fill must be strictly
net-positive, and no lot may fill more than one partial tranche.

## Frozen data and checkpoint gate

Preselection is physically capped at close time
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- complete rows: `52,608`;
- data defects: all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V4 performance is accepted, reproduce V1 through V3B and the terminal
V3C Validation, tier/route audit, and annual wins exactly. Artifact drift,
data drift, or numeric/operational mismatch is a hard
`PREREGISTRATION_REJECT`, `DATA_REJECT`, or `BASELINE_PARITY_REJECT`.

## Frozen selection protocol

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`
through `2024`. Indicators warm causally while all entry, tier, lot, partial,
ratchet, capacity, and accounting state resets at each window boundary.

The sole candidate passes only when all gates hold:

- Validation total PnL is at least V1 and at least `90%` V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation cost-weighted median/P90 holding are at most
  `182.5h / 1,418.3h`;
- annual total wins and cost-weighted median wins are each at least `3/5`;
- Validation and fold route inputs are complete;
- exact tier uniqueness, medium-arm, weak-classification, EMA, positive-fill,
  cost, quantity, and single-partial audits all pass.

No gate may be removed, rounded into a pass, or relaxed.

## OOS and authorization boundary

If every gate passes, freeze this exact candidate and only then permit one
explicit 2025+ OOS query plus the independent `30 USDT` one-slot overlay. If
any gate fails, emit:

```text
NO_CANDIDATE
QUALITY_TIERED_EXIT_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

A historical pass remains RESEARCH and still requires a separate versioned
SHADOW/runtime proposal and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_two_factor_quality_tiered_partial_exit_v4.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_two_factor_quality_tiered_partial_exit_v4.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
