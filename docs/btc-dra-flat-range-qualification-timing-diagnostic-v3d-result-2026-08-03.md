# BTC DRA Flat-Range Qualification-Timing Diagnostic V3D Result

- Research identity:
  `BTC_DRA_FLAT_RANGE_QUALIFICATION_TIMING_DIAGNOSTIC_V3D_RESEARCH`
- Result date: 2026-08-03
- Status: `NO_NEXT_HYPOTHESIS`
- Scope: post-hoc read-only diagnostic only; not a candidate, not `SHADOW`,
  not `PAPER`, and not `LIVE`

## Decision

None of the three preregistered qualification milestones preserves enough of
V3's range harvest while also solving its holding-time problem. No threshold
is relaxed and no mode is promoted to a new candidate.

The result exposes a clean trade-off:

- qualifying at first strict net-positive cuts Validation median holding from
  `512` to `29` hours and P90 from `2134.6` to `1171.6` hours, but total PnL
  falls from `31.92221317` to `6.16298366 USDT`;
- waiting for the frozen midpoint raises total to `12.31045808 USDT` and keeps
  the median at `98` hours, but P90 remains `1671.4` hours and total is still
  far below V3;
- waiting for the frozen upper third reproduces V3's `31.92221317 USDT`, but
  also reproduces its `512 / 2134.6`-hour holding failure.

All three modes have negative Design total PnL. The remaining economic problem
is not the speed of the EMA5 reversal after qualification. It is the set of
entries that take months to become profitable, or never become profitable at
all. Under the frozen profit-only rule, a sell-condition change cannot close
those underwater lots.

## Frozen causal comparison

The V3 flat lower-third reclaim entry, complete-bar observations, next-open
fills, costs, independent-lot accounting, arm, cooldown, reference cap, and
profit-only boundary are unchanged. Only the qualification milestone before a
strictly later `close < EMA5` reversal differs:

1. first estimated net PnL strictly above zero;
2. first touch of the entry-frozen range midpoint;
3. first touch of the entry-frozen range upper third.

The qualification bar cannot also queue the exit. Actual next-open net PnL
must remain strictly positive. No fixed profit target, stop, time exit, forced
loss, or final liquidation is present.

## Validation comparison: 2023-2024

| Metric | DRA V1 | First net-positive | Frozen midpoint | Frozen upper third |
| --- | ---: | ---: | ---: | ---: |
| Realized PnL (USDT) | 89.41118307 | 6.16298366 | 12.31045808 | 32.75201476 |
| Unrealized PnL (USDT) | -3.20820121 | 0.00000000 | 0.00000000 | -0.82980159 |
| Total PnL (USDT) | 86.20298186 | 6.16298366 | 12.31045808 | 31.92221317 |
| Max drawdown | 7.121498% | 3.892803% | 5.785905% | 5.762120% |
| Median hold | 182.5 h | 29.0 h | 98.0 h | 512.0 h |
| P90 hold | 1418.3 h | 1171.6 h | 1671.4 h | 2134.6 h |
| Average utilization | 21.632695% | 3.955540% | 6.472640% | 9.837893% |
| Turnover (USDT) | 1589.41118307 | 546.16298366 | 552.31045808 | 542.75201476 |
| Buys / sells / open | 51 / 50 / 1 | 18 / 18 / 0 | 18 / 18 / 0 | 18 / 17 / 1 |
| Blocked entries | 0 | 0 | 0 | 0 |

The entry-signal sets are exactly identical: all three modes have the same 18
Validation entries and every pairwise intersection is 18. The comparison is
therefore a clean exit-qualification ablation, not an entry-set change.

## Waiting-time decomposition

| Mode | Entry to qualification median / P90 | Qualification to queue median / P90 | Completed hold median / P90 |
| --- | ---: | ---: | ---: |
| First net-positive | 8.5 / 1160.5 h | 9.5 / 45.9 h | 29.0 / 1171.6 h |
| Frozen midpoint | 87.0 / 1667.0 h | 5.0 / 9.6 h | 98.0 / 1671.4 h |
| Frozen upper third | 503.0 / 2127.2 h | 10.0 / 17.4 h | 512.0 / 2134.6 h |

The reversal confirmation remains fast in all three modes. The long tail is
already present before qualification. Even the earliest possible economic
milestone, first strict net-positive, has a `1160.5`-hour P90 wait.

## Design robustness: 2019-2022

| Metric | First net-positive | Frozen midpoint | Frozen upper third |
| --- | ---: | ---: | ---: |
| Realized PnL (USDT) | 10.00846449 | 34.23541130 | 58.29106771 |
| Unrealized PnL (USDT) | -40.51227275 | -60.13520158 | -60.13520158 |
| Total PnL (USDT) | -30.50380826 | -25.89979028 | -1.84413387 |
| Max drawdown | 15.159037% | 21.065178% | 19.402320% |
| Median / P90 hold | 22.0 / 94.8 h | 68.0 / 640.9 h | 268.5 / 3538.4 h |
| Buys / sells / open | 31 / 29 / 2 | 31 / 28 / 3 | 31 / 28 / 3 |
| Median open-lot age | 8123 h | 8833 h | 8833 h |

