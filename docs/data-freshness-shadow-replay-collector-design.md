# DataFreshness Shadow Replay Collector Design

This is a design contract for a future evidence-only collector. It is not
authorization to edit production env, deploy, restart the service, relax
DataFreshnessGuard, enable live trading, place orders, create live positions,
change OCO, run grid/fund/Earn actions, send Telegram, mutate schedulers, run
external backfill/import jobs, or mutate DB schema.

## Code Inventory

The current `LiveSignalEvaluator` L0 DataFreshnessGuard path returns before the
normal candidate pipeline:

- `dataFreshnessContext(...)` is written with stale K-line metadata.
- `auditWriter.logFilterBlock(..., "DataFreshnessGuard", ...)` persists the
  block.
- evaluation then returns before indicators, candidate plan, live signal, EV,
  TQS, OCO preflight, exposure, duplicate, daily loss, event-risk, or order
  paths run.

The normal downstream candidate pipeline is therefore not available for current
DataFreshness rows:

- `candidateTradePlanContext(...)` captures entry, TP, SL, expected R, EV, and
  related candidate fields after the L0 guard.
- `shadowExecutionIntentContext(...)` is also downstream of live signal creation
  and notification/trading gating.
- `auditExpectedValueGateDryRun(...)` runs only after a candidate and
  `BtLiveSignal` already exist.

`RuntimeDecisionEvidenceService.writeFromDecisionAudit(...)` is already gated by
`trading.runtime-evidence.enabled:false`. It can copy decision-audit context into
`RuntimeDecisionEvidence`, but current DataFreshness rows only resolve to
`freshnessState=BLOCKED_BY_DATA_FRESHNESS_GUARD`, `evResultJson` with
`status=NOT_EVALUATED`, and execution previews built without real candidate
entry/TP/SL/EV/OCO snapshots.

New DataFreshness L0 audit rows include a deterministic `replayCandidateId`
(`dfsr1_...`) plus `orderSent=false`, `intentCreated=false`, and
`ocoPlanCreated=false`. This identifies a future replay row without creating a
`BtLiveSignal` or changing the hard block outcome. It is still not a complete
candidate snapshot until entry, TP, SL, EV, OCO, and hard-gate evidence exist.

`DataFreshnessShadowReplayCollector` is now present as the disabled-by-default
L0 hook. With
`trading.data-freshness.shadow-replay.collector.enabled=false`, it only enriches
the DataFreshness audit context with safety markers such as
`shadowReplayCollectorStatus=DISABLED`, `shadowReplayKeepsHardBlock=true`,
`shadowReplayCreatesLiveSignal=false`, `shadowReplaySendsTelegram=false`,
`shadowReplayPlacesOrder=false`, `shadowReplayCreatesOco=false`, and
`shadowReplayMutatesPolicy=false`.

If enabled in a separately reviewed evidence-only rollout, the current skeleton
captures scalar K-line/strategy snapshot fields. For strategies whose candidate
plan can be derived from fixed SL/TP config without ATR, live-signal
persistence, Telegram, order, or OCO helpers, it also emits
`shadowReplayCollectorStatus=CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE` with
`candidateEntry`, `candidateTp`, `candidateSl`, `candidateQty=NOT_SIZED`, and
`riskUsdt=NOT_SIZED`. Dynamic ATR-based plans are not guessed; they stay
`SNAPSHOT_ONLY_NOT_REPLAYABLE` with
`shadowReplayCandidatePlanStatus=NOT_REPLAYABLE_DYNAMIC_ATR_CONFIG`.

The skeleton still does not write a separate replay table, create runtime
evidence rows by itself, run EV/OCO/risk gates, or make the row executable
replay evidence.

`DataFreshnessShadowReplayHardGatePreviewBuilder` adds explicit placeholder
evidence fields when a candidate plan snapshot exists:
`ev_result`, `tqs_result`, `oco_preflight`, `duplicate_gate`, `daily_cap`,
`exposure_gate`, `event_risk`, `open_position`, and `loss_budget`. Each gate is
marked `NOT_EVALUATED_REPLAY_INPUT_ONLY` or terminal-blocked by
`DataFreshnessGuard`; this closes the hidden-field gap for downstream review
while still requiring evaluated EV/OCO/risk evidence before any policy review.
It also emits `shadowReplayPreviewScope=READ_ONLY_REPLAY_INPUT_PROXY_NOT_RUNTIME_EV`,
`expectedRProxy`, `expectedRProxyStatus`, `ocoPlanShapeStatus`, and
`ocoRouteStatus`. These fields only describe the fixed entry/TP/SL plan shape;
they are not runtime EV, not exchange OCO preflight, and do not count toward
`complete_replayable_candidate_rows`.

