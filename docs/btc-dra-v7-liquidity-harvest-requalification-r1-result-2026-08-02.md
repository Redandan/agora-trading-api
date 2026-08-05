# BTC DRA V7 Liquidity-Harvest Requalification R1 Result

Date: 2026-08-02

Research identity:
`BTC_DRA_V7_LIQUIDITY_HARVEST_REQUALIFICATION_R1_RESEARCH`

Final decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_V7
PRE_2025_POST_HOC_REQUALIFICATION_PASS
CANDIDATE_FROZEN
OOS_OPENED_ONCE
OUT_OF_SAMPLE_FAIL
LIQUIDITY_HARVEST_REQUALIFICATION_BRANCH_STOP
KEEP_DRA_V1
ONE_SLOT_OVERLAY_COMPLETE_AND_FAIL
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

Exact V7 passed the new pre-2025 liquidity-harvest gates, but failed the only
independent `2025+` OOS opening by a wide economic margin. V7 recycled capital
faster and reduced drawdown, but converted the trend into many small partial
profits and produced substantially less realized and total PnL than V1.

The OOS decision is final for this frozen V7 manifest. The branch stops without
changing thresholds or using OOS to tune another V7 variant.

## Pre-2025 requalification

The pre-2025 data were disclosed as reused, post-hoc selection evidence rather
than an independent holdout. Exact V7 nevertheless passed every frozen manifest
gate:

- Validation realized/unrealized/total:
  `96.02789691 / -0.64164024 / 95.38625667`;
- V1 Validation total: `86.20298186`;
- drawdown: `6.832349%` versus V1 `7.121498%`;
- cost-weighted median/P90: `192h / 836h`;
- turnover: `1,620.02789691` versus V1 `1,589.41118307`;
- harvest efficiency: `0.13837909` versus V1 `0.09423508`;
- annual total / median-hold / harvest-efficiency wins:
  `2/5 / 3/5 / 3/5`;
- all V7 promotion, route, quantity, cost, partial, next-open, net-positive,
  and no-quota audits passed.

Two preselection runs produced byte-identical manifests and froze:

```text
candidate = V3C_NONSTRONG_PRE_PARTIAL_1R_PROMOTE_FULL_V2A_ELSE_NET_POSITIVE_EMA5_PARTIAL_24_6
freeze_sha256 = ae3a415403a3189b3252113c16d85872b16af4c2a8681d4c6d7a07942b5c838e
```

## OOS result

Window: `2025-01-01T00:00:00` through the complete bar ending exactly
`2026-08-02T00:00:00` UTC. Amounts are USDT; reference capacity is `250 USDT`.

| OOS | V1 | V7 | V7 minus V1 |
| --- | ---: | ---: | ---: |
| Realized | `44.83826545` | `12.62061106` | `-32.21765439` |
| Unrealized | `-9.29563441` | `-1.72637173` | `+7.56926268` |
| Total | `35.54263104` | `10.89423933` | `-24.64839171` |
| Max drawdown | `8.870663%` | `6.144889%` | `-2.725774pp` |
| Median holding | `304h` | `146h` | `-158h` |
| P90 holding | `1,631h` | `1,264h` | `-367h` |
| Turnover | `854.83826545` | `924.62061106` | `+69.78234561` |
| Harvest efficiency | `0.07061366` | `0.03210812` | `-54.530%` |
| Buys / exit slices / open | `31 / 27 / 4` | `31 / 49 / 3` | — |
| Blocked entries | `0` | `0` | `0` |

V7 passed ending unrealized, drawdown, median, turnover, and every audit gate.
It failed:

- total at least V1;
- realized at least V1;
- absolute P90 at most `1,000h`; and
- harvest efficiency greater than V1.

## Why it failed

OOS routed `8` entries directly to full V2A and `23` to the partial-eligible
path. Only `2` partial-eligible lots reached pre-partial `1R` and promoted to
full V2A. The other path produced `21` partial fills and `18` completed
remainder exits.

This made the strategy visibly faster, but the freed capacity did not generate
enough additional high-value DRA entries to offset the trend profit sold early:

- V7 made `49` exit slices versus V1's `27`;
- turnover increased, while realized PnL fell by `71.853%`;
- realized profit per occupied capital-hour fell by `54.530%`;
- better ending unrealized and lower drawdown could not offset the
  `24.64839171` total-PnL deficit.

The failure is economic, not causal or operational. Data, prefix hash,
next-open accounting, strictly positive fills, independent-lot state,
promotion uniqueness, and cost/quantity reconciliation all passed.

## Independent 30-USDT one-slot overlay

The OOS run also evaluated V1 and V7 with independent `30 USDT` capacity.

| OOS one-slot | V1 | V7 |
| --- | ---: | ---: |
| Realized | `7.88578386` | `2.12093538` |
| Unrealized | `-14.62886133` | `-14.76158723` |
| Total | `-6.74307747` | `-12.64065185` |
| Max drawdown | `42.792010%` | `50.731741%` |
| Median / P90 | `793h / 2,258.4h` | `393h / 2,589h` |
| Harvest efficiency | `0.02022151` | `0.00626976` |
| Buys / exit slices / open | `6 / 5 / 1` | `7 / 9 / 1` |
| Blocked entries | `98` | `102` |

The one-slot overlay confirms that faster first realization does not solve the
single-lot opportunity cost. V7 produced lower realized and total PnL, worse
drawdown, worse P90, lower efficiency, and more blocked entries than V1.

## Reproducibility

- preselection rows/hash: `52,608` /
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- two byte-identical `5,707,788`-byte preselection JSON SHA-256:
  `a9c003fb5b0c2a171de0faeefdce04c84600762ad9b497ca715af5d6f0d4e52f`;
- R1 specification SHA-256:
  `6c306005dc9a062fb98fae08050d0bb9852272f1c0033c3781227403a0dac533`;
- R1 runner SHA-256:
  `cf0713e5358d95a177711f609daabd0650813dd3b41ae03756159218f5b8f0ea`;
- full `2019-2026-08-02` rows/hash: `66,480` /
  `97a62d520bde4242336643b161a74e36d224de04f0290dfd5ad9acb4972ce9e0`;
- selection prefix inside the OOS export remained exactly `52,608` rows with
  the original preselection hash;
- immutable `291,612`-byte OOS JSON SHA-256:
  `202100ec2325a99de0c04c578c68411c6ef9aa353bac88000b516d9189d23fb3`.

## Boundary and next decision

The sealed OOS was opened once and is now consumed for V7 and closely derived
promotion variants. It must not be reused to tune an inverted filter, partial
ratio, `1R`, EMA, or ATR threshold.

No runtime, configuration, database, DRA V1, position `263`, owner `509`,
Grid/OCO, funds, schedules, Telegram, order, commit, or deployment changed.
V7 is not authorized for SHADOW or LIVE. DRA V1 remains the reference.

## Reproducible artifacts

- specification:
  `docs/btc-dra-v7-liquidity-harvest-requalification-r1-research.md`;
- runner:
  `research/btc_dra_v7_liquidity_harvest_requalification_r1.py`;
- preselection manifests:
  `btc-dra-v7-liquidity-harvest-requalification-r1-preselection-2026-08-02-run1.json`
  and `run2.json`;
- single OOS and one-slot result:
  `btc-dra-v7-liquidity-harvest-requalification-r1-oos-2026-08-02.json`.
