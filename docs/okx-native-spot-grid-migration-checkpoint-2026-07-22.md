# OKX Native Spot Grid Migration Checkpoint — 2026-07-22

## Boundary

This is a fresh read-only Production checkpoint and decision packet. It is not
authorization to deploy, change Production env, close a legacy Grid, sell BTC,
create or stop an OKX bot, mutate the database, or drop tables.

Evidence was collected through server-local Trading MCP, read-only SQL, and
authenticated OKX GET endpoints. The stale Marketplace connector was rejected
after its advertised Trading tools returned `Unknown tool`.

## Current Production

- Trading worktree HEAD and deployed `app.commit`:
  `5a97d2cdee5438e3dab06b94902d6db86d8d7eb8`.
- Active port: `8084`; server worktree clean; health and server-local MCP pass.
- Production MCP tool count: `331`.
- OKX-native migration tools are not deployed.
- Custom `createGrid`, `resumeGrid`, `enableGridAutoRebalance`, and `closeGrid`
  are still present in Production.
- Runtime has zero active custom Grids, two paused Grids, and nine closed Grids.
- OKX-native Spot Grid inventory: zero active and zero history bots.

## Legacy Grid inventory

### Grid #10

- Status: paused, not closed; three `PENDING` levels and one `HOLDING` level.
- Level ID: `70`; provider buy order ID: `3707656681529860098`.
- Gross fill: `0.00008096 BTC` at `61756.5 USDT` from a `5 USDT` market buy.
- Signed buy fee: `-0.00000008096 BTC` at fee rate `-0.001`.
- Net BTC attributable to this Grid fill: `0.00008087904 BTC`.
- Paired sell target: `66511.14666667 USDT`.
- No sell order ID, retry, in-flight intent, partial sell, or sell failure exists.
- At the paired target and an assumed 0.1% sell fee, estimated proceeds are
  `5.3739783340 USDT` and estimated exact-net PnL is `+0.3739783340 USDT`.
  This estimate is not a fill receipt and must be replaced by the actual signed
  sell fee after execution.

Critical guard: the legacy DB `filled_qty` is the gross fill, while OKX charged
the buy fee in BTC. Selling the DB gross quantity can consume BTC belonging to
other positions. Any retirement sell must use a fresh account/order
reconciliation and a lot-size-safe quantity derived from the net attributable
amount, not blindly call the existing gross-quantity close path.

With current OKX `lotSz=0.00000001 BTC`, a conservative rounded-down candidate
quantity is `0.00008087 BTC`, leaving less than one lot (`0.00000000904 BTC`)
as attribution dust. This is a proposal only; it requires a fresh pre-execution
balance check and separate exact authorization.

### Grid #11

- Status: paused, not closed; two `PENDING` levels.
- No buy fill, holding, fee, sell order, in-flight state, or provider exposure.
- It may be retired through a DB-only lifecycle close after separate
  authorization and verification that no provider orders reference it.

## Reconciliation

- Seven-day orphan reconciliation reports zero unmatched OKX trades and zero
  unmatched DB rows.
- The #10 buy is older than that window and was separately verified through OKX
  order details and three-month fill history.
- Current account also contains BTC attributed to other positions. Aggregate
  BTC balance is not authorization to sell the legacy Grid gross quantity.

## Native Grid provider preflight

- Current public BTC-USDT rules: `minSz=0.00001 BTC`,
  `lotSz=0.00000001 BTC`, and `tickSz=0.1 USDT`.
- Around a 66.2k BTC price, the public minimum-size lower bound is about
  `0.66 USDT` per underlying order. A 10 USDT bot is therefore not rejected by
  this public-rule lower bound alone.
- OKX documents native Grid create and stop endpoints but no non-mutating Grid
  create dry-run endpoint. Public-rule evidence cannot prove that the Bot
  create endpoint will accept an exact 10 USDT package.
- An exact provider acceptance test would be a potentially successful Bot
  create and therefore remains a separately authorized Production trade
  action.

## Decision and next gates

Current status: `NOT_READY_FOR_OKX_NATIVE_GRID_CREATE_AUTHORIZATION`.

Blockers:

1. Grid #10 still has attributable BTC inventory.
2. Grid #11 and #10 are paused but not lifecycle-closed.
3. Production lacks the read-only native migration bridge and still exposes
   custom Grid mutation tools.
4. The exact native range/grid-count package must be regenerated against a
   fresh price and hashed immediately before authorization.
5. OKX Bot minimum-investment acceptance remains unproven without a create.

Next separately authorized actions must remain distinct:

1. Grid #10 disposition: choose immediate sale versus wait-for-target; provide
   an exact net/rounded quantity, price condition, expected fee, residual rule,
   and post-fill reconciliation.
2. Grid #11 DB-only retirement.
3. Clean synchronized deployment of the read-only migration bridge with custom
   create/resume/auto-rebalance frozen.
4. Only after legacy exposure is zero: exact OKX-native Bot create package,
   idempotent `algoClOrdId`, and separate create authorization.
