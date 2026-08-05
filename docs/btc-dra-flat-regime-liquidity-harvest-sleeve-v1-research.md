# BTC DRA Flat-Regime Liquidity-Harvest Sleeve V1 Research

Research identity:
`BTC_DRA_FLAT_REGIME_LIQUIDITY_HARVEST_SLEEVE_V1_RESEARCH`

Status before execution: `PREREGISTERED_POST_HOC_HISTORICAL_RESEARCH_ONLY`.

## Boundary

This is a read-only research contract. It does not authorize `SHADOW`, `PAPER`,
or `LIVE`, and it must not change Production, runtime/configuration, DRA V1,
position 263, owner 509, Grid/OCO, funds, schedules, databases, Telegram, or
orders.

The candidate is a separate flat-regime sleeve. It is not a replacement for
the DRA V1 trend entry and it does not require a rising trend. The routed
portfolio below is a research comparator only.

## Contamination ledger

- The 2026-07 price path was inspected before this contract and generated the
  flat-regime hypothesis.
- The `0.25 ATR14` flatness formula and its 2019-2024 headline metrics were also
  inspected before this formal goal.
- DRA V7 2025+ OOS was already opened, and 2026-07 was inspected trade by
  trade.

Therefore 2019-2024 is reproducibility and historical robustness evidence,
not a clean selection set. The runner must never label 2025+ or 2026-07 as a
new OOS. A historical gate pass can produce only `FORWARD_PENDING`; it cannot
authorize activation.

## Data and accounting

- Source: server-local `md_kline`, `source=okx`, `symbol=BTCUSDT`,
  `interval_code=1h`.
- Only complete, gap-free, causal hourly bars are allowed.
- Historical cutoff: `2025-01-01T00:00:00Z`.
- Design: `2019-01-01` through `2022-12-31`.
- Validation: `2023-01-01` through `2024-12-31`.
- Fair-reset folds: calendar years 2020 through 2024.
- Each lot costs `30 USDT`; reference open-cost cap is `250 USDT`.
- Fee is `0.10%` per side and adverse slippage is `0.05%` per side.
- Signals use complete bars and fill at the next hourly open.
- Lots are independent. There is no stop loss, time exit, forced loss, or final
  liquidation. Every sell fill must remain strictly net-positive after fee and
  slippage.

## Frozen flat-regime sleeve

All indicators use current or past complete bars only.

Daily EMA20 and Wilder ATR14 update on the complete UTC daily close. At any
hour, `EMA20_5D_AGO` is the EMA20 value from five complete UTC days earlier.

```text
flat = abs(EMA20 - EMA20_5D_AGO) <= 0.25 * ATR14
```

No positive EMA slope, breakout, positive daily momentum, or DRA V1 trend
signal is required.

The existing DRA timing discipline is retained as a research control: a
30-day arm window and seven-day cooldown after an accepted entry signal.

Entry on a complete hourly bar requires:

```text
flat
and previousHourlyClose <= causalDailyEMA20
and currentHourlyClose > causalDailyEMA20
```

An accepted signal buys one `30 USDT` lot at the next hourly open, subject to
the `250 USDT` open-cost cap.

For each lot:

```text
R = entryCausalATR14 * filledBTCQuantity
peakNetPnl = max(causal estimated full-lot net PnL since fill)
armed = peakNetPnl >= 1.0R
exit = armed
       and currentHourlyClose < causalHourlyEMA5
       and currentEstimatedNetPnl >= 0.5R
```

The complete lot queues for next-open sale. The fill is cancelled if actual
modeled next-open net PnL is not strictly positive.

## Comparators

1. Frozen DRA V1, unchanged.
2. Flat sleeve at the `250 USDT` reference cap.
3. Independent `30 USDT` one-slot accept/block overlay. A signal while the
   slot is occupied is counted as blocked; this is not a direct scaling claim.
4. Research-only mutually exclusive router:
   - while `flat`, only the flat reclaim entry may open a new lot;
   - while not `flat`, only the unchanged DRA V1 entry may open a new lot;
   - existing lots retain their own exit policy;
   - flat lots use the frozen `1R/EMA5/0.5R` exit;
   - V1-routed lots use the unchanged V1 exit;
   - arm, cooldown, and the `250 USDT` cap are shared.

## Frozen gates

Baseline parity must reproduce the historical DRA V1 Validation checkpoint.

The standalone flat sleeve passes historical robustness only if all are true:

- Validation realized PnL and total PnL are positive.
- Validation unrealized PnL is no worse than DRA V1.
- Validation maximum drawdown is no higher than DRA V1.
- Validation median and P90 holding hours are no higher than DRA V1.
- At least four of five fair-reset folds have positive total PnL.

The routed portfolio passes only if all are true:

- Validation total and realized PnL are at least DRA V1.
- Validation unrealized PnL is no worse than DRA V1.
- Validation drawdown is at most DRA V1 plus two percentage points.
- Validation median and P90 holding hours are no higher than DRA V1.
- It beats DRA V1 total PnL and median holding in at least three of five folds.

The one-slot overlay must report the same ledger fields and blocked entries. It
cannot override a failed standalone or routed gate.

If every historical gate passes, status is
`HISTORICAL_GATE_PASS_FORWARD_PENDING`. Otherwise status is
`NO_CANDIDATE`. Neither status authorizes `SHADOW` or `LIVE`.

## Outputs

The runner must emit deterministic JSON containing input hashes, specification
and runner hashes, complete metrics, gate booleans, entry/exit audit counts,
one-slot evidence, and a separate 2026-07 post-hoc diagnostic. It must not
write over an existing output file.
