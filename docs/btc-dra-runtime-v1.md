# BTC DRA Runtime V1

## Status

`BTC_DAILY_REVERSAL_ACCUMULATION_V1@v1`, abbreviated `DRA`, means
**Daily Reversal Accumulation**.

Runtime boundary:

- catalog capability: `LIVE`;
- configuration switch: `TRADING_BTC_DRA_MODE`;
- safe default: `OFF`;
- authorized Production value: `LIVE`;
- new buy: `30 USDT` in default AGGRESSIVE mode, `15 USDT` in CONSERVATIVE;
- shared DRA capital ceiling: `30 USDT`;
- at most one live lot;
- no OCO, Grid, fund, leverage, or Telegram dependency;
- actual fills use the existing `bt_live_signal` durable ledger under the
  catalog-only strategy id `-10001`;
- provider submissions and cumulative reconciliation use the dedicated
  `bt_spot_execution_attempt` mechanical ledger;
- the DRA position prefix remains inside the intentional BTC-base namespace so
  OCO reconciliation cannot adopt it;
- one forward-only execution-attempt schema migration and no additional MCP
  tool;
- reuses the catalog-owned OKX `BTCUSDT@1h` stream.

The previous MEI directional runtime is retired. Its evidence rows remain
immutable history and are not valid DRA state.

## Frozen entry contract

Market and timing:

- source: OKX;
- symbol: `BTCUSDT`;
- input interval: `1h`;
- decision interval: UTC daily close, represented by the `23:00` hourly bar;
- closed bars only;
- exact contiguous hourly sequence required;
- virtual fill at the next hourly open.

Entry confirmation therefore occurs once per UTC day. After a live lot exists,
exit eligibility is evaluated on each fresh closed OKX `1h` bar. V1 does not
continuously watch intrabar prices; adding an intrabar exit would require a new
strategy version.

An entry may be confirmed only while the lane is armed and all three conditions
are true:

```text
daily close > daily EMA20
AND daily EMA20 > daily EMA20 five daily closes earlier
AND 24-hour close momentum > 0
```

The strategy intentionally has:

- no MEI threshold;
- no drawdown threshold;
- one entry per arm;
- seven-day entry cooldown;
- 30-day arm expiry followed by a new arm when cooldown permits.

Because confirmation is evaluated before re-arming on the same daily bar, a
new arm cannot confirm until a later daily close.

## Reference accounting contract

- one virtual entry lot: `30 USDT`;
- maximum open virtual cost: `250 USDT`;
- buy/sell fee: `0.10%` per side;
- adverse buy/sell slippage: `0.05%` per side;
- independent lots;
- exit queues at `+5%` estimated net liquidation return;
- next-open sell is deferred unless at least `+1%` net profit remains;
- no stop loss, time exit, forced risk exit, or end-of-period liquidation.

The 250 USDT cap belongs to the historical/reference ledger. LIVE execution is
independently capped at one lot within 30 USDT.

## New-buy risk modes — 2026-10-03

`TRADING_BTC_DRA_RISK_MODE` selects the versioned allocation profile
`DRA_NEW_BUY_RISK_MODE_V1`. This is independent of the OFF/SHADOW/LIVE lifecycle
switch and never enables LIVE by itself.

| Contract | AGGRESSIVE (default) | CONSERVATIVE |
| --- | --- | --- |
| New-buy allocation | 30 USDT | 15 USDT |
| Maximum simultaneous live lots | 1 | 1 |
| Shared DRA exposure ceiling | 30 USDT | 30 USDT |
| Entry/cooldown | Frozen V1 signal and seven-day virtual cooldown | Same |
| Existing lot after selection change | Keep original quantity and exit rule | Same |
| Profit exit | Existing +5% estimated net return | Same |
| Loss, drawdown or holding-age action | Observe; no automatic reduction, loss sale, time exit or mode switch | Same |

The lower CONSERVATIVE allocation is a capital-risk choice, not a backtest
optimization or proof of improved returns. Spot allocation can lose its full
cost, and a profit-only lot may remain open indefinitely. The +5% threshold is
an exit condition, not an expected return, guarantee or delivery deadline.
Historical performance is reference evidence only. No confidence score or AI
opinion grants sell authority. A future evidence-based risk exit needs a
separate versioned rule with verifiable inputs and explicit acceptance.

