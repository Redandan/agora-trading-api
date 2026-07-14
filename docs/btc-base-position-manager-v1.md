# BTC Base Position Manager V1

## Purpose

`BTC_BASE_POSITION_MANAGER_V1` is a fail-closed, read-only/shadow review lane
for explicitly selected open BTCUSDT OCO positions. It is separate from the
existing Local TradingView `BTC_BASE` entry mode.

V1 answers two questions without taking an exchange action:

1. Can the selected `bt_live_signal` rows be attributed exactly to their
   recorded OCO quantities?
2. Should the current OCO be kept, reviewed for a bounded recovery exit, or
   reviewed for controlled retirement?

It is not a loss-hiding or indefinite bag-holding bucket.

## Ownership Boundary

- Every preview requires explicit comma-separated `bt_live_signal` IDs.
- V1 accepts only open auto-traded BTCUSDT spot LONG rows.
- `tradedQty` and `ocoQty` must both be positive and equal within one satoshi.
- The recorded OCO parent must still be active, every visible child lookup must
  complete, and no child order may already be filled.
- Existing Local TradingView no-OCO BTC_BASE slices are not adoptable.
- Wallet BTC, Grid inventory, manual BTC, and unrelated holdings are never used
  to infer ownership.
- One failed ownership, price, position, or OCO check blocks the whole selected
  cohort. Existing OCO protection remains unchanged.

## Dispositions

- `KEEP_OCO_UNDER_MANAGER_REVIEW`: no selected position has negative heuristic
  EV at the time of the preview.
- `RECOVERY_EXIT_REVIEW`: combined heuristic EV is negative, but the position is
  not yet stale under the fixed 72-hour review rule.
- `RETIRE_CLOSE_REVIEW`: a selected position is at least 72 hours old with
  negative heuristic EV, or the existing heuristic explicitly suggests close.
- `DO_NOT_ADOPT` / `BLOCKED_FAIL_CLOSED`: exact attribution or health checks did
  not pass.

The EV input is directional and not statistically calibrated. The 24-hour
recovery review TTL is risk governance, not evidence of a profitable recovery
edge. None of these dispositions authorizes a close or OCO change.

## MCP Surface

- `getBtcBasePositionManagerStatus`
- `previewBtcBasePositionAdoption`
- `previewBtcBasePositionDisposition`

All three tools are OPS read-only tools. V1 has no scheduler, persistence,
order, close, OCO modification, Telegram, fund, Grid, Earn, or production-env
mutation path. A future authorized close must use
`SpotPositionCloseService.closeAtMarket`, not the DB-only
`forceClosePosition` compatibility tool.

## Current Predeploy Evidence

The 2026-07-14 server-local production read-only packet for explicit IDs
`260,261,262` confirmed:

- all three rows belong to strategy 508;
- intervals are `4h`, `4h`, and `1h` respectively;
- all three recorded OCOs are active, with `0 SYNC_ERROR` and `0` anomalies;
- displayed quantity is `0.00047090 BTC` in total;
- recorded cost is `29.999253104 USDT` and weighted entry is approximately
  `63706.20748`;
- all three heuristic suggestions were `MODIFY`, all EV values were negative,
  and the cohort disposition simulation was `RETIRE_CLOSE_REVIEW`.

The predeploy packet intentionally keeps `adoptionEligible=false`, because the
old deployed tools do not expose `tradedQty` and `ocoQty` together. Exact
adoption can only be claimed by the new manager after deployment.

## Deployment Acceptance

Runtime commit `c75814f` was deployed on 2026-07-14 from blue-green port `8084`
to `8085`. Shared-schema split acceptance, MCP parity, cross-service ownership,
and post-call runtime log smoke passed. The deployed manager returned the
following exact read-only evidence for `260,261,262`:

- `tradedQty`, `ocoQty`, and `ownedQty` match for every row;
- all rows are strategy 508 BTCUSDT LONG positions with intervals `4h`, `4h`,
  and `1h`;
- all three exchange OCO parents are `live`, healthy, and have no blockers;
- aggregate quantity is `0.00047090 BTC`, recorded cost is
  `29.99925310 USDT`, and weighted entry is `63706.20748354`;
- adoption preview is eligible for review but not persisted, and both previews
  return `RETIRE_CLOSE_REVIEW`;
- every safety marker is false and OCO sync health reports zero current errors.

The production environment SHA-256 was unchanged across deployment. This
acceptance does not authorize adoption persistence, OCO cancellation or
modification, position close, order placement, or any other live action.

### OCO All-Child State Hardening

Runtime commit `4f11774` was deployed on 2026-07-14 from blue-green port
`8085` to `8084`. OCO state is now resolved by one shared read-only inspector
across BTC Base previews, the OCO poller, market-close protection, preflight
gates, reports, fee attribution, swap reconciliation, and backtest fill-price
resolution.

- The inspector queries the parent and every visible child order.
- Any confirmed child fill takes precedence over a stale active parent state.
- A child-query failure is incomplete evidence and fails closed; it cannot be
  treated as active, canceled, or safe for a replacement market sell.
- A later confirmed fill still wins if an earlier child lookup failed, because
  that is sufficient evidence to prevent a duplicate sell.

The post-deploy production assertions confirmed all three selected OCOs remain
`live`, exact quantity ownership still holds, OCO health is
`3 OK / 0 SYNC_ERROR / 0 anomaly`, and every preview safety flag remains
false. Shared-schema split acceptance, MCP parity, signal correctness,
strategy 508 SHADOW checks, Local TradingView `BTC_BASE_DRY_RUN` checks, and a
final runtime-log smoke passed. This deployment did not change strategy modes,
environment settings, orders, positions, existing OCOs, or database state.

## Verification And Rollout

Local acceptance:

```powershell
.\scripts\test_btc_base_position_manager_smoke.ps1
.\scripts\test_btc_base_position_manager_shadow_packet.ps1
.\scripts\verify_local.ps1
.\scripts\smoke_local_health.ps1 -Port 18084 -TimeoutSeconds 180
```

Refresh predeploy production evidence without mutation:

```powershell
.\scripts\prepare_btc_base_position_manager_shadow_packet_ssh.ps1 `
  -PositionIds "260,261,262"
```

Deployment is a separate authorization. After an authorized deploy, first run
server verification and MCP parity, then call both preview tools for the exact
IDs. Do not persist adoption or execute a disposition in the same authorization.
Any later persistence, OCO change, or controlled close requires its own exact
scope, preflight, and post-action OCO/position verification.
