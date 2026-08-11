"""Pure synthetic-only evaluator for one frozen CFTC TFF factor contract."""

from __future__ import annotations

import hashlib
import json
import math
import re
from copy import deepcopy
from datetime import date, datetime, timedelta, timezone
from decimal import Decimal, localcontext
from functools import lru_cache
from math import comb
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


AUTHORIZATION = "RESEARCH_ONLY_NOT_SHADOW_PAPER_OR_LIVE"
FACTOR_IDENTITY = "CFTC_TFF_LEV_MONEY_NET_PCT_OI_WEEKLY_DELTA_CONTINUATION_168H_V1"
SOURCE_LABEL = "CFTC_CME_BITCOIN_TFF_FUTURES_ONLY_V2"
SOURCE_CONTRACT_SHA256 = "726d7ebff05d1c9fb5df9399996ff9817b28025d9d696f207191ec8c62e7dde5"
SOURCE_CONTRACT_SCHEMA_SHA256 = "f8daf4c6f9014c4874bb2c62865597c764ffd46833b4b31222fbc20617013a73"
OBSERVATION_SCHEMA_SHA256 = "44d46dc857dab5d874f6b730060c798266e6ed386597a7ab18245274ee98f53b"
ORDERED_FIELDS_SHA256 = "b11b7d92722962c7f3a40d8c84c228b2cc39fab0caf0afb2bd86d92d33af283b"
LONG_FIELD = "Pct_of_OI_Lev_Money_Long_All"
SHORT_FIELD = "Pct_of_OI_Lev_Money_Short_All"
DECIMAL_PATTERN = re.compile(r"^[+-]?(?:[0-9]+(?:[.][0-9]+)?|[.][0-9]+)$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
UTC_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")
OFFSET_PATTERN = re.compile(r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}$")
UPPER_ID_PATTERN = re.compile(r"^[A-Z0-9][A-Z0-9_.-]{0,127}$")
_HERE = Path(__file__).resolve().parent
_CONTRACT_PATH = _HERE / "cftc-tff-lev-money-net-pct-oi-delta-factor-contract.v1.json"
_CONTRACT_SCHEMA_PATH = _HERE / "cftc-tff-lev-money-net-pct-oi-delta-factor-contract.v1.schema.json"
_EVALUATION_SCHEMA_PATH = _HERE / "cftc-tff-lev-money-net-pct-oi-delta-evaluation.v1.schema.json"


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _strict_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON object key")
        result[key] = value
    return result


def _load_adjacent_json(path: Path) -> tuple[dict[str, Any], bytes]:
    raw = path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_strict_object)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("adjacent frozen package JSON is invalid") from error
    if not isinstance(value, dict):
        raise ValueError("adjacent frozen package root must be an object")
    return value, raw


def _exact_keys(value: Mapping[str, Any], expected: set[str], label: str) -> None:
    if not isinstance(value, Mapping) or set(value) != expected:
        raise ValueError(f"{label} must be a closed object")


def _assert_closed_schema(value: Any) -> None:
    if isinstance(value, dict):
        if value.get("type") == "object" and value.get("additionalProperties") is not False:
            raise ValueError("every schema object must be closed")
        for child in value.values():
            _assert_closed_schema(child)
    elif isinstance(value, list):
        for child in value:
            _assert_closed_schema(child)


