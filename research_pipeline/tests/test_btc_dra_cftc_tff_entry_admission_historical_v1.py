from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal
import hashlib
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator


ROOT = Path(__file__).resolve().parents[2]
RESEARCH = ROOT / "research"
if str(RESEARCH) not in sys.path:
    sys.path.insert(0, str(RESEARCH))

import btc_dra_cftc_tff_entry_admission_historical_v1 as runner


D = Decimal


def _hash(label: str) -> str:
    return hashlib.sha256(label.encode("utf-8")).hexdigest()


def _row(day: date, long_pct: str = "50", short_pct: str = "50") -> list[str]:
    row = ["0"] * 87
    row[0] = runner.cftc_source.MARKET_NAME
    row[1] = day.strftime("%y%m%d")
    row[2] = day.isoformat()
    row[3] = runner.cftc_source.CONTRACT_CODE
    row[runner.LONG_INDEX] = long_pct
    row[runner.SHORT_INDEX] = short_pct
    row[86] = runner.cftc_source.REPORT_FAMILY_MARKER
    return row


def _manifest() -> dict:
    bindings = {
        "runner": {"path": "research/btc_dra_cftc_tff_entry_admission_historical_v1.py", "sha256": _hash("runner")},
        "capacity_runner": {"path": "research/btc_dra_equal_capital_capacity_v1.py", "sha256": _hash("capacity")},
        "base_runner": {"path": "research/btc_dra_reversal_confirmed_exit_v2c.py", "sha256": _hash("base")},
        "factor_contract": {"path": "research_pipeline/cftc-tff-lev-money-net-pct-oi-delta-factor-contract.v1.json", "sha256": runner.FACTOR_CONTRACT_SHA256},
        "factor_evaluator": {"path": "research_pipeline/cftc_tff_lev_money_net_pct_oi_delta_evaluator_v1.py", "sha256": _hash("factor-evaluator")},
        "manifest_schema": {"path": "research_pipeline/btc-dra-cftc-tff-historical-entry-admission-manifest.v1.schema.json", "sha256": _hash("manifest-schema")},
        "source_contract": {"path": "research_pipeline/cftc-cme-bitcoin-tff-source-contract.v2.json", "sha256": runner.factor_evaluator.SOURCE_CONTRACT_SHA256},
        "source_field_definition": {"path": "research_pipeline/cftc_cme_bitcoin_tff_source.py", "sha256": _hash("source-field-definition")},
    }
    archives = []
    for year in range(2019, 2025):
        name = f"fut_fin_txt_{year}.zip"
        archives.append({
            "archive_bytes": 1,
            "archive_sha256": _hash(f"archive-{year}"),
            "entry_bytes": 1,
            "entry_name": "FinFutYY.txt",
            "entry_sha256": _hash(f"entry-{year}"),
            "exact_contract_rows": 52,
            "path": f".research-state/experiments/cftc-tff-dra-entry-admission-historical-v1/inputs/{name}",
            "source_url": f"https://www.cftc.gov/files/dea/history/{name}",
            "year": year,
        })
    return {
        "archives": archives,
        "authorization": runner.AUTHORIZATION,
        "availability": {
            "eligible_time": "REPORT_DATE_PLUS_14_CALENDAR_DAYS_AT_00_00_UTC",
            "exact_predecessor_days": 7,
            "factor_valid_hours": 168,
            "ion_exclusion": {
                "end_inclusive": "2023-03-14",
                "reason": "CFTC_2023_ION_DELAYED_PUBLICATION",
                "start_inclusive": "2023-01-31",
            },
            "non_tuesday_action": "EXCLUDE",
            "report_lag_calendar_days": 14,
        },
        "bindings": bindings,
        "dataset": {"canonical_sha256": _hash("dataset"), "rows": 52608},
        "document_type": runner.DOCUMENT_TYPE,
        "economics": {
            "fee_rate": "0.0010",
            "initial_equity_usdt": "250",
            "slippage_rate": "0.0005",
            "slot_capacity_usdt": "240",
        },
        "experiment_id": "cftc-tff-dra-entry-admission-historical-v1",
        "factor": {
            "admission_rule": "ADMIT_PARENT_SIGNAL_ONLY_WHEN_FACTOR_DELTA_GT_ZERO",
            "factor_identity": runner.FACTOR_IDENTITY,
            "formula": "(current_long_pct-current_short_pct)-(prior_long_pct-prior_short_pct)",
            "negative_action": "HOLD_CASH",
            "positive_action": "ADMIT",
            "zero_action": "HOLD_CASH",
        },
        "gate_set": runner.GATE_SET,
        "oos_access": "DENY",
        "parent_strategy": runner.PARENT_STRATEGY,
        "schema_version": "1",
        "selection_cutoff": "2025-01-01T00:00:00",
        "windows": {
            "annual_folds": ["2020", "2021", "2022", "2023", "2024"],
            "design": {"end_exclusive": "2023-01-01T00:00:00", "start_inclusive": "2020-01-01T00:00:00"},
            "outcome_horizon_hours": 168,
            "validation": {"end_exclusive": "2025-01-01T00:00:00", "start_inclusive": "2023-01-01T00:00:00"},
        },
    }


