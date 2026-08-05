# BTC DRA Independent-Lot Profit-Lock Exit V1 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_INDEPENDENT_LOT_PROFIT_LOCK_EXIT_V1_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_V1_V2A_V2B_V2C_V2D_V2E_PREVIOUS_STRUCTURAL
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

Removing runner and entitlement limits was directionally useful but did not
produce an acceptable exit.

The sole preregistered per-lot profit lock increased Validation total PnL from
V1's `86.20298186` to `96.72713306`. It nevertheless failed because realized
PnL fell, drawdown more than doubled, median holding increased, annual holding
improved in only one year, and total still did not retain `90%` of V2A.

The improvement was entirely mark-to-market. Relative to V1, realized PnL was
`9.88732681` lower while ending unrealized PnL was `20.41147801` higher. The
strategy had not converted its apparent advantage into completed profitable
sales.

The correct current action remains `KEEP_DRA_V1`.

## Frozen candidate

`ENTRY_ATR_1R_ARM_PEAK_PROFIT_50PCT_LOCK_EXIT`

Every DRA lot was independent. There was no runner, entitlement, breakout,
trend epoch, quota, or current-ATR trail.

For each lot:

```text
entryRiskR = entry ATR14 * filled quantity

armed when peak estimated net PnL >= 1R

profit floor = 50% * peak estimated net PnL

queue when armed
       and current estimated net PnL > 0
       and current estimated net PnL <= profit floor
```

Every queue used a complete hourly close. Every sale used the next hourly open
and still had to remain strictly net positive after fees and adverse slippage.
Neither `1R` nor `50%` was scanned.

## Data, artifacts, and reproducibility

- source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- all data-quality defect counts: zero;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `98b13326ebf22a345162a4046b2c0c64723aaaaf0753fa1ca031bec8b3008277`;
- previous structural specification SHA-256:
  `33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be`;