@lru_cache(maxsize=1)
def frozen_package() -> dict[str, Any]:
    contract, contract_raw = _load_adjacent_json(_CONTRACT_PATH)
    contract_schema, contract_schema_raw = _load_adjacent_json(_CONTRACT_SCHEMA_PATH)
    evaluation_schema, evaluation_schema_raw = _load_adjacent_json(_EVALUATION_SCHEMA_PATH)
    _assert_closed_schema(contract_schema)
    _assert_closed_schema(evaluation_schema)
    if contract_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise ValueError("factor contract schema is not Draft 2020-12")
    if evaluation_schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise ValueError("evaluation schema is not Draft 2020-12")
    required = {
        "schema_version", "contract_id", "authorization", "document_status",
        "factor_identity", "source_binding", "ordered_fields", "formula",
        "eligibility", "outcome", "sample_policy", "review_gates",
        "dispositions", "architecture", "readiness",
    }
    _exact_keys(contract, required, "factor contract")
    if contract["authorization"] != AUTHORIZATION or contract["factor_identity"] != FACTOR_IDENTITY:
        raise ValueError("factor contract identity drift")
    binding = contract["source_binding"]
    _exact_keys(binding, {"source_label", "observation_state", "source_contract_sha256", "source_contract_schema_sha256", "observation_schema_sha256"}, "source binding")
    if binding != {
        "source_label": SOURCE_LABEL,
        "observation_state": "NEW_REPORT_SEALED",
        "source_contract_sha256": SOURCE_CONTRACT_SHA256,
        "source_contract_schema_sha256": SOURCE_CONTRACT_SCHEMA_SHA256,
        "observation_schema_sha256": OBSERVATION_SCHEMA_SHA256,
    }:
        raise ValueError("source binding drift")
    ordered_fields = contract["ordered_fields"]
    if not isinstance(ordered_fields, list) or len(ordered_fields) != 87 or len(set(ordered_fields)) != 87:
        raise ValueError("ordered field inventory is not exact")
    if sha256_bytes(canonical_json_bytes(ordered_fields)) != ORDERED_FIELDS_SHA256:
        raise ValueError("ordered fields do not match the frozen V2 set")
    if ordered_fields.count(LONG_FIELD) != 1 or ordered_fields.count(SHORT_FIELD) != 1:
        raise ValueError("factor field inventory drift")
    if contract["formula"]["fields"] != [LONG_FIELD, SHORT_FIELD]:
        raise ValueError("factor field selection drift")
    if contract["readiness"]["immediate_pnl_effect"] != "ZERO" or contract["readiness"]["immediate_drawdown_effect"] != "ZERO":
        raise ValueError("zero-effect boundary drift")
    return {
        "contract": contract,
        "ordered_fields": tuple(ordered_fields),
        "contract_sha256": sha256_bytes(contract_raw),
        "contract_schema_sha256": sha256_bytes(contract_schema_raw),
        "evaluation_schema_sha256": sha256_bytes(evaluation_schema_raw),
    }


