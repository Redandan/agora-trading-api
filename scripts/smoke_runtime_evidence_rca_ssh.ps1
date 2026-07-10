param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [int]$Minutes = 43200,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}

if ($Minutes -lt 60 -or $Minutes -gt 43200) {
    throw "Minutes must be between 60 and 43200."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-McpSmokeTokenSafe -Name "Side" -Value $Side -MaxLength 16

$remoteScript = @"
set -euo pipefail
cd '$AppDir'

PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "FAIL: TRADING_MCP_KEY missing in env file" >&2
  exit 1
fi

export PORT MCP_KEY SYMBOL='$Symbol' STRATEGY_ID='$StrategyId' SIDE='$Side' MINUTES='$Minutes' ENV_FILE='$EnvFile' REQUIRE_READY='$($RequireReady.IsPresent)'
python3 - <<'PY'
import json
import os
import re
import sys
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
headers = {
    "Content-Type": "application/json",
    "Authorization": f"Bearer {os.environ['MCP_KEY']}",
}
symbol = os.environ["SYMBOL"].upper()
strategy_id = int(os.environ["STRATEGY_ID"])
side = os.environ["SIDE"].upper()
minutes = int(os.environ["MINUTES"])
env_file = os.environ["ENV_FILE"]
require_ready = os.environ.get("REQUIRE_READY", "").lower() == "true"

def read_env_key(key):
    value = ""
    try:
        with open(env_file, "r", encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k == key:
                    value = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        return "MISSING_ENV_FILE"
    return value or "EMPTY"

def request(body, timeout=160):
    req = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        error_body = exc.read().decode("utf-8", "replace")
        raise RuntimeError(f"HTTP {exc.code}: {error_body}") from exc
    data = json.loads(raw)
    if data.get("error"):
        raise RuntimeError(data["error"])
    return data

def call_tool(name, arguments=None, timeout=160):
    data = request({
        "jsonrpc": "2.0",
        "id": f"runtime-evidence-rca-{name}",
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }, timeout=timeout)
    result = data.get("result") or {}
    if result.get("isError"):
        raise RuntimeError(f"{name} returned isError=true: {result}")
    content = result.get("content") or []
    if content and isinstance(content[0], dict):
        text = content[0].get("text") or ""
    else:
        text = json.dumps(result, ensure_ascii=False)
    if isinstance(text, str) and len(text) >= 2 and text[0] == '"' and text[-1] == '"':
        try:
            decoded = json.loads(text)
            if isinstance(decoded, str):
                return decoded
        except Exception:
            pass
    return text

def require(description, pattern, text):
    if not re.search(pattern, text, re.MULTILINE):
        print(f"FAIL: missing {description}; pattern={pattern}", file=sys.stderr)
        sys.exit(1)

def field(pattern, text, default="N/A"):
    match = re.search(pattern, text, re.MULTILINE)
    return match.group(1).strip() if match else default

def int_or_none(value):
    try:
        return int(str(value).strip())
    except Exception:
        return None

def compact(text, limit=320):
    value = str(text or "N/A").replace("\n", " ").strip()
    return value if len(value) <= limit else value[:limit - 3] + "..."

def count_lines(pattern, text):
    return sum(1 for line in text.splitlines() if re.search(pattern, line))

def evidence_row_chunks(text):
    matches = list(re.finditer(r"(?m)^(\d+)\. #([0-9]+)\s+(.*)$", text or ""))
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text or "")
        yield match.group(0), (text or "")[start:end]

def is_target_strategy_row(header, strategy_id):
    return re.search(r"\bstrategy=" + re.escape(str(strategy_id)) + r"\b", header or "") is not None

def count_target_strategy_rows(text, strategy_id):
    return sum(1 for header, _chunk in evidence_row_chunks(text) if is_target_strategy_row(header, strategy_id))

def is_shadow_like_row(header, chunk):
    combined = f"{header}\n{chunk}"
    return re.search(
        r"SHADOW_MODE|intentCreated\s*=\s*True|intentCreated\s*=\s*true|\"intentCreated\"\s*:\s*true",
        combined,
        re.IGNORECASE,
    ) is not None

def count_target_strategy_shadow_like_rows(text, strategy_id):
    return sum(
        1
        for header, chunk in evidence_row_chunks(text)
        if is_target_strategy_row(header, strategy_id) and is_shadow_like_row(header, chunk)
    )

def parse_order_sent_rows(text, target_strategy_id, raw_order_sent_count):
    rows = []
    matches = list(re.finditer(r"(?m)^(\d+)\. #([0-9]+)\s+(.*)$", text or ""))
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        chunk = text[start:end]
        header = match.group(0)
        if not re.search(r"\borderSent=(true|True)\b", header):
            continue

        def hfield(pattern, default="N/A"):
            m = re.search(pattern, header)
            return m.group(1).strip() if m else default

        strategy = hfield(r"\bstrategy=([^\s]+)")
        action = hfield(r"\baction=([^\s]+)")
        decision = hfield(r"\bdecision=([^\s]+)")
        source = "N/A"
        source_match = re.search(r'"source"\s*:\s*"([^"]+)"', chunk)
        if source_match:
            source = source_match.group(1)
        elif "GRID_" in chunk:
            source = "GRID"
        time_match = re.search(r"#\d+\s+decisionId=\S+\s+(\d\d-\d\d\s+\d\d:\d\d:\d\d\s+Taipei)", header)
        row = {
            "id": match.group(2),
            "decisionId": hfield(r"\bdecisionId=([^\s]+)"),
            "time": time_match.group(1) if time_match else "N/A",
            "symbol": hfield(r"\bTaipei\s+([^\s]+)"),
            "side": hfield(r"\bside=([^\s]+)"),
            "strategyId": strategy,
            "action": action,
            "decision": decision,
            "source": source,
        }
        if strategy == str(target_strategy_id):
            category = "TARGET_STRATEGY"
        elif source.startswith("GRID_") or source == "GRID" or "GRID_" in chunk:
            category = "NON_AUTONOMOUS_GRID"
        elif strategy not in ("N/A", "NULL", "None", ""):
            category = "OTHER_STRATEGY"
        else:
            category = "UNKNOWN_ORDER_SENT"
        row["category"] = category
        rows.append(row)

    raw_count = int_or_none(raw_order_sent_count)
    if raw_count is None:
        raw_count = 0
    unknown_unlisted = max(0, raw_count - len(rows))
    target_count = sum(1 for row in rows if row["category"] == "TARGET_STRATEGY")
    other_count = sum(1 for row in rows if row["category"] == "OTHER_STRATEGY")
    grid_count = sum(1 for row in rows if row["category"] == "NON_AUTONOMOUS_GRID")
    unknown_count = sum(1 for row in rows if row["category"] == "UNKNOWN_ORDER_SENT") + unknown_unlisted
    blocker_count = target_count + unknown_count
    return rows, target_count, other_count, grid_count, unknown_count, blocker_count

print("[runtime-evidence-rca] read-only server-local MCP smoke")
print(f"url={url} symbol={symbol} strategyId={strategy_id} side={side} minutes={minutes}")

dashboard = call_tool("getAutonomousReadinessDashboard", {"symbol": symbol, "minutes": minutes})
evidence = call_tool("listRuntimeDecisionEvidence", {"symbol": symbol, "minutes": minutes, "limit": 50})
target_strategy_evidence_minutes = 1440
target_strategy_evidence = call_tool("listRuntimeDecisionEvidence", {"symbol": symbol, "minutes": target_strategy_evidence_minutes, "limit": 200})
preview = call_tool("previewTinyLiveMinimumOrder", {"symbol": symbol, "strategyId": strategy_id, "side": side})
auto_execution = call_tool("previewTinyLiveAutoExecution", {"symbol": symbol, "strategyId": strategy_id, "side": side})
opportunity = call_tool("validateAutonomousOpportunityReadiness", {"symbol": symbol, "strategyId": strategy_id, "side": side})
truth = call_tool("getNoBuyReasonTruthTable", {"symbol": symbol, "hours": min(720, max(1, minutes // 60)), "limit": 20})

for name, text in [
    ("getAutonomousReadinessDashboard", dashboard),
    ("listRuntimeDecisionEvidence", evidence),
    ("listRuntimeDecisionEvidence target-strategy window", target_strategy_evidence),
    ("previewTinyLiveMinimumOrder", preview),
    ("previewTinyLiveAutoExecution", auto_execution),
    ("validateAutonomousOpportunityReadiness", opportunity),
]:
    require(f"{name} read-only boundary", r"READ_ONLY|no order/OCO/strategy/grid/fund/Earn", text)

require("preview no-order marker", r"orderSent=false", preview)
require("auto-execution no-order marker", r"orderSent=false", auto_execution)
require("truth table read-only marker", r"READ_ONLY|orderSent", truth)

runtime_flag = read_env_key("TRADING_RUNTIME_EVIDENCE_ENABLED")
dashboard_enabled = field(r"symbol=.* enabled=([^\n]+)", dashboard)
runtime_status = field(r"runtimeEvidenceStatus=([^\n]+)", preview)
preview_status = field(r"status=([^\n]+)", preview)
runtime_rows = field(r"runtimeEvidenceRows=(\d+)", dashboard)
sample_count = field(r"sampleCount=(\d+)", dashboard)
readiness_level = field(r"readinessLevel=([^\n]+)", dashboard)
evidence_mode = field(r"evidenceMode=([^\n]+)", dashboard)
final_verdict = field(r"finalReadinessVerdict=([^\n]+)", dashboard)
shadow_intents = field(r"shadowExecutionIntents=(\d+)", dashboard)
shadow_intent_count = field(r"shadowIntentCount=(\d+)", dashboard)
order_sent_evidence = field(r"orderSentEvidence=(\d+)", dashboard)
freshness_blocks = field(r"freshnessTerminalBlocks=(\d+)", dashboard)
evidence_row_lines = count_lines(r"^\d+\. #", evidence)
shadow_line_count = count_lines(r"SHADOW_MODE|intentCreated=True|intentCreated=true|fearGreedWarning", evidence)
target_strategy_review_window_rows = count_target_strategy_rows(evidence, strategy_id)
target_strategy_evidence_rows = count_target_strategy_rows(target_strategy_evidence, strategy_id)
target_strategy_shadow_like_rows = count_target_strategy_shadow_like_rows(target_strategy_evidence, strategy_id)
target_strategy_review_shadow_like_rows = count_target_strategy_shadow_like_rows(evidence, strategy_id)
order_sent_rows, target_order_sent_count, other_strategy_order_sent_count, non_autonomous_grid_order_sent_count, unknown_order_sent_count, order_sent_blocker_count = parse_order_sent_rows(
    evidence, strategy_id, order_sent_evidence)
required_fields = {
    "dashboardEnabled": dashboard_enabled,
    "runtimeEvidenceStatus": runtime_status,
    "runtimeEvidenceRows": runtime_rows,
    "sampleCount": sample_count,
    "readinessLevel": readiness_level,
    "evidenceMode": evidence_mode,
    "finalReadinessVerdict": final_verdict,
    "shadowExecutionIntents": shadow_intents,
    "shadowIntentCount": shadow_intent_count,
    "orderSentEvidence": order_sent_evidence,
    "freshnessTerminalBlocks": freshness_blocks,
}
missing_fields = [key for key, value in required_fields.items() if value in ("", "N/A")]

if missing_fields:
    diagnosis = "REVIEW_RUNTIME_EVIDENCE_STATUS"
elif runtime_status == "NOT_READY_ENABLED_FALSE" or dashboard_enabled.lower() == "false":
    diagnosis = "CONFIG_DISABLED"
elif runtime_rows == "0":
    diagnosis = "NO_CANONICAL_ROWS"
elif runtime_status == "NOT_READY_NO_CANONICAL_ROWS" and target_strategy_evidence_rows == 0:
    diagnosis = "NO_TARGET_STRATEGY_CANONICAL_ROWS"
elif runtime_status == "NOT_READY_NO_CANONICAL_ROWS":
    diagnosis = "REVIEW_RUNTIME_EVIDENCE_STATUS"
elif runtime_status == "AVAILABLE_CANONICAL_ROWS":
    diagnosis = "CANONICAL_ROWS_NO_SHADOW_INTENT"
elif runtime_status.startswith("AVAILABLE_CANONICAL") and target_strategy_shadow_like_rows > 0:
    diagnosis = "CANONICAL_SHADOW_READY"
elif runtime_status.startswith("AVAILABLE_CANONICAL"):
    diagnosis = "CANONICAL_ROWS_NO_SHADOW_INTENT"
else:
    diagnosis = "REVIEW_RUNTIME_EVIDENCE_STATUS"

review_plan = []
if missing_fields:
    review_plan.append({
        "gate": "missing-fields",
        "state": "BLOCKED",
        "riskCategory": "incomplete-evidence",
        "evidenceMarkers": [f"missing_runtime_evidence_fields={json.dumps(missing_fields)}"],
        "requiredEvidence": "missing_runtime_evidence_fields=[]",
        "nextAction": "Fix runtime-evidence RCA output or MCP tool fields before trusting this evidence.",
        "notAuthorization": "Do not treat incomplete runtime-evidence output as live-review evidence.",
    })
if diagnosis == "CONFIG_DISABLED":
    review_plan.append({
        "gate": "config",
        "state": "BLOCKED",
        "riskCategory": "evidence-collection-disabled",
        "evidenceMarkers": [f"env.TRADING_RUNTIME_EVIDENCE_ENABLED={runtime_flag}", f"runtimeEvidenceStatus={runtime_status}", f"dashboardEnabled={dashboard_enabled}"],
        "requiredEvidence": "A separately authorized evidence-only env change enables runtime evidence while all order-capable/background automation flags remain disabled.",
        "nextAction": "Review docs/live-runtime-evidence-env-proposal.md; do not apply env changes from this smoke.",
        "notAuthorization": "This does not authorize production env mutation, scheduler enablement, orders, OCO, grid, fund, Earn, Telegram, exchange writes, or DB mutation.",
    })
elif diagnosis == "NO_CANONICAL_ROWS":
    review_plan.append({
        "gate": "canonical-rows",
        "state": "BLOCKED",
        "riskCategory": "no-evidence-sample",
        "evidenceMarkers": [f"runtimeEvidenceRows={runtime_rows}", f"sampleCount={sample_count}"],
        "requiredEvidence": "Canonical runtime evidence rows exist in the bounded review window.",
        "nextAction": "Keep collection enabled only if separately authorized and rerun this RCA after enough dry-run/shadow candidates exist.",
        "notAuthorization": "Do not bypass this by enabling live execution.",
    })
elif diagnosis == "NO_TARGET_STRATEGY_CANONICAL_ROWS":
    review_plan.append({
        "gate": "target-strategy-canonical-rows",
        "state": "BLOCKED",
        "riskCategory": "target-strategy-no-evidence-sample",
        "evidenceMarkers": [f"targetStrategyId={strategy_id}", f"targetStrategyEvidenceWindowMinutes={target_strategy_evidence_minutes}", f"targetStrategyEvidenceRows={target_strategy_evidence_rows}", f"runtimeEvidenceRows={runtime_rows}", f"runtimeEvidenceStatus={runtime_status}"],
        "requiredEvidence": "Canonical runtime evidence rows exist for the reviewed target strategy in the same recent window used by the preview gate.",
        "nextAction": "Rerun this RCA with the intended strategy id after a dry-run/shadow candidate is observed; do not treat symbol-level rows as target-strategy approval.",
        "notAuthorization": "Target-strategy evidence gaps do not authorize live execution, env relaxation, or bypassing strategy-specific blockers.",
    })
elif diagnosis == "CANONICAL_ROWS_NO_SHADOW_INTENT":
    review_plan.append({
        "gate": "shadow-intent",
        "state": "BLOCKED",
        "riskCategory": "missing-shadow-intent",
        "evidenceMarkers": [f"targetStrategyShadowLikeRows={target_strategy_shadow_like_rows}", f"targetStrategyReviewShadowLikeRows={target_strategy_review_shadow_like_rows}", f"shadowIntentCount={shadow_intent_count}", f"shadowExecutionIntents={shadow_intents}"],
        "requiredEvidence": "targetStrategyShadowLikeRows > 0 and orderSentEvidenceBlockerCount=0 for the reviewed target-strategy window.",
        "nextAction": "Continue dry-run/shadow evidence collection; keep execution disabled.",
        "notAuthorization": "Canonical rows without shadow intent do not authorize live trading.",
    })
elif diagnosis == "CANONICAL_SHADOW_READY":
    review_plan.append({
        "gate": "canonical-shadow",
        "state": "READY_FOR_OTHER_BLOCKER_REVIEW",
        "riskCategory": "runtime-evidence-ready",
        "evidenceMarkers": [f"targetStrategyShadowLikeRows={target_strategy_shadow_like_rows}", f"shadowIntentCount={shadow_intent_count}", f"orderSentEvidenceBlockerCount={order_sent_blocker_count}"],
        "requiredEvidence": "Full live-readiness bundle clears all other blockers.",
        "nextAction": "Use full bundle, tiny-live hard-stop, signal policy, background automation, and audit evidence before drafting any live review packet.",
        "notAuthorization": "Runtime evidence readiness alone is not live approval.",
    })
else:
    review_plan.append({
        "gate": "status-review",
        "state": "BLOCKED",
        "riskCategory": "unclassified-runtime-evidence-status",
        "evidenceMarkers": [f"diagnosis={diagnosis}", f"runtimeEvidenceStatus={runtime_status}"],
        "requiredEvidence": "diagnosis=CANONICAL_SHADOW_READY, missing_runtime_evidence_fields=[], targetStrategyShadowLikeRows > 0, orderSentEvidenceBlockerCount=0.",
        "nextAction": "Review and classify the runtime-evidence status before any env plan.",
        "notAuthorization": "Unclassified runtime-evidence status cannot be used for live review.",
    })
if order_sent_blocker_count > 0:
    review_plan.append({
        "gate": "order-sent",
        "state": "HARD_BLOCKED",
        "riskCategory": "unexpected-order-evidence",
        "evidenceMarkers": [f"orderSentEvidenceBlockerCount={order_sent_blocker_count}", f"orderSentEvidenceTargetStrategy={target_order_sent_count}", f"orderSentEvidenceUnknown={unknown_order_sent_count}"],
        "requiredEvidence": "orderSentEvidenceBlockerCount=0 in the reviewed target-strategy window.",
        "nextAction": "Stop live review and investigate target-strategy or unclassified order-sent evidence.",
        "notAuthorization": "Target-strategy or unclassified order-sent evidence blocks live review.",
    })
elif int_or_none(order_sent_evidence) not in (None, 0):
    review_plan.append({
        "gate": "order-sent-non-target",
        "state": "REVIEWED_NON_TARGET_ORDER_EVIDENCE",
        "riskCategory": "known-order-evidence-scoped-out",
        "evidenceMarkers": [f"orderSentEvidence={order_sent_evidence}", f"orderSentEvidenceOtherStrategy={other_strategy_order_sent_count}", f"orderSentEvidenceNonAutonomousGrid={non_autonomous_grid_order_sent_count}"],
        "requiredEvidence": "Known non-target order-sent rows are listed and orderSentEvidenceBlockerCount=0.",
        "nextAction": "Keep this as related order evidence; do not use it as target-strategy live approval or as a reason to bypass other blockers.",
        "notAuthorization": "Known non-target order evidence does not authorize new orders or live policy relaxation.",
    })

print("")
print("Runtime Evidence Gate:")
print(f"  diagnosis={diagnosis}")
print(f"  env.TRADING_RUNTIME_EVIDENCE_ENABLED={runtime_flag}")
print(f"  dashboardEnabled={dashboard_enabled}")
print(f"  runtimeEvidenceStatus={runtime_status}")
print(f"  targetStrategyId={strategy_id}")
print(f"  previewStatus={preview_status}")
print(f"  evidenceMode={evidence_mode}")
print(f"  readinessLevel={readiness_level}")
print(f"  finalReadinessVerdict={final_verdict}")

print("")
print("Recent Evidence Window:")
print(f"  minutes={minutes}")
print(f"  runtimeEvidenceRows={runtime_rows}")
print(f"  sampleCount={sample_count}")
print(f"  listedRows={evidence_row_lines}")
print(f"  targetStrategyEvidenceWindowMinutes={target_strategy_evidence_minutes}")
print(f"  targetStrategyEvidenceRows={target_strategy_evidence_rows}")
print(f"  targetStrategyReviewWindowRows={target_strategy_review_window_rows}")
print(f"  targetStrategyShadowLikeRows={target_strategy_shadow_like_rows}")
print(f"  targetStrategyReviewShadowLikeRows={target_strategy_review_shadow_like_rows}")
print(f"  shadowExecutionIntents={shadow_intents}")
print(f"  shadowIntentCount={shadow_intent_count}")
print(f"  shadowLikeListedRows={shadow_line_count}")
print(f"  orderSentEvidence={order_sent_evidence}")
print(f"  orderSentEvidenceTargetStrategy={target_order_sent_count}")
print(f"  orderSentEvidenceOtherStrategy={other_strategy_order_sent_count}")
print(f"  orderSentEvidenceNonAutonomousGrid={non_autonomous_grid_order_sent_count}")
print(f"  orderSentEvidenceUnknown={unknown_order_sent_count}")
print(f"  orderSentEvidenceBlockerCount={order_sent_blocker_count}")
print(f"  order_sent_evidence_rows={json.dumps(order_sent_rows[:8], sort_keys=True)}")
print(f"  freshnessTerminalBlocks={freshness_blocks}")
print(f"  missing_runtime_evidence_fields={json.dumps(missing_fields)}")
print(f"  runtime_evidence_review_plan={json.dumps(review_plan, sort_keys=True)}")

print("")
print("Candidate Context:")
print(f"  noCurrentBuyCandidateReason={field(r'noCurrentBuyCandidateReason=([^\n]+)', preview)}")
print(f"  currentSignalDecision={field(r'currentSignalDecision=([^\n]+)', preview)}")
print(f"  currentSignalSource={field(r'currentSignalSource=([^\n]+)', preview)}")
print(f"  currentSignalAgeMinutes={field(r'currentSignalAgeMinutes=([^\n]+)', preview)}")
print(f"  opportunity={compact(opportunity, 520)}")
print(f"  noBuyTruthTable={compact(truth, 520)}")

print("")
print("Recommendations:")
if diagnosis == "CONFIG_DISABLED":
    print("  - KEEP_DISABLED_FOR_NOW: runtime evidence collection is disabled; do not enable live until an explicit dry-run/evidence plan is approved.")
    print("  - PLAN: if authorized later, enable runtime-evidence collection before any live execution flag, then rerun this RCA for canonical shadow rows.")
elif diagnosis == "NO_CANONICAL_ROWS":
    print("  - COLLECT_EVIDENCE_FIRST: runtime evidence is enabled but no canonical rows were found in the selected window.")
elif diagnosis == "NO_TARGET_STRATEGY_CANONICAL_ROWS":
    print("  - COLLECT_TARGET_STRATEGY_EVIDENCE_FIRST: symbol-level canonical rows exist, but the reviewed target strategy has no canonical rows in the selected window.")
elif diagnosis == "CANONICAL_ROWS_NO_SHADOW_INTENT":
    print("  - REQUIRE_TARGET_STRATEGY_SHADOW_INTENT: target-strategy canonical rows exist but do not yet prove shadow/tiny-live intent coverage.")
elif diagnosis == "CANONICAL_SHADOW_READY":
    print("  - REVIEW_WITH_FULL_AUDIT: runtime evidence gate appears satisfied; live-readiness still requires all other blockers to clear.")
else:
    print("  - REVIEW: runtime evidence status needs operator interpretation before any live env plan.")
print("  - SCOPE: this smoke is read-only and must not write RuntimeDecisionEvidence, place orders, send Telegram, or enable flags.")
print("[runtime-evidence-rca] OK read-only check complete")
try:
    shadow_ready = int(target_strategy_shadow_like_rows) > 0
except Exception:
    shadow_ready = False
no_blocking_order_sent = order_sent_blocker_count == 0
if require_ready and not (diagnosis == "CANONICAL_SHADOW_READY" and not missing_fields and shadow_ready and no_blocking_order_sent):
    raise SystemExit(2)
PY
"@

$remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
if ($LASTEXITCODE -ne 0) {
    if ($RequireReady.IsPresent -and $LASTEXITCODE -eq 2) {
        exit 2
    }
    throw "runtime evidence RCA smoke failed with exit code $LASTEXITCODE"
}
