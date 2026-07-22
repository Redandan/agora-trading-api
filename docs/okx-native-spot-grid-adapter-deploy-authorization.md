# OKX Native Spot Grid Adapter Deploy Authorization

## Purpose

This packet deploys the disabled-by-default OKX-native BTC-USDT Spot Grid
create/stop adapter after Phase 1 has passed Production acceptance. Deployment
alone does not create, stop, amend, or fund a Bot.

This packet is not valid while Production is still on the pre-Phase-1 commit.
The exact candidate must descend from the accepted Phase 1 commit, and all
pre-deploy evidence below must be refreshed immediately before authorization.

## Exact deploy scope

The candidate may:

- register protected `createOkxNativeSpotGrid` and `stopOkxNativeSpotGrid`
  workflows;
- fix product scope to `BTC-USDT`, Spot Grid, arithmetic spacing, 1x/no
  leverage, one Bot, and quote-only investment no greater than 10 USDT;
- call OKX's public minimum-investment calculation and fail closed unless the
  exact USDT minimum is present and no greater than both the requested amount
  and the 10 USDT cap;
- read active/history/detail provider state for idempotency and reconciliation;
- read filled/live Grid sub-orders and authenticated BTC-USDT fills for
  signed-fee exact-net acceptance evidence;
- expose read-only Gate A safety evidence for legacy closure, zero custom Grid
  order activity in the acceptance window, one provider Bot identity, and
  current BTC/USDT holdings;
- keep `TRADING_OKX_NATIVE_GRID_ENABLED=false` and
  `TRADING_OKX_NATIVE_GRID_LIVE_ACTION_ENABLED=false`;
- expose dry-run packets whose required confirmation is bound to the exact
  create parameters or current provider Bot state.

## Explicitly excluded

This authorization does not permit:

- enabling either native Grid execution gate;
- creating, starting, stopping, amending, or funding an OKX Bot;
- sending any provider BUY or SELL;
- changing Grid #10 or Grid #11, or disposing of any BTC;
- database insert, update, delete, migration, archive, or table drop;
- removing the custom Grid runtime;
- deploying any commit other than the full authorized candidate.

## Pre-deploy evidence

Refresh and retain all of the following:

1. Production `app.commit`, health, clean server worktree, and accepted Phase 1
   ancestry;
2. both native Grid execution flags remain false;
3. authenticated OKX active and history inventories;
4. every legacy Grid and unsafe holding/in-flight level count;
5. the public minimum-investment response for the proposed tiny package;
6. candidate commit, clean candidate worktree, full tests, startup smoke, and
   split-boundary verification.

Any changed item invalidates the authorization packet.

## Post-deploy read-only acceptance

Deployment passes only when:

1. health is UP and `app.commit` equals the exact candidate;
2. both native execution flags remain false;
3. a create dry-run sends no provider request and reports the exact OKX USDT
   minimum, current native inventory, legacy blockers, and dynamic confirmation;
4. requests above 10 USDT, below the exact minimum, with an unsupported symbol,
   or while another native/legacy holding exists are blocked;
5. a stop dry-run sends no provider request and binds disposition to the exact
   active Bot detail hash;
6. the acceptance evidence tool is read-only and refuses exact-net proof when
   Bot lifecycle, provider pair, fill coverage, signed fee, pagination, live
   sub-order, or base-residual evidence is incomplete;
7. the functional safety evidence tool remains read-only and refuses its
   component PASS on any open/unsafe legacy state, custom Grid order activity,
   duplicate/missing provider Bot identity, or unavailable holdings snapshot;
8. Production Grid rows, balances, provider orders, and native Bot inventories
   are unchanged by deployment.

This is adapter deployment acceptance only. It is not
`PASS_OKX_NATIVE_GRID_FUNCTIONAL`; that receipt still requires separately
authorized create, fill-pair evidence, restart reconciliation, and stop.

## Exact authorization text

After refreshing the evidence, the operator may authorize only this deployment:

> I authorize deployment of candidate commit `<FULL_COMMIT>` to Production for
> the disabled-by-default OKX Native Spot Grid adapter exactly as bounded in
> `docs/okx-native-spot-grid-adapter-deploy-authorization.md`. I do not authorize
> enabling native Grid execution, any provider order, OKX Bot create/stop,
> Grid #10/#11 disposition, BTC sale, DB mutation, or custom-runtime deletion.

The placeholder must be replaced by the current full candidate commit. A short,
older, or placeholder commit is invalid.
