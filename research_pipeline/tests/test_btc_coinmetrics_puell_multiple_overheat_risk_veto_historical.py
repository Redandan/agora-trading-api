from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
import importlib.util
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "research" / "btc_coinmetrics_puell_multiple_overheat_risk_veto_historical.py"


@dataclass(frozen=True)
class Point:
    close_time: datetime
    close: Decimal


def load_runner():
    spec = importlib.util.spec_from_file_location("puell_runner_test", RUNNER)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_puell_uses_inclusive_365_days_and_strict_overheat_threshold() -> None:
    runner = load_runner()
    start = date(2019, 1, 1)
    issuance = {start + timedelta(days=index): Decimal("1") for index in range(366)}
    daily = [
        Point(
            close_time=datetime.combine(start + timedelta(days=index + 1), datetime.min.time()),
            close=Decimal("1") if index < 364 else (Decimal("4") if index == 364 else Decimal("5")),
        )
        for index in range(366)
    ]

    targets, feature, observations = runner.build_puell_targets(issuance, daily)

    first_clock = datetime(2020, 1, 1)
    second_clock = datetime(2020, 1, 2)
    assert observations[0][0] == first_clock
    assert observations[0][1] == Decimal("4") / (Decimal("368") / Decimal("365"))
    assert targets[first_clock] is True
    assert observations[1][1] > Decimal("4")
    assert targets[second_clock] is False
    assert feature["formula_days"] == 365
    assert feature["overheat_threshold"] == "4.00000000"


def test_continuous_correlation_rejects_degenerate_input() -> None:
    runner = load_runner()
    try:
        runner.pearson([Decimal("1"), Decimal("1")], [Decimal("2"), Decimal("3")])
    except runner.ResearchReject as error:
        assert str(error) == "METRIC_REJECT:PEARSON_DEGENERATE"
    else:
        raise AssertionError("expected degenerate correlation to fail closed")
