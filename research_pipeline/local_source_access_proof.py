from __future__ import annotations

from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import re
from typing import Any

from .local_dispatch import canonical_json_document_bytes


DOCUMENT_TYPE = "LOCAL_PRIMARY_SOURCE_ACCESS_PROOF_V1"
ACCESS_MODE = "PUBLIC_READ_ONLY_BROWSER"
PROOF_VALIDITY = timedelta(hours=24)

_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$")
_DOI = re.compile(r"^10\.[0-9]{4,9}/[^ ]+$")
_HTTPS = re.compile(r"^https://[^ ]+$")
_ROOT_KEYS = {
    "access_mode",
    "checked_at",
    "document_type",
    "expires_at",
    "local_thread_id",
    "local_turn_id",
    "proof_id",
    "safety_assertions",
    "schema_version",
    "sources",
}
_SOURCE_KEYS = {
    "access_status",
    "authors",
    "authors_match",
    "body_readable",
    "distinct_sections_observed",
    "doi",
    "findings_fact_observed",
    "methodology_fact_observed",
    "publisher_url",
    "readable_source_kind",
    "readable_url",
    "title",
    "title_match",
}
_READABLE_SOURCE_KINDS = {
    "AUTHOR_HOSTED_MANUSCRIPT",
    "INSTITUTIONAL_REPOSITORY_MANUSCRIPT",
    "OPEN_ACCESS_PUBLISHER_FULL_TEXT",
    "PREPRINT_SERVER_MANUSCRIPT",
}
_SAFETY_KEYS = {
    "canonical_state_changed",
    "oos_opened",
    "paid_api_used",
    "repository_written",
    "second_timer_created",
    "server_research_mcp_write_attempted",
    "trading_action_attempted",
}


def _reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"source access proof contains duplicate key: {key}")
        value[key] = item
    return value


def _exact_keys(value: Any, expected: set[str], label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ValueError(f"{label} does not satisfy the closed contract")
    return value


def _pattern(value: Any, pattern: re.Pattern[str], label: str) -> str:
    if not isinstance(value, str) or pattern.fullmatch(value) is None:
        raise ValueError(f"{label} has an invalid format")
    return value


def _timestamp(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise ValueError(f"{label} must be a UTC timestamp")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"{label} must be a UTC timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() != timezone.utc.utcoffset(parsed):
        raise ValueError(f"{label} must be timezone-aware UTC")
    return parsed


def validate_source_access_proof(value: Any) -> dict[str, Any]:
    document = _exact_keys(value, _ROOT_KEYS, "source access proof")
    if document["schema_version"] != "1" or document["document_type"] != DOCUMENT_TYPE:
        raise ValueError("source access proof identity is unsupported")
    if document["access_mode"] != ACCESS_MODE:
        raise ValueError("source access proof must use the public read-only browser")
    _pattern(document["proof_id"], _IDENTIFIER, "proof_id")
    _pattern(document["local_thread_id"], _IDENTIFIER, "local_thread_id")
    _pattern(document["local_turn_id"], _IDENTIFIER, "local_turn_id")
    checked_at = _timestamp(document["checked_at"], "checked_at")
    expires_at = _timestamp(document["expires_at"], "expires_at")
    if expires_at - checked_at != PROOF_VALIDITY:
        raise ValueError("source access proof must use the fixed 24-hour validity")

    safety = _exact_keys(document["safety_assertions"], _SAFETY_KEYS, "safety_assertions")
    if any(item is not False for item in safety.values()):
        raise ValueError("source access proof safety assertions must all be false")

    sources = document["sources"]
    if not isinstance(sources, list) or not 3 <= len(sources) <= 8:
        raise ValueError("source access proof requires three to eight readable sources")
    identities: set[tuple[str, str, str]] = set()
    for index, raw_source in enumerate(sources):
        source = _exact_keys(raw_source, _SOURCE_KEYS, f"sources[{index}]")
        doi = _pattern(source["doi"], _DOI, f"sources[{index}].doi")
        publisher_url = _pattern(
            source["publisher_url"], _HTTPS, f"sources[{index}].publisher_url"
        )
        readable_url = _pattern(
            source["readable_url"], _HTTPS, f"sources[{index}].readable_url"
        )
        if source["readable_source_kind"] not in _READABLE_SOURCE_KINDS:
            raise ValueError("source access proof readable source kind is unsupported")
        if source["access_status"] != "READABLE":
            raise ValueError("source access proof cannot bind an unreadable source")
        if any(
            source[name] is not True
            for name in (
                "authors_match",
                "body_readable",
                "findings_fact_observed",
                "methodology_fact_observed",
                "title_match",
            )
        ):
            raise ValueError("source access proof requires complete identity and body evidence")
        if (
            not isinstance(source["distinct_sections_observed"], int)
            or isinstance(source["distinct_sections_observed"], bool)
            or source["distinct_sections_observed"] < 2
        ):
            raise ValueError("source access proof requires at least two distinct sections")
        if not isinstance(source["title"], str) or len(source["title"].strip()) < 8:
            raise ValueError("source access proof title is invalid")
        authors = source["authors"]
        if (
            not isinstance(authors, list)
            or not 1 <= len(authors) <= 20
            or any(not isinstance(author, str) or not author.strip() for author in authors)
            or len(set(authors)) != len(authors)
        ):
            raise ValueError("source access proof authors are invalid")
        identities.add((doi.lower(), publisher_url, readable_url))
    if len(identities) != len(sources):
        raise ValueError("source access proof sources must be distinct")
    return document


def load_and_validate_source_access_proof(
    raw_or_path: bytes | Path,
) -> tuple[dict[str, Any], bytes]:
    raw = raw_or_path if isinstance(raw_or_path, bytes) else raw_or_path.read_bytes()
    try:
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_reject_duplicates)
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise ValueError("source access proof must be strict UTF-8 JSON") from error
    if not isinstance(value, dict) or raw != canonical_json_document_bytes(value):
        raise ValueError("source access proof must use canonical JSON document bytes")
    return validate_source_access_proof(value), raw


def validate_source_access_proof_context(
    proof: dict[str, Any],
    *,
    discovery: dict[str, Any],
    task: dict[str, Any],
    allocation_time: datetime,
) -> None:
    checked_at = _timestamp(proof["checked_at"], "checked_at")
    expires_at = _timestamp(proof["expires_at"], "expires_at")
    if not checked_at <= allocation_time <= expires_at:
        raise ValueError("source access proof is not fresh at allocation time")

    expected_sources = {
        (source["doi"].lower(), source["publisher_url"])
        for source in discovery["discovery_contract"]["primary_sources"]
    }
    proven_sources = {
        (source["doi"].lower(), source["publisher_url"])
        for source in proof["sources"]
    }
    if proven_sources != expected_sources:
        raise ValueError("source access proof does not bind the exact discovery source set")

    task_messages = [
        item["locator"] for item in task["inputs"] if item["kind"] == "TASK_MESSAGE"
    ]
    for source in proof["sources"]:
        if not any(
            source["doi"] in message
            and source["publisher_url"] in message
            and source["readable_url"] in message
            for message in task_messages
        ):
            raise ValueError(
                "each readable primary source must be frozen in one task message"
            )
