#!/usr/bin/env python3
"""Deterministic pre-economic and matched-capital FOMC event-day screen."""

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
from decimal import Decimal
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
RESEARCH_ROOT = REPO_ROOT / "research"
if str(RESEARCH_ROOT) not in sys.path:
    sys.path.insert(0, str(RESEARCH_ROOT))

import btc_deribit_last_friday_monthly_options_expiry_risk_veto_historical as base


D = Decimal
ZERO = D("0")
ONE = D("1")
HUNDRED = D("100")
DESIGN = (datetime(2020, 1, 1), datetime(2023, 1, 1))
VALIDATION = (datetime(2023, 1, 1), datetime(2025, 1, 1))
ANNUAL = {
    str(year): (datetime(year, 1, 1), datetime(year + 1, 1, 1))
    for year in range(2020, 2025)
}
EXPERIMENT_ID = "btc-fomc-scheduled-announcement-day-risk-veto-historical-v1"
HYPOTHESIS_ID = "btc-fomc-scheduled-announcement-day-risk-veto-v1"
EXPECTED_MANIFEST_TYPE = (
    "BTC_FOMC_SCHEDULED_ANNOUNCEMENT_DAY_RISK_VETO_HISTORICAL_MANIFEST_V1"
)
EXPECTED_BTC_SHA256 = "e436a5a2b093365886464dd3e471cc5cd55c54bd2c06ceaa898ac218c45436dd"
EXPECTED_BTC_ROWS = 52_608
EXPECTED_PRIOR_SHA256 = "ee57b53c4e3582c55ed9c4c77155ff3adc44b80f3556d4ab0be1c84cf3a9d630"
EXPECTED_CALENDAR_SHA256 = "e122edcec53d93ce838e915b351564c422da11c0b6afc15f5271b587d32e5d2c"
EXPECTED_HYPOTHESIS_SHA256 = "a097682c028f3eea6daefb9cf1193d17e8212aaf22b0a1580ce5f96d24b04e26"
EXPECTED_EVENT_DATES = (
    "2020-01-29", "2020-04-29", "2020-06-10", "2020-07-29",
    "2020-09-16", "2020-11-05", "2020-12-16", "2021-01-27",
    "2021-03-17", "2021-04-28", "2021-06-16", "2021-07-28",
    "2021-09-22", "2021-11-03", "2021-12-15", "2022-01-26",
    "2022-03-16", "2022-05-04", "2022-06-15", "2022-07-27",
    "2022-09-21", "2022-11-02", "2022-12-14", "2023-02-01",
    "2023-03-22", "2023-05-03", "2023-06-14", "2023-07-26",
    "2023-09-20", "2023-11-01", "2023-12-13", "2024-01-31",
    "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31",
    "2024-09-18", "2024-11-07", "2024-12-18",
)
EVENT_DATES = frozenset(datetime.fromisoformat(value).date() for value in EXPECTED_EVENT_DATES)

EXPECTED_SOURCE_PATHS = {
    "FROZEN_DIRECT_FOMC_PRE_ECONOMIC_AND_ECONOMIC_RUNNER": "research/btc_fomc_scheduled_announcement_day_risk_veto_historical.py",
    "FROZEN_MONTHLY_EVENT_RUNNER_REFERENCE": "research/btc_deribit_last_friday_monthly_options_expiry_risk_veto_historical.py",
    "FROZEN_CALENDAR_ECONOMIC_SIMULATION_KERNEL": "research/btc_turn_of_month_last_day_plus_three_historical.py",
    "FROZEN_PREDICTIVE_STATISTICS_KERNEL": "research/btc_fred_on_rrp_drawdown_liquidity_support_historical.py",
    "FROZEN_H1_PARSER_AND_DATA_INTEGRITY_REFERENCE": "research/btc_dra_reversal_confirmed_exit_v2c.py",
    "FROZEN_PASSIVE_BENCHMARK_AND_PATH_METRIC_REFERENCE": "research/btc_monthly_12m_time_series_momentum_historical.py",
    "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR": "research_pipeline/examples/btc-fomc-scheduled-announcement-day-risk-veto-primary-prior.v1.json",
    "FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR": "research_pipeline/examples/btc-fomc-regular-meeting-decision-dates-2020-2024.v1.json",
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": "research_pipeline/examples/btc-fomc-scheduled-announcement-day-risk-veto-v1.hypothesis.json",
}
EXPECTED_SOURCE_HASHES = {
    "FROZEN_MONTHLY_EVENT_RUNNER_REFERENCE": "049d2680ead6355d62406ed08c9655e56467998754dafbbe6173ef3f65d84d35",
    "FROZEN_CALENDAR_ECONOMIC_SIMULATION_KERNEL": "a23699034fe081a959010543b0a0c43a8fd12b04c0a495dd9a89bc1642e3ae0b",
    "FROZEN_PREDICTIVE_STATISTICS_KERNEL": "2f958e0b0d481acbd4610cdc234d9ddc069713984c46c0690720ebe1c0192fc9",
    "FROZEN_H1_PARSER_AND_DATA_INTEGRITY_REFERENCE": "7b17f1acc591b571ed238b5d8141d0bc2b2ebd4bde860273db23c17398e05e37",
    "FROZEN_PASSIVE_BENCHMARK_AND_PATH_METRIC_REFERENCE": "6682a56f08f70435064e4fdd2394350e418f9a2f5cd76c02ec936c6ee8802c1b",
    "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR": EXPECTED_PRIOR_SHA256,
    "FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR": EXPECTED_CALENDAR_SHA256,
    "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS": EXPECTED_HYPOTHESIS_SHA256,
}

