# BTC DRA Reversal-Confirmed Volatility Exit V2C Research

Status: `RESEARCH_ONLY / OOS_SEALED / NOT_IN_RUNTIME_CATALOG / NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE`

Research identity: `BTC_DRA_REVERSAL_CONFIRMED_VOLATILITY_EXIT_V2C_RESEARCH`

## Objective and preregistered expectation

Test whether a small set of causal, observable trend-reversal confirmations can
shorten the V2A ATR-trail holding distribution without surrendering its trend
capture. V2C is a new frozen study. It does not reopen or relax the failed V2A
or V2B selection decisions.

The expected Validation case, frozen before candidate results are read, is:

- total PnL between DRA V1 `86.20298186 USDT` and V2A ATR trail `1.50`
  `113.25094608 USDT`;
- maximum drawdown no higher than `9.121498%`, the unchanged V1 plus two
  percentage-point gate;
- median and P90 holding no worse than the V1 `182.5h / 1,418.3h` reference;
- a low probability that all profitability, holding, and annual-fold gates pass
  together. Failure must return `NO_CANDIDATE` without lowering a gate.

The opportunity cost is limited to a local research runner and research
documents. No Java runtime, strategy catalog, scheduler, database, deployment,
or Production work is part of this study.

## Frozen common contract

- Source: server-local, read-only Production `md_kline` rows for OKX
  `BTCUSDT`, closed `1h` bars.
- Entry: exactly DRA V1 daily close above EMA20, EMA20 above its value five
  daily closes earlier, positive 24-hour momentum, seven-day cooldown, and
  30-day arm expiry.
- Fill timing: signal on a complete closed bar and fill at the next `1h` open.
- Lot size: `30 USDT`; reference reserve: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots remain independent. No average-cost exit is permitted.
- No stop loss, time exit, forced loss exit, or final liquidation.
- A V2C sell may queue only when estimated liquidation after fee and adverse
  slippage is net positive. The next-open fill is deferred unless it remains
  strictly net positive.
- V2C contains no fixed profit percentage. DRA V1's fixed `+5% / +1%`
  semantics exist only in the checkpoint evaluator.

## Data seal and checkpoint gate

Preselection queries only rows whose close time is at or before
`2025-01-01T00:00:00`. No post-2024 row may be fetched before a single candidate
is formally frozen.

The accepted preselection input is frozen as:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- gaps, duplicates, off-grid rows, non-one-hour durations, and invalid OHLCV:
  all zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before any V2C candidate is accepted, the runner must exactly reproduce, with
no numeric tolerance:

1. DRA V1 Design and Validation ledgers;
2. V2A ATR trail `1.50` Validation realized, unrealized, total, drawdown,
   median hold, and P90 hold;
3. all three V2B Validation profiles plus their preregistered annual total and
   median-hold win counts.

Any mismatch returns `BASELINE_PARITY_REJECT`. Data-quality or input-hash drift
returns `DATA_REJECT`. Neither result permits candidate evaluation.

## Shared V2C exit architecture

Every candidate retains V2A's causal complete-day Wilder ATR14 and its fixed
`1.50 ATR` monotonic ratchet:

```text
base candidate stop = highest closed-hour close since fill - ATR14 * 1.50
base ratchet stop   = max(previous base ratchet stop, base candidate stop)
```

The baseline ratchet may queue a net-positive exit exactly as in V2A. The only
new behavior is one preregistered reversal confirmation. At a complete UTC-day
close, a confirmed reversal may queue an earlier exit when estimated net PnL
is strictly positive. Thus a V2C factor can shorten, but cannot extend, V2A's
lot path. There is no tightening-multiplier search.

## Preregistered factor ablation

All inputs use the current or earlier complete candles only.

1. `EMA20_SLOPE_NONPOSITIVE`
   - current complete-day EMA20 is less than or equal to its value five
     complete daily closes earlier.
2. `CLOSE_BELOW_EMA5`
   - current complete-day close is below the recursive causal daily EMA5.
3. `ATR1_REVERSAL`
   - the decline from the lot's highest closed-hour close through the current
     complete-day close is at least the current complete-day ATR14.
4. `DONCHIAN5_NEGATIVE_MOMENTUM`
   - current complete-day close is below the lowest low of the prior five
     complete UTC days, excluding the current day, and below the prior
     complete-day close.
5. `CONSENSUS_2_OF_4`
   - at least two of the four confirmations above are true on the same
     complete-day close.

EMA5 uses `alpha = 2 / (5 + 1)`, initializes from the first available complete
daily close in the causal warm-up, and follows the same deterministic decimal
rounding as the EMA20 checkpoint path. ATR14 is the same Wilder series used by
V2A and V2B. No look-ahead, intrabar high/low exit, future percentile, or
post-window state is allowed.

## Frozen selection protocol

1. Use Design `2019-2022`, Validation `2023-2024`, and fair-reset calendar
   folds `2020`, `2021`, `2022`, `2023`, and `2024`. A fold warms indicators
   causally but resets arms, cooldown, lots, realized PnL, and equity at its
   boundary.
2. Report buys, sells, open lots, blocked entries, deferred exits, realized,
   ending unrealized, total, maximum drawdown, average and peak utilization,
   median/P90 holding, turnover, and exit-trigger attribution.
3. A candidate passes only when every condition holds:
   - Validation realized and total PnL are not below V1;
   - Validation ending unrealized PnL is not worse than V1;
   - Validation maximum drawdown is no more than two percentage points above
     V1;
   - Validation median and P90 holding are both no worse than V1;
   - Validation total PnL retains at least `90%` of V2A ATR trail `1.50`;
   - it beats fair-reset V1 total PnL in at least three of five annual folds;
   - it improves fair-reset V1 median holding in at least three folds.
4. Rank passing candidates by Validation total PnL, then lower drawdown,
   shorter P90 holding, and shorter median holding.
5. Freeze exactly one candidate or `NO_CANDIDATE`. No parameter, factor, or
   gate may be changed after results are inspected.
6. Only after a candidate is frozen, calculate its independent `30 USDT`
   one-slot Design and Validation overlay. The overlay uses `30 USDT` as both
   capacity and equity reference; blocked entries remain explicit.

## OOS one-open rule

Only a `CANDIDATE_FROZEN` manifest tied to the exact preselection data and
runner hashes may open `2025-01-01` onward once. The OOS output path must not
already exist. OOS compares V1 and the frozen V2C candidate under the same
unchanged gates and also reports the independent one-slot overlay.

An OOS miss returns `OUT_OF_SAMPLE_FAIL`; it cannot trigger reselection or a
second OOS opening. `NO_CANDIDATE` keeps OOS sealed and does not run an overlay.

## Reproduction commands

Preselection, which cannot query post-2024 bars:

```powershell
python research/btc_dra_reversal_confirmed_exit_v2c.py preselect `
  --output <preselection.json>
```

OOS, permitted only for a frozen candidate and an explicit final cutoff:

```powershell
python research/btc_dra_reversal_confirmed_exit_v2c.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner needs the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`
values. It reads database credentials only inside the server process and never
prints or copies them locally.

## Promotion boundary

A historical pass is research evidence only. It does not add V2C to the
runtime catalog, authorize SHADOW or LIVE, modify DRA V1 or position `263`,
change owner `509`, Grid/OCO, funds, schedules, database state, or send an
order. Any later runtime proposal requires a separate versioned contract,
forward-evidence plan, risk review, and explicit authorization.
