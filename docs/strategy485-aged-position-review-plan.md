# Strategy 485 Aged Position Review Plan

This is a read-only review packet contract for SCORE_BUY strategy 485 open
positions. It is not authorization to close positions, modify OCO, place
orders, enable live trading, change scheduler/live policy, mutate production
env, mutate DB state, run grid/fund/Earn actions, send Telegram, deploy,
restart, or run external backfill/import jobs.

## Current Evidence Pattern

The focused production smoke currently reports the strategy 485 risk route as:

```text
strategy485_position_risk_recommendation=REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY
strategy485_position_review_decision={...}
```

The supporting evidence class is:

- open strategy 485 BTCUSDT positions exist
- OCO health is currently OK before any review decision
- active-position EV can be negative
- position timeout or aging events can be present
- TP stretch may be `WATCH` without proving the TP is too high
- recent closed trades and monthly PnL provide context, not mutation approval

This evidence is sufficient to route an operator review. It is not sufficient to
close positions or modify OCO automatically.

## Required Packet Inputs

Before drafting any operator review packet, collect a fresh read-only bundle:

```powershell
.\scripts\smoke_strategy485_position_risk_ssh.ps1
.\scripts\smoke_profit_improvement_review_bundle_ssh.ps1
.\scripts\smoke_tiny_live_post_trade_ssh.ps1
```

The packet must include these markers:

- `scope=READ_ONLY`
- `server-local /api/mcp only`
- `positionIds=[...]`
- `ocoHealthOk=true`
- `negativeEvPositions`
- `closeOrModifySuggestions`
- `positionTimeoutEvents`
- `tpStretchWatchCount`
- `tpStretchStretchedCount`
- `monthlyPnl`
- `strategy485_position_risk_recommendation`
- `strategy485_position_review_decision`
- `notAuthorization`

For each open position, include:

- position id, symbol, side, strategy id, entry, current, TP, SL, quantity, and
  age
- OCO algo id and OCO health status
- active-position EV and EV horizon
- TP stretch status, recent extreme, TP progress, pullback, and
  risk-reducing preview result
- stop-sweep policy status
- recent execution events such as `POSITION_TIMEOUT`
- recent closed strategy 485 trade context
- 3-month PnL context

The `strategy485_position_review_decision` JSON should be the packet's primary
routing object. It must preserve `canDraftOperatorReviewPacket`,
`positionOrOcoMutationAllowed=false`, OCO health, open/negative-EV position
counts, close/modify suggestion counts, timeout and TP-stretch counts,
per-position EV summaries, required evidence, next action, and
non-authorization text.

## Decision Routing

Use these routing rules for the packet conclusion:

- `FIX_OCO_PROTECTION_FIRST`: OCO is missing or unhealthy. Do not review
  profit optimization before protection is restored.
- `REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY`: OCO is healthy, positions are
  aged, and active-position EV suggests close or modify. This routes an
  operator decision only.
- `WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED`: OCO is healthy and EV is negative,
  but the aging/timeout evidence is not enough for an action packet.
- `WATCH_TP_STRETCH`: TP stretch is watch-worthy but not proven enough for a
  risk-reducing action packet.
- `NO_POSITION_RISK_ACTION`: no current strategy 485 open-position review item.

## Authorization Boundary

The packet may recommend one of these operator-reviewed paths, but it must not
execute them:

- keep current OCO and continue monitoring
- prepare a separate risk-reducing OCO modification request
- prepare a separate close-position request
- lower future exposure only through a later strategy/risk-policy review

Any actual close, OCO modification, new order, scheduler/live-policy change, or
production env change requires a separate exact diff or operator action command,
fresh read-only evidence, and explicit authorization after this packet.

## Stop Conditions

Stop the strategy 485 profit-improvement path and review safety first if any of
these appear:

- `ocoHealthOk=false`
- any `SYNC_ERROR`
- missing OCO algo id on an open position
- missing active-position EV evidence
- missing TP stretch/stop-sweep evidence
- stale production runtime or `origin_delta_status=RUNTIME_DRIFT`
- runtime log errors related to order/OCO/exchange execution
- packet evidence lacks `notAuthorization`
- packet output implies it can close positions or modify OCO
- any order/OCO/grid/fund/Earn/Telegram/exchange mutation happens during
  evidence collection
