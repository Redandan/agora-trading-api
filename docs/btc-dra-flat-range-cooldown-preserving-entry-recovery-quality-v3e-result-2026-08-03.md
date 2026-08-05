# BTC DRA Flat-Range Cooldown-Preserving Entry-Recovery Quality V3E Result

- Research identity:
  `BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_ENTRY_RECOVERY_QUALITY_V3E_RESEARCH`
- Result date: 2026-08-03
- Status: `NO_NEXT_HYPOTHESIS`
- Scope: post-hoc read-only diagnostic only; not a candidate, not `SHADOW`,
  not `PAPER`, and not `LIVE`

## Decision

None of the three preregistered local-recovery factors distinguishes fast
recovery from long-underwater entries. They select the stale lots at least as
often as the unfiltered strategy while rejecting many lots that recover within
seven days.

The failure is directional, not a narrow threshold miss:

- every one of the three Design long-underwater lots closes above hourly EMA5
  and above the prior complete UTC-day close at entry;
- all three Validation long-underwater lots close above hourly EMA5, and two
  of three also close above the prior-day close;
- the hourly-EMA5 factor rejects zero long-underwater lots in both Design and
  Validation, but rejects 14 Design and five Validation rapid recoveries.

Local bounce confirmation therefore provides false comfort near a failing
range floor. No factor is promoted and no screen is relaxed.

## Frozen labels

- `RAPID_RECOVERY`: first fee/slippage-adjusted strict net-positive close
  within `168` hours, the frozen seven-day cooldown horizon.
- `INTERMEDIATE_RECOVERY`: first strict net-positive after `168` and within
  `720` hours.
- `LONG_UNDERWATER`: no strict net-positive within `720` hours, the frozen
  30-day arm/expiry horizon.
- `RIGHT_CENSORED`: insufficient in-window observation for 720 hours and no
  earlier label.

Labels use only future bars inside their own Design, Validation, or annual
window. They are never input features.

## Baseline recovery cohorts

| Window | Filled | Rapid | Intermediate | Long underwater | Rapid rate | Long rate | First-positive median / P90 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Design 2019-2022 | 31 | 27 | 1 | 3 | 87.10% | 9.68% | 4.0 / 78.0 h |
| Validation 2023-2024 | 18 | 15 | 0 | 3 | 83.33% | 16.67% | 8.5 / 1160.5 h |

Most entries recover quickly, but a small minority dominates the holding tail
and unrealized loss. The entry filter must identify that minority without
discarding the much larger rapid-recovery cohort.

## Factor discrimination

| Factor and window | Admitted mature lots | Rapid rate | Long rate | Rapid rejected | Long rejected |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unfiltered Design | 31 | 87.10% | 9.68% | 0 | 0 |
| Close > EMA5 Design | 16 | 81.25% | 18.75% | 14 | 0 |
| Close > prior-day close Design | 13 | 76.92% | 23.08% | 17 | 0 |
| Both Design | 11 | 72.73% | 27.27% | 19 | 0 |
| Unfiltered Validation | 18 | 83.33% | 16.67% | 0 | 0 |
| Close > EMA5 Validation | 13 | 76.92% | 23.08% | 5 | 0 |
| Close > prior-day close Validation | 9 | 77.78% | 22.22% | 8 | 1 |
| Both Validation | 8 | 75.00% | 25.00% | 9 | 1 |

Every factor lowers rapid-recovery precision and raises the admitted
long-underwater rate in both major windows. The conjunction is worst because
it removes the most good entries while retaining five of the six historical
long-underwater entries.

## Long-underwater lots

| Signal UTC | Window | Buy price | First positive | Above EMA5 | Above prior day |
| --- | --- | ---: | ---: | :---: | :---: |
| 2021-05-13 06:00 | Design | 51,346.96065 | 2773 h | Yes | Yes |
| 2021-11-17 10:00 | Design | 60,542.35605 | Not reached | Yes | Yes |
| 2022-04-08 14:00 | Design | 43,776.57735 | Not reached | Yes | Yes |
| 2023-05-10 12:00 | Validation | 28,188.98745 | 994 h | Yes | Yes |
| 2023-07-17 01:00 | Validation | 30,301.04295 | 2353 h | Yes | Yes |
| 2023-08-16 14:00 | Validation | 29,174.58000 | 1549 h | Yes | No |

These are not weak-looking hourly reclaims. Five of six pass both apparent
recovery confirmations, yet remain underwater for 41 to 115 days or never
recover inside the window.

Other descriptive features do not provide a stable rescue. For example,
median signed EMA20 slope is positive for Design long-underwater lots while it
is negative for Validation long-underwater lots. Median range width is wider
for Design long lots but narrower for Validation long lots. Selecting either
relationship after seeing it would be unstable post-hoc fitting.

## Frozen V3 upper-third economics

