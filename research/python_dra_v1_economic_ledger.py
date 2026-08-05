#!/usr/bin/env python3
"""Independent Python DRA V1 economic-ledger trace for Java Phase B parity."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter
from datetime import datetime, timedelta
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP
from pathlib import Path

import btc_dra_reversal_confirmed_exit_v2c as base


D = Decimal
ZERO = D("0")
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
SCHEMA_VERSION = "PYTHON_DRA_ECONOMIC_LEDGER_V2"
EXPECTED_ROWS = 52_608
EXPECTED_INPUT_SHA256 = (
    "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
)
WINDOWS = {"design": base.DESIGN, "validation": base.VALIDATION}
EXPECTED_CHECKPOINTS = {
    "design": base.EXPECTED["v1_design"],
    "validation": base.EXPECTED["v1_validation"],
}


class LedgerReject(RuntimeError):
    pass


def timestamp(value: datetime | None) -> str:
    return "" if value is None else value.isoformat(timespec="seconds")


def java_local_time(value: datetime) -> str:
    if value.second == 0 and value.microsecond == 0:
        return value.isoformat(timespec="minutes")
    return value.isoformat(timespec="seconds")


def lot_id(signal_time: datetime) -> str:
    return f"DRA-V1-{java_local_time(signal_time)}"


def fixed(value: D | None, quantum: D, rounding: str = ROUND_HALF_UP) -> str:
    normalized = (ZERO if value is None else value).quantize(quantum, rounding=rounding)
    return format(normalized, "f")


def money(value: D | None) -> str:
    return fixed(value, base.MONEY_Q)


def quantity(value: D | None) -> str:
    return fixed(value, base.QTY_Q, ROUND_DOWN)


def ratio(value: D | None) -> str:
    return fixed(value, base.RETURN_Q)


def canonical_event(event: dict[str, object]) -> str:
    return "\t".join(
        (
            str(event["event_type"]),
            timestamp(event["event_time"]),
            timestamp(event["signal_time"]),
            str(event["lot_id"]),
            money(event["notional"]),
            money(event["fill_price"]),
            quantity(event["fill_qty"]),
            money(event["fee"]),
            money(event["net_pnl"]),
            ratio(event["net_return"]),
            str(event["reason"]),
        )
    )


class TraceEngine(base.Engine):
    def __init__(self) -> None:
        super().__init__("v1")
        self.events: list[str] = []
        self.fill_events: list[str] = []
        self.state_rows: list[str] = []
        self.entry_reason_by_lot: dict[str, str] = {}
        self.pending_reason_trace = ""
        self.total_buy_notional_trace = ZERO
        self.total_fees_trace = ZERO
        self.winning_exit_count_trace = 0
        self.queued_entry_count_trace = 0
        self.arm_count_trace = 0
        self.expired_arm_count_trace = 0
        self.max_open_capital_loss_trace = ZERO

    def _record(
        self,
        event_type: str,
        event_time: datetime,
        signal_time: datetime | None,
        event_lot_id: str,
        notional: D,
        fill_price: D,
        fill_qty: D,
        fee: D,
        net_pnl: D,
        net_event_return: D,
        reason: str,
    ) -> None:
        line = canonical_event(
            {
                "event_type": event_type,
                "event_time": event_time,
                "signal_time": signal_time,
                "lot_id": event_lot_id,
                "notional": notional,
                "fill_price": fill_price,
                "fill_qty": fill_qty,
                "fee": fee,
                "net_pnl": net_pnl,
                "net_return": net_event_return,
                "reason": reason,
            }
        )
        self.events.append(line)
        if event_type in {"VIRTUAL_BUY_FILL", "VIRTUAL_SELL_FILL"}:
            self.fill_events.append(line)

    def _fill_exits(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is None:
                continue
            sell_price = base.adverse_sell(bar.open)
            gross = base.money(lot.quantity * sell_price)
            fee = base.money(gross * base.FEE)
            net = gross - fee
            pnl = base.money(net - lot.cost)
            realized_return = base.net_return(net, lot.cost)
            if realized_return < base.V1_FILL_RETURN:
                self._record(
                    "VIRTUAL_EXIT_DEFERRED",
                    bar.open_time,
                    lot.signal_time,
                    lot_id(lot.signal_time),
                    ZERO,
                    sell_price,
                    lot.quantity,
                    ZERO,
                    pnl,
                    realized_return,
                    "NEXT_OPEN_BELOW_NET_PROFIT_FLOOR",
                )
            else:
                self.total_fees_trace += fee
                if pnl > ZERO:
                    self.winning_exit_count_trace += 1
                self._record(
                    "VIRTUAL_SELL_FILL",
                    bar.open_time,
                    lot.signal_time,
                    lot_id(lot.signal_time),
                    lot.cost,
                    sell_price,
                    lot.quantity,
                    fee,
                    pnl,
                    realized_return,
                    "NEXT_1H_OPEN_NET_PROFIT_CONFIRMED",
                )
        super()._fill_exits(bar)

    def _fill_buy(self, bar: base.Bar) -> None:
        pending_signal = self.pending_signal
        if pending_signal is not None:
            buy_price = base.adverse_buy(bar.open)
            fee = base.money(base.LOT_COST * base.FEE)
            fill_quantity = base.quantity((base.LOT_COST - fee) / buy_price)
            identifier = lot_id(pending_signal)
            self.total_buy_notional_trace += base.LOT_COST
            self.total_fees_trace += fee
            self.entry_reason_by_lot[identifier] = self.pending_reason_trace
            self._record(
                "VIRTUAL_BUY_FILL",
                bar.open_time,
                pending_signal,
                identifier,
                base.LOT_COST,
                buy_price,
                fill_quantity,
                fee,
                ZERO,
                ZERO,
                "NEXT_1H_OPEN",
            )
        super()._fill_buy(bar)
        if pending_signal is not None:
            self.pending_reason_trace = ""

    def _queue_v1(self, bar: base.Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            net = base.estimated_net(lot.quantity, bar.close)
            net_event_return = base.net_return(net, lot.cost)
            if net_event_return >= base.V1_QUEUE_RETURN:
                self._record(
                    "VIRTUAL_EXIT_QUEUED",
                    bar.open_time,
                    lot.signal_time,
                    lot_id(lot.signal_time),
                    lot.cost,
                    base.adverse_sell(bar.close),
                    lot.quantity,
                    ZERO,
                    net - lot.cost,
                    net_event_return,
                    "CLOSE_NET_RETURN_AT_LEAST_5_PERCENT",
                )
        super()._queue_v1(bar)

    def _signal_reason(self, bar: base.Bar) -> str:
        close = self.close_history[-1][1]
        close_24h_ago = self.close_history[0][1]
        momentum = ((close - close_24h_ago) / close_24h_ago).quantize(
            base.RETURN_Q, rounding=ROUND_HALF_UP
        )
        ema_five_days_ago = self.ema20_history[0][1]
        return (
            f"dailyDecision={str(bar.open_time.hour == 23).lower()} "
            f"momentum24h={format(momentum, 'f')} close={str(close)} "
            f"dailyEma20={str(self.ema20)} "
            f"dailyEma20FiveDaysAgo={str(ema_five_days_ago)} "
            f"closeAboveEma={str(close > self.ema20).lower()} "
            f"emaRisingFiveDays={str(self.ema20 > ema_five_days_ago).lower()}"
        )

    def _entry_lifecycle(self, bar: base.Bar) -> None:
        prior_armed = self.armed_at
        prior_expiry = self.arm_expires_at
        prior_last_signal = self.last_entry_signal

        expired = prior_armed is not None and bar.open_time >= prior_expiry
        effective_armed = None if expired else prior_armed
        confirmation = (
            effective_armed is not None
            and bar.open_time > effective_armed
            and self._signal(bar)
        )
        blocked = confirmation and base.LOT_COST * D(len(self.lots) + 1) > self.cap
        queued = confirmation and not blocked
        reason = self._signal_reason(bar) if queued else ""
        updated_last_signal = bar.open_time if queued else prior_last_signal
        armed_after_confirmation = None if confirmation else effective_armed
        cooldown_passed = (
            updated_last_signal is None
            or bar.open_time >= updated_last_signal + timedelta(days=7)
        )
        newly_armed = armed_after_confirmation is None and cooldown_passed

        super()._entry_lifecycle(bar)

        if expired:
            self.expired_arm_count_trace += 1
            self._record(
                "DRA_ARM_EXPIRED",
                bar.open_time,
                prior_armed,
                "",
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                "NO_DAILY_REVERSAL_CONFIRMATION_WITHIN_30_DAYS",
            )
        if blocked:
            self._record(
                "VIRTUAL_ENTRY_BLOCKED",
                bar.open_time,
                bar.open_time,
                lot_id(bar.open_time),
                base.LOT_COST,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                "MAX_OPEN_COST_EXCEEDED",
            )
        elif queued:
            self.queued_entry_count_trace += 1
            self.pending_reason_trace = reason
            self._record(
                "VIRTUAL_ENTRY_QUEUED",
                bar.open_time,
                bar.open_time,
                lot_id(bar.open_time),
                base.LOT_COST,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                reason,
            )
        if newly_armed:
            self.arm_count_trace += 1
            self._record(
                "DRA_ARMED",
                bar.open_time,
                bar.open_time,
                "",
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                ZERO,
                "WAITING_FOR_DAILY_EMA20_REVERSAL_CONFIRMATION",
            )

    def step(self, bar: base.Bar) -> None:
        super().step(bar)
        self.state_rows.append(self._state_row(bar))

    def _lot_line(self, lot: base.Lot) -> str:
        identifier = lot_id(lot.signal_time)
        return "\t".join(
            (
                identifier,
                timestamp(lot.signal_time),
                timestamp(lot.fill_time),
                money(lot.cost),
                money(lot.buy_price),
                quantity(lot.quantity),
                self.entry_reason_by_lot[identifier],
                timestamp(lot.exit_queued_at),
            )
        )

    def lot_lines(self) -> list[str]:
        return [self._lot_line(lot) for lot in self.lots]

    def _state_row(self, bar: base.Bar) -> str:
        lots = self.lot_lines()
        lot_bytes = "".join(f"{line}\n" for line in lots).encode("utf-8")
        lot_hash = hashlib.sha256(lot_bytes).hexdigest()
        open_cost = sum((lot.cost for lot in self.lots), ZERO)
        inventory_qty = sum((lot.quantity for lot in self.lots), ZERO)
        inventory_value = sum(
            (base.estimated_net(lot.quantity, bar.close) for lot in self.lots), ZERO
        )
        unrealized = base.money(inventory_value - open_cost)
        total_pnl = base.money(self.realized + unrealized)
        open_return = (
            (unrealized / open_cost).quantize(base.RETURN_Q, rounding=ROUND_HALF_UP)
            if open_cost > ZERO
            else ZERO
        )
        open_loss = abs(open_return) if open_return < ZERO else ZERO
        self.max_open_capital_loss_trace = max(
            self.max_open_capital_loss_trace, open_loss
        )
        return "\t".join(
            (
                timestamp(bar.open_time),
                timestamp(self.armed_at),
                timestamp(self.arm_expires_at),
                timestamp(self.last_entry_signal),
                timestamp(self.pending_signal),
                money(base.LOT_COST if self.pending_signal is not None else ZERO),
                self.pending_reason_trace,
                str(len(self.lots)),
                lot_hash,
                money(self.total_buy_notional_trace),
                money(self.total_sell_proceeds),
                money(self.realized),
                money(self.total_fees_trace),
                money(open_cost),
                quantity(inventory_qty),
                money(inventory_value),
                money(unrealized),
                money(total_pnl),
                str(self.buy_count),
                str(self.sell_count),
                str(self.winning_exit_count_trace),
                str(self.deferred_count),
                str(self.queued_entry_count_trace),
                str(self.blocked_count),
                str(self.arm_count_trace),
                str(self.expired_arm_count_trace),
                money(self.max_open_cost),
                ratio(self.max_open_capital_loss_trace),
                money(self.peak_equity),
                ratio(self.max_drawdown),
            )
        )


def write_lines(path: Path, lines: list[str]) -> dict[str, object]:
    payload = "".join(f"{line}\n" for line in lines).encode("utf-8")
    path.write_bytes(payload)
    return {"rows": len(lines), "sha256": hashlib.sha256(payload).hexdigest()}


def replay(
    bars: list[base.Bar],
    name: str,
    window: tuple[datetime, datetime],
    output_dir: Path,
) -> dict[str, object]:
    start, end = window
    warmup_start = start - timedelta(days=90)
    selected = [
        bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise LedgerReject(f"DATA_REJECT: no bars in {name}")
    engine = TraceEngine()
    for bar in selected:
        if bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    checkpoint = base.checkpoint_tuple(engine.result(trading[-1], start, end))
    if checkpoint != EXPECTED_CHECKPOINTS[name]:
        raise LedgerReject(
            f"BASELINE_REJECT: {name} expected {EXPECTED_CHECKPOINTS[name]} got {checkpoint}"
        )

    window_dir = output_dir / name
    window_dir.mkdir()
    event_evidence = write_lines(window_dir / "events.tsv", engine.events)
    fill_evidence = write_lines(window_dir / "fills.tsv", engine.fill_events)
    state_evidence = write_lines(window_dir / "states.tsv", engine.state_rows)
    lot_evidence = write_lines(window_dir / "lots.tsv", engine.lot_lines())
    event_counts = dict(sorted(Counter(line.split("\t", 1)[0] for line in engine.events).items()))
    return {
        "checkpoint": list(checkpoint),
        "checkpoint_parity": True,
        "event_counts": event_counts,
        "events": event_evidence,
        "fills": fill_evidence,
        "states": state_evidence,
        "terminal_lots": lot_evidence,
    }


def run(input_path: Path, output_dir: Path) -> dict[str, object]:
    if output_dir.exists():
        raise LedgerReject(f"OUTPUT_SEAL_REJECT: {output_dir}")
    raw = input_path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    bars = base.parse_rows(raw.decode("utf-8"))
    if len(bars) != EXPECTED_ROWS or digest != EXPECTED_INPUT_SHA256:
        raise LedgerReject(
            "DATA_REJECT: "
            f"rows={len(bars)} sha256={digest} expected_rows={EXPECTED_ROWS} "
            f"expected_sha256={EXPECTED_INPUT_SHA256}"
        )
    output_dir.mkdir(parents=True)
    result: dict[str, object] = {
        "schema_version": SCHEMA_VERSION,
        "status": "PYTHON_LEDGER_GENERATED",
        "authorization": AUTHORIZATION,
        "input_rows": len(bars),
        "input_sha256": digest,
        "windows": {},
    }
    for name, window in WINDOWS.items():
        result["windows"][name] = replay(bars, name, window, output_dir)
    result_path = output_dir / "result.json"
    result_path.write_text(
        json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.input, args.output_dir)
    except (LedgerReject, base.ResearchReject) as error:
        print(json.dumps({"status": "PYTHON_LEDGER_REJECT", "detail": str(error)}))
        return 2
    print(
        json.dumps(
            {
                "status": result["status"],
                "output_dir": str(args.output_dir.resolve()),
            }
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
