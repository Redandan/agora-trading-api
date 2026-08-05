# BTC DRA Post-Entry Breakout Promotion Partial Exit V6 Research

Date: 2026-08-02

Research identity:
`BTC_DRA_POST_ENTRY_BREAKOUT_PROMOTION_PARTIAL_EXIT_V6_RESEARCH`

Single candidate:
`POST_ENTRY_FRESH_DONCHIAN20_NET_POSITIVE_PROMOTION_ELSE_EMA5_PARTIAL_24_6`

## Objective and distinction from prior branches

V3 through V5 classified the exit path at entry time. V5 showed that early
EMA20 trend age selected profitable lots in Design but did not generalize: its
Validation total retained only `78.412249%` of V2A while holding gates still
failed.

V6 tests one new causal mechanism. Every lot begins on the fast path and may
earn full-runner status only from post-entry price behavior. This is not V2D
or V2E:

- no trend epoch exists;
- no runner slot, count limit, entitlement, target, `1R`, or tie-break exists;
- a single event may promote every independently eligible open lot;
- no event or runner state affects DRA entry eligibility or capacity.

This is read-only research. It cannot change or authorize Production, runtime,
configuration, database state, DRA V1, position `263`, owner `509`, Grid/OCO,
funds, schedules, Telegram, or orders.

## Frozen common contract

- Data: server-local read-only OKX `BTCUSDT` complete `1h` bars.
- Entry, arm, 30-day expiry, seven-day cooldown, and next-open fill: unchanged
  DRA V1.
- Lot/reference: independent `30 USDT` lots under the `250 USDT` research cap.
- Costs: `0.10%` fee and `0.05%` adverse slippage per side.
- No fixed profit percentage, stop loss, time exit, forced loss, or final
  liquidation.
- Every sell must be estimated strictly net-positive after costs when queued
  and remain strictly net-positive at the next-open fill.

No entry is blocked or resized by V6. Capacity blocking remains only the
unchanged common `250 USDT` accounting rule.

## Exact causal fresh Donchian-20 event

Aggregate complete UTC days from the hourly source. On complete day `t`:

```text
prior20High_t   = max(high[t-20], ..., high[t-1])
prior20High_t-1 = max(high[t-21], ..., high[t-2])

freshBreakout_t =
    close[t] > prior20High_t
    and close[t-1] <= prior20High_t-1
```

Both thresholds exclude the day being evaluated. A continuing close above an
old threshold is not fresh. The event is known only after the `23:00` hourly
bar completes the UTC day.

## Per-lot post-entry promotion

Every filled lot with causal entry ATR14 starts as `FAST_UNPROMOTED`. The hard
dataset-inception lot may use recorded full-V2A fallback only when entry ATR14
is unavailable.

On a fresh breakout close, promote every open lot that satisfies all of:

1. it was filled before this daily close;
2. it has not already been promoted;
3. it has not filled its partial tranche;
4. it has no pending exit; and
5. estimated liquidation of its full current quantity is strictly net-positive
   after fee and slippage.

There is no maximum promoted count. Multiple lots on the same event must all be
promoted, and the audit must report the maximum same-event promotion count.
Lots rejected solely because they are not yet profitable remain eligible for a
later distinct fresh event. A lot that already partially exited cannot promote;
its exact `6 USDT` remainder already uses V2A.

The complete daily event is processed before that `23:00` bar's hourly exit
decision. Promotion sets highest close to the breakout close, clears ratchet
state, and begins unchanged V2A `1.50 ATR` from that observable point. It does
not reconstruct an earlier high.

## Fast path and promoted exit

An unpromoted lot uses the unchanged V3C fast path:

- complete hourly close below recursive causal EMA5;
- `24 USDT` tranche estimated strictly net-positive;
- next-open strictly net-positive fill;
- at most one partial fill;
- exact `6 USDT` remainder rebased at the fill open, with prior ratchet state
  cleared, then unchanged V2A `1.50 ATR`.

A promoted lot skips the partial path and uses full-quantity V2A `1.50 ATR`.
Its ratchet never moves down and can queue only while full liquidation remains
strictly net-positive. A failed next-open positive check clears the queue and
records a deferred fill without revoking promotion.

No alternative Donchian period, freshness formula, profitability threshold,
EMA, ATR multiplier, partial ratio, or promotion window is tested.

## Pre-2025 protocol and gates

Preselection is physically capped at `2025-01-01T00:00:00`:

- rows: `52,608`;
- first open: `2019-01-01T00:00:00`;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Before V6 results are accepted, reproduce V1, V2A, V2B, V2C, V3B, V3C, V4,
and the frozen V5 candidate checkpoints and annual win counts exactly.

Use Design `2019-2022`, Validation `2023-2024`, and fair-reset folds `2020`
through `2024`. Each window warms indicators causally but resets arm, expiry,
cooldown, lots, promotion state, capacity, realized PnL, and equity.

The sole candidate passes only when every gate holds:

- Validation total PnL is at least V1 and at least `90%` V2A;
- Validation realized PnL is at least V1;
- Validation ending unrealized PnL is no worse than V1;
- Validation maximum drawdown is at most `9.121498%`;
- Validation cost-weighted median/P90 holding are at most
  `182.5h / 1,418.3h`;
- annual total wins and annual median-hold wins versus V1 are each at least
  `3/5`;
- all breakout observations are complete and causal;
- every promotion is post-entry, pre-partial, strictly net-positive, unique per
  lot, and tied to a fresh event;
- every same-event eligible lot is promoted, proving no quota or tie-break;
- all partial conditions, positive fills, cost, quantity, and one-partial
  audits pass.

No gate may be removed, rounded into a pass, or relaxed.

## OOS and authorization boundary

If and only if the candidate passes every pre-2025 gate, bind it to the exact
data, specification, dependency, and runner hashes, then permit one explicit
2025+ OOS query and the independent `30 USDT` one-slot overlay. Otherwise emit:

```text
NO_CANDIDATE
POST_ENTRY_PROMOTION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
```

An OOS pass remains RESEARCH and is not SHADOW or LIVE authorization.

## Reproduction commands

```powershell
python research/btc_dra_post_entry_breakout_promotion_partial_exit_v6.py preselect `
  --output <preselection.json>
```

```powershell
python research/btc_dra_post_entry_breakout_promotion_partial_exit_v6.py oos `
  --preselect <preselection.json> `
  --cutoff <YYYY-MM-DDTHH:00:00> `
  --output <new-oos-output.json>
```
