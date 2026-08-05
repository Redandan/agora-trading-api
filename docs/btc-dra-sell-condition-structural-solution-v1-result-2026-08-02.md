# BTC DRA Sell-Condition Structural Solution V1 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_SELL_CONDITION_STRUCTURAL_SOLUTION_V1_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_V1_V2A_V2B_V2C_V2D_V2E
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
CONSTRAINT_SET_INFEASIBLE
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

The one preregistered architecture did not beat V1 and did not retain `90%` of
V2A. More importantly, it proved that runner concurrency was not V2E's
sparsity problem: Validation already never had more than one runner open at a
time. Adding a hard global one-runner cap therefore left V2E's Validation
ledger exactly unchanged at 11 sequential runner assignments.

The full-lot binary choice remains the binding constraint. Fast exits recycle
capital but give up the entire future tail of that `30 USDT` lot. Retaining the
whole lot captures some tail profit but produces too many long-held lots and
still lacks enough payoff capacity to meet the V2A-retention gate.

The correct current action is `KEEP_DRA_V1`. This is not a SHADOW or LIVE
candidate.

## Frozen candidate tested

`GLOBAL_ONE_ACTIVE_FRESH_DONCHIAN20_ENTITLEMENT_FIRST_TARGET_RUNNER`

The candidate inherited V2E unchanged except for one rule: at most one full
runner lot could be open globally across all trend epochs. A fresh causal
Donchian-20 event could create an entitlement only while no runner was open;
the first later lot reaching the `1.0 * entry ATR14 * quantity` net-profit
target received the V2A `1.50 ATR` ratchet. Other lots sold on the fast core
path. There was no parameter sweep or alternate factor.

## Data and reproducibility acceptance

- source: server-local, read-only `md_kline`, OKX `BTCUSDT`, complete `1h`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- rows: `52,608`;
- all data-quality defect counts: zero;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `33ab31ab4b60beef918e4e75a3cd4445d3af9d874e0a2bf969b9c6532cd371be`;
- V2E specification SHA-256:
  `cc75af188264a49c4e915d72a911aac23f512ed749392324f10795477f8713f2`;
