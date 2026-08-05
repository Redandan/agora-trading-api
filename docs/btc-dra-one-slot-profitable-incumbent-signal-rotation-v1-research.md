# BTC DRA One-Slot Profitable-Incumbent Signal Rotation V1 Research

Status: `FROZEN_POST_HOC_HISTORICAL_DIAGNOSTIC`

Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Question

Can a fixed `30 USDT` one-slot DRA lane recover blocked opportunity cost by
rotating an already-profitable incumbent into a fresh confirmed DRA signal,
without adding capital, selling a loss, increasing drawdown, or weakening the
existing next-open fill safety rule?

This is a new one-slot economic mechanism. It does not change the multi-lot
DRA V1 reference and cannot activate or modify Production.

## Economic thesis

The one-slot parent is occupied roughly ninety percent of the time and records
many capacity-blocked confirmations. Some blockage may come from an incumbent
that is already safely profitable but has not yet reached the ordinary `+5%`
queue threshold. A new confirmed DRA signal is an event-defined opportunity to
compare retaining that incumbent with recycling the same `30 USDT` slot.

The expected benefit is higher fee-adjusted total PnL and fewer blocked
confirmations under the same capital. The explicit opportunity cost is lost
future convexity from selling the incumbent before `+5%`. That cost must remain
visible in realized, unrealized, total PnL, drawdown, holding age, and annual
folds.

## Frozen data

- source: read-only server-local `md_kline`;
- market: OKX `BTCUSDT`, complete `1h` bars;
- selection cutoff: `2025-01-01T00:00:00`;
- rows: `52,608`;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- Design: `[2019-01-01, 2023-01-01)`;
- Validation: `[2023-01-01, 2025-01-01)`;
- fair-reset annual folds: calendar years `2020` through `2024`;
- warmup: prior 90 days, indicators only.

The runner must reject gaps, duplicates, off-grid timestamps, invalid one-hour
duration, invalid OHLCV, wrong row count, wrong hash, or data beyond the
selection cutoff.

## Frozen parent

The parent is the unchanged Python DRA V1 engine with:

- capital and maximum open cost: `30 USDT`;
- exactly one independent `30 USDT` lot;
- fee: `0.10%` per side;
- adverse slippage: `0.05%` per side;
- ordinary exit queue: estimated-net return at least `+5%` on the hourly close;
- actual sell: next hourly open only when realized-net return remains at least
  `+1%`;
- seven-day accepted-signal cooldown and 30-day arm expiry;
- no loss sale, time exit, stop, forced liquidation, leverage, or added funds.

Required exact parent checkpoints:

| Window | Realized | Unrealized | Total | DD | Median / P90 | Buy / Sell / Open | Blocked | Avg util. | Turnover |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Design | 51.82581283 | -22.31327703 | 29.51253580 | 40.321240% | 112.0 / 1704.8 h | 29 / 28 / 1 | 273 | 90.089551% | 891.82581283 |
| Validation | 43.54302055 | -3.60947416 | 39.93354639 | 17.699055% | 146.0 / 775.6 h | 26 / 25 / 1 | 117 | 88.377793% | 793.54302055 |

Any mismatch returns `BASELINE_REJECT` before candidate interpretation.

## One frozen candidate

Identity: `FRESH_DRA_SIGNAL_PROFITABLE_INCUMBENT_ROTATION_V1`.

The candidate inherits the parent exactly except at a capacity-blocked DRA
confirmation:

1. Entry confirmation uses the unchanged DRA V1 closed-bar formula and
   lifecycle ordering.
2. If no lot is open, the ordinary parent next-open buy is unchanged.
3. If one lot is open, compute its estimated-net return from the current
   closed hourly bar using the frozen fee and adverse-sell assumptions.
4. A rotation is eligible only when that return is at least the existing
   `+1%` realized-net fill floor. There is no new threshold.
5. If the incumbent already has the ordinary `+5%` exit queued, keep that
   queue. Otherwise queue it specifically for rotation.
6. Reserve the fresh signal, advance the normal seven-day accepted-signal
   cooldown, and record one rotation attempt.
7. At the next hourly open, process the incumbent sale before any buy. The
   unchanged `+1%` realized-net floor still applies.
8. Only after a successful incumbent sale may the replacement buy fill at the
   same next-open adverse-buy price and normal buy fee.
9. If the sale is deferred below `+1%`, cancel the replacement permanently,
   keep the incumbent, retain the consumed cooldown, and add no exposure.
10. If the incumbent is below `+1%` at confirmation, preserve the parent
    capacity block; do not queue, reserve, resize, or sell anything.

No rotation fraction, age, extra signal, EMA/ATR threshold, target, timeout,
retry, or alternative cancellation rule may be tested.

## Accounting and causal audits

The candidate must prove:

- at most one open lot and maximum open cost no greater than `30 USDT`;
- every replacement buy occurs after a successful same-open incumbent sale;
- every rotation sale realizes at least `+1%` net after frozen costs;
- a deferred rotation sale produces no replacement buy;
- attempts equal successful sales plus cancellations plus any explicitly
  reported terminal pending attempt;
- successful sales equal replacement buys;
- no same-bar close information is filled before the next hourly open;
- ordinary V1 entries, exits, arm, expiry, fee, quantity, and cooldown
  semantics are unchanged outside the rotation branch;
- realized plus unrealized equals total PnL, with terminal inventory visible;
- the output path is sealed and never overwritten.

Any failure returns `ACCOUNTING_REJECT` or `LEAKAGE_REJECT`.

## Frozen economic gates

The candidate passes historical screening only if every gate below is true:

1. Design total PnL is at least the one-slot parent.
2. Design maximum drawdown is no higher than the parent.
3. Validation realized PnL is strictly greater than the parent.
4. Validation total PnL is strictly greater than the parent.
5. Validation ending unrealized PnL is no worse than the parent.
6. Validation maximum drawdown is no higher than the parent.
7. Validation median and P90 holding hours are both no higher than the parent.
8. Validation capacity-blocked confirmation count is strictly lower.
9. Validation completes at least five successful rotation replacements.
10. At least three of five annual folds beat the parent on total PnL.
11. At least three of five annual folds have maximum drawdown no higher than
    the parent.
12. There is no terminal pending rotation in Design or Validation.
13. Every causal and accounting audit passes.

No gate may be relaxed after results.

## Decision boundary

- data or parent mismatch: `DATA_REJECT` / `BASELINE_REJECT`;
- causal or accounting failure: `LEAKAGE_REJECT` / `ACCOUNTING_REJECT`;
- any economic gate fails:
  `NO_CANDIDATE_KEEP_ONE_SLOT_DRA_V1`;
- all historical gates pass:
  `HISTORICAL_GATE_PASS_NO_CLEAN_OOS`.

This hypothesis was formulated after extensive historical DRA research and
the research program has already observed post-2024 market context. Therefore
`2025+` is not opened or described as clean OOS for this branch. A historical
pass may justify a new prospective manifest from a future untouched cutoff,
but it remains `REPORTED_NOT_ACTIVATED`.

Java Phase C is not built for this candidate before the Python historical gate
passes. A rejected economic mechanism does not justify additional Java
infrastructure.
