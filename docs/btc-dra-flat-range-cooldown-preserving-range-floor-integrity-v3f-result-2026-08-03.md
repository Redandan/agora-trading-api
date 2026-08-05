# BTC DRA Flat-Range Cooldown-Preserving Range-Floor Integrity V3F Result

- Research identity:
  `BTC_DRA_FLAT_RANGE_COOLDOWN_PRESERVING_RANGE_FLOOR_INTEGRITY_V3F_RESEARCH`
- Result date: 2026-08-03
- Status: `NO_NEXT_HYPOTHESIS`
- Scope: post-hoc read-only diagnostic only; not a candidate, not `SHADOW`,
  not `PAPER`, and not `LIVE`

## Decision

Range-floor age has no useful discrimination. Comparing the current prior-20
day floor with the preceding non-overlapping 20-day floor does improve the
rapid-versus-long-underwater cohort split, but it does not improve the frozen
V3 upper-third economics. No factor passes the preregistered screen.

The key distinction is:

- floor stability contains information about reaching any strict net-positive
  price within 30 days;
- it does not identify whether the much higher frozen upper third can be
  reached and harvested economically.

The factor therefore improves the diagnostic label while worsening both
Design and Validation total PnL. It is not promoted.

## Frozen factors

Using complete UTC days strictly before the signal day:

```text
currentFloor = min low of prior 20 complete days
precedingFloor = min low of preceding non-overlapping 20 complete days
floorAge = signal UTC date - latest date matching currentFloor
```

The three preregistered modes are:

1. current floor age at least seven complete days;
2. current floor not below preceding floor;
3. both conditions together.

Seven days is the frozen cooldown horizon. There is no threshold scan, ATR
buffer, incomplete-day use, trend regime, or non-price input.

## Recovery-cohort discrimination

| Mode and window | Mature lots | Rapid rate | Long-underwater rate | Rapid rejected | Long rejected |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unfiltered Design | 31 | 87.10% | 9.68% | 0 | 0 |
| Floor age >= 7d Design | 29 | 86.21% | 10.34% | 2 | 0 |
| Floor not below preceding Design | 25 | 88.00% | 8.00% | 5 | 1 |
| Both Design | 23 | 86.96% | 8.70% | 7 | 1 |
| Unfiltered Validation | 18 | 83.33% | 16.67% | 0 | 0 |
| Floor age >= 7d Validation | 13 | 76.92% | 23.08% | 5 | 0 |
| Floor not below preceding Validation | 12 | 91.67% | 8.33% | 4 | 2 |
| Both Validation | 10 | 90.00% | 10.00% | 6 | 2 |

Floor age alone is directionally wrong: all six long-underwater lots are at
least seven days from their latest 20-day low, so it rejects only rapid
recoveries.

The non-declining-floor rule is the only informative diagnostic. It rejects
one of three Design long lots and two of three Validation long lots while
raising rapid precision in both windows. The conjunction loses that Design
precision improvement because its floor-age component removes additional good
entries.

## Long-underwater floor states

| Signal UTC | Window | Buy | Floor / preceding floor | Floor age | Stability pass |
| --- | --- | ---: | ---: | ---: | :---: |
| 2021-05-13 06:00 | Design | 51,346.96065 | 46,988.10 / 50,471.90 | 18d | No |
| 2021-11-17 10:00 | Design | 60,542.35605 | 55,896.00 / 53,615.10 | 20d | Yes |
| 2022-04-08 14:00 | Design | 43,776.57735 | 40,480.00 / 36,965.80 | 18d | Yes |
| 2023-05-10 12:00 | Validation | 28,188.98745 | 26,902.60 / 27,202.90 | 16d | No |
| 2023-07-17 01:00 | Validation | 30,301.04295 | 29,419.00 / 24,783.00 | 17d | Yes |
| 2023-08-16 14:00 | Validation | 29,174.58000 | 28,570.10 / 28,866.70 | 15d | No |

The rule removes three lower-shifted floors, but keeps the two Design lots that
never become net-positive inside the entire Design window. A floor can be
higher than its preceding 20-day floor and still be located near a larger
market transition.

## Frozen V3 upper-third economics

| Mode | Design total | Validation total | Validation DD | Validation median / P90 | Buys / sells / open |
| --- | ---: | ---: | ---: | ---: | ---: |
| Unfiltered V3 | -1.84413387 | 31.92221317 | 5.762120% | 512.0 / 2134.6 h | 18 / 17 / 1 |
| Floor age >= 7d | -6.60344251 | 21.41879299 | 3.888736% | 447.5 / 1503.9 h | 13 / 12 / 1 |
| Floor not below preceding | -16.97355572 | 19.98472173 | 4.060577% | 129.0 / 2380.0 h | 12 / 11 / 1 |
| Both | -21.73286436 | 16.80797742 | 2.149120% | 129.0 / 1206.4 h | 10 / 9 / 1 |

