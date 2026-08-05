# BTC DRA ATR-Target Sparse Breakout Runner V2D Result

Date: 2026-08-02

Research identity: `BTC_DRA_ATR_TARGET_SPARSE_BREAKOUT_RUNNER_V2D_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_V1_V2A_V2B_V2C
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Frozen hypothesis tested

Every non-runner lot used the preregistered `1.0 entry ATR14 * quantity`
estimated-net profit target and next-open net-positive fill. A runner used the
V2A `1.50 ATR` monotonic ratchet. A fresh breakout required the current
complete daily close to cross above its prior 20 complete-day highs while the
previous close had not crossed its own prior-20 threshold.

At most one newest net-positive open lot could be assigned in each trend epoch.
An EMA20 down-cross reset the epoch slot without forcing an old runner to sell.
The three candidates were frozen before performance inspection:

1. fresh Donchian-20 only;
2. fresh Donchian-20 plus positive acceleration between the latest and prior
   non-overlapping seven-day returns; and
3. fresh Donchian-20 plus current complete-day true range above prior ATR14.

No ATR multiplier, factor threshold, fixed profit percentage, stop, time exit,
or final liquidation was scanned or added.

## Data and reproducibility acceptance

The preselection query was physically capped at the sealed OOS boundary.

- source: server-local, read-only `md_kline`, OKX `BTCUSDT` complete `1h`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- gaps, duplicates, off-grid rows, non-one-hour durations, invalid numeric,
  OHLC, or volume rows: zero;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- frozen specification SHA-256:
  `de3279688e1362360cd5f3d91ed6ba387a40ece95e6cac4f571c7bd411b4af3e`;
- V2C dependency SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`;
- V2D runner SHA-256:
  `5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952`.

Two complete server-local preselection runs produced byte-identical
`564,712`-byte JSON outputs. Both have SHA-256:

`d2f44f6c61e69b27fcf1eedcb32d6f16d2fdc85b4f9d0bca67fa8b316ca57cb1`.

The runner returns process exit code `2` for the formal `NO_CANDIDATE` status.
Both runs completed normally and wrote the full result before returning that
fail-closed code.

## Exact checkpoint reproduction

The runner first reproduced all frozen numeric and operational fields,
including buys, sells, open lots, blocked entries, utilization, and turnover.
Amounts below are USDT.

