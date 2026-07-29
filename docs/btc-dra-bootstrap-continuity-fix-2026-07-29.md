# BTC DRA Bootstrap Continuity Fix

Date: 2026-07-29

Status:

```text
ROOT_CAUSE_CONFIRMED
FRESH_EDGE_CANDIDATE_REJECTED
BOOTSTRAP_REPLAY_CANDIDATE_PASS
LOCAL_IMPLEMENTATION_PRESENT
LOCAL_VALIDATION_PASS
DEPLOYMENT_AUTHORIZED
PRODUCTION_ACCEPTANCE_DEFINED
```

## Defect

DRA V1 warms 90 days of indicators without replaying its entry lifecycle.
Before this fix, the first genuine current bar therefore started with no
historical arm, cooldown, or last signal.

On the first Production activation:

- the 2026-07-25 23:00 UTC daily close already satisfied the DRA entry
  confirmation;
- bootstrap at 2026-07-26 15:00 UTC discarded that historical signal state and
  created a new arm;
- the still-confirmed 2026-07-26 23:00 UTC daily close was then treated as a
  new signal;
- the resulting market buy filled at 65,416.7, about 1.62% above the prior
  day's valid close.

This is a bootstrap continuity defect. It is not a generic risk gate, market
data gap, or evidence that the continuous DRA entry rule should be tightened.

## Rejected candidate

The first candidate required the daily confirmation to transition from false
to true before every entry. It prevented the Production startup buy but
materially damaged the frozen economics:

| Window and capacity | V1 total | Fresh-edge total | V1 drawdown | Fresh-edge drawdown |
| --- | ---: | ---: | ---: | ---: |
| Three-year, 250 USDT | +100.68642529 | +78.55019022 | 10.183632% | 14.330781% |
| Three-year, one 30 USDT lot | +33.78714441 | +11.43086957 | 18.312428% | 28.412601% |
| OOS, 250 USDT | +38.37338687 | +16.12844514 | 8.870663% | 8.870663% |
| OOS, one 30 USDT lot | +6.52944156 | -8.09961607 | 29.420245% | 43.705876% |

The fresh-edge candidate is rejected. Avoiding one startup chase does not
justify replacing the continuous entry rule with a materially weaker one.

## Selected correction

During the 90-day bootstrap, replay only:

- arm creation and expiry;
- observed historical entry confirmation;
- the seven-day cooldown watermark;
- arm and expiry counters.

Bootstrap still must not create:

- a pending buy;
- a virtual or live lot;
- a live-signal reservation;
- an exchange order;
- OCO, Grid, fund, or Telegram activity.

The replayed entry state is seeded immediately before evaluating the first
genuine current closed bar. Normal post-bootstrap DRA entry and exit behavior
is unchanged.

## Causal comparison

Frozen research input:

- provider: OKX;
- symbol and interval: BTCUSDT 1h;
- frozen rows: 66,312;
- window: 2019-01-01 00:00 through 2026-07-25 23:00 UTC;
- fee: 0.10% per side;
- adverse slippage: 0.05% per side;
- exit: +5% estimated net, with +1% next-open floor;
- no stop-loss, time exit, or final forced liquidation.

The replay first reproduced every published V1 baseline exactly. The selected
bootstrap correction then produced the same continuous results:

| Window and capacity | Realized | Unrealized | Total | Drawdown | Utilization |
| --- | ---: | ---: | ---: | ---: | ---: |
| Three-year, 250 USDT | +107.15130387 | -6.46487858 | +100.68642529 | 10.183632% | 19.203923% |
| Three-year, one 30 USDT lot | +39.92025564 | -6.13311123 | +33.78714441 | 18.312428% | 67.613291% |
| OOS, 250 USDT | +44.83826545 | -6.46487858 | +38.37338687 | 8.870663% | 17.945709% |
| OOS, one 30 USDT lot | +12.66255279 | -6.13311123 | +6.52944156 | 29.420245% | 70.395505% |

The 2026-07-26 15:00 through 2026-07-27 01:00 UTC event regression produced:

| Runtime behavior | Buy signals | Buy fills |
| --- | ---: | ---: |
| Old bootstrap | 1 | 1 |
| Bootstrap entry-state replay | 0 | 0 |

The selected correction therefore removes the startup-only duplicate
opportunity without changing continuous historical or OOS economics.

## Local implementation boundary

The local implementation adds:

- a pure bootstrap entry-state replayer;
- state seeding before the genuine current bootstrap bar;
- four focused deterministic contract tests.

The existing open Production lot, stored evidence, exchange order, DRA
notional, +5% exit, Grid, OCO, owner 509, and Production configuration are not
changed. On 2026-07-29, the owner authorized commit, deployment, and an updated
read-only acceptance target for this narrow correction.

Before deployment:

1. review the focused code diff and package result;
2. capture the existing DRA lot, evidence, owner 509, Grid, and OCO baseline;
3. verify after deployment that existing valid evidence restores rather than
   bootstraps;
4. require the next natural complete bar to remain contiguous and hash-valid;
5. perform Production read-only continuity acceptance without a test order,
   evidence mutation, backfill, or forced fresh bootstrap.
