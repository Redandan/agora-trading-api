# BTC MEI Directional SHADOW Candidate V1

## Status

`BTC_MEI_DIRECTIONAL_ACCUMULATION_V1@v1` is a new, isolated research
candidate derived from the old strategy-567 MEI idea.

Current status:

- deployed as an isolated candidate;
- registered as `SHADOW`, but its runtime switch defaults to `OFF`;
- returned to `OFF` for the V2 restart-continuity rollout; current operator
  mode must be verified from live configuration;
- no exchange, OCO, Grid, fund, Telegram, or live-signal dependency;
- no schema migration and no new MCP tool;
- when enabled, reuses the already cataloged OKX `BTCUSDT@1h` stream.

Old strategy-567 results do not transfer to this version. The old result used a
different source contract and mostly `TIME`, `SL`, or `END` exits.

## Frozen entry contract

Market and timing:

- source: OKX;
- symbol: `BTCUSDT`;
- interval: `1h`;
- closed bars only;
- exact contiguous hourly sequence required.

The entry condition is:

```text
MEI score >= 60
AND 24-hour close momentum > 0
AND close > EMA20
```

MEI is calculated directly from source-pinned closed prices:

- 24-hour entropy: 40%;
- 48-hour entropy: 35%;
- 72-hour entropy: 25%;
- 20 Shannon-entropy bins.

Because entropy is nondirectional, positive 24-hour momentum and `close >
EMA20` are mandatory. A buy is queued only on the false-to-true edge of the
complete condition, rather than on every eligible hourly bar.

## Virtual accounting contract

- one virtual entry lot: `10 USDT`;
- maximum open virtual cost: `250 USDT`;
- buy/sell fee: `0.10%` per side;
- adverse buy/sell slippage: `0.05%` per side;
- signal at a closed bar, virtual fill at the next hourly open;
- each lot is independent;
- an exit queues when estimated net liquidation return reaches `+5%`;
- the next-open virtual sell is deferred unless at least `+1%` net profit
  remains after costs;
- no stop loss, time exit, or end-of-test forced liquidation.

Every evidence row records realized PnL, open-lot count, open cost, effective
average cost, oldest lot, maximum observed cost, maximum open capital loss,
fees, a 250 USDT virtual-equity curve, maximum virtual drawdown, state hash,
and `orderSent=false`.

## Runtime and restart behavior

The lane bootstraps from exactly 73 contiguous OKX hourly bars. Evidence schema
V2 stores the exact serialized state as a JSON string plus its SHA-256 in the
existing runtime-evidence table. The string boundary is intentional: the
database `JSON` type may normalize numeric JSON tokens, so the restart state
must not depend on the scale or precision of an embedded JSON number.

Only the first V2 row may bootstrap when no V2 state exists. Once any V2 row
exists, a missing, corrupt, or hash-mismatched restart state writes
`STATE_RESTORE_FAILED` and blocks instead of silently bootstrapping again.
Valid state may catch up at most 30 days of missing hourly bars. Missing,
mixed-source, non-hourly, non-closed, or discontinuous data also fails closed.

The four initial Production V1 rows from 2026-07-25 10:00–13:00 UTC are
excluded from forward evidence. Database JSON numeric normalization made their
post-write state hash unverifiable, so every row incorrectly bootstrapped from
73 bars. They remain as audit history and are not deleted or migrated.

`TRADING_BTC_MEI_DIRECTIONAL_SHADOW_MODE` accepts only:

- `OFF` — default; no evaluation and no evidence writes;
- `SHADOW` — virtual evaluation and evidence writes only.

The catalog lifecycle is never sufficient to place an order. This candidate
has no live implementation.

## Acceptance boundary

This local implementation is structurally ready for a separate deployment
decision, not for LIVE promotion.

Before enabling SHADOW:

1. deploy with the switch still `OFF`;
2. verify owner 509, Donchian, Grid, OCO, and account safety are unchanged;
3. verify the catalog has exactly one `LIVE` contract;
4. verify enabling this lane would deduplicate onto the existing OKX hourly
   subscription;
5. obtain explicit authorization for the Production configuration change.

After a V2 restart-continuity deployment:

1. the first genuine closed hourly bar must write `bootstrap=true`;
2. the next genuine bar must write `bootstrap=false`, `catchUp=false`, and
   `invalidStateRowsScanned=0`;
3. both rows must retain the exact canonical-state hash after database
   round-trip;
4. a deliberately unreadable current-schema state must fail closed rather than
   creating another bootstrap row.

Minimum forward screening after SHADOW activation:

- at least 30 non-bootstrap observation days;
- at least 5 independent entries and 5 completed virtual exits;
- positive realized net PnL after the frozen cost model;
- no forced exits and no order-sent violations;
- open cost never above `250 USDT`;
- complete causal hourly evidence with valid state hashes.

These are minimum rejection gates, not proof that the strategy will remain
profitable. LIVE would require a new, separately authorized implementation and
a materially stronger performance/drawdown case.

The first full historical result and benchmark comparison are recorded in
`btc-mei-directional-performance-report-2026-07-25.md`.
