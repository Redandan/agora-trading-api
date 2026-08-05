# BTC DRA V3C Pre-Partial 1R Promotion Exit V7 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_V3C_PRE_PARTIAL_ONE_R_PROMOTION_EXIT_V7_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_POST_ENTRY_BREAKOUT_PROMOTION_PARTIAL_V6
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
PRE_PARTIAL_1R_PROMOTION_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

V7 improved V3C's profit while preserving most of its fast-exit behavior, but
still failed three frozen gates.

Validation realized/unrealized/total were
`96.02789691 / -0.64164024 / 95.38625667`. Total beat V1 by `9.18327481`
and V3C by `5.56803244`; realized beat V1 by `6.61671384`. Drawdown
`6.832349%` and P90 `836h` passed.

V7 nevertheless retained only `84.225572%` of V2A and missed the frozen
`90%` V2A threshold by `6.53959480`. Cost-weighted median was `192h`, missing
by `9.5h`, and annual total wins were `2/5` instead of `3/5`. Annual
median-hold wins reached the required `3/5`.

The decision remains `NO_CANDIDATE`; DRA V1 stays the reference and 2025+ OOS
remains sealed.

## Frozen architecture

V7 retained V3C's entry routing:

- the `18` entries where both 7-day momentum acceleration and daily range
  expansion passed used full V2A;
- the other `33` entries started on the unchanged V3C net-positive plus hourly
  EMA5 `24/6` partial path.

For a partial-eligible lot only, V7 tracked full-lot estimated net PnL. If its
first causal peak reached:

```text
entry ATR14 * original filled quantity
```

before any partial fill, the lot immediately promoted to full V2A. It did not
wait for EMA deterioration and never partially sold. Lots that had not reached
`1R` continued to use V3C immediately; they were never forced to wait.

This is different from V3B, which required all partial-eligible lots to reach
`1R` before allowing their partial exit. V7 had no runner quota, epoch,
entitlement, tie-break, daily breakout, or changed entry.

## Validation comparison

| Exit | Realized | Unrealized | Total | DD | Median | P90 | Annual total / hold wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401h` | `1,846.6h` | reference |
| V3B wait-for-1R partial | `108.20391069` | `-3.20820121` | `104.99570948` | `6.388173%` | `317h` | `1,329.1h` | `4/5 / 0/5` |
| V3C no-1R partial | `90.45986447` | `-0.64164024` | `89.81822423` | `5.905520%` | `184h` | `836h` | `2/5 / 4/5` |
| V4 entry quality tier | `99.68390014` | `-3.20820121` | `96.47569893` | `4.938290%` | `210h` | `1,315h` | `2/5 / 0/5` |
| **V7 pre-partial 1R promotion** | **`96.02789691`** | **`-0.64164024`** | **`95.38625667`** | **`6.832349%`** | **`192h`** | **`836h`** | **`2/5 / 3/5`** |

Among the fast/hybrid branches, V7 is the best balanced endpoint so far. V4
has `1.08944226` more total PnL, but V7 reduces median by `18h`, P90 by
`479h`, and improves annual hold wins from `0/5` to `3/5`.

V7 had `51` buys, `75` exit slices, one open `6 USDT` remainder, zero blocked
entries, zero deferred fills, average utilization `15.821888%`, maximum open
cost `132 USDT`, and turnover `1,620.02789691`.

Median first realization was `149h`; median final completion remained `401h`.
Route total PnL was `64.40155862` for the original full-V2A route and
`30.98469805` for the partial-eligible route.

## Promotion audit

V7 promoted `8` of the `33` partial-eligible lots, or `24.242424%`. All eight
were unique first crossings of the exact entry-ATR `1R` threshold, occurred
before any partial fill, completed their full V2A exit, and never partially
sold. No quota or tie-break rejection existed.

Median/P90 promotion delay were `45.5h / 177.9h`, substantially earlier than
V6's Donchian promotion `120h / 172.8h`. The other `25` partial-eligible lots
kept the V3C partial path without waiting for `1R`.

No promotion and EMA5 partial condition occurred on the same hour in
Validation, so the frozen promotion-first ordering was not outcome-active.
All non-promoted partial queues were still below `1R`; all cost, quantity,
positive-fill, route, EMA, threshold, and single-partial audits passed.

## Annual folds

| Year | V7 total | V1 total | V7 median | V1 median | Promotions | Result |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 2020 | `51.28045134` | `56.19904224` | `103.5h` | `113h` | `3` | total loss, hold win |
| 2021 | `14.60479908` | `31.45159316` | `26h` | `103.5h` | `2` | total loss, hold win |
| 2022 | `-2.14051706` | `-15.77563722` | `63h` | `126h` | `2` | total win, hold win |
| 2023 | `39.87706746` | `44.20017391` | `302.5h` | `204h` | `4` | total loss, hold loss |
| 2024 | `54.01341685` | `38.78866787` | `190h` | `160h` | `4` | total win, hold loss |

Design realized/unrealized/total were
`133.02782143 / -38.12504610 / 94.90277533`, drawdown was `16.496399%`, and
median/P90 were `117h / 1,094h`. Design also produced eight promotions.

The remaining problem is promotion precision. Promoting all pre-partial `1R`
lots recovered profit but held too many mediocre continuations, especially in
2023. A new threshold scan is not justified; the next defensible step is a
Design-only attribution of the eight promotions to determine whether one
observable state at the first `1R` crossing separates useful and harmful
promotions.

## Reproducibility and OOS seal

- preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `b4034444510411a5e45681f5a9b12744e072bfee0b14e94842a09e5d9ee7be79`;
- runner SHA-256:
  `9441ff63db551d5105082387822f7a4ccdcd01e247ad86c6db5382d6df21d532`;
- two byte-identical `5,308,521`-byte preselection JSONs SHA-256:
  `a9fa40c204f841559bb12ab32a48b4bf7c8aee655f8408b10b0309e7d3e34ab4`.

The OOS command returned `OOS_SEAL_REJECT` before post-2024 data access because
preselection froze no candidate. A second attempt refused to overwrite the
guard and preserved SHA-256
`7794f90e952e99656f88556dc29843e9c8cc6384bcd4523e90e124e0429ce027`.

The independent one-slot overlay did not run. No runtime, configuration,
database, DRA V1, position `263`, owner `509`, Grid/OCO, funds, schedules,
Telegram, order, commit, or deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-v3c-pre-partial-one-r-promotion-exit-v7-research.md`;
- runner:
  `research/btc_dra_v3c_pre_partial_one_r_promotion_exit_v7.py`;
- accepted preselection runs:
  `btc-dra-v3c-pre-partial-1r-promotion-v7-preselection-2026-08-02-run1.json`
  and `run2.json` in the task visualization directory;
- OOS seal:
  `btc-dra-v3c-pre-partial-1r-promotion-v7-oos-seal-guard-2026-08-02.json` in
  the same directory.