| Mode | Design total | Validation total | Validation DD | Validation median / P90 | Buys / sells / open |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unfiltered V3 | -1.84413387 | 31.92221317 | 5.762120% | 512.0 / 2134.6 h | 18 / 17 / 1 |
| Close > EMA5 | -15.00942295 | 21.63330822 | 5.839277% | 745.5 / 2339.1 h | 13 / 12 / 1 |
| Close > prior day | -22.59675156 | 17.71526501 | 4.108466% | 578.0 / 2571.6 h | 9 / 9 / 0 |
| Both | -26.56175443 | 16.48824808 | 4.108466% | 745.5 / 2667.4 h | 8 / 8 / 0 |

The filters reduce drawdown in some cases only by participating less. They do
not remove the Design stale loss, their Design totals worsen, and every
Validation total falls below the frozen `28.72999185 USDT` floor. Holding
median and P90 also remain above DRA V1.

The EMA5-only factor retains four positive annual folds, but fails the cohort,
Design, Validation-total, median, and P90 gates. The other two factors have
only three positive folds.

## Cooldown-preserving audit

Every raw V3 signal reserves the cooldown before factor or capacity decision.
Quality and capacity rejections therefore advance `lastEntrySignal` exactly as
an admitted signal would.

Across Design, Validation, all five annual folds, and every fixed-calendar
one-slot run:

- reservation timestamps are exactly identical across all four modes;
- every adjacent reservation is at least 168 hours apart;
- every rejection advances the cooldown clock;
- factor decisions reconcile exactly;
- every admitted fill belongs to the unfiltered 250-USDT V3 fill set;
- all entry, range, qualification, reversal, positivity, and accounting audits
  pass.

This eliminates the route-timing substitution that invalidated the earlier
stale-inventory veto interpretation.

## Independent fixed-calendar one-slot result

| Mode | Validation total | DD | Median / P90 | Buys / sells / open | Capacity / quality rejects |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unfiltered V3 | 16.22719511 | 19.031304% | 745.5 / 2667.4 h | 9 / 8 / 1 | 9 / 0 |
| Close > EMA5 | 12.47077539 | 20.701881% | 965.0 / 2859.0 h | 7 / 6 / 1 | 6 / 5 |
| Close > prior day | 13.30057698 | 20.701881% | 965.0 / 2859.0 h | 6 / 6 / 0 | 3 / 9 |
| Both | 13.30057698 | 20.701881% | 965.0 / 2859.0 h | 6 / 6 / 0 | 2 / 10 |

The filters do not improve the one-slot opportunity-cost profile.

## July 2026 post-hoc illustration

The July V3 buy at `63,485.72700` reaches strict net-positive after nine hours
and is correctly labelled `RAPID_RECOVERY`.

- It passes the hourly-EMA5 factor.
- It fails the prior-day-close factor because signal close `63,454.00` is
  below the prior complete-day close `63,759.90`.

The prior-day and combined filters would therefore reject the user's correctly
located July buy even though it recovers rapidly. This agrees with, but cannot
override, the pre-2025 failure.

## Frozen eligibility screen

No factor improves rapid precision or long-underwater rate in either major
window. No factor improves Design total, reaches 90% of V3 Validation total,
or passes the DRA V1 holding gates. The formal result is therefore
`NO_NEXT_HYPOTHESIS`.

## Interpretation and next direction

The useful finding is negative but specific: do not use `close > EMA5`,
`close > prior-day close`, or their conjunction as a flat lower-range
admission rule. These describe the immediate bounce, while the damaging risk
is whether the underlying range floor will subsequently fail.

This study does not authorize another factor. If a new research goal is
opened, the only materially different price-only family worth diagnosing is
entry-time range-floor integrity: for example the causal age and stability of
the prior-20-day low, rather than another momentum or trend confirmation. That
must be preregistered separately and must retain this fixed reservation-clock
implementation.

## Reproducibility

- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `b66e40f4414a595f94a6a07f9036c3333ceb1c65c0a198dbcabb1d3ff8b04de9`.
- Canonical runner SHA-256:
  `77ffd6fa7bd2a51bb1db5820dd6eb0a7089b03f5fe4dbbaf25f22e203b06725e`.
- Canonical evidence JSON SHA-256:
  `a4b1af9b70756910387f2f4683ef10f18dff044ed27616c96a1cedb942abcb41`.
- DRA V1, V3 upper-third, and V3 first-net-positive Validation checkpoints:
  exact.
- Canonical evidence:
  `btc-dra-flat-range-cooldown-preserving-entry-recovery-quality-v3e-2026-08-03-evidence-r1.json`.

The first JSON is preserved and was not overwritten. The canonical `r1`
changes only the one-slot audit reference from the capacity-constrained
one-slot fill subset to the preregistered unfiltered 250-USDT V3 fill set. No
strategy condition, label, factor, result metric, or gate changed.

All results are post-hoc historical diagnostic evidence; 2025+ is not claimed
as clean OOS and no candidate is promoted.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