PREDICTIVE_GATES = (
    "minimum_evaluable_matched_weekday_episodes",
    "exact_scheduled_fomc_event_count",
    "minimum_same_weekday_non_event_controls",
    "both_states_anchor_year_breadth",
    "control_median_return_at_least_25bp_higher",
    "control_negative_rate_at_least_5pp_lower",
    "one_sided_fisher_negative_rate_p_value_at_most_0_10",
    "control_median_path_drawdown_non_worse",
    "control_p75_path_drawdown_non_worse",
    "annual_median_return_direction_breadth",
    "annual_median_path_drawdown_direction_breadth",
    "top1_negative_event_loss_contribution_at_most_25pct",
    "top3_negative_event_loss_contribution_at_most_50pct",
)
ECONOMIC_GATES = (
    "design_normal_total_return_delta_gt_0",
    "design_stress_total_return_delta_gt_0",
    "design_normal_drawdown_non_worse",
    "design_normal_calmar_at_least_buy_hold",
    "design_normal_average_exposure_between_97pct_and_99pct",
    "design_normal_signal_evaluations_exactly_47",
    "design_normal_sell_trades_exactly_23",
    "validation_normal_total_return_delta_gt_0",
    "validation_stress_total_return_delta_gt_0",
    "validation_normal_drawdown_non_worse",
    "validation_stress_drawdown_non_worse",
    "validation_normal_calmar_at_least_buy_hold",
    "validation_normal_average_exposure_between_97pct_and_99pct",
    "validation_normal_signal_evaluations_exactly_33",
    "validation_normal_sell_trades_exactly_16",
    "normal_positive_annual_total_return_delta_at_least_4_of_5",
    "stress_positive_annual_total_return_delta_at_least_4_of_5",
    "normal_annual_drawdown_non_worse_than_buy_hold_5_of_5",
    "top_year_positive_total_return_delta_contribution_at_most_60pct",
    "validation_normal_p90_realized_lot_hold_at_most_840_hours",
    "validation_normal_terminal_oldest_lot_age_at_most_120_hours",
    "validation_normal_terminal_liquidation_adjusted_return_delta_gt_0",
    "validation_normal_terminal_liquidation_cost_at_most_0_5pp",
)


class ResearchReject(RuntimeError):
    pass


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def q(value: D) -> str:
    return format(value.quantize(D("0.00000001")), "f")


def is_event_day(value: datetime) -> bool:
    return value.date() in EVENT_DATES


def target_long(value: datetime) -> bool:
    return not is_event_day(value)


def expected_transition_times(start: datetime, end: datetime) -> list[datetime]:
    if start >= end or start.minute or start.second or start.microsecond:
        raise ResearchReject("POLICY_REJECT:INVALID_WINDOW")
    values: list[datetime] = []
    previous: bool | None = None
    current = start
    while current < end:
        target = target_long(current)
        if previous is None or target != previous:
            values.append(current)
            previous = target
        current += timedelta(hours=1)
    return values


