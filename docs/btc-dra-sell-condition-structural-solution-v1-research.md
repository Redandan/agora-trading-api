# BTC DRA Sell-Condition Structural Solution V1 Research

Date frozen: 2026-08-02

Research identity:
`BTC_DRA_SELL_CONDITION_STRUCTURAL_SOLUTION_V1_RESEARCH`

Candidate:
`GLOBAL_ONE_ACTIVE_FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_RUNNER`

Status at freeze: `PREREGISTERED_PRE_PERFORMANCE`

This is a read-only historical study. It is neither DRA V2F nor permission to
change DRA V1. It tests one minimal core/runner architecture after V2A through
V2E exposed a structural conflict: broad ATR runners retained trend profit but
held too long, while faster full-lot exits recycled capital but sold too many
future large winners.

## Frozen research question

Can the V2E fast core recover V1/V2A economics and improve holding-time
stability if runner concurrency, rather than another price factor, is the
sparsity control?

The study changes exactly one V2E rule: there may be no more than one open
runner globally at any instant. It does not scan a threshold, multiplier,
indicator, lookback, runner count, or tie-break.

## Unchanged common contract

- Source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`
  candles only.
- DRA V1 entry signal, arm, 30-day expiry, seven-day cooldown, and next-open
  entry fill remain exact.
- Lot cost: `30 USDT`; reference capacity: `250 USDT`.
- Fee: `0.10%` per side; adverse slippage: `0.05%` per side.
- Lots are independent. No averaging or future-aware lot reassignment is
  allowed.
- No stop loss, time exit, forced loss, or final liquidation is allowed.
- Every sale is queued from a complete close and fills at the next hourly open
  only if the adverse fill remains strictly net positive after fees and
  slippage. A failed safety check clears the queue and records a deferred exit.

## Causal state inherited unchanged from V2E

Daily values use only complete UTC days. The `23:00` hourly close completes the
day and updates daily state before that closed bar's exit decision.

- EMA20 uses `alpha = 2 / 21` and the existing eight-decimal deterministic
  rounding.
- ATR14 uses the V2A/V2E Wilder definition.
- A fresh Donchian-20 event is exactly:

```text
prior20High_t   = max(high[t-20], ..., high[t-1])
prior20High_t-1 = max(high[t-21], ..., high[t-2])

freshBreakout_t =
    close[t] > prior20High_t
    and close[t-1] <= prior20High_t-1
```

- A trend epoch resets only when the complete daily close crosses down through
  its causal EMA20:

```text
close[t] < EMA20[t] and close[t-1] >= EMA20[t-1]
```

## Fast core exit

Each lot stores the causal ATR14 available at its entry-signal close. A
non-runner lot becomes target-ready when a complete hourly close first has:

```text
estimated net liquidation profit
    >= 1.0 * entry ATR14 * filled quantity
