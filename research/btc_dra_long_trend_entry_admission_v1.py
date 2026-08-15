#!/usr/bin/env python3
"""Causal DRA V1 long-trend entry-admission runner without outcome gates."""

from __future__ import annotations

from collections import deque
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

import btc_dra_equal_capital_capacity_v1 as capacity


base = capacity.base
D = Decimal
AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
IDENTITY = "BTC_DRA_LONG_TREND_ENTRY_ADMISSION_RUNNER_V1"
FEATURE = "CURRENT_COMPLETE_UTC_DAY_CLOSE_ABOVE_PRIOR_200_COMPLETE_DAY_SMA"
PRIOR_DAY_COUNT = 200
SLOT_CAPACITY_USDT = D("240")
INITIAL_EQUITY_USDT = D("250")


class LongTrendEntryAdmissionEngine(capacity.EqualCapitalCapacityEngine):
    """Veto only parent DRA signals below a causal prior-200-day trend level."""

    def __init__(self) -> None:
        super().__init__(
            slot_capacity_usdt=SLOT_CAPACITY_USDT,
            initial_equity_usdt=INITIAL_EQUITY_USDT,
        )
        self.prior_daily_closes: deque[D] = deque(maxlen=PRIOR_DAY_COUNT)
        self.current_complete_day_close: D | None = None
        self.current_prior_sma200: D | None = None
        self.parent_signal_count = 0
        self.admitted_signal_count = 0
        self.vetoed_signal_count = 0
        self.hard_inception_fallback_admit_count = 0

    def _update_trend_feature(self, bar: base.Bar) -> None:
        if bar.open_time.hour != 23:
            return
        self.current_complete_day_close = bar.close
        self.current_prior_sma200 = (
            sum(self.prior_daily_closes, base.ZERO) / D(PRIOR_DAY_COUNT)
            if len(self.prior_daily_closes) == PRIOR_DAY_COUNT
            else None
        )
        self.prior_daily_closes.append(bar.close)

    def feature_warmup(self, bar: base.Bar) -> None:
        """Warm only the trend feature before the unchanged 90-day DRA warmup."""

        self._update_trend_feature(bar)

    def _indicators(self, bar: base.Bar) -> None:
        super()._indicators(bar)
        self._update_trend_feature(bar)

    def _signal(self, bar: base.Bar) -> bool:
        parent_signal = super()._signal(bar)
        if not parent_signal:
            return False
        self.parent_signal_count += 1
        if self.current_prior_sma200 is None:
            self.admitted_signal_count += 1
            self.hard_inception_fallback_admit_count += 1
            return True
        admitted = (
            self.current_complete_day_close is not None
            and self.current_complete_day_close > self.current_prior_sma200
        )
        if admitted:
            self.admitted_signal_count += 1
        else:
            self.vetoed_signal_count += 1
        return admitted

    def result(
        self, final_bar: base.Bar, start: datetime, end: datetime
    ) -> dict[str, Any]:
        result = super().result(final_bar, start, end)
        result.update(
            {
                "runner_identity": IDENTITY,
                "admission_feature": FEATURE,
                "prior_complete_day_count": PRIOR_DAY_COUNT,
                "parent_signal_count": self.parent_signal_count,
                "admitted_signal_count": self.admitted_signal_count,
                "vetoed_signal_count": self.vetoed_signal_count,
                "hard_inception_fallback_admit_count": (
                    self.hard_inception_fallback_admit_count
                ),
                "admission_accounting_reconciles": self.parent_signal_count
                == self.admitted_signal_count + self.vetoed_signal_count,
            }
        )
        return result


def simulate_trend_admission(
    bars: list[base.Bar], window: tuple[datetime, datetime]
) -> dict[str, Any]:
    """Run one frozen window; callers own data integrity and economic gates."""

    start, end = window
    feature_warmup_start = start - timedelta(days=220)
    dra_warmup_start = start - timedelta(days=90)
    selected = [
        bar
        for bar in bars
        if feature_warmup_start <= bar.open_time and bar.close_time <= end
    ]
    trading = [bar for bar in selected if bar.open_time >= start]
    if not trading:
        raise base.ResearchReject(
            "DATA_REJECT", f"no bars for {start.isoformat()}..{end.isoformat()}"
        )
    engine = LongTrendEntryAdmissionEngine()
    for bar in selected:
        if bar.open_time < dra_warmup_start:
            engine.feature_warmup(bar)
        elif bar.open_time < start:
            engine.warmup(bar)
        else:
            engine.step(bar)
    return engine.result(trading[-1], start, end)


def paired_deltas(
    parent: dict[str, Any], candidate: dict[str, Any]
) -> dict[str, Any]:
    """Return paired equal-equity deltas without a performance decision."""

    return capacity.equal_capital_deltas(parent, candidate)