- V2C/V2D/V2E dependency SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`,
  `5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952`,
  and `0d0f8542d4d0863e7148d4ce69abb7ed41cb1abb053ff382effd9e3ab8d65d9b`;
- previous structural runner SHA-256:
  `41e1150510a1a68bff2d12fe4b3edf7d594623e701ab46f13485039e4bb74fa3`;
- profit-lock runner SHA-256:
  `739fea38ec89d2ea6dfc9161addc8a69213b13fbb6dd40d103155d49e06aae6c`.

Two complete preselection runs produced byte-identical `1,193,764`-byte JSON
outputs with SHA-256:

`d93768b6d05718e269d7193b05234acfec54abc29d6a8fc3251c0e21a9c30b67`.

The runner intentionally returns nonzero for `NO_CANDIDATE` after writing the
complete result.

## Exact checkpoint gate

Before evaluating the new formula, the runner exactly reproduced V1, V2A,
every V2B profile, every V2C factor, every V2D candidate and audit, V2E, and
the prior global-one-runner structural study. Numeric checkpoints, annual
wins, entitlement/runner audits, and prior per-path PnL attribution all
matched.

## Validation result

Window: `2023-01-01` through `2024-12-31`. Amounts are USDT.

| Exit | Realized | Unrealized | Total | Max DD | Median | P90 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` |
| Previous structural/V2E ledger | `89.40956909` | `-3.20820121` | `86.20136788` | `6.624292%` | `181.5h` | `833.4h` |
| V2A `1.50 ATR` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` |
| **Independent profit lock** | **`79.52385626`** | **`17.20327680`** | **`96.72713306`** | **`16.478718%`** | **`210.0h`** | **`1,263.0h`** |

The candidate had `51` buys, `48` sells, three open lots, zero blocked entries,
zero deferred exits, `25.544460%` average utilization, `60%` peak utilization,
and `1,519.52385626` turnover.

Compared with V1:

- realized: `-9.88732681`;
- unrealized: `+20.41147801`;
- total: `+10.52415120`;
- drawdown: `+9.357220` percentage points; and
- median holding: `+27.5h`.

The frozen `90% * V2A` threshold was `101.92585147`. The candidate missed it
by `5.19871841`.

## Per-lot state audit

| Audit | Validation result |
| --- | ---: |
| Bought lots | `51` |
| Lots armed after reaching `1R` | `50` |
| Profit-lock queues | `48` |
| Successful next-open exits | `48` |
| Deferred fills | `0` |
| Ending armed open lots | `2` |
| Ending unarmed open lots | `1` |
| Missing entry ATR | `0` |
| Trigger-condition violations | `0` |
| Profit-floor decreases | `0` |
| Non-positive/accounting exit violations | `0` |

All operational assertions passed. The failure was economic, not an
implementation or causal-data defect.

At the Validation boundary, the three open lots were:

| Fill | Current PnL | Peak PnL | `1R` | 50% floor | State |
| --- | ---: | ---: | ---: | ---: | --- |
| 2024-03-26 | `10.06223228` | `16.34555551` | `1.73351475` | `8.17277776` | armed, still above floor |
| 2024-11-06 | `10.34924573` | `16.67758389` | `0.95542845` | `8.33879195` | armed, still above floor |
| 2024-12-16 | `-3.20820121` | `0.99379959` | `1.15149129` | n/a | unarmed |

The two profitable open lots explain the positive ending unrealized result.
They had each surrendered more than `6 USDT` from their peaks but had not yet
reached the permissive half-peak floor, so the candidate continued holding
them. The third lot never reached `1R` and profit-only correctly left it open.

## Design result and regime instability

Design produced realized `524.42667149`, unrealized `14.89205948`, total
`539.31873097`, drawdown `48.036338%`, median/P90 holding
`375.0h / 4,095.8h`, `95 / 89 / 6` buys/sells/open lots, `23` blocked entries,
`61.818617%` average utilization, and `96%` peak utilization.

The large Design profit did not justify selection. Its `48%` drawdown,
multi-month holding tail, high utilization, blocked entries, and six ending
lots show strong regime dependence rather than a stable improvement.

## Fair-reset annual folds

| Year | Candidate total | V1 total | Candidate median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `225.18313811` | `56.19904224` | `263.0h` | `113.0h` | total win, hold loss |
| 2021 | `51.79761153` | `31.45159316` | `463.5h` | `103.5h` | total win, hold loss |
| 2022 | `-26.11282922` | `-15.77563722` | `113.0h` | `126.0h` | total loss, hold win |
| 2023 | `33.18382974` | `44.20017391` | `208.0h` | `204.0h` | total loss, hold loss |
| 2024 | `54.42759612` | `38.78866787` | `194.0h` | `160.0h` | total win, hold loss |

Annual total wins passed at `3/5`. Annual median-hold wins failed at `1/5`.
The candidate's strong bull-regime profit came with systematically slower
capital recycling.

## Frozen gate decision

Passed:

- Validation total `>= V1`;
- ending unrealized PnL;
- aggregate P90 holding;
- annual total wins;
- complete entry ATR state;
- all arming/trigger conditions;
- monotone profit floors; and
- strictly net-positive realized exits.

Failed:

- Validation total `>= 90% V2A` by `5.19871841`;
- Validation realized `>= V1` by `9.88732681`;
- maximum drawdown by `7.357220` percentage points versus the frozen cap;
- median holding by `27.5h`; and
- annual median-hold wins, `1/5` versus required `3/5`.

No failed gate was relaxed.

## Interpretation and next minimum hypothesis

The user's economic intuition was partly confirmed: positive profit should be
a safety condition, not the entire sell signal, and removing runner slots lets
independent lots capture more trend value. The candidate beat V1 total without
a runner concept.

However, allowing every full `30 USDT` lot to keep half of peak profit was too
permissive. It delayed realization, increased drawdown and holding, and left
the apparent edge concentrated in open mark-to-market gains.

The next smallest structural hypothesis is not a post-result sweep of the
`50%` floor. It is a separately authorized two-stage partial exit within each
lot: realize a core quantity after causal profit maturity and retain a smaller
residual quantity under the profit lock. That directly separates capital
recycling from tail participation. It changes the full-lot exit contract,
order count, fees, rounding, and holding accounting, so no split or threshold
was selected or tested here.

## OOS and Production boundary

No candidate was frozen. The OOS command returned `OOS_SEAL_REJECT` with
`preselection froze no candidate` before any post-2024 data access. A second
attempt refused to overwrite the existing guard artifact; its SHA-256 remained:

`0ff59a0c1165cd3174f4c0137f96741d4eb3893a9860472ed46fd3af6e97bc95`.

Therefore 2025+ OOS was not opened and the independent `30 USDT` one-slot
overlay was not run. No runtime, configuration, database, DRA V1, position
`263`, owner `509`, Grid/OCO, funds, schedules, Telegram, order, commit, or
deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-independent-lot-profit-lock-exit-v1-research.md`;
- research runner:
  `research/btc_dra_independent_lot_profit_lock_exit_v1.py`;
- result JSON run 1:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-independent-profit-lock-v1-preselection-2026-08-02-run1.json`;
- byte-identical run 2:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-independent-profit-lock-v1-preselection-2026-08-02-run2.json`;
- OOS seal guard:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-independent-profit-lock-v1-oos-seal-guard-2026-08-02.json`.

This result is historical research evidence only. It is not permission to
change DRA V1 or activate a SHADOW/LIVE strategy.
