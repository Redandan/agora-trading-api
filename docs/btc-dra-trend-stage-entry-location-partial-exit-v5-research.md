# BTC DRA Trend-Stage Entry-Location Partial Exit V5 Research

Date: 2026-08-02

Research identity:
`BTC_DRA_TREND_STAGE_ENTRY_LOCATION_PARTIAL_EXIT_V5_RESEARCH`

## Purpose and boundary

This is a read-only causal research branch. It tests whether the position of
each unchanged DRA V1 entry inside an observable daily EMA20 trend can decide
which lots deserve the full V2A `1.50 ATR` runner and which lots should recycle
capital through the already-audited V3C partial exit.

It does not block, resize, reserve, or quota any buy. Every eligible DRA V1
signal still buys an independent `30 USDT` lot under the `250 USDT` research
reference cap. It preserves DRA V1 arm, 30-day expiry, seven-day cooldown,
entry, fee `0.10%` per side, adverse slippage `0.05%` per side, next-open fill,
profit-only behavior, no stop, no time exit, no forced loss, and no final
liquidation. A sell must remain strictly net-positive after both costs.

This document does not authorize SHADOW or LIVE behavior. It must not modify
Production, runtime, configuration, the database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, Telegram, or orders.

## Causal trend-stage observations

At each complete UTC daily close, calculate the unchanged recursive causal
EMA20. Define `aboveStreakDays` as:

```text
0                                      when close <= EMA20
1                                      when close > EMA20 and prior close <= prior EMA20
priorAboveStreakDays + 1                when close > EMA20 and prior close > prior EMA20
```

At hard dataset inception, the first observed close above EMA20 starts at one.
The engine warms this state only from already complete bars. At a DRA entry
signal close, also calculate:

```text
entryExtensionAtr = (dailyClose - dailyEMA20) / dailyATR14
```

No current incomplete daily candle, future bar, later epoch result, or future
trade outcome enters either feature.

## Preregistered Design-only ablation

Exactly three candidates are permitted; there is no other streak threshold,
ATR threshold, EMA length, ATR trail multiplier, partial ratio, or parameter
scan:

1. `EMA20_ABOVE_STREAK_LE_7_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6`
   - full V2A only when `1 <= aboveStreakDays <= 7`.
2. `EMA20_ABOVE_STREAK_LE_20_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6`
   - full V2A only when `1 <= aboveStreakDays <= 20`.
3. `EMA20_ABOVE_STREAK_LE_7_AND_EXTENSION_LE_1ATR_FULL_V2A_ELSE_NET_POSITIVE_PARTIAL_24_6`
   - full V2A only when `1 <= aboveStreakDays <= 7` and
     `entryExtensionAtr <= 1.0`.

If a required feature is unavailable only at hard dataset inception, the lot
uses full V2A and is recorded as an inception fallback; it cannot make a
candidate appear trend-qualified.

For every non-qualified lot, use the unchanged V3C path:

- after a complete hourly close is below causal hourly EMA5;
- and a `24 USDT` tranche is estimated strictly net-positive after costs;
- queue that tranche and fill only at the next hourly open if it remains
  strictly net-positive;
- retain exactly `6 USDT`, rebase it at the observed partial fill open, clear
  prior ratchet state, and run unchanged V2A `1.50 ATR` on the remainder;
- allow at most one partial tranche per original lot.

Profit is necessary but not sufficient: hourly EMA5 trend deterioration is
also mandatory. There is no `1R` arm in this branch.

## Physical Design/Validation firewall

The `design` stage may query only complete OKX `BTCUSDT` `1h` rows with close
time no later than `2023-01-01T00:00:00`. It must contain exactly `35,064`
rows from `2019-01-01T00:00:00`, pass the existing OHLCV/time-gap audits, and
record its SHA-256. It cannot query or receive any 2023+ row.

It reproduces the frozen Design V1 and V2A checkpoints, evaluates only the
three candidates above, and marks a candidate Design-eligible only when:

- total and realized PnL are at least Design V1;
- ending unrealized PnL is no worse than Design V1;
- drawdown is no more than two percentage points above Design V1;
- median holding is lower than Design V2A; and
- P90 holding is no worse than Design V1.

Among eligible candidates, freeze the highest Design total PnL, then lower
median holding, then lower drawdown, then candidate name. If none is eligible,
emit `DESIGN_NO_CANDIDATE` and do not read Validation.

The later `preselect` stage must receive the byte-stable Design freeze, verify
its specification, runner, data, candidate, and freeze hashes, and rerun the
Design decision exactly before it may query the pre-2025 selection dataset.
This prevents Validation from choosing the formula.

## Frozen pre-2025 selection gates

After one candidate is frozen by Design, use Validation `2023-2024` and
fair-reset folds `2020` through `2024`. Every fold warms indicators causally
but resets entry lifecycle, lots, exit state, capacity, realized PnL, and
equity at the boundary.

Before accepting performance, reproduce the V1, V2A, V2B, V2C, V3B, V3C,
and V4 checkpoints. The sole candidate passes only when all existing gates
hold:

- Validation total PnL is at least V1 and at least `90%` V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation cost-weighted median/P90 holding are at most
  `182.5h / 1,418.3h`;
- annual total wins and annual median-hold wins versus fair-reset V1 are each
  at least `3/5`; and
- all feature, route, positive-fill, cost, quantity, and one-partial audits
  pass.

No gate may be removed, rounded into a pass, or relaxed.

## OOS and decision boundary

The preselection input is physically capped at `2025-01-01T00:00:00` with the
existing expected `52,608` rows and SHA-256
`e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

If the frozen candidate passes every pre-2025 gate, freeze it formally and
permit one explicit 2025+ OOS query plus the independent `30 USDT` one-slot
overlay. Otherwise emit:

```text
NO_CANDIDATE
TREND_STAGE_ENTRY_LOCATION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

Even an OOS pass remains RESEARCH and needs a separate versioned SHADOW
proposal and explicit authorization before any runtime work.

## Reproduction commands

```powershell
python research/btc_dra_trend_stage_entry_location_partial_exit_v5.py design `
  --output <design-freeze.json>
```

```powershell
python research/btc_dra_trend_stage_entry_location_partial_exit_v5.py preselect `
  --design-freeze <design-freeze.json> `
  --output <preselection.json>
```

```powershell
python research/btc_dra_trend_stage_entry_location_partial_exit_v5.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