def validate_calendar() -> dict[str, Any]:
    path = REPO_ROOT / EXPECTED_SOURCE_PATHS["FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR"]
    if sha256(path) != EXPECTED_CALENDAR_SHA256:
        raise ResearchReject("CALENDAR_REJECT:SHA256")
    document = json.loads(path.read_text(encoding="utf-8"))
    dates = tuple(item["date"] for item in document.get("events", []))
    design = [item for item in document.get("events", []) if item.get("window") == "DESIGN"]
    validation = [item for item in document.get("events", []) if item.get("window") == "VALIDATION"]
    if (
        document.get("status") != "FROZEN_BEFORE_BTC_EVENT_OUTCOME_ACCESS"
        or dates != EXPECTED_EVENT_DATES
        or len(set(dates)) != 39
        or len(design) != 23
        or len(validation) != 16
    ):
        raise ResearchReject("CALENDAR_REJECT:IDENTITY_OR_COUNTS")
    return document


def _percentile(values: list[D], probability: D) -> D | None:
    return base.predictive_kernel.shared.percentile(values, probability)


def _rate(count: int, total: int) -> D | None:
    return None if total == 0 else D(count) / D(total)


def predictive_evidence(
    bars: list[Any], window: tuple[datetime, datetime], *, label: str
) -> dict[str, Any]:
    event_weekdays = {
        value.weekday()
        for value in (datetime.fromisoformat(item) for item in EXPECTED_EVENT_DATES)
        if window[0].year <= value.year < window[1].year
    }
    if event_weekdays != {2, 3}:
        raise ResearchReject(f"CALENDAR_REJECT:WEEKDAY_SUPPORT:{label}")
    bars_by_open = {bar.open_time: bar for bar in bars}
    episodes: list[dict[str, Any]] = []
    current = window[0]
    while current < window[1]:
        if current.weekday() in event_weekdays:
            terminal_at = current + timedelta(hours=24)
            anchor = bars_by_open.get(current)
            terminal = bars_by_open.get(terminal_at)
            path = [bars_by_open.get(current + timedelta(hours=i)) for i in range(24)]
            if anchor is None or terminal is None or any(item is None for item in path):
                raise ResearchReject(f"DATA_REJECT:EVENT_PATH:{current.isoformat()}")
            peak = anchor.open
            drawdown = ZERO
            for bar in path:
                assert bar is not None
                peak = max(peak, bar.high)
                drawdown = max(drawdown, (peak - bar.low) / peak)
            episodes.append(
                {
                    "anchor_at": current.isoformat(),
                    "terminal_at": terminal_at.isoformat(),
                    "weekday": current.strftime("%A").upper(),
                    "state": "EVENT" if is_event_day(current) else "CONTROL",
                    "complete_day_return": q(terminal.open / anchor.open - ONE),
                    "intraday_peak_to_trough_max_drawdown": q(drawdown),
                }
            )
        current += timedelta(days=1)

    controls = [item for item in episodes if item["state"] == "CONTROL"]
    events = [item for item in episodes if item["state"] == "EVENT"]
    control_returns = [D(item["complete_day_return"]) for item in controls]
    event_returns = [D(item["complete_day_return"]) for item in events]
    control_dd = [D(item["intraday_peak_to_trough_max_drawdown"]) for item in controls]
    event_dd = [D(item["intraday_peak_to_trough_max_drawdown"]) for item in events]
    control_negative = sum(value < ZERO for value in control_returns)
    event_negative = sum(value < ZERO for value in event_returns)
    control_negative_rate = _rate(control_negative, len(controls))
    event_negative_rate = _rate(event_negative, len(events))
    fisher = base.predictive_kernel.one_sided_fisher_less(
        control_negative,
        len(controls) - control_negative,
        event_negative,
        len(events) - event_negative,
    )
    control_median_return = _percentile(control_returns, D("0.5"))
    event_median_return = _percentile(event_returns, D("0.5"))
    control_median_dd = _percentile(control_dd, D("0.5"))
    event_median_dd = _percentile(event_dd, D("0.5"))
    control_p75_dd = _percentile(control_dd, D("0.75"))
    event_p75_dd = _percentile(event_dd, D("0.75"))

    annual: dict[str, dict[str, str]] = {}
    return_wins = 0
    drawdown_wins = 0
    for year in range(window[0].year, window[1].year):
        prefix = str(year)
        cr = [D(item["complete_day_return"]) for item in controls if item["anchor_at"].startswith(prefix)]
        er = [D(item["complete_day_return"]) for item in events if item["anchor_at"].startswith(prefix)]
        cd = [D(item["intraday_peak_to_trough_max_drawdown"]) for item in controls if item["anchor_at"].startswith(prefix)]
        ed = [D(item["intraday_peak_to_trough_max_drawdown"]) for item in events if item["anchor_at"].startswith(prefix)]
        values = (
            _percentile(cr, D("0.5")), _percentile(er, D("0.5")),
            _percentile(cd, D("0.5")), _percentile(ed, D("0.5")),
        )
        if any(value is None for value in values):
            raise ResearchReject(f"DATA_REJECT:ANNUAL_EVENT_SUPPORT:{year}")
        cr_median, er_median, cd_median, ed_median = values
        assert cr_median is not None and er_median is not None
        assert cd_median is not None and ed_median is not None
        return_wins += cr_median > er_median
        drawdown_wins += cd_median <= ed_median
        annual[prefix] = {
            "control_median_return": q(cr_median),
            "event_median_return": q(er_median),
            "control_median_drawdown": q(cd_median),
            "event_median_drawdown": q(ed_median),
        }

    losses = sorted((-value for value in event_returns if value < ZERO), reverse=True)
    total_losses = sum(losses, ZERO)
    top1 = HUNDRED if total_losses == ZERO else losses[0] / total_losses * HUNDRED
    top3 = HUNDRED if total_losses == ZERO else sum(losses[:3], ZERO) / total_losses * HUNDRED
    years = window[1].year - window[0].year
    minimum_total = 300 if label == "design" else 200
    minimum_control = 270 if label == "design" else 180
    expected_events = 23 if label == "design" else 16
    minimum_annual_wins = 2
    gates = {
        "minimum_evaluable_matched_weekday_episodes": len(episodes) >= minimum_total,
        "exact_scheduled_fomc_event_count": len(events) == expected_events,
        "minimum_same_weekday_non_event_controls": len(controls) >= minimum_control,
        "both_states_anchor_year_breadth": len(annual) == years,
        "control_median_return_at_least_25bp_higher": control_median_return is not None and event_median_return is not None and control_median_return >= event_median_return + D("0.0025"),
        "control_negative_rate_at_least_5pp_lower": control_negative_rate is not None and event_negative_rate is not None and control_negative_rate <= event_negative_rate - D("0.05"),
        "one_sided_fisher_negative_rate_p_value_at_most_0_10": fisher is not None and fisher <= D("0.10"),
        "control_median_path_drawdown_non_worse": control_median_dd is not None and event_median_dd is not None and control_median_dd <= event_median_dd,
        "control_p75_path_drawdown_non_worse": control_p75_dd is not None and event_p75_dd is not None and control_p75_dd <= event_p75_dd,
        "annual_median_return_direction_breadth": return_wins >= minimum_annual_wins,
        "annual_median_path_drawdown_direction_breadth": drawdown_wins >= minimum_annual_wins,
        "top1_negative_event_loss_contribution_at_most_25pct": top1 <= D("25"),
        "top3_negative_event_loss_contribution_at_most_50pct": top3 <= D("50"),
    }
    if tuple(gates) != PREDICTIVE_GATES:
        raise ResearchReject("MANIFEST_REJECT:PREDICTIVE_GATE_DRIFT")
    return {
        "episodes": episodes,
        "statistics": {
            "episode_count": len(episodes),
            "same_weekday_non_event_control_count": len(controls),
            "scheduled_fomc_event_count": len(events),
            "control_median_return": None if control_median_return is None else q(control_median_return),
            "event_median_return": None if event_median_return is None else q(event_median_return),
            "control_negative_rate": None if control_negative_rate is None else q(control_negative_rate),
            "event_negative_rate": None if event_negative_rate is None else q(event_negative_rate),
            "one_sided_fisher_p_value": None if fisher is None else q(fisher),
            "control_median_drawdown": None if control_median_dd is None else q(control_median_dd),
            "event_median_drawdown": None if event_median_dd is None else q(event_median_dd),
            "control_p75_drawdown": None if control_p75_dd is None else q(control_p75_dd),
            "event_p75_drawdown": None if event_p75_dd is None else q(event_p75_dd),
            "annual_return_direction_wins": f"{return_wins}_of_{years}",
            "annual_drawdown_direction_wins": f"{drawdown_wins}_of_{years}",
            "top1_negative_event_loss_contribution_pct": q(top1),
            "top3_negative_event_loss_contribution_pct": q(top3),
            "annual": annual,
        },
        "gates": gates,
    }


