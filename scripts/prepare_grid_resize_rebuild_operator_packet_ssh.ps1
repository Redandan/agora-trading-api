param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [decimal]$TotalReviewCapitalCapUsdt = 30,
    [switch]$RequireReviewReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH." }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 336) { throw "LookbackHours must be between 24 and 336." }
if ($TotalReviewCapitalCapUsdt -lt 5 -or $TotalReviewCapitalCapUsdt -gt 10000) { throw "TotalReviewCapitalCapUsdt must be between 5 and 10000." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$remoteScript = @'
set -euo pipefail
cd '__APPDIR__'

env_value() {
  local key="$1"
  grep -E "^${key}=" '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//'
}

PORT=$(cat app.port)
MCP_URL="http://127.0.0.1:${PORT}/api/mcp"
MCP_KEY=$(grep -E '^(MCP_OPS_KEY|TRADING_MCP_OPS_KEY|TRADING_OPS_MCP_KEY)=' '__ENVFILE__' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"$//; s/^'\''//; s/'\''$//')
if [ -z "$MCP_KEY" ]; then
  echo "[grid-resize-rebuild-operator-packet] FAIL: OPS MCP key missing in env file" >&2
  exit 1
fi

export MCP_URL MCP_KEY SYMBOL='__SYMBOL__' LOOKBACK_HOURS='__LOOKBACK_HOURS__' TOTAL_REVIEW_CAPITAL_CAP_USDT='__TOTAL_REVIEW_CAPITAL_CAP_USDT__'
export TRADING_GRID_ENABLED=$(env_value TRADING_GRID_ENABLED)
export TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=$(env_value TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED)
export GRID_RECOVERY_ENABLED=$(env_value GRID_RECOVERY_ENABLED)
export TRADING_OKX_ENABLED=$(env_value TRADING_OKX_ENABLED)
export OKX_EARN_TOPUP_ENABLED=$(env_value OKX_EARN_TOPUP_ENABLED)

python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

mcp_url = os.environ["MCP_URL"]
mcp_key = os.environ["MCP_KEY"]
symbol = os.environ["SYMBOL"].upper()
lookback_hours = int(os.environ["LOOKBACK_HOURS"])
total_review_capital_cap = float(os.environ["TOTAL_REVIEW_CAPITAL_CAP_USDT"])

def fail(message):
    print(f"[grid-resize-rebuild-operator-packet] FAIL: {message}", file=sys.stderr)
    sys.exit(1)

def call_tool(name, arguments=None):
    body = {
        "jsonrpc": "2.0",
        "id": f"grid-resize-rebuild-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    req = urllib.request.Request(
        mcp_url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {mcp_key}",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            message = json.loads(response.read().decode("utf-8", "replace"))
    except urllib.error.HTTPError as exc:
        fail(f"{name} HTTP {exc.code}: {exc.read().decode('utf-8', 'replace')[:800]}")
    except Exception as exc:
        fail(f"{name} call failed: {exc}")
    if "error" in message:
        fail(f"{name} JSON-RPC error: {message['error']}")
    result = message.get("result") or {}
    if result.get("isError"):
        fail(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        text = content[0].get("text") or ""
        stripped = text.strip()
        if stripped.startswith('"') and stripped.endswith('"'):
            try:
                decoded = json.loads(stripped)
                if isinstance(decoded, str):
                    return decoded
            except Exception:
                pass
        return text
    return json.dumps(result, ensure_ascii=False)

def first(pattern, text, default="UNKNOWN"):
    match = re.search(pattern, text or "", re.M)
    return match.group(1) if match else default

def parse_number(value):
    if value is None:
        return None
    text = str(value).strip().replace("$", "").replace(",", "").strip("%")
    if not text or text.upper() in ("N/A", "NA", "UNKNOWN", "NULL"):
        return None
    try:
        return float(text)
    except ValueError:
        return None

def round_or_none(value, digits=4):
    return None if value is None else round(value, digits)

def key_values(text):
    values = {}
    for key, value in re.findall(r"([A-Za-z][A-Za-z0-9]*)=([^ ]+)", text or ""):
        values[key] = value.rstrip(",;")
    return values

def append_unique(items, value):
    if value and value not in items:
        items.append(value)

def extract_grid_blocks(review):
    blocks = []
    pattern = re.compile(
        r"^Grid #(?P<id>\d+) (?P<symbol>[A-Z0-9_-]+) enabled=(?P<enabled>\S+) paused=(?P<paused>\S+) closed=(?P<closed>\S+)\n(?P<body>.*?)(?=^Grid #|\Z)",
        re.M | re.S,
    )
    for match in pattern.finditer(review or ""):
        body = match.group("body")
        grid = {
            "gridId": int(match.group("id")),
            "symbol": match.group("symbol"),
            "enabled": match.group("enabled") == "true",
            "paused": match.group("paused") == "true",
            "closed": match.group("closed") == "true",
            "rawRecommendationBlockExcerpt": ("Grid #" + match.group("id") + " " + body)[:1600],
        }
        range_match = re.search(r"range=(\S+) lower=([^ ]+) upper=([^ ]+) pricePosition=([^ ]+) rangeWidthPct=([^ \n]+)", body)
        if range_match:
            grid.update({
                "rangeStatus": range_match.group(1),
                "currentLower": round_or_none(parse_number(range_match.group(2)), 8),
                "currentUpper": round_or_none(parse_number(range_match.group(3)), 8),
                "pricePositionPct": round_or_none(parse_number(range_match.group(4)), 4),
                "currentRangeWidthPct": round_or_none(parse_number(range_match.group(5)), 4),
            })
        efficiency_match = re.search(r"efficiencyScore=(\d+)/100 pairs=(\d+) pairsPerDay=([-+0-9.]+) pnlPerDay=([-+0-9.]+) bpPerDay=([-+0-9.]+)", body)
        if efficiency_match:
            grid.update({
                "efficiencyScore": int(efficiency_match.group(1)),
                "closedPairs": int(efficiency_match.group(2)),
                "pairsPerDay": round_or_none(parse_number(efficiency_match.group(3)), 4),
                "pnlPerDay": round_or_none(parse_number(efficiency_match.group(4)), 4),
                "bpPerDay": round_or_none(parse_number(efficiency_match.group(5)), 4),
            })
        level_match = re.search(r"levels pending=(\d+) holding=(\d+) sellFailed=(\d+) dustStale=(\d+) materialFailed=(\d+)", body)
        if level_match:
            pending = int(level_match.group(1))
            holding = int(level_match.group(2))
            sell_failed = int(level_match.group(3))
            dust_stale = int(level_match.group(4))
            material_failed = int(level_match.group(5))
            grid.update({
                "pending": pending,
                "holding": holding,
                "sellFailed": sell_failed,
                "dustStale": dust_stale,
                "materialFailed": material_failed,
                "existingBuyLevelsObserved": pending + holding + sell_failed,
            })
        grid["recommendation"] = first(r"recommendation=([A-Z0-9_]+)", body, "UNKNOWN")
        grid["trendAlignment"] = first(r"trendAlignment=([A-Z0-9_]+)", body, "UNKNOWN")
        blockers_text = first(r"decisionBlockers=([^\n]+)", body, "[]")
        grid["decisionBlockers"] = [] if blockers_text == "[]" else [part.strip() for part in blockers_text.strip("[]").split(",") if part.strip()]
        candidate_text = first(r"candidatePlan=([^\n]+)", body, "")
        grid["candidatePlanText"] = candidate_text
        candidate = key_values(candidate_text)
        if candidate:
            grid["candidatePlan"] = {
                "status": "PREVIEW_ONLY" if candidate_text.startswith("PREVIEW_ONLY") else candidate_text.split(";", 1)[0],
                "symbol": candidate.get("symbol"),
                "lower": round_or_none(parse_number(candidate.get("lower")), 8),
                "upper": round_or_none(parse_number(candidate.get("upper")), 8),
                "priceLines": int(candidate["priceLines"]) if candidate.get("priceLines", "").isdigit() else None,
                "buyLevels": int(candidate["buyLevels"]) if candidate.get("buyLevels", "").isdigit() else None,
                "perLevelUsdt": round_or_none(parse_number(candidate.get("perLevelUsdt")), 4),
                "capitalUsdt": round_or_none(parse_number(candidate.get("capital")), 4),
                "widthPct": round_or_none(parse_number(candidate.get("widthPct")), 4),
                "alignment": candidate.get("alignment"),
                "basedOn": candidate.get("basedOn"),
            }
        else:
            grid["candidatePlan"] = None
        grid["rationale"] = first(r"rationale=([^\n]+)", body, "")
        grid["safeNextStep"] = first(r"safeNextStep=([^\n]+)", body, "")
        enrich_grid_comparison(grid)
        blocks.append(grid)
    return blocks

def enrich_grid_comparison(grid):
    candidate = grid.get("candidatePlan") or {}
    current_lower = grid.get("currentLower")
    current_upper = grid.get("currentUpper")
    candidate_lower = candidate.get("lower")
    candidate_upper = candidate.get("upper")
    per_level = candidate.get("perLevelUsdt")
    existing_buy_levels = grid.get("existingBuyLevelsObserved")
    current_width = grid.get("currentRangeWidthPct")
    candidate_width = candidate.get("widthPct")
    if per_level is not None and existing_buy_levels is not None:
        grid["existingObservedCapitalUsdt"] = round(per_level * existing_buy_levels, 4)
    if candidate.get("capitalUsdt") is not None and grid.get("existingObservedCapitalUsdt") is not None:
        grid["capitalDeltaUsdt"] = round(candidate["capitalUsdt"] - grid["existingObservedCapitalUsdt"], 4)
    if current_width is not None and candidate_width is not None:
        grid["widthDeltaPct"] = round(candidate_width - current_width, 4)
    if None not in (current_lower, current_upper, candidate_lower, candidate_upper):
        current_center = (current_lower + current_upper) / 2.0
        candidate_center = (candidate_lower + candidate_upper) / 2.0
        grid["currentCenter"] = round(current_center, 8)
        grid["candidateCenter"] = round(candidate_center, 8)
        grid["centerShiftPct"] = round(((candidate_center - current_center) / current_center) * 100.0, 4) if current_center else None
    execution_blockers = []
    if grid.get("recommendation") not in ("RESIZE_REVIEW", "REBUILD_REVIEW"):
        append_unique(execution_blockers, "NOT_A_RESIZE_REBUILD_RECOMMENDATION")
    if not candidate or candidate.get("status") != "PREVIEW_ONLY":
        append_unique(execution_blockers, "REPLAYABLE_PREVIEW_CANDIDATE_PLAN_MISSING")
    if grid.get("holding", 0) > 0:
        append_unique(execution_blockers, "ACTIVE_GRID_HOLDING_EXIT_PLAN_REQUIRED_BEFORE_CLOSE_RECREATE")
    if grid.get("sellFailed", 0) > 0 or grid.get("materialFailed", 0) > 0:
        append_unique(execution_blockers, "GRID_LEVEL_FAILURE_RECONCILIATION_REQUIRED")
    if any("INSUFFICIENT" in blocker for blocker in grid.get("decisionBlockers", [])):
        append_unique(execution_blockers, "TREND_OR_RANGE_EVIDENCE_INSUFFICIENT")
    grid["executionBlockersBeforeMutation"] = execution_blockers
    grid["reviewAction"] = (
        "PREPARE_SEPARATE_GRID_RESIZE_REVIEW"
        if grid.get("recommendation") == "RESIZE_REVIEW"
        else "PREPARE_SEPARATE_GRID_REBUILD_REVIEW"
        if grid.get("recommendation") == "REBUILD_REVIEW"
        else "KEEP_OR_WATCH_NO_RESIZE_REBUILD"
    )

review = call_tool("getGridTrendAdjustmentReview", {"symbol": symbol, "lookbackHours": lookback_hours})
list_grids = call_tool("listGrids", {})
alignment = call_tool("getGridPriceAlignment", {})
exposure = call_tool("getCurrentExposure", {})
event_risk = call_tool("getEventRiskControlStatus", {"symbol": symbol})

required_markers = [
    "boundary=READ_ONLY",
    "mutationAllowed=false",
    "orderAllowed=false",
    "gridMutationAllowed=false",
    "schedulerChangeAllowed=false",
    "telegramSendAllowed=false",
    "trend1h=",
    "trend4h=",
    "trendAlignment=",
    "decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW",
    "automationAllowed=false",
    "candidatePlan=",
]
missing_markers = [marker for marker in required_markers if marker not in review]
grids = extract_grid_blocks(review)
review_grids = [grid for grid in grids if grid.get("recommendation") in ("RESIZE_REVIEW", "REBUILD_REVIEW")]

market_evidence = {
    "symbol": symbol,
    "lookbackHours": lookback_hours,
    "trend": first(r"market symbol=\S+ lookbackHours=\d+ trend=([A-Z0-9_]+)", review),
    "trendPct": first(r"market symbol=\S+ lookbackHours=\d+ trend=\S+ trendPct=([^ ]+)", review),
    "atrPct": first(r"market symbol=\S+ lookbackHours=\d+ trend=\S+ trendPct=[^ ]+ atrPct=([^ ]+)", review),
    "current": first(r"market symbol=\S+ lookbackHours=\d+ .* current=([^ ]+)", review),
    "trendAlignment": first(r"market symbol=\S+ lookbackHours=\d+ .* trendAlignment=([A-Z0-9_]+)", review),
    "trend1h": first(r"trend1h=([A-Z0-9_]+)", review),
    "trend1hBars": parse_number(first(r"trend1h=\S+ bars=([0-9]+)", review, "")),
    "trend4h": first(r"trend4h=([A-Z0-9_]+)", review),
    "trend4hBars": parse_number(first(r"trend4h=\S+ bars=([0-9]+)", review, "")),
}
event_risk_level = first(r"riskLevel=([A-Z0-9_:-]+)", event_risk, "UNKNOWN")
event_risk_gate = "CLEAR_EVENT_RISK_R0" if event_risk_level == "R0" else "BLOCKED_EVENT_RISK_NOT_R0"
grid_env = {
    "TRADING_GRID_ENABLED": os.environ.get("TRADING_GRID_ENABLED", ""),
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED": os.environ.get("TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED", ""),
    "GRID_RECOVERY_ENABLED": os.environ.get("GRID_RECOVERY_ENABLED", ""),
    "TRADING_OKX_ENABLED": os.environ.get("TRADING_OKX_ENABLED", ""),
    "OKX_EARN_TOPUP_ENABLED": os.environ.get("OKX_EARN_TOPUP_ENABLED", ""),
}

missing_evidence = []
for marker in missing_markers:
    append_unique(missing_evidence, f"grid trend review marker {marker}")
if not grids:
    append_unique(missing_evidence, "active grid recommendation blocks from getGridTrendAdjustmentReview")
for grid in review_grids:
    candidate = grid.get("candidatePlan") or {}
    for field in ("lower", "upper", "buyLevels", "perLevelUsdt", "capitalUsdt", "widthPct"):
        if candidate.get(field) is None:
            append_unique(missing_evidence, f"Grid #{grid.get('gridId')} candidatePlan.{field}")

aggregate_candidate_capital = round(sum((grid.get("candidatePlan") or {}).get("capitalUsdt") or 0.0 for grid in review_grids), 4)
aggregate_existing_capital = round(sum(grid.get("existingObservedCapitalUsdt") or 0.0 for grid in review_grids), 4)
remaining_execution_blockers = []
if event_risk_gate != "CLEAR_EVENT_RISK_R0":
    append_unique(remaining_execution_blockers, "EVENT_RISK_NOT_R0_BEFORE_GRID_RESIZE_REBUILD")
if aggregate_candidate_capital > total_review_capital_cap:
    append_unique(remaining_execution_blockers, "TOTAL_CANDIDATE_CAPITAL_ABOVE_REVIEW_CAP")
if grid_env.get("TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED") != "false":
    append_unique(remaining_execution_blockers, "GRID_AUTO_REBALANCE_SCHEDULER_NOT_DISABLED")
if grid_env.get("GRID_RECOVERY_ENABLED") != "false":
    append_unique(remaining_execution_blockers, "GRID_RECOVERY_NOT_DISABLED_FOR_REVIEW")
if grid_env.get("OKX_EARN_TOPUP_ENABLED") != "false":
    append_unique(remaining_execution_blockers, "OKX_EARN_TOPUP_NOT_DISABLED_FOR_REVIEW")
for grid in review_grids:
    for blocker in grid.get("executionBlockersBeforeMutation", []):
        if blocker not in ("NOT_A_RESIZE_REBUILD_RECOMMENDATION", "REPLAYABLE_PREVIEW_CANDIDATE_PLAN_MISSING"):
            append_unique(remaining_execution_blockers, f"GRID_{grid.get('gridId')}_{blocker}")

review_ready = bool(review_grids) and not missing_evidence
if missing_evidence:
    status = "BLOCKED_GRID_RESIZE_REBUILD_EVIDENCE_MISSING_NOT_MUTATION"
    decision = "REFRESH_GRID_TREND_REVIEW_EVIDENCE"
elif not grids:
    status = "BLOCKED_GRID_RESIZE_REBUILD_NO_ACTIVE_GRID_NOT_MUTATION"
    decision = "NO_ACTIVE_GRID_TO_RESIZE_OR_REBUILD"
elif not review_grids:
    status = "NO_GRID_RESIZE_REBUILD_RECOMMENDED_NOT_MUTATION"
    decision = "KEEP_WATCH_OR_PAUSE_PER_TREND_REVIEW"
else:
    status = "READY_FOR_GRID_RESIZE_REBUILD_OPERATOR_REVIEW_NOT_MUTATION"
    decision = "PREPARE_SEPARATE_GRID_RESIZE_REBUILD_OPERATOR_DECISION"

packet = {
    "packetType": "GRID_RESIZE_REBUILD_OPERATOR_PACKET",
    "scope": "READ_ONLY",
    "status": status,
    "decision": decision,
    "symbol": symbol,
    "mcpUrl": mcp_url,
    "sourceTools": [
        "getGridTrendAdjustmentReview",
        "listGrids",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "getEventRiskControlStatus",
    ],
    "marketEvidence": market_evidence,
    "eventRiskGate": {
        "status": event_risk_gate,
        "riskLevel": event_risk_level,
        "notAuthorization": "event-risk evidence is read-only and does not authorize grid mutation",
    },
    "gridEnv": grid_env,
    "activeGridCount": len(grids),
    "resizeRebuildCandidateGridCount": len(review_grids),
    "resizeRebuildCandidateGridIds": [grid["gridId"] for grid in review_grids],
    "aggregateCapital": {
        "existingObservedCapitalUsdt": aggregate_existing_capital,
        "candidateCapitalUsdt": aggregate_candidate_capital,
        "candidateCapitalDeltaUsdt": round(aggregate_candidate_capital - aggregate_existing_capital, 4),
        "totalReviewCapitalCapUsdt": total_review_capital_cap,
    },
    "gridRecommendations": grids,
    "missingEvidence": missing_evidence,
    "remainingExecutionBlockersBeforeMutation": remaining_execution_blockers,
    "requiredOperatorAuthorization": [
        "separate written approval naming grid ids to close/recreate or resize/rebuild",
        "fresh grid post-open smoke for every target grid immediately before mutation",
        "fresh getGridTrendAdjustmentReview packet immediately before mutation",
        "fresh event-risk R0 evidence or separate event-risk operating override",
        "separate maximum capital approval matching candidateCapitalUsdt and totalReviewCapitalCapUsdt",
        "separate createGrid/closeGrid approval after reviewing candidate range, buyLevels, capital, and stop condition",
    ],
    "followUpReadOnlyVerification": [
        "scripts/smoke_grid_trend_adjustment_review_ssh.ps1 -Symbol BTCUSDT -LookbackHours 72",
        "scripts/smoke_grid_post_open_ssh.ps1 -GridId <id> -Symbol BTCUSDT",
        "scripts/verify_split_acceptance_ssh.ps1",
        "rerun this prepare_grid_resize_rebuild_operator_packet_ssh.ps1 packet",
    ],
    "abortCriteria": [
        "any target grid has holding > 0 without a separate exit plan",
        "any target grid has material failed levels",
        "event-risk is not R0 and no separate event-risk override exists",
        "candidate total capital exceeds the reviewed cap",
        "trend review loses 1h/4h evidence or candidatePlan fields",
        "runtime log smoke reports high-risk operation-like lines",
    ],
    "reviewReady": review_ready,
    "resizeRebuildReviewAllowed": review_ready,
    "productionEnvChangeAllowed": False,
    "deployAllowed": False,
    "closeGridAllowed": False,
    "createGridAllowed": False,
    "gridMutationAllowed": False,
    "schedulerEnablementAllowed": False,
    "orderAllowed": False,
    "ocoMutationAllowed": False,
    "telegramSendAllowed": False,
    "notAuthorization": "read-only grid resize/rebuild operator packet only; not approval to close, create, resize, rebalance, place orders, modify OCO, send Telegram, change env, deploy, restart, or mutate DB/grid/fund/Earn/exchange state",
    "excerpts": {
        "trendReview": review[:1800],
        "listGrids": list_grids[:1200],
        "priceAlignment": alignment[:1000],
        "exposure": exposure[:1200],
        "eventRisk": event_risk[:1000],
    },
}

print("[grid-resize-rebuild-operator-packet] read-only packet")
print("scope=READ_ONLY; server-local /api/mcp only; no production env, DB, order, OCO, grid mutation, fund, Earn, Telegram, scheduler, exchange, deploy, restart, or nginx state changed.")
print("grid_resize_rebuild_operator_packet_type=GRID_RESIZE_REBUILD_OPERATOR_PACKET")
print(f"grid_resize_rebuild_operator_status={status}")
print(f"grid_resize_rebuild_operator_decision={decision}")
print(f"grid_resize_rebuild_operator_review_ready={str(review_ready).lower()}")
print(f"grid_resize_rebuild_candidate_grid_count={len(review_grids)}")
print("grid_resize_rebuild_candidate_grid_ids=" + json.dumps([grid["gridId"] for grid in review_grids]))
print(f"grid_resize_rebuild_candidate_capital_usdt={aggregate_candidate_capital}")
print(f"grid_resize_rebuild_existing_observed_capital_usdt={aggregate_existing_capital}")
print(f"grid_resize_rebuild_event_risk_gate={event_risk_gate}")
print("production_env_change_allowed=false")
print("deploy_allowed=false")
print("close_grid_allowed=false")
print("create_grid_allowed=false")
print("grid_mutation_allowed=false")
print("scheduler_enablement_allowed=false")
print("order_allowed=false")
print("oco_mutation_allowed=false")
print("telegram_send_allowed=false")
print("grid_resize_rebuild_operator_missing_evidence=" + json.dumps(missing_evidence, sort_keys=True))
print("grid_resize_rebuild_operator_execution_blockers=" + json.dumps(remaining_execution_blockers, sort_keys=True))
print("grid_resize_rebuild_operator_packet=" + json.dumps(packet, ensure_ascii=False, sort_keys=True))
print("notAuthorization=read-only grid resize/rebuild operator packet only; separate explicit approval required for any closeGrid/createGrid/rebalance/order/OCO/env/deploy action")
if os.environ.get("REQUIRE_REVIEW_READY", "false").lower() == "true" and not review_ready:
    fail(f"RequireReviewReady set but status={status}; missingEvidence={missing_evidence}")
PY
'@

$remoteScript = $remoteScript.Replace("__APPDIR__", $AppDir)
$remoteScript = $remoteScript.Replace("__ENVFILE__", $EnvFile)
$remoteScript = $remoteScript.Replace("__SYMBOL__", $Symbol)
$remoteScript = $remoteScript.Replace("__LOOKBACK_HOURS__", [string]$LookbackHours)
$remoteScript = $remoteScript.Replace("__TOTAL_REVIEW_CAPITAL_CAP_USDT__", [string]$TotalReviewCapitalCapUsdt)

if ($RequireReviewReady) {
    $remoteScript = "export REQUIRE_REVIEW_READY=true`n" + $remoteScript
} else {
    $remoteScript = "export REQUIRE_REVIEW_READY=false`n" + $remoteScript
}

Write-Host "[grid-resize-rebuild-operator-packet] read-only packet"
Write-Host "scope=READ_ONLY; prepares operator evidence only; no production env, DB, order, OCO, grid mutation, fund, Earn, Telegram, scheduler, exchange, deploy, restart, or nginx state changed."

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    throw "grid resize/rebuild operator packet failed with exit code $LASTEXITCODE"
}