An absent setting resolves to AGGRESSIVE. A saved CONSERVATIVE value remains
CONSERVATIVE; blank and unknown values are rejected. Selection is deployment
configuration, not an MCP write. Change only this setting when switching;
the existing notional and exposure settings remain `30.00` as hard bounds.
Switches affect subsequent new buys after the current lot closes naturally.
The application will neither add to a 15 USDT lot nor trim a 30 USDT lot after
a selection change. A provider minimum-size failure blocks the order rather
than increasing the selected amount.

The durable execution attempt freezes requested quote amount before provider
submission. The decision evidence records mode/profile and amount; reconciliation
uses the existing attempt and provider receipt without replacing its allocation.
Catalog observations distinguish current configuration from the recorded
decision contract. Pre-profile decisions explicitly lack mode evidence.
The reference engine state/hash/schema and offline 30 USDT replay are unchanged.
The two modes share existing DRA history; there is no PnL reset or new ledger.

This profile applies only to DRA. Owner 509, Grid and legacy/manual assets are
outside its allocation budget; no whole-account loss protection is implied.

Local acceptance on 2026-10-03 passed 118 focused contract tests, Java 21
`mvn -DskipTests package`, retained shell/PowerShell syntax checks, environment
template validation, and 14 deployment risk-mode parsing cases. Tests cover
default/explicit/invalid mode binding, selected amount through durable
reservation to the offline provider double, provider-minimum rejection,
unchanged existing quantities and profit exits, no loss/age exit, and separate
current-versus-recorded mode evidence. No test starts Spring or places a real order.

The frozen AGGRESSIVE baseline replay remains byte-identical to the previous
diagnostic (`sha256: 0d9855902eeaba782310f0479f8dee6cacda18e019bee2149f6b56e385530bec`).
This checks behavior preservation only; no new candidate, OOS, return forecast,
or profitability claim was created.

## Provider-owned quantity precision — 2026-10-06

`DRA_OWNED_QUANTITY_V1` separates the exact provider net quantity from the
eight-decimal tradable position, which is always floored. A new SELL
reservation requires one reconciled owned BUY receipt and cannot exceed its
net quantity minus all previously applied SELL fills. Account BTC availability
alone never proves ownership. Existing positions with an old upward-rounding
error are corrected by the existing receipt maintenance clock only after a
fresh matching provider receipt, no SELL attempt and a row-locked check of
the precise rounding signature. An append-only quantity-correction audit
records before/after and residual dust; no trade or cost/PnL rewrite occurs.
Partial/closed inventory cannot be reopened by replaying its BUY receipt.
Dust remains in the provider ledger; tradable closure is not automatically
a completely liquidated, fee-exact lifecycle.

## Receipt maintenance and protected profit exits — 2026-10-03

The follow-up design repair versions the LIVE exit adapter as
`DRA_PROFIT_EXIT_IOC_V1`. Entry signals, virtual ledger/cooldown, allocation
modes, single-lot ceiling and the +5% profit condition remain unchanged.
The historical/reference replay retains its original execution model and must
not be presented as performance evidence for the new IOC adapter.

- The existing ten-minute maintenance clock also reconciles DRA BUY and SELL
  receipts. It does not depend on a current bar, restored signal state, DRA
  entry mode, or new-buy allocation settings. OKX connectivity and private
  credentials are still required. It never claims/submits/retries/cancels an
  order, expires a RESERVED row, or rewrites an old decision. Cumulative
  provider receipts and fee-only adjustments remain in the durable attempt.
- One failed BUY maintenance lookup cannot starve SELL maintenance. Uncertain
  outcomes remain unresolved; provider NOT_FOUND never grants retry authority.
- Profit exits still require LIVE authorization and a fresh closed hourly
  evaluation. A malformed new-buy allocation no longer disables profit exit
  permission. OFF/SHADOW does not authorize new exits, while receipt maintenance
  continues for orders already submitted.
- After the original +5% quote test, read the current account taker commission.
  Use at least the existing 0.10% fee assumption, retain the 0.05% adverse-price
  buffer, and round the minimum sell price UP to the instrument tick. Missing
  fees/rules block submission. Persist the price and fee evidence before claim.
- Submit a cash SELL IOC limit at that minimum price with `pxAmendType=0`,
  preserving deterministic client ids and the atomic single-submitter claim.
  There is no market fallback, price lowering, leverage, loss exit or OCO.
  The price floor is an execution constraint; final net profit is still proved
  by the actual fill and final fees, not by a quote or an order acknowledgement.