def expected_predictive_policy() -> dict[str, Any]:
    return {
        "event": "COMPLETE_UTC_DAY_IS_FROZEN_REGULAR_FOMC_MEETING_FINAL_DATE",
        "control": "NON_EVENT_COMPLETE_UTC_DAYS_WITH_EVENT_WEEKDAYS_IN_SAME_WINDOW",
        "event_anchor": "FROZEN_EVENT_DATE_00_00_UTC_KNOWN_BEFORE_MARKET_OUTCOME",
        "event_terminal": "FOLLOWING_UTC_DAY_00_00_UTC",
        "event_horizon_hours": 24,
        "supportive_state": "SAME_WEEKDAY_NON_EVENT_CONTROL",
        "other_state": "SCHEDULED_FOMC_DECISION_DAY",
        "variants": 1,
        "strategy_economics": "DENY_UNLESS_ALL_PRE_ECONOMIC_GATES_PASS",
    }


def expected_economic_policy() -> dict[str, Any]:
    return {
        "target_exposure": "ZERO_FOR_COMPLETE_FROZEN_FOMC_EVENT_UTC_DAY_OTHERWISE_ONE",
        "exit": "FROZEN_EVENT_DATE_00_00_UTC_FIRST_H1_OPEN",
        "reentry": "FOLLOWING_UTC_DAY_00_00_UTC_FIRST_H1_OPEN",
        "sizing": "ONE_HUNDRED_PERCENT_PRE_TRADE_EQUITY_NO_LEVERAGE",
        "normal_fee_rate_per_side": "0.0010",
        "normal_adverse_slippage_rate_per_side": "0.0005",
        "stress_fee_rate_per_side": "0.0020",
        "stress_adverse_slippage_rate_per_side": "0.0010",
        "cash_yield": "ZERO",
        "variants": 1,
    }


