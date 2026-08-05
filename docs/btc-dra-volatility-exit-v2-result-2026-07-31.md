# BTC DRA Volatility Exit V2 Research Result

Date: 2026-07-31

Research identity: `BTC_DRA_VOLATILITY_EXIT_V2_RESEARCH`

Decision:

```text
DATA_QUALITY_PASS
BASELINE_PARITY_PASS
DESIGN_VALIDATION_COMPLETE
NO_CANDIDATE
OOS_NOT_OPENED
RESEARCH_ONLY
NOT_AUTHORIZED_FOR_SHADOW_OR_LIVE
```

## What was tested

DRA V1 entry, capital, fees, adverse slippage, independent-lot accounting,
next-open fills, and profit-only inventory rules were held constant. Only the
fixed `+5%` exit trigger was replaced by the preregistered volatility exits in
[`btc-dra-volatility-exit-v2-research.md`](btc-dra-volatility-exit-v2-research.md):

1. an entry-frozen daily ATR14 profit target; and
2. a monotonic daily ATR14 trailing exit.

Each family was tested at ATR multipliers `0.50`, `0.75`, `1.00`, `1.25`,
`1.50`, and `2.00`. No candidate contains a fixed percentage profit target.

## Data and evaluator acceptance

- Source: Production database, read-only OKX `BTCUSDT` closed `1h` bars.
- Data boundary: `2019-01-01T00:00:00` through `2026-07-31T14:00:00` close.
- Rows: `66,446`.
- Duplicate open times: `0`.
- Non-hourly gaps: `0`.
- Off-grid or non-one-hour bars: `0`.
- Invalid numeric or OHLC rows: `0`.
- Exact input SHA-256:
  `9f7ae95212ac4b2d58e938f3fddba384f4d6af5e45586e67153115bc78b9f544`.

Before V2 performance was inspected, the independent evaluator reproduced all
five frozen DRA V1 checkpoints exactly, including realized PnL, unrealized PnL,
total PnL, and maximum drawdown for Design, Validation, OOS, Main, and Full.
This is `BASELINE_PARITY_PASS`; it is not merely approximate agreement.

## Validation result

Window: `2023-01-01` through `2024-12-31`. Amounts are USDT. The OOS window
starting in 2025 was not evaluated for any V2 candidate.

| Exit | ATR | Realized | Unrealized | Total | Max DD | Median hold | P90 hold |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| DRA V1 fixed exit | n/a | `89.41118307` | `-3.20820121` | `86.20298186` | `7.121498%` | `182.5h` | `1,418.3h` |
| Frozen target | `0.50` | `35.98616722` | `0.00000000` | `35.98616722` | `4.064061%` | `80.0h` | `315.0h` |
| Frozen target | `0.75` | `49.31943347` | `0.00000000` | `49.31943347` | `4.384282%` | `98.0h` | `316.0h` |
| Frozen target | `1.00` | `65.06629518` | `-3.20820121` | `61.85809397` | `6.641564%` | `140.5h` | `830.7h` |
| Frozen target | `1.25` | `83.75907952` | `-3.20820121` | `80.55087831` | `11.222212%` | `168.5h` | `2,881.0h` |
| Frozen target | `1.50` | `95.15452883` | `-3.20820121` | `91.94632762` | `10.886068%` | `212.5h` | `2,898.7h` |
| Frozen target | `2.00` | `106.89010603` | `-5.17067796` | `101.71942807` | `21.624734%` | `376.0h` | `3,955.0h` |
| ATR trail | `0.50` | `48.51204968` | `0.00000000` | `48.51204968` | `4.322904%` | `112.0h` | `343.0h` |
| ATR trail | `0.75` | `65.22179750` | `0.00000000` | `65.22179750` | `4.296502%` | `184.0h` | `379.0h` |
| ATR trail | `1.00` | `63.94491506` | `-3.20820121` | `60.73671385` | `5.024518%` | `204.5h` | `651.6h` |
| ATR trail | `1.25` | `87.34568407` | `-3.20820121` | `84.13748286` | `7.401837%` | `330.5h` | `1,703.5h` |
| ATR trail | `1.50` | `116.45914729` | `-3.20820121` | `113.25094608` | `8.945793%` | `401.0h` | `1,846.6h` |
| ATR trail | `2.00` | `142.41633532` | `-3.20820121` | `139.20813411` | `14.566882%` | `623.5h` | `2,801.0h` |

## Why no candidate passed

The results reveal a real trade-off rather than one universally better exit:

- Small ATR targets substantially reduced drawdown, holding time, and ending
  inventory, but surrendered too much realized and total profit.
- Wider ATR targets increased realized profit, but holding tails and drawdown
  became materially worse.
- The strongest risk-bounded exploratory result was ATR trail `1.50`: versus
  V1 it improved realized and total PnL by `27.04796422 USDT` and left the same
  ending unrealized PnL. Drawdown increased by `1.824295` percentage points,
  which remained inside the preregistered two-point allowance. However, median
  holding time worsened from `182.5h` to `401.0h`, and P90 worsened from
  `1,418.3h` to `1,846.6h`; it therefore failed the frozen holding-time gates.
- ATR trail `2.00` had the highest Validation total PnL, but its `14.566882%`
  drawdown and much longer holding time failed the risk and capital-efficiency
  requirements.

Changing the gates after seeing these values and then opening OOS would turn a
clean study into post-hoc selection. The correct V2 result is therefore
`NO_CANDIDATE`, not an activation proposal.

## Relationship to owner 509

The earlier equal-capital Main-window baseline for owner 509 was realized
`+36.28661631`, unrealized `-67.10733974`, total `-30.82072343`, and maximum
drawdown `28.304359%`. That historical line remains context only. Because no
V2 candidate passed selection and OOS stayed sealed, this study makes no new
claim that a specific V2 exit beats owner 509.

## Recommended next research

Do not relax this study retroactively. If another iteration is desired, create
an explicitly versioned V2B study using only pre-2025 folds for selection and
keep 2025 onward sealed. The most justified new family is a regime-adaptive
ATR ratchet whose multiplier is chosen causally from a trailing volatility
percentile, with holding time reported as an opportunity-cost metric rather
than silently optimized away. It still must retain the next-open net-positive
safety boundary and must not modify DRA V1 or position `263`.
