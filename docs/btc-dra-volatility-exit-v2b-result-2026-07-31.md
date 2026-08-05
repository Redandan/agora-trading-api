# BTC DRA Volatility Exit V2B Result

Date: 2026-07-31

Research identity: `BTC_DRA_VOLATILITY_PERCENTILE_EXIT_V2B_RESEARCH`

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS
PRE_2025_FOLDS_COMPLETE
NO_CANDIDATE
OOS_NOT_OPENED
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## Boundary and reproducibility

Candidate selection read only OKX `BTCUSDT` complete `1h` bars ending exactly
at the sealed OOS boundary. It did not read any bar after 2024.

- rows: `52,608`;
- first open: `2019-01-01T00:00:00`;
- last included close: `2025-01-01T00:00:00`;
- gaps, duplicates, off-grid rows, and invalid OHLC: `0`;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

The evaluator exactly reproduced the frozen DRA V1 Validation checkpoint and
the V2A ATR-trail `1.50` checkpoint before V2B results were accepted.

## Validation result

Window: `2023-01-01` through `2024-12-31`. Amounts are USDT.

| Exit | Realized | Unrealized | Total | Max DD | Median hold | P90 hold | Open lots |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DRA V1 fixed `+5%` | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` | `1` |
| V2A ATR trail `1.50` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` | `1` |
| V2B `TURNOVER` | `59.93271313` | `0.00000000` | `59.93271313` | `5.177908%` | `179.0h` | `891.0h` | `0` |
| V2B `BALANCED` | `71.44693976` | `0.00000000` | `71.44693976` | `5.257247%` | `209.0h` | `1,263.0h` | `0` |
| V2B `TREND` | `71.52625635` | `0.00000000` | `71.52625635` | `7.302341%` | `256.0h` | `1,451.0h` | `0` |

All four reference ledgers had `51` buys. DRA V1 and V2A had `50` sells and
one open lot; each V2B profile closed all `51` lots.

## Annual stability

Against the fair-reset V1 ledger, `TURNOVER` beat annual total PnL in only two
of five calendar folds and improved annual median holding time in only one.
`BALANCED` and `TREND` each beat annual total PnL in one fold and improved
annual median holding time in none. The preregistered requirement was at least
three of five for both measures.

The central failure was 2022. `TURNOVER` ended with the same unrealized loss as
V1 but substantially less realized profit; `BALANCED` and `TREND` carried
larger ending inventory losses. Closing every lot in the aggregate Validation
window therefore did not establish cross-regime stability.

## Decision

V2B solved one side of the V2A trade-off:

- ending unrealized PnL improved from `-3.20820121` to zero;
- `TURNOVER` reduced P90 holding time by `527.3h` and drawdown by `1.943590`
  percentage points versus V1.

But this came from harvesting trends earlier. Validation total PnL fell by:

- `26.27026873` for `TURNOVER`;
- `14.75604210` for `BALANCED`;
- `14.67672551` for `TREND`.

No profile passed the frozen profitability and annual-stability gates. The
correct result is `NO_CANDIDATE`. OOS remains sealed; no one-slot overlay,
owner-509 promotion comparison, runtime implementation, SHADOW, LIVE,
deployment, order, or Production change is authorized.

## Next hypothesis

Do not widen this V2B parameter sweep. The evidence suggests that a single
price-only ratchet cannot simultaneously maximize trend capture and capital
recycling. A separate future study would need an observable reversal signal
to tighten the ratchet only after trend deterioration, instead of tightening
solely because volatility is high or low.
