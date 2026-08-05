from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Literal

from mcp.server.auth.settings import AuthSettings, ClientRegistrationOptions, RevocationOptions
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from mcp.types import ToolAnnotations
from pydantic import AnyHttpUrl
from starlette.requests import Request
from starlette.responses import Response

from .provider import SCOPES, SqliteOAuthProvider
from .queue import (
    get_run,
    request_candidate_bundle,
    request_heartbeat,
    research_briefing,
    research_status,
)


ISSUER = os.environ.get("AGORA_RESEARCH_MCP_ISSUER", "https://agoratradingapi.purrtechllc.com")
RESOURCE = os.environ.get(
    "AGORA_RESEARCH_MCP_RESOURCE",
    "https://agoratradingapi.purrtechllc.com/research/mcp",
)
AUTH_DIR = Path(os.environ.get("AGORA_RESEARCH_AUTH_DIR", "/var/lib/agora-research/auth"))

provider = SqliteOAuthProvider(
    database=AUTH_DIR / "oauth.sqlite3",
    enrollment_hash=AUTH_DIR / "enrollment-code.hash",
    enrollment_consumed=AUTH_DIR / "enrollment-consumed",
    issuer=ISSUER,
    resource=RESOURCE,
    login_url=ISSUER.rstrip("/") + "/research/login",
    allow_local_redirects=os.environ.get("AGORA_RESEARCH_ALLOW_LOCAL_REDIRECTS") == "1",
)

mcp = FastMCP(
    name="Agora Autonomous Research Worker",
    instructions=(
        "Research-only control plane. Call get_research_status first. "
        "request_research_heartbeat queues at most one due deterministic server Worker run; "
        "submit_research_candidate_bundle may preregister exactly one evidence-bound frozen "
        "research experiment after the canonical evidence review is READY; "
        "it never changes Trading runtime, strategies, orders, funds, databases, OCO/Grid, "
        "SHADOW, PAPER or LIVE. Scientific WAIT, DATA_REJECT and NO_CANDIDATE are valid outcomes. "
        "After requesting a run, poll get_research_run or inspect status on the next scheduled cycle."
    ),
    auth_server_provider=provider,
    auth=AuthSettings(
        issuer_url=AnyHttpUrl(ISSUER),
        resource_server_url=AnyHttpUrl(RESOURCE),
        required_scopes=["research:read", "research:heartbeat"],
        client_registration_options=ClientRegistrationOptions(
            enabled=True,
            client_secret_expiry_seconds=None,
            valid_scopes=SCOPES,
            default_scopes=SCOPES,
        ),
        revocation_options=RevocationOptions(enabled=True),
    ),
    json_response=True,
    stateless_http=True,
    transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False),
)


@mcp.custom_route("/research/login", methods=["GET"])
async def research_login(request: Request) -> Response:
    return await provider.login_page(request)


@mcp.custom_route("/research/login", methods=["POST"])
async def research_login_callback(request: Request) -> Response:
    return await provider.login_callback(request)


@mcp.tool(
    title="Get autonomous research status",
    annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True),
)
def get_research_status() -> dict[str, Any]:
    """Read canonical registry, queue state and the latest sealed heartbeat."""
    return research_status()


@mcp.tool(
    title="Request one autonomous research heartbeat",
    annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True),
)
def request_research_heartbeat(
    ops_schedule_contract_sha256: str,
) -> dict[str, Any]:
    """Queue one due bounded heartbeat after attesting the deployed Ops contract."""
    return request_heartbeat(ops_schedule_contract_sha256)


@mcp.tool(
    title="Submit one evidence-bound research candidate",
    annotations=ToolAnnotations(readOnlyHint=False, destructiveHint=False, idempotentHint=True),
)
def submit_research_candidate_bundle(
    bundle: dict[str, Any],
    ops_schedule_contract_sha256: str,
) -> dict[str, Any]:
    """Queue one attested candidate bundle; it can only end at preregistration."""
    return request_candidate_bundle(bundle, ops_schedule_contract_sha256)


@mcp.tool(
    title="Get autonomous research run",
    annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True),
)
def get_research_run(request_id: str) -> dict[str, Any]:
    """Read one queued/running/completed fixed research request by its 32-character id."""
    return get_run(request_id)


@mcp.tool(
    title="Get deterministic research briefing",
    annotations=ToolAnnotations(readOnlyHint=True, destructiveHint=False, idempotentHint=True),
)
def get_research_briefing(period: Literal["weekly", "monthly"] = "weekly") -> dict[str, Any]:
    """Read the latest sealed weekly or monthly briefing with artifact id and SHA-256."""
    return research_briefing(period)


app = mcp.streamable_http_app()