- Reconcile zero-fill cancellation without closing the lot; apply partial
  fills as cumulative deltas. A terminal canceled/partial attempt may allocate
  a new sequence only on a later genuine hourly evaluation after fee resolution.
  This is not a blind retry of an ambiguous order.
- Hourly evaluation can miss an intrahour profit opportunity. No real-time
  capture or maximum time-to-profit is promised. IOC can remain unfilled.

The read-only `DRA_POSITION_REVIEW_V1` classifies missing/stale/unmatched data,
pending accounting, and changes in confirmed daily entry conditions. Every
classification retains `KEEP_EXISTING_RULES` and `riskSellAuthorized=false`.
High-confidence loss exits remain undefined: activation requires a versioned
accepted rule, fresh source-matched evidence, reconciled ownership/cost and
predeclared confirmation/conflict criteria. A numeric AI confidence score,
holding age, or risk scenario alone is never authority to sell.

Catalog expectations state that modes change new-buy capital only, the nominal
profit at the +5% threshold is 1.50/0.75 USDT for 30/15 USDT buys, and cycle
duration/monthly return are unknown. One occupied lot blocks further buys;
virtual queued signals can advance cooldown without an actual buy. None of
these observations changes the accepted capital or holding policy.

`getExchangeAccountSafetySnapshot` now appends `SPOT_ACCOUNT_RISK_OBSERVATION_V2`
from fresh trading equity and funding balance reads. Trading `eq` is paired
with `eqUsd`; `cashBal` and `availBal` are separately labelled. Bot `stgyEq`
is included in trading equity and must not be added again. Funding total includes frozen
funds. Failed reads and missing valuations cannot be reported as zero risk.
BTC -10/-30/-50% shocks use observed BTC USD value, holding other balances
fixed; they are scenarios, not probabilities or forecasts. DRA/509 ledgers are
not added again to provider balances. Reported bot equity is included once;
Earn and exact bot lifecycle reconciliation are excluded, so whole-account
completeness remains explicitly false. No whole-account loss cap, transfer,
pause or rebalance is introduced.

The 2026-10-03 local acceptance passed 140 focused contract tests, Java 21
`mvn -DskipTests package`, shell syntax checking and `git diff --check`.
New tests use in-memory repositories and an in-process HTTP interceptor;
they cover no-fill/partial IOC receipts, no market fallback, tick rounding,
fee failures, immutable reserved quantity, independent reconciliation,
foreign-lot rejection, frozen funding balances and missing-data risk reporting.
A read-only Production fee probe returned SPOT taker `-0.001` (0.10% commission).
No test order or provider fill is claimed by this local acceptance.

