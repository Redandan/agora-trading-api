from __future__ import annotations

import hashlib
import json
from pathlib import Path
from typing import Any

from .models import RESEARCH_AUTHORIZATION


def load_policy(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if value.get("schema_version") != "1":
        raise ValueError("policy schema_version must be 1")
    if value.get("authorization") != RESEARCH_AUTHORIZATION:
        raise ValueError("policy must remain research-only")
    budget = value.get("budget")
    if not isinstance(budget, dict):
        raise ValueError("policy budget is required")
    for field in (
        "max_active_experiments",
        "max_new_hypotheses_per_cycle",
        "max_candidate_variants",
        "runner_timeout_seconds",
        "lock_stale_seconds",
    ):
        if int(budget.get(field, 0)) <= 0:
            raise ValueError(f"policy budget.{field} must be positive")
    if value.get("policy_id") == "AUTONOMOUS_TRADING_RESEARCH_V3":
        evidence = value.get("evidence")
        if not isinstance(evidence, dict):
            raise ValueError("V3 policy evidence contract is required")
        if int(evidence.get("capture_max_lag_seconds", 0)) <= 0:
            raise ValueError("policy evidence.capture_max_lag_seconds must be positive")
        if evidence.get("daily_observation_unit") != "COMPLETE_UTC_DAY":
            raise ValueError("policy evidence.daily_observation_unit must remain COMPLETE_UTC_DAY")
        if evidence.get("backfill") != "DENY" or evidence.get("sealed_day_chain") != "SHA256":
            raise ValueError("policy evidence must deny backfill and require SHA256 chaining")
        worker = value.get("server_worker")
        if not isinstance(worker, dict):
            raise ValueError("V3 policy server_worker contract is required")
        if worker.get("allowed_operations") != [
            "RESEARCH_HEARTBEAT",
            "REGISTER_CANDIDATE_BUNDLE",
        ]:
            raise ValueError("policy server_worker operations must remain fixed and ordered")
        if int(worker.get("max_candidate_bundle_bytes", 0)) != 131072:
            raise ValueError("policy candidate bundle limit must remain 131072 bytes")
        source = value.get("forward_evidence_source")
        if not isinstance(source, dict):
            raise ValueError("V3 policy authorized forward evidence source is required")
        frozen_source = {
            "status": "AUTHORIZED",
            "clock": "CODEX_CLOUD_HEARTBEAT_COMPANION",
            "producer": "agora-okx-forward-source-v1",
            "transport": "SEALED_ONE_WAY_DROP_V1",
            "public_origin": "https://www.okx.com",
            "endpoint": "/api/v5/market/candles",
            "instrument": "BTC-USDT",
            "bar": "1H",
            "confirm": "1",
            "source_identity": "agora-evidence-source",
            "worker_network_access": "DENY",
            "worker_database_access": "DENY",
            "producer_credentials": "DENY",
            "backfill": "DENY",
            "timer": "DENY",
        }
        for field, expected in frozen_source.items():
            if source.get(field) != expected:
                raise ValueError(f"policy forward_evidence_source.{field} must remain frozen")
        if int(source.get("max_response_bytes", 0)) != 1048576:
            raise ValueError("policy forward evidence source response limit must remain 1048576")
    return value


def policy_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()
