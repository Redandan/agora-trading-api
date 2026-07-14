# BTC Base Position Manager V1

## Purpose

`BTC_BASE_POSITION_MANAGER_V1` owns read-only review of explicitly selected
open BTCUSDT positions. `BTC_BASE_ADOPTION_V1` is its separately gated write
lane. Adoption means exactly one thing: cancel the selected position's OCO,
keep the purchased BTC open, and move the row into intentional no-OCO BTC Base
management.

Negative heuristic EV changes only the risk label. It never authorizes a
market sell, position close, Grid/Earn action, fund movement, or Telegram send.
The write lane has no market-sell call and does not use wallet BTC to infer row
ownership.

## Ownership Boundary

- Every preview and execution requires explicit comma-separated
  `bt_live_signal` IDs.
- V1 accepts only open, auto-traded, exact `LONG` BTCUSDT spot rows.
- `tradedQty` and `ocoQty` must both be positive and exactly equal.
- The exchange OCO `sz` must exactly match the database OCO quantity.
- The OCO parent and every visible child lookup must complete; any confirmed
  fill, missing quantity, incomplete query, or unexpected state fails closed.
- Native Local TradingView BTC Base slices are not adoptable.
- Wallet BTC, Grid inventory, manual BTC, and unrelated positions are excluded
  from ownership.
- A final database row lock repeats the position, OCO reference, and exact
  quantity checks before adoption is completed.

## Persisted State Machine

The external OCO cancellation and database transition form a recoverable saga:

1. `OCO_ACTIVE`: the row has its original OCO and is not BTC Base managed.
2. `ADOPTION_PENDING`: a `REQUIRES_NEW` transaction persists the original OCO
   ID, timestamp, and prior filter reason before any exchange mutation.
3. The service invalidates cached OCO state, rechecks every visible child, and
   requests cancellation only while the parent is still active.
4. `ADOPTED_FROM_OCO`: only a fresh, complete, canceled-with-no-fill exchange
   result permits the locked final transition. `ocoOrderListId` and `ocoQty`
   are cleared; `tradedQty`, entry data, strategy attribution, and the open
   position remain unchanged.

If cancellation, confirmation, or child inspection is uncertain, the row stays
`ADOPTION_PENDING`. Repeating the same guarded call resumes it. A confirmed
child fill wins over cancellation and requires normal OCO reconciliation; the
adoption path never submits a compensating sell. Repeating a completed call is
idempotent and performs no exchange action.

## Runtime Guards

Both settings default to `false` and must be true for execution:

```text
TRADING_BTC_BASE_ADOPTION_ENABLED=false
TRADING_BTC_BASE_ADOPTION_LIVE_ACTION_ENABLED=false
```

`execute=true` also requires:

- exact aggregate `expectedTotalQty`;
- the exact dynamic confirmation text returned by the current dry-run;
- no in-process position mutation lease held by OCO polling, trailing, legacy
  SELL, time exit, generic spot close, or OCO management;
- exactly one active Trading runtime. The mutation lease is in-process, so a
  blue-green predecessor must be fully drained before live adoption is armed.

Deployment, production flag changes, and the actual adoption call are separate
authorizations. The implementation does not add a background recovery writer
or a database migration.

## Managed Position Behavior

Pending and adopted rows use the shared `LOCAL_TRADINGVIEW_BTC_BASE:` state
prefix. Existing runtime paths then apply the same intentional BTC Base rules:

- OCO auto-retry and trailing OCO changes are suppressed;
- generic market close, strategy 508 24-hour close, and legacy SELL signals
  cannot sell the row;
- missing-OCO execution events and reports identify it as managed rather than
  unprotected;
- adopted legacy positions consume the same symbol-level BTC Base exposure cap
  as native Local TradingView BTC Base positions, regardless of strategy ID;
- OCO cancellation observed during `ADOPTION_PENDING` does not emit the false
  manual-cancel Telegram warning.

`ADOPT_KEEP_BTC`, `ADOPT_KEEP_BTC_RISK_REVIEW`, and
`ADOPT_KEEP_BTC_HIGH_RISK_REVIEW` are management labels only. None is a sell or
close recommendation.