The first-net-positive mode realizes quickly on lots that recover, but two
remaining underwater lots contribute `-40.51227275 USDT`. Midpoint and upper
third retain three stale lots with `-60.13520158 USDT`. This is why shortening
completed-trade holding time does not make the strategy robust.

## Frozen eligibility screen

| Gate | First net-positive | Frozen midpoint | Frozen upper third |
| --- | :---: | :---: | :---: |
| Design total > 0 | Fail | Fail | Fail |
| Validation total >= 31.92221317 | Fail | Fail | Pass |
| Validation DD <= 7.121498% | Pass | Pass | Pass |
| Validation median <= 182.5 h | Pass | Pass | Fail |
| Validation P90 <= 1418.3 h | Pass | Fail | Fail |
| Validation sells >= 10 | Pass | Pass | Pass |
| Positive-total folds >= 4/5 | Fail (3/5) | Pass (4/5) | Pass (4/5) |
| All causal/accounting audits | Pass | Pass | Pass |

No mode passes every frozen gate, so the formal result is
`NO_NEXT_HYPOTHESIS`.

## Annual fair-reset folds

| Year | First net-positive total | Midpoint total | Upper-third total |
| --- | ---: | ---: | ---: |
| 2020 | 1.35177042 | 5.17802491 | 8.97658792 |
| 2021 | -3.64392479 | 9.94324606 | 22.19555976 |
| 2022 | -15.88945858 | -10.77436066 | -6.61334241 |
| 2023 | 3.14439218 | 3.98092928 | 11.40421226 |
| 2024 | 3.73666581 | 9.04760313 | 18.97600676 |

Earlier selling does not repair the adverse 2022 entry cohort and also makes
2021 negative in the first-net-positive mode. Later qualification earns more
from recoveries but lengthens inventory lock-up.

## Independent one-slot overlay

| Metric | First net-positive | Frozen midpoint | Frozen upper third |
| --- | ---: | ---: | ---: |
| Total PnL (USDT) | 4.16725139 | 7.65739720 | 16.22719511 |
| Max drawdown | 17.651477% | 17.320600% | 19.031304% |
| Median / P90 hold | 25.0 / 870.4 h | 50.0 / 996.7 h | 745.5 / 2667.4 h |
| Buys / sells / open | 13 / 13 / 0 | 12 / 12 / 0 | 9 / 8 / 1 |
| Blocked entries | 26 | 31 | 43 |

The one-slot constraint converts long holding into blocked opportunities and
material percentage drawdown. It does not rescue any mode.

## July 2026 post-hoc illustration

All modes share the same causal buy at `63,485.72700` on 2026-07-28 06:00 UTC.
The frozen midpoint is `64,251.70`; the upper third is `65,152.80`.

- First net-positive qualifies after 9 hours and exits after 12 hours at an
  effective `63,655.15650`, realizing only `0.01993333 USDT`.
- Midpoint qualifies after 24 hours and exits after 31 hours at an effective
  `64,251.55815`, realizing `0.30119785 USDT`.
- Upper third is not reached before the cutoff, so the lot remains open at
  `-0.35522747 USDT`.

This illustrates why `有賺` is a necessary safety condition but not a
sufficient sell rationale: immediate net-positive harvest is fast, yet it
mostly captures noise-sized profits. The midpoint is economically more
meaningful, but the historical screen shows that it still does not solve the
tail or preserve enough total PnL.

## Interpretation and next direction

This branch should not continue by moving the sell threshold between midpoint
and upper third. The three anchors already identify the underlying frontier:
earlier qualification improves recycling by surrendering too much excursion;
later qualification preserves excursion by retaining stale inventory.

The next research, if authorized as a new goal, should move upstream to entry
admission rather than keep tuning exits. It should diagnose which causal
pre-entry contexts distinguish lots that reach strict net-positive promptly
from lots that remain underwater. Any future admission veto must preserve the
baseline cooldown phase even when it rejects a fill, so the result is not
confounded by shifted later entries. This is a new hypothesis family and is
not selected or tested by V3D.

## Reproducibility

- Selection data: server-local OKX `BTCUSDT` 1h complete bars.
- Selection cutoff: `2025-01-01T00:00:00Z`.
- Rows: `52,608`.
- Data SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `e0553993302fd1b13b26ea26142b09d7d66742bb6f69c39f87c58f4f48a70b72`.
- Runner SHA-256:
  `0a60cef8ef40fdbcaa01c151b3aab8c10535a6f31853431a0fa9a06216d90d8f`.
- Evidence JSON SHA-256:
  `d19488963f796a1fcd9740c5ade7fcfe5ac5ae825ae85c9e8f0e6fd4c4b3db32`.
- DRA V1 and frozen-upper-third V3 Validation checkpoints: exact.
- Canonical evidence:
  `btc-dra-flat-range-qualification-timing-diagnostic-v3d-2026-08-03.json`.

All selection and July evidence is post-hoc historical diagnostic material;
2025+ is not claimed as clean OOS and no candidate is promoted.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
