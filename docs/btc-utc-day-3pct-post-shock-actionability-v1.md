# BTC UTC-day 3% post-shock actionability V1

## Scope

This is a parent-neutral, offline research capability. It proves only causal
timing and immutable byte bindings between a reviewed terminal discovery result,
untouched future evidence, one later eligible shock diagnostic, and the first
complete UTC-hour open strictly after all required information is available.

The exact source slice is limited to four new files:

- `research_pipeline/btc-utc-day-3pct-post-shock-actionability.v1.schema.json`
- `research_pipeline/post_shock_actionability.py`
- `research_pipeline/tests/test_post_shock_actionability.py`
- `docs/btc-utc-day-3pct-post-shock-actionability-v1.md`

It adds no heartbeat hook, timer, source, writer, queue, adapter, manifest,
hypothesis, candidate, OOS action, deployment path, canonical-state write, or
Trading action.

## Performance thesis and boundary

The capability cannot improve fee-adjusted PnL and has immediate PnL and
drawdown effect `ZERO`. Its research value is preventing discovery-label
lookahead: later work cannot use a terminal factor before semantic Manager
review, cannot treat nominal `t0` as availability, and cannot borrow an
`h1`/`h6`/`h24` diagnostic close as an executable fill.

Predictive value, actionability latency, parent compatibility, fees, slippage,
capacity, matched-capital PnL, path risk, drawdown, candidate readiness, OOS,
deployment and Trading value remain `MISSING_PROOF`.

## Frozen causal clocks

1. A canonical terminal post-shock factor artifact is bound by path, SHA-256,
   document type, terminal disposition, `sealed_at`, `latest_outcome_day`, and
   cumulative chain.
2. A semantic Manager review binds an immutable review id, `reviewed_at`, and
   the exact terminal-result SHA-256. Queue delivery or acknowledgement alone
   is not review proof.
3. `contract_activated_at` is no earlier than review. The untouched future
   evidence start is strictly later than activation and terminal-result seal,
   and does not overlap the latest discovery day.
4. A later `FORWARD_FACTOR_ELIGIBLE` diagnostic is bound by canonical bytes,
   path, SHA-256, derived `t0`, target-evidence receipt, and diagnostic seal.
   Its `t0` cannot precede the untouched evidence start.
5. `decision_available_at` is exactly the maximum of activation, Manager
   review, target-evidence receipt, and diagnostic seal.
6. `FIRST_COMPLETE_UTC_HOUR_OPEN_STRICTLY_AFTER_DECISION_V1` always advances to
   the next UTC-hour boundary, including when decision availability is already
   on an hour boundary.
7. The fill observation binds a source-contract id/hash, canonical artifact
   path/hash, interval and observation equal to that derived boundary, receipt
   no earlier than observation, a positive finite decimal open, and record seal
   no earlier than receipt.

## Discovery and future evidence separation

Terminal discovery episodes remain discovery evidence. No discovery episode,
pre-activation observation, partial future interval, diagnostic response close,
or nominal schedule is accepted as an actionability fill. All source paths are
contained relative references and all supplied source documents must be
canonical UTF-8 JSON with exact byte hashes.

The actionability document is closed recursively and intentionally has no
strategy parent, direction, side, quantity, capital, fee, slippage, holding
horizon, exit, PnL, drawdown, utilization, candidate, or OOS field. Unknown
fields fail closed.

## Later boundary

After a real positive terminal factor and semantic Manager review exist, a
separately frozen task must choose and validate any adapter, parent, price
source contract, economic ledger, and future evaluation boundary. This offline
capability does not imply that such a strategy is ready, compatible, deployable,
or authorized.