def validate_manifest(manifest: dict[str, Any]) -> None:
    if (
        manifest.get("document_type") != EXPECTED_MANIFEST_TYPE
        or manifest.get("experiment_id") != EXPERIMENT_ID
        or manifest.get("hypothesis_id") != HYPOTHESIS_ID
    ):
        raise ResearchReject("MANIFEST_REJECT:IDENTITY")
    if (
        manifest.get("authorization") != "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
        or manifest.get("oos_access") != "DENY"
        or manifest.get("variants") != 1
    ):
        raise ResearchReject("MANIFEST_REJECT:AUTHORIZATION_OR_VARIANTS")
    if manifest.get("dataset") != {
        "path": ".research-state/java-parity/selection-2019-2024.tsv",
        "sha256": EXPECTED_BTC_SHA256,
        "hourly_rows": EXPECTED_BTC_ROWS,
        "selection_cutoff": "2025-01-01T00:00:00",
    }:
        raise ResearchReject("MANIFEST_REJECT:DATASET")
    if manifest.get("predictive_policy") != expected_predictive_policy():
        raise ResearchReject("MANIFEST_REJECT:PREDICTIVE_POLICY")
    if manifest.get("economic_policy") != expected_economic_policy():
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_POLICY")
    if manifest.get("windows") != {
        "design": {"start": "2020-01-01T00:00:00", "end_exclusive": "2023-01-01T00:00:00", "expected_events": 23},
        "validation": {"start": "2023-01-01T00:00:00", "end_exclusive": "2025-01-01T00:00:00", "expected_events": 16},
        "annual_fair_reset_years": [2020, 2021, 2022, 2023, 2024],
    }:
        raise ResearchReject("MANIFEST_REJECT:WINDOWS")
    gate_set = manifest.get("gate_set", {})
    if gate_set != {
        "predictive_required": [
            *(f"design_{name}" for name in PREDICTIVE_GATES),
            *(f"validation_{name}" for name in PREDICTIVE_GATES),
        ],
        "economic_required": list(ECONOMIC_GATES),
        "pass": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED_INDEPENDENT_OOS_REQUIRED",
        "failure": "PERMANENTLY_CLOSE_WITHOUT_EVENT_DATE_OR_WINDOW_TUNING",
    }:
        raise ResearchReject("MANIFEST_REJECT:GATES")
    bindings = manifest.get("source_bindings")
    if not isinstance(bindings, list) or {item.get("role") for item in bindings} != set(EXPECTED_SOURCE_PATHS):
        raise ResearchReject("MANIFEST_REJECT:SOURCE_BINDINGS")
    for binding in bindings:
        role = binding["role"]
        if binding.get("path") != EXPECTED_SOURCE_PATHS[role]:
            raise ResearchReject(f"BINDING_REJECT:{role}:PATH")
        path = REPO_ROOT / binding["path"]
        expected_hash = sha256(Path(__file__).resolve()) if role == "FROZEN_DIRECT_FOMC_PRE_ECONOMIC_AND_ECONOMIC_RUNNER" else EXPECTED_SOURCE_HASHES[role]
        if not path.is_file() or sha256(path) != binding.get("sha256") or binding.get("sha256") != expected_hash:
            raise ResearchReject(f"BINDING_REJECT:{role}:SHA256")