- V2C/V2D/V2E dependency SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`,
  `5443f8efdfdfc0522e867513efb7090d547eada7865f2fde2097309cdf224952`,
  and `0d0f8542d4d0863e7148d4ce69abb7ed41cb1abb053ff382effd9e3ab8d65d9b`;
- structural runner SHA-256:
  `41e1150510a1a68bff2d12fe4b3edf7d594623e701ab46f13485039e4bb74fa3`.

Two complete preselection runs produced byte-identical `799,737`-byte JSON
outputs with SHA-256:

`fe4936e772a1a657c652f03fcd3f5c83b20fce2818a29792426be068dcde0a3e`.

The runner intentionally returns nonzero for the terminal infeasibility
decision after writing the complete result.

## Exact checkpoint gate

The runner reproduced V1, V2A, every V2B profile, every V2C factor, every V2D
candidate and audit, and V2E Design/Validation plus annual wins before
accepting the structural candidate result.

Key Validation references, amounts in USDT:

| Exit | Realized | Unrealized | Total | Max DD | Median | P90 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| V1 | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` |
| V2A `1.50 ATR` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` |
| V2E | `89.40956909` | `-3.20820121` | `86.20136788` | `6.624292%` | `181.5h` | `833.4h` |
| **Global-one candidate** | **`89.40956909`** | **`-3.20820121`** | **`86.20136788`** | **`6.624292%`** | **`181.5h`** | **`833.4h`** |

The structural candidate had `51` buys, `50` sells, one open lot, zero blocked
entries, zero deferred exits, `17.041040%` average utilization, `60%` peak
utilization, and `1,589.40956909` turnover.

## Why the sell condition still fails

Validation split the `89.40956909` realized PnL into:

| Path | Exits | Realized PnL |
| --- | ---: | ---: |
| Fast `1.0 entry ATR` core | `39` | `51.73947360` |
| `1.50 ATR` runners | `11` | `37.67009549` |

For every runner, the research also calculated the causal next-open PnL it
would have received on the ordinary core path. Those 11 counterfactual core
sales sum to `13.32682158`. Keeping them as runners therefore added
`24.34327391` relative to selling every lot on the fast core path.

That uplift was highly concentrated:

- six runners had positive incremental value;
- five runners had zero-or-negative incremental value and together reduced
  the uplift by `2.34064837`;
- the best six runners selected with impossible after-the-fact knowledge would
  contribute `26.68392228` incremental PnL.

The all-core Validation total is `61.85809397`. Even the non-causal oracle that
keeps exactly the best six historical runners reaches only:

```text
61.85809397 + 26.68392228 = 88.54201625
```

That oracle would exceed V1 by `2.33903439`, but it would still miss the frozen
`90% * V2A` threshold `101.92585147` by `13.38383522`. This is diagnostic only;
there is no observable causal rule here for identifying those six winners in
advance.

So the defect is not merely a weak breakout formula:

1. a full fast sale removes all tail exposure from that lot;
2. a full runner preserves tail exposure but ties up the entire `30 USDT` and
   lengthens its holding time;
3. the payoff from the few successful full runners is not large enough to
   offset fast-sale opportunity cost under the frozen V2A-retention gate; and
4. profit-only/no-final-liquidation leaves stale losing lots outside the sell
   condition's reach.

## Runner concurrency and sparsity audit

| Audit | Validation result |
| --- | ---: |
| Fresh breakout events | `38` |
| Fresh events rejected while a runner was active | `15` |
| Runner assignments / exits | `11 / 11` |
| Core exits | `39` |
| Maximum simultaneous open runners | `1` |
| Ending open runners | `0` |
| Global one-active invariant | `PASS` |
| Epoch entitlement / runner uniqueness | `PASS / PASS` |
| Sparse limit / assignments | `6 / 11` |
| Sparse-count gate | `FAIL` |

The 15 active-runner rejections did not remove any eventual V2E handoff in the
aggregate Validation ledger. Once one runner exited, a later fresh event could
create another entitlement. Concurrency was one, but sequential turnover still
created 11 runners. A simultaneous-position cap is therefore not a causal
sparsity selector.

## Design and annual stability

Design produced realized `186.21921267`, unrealized `-14.17647454`, total
`172.04273813`, drawdown `54.868711%`, median/P90 holding
`155.5h / 1,684.6h`, `96 / 88 / 8` buys/sells/open lots, `20` blocked entries,
and `13` runners. The global cap changed Design relative to V2E, but did not
resolve its high drawdown, ending inventory, or runner sparsity.

Fair-reset annual totals and medians:

| Year | Candidate total | V1 total | Candidate median | V1 median | Runners |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2020 | `60.62346950` | `56.19904224` | `158.5h` | `113.0h` | `7` |
| 2021 | `37.03388306` | `31.45159316` | `240.0h` | `103.5h` | `5` |
| 2022 | `-19.76622386` | `-15.77563722` | `155.0h` | `126.0h` | `3` |
| 2023 | `41.69128901` | `44.20017391` | `167.5h` | `204.0h` | `5` |
| 2024 | `42.33556187` | `38.78866787` | `188.0h` | `160.0h` | `6` |

The candidate met annual total wins at `3/5`, but improved annual median
holding in only `1/5`. Its acceptable aggregate median concealed unstable
capital recycling across years.

## Frozen gate decision

Passed:

- ending unrealized PnL;
- maximum drawdown;
- aggregate median and P90 holding;
- annual total wins;
- the global one-active-runner invariant; and
- epoch entitlement and runner uniqueness.

Failed:

- Validation total `>= V1` by `0.00161398`;
- Validation realized `>= V1` by `0.00161398`;
- Validation total `>= 90% V2A` by `15.72448359`;
- annual median-hold wins, `1/5` versus required `3/5`; and
- sparse runner assignments, `11` versus maximum `6`.

The near-equality to V1 cannot be rounded into a pass because the V2A-retention,
annual holding, and sparsity failures are independent and material.

## Minimum contract change worth a separate study

Within the frozen full-lot, price-only, profit-only contract, this line of exit
research is closed. The smallest next hypothesis that directly addresses the
binary trade-off is a partial core/runner exit within each existing `30 USDT`
lot:

- realize most of a profitable lot on the fast causal core signal;
- retain only a smaller residual quantity on the V2A runner path; and
- measure both capital-weighted and final-lot holding time, all added sell fees,
  quantity rounding, and exchange minimum-order feasibility.

This would preserve DRA entry and avoid a forced loss or time exit, but it
changes the full-lot/independent-exit contract and creates an additional order
path. It therefore needs a new explicit research authorization and a new
preregistered specification. No split fraction or trigger was selected or
tested here.

Changing DRA entry quality or adding a loss/time release could also address
stale inventory, but those are broader contract changes and are not the next
minimal step.

## OOS and Production boundary

No candidate was frozen. The OOS command returned `OOS_SEAL_REJECT` with
`preselection froze no candidate` before any post-2024 data access. A second
attempt saw the existing guard output and refused to overwrite it; its SHA-256
remained:

`31d60effbb3aec369c162ab9ed571058d7c2035e80c3a2fb9f220b509f20914b`.

Therefore 2025+ OOS was not opened and the independent `30 USDT` one-slot
overlay was not run. No runtime, configuration, database, DRA V1, position
`263`, owner `509`, Grid/OCO, funds, schedules, Telegram, order, commit, or
deployment changed.

## Reproducible artifacts

- specification:
  `docs/btc-dra-sell-condition-structural-solution-v1-research.md`;
- research runner:
  `research/btc_dra_sell_condition_structural_solution_v1.py`;
- result JSON run 1:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-structural-solution-v1-preselection-2026-08-02-run1.json`;
- byte-identical run 2:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-structural-solution-v1-preselection-2026-08-02-run2.json`;
- OOS seal guard:
  `C:\Users\Redan\.codex\visualizations\2026\07\31\019fb8c9-167b-7622-9000-c2ea73925092\btc-dra-structural-solution-v1-oos-seal-guard-2026-08-02.json`.

This is historical research evidence only. `KEEP_DRA_V1` preserves the current
research and Production boundary; it does not activate or modify a strategy.
