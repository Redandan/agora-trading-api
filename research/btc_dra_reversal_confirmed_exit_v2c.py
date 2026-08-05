#!/usr/bin/env python3
"""Causal, server-local DRA V2C research with a sealed 2025+ OOS stage."""

from __future__ import annotations

import argparse
import base64
import csv
import hashlib
import json
import os
import subprocess
import sys
from collections import deque
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal, ROUND_DOWN, ROUND_HALF_UP, getcontext
from pathlib import Path
from typing import Iterable

getcontext().prec = 34

D = Decimal
ZERO = D("0")
ONE = D("1")
LOT_COST = D("30.00")
REFERENCE_CAP = D("250.00")
FEE = D("0.0010")
SLIPPAGE = D("0.0005")
V1_QUEUE_RETURN = D("0.0500")
V1_FILL_RETURN = D("0.0100")
V2A_MULTIPLIER = D("1.50")
MONEY_Q = D("0.00000001")
QTY_Q = D("0.000000000001")
RETURN_Q = D("0.00000001")
EMA20_ALPHA = (D(2) / D(21)).quantize(D("0.0000000000000001"), rounding=ROUND_HALF_UP)
EMA5_ALPHA = (D(2) / D(6)).quantize(D("0.0000000000000001"), rounding=ROUND_HALF_UP)
RESEARCH_IDENTITY = "BTC_DRA_REVERSAL_CONFIRMED_VOLATILITY_EXIT_V2C_RESEARCH"
SELECTION_CUTOFF = datetime(2025, 1, 1)
SELECTION_ROWS = 52_608
SELECTION_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
FACTORS = (
    "EMA20_SLOPE_NONPOSITIVE",
    "CLOSE_BELOW_EMA5",
    "ATR1_REVERSAL",
    "DONCHIAN5_NEGATIVE_MOMENTUM",
    "CONSENSUS_2_OF_4",
)
V2B_PROFILES = {
    "TURNOVER": {"LOW": D("0.75"), "NORMAL": D("1.25"), "HIGH": D("0.50")},
    "BALANCED": {"LOW": D("1.00"), "NORMAL": D("1.50"), "HIGH": D("0.75")},
    "TREND": {"LOW": D("1.25"), "NORMAL": D("1.75"), "HIGH": D("1.00")},
}
DESIGN = (datetime(2019, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
FOLDS = {str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1)) for year in range(2020, 2025)}


class ResearchReject(RuntimeError):
    def __init__(self, status: str, detail: object):
        super().__init__(str(detail))
        self.status = status
        self.detail = detail


def money(value: D) -> D:
    return value.quantize(MONEY_Q, rounding=ROUND_HALF_UP)


def quantity(value: D) -> D:
    return value.quantize(QTY_Q, rounding=ROUND_DOWN)


def net_return(value: D, cost: D) -> D:
    if cost <= 0:
        return ZERO
    return ((value - cost) / cost).quantize(RETURN_Q, rounding=ROUND_HALF_UP)


def adverse_buy(price: D) -> D:
    return money(price * (ONE + SLIPPAGE))


def adverse_sell(price: D) -> D:
    return money(price * (ONE - SLIPPAGE))


def estimated_net(qty: D, price: D) -> D:
    gross = money(qty * adverse_sell(price))
    return money(gross - money(gross * FEE))


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    if len(ordered) == 1:
        return round(ordered[0], 2)
    position = (len(ordered) - 1) * p
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    return round(ordered[low] + (ordered[high] - ordered[low]) * (position - low), 2)


def decimal_quantile(values: list[D], p: D) -> D:
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = D(len(ordered) - 1) * p
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    fraction = position - D(low)
    return ordered[low] + (ordered[high] - ordered[low]) * fraction


@dataclass(frozen=True)
class Bar:
    open_time: datetime
    close_time: datetime
    open: D
    high: D
    low: D
    close: D
    volume: D

    def canonical(self) -> str:
        return "\t".join(
            (
                self.open_time.isoformat(timespec="seconds"),
                self.close_time.isoformat(timespec="seconds"),
                str(self.open),
                str(self.high),
                str(self.low),
                str(self.close),
                str(self.volume),
            )
        )


@dataclass
class Lot:
    signal_time: datetime
    fill_time: datetime
    cost: D
    buy_price: D
    quantity: D
    entry_atr: D | None
    highest_close: D
    ratchet_stop: D | None = None
    exit_queued_at: datetime | None = None


@dataclass(frozen=True)
class DailyPoint:
    day: datetime
    close: D
    low: D
    ema5: D
    ema20: D
    atr14: D | None


