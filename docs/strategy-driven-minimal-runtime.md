# Strategy-Driven Minimal Runtime

## Decision

The minimal product is a BTC spot strategy runner plus the exchange-native OKX
Spot Grid integration.

The frozen entry strategy is named `TV_BTC_DAILY_ACCUMULATION_V1`. The active
runtime is `TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2`, owner alias `509`, which keeps
those entries and adds per-lot profit exits. Historical V1 remains archived
under alias `508`; neither alias is a database primary key.

Owner 509 is authorized for bounded OKX spot LIVE execution. Weights `1/2/5`
map to `10/20/50 USDT`, the maximum same-bar aggregate is `80 USDT`, and total
open owner-509 cost is capped at `250 USDT`. This authorization does not include
manual/test orders, Grid changes, transfers, OCO, or unrelated strategy actions.

## Pre-promotion production baseline

Read-only verification on 2026-07-25 confirmed:

- Score Buy Auto Exit V2 commit `8396769` is deployed on active port `8084`;
  inactive port `8085` is drained;
- 10 MCP tools and 11 resources;
- three registered strategy contracts: V2 PAPER, V1 ARCHIVED, and Donchian
  SHADOW, with no LIVE contract or exchange-order authorization;
- exactly two connected catalog streams: Binance `BTCUSDT@1d` and OKX
  `BTCUSDT@1h`;
- owner 508 mapped to database strategy `485` and V2 PAPER, with
  `TRADINGVIEW_LOCAL_ENABLED=false`; V1 remains its frozen entry contract;
- Donchian configured SHADOW with runtime evidence enabled and no exchange
  implementation;
- one provider-managed OKX BTC-USDT Spot Grid running through the read-only
  monitoring boundary; the runtime still has no Grid mutation adapter and
  exact-net profitability remains unproven while the bot is active;
- execution-safety status `OK`, with positions `#260/#261/#262` intentionally
  classified as BTC Base holdings without OCO;
- the last shared-database comparison found 35 source entity tables, 209
  database tables, and 0 missing source tables; V2 added no schema change;
- runtime-log verification found 0 errors, 0 unknown warnings, and 0
  high-risk operation-like lines.

The staged source-reduction plan is maintained in
`minimal-runtime-cleanup-roadmap.md`.

## LIVE production acceptance

Owner 509 was deployed from commit `6dae3fb` on 2026-07-25. Production
verification confirmed:

- active port `8084`, inactive port `8085` drained, with local/public health
  and authenticated dedicated MCP passing;
- V2 `LIVE`, V1 `ARCHIVED`, Donchian `SHADOW`, and
  `executionArmed=true`;
- base/max/exposure settings `10/80/250 USDT`, with OKX private credentials
  and account connectivity confirmed;
- Binance `BTCUSDT@1d` and OKX `BTCUSDT@1h` catalog subscriptions connected;
- execution-safety status `OK`; existing positions `#260/#261/#262` remained
  intentional legacy BTC Base holdings and are outside the owner-509 exit
  namespace;
- the same provider-managed OKX Spot Grid remained `running`, and no Grid
  mutation capability was added;
- 0 runtime errors, 0 unknown warnings, and 0 high-risk operation-like lines.

Acceptance intentionally sent no test order and used no simulated performance
claim. A real order will be sent only when the next genuine fresh closed daily
bar produces a weighted entry. The durable signal reservation and OKX client
order ID are written before provider submission so an ambiguous timeout is not
blindly retried.

## Local second-candidate development

`BTC_MEI_DIRECTIONAL_ACCUMULATION_V1@v1` was subsequently implemented as a new
source-pinned research contract. It is not the old database strategy 567 and
does not inherit 567's historical result.

The local catalog now contains four contracts, but Production remains on the
three-contract deployed baseline until a separate deployment is authorized.
The new candidate:

- is registered as `SHADOW` with its explicit switch defaulting to `OFF`;
- uses OKX `BTCUSDT@1h` closed bars and would reuse the Donchian stream through
  subscription deduplication;
- requires MEI `>=60`, positive 24-hour momentum, and `close>EMA20`;
- uses edge-triggered 10 USDT virtual lots, a 250 USDT open-cost cap, and
  fee/slippage-aware profit-only exits;
- writes only audit/runtime evidence and has no live exchange implementation.

