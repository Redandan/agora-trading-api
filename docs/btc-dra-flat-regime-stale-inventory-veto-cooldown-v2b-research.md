# BTC DRA Flat-Regime Stale-Inventory Veto Cooldown V2B Research

Research identity:
`BTC_DRA_FLAT_REGIME_STALE_INVENTORY_VETO_COOLDOWN_V2B_RESEARCH`

Status before execution: `PREREGISTERED_POST_HOC_HISTORICAL_RESEARCH_ONLY`.

## Boundary

This is a read-only diagnostic contract. It cannot authorize `SHADOW`,
`PAPER`, `LIVE`, Production/runtime/configuration changes, database writes,
orders, transfers, schedules, Telegram sends, deployment, or promotion.

The experiment changes only the shared cooldown timestamp after the frozen V2
stale-inventory veto rejects an otherwise valid flat-route signal. It does not
change DRA V1, the flat-regime formula, entry, exit, fees, slippage, lot size,
capital cap, arm duration, cooldown duration, or veto thresholds.

## Source and contamination

The predecessor V2 result identified route substitution after a rejected flat
signal as its next defensible hypothesis. All 2019-2024 predecessor outcomes,
the V7 2025+ OOS, and July 2026 were already inspected. V2B is therefore
post-hoc historical evidence only. Even if every frozen gate passes, its best
possible status is `HISTORICAL_GATE_PASS_NO_CLEAN_OOS`; it cannot become a
candidate or activation claim.

## Fixed data and accounting

- Server-local OKX `BTCUSDT` complete causal 1h bars.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Design: 2019-2022; Validation: 2023-2024.
- Fair-reset folds: calendar years 2020-2024.
- `30 USDT` independent lots and `250 USDT` reference open-cost cap.
- `0.10%` fee and `0.05%` adverse slippage per side.
- Complete-bar signals and next-hour-open fills.
- No stop, time exit, forced loss, or final liquidation.
- Every sell remains strictly net-positive after modeled costs.

## Frozen parent V2 rule

The flat-route admission veto is unchanged:

```text
staleExposureVeto = oldestOpenFlatLotAge >= 168 hours
                    and openFlatLotCost >= 60 USDT
```

The `168-hour` and `60-USDT` constants must not be scanned or relaxed. The
frozen flat formula, EMA20 reclaim, `1R / EMA5 / 0.5R` profit-only exit, DRA V1
non-flat route, 30-day arm, and seven-day shared cooldown remain identical to
V2.

## Single V2B change

When an otherwise valid flat signal is rejected specifically by the frozen
stale-exposure veto:

```text
lastEntrySignal = rejectedSignalBarTime
nextArmEligible = rejectedSignalBarTime + 168 hours
```

The rejected signal still creates no order or lot and does not modify existing
inventory. Capacity rejection behavior is unchanged. Accepted signals already
set the same timestamp and are unchanged. Non-flat DRA signals are never
directly rejected by the veto.

This counterfactual holds the shared reservation calendar closer to the path
that would have occurred had the flat signal been admitted. Its purpose is to
separate entry-quality filtering from later route substitution.

## Comparators

For Design, Validation, and every annual fold, report:

1. frozen DRA V1;
2. frozen flat sleeve V1;
3. frozen V2 flat veto without cooldown reservation;
4. V2B flat veto with cooldown reservation;
5. frozen router V1;
6. frozen router V2 without cooldown reservation;
7. V2B router with cooldown reservation;
8. the independent `30-USDT` one-slot V2B overlay.

The runner must reproduce the frozen parent specification and runner hashes,
selection row count/hash, and DRA V1 / flat V1 / router V1 / one-slot
Validation checkpoints before judging V2B.

## Frozen audits

- Every stale-exposure veto satisfies both parent predicates.
- Every V2B veto records `lastEntrySignal` at the rejected signal timestamp.
- Every adjacent accepted-or-veto-reserved timestamp is at least 168 hours
  apart.
- Admission counts reconcile exactly.
- No non-flat signal is directly vetoed.
- Accepted fill sets, quantities, fees, next-open timing, exits, and accounting
  reconcile with the inherited engines.
- The one-slot overlay has zero stale-exposure vetoes and exactly reproduces
  the parent one-slot checkpoint.

## Frozen performance gates

V2B reruns every parent V2 standalone and router gate without relaxation.
Additionally, the V2B routed Validation result must:

- have total PnL at least the parent V2 router;
- have drawdown no higher than the parent V2 router;
- have no more terminal open cost than the parent V2 router;
- reduce the 2022 non-flat DRA entry count below the parent V2 router;
- have no more 2022 terminal open lots than the parent V2 router.

The standalone, routed, one-slot, audit, and added mechanism gates must all
pass. If any fails, the result is `NO_CANDIDATE_KEEP_DRA_V1`. If all pass, the
result is `HISTORICAL_GATE_PASS_NO_CLEAN_OOS`. No gate may be changed after
execution.

## Required output

The output path is sealed against overwrite. Deterministic JSON must include
the data, specification, parent-runner, and V2B-runner hashes; comparator
ledgers; all gate booleans; accepted/veto/cap accounting; cooldown reservation
records; route counts; annual folds; and the terminal research-only status.