## MCP Surface

- `getBtcBasePositionManagerStatus`
- `previewBtcBasePositionAdoption`
- `previewBtcBasePositionDisposition`
- `adoptBtcBasePositionsKeepBtc`

The first three tools remain read-only. The fourth is an OPS protected write,
but dry-run is the default and the two server gates keep execution disabled.
Its safety evidence distinguishes requested execution from authorization and
only reports cohort-level cancellation/retention confirmation when every
selected row is adopted or already adopted.

## Production Evidence Before This Change

Read-only production evidence on 2026-07-14 for explicit positions
`260,261,262` established the intended cohort:

- all three are strategy 508 BTCUSDT LONG rows with intervals `4h`, `4h`, and
  `1h`;
- `tradedQty == ocoQty == ownedQty` on every row;
- all three OCO parents were live and healthy, with no sync anomaly;
- aggregate quantity was `0.00047090 BTC`, recorded cost was
  `29.99925310 USDT`, and weighted entry was `63706.20748354`.

The then-deployed read-only manager returned the historical
`RETIRE_CLOSE_REVIEW` label. That label was not close authorization. The local
adoption implementation replaces that decision language with keep-BTC risk
labels, remains default-off, and has not canceled those production OCOs.

## Verification And Rollout

Local acceptance:

```powershell
.\scripts\test_btc_base_position_manager_smoke.ps1
.\scripts\test_btc_base_position_manager_shadow_packet.ps1
.\scripts\verify_local.ps1
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Safe rollout sequence:

1. Deploy with both adoption flags still `false` and run normal server, MCP,
   OCO-health, and runtime-log verification.
2. Call the manager previews and adoption dry-run for the exact IDs. Confirm
   quantities, OCO IDs, child-state completeness, and dynamic confirmation
   text. This step does not cancel anything.
3. Under a new explicit authorization, fully drain the predecessor runtime,
   enable both flags on the single active runtime, and re-run the dry-run.
4. Under the exact live-action authorization, call
   `adoptBtcBasePositionsKeepBtc` with `execute=true`, exact quantity, and the
   newly returned confirmation text.
5. Verify every row is `ADOPTED_FROM_OCO`, remains open with unchanged
   `tradedQty`, has no OCO reference, consumes BTC Base exposure, produces no
   OCO-missing alert, and has no market-sell/order evidence.
6. Return both flags to `false` after the scoped action unless another explicit
   authorization says otherwise.

The guarded operator wrapper implements steps 2-6 with a default read-only
mode, exact local confirmation, single-runtime restart, dynamic MCP
confirmation, and automatic restoration of the original environment file and
gates-off runtime:

```powershell
.\scripts\execute_btc_base_position_adoption_ssh.ps1 `
  -PositionIds "260,261,262" `
  -ExpectedTotalQty 0.00047090
```

Only after the exact live authorization, repeat with `-Execute` and the
`required_local_confirm_text` emitted by the read-only run. The wrapper stores
sanitized execution evidence but does not persist the dynamic MCP confirmation
text.

## Production Adoption Evidence

The explicitly authorized production action completed on 2026-07-14 for
strategy 508 positions `#260/#261/#262`, aggregate quantity `0.00047090 BTC`.
The three exact exchange OCO algo IDs were canceled and independently confirmed
unfilled. All three database rows remain open with unchanged quantities and
state `ADOPTED_FROM_OCO`; the manager reports zero pending rows, zero active OCO
candidates, and three adopted holdings.

No BTC sell or position close occurred. The trading-wallet BTC cash balance was
`0.00058897202`, covering the managed cohort without being treated as proof of
row-level ownership. The original production environment was restored
byte-for-byte, both adoption gates and `executionArmed` are false, and the
gates-off runtime passed post-action split acceptance. Sanitized evidence is at
`/home/ubuntu/agora-trading-api/target/btc-base-adoption/adoption-20260714T142520Z.json`;
the dynamic MCP confirmation is intentionally absent.

Any `ADOPTION_PENDING`, child fill, quantity drift, exchange-query failure, or
partial cohort result stops acceptance. Keep the flags off and resume only
after read-only OCO and position reconciliation.