Its frozen contract and later SHADOW acceptance gate are documented in
`btc-mei-directional-shadow-candidate-v1.md`. This local development did not
deploy or enable the lane.

## Identity correction

| Identity | Current meaning | Disposition |
| --- | --- | --- |
| owner alias `508` | Historical TradingView-validated V1 entry strategy | Preserve as archived display lineage |
| owner alias `509` | V2 score-buy plus automatic profit-only exit | Active LIVE display alias |
| canonical key `TV_BTC_DAILY_ACCUMULATION_V1` | Frozen versioned entry contract defined below | Preserve as archived entry evidence |
| canonical key `TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2` | Current per-lot auto-exit runtime | Register as LIVE under owner 509 |
| production DB `bt_strategy.id=485` | `SCORE_BUY_V2-BTC-1d-MLGated-v1`; captured TradingView Pine parity evidence | Temporary database mapping for migration |
| production DB `bt_strategy.id=508` | `OI_FUNDING_DIVERGENCE`; OKX market features; one-hour strategy | Archive/shadow only; never treat as the daily TradingView strategy |
| `STRATEGY_508_4H_24H_V1` | Later four-hour, 24-hour exit experiment | Retire from the canonical `508` path |

This mapping is required because using the production numeric ID as a strategy
name has already mixed three unrelated contracts.

## Frozen strategy contract

### Performance evidence boundary

The signed-in TradingView chart was rechecked on 2026-07-24:

- the account has the `AI` Pine script and its
  `AI - Strategy 485 NN Export Audit` copy; no separate saved script named 508
  was present;
- the chart is `BINANCE:BTCUSDT`, `1D`, with the same inputs captured by the
  existing parity fixture;
- TradingView displayed total PnL `+660,520.66%`, but the same report was marked
  `INVALID DATA`, showed maximum drawdown `57,401.08%`, and required
  approximately `574,010,850 USDT` of account size;
- the invalid result occurs because Pine `qty=1000/2000/5000` is interpreted as
  BTC contract quantity rather than affordable USDT notional;
- the production read-only 1,095-day parity backtest, interpreting those values
  as USDT notional and charging 0.1% fees, produced 72 intents, `-8.50%` total
  return, and `23.38%` maximum drawdown as of 2026-07-24.

Therefore exact signal parity is proven, but profitable three-year performance
is not. The owner explicitly chose bounded LIVE execution without using
simulated performance as a promotion gate. This limitation remains visible as
economic context and must not become a per-signal platform veto.

### Market and clock

- Signal market: `BINANCE:BTCUSDT`.
- Signal interval: `1D`.
- Signal evaluation: closed bars only.
- Signal time zone: Binance daily UTC bars.
- Order timing: calculate after the signal bar closes and submit at the next
  available execution opportunity. This preserves TradingView's default
  next-bar market-order semantics; `process_orders_on_close` is not assumed.
- Execution market: OKX `BTC-USDT` spot. Signal venue and execution venue are
  deliberately separate and must both be written into every intent and fill.
- Missing or stale Binance data produces `SOURCE_UNAVAILABLE`; it must not
  silently substitute an OKX candle.

### Entry intents

The captured Pine contract emits these independent entry intents, in order:

1. `TRADINGVIEW_RELATIVE_LOW`, quantity weight `1`
2. `TRADINGVIEW_POTENTIAL_LOW`, quantity weight `2`
3. `TRADINGVIEW_AI_BUY_SIGNAL`, quantity weight `5`

The original Pine quantities `1000 / 2000 / 5000` are treated as relative
notional weights, not literal live BTC quantities. When more than one intent
occurs on the same bar, the runtime persists every intent and may submit one
aggregated provider order whose weight is the sum of the intents. This preserves
economic exposure while avoiding unnecessary duplicate market orders.

The base notional is runtime configuration. PAPER defaults to `10 USDT` per
weight unit; the default per-order ceiling is `80 USDT`, which permits the
largest possible same-bar aggregate weight `1+2+5=8` without resizing it.
LIVE has no implicit authority and remains unavailable until an operator
explicitly reviews both the base notional and total BTC inventory cap.

### Position and exit semantics

- Position model: BTC inventory accumulation, not one isolated OCO position per
  signal.
