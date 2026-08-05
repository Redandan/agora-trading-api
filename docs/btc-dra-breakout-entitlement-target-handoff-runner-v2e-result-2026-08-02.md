# BTC DRA Breakout-Entitlement Target-Handoff Runner V2E Result

Date: 2026-08-02

Research identity: `BTC_DRA_BREAKOUT_ENTITLEMENT_TARGET_HANDOFF_RUNNER_V2E_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_V1_V2A_V2B_V2C_V2D
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
DRA_DYNAMIC_EXIT_RESEARCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Frozen candidate tested

V2E tested one candidate only:

`FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_HANDOFF_RUNNER`

A fresh Donchian-20 cross created one pending entitlement for its causal EMA20
trend epoch. It did not immediately retain a lot. The earliest later complete
hour with at least one lot at its `1.0 entry ATR14 * quantity` net-profit target
consumed the entitlement; the latest-filled target-ready lot became the V2A
`1.50 ATR` runner and other lots stayed on the default target. An EMA20
down-cross expired a pending entitlement before that bar's exit decision.

There was no parameter sweep, alternate expiry, event window, factor filter,
target, ATR multiplier, or post-result tie-break.

## Data, artifact, and reproducibility acceptance

- source: server-local, read-only `md_kline`, OKX `BTCUSDT` complete `1h`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- gaps, duplicates, off-grid rows, invalid duration, numeric, OHLC, or volume:
  zero;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- V2E specification SHA-256:
  `cc75af188264a49c4e915d72a911aac23f512ed749392324f10795477f8713f2`;
- V2D specification SHA-256:
  `de3279688e1362360cd5f3d91ed6ba387a40ece95e6cac4f571c7bd411b4af3e`;
- V2C dependency SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`;
- V2D dependency SHA-256:
  `5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952`;
- V2E runner SHA-256:
  `0d0f8542d4d0863e7148d4ce69abb7ed41cb1abb053ff382effd9e3ab8d65d9b`.

Two complete preselection runs produced byte-identical `516,847`-byte JSON
outputs with SHA-256:

`fc108f494f33b27cb3a9d5884a0dd197ff25629df55dd8dce209eb0dab8e49a3`.

The runner intentionally returns process exit code `2` for the terminal
research-stop decision. Both runs completed all simulations and wrote the full
result normally.

## Exact checkpoint reproduction

Before V2E was evaluated, the runner reproduced the exact V1 through V2D
numeric and operational checkpoints. This included buys, sells, open lots,
blocked entries, utilization, turnover, annual wins, V2D runner counts,
rejection counts, and epoch uniqueness.

| Checkpoint | Realized | Unrealized | Total | Max DD | Median | P90 | Annual total/median wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 Design | `169.89846767` | `-79.12049441` | `90.77797326` | `29.530448%` | `126.0h` | `1,818.6h` | n/a |
| V1 Validation | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | reference |
| V2A Design | `277.82610201` | `-101.42144167` | `176.40466034` | `22.420205%` | `371.0h` | `1,561.8h` | n/a |
| V2A Validation | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` | n/a |
| V2D Fresh D20 | `85.25456892` | `-3.20820121` | `82.04636771` | `6.653979%` | `167.5h` | `833.4h` | `3/2` |
| V2D + 7D acceleration | `80.66650314` | `-3.20820121` | `77.45830193` | `6.772957%` | `162.0h` | `830.7h` | `2/2` |
| V2D + range expansion | `75.63344136` | `-3.20820121` | `72.42524015` | `6.858124%` | `159.0h` | `833.4h` | `2/2` |

The V2B annual wins reproduced as `TURNOVER 2/1`, `BALANCED 1/0`, and
`TREND 1/0`. The five V2C annual wins reproduced as `4/0`, `3/0`, `2/0`,
`4/0`, and `3/0` in their frozen factor order. All underlying Validation
ledgers matched exactly.

## Validation result

Window: `2023-01-01` through `2024-12-31`. Amounts are USDT.

| Exit | Realized | Unrealized | Total | Max DD | Median | P90 | Runners | Annual total/median wins |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Plain `1.0 ATR` target | `65.06629518` | `-3.20820121` | `61.85809397` | `6.641564%` | `140.5h` | `830.7h` | `0` | n/a |
| V2D Fresh D20 | `85.25456892` | `-3.20820121` | `82.04636771` | `6.653979%` | `167.5h` | `833.4h` | `9` | `3/2` |
| **V2E target handoff** | **`89.40956909`** | **`-3.20820121`** | **`86.20136788`** | **`6.624292%`** | **`181.5h`** | **`833.4h`** | **`11`** | **`3/1`** |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | n/a | reference |
| V2A `1.50 ATR` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` | all lots | n/a |

V2E had `51` buys, `50` sells, one open lot, zero blocked entries, zero
deferred exits, `17.041040%` average utilization, `60%` peak utilization, and
`1,589.40956909` turnover.

The handoff recovered `24.34327391` over the plain target and `4.15500017`
over V2D Fresh D20. It finished only `0.00161398` below V1 total and realized
PnL. Exact near-equality is still a failed `>= V1` gate. More importantly, V2E
was `15.72448359` below the frozen `90% * V2A` requirement
`101.92585147`.

