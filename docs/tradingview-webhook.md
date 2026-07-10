# TradingView Webhook Ingress

The TradingView webhook is the primary live signal ingress. Legacy K-line close
strategy evaluation is disabled by default and only runs when explicitly
restored with the legacy signal-source flags. The webhook is still disabled and
dry-run by default until a secret is configured.

Endpoint:

```text
POST /api/tradingview/webhook
```

Required production env:

```bash
TRADINGVIEW_WEBHOOK_ENABLED=true
TRADINGVIEW_WEBHOOK_DRY_RUN=true
TRADINGVIEW_WEBHOOK_SECRET=<random-secret-in-alert-body>
TRADINGVIEW_WEBHOOK_ALLOWED_SYMBOLS=BTCUSDT
TRADINGVIEW_WEBHOOK_DEFAULT_NOTIONAL_USDT=10.0
TRADINGVIEW_WEBHOOK_MAX_NOTIONAL_USDT=10.0
TRADINGVIEW_WEBHOOK_IDEMPOTENCY_TTL_HOURS=24
TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=false
```

Expected alert JSON:

```json
{
  "secret": "<same value as TRADINGVIEW_WEBHOOK_SECRET>",
  "strategy": "AI",
  "strategyId": "485",
  "action": "BUY",
  "symbol": "BINANCE:BTCUSDT",
  "timeframe": "1D",
  "orderReason": "TRADINGVIEW_RELATIVE_LOW",
  "orderLabel": "相对低点买入",
  "qty": "1000",
  "barTime": "2026-07-02T00:00:00Z",
  "price": "60400",
  "notionalUsdt": "10",
  "alertId": "unique-alert-id"
}
```

TradingView parity fields:

- `strategyId`: optional but recommended. When present, audit rows are attached
  to the matching `bt_strategy.id`.
- `orderReason`: one of `TRADINGVIEW_RELATIVE_LOW`,
  `TRADINGVIEW_POTENTIAL_LOW`, or `TRADINGVIEW_AI_BUY_SIGNAL`.
- `orderLabel`: the Pine order label, for example `相对低点买入`,
  `潜在低点买入`, or `AI买点买入`.
- `qty`: the Pine `strategy.order(..., qty=...)` quantity. This is captured for
  parity audit only; live notional is still capped by the webhook notional
  settings.
- `timeframe`: TradingView values such as `1D` are normalized to service
  interval codes such as `1d`; the raw chart symbol is retained in audit context.

If the Pine script does not provide `orderReason`, the webhook derives it from
`orderLabel` where possible. Do not reuse the same explicit `alertId` for
different order reasons on the same bar; an explicit alert id is treated as the
idempotency key.

Pine alert payloads should be emitted for all three TradingView buy branches, not
only the final AI `buySignal` branch. The webhook distinguishes same-bar
relative-low and potential-low alerts by including the order reason and label in
the generated idempotency key.

Current behavior:

- Invalid or missing secret is rejected before audit writes.
- Accepted alerts write `bt_decision_audit` evidence with source
  `TRADINGVIEW`.
- Duplicate alerts are suppressed by idempotency key for the configured TTL.
- Same-bar TradingView buy alerts with different order reasons are not
  de-duplicated against each other unless TradingView sends the same explicit
  `alertId`.
- K-line close events no longer invoke the legacy `LiveSignalEvaluator` while
  `TRADING_SIGNAL_SOURCE_PRIMARY=TRADINGVIEW`.
- Orders are not sent in this release. If dry-run is turned off, the endpoint
  still blocks live execution until the TradingView path creates tracked
  `bt_live_signal` rows and OCO/exit accounting.
- Dry-run alerts intentionally do not create `bt_live_signal` rows yet. The
  current `bt_live_signal` uniqueness contract is one row per
  `(strategy_id, symbol, interval_code, bar_open_time)`, while the TradingView
  Pine script can emit multiple order intents on the same bar.

This lets TradingView become the source-of-truth signal stream without creating
untracked exchange positions.

## Free Local Parity Mode

TradingView Basic accounts cannot fill the alert `Webhook URL` field. For that
case the service has a local, audit-only parity mode that evaluates the same
ScoreBuy TradingView order-intent logic on closed K-lines:

```bash
TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=false
TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED=false
TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS=
TRADING_LEGACY_SECONDARY_MAX_NOTIONAL_USDT=0
TRADINGVIEW_LOCAL_ENABLED=true
TRADINGVIEW_LOCAL_STRATEGY_ID=485
TRADINGVIEW_LOCAL_ALLOWED_SYMBOLS=BTCUSDT
TRADINGVIEW_LOCAL_ALLOWED_INTERVALS=1d
TRADINGVIEW_LOCAL_ALLOWED_SOURCES=
TRADINGVIEW_LOCAL_HISTORY_BARS=320
TRADINGVIEW_LOCAL_CATCH_UP_BARS=3
TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS=72
TRADINGVIEW_LOCAL_DEFAULT_NOTIONAL_USDT=10.0
TRADINGVIEW_LOCAL_MAX_NOTIONAL_USDT=10.0
# Allowed: LEGACY, OFF, DRY_RUN, BTC_BASE_DRY_RUN, BTC_BASE_LIVE_MICRO, LIVE_MICRO.
TRADINGVIEW_LOCAL_EXECUTION_MODE=LEGACY
TRADINGVIEW_LOCAL_EXECUTION_ENABLED=false
TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN=true
TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED=false
TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_BAR=3
TRADINGVIEW_LOCAL_EXECUTION_MAX_ORDERS_PER_DAY=1
TRADINGVIEW_LOCAL_EXECUTION_MAX_OPEN_POSITIONS=1
TRADINGVIEW_LOCAL_EXECUTION_TAKE_PROFIT_PCT=0.0300
TRADINGVIEW_LOCAL_EXECUTION_STOP_LOSS_PCT=0.1200
TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT=250.0
```

Local parity mode re-evaluates the latest bounded closed bars on each closed-K
event using `TRADINGVIEW_LOCAL_CATCH_UP_BARS`, then writes one `SIGNAL_EVAL`
plus one `ENTRY_SKIP/LocalTradingViewDryRun` audit pair for each
Pine-equivalent order intent that has not already been seen by its idempotency
key. It does not call the legacy `LiveSignalEvaluator`, create
`bt_live_signal`, place exchange orders, mutate OCO/grid/fund/Earn state, or
send Telegram.

When `TRADINGVIEW_LOCAL_EXECUTION_MODE=DRY_RUN`, the service also writes a
dedicated LOCAL_TRADINGVIEW execution receipt for each local parity order
intent. This proves that the TradingView-equivalent buy point reached the
LOCAL_TRADINGVIEW execution lane without enabling the separate ScoreBuy
pre-position, confirmed-deploy, or post-scout schedulers.
`TRADINGVIEW_LOCAL_EXECUTION_MODE=BTC_BASE_DRY_RUN` uses the same
TradingView-equivalent buy points but records BTC-base accumulation/shadow
semantics instead of the OCO execution semantics. It is read-only/dry-run: no
market order is sent, no OCO is attached, and the OCO lifecycle smoke is not a
requirement for this mode. For historical comparison, call
`runScoreBuyTradingViewBtcBaseBacktest`; it reports TradingView order intents,
executed base buys, exposure-cap skips, profit-reduction events, emergency
drawdown markers, and PnL without writing DB rows.
`TRADINGVIEW_LOCAL_EXECUTION_MODE=BTC_BASE_LIVE_MICRO` uses the same
TradingView-equivalent buy points, places the configured small market buy,
records `bt_live_signal`, `bt_decision_audit`, and
`bt_runtime_decision_evidence`, and deliberately does not attach OCO. It still
requires OKX private credentials, allowlisted symbol/interval/source,
signal-age, per-bar, daily, duplicate-bar, notional, and
`TRADINGVIEW_LOCAL_BTC_BASE_MAX_EXPOSURE_USDT` gates. Open-position/OCO caps do
not block BTC_BASE accumulation; OCO poller/missing-OCO detectors and OCO health
classify these rows as intentional BTC_BASE no-OCO positions.
`TRADINGVIEW_LOCAL_EXECUTION_MODE=LEGACY` preserves the previous three-flag
behavior for rollback.