def validate_inventory(bars: list[Any]) -> dict[str, int]:
    if len(bars) != EXPECTED_BTC_ROWS:
        raise ResearchReject(f"DATA_REJECT:ROWS:{len(bars)}")
    for index, bar in enumerate(bars):
        if bar.close_time - bar.open_time != timedelta(hours=1):
            raise ResearchReject(f"DATA_REJECT:BAR_WIDTH:{index}")
        if index and bars[index - 1].close_time != bar.open_time:
            raise ResearchReject(f"DATA_REJECT:HOURLY_GAP:{index}")
        if not (bar.low > ZERO and bar.low <= bar.open <= bar.high and bar.low <= bar.close <= bar.high and bar.volume >= ZERO):
            raise ResearchReject(f"DATA_REJECT:OHLCV:{index}")
    if bars[0].open_time != datetime(2019, 1, 1) or bars[-1].close_time != datetime(2025, 1, 1):
        raise ResearchReject("DATA_REJECT:BOUNDARY")
    transitions = expected_transition_times(bars[0].open_time, bars[-1].close_time)
    if len(transitions) != 79:
        raise ResearchReject(f"DATA_REJECT:TRANSITIONS:{len(transitions)}")
    return {
        "complete_day_count": sum(bar.close_time.hour == 0 for bar in bars),
        "calendar_transition_count": len(transitions),
        "scheduled_fomc_event_count": len(EVENT_DATES),
    }


