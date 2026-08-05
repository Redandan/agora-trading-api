# Java Phase C Opportunity-Cost Audit V1

Status: `DIAGNOSTIC_ONLY_DEFER_PHASE_C_NO_CANDIDATE_NO_OOS`

## Decision

Do not implement or run Java Phase C now. Phase A and Phase B prove that Java
can reproduce the frozen DRA V1 baseline, but there is no economically eligible
complex overlay to translate. Building Phase C against a rejected historical
branch would spend research capacity without improving PnL, drawdown, holding
risk, or forward evidence quality.

Phase C becomes eligible only after a future evidence-bound experiment reaches
`CANDIDATE_FROZEN` on its preregistered Design and Validation gates, before its
sealed OOS is opened. The candidate must exercise material lot-management
semantics that baseline Phase B does not cover. Until then, Python remains
authoritative for overlays and Java remains a non-mandatory baseline economic
kernel.

## Sealed evidence reviewed

- Phase A diagnostic:
  `383c38bf6edfa92c096090810da3c753dacd296bc8f611c391d75edf8d85d0db`;
- Phase B diagnostic:
  `6e9b2b9d8146c64b704d24020e1437c1147bca012c9b571441b645e0be47728c`;
- frozen input: `52,608` complete pre-2025 OKX BTCUSDT hourly rows,
  SHA-256
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- Phase A and B both ended `CLOSED`, opened no OOS, and remained
  `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`;
- the one-slot profitable-incumbent rotation diagnostic ended
  `NO_CANDIDATE_KEEP_ONE_SLOT_DRA_V1`, SHA-256
  `44245c9b393f3c3fd08c58c455f80f52a8024832a52639185b33b678e13ee609`.

No runner was rerun and no performance value was recomputed for this audit.

## Risks Phase A and B already eliminate

Phase A proves exact Design and Validation terminal checkpoints for:

- realized, unrealized, and total PnL;
- maximum drawdown and average utilization;
- buy, sell, open-lot, blocked-entry, and turnover counts;
- median and P90 holding age;
- the frozen clock, warmup, policy, and input boundary.

Phase B strengthens that proof with identical Java/Python hashes for:

- ordered decisions and lifecycle events;
- buy and sell fills;
- every hourly economic state;
- terminal lot identity, quantity, entry reason, and queued-exit state.

Together they materially reduce false gains caused by UTC ordering, next-open
fill timing, fee or slippage accounting, quantity rounding, inventory marking,
capital utilization, drawdown-path state, or terminal-lot drift.

## Risks still missing

Baseline parity does not cover:

- partial exits and remaining-cost allocation;
- multiple fills against one lot;
- ratcheted or staged de-risking state;
- manager ownership and cross-lot allocation;
- cancellation or replacement of pending overlay actions;
- overlay-specific causal event order and path-risk attribution;
- post-2024 forward generalization or any clean candidate OOS.

It also makes no claim that Java improves PnL, drawdown, holding time, or
strategy quality.

## Why Phase C is deferred

The previously plausible one-slot rotation exercised replacement and inventory
semantics, but its Validation realized PnL fell, drawdown rose, median holding
lengthened, and annual drawdown breadth passed only two of five folds. The
failed-reclaim and related historical exit branches are also closed. Translating
one of them would validate implementation parity for a mechanism that is not
eligible to consume further research budget.

Starting Phase C without an eligible candidate would additionally require a
new versioned Java CLI, a matching independent Python trace, overlay event and
state normalization, four ledger comparisons, adapter registration, and sealed
artifacts. That work has evidence-quality value only when it protects a real
candidate from cross-language accounting drift.

## Frozen activation gate

Start Phase C only when all are true:

1. canonical evidence produced one typed `READY_FOR_HYPOTHESIS` review;
2. Codex registered one deduplicated evidence-bound hypothesis and frozen
   manifest;
3. the approved Python adapter passed every preregistered Design and Validation
   economic, drawdown, holding, stability, and concentration gate, reaching
   `CANDIDATE_FROZEN`;
4. sealed OOS has not been opened;
5. the overlay contains complex lot-management behavior not covered by Phase B;
6. Phase C freezes the same input, rules, event vocabulary, and four-ledger
   comparison before implementation results are viewed.

If any item is false, disposition remains
`DEFER_PHASE_C_RETURN_TO_PERFORMANCE_EVIDENCE`.

## Performance and safety boundary

Expected immediate PnL and drawdown impact: `0`. The value of this decision is
opportunity-cost control: preserve engineering time for forward evidence and a
candidate with an actual causal performance thesis. This audit creates no
hypothesis, manifest, candidate, runner output, OOS access, canonical-state
write, schedule change, or Trading/DB/order/fund/SHADOW/PAPER/LIVE action.
