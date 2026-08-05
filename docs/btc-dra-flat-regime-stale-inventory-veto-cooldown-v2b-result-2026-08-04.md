# BTC DRA Flat-Regime Stale-Inventory Veto Cooldown V2B Result

- Research identity:
  `BTC_DRA_FLAT_REGIME_STALE_INVENTORY_VETO_COOLDOWN_V2B_RESEARCH`
- Result date: 2026-08-04
- Status: `NO_CANDIDATE_KEEP_DRA_V1`
- Scope: post-hoc historical research only; not `SHADOW`, `PAPER`, or `LIVE`

## Decision

Reserving the shared seven-day cooldown after a flat stale-inventory veto fixes
the diagnosed 2022 route-substitution mechanism, but it does not improve the
full Validation economics enough to pass the frozen gates.

The branch is closed. Keep DRA V1 and do not scan the `168-hour` cooldown or
`60-USDT` exposure constants.

## What the mechanism fixed

The V2B implementation reserved the rejected flat signal timestamp for every
veto. In Validation:

- all four vetoes reserved the cooldown;
- the accepted-or-reserved calendar had a minimum gap of `170` hours;
- admission accounting, veto predicates, route isolation, and one-slot parity
  all passed.

For the diagnosed 2022 failure:

| Metric | Parent V2 | V2B cooldown reservation |
| --- | ---: | ---: |
| Non-flat DRA entries | 9 | 7 |
| Terminal open lots | 6 | 4 |
| Terminal open cost | 180 USDT | 120 USDT |
| Total PnL | -44.78885760 USDT | -36.40596067 USDT |
| Max drawdown | 21.647525% | 18.744914% |

This confirms that the parent V2 veto changed later route eligibility and that
cooldown reservation removes the two additional non-flat entries identified in
the predecessor diagnosis.

## Why it still failed

Matched-capital Validation remained economically worse than the parent V2:

| Metric | DRA V1 | Parent V2 router | V2B router |
| --- | ---: | ---: | ---: |
| Realized PnL | 89.41118307 | 99.94153541 | 97.93590789 |
| Unrealized PnL | -3.20820121 | -1.56726556 | -1.56726556 |
| Total PnL | 86.20298186 | 98.37426985 | 96.36864233 |
| Max drawdown | 7.121498% | 10.770310% | 10.818832% |
| Median hold | 182.5 h | 245.0 h | 197.0 h |
| P90 hold | 1418.3 h | 2435.6 h | 2457.8 h |
| Terminal open lots / cost | 1 / 30 USDT | 1 / 30 USDT | 1 / 30 USDT |

Relative to parent V2, V2B lost `2.00562752 USDT` of Validation total PnL and
increased drawdown by `0.048522` percentage points. Median holding improved,
but remained above DRA V1; P90 holding worsened.

The parent gates that failed were:

- standalone median and P90 holding no higher than DRA V1;
- routed drawdown within DRA V1 plus two percentage points;
- routed median and P90 holding no higher than DRA V1;
- annual median-hold wins in at least three of five folds (`2/5`).

The added mechanism gates also rejected V2B because Validation total PnL was
below parent V2 and drawdown was higher. The mechanism improvement is real but
insufficient as a strategy improvement.

## Evidence integrity

- Data quality: `PASS`.
- Baseline parity:
  `PASS_DRA_V1_FLAT_V1_ROUTER_V1_ONE_SLOT`.
- Selection rows: `52,608`.
- Selection SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.
- Specification SHA-256:
  `26bbb9695679db3d7e5b2cdc2cae84660bc4051489d6fa00a4497b9437fb9fab`.
- Parent V2 runner SHA-256:
  `41ee7d08bb459890b4e1b078fe839f31f6ce32dd4cec02cc473450d01c28dbbc`.
- V2B runner SHA-256:
  `4347f5b3ed090a11c6bea018e0bacfb6f0ab18a95ff1d29c900f0e6a1e7c623e`.
- Sealed diagnostic SHA-256:
  `584856a3342e63248751a9b4669b7ff32105ac8bec473b8c22fc21a1f1279fac`.
- Sealed learning SHA-256:
  `3c85b9cb6a0de6eaefbbc02a064a9f59d24532900e6c48e45e5271678177d0a9`.
- Qualified candidates: `0`.
- OOS opened: `false`; no clean OOS was available by contract.

## Learning disposition

`DO_NOT_REPEAT_WITH_RELAXED_GATES`.

Cooldown reservation is retained as causal knowledge: it prevents the observed
route substitution, but it is not a sufficient alpha or risk-adjusted
performance improvement. A future branch must introduce a separately frozen
economic mechanism rather than tune this veto or its cooldown.

## Operational boundary

No Production runtime, configuration, database, DRA V1, position `263`, owner
`509`, Grid/OCO, funds, schedules, Telegram, orders, deployment, or external
write changed.
