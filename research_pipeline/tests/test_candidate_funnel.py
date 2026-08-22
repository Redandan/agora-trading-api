from __future__ import annotations

import hashlib
import json
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from jsonschema import Draft202012Validator

from research_pipeline.candidate_funnel import (
    build_candidate_funnel,
    candidate_funnel_status,
    load_candidate_pool_catalog,
)
from research_pipeline.forward_volatility_persistence import (
    CLOSE as VOLATILITY_CLOSE,
    RETAIN as VOLATILITY_RETAIN,
    _canonical_bytes as _volatility_canonical_bytes,
)
from research_pipeline.forward_volatility_persistence_activation import (
    ACTIVATION_RECEIPT_RETIRED,
    ACTIVATION_STATE_KEY,
    ActivationDecision,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
CATALOG_PATH = REPO_ROOT / "research_pipeline" / "pre-candidate-pool.v1.json"
SCHEMA_PATH = REPO_ROOT / "research_pipeline" / "pre-candidate-pool.v1.schema.json"
VOLUME = "DRA_ENTRY_VOLUME_CONFIRMATION_20D"
RANGE = "DRA_ENTRY_RANGE_CONFIRMATION_20D"


def _registry(*, eligible: list[str] | None = None) -> dict[str, object]:
    return {
        "research_status": "WAITING_FOR_EVIDENCE",
        "forward_candidate_readiness": {
            "status": "READY",
            "diagnostic_contract": {"mechanisms": [VOLUME, RANGE]},
        },
        "evidence_triggers": [
            {
                "trigger_id": "prospective-mechanism-neutral-evidence-refresh",
                "purpose": "HYPOTHESIS_DISCOVERY",
                "status": "OPEN",
                "progress": {
                    "status": "COLLECTING",
                    "observation_count": 1,
                    "minimum_observations": 90,
                },
                "candidate_context": {"eligible_mechanisms": eligible or []},
                "next_review_at": "2026-11-15T00:00:00Z",
            }
        ],
        "experiments": [
            {
                "experiment_id": "sealed-example",
                "title": "Sealed example",
                "stage": "CLOSED",
                "outcome": "NO_CANDIDATE",
                "adapter": "example-adapter",
                "updated_at": "2026-08-18T00:00:00Z",
            }
        ],
    }


def _microstructure(status: str = "CAPTURE_OVERDUE") -> dict[str, object]:
    return {
        "diagnostic_id": "okx-btcusdt-microstructure-v3r1",
        "status": status,
        "complete_day_count": 2,
        "required_day_count": 42,
        "next_calendar_day": "2026-08-17",
    }


class CandidateFunnelTest(unittest.TestCase):
    def test_catalog_is_schema_valid_and_all_evidence_hashes_verify(self) -> None:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        catalog_document = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        Draft202012Validator.check_schema(schema)
        Draft202012Validator(schema).validate(catalog_document)

        catalog = load_candidate_pool_catalog(REPO_ROOT, CATALOG_PATH)
        self.assertEqual(len(catalog["families"]), 5)
        self.assertEqual(len(catalog["closed_families"]), 146)
        self.assertEqual(
            {
                family["family_id"]
                for family in catalog["closed_families"]
                if family["duplicate_family_key"].startswith("dra-binance-usdm-")
            },
            {
                "closed-dra-binance-usdm-deleveraging-flush-entry-admission-v1",
                "closed-dra-binance-usdm-positioning-divergence-entry-admission-v1",
                "closed-dra-binance-usdm-taker-flow-open-interest-confirmation-entry-admission-v1",
            },
        )
        basis_convergence = next(
            family
            for family in catalog["families"]
            if family["family_id"]
            == "btc-binance-usdm-perpetual-basis-convergence-market-neutral"
        )
        self.assertEqual(basis_convergence["base_stage"], "HISTORICAL_PRIOR")
        self.assertEqual(
            [binding["role"] for binding in basis_convergence["evidence_bindings"]],
            [
                "OFFICIAL_PUBLIC_BINANCE_DERIVATIVES_SOURCE_CAPABILITY",
                "SEALED_CHECKSUM_VERIFIED_SPOT_PERPETUAL_MARK_AND_INDEX_CORPUS_BUNDLE",
                "SEALED_CONSTANT_FUNDING_CARRY_CLOSURE_AND_NON_REOPEN_BOUNDARY",
                "SEALED_DIRECTIONAL_BASIS_TRANSFER_REJECT_AND_DISTINCT_CONVERGENCE_ROUTE_BOUNDARY",
            ],
        )
        closed_basis_instability = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-binance-usdm-basis-instability-passive-core-risk-overlay"
        )
        self.assertEqual(
            closed_basis_instability["disposition"],
            "NO_HYPOTHESIS_CLOSE_DIRECTIONAL_BASIS_DISPERSION_TRANSFER_AT_PRIMARY_PRIOR_GATE",
        )
        self.assertTrue(closed_basis_instability["prohibited_reopen"])
        closed_funding_carry = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-binance-usdm-delta-neutral-funding-carry-v3"
        )
        self.assertEqual(
            closed_funding_carry["disposition"],
            "BTC_BINANCE_USDM_FUNDING_CARRY_FAMILY_CLOSE",
        )
        self.assertTrue(closed_funding_carry["prohibited_reopen"])
        closed_ulcer28 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-ulcer28-passive-core-risk-overlay-v1"
        )
        self.assertEqual(
            closed_ulcer28["disposition"],
            "BTC_DAILY_ULCER_PASSIVE_CORE_FAMILY_CLOSE",
        )
        self.assertTrue(closed_ulcer28["prohibited_reopen"])
        closed_cci14 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-cci14-hysteresis-long-cash-v1"
        )
        self.assertEqual(
            closed_cci14["disposition"],
            "BTC_DAILY_CCI14_FAMILY_CLOSE",
        )
        self.assertTrue(closed_cci14["prohibited_reopen"])
        closed_kama30 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-kama30-adaptive-trend-long-cash-v1"
        )
        self.assertEqual(
            closed_kama30["disposition"],
            "BTC_DAILY_KAMA30_FAMILY_CLOSE",
        )
        self.assertTrue(closed_kama30["prohibited_reopen"])
        closed_stochastic = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-stochastic-5-3-3-kd-cross-long-cash-v1"
        )
        self.assertEqual(
            closed_stochastic["disposition"],
            "BTC_DAILY_STOCHASTIC_5_3_3_EVIDENCE_INSUFFICIENT_TEMPORAL_PREREGISTRATION_ORDER_FAILED_NO_RERUN",
        )
        self.assertTrue(closed_stochastic["prohibited_reopen"])
        closed_search_attention = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-search-attention-volume-interaction-v1"
        )
        self.assertEqual(
            closed_search_attention["disposition"],
            "DATA_REJECT_POINT_IN_TIME_SEARCH_VINTAGE_SOURCE_NOT_EXECUTABLE",
        )
        self.assertTrue(closed_search_attention["prohibited_reopen"])
        closed_aroon14 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-aroon14-oscillator-long-cash-v1"
        )
        self.assertEqual(
            closed_aroon14["disposition"],
            "BTC_DAILY_AROON14_FAMILY_CLOSE",
        )
        self.assertTrue(closed_aroon14["prohibited_reopen"])
        closed_mfi14 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-money-flow-index-14-midline-long-cash-v1"
        )
        self.assertEqual(
            closed_mfi14["disposition"],
            "BTC_DAILY_MFI14_FAMILY_CLOSE",
        )
        self.assertTrue(closed_mfi14["prohibited_reopen"])
        closed_positive_hour_breadth = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-signal-day-positive-hour-breadth-entry-admission"
        )
        self.assertEqual(
            closed_positive_hour_breadth["disposition"],
            "NO_HYPOTHESIS_CLOSE_SIGNAL_DAY_POSITIVE_HOUR_BREADTH_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_positive_hour_breadth["prohibited_reopen"])
        closed_vwap_occupancy = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-signal-day-vwap-occupancy-entry-admission"
        )
        self.assertEqual(
            closed_vwap_occupancy["disposition"],
            "NO_HYPOTHESIS_CLOSE_SIGNAL_DAY_VWAP_OCCUPANCY_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_vwap_occupancy["prohibited_reopen"])
        closed_ema20_occupancy = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-signal-day-ema20-occupancy-entry-admission"
        )
        self.assertEqual(
            closed_ema20_occupancy["disposition"],
            "NO_HYPOTHESIS_CLOSE_SIGNAL_DAY_EMA20_OCCUPANCY_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_ema20_occupancy["prohibited_reopen"])
        closed_prior_day_momentum = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-prior-day-momentum-continuation-entry-admission"
        )
        self.assertEqual(
            closed_prior_day_momentum["disposition"],
            "NO_HYPOTHESIS_CLOSE_PRIOR_DAY_MOMENTUM_DUPLICATE_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_prior_day_momentum["prohibited_reopen"])
        closed_trend_acceleration = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-trend-acceleration-entry-admission"
        )
        self.assertEqual(
            closed_trend_acceleration["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_NATIVE_TREND_ACCELERATION_FAMILY",
        )
        self.assertTrue(closed_trend_acceleration["prohibited_reopen"])
        closed_signal_latency = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-signal-confirmation-latency-entry-admission"
        )
        self.assertEqual(
            closed_signal_latency["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_SIGNAL_CONFIRMATION_LATENCY_FAMILY",
        )
        self.assertTrue(closed_signal_latency["prohibited_reopen"])
        closed_stale_inventory = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-stale-inventory-age-entry-admission"
        )
        self.assertEqual(
            closed_stale_inventory["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_STALE_INVENTORY_AGE_FAMILY",
        )
        self.assertTrue(closed_stale_inventory["prohibited_reopen"])
        closed_breakout = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-signal-prior-24h-breakout-entry-admission"
        )
        self.assertEqual(
            closed_breakout["disposition"],
            "NO_HYPOTHESIS_CLOSE_PRIOR_24H_BREAKOUT_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_breakout["prohibited_reopen"])
        closed_signal_margin = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-native-signal-overextension-entry-admission"
        )
        self.assertEqual(
            closed_signal_margin["disposition"],
            "NO_HYPOTHESIS_CLOSE_NATIVE_SIGNAL_OVEREXTENSION_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_signal_margin["prohibited_reopen"])
        closed_inventory = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-underwater-inventory-congestion-entry-admission"
        )
        self.assertEqual(
            closed_inventory["disposition"],
            "NO_HYPOTHESIS_CLOSE_INVENTORY_CONGESTION_FAMILY_AT_SUPPORT_GATE",
        )
        self.assertTrue(closed_inventory["prohibited_reopen"])
        closed_rotation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-one-slot-profitable-incumbent-signal-rotation"
        )
        self.assertEqual(closed_rotation["disposition"], "NO_CANDIDATE_KEEP_ONE_SLOT_DRA_V1")
        self.assertTrue(closed_rotation["prohibited_reopen"])
        closed_flat_veto = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-flat-veto-cooldown-route-substitution"
        )
        self.assertEqual(closed_flat_veto["disposition"], "NO_CANDIDATE_KEEP_DRA_V1")
        self.assertTrue(closed_flat_veto["prohibited_reopen"])
        closed_sizing = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-lagged-realized-variance-scaled-lot-sizing"
        )
        self.assertEqual(
            closed_sizing["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_VARIABLE_LOT_SIZING_FAMILY",
        )
        self.assertTrue(closed_sizing["prohibited_reopen"])
        closed_spread = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-corwin-schultz-spread-entry-admission"
        )
        self.assertEqual(
            closed_spread["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_DRA_CORWIN_SCHULTZ_SPREAD_ENTRY_ADMISSION_FAMILY",
        )
        self.assertTrue(closed_spread["prohibited_reopen"])
        closed_taker_buy = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-binance-spot-seven-day-taker-buy-imbalance-long-cash"
        )
        self.assertEqual(
            closed_taker_buy["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BINANCE_SPOT_TAKER_BUY_IMBALANCE_FAMILY_BEFORE_FACTOR_OR_OUTCOME_ACCESS",
        )
        self.assertTrue(closed_taker_buy["prohibited_reopen"])
        closed_variance_ratio = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-h1-four-day-variance-ratio-positive-persistence-long-cash"
        )
        self.assertEqual(
            closed_variance_ratio["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_H1_FOUR_DAY_VARIANCE_RATIO_POSITIVE_PERSISTENCE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_variance_ratio["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SEALED_PRIMARY_ACADEMIC_AND_ADVERSARIAL_PRIOR",
                "FROZEN_VALID_PREOUTCOME_SUPPORT_SPEC_V2",
                "RECORDED_PRE_ECONOMIC_V1_SUPPORT_INVALIDATION",
                "FROZEN_IMPORT_ORDER_INDEPENDENT_SUPPORT_PROBE_V2",
                "SEALED_VALID_PREOUTCOME_FEATURE_SUPPORT_V2",
                "FROZEN_DETERMINISTIC_SINGLE_VARIANT_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_HISTORICAL_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_variance_ratio["prohibited_reopen"])
        closed_cfnai = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-cfnai-above-trend-long-cash"
        )
        self.assertEqual(
            closed_cfnai["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_CFNAI_ABOVE_TREND_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_cfnai["prohibited_reopen"])
        closed_gscpi = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-nyfed-gscpi-3m-easing-long-cash"
        )
        self.assertEqual(
            closed_gscpi["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_NYFED_GSCPI_3M_EASING_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_gscpi["prohibited_reopen"])
        closed_copper = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-pcoppusdm-3m-uptrend-long-cash"
        )
        self.assertEqual(
            closed_copper["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_PCOPPUSDM_3M_UPTREND_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_copper["prohibited_reopen"])
        closed_yuan = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-dexchus-4w-yuan-appreciation-long-cash"
        )
        self.assertEqual(
            closed_yuan["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_DEXCHUS_4W_YUAN_APPRECIATION_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_yuan["prohibited_reopen"])
        closed_yen = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-dexjpus-4w-yen-depreciation-long-cash"
        )
        self.assertEqual(
            closed_yen["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_DEXJPUS_4W_YEN_DEPRECIATION_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_yen["prohibited_reopen"])
        closed_wti = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-wcoilwtico-4w-uptrend-long-cash"
        )
        self.assertEqual(
            closed_wti["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_WCOILWTICO_4W_UPTREND_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_wti["prohibited_reopen"])
        closed_busloans = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-busloans-yoy-growth-acceleration-long-cash"
        )
        self.assertEqual(
            closed_busloans["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_BUSLOANS_YOY_GROWTH_ACCELERATION_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_busloans["prohibited_reopen"])
        closed_dspic96 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-dspic96-yoy-growth-acceleration-long-cash"
        )
        self.assertEqual(
            closed_dspic96["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_DSPIC96_YOY_GROWTH_ACCELERATION_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_dspic96["prohibited_reopen"])
        closed_unrate = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-unrate-yoy-nondeterioration-long-cash"
        )
        self.assertEqual(
            closed_unrate["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_UNRATE_YOY_NONDETERIORATION_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_unrate["prohibited_reopen"])
        closed_cpi = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-fred-cpiaucsl-yoy-disinflation-long-cash"
        )
        self.assertEqual(closed_cpi["disposition"], "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_FRED_CPIAUCSL_YOY_DISINFLATION_LONG_CASH_FAMILY")
        self.assertTrue(closed_cpi["prohibited_reopen"])
        closed_halloween = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-halloween-november-april-long-cash"
        )
        self.assertEqual(
            closed_halloween["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_HALLOWEEN_NOVEMBER_APRIL_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_halloween["prohibited_reopen"])
        closed_rrsfs = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-fred-rrsfs-yoy-growth-long-cash"
        )
        self.assertEqual(
            closed_rrsfs["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_RRSFS_YOY_GROWTH_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_rrsfs["prohibited_reopen"])
        closed_dvol = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-deribit-dvol-rising-half-risk"
        )
        self.assertEqual(
            closed_dvol["disposition"],
            "NO_CANDIDATE_PERMANENTLY_CLOSE_BTC_DERIBIT_DVOL_RISING_HALF_RISK_FAMILY",
        )
        self.assertTrue(closed_dvol["prohibited_reopen"])
        closed_indpro = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-fred-indpro-yoy-growth-long-cash"
        )
        self.assertEqual(
            closed_indpro["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_INDPRO_YOY_GROWTH_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_indpro["prohibited_reopen"])
        closed_rctc = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-coinmetrics-rctc-market-top-risk-veto-long-cash"
        )
        self.assertEqual(
            closed_rctc["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_COINMETRICS_RCTC_MARKET_TOP_RISK_VETO_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_rctc["prohibited_reopen"])
        closed_permit = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-housing-permits-yoy-growth-long-cash"
        )
        self.assertEqual(
            closed_permit["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_HOUSING_PERMITS_YOY_GROWTH_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_permit["prohibited_reopen"])
        closed_umcsent = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-umcsent-rising-consumer-sentiment-long-cash"
        )
        self.assertEqual(
            closed_umcsent["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_UMCSENT_RISING_CONSUMER_SENTIMENT_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_umcsent["prohibited_reopen"])
        closed_stlfsi4 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-stlfsi4-below-normal-stress-long-cash"
        )
        self.assertEqual(
            closed_stlfsi4["disposition"],
            "DATA_REJECT_PERMANENTLY_CLOSE_BTC_FRED_STLFSI4_BELOW_NORMAL_STRESS_LONG_CASH_FAMILY",
        )
        self.assertTrue(closed_stlfsi4["prohibited_reopen"])
        closed_first_six_hour_direction = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-first-six-hour-direction-24h-state-long-cash"
        )
        self.assertEqual(
            closed_first_six_hour_direction["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FIRST_SIX_HOUR_DIRECTION_24H_STATE_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_first_six_hour_direction["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ACADEMIC_AND_ADVERSARIAL_PRIOR",
                "SEALED_PRE_OUTCOME_FEATURE_SUPPORT",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SEALED_PRE_RESULT_INITIAL_CASH_BOUNDARY_AMENDMENT",
                "FROZEN_DETERMINISTIC_SINGLE_VARIANT_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_HISTORICAL_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_first_six_hour_direction["prohibited_reopen"])
        closed_low_volatility_rotation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-eth-fixed-universe-monthly-low-volatility-rotation"
        )
        self.assertEqual(
            closed_low_volatility_rotation["disposition"],
            "DUPLICATE_SOURCE_BOUNDARY_REJECT_CLOSE_BEFORE_SOURCE_OR_OUTCOME",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_low_volatility_rotation["evidence_bindings"]],
            [
                "SEALED_PRE_SOURCE_DUPLICATE_BOUNDARY_DECISION",
                "SEALED_PRE_SOURCE_OFFICIAL_ACADEMIC_AND_ADVERSARIAL_PRIOR",
                "PREEXISTING_TERMINAL_BTC_ETH_REFERENCE_RATE_SOURCE_BOUNDARY",
            ],
        )
        self.assertTrue(closed_low_volatility_rotation["prohibited_reopen"])
        closed_realized_cap_growth = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-coinmetrics-realized-cap-28d-growth-long-cash"
        )
        self.assertEqual(
            closed_realized_cap_growth["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_REALIZED_CAP_28D_GROWTH_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_realized_cap_growth["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_TIMING_REVISION_METRIC_AND_LICENSE_IDENTITY",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_DETERMINISTIC_SINGLE_VARIANT_ECONOMIC_RUNNER",
                "SEALED_COINMETRICS_SOURCE_AND_PRE_OUTCOME_SUPPORT_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_HISTORICAL_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_realized_cap_growth["prohibited_reopen"])
        fee_pressure = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-bitcoin-fee-pressure-entry-admission"
        )
        self.assertEqual(
            fee_pressure["disposition"],
            "NO_CANDIDATE_CLOSE_BITCOIN_FEE_PRESSURE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in fee_pressure["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_IDENTITY_CORRECTED_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_TIMING_REVISION_METRIC_AND_LICENSE_IDENTITY",
            ],
        )
        self.assertTrue(fee_pressure["prohibited_reopen"])
        real_yield = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-real-yield-easing-entry-admission"
        )
        self.assertEqual(
            real_yield["disposition"],
            "DATA_REJECT_CLOSE_REAL_YIELD_SOURCE_TRANSPORT_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in real_yield["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_FAILED_SOURCE_PROBE",
            ],
        )
        self.assertTrue(real_yield["prohibited_reopen"])
        active_supply = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-bitcoin-active-supply-contraction-entry-admission"
        )
        self.assertEqual(
            active_supply["disposition"],
            "DATA_REJECT_CLOSE_BITCOIN_ACTIVE_SUPPLY_CONTRACTION_FREE_SOURCE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in active_supply["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_FAILED_SOURCE_PROBE",
            ],
        )
        self.assertTrue(active_supply["prohibited_reopen"])
        mvrv = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-bitcoin-mvrv-relative-value-entry-admission"
        )
        self.assertEqual(
            mvrv["disposition"],
            "NO_CANDIDATE_CLOSE_BITCOIN_MVRV_RELATIVE_VALUE_FAMILY",
        )
        self.assertTrue(mvrv["prohibited_reopen"])
        hashrate = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-bitcoin-hashrate-growth-entry-admission"
        )
        self.assertEqual(
            hashrate["disposition"],
            "NO_CANDIDATE_CLOSE_BITCOIN_HASHRATE_GROWTH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in hashrate["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_TIMING_REVISION_METRIC_AND_LICENSE_IDENTITY",
            ],
        )
        self.assertTrue(hashrate["prohibited_reopen"])
        carry = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-dra-crypto-carry-risk-veto"
        )
        self.assertEqual(
            carry["disposition"],
            "NO_SOURCE_CLOSE_DRA_CRYPTO_CARRY_RISK_VETO_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in carry["evidence_bindings"]],
            [
                "SEALED_V3R1_SCHEMA_ONLY_SOURCE_FAILURE",
                "FROZEN_V3R1_PUBLIC_SOURCE_CONTRACT",
                "PREACCESS_V3_TO_V3R1_SUPERSESSION_DECISION",
            ],
        )
        self.assertTrue(carry["prohibited_reopen"])
        drawdown_recovery = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-90d-drawdown-recovery-entry-admission"
        )
        self.assertEqual(
            drawdown_recovery["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_90D_DRAWDOWN_RECOVERY_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in drawdown_recovery["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
            ],
        )
        self.assertTrue(drawdown_recovery["prohibited_reopen"])
        partial_core_runner = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-partial-core-runner-profit-only-exit"
        )
        self.assertEqual(
            partial_core_runner["disposition"],
            "NO_CANDIDATE_CLOSE_PARTIAL_CORE_RUNNER_EXIT_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in partial_core_runner["evidence_bindings"]
            ],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PRE_PERFORMANCE_SPECIFICATION",
                "SPEC_HASH_BOUND_DETERMINISTIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_EVIDENCE",
            ],
        )
        self.assertTrue(partial_core_runner["prohibited_reopen"])
        closed_fomc_announcement_day = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fomc-scheduled-announcement-day-risk-veto"
        )
        self.assertEqual(
            closed_fomc_announcement_day["disposition"],
            "NO_CANDIDATE_CLOSE_FOMC_SCHEDULED_ANNOUNCEMENT_DAY_RISK_VETO_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_fomc_announcement_day["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_fomc_announcement_day["prohibited_reopen"])
        closed_monthly_options_expiry = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-deribit-last-friday-monthly-options-expiry-risk-veto"
        )
        self.assertEqual(
            closed_monthly_options_expiry["disposition"],
            "NO_CANDIDATE_CLOSE_DERIBIT_MONTHLY_OPTIONS_EXPIRY_RISK_VETO_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_monthly_options_expiry["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_monthly_options_expiry["prohibited_reopen"])
        closed_bls_cpi = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-bls-cpi-scheduled-release-day-risk-veto"
        )
        self.assertEqual(
            closed_bls_cpi["disposition"],
            "NO_CANDIDATE_CLOSE_BLS_CPI_SCHEDULED_RELEASE_DAY_RISK_VETO_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_bls_cpi["evidence_bindings"]],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SEALED_PRE_RESULT_CALENDAR_WEEKDAY_AMENDMENT",
                "SEALED_PRE_RESULT_TERMINAL_BOUNDARY_AMENDMENT",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_bls_cpi["prohibited_reopen"])
        closed_bls_employment = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-bls-employment-situation-scheduled-release-day-risk-veto"
        )
        self.assertEqual(
            closed_bls_employment["disposition"],
            "NO_CANDIDATE_CLOSE_BLS_EMPLOYMENT_SITUATION_SCHEDULED_RELEASE_DAY_RISK_VETO_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_bls_employment["evidence_bindings"]],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "FROZEN_PRE_OUTCOME_OFFICIAL_EVENT_CALENDAR",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_bls_employment["prohibited_reopen"])
        closed_breakeven_reflation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-five-year-breakeven-inflation-reflation-support-long-cash"
        )
        self.assertEqual(
            closed_breakeven_reflation["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_FIVE_YEAR_BREAKEVEN_INFLATION_REFLATION_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_breakeven_reflation["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_PRE_OUTCOME_SOURCE_FEASIBILITY_SPEC",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_SOURCE_AND_SUPPORT_PROBE",
                "SEALED_FRED_T5YIE_SOURCE_AND_PRE_OUTCOME_SUPPORT_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_breakeven_reflation["prohibited_reopen"])
        closed_initial_claims = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-initial-claims-easing-labor-resilience-long-cash"
        )
        self.assertEqual(
            closed_initial_claims["disposition"],
            "DATA_REJECT_CLOSE_ICSA_LABOR_RESILIENCE_FAMILY_BEFORE_BTC_OUTCOME_ACCESS",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_initial_claims["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_SOURCE_GATE_DECISION",
                "FROZEN_PRE_FACTOR_SOURCE_FEASIBILITY_SPEC",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_FAIL_CLOSED_SOURCE_AND_SUPPORT_PROBE",
                "SEALED_FRED_ICSA_SOURCE_AND_PRE_OUTCOME_SUPPORT_BUNDLE",
            ],
        )
        self.assertTrue(closed_initial_claims["prohibited_reopen"])
        closed_business_applications = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-business-applications-yoy-growth-long-cash"
        )
        self.assertEqual(
            closed_business_applications["disposition"],
            "DATA_REJECT_CLOSE_BUSINESS_APPLICATIONS_YOY_GROWTH_FAMILY_BEFORE_BTC_OUTCOME_ACCESS",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_business_applications["evidence_bindings"]
            ],
            [
                "SEALED_PRE_OUTCOME_SOURCE_GATE_DECISION",
                "FROZEN_PRE_FACTOR_SOURCE_FEASIBILITY_SPEC",
                "SEALED_PRIMARY_OFFICIAL_AND_ADVERSARIAL_PRIOR",
                "FROZEN_FAILED_CREATE_ONCE_SOURCE_PROBE",
            ],
        )
        self.assertTrue(closed_business_applications["prohibited_reopen"])
        closed_native_issuance = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-coinmetrics-native-issuance-contraction-supply-pressure-long-cash"
        )
        self.assertEqual(
            closed_native_issuance["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_NATIVE_ISSUANCE_CONTRACTION_SUPPLY_PRESSURE_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_native_issuance["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_OFFICIAL_COMMUNITY_TRANSPORT_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_PRE_OUTCOME_SOURCE_FEASIBILITY_SPEC",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_SOURCE_AND_SUPPORT_PROBE",
                "SEALED_COINMETRICS_ISS_TOT_NTV_SOURCE_AND_PRE_OUTCOME_SUPPORT_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_native_issuance["prohibited_reopen"])
        closed_address_balance_growth = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-coinmetrics-address-balance-count-28d-growth-long-cash"
        )
        self.assertEqual(
            closed_address_balance_growth["disposition"],
            "DATA_REJECT_CLOSE_BTC_COINMETRICS_ADDRESS_BALANCE_COUNT_GROWTH_FAMILY_BEFORE_HYPOTHESIS_OR_BTC_OUTCOME",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_address_balance_growth["evidence_bindings"]],
            [
                "SEALED_PRE_HYPOTHESIS_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_ADVERSARIAL_AND_FREE_TRANSPORT_PREFLIGHT_PRIOR",
                "FROZEN_FAIL_CLOSED_SOURCE_AND_SUPPORT_PROBE",
                "SEALED_COINMETRICS_ADRBALCNT_SOURCE_AND_PRE_OUTCOME_SUPPORT_BUNDLE",
            ],
        )
        self.assertTrue(closed_address_balance_growth["prohibited_reopen"])
        closed_on_rrp_drawdown = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-on-rrp-drawdown-liquidity-support-long-cash"
        )
        self.assertEqual(
            closed_on_rrp_drawdown["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_ON_RRP_DRAWDOWN_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [binding["role"] for binding in closed_on_rrp_drawdown["evidence_bindings"]],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_OFFICIAL_SOURCE_PROBE",
                "SEALED_OFFICIAL_RRPONTSYD_SOURCE_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_on_rrp_drawdown["prohibited_reopen"])
        closed_reserve_balances_growth = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-reserve-balances-growth-liquidity-support-long-cash"
        )
        self.assertEqual(
            closed_reserve_balances_growth["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_RESERVE_BALANCES_GROWTH_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_reserve_balances_growth["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_OFFICIAL_SOURCE_PROBE",
                "SEALED_OFFICIAL_WRESBAL_SOURCE_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_reserve_balances_growth["prohibited_reopen"])
        closed_cftc_carry_crash_proxy = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-leveraged-fund-net-short-level-carry-crash-risk-veto"
        )
        self.assertEqual(
            closed_cftc_carry_crash_proxy["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_LEVERAGED_FUND_NET_SHORT_CARRY_CRASH_PROXY_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_cftc_carry_crash_proxy["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_cftc_carry_crash_proxy["prohibited_reopen"])
        closed_tga_liquidity_support = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-fred-tga-drawdown-liquidity-support-long-cash"
        )
        self.assertEqual(
            closed_tga_liquidity_support["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_FRED_TGA_DRAWDOWN_LIQUIDITY_SUPPORT_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_tga_liquidity_support["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_OFFICIAL_SOURCE_PROBE",
                "SEALED_OFFICIAL_WTREGEN_SOURCE_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_tga_liquidity_support["prohibited_reopen"])
        closed_exchange_net_inflow = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-coinmetrics-weekly-exchange-net-inflow-sell-pressure-risk-veto"
        )
        self.assertEqual(
            closed_exchange_net_inflow["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_COINMETRICS_WEEKLY_EXCHANGE_NET_INFLOW_SELL_PRESSURE_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in closed_exchange_net_inflow["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_PRE_OUTCOME_TRANSPORT_ERRATUM",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SPEC_HASH_BOUND_DETERMINISTIC_PRE_ECONOMIC_RUNNER",
                "FROZEN_FAIL_CLOSED_COINMETRICS_SOURCE_PROBE",
                "SEALED_COINMETRICS_SOURCE_BUNDLE",
                "BYTE_IDENTICAL_RUN1_AND_RUN2_PRE_ECONOMIC_EVIDENCE",
            ],
        )
        self.assertTrue(closed_exchange_net_inflow["prohibited_reopen"])
        cftc_trader_breadth = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-reportable-trader-breadth-growth-long-cash"
        )
        self.assertEqual(
            cftc_trader_breadth["disposition"],
            "PRIOR_REJECT_CLOSE_BTC_CFTC_REPORTABLE_TRADER_BREADTH_GROWTH_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in cftc_trader_breadth["evidence_bindings"]],
            [
                "SEALED_PRE_FACTOR_PRE_OUTCOME_PRIMARY_PRIOR_REJECT",
                "HASH_BOUND_PRIOR_REJECT_ACCEPTANCE",
            ],
        )
        self.assertTrue(cftc_trader_breadth["prohibited_reopen"])
        cftc_concentration = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-top4-net-concentration-level-long-cash"
        )
        self.assertEqual(
            cftc_concentration["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_TOP4_NET_CONCENTRATION_LEVEL_FAMILY_PRE_ECONOMIC",
        )
        self.assertTrue(cftc_concentration["prohibited_reopen"])
        cftc_nonreportable = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-nonreportable-net-position-change-long-cash"
        )
        self.assertEqual(
            cftc_nonreportable["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_NONREPORTABLE_NET_POSITION_CHANGE_FAMILY_PRE_ECONOMIC",
        )
        self.assertTrue(cftc_nonreportable["prohibited_reopen"])
        m2_liquidity = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-m2-liquidity-acceleration-long-cash"
        )
        self.assertEqual(
            m2_liquidity["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_M2_LIQUIDITY_ACCELERATION_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in m2_liquidity["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRE_OUTCOME_PRIOR",
                "FROZEN_V1_CAPABILITY_ERRATUM_WITHOUT_SCIENTIFIC_CHANGE",
                "SEALED_OFFICIAL_H6_SOURCE_IDENTITY_TIMING_AND_REVISION_BUNDLE",
            ],
        )
        self.assertTrue(m2_liquidity["prohibited_reopen"])
        cftc_open_interest = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-total-open-interest-growth-long-cash"
        )
        self.assertEqual(
            cftc_open_interest["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_TOTAL_OPEN_INTEREST_GROWTH_FAMILY_PRE_ECONOMIC",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in cftc_open_interest["evidence_bindings"]
            ],
            [
                "SEALED_PRE_ECONOMIC_HISTORICAL_DECISION",
                "FROZEN_V2_CAPABILITY_AMENDMENT_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRE_OUTCOME_PRIOR",
                "FROZEN_SCHEMA_VALID_PRE_OUTCOME_HYPOTHESIS",
                "FROZEN_V1_CAPABILITY_ERRATUM_WITHOUT_SCIENTIFIC_CHANGE",
            ],
        )
        self.assertTrue(cftc_open_interest["prohibited_reopen"])
        other_reportables = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-other-reportables-net-position-change-long-cash"
        )
        self.assertEqual(
            other_reportables["disposition"],
            "PRIOR_REJECT_CLOSE_BTC_CFTC_OTHER_REPORTABLES_NET_POSITION_CHANGE_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in other_reportables["evidence_bindings"]],
            [
                "SEALED_PRE_FACTOR_PRE_OUTCOME_PRIMARY_PRIOR_REJECT",
                "HASH_BOUND_PRIOR_REJECT_ACCEPTANCE",
            ],
        )
        self.assertTrue(other_reportables["prohibited_reopen"])
        dealer_positioning = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-cftc-dealer-net-position-change-long-cash"
        )
        self.assertEqual(
            dealer_positioning["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_CFTC_DEALER_NET_POSITION_CHANGE_FAMILY_PRE_ECONOMIC",
        )
        self.assertTrue(dealer_positioning["prohibited_reopen"])
        short_rate = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-us-treasury-3m-yield-easing-long-cash"
        )
        self.assertEqual(
            short_rate["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_US_TREASURY_3M_YIELD_EASING_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in short_rate["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "SEALED_BYTE_IDENTICAL_DIRECT_ECONOMIC_RUN",
            ],
        )
        self.assertTrue(short_rate["prohibited_reopen"])
        miner_revenue = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-miner-revenue-per-hash-momentum-long-cash"
        )
        self.assertEqual(
            miner_revenue["disposition"],
            "DUPLICATE_REJECT_CLOSE_BTC_MINER_REVENUE_PER_HASH_MOMENTUM_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in miner_revenue["evidence_bindings"]],
            [
                "SEALED_PRE_ECONOMIC_DUPLICATE_REJECT_DECISION",
                "FROZEN_PREREGISTRATION_AND_NONREDUNDANCY_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "SEALED_BYTE_IDENTICAL_PRE_ECONOMIC_NONREDUNDANCY_RUN",
            ],
        )
        self.assertTrue(miner_revenue["prohibited_reopen"])
        relative_strength = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-nasdaq-relative-strength-long-cash"
        )
        self.assertEqual(
            relative_strength["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_NASDAQ_RELATIVE_STRENGTH_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in relative_strength["evidence_bindings"]
            ],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "SEALED_BYTE_IDENTICAL_DIRECT_ECONOMIC_RUN",
            ],
        )
        self.assertTrue(relative_strength["prohibited_reopen"])
        cmf = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-chaikin-money-flow-long-cash"
        )
        self.assertEqual(
            cmf["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_CHAIKIN_MONEY_FLOW_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in cmf["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "SEALED_BYTE_IDENTICAL_DIRECT_ECONOMIC_RUN",
            ],
        )
        self.assertTrue(cmf["prohibited_reopen"])
        monthly_rebalanced = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-monthly-rebalanced-half-passive-half-dra-v1"
        )
        self.assertEqual(
            monthly_rebalanced["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_MONTHLY_REBALANCED_HALF_PASSIVE_HALF_DRA_V1_FAMILY",
        )
        self.assertTrue(monthly_rebalanced["prohibited_reopen"])
        cross_sectional_momentum = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-liquid-crypto-cross-sectional-momentum-long-only-source-reject"
        )
        self.assertEqual(
            cross_sectional_momentum["disposition"],
            "DATA_REJECT_CLOSE_LIQUID_CRYPTO_CROSS_SECTIONAL_MOMENTUM_FREE_ARCHIVE_FAMILY",
        )
        self.assertTrue(cross_sectional_momentum["prohibited_reopen"])
        movement_volume = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-h1-absolute-return-volume-coupling-entry-admission"
        )
        self.assertEqual(
            movement_volume["disposition"],
            "NO_MECHANISM_CLOSE_FEATURE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in movement_volume["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "SEALED_BYTE_IDENTICAL_DIRECT_ECONOMIC_RUN",
            ],
        )
        self.assertTrue(movement_volume["prohibited_reopen"])
        onchain_activity = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-onchain-activity-breadth-entry-admission"
        )
        self.assertEqual(
            onchain_activity["disposition"],
            "NO_CANDIDATE_CLOSE_ONCHAIN_ACTIVITY_BREADTH_DRA_ADMISSION_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in onchain_activity["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "REUSED_SEALED_SOURCE_NO_NEW_DOWNLOAD",
            ],
        )
        self.assertTrue(onchain_activity["prohibited_reopen"])
        stablecoin_liquidity = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-stablecoin-liquidity-growth-entry-admission"
        )
        self.assertEqual(
            stablecoin_liquidity["disposition"],
            "NO_CANDIDATE_CLOSE_STABLECOIN_LIQUIDITY_DRA_ADMISSION_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in stablecoin_liquidity["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
                "REUSED_SEALED_SOURCE_NO_NEW_DOWNLOAD",
            ],
        )
        self.assertTrue(stablecoin_liquidity["prohibited_reopen"])
        intraday_close_path_drawdown = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-intraday-close-path-drawdown-entry-admission"
        )
        self.assertEqual(
            intraday_close_path_drawdown["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_INTRADAY_CLOSE_PATH_DRAWDOWN_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in intraday_close_path_drawdown["evidence_bindings"]
            ],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
            ],
        )
        self.assertTrue(intraday_close_path_drawdown["prohibited_reopen"])
        late_day_activity = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-late-day-price-activity-entry-admission"
        )
        self.assertEqual(
            late_day_activity["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_LATE_DAY_PRICE_ACTIVITY_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in late_day_activity["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_EXECUTABLE_DATA_PATH_PRIOR",
            ],
        )
        self.assertTrue(late_day_activity["prohibited_reopen"])
        eth_relative_strength = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-eth-relative-strength-breadth-entry-admission"
        )
        self.assertEqual(
            eth_relative_strength["disposition"],
            "DATA_REJECT_CLOSE_BTC_ETH_REFERENCE_RATE_FREE_SOURCE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in eth_relative_strength["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_ADVERSARIAL_AND_SOURCE_BOUNDARY_PRIOR",
                "FROZEN_FAILED_CREATE_ONCE_SOURCE_PROBE",
            ],
        )
        self.assertTrue(eth_relative_strength["prohibited_reopen"])
        bitcoin_nvt = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-bitcoin-nvt-relative-utility-entry-admission"
        )
        self.assertEqual(
            bitcoin_nvt["disposition"],
            "DATA_REJECT_CLOSE_NVTADJ_FREE_SOURCE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in bitcoin_nvt["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_ADVERSARIAL_AND_SOURCE_BOUNDARY_PRIOR",
                "FROZEN_FAILED_CREATE_ONCE_SOURCE_PROBE",
            ],
        )
        self.assertTrue(bitcoin_nvt["prohibited_reopen"])
        gold_implied_volatility = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-gold-implied-volatility-calm-state-entry-admission"
        )
        self.assertEqual(
            gold_implied_volatility["disposition"],
            "DATA_REJECT_CLOSE_GVZ_SOURCE_SCHEMA_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in gold_implied_volatility["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_DATA_REJECT_DECISION",
                "SEALED_PRIMARY_ADVERSARIAL_AND_SOURCE_BOUNDARY_PRIOR",
                "FROZEN_FAILED_CREATE_ONCE_SOURCE_PROBE",
            ],
        )
        self.assertTrue(gold_implied_volatility["prohibited_reopen"])
        nasdaq_diversification = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-btc-nasdaq-diversification-state-entry-admission"
        )
        self.assertEqual(
            nasdaq_diversification["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_NASDAQ_DIVERSIFICATION_STATE_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in nasdaq_diversification["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_FACTOR_BOUNDARY_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "REUSED_SEALED_NASDAQCOM_SOURCE_NO_NEW_DOWNLOAD",
            ],
        )
        self.assertTrue(nasdaq_diversification["prohibited_reopen"])
        epu = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-dra-us-epu-hedge-entry-admission"
        )
        self.assertEqual(
            epu["disposition"],
            "NO_CANDIDATE_CLOSE_US_EPU_HEDGE_ENTRY_ADMISSION_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in epu["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_SOURCE_FEASIBILITY_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
                "SEALED_CREATE_ONCE_FRED_SOURCE_NO_BTC_OUTCOME",
            ],
        )
        self.assertTrue(epu["prohibited_reopen"])
        realized_skewness = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-intraday-realized-skewness-entry-admission"
        )
        self.assertEqual(
            realized_skewness["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_REALIZED_SKEWNESS_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in realized_skewness["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
            ],
        )
        self.assertTrue(realized_skewness["prohibited_reopen"])
        h1_first_extreme_order = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-h1-first-extreme-order-entry-admission"
        )
        self.assertEqual(
            h1_first_extreme_order["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_H1_FIRST_EXTREME_ORDER_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in h1_first_extreme_order["evidence_bindings"]
            ],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "FROZEN_PRE_OUTCOME_HYPOTHESIS",
            ],
        )
        self.assertTrue(h1_first_extreme_order["prohibited_reopen"])
        cftc_asset_manager = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-cftc-asset-manager-contrarian-entry-admission"
        )
        self.assertEqual(
            cftc_asset_manager["disposition"],
            "NO_CANDIDATE_CLOSE_CFTC_ASSET_MANAGER_CONTRARIAN_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in cftc_asset_manager["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_CITATION_METADATA_ERRATUM",
            ],
        )
        self.assertTrue(cftc_asset_manager["prohibited_reopen"])
        volatility_instability = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-monthly-volatility-of-volatility-half-risk"
        )
        self.assertEqual(
            volatility_instability["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_MONTHLY_VOLATILITY_OF_VOLATILITY_HALF_RISK_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in volatility_instability["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
            ],
        )
        self.assertTrue(volatility_instability["prohibited_reopen"])
        vix_risk_state = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-vix-risk-state-long-cash"
        )
        self.assertEqual(
            vix_risk_state["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_VIX_RISK_STATE_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in vix_risk_state["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
                "SEALED_EXTERNAL_SOURCE_PROVENANCE",
            ],
        )
        self.assertTrue(vix_risk_state["prohibited_reopen"])
        vix_term_structure = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-vix-vix3m-term-structure-long-cash"
        )
        self.assertEqual(
            vix_term_structure["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_VIX_TERM_STRUCTURE_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in vix_term_structure["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_ALIGNMENT_AND_TIMING",
            ],
        )
        self.assertTrue(vix_term_structure["prohibited_reopen"])
        high_yield_oas = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-high-yield-oas-risk-state-source-unavailable"
        )
        self.assertEqual(
            high_yield_oas["disposition"],
            "DATA_PATH_REJECT_INSUFFICIENT_FREE_HISTORICAL_COVERAGE",
        )
        self.assertEqual(
            [binding["role"] for binding in high_yield_oas["evidence_bindings"]],
            [
                "SEALED_PRE_OUTCOME_SOURCE_FEASIBILITY_REJECT",
                "MANAGER_EXCLUDE_ACCEPTANCE",
            ],
        )
        self.assertTrue(high_yield_oas["prohibited_reopen"])
        treasury_term_spread = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-us-treasury-term-spread-noninversion-long-cash"
        )
        self.assertEqual(
            treasury_term_spread["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_TREASURY_TERM_SPREAD_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in treasury_term_spread["evidence_bindings"]
            ],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_TIMING_SERIES_BREAK_AND_REVISION_IDENTITY",
            ],
        )
        self.assertTrue(treasury_term_spread["prohibited_reopen"])
        nasdaq_trend = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-nasdaq-composite-external-trend-long-cash"
        )
        self.assertEqual(
            nasdaq_trend["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_NASDAQ_COMPOSITE_TREND_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in nasdaq_trend["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
                "SEALED_SOURCE_PROVENANCE_TIMING_REVISION_AND_COPYRIGHT_IDENTITY",
            ],
        )
        self.assertTrue(nasdaq_trend["prohibited_reopen"])
        intraday_price_path = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-intraday-price-path-efficiency-entry-admission"
        )
        self.assertEqual(
            intraday_price_path["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_INTRADAY_PRICE_PATH_EFFICIENCY_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in intraday_price_path["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(intraday_price_path["prohibited_reopen"])
        weekend_calendar = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-weekend-calendar-entry-admission"
        )
        self.assertEqual(
            weekend_calendar["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_WEEKEND_CALENDAR_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in weekend_calendar["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(weekend_calendar["prohibited_reopen"])
        autocorrelation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-h1-lag1-return-autocorrelation-entry-admission"
        )
        self.assertEqual(
            autocorrelation["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_H1_LAG1_RETURN_AUTOCORRELATION_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in autocorrelation["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(autocorrelation["prohibited_reopen"])
        realized_performance = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-realized-performance-entry-admission"
        )
        self.assertEqual(
            realized_performance["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_REALIZED_PERFORMANCE_FACTOR_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in realized_performance["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(realized_performance["prohibited_reopen"])
        turn_of_month = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-turn-of-month-last-day-plus-three-long-cash"
        )
        self.assertEqual(
            turn_of_month["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_TURN_OF_MONTH_LAST_DAY_PLUS_THREE_FAMILY",
        )
        self.assertEqual(
            turn_of_month["duplicate_family_key"],
            "btc-turn-of-month-last-day-plus-three-long-cash",
        )
        self.assertTrue(turn_of_month["prohibited_reopen"])
        volatility_target = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-monthly-30d-volatility-target-40pct"
        )
        self.assertEqual(
            volatility_target["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_MONTHLY_30D_VOLATILITY_TARGET_40PCT_FAMILY",
        )
        self.assertTrue(volatility_target["prohibited_reopen"])
        monthly_momentum = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-monthly-12m-time-series-momentum-long-cash"
        )
        self.assertEqual(
            monthly_momentum["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_MONTHLY_12M_TIME_SERIES_MOMENTUM_FAMILY",
        )
        self.assertTrue(monthly_momentum["prohibited_reopen"])
        static_allocation = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-static-half-passive-half-dra-v1"
        )
        self.assertEqual(
            static_allocation["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_STATIC_HALF_PASSIVE_HALF_DRA_V1_FAMILY",
        )
        self.assertTrue(static_allocation["prohibited_reopen"])
        donchian = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-donchian-20d-10d-standalone"
        )
        self.assertEqual(
            donchian["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DONCHIAN_20D_10D_STANDALONE_FAMILY",
        )
        self.assertTrue(donchian["prohibited_reopen"])
        microstructure = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-microstructure-dra-entry-admission-v3r1"
        )
        self.assertEqual(
            microstructure["disposition"],
            "NO_EVIDENCE_CLOSE_MICROSTRUCTURE_SOURCE_INTEGRITY_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in microstructure["evidence_bindings"]],
            [
                "SOURCE_INTEGRITY_OPPORTUNITY_COST_CLOSURE",
                "FROZEN_SOURCE_RECOVERY_CONTRACT",
                "FROZEN_PREOUTCOME_ADMISSION_CONTRACT",
            ],
        )
        self.assertTrue(microstructure["prohibited_reopen"])
        cftc = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-cftc-leveraged-money-positioning-delta"
        )
        self.assertEqual(
            cftc["disposition"],
            "NO_CANDIDATE_CLOSE_CFTC_TFF_FACTOR_FAMILY",
        )
        self.assertTrue(cftc["prohibited_reopen"])
        close_location = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-close-location-entry-admission"
        )
        self.assertEqual(
            close_location["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_CLOSE_LOCATION_FACTOR_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in close_location["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(close_location["prohibited_reopen"])
        h1_volume_weighted_close_location = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-h1-volume-weighted-close-location-entry-admission"
        )
        self.assertEqual(
            h1_volume_weighted_close_location["disposition"],
            "NO_CANDIDATE_CLOSE_DRA_H1_VOLUME_WEIGHTED_CLOSE_LOCATION_FACTOR_FAMILY",
        )
        self.assertTrue(h1_volume_weighted_close_location["prohibited_reopen"])
        amihud = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-amihud-illiquidity-entry-admission"
        )
        self.assertEqual(
            amihud["disposition"],
            "DRA_AMIHUD_ILLIQUIDITY_EVIDENCE_INSUFFICIENT",
        )
        self.assertEqual(
            [binding["role"] for binding in amihud["evidence_bindings"]],
            ["SEALED_DATA_REJECT_RESULT", "MANAGER_EXCLUDE_ACCEPTANCE"],
        )
        self.assertTrue(amihud["prohibited_reopen"])
        lagged_volatility = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-dra-lagged-realized-volatility-entry-admission"
        )
        self.assertEqual(
            lagged_volatility["disposition"],
            "CLOSE_FEATURE_FAMILY_WITHOUT_TUNING",
        )
        self.assertEqual(
            [
                binding["role"]
                for binding in lagged_volatility["evidence_bindings"]
            ],
            [
                "LEGACY_SEALED_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_PRIOR",
            ],
        )
        self.assertTrue(lagged_volatility["prohibited_reopen"])
        sma200 = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-daily-sma200-long-cash"
        )
        self.assertEqual(
            sma200["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_SMA200_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in sma200["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_FINGERPRINT_PRIOR",
            ],
        )
        self.assertTrue(sma200["prohibited_reopen"])
        bollinger = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-daily-bollinger20-2-long-cash"
        )
        self.assertEqual(
            bollinger["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_BOLLINGER20_2_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in bollinger["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_FINGERPRINT_PRIOR",
            ],
        )
        self.assertTrue(bollinger["prohibited_reopen"])
        psar = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"] == "closed-btc-daily-psar-long-cash"
        )
        self.assertEqual(
            psar["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_DAILY_PSAR_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in psar["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_ADVERSARIAL_AND_FINGERPRINT_PRIOR",
            ],
        )
        self.assertTrue(psar["prohibited_reopen"])
        intraday_session = next(
            family
            for family in catalog["closed_families"]
            if family["family_id"]
            == "closed-btc-intraday-fixed-utc-session-long-cash"
        )
        self.assertEqual(
            intraday_session["disposition"],
            "NO_CANDIDATE_CLOSE_BTC_INTRADAY_FIXED_UTC_SESSION_LONG_CASH_FAMILY",
        )
        self.assertEqual(
            [binding["role"] for binding in intraday_session["evidence_bindings"]],
            [
                "SEALED_HISTORICAL_ECONOMIC_DECISION",
                "FROZEN_PREREGISTRATION_MANIFEST",
                "SEALED_PRIMARY_AND_ADVERSARIAL_PRIOR",
            ],
        )
        self.assertTrue(intraday_session["prohibited_reopen"])
        self.assertTrue(
            all(
                binding["verified"]
                for family in catalog["families"] + catalog["closed_families"]
                for binding in family["evidence_bindings"]
            )
        )

    def test_closed_microstructure_source_does_not_block_open_family_ranking(self) -> None:
        snapshot = build_candidate_funnel(
            _registry(),
            microstructure=_microstructure(),
            repo_root=REPO_ROOT,
        )

        self.assertEqual(snapshot["status"], "READY")
        self.assertEqual(snapshot["summary"]["open_family_count"], 5)
        self.assertEqual(snapshot["summary"]["closed_family_count"], 147)
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 0)
        self.assertEqual(snapshot["summary"]["active_experiment_count"], 0)
        self.assertEqual(snapshot["summary"]["candidate_oos_count"], 0)
        self.assertNotIn(
            "microstructure-dra-entry-admission",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        self.assertEqual(snapshot["summary"]["integrity_blocked_family_count"], 0)
        self.assertNotIn(
            "btc-donchian-20d-10d-standalone",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        forward = {
            family["family_id"]: family
            for family in snapshot["ranked_families"]
            if family["canonical_binding"]["kind"] == "FORWARD_MECHANISM"
        }
        self.assertEqual(forward["dra-entry-volume-confirmation-20d"]["stage"], "FORWARD_EVIDENCE")
        self.assertEqual(forward["dra-entry-volume-confirmation-20d"]["estimated_days_to_next_gate"], 89)
        self.assertTrue(snapshot["safety"]["read_only_derived_view"])
        self.assertFalse(snapshot["safety"]["canonical_state_write"])
        self.assertFalse(snapshot["safety"]["second_timer_or_writer"])
        self.assertFalse(snapshot["safety"]["shadow_paper_live"])

    def test_evidence_ready_mechanism_is_not_counted_as_formal_candidate(self) -> None:
        snapshot = build_candidate_funnel(
            _registry(eligible=[VOLUME]),
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        volume = next(
            family
            for family in snapshot["ranked_families"]
            if family["family_id"] == "dra-entry-volume-confirmation-20d"
        )
        self.assertEqual(snapshot["status"], "READY")
        self.assertEqual(volume["stage"], "READY_FOR_HYPOTHESIS")
        self.assertEqual(volume["rank"], 1)
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 0)

    def test_frozen_candidate_is_linked_to_its_pool_family(self) -> None:
        registry = _registry()
        registry["experiments"] = [
            {
                "experiment_id": "volume-candidate",
                "title": "Volume candidate",
                "adapter": "dra-forward-entry-admission-v1",
                "stage": "PREREGISTERED",
                "outcome": None,
                "candidate_mechanism_key": VOLUME,
                "candidate_frozen_at": "2026-11-16T00:00:00Z",
                "oos_evidence_trigger_id": "volume-candidate-oos",
                "updated_at": "2026-11-16T00:00:00Z",
            }
        ]
        registry["evidence_triggers"].append(
            {
                "trigger_id": "volume-candidate-oos",
                "purpose": "CANDIDATE_OOS",
                "status": "WAITING",
            }
        )

        snapshot = build_candidate_funnel(
            registry,
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        volume = next(
            family
            for family in snapshot["ranked_families"]
            if family["family_id"] == "dra-entry-volume-confirmation-20d"
        )
        self.assertEqual(volume["stage"], "CANDIDATE_FROZEN")
        self.assertEqual(volume["progress"]["experiment_id"], "volume-candidate")
        self.assertEqual(snapshot["summary"]["formal_candidate_count"], 1)
        self.assertEqual(snapshot["summary"]["active_experiment_count"], 1)
        self.assertEqual(snapshot["summary"]["candidate_oos_count"], 1)

    def test_single_lane_constraints_fail_closed(self) -> None:
        registry = _registry()
        registry["experiments"] = [
            {"experiment_id": "active-a", "stage": "DESIGN"},
            {"experiment_id": "active-b", "stage": "VALIDATION"},
        ]
        registry["evidence_triggers"].extend(
            [
                {"trigger_id": "oos-a", "purpose": "CANDIDATE_OOS", "status": "OPEN"},
                {"trigger_id": "oos-b", "purpose": "CANDIDATE_OOS", "status": "OPEN"},
            ]
        )

        snapshot = build_candidate_funnel(
            registry,
            microstructure=_microstructure("WAITING_FOR_DAY"),
            repo_root=REPO_ROOT,
        )

        self.assertEqual(snapshot["status"], "INTEGRITY_BLOCKED")
        self.assertEqual(
            snapshot["constraint_violations"],
            ["MAXIMUM_ACTIVE_EXPERIMENTS_EXCEEDED", "MAXIMUM_CANDIDATE_OOS_EXCEEDED"],
        )

    def test_volatility_activation_promotes_only_to_forward_evidence(self) -> None:
        receipt = _volatility_receipt()
        with tempfile.TemporaryDirectory() as directory, patch(
            "research_pipeline.candidate_funnel."
            "prepare_forward_volatility_persistence_activation",
            return_value=ActivationDecision(
                receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
            ),
        ), patch(
            "research_pipeline.candidate_funnel.resolve_active_forward_trigger_lineage",
            return_value=object(),
        ), patch(
            "research_pipeline.candidate_funnel._load_volatility_snapshots",
            return_value=[],
        ):
            snapshot = build_candidate_funnel(
                _registry(),
                microstructure=_microstructure("WAITING_FOR_DAY"),
                heartbeat_state={
                    "last_success": "2026-08-18T01:05:00Z",
                    ACTIVATION_STATE_KEY: receipt,
                },
                state_root=Path(directory),
                as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                repo_root=REPO_ROOT,
            )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("FORWARD_EVIDENCE", volatility["stage"])
        self.assertEqual(
            "FORWARD_VOLATILITY_PERSISTENCE",
            volatility["canonical_binding"]["kind"],
        )
        self.assertEqual(0, volatility["progress"]["episode_count"])
        self.assertFalse(volatility["progress"]["terminal"])
        self.assertEqual(0, snapshot["summary"]["formal_candidate_count"])

    def test_volatility_terminal_retain_is_not_a_formal_candidate(self) -> None:
        receipt = _volatility_receipt()
        terminal = _volatility_terminal(receipt, VOLATILITY_RETAIN)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "terminal-retain.json"
            path.write_text("{}\n", encoding="utf-8")
            with patch(
                "research_pipeline.candidate_funnel."
                "prepare_forward_volatility_persistence_activation",
                return_value=ActivationDecision(
                    receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
                ),
            ), patch(
                "research_pipeline.candidate_funnel."
                "resolve_active_forward_trigger_lineage",
                return_value=object(),
            ), patch(
                "research_pipeline.candidate_funnel._load_volatility_snapshots",
                return_value=[(path, terminal)],
            ):
                snapshot = build_candidate_funnel(
                    _registry(),
                    microstructure=_microstructure("WAITING_FOR_DAY"),
                    heartbeat_state={ACTIVATION_STATE_KEY: receipt},
                    state_root=Path(directory),
                    as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                    repo_root=REPO_ROOT,
                )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("READY_FOR_HYPOTHESIS", volatility["stage"])
        self.assertTrue(volatility["progress"]["terminal"])
        self.assertEqual(VOLATILITY_RETAIN, volatility["progress"]["disposition"])
        self.assertEqual(0, snapshot["summary"]["formal_candidate_count"])

    def test_volatility_terminal_close_becomes_dynamic_tombstone(self) -> None:
        receipt = _volatility_receipt()
        terminal = _volatility_terminal(receipt, VOLATILITY_CLOSE)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "terminal-close.json"
            path.write_text("{}\n", encoding="utf-8")
            with patch(
                "research_pipeline.candidate_funnel."
                "prepare_forward_volatility_persistence_activation",
                return_value=ActivationDecision(
                    receipt, False, "ACTIVATION_RECEIPT_REVALIDATED"
                ),
            ), patch(
                "research_pipeline.candidate_funnel."
                "resolve_active_forward_trigger_lineage",
                return_value=object(),
            ), patch(
                "research_pipeline.candidate_funnel._load_volatility_snapshots",
                return_value=[(path, terminal)],
            ):
                snapshot = build_candidate_funnel(
                    _registry(),
                    microstructure=_microstructure("WAITING_FOR_DAY"),
                    heartbeat_state={ACTIVATION_STATE_KEY: receipt},
                    state_root=Path(directory),
                    as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                    repo_root=REPO_ROOT,
                )

        self.assertNotIn(
            "btc-3pct-post-shock-volatility-persistence",
            [family["family_id"] for family in snapshot["ranked_families"]],
        )
        closed = _family(
            {"ranked_families": snapshot["closed_families"]},
            "btc-3pct-post-shock-volatility-persistence",
        )
        self.assertEqual("CLOSED", closed["stage"])
        self.assertEqual(VOLATILITY_CLOSE, closed["disposition"])
        self.assertTrue(closed["prohibited_reopen"])
        self.assertEqual(4, snapshot["summary"]["open_family_count"])

    def test_volatility_receipt_conflict_blocks_only_that_family(self) -> None:
        with tempfile.TemporaryDirectory() as directory, patch(
            "research_pipeline.candidate_funnel."
            "prepare_forward_volatility_persistence_activation",
            side_effect=ValueError("synthetic receipt conflict"),
        ):
            snapshot = build_candidate_funnel(
                _registry(),
                microstructure=_microstructure("WAITING_FOR_DAY"),
                heartbeat_state={ACTIVATION_STATE_KEY: _volatility_receipt()},
                state_root=Path(directory),
                as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                repo_root=REPO_ROOT,
            )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("INTEGRITY_BLOCKED", volatility["stage"])
        self.assertIn("synthetic receipt conflict", volatility["integrity_status"])
        self.assertEqual(
            ["btc-3pct-post-shock-volatility-persistence"],
            snapshot["summary"]["integrity_blocked_families"],
        )

    def test_lawful_rollover_retires_volatility_without_global_integrity_alert(
        self,
    ) -> None:
        receipt = _volatility_receipt()
        current_leaf = {
            "trigger_id": "prospective-mechanism-neutral-evidence-refresh-rollover-r3",
            "fingerprint": "c" * 64,
        }
        with tempfile.TemporaryDirectory() as directory, patch(
            "research_pipeline.candidate_funnel."
            "prepare_forward_volatility_persistence_activation",
            return_value=ActivationDecision(
                receipt, False, ACTIVATION_RECEIPT_RETIRED
            ),
        ), patch(
            "research_pipeline.candidate_funnel."
            "resolve_active_forward_trigger_lineage",
            return_value=SimpleNamespace(leaf_trigger=current_leaf),
        ), patch(
            "research_pipeline.candidate_funnel._load_volatility_snapshots"
        ) as load_snapshots:
            snapshot = build_candidate_funnel(
                _registry(),
                microstructure=_microstructure("WAITING_FOR_DAY"),
                heartbeat_state={ACTIVATION_STATE_KEY: receipt},
                state_root=Path(directory),
                as_of=datetime(2026, 8, 18, 2, 5, tzinfo=timezone.utc),
                repo_root=REPO_ROOT,
            )

        volatility = _family(snapshot, "btc-3pct-post-shock-volatility-persistence")
        self.assertEqual("DEFERRED", volatility["stage"])
        self.assertFalse(volatility["progress"]["evidence_collection_active"])
        self.assertEqual(
            current_leaf["trigger_id"],
            volatility["progress"]["current_leaf_trigger_id"],
        )
        self.assertEqual("READY", snapshot["status"])
        self.assertEqual([], snapshot["summary"]["integrity_blocked_families"])
        load_snapshots.assert_not_called()

    def test_status_wrapper_fails_closed_when_catalog_is_outside_repo_root(self) -> None:
        result = candidate_funnel_status(
            _registry(),
            microstructure=_microstructure(),
            repo_root=REPO_ROOT / "research_pipeline" / "tests",
        )

        self.assertEqual(result["status"], "INTEGRITY_BLOCKED")
        self.assertFalse(result["safety"]["canonical_state_write"])
        self.assertFalse(result["safety"]["second_timer_or_writer"])


def _family(snapshot: dict[str, object], family_id: str) -> dict[str, object]:
    return next(
        family
        for family in snapshot["ranked_families"]
        if family["family_id"] == family_id
    )


def _volatility_receipt() -> dict[str, object]:
    return {
        "activated_at": "2026-08-18T01:05:00Z",
        "worker_release_id": "20260818T010000Z",
        "worker_source_commit": "a" * 40,
        "leaf_trigger_id": "prospective-mechanism-neutral-evidence-refresh-rollover",
        "leaf_trigger_fingerprint": "b" * 64,
    }


def _volatility_terminal(
    receipt: dict[str, object], disposition: str
) -> dict[str, object]:
    return {
        "episodes": [{} for _ in range(12)],
        "terminal": True,
        "disposition": disposition,
        "activation_receipt_sha256": hashlib.sha256(
            _volatility_canonical_bytes(receipt)
        ).hexdigest(),
    }


if __name__ == "__main__":
    unittest.main()
