# Strategy 508 HOLD Counterfactual Report

## Boundary

- Generated from a read-only production query on 2026-07-12.
- Window: latest 30 days, `BTCUSDT`, strategy `508`.
- No environment, database, order, OCO, strategy flag, scheduler, Telegram,
  exchange, deploy, or runtime state was changed.
- The production runtime did not contain the new MCP tool yet. The current
  numbers were independently reproduced with the same event-key, fail-closed,
  fee, TP, and SL semantics before deployment.

## Method

- Unique event key: `strategyId|symbol|side|interval|barOpenTime`.
- Evidence source, blocker text, reason, and audit row id are not part of the
  key. All rows in one decision chain are merged before classification.
- An event is eligible only when strategy 508 records `BUY` and
  `trigger_reason=all_gates_passed`, no order exists, no hard or unknown blocker
  exists, and an allowlisted soft gate is the sole blocker.
- Ordinary `HOLD`, missing predicate evidence, existing orders, EntryDedup,
  exposure, position sizing, data freshness, OCO, event risk, daily risk, and
  unknown blockers fail closed.
- Simulation: one 10 USDT gross buy, 0.1% entry fee, 0.1% exit fee, +6% TP,
  -12% disaster SL, and at most 24 hours using OKX 1m OHLC.
- A 1m bar touching TP and SL is ambiguous and not finalized. A finalized
  sample requires at least 99% 1m coverage through the resolved horizon.

## Production Evidence

| Metric | Result |
| --- | ---: |
| Raw evidence rows | 947 |
| Unique market events | 604 |
| Event-chain rows collapsed | 343 |
| Signal not ready | 576 |
| Hard-safety excluded | 11 |
| Already ordered | 3 |
| Missing event key | 9 |
| No proven soft blocker | 1 |
| Eligible soft-gate events | 4 |
| Finalized events | 4 |
| Minimum finalized sample | 30 |

Eligible outcomes:

| Bar open UTC | Soft blocker | Outcome | Net PnL USDT |
| --- | --- | --- | ---: |
| 2026-07-08 07:00 | ExpectedValueGate | TIMEOUT_24H | -0.00279665 |
| 2026-07-08 13:00 | TradePlanQualityGate | TIMEOUT_24H | +0.15885649 |
| 2026-07-09 08:00 | TradePlanQualityGate | TIMEOUT_24H | +0.17582038 |
| 2026-07-09 09:00 | TradePlanQualityGate | TIMEOUT_24H | +0.20876760 |

Total net PnL was `+0.54064782 USDT` on `40 USDT` gross buy notional.
Maximum cumulative PnL drawdown was `0.00279665 USDT`. No eligible event hit
the +6% TP or -12% SL within 24 hours.

## Verdict

`sampleStatus=INSUFFICIENT_DATA`

The positive four-event result is not evidence of a durable edge. It contains
no TP/SL outcome diversity, is far below the 30-event minimum, and covers one
short market window. `liveRelaxationAllowed=false`; existing live gates and
order size must remain unchanged.

After an explicitly authorized deployment, reproduce the canonical report with:

```powershell
.\scripts\smoke_strategy508_hold_counterfactual_ssh.ps1 -Hours 720 -RequireInsufficientData
```