class Engine:
    def __init__(
        self,
        mode: str,
        *,
        factor: str | None = None,
        profile: str | None = None,
        cap: D = REFERENCE_CAP,
    ) -> None:
        self.mode = mode
        self.factor = factor
        self.profile = profile
        self.cap = cap
        self.close_history: deque[tuple[datetime, D]] = deque(maxlen=25)
        self.ema20_history: deque[tuple[datetime, D]] = deque(maxlen=6)
        self.daily_points: deque[DailyPoint] = deque(maxlen=400)
        self.atr_window: deque[D] = deque(maxlen=252)
        self.ema20: D | None = None
        self.ema5: D | None = None
        self.daily_day: datetime | None = None
        self.daily_high: D | None = None
        self.daily_low: D | None = None
        self.daily_close: D | None = None
        self.previous_daily_close: D | None = None
        self.tr_seed: list[D] = []
        self.atr14: D | None = None
        self.armed_at: datetime | None = None
        self.arm_expires_at: datetime | None = None
        self.last_entry_signal: datetime | None = None
        self.pending_signal: datetime | None = None
        self.pending_atr: D | None = None
        self.lots: list[Lot] = []
        self.realized = ZERO
        self.buy_count = 0
        self.sell_count = 0
        self.blocked_count = 0
        self.deferred_count = 0
        self.peak_equity = cap
        self.max_drawdown = ZERO
        self.max_open_cost = ZERO
        self.utilization_sum = ZERO
        self.utilization_points = 0
        self.hold_hours: list[float] = []
        self.total_sell_proceeds = ZERO
        self.exit_trigger_counts: dict[str, int] = {}
        self.bucket_counts = {"LOW": 0, "NORMAL": 0, "HIGH": 0}

    def volatility_warmup(self, bar: Bar) -> None:
        self._finish_day(bar)
        if bar.open_time.hour == 23 and self.atr14 is not None:
            self.atr_window.append(self.atr14)

    def warmup(self, bar: Bar) -> None:
        self._indicators(bar)

    def step(self, bar: Bar) -> None:
        self._fill_exits(bar)
        self._fill_buy(bar)
        self._indicators(bar)
        self._queue_exits(bar)
        self._entry_lifecycle(bar)
        self._track(bar)

    def _finish_day(self, bar: Bar) -> None:
        if self.daily_day is None or self.daily_day.date() != bar.open_time.date():
            self.daily_day = bar.open_time
            self.daily_high = bar.high
            self.daily_low = bar.low
        else:
            self.daily_high = max(self.daily_high, bar.high)
            self.daily_low = min(self.daily_low, bar.low)
        self.daily_close = bar.close
        if bar.open_time.hour != 23:
            return
        true_range = self.daily_high - self.daily_low
        if self.previous_daily_close is not None:
            true_range = max(
                true_range,
                abs(self.daily_high - self.previous_daily_close),
                abs(self.daily_low - self.previous_daily_close),
            )
        self.previous_daily_close = self.daily_close
        if self.atr14 is None:
            self.tr_seed.append(true_range)
            if len(self.tr_seed) == 14:
                self.atr14 = sum(self.tr_seed, ZERO) / D(14)
        else:
            self.atr14 = ((self.atr14 * D(13)) + true_range) / D(14)

    def _indicators(self, bar: Bar) -> None:
        self.close_history.append((bar.open_time, bar.close))
        self._finish_day(bar)
        if bar.open_time.hour != 23:
            return
        self.ema20 = money(bar.close) if self.ema20 is None else money(
            bar.close * EMA20_ALPHA + self.ema20 * (ONE - EMA20_ALPHA)
        )
        self.ema5 = money(bar.close) if self.ema5 is None else money(
            bar.close * EMA5_ALPHA + self.ema5 * (ONE - EMA5_ALPHA)
        )
        self.ema20_history.append((bar.open_time, self.ema20))
        self.daily_points.append(
            DailyPoint(bar.open_time, bar.close, self.daily_low, self.ema5, self.ema20, self.atr14)
        )
        if self.atr14 is not None:
            self.atr_window.append(self.atr14)

    def _fill_exits(self, bar: Bar) -> None:
        survivors: list[Lot] = []
        for lot in self.lots:
            if lot.exit_queued_at is None:
                survivors.append(lot)
                continue
            sell_price = adverse_sell(bar.open)
            gross = money(lot.quantity * sell_price)
            fee = money(gross * FEE)
            net = gross - fee
            pnl = money(net - lot.cost)
            if self.mode == "v1":
                fill_allowed = net_return(net, lot.cost) >= V1_FILL_RETURN
            else:
                fill_allowed = pnl > 0
            if not fill_allowed:
                lot.exit_queued_at = None
                self.deferred_count += 1
                survivors.append(lot)
                continue
            self.realized += pnl
            self.total_sell_proceeds += net
            self.sell_count += 1
            self.hold_hours.append((bar.open_time - lot.fill_time).total_seconds() / 3600)
        self.lots = survivors

    def _fill_buy(self, bar: Bar) -> None:
        if self.pending_signal is None:
            return
        price = adverse_buy(bar.open)
        fee = money(LOT_COST * FEE)
        fill_quantity = quantity((LOT_COST - fee) / price)
        self.lots.append(
            Lot(
                signal_time=self.pending_signal,
                fill_time=bar.open_time,
                cost=LOT_COST,
                buy_price=price,
                quantity=fill_quantity,
                entry_atr=self.pending_atr,
                highest_close=bar.close,
            )
        )
        self.buy_count += 1
        self.pending_signal = None
        self.pending_atr = None

    def _queue_exits(self, bar: Bar) -> None:
        if self.mode == "v1":
            self._queue_v1(bar)
        elif self.mode == "v2a":
            self._queue_v2a_or_v2c(bar, None)
        elif self.mode == "v2b":
            self._queue_v2b(bar)
        elif self.mode == "v2c":
            self._queue_v2a_or_v2c(bar, self.factor)
        else:
            raise ValueError(self.mode)

    def _queue_v1(self, bar: Bar) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            net = estimated_net(lot.quantity, bar.close)
            if net_return(net, lot.cost) >= V1_QUEUE_RETURN:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("V1_FIXED_5_PERCENT")

    def _queue_v2a_or_v2c(self, bar: Bar, factor: str | None) -> None:
        for lot in self.lots:
            if lot.exit_queued_at is not None or self.atr14 is None:
                continue
            lot.highest_close = max(lot.highest_close, bar.close)
            candidate_stop = lot.highest_close - self.atr14 * V2A_MULTIPLIER
            lot.ratchet_stop = candidate_stop if lot.ratchet_stop is None else max(lot.ratchet_stop, candidate_stop)
            pnl = money(estimated_net(lot.quantity, bar.close) - lot.cost)
            if bar.close <= lot.ratchet_stop and pnl > 0:
                lot.exit_queued_at = bar.open_time
                self._count_trigger("BASE_ATR_TRAIL_1_50")
                continue
            if factor is not None and bar.open_time.hour == 23 and pnl > 0:
                flags = self._reversal_flags(lot, bar)
                confirmed = sum(flags.values()) >= 2 if factor == "CONSENSUS_2_OF_4" else flags[factor]
                if confirmed:
                    lot.exit_queued_at = bar.open_time
                    self._count_trigger(factor)

    def _reversal_flags(self, lot: Lot, bar: Bar) -> dict[str, bool]:
        ema_slope = (
            len(self.ema20_history) >= 6
            and self.ema20_history[-1][1] <= self.ema20_history[0][1]
        )
        close_below_ema5 = self.ema5 is not None and bar.close < self.ema5
        atr_reversal = self.atr14 is not None and lot.highest_close - bar.close >= self.atr14
        donchian = False
        if len(self.daily_points) >= 6:
            prior_five = list(self.daily_points)[-6:-1]
            prior_close = list(self.daily_points)[-2].close
            donchian = bar.close < min(point.low for point in prior_five) and bar.close < prior_close
        return {
            "EMA20_SLOPE_NONPOSITIVE": ema_slope,
            "CLOSE_BELOW_EMA5": close_below_ema5,
            "ATR1_REVERSAL": atr_reversal,
            "DONCHIAN5_NEGATIVE_MOMENTUM": donchian,
        }

    def _volatility_state(self) -> tuple[D, str] | None:
        if len(self.atr_window) < 60 or self.atr14 is None:
            return None
        values = list(self.atr_window)
        p25 = decimal_quantile(values, D("0.25"))
        p75 = decimal_quantile(values, D("0.75"))
        effective = min(max(self.atr14, p25), p75)
        rank = D(sum(value <= self.atr14 for value in values)) / D(len(values))
        if rank <= D("0.25"):
            bucket = "LOW"
        elif rank >= D("0.75"):
            bucket = "HIGH"
        else:
            bucket = "NORMAL"
        return effective, bucket

    def _queue_v2b(self, bar: Bar) -> None:
        state = self._volatility_state()
        if state is None:
            return
        effective_atr, bucket = state
        self.bucket_counts[bucket] += 1
        multiplier = V2B_PROFILES[self.profile][bucket]
        for lot in self.lots:
            if lot.exit_queued_at is not None:
                continue
            lot.highest_close = max(lot.highest_close, bar.close)
            candidate_stop = lot.highest_close - effective_atr * multiplier
            lot.ratchet_stop = candidate_stop if lot.ratchet_stop is None else max(lot.ratchet_stop, candidate_stop)
            pnl = money(estimated_net(lot.quantity, bar.close) - lot.cost)
            if bar.close <= lot.ratchet_stop and pnl > 0:
                lot.exit_queued_at = bar.open_time
                self._count_trigger(f"V2B_{self.profile}_{bucket}")

    def _signal(self, bar: Bar) -> bool:
        if len(self.close_history) < 25 or len(self.ema20_history) < 6 or self.ema20 is None:
            return False
        close = self.close_history[-1][1]
        return (
            bar.open_time.hour == 23
            and close > self.ema20
            and self.ema20 > self.ema20_history[0][1]
            and close > self.close_history[0][1]
        )

    def _entry_lifecycle(self, bar: Bar) -> None:
        if self.armed_at is not None and bar.open_time >= self.arm_expires_at:
            self.armed_at = None
            self.arm_expires_at = None
        if self.armed_at is not None and bar.open_time > self.armed_at and self._signal(bar):
            open_cost = LOT_COST * D(len(self.lots))
            if open_cost + LOT_COST > self.cap:
                self.blocked_count += 1
            else:
                self.pending_signal = bar.open_time
                self.pending_atr = self.atr14
                self.last_entry_signal = bar.open_time
            self.armed_at = None
            self.arm_expires_at = None
        cooldown_passed = (
            self.last_entry_signal is None
            or bar.open_time >= self.last_entry_signal + timedelta(days=7)
        )
        if self.armed_at is None and cooldown_passed:
            self.armed_at = bar.open_time
            self.arm_expires_at = bar.open_time + timedelta(days=30)

    def _track(self, bar: Bar) -> None:
        open_cost = LOT_COST * D(len(self.lots))
        unrealized = money(sum((estimated_net(lot.quantity, bar.close) - lot.cost for lot in self.lots), ZERO))
        equity = money(self.cap + self.realized + unrealized)
        self.peak_equity = max(self.peak_equity, equity)
        drawdown = (self.peak_equity - equity) / self.peak_equity if self.peak_equity > 0 else ZERO
        self.max_drawdown = max(self.max_drawdown, drawdown)
        self.max_open_cost = max(self.max_open_cost, open_cost)
        self.utilization_sum += open_cost / self.cap
        self.utilization_points += 1

    def _count_trigger(self, name: str) -> None:
        self.exit_trigger_counts[name] = self.exit_trigger_counts.get(name, 0) + 1

    def result(self, final_bar: Bar, start: datetime, end: datetime) -> dict:
        unrealized = money(sum((estimated_net(lot.quantity, final_bar.close) - lot.cost for lot in self.lots), ZERO))
        realized = money(self.realized)
        open_ages = sorted((end - lot.fill_time).total_seconds() / 3600 for lot in self.lots)
        utilization = self.utilization_sum / D(self.utilization_points)
        return {
            "mode": self.mode,
            "factor": self.factor,
            "profile": self.profile,
            "start": start.isoformat(),
            "end_exclusive": end.isoformat(),
            "reference_cap_usdt": str(money(self.cap)),
            "realized_usdt": str(realized),
            "unrealized_usdt": str(unrealized),
            "total_pnl_usdt": str(money(realized + unrealized)),
            "max_drawdown_pct": str((self.max_drawdown * D(100)).quantize(D("0.000001"), rounding=ROUND_HALF_UP)),
            "buy_count": self.buy_count,
            "sell_count": self.sell_count,
            "open_lots": len(self.lots),
            "blocked_entries": self.blocked_count,
            "deferred_exits": self.deferred_count,
            "ending_open_cost_usdt": str(money(LOT_COST * D(len(self.lots)))),
            "max_open_cost_usdt": str(money(self.max_open_cost)),
            "avg_utilization_pct": str((utilization * D(100)).quantize(D("0.000001"), rounding=ROUND_HALF_UP)),
            "peak_utilization_pct": str(((self.max_open_cost / self.cap) * D(100)).quantize(D("0.000001"), rounding=ROUND_HALF_UP)),
            "median_hold_hours": percentile(self.hold_hours, 0.5),
            "p90_hold_hours": percentile(self.hold_hours, 0.9),
            "median_open_age_hours": percentile(open_ages, 0.5),
            "p90_open_age_hours": percentile(open_ages, 0.9),
            "turnover_usdt": str(money(self.total_sell_proceeds)),
            "exit_trigger_counts": self.exit_trigger_counts,
            "v2b_bucket_evaluation_counts": self.bucket_counts if self.mode == "v2b" else None,
        }


