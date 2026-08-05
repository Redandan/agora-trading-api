# BTC DRA V3C Pre-Partial 1R Promotion Exit V7 Research

Date: 2026-08-02

Research identity:
`BTC_DRA_V3C_PRE_PARTIAL_ONE_R_PROMOTION_EXIT_V7_RESEARCH`

Single candidate:
`V3C_NONSTRONG_PRE_PARTIAL_1R_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6`

## Objective and distinction from V3B

V3C is the best fast-exit reference so far: Validation total
`89.81822423`, median `184h`, P90 `836h`, and annual median-hold wins `4/5`.
Its weakness is premature `24 USDT` realization on some later winners.

V7 tests one causal exception. It is the opposite of V3B:

- V3B forced every partial-eligible lot to wait until `1R` before it could
  partially sell;
- V7 never makes a weak lot wait for `1R`;
- V7 promotes only a lot that has already reached `1R` before its first
  partial fill, while every other lot keeps unchanged V3C timing.

There is no runner quota, epoch, entitlement, daily breakout, time window,
tie-break, or entry block.

This is read-only historical research and cannot modify or authorize
Production, runtime, configuration, the database, DRA V1, position `263`,
owner `509`, Grid/OCO, funds, schedules, Telegram, or orders.

## Frozen common contract

- Source: server-local read-only OKX `BTCUSDT` complete `1h` bars.
- Entry, arm, 30-day expiry, seven-day cooldown, and next-open fill: unchanged
  DRA V1.
- Independent `30 USDT` lots; `250 USDT` research reference cap.
- Fee `0.10%` and adverse slippage `0.05%` per side.
- No fixed profit percentage, stop loss, time exit, forced loss, or final
  liquidation.
- Every sell queues only from complete causal bars and fills next open only
  while the exact quantity remains strictly net-positive after both costs.

## Unchanged V3C entry routing

At the entry-signal complete daily close:

```text
recent7d = close[t] / close[t-7 complete days] - 1
prior7d  = close[t-7] / close[t-14 complete days] - 1

acceleration = recent7d > 0 and recent7d > prior7d
rangeExpansion = signalDayTrueRange > priorCompleteDayATR14

FULL_V2A = acceleration and rangeExpansion
PARTIAL_ELIGIBLE = not FULL_V2A
```

`FULL_V2A` lots keep the complete `30 USDT` on unchanged V2A `1.50 ATR`.
Hard dataset-inception fallback remains unchanged and is audited separately.

## Exact pre-partial 1R promotion

Each `PARTIAL_ELIGIBLE` lot stores its causal entry ATR14 and original filled
quantity:

```text
entryRisk1R = entryATR14 * originalFilledQuantity

currentFullNetPnl =
    estimated net liquidation value of originalFilledQuantity at hourly close
    - 30 USDT

peakFullNetPnl = max(previousPeakFullNetPnl, currentFullNetPnl)
```

At every complete hourly close, before evaluating the V3C partial condition:

```text
promote =
    partial fill has not occurred
    and lot has not already been promoted
    and peakFullNetPnl >= entryRisk1R
```

The first threshold crossing permanently promotes that lot to full-quantity
V2A. Promotion is not a sale and is not a fixed percentage target. It uses the
entry's causal volatility and observed net profit after costs.

If the `1R` crossing and an EMA5 partial condition occur on the same complete
hour, promotion is processed first and the partial is not queued. Promotion
sets highest close to that crossing close, clears ratchet state, and starts
unchanged V2A `1.50 ATR` from the observable crossing point. Earlier highs are
not reconstructed.

Every qualifying lot promotes independently. Multiple same-hour promotions
are allowed; there is no quota or tie-break. A lot can promote at most once and
a promoted lot can never fill a partial tranche.

## Unchanged V3C fast path

A non-promoted `PARTIAL_ELIGIBLE` lot does not wait for `1R`. It queues exactly
the V3C `24 USDT` tranche when:

```text
complete hourly close < causal hourly EMA5
and estimated partial-tranche net PnL > 0
```

The next-open fill must remain strictly net-positive. The exact `6 USDT`
remainder is rebased at the fill open, prior ratchet state is cleared, and the
remainder uses unchanged V2A `1.50 ATR`. At most one partial fill is allowed.

No alternate `R` threshold, ATR multiplier, EMA, partial ratio, promotion
expiry, or factor is tested.

## Pre-2025 selection protocol

Preselection is physically capped at `2025-01-01T00:00:00`:

- `52,608` complete rows;
- first open `2019-01-01T00:00:00`;
- SHA-256
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before accepting V7, reproduce V1, V2A, V2B, V2C, V3B, V3C, V4, V5, and V6
checkpoints and annual win counts exactly.

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`
through `2024`. Indicators warm causally; entry lifecycle, lots, peak PnL,
promotion, partial, capacity, realized PnL, and equity reset at every boundary.

The sole candidate passes only when every gate holds:

- Validation total PnL is at least V1 and at least `90%` V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation cost-weighted median/P90 holding are at most
  `182.5h / 1,418.3h`;
- annual total wins and annual median-hold wins versus V1 are each at least
  `3/5`;
- every promotion is the unique first causal `1R` crossing, occurs before any
  partial fill, and uses exact entry ATR/original quantity;
- a same-hour crossing always promotes before partial evaluation;
- no promoted lot partially fills and no non-promoted lot is forced to wait
  for `1R`;
- route, cost, quantity, causal EMA, positive-fill, and one-partial audits pass.

No gate may be removed, rounded into a pass, or relaxed.

## OOS and authorization boundary

If every pre-2025 gate passes, bind the candidate to the exact data,
specification, dependency, and runner hashes, then permit one explicit 2025+
OOS query and the independent `30 USDT` one-slot overlay. Otherwise emit:

```text
NO_CANDIDATE
PRE_PARTIAL_1R_PROMOTION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

An OOS pass remains RESEARCH and requires a separate SHADOW/runtime proposal
and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_v3c_pre_partial_one_r_promotion_exit_v7.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_v3c_pre_partial_one_r_promotion_exit_v7.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
