# Java/Python DRA Cross-Language Economic Ledger V2

Status: `FROZEN_PHASE_B_BASELINE_PARITY`

## Purpose

Prove that the existing Python DRA V1 research engine and the existing Java
`BtcDraShadowEngine` produce the same ordered economic history, not merely the
same terminal performance checkpoint. This is the Phase B prerequisite for
treating Java as a shared economic kernel for later research overlays.

The parity result has no strategy-improvement or activation claim. It remains
`RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE`.

## Hard boundary

Both replay implementations:

- read only the frozen local canonical TSV used by Phase A;
- run as plain offline processes without Spring, repositories, databases,
  exchanges, network calls, orders, schedulers, deployment, or notification;
- write only sealed local research artifacts;
- never invoke Maven `exec:java`; Java is launched directly from Java 21 with
  an explicit compile-time dependency classpath;
- cannot activate `SHADOW`, `PAPER`, or `LIVE`.

## Frozen input and windows

- rows: `52,608` complete hourly OKX `BTCUSDT` bars;
- input SHA-256:
  `e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd`;
- Design: `[2019-01-01T00:00:00, 2023-01-01T00:00:00)`;
- Validation: `[2023-01-01T00:00:00, 2025-01-01T00:00:00)`;
- warmup: the preceding 90 days, indicators only;
- policy: `BTC_DAILY_REVERSAL_ACCUMULATION_V1`.

The exact Phase A terminal checkpoints remain mandatory and unchanged.

## Canonical normalization

- encoding: UTF-8;
- delimiter: tab;
- record terminator: LF;
- timestamps: local UTC trading-clock values with fixed seconds,
  `yyyy-MM-dd'T'HH:mm:ss`, and empty when absent;
- money, prices, notional, fees, and PnL: fixed 8 decimal places,
  `ROUND_HALF_UP`;
- quantities: fixed 12 decimal places, `ROUND_DOWN`;
- returns and drawdown ratios: fixed 8 decimal places, `ROUND_HALF_UP`;
- booleans embedded in event reasons: lowercase `true` or `false`;
- all hashes: lowercase SHA-256 over the exact canonical bytes.

## Four mandatory ledgers

### 1. Ordered decision-event ledger

Every Java runtime event has one row with these fields, in emitted order:

```text
event_type event_time signal_time lot_id notional fill_price fill_qty fee net_pnl net_return reason
```

The Python trace adapter must independently reconstruct the same events from
the frozen Python V1 engine. Events include arm, expiry, entry queue/block,
buy fill, exit queue/defer, and sell fill.

### 2. Fill ledger

The ordered subset of decision events whose type is `VIRTUAL_BUY_FILL` or
`VIRTUAL_SELL_FILL`. Both row count and SHA-256 must match.

### 3. Hourly economic-state ledger

One row follows every genuine trading bar, after all same-bar transitions:

```text
bar_time armed_at arm_expires_at last_entry_signal pending_signal
pending_notional pending_reason open_lot_count lot_book_sha256 total_buy_notional
total_sell_proceeds realized total_fees open_cost inventory_qty
inventory_value unrealized total_pnl buy_count sell_count winning_exit_count
deferred_exit_count queued_entry_count blocked_entry_count arm_count
expired_arm_count max_open_cost max_open_capital_loss_ratio peak_equity
max_drawdown_ratio
```

The per-row lot-book hash uses the terminal-lot row format below. This makes
the state ledger sensitive to lot identity, timestamps, quantity, entry
reason, and queued-exit state without embedding variable-width nested data.

### 4. Terminal lot ledger

One row per open lot, preserving engine order:

```text
lot_id signal_time buy_fill_time gross_buy_notional buy_fill_price quantity entry_reason exit_queued_at
```

Both row count and SHA-256 must match.

## Fail-closed gate

For both Design and Validation, all of the following must be true:

- the frozen Phase A checkpoint matches;
- event count and event SHA-256 match;
- fill count and fill SHA-256 match;
- hourly state row count and state SHA-256 match;
- terminal lot count and lot SHA-256 match.

If any field differs, the adapter returns `JAVA_LEDGER_PARITY_REJECT` and
records the first differing ledger and line. It must not weaken formatting,
rounding, event coverage, or window gates to obtain a pass.

The success status is `JAVA_LEDGER_PARITY_PASS_RESEARCH_ONLY`. Even after a
pass, Java is not mandatory for all research until a separately frozen Phase C
reproduces one representative complex lot-management overlay with these same
four ledgers.