The stability factor reaches the DRA V1 median-hold gate but fails P90, Design
total, and the frozen `28.72999185 USDT` Validation floor. The conjunction
passes both holding gates and has four positive annual folds, but realizes
only `16.80797742 USDT`, completes only nine Validation sells, and has a much
worse Design total.

The filters lower drawdown primarily through reduced participation. They do
not remove the three Design ending open lots, whose combined unrealized PnL
remains `-60.13520158 USDT` in every mode.

## Label-to-economic-target mismatch

First strict net-positive is necessary for any profit-only exit, but it is not
enough for the frozen upper-third harvest.

Two ending open lots are especially informative:

- the 2021-12-28 Design lot becomes net-positive after three hours, passes both
  floor factors, but never completes the frozen upper-third exit by the Design
  boundary;
- the 2024-12-22 Validation lot becomes net-positive after 15 hours, passes
  both factors, but remains open below its frozen `102,147.30` upper third at
  the Validation boundary.

Both are labelled rapid recovery even though they still consume upper-third
inventory. This explains why better first-positive cohort statistics do not
translate into better V3 total PnL or holding tails.

## Annual and one-slot evidence

All three factors have four positive annual folds, but none repairs the 2022
loss. Under the floor-stability rule, 2022 total worsens from `-6.61334241` to
`-13.37906189 USDT`.

The floor-age one-slot diagnostic increases Validation total from
`16.22719511` to `17.46513883 USDT` and reduces P90 from `2667.4` to `1289.6`
hours. This cannot override the multi-lot failure: its recovery precision is
worse, it rejects no long-underwater lot, and its result depends on which raw
fixed-calendar reservations encounter the single available slot.

## July 2026 post-hoc illustration

The July buy at `63,485.72700` is retained by every factor:

- current prior-20 floor: `61,548.40`;
- preceding non-overlapping floor: `57,809.40`;
- latest current-floor day: 2026-07-08;
- floor age at signal: `20` complete days;
- first strict net-positive: nine hours.

The July lot is a rapid recovery and the range-floor state looks stable. It
still does not reach the frozen upper-third exit before the July cutoff, which
again separates recovery from range-completion feasibility.

## Cooldown and causality audit

Across Design, Validation, all five annual folds, and every fixed-calendar
one-slot run:

- reservation timestamps are identical across all modes;
- every rejection advances the cooldown clock;
- each current and preceding floor window contains exactly 20 complete days;
- the windows are non-overlapping and exclude the current signal day;
- every admitted fill belongs to the unfiltered 250-USDT V3 fill set;
- all entry, qualification, reversal, positivity, and accounting audits pass.

There is no cooldown phase-shift contamination.

## Frozen eligibility screen

No factor passes every screen. Floor stability has genuine recovery-label
information, but fails Design total, Validation total, and Validation P90.
The conjunction additionally fails minimum completed sells. The formal result
is `NO_NEXT_HYPOTHESIS`.

## Interpretation and next direction

Do not tune the seven-day age or add a floor buffer. The observed mismatch is
more fundamental: the label measures first profitability, while the economic
strategy requires timely upper-third completion.

If another separately preregistered diagnostic is authorized, it should align
the outcome with the actual strategy: classify whether each lot reaches its
entry-frozen upper third within the same 720-hour horizon, then inspect only
entry-known range-completion geometry such as upper-third distance and range
width normalized by causal ATR. V3F itself does not select or test that new
hypothesis family.

## Reproducibility

- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `a2b32b75a38522bcf096954ec5197ce73b659f6d4e84862301ce3a3f81101088`.
- Runner SHA-256:
  `7f51721fa3efbed6a3521310d25197fd3de0303ecbd678c128c00b29245f81ad`.
- Evidence JSON SHA-256:
  `0d5f6d256106a8dcb5a7b788968abe3fc038c79cc92f107bbc847e2785d190e7`.
- DRA V1, V3 upper-third, and V3 first-net-positive Validation checkpoints:
  exact.
- Canonical evidence:
  `btc-dra-flat-range-cooldown-preserving-range-floor-integrity-v3f-2026-08-03.json`.

All results are post-hoc historical diagnostic evidence; 2025+ is not claimed
as clean OOS and no candidate is promoted.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, orders, deployment, or external write was
changed.
