# Legacy Grid Retirement Authorization Packets

## Boundary

These are two independent Production authorization packets. They are not a
combined authorization and do not authorize an OKX-native Bot. Each execution
must begin with a fresh server-local dry-run on the deployed Phase 1 commit and
must stop if any count, provider order, quantity, fee, state hash, balance, or
confirmation differs from the packet below.

Current read-only evidence was collected at `2026-07-22T08:38:38Z` from
Production commit `3401f96d723fb2e49c55a810b137dc77e1bfad5f`:

- OKX-native active Bot count: `0`;
- Grid #10: PAUSED, one HOLDING level, zero in-flight, zero residual;
- Grid #11: PAUSED, zero HOLDING, zero in-flight, zero residual;
- BTC cash/available balance: `0.00058897202` / `0.00058897202`;
- USDT cash/available balance: `477.96415982941477` /
  `477.96415982941477`;
- both legacy-retirement gates are effectively false.

The operator should authorize and execute Grid #11 first because it requires
no provider order. Grid #10 remains a separate decision afterward.

## Packet A: Grid #11 no-holding closure

### Exact current dry-run

- `gridId=11`
- `disposition=CLOSE_NO_HOLDING`
- `holdingCount=0`
- `inFlightCount=0`
- `residualCount=0`
- `totalSellQty=0`
- `stateSha256=c494cbaabe568a90913cd71791eabcbf25fac81620b21e13d7e4ea16fd577be6`
- `providerOrderAttempted=false`
- `databaseMutationAttempted=false`

Exact current confirmation:

```text
AUTHORIZE_LEGACY_GRID_RETIREMENT|gridId=11|symbol=BTCUSDT|disposition=CLOSE_NO_HOLDING|holdingCount=0|totalSellQty=0|stateSha256=c494cbaabe568a90913cd71791eabcbf25fac81620b21e13d7e4ea16fd577be6
```

### Exact authorization text

> I authorize one Production retirement of legacy Grid #11 on deployed commit
> `3401f96d723fb2e49c55a810b137dc77e1bfad5f`, only if a fresh server-local
> `retireLegacyGrid` dry-run immediately before execution exactly matches Packet
> A in `docs/legacy-grid-retirement-authorization-packets.md`. I authorize a
> byte-preserving temporary change of
> `TRADING_GRID_LEGACY_RETIREMENT_ENABLED=true` and
> `TRADING_GRID_LEGACY_RETIREMENT_LIVE_ACTION_ENABLED=true`, the controlled
> restart required to load those gates, and exactly one
> `retireLegacyGrid(gridId=11, disposition=CLOSE_NO_HOLDING, execute=true)`
> request using the fresh exact confirmation. I authorize DB mutations only to
> close Grid #11 and its lifecycle metadata. After verification, restore the
> original environment bytes, restart with both gates false, and prove Grid #11
> closed with zero holding/in-flight/residual. I do not authorize any provider
> order, BTC sale, Grid #10 mutation, native Bot create/stop, other DB mutation,
> custom-runtime deletion, or deployment.

## Packet B: Grid #10 exact attributable market disposition

### Exact current dry-run

- `gridId=10`
- `disposition=MARKET_SELL_AND_CLOSE`
- level `70`, original BUY order `3707656681529860098`;
- provider gross quantity `0.00008096 BTC`;
- signed BUY fee `-0.00000008096 BTC`;
- net attributable quantity `0.00008087904 BTC`;
- lot-size-rounded maximum SELL quantity `0.00008087 BTC`;
- attribution dust `0.00000000904 BTC`;
- `holdingCount=1`, `inFlightCount=0`, `residualCount=0`;
- `stateSha256=0f0395372779e55c702c211ad85f5a6c207fc18f0f3eaadf43110591cb15c1a6`;
- `providerOrderAttempted=false` and `databaseMutationAttempted=false` in the
  dry-run.

Exact current confirmation:

```text
AUTHORIZE_LEGACY_GRID_RETIREMENT|gridId=10|symbol=BTCUSDT|disposition=MARKET_SELL_AND_CLOSE|holdingCount=1|totalSellQty=0.00008087|stateSha256=0f0395372779e55c702c211ad85f5a6c207fc18f0f3eaadf43110591cb15c1a6
```

### Exact authorization text

> I authorize one Production retirement of legacy Grid #10 on deployed commit
> `3401f96d723fb2e49c55a810b137dc77e1bfad5f`, only if a fresh server-local
> `retireLegacyGrid` dry-run immediately before execution exactly matches Packet
> B in `docs/legacy-grid-retirement-authorization-packets.md`. I authorize a
> byte-preserving temporary change of
> `TRADING_GRID_LEGACY_RETIREMENT_ENABLED=true` and
> `TRADING_GRID_LEGACY_RETIREMENT_LIVE_ACTION_ENABLED=true`, the controlled
> restart required to load those gates, and exactly one
> `retireLegacyGrid(gridId=10, disposition=MARKET_SELL_AND_CLOSE,
> execute=true)` request using the fresh exact confirmation. I authorize at
> most one OKX BTC-USDT Spot market SELL, quantity no greater than
> `0.00008087 BTC`, attributable only to level 70 and original BUY order
> `3707656681529860098`, plus DB mutations required to record the exact SELL
> result and close Grid #10 if and only if no residual remains. After
> verification, restore the original environment bytes, restart with both gates
> false, and reconcile the provider fill, signed fees, exact-net PnL, balances,
> and Grid state. I do not authorize selling any other BTC, any BUY, Grid #11
> mutation, native Bot create/stop, unrelated DB mutation, custom-runtime
> deletion, or deployment.

## Execution hard stops

For either packet, stop without execution when any fresh evidence differs,
either gate cannot be restored, another native Bot appears, a legacy in-flight
or residual state appears, provider attribution is incomplete, the exact
confirmation changes, or runtime health/log verification fails.

Packet B also stops if the original BUY is not filled, its side/order ID/fee
binding changes, the exact SELL quantity is not `0.00008087 BTC`, or available
BTC is insufficient. A partial/ambiguous provider response is not permission to
retry; it enters reconciliation and requires a new authorization.
