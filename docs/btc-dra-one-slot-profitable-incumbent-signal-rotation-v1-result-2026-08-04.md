# BTC DRA One-Slot Profitable-Incumbent Signal Rotation V1 Result

Status: `NO_CANDIDATE_KEEP_ONE_SLOT_DRA_V1`

Authorization: `RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`

## Decision

Directly rotating an already-profitable one-slot incumbent into a fresh DRA
signal increased historical Validation total PnL and removed the terminal
losing lot, but it also increased maximum drawdown, lengthened median holding,
slightly reduced realized PnL, and improved annual drawdown in only two of five
folds. The candidate failed the frozen gates and is closed without changing the
rule or thresholds.

Keep the one-slot DRA V1 parent. Do not tune the existing `+1%` floor, add an
age filter, select rotation years, or open post-2024 data for this branch.

## Frozen evidence

- input: `52,608` complete OKX `BTCUSDT` hourly rows;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- baseline parity: `PASS_ONE_SLOT_DRA_V1_DESIGN_VALIDATION`;
- candidate variants: `1`;
- qualified candidates: `0`;
- OOS opened: `false`;
- contamination: `POST_HOC_HISTORICAL_NO_CLEAN_OOS`;
- formal diagnostic SHA-256:
  `44245c9b393f3c3fd08c58c455f80f52a8024832a52639185b33b678e13ee609`;
- formal learning SHA-256:
  `12fd48d429189054bde22297adc62bfc47d1b71db5e603993f9c3958f1f9049f`.

Two independent development executions and the formal pipeline execution were
byte-identical: `50,546` bytes with the same diagnostic SHA-256 above.

## Design result

| Metric | One-slot parent | Rotation | Delta |
| --- | ---: | ---: | ---: |
| Realized PnL | 51.82581283 | 57.58222511 | +5.75641228 |
| Unrealized PnL | -22.31327703 | -22.31327703 | 0.00000000 |
| Total PnL | 29.51253580 | 35.26894808 | +5.75641228 |
| Maximum drawdown | 40.321240% | 39.010882% | -1.310358pp |
| Median / P90 hold | 112.0 / 1704.8h | 136.0 / 996.6h | +24.0 / -708.2h |
| Buy / sell / open | 29 / 28 / 1 | 38 / 37 / 1 | +9 / +9 / 0 |
| Capacity blocks | 273 | 233 | -40 |

The candidate completed eight successful rotations with no cancellation or
terminal pending rotation. Design total and drawdown gates passed, but median
holding already showed that more turnover did not uniformly shorten completed
lot duration.

## Validation result

| Metric | One-slot parent | Rotation | Delta |
| --- | ---: | ---: | ---: |
| Realized PnL | 43.54302055 | 43.44143025 | -0.10159030 |
| Unrealized PnL | -3.60947416 | 0.00000000 | +3.60947416 |
| Total PnL | 39.93354639 | 43.44143025 | +3.50788386 |
| Maximum drawdown | 17.699055% | 18.626622% | +0.927567pp |
| Median / P90 hold | 146.0 / 775.6h | 192.0 / 722.6h | +46.0 / -53.0h |
| Buy / sell / open | 26 / 25 / 1 | 33 / 33 / 0 | +7 / +8 / -1 |
| Capacity blocks | 117 | 85 | -32 |
| Average utilization | 88.377793% | 86.206110% | -2.171683pp |

All 14 rotation attempts sold and replaced successfully; none were cancelled.
The mechanism therefore operated often enough to evaluate. Its total-PnL gain
came from clearing the parent's terminal loss, not from higher realized PnL.
The higher drawdown and longer median holding contradict the stated
risk-adjusted thesis.

## Annual stability

| Year | Parent total | Rotation total | Total win | Parent DD | Rotation DD | DD non-worse |
| --- | ---: | ---: | --- | ---: | ---: | --- |
| 2020 | 24.43472128 | 24.49945121 | yes | 50.809912% | 49.568711% | yes |
| 2021 | 7.95292496 | 12.72951140 | yes | 36.867348% | 34.379080% | yes |
| 2022 | -13.12231924 | -14.99082334 | no | 55.155223% | 58.832216% | no |
| 2023 | 21.58908722 | 23.59597311 | yes | 17.699055% | 18.626622% | no |
| 2024 | 17.14588570 | 18.77817774 | yes | 21.776773% | 23.509943% | no |

Total PnL improved in `4/5` folds, but drawdown was non-worse in only `2/5`.
The candidate systematically exchanged more marked-to-market path risk for
additional participation; aggregate total PnL alone is insufficient.

## Gate result

Passed:

- Design total and drawdown;
- Validation total, ending unrealized, P90, blocked confirmations, and minimum
  successful rotations;
- annual total wins (`4/5`);
- terminal-state, causality, one-slot, sale-before-buy, fee, quantity, and
  accounting audits.

Failed:

- Validation realized PnL strictly greater than parent;
- Validation maximum drawdown no higher than parent;
- Validation median holding no higher than parent;
- annual drawdown non-worse in at least `3/5` folds (`2/5`).

The failed gates are independent and material. They must not be relaxed after
observing the result.

## Learning

Capacity blocks are not themselves a performance defect. Rotating a profitable
incumbent recovered more entry opportunities and improved historical total PnL,
but the new lots changed the equity path and increased drawdown in three recent
or difficult folds. A higher trade count is not the same as better capital
efficiency.

This branch does not select a follow-up hypothesis. Any future one-slot work
needs a separately motivated causal mechanism, not a filter added to rescue
these rotations. Because the economic gate failed, no Java Phase C translation
is justified for this candidate.

## Reproducibility and boundary

- specification SHA-256:
  `9d64a8d18df34a9d3551250fa38eff9b8c8749a4f9accc8b95f4a1e725cf6941`;
- runner SHA-256:
  `31592e92a1035a10ff2f0f637ef567a81251ee910ac4d360b0ef04c8fe65d499`;
- Python reference engine SHA-256:
  `7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37`.

No Production runtime, strategy catalog, SHADOW/PAPER/LIVE state, database,
order, fund, Grid/OCO, scheduler, Telegram, deployment, or external write was
changed. The only mutable outputs are sealed local research artifacts under
`.research-state`.