## Entitlement and sparsity audit

| Audit | Validation result |
| --- | ---: |
| Fresh breakout events | `38` |
| Trend-epoch resets / epochs observed | `40 / 41` |
| Entitlements created | `16` |
| Duplicate events while pending / consumed | `4 / 18` |
| Pending entitlements expired | `5` |
| Entitlements handed off | `11` |
| Runner share of buys | `21.568627%` |
| Runner / target exit fills | `11 / 39` |
| Active ending runners | `0` |
| Same-bar handoff rejections | `0` |
| Median / P90 entitlement-to-handoff latency | `59.0h / 213.0h` |
| Entitlement uniqueness / runner uniqueness | `PASS / PASS` |
| Sparse limit / result | `6 / FAIL` |

All 11 handoffs occurred on a target-ready bar and each epoch used at most one
entitlement and one runner. The JSON records every entitlement event, expiry,
handoff time, latency, selected signal/fill time, target threshold, estimated
net PnL, and tie-break count.

Waiting for the target improved runner profitability but made entitlement
coverage broader, not sparser: V2D Fresh D20 assigned nine runners, while V2E
event persistence assigned eleven.

## Design result

V2E Design produced realized `185.17761344`, unrealized `-9.57666552`, total
`175.60094792`, drawdown `54.242826%`, median/P90 holding
`206.0h / 1,596.4h`, `97 / 89 / 8` buys/sells/open lots, `15` blocked entries,
`25` entitlements, `17` runners, and eight expired pending entitlements.

The high Design drawdown, eight ending lots, and 17-runner handoff count are
additional regime-instability evidence. They do not alter the frozen
Validation decision.

## Fair-reset annual folds

Annual total PnL:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `56.19904224` | `31.45159316` | `-15.77563722` | `44.20017391` | `38.78866787` |
| V2E | `60.62346950` | `37.03388306` | `-19.26766362` | `41.69128901` | `42.33556187` |

Annual median holding hours:

| Exit | 2020 | 2021 | 2022 | 2023 | 2024 |
| --- | ---: | ---: | ---: | ---: | ---: |
| V1 | `113.0` | `103.5` | `126.0` | `204.0` | `160.0` |
| V2E | `158.5` | `240.0` | `206.0` | `167.5` | `188.0` |

V2E met the annual total gate at `3/5`, but improved annual median holding in
only `1/5`. Its aggregate `181.5h` median therefore concealed unstable annual
capital recycling.

## Frozen gate decision

V2E passed:

- ending unrealized PnL;
- drawdown;
- aggregate median and P90 holding;
- annual total wins; and
- entitlement and runner epoch uniqueness.

V2E failed:

- Validation total `>= V1` by `0.00161398`;
- Validation realized `>= V1` by the same amount;
- Validation total `>= 90% V2A` by `15.72448359`;
- annual median-hold wins, `1/5` versus required `3/5`; and
- sparse runner count, `11` versus maximum `6`.

The result cannot be promoted by rounding away the V1 miss because three other
independent frozen gates also fail materially.

## Terminal interpretation

V2A showed that broad runners capture large trends but hold too long. V2D
showed that immediate breakout assignment improves recycling but selects too
many weak runners. V2E proved that delaying assignment until an ATR-profit
buffer can recover almost exactly V1 aggregate PnL, but it still cannot retain
V2A trend value, remain sparse, or improve median holding across years.

Changing entitlement expiry, requiring a different number of breakouts,
altering the target, selecting only certain target crossings, or adding another
factor now would tune the design after seeing the complete pre-2025 frontier.
Under the preregistered terminal boundary, the correct decision is:

```text
DRA_DYNAMIC_EXIT_RESEARCH_STOP
KEEP_DRA_V1
DO_NOT_OPEN_V2F
```

## OOS, one-slot, and Production boundary

No candidate was frozen, therefore:

- no row after the sealed `2025-01-01` boundary was queried or evaluated;
- the independent `30 USDT` one-slot overlay was not run;
- OOS was not opened;
- no SHADOW or LIVE proposal was created; and
- no runtime, configuration, database, DRA V1, position `263`, owner `509`,
  Grid/OCO, funds, schedules, Telegram, order, commit, or deployment changed.

The OOS command was exercised against the failed manifest and returned
`OOS_SEAL_REJECT` before data access. A second call could not overwrite the
existing guard artifact; its SHA-256 remained
`c23c653e8477b8199a8835472f2d0ef229e6f6d450d55d19ee0d62675cf65f05`.

## Reproducible artifacts

- specification:
  `docs/btc-dra-breakout-entitlement-target-handoff-runner-v2e-research.md`;
- runner:
  `research/btc_dra_breakout_entitlement_target_handoff_runner_v2e.py`;
- machine-readable run 1:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-v2e-preselection-2026-08-02-run1.json`;
- byte-identical run 2:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-v2e-preselection-2026-08-02-run2.json`.

This result is historical research evidence only. `KEEP_DRA_V1` means no
dynamic-exit candidate passed the frozen research gates; it is not a new LIVE
action and does not change the existing DRA runtime or position.
