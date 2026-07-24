# Strategy-Driven Minimal Runtime

## Decision

The minimal product is a BTC spot strategy runner plus the exchange-native OKX
Spot Grid integration.

The first strategy is named `TV_BTC_DAILY_ACCUMULATION_V1`. The owner-facing
alias may remain `508`, but the runtime must not use the bare number `508` as
the strategy identity because the production database already assigns that ID
to a different strategy.

No production environment, database row, exchange order, Grid configuration,
fund allocation, or scheduler state is changed by this plan.

## Identity correction

| Identity | Current meaning | Disposition |
| --- | --- | --- |
| owner alias `508` | TradingView-validated BTC daily strategy | Preserve as a display alias only |
| canonical key `TV_BTC_DAILY_ACCUMULATION_V1` | Versioned strategy contract defined below | New source-of-truth identity |
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
is not. Runtime simplification may proceed, while this strategy remains PAPER
until a corrected position-sizing model has positive out-of-sample evidence.
This is a promotion decision, not a per-signal risk veto.

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

The repository has exact buy-point parity evidence. It does not contain proof
that the original Pine strategy had an exit rule. Therefore adding a local exit
would be an unverified strategy change.

## Runtime modes

Every strategy has exactly one mode:

- `ARCHIVED`: stored and queryable; never evaluated.
- `SHADOW`: evaluated and audited; no simulated or real order.
- `PAPER`: evaluated with durable simulated intents/fills and inventory.
- `LIVE`: may reach an exchange adapter after execution-safety checks.

Only `TV_BTC_DAILY_ACCUMULATION_V1` is eligible for LIVE research in the first
minimal release. It must not progress beyond PAPER until corrected sizing has a
positive out-of-sample result. All other strategies are preserved as
`ARCHIVED`, `SHADOW`, or `PAPER`; they do not receive their own schedulers,
execution services, or risk pipelines.

OKX Native Spot Grid remains a separate provider-managed lane with separate
capital ownership. Strategy inventory and Grid inventory must never consume or
sell each other's BTC implicitly.

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
`TV_BTC_DAILY_ACCUMULATION_V1`:

- ensemble votes and external AI/ML promotion gates;
- event-risk, regime, fear/greed, news, Expected-R, and trade-quality gates;
- `VersionedProfitStart` cohort/readiness/snapshot gates;
- generic TP/SL, OCO, trailing-stop, time-exit, and open-position rules;
- cross-strategy exposure optimization;
- independent 508 time-exit, Donchian, and legacy live evaluators;
- strategy-specific schedulers and MCP review-packet chains.

The deterministic online model contained in the captured Pine source remains
inside the strategy calculation. It is strategy code, not a platform ML gate.

## Development sequence

Implemented locally on 2026-07-24:

1. The Pine hash, Binance daily source, owner alias, and intent weights are
   frozen in `TradingViewDailyStrategyContract`.
2. `StrategyRuntimeCatalog` assigns owner 508 to `PAPER`, Donchian to
   `SHADOW`, and every other database strategy to `ARCHIVED`.
3. `KlineClosedEventListener` dispatches only the catalog-owned PAPER and
   SHADOW lanes. Database `enabled` flags cannot revive the legacy evaluator.
4. The PAPER engine persists weighted intents, next-daily-open fills, fees,
   and BTC inventory without an exchange-order adapter.
5. The legacy live evaluator, Webhook, 508 time-exit, AI/ML/Ensemble,
   auto-entry services, strategy risk filters, Earn/Funding lanes, and their
   schedulers/MCP tools were removed.
6. The MCP surface is a fixed 14-tool whitelist: runtime identity, strategy
   catalog, Donchian evidence, read-only execution safety, and guarded OKX
   Native Grid.
7. Existing spot OCO reconciliation remains for mechanical execution safety;
   it is not part of owner 508 strategy logic.
8. No production environment, Grid, order, fund, or database state was changed.

## Acceptance evidence

The repository test tree was removed at the owner's request, so current local
acceptance uses compilation plus direct source/config assertions:

- the historical parity checkpoint was 42 expected intents with zero missing
  and zero extra; this is retained as prior evidence, not rerun in this change;
- compilation must succeed with no test source or test dependency;
- the runtime catalog must contain no `LIVE` assignment;
- owner 508 must remain `BTCUSDT`, `1d`, `binance`, `PAPER`;
- its evaluator must accept only closed, allowed, non-stale bars;
- PAPER accounting must use next-daily-open fills and own resulting BTC without
  calling an exchange adapter;
- the application must contain no TradingView Webhook, legacy live evaluator,
  time-exit, AI/ML/Ensemble, or auto-entry runtime;
- MCP registration must expose exactly 14 whitelisted tools;
- all retained deployment scripts must parse, and the environment template
  must pass its fail-closed validator;
- LIVE remains unavailable and all provider mutations require separate gates
  and explicit authorization.

## Deferred operator choices

These choices do not block PAPER development:

- LIVE base notional per weight unit;
- LIVE total BTC inventory cap;
- whether the first LIVE release aggregates same-bar intents or submits them
  individually;
- production deployment and LIVE enablement;
- capital allocation between the strategy and OKX Native Spot Grid.