def evaluate_economic_gates(
    design: dict[str, dict[str, D]],
    validation: dict[str, dict[str, D]],
    annual: dict[str, tuple[dict[str, object], dict[str, dict[str, D]]]],
    validation_output: dict[str, object],
) -> tuple[dict[str, bool], list[str], dict[str, Any]]:
    dn, ds = design["NORMAL"], design["STRESS"]
    vn, vs = validation["NORMAL"], validation["STRESS"]
    annual_raw = {year: value[1] for year, value in annual.items()}
    normal_positive = sum(value["NORMAL"]["total_return"] > value["NORMAL"]["buy_hold_return"] for value in annual_raw.values())
    stress_positive = sum(value["STRESS"]["total_return"] > value["STRESS"]["buy_hold_return"] for value in annual_raw.values())
    drawdown_nonworse = sum(value["NORMAL"]["drawdown"] <= value["NORMAL"]["buy_hold_drawdown"] for value in annual_raw.values())
    positive_deltas = [max(value["NORMAL"]["total_return"] - value["NORMAL"]["buy_hold_return"], ZERO) for value in annual_raw.values()]
    positive_sum = sum(positive_deltas, ZERO)
    top_year = HUNDRED if positive_sum == ZERO else max(positive_deltas) / positive_sum * HUNDRED
    terminal_adjusted = D(validation_output["NORMAL"]["candidate"]["terminal_liquidation_adjusted_return_pct"])
    gates = {
        "design_normal_total_return_delta_gt_0": dn["total_return"] > dn["buy_hold_return"],
        "design_stress_total_return_delta_gt_0": ds["total_return"] > ds["buy_hold_return"],
        "design_normal_drawdown_non_worse": dn["drawdown"] <= dn["buy_hold_drawdown"],
        "design_normal_calmar_at_least_buy_hold": dn["calmar"] >= dn["buy_hold_calmar"],
        "design_normal_average_exposure_between_97pct_and_99pct": D("97") <= dn["average_exposure"] <= D("99"),
        "design_normal_signal_evaluations_exactly_47": dn["signal_evaluations"] == D("47"),
        "design_normal_sell_trades_exactly_23": dn["sell_trades"] == D("23"),
        "validation_normal_total_return_delta_gt_0": vn["total_return"] > vn["buy_hold_return"],
        "validation_stress_total_return_delta_gt_0": vs["total_return"] > vs["buy_hold_return"],
        "validation_normal_drawdown_non_worse": vn["drawdown"] <= vn["buy_hold_drawdown"],
        "validation_stress_drawdown_non_worse": vs["drawdown"] <= vs["buy_hold_drawdown"],
        "validation_normal_calmar_at_least_buy_hold": vn["calmar"] >= vn["buy_hold_calmar"],
        "validation_normal_average_exposure_between_97pct_and_99pct": D("97") <= vn["average_exposure"] <= D("99"),
        "validation_normal_signal_evaluations_exactly_33": vn["signal_evaluations"] == D("33"),
        "validation_normal_sell_trades_exactly_16": vn["sell_trades"] == D("16"),
        "normal_positive_annual_total_return_delta_at_least_4_of_5": normal_positive >= 4,
        "stress_positive_annual_total_return_delta_at_least_4_of_5": stress_positive >= 4,
        "normal_annual_drawdown_non_worse_than_buy_hold_5_of_5": drawdown_nonworse == 5,
        "top_year_positive_total_return_delta_contribution_at_most_60pct": top_year <= D("60"),
        "validation_normal_p90_realized_lot_hold_at_most_840_hours": vn["p90_hold"] <= D("840"),
        "validation_normal_terminal_oldest_lot_age_at_most_120_hours": vn["terminal_oldest_age"] <= D("120"),
        "validation_normal_terminal_liquidation_adjusted_return_delta_gt_0": terminal_adjusted > vn["buy_hold_return"],
        "validation_normal_terminal_liquidation_cost_at_most_0_5pp": vn["terminal_liquidation_cost"] <= D("0.5"),
    }
    if tuple(gates) != ECONOMIC_GATES:
        raise ResearchReject("MANIFEST_REJECT:ECONOMIC_GATE_DRIFT")
    return gates, [name for name, passed in gates.items() if not passed], {
        "normal_positive_total_return_delta_years": f"{normal_positive}_of_5",
        "stress_positive_total_return_delta_years": f"{stress_positive}_of_5",
        "normal_drawdown_non_worse_years": f"{drawdown_nonworse}_of_5",
        "top_year_positive_total_return_delta_contribution_pct": q(top_year),
    }