def _sha256(value: Any, label: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must be lowercase SHA-256")
    return value


def _day(value: Any, label: str) -> date:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a date")
    try:
        parsed = date.fromisoformat(value)
    except ValueError as error:
        raise ValueError(f"{label} must be YYYY-MM-DD") from error
    if parsed.isoformat() != value:
        raise ValueError(f"{label} must be canonical YYYY-MM-DD")
    return parsed


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or UTC_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must be whole-second UTC")
    parsed = datetime.strptime(value, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    return parsed


def _timestamp_text(value: datetime) -> str:
    if value.tzinfo != timezone.utc or value.microsecond:
        raise ValueError("timestamp must be whole-second UTC")
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def _offset_timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str) or OFFSET_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must be a whole-second offset timestamp")
    parsed = datetime.fromisoformat(value)
    if parsed.utcoffset() not in {timedelta(hours=-4), timedelta(hours=-5)}:
        raise ValueError(f"{label} must preserve a New York offset")
    return parsed


def _upper_id(value: Any, label: str) -> str:
    if not isinstance(value, str) or UPPER_ID_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must use the frozen identifier grammar")
    return value


def parse_factor_decimal(value: Any) -> Decimal:
    if not isinstance(value, str):
        raise ValueError("factor value must be a string")
    trimmed = value.strip(" ")
    if DECIMAL_PATTERN.fullmatch(trimmed) is None:
        raise ValueError("factor value violates the frozen decimal grammar")
    parsed = Decimal(trimmed)
    if not parsed.is_finite() or parsed < Decimal("0") or parsed > Decimal("100"):
        raise ValueError("factor value is outside [0,100]")
    return parsed


def _positive_decimal(value: Any, label: str) -> Decimal:
    if not isinstance(value, str) or DECIMAL_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{label} must use the frozen decimal grammar")
    parsed = Decimal(value)
    if not parsed.is_finite() or parsed <= 0:
        raise ValueError(f"{label} must be positive")
    return parsed


def _decimal_text(value: Decimal) -> str:
    if value == 0:
        return "0"
    text = format(value, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text


_OBSERVATION_KEYS = {
    "schema_version", "document_type", "authorization", "source_label",
    "source_contract_sha256", "source_contract_schema_sha256",
    "observation_schema_sha256", "state", "expected_report_date",
    "release_proof", "scheduled_cycle_at", "evaluated_at", "state_evidence",
}
_EVIDENCE_KEYS = {
    "report_date", "received_at", "field_count", "raw_seal", "record_seal",
    "row_identity", "predecessor_sha256", "decision_schedule", "chain_sha256",
}


def _validate_observation(observation: Mapping[str, Any], row: Mapping[str, Any], label: str) -> dict[str, Any]:
    package = frozen_package()
    _exact_keys(observation, _OBSERVATION_KEYS, label)
    if observation["schema_version"] != "CFTC_CME_BITCOIN_TFF_OBSERVATION_V2" or observation["document_type"] != "OFFLINE_CFTC_TFF_HEADERLESS_SOURCE_EVALUATION_V2":
        raise ValueError(f"{label} is not a V2 observation")
    if observation["authorization"] != AUTHORIZATION or observation["source_label"] != SOURCE_LABEL:
        raise ValueError(f"{label} source boundary drift")
    if observation["source_contract_sha256"] != SOURCE_CONTRACT_SHA256 or observation["source_contract_schema_sha256"] != SOURCE_CONTRACT_SCHEMA_SHA256 or observation["observation_schema_sha256"] != OBSERVATION_SCHEMA_SHA256:
        raise ValueError(f"{label} schema hash binding drift")
    if observation["state"] != "NEW_REPORT_SEALED":
        raise ValueError(f"{label} is not NEW_REPORT_SEALED")
    evidence = observation["state_evidence"]
    _exact_keys(evidence, _EVIDENCE_KEYS, f"{label} state evidence")
    report_date = _day(evidence["report_date"], f"{label} report date")
    if observation["expected_report_date"] != report_date.isoformat():
        raise ValueError(f"{label} expected report date drift")
    received_at = _timestamp(evidence["received_at"], f"{label} received_at")
    _timestamp(observation["scheduled_cycle_at"], f"{label} scheduled_cycle_at")
    _timestamp(observation["evaluated_at"], f"{label} evaluated_at")
    release_proof = observation["release_proof"]
    _exact_keys(release_proof, {"release_schedule_version", "release_schedule_sha256", "coverage_start", "coverage_end", "expected_tuesday", "release_at", "release_timezone"}, f"{label} release proof")
    _upper_id(release_proof["release_schedule_version"], f"{label} release schedule version")
    _sha256(release_proof["release_schedule_sha256"], f"{label} release schedule")
    if release_proof["expected_tuesday"] != report_date.isoformat() or release_proof["release_timezone"] != "America/New_York":
        raise ValueError(f"{label} release proof drift")
    _day(release_proof["coverage_start"], f"{label} coverage_start")
    _day(release_proof["coverage_end"], f"{label} coverage_end")
    _offset_timestamp(release_proof["release_at"], f"{label} release_at")
    if evidence["field_count"] != 87:
        raise ValueError(f"{label} field count drift")
    raw_seal = evidence["raw_seal"]
    _exact_keys(raw_seal, {"raw_response_size_bytes", "raw_response_sha256"}, f"{label} raw seal")
    if isinstance(raw_seal["raw_response_size_bytes"], bool) or not isinstance(raw_seal["raw_response_size_bytes"], int) or raw_seal["raw_response_size_bytes"] < 0:
        raise ValueError(f"{label} raw size invalid")
    _sha256(raw_seal["raw_response_sha256"], f"{label} raw response")
    record_seal = evidence["record_seal"]
    _exact_keys(record_seal, {"selected_record_size_bytes", "selected_record_sha256", "selected_record_terminator", "canonical_row_sha256"}, f"{label} record seal")
    if isinstance(record_seal["selected_record_size_bytes"], bool) or not isinstance(record_seal["selected_record_size_bytes"], int) or record_seal["selected_record_size_bytes"] < 1 or record_seal["selected_record_terminator"] not in {"NONE", "LF", "CRLF", "CR"}:
        raise ValueError(f"{label} selected record seal invalid")
    _sha256(record_seal["selected_record_sha256"], f"{label} selected record")
    canonical_row_sha256 = _sha256(record_seal["canonical_row_sha256"], f"{label} canonical row")
    row_identity = evidence["row_identity"]
    identity_keys = {"market_and_exchange_names", "cftc_contract_market_code", "cftc_market_code", "cftc_commodity_code", "contract_units", "cftc_contract_market_code_quotes", "cftc_market_code_quotes", "cftc_commodity_code_quotes", "cftc_subgroup_code", "futonly_or_combined"}
    _exact_keys(row_identity, identity_keys, f"{label} row identity")
    if row_identity["market_and_exchange_names"] != "BITCOIN - CHICAGO MERCANTILE EXCHANGE" or row_identity["cftc_contract_market_code"] != "133741" or row_identity["cftc_contract_market_code_quotes"] != "133741" or row_identity["futonly_or_combined"] != "FutOnly":
        raise ValueError(f"{label} fixed row identity drift")
    if any(not isinstance(row_identity[key], str) or not row_identity[key] for key in identity_keys):
        raise ValueError(f"{label} row identity contains an empty value")
    schedule = evidence["decision_schedule"]
    _exact_keys(schedule, {"schedule_id", "schedule_version", "schedule_sha256", "decision_at"}, f"{label} decision schedule")
    _upper_id(schedule["schedule_id"], f"{label} schedule id")
    _upper_id(schedule["schedule_version"], f"{label} schedule version")
    _sha256(schedule["schedule_sha256"], f"{label} schedule")
    decision_at = _timestamp(schedule["decision_at"], f"{label} decision_at")
    if decision_at <= received_at:
        raise ValueError(f"{label} decision_at must be strictly after received_at")
    predecessor_sha256 = _sha256(evidence["predecessor_sha256"], f"{label} predecessor")
    chain_sha256 = _sha256(evidence["chain_sha256"], f"{label} chain")
    if not isinstance(row, Mapping) or set(row) != set(package["ordered_fields"]) or any(not isinstance(value, str) for value in row.values()):
        raise ValueError(f"{label} canonical row must contain exactly 87 frozen string fields")
    if sha256_bytes(canonical_json_bytes(dict(row))) != canonical_row_sha256:
        raise ValueError(f"{label} canonical row hash drift")
    if row["Market_and_Exchange_Names"] != row_identity["market_and_exchange_names"] or row["CFTC_Contract_Market_Code"] != "133741" or row["CFTC_Contract_Market_Code_Quotes"] != "133741" or row["FutOnly_or_Combined"] != "FutOnly":
        raise ValueError(f"{label} canonical row identity drift")
    try:
        compact_date = datetime.strptime(row["As_of_Date_In_Form_YYMMDD"], "%y%m%d").date()
        dashed_date = datetime.strptime(row["Report_Date_as_MM_DD_YYYY"], "%Y-%m-%d").date()
    except ValueError as error:
        raise ValueError(f"{label} canonical row date grammar drift") from error
    if compact_date != dashed_date or dashed_date != report_date or report_date.weekday() != 1:
        raise ValueError(f"{label} canonical row dates are not the same Tuesday")
    return {
        "report_date": report_date,
        "received_at": received_at,
        "decision_at": decision_at,
        "predecessor_sha256": predecessor_sha256,
        "chain_sha256": chain_sha256,
        "canonical_row_sha256": canonical_row_sha256,
        "level": parse_factor_decimal(row[LONG_FIELD]) - parse_factor_decimal(row[SHORT_FIELD]),
    }


_TRANSITION_KEYS = {
    "transition_state", "review_status", "prior_report_date", "current_report_date",
    "prior_chain_sha256", "current_chain_sha256", "prior_canonical_row_sha256",
    "current_canonical_row_sha256", "decision_at", "prior_level", "current_level",
    "factor_delta", "factor_sign", "anchor_at", "anchor_artifact_sha256",
    "outcome_terminal_at", "outcome_terminal_artifact_sha256",
    "outcome_terminal_chain_sha256", "raw_return_168h", "signed_response_168h",
    "sign_adjusted_mae_168h", "episode_sha256",
}


def _seal_transition(value: dict[str, Any]) -> dict[str, Any]:
    sealed = dict(value)
    sealed.pop("episode_sha256", None)
    sealed["episode_sha256"] = sha256_bytes(canonical_json_bytes(sealed))
    return sealed


def _verify_transition(value: Mapping[str, Any]) -> None:
    _exact_keys(value, _TRANSITION_KEYS, "transition")
    expected = dict(value)
    actual = expected.pop("episode_sha256")
    _sha256(actual, "episode")
    if sha256_bytes(canonical_json_bytes(expected)) != actual:
        raise ValueError("episode SHA-256 drift")


def build_transition(
    prior_observation: Mapping[str, Any],
    prior_row: Mapping[str, str],
    current_observation: Mapping[str, Any],
    current_row: Mapping[str, str],
    anchor_proof: Mapping[str, Any] | None = None,
    outcome_intervals: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    prior = _validate_observation(prior_observation, prior_row, "prior observation")
    current = _validate_observation(current_observation, current_row, "current observation")
    if current["report_date"] - prior["report_date"] != timedelta(days=7):
        raise ValueError("report dates must be exactly seven days apart")
    if current["predecessor_sha256"] != prior["chain_sha256"]:
        raise ValueError("current predecessor does not equal prior chain")
    prior_level = prior["level"]
    current_level = current["level"]
    factor_delta = current_level - prior_level
    if not Decimal("-100") <= prior_level <= Decimal("100") or not Decimal("-100") <= current_level <= Decimal("100") or not Decimal("-200") <= factor_delta <= Decimal("200"):
        raise ValueError("factor arithmetic bounds drift")
    common = {
        "prior_report_date": prior["report_date"].isoformat(),
        "current_report_date": current["report_date"].isoformat(),
        "prior_chain_sha256": prior["chain_sha256"],
        "current_chain_sha256": current["chain_sha256"],
        "prior_canonical_row_sha256": prior["canonical_row_sha256"],
        "current_canonical_row_sha256": current["canonical_row_sha256"],
        "decision_at": _timestamp_text(current["decision_at"]),
        "prior_level": _decimal_text(prior_level),
        "current_level": _decimal_text(current_level),
        "factor_delta": _decimal_text(factor_delta),
    }
    if factor_delta == 0:
        if anchor_proof is not None or outcome_intervals is not None:
            raise ValueError("zero factor must not consume outcome evidence")
        return _seal_transition({
            "transition_state": "NO_FACTOR_ACTION", "review_status": "NO_FACTOR_ACTION",
            **common, "factor_sign": 0, "anchor_at": None,
            "anchor_artifact_sha256": None, "outcome_terminal_at": None,
            "outcome_terminal_artifact_sha256": None,
            "outcome_terminal_chain_sha256": None, "raw_return_168h": None,
            "signed_response_168h": None, "sign_adjusted_mae_168h": None,
        })
    factor_sign = 1 if factor_delta > 0 else -1
    if anchor_proof is None or outcome_intervals is None:
        raise ValueError("nonzero factor requires frozen anchor and outcome evidence")
    _exact_keys(anchor_proof, {"symbol", "interval", "anchor_at", "anchor_close", "anchor_artifact_sha256", "first_complete_close_strictly_after_decision"}, "anchor proof")
    if anchor_proof["symbol"] != "BTCUSDT" or anchor_proof["interval"] != "1h" or anchor_proof["first_complete_close_strictly_after_decision"] is not True:
        raise ValueError("anchor proof does not assert the frozen first-close rule")
    anchor_at = _timestamp(anchor_proof["anchor_at"], "anchor_at")
    if anchor_at <= current["decision_at"]:
        raise ValueError("anchor must be strictly after decision_at")
    anchor_close = _positive_decimal(anchor_proof["anchor_close"], "anchor close")
    anchor_artifact_sha256 = _sha256(anchor_proof["anchor_artifact_sha256"], "anchor artifact")
    if not isinstance(outcome_intervals, Sequence) or isinstance(outcome_intervals, (str, bytes)) or len(outcome_intervals) != 168:
        raise ValueError("outcome must contain exactly 168 intervals")
    path_signed_returns = [Decimal("0")]
    terminal: Mapping[str, Any] | None = None
    for index, interval in enumerate(outcome_intervals, start=1):
        _exact_keys(interval, {"closed_at", "close", "artifact_sha256", "chain_sha256"}, f"outcome interval {index}")
        closed_at = _timestamp(interval["closed_at"], f"outcome interval {index} closed_at")
        if closed_at != anchor_at + timedelta(hours=index):
            raise ValueError("outcome intervals must be contiguous one-hour closes")
        close = _positive_decimal(interval["close"], f"outcome interval {index} close")
        _sha256(interval["artifact_sha256"], f"outcome interval {index} artifact")
        _sha256(interval["chain_sha256"], f"outcome interval {index} chain")
        path_signed_returns.append(Decimal(factor_sign) * (close / anchor_close - Decimal("1")))
        terminal = interval
    assert terminal is not None
    terminal_close = _positive_decimal(terminal["close"], "terminal close")
    raw_return = terminal_close / anchor_close - Decimal("1")
    signed_response = Decimal(factor_sign) * raw_return
    sign_adjusted_mae = abs(min(path_signed_returns)) if min(path_signed_returns) < 0 else Decimal("0")
    return _seal_transition({
        "transition_state": "EVALUABLE", "review_status": "COUNTED", **common,
        "factor_sign": factor_sign, "anchor_at": _timestamp_text(anchor_at),
        "anchor_artifact_sha256": anchor_artifact_sha256,
        "outcome_terminal_at": terminal["closed_at"],
        "outcome_terminal_artifact_sha256": terminal["artifact_sha256"],
        "outcome_terminal_chain_sha256": terminal["chain_sha256"],
        "raw_return_168h": _decimal_text(raw_return),
        "signed_response_168h": _decimal_text(signed_response),
        "sign_adjusted_mae_168h": _decimal_text(sign_adjusted_mae),
    })


def _median(values: Sequence[Decimal]) -> Decimal | None:
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / Decimal("2")


def _nearest_rank_p90(values: Sequence[Decimal]) -> Decimal | None:
    if not values:
        return None
    ordered = sorted(values)
    rank = math.ceil(Decimal("0.9") * len(ordered))
    return ordered[rank - 1]


def _optional_text(value: Decimal | None) -> str | None:
    return None if value is None else _decimal_text(value)


def _sign_test(successes: int, failures: int) -> Decimal | None:
    total = successes + failures
    if total == 0:
        return None
    numerator = sum(comb(total, index) for index in range(successes, total + 1))
    with localcontext() as context:
        context.prec = 80
        return Decimal(numerator) / Decimal(2**total)


def _evaluate_transitions_document(transitions: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    package = frozen_package()
    values = [deepcopy(dict(value)) for value in transitions]
    for value in values:
        _verify_transition(value)
    identities = [(value["current_report_date"], value["current_chain_sha256"]) for value in values]
    if len(identities) != len(set(identities)) or len({item[0] for item in identities}) != len(identities):
        raise ValueError("duplicate current report identity")
    nonzero = [value for value in values if value["transition_state"] == "EVALUABLE"]
    no_factor = [value for value in values if value["transition_state"] == "NO_FACTOR_ACTION"]
    nonzero.sort(key=lambda value: (value["anchor_at"], value["current_chain_sha256"]))
    kept: list[dict[str, Any]] = []
    excluded: list[dict[str, Any]] = []
    last_kept_anchor: datetime | None = None
    for value in nonzero:
        anchor = _timestamp(value["anchor_at"], "transition anchor")
        if last_kept_anchor is not None and anchor < last_kept_anchor + timedelta(hours=168):
            value["review_status"] = "OVERLAPPING_WINDOW_EXCLUDED"
            value = _seal_transition(value)
            excluded.append(value)
        else:
            value["review_status"] = "COUNTED"
            value = _seal_transition(value)
            kept.append(value)
            last_kept_anchor = anchor
    for value in no_factor:
        value["review_status"] = "NO_FACTOR_ACTION"
        value = _seal_transition(value)
    output_transitions = kept + excluded + sorted(no_factor, key=lambda value: (value["decision_at"], value["current_chain_sha256"]))
    n = len(kept)
    positive = [value for value in kept if value["factor_sign"] == 1]
    negative = [value for value in kept if value["factor_sign"] == -1]
    quartiles = [0, 0, 0, 0]
    for index in range(n):
        quartiles[(4 * index) // n if n else 0] += 1
    months: dict[str, int] = {}
    for value in kept:
        month = value["anchor_at"][:7]
        months[month] = months.get(month, 0) + 1
    max_month_share = (Decimal(max(months.values())) / Decimal(n)) if n else None
    signed = [Decimal(value["signed_response_168h"]) for value in kept]
    raw = [Decimal(value["raw_return_168h"]) for value in kept]
    positive_raw = [Decimal(value["raw_return_168h"]) for value in positive]
    negative_raw = [Decimal(value["raw_return_168h"]) for value in negative]
    nonzero_signed = [value for value in signed if value != 0]
    successes = sum(value > 0 for value in nonzero_signed)
    failures = sum(value < 0 for value in nonzero_signed)
    p_value = _sign_test(successes, failures)
    total_absolute_signed = sum((abs(value) for value in signed), Decimal("0"))
    max_episode_share = (max((abs(value) for value in signed), default=Decimal("0")) / total_absolute_signed) if total_absolute_signed else None
    absolute_returns = [abs(value) for value in raw]
    absolute_mae = [abs(Decimal(value["sign_adjusted_mae_168h"])) for value in kept]
    median_signed = _median(signed)
    positive_median = _median(positive_raw)
    negative_median = _median(negative_raw)
    gates = {
        "minimum_episodes": n >= 26,
        "positive_factor_breadth": len(positive) >= 8,
        "negative_factor_breadth": len(negative) >= 8,
        "quartile_breadth": all(value >= 4 for value in quartiles),
        "month_breadth": len(months) >= 6,
        "month_concentration": max_month_share is not None and max_month_share <= Decimal("0.25"),
        "breadth_complete": False,
        "median_signed_response_positive": median_signed is not None and median_signed > 0,
        "positive_factor_median_raw_return_positive": positive_median is not None and positive_median > 0,
        "negative_factor_median_raw_return_negative": negative_median is not None and negative_median < 0,
        "one_sided_sign_test": p_value is not None and p_value <= Decimal("0.10"),
        "predictive_complete": False,
        "episode_concentration": max_episode_share is not None and max_episode_share <= Decimal("0.20"),
        "concentration_complete": False,
    }
    gates["breadth_complete"] = all(gates[key] for key in ("minimum_episodes", "positive_factor_breadth", "negative_factor_breadth", "quartile_breadth", "month_breadth", "month_concentration"))
    gates["predictive_complete"] = all(gates[key] for key in ("median_signed_response_positive", "positive_factor_median_raw_return_positive", "negative_factor_median_raw_return_negative", "one_sided_sign_test"))
    gates["concentration_complete"] = gates["episode_concentration"]
    if not gates["breadth_complete"]:
        disposition = "WAIT_FOR_MORE_UNTOUCHED_EVIDENCE"
    elif gates["predictive_complete"] and gates["concentration_complete"]:
        disposition = "CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_POSITIVE_FOR_MANAGER_REVIEW"
    else:
        disposition = "CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_ROUTE_CLOSE"
    statistics = {
        "total_input_transitions": len(values),
        "nonzero_evaluable_transitions": len(nonzero),
        "nonoverlap_count": n,
        "overlap_excluded_count": len(excluded),
        "positive_factor_count": len(positive),
        "negative_factor_count": len(negative),
        "quartile_counts": quartiles,
        "anchor_month_count": len(months),
        "maximum_month_share": _optional_text(max_month_share),
        "median_signed_response": _optional_text(median_signed),
        "positive_factor_median_raw_return": _optional_text(positive_median),
        "negative_factor_median_raw_return": _optional_text(negative_median),
        "sign_test_successes": successes,
        "sign_test_failures": failures,
        "sign_test_p_value": _optional_text(p_value),
        "maximum_episode_absolute_signed_response_share": _optional_text(max_episode_share),
        "median_absolute_return": _optional_text(_median(absolute_returns)),
        "p90_absolute_return": _optional_text(_nearest_rank_p90(absolute_returns)),
        "median_absolute_sign_adjusted_mae": _optional_text(_median(absolute_mae)),
        "p90_absolute_sign_adjusted_mae": _optional_text(_nearest_rank_p90(absolute_mae)),
    }
    document = {
        "schema_version": "1",
        "document_type": "CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_EVALUATION_V1",
        "authorization": AUTHORIZATION,
        "factor_identity": FACTOR_IDENTITY,
        "factor_contract_sha256": package["contract_sha256"],
        "factor_contract_schema_sha256": package["contract_schema_sha256"],
        "evaluation_schema_sha256": package["evaluation_schema_sha256"],
        "disposition": disposition,
        "transitions": output_transitions,
        "sample_statistics": statistics,
        "gates": gates,
        "readiness": {
            "implementation_capability_only": True,
            "real_source_continuity_proven": False,
            "real_first_close_proven": False,
            "predictive_value_proven": False,
            "economic_value_proven": False,
            "candidate_authorized": False,
            "oos_authorized": False,
            "trading_authorized": False,
            "immediate_pnl_effect": "ZERO",
            "immediate_drawdown_effect": "ZERO",
        },
    }
    return document


def evaluate_transitions(transitions: Iterable[Mapping[str, Any]]) -> dict[str, Any]:
    document = _evaluate_transitions_document(transitions)
    validate_evaluation_document(document)
    return document


def validate_evaluation_document(document: Mapping[str, Any]) -> dict[str, Any]:
    package = frozen_package()
    root_keys = {"schema_version", "document_type", "authorization", "factor_identity", "factor_contract_sha256", "factor_contract_schema_sha256", "evaluation_schema_sha256", "disposition", "transitions", "sample_statistics", "gates", "readiness"}
    _exact_keys(document, root_keys, "evaluation document")
    if document["schema_version"] != "1" or document["document_type"] != "CFTC_TFF_LEV_MONEY_NET_PCT_OI_DELTA_EVALUATION_V1":
        raise ValueError("evaluation document identity drift")
    if document["authorization"] != AUTHORIZATION or document["factor_identity"] != FACTOR_IDENTITY:
        raise ValueError("evaluation identity drift")
    if document["factor_contract_sha256"] != package["contract_sha256"] or document["factor_contract_schema_sha256"] != package["contract_schema_sha256"] or document["evaluation_schema_sha256"] != package["evaluation_schema_sha256"]:
        raise ValueError("evaluation package hash binding drift")
    if document["disposition"] not in package["contract"]["dispositions"]:
        raise ValueError("evaluation disposition drift")
    if not isinstance(document["transitions"], list):
        raise ValueError("evaluation transitions must be an array")
    for transition in document["transitions"]:
        _verify_transition(transition)
    readiness = document["readiness"]
    _exact_keys(readiness, {"implementation_capability_only", "real_source_continuity_proven", "real_first_close_proven", "predictive_value_proven", "economic_value_proven", "candidate_authorized", "oos_authorized", "trading_authorized", "immediate_pnl_effect", "immediate_drawdown_effect"}, "evaluation readiness")
    if readiness != {
        "implementation_capability_only": True, "real_source_continuity_proven": False,
        "real_first_close_proven": False, "predictive_value_proven": False,
        "economic_value_proven": False, "candidate_authorized": False,
        "oos_authorized": False, "trading_authorized": False,
        "immediate_pnl_effect": "ZERO", "immediate_drawdown_effect": "ZERO",
    }:
        raise ValueError("evaluation readiness boundary drift")
    expected = _evaluate_transitions_document(document["transitions"])
    if dict(document) != expected:
        raise ValueError("evaluation document does not match deterministic recomputation")
    return dict(document)