def _bar(opened: datetime, close: str = "100") -> runner.base.Bar:
    price = D(close)
    return runner.base.Bar(
        open_time=opened,
        close_time=opened + timedelta(hours=1),
        open=price,
        high=price,
        low=price,
        close=price,
        volume=D("1"),
    )


class CftcHistoricalDraScreenTest(unittest.TestCase):
    def test_manifest_accepts_one_frozen_route_and_rejects_factor_inversion(self) -> None:
        value = _manifest()
        schema = runner.json.loads(
            (ROOT / "research_pipeline" / "btc-dra-cftc-tff-historical-entry-admission-manifest.v1.schema.json").read_text(encoding="utf-8")
        )
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(value)
        self.assertIs(value, runner.validate_manifest(value))
        value["factor"]["positive_action"] = "HOLD_CASH"
        with self.assertRaisesRegex(runner.ScreenReject, "factor semantics"):
            runner.validate_manifest(value)

    def test_factor_points_apply_fourteen_day_lag_exact_predecessor_and_ion_exclusion(self) -> None:
        rows = {
            date(2022, 12, 27): _row(date(2022, 12, 27), "50", "50"),
            date(2023, 1, 3): _row(date(2023, 1, 3), "51", "50"),
            date(2023, 1, 24): _row(date(2023, 1, 24), "50", "50"),
            date(2023, 1, 31): _row(date(2023, 1, 31), "51", "50"),
            date(2023, 3, 14): _row(date(2023, 3, 14), "52", "50"),
            date(2023, 3, 21): _row(date(2023, 3, 21), "53", "50"),
            date(2023, 3, 28): _row(date(2023, 3, 28), "55", "50"),
        }
        points, exclusions = runner.build_factor_points(rows)
        self.assertEqual(["2023-01-03", "2023-03-28"], [item["report_date"] for item in points])
        self.assertEqual("2023-01-17T00:00:00", points[0]["eligible_at"])
        self.assertEqual("2", points[1]["factor_delta"])
        self.assertEqual(2, exclusions["ION_DELAY"])
        self.assertEqual(3, exclusions["MISSING_EXACT_PREDECESSOR"])

    def test_non_tuesday_report_is_excluded_not_used_as_predecessor(self) -> None:
        monday = date(2024, 1, 1)
        next_monday = monday + timedelta(days=7)
        points, exclusions = runner.build_factor_points({monday: _row(monday), next_monday: _row(next_monday, "51", "50")})
        self.assertEqual([], points)
        self.assertEqual(2, exclusions["NON_TUESDAY"])

    def test_admission_uses_latest_eligible_factor_and_holds_cash_for_nonpositive(self) -> None:
        engine = runner.CftcEntryAdmissionEngine([
            {"eligible_at": "2024-01-01T00:00:00", "factor_sign": 1},
            {"eligible_at": "2024-01-08T00:00:00", "factor_sign": -1},
        ])
        with patch.object(runner.capacity.EqualCapitalCapacityEngine, "_signal", return_value=True):
            self.assertFalse(engine._signal(_bar(datetime(2023, 12, 31, 23))))
            self.assertTrue(engine._signal(_bar(datetime(2024, 1, 7, 23))))
            self.assertFalse(engine._signal(_bar(datetime(2024, 1, 8, 23))))
            self.assertFalse(engine._signal(_bar(datetime(2024, 1, 15, 0))))
        self.assertEqual((4, 1, 3, 2), (
            engine.parent_signal_count,
            engine.admitted_signal_count,
            engine.vetoed_signal_count,
            engine.factor_unavailable_signal_count,
        ))

    def test_predictive_gates_pass_only_with_breadth_and_both_signs(self) -> None:
        episodes = []
        opened = datetime(2020, 1, 1)
        for index in range(26):
            sign = 1 if index % 2 == 0 else -1
            raw = D("0.01") if sign > 0 else D("-0.01")
            episodes.append({
                "anchor_at": (opened + timedelta(days=14 * index)).isoformat(),
                "factor_sign": sign,
                "raw_return_168h": str(raw),
                "signed_response_168h": "0.01",
                "sign_adjusted_mae_168h": "0.001",
            })
        evidence = runner.predictive_evidence(episodes)
        self.assertTrue(all(evidence["gates"].values()))
        one_sign = runner.predictive_evidence([{**item, "factor_sign": 1, "raw_return_168h": "0.01"} for item in episodes])
        self.assertFalse(one_sign["gates"]["minimum_8_negative_factors"])
        self.assertFalse(one_sign["gates"]["negative_factor_median_raw_return_negative"])

    def test_predictive_episode_uses_first_close_strictly_after_decision(self) -> None:
        start = datetime(2024, 1, 1)
        bars = [_bar(start + timedelta(hours=index), str(100 + index)) for index in range(200)]
        points = [{
            "eligible_at": start.isoformat(),
            "factor_delta": "1",
            "factor_sign": 1,
            "report_date": "2023-12-18",
        }]
        episodes = runner.build_predictive_episodes(bars, points, (start, start + timedelta(hours=200)))
        self.assertEqual(1, len(episodes))
        self.assertEqual((start + timedelta(hours=1)).isoformat(), episodes[0]["anchor_at"])
        self.assertEqual((start + timedelta(hours=169)).isoformat(), episodes[0]["terminal_at"])


if __name__ == "__main__":
    unittest.main()
