"""One-shot producer for the isolated OKX carry V3R1 schema-only probe.

This module is source-host code, not Research Worker code. Importing it performs
no network access. The CLI has one output-path argument, makes the four frozen
credential-free GETs once, persists no raw response, and create-once writes only
the redacted hash-bound receipt.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
from typing import Any, Callable
from urllib.parse import urlencode
from urllib.request import HTTPSHandler, ProxyHandler, Request, build_opener

from research_pipeline import dra_crypto_carry_public_axes_v3r1 as contract_validator


TIMEOUT_SECONDS = 15
USER_AGENT = "AgoraResearchSchemaProbe/3.1"
FUTURES_INST_ID_PATTERN = re.compile(r"^BTC-USDT-[0-9]{6}$")
Clock = Callable[[], datetime]


class ProbeFailure(RuntimeError):
    pass


def _fail(detail: str) -> None:
    raise ProbeFailure(detail)


def _utc_timestamp(clock: Clock) -> str:
    value = clock()
    if not isinstance(value, datetime) or value.tzinfo is None:
        _fail("probe clock must be timezone-aware")
    value = value.astimezone(timezone.utc).replace(microsecond=0)
    return value.strftime("%Y-%m-%dT%H:%M:%SZ")


def _fixed_url(request_contract: dict[str, Any]) -> str:
    if request_contract["origin"] != "https://www.okx.com":
        _fail("source origin drift")
    if request_contract["method"] != "GET" or request_contract["credentials"] != "NONE":
        _fail("only credential-free GET is allowed")
    path = request_contract["path"]
    if not isinstance(path, str) or not path.startswith("/api/v5/") or "/account/" in path:
        _fail("source path is not an approved public path")
    query = urlencode(sorted(request_contract["query"].items()))
    return f"{request_contract['origin']}{path}?{query}"


def _response_document(raw: bytes) -> dict[str, Any]:
    try:
        return contract_validator.load_json_bytes(raw, canonical=False)
    except contract_validator.ContractViolation as error:
        raise ProbeFailure(f"response JSON rejected: {error.code}") from error


def _all_string_closed_rows(data: Any, expected_keys: list[str], endpoint_id: str) -> list[dict[str, str]]:
    if not isinstance(data, list) or not data:
        _fail(f"{endpoint_id} has no data rows")
    rows: list[dict[str, str]] = []
    for row in data:
        if not isinstance(row, dict) or set(row) != set(expected_keys):
            _fail(f"{endpoint_id} row schema drift")
        if any(not isinstance(row[key], str) for key in expected_keys):
            _fail(f"{endpoint_id} row value type drift")
        rows.append(row)
    return rows


def _select_row(rows: list[dict[str, str]], request_contract: dict[str, Any]) -> tuple[int, dict[str, str]]:
    endpoint_id = request_contract["endpoint_id"]
    if request_contract["selection_rule"] == "EXACTLY_ONE_ROW":
        if len(rows) != 1:
            _fail(f"{endpoint_id} expected exactly one row")
        return 0, rows[0]
    if endpoint_id != "FUTURES_OPEN_INTEREST" or request_contract["selection_rule"] != "LEXICOGRAPHICALLY_SMALLEST_INSTID_MATCHING_BTC_USDT_YYMMDD":
        _fail(f"{endpoint_id} selection rule is unsupported")
    eligible: list[tuple[str, int, dict[str, str]]] = []
    for index, row in enumerate(rows):
        inst_id = row["instId"]
        if row["instType"] != "FUTURES" or FUTURES_INST_ID_PATTERN.fullmatch(inst_id) is None:
            _fail("FUTURES response contains a non-expiry BTC-USDT identity")
        eligible.append((inst_id, index, row))
    _, index, row = min(eligible, key=lambda item: item[0])
    return index, row


def _identity_values(row: dict[str, str], request_contract: dict[str, Any]) -> dict[str, str]:
    values = {key: row[key] for key in request_contract["identity_fields"]}
    endpoint_id = request_contract["endpoint_id"]
    if endpoint_id == "FUTURES_OPEN_INTEREST":
        if values["instType"] != "FUTURES" or FUTURES_INST_ID_PATTERN.fullmatch(values["instId"]) is None:
            _fail("FUTURES fixed identity mismatch")
    elif endpoint_id == "SWAP_OPEN_INTEREST":
        if values != {"instId": "BTC-USDT-SWAP", "instType": "SWAP"}:
            _fail("SWAP open-interest fixed identity mismatch")
    elif endpoint_id == "SWAP_FUNDING_RATE_HISTORY":
        if values["instId"] != "BTC-USDT-SWAP" or not values["formulaType"] or not values["method"]:
            _fail("funding fixed identity mismatch")
    elif endpoint_id == "USDT_LENDING_RATE_HISTORY":
        if values != {"ccy": "USDT"}:
            _fail("lending fixed identity mismatch")
    else:
        _fail("unknown endpoint identity")
    return values


def _probe_endpoint(opener: Any, request_contract: dict[str, Any], clock: Clock) -> dict[str, Any]:
    endpoint_id = request_contract["endpoint_id"]
    url = _fixed_url(request_contract)
    request = Request(
        url,
        headers={"Accept": "application/json", "User-Agent": USER_AGENT},
        method="GET",
    )
    forbidden_headers = {"authorization", "cookie", "ok-access-key", "ok-access-passphrase", "ok-access-sign", "ok-access-timestamp"}
    if any(name.lower() in forbidden_headers for name, _ in request.header_items()):
        _fail(f"{endpoint_id} credential header present")
    maximum_bytes = request_contract["maximum_raw_response_bytes"]
    try:
        with opener.open(request, timeout=TIMEOUT_SECONDS) as response:
            if response.geturl() != url:
                _fail(f"{endpoint_id} redirect or final URL drift")
            status = response.status
            raw = response.read(maximum_bytes + 1)
    except ProbeFailure:
        raise
    except Exception as error:
        raise ProbeFailure(f"{endpoint_id} source request failed without retry") from error
    if status != 200:
        _fail(f"{endpoint_id} HTTP status is not 200")
    if not raw or len(raw) > maximum_bytes:
        _fail(f"{endpoint_id} response size is outside the frozen bound")
    document = _response_document(raw)
    if set(document) != set(request_contract["expected_top_level_keys"]):
        _fail(f"{endpoint_id} top-level schema drift")
    if document["code"] != request_contract["expected_api_code"]:
        _fail(f"{endpoint_id} API code is not zero")
    rows = _all_string_closed_rows(document["data"], request_contract["expected_data_row_keys"], endpoint_id)
    row_index, row = _select_row(rows, request_contract)
    if any(not row[field] for field in request_contract["timestamp_fields"]):
        _fail(f"{endpoint_id} timestamp field is empty")
    identity_values = _identity_values(row, request_contract)
    return {
        "endpoint_id": endpoint_id,
        "request_fingerprint_sha256": hashlib.sha256(contract_validator.canonical_json_bytes(request_contract)).hexdigest(),
        "credential_sent": False,
        "http_status": status,
        "response_observed_at": _utc_timestamp(clock),
        "top_level_keys": request_contract["expected_top_level_keys"],
        "api_code": document["code"],
        "response_data_row_count": len(rows),
        "inspected_data_row_index": row_index,
        "inspected_data_row_keys": request_contract["expected_data_row_keys"],
        "inspected_data_value_types": {key: "STRING" for key in request_contract["expected_data_row_keys"]},
        "identity_values": identity_values,
        "required_timestamp_fields_present": request_contract["timestamp_fields"],
        "raw_response_sha256": hashlib.sha256(raw).hexdigest(),
        "raw_response_bytes": len(raw),
        "values_redacted": True,
        "schema_match": True,
    }


def build_probe_receipt(opener: Any, clock: Clock) -> bytes:
    contract_validator.validate_frozen_files()
    contract = contract_validator.frozen_contract()
    endpoints = [_probe_endpoint(opener, request, clock) for request in contract["requests"]]
    payload = {
        "schema_version": "3.1",
        "document_type": contract_validator.DOCUMENT_TYPE,
        "authorization": contract_validator.AUTHORIZATION,
        "source_contract_id": contract_validator.CONTRACT_ID,
        "source_contract_sha256": contract_validator.CONTRACT_SHA256,
        "probed_at": _utc_timestamp(clock),
        "probe_host_identity": "APPROVED_ISOLATED_CREDENTIAL_FREE_SOURCE_HOST",
        "network_path": "SOURCE_HOST_HTTPS_GET_ONLY_NO_RESEARCH_WORKER_NETWORK",
        "endpoints": endpoints,
        "no_market_values_persisted": True,
        "raw_responses_persisted": False,
        "raw_responses_transported": False,
        "source_activation_authorized": False,
    }
    return contract_validator.seal_probe_payload(payload)


def write_create_once(path: Path, raw: bytes) -> None:
    if path.is_symlink() or not path.parent.is_dir() or path.parent.is_symlink():
        _fail("receipt output parent must be an existing non-symlink directory")
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    descriptor = os.open(path, flags, 0o640)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
    except Exception:
        try:
            path.unlink(missing_ok=True)
        finally:
            raise


def _direct_https_opener() -> Any:
    context = ssl.create_default_context()
    return build_opener(ProxyHandler({}), HTTPSHandler(context=context))


def _system_clock() -> datetime:
    return datetime.now(timezone.utc)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Run the fixed OKX carry V3R1 schema-only source-host probe once")
    parser.add_argument("output", type=Path)
    args = parser.parse_args(argv)
    raw = build_probe_receipt(_direct_https_opener(), _system_clock)
    write_create_once(args.output.resolve(), raw)
    print(hashlib.sha256(raw).hexdigest())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
