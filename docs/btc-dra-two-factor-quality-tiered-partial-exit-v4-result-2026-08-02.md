# BTC DRA Two-Factor Quality-Tiered Partial Exit V4 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_TWO_FACTOR_QUALITY_TIERED_PARTIAL_EXIT_V4_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_NET_POSITIVE_EMA_PARTIAL_V3C
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
QUALITY_TIERED_EXIT_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

The three-tier candidate landed between V3B's profitable-but-slow endpoint and
V3C's fast-but-lower-profit endpoint, but did not combine their passing gates.

Validation realized/total were `99.68390014 / 96.47569893`, so the candidate
beat V1 realized and total by `10.27271707`. Drawdown `4.938290%` and P90
`1,315h` also passed. It nevertheless missed the frozen `90%` V2A total
threshold by `5.45015254`, missed median holding by `27.5h`, and produced only
`2/5` annual total wins and `0/5` annual holding wins.

The decision is `NO_CANDIDATE`; DRA V1 remains the reference.

## Frozen candidate

Every DRA entry still bought the unchanged `30 USDT` lot. The entry-signal
day's causal 7-day momentum acceleration and daily range expansion assigned:

- both true: full V2A `1.50 ATR`;
- exactly one true: `1R`-armed, EMA5-confirmed `24/6` partial path; and
- neither true: net-positive, EMA5-confirmed `24/6` partial path without `1R`.

Profit was necessary but not sufficient for both partial tiers. No entry was
blocked or resized by the tier, and there was no new factor, ratio, EMA, ATR,
or threshold scan.

## Validation result

| Exit | Realized | Unrealized | Total | DD | Median | P90 | Annual total / hold wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401h` | `1,846.6h` | reference |
| V3B all non-strong wait `1R` | `108.20391069` | `-3.20820121` | `104.99570948` | `6.388173%` | `317h` | `1,329.1h` | `4/5 / 0/5` |
| V3C all non-strong skip `1R` | `90.45986447` | `-0.64164024` | `89.81822423` | `5.905520%` | `184h` | `836h` | `2/5 / 4/5` |
| **V4 quality-tiered** | **`99.68390014`** | **`-3.20820121`** | **`96.47569893`** | **`4.938290%`** | **`210h`** | **`1,315h`** | **`2/5 / 0/5`** |

V4 had `51` buys, `82` exit slices, one ending lot, zero blocked entries and
zero deferred fills. Average utilization was `17.949932%`; turnover was
`1,599.68390014`.

## Tier audit

| Tier | Entries | Partial queues | Total PnL |
| --- | ---: | ---: | ---: |
| Strong: both factors, full V2A | `18` | n/a | `64.40155862` |
| Medium: exactly one, `1R` partial | `19` | `18` | `22.53515194` |
| Weak: neither, no-`1R` partial | `14` | `14` | `9.53898837` |

The tier counts sum exactly to all 51 entries. All medium queues were correctly
armed at `1R`; all weak entries had neither factor; cost and quantity
reconciled; every fill was strictly net-positive; and no lot filled two
partial tranches. The failure was economic, not causal or accounting drift.

Median first realization was `177.5h`, but cost-weighted median remained
`210h` and final-completion median remained `401h`. Accelerating only the 14
neither-factor entries did not release enough capital early. Accelerating more
entries would move the result toward V3C and its already-proven V2A-retention
failure.

## Annual folds

| Year | V4 total | V1 total | V4 median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `64.04986871` | `56.19904224` | `148h` | `113h` | total win, hold loss |
| 2021 | `27.27081806` | `31.45159316` | `240h` | `103.5h` | total loss, hold loss |
| 2022 | `-25.40075621` | `-15.77563722` | `160h` | `126h` | total loss, hold loss |
| 2023 | `44.03568376` | `44.20017391` | `305.5h` | `204h` | total loss, hold loss |
| 2024 | `50.94424281` | `38.78866787` | `208h` | `160h` | total win, hold loss |

The 2023 total miss was only `0.16449015`, but rounding it into a win would
still leave annual total wins at `3/5` while aggregate total retention,
aggregate median, and all five annual holding comparisons independently fail.

## Design

Design realized/unrealized/total were
`182.65888611 / -101.42144167 / 81.23744444`, drawdown `30.071381%`, and
median/P90 `183h / 1,640h`. It had `100` buys, `162` exit slices, six ending
lots, and three blocked entries. Tier counts were one inception fallback, 26
strong, 46 medium, and 27 weak. The known pre-2023 stale-inventory and regime
risk remained.

## Reproducibility

- selection data: `52,608` complete rows, first open
  `2019-01-01T00:00:00`, last close `2025-01-01T00:00:00`;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `0b16a6e98b9774b64106be77e20af15cc33a4cb33de3d2c0ef48d799f253e74e`;
- runner SHA-256:
  `9b7644aaf0702f982a0ef637c2a5e8604c8741353a14027c07dcabcb31bb3d3d`;
- two byte-identical `4,102,413`-byte accepted JSONs SHA-256:
  `ab0846b8163c7aab2667deee61cc401c2e75261213462e02c88722d9f9b8538e`.

The first two numeric runs exposed only nondeterministic dictionary ordering
in tier-PnL JSON fields. The runner was mechanically corrected to emit a fixed
tier order; no rule, trade, metric, or decision changed before the two accepted
reproducibility runs.

## OOS and Production boundary

No candidate was frozen. The OOS command returned `OOS_SEAL_REJECT` before
post-2024 data access; a second attempt refused to overwrite the guard and
preserved SHA-256
`f7e8878cca414d693eaa24bcd9cb7f2fa04136186621c82c7cfc8f5b056402f0`.

The independent one-slot overlay did not run. No runtime, configuration,
database, DRA V1, position `263`, owner `509`, Grid/OCO, funds, schedules,
Telegram, order, commit, or deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-two-factor-quality-tiered-partial-exit-v4-research.md`;
- runner:
  `research/btc_dra_two_factor_quality_tiered_partial_exit_v4.py`;
- accepted JSON runs:
  `btc-dra-quality-tiered-partial-v4-preselection-2026-08-02-run1-fixed.json`
  and `run2-fixed.json` in the task visualization directory;
- OOS seal:
  `btc-dra-quality-tiered-partial-v4-oos-seal-guard-2026-08-02.json` in the
  same directory.
