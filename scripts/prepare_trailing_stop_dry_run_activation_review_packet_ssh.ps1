param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [string]$IntervalCode = "1h",
    [string]$ReplayIntervalCode = "1m",
    [int]$Days = 30,
    [int]$Limit = 500,
    [int[]]$StrategyIds = @(485, 574),
    [switch]$RequireReady
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

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for trailing activation review arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-ChildScript {
    param([string]$Name, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $Name
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for trailing activation review." }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

function Invoke-RemoteSnapshot {
    param([string]$StrategyIdsCsv)

    $remoteScript = @"
set -euo pipefail
cd '$AppDir'
PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "trailing_activation_snapshot_status=MCP_KEY_MISSING"
  exit 0
fi
export PORT MCP_KEY STRATEGY_IDS='$StrategyIdsCsv'
python3 - <<'PY'
import json
import os
import re
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
key = os.environ["MCP_KEY"]
strategy_ids = [int(x) for x in os.environ["STRATEGY_IDS"].split(",") if x.strip()]

def call_tool(name, arguments=None):
    body = {
        "jsonrpc": "2.0",
        "id": name,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments or {}},
    }
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        payload = json.loads(response.read().decode("utf-8"))
    if payload.get("error"):
        return "MCP_ERROR:" + json.dumps(payload["error"], sort_keys=True)
    content = payload.get("result", {}).get("content", [])
    text = "\n".join(item.get("text", "") for item in content if isinstance(item, dict))
    try:
        decoded = json.loads(text)
        if isinstance(decoded, str):
            return decoded
    except Exception:
        pass
    return text

status_text = call_tool("getTrailingStopStatus")
enabled_match = re.search(r"global\.enabled:\s*(true|false)", status_text, re.I)
dry_run_match = re.search(r"global\.dryRun:\s*(true|false)", status_text, re.I)
open_match = re.search(r"open_oco_positions:\s*([0-9]+)", status_text, re.I)
print("trailing_activation_snapshot_status=OK")
print("trailing_stop_status_global_enabled=" + (enabled_match.group(1).lower() if enabled_match else "UNKNOWN"))
print("trailing_stop_status_global_dry_run=" + (dry_run_match.group(1).lower() if dry_run_match else "UNKNOWN"))
print("trailing_stop_status_open_oco_positions=" + (open_match.group(1) if open_match else "UNKNOWN"))

opt_in_count = 0
for strategy_id in strategy_ids:
    config_text = call_tool("getStrategyConfig", {"strategyId": strategy_id})
    opt_in = bool(re.search(r'"trailingStopEnabled"\s*:\s*true', config_text, re.I))
    if opt_in:
        opt_in_count += 1
    print(f"strategy_{strategy_id}_trailing_opt_in={str(opt_in).lower()}")
print("trailing_strategy_opt_in_count=" + str(opt_in_count))
PY
"@

    $output = @($remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s")
    $exitCode = $LASTEXITCODE
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($null -eq (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH. Install OpenSSH client or Git for Windows with ssh." }
if ($Days -lt 1 -or $Days -gt 90) { throw "Days must be between 1 and 90." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }
if ($StrategyIds.Count -lt 1 -or $StrategyIds.Count -gt 10) { throw "StrategyIds must include between 1 and 10 strategy ids." }
foreach ($strategyId in $StrategyIds) {
    if ($strategyId -lt 1 -or $strategyId -gt 1000000) { throw "StrategyIds contains unsupported strategy id: $strategyId" }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-IntervalCode", $IntervalCode,
    "-ReplayIntervalCode", $ReplayIntervalCode,
    "-Days", [string]$Days,
    "-Limit", [string]$Limit
)

$trailingPacketResult = Invoke-ChildScript -Name "prepare_trailing_stop_operator_review_packet_ssh.ps1" -Arguments $commonArgs
$liveAuditResult = Invoke-ChildScript -Name "audit_live_readiness_ssh.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)
$snapshot = Invoke-RemoteSnapshot -StrategyIdsCsv (($StrategyIds | ForEach-Object { [string]$_ }) -join ",")

$trailingPacketJson = Get-LastPrefixedValue -Text $trailingPacketResult.Text -Prefix "trailing_stop_operator_review_packet="
$trailingPacket = $null
if (-not [string]::IsNullOrWhiteSpace($trailingPacketJson)) {
    $trailingPacket = $trailingPacketJson | ConvertFrom-Json -ErrorAction Stop
}

$snapshotStatus = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "trailing_activation_snapshot_status="
$globalEnabled = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "trailing_stop_status_global_enabled="
$globalDryRun = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "trailing_stop_status_global_dry_run="
$openOcoPositions = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "trailing_stop_status_open_oco_positions="
$optInCountText = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "trailing_strategy_opt_in_count="
$optInCount = 0
[void][int]::TryParse($optInCountText, [ref]$optInCount)
$reviewedStrategyOptIn = @()
foreach ($strategyId in $StrategyIds) {
    $value = Get-LastPrefixedValue -Text $snapshot.Text -Prefix "strategy_$($strategyId)_trailing_opt_in="
    if ([string]::IsNullOrWhiteSpace($value)) { $value = "UNKNOWN" }
    $reviewedStrategyOptIn += [pscustomobject]@{
        strategyId = $strategyId
        trailingStopEnabled = $value
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($trailingPacketResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "trailing-stop operator review packet completed" }
if ($null -eq $trailingPacket) { Add-MissingRequirement -List $missingRequirements -Value "trailing_stop_operator_review_packet valid JSON" }
if ($null -ne $trailingPacket -and [string]$trailingPacket.status -ne "READY_FOR_OPERATOR_PACKET_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing-stop operator packet ready"
}
if ($null -ne $trailingPacket -and [string]$trailingPacket.acceptance -ne "PASS") {
    Add-MissingRequirement -List $missingRequirements -Value "trailing replay acceptance=PASS"
}
if ($liveAuditResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "live-readiness audit completed" }
if ($snapshot.ExitCode -ne 0 -or $snapshotStatus -ne "OK") { Add-MissingRequirement -List $missingRequirements -Value "server-local trailing snapshot completed" }
if ($globalDryRun -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "TRAILING_STOP_DRY_RUN currently true before activation" }
if ($optInCount -lt 1) { Add-MissingRequirement -List $missingRequirements -Value "separate strategy trailingStopEnabled opt-in authorization for at least one reviewed strategy" }

$currentDryRunActive = $globalEnabled -eq "true" -and $globalDryRun -eq "true"
$ready = $missingRequirements.Count -eq 0
$status = if ($ready -and $currentDryRunActive) {
    "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_REVIEW_ONLY"
} elseif ($ready) {
    "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_REVIEW_NOT_APPLIED"
} elseif ($optInCount -lt 1 -and $missingRequirements.Count -eq 1) {
    "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED"
} else {
    "NOT_READY"
}
$activationDecision = if ($status -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_REVIEW_ONLY") {
    "VERIFY_ACTIVE_DRY_RUN_OBSERVATION"
} elseif ($ready) {
    "REQUEST_OPERATOR_AUTHORIZATION_FOR_DRY_RUN_ENV_DIFF"
} elseif ($status -eq "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED") {
    "REQUEST_SEPARATE_STRATEGY_OPT_IN_AUTHORIZATION_BEFORE_ENV_DIFF"
} else {
    "FIX_TRAILING_DRY_RUN_ACTIVATION_EVIDENCE"
}
$currentObservationSampleStatus = if ($openOcoPositions -eq "0") {
    "NO_OPEN_OCO_POSITIONS_CURRENTLY"
} elseif ([string]::IsNullOrWhiteSpace($openOcoPositions) -or $openOcoPositions -eq "UNKNOWN") {
    "OPEN_OCO_POSITION_COUNT_UNKNOWN"
} else {
    "OPEN_OCO_POSITIONS_AVAILABLE_FOR_DRY_RUN_OBSERVATION"
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_DRY_RUN_ACTIVATION_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    strategyIds = @($StrategyIds)
    sourceTrailingPacket = "prepare_trailing_stop_operator_review_packet_ssh.ps1"
    sourceTrailingPacketStatus = if ($null -ne $trailingPacket) { [string]$trailingPacket.status } else { "" }
    sourceLiveReadiness = "audit_live_readiness_ssh.ps1"
    trailingAcceptance = if ($null -ne $trailingPacket) { [string]$trailingPacket.acceptance } else { "" }
    trailingImprovementPct = if ($null -ne $trailingPacket) { [string]$trailingPacket.improvementPct } else { "" }
    trailingDeltaPnl = if ($null -ne $trailingPacket) { [string]$trailingPacket.acceptanceDeltaPnl } else { "" }
    acceptanceRows = if ($null -ne $trailingPacket) { [string]$trailingPacket.acceptanceRows } else { "" }
    ambiguousSameBarRows = if ($null -ne $trailingPacket) { [string]$trailingPacket.ambiguousSameBar } else { "" }
    currentGlobalEnabled = $globalEnabled
    currentGlobalDryRun = $globalDryRun
    currentOpenOcoPositions = $openOcoPositions
    currentObservationSampleStatus = $currentObservationSampleStatus
    reviewedStrategyOptInCount = $optInCount
    reviewedStrategyOptIn = @($reviewedStrategyOptIn)
    activationDecision = $activationDecision
    proposedSeparateStrategyOptInReview = @($StrategyIds | ForEach-Object {
        "setTrailingStopOptIn(strategyId=$_, enabled=true, notes='trailing dry-run observation only')"
    })
    proposedSeparateEnvDiff = @(
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true"
    )
    envDiffMustRemain = @(
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "TRADING_OCO_POLLER_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false"
    )
    proposedRuntimeBoundary = [pscustomobject]@{
        schedulerDryRunOnly = $true
        liveTradingAllowed = $false
        orderAllowed = $false
        ocoMutationAllowed = $false
        positionCloseAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
    }
    requiredSeparateAuthorization = @(
        "operator explicitly authorizes production env diff",
        "operator explicitly authorizes deploy/restart",
        "operator explicitly authorizes strategy trailingStopEnabled opt-in for at least one reviewed strategy if current opt-in count is 0",
        "TRAILING_STOP_ENABLED=true",
        "TRAILING_STOP_DRY_RUN=true",
        "post-activation read-only verification only"
    )
    postActivationReadOnlyVerification = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_trailing_stop_pnl_replay_ssh.ps1 -Symbol $Symbol -IntervalCode $IntervalCode -ReplayIntervalCode $ReplayIntervalCode -Days $Days -Limit $Limit -RequireAcceptance",
        ".\scripts\audit_live_readiness_ssh.ps1",
        "server-local MCP getTrailingStopStatus",
        "server-local MCP getStrategyConfig confirms reviewed strategy opt-in",
        "runtime-log smoke: no OCO modification/order/close-position lines while dry-run=true"
    )
    explicitNonAuthorizations = @(
        "does not call setTrailingStopOptIn",
        "does not set TRAILING_STOP_DRY_RUN=false",
        "does not modify OCO",
        "does not place orders",
        "does not close positions",
        "does not enable live trading",
        "does not send Telegram",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    missingRequirements = @($missingRequirements)
    nextAction = if ($ready) {
        "Request separate operator authorization for the exact trailing-stop dry-run env diff, then deploy/restart and run read-only verification."
    } elseif ($status -eq "BLOCKED_STRATEGY_TRAILING_OPT_IN_NOT_APPLIED") {
        "Request separate operator authorization to set trailingStopEnabled=true for at least one reviewed strategy, then rerun this packet before requesting the global dry-run env diff."
    } else {
        "Fix missing trailing-stop dry-run activation evidence before requesting env/deploy authorization."
    }
    notAuthorization = "read-only trailing-stop dry-run activation review packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[trailing-stop-dry-run-activation-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_trailing_stop_operator_review_packet_ssh.ps1, audit_live_readiness_ssh.ps1, and server-local read-only MCP status/config calls only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_trailing_packet=prepare_trailing_stop_operator_review_packet_ssh.ps1 exitCode=$($trailingPacketResult.ExitCode)"
Write-Host "source_live_readiness=audit_live_readiness_ssh.ps1 exitCode=$($liveAuditResult.ExitCode)"
Write-Host $snapshot.Text
Write-Host "trailing_stop_acceptance=$($packet.trailingAcceptance)"
Write-Host "trailing_stop_improvement_pct=$($packet.trailingImprovementPct)"
Write-Host "trailing_stop_delta_pnl=$($packet.trailingDeltaPnl)"
Write-Host "trailing_stop_activation_current_global_enabled=$globalEnabled"
Write-Host "trailing_stop_activation_current_global_dry_run=$globalDryRun"
Write-Host "trailing_stop_activation_open_oco_positions=$openOcoPositions"
Write-Host "trailing_stop_activation_observation_sample_status=$currentObservationSampleStatus"
Write-Host "trailing_stop_activation_reviewed_strategy_opt_in_count=$optInCount"
Write-Host ("trailing_stop_activation_reviewed_strategy_opt_in=" + (ConvertTo-Json -Compress @($reviewedStrategyOptIn)))
Write-Host ("trailing_stop_activation_strategy_opt_in_required=" + ($(if ($optInCount -lt 1) { "true" } else { "false" })))
Write-Host "trailing_stop_activation_allowed=false"
Write-Host "trailing_stop_strategy_opt_in_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_dry_run_activation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("trailing_stop_dry_run_activation_review_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_dry_run_activation_status=$status"
Write-Host "trailing_stop_dry_run_activation_decision=$activationDecision"
Write-Host "notAuthorization=read-only trailing-stop dry-run activation review packet only; does not call setTrailingStopOptIn, change production env, deploy, restart, enable scheduler, place orders, modify OCO, close positions, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[trailing-stop-dry-run-activation-review-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop dry-run activation review packet is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
