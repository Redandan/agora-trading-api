# BTC DRA Net-Positive EMA-Deterioration Partial V3C Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_NET_POSITIVE_EMA_DETERIORATION_PARTIAL_V3C_RESEARCH`

Candidate:
`ENTRY_ACCELERATION_AND_RANGE_EXPANSION_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It cannot modify or deploy Production,
runtime, configuration, database, DRA V1, position `263`, owner `509`,
Grid/OCO, funds, schedules, Telegram, or orders, and it does not authorize
SHADOW or LIVE. This single terminal candidate is frozen before performance is
queried.

## V3B diagnosis and terminal hypothesis

V3B preserved the required profit and risk frontier: Validation realized
`108.20391069`, total `104.99570948`, drawdown `6.388173%`, P90 `1,329.1h`,
and annual total wins `4/5`. It failed only cost-weighted median (`317h`) and
annual median wins (`0/5`).

The `24/6` allocation and conjunctive entry routing worked as designed: 18
entries stayed full V2A, 33 were partial-eligible, and 32 partial fills
completed. The partial exits themselves had a `147h` median, but only 22
occurred by `182.5h`; the remaining ten waited for the separate `1R` maturity
condition, including extreme `1,255h`, `2,182h`, and `3,764h` waits.

The user contract requires profit to be necessary but not sufficient; it does
not require a `1R` profit target. V3C therefore removes the maturity target and
retains the actual trend-deterioration confirmation. This directly tests
whether `1R`, rather than the reasonable sell condition, caused the remaining
holding failure.

No ratio, EMA, ATR, entry factor, or gate changes. This is the final candidate
in the partial-de-risk branch; failure returns `NO_CANDIDATE` without another
threshold or factor iteration.

## Unchanged contract and route

- Server-local read-only `md_kline`, OKX `BTCUSDT`, complete `1h` candles only.
- Exact DRA V1 entry, arm, 30-day expiry, seven-day cooldown, and next-open
  fill. Route labels never reject a qualifying buy.
- Original lot `30 USDT`; reference capacity `250 USDT`.
- Fee `0.10%` per side; adverse slippage `0.05%` per side.
- Independent lots; no runner quota, entitlement, epoch slot, or average-cost
  exit.
- No stop loss, time exit, forced loss, or final liquidation.
- Every queued sale fills only at the next hourly open and only when its exact
  tranche remains strictly net positive after all costs.
- Hard-data-inception inputs follow the frozen conservative full-V2A fallback;
  Validation and annual folds must have zero missing route inputs.

The entry-signal day's causal route remains exactly V3B:

```text
recent7d = close[t] / close[t-7 complete days] - 1
prior7d  = close[t-7] / close[t-14 complete days] - 1

acceleration = recent7d > 0 and recent7d > prior7d
rangeExpansion = signalDayTrueRange > priorCompleteDayATR14

FULL_V2A = acceleration and rangeExpansion
PARTIAL_ELIGIBLE = not FULL_V2A
```

A `FULL_V2A` lot keeps all `30 USDT` on unchanged causal V2A `1.50 ATR`.

A `PARTIAL_ELIGIBLE` lot keeps the frozen V3B cost/quantity split:

```text
partialCost = 24 USDT
remainderCost = 6 USDT
partialQuantity = floor(originalQuantity * 4 / 5, existing quantity precision)
remainderQuantity = originalQuantity - partialQuantity
```

## Sole changed sell condition

There is no `1R` arm and no fixed profit target. At each complete hourly close,
the `24 USDT` tranche queues only when both conditions are true:

```text
hourlyClose < causalHourlyEMA5
estimated partial-tranche net PnL > 0
```

Hourly EMA5 remains V3/V3B's recursive `alpha = 2 / (5 + 1)` series using
only the current and prior complete hourly closes. Strict positive PnL is
necessary but not sufficient: an observable causal trend deterioration is
also mandatory. A next-open fill is deferred unless exact adverse-fill PnL is
still strictly positive.

After a successful fill, the exact `6 USDT` residual quantity is rebased at the
observed fill open, prior ratchet state is cleared, and the remainder uses
unchanged V2A `1.50 ATR`. At most one partial fill may occur per original lot.

## Accounting, holding, and audit

Capacity uses actual allocated open cost. Selection uses cost-weighted holding
with `30`, `24`, and `6 USDT` slice weights; V1's equal `30 USDT` lots are
exactly equivalent to its ordinary holding distribution. Report unweighted
slice holding, first realization, final completion, capital-hours,
utilization, turnover, blocked entries, deferred fills, route counts/PnL, and
ending open cost as diagnostics.

Every entry must be retained, every original quantity and `30 USDT` cost must
reconcile, every partial queue must be below causal EMA5 and estimated
net-positive, every fill must be strictly net-positive, and no original lot
may fill more than one partial tranche.

## Frozen data, checkpoint, and selection gates

Preselection is physically capped at close time
`2025-01-01T00:00:00`: `52,608` complete rows from
`2019-01-01T00:00:00`, SHA-256
`e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`,
with all data defects zero.

Before V3C is accepted, reproduce V1 through V3, plus V3B Validation, route
audit, and annual wins exactly. Drift or mismatch is a hard rejection.

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`
through `2024`. The candidate passes only if all gates hold:

- Validation total is at least V1 and at least `90%` V2A;
- Validation realized is at least V1;
- ending unrealized is no worse than V1;
- drawdown is at most `9.121498%`;
- cost-weighted median/P90 are at most `182.5h / 1,418.3h`;
- annual total wins and cost-weighted median wins are each at least `3/5`;
- Validation/fold route inputs and all quantity, cost, trigger, positive-fill,
  and single-partial audits pass.

No gate may be relaxed or rounded into a pass.

## OOS and terminal boundary

If all gates pass, freeze this exact candidate and only then allow one explicit
2025+ OOS query plus the independent `30 USDT` one-slot overlay. If any gate
fails, emit:

```text
NO_CANDIDATE
PARTIAL_DE_RISK_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

A historical pass remains RESEARCH, not SHADOW or LIVE, and still needs a
separate runtime proposal and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_net_positive_ema_deterioration_partial_v3c.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_net_positive_ema_deterioration_partial_v3c.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
