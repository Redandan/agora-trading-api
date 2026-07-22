# OKX Native Spot Grid Phase 1 Deploy Authorization

## Purpose

Phase 1 deploys a read-only migration bridge and freezes expansion of the
deprecated custom Grid. It does not operate an OKX bot or dispose of legacy
inventory.

This packet is intentionally narrower than Grid #10 retirement, Grid #11
lifecycle closure, and an OKX-native Bot create or stop. Those actions require
separate, fresh authorization packets.

## Exact deploy scope

The candidate commit may:

- register read-only OKX-native Spot Grid active/history inventory;
- register a read-only BTC-USDT, Spot, 1x, single-bot, at-most-10-USDT
  migration preview;
- keep `TRADING_GRID_ENABLED=false`;
- set `TRADING_GRID_CUSTOM_CREATE_RESUME_ENABLED=false` by default;
- block deprecated custom `createGrid`, `resumeGrid`, and enabling custom
  auto-rebalance;
- retain custom query and retirement-only paths while legacy inventory exists;
- make the retirement-only close path reconstruct its maximum attributable
  sell quantity from the original OKX BUY, signed base-currency fee, and OKX
  lot size before any sell can proceed.

## Explicitly excluded

This authorization does not permit:

- creating, amending, starting, stopping, or deleting an OKX-native Grid Bot;
- sending a provider BUY or SELL;
- closing or changing Grid #10 or Grid #11;
- selling any BTC, including the Grid #10 attributable quantity;
- changing a Production Grid enablement flag to true;
- database insert, update, delete, migration, archival, or table drop;
- deleting the custom Grid runtime;
- deploying any source other than the exact reviewed candidate commit.

## Pre-deploy evidence to refresh

Immediately before authorization and deploy, record:

1. candidate commit and clean server worktree;
2. current deployed `app.commit` and health;
3. `TRADING_GRID_ENABLED=false`;
4. zero active OKX-native Grid Bots;
5. Grid #10/#11 status and all inventory/in-flight level counts;
6. no unexpected Production schema or environment change.

Any changed item invalidates the packet and requires regeneration.

## Post-deploy acceptance

The deploy passes only if all of the following are read-only verified:

1. health and startup are clean and `app.commit` equals the authorized commit;
2. the native status tool returns provider inventory without mutation;
3. the migration preview reports the capital/product/single-bot blockers and
   sends no order;
4. deprecated custom create/resume/auto-rebalance expansion is blocked;
5. `TRADING_GRID_ENABLED` remains false;
6. Grid #10/#11 rows, holdings, provider orders, and account balances are
   unchanged by the deploy;
7. zero OKX-native Bots and zero new custom Grid orders were created.

Failure triggers application rollback to the preceding reviewed commit. A
rollback must not call any Grid close, provider order, or Bot stop operation.

## Exact authorization text

After the evidence above is refreshed, the operator may authorize only this
phase with:

> I authorize deployment of candidate commit `<FULL_COMMIT>` to Production for
> OKX Native Spot Grid Phase 1 exactly as bounded in
> `docs/okx-native-spot-grid-phase1-deploy-authorization.md`. I do not authorize
> any provider order, Grid #10/#11 disposition, OKX Bot create/stop, DB mutation,
> Grid enablement, or custom-runtime deletion.

The `<FULL_COMMIT>` placeholder must be replaced by the full current candidate
commit. An authorization containing the placeholder, an older commit, or a
short commit is invalid.
