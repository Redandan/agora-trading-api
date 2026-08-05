# BTC DRA Flat-Range Cooldown-Preserving Upper-Touch Feasibility V3G Result

- Research identity:
  `BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_UPPER_TOUCH_FEASIBILITY_V3G_RESEARCH`
- Result date: 2026-08-03
- Status: `NO_NEXT_HYPOTHESIS`
- Scope: post-hoc read-only diagnostic only; not a candidate, not `SHADOW`,
  not `PAPER`, and not `LIVE`

## Decision

The frozen V3 flat-range entry reaches its entry-frozen upper third within 720
hours often enough to be meaningful, but neither preregistered entry-known
geometry measure predicts that outcome stably.

- Design: 18 of 31 mature lots touch within 720 hours (`58.06%`).
- Validation: 11 of 17 mature lots touch within 720 hours (`64.71%`).
- The remaining mature Validation lots all touch late; there are six late
  touches and no mature no-touch lot. One additional December 2024 lot is
  right-censored and is not counted as either success or failure.

The strongest apparent Design discriminator, upper distance no greater than
one ATR, reverses direction in Validation: its timely-touch precision falls
from `75.00%` in Design to `16.67%` in Validation. No factor passes the frozen
screen, so no next hypothesis is promoted.

## Frozen outcome and geometry

For every filled V3 lot, the outcome is the first later complete hourly close
at or above the upper third frozen on the entry signal bar:

```text
TIMELY_UPPER_TOUCH = first touch <= 720 hours after fill
upperDistanceAtr = (frozenUpperThird - signalClose) / causalATR14
rangeWidthAtr = prior20CompleteDayRangeWidth / causalATR14
```

The three preregistered admission filters were:

1. `upperDistanceAtr <= 1`;
2. `rangeWidthAtr <= 6`;
3. both conditions together.

One ATR is only an entry-feasibility unit. It is not a `1R` profit target and
does not change the frozen upper-third qualification or EMA5 reversal exit.

## Upper-touch evidence

| Mode | Design mature | Design timely | Design precision | Validation mature | Validation timely | Validation precision | Validation failure |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Unfiltered V3 upper | 31 | 18 | 58.06% | 17 | 11 | 64.71% | 35.29% |
| Upper distance <= 1 ATR | 8 | 6 | 75.00% | 6 | 1 | 16.67% | 83.33% |
| Range width <= 6 ATR | 31 | 18 | 58.06% | 16 | 10 | 62.50% | 37.50% |
| Both | 8 | 6 | 75.00% | 6 | 1 | 16.67% | 83.33% |

The distance rule keeps six Design successes and two failures, but in
Validation it keeps one success and five failures. Conversely, ten of the 11
timely Validation lots have distance greater than one ATR. The relationship is
therefore not stable across time and is unsuitable as an admission rule.

The range-width rule has almost no discrimination. Every Design entry passes.
In Validation it removes one timely 2024 entry and no failure, so precision and
economics both worsen. Every lot passing the distance rule also passes the
range-width rule, making the conjunction identical to distance alone.

## Annual stability

The distance rule's reversal is visible by year:

| Year | Unfiltered timely / mature | Distance <= 1 ATR timely / mature |
| --- | ---: | ---: |
| 2020 | 4 / 7 | 1 / 2 |
| 2021 | 7 / 11 | 1 / 1 |
| 2022 | 5 / 8 | 4 / 4 |
| 2023 | 3 / 7 | 0 / 3 |
| 2024 | 8 / 10 | 1 / 3 |

It looks strong in 2021-2022, then selects only late touches in 2023 and mostly
late touches in 2024. This is temporal instability rather than a marginal
threshold miss, so neighboring ATR values must not be scanned.

## Frozen V3 upper-third economics

| Mode | Design total | Validation realized | Validation unrealized | Validation total | DD | Median / P90 hold | Buys / sells / open |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Unfiltered V3 upper | -1.84413387 | 32.75201476 | -0.82980159 | 31.92221317 | 5.762120% | 512.0 / 2134.6 h | 18 / 17 / 1 |
| Upper distance <= 1 ATR | -10.82736638 | 10.93886751 | 0 | 10.93886751 | 3.940808% | 1287.5 / 2859.0 h | 6 / 6 / 0 |
| Range width <= 6 ATR | -1.84413387 | 29.26913629 | -0.82980159 | 28.43933470 | 5.762120% | 545.0 / 2175.5 h | 17 / 16 / 1 |
| Both | -10.82736638 | 10.93886751 | 0 | 10.93886751 | 3.940808% | 1287.5 / 2859.0 h | 6 / 6 / 0 |