Provider contracts: [OKX order and fee API](https://www.okx.com/docs-v5/),
[OKX order types](https://www.okx.com/en-us/help/how-do-i-trade-with-different-order-types-eea).

## LIVE execution contract

- only a genuine fresh current bar may reach the adapter;
- bootstrap, catch-up, stale, incomplete, duplicate, or hash-invalid state can
  never place an order;
- a new daily entry signal is durably reserved before submitting one OKX market
  buy with deterministic `clOrdId`;
- the runtime queries OKX by deterministic `clOrdId` before submission and
  requires one atomic database submitter claim;
- an unresolved or ambiguous submission is never retried automatically;
- while any DRA reservation or open lot exists, another DRA buy is blocked;
- the live lot uses actual fill quantity, price, and fee-aware effective cost;
- a sell is considered only when fee- and adverse-slippage-adjusted estimated
  net return reaches `+5%`;
- no stop-loss, time exit, forced loss sale, OCO, or trailing exit exists;
- a sell uses only the quantity recorded in the DRA-owned live lot and the
  protected IOC execution contract above;
- cumulative fills and fees are applied as monotonic deltas; overfill and
  backwards provider receipts fail closed;
- only a reconciled partial sell may allocate the next durable sell sequence;
- provider order id, client order id, observed fill price, gross quantity,
  observed fee, sellable strategy quantity, and realized PnL are linked to the
  canonical DRA evidence row;
- when the provider buy fee is not yet available, the current adapter records a
  conservative sellable quantity using a `0.2%` buffer. The provider receipt,
  not that provisional quantity, remains the exact source of truth;
- no deployment or acceptance step sends a test order.

## Performance reporting contract

DRA must be compared with owner 509 under equal starting capital, market
window, fees, adverse slippage, and final valuation time. Report realized PnL,
unrealized PnL, total PnL, maximum drawdown, capital utilization, blocked
entries, and holding age separately.

The 250 USDT reference ledger permits multiple lots. The Production canary
permits one lot within 30 USDT under the selected allocation profile. The reference result cannot be used as the expected
return of the one-lot LIVE deployment. Realized PnL alone is not sufficient
because V1 intentionally leaves losing inventory open.

## Bootstrap and restart contract

The first DRA event warms indicators from exactly 90 days of contiguous OKX
hourly bars. Historical warm-up bars also reconstruct arm, expiry, last signal
and cooldown through `BtcDraBootstrapEntryStateReplayer`. They create no virtual
lots, pending buys, live reservations or orders. The current bootstrap bar
advances that seeded state, but remains ineligible for LIVE execution.

Evidence schema is `BTC_DRA_RUNTIME_EVIDENCE_V1`; state schema is
`BTC_DRA_RUNTIME_STATE_V1`. Evidence stores the exact canonical state as a JSON
string and its SHA-256.
After any current-schema DRA evidence exists:

- valid state may catch up at most 30 days;
- missing, corrupt, or hash-mismatched state fails closed;
- the runtime must never silently bootstrap a second independent DRA state;
- MEI policy keys, event types, schemas, and evidence are ignored.

Every DRA evidence row records:

- daily signal fields and arm state;
- virtual events and exposure;
- realized and unrealized PnL;
- state canonical JSON and SHA-256;
- actual `orderSent`;
- `liveImplementationPresent=true` while configured LIVE;
- `ocoModified=false`;
- `gridModified=false`.

## Known V1 limitations

The current execution profile and historical comparison correction are in
`btc-dra-execution-contract-audit-2026-10-03.md`. A single-slot research engine
with cap 30 does not reproduce the virtual-cap-250 signal lifecycle consumed
by the separately bounded LIVE adapter. New observations label this profile,
entry stage and virtual-reference accounting explicitly; missing legacy stage
evidence must not be reconstructed from the latest inventory.

These limitations do not authorize changing the frozen V1 strategy:

1. Position `263` predates the execution-attempt ledger. It remains
   intentionally unbackfilled, so its provider/DB quantity difference must be
   reported as a separate BTC safety remainder during the first sell
   reconciliation.
2. State restore scans recent append-only evidence rows. This fails closed for
   the current canary, but current state should be separated from evidence
   history before more LIVE strategies are added.
3. Duplicate bar evaluation is guarded in process; strategy-plus-bar
   uniqueness is not yet a database contract for multi-instance evaluation.
4. The broad historical test tree remains removed. The retained 14-test
   bootstrap/execution-attempt contract suite must be extended whenever DRA
   order, fill, ownership, or state behavior changes.

## Production acceptance

Deployment acceptance requires:

1. owner 509 remains `LIVE` and `executionArmed=true` with `10/80/250 USDT`;
2. positions `260/261/262` remain outside DRA and owner-509 takeover;
3. OKX Native Grid `3767345250394603520` remains provider-managed and running;
4. OCO execution safety has no new issue;
5. catalog shows DRA as `LIVE`, configured mode `LIVE`, and
   `draExecutionArmed=true`;
6. live notional and maximum live exposure both equal exactly `30.00 USDT`;
7. the first genuine DRA row has `bootstrap=true`, `catchUp=false`,
   `orderSent=false`, no blocker, and a valid canonical-state hash;
8. the next genuine row restores that state with `bootstrap=false`,
   `catchUp=false`, `invalidStateRowsScanned=0`, and contiguous bar time.
9. deployment, bootstrap, catch-up, and acceptance create no DRA live-signal
   reservation and send no order;
10. the first later qualifying signal may send exactly one 30 USDT buy; its
    reservation, `clOrdId`, provider order id, fill, quantity, and fees must be
    reconciled before economic acceptance is called complete.

The first rows prove deployment and restart continuity only. Forward
profitability requires later completed actual exits and cannot be inferred from
historical backtest results.

Historical Production checkpoint (2026-07-30): commit
`ae47ef0609b6f86c7cfe2338c6d80a3135dc7e25`, active port `8084`, V4
execution-attempt table present with zero initial rows, and first new-JVM
natural bar accepted at evidence `28830` for
`2026-07-30T05:00:00Z`. Position `263` remains the only DRA lot and has not
sold.
