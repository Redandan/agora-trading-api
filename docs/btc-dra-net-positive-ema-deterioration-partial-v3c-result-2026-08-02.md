# BTC DRA Net-Positive EMA-Deterioration Partial V3C Result

Date: 2026-08-02

Research identity:
`BTC_DRA_NET_POSITIVE_EMA_DETERIORATION_PARTIAL_V3C_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS_THROUGH_CONJUNCTIVE_PARTIAL_V3B
DESIGN_VALIDATION_COMPLETE
PRE_2025_FAIR_RESET_FOLDS_COMPLETE
NO_CANDIDATE
PARTIAL_DE_RISK_BRANCH_STOP
KEEP_DRA_V1
OOS_NOT_OPENED
ONE_SLOT_OVERLAY_NOT_RUN
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Outcome first

Removing the `1R` maturity requirement nearly matched V1 holding and still
beat V1 realized and total, but it surrendered too much V2A trend value and
lost annual profitability stability.

Validation produced realized `90.45986447`, unrealized `-0.64164024`, total
`89.81822423`, drawdown `5.905520%`, and cost-weighted median/P90
`184h / 836h`. This beats V1 realized by `1.04868140`, total by `3.61524237`,
and substantially improves drawdown and P90. It misses the median limit by
only `1.5h`.

Those near-passes cannot hide the material failures: total retains only
`79.31%` of V2A and misses the frozen `90%` threshold by `12.10762724`;
annual total wins are only `2/5`. The candidate is `NO_CANDIDATE` and this
partial-de-risk branch stops without ratio or signal tuning.

## Frozen candidate

Entry routing and `24/6` allocation were identical to V3B. A partial-eligible
lot no longer waited for `1R`; its `24 USDT` tranche queued only when estimated
net PnL was strictly positive and the complete hourly close was below causal
hourly EMA5. The exact next-open tranche also had to remain strictly positive.
The `6 USDT` residual then used V2A `1.50 ATR`.

This implemented the requested semantics directly: profit was necessary but
not sufficient, and the additional sell condition was an observable causal
trend deterioration, not a fixed profit percentage.

## Validation audit

- buys / exit slices / open / blocked: `51 / 83 / 1 / 0`;
- full V2A / partial-eligible: `18 / 33`;
- partial fills / direct V2A / remainder fills: `33 / 18 / 32`;
- average utilization: `15.172914%`;
- turnover: `1,614.45986447`;
- median first realization: `77h`;
- median final completion: `401h`;
- cost, quantity, trigger, single-partial, and positive-fill violations: zero.

The ending open inventory was only the `6 USDT` residual share, hence ending
unrealized improved from V1's `-3.20820121` to `-0.64164024` without any final
liquidation.

## Annual folds

| Year | Candidate total | V1 total | Candidate median | V1 median | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 2020 | `48.96057999` | `56.19904224` | `76h` | `113h` | total loss, hold win |
| 2021 | `16.92540456` | `31.45159316` | `25h` | `103.5h` | total loss, hold win |
| 2022 | `-0.81030310` | `-15.77563722` | `31h` | `126h` | total win, hold win |
| 2023 | `40.34551667` | `44.20017391` | `120h` | `204h` | total loss, hold win |
| 2024 | `47.97693520` | `38.78866787` | `187h` | `160h` | total win, hold loss |

The exit improves holding in `4/5` years but profitability in only `2/5`—the
mirror image of V3B's `4/5` profitability and `0/5` holding wins. This is the
clearest evidence that the remaining problem is not one bad threshold but a
stable economic trade-off.

## Design

Design realized/unrealized/total were
`135.16756189 / -38.12504610 / 97.04251579`, drawdown `16.450700%`, and
median/P90 `86h / 1,094h`. It had `101` buys, `169` exit slices, six ending
lots, and zero blocked entries. Faster capital release improved recycling but
did not remove profit-only stale inventory or regime risk.

## Frozen gate decision

Passed:

- Validation realized and total versus V1;
- ending unrealized;
- drawdown;
- P90 holding;
- annual median wins (`4/5`); and
- all causal/accounting audits.

Failed:

- `90%` V2A retention by `12.10762724`;
- median holding by `1.5h`; and
- annual total wins (`2/5` versus `3/5`).

No gate was relaxed.

## Reproducibility, OOS, and Production boundary

- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- specification SHA-256:
  `2fad0cfcf7851064e084a2bc969497c5cded562f50c9c09a875b1e40e972cfe9`;
- runner SHA-256:
  `243f5be3b504148ac91c06b159a6b93e217dceb9bb332db4aebe24125d4e818c`;
- two byte-identical `3,697,659`-byte JSONs SHA-256:
  `82bbced635d0af9f539cac130f94761f9d80a2123db8d3fec9e000570ac1c1d9`;
- OOS seal SHA-256:
  `9eb97d0737b8e28c325f869bf54f4071b2c807ab4ef74371a821f562469641b6`.

The OOS command rejected before 2025+ access; a second attempt refused to
overwrite the guard and preserved its hash. No one-slot overlay ran. No
runtime, configuration, database, DRA V1, position `263`, owner `509`,
Grid/OCO, funds, schedules, Telegram, order, commit, or deployment changed.

Reproducible artifacts are the matching V3C specification and runner plus
`btc-dra-net-positive-ema-partial-v3c-preselection-2026-08-02-run1.json`,
`run2`, and `btc-dra-net-positive-ema-partial-v3c-oos-seal-guard-2026-08-02.json`
in the task visualization directory.