- Pyramiding/additions: allowed when a new closed daily bar emits an intent.
- Strategy exit: `NONE` in V1.
- TP, SL, OCO, maximum holding time, trailing stop, break-even movement, aged
  position review, and forced risk reduction are not part of this strategy.
- Performance is mark-to-market BTC inventory performance, including actual
  fees and slippage. It must not be compared with a round-trip/OCO backtest.
- A future sell rule requires a new versioned strategy contract. It must not be
  injected by the platform under the same V1 identity.

The first such strategy version is documented in
`score-buy-auto-exit-v2.md`. It keeps V1 entries and tracks each buy bar as an
independent lot, queuing a profit-only exit after estimated net return reaches
5%. The current source retains V1 as `ARCHIVED` and registers V2/owner 509 as
`LIVE`. Actual OKX fills are stored in `bt_live_signal`; historical PAPER
snapshots remain evidence only and are not consulted by the LIVE order path.

The repository has exact buy-point parity evidence. It does not contain proof
that the original Pine strategy had an exit rule. Therefore adding a local exit
would be an unverified strategy change.

## Runtime modes

Every strategy has exactly one mode:

- `ARCHIVED`: stored and queryable; never evaluated.
- `SHADOW`: evaluated and audited; no simulated or real order.
- `PAPER`: evaluated with durable simulated intents/fills and inventory.
- `LIVE`: may reach an exchange adapter after execution-safety checks.

Only `TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2` under owner alias `509` is LIVE.
All other strategies are preserved as `ARCHIVED`, `SHADOW`, or `PAPER`; they
do not receive their own schedulers, execution services, or risk pipelines.

OKX Native Spot Grid remains a separate provider-managed lane with separate
capital ownership. This runtime can read Grid status and economic evidence but
cannot create, amend, or stop a Grid. Strategy inventory and Grid inventory
must never consume or sell each other's BTC implicitly.

## Platform responsibilities

The platform may enforce only mechanical execution safety:

- explicit mode and kill switch;
- exact symbol, interval, source, side, and exchange allowlists;
- closed-bar completeness and maximum signal age;
- a durable unique intent key before submission;
- duplicate prevention across restart and concurrent delivery;
- configured base notional, exchange minimums, per-order maximum, daily order
  maximum, and total strategy-owned inventory cap;
- credential presence and exchange trading availability;
- provider request/response validation and persisted provider order ID;
- partial-fill and rejected-order reconciliation;
- an ambiguous submission outcome is alerted and reconciled, never blindly
  retried;
- actual fees, fills, balances, and inventory ownership are recorded;
- no sell, transfer, OCO, Grid, or fund action may be inferred from a BUY intent.

These are execution correctness rules. They do not decide whether the alpha is
good.

## Removed from the active decision path

The following are strategy/risk opinions and must not block
`TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_V2`:

- ensemble votes and external AI/ML promotion gates;
- event-risk, regime, fear/greed, news, Expected-R, and trade-quality gates;
- `VersionedProfitStart` cohort/readiness/snapshot gates;
- generic TP/SL, OCO, trailing-stop, time-exit, and open-position rules;
- cross-strategy exposure optimization;
- independent 508 time-exit and legacy live evaluators;
- strategy-specific schedulers and MCP review-packet chains.

The deterministic online model contained in the captured Pine source remains
inside the strategy calculation. It is strategy code, not a platform ML gate.
Donchian remains an isolated SHADOW evidence lane; it cannot block owner 509
and has no exchange-order adapter.

## Development sequence

Implemented and deployed by 2026-07-25:

1. The Pine hash, Binance daily source, owner alias, and intent weights are
   frozen in `TradingViewDailyStrategyContract`.
2. `StrategyRuntimeCatalog` assigns owner 509 to `LIVE`, archived V1/508 to
   `ARCHIVED`, Donchian and the default-OFF MEI directional candidate to
   `SHADOW`, and every other database strategy to `ARCHIVED`.
3. `KlineClosedEventListener` dispatches only the catalog-owned LIVE and
   explicitly enabled SHADOW lanes. Database `enabled` flags cannot revive the
   legacy evaluator.
4. The LIVE adapter aggregates the current daily bar's weights, commits a
   unique reservation, places an OKX spot order with `clOrdId`, and persists
   actual fills/fees. Catch-up bars are audit-only.
