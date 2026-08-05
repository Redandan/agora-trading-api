# Java DRA Offline Research CLI Parity V1

Status: `FROZEN_IMPLEMENTATION_PARITY_ONLY`

## Purpose

Establish one offline Java economic-kernel vertical slice before translating
any strategy overlay. The CLI must reproduce the frozen Python DRA V1 Design
and Validation accounting exactly by calling the existing deterministic
`BtcDraShadowEngine` directly.

This work has no direct PnL claim. Its value is preventing research candidates
from passing because Python and the Production reference engine disagree on
clock, fill, fee, quantity, inventory, or drawdown semantics.

## Hard boundary

The CLI:

- is a plain Java `main`, not a Spring bean, HTTP/MCP endpoint, scheduler, or
  runtime strategy;
- must not start `TradingApiApplication` or a Spring application context;
- has no repository, database, exchange, network, order, OCO, Grid,
  notification, Production, deployment, or promotion path;
- reads one explicitly supplied local TSV and writes one sealed local JSON;
- cannot activate `SHADOW`, `PAPER`, or `LIVE`; a pass means only
  `JAVA_PARITY_PASS_RESEARCH_ONLY`.

## Frozen input

The input is the canonical pre-2025 selection dataset:

- source: server-local OKX `BTCUSDT` complete causal `1h` rows, exported by a
  separate read-only Python data adapter;
- range: `2019-01-01T00:00:00` through the last bar whose close is no later
  than `2025-01-01T00:00:00`;
- rows: `52,608`;
- SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`.

Each UTF-8 TSV row has exactly:

```text
open_time close_time open high low close volume
```

Fields are tab-separated and each canonical row ends with LF. The CLI rejects
wrong hash, row count, duplicate/gap/off-grid time, non-one-hour duration,
invalid OHLC, non-positive prices, or negative volume before replay.

## Frozen engine and windows

- Engine: current `BtcDraShadowEngine` and `BtcDraPolicy`.
- Warmup: the prior 90 days available inside the frozen input; warmup updates
  indicators only and creates no arm, signal, pending fill, or lot.
- Design: `[2019-01-01T00:00:00, 2023-01-01T00:00:00)`.
- Validation: `[2023-01-01T00:00:00, 2025-01-01T00:00:00)`.
- Initial virtual equity/reference cap: `250 USDT`.
- Lot notional: `30 USDT`.
- Fees, adverse slippage, next-open fills, +5% estimated-net queue, +1%
  next-open realized-net floor, seven-day cooldown, 30-day arm expiry, and no
  final liquidation come only from `BtcDraPolicy`.

## Exact checkpoint contract

The CLI must match these existing sealed Python checkpoints field-for-field:

| Window | Realized | Unrealized | Total | DD | Median/P90 hold | Buy/Sell/Open | Blocked | Avg utilization | Turnover |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Design | 169.89846767 | -79.12049441 | 90.77797326 | 29.530448% | 126.0 / 1818.6 h | 100 / 95 / 5 | 3 | 34.364819% | 3019.89846767 |
| Validation | 89.41118307 | -3.20820121 | 86.20298186 | 7.121498% | 182.5 / 1418.3 h | 51 / 50 / 1 | 0 | 21.632695% | 1589.41118307 |

Required output also includes event counts, an ordered event-ledger SHA-256,
the final Java runtime-state SHA-256, open lots, data hash, engine class, and
policy identity. These hashes become Phase B comparison surfaces; Phase A
passes only on all exact checkpoint fields.

## Status

- all input and checkpoint gates pass:
  `JAVA_PARITY_PASS_RESEARCH_ONLY`;
- input gate fails: `DATA_REJECT`;
- any checkpoint differs: `JAVA_PARITY_REJECT`.

Output paths are immutable. Existing output is never overwritten. Java parity
does not make Java mandatory for candidate research until a later frozen Phase
B proves normalized event, fill, lot, and cross-language economic-state hashes
for DRA V1 and one representative complex overlay.
