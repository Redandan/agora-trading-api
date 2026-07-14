# BTC Price-Only Profit Research Report - 2026-07-13

## Decision

`BTC_DONCHIAN_20D_10D_V1` is the only candidate that passed the frozen local
historical research gate. The result authorizes only design review for a future
SHADOW lane. It does not authorize deployment, an environment change, a live
order, OCO mutation, Telegram notification, production backfill, or database
write.

Strategy 508 remains a separate `STRATEGY_508_4H_24H_V1` SHADOW experiment.
Price-only candles cannot reconstruct its historical provider-specific OI and
predicted-funding inputs, so this report does not compare or replace strategy
508 signal parity.

## Dataset Evidence

The final dataset uses confirmed OKX spot `BTC-USDT` 1H UTC candles from
2019-01-01 00:00 through 2026-07-13 08:00 UTC.

| Evidence | Value |
| --- | --- |
| Rows / raw pages | `66,009 / 221` |
| Missing / duplicate / off-grid rows | `0 / 0 / 0` |
| Canonical coverage | `100%` |
| Canonical CSV SHA-256 | `74bccfdc621884447e224536cedb7471f8c28bbb612f38e81d8b23e02ff8cfd8` |
| Policy SHA-256 | `c30a4e377f6e8b2f4ea7681d2bb80bc9b907879f7142313c4f0ba949c2ddc11c` |
| Dataset builder SHA-256 | `f4e98a1298e81342a3800e16febf4e1f8b686682899816c45baaceffdd039b61` |
| Analyzer SHA-256 | `ff6684ba04ce61da2d0d719d5a9fdaa50f6a683096ded676d36a534f8e4efaf0` |
| Deterministic result SHA-256 | `56869fd7f9b2cb282a13992035d045c0c84fddf8550c13222261c66faf28226c` |
| Report file SHA-256 | `ff5efeb2f5910af1122def56a7b803a0aacf73447bf6cb028958b05be00b94bc` |

The analyzer verifies the instrument/bar/source contract, every raw page hash,
the exact UTC lattice, OHLC invariants, and a row-by-row reconstruction of the
canonical CSV from raw API pages. The bundle is locally tamper-evident, not
externally signed.

## Frozen Method

- Normal cost: 0.10% fee and 0.05% adverse slippage per side, next 1H open.
- Stress cost: 0.20% fee and 0.10% adverse slippage per side, plus one 1H delay.
- No shorting, leverage, deleted signals, window selection, or parameter sweep.
- At least 30 completed round trips overall and three in every fold.
- Five isolated fixed-parameter forward folds; each starts from cash, uses only
  earlier warm-up data, carries no position from another fold, and liquidates at
  its own end.
- Normal/stress CAGR, PF, fold count, worst fold, and drawdown gates all apply.
- HODL is context, not a promotion gate; the objective is positive net return
  under a drawdown cap rather than maximum BTC beta.

## Results

| Candidate | Normal | Stress | Samples | Fold result | Max DD | Decision |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| `BTC_WEEKLY_TSMOM_V1` | `+397.80%` | `+346.06%` | 10 round trips | `4/5`, but only 1-5 trades/fold | `64.60% / 67.73%` | Reject |
| `BTC_DONCHIAN_20D_10D_V1` | `+171.89%` | `+163.25%` | 41 round trips | `4/5`, 6-10 trades/fold | `15.10% / 15.10%` | SHADOW review candidate |
| `BTC_VOL_MANAGED_LONG_V1` | `+807.29%` | `+758.76%` | 1 continuous round trip | `3/5` | `67.33% / 67.65%` | Reject |

Donchian details:

| Metric | Normal | Stress |
| --- | ---: | ---: |
| CAGR | `14.32%` | `13.83%` |
| Profit factor | `4.443` | `4.170` |
| Wins / losses | `17 / 24` | `17 / 24` |
| Median trade | `-0.613%` | `-0.652%` |
| Fold returns | `+23.27, +90.15, +5.53, +12.78, -4.45%` | `+22.21, +88.36, +4.97, +12.53, -4.83%` |

The negative median is expected for a trend strategy with many small losses and
fewer large wins, but it raises sequencing risk. The latest isolated fold is
negative in both scenarios. Same-window HODL returned about `+1686.53%` with a
`77.19%` drawdown, so Donchian reduced drawdown materially but did not maximize
absolute return.

All six candidate/scenario ledgers passed independent fee, slippage, quantity,
cash, position, signal-time, and round-trip reconciliation. A second full run
produced the same deterministic result and Donchian order-ledger hashes.

## Reproduction

```powershell
.\scripts\test_btc_price_only_research.ps1
.\scripts\analyze_btc_price_only_candidates.ps1 `
  -DatasetDirectory .\target\research\okx-btc-usdt-1h-final-ledger-v2\okx-btc-usdt-1h-20260713T090000Z `
  -OutputPath <new-report-path>
.\scripts\verify_btc_price_only_research_report.ps1 `
  -ReportPath .\target\research\okx-btc-usdt-1h-final-ledger-v2\okx-btc-usdt-1h-20260713T090000Z\price-only-research-report-isolated-v2.json
```

The immutable dataset and full per-signal, per-order, and per-trade ledgers live
under `target/research` and are intentionally not committed.

## Next Gate

The next change, if separately approved, is a SHADOW-only runtime implementation
of the exact Donchian policy. It must compare closed-bar signal parity and
virtual fills for at least 30 days and five independent entries. A historical
pass alone cannot enable live execution or increase order size.
