# BTC DRA V7 Liquidity-Harvest Requalification R1 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_V7_LIQUIDITY_HARVEST_REQUALIFICATION_R1_RESEARCH`

Sole candidate:
`V3C_NONSTRONG_PRE_PARTIAL_1R_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE_REQUALIFICATION`

This read-only study requalifies the exact existing V7 strategy under a new
liquidity-harvest objective. It does not change V7, DRA V1, Production,
runtime configuration, database state, orders, or funds, and it does not
authorize SHADOW or LIVE.

## Evidence limitation and purpose

The `2019-2024` data have already been used repeatedly to design and inspect
V1 through V8. They are not an independent holdout. R1 uses them only to:

1. reproduce the complete causal checkpoint chain;
2. confirm that exact V7 satisfies the frozen harvest objective;
3. create one hash-bound candidate manifest.

The actual independent decision evidence is a single sealed OOS opening from
`2025-01-01T00:00:00` through the complete bar ending exactly
`2026-08-02T00:00:00` UTC. No parameter or gate may change after that OOS is
read.

## Exact unchanged V7 candidate

V7 preserves DRA V1 entry, arm, expiry, cooldown, `30 USDT` independent lots,
the `250 USDT` reference cap, `0.10%` fee per side, `0.05%` adverse slippage
per side, next-open fills, and profit-only/no-forced-loss/no-final-liquidation
semantics.

Its entry routing and exits remain byte-for-byte those in the frozen V7 runner:

- entries where both seven-day momentum acceleration and daily range expansion
  pass use the full V2A `1.50 ATR` ratchet;
- every other entry starts on the V3C no-`1R` `24/6` partial path;
- before a partial fill, the unique first causal peak-net-PnL crossing of
  `entryATR14 * originalFilledQuantity` promotes that lot to full V2A;
- a nonpromoted lot never waits for `1R`; strictly net-positive partial PnL and
  hourly close below causal EMA5 queue the `24 USDT` tranche for next-open;
- the `6 USDT` residual is rebased and follows V2A `1.50 ATR`;
- every lot qualifies independently, with no runner quota, epoch, entitlement,
  or tie-break.

There is no fixed profit percentage, stop loss, time exit, forced loss, final
liquidation, changed allocation, threshold scan, or alternate candidate.

## Data and accounting

- Server-local read-only `md_kline` only.
- Source/symbol/interval: OKX `BTCUSDT` complete `1h` candles.
- Preselection cutoff: `2025-01-01T00:00:00`.
- Expected preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- OOS cutoff: exactly `2026-08-02T00:00:00` UTC; the runner rejects an older
  last complete close rather than silently shortening the window.

Realized, unrealized, total, maximum drawdown, cost-weighted median/P90 holding,
turnover, utilization, blocked entries, deferred fills, and every causal and
accounting audit remain required.

The frozen harvest metric is unchanged from V8:

```text
capitalHoursUsdt = referenceCapUsdt
                   * windowHours
                   * avgUtilizationPct / 100

harvestEfficiency = realizedPnlUsdt * 1000 / capitalHoursUsdt
```

It measures realized USDT per `1,000 USDT-hours` of occupied reference capital.
Total and unrealized PnL remain hard protections against hiding inventory loss.

## Pre-2025 manifest gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. Exact V7 freezes only when all hold:

- Validation total PnL is at least V1;
- Validation realized PnL is at least V1;
- Validation ending unrealized is no worse than V1;
- Validation maximum drawdown is no higher than V1 `7.121498%`;
- Validation cost-weighted median/P90 are at most `200h / 1,000h`;
- Validation turnover is at least V1;
- Validation harvest efficiency is strictly greater than V1;
- harvest efficiency beats V1 in at least three of five annual folds;
- total PnL beats V1 in at least two of five annual folds;
- all V7 first-`1R`, promotion, partial, route, cost, quantity, next-open,
  net-positive, and no-quota audits pass.

There is one candidate and no ranking. A pass creates a manifest bound to the
selection-data, R1 specification, V7 specification, V7 runner, V8 harvest-gate
specification, and R1 runner hashes. A failure returns `NO_CANDIDATE` and keeps
OOS sealed.

## Frozen OOS gates

For `2025-01-01T00:00:00` through `2026-08-02T00:00:00`, exact V7 passes only
when all hold:

- OOS total PnL is at least OOS V1;
- OOS realized PnL is at least OOS V1;
- OOS ending unrealized is no worse than OOS V1;
- OOS maximum drawdown is no higher than OOS V1;
- OOS cost-weighted median/P90 are at most `200h / 1,000h`;
- OOS turnover is at least OOS V1;
- OOS harvest efficiency is strictly greater than OOS V1;
- every V7 causal, promotion, route, quantity, cost, partial, next-open, and
  strictly-net-positive audit passes.

No OOS threshold may be weakened, rounded into a pass, or replaced after
results are seen. `OUT_OF_SAMPLE_FAIL` stops the branch.

## Independent one-slot overlay

After a valid preselection manifest opens OOS, the same run reports both V1
and V7 under an independent `30 USDT` capacity for Design, Validation, every
annual fold, and OOS. This overlay changes only reference capacity; it does not
simulate or place a Production order.

## Authorization boundary

R1 may perform one server-local read-only OOS export only after preselection
freezes exact V7. It cannot modify or deploy Production/runtime/config/database/
DRA V1/position `263`/owner `509`/Grid/OCO/funds/schedules/Telegram/orders.
A historical and OOS pass still requires a separate SHADOW proposal and owner
authorization. It never authorizes LIVE.

## Reproduction commands

```powershell
python research/btc_dra_v7_liquidity_harvest_requalification_r1.py preselect `
  --output <new-preselection.json>
```

```powershell
python research/btc_dra_v7_liquidity_harvest_requalification_r1.py oos `
  --preselect <accepted-preselection.json> `
  --cutoff 2026-08-02T00:00:00 `
  --output <new-oos.json>
```

OOS output must not already exist. The runner refuses overwrite and validates
the exact manifest and all dependency hashes before any post-2024 query.
Database credentials remain server-local and are never printed or copied.