| Checkpoint | Realized | Unrealized | Total | Max DD | Median | P90 | Annual total/median wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 Design | `169.89846767` | `-79.12049441` | `90.77797326` | `29.530448%` | `126.0h` | `1,818.6h` | n/a |
| V1 Validation | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A Design | `277.82610201` | `-101.42144167` | `176.40466034` | `22.420205%` | `371.0h` | `1,561.8h` | n/a |
| V2A Validation | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` | n/a |
| V2B `TURNOVER` | `59.93271313` | `0.00000000` | `59.93271313` | `5.177908%` | `179.0h` | `891.0h` | `2/1` |
| V2B `BALANCED` | `71.44693976` | `0.00000000` | `71.44693976` | `5.257247%` | `209.0h` | `1,263.0h` | `1/0` |
| V2B `TREND` | `71.52625635` | `0.00000000` | `71.52625635` | `7.302341%` | `256.0h` | `1,451.0h` | `1/0` |
| V2C EMA20 slope | `113.10322216` | `-3.20820121` | `109.89502095` | `8.978650%` | `384.0h` | `1,704.4h` | `4/0` |
| V2C close below EMA5 | `81.23826208` | `-3.20820121` | `78.03006087` | `5.648317%` | `288.0h` | `1,416.0h` | `3/0` |
| V2C ATR1 reversal | `85.36169189` | `-3.20820121` | `82.15349068` | `7.192787%` | `336.0h` | `1,468.8h` | `2/0` |
| V2C Donchian5 + negative momentum | `116.85354741` | `-3.20820121` | `113.64534620` | `8.936078%` | `392.5h` | `1,846.6h` | `4/0` |
| V2C two-of-four | `87.71294693` | `-3.20820121` | `84.50474572` | `7.095375%` | `348.0h` | `1,487.0h` | `3/0` |

This is exact parity, not tolerance-based similarity.

## Validation result

Window: `2023-01-01` through `2024-12-31`. All candidates had `51` buys,
`50` sells, one open lot, zero blocked entries, and zero deferred exits.

| Candidate | Realized | Unrealized | Total | Max DD | Median | P90 | Avg util. | Turnover | Annual wins total/median |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fresh D20 | `85.25456892` | `-3.20820121` | `82.04636771` | `6.653979%` | `167.5h` | `833.4h` | `16.370041%` | `1,585.25456892` | `3/2` |
| Fresh D20 + 7D acceleration | `80.66650314` | `-3.20820121` | `77.45830193` | `6.772957%` | `162.0h` | `830.7h` | `16.118331%` | `1,580.66650314` | `2/2` |
| Fresh D20 + range expansion | `75.63344136` | `-3.20820121` | `72.42524015` | `6.858124%` | `159.0h` | `833.4h` | `16.023256%` | `1,575.63344136` | `2/2` |

The strongest candidate, fresh Donchian-20 alone, recovered
`20.18827374 USDT` over the plain `1.0 ATR` target total
`61.85809397`, but remained `4.15661415` below V1 total and realized PnL.
It also remained `19.87948376` below the `90% * V2A` total gate
`101.92585147`.

## Sparse-runner audit

All candidates passed the hard one-runner-per-epoch uniqueness assertion. The
extra confirmations reduced event count only modestly and none passed the
preregistered Validation limit of `ceil(10% * 51) = 6` assignments.

| Candidate | Fresh events | Confirmed | Filter rejected | No eligible lot | Epoch resets | Runners | Share | Runner/target fills | Rejected runner lots | Unique |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Fresh D20 | `38` | `38` | `0` | `26` | `40` | `9` | `17.647059%` | `9 / 41` | `5` epoch-used, `0` same-event | yes |
| Fresh D20 + 7D acceleration | `38` | `30` | `8` | `22` | `40` | `7` | `13.725490%` | `7 / 43` | `1` epoch-used, `1` same-event | yes |
| Fresh D20 + range expansion | `38` | `32` | `6` | `22` | `40` | `8` | `15.686275%` | `8 / 42` | `3` epoch-used, `0` same-event | yes |

The audit records every selected runner's breakout day, signal/fill time,
epoch, estimated net PnL, eligible-lot count, and same-event rejection count in
the JSON. The most important structural observation is that EMA20 generated
`40` down-cross resets in two Validation years. One runner per such epoch was
therefore not, by itself, a sufficiently sparse economic constraint.

## Design result

| Candidate | Realized | Unrealized | Total | Max DD | Median | P90 | Buys/sells/open | Blocked | Runners/share |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Fresh D20 | `171.51858518` | `-9.57666552` | `161.94191966` | `55.071096%` | `175.0h` | `1,711.6h` | `97 / 89 / 8` | `14` | `18 / 18.556701%` |
| Fresh D20 + 7D acceleration | `164.91883717` | `-9.57666552` | `155.34217165` | `55.480429%` | `156.0h` | `1,711.6h` | `97 / 89 / 8` | `14` | `18 / 18.556701%` |
| Fresh D20 + range expansion | `166.77374027` | `-9.57666552` | `157.19707475` | `55.463908%` | `156.0h` | `1,711.6h` | `97 / 89 / 8` | `14` | `17 / 17.525773%` |

Design profitability was positive, but its approximately `55%` drawdown and
eight ending open lots show that the runner handoff was not regime-stable.
Design is reported as required and was not used to relax the frozen Validation
gates.

## Fair-reset annual folds

Annual total PnL:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `56.19904224` | `31.45159316` | `-15.77563722` | `44.20017391` | `38.78866787` |
| Fresh D20 | `68.06362871` | `37.40329875` | `-19.57580034` | `40.73405466` | `39.13779605` |
| Fresh D20 + 7D acceleration | `63.61669196` | `37.40329875` | `-19.57580034` | `38.72011862` | `36.56366631` |
| Fresh D20 + range expansion | `68.06362871` | `30.12704591` | `-21.55646018` | `31.11292710` | `39.13779605` |

Annual median holding hours:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `113.0` | `103.5` | `126.0` | `204.0` | `160.0` |
| Fresh D20 | `108.5` | `175.0` | `206.0` | `151.5` | `188.0` |
| Fresh D20 + 7D acceleration | `108.0` | `175.0` | `206.0` | `151.5` | `188.0` |
| Fresh D20 + range expansion | `108.5` | `175.0` | `155.0` | `142.0` | `188.0` |

Fresh Donchian-20 alone met the total-win requirement at `3/5`, but all three
candidates improved annual median holding in only `2/5` folds. The two stricter
confirmations also reduced total wins to `2/5`.

## Why no candidate passed

- Fresh Donchian-20 alone passed Validation drawdown, ending unrealized,
  aggregate median/P90, annual total wins, and epoch uniqueness. It failed
  Validation realized, total versus V1, `90%` V2A retention, annual median
  wins, and the sparse runner-count gate.
- Seven-day acceleration and range expansion shortened aggregate median by a
  further `5.5h` and `8.5h`, respectively, but removed profitable runner
  opportunities faster than they removed weak ones. Both finished farther
  below V1 and achieved only `2/5` annual total wins.
- The common architecture materially improved drawdown and holding time versus
  V2A, but it did not identify the small set of large winners needed to offset
  the default target's surrendered trend PnL.
- The failure is not caused only by the explicit 10% sparsity audit. Ignoring
  that audit, every candidate still fails realized, total, `90%` V2A
  retention, and annual median stability.

Changing the target, ATR multiplier, epoch reset, formulas, tie-break, or gates
after these results would be a new post-hoc study. The correct frozen decision
for V2D is `NO_CANDIDATE`.

## Sealed boundary and artifacts

Because no candidate passed:

- no row from `2025-01-01` onward was queried or evaluated;
- no independent `30 USDT` one-slot overlay was run;
- no runtime implementation, strategy-catalog entry, SHADOW, LIVE, deployment,
  order, database, position, owner-509, Grid/OCO, fund, scheduler, Telegram, or
  Production change was made; and
- DRA V1 and position `263` remain unchanged.

The guarded OOS command was also exercised against the `NO_CANDIDATE`
manifest. It returned `OOS_SEAL_REJECT` before data access; a second call could
not overwrite the rejection artifact, whose SHA-256 remained
`d66eed0430608da16b83f3944ed9eb124dc2a655cb76e3c1a704d18c10de76b6`.

Reproducible artifacts:

- specification: `docs/btc-dra-atr-target-sparse-breakout-runner-v2d-research.md`;
- runner: `research/btc_dra_atr_target_sparse_breakout_runner_v2d.py`;
- machine-readable run 1:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-v2d-preselection-2026-08-02-run1.json`;
- byte-identical run 2:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-v2d-preselection-2026-08-02-run2.json`.

This result is historical research evidence only and is not authorization for
SHADOW or LIVE.