def build_output(input_path: Path, manifest_path: Path) -> dict[str, Any]:
    validate_calendar()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    validate_manifest(manifest)
    if sha256(input_path) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:BTC_SHA256")
    kernel = base.economic_kernel.load_module("frozen_fomc_economic_reference", base.economic_kernel.BASE_RUNNER_SOURCE)
    parser = base.economic_kernel.load_module("frozen_fomc_h1_parser", base.economic_kernel.PARSER_SOURCE)
    bars = parser.parse_rows(input_path.read_text(encoding="utf-8"))
    if len(bars) != EXPECTED_BTC_ROWS or parser.data_hash(bars) != EXPECTED_BTC_SHA256:
        raise ResearchReject("DATA_REJECT:ROWS_OR_CANONICAL_SHA256")
    inventory = validate_inventory(bars)
    predictive = {
        "design": predictive_evidence(bars, DESIGN, label="design"),
        "validation": predictive_evidence(bars, VALIDATION, label="validation"),
    }
    predictive_gates = {
        **{f"design_{name}": passed for name, passed in predictive["design"]["gates"].items()},
        **{f"validation_{name}": passed for name, passed in predictive["validation"]["gates"].items()},
    }
    predictive_failed = [name for name, passed in predictive_gates.items() if not passed]
    economic_output: dict[str, Any] | str = "NOT_OPENED_PRE_ECONOMIC_GATE_FAILURE"
    economic_gates: dict[str, bool] = {}
    economic_failed: list[str] = []
    if not predictive_failed:
        base.economic_kernel.is_turn_of_month_hour = target_long
        base.economic_kernel.expected_transition_times = expected_transition_times
        design_output, design_raw = base.economic_kernel.simulate_window(bars, DESIGN, kernel)
        validation_output, validation_raw = base.economic_kernel.simulate_window(bars, VALIDATION, kernel)
        annual = {year: base.economic_kernel.simulate_window(bars, window, kernel) for year, window in ANNUAL.items()}
        economic_gates, economic_failed, breadth = evaluate_economic_gates(design_raw, validation_raw, annual, validation_output)
        economic_output = {
            "windows": {"design": design_output, "validation": validation_output},
            "annual_fair_reset": {year: value[0] for year, value in annual.items()},
            "breadth_and_concentration": breadth,
        }
    passed = not predictive_failed and not economic_failed
    return {
        "schema_version": "1",
        "document_type": "BTC_FOMC_SCHEDULED_ANNOUNCEMENT_DAY_RISK_VETO_HISTORICAL_RESULT_V1",
        "experiment_id": EXPERIMENT_ID,
        "authorization": "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE",
        "status": "CANDIDATE_FROZEN_REPORTED_NOT_ACTIVATED" if passed else "NO_CANDIDATE_CLOSE_FOMC_SCHEDULED_ANNOUNCEMENT_DAY_RISK_VETO_FAMILY",
        "decision": "HISTORICAL_GATES_PASS_INDEPENDENT_OOS_REQUIRED" if passed else "PERMANENTLY_CLOSE_WITHOUT_EVENT_DATE_OR_WINDOW_TUNING",
        "manifest": {"path": manifest_path.relative_to(REPO_ROOT).as_posix(), "sha256": sha256(manifest_path)},
        "runner": {"path": Path(__file__).resolve().relative_to(REPO_ROOT).as_posix(), "sha256": sha256(Path(__file__).resolve()), "python": "DIRECT_NO_SPRING_NO_SERVER_NO_DATABASE"},
        "event_calendar": {"path": EXPECTED_SOURCE_PATHS["FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR"], "sha256": EXPECTED_CALENDAR_SHA256, "events": len(EVENT_DATES)},
        "dataset": {"path": input_path.relative_to(REPO_ROOT).as_posix(), "sha256": EXPECTED_BTC_SHA256, "rows": len(bars), "selection_cutoff": "2025-01-01T00:00:00", **inventory},
        "predictive": predictive,
        "predictive_gates": predictive_gates,
        "predictive_failed_gates": predictive_failed,
        "economic": economic_output,
        "economic_gates": economic_gates,
        "economic_failed_gates": economic_failed,
        "all_gates_pass": passed,
        "oos_opened": False,
        "claim_boundary": "Historical preregistered Design and Validation only. A calendar association does not identify monetary policy as the cause, and a pass is not independent OOS or activation authority.",
        "scope_note": "No paid API, second timer, second writer, backfill, canonical write, OOS opening, Production, Trading, database, order, fund, OCO, Grid, SHADOW, PAPER or LIVE action occurred.",
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    input_path = args.input.resolve()
    manifest_path = args.manifest.resolve()
    output_path = args.output.resolve()
    for path in (input_path, manifest_path):
        if not path.is_relative_to(REPO_ROOT):
            raise ResearchReject(f"PATH_REJECT:{path}")
    if not output_path.is_relative_to(REPO_ROOT / ".research-state"):
        raise ResearchReject(f"OUTPUT_PATH_REJECT:{output_path}")
    if output_path.exists():
        raise ResearchReject(f"SEALED_OUTPUT_EXISTS:{output_path}")
    result = build_output(input_path, manifest_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("x", encoding="utf-8", newline="\n") as stream:
        json.dump(result, stream, ensure_ascii=True, sort_keys=True, separators=(",", ":"))
        stream.write("\n")
    print(json.dumps({
        "status": result["status"],
        "output": output_path.relative_to(REPO_ROOT).as_posix(),
        "sha256": sha256(output_path),
        "predictive_failed_gates": result["predictive_failed_gates"],
        "economic_failed_gates": result["economic_failed_gates"],
    }, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