## Design Conclusion

The existing 74-row DataFreshness counterfactual sample is a positive
forward-return proxy, not an executable replay sample. A collector cannot safely
reuse the normal downstream candidate helpers by simply moving the L0 guard
later; that would change the live decision order and could expose side effects
before a hard freshness failure.

Any future collector must keep the L0 DataFreshnessGuard outcome unchanged and
run as a separate shadow replay-input path after the hard block is recorded.

## Future Collector Boundary

If implemented later, the collector must be disabled by default:

```text
TRADING_DATAFRESHNESS_SHADOW_REPLAY_COLLECTOR_ENABLED=false
trading.data-freshness.shadow-replay.collector.enabled=false
```

The collector may only run after separate evidence-only authorization and
deploy. It must:

- keep `DataFreshnessGuard` as the terminal live decision
- keep `TRADING_OKX_ENABLED=false`
- keep `TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false`
- keep `TRADING_RUNTIME_EVIDENCE_ENABLED` as the only separately reviewable
  evidence sink switch
- create a stable `replayCandidateId` without creating a `BtLiveSignal`
- set or preserve `liveSignalId=null` unless a live signal already exists
  naturally from another path
- produce entry, TP, SL, current price, expected R, EV decision, TQS snapshot,
  OCO dry-run/preflight, duplicate, daily cap, exposure, event-risk,
  open-position, and loss-budget snapshots
- set `orderSent=false`, `intentCreated=false`, and `ocoPlanCreated=false`
- write only replay evidence or runtime evidence rows explicitly marked as
  shadow/counterfactual

It must not:

- move L0 below live signal creation, Telegram, order, OCO, or scheduler paths
- create `BtLiveSignal` just for replay
- send Telegram
- place or amend exchange orders
- create or modify OCO algo orders
- open, close, or resize positions
- run grid, fund, or Earn operations
- run external backfill/import jobs
- change EntryDedup, live policy, scheduler state, production env, or DB schema
- treat derived forward-return rows as executable candidates

## Implementation Shape

The current skeleton is a pure context-enrichment service invoked from the
DataFreshness L0 block immediately before `auditWriter.logFilterBlock(...)`, so
the same hard-block audit row carries the disabled/snapshot markers. It must not
persist independently or call downstream candidate helpers. A future collector
that writes a separate replay-evidence row should run only after the hard block
has been recorded and before the method returns. That future service should
receive immutable inputs from the already-loaded strategy/config/K-line window
and then run a side-effect-free candidate snapshot calculation.

The service must not call helpers that persist live signals, send notifications,
place orders, or mutate position/OCO state. If the current candidate logic
cannot be reused without those side effects, extract a pure candidate snapshot
builder first and keep its local verification separate from any collector
activation.

`DataFreshnessShadowReplayCandidatePlanBuilder` is the first such pure builder.
It intentionally supports only fixed-config SL/TP snapshots and horizon caps
that can be calculated from already-loaded inputs. It refuses dynamic ATR plans
instead of fabricating candidate prices.

`DataFreshnessShadowReplayHardGatePreviewBuilder` is also pure. It does not
query DB state, does not call exchange or Telegram adapters, and does not attach
or modify OCO. Its purpose is to make every missing hard gate explicit in the
audit context so a future replay-input smoke can distinguish "field absent" from
"field present but not evaluated because DataFreshnessGuard stayed terminal."

## Acceptance Gate

The collector is not useful for policy review until read-only production smokes
show:

```text
complete_replayable_candidate_rows > 0
ev_snapshot_rows > 0
oco_plan_snapshot_rows > 0
hard_gate_snapshot_rows > 0
missing_counterfactual_fields=[]
orderSentEvidence=0
data_freshness_counterfactual_recommendation=REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES
```

The first review sample should contain at least 30 mature replay candidates and
must keep ExpectedValueGate, TQS, OCO preflight, duplicate, daily cap, exposure,
event-risk, open-position, and loss-budget gates intact.

Even after that gate passes, the result is only an operator review packet input.
It is not approval to relax DataFreshnessGuard or enable live trading.

## Stop Conditions

Stop and revert the collector path if any evidence row shows:

- `orderSent=true`
- non-null exchange order id
- non-null OCO algo id created by the collector
- Telegram send
- live signal creation for replay only
- position/OCO/grid/fund/Earn mutation
- scheduler/live-policy mutation
- DB schema mutation
- replay that removes any hard gate other than DataFreshnessGuard
- sample edge that disappears after EV/OCO/daily-cap/exposure/event-risk gates
  are applied