The OCO lane is order-capable only when all hard gates pass:
`TRADING_SIGNAL_SOURCE_PRIMARY=LOCAL_TRADINGVIEW`, `TRADINGVIEW_LOCAL_ENABLED=true`,
`TRADINGVIEW_LOCAL_EXECUTION_MODE=LIVE_MICRO`, OKX auto-trade enabled with
private credentials, the configured symbol/interval allowlist, per-bar, daily,
and open-position caps, `TRADINGVIEW_LOCAL_MAX_SIGNAL_AGE_HOURS`, valid TP/SL,
and no duplicate live signal for the bar. A
successful real execution places a market buy, immediately attaches OKX OCO,
then writes `bt_live_signal`, `bt_decision_audit`, and
`bt_runtime_decision_evidence` with `signalSource=LOCAL_TRADINGVIEW`. Grid,
fund, Earn, Telegram, and ScoreBuy scheduler state are not changed by this
lane. In `LIVE_MICRO`, the legacy `TRADINGVIEW_LOCAL_EXECUTION_ENABLED`,
`TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN`, and
`TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED` values are ignored for the
effective execution state. If a market buy is filled but the protection/audit
step fails, runtime evidence records `CRITICAL_UNPROTECTED_LOCAL_TRADINGVIEW`
and the service sends a `LocalTradingViewExecution` CRITICAL Telegram alert.
The read-only LOCAL_TRADINGVIEW candidate smoke treats `LIVE_MICRO` separately
from the dry-run receipt lane: `localTradingViewLiveMicroArmed=true` is the
live-micro armed marker, and `localTradingViewExecutionDryRunArmed=false` is
expected in that mode. Because the real order path attaches OCO immediately,
LIVE_MICRO review also requires a tracked OCO lifecycle marker; if
`TRADING_OCO_POLLER_ENABLED=false`, the smoke emits
`LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED` so TP/SL fills are not silently left
without `bt_live_signal.exit_time` reconciliation. Before requesting that
handoff, run
`.\scripts\prepare_local_tradingview_oco_lifecycle_env_handoff.ps1 -RequireReady`.
It emits `LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_PACKET`,
`local_tradingview_oco_lifecycle_env_handoff_status=READY_FOR_LOCAL_TRADINGVIEW_OCO_LIFECYCLE_ENV_HANDOFF_NOT_MUTATION`,
and the exact OCO lifecycle authorization for `TRADING_OCO_POLLER_ENABLED=true`
while keeping `POSITION_EXIT_MANAGER_ENABLED=false`. Post-env verification must
include
`.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed -RequireOcoLifecycleTracked`.
For BTC_BASE live review, use
`.\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireBtcBaseLiveMicroArmed`;
the ready marker is `localTradingViewBtcBaseLiveMicroArmed=true`, and OCO
lifecycle tracking is intentionally not required for that mode.
In `BTC_BASE_DRY_RUN`, the same candidate smoke prints
`localTradingViewBtcBaseDryRunArmed=true` when the local evaluator and dry-run
receipt path are armed. That mode can still be used to audit missed buy points,
but it is not live approval and does not exercise the OKX/OCO write path.
For day-to-day LOCAL_TRADINGVIEW-only monitoring, use
`.\scripts\smoke_local_tradingview_only_readiness_ssh.ps1`; it wraps deployment
metadata, live-readiness audit, background automation, and the candidate smoke
without running TinyLive, ScoreBuy, runtime-evidence, or signal-policy checks.
Its `local_tradingview_only_status` is `WAIT_BUY`,
`READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED`,
`READY_CURRENT_BUY_CANDIDATE_BTC_BASE_LIVE_MICRO_ARMED`, or `BLOCKED`, and it keeps
`RUNTIME_LOG_NOT_CLEAN`, `EVENT_RISK_NOT_BASELINE`, and similar audit health
findings in `local_tradingview_only_health_warnings` instead of the focused
blocker list.
The same smoke surfaces the real pre-order gates as
`local_tradingview_pre_execution_evidence_status`,
`local_tradingview_pre_execution_readiness`,
`local_tradingview_pre_execution_blockers`,
`local_tradingview_okx_auto_trade_enabled`,
`local_tradingview_okx_private_credentials_configured`,
`local_tradingview_notional_accepted`,
`local_tradingview_daily_cap_available`,
`local_tradingview_open_position_cap_available`,
`local_tradingview_open_exact_position_exists`, and
`local_tradingview_duplicate_bar_exists`. It stays read-only but fails closed
on missing DB evidence, OKX credential gaps, below-minimum notional,
daily/open-position caps, duplicate-bar rows, stale current signals, or invalid
TP/SL plans before claiming a parity BUY is executable.
Docs/tooling-only origin drift is also reported as
`deployment_metadata_effective_status=DOCS_TOOLING_ONLY_DRIFT` with
`DOCS_TOOLING_ONLY_DRIFT_NOT_DEPLOYED`, so the daily LOCAL_TRADINGVIEW check does
not require a runtime deploy for read-only script or documentation changes.
It also keeps `notAuthorization=read-only LOCAL_TRADINGVIEW-only readiness
evidence`.
For bounded monitoring, use
`.\scripts\watch_local_tradingview_buy_candidate_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300`.
It emits `local_tradingview_buy_candidate_watch_status`,
`local_tradingview_buy_candidate_watch_pre_execution_blockers`,
`local_tradingview_buy_candidate_watch_only_status`, and
`local_tradingview_buy_candidate_watch_next_action`. The watcher stays
read-only: `WAIT_BUY` means no current parity BUY yet,
`READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED` means a current BUY exists and
the LocalTradingView-only readiness gates are clear, and
`BLOCKED_CURRENT_BUY_CANDIDATE` means the current BUY exists but one focused
blocker still prevents treating it as executable. It keeps
`notAuthorization=read-only LOCAL_TRADINGVIEW BUY candidate watcher only` and
does not deploy, change production env, place orders, modify OCO, send
Telegram, mutate DB/grid/fund/Earn/exchange state, change schedulers, or run
external backfill/import.
To verify that the deployed evaluator has actually produced strategy-specific
runtime evidence after the next configured closed-K event, use
`.\scripts\watch_local_tradingview_runtime_evidence_ssh.ps1 -MaxAttempts 3 -SleepSeconds 300`.
It emits `local_tradingview_runtime_evidence_watch_status`,
`local_tradingview_runtime_evidence_watch_target_strategy_evidence_rows`, and
`local_tradingview_runtime_evidence_watch_target_interval_persisted_count`.
`WAIT_1D_CLOSED_K_EVENT` means the active runtime log has not yet seen the
configured closed-K persist after deploy.
`WAIT_NO_BUY_RUNTIME_EVIDENCE_OBSERVED` means the strategy 485 canonical
WAIT/no-buy runtime evidence exists. `BUY_OR_SHADOW_RUNTIME_EVIDENCE_OBSERVED`
means the target strategy produced buy/shadow-like evidence. The watcher keeps
`notAuthorization=read-only LOCAL_TRADINGVIEW runtime evidence watcher only` and
does not deploy, change production env, place orders, modify OCO, send
Telegram, mutate DB/grid/fund/Earn/exchange state, change schedulers, or run
external backfill/import.
To start before the next configured close and let the tool run evidence
verification after the next `BTCUSDT@1d` persist, use
`.\scripts\watch_local_tradingview_post_close_evidence_ssh.ps1 -MaxWaitMinutes 1800 -PollSeconds 300`.
It emits `local_tradingview_post_close_evidence_watch_status`,
`local_tradingview_post_close_evidence_watch_runtime_evidence_watch_status`,
and `local_tradingview_post_close_evidence_watch_target_strategy_evidence_rows`.
`CLOSED_K_OBSERVED_EVIDENCE_CONFIRMED` means the closed-K event and target
strategy evidence both exist. `CLOSED_K_OBSERVED_EVIDENCE_MISSING` means the
closed-K event occurred but strategy-specific evidence is absent.
`WAIT_1D_CLOSED_K_EVENT_TIMEOUT` means no fresh target closed-K appeared before
the wait deadline. The post-close watcher keeps
`notAuthorization=read-only LOCAL_TRADINGVIEW post-close evidence watcher only`
and does not deploy, change production env, place orders, modify OCO, send
Telegram, mutate DB/grid/fund/Earn/exchange state, change schedulers, or run
external backfill/import.

Audit rows use `context_json.source=LOCAL_TRADINGVIEW_PARITY` so they can be
distinguished from real TradingView webhook deliveries.

Legacy restore switch:

```bash
TRADING_SIGNAL_SOURCE_PRIMARY=LEGACY
TRADING_LEGACY_LIVE_EVALUATOR_ENABLED=true
```

Use that only for a deliberate rollback or shadow investigation. It restores
K-line close events into `LiveSignalEvaluator`.

To run one reviewed legacy strategy beside `LOCAL_TRADINGVIEW`, keep primary on
`LOCAL_TRADINGVIEW` and use the secondary allowlist instead:

```bash
TRADING_LEGACY_SECONDARY_EVALUATOR_ENABLED=true
TRADING_LEGACY_SECONDARY_ALLOWED_STRATEGY_IDS=508
TRADING_LEGACY_SECONDARY_MAX_NOTIONAL_USDT=10.0
```

The allowlist path filters `LiveSignalEvaluator` by strategy id and caps the
secondary live notional. It does not enable all legacy strategies.