```

Unless it is handed to the sole runner slot on that bar, it queues a next-open
net-positive sale. This is the frozen V2E core and is not a percentage-profit
target.

## Entitlement and first-target handoff

Each epoch has entitlement state `NONE`, `PENDING`, or `CONSUMED`.

1. A fresh Donchian-20 event while state is `NONE` and no runner is open creates
   one `PENDING` entitlement.
2. Later fresh events cannot refresh or duplicate a pending/consumed
   entitlement.
3. The first later hourly close with target-ready lots consumes the entitlement
   and hands exactly one lot to the runner. If several are ready, latest fill
   time wins and signal time is the deterministic secondary tie-break.
4. A pending entitlement expires at the next EMA20 down-cross before that
   close's exit decision.
5. An epoch reset does not force an existing runner to exit.

## Global one-active-runner invariant

This is the sole new rule.

- An open runner occupies the only global runner slot across all trend epochs.
- A fresh breakout while that slot is occupied is rejected and cannot create,
  queue, or refresh an entitlement.
- An epoch reset may clear entitlement state, but does not release the global
  slot. Only the runner's actual next-open sale releases it.
- After release, a later genuinely fresh Donchian-20 event may create a new
  entitlement. A rejected past event is never replayed.
- At no point may two open lots have runner identity. The research runner must
  record maximum simultaneous open runners and reject the candidate if it is
  greater than one.

## Runner exit

The handed-off full lot uses V2A's unchanged hourly ratchet:

```text
candidate stop = highest closed-hour close since fill - current ATR14 * 1.50
ratchet stop   = max(previous ratchet stop, candidate stop)
```

The ratchet never moves down. It queues only when the complete hourly close is
at or below the ratchet and estimated net PnL is strictly positive. The
next-open fill uses the common net-positive safety floor.

## Required structural audit

Every window reports the normal realized, unrealized, total, drawdown, holding,
utilization, turnover, blocked-entry, deferred-exit, buy/sell, and open-lot
metrics plus:

- fresh breakout events, entitlement creations/duplicates/expirations/handoffs;
- fresh events rejected because a runner was already open;
- runner assignments, exits, share of buys, and target exits;
- maximum and ending simultaneous open runners;
- epoch entitlement and runner counts and uniqueness assertions;
- handoff latency and deterministic lot-selection details; and
- the Validation sparsity comparison against `ceil(10% * buy_count)`.

## Frozen data, checkpoints, and selection sequence

Preselection may query only complete rows whose close time is at or before
`2025-01-01T00:00:00`:

- first open: `2019-01-01T00:00:00`;
- rows: `52,608`;
- data SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before candidate performance is accepted, the runner must exactly reproduce
the frozen V1, V2A, V2B, V2C, V2D, and V2E checkpoints and annual wins. Data,
specification, or dependency drift returns `DATA_REJECT`,
`PREREGISTRATION_REJECT`, or `BASELINE_PARITY_REJECT`.

Performance is evaluated only after the specification hash is frozen. Use
Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`, `2021`,
`2022`, `2023`, and `2024`. Indicators receive only causal warm-up; entry,
ledger, entitlement, epoch, and runner state reset at every window boundary.

## Frozen gates

The sole candidate passes only when every condition holds:

- Validation total PnL is at least V1 and at least `90%` of V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation median holding is at most `182.5h`;
- Validation P90 holding is at most `1,418.3h`;
- total PnL beats V1 in at least three of five fair-reset folds;
- median holding beats V1 in at least three of five folds;
- maximum simultaneous open runners is exactly at most one;
- entitlement and runner epoch uniqueness both pass; and
- Validation runner assignments are at most `ceil(10% * buy_count)`.

No gate may be relaxed or removed after results are known.

## Decision and contract-infeasibility boundary

If all gates pass, emit `CANDIDATE_FROZEN` bound to the exact input,
specification, V2C/V2D/V2E dependencies, and runner hashes. Only then may the
runner open 2025+ OOS once and calculate the independent `30 USDT` one-slot
overlay.

If any gate fails, emit:

```text
CONSTRAINT_SET_INFEASIBLE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

This means the observed full-lot, price-only, profit-only architecture could
not jointly preserve trend value and capital recycling under the frozen gates.
It does not authorize a second candidate. The report may identify the smallest
future contract change worth separate authorization—such as partial exits,
entry-quality selection, or a loss/time-release rule—but must not test it here.

## OOS seal and authorization boundary

The OOS command must reject before data access when no candidate is frozen and
must never overwrite an existing output. A passed preselection may open OOS
once to one explicit complete-hour cutoff; an OOS miss cannot trigger
reselection.

This research cannot add a runtime catalog entry, authorize SHADOW or LIVE,
deploy, write the database, change DRA V1 or position `263`, change owner `509`,
Grid/OCO, funds, schedules, Telegram, or send an order. A historical pass would
still require a separate versioned runtime proposal and explicit authorization.

## Reproduction commands

```powershell
python research/btc_dra_sell_condition_structural_solution_v1.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_sell_condition_structural_solution_v1.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```

The runner uses the existing user-level `AGORA_SSH_KEY` and `AGORA_SSH_HOST`.
Database credentials remain on the server and are never printed or copied
locally.