5. The legacy live evaluator, Webhook, 508 time-exit, AI/ML/Ensemble,
   auto-entry services, strategy risk filters, Earn/Funding lanes, and their
   schedulers/MCP tools were removed.
6. The MCP surface remains a fixed 10-tool whitelist: runtime identity,
   strategy catalog, Donchian evidence, read-only execution safety, and
   read-only OKX Native Grid monitoring. The MEI candidate adds no tool.
7. Existing spot OCO reconciliation remains for mechanical execution safety;
   it is not part of owner 509 strategy logic.
8. Grid create/stop services, migration previews, write gates, and obsolete
   authorization documents were removed. Provider Grid state is not changed.
9. Market-data startup is catalog-driven. Owner 509 contributes exactly
   `binance:BTCUSDT@1d` for LIVE evaluation. Donchian and MEI directional
   candidates contribute the same deduplicated `okx:BTCUSDT@1h` requirement
   only while their explicit modes are SHADOW. Database `bt_strategy.enabled`
   values cannot add subscriptions, startup validation, warm-up evaluation,
   or resubscription side effects.
10. The legacy enabled-strategy startup validator, database-change
    resubscription listener, and dual-provider divergence monitor were removed.
    The provider list is retained only as a fail-closed mechanical allowlist.
11. Trading MCP authentication is a fail-closed Bearer API-key boundary.
    Guardian policy lists, External-AI session approval, Telegram approval
    prompts, and Trading-local in-memory approval state were removed. Telegram
    callbacks remain owned by AgoraMarketAPI and cannot approve state inside a
    separate Trading JVM.
12. Batch 3A removed six archived executable strategy implementations and ten
    uncalled backtest, replay, simulation, and optimization helpers while
    preserving the `SCORE_BUY_V2` compatibility adapter, frozen owner-508
    strategy implementation, Donchian SHADOW, Grid reads, OCO safety,
    historical strategy rows, entities, repositories, and migrations.

## Acceptance evidence

The repository test tree was removed at the owner's request, so current local
acceptance uses compilation plus direct source/config assertions:

- the historical parity checkpoint was 42 expected intents with zero missing
  and zero extra; this is retained as prior evidence, not rerun in this change;
- compilation must succeed with no test source or test dependency;
- the runtime catalog must contain exactly one `LIVE` assignment;
- owner 509 must remain `BTCUSDT`, `1d`, `binance`, `LIVE`;
- the local catalog must contain the default-OFF MEI candidate as `SHADOW`
  without any live exchange implementation;
- with either hourly SHADOW lane enabled, startup must resolve exactly two
  deduplicated streams: `binance:BTCUSDT@1d` and `okx:BTCUSDT@1h`; with both
  hourly lanes OFF, only the owner 509 stream remains;
- changing database strategy `enabled` flags must not change stream inventory;
- startup must not run legacy enabled-strategy data validation, warm-up
  evaluation, database-change resubscription, or dual-provider divergence;
- its evaluator must accept only closed, allowed, non-stale bars;
- LIVE execution must accept only the current complete bar, use durable
  reservation plus OKX `clOrdId`, enforce `10/80/250 USDT` limits, persist
  actual fills/fees, and never blindly retry an ambiguous result;
- the application must contain no TradingView Webhook, legacy live evaluator,
  time-exit, AI/ML/Ensemble, or auto-entry runtime;
- MCP registration must expose exactly 10 whitelisted tools and no Grid
  create, stop, migration-preview, or Gate-A authorization tool;
- every registered tool must retain explicit `@McpAuth`; a missing, invalid, or
  unannotated tool request must be denied, while the configured OPS key must
  continue to support initialize, tools/list, resources, and tool calls;
- initialize and tools/list metadata must report `BEARER_API_KEY`, with no
  `SESSION_BATCH`, `getMcpAuthProbe`, Guardian, or Telegram approval contract;
- all retained deployment scripts must parse, and the environment template
  must pass its fail-closed validator;
- owner 509 LIVE must report `executionArmed=true`; provider-managed Grid
  mutations remain outside this runtime and must be performed separately at
  the exchange.

## Deferred operator choices

These choices do not block PAPER development:

- LIVE base notional per weight unit;
- LIVE total BTC inventory cap;
- whether the first LIVE release aggregates same-bar intents or submits them
  individually;
- production deployment and LIVE enablement;
- capital allocation between the strategy and OKX Native Spot Grid.