def env_value(name: str) -> str:
    value = os.getenv(name)
    if value:
        return value
    if sys.platform == "win32":
        command = [
            "powershell",
            "-NoProfile",
            "-Command",
            f"[Environment]::GetEnvironmentVariable('{name}','User')",
        ]
        value = subprocess.run(command, capture_output=True, text=True, check=True).stdout.strip()
    if not value:
        raise ResearchReject("DATA_REJECT", f"missing required local setting {name}")
    return value


def fetch_rows(cutoff: datetime) -> str:
    remote_script = r'''set -euo pipefail
ENV_FILE=/home/ubuntu/.env.trading.secrets
read_env() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }
URL=$(read_env SPRING_DATASOURCE_URL)
USER=$(read_env SPRING_DATASOURCE_USERNAME)
PASS=$(read_env SPRING_DATASOURCE_PASSWORD)
JDBC=${URL#jdbc:mysql://}
HOSTPORT=${JDBC%%/*}
DBPATH=${JDBC#*/}
DB=${DBPATH%%\?*}
HOST=${HOSTPORT%%:*}
PORT=${HOSTPORT##*:}
if [ "$HOST" = "$PORT" ]; then PORT=3306; fi
MYSQL_PWD="$PASS" mysql --batch --raw --skip-column-names -h "$HOST" -P "$PORT" -u "$USER" "$DB" -e "SELECT DATE_FORMAT(open_time,'%Y-%m-%dT%H:%i:%s'),DATE_FORMAT(close_time,'%Y-%m-%dT%H:%i:%s'),open_price,high_price,low_price,close_price,volume FROM md_kline WHERE source='okx' AND symbol='BTCUSDT' AND interval_code='1h' AND open_time >= '2019-01-01 00:00:00' AND close_time <= '__CUTOFF__' ORDER BY open_time"
'''.replace("__CUTOFF__", cutoff.strftime("%Y-%m-%d %H:%M:%S"))
    encoded = base64.b64encode(remote_script.encode()).decode()
    remote_command = f"printf '%s' '{encoded}' | base64 -d | bash -s"
    process = subprocess.run(
        ["ssh", "-i", env_value("AGORA_SSH_KEY"), env_value("AGORA_SSH_HOST"), remote_command],
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    if process.returncode:
        raise ResearchReject("DATA_REJECT", f"read-only server export failed: {process.stderr.strip()}")
    return process.stdout


def parse_rows(raw: str) -> list[Bar]:
    bars: list[Bar] = []
    for row_number, row in enumerate(csv.reader(raw.splitlines(), delimiter="\t"), start=1):
        if not row:
            continue
        if len(row) != 7:
            raise ResearchReject("DATA_REJECT", f"row {row_number} has {len(row)} fields")
        try:
            bar = Bar(datetime.fromisoformat(row[0]), datetime.fromisoformat(row[1]), *map(D, row[2:]))
        except Exception as error:
            raise ResearchReject("DATA_REJECT", f"row {row_number} parse error: {error}") from error
        bars.append(bar)
    if not bars:
        raise ResearchReject("DATA_REJECT", "empty dataset")
    seen: set[datetime] = set()
    for index, bar in enumerate(bars):
        if bar.open_time in seen:
            raise ResearchReject("DATA_REJECT", f"duplicate open time {bar.open_time.isoformat()}")
        seen.add(bar.open_time)
        if bar.open_time.minute or bar.open_time.second or bar.open_time.microsecond:
            raise ResearchReject("DATA_REJECT", f"off-grid bar {bar.open_time.isoformat()}")
        if bar.close_time != bar.open_time + timedelta(hours=1):
            raise ResearchReject("DATA_REJECT", f"non-one-hour bar {bar.open_time.isoformat()}")
        if min(bar.open, bar.high, bar.low, bar.close) <= 0 or bar.volume < 0:
            raise ResearchReject("DATA_REJECT", f"invalid numeric value {bar.open_time.isoformat()}")
        if bar.high < max(bar.open, bar.close) or bar.low > min(bar.open, bar.close) or bar.high < bar.low:
            raise ResearchReject("DATA_REJECT", f"invalid OHLC {bar.open_time.isoformat()}")
        if index and bar.open_time != bars[index - 1].open_time + timedelta(hours=1):
            raise ResearchReject(
                "DATA_REJECT",
                f"hourly gap {bars[index - 1].open_time.isoformat()} -> {bar.open_time.isoformat()}",
            )
    return bars


def data_hash(bars: Iterable[Bar]) -> str:
    digest = hashlib.sha256()
    for bar in bars:
        digest.update((bar.canonical() + "\n").encode())
    return digest.hexdigest()


def simulate(
    bars: list[Bar],
    window: tuple[datetime, datetime],
    mode: str,
    *,
    factor: str | None = None,
    profile: str | None = None,
    cap: D = REFERENCE_CAP,
) -> dict:
    start, end = window
    volatility_start = start - timedelta(days=365)
    entry_warmup_start = start - timedelta(days=90)
    warmup_start = volatility_start if mode == "v2b" else entry_warmup_start
    selected = [bar for bar in bars if warmup_start <= bar.open_time and bar.close_time <= end]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise ResearchReject("DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}")
    engine = Engine(mode, factor=factor, profile=profile, cap=cap)
    for bar in selected:
        if bar.open_time < start:
            if mode == "v2b" and bar.open_time < entry_warmup_start:
                engine.volatility_warmup(bar)
            else:
                engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


CHECKPOINT_FIELDS = (
    "realized_usdt",
    "unrealized_usdt",
    "total_pnl_usdt",
    "max_drawdown_pct",
    "median_hold_hours",
    "p90_hold_hours",
    "buy_count",
    "sell_count",
    "open_lots",
    "blocked_entries",
    "avg_utilization_pct",
    "turnover_usdt",
)


def checkpoint_tuple(result: dict) -> tuple:
    return tuple(result[field] for field in CHECKPOINT_FIELDS)


EXPECTED = {
    "v1_design": (
        "169.89846767", "-79.12049441", "90.77797326", "29.530448", 126.0, 1818.6,
        100, 95, 5, 3, "34.364819", "3019.89846767",
    ),
    "v1_validation": (
        "89.41118307", "-3.20820121", "86.20298186", "7.121498", 182.5, 1418.3,
        51, 50, 1, 0, "21.632695", "1589.41118307",
    ),
    "v2a_validation": (
        "116.45914729", "-3.20820121", "113.25094608", "8.945793", 401.0, 1846.6,
        51, 50, 1, 0, "29.008208", "1616.45914729",
    ),
    "v2b_TURNOVER": (
        "59.93271313", "0E-8", "59.93271313", "5.177908", 179.0, 891.0,
        51, 51, 0, 0, "14.190834", "1589.93271313",
    ),
    "v2b_BALANCED": (
        "71.44693976", "0E-8", "71.44693976", "5.257247", 209.0, 1263.0,
        51, 51, 0, 0, "15.785910", "1601.44693976",
    ),
    "v2b_TREND": (
        "71.52625635", "0E-8", "71.52625635", "7.302341", 256.0, 1451.0,
        51, 51, 0, 0, "20.450068", "1601.52625635",
    ),
}
EXPECTED_V2B_WINS = {
    "TURNOVER": (2, 1),
    "BALANCED": (1, 0),
    "TREND": (1, 0),
}


def dec(result: dict, field: str) -> D:
    return D(result[field])


def reproduce_checkpoints(bars: list[Bar]) -> dict:
    v1_design = simulate(bars, DESIGN, "v1")
    v1_validation = simulate(bars, VALIDATION, "v1")
    v1_folds = {name: simulate(bars, window, "v1") for name, window in FOLDS.items()}
    v2a_design = simulate(bars, DESIGN, "v2a")
    v2a_validation = simulate(bars, VALIDATION, "v2a")
    actual = {
        "v1_design": checkpoint_tuple(v1_design),
        "v1_validation": checkpoint_tuple(v1_validation),
        "v2a_validation": checkpoint_tuple(v2a_validation),
    }
    v2b: dict[str, dict] = {}
    mismatches: list[dict] = []
    for key in ("v1_design", "v1_validation", "v2a_validation"):
        if actual[key] != EXPECTED[key]:
            mismatches.append({"checkpoint": key, "expected": EXPECTED[key], "actual": actual[key]})
    for profile in V2B_PROFILES:
        validation = simulate(bars, VALIDATION, "v2b", profile=profile)
        folds = {name: simulate(bars, window, "v2b", profile=profile) for name, window in FOLDS.items()}
        total_wins = sum(dec(folds[name], "total_pnl_usdt") > dec(v1_folds[name], "total_pnl_usdt") for name in FOLDS)
        hold_wins = sum(
            folds[name]["median_hold_hours"] is not None
            and v1_folds[name]["median_hold_hours"] is not None
            and folds[name]["median_hold_hours"] < v1_folds[name]["median_hold_hours"]
            for name in FOLDS
        )
        checkpoint_key = f"v2b_{profile}"
        if checkpoint_tuple(validation) != EXPECTED[checkpoint_key]:
            mismatches.append(
                {"checkpoint": checkpoint_key, "expected": EXPECTED[checkpoint_key], "actual": checkpoint_tuple(validation)}
            )
        if (total_wins, hold_wins) != EXPECTED_V2B_WINS[profile]:
            mismatches.append(
                {
                    "checkpoint": f"{checkpoint_key}_annual_wins",
                    "expected": EXPECTED_V2B_WINS[profile],
                    "actual": (total_wins, hold_wins),
                }
            )
        v2b[profile] = {
            "validation": validation,
            "folds": folds,
            "annual_total_wins": total_wins,
            "annual_median_hold_wins": hold_wins,
        }
    if mismatches:
        raise ResearchReject("BASELINE_PARITY_REJECT", mismatches)
    return {
        "v1": {"design": v1_design, "validation": v1_validation, "folds": v1_folds},
        "v2a": {"design": v2a_design, "validation": v2a_validation},
        "v2b": v2b,
    }


def candidate_gates(result: dict, v1: dict, v2a: dict, total_wins: int, hold_wins: int) -> dict[str, bool]:
    return {
        "validation_realized_at_least_v1": dec(result, "realized_usdt") >= dec(v1, "realized_usdt"),
        "validation_total_at_least_v1": dec(result, "total_pnl_usdt") >= dec(v1, "total_pnl_usdt"),
        "validation_unrealized_no_worse": dec(result, "unrealized_usdt") >= dec(v1, "unrealized_usdt"),
        "validation_drawdown_within_2pp": dec(result, "max_drawdown_pct") <= dec(v1, "max_drawdown_pct") + D("2.0"),
        "validation_median_hold_no_worse": (
            result["median_hold_hours"] is not None
            and v1["median_hold_hours"] is not None
            and result["median_hold_hours"] <= v1["median_hold_hours"]
        ),
        "validation_p90_hold_no_worse": (
            result["p90_hold_hours"] is not None
            and v1["p90_hold_hours"] is not None
            and result["p90_hold_hours"] <= v1["p90_hold_hours"]
        ),
        "validation_total_retains_90pct_v2a": dec(result, "total_pnl_usdt") >= dec(v2a, "total_pnl_usdt") * D("0.90"),
        "annual_total_wins_at_least_3_of_5": total_wins >= 3,
        "annual_median_hold_wins_at_least_3_of_5": hold_wins >= 3,
    }


def source_hash() -> str:
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def freeze_hash(data_sha: str, runner_sha: str, candidate: str) -> str:
    payload = {
        "research_identity": RESEARCH_IDENTITY,
        "selection_data_sha256": data_sha,
        "runner_sha256": runner_sha,
        "candidate": candidate,
    }
    return hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def run_preselect(output: Path) -> dict:
    bars = parse_rows(fetch_rows(SELECTION_CUTOFF))
    digest = data_hash(bars)
    if len(bars) != SELECTION_ROWS or digest != SELECTION_SHA256:
        raise ResearchReject(
            "DATA_REJECT",
            {
                "expected_rows": SELECTION_ROWS,
                "actual_rows": len(bars),
                "expected_sha256": SELECTION_SHA256,
                "actual_sha256": digest,
            },
        )
    baselines = reproduce_checkpoints(bars)
    v1_validation = baselines["v1"]["validation"]
    v2a_validation = baselines["v2a"]["validation"]
    candidates: list[dict] = []
    for factor in FACTORS:
        design = simulate(bars, DESIGN, "v2c", factor=factor)
        validation = simulate(bars, VALIDATION, "v2c", factor=factor)
        folds = {name: simulate(bars, window, "v2c", factor=factor) for name, window in FOLDS.items()}
        total_wins = sum(
            dec(folds[name], "total_pnl_usdt") > dec(baselines["v1"]["folds"][name], "total_pnl_usdt")
            for name in FOLDS
        )
        hold_wins = sum(
            folds[name]["median_hold_hours"] is not None
            and baselines["v1"]["folds"][name]["median_hold_hours"] is not None
            and folds[name]["median_hold_hours"] < baselines["v1"]["folds"][name]["median_hold_hours"]
            for name in FOLDS
        )
        gates = candidate_gates(validation, v1_validation, v2a_validation, total_wins, hold_wins)
        candidates.append(
            {
                "factor": factor,
                "design": design,
                "validation": validation,
                "folds": folds,
                "annual_total_wins": total_wins,
                "annual_median_hold_wins": hold_wins,
                "gates": gates,
                "pass": all(gates.values()),
            }
        )
    qualified = [candidate for candidate in candidates if candidate["pass"]]
    qualified.sort(
        key=lambda candidate: (
            dec(candidate["validation"], "total_pnl_usdt"),
            -dec(candidate["validation"], "max_drawdown_pct"),
            -D(str(candidate["validation"]["p90_hold_hours"])),
            -D(str(candidate["validation"]["median_hold_hours"])),
        ),
        reverse=True,
    )
    selected = qualified[0] if qualified else None
    runner_sha = source_hash()
    result = {
        "status": "CANDIDATE_FROZEN" if selected else "NO_CANDIDATE",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "selection_data_rows": len(bars),
        "selection_data_first_open": bars[0].open_time.isoformat(),
        "selection_data_last_close": bars[-1].close_time.isoformat(),
        "selection_data_sha256": digest,
        "runner_sha256": runner_sha,
        "data_quality": "PASS",
        "baseline_parity": "PASS",
        "oos_opened": False,
        "baselines": baselines,
        "candidates": candidates,
        "qualified_count": len(qualified),
        "selected": selected,
        "one_slot_overlay": None,
    }
    if selected:
        candidate = selected["factor"]
        result["frozen_candidate_key"] = candidate
        result["freeze_sha256"] = freeze_hash(digest, runner_sha, candidate)
        result["one_slot_overlay"] = {
            "design": simulate(bars, DESIGN, "v2c", factor=candidate, cap=LOT_COST),
            "validation": simulate(bars, VALIDATION, "v2c", factor=candidate, cap=LOT_COST),
        }
    write_json(output, result)
    return result


def oos_gates(candidate: dict, v1: dict, v2a: dict) -> dict[str, bool]:
    return {
        "oos_realized_at_least_v1": dec(candidate, "realized_usdt") >= dec(v1, "realized_usdt"),
        "oos_total_at_least_v1": dec(candidate, "total_pnl_usdt") >= dec(v1, "total_pnl_usdt"),
        "oos_unrealized_no_worse": dec(candidate, "unrealized_usdt") >= dec(v1, "unrealized_usdt"),
        "oos_drawdown_within_2pp": dec(candidate, "max_drawdown_pct") <= dec(v1, "max_drawdown_pct") + D("2.0"),
        "oos_median_hold_no_worse": (
            candidate["median_hold_hours"] is not None
            and v1["median_hold_hours"] is not None
            and candidate["median_hold_hours"] <= v1["median_hold_hours"]
        ),
        "oos_p90_hold_no_worse": (
            candidate["p90_hold_hours"] is not None
            and v1["p90_hold_hours"] is not None
            and candidate["p90_hold_hours"] <= v1["p90_hold_hours"]
        ),
        "oos_total_retains_90pct_v2a": dec(candidate, "total_pnl_usdt") >= dec(v2a, "total_pnl_usdt") * D("0.90"),
    }


def run_oos(preselect_path: Path, cutoff: datetime, output: Path) -> dict:
    if output.exists():
        raise ResearchReject("OOS_SEAL_REJECT", f"output already exists: {output}")
    preselection = json.loads(preselect_path.read_text(encoding="utf-8"))
    if preselection.get("status") != "CANDIDATE_FROZEN":
        raise ResearchReject("OOS_SEAL_REJECT", "preselection did not freeze one candidate")
    if preselection.get("selection_data_sha256") != SELECTION_SHA256:
        raise ResearchReject("OOS_SEAL_REJECT", "selection data hash mismatch")
    runner_sha = source_hash()
    if preselection.get("runner_sha256") != runner_sha:
        raise ResearchReject("OOS_SEAL_REJECT", "runner changed after candidate freeze")
    candidate = preselection.get("frozen_candidate_key")
    if candidate not in FACTORS:
        raise ResearchReject("OOS_SEAL_REJECT", f"unknown candidate {candidate}")
    expected_freeze = freeze_hash(SELECTION_SHA256, runner_sha, candidate)
    if preselection.get("freeze_sha256") != expected_freeze:
        raise ResearchReject("OOS_SEAL_REJECT", "candidate freeze hash mismatch")
    if cutoff <= SELECTION_CUTOFF:
        raise ResearchReject("OOS_SEAL_REJECT", "OOS cutoff must be after 2025-01-01")
    bars = parse_rows(fetch_rows(cutoff))
    available_end = bars[-1].close_time
    window = (SELECTION_CUTOFF, available_end)
    v1 = simulate(bars, window, "v1")
    v2a = simulate(bars, window, "v2a")
    v2c = simulate(bars, window, "v2c", factor=candidate)
    gates = oos_gates(v2c, v1, v2a)
    result = {
        "status": "OUT_OF_SAMPLE_PASS" if all(gates.values()) else "OUT_OF_SAMPLE_FAIL",
        "research_identity": RESEARCH_IDENTITY,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
        "frozen_candidate_key": candidate,
        "freeze_sha256": expected_freeze,
        "oos_opened_once": True,
        "oos_requested_cutoff": cutoff.isoformat(),
        "oos_last_complete_close": available_end.isoformat(),
        "full_data_rows": len(bars),
        "full_data_sha256": data_hash(bars),
        "oos": {
            "v1_reference_250": v1,
            "v2a_reference_250": v2a,
            "v2c_reference_250": v2c,
            "v2c_one_slot_30": simulate(bars, window, "v2c", factor=candidate, cap=LOT_COST),
            "gates": gates,
        },
    }
    write_json(output, result)
    return result


def write_json(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def summary(result: dict) -> dict:
    omitted = {"baselines", "candidates", "selected", "one_slot_overlay", "oos"}
    return {key: value for key, value in result.items() if key not in omitted}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="stage", required=True)
    preselect = subparsers.add_parser("preselect")
    preselect.add_argument("--output", type=Path, required=True)
    oos = subparsers.add_parser("oos")
    oos.add_argument("--preselect", type=Path, required=True)
    oos.add_argument("--cutoff", required=True)
    oos.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output: Path = args.output
    try:
        if args.stage == "preselect":
            result = run_preselect(output)
        else:
            cutoff = datetime.fromisoformat(args.cutoff)
            if cutoff.tzinfo is not None:
                cutoff = cutoff.astimezone(UTC).replace(tzinfo=None)
            result = run_oos(args.preselect, cutoff, output)
    except ResearchReject as reject:
        result = {
            "status": reject.status,
            "research_identity": RESEARCH_IDENTITY,
            "authorization": "RESEARCH_ONLY_NOT_SHADOW_OR_LIVE",
            "detail": reject.detail,
        }
        # The OOS one-open guard must never mutate an already-created result.
        if not (args.stage == "oos" and output.exists()):
            write_json(output, result)
    print(json.dumps(summary(result), ensure_ascii=False, indent=2))
    return 0 if result["status"] in ("CANDIDATE_FROZEN", "OUT_OF_SAMPLE_PASS") else 2


if __name__ == "__main__":
    raise SystemExit(main())