Distance filtering lowers drawdown by participating much less, but it discards
most timely and profitable Validation lots. It fails minimum sample, touch
precision, failure rate, Design total, Validation total, holding, and completed
sell gates.

Range width retains the minimum sample and 10 completed sells, but it removes
one good Validation trade. Its total falls below the frozen
`28.72999185 USDT` floor and its holding tails remain worse than DRA V1.

Touching the upper third and completing a sale are also distinct events. The
unfiltered Validation median timely touch is `121` hours, while median completed
hold is `512` hours because a lot must subsequently show the frozen causal EMA5
reversal and remain net-positive at next-open fill. V3G diagnoses qualification
feasibility; it does not justify weakening the sell rule.

## Annual and one-slot economics

Unfiltered annual totals for 2020-2024 are `8.97658792`, `22.19555976`,
`-6.61334241`, `11.40421226`, and `18.97600676 USDT`. The distance rule is
positive in all five folds, but this comes from selecting very few lots; its
Validation precision and total fail catastrophically. Positive fold count
alone is not sufficient.

Under the independent 30-USDT one-slot overlay, unfiltered Validation total is
`16.22719511 USDT` with median/P90 holds of `745.5/2667.4` hours. Distance
filtering reduces total to `10.66575896 USDT` and worsens the holding tail to
`1017/2954.8` hours. Range width produces the same one-slot ledger as the
unfiltered mode apart from one extra rejected reservation that was already
capacity-blocked; it provides no economic improvement.

## July 2026 post-hoc illustration

The July signal occurs at `63,454.00`, fills at `63,485.72700`, and freezes the
upper third at `65,152.80`. Its signal-to-upper distance is
`1.002209 ATR`, while range width is `3.189631 ATR`.

Through the frozen July cutoff only 90 hours after fill are observable and no
upper touch occurs. The lot is therefore `RIGHT_CENSORED`, not a 720-hour
failure. It passes the range-width filter but misses the distance filter by
`0.002209 ATR`; this post-hoc near miss is not permission to loosen the frozen
one-ATR threshold.

## Cooldown, causality, and checkpoint audit

Across Design, Validation, all five fair-reset folds, and every fixed-calendar
one-slot run:

- reservation timestamps are identical across all modes;
- every rejected signal advances the unchanged 168-hour cooldown clock;
- every admitted fill is a subset of the unfiltered 250-USDT V3 fill set;
- ATR, range width, upper distance, and factor truth reconcile exactly;
- current incomplete UTC days are excluded from the 20-day range;
- entry, qualification, reversal, next-open positivity, and accounting audits
  pass.

DRA V1, V3 frozen upper-third, and V3 first-net-positive Validation checkpoints
all reproduce exactly. There is no cooldown phase-shift contamination.

## Frozen eligibility screen

No factor passes every screen:

- distance and conjunction fail sample size, both Validation label gates,
  Design/Validation economics, holding gates, and minimum completed sells;
- range width fails to improve either label window, reduces Validation total,
  and fails both holding gates.

The formal result is `NO_NEXT_HYPOTHESIS`. Do not scan adjacent ATR thresholds
or treat July's `1.002209 ATR` value as evidence for a relaxed cutoff.

## Interpretation

Entry-known target distance and range width are not enough to separate a
healthy oscillation from a range that is transitioning or failing. A nearby
upper target can remain untouched for months when the path regime changes,
while a target more than one ATR away can be reached quickly during an active
rebound.

V3G therefore closes the simple geometry branch. Any future research would
need a separately preregistered causal path-state thesis, not another ATR
multiple, and would still have to preserve the raw cooldown reservation
calendar. V3G itself does not select such a thesis.

## Reproducibility

- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `088284ec890431c83d6881471d03e75340f6fd936e88d8aba54d870294f7d313`.
- Runner SHA-256:
  `427423585c7074498314c71db13ac46f261ac1802da870e0f7f8c2b5d641130a`.
- Evidence JSON SHA-256:
  `6cea4adf6b4fad15ac418d8b5e0862d86e2b9e0b348f8523fac9e5be9d28f8c8`.
- Canonical evidence:
  `btc-dra-flat-range-cooldown-preserving-upper-touch-feasibility-v3g-2026-08-03.json`.

All results are post-hoc historical diagnostic evidence. No OOS, candidate,
`SHADOW`, `PAPER`, or `LIVE` claim is made.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
