# BTC DRA Independent-Lot Conditional Partial De-Risk V3 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_CONDITIONAL_PARTIAL_DE_RISK_V3_RESEARCH`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It does not modify DRA V1, Production,
runtime configuration, database state, orders, or funds, and it does not
authorize SHADOW or LIVE. The architecture, three factor ablations, accounting
rules, and gates below are frozen before their performance is queried.

## Why this is a new contract dimension

The loose independent-lot profit lock retained more total value than V1 but
left its advantage unrealized, held too long, and drew down too far. The tighter
hybrid lock fixed ending inventory and drawdown but sold future winners too
early. A static quantity split cannot resolve this frontier: interpolating the
plain `1R` core total (`61.85809397`) and V2A total (`113.25094608`) requires at
least `77.9636775%` of capital to remain on V2A merely to retain `90%` of V2A,
while an early tranche must exceed `50%` of capital to control a
capital-weighted median.

This study therefore tests conditional, not static, partial realization. Every
DRA entry still fills normally. An observable entry-day continuation regime
decides whether the full lot remains on V2A or whether a later profitable
hourly deterioration may release two thirds of its capital while a one-third
remainder keeps trend exposure.

## Unchanged shared contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill remain exact. No entry-quality factor may block a buy.
- Original lot cost: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots are independent. There is no runner quota, entitlement, epoch slot, or
  average-cost exit.
- No stop loss, time exit, forced loss, or final liquidation.
- A sale may fill only at the next `1h` open and only when that exact tranche
  remains strictly net positive after both sides' fee and adverse slippage.
- The `1R` threshold only arms partial realization. It is not sufficient by
  itself to sell and is not a fixed percentage-profit exit.

## Frozen 20/10 partial architecture

Each filled lot stores the entry signal day's causal daily state and:

```text
originalCost = 30 USDT
partialCost  = 20 USDT
runnerCost   = 10 USDT
entryRisk1R  = entryATR14 * originalFilledQuantity
```

Quantity is split in the same `2/3 : 1/3` ratio as cost. The partial quantity
is rounded down at the existing quantity precision and the remainder receives
all residual quantity, so quantity is conserved exactly.

An entry classified `FULL_V2A` never partially realizes and uses the unchanged
V2A causal `1.50 ATR` ratchet for its full `30 USDT` lot.

An entry classified `PARTIAL_ELIGIBLE` becomes permanently armed only after
its peak estimated full-lot net PnL reaches `entryRisk1R`. It queues the
`20 USDT` tranche only when, on a later or same complete hourly close:

```text
armed == true
hourlyClose < causalHourlyEMA5
estimated partial-tranche net PnL > 0
```

Hourly EMA5 uses `alpha = 2 / (5 + 1)`, initializes from the first causal
warm-up hourly close, and is updated using the current complete hourly close.
Thus profitability is necessary but a causal trend deterioration is also
required.

At a successful next-open partial fill:

- realized PnL is exact net proceeds minus the allocated `20 USDT` cost;
- the remaining lot keeps exactly `10 USDT` cost and one third of original
  quantity;
- its highest-close state is rebased to the observed fill open;
- its prior ratchet is cleared; and
- from that bar onward it uses the unchanged V2A `1.50 ATR` monotone ratchet.

If the next-open partial tranche is not strictly net positive, the queue is
cleared and the armed lot remains eligible. A remaining or full V2A tranche
also fills only if strictly net positive.

## Preregistered entry-day ablation

All inputs are known at the DRA entry-signal close. These labels select an exit
path only; all qualifying DRA buys still occur.

At the hard dataset inception only, a DRA signal can precede the 14 complete
days required by these labels and entry ATR14. Such a lot is conservatively
assigned `FULL_V2A`, which does not need entry ATR to arm a partial sale. It is
never skipped or partially sold. The count is reported. Validation and every
fair-reset fold have causal warm-up available and must have zero missing route
inputs; otherwise the candidate fails. This inception fallback was frozen
after a pre-performance structural rejection and before any candidate metric
was produced.

1. `ENTRY_7D_MOMENTUM_ACCELERATION_FULL_V2A_ELSE_PARTIAL_20_10`
   - `recent7d = close[t] / close[t-7 complete days] - 1`;
   - `prior7d = close[t-7] / close[t-14 complete days] - 1`;
   - `FULL_V2A` only when `recent7d > 0` and `recent7d > prior7d`.
2. `ENTRY_DAILY_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_20_10`
   - signal-day true range uses only that completed UTC day's OHLC and the
     prior complete-day close;
   - `FULL_V2A` only when signal-day true range is greater than the prior
     complete day's causal Wilder ATR14.
3. `ENTRY_ACCELERATION_OR_RANGE_EXPANSION_FULL_V2A_ELSE_PARTIAL_20_10`
   - `FULL_V2A` when either frozen condition above is true.

There is no threshold, lookback, allocation, ATR multiplier, EMA period, or
logical-form scan beyond these three predeclared factor ablations.

## Holding and accounting semantics

Because a lot may produce two exits, the primary holding distribution is
cost-weighted capital holding time. Each exit contributes its allocated entry
cost (`30`, `20`, or `10 USDT`) at its fill age. V1's equal `30 USDT` lots make
its ordinary median/P90 exactly equivalent to this weighting, so the frozen
holding gates remain comparable and are not redefined to obtain a pass.

The study also reports unweighted exit-slice holding, time to first
realization, time to final lot closure, capital-hours, original-lot completion,
partial/full fill counts, route counts, route PnL, open cost, utilization,
turnover, blocked entries, and deferred fills. Selection uses the
cost-weighted median and P90; final-closure statistics are diagnostic and may
not replace them.

Capacity uses actual remaining allocated cost, not `30 * open lot count`.
Partial proceeds return capacity after fill. Every original quantity and cost
allocation must reconcile exactly, and every realized tranche must be strictly
net positive.

## Frozen data and checkpoint gate

Preselection may query only complete rows with close time at or before
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- all data-quality defect counts: zero;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before candidate results are accepted, the runner must reproduce V1, V2A,
V2B, V2C, V2D, V2E, the structural one-runner diagnostic, the loose
independent-lot lock, and the hybrid lock checkpoint. Hash or data drift is a
hard `PREREGISTRATION_REJECT`, `DATA_REJECT`, or `BASELINE_PARITY_REJECT`.

## Frozen selection gates

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`,
`2021`, `2022`, `2023`, and `2024`. Indicators warm causally; entry, lot,
partial, ratchet, accounting, and capacity state reset at every boundary.

A candidate passes only when all conditions hold:

- Validation total PnL is at least V1 and at least `90%` of V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation cost-weighted median holding is at most `182.5h`;
- Validation cost-weighted P90 holding is at most `1,418.3h`;
- total PnL beats V1 in at least three of five fair-reset folds;
- cost-weighted median holding beats V1 in at least three folds;
- no entry is rejected by an exit-route label;
- Validation and every fair-reset-fold bought lot have causal entry ATR and
  complete entry-route inputs; any Design-only hard-inception fallback is
  reported and must remain on `FULL_V2A`;
- quantity and allocated cost reconcile exactly;
- every partial queue was armed, below causal hourly EMA5, and net positive;
- every exit fill is strictly net positive after costs; and
- no partial-eligible lot fills more than one partial tranche.

No gate may be relaxed, rounded into a pass, or replaced after results are
seen. Passing candidates rank by Validation total, then lower drawdown,
shorter P90, shorter median, and fewer ending open lots. Freeze exactly one or
`NO_CANDIDATE`.

## OOS and authorization boundary

Only a `CANDIDATE_FROZEN` manifest bound to exact data, specification,
dependency, candidate, and runner hashes may query 2025+ once. A pass then runs
the independent `30 USDT` one-slot overlay for Design, Validation, folds, and
OOS. Otherwise emit:

```text
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

This research cannot modify or deploy Production/runtime/config/database/DRA
V1/position `263`/owner `509`/Grid/OCO/funds/schedules/Telegram/orders. A
historical pass still requires a separate SHADOW proposal and authorization.

## Reproduction commands

```powershell
python research/btc_dra_independent_lot_conditional_partial_de_risk_v3.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_independent_lot_conditional_partial_de_risk_v3.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain server-local and are never printed or copied.
