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
    [int]$StrategyId = 574,
    [string]$Notes = "trailing dry-run observation only; no order or OCO mutation",
    [string]$RollbackNotes = "rollback trailing dry-run opt-in observation",
    [string]$SourceReviewLog = "",
    [string]$SourcePostOptInLog = "",
    [switch]$Rollback,
    [switch]$Execute,
    [string]$ConfirmText = "",
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
        throw "$Name contains unsupported characters for trailing opt-in execution arguments."
    }
}

function Assert-NotesSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 160 -or $Value -notmatch "^[A-Za-z0-9 ._:/;,+()=-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-PacketValue {
    param([object]$Packet, [string]$Name)
    if ($null -eq $Packet) { return "" }
    $property = $Packet.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    return [string]$property.Value
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-ChildScript {
    param([string]$Name, [hashtable]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $Name
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing script: $scriptPath"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    $output = @()
    $exitCode = 0
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath @Arguments *>&1
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

function Read-SourceLog {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Source log not found: $Path"
    }
    return [pscustomobject]@{
        Text = (Get-Content -Raw -LiteralPath $Path)
        ExitCode = 0
    }
}

function Invoke-RemoteSetTrailingStopOptIn {
    param([int]$TargetStrategyId, [bool]$TargetEnabled, [string]$TargetNotes)

    $targetEnabledText = if ($TargetEnabled) { "true" } else { "false" }

    $remoteScript = @"
set -euo pipefail
cd '$AppDir'
PORT=`$(cat app.port)
MCP_KEY=`$(grep -E '^TRADING_MCP_KEY=' '$EnvFile' | tail -n 1 | sed 's/^[^=]*=//' | sed 's/^"//; s/"`$//; s/^'\''//; s/'\''`$//')
if [ -z "`$MCP_KEY" ]; then
  echo "mcp_write_status=MCP_KEY_MISSING"
  exit 1
fi
export PORT MCP_KEY STRATEGY_ID='$TargetStrategyId' TARGET_ENABLED='$targetEnabledText' NOTES='$TargetNotes'
python3 - <<'PY'
import json
import os
import urllib.error
import urllib.request

url = f"http://127.0.0.1:{os.environ['PORT']}/api/mcp"
key = os.environ["MCP_KEY"]
strategy_id = int(os.environ["STRATEGY_ID"])
target_enabled = os.environ["TARGET_ENABLED"].lower() == "true"
notes = os.environ["NOTES"]

body = {
    "jsonrpc": "2.0",
    "id": "setTrailingStopOptIn",
    "method": "tools/call",
    "params": {
        "name": "setTrailingStopOptIn",
        "arguments": {
            "strategyId": strategy_id,
            "enabled": target_enabled,
            "notes": notes,
        },
    },
}
request = urllib.request.Request(
    url,
    data=json.dumps(body).encode("utf-8"),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
    method="POST",
)
try:
    with urllib.request.urlopen(request, timeout=60) as response:
        payload = json.loads(response.read().decode("utf-8", "replace"))
except urllib.error.HTTPError as exc:
    print("mcp_write_status=HTTP_ERROR")
    print("mcp_write_http_status=" + str(exc.code))
    print("mcp_write_result_json=" + json.dumps(exc.read().decode("utf-8", "replace"), ensure_ascii=True))
    raise

if payload.get("error"):
    print("mcp_write_status=JSONRPC_ERROR")
    print("mcp_write_result_json=" + json.dumps(payload["error"], ensure_ascii=True, sort_keys=True))
    raise SystemExit(2)

content = payload.get("result", {}).get("content", [])
text = "\n".join(item.get("text", "") for item in content if isinstance(item, dict))
lower_text = text.lower()
expected_marker = "trailingStopEnabled=" + str(target_enabled).lower()
enabled_marker = expected_marker in text
oco_marker = "OCO writes performed: false" in text
failed_marker = "failed to update" in lower_text or "strategy not found" in lower_text or "required" in lower_text
print("mcp_write_status=" + ("OK" if enabled_marker and oco_marker and not failed_marker else "UNVERIFIED"))
print("mcp_write_tool=setTrailingStopOptIn")
print("mcp_write_strategy_id=" + str(strategy_id))
print("mcp_write_enabled=" + str(target_enabled).lower())
print("mcp_write_trailing_enabled_confirmed=" + str(enabled_marker).lower())
print("mcp_write_oco_writes_performed_false=" + str(oco_marker).lower())
print("mcp_write_result_json=" + json.dumps(text, ensure_ascii=True))
if not enabled_marker or not oco_marker or failed_marker:
    raise SystemExit(3)
PY
"@

    $output = @($remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s")
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $LASTEXITCODE
    }
}

if ($Days -lt 1 -or $Days -gt 90) { throw "Days must be between 1 and 90." }
if ($Limit -lt 1 -or $Limit -gt 500) { throw "Limit must be between 1 and 500." }
if ($StrategyIds.Count -lt 1 -or $StrategyIds.Count -gt 10) { throw "StrategyIds must include between 1 and 10 strategy ids." }
foreach ($id in $StrategyIds) {
    if ($id -lt 1 -or $id -gt 1000000) { throw "StrategyIds contains unsupported strategy id: $id" }
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) { throw "StrategyId must be between 1 and 1000000." }
if ($StrategyIds -notcontains $StrategyId) { throw "StrategyId must be included in StrategyIds." }
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-McpSmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode
Assert-McpSmokeTokenSafe -Name "ReplayIntervalCode" -Value $ReplayIntervalCode
Assert-NotesSafe -Name "Notes" -Value $Notes
Assert-NotesSafe -Name "RollbackNotes" -Value $RollbackNotes

$usingReviewLog = -not [string]::IsNullOrWhiteSpace($SourceReviewLog)
$usingPostLog = -not [string]::IsNullOrWhiteSpace($SourcePostOptInLog)
if ((-not $Rollback.IsPresent -and -not $usingReviewLog) -or ($Rollback.IsPresent -and -not $usingPostLog) -or $Execute.IsPresent) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    if ($null -eq (Get-Command ssh -ErrorAction SilentlyContinue)) { throw "ssh is not available on PATH. Install OpenSSH client or Git for Windows with ssh." }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
}

$targetEnabled = -not $Rollback.IsPresent
$selectedNotes = if ($Rollback.IsPresent) { $RollbackNotes } else { $Notes }
$expectedConfirmText = if ($Rollback.IsPresent) { "ROLLBACK_TRAILING_STOP_OPT_IN_$StrategyId" } else { "EXECUTE_TRAILING_STOP_OPT_IN_$StrategyId" }
if ($Execute.IsPresent -and $ConfirmText -ne $expectedConfirmText) {
    throw "ConfirmText must equal $expectedConfirmText when -Execute is used."
}

$reviewResult = $null
$postSourceResult = [pscustomobject]@{ Text = ""; ExitCode = 0 }
if ($Rollback.IsPresent) {
    if ($usingPostLog) {
        $postSourceResult = Read-SourceLog -Path $SourcePostOptInLog
    } else {
        $postArgs = @{
            SshHost = $SshHost
            SshKey = $SshKey
            AppDir = $AppDir
            EnvFile = $EnvFile
            Symbol = $Symbol
            IntervalCode = $IntervalCode
            ReplayIntervalCode = $ReplayIntervalCode
            Days = $Days
            Limit = $Limit
            StrategyIds = $StrategyIds
            ExpectedOptInStrategyId = $StrategyId
        }
        $postSourceResult = Invoke-ChildScript -Name "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1" -Arguments $postArgs
    }
    $reviewResult = [pscustomobject]@{ Text = ""; ExitCode = 0 }
} elseif ($usingReviewLog) {
    $reviewResult = Read-SourceLog -Path $SourceReviewLog
} else {
    $reviewArgs = @{
        SshHost = $SshHost
        SshKey = $SshKey
        AppDir = $AppDir
        EnvFile = $EnvFile
        Symbol = $Symbol
        IntervalCode = $IntervalCode
        ReplayIntervalCode = $ReplayIntervalCode
        Days = $Days
        Limit = $Limit
        StrategyIds = $StrategyIds
        PreferredStrategyId = $StrategyId
    }
    $reviewResult = Invoke-ChildScript -Name "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1" -Arguments $reviewArgs
}

$reviewJson = Get-LastPrefixedValue -Text $reviewResult.Text -Prefix "trailing_stop_strategy_opt_in_review_packet="
$reviewPacket = $null
if (-not [string]::IsNullOrWhiteSpace($reviewJson)) {
    $reviewPacket = $reviewJson | ConvertFrom-Json -ErrorAction Stop
}

$postSourceJson = Get-LastPrefixedValue -Text $postSourceResult.Text -Prefix "trailing_stop_post_opt_in_readiness_packet="
$postSourcePacket = $null
if (-not [string]::IsNullOrWhiteSpace($postSourceJson)) {
    $postSourcePacket = $postSourceJson | ConvertFrom-Json -ErrorAction Stop
}

$reviewStatus = Get-PacketValue -Packet $reviewPacket -Name "status"
$reviewDecision = Get-PacketValue -Packet $reviewPacket -Name "decision"
if ([string]::IsNullOrWhiteSpace($reviewDecision)) {
    $reviewDecision = Get-LastPrefixedValue -Text $reviewResult.Text -Prefix "trailing_stop_strategy_opt_in_review_decision="
}
$trailingAcceptance = Get-PacketValue -Packet $reviewPacket -Name "trailingAcceptance"
$trailingImprovementPct = Get-PacketValue -Packet $reviewPacket -Name "trailingImprovementPct"
$trailingDeltaPnl = Get-PacketValue -Packet $reviewPacket -Name "trailingDeltaPnl"
$currentGlobalEnabled = Get-PacketValue -Packet $reviewPacket -Name "currentGlobalEnabled"
$currentGlobalDryRun = Get-PacketValue -Packet $reviewPacket -Name "currentGlobalDryRun"
$recommendedStrategyIdText = Get-PacketValue -Packet $reviewPacket -Name "recommendedStrategyId"
$recommendedStrategyId = 0
[void][int]::TryParse($recommendedStrategyIdText, [ref]$recommendedStrategyId)
$proposedWrite = Get-PacketValue -Packet $reviewPacket -Name "proposedSeparateMcpWrite"
$rollbackWrite = Get-PacketValue -Packet $reviewPacket -Name "rollbackMcpWrite"
$postSourceStatus = Get-PacketValue -Packet $postSourcePacket -Name "status"
$postSourceDecision = Get-PacketValue -Packet $postSourcePacket -Name "decision"
if ([string]::IsNullOrWhiteSpace($postSourceDecision)) {
    $postSourceDecision = Get-LastPrefixedValue -Text $postSourceResult.Text -Prefix "trailing_stop_post_opt_in_readiness_decision="
}
if ($Rollback.IsPresent) {
    $trailingAcceptance = Get-PacketValue -Packet $postSourcePacket -Name "trailingAcceptance"
    $trailingImprovementPct = Get-PacketValue -Packet $postSourcePacket -Name "trailingImprovementPct"
    $trailingDeltaPnl = Get-PacketValue -Packet $postSourcePacket -Name "trailingDeltaPnl"
    $currentGlobalEnabled = Get-PacketValue -Packet $postSourcePacket -Name "currentGlobalEnabled"
    $currentGlobalDryRun = Get-PacketValue -Packet $postSourcePacket -Name "currentGlobalDryRun"
    $proposedWrite = "setTrailingStopOptIn(strategyId=$StrategyId, enabled=false, notes='$RollbackNotes')"
    $rollbackWrite = ""
}

$preMissing = [System.Collections.Generic.List[string]]::new()
if ($Rollback.IsPresent) {
    if ($postSourceResult.ExitCode -ne 0) { Add-MissingRequirement -List $preMissing -Value "post-opt-in readiness packet completed before rollback" }
    if ($null -eq $postSourcePacket) { Add-MissingRequirement -List $preMissing -Value "post-opt-in readiness packet valid JSON before rollback" }
    if ($postSourceStatus -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -and $postSourceStatus -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY") {
        Add-MissingRequirement -List $preMissing -Value "post-opt-in readiness confirms strategy opt-in before rollback"
    }
} else {
    if ($reviewResult.ExitCode -ne 0) { Add-MissingRequirement -List $preMissing -Value "strategy opt-in review packet completed" }
    if ($null -eq $reviewPacket) { Add-MissingRequirement -List $preMissing -Value "trailing_stop_strategy_opt_in_review_packet valid JSON" }
    if ($reviewStatus -ne "READY_FOR_STRATEGY_TRAILING_OPT_IN_OPERATOR_REVIEW_NOT_MUTATION") {
        Add-MissingRequirement -List $preMissing -Value "strategy opt-in review status ready"
    }
    if ($reviewDecision -ne "REQUEST_SEPARATE_SET_TRAILING_STOP_OPT_IN_AUTHORIZATION") {
        Add-MissingRequirement -List $preMissing -Value "strategy opt-in review requests separate authorization"
    }
}
if ($trailingAcceptance -ne "PASS") { Add-MissingRequirement -List $preMissing -Value "trailing replay acceptance=PASS" }
if ($currentGlobalDryRun -ne "true") { Add-MissingRequirement -List $preMissing -Value "global dry-run remains true" }
if ($Rollback.IsPresent) {
    if ($postSourceStatus -eq "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION" -and $currentGlobalEnabled -ne "false") {
        Add-MissingRequirement -List $preMissing -Value "global trailing remains disabled before env diff"
    }
    if ($postSourceStatus -eq "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY" -and $currentGlobalEnabled -ne "true") {
        Add-MissingRequirement -List $preMissing -Value "global trailing enabled is true for active dry-run rollback"
    }
} elseif ($currentGlobalEnabled -ne "false") {
    Add-MissingRequirement -List $preMissing -Value "global trailing remains disabled before env diff"
}
if (-not $Rollback.IsPresent -and $recommendedStrategyId -ne $StrategyId) { Add-MissingRequirement -List $preMissing -Value "recommended strategy matches requested StrategyId" }
$expectedWritePattern = if ($Rollback.IsPresent) { "setTrailingStopOptIn\(strategyId=$StrategyId, enabled=false" } else { "setTrailingStopOptIn\(strategyId=$StrategyId, enabled=true" }
if ($proposedWrite -notmatch $expectedWritePattern) {
    Add-MissingRequirement -List $preMissing -Value "proposed write is setTrailingStopOptIn for requested StrategyId and enabled value"
}

$preReady = $preMissing.Count -eq 0
$writeResult = [pscustomobject]@{ Text = ""; ExitCode = 0 }
$writeStatus = "NOT_EXECUTED_DRY_RUN"
$writeTrailingEnabled = "false"
$writeOcoFalse = "false"

if ($Execute.IsPresent -and $preReady) {
    $writeResult = Invoke-RemoteSetTrailingStopOptIn -TargetStrategyId $StrategyId -TargetEnabled $targetEnabled -TargetNotes $selectedNotes
    $writeStatus = Get-LastPrefixedValue -Text $writeResult.Text -Prefix "mcp_write_status="
    $writeTrailingEnabled = Get-LastPrefixedValue -Text $writeResult.Text -Prefix "mcp_write_trailing_enabled_confirmed="
    $writeOcoFalse = Get-LastPrefixedValue -Text $writeResult.Text -Prefix "mcp_write_oco_writes_performed_false="
}

$postResult = [pscustomobject]@{ Text = ""; ExitCode = 0 }
$postPacket = $null
$postStatus = ""
$postDecision = ""
if ($Execute.IsPresent -and $preReady -and $writeResult.ExitCode -eq 0 -and $writeStatus -eq "OK") {
    if ($usingPostLog) {
        $postResult = Read-SourceLog -Path $SourcePostOptInLog
    } else {
        $postArgs = @{
            SshHost = $SshHost
            SshKey = $SshKey
            AppDir = $AppDir
            EnvFile = $EnvFile
            Symbol = $Symbol
            IntervalCode = $IntervalCode
            ReplayIntervalCode = $ReplayIntervalCode
            Days = $Days
            Limit = $Limit
            StrategyIds = $StrategyIds
            ExpectedOptInStrategyId = $StrategyId
        }
        $postResult = Invoke-ChildScript -Name "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1" -Arguments $postArgs
    }
    $postJson = Get-LastPrefixedValue -Text $postResult.Text -Prefix "trailing_stop_post_opt_in_readiness_packet="
    if (-not [string]::IsNullOrWhiteSpace($postJson)) {
        $postPacket = $postJson | ConvertFrom-Json -ErrorAction Stop
    }
    $postStatus = Get-PacketValue -Packet $postPacket -Name "status"
    $postDecision = Get-PacketValue -Packet $postPacket -Name "decision"
    if ([string]::IsNullOrWhiteSpace($postDecision)) {
        $postDecision = Get-LastPrefixedValue -Text $postResult.Text -Prefix "trailing_stop_post_opt_in_readiness_decision="
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($item in @($preMissing)) { Add-MissingRequirement -List $missingRequirements -Value $item }
if ($Execute.IsPresent) {
    if ($writeResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "setTrailingStopOptIn MCP write completed" }
    if ($writeStatus -ne "OK") { Add-MissingRequirement -List $missingRequirements -Value "setTrailingStopOptIn MCP write returned OK" }
    if ($writeTrailingEnabled -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "setTrailingStopOptIn confirms requested trailingStopEnabled value" }
    if ($writeOcoFalse -ne "true") { Add-MissingRequirement -List $missingRequirements -Value "setTrailingStopOptIn confirms OCO writes performed false" }
    if (-not $Rollback.IsPresent) {
        if ($postResult.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "post-opt-in readiness packet completed" }
        if ($null -eq $postPacket) { Add-MissingRequirement -List $missingRequirements -Value "post-opt-in readiness packet valid JSON" }
        if ($postStatus -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION") {
            Add-MissingRequirement -List $missingRequirements -Value "post-opt-in readiness reaches env-diff operator review"
        }
    }
}

$ready = $missingRequirements.Count -eq 0
$status = if ($Rollback.IsPresent -and -not $Execute.IsPresent -and $preReady) {
    "ROLLBACK_DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION"
} elseif ($Rollback.IsPresent -and $Execute.IsPresent -and $ready) {
    "ROLLBACK_EXECUTED_STRATEGY_OPT_IN_DISABLED"
} elseif (-not $Execute.IsPresent -and $preReady) {
    "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION"
} elseif ($Execute.IsPresent -and $ready) {
    "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW"
} else {
    "NOT_READY"
}
$decision = if ($status -eq "ROLLBACK_DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION") {
    "AWAIT_EXPLICIT_ROLLBACK_CONFIRMATION"
} elseif ($status -eq "ROLLBACK_EXECUTED_STRATEGY_OPT_IN_DISABLED") {
    "ROLLBACK_COMPLETE_REVIEW_TRAILING_BLOCKER_AGAIN"
} elseif ($status -eq "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION") {
    "AWAIT_EXPLICIT_EXECUTE_CONFIRMATION"
} elseif ($status -eq "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW") {
    "REQUEST_SEPARATE_DRY_RUN_ENV_DIFF_AND_DEPLOY_AUTHORIZATION"
} else {
    "FIX_TRAILING_STRATEGY_OPT_IN_EXECUTION_EVIDENCE"
}

$packet = [pscustomobject]@{
    packetType = "TRAILING_STOP_STRATEGY_OPT_IN_EXECUTION_PACKET"
    status = $status
    symbol = $Symbol
    strategyId = $StrategyId
    rollbackRequested = $Rollback.IsPresent
    targetEnabled = $targetEnabled
    executeRequested = $Execute.IsPresent
    requiredConfirmText = $expectedConfirmText
    sourceReviewLog = if ($usingReviewLog) { $SourceReviewLog } else { "prepare_trailing_stop_strategy_opt_in_review_packet_ssh.ps1" }
    sourceReviewStatus = $reviewStatus
    sourceReviewDecision = $reviewDecision
    sourcePostOptInStatus = $postSourceStatus
    sourcePostOptInDecision = $postSourceDecision
    trailingAcceptance = $trailingAcceptance
    trailingImprovementPct = $trailingImprovementPct
    trailingDeltaPnl = $trailingDeltaPnl
    currentGlobalEnabled = $currentGlobalEnabled
    currentGlobalDryRun = $currentGlobalDryRun
    proposedMcpWrite = $proposedWrite
    rollbackMcpWrite = $rollbackWrite
    strategyOptInWritePerformed = ($Execute.IsPresent -and $writeStatus -eq "OK")
    mcpWriteStatus = $writeStatus
    mcpWriteTrailingEnabledConfirmed = $writeTrailingEnabled
    mcpWriteOcoWritesPerformedFalse = $writeOcoFalse
    sourcePostOptInLog = if ($Execute.IsPresent) { if ($usingPostLog) { $SourcePostOptInLog } else { "prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1" } } else { "" }
    postOptInReadinessStatus = $postStatus
    postOptInReadinessDecision = $postDecision
    nextRequiredAuthorization = if ($status -eq "ROLLBACK_DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION") {
        "rerun with -Rollback -Execute -ConfirmText $expectedConfirmText to perform only the reviewed rollback setTrailingStopOptIn write"
    } elseif ($status -eq "ROLLBACK_EXECUTED_STRATEGY_OPT_IN_DISABLED") {
        "rollback complete; rerun strategy opt-in review before any future activation attempt"
    } elseif ($status -eq "DRY_RUN_READY_FOR_SEPARATE_EXECUTION_AUTHORIZATION_NOT_MUTATION") {
        "rerun with -Execute -ConfirmText $expectedConfirmText to perform only the reviewed setTrailingStopOptIn write"
    } elseif ($status -eq "EXECUTED_POST_OPT_IN_READY_FOR_ENV_DIFF_REVIEW") {
        "request separate authorization for TRAILING_STOP_ENABLED=true and TRAILING_STOP_DRY_RUN=true, then deploy/restart and run read-only verification"
    } else {
        "fix missing execution evidence before any further action"
    }
    proposedRuntimeBoundary = [pscustomobject]@{
        strategyOptInWriteAllowedOnlyWhenExecuteAndConfirmTextMatch = $true
        productionEnvChangeAllowedByThisPacket = $false
        deployAllowedByThisPacket = $false
        schedulerEnablementAllowedByThisPacket = $false
        liveTradingAllowed = $false
        orderAllowed = $false
        ocoMutationAllowed = $false
        positionCloseAllowed = $false
        telegramSendAllowed = $false
        policyRelaxationAllowed = $false
        gridMutationAllowed = $false
        fundEarnMutationAllowed = $false
    }
    postExecutionReadOnlyVerification = @(
        ".\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -ExpectedOptInStrategyId $StrategyId -RequireReady",
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $Symbol",
        "server-local MCP getStrategyConfig confirms strategy $StrategyId trailingStopEnabled=true",
        "runtime-log smoke confirms no order/OCO/grid/fund/Earn/Telegram/exchange mutation"
    )
    explicitNonAuthorizations = @(
        "does not change production env",
        "does not deploy or restart",
        "does not enable scheduler",
        "does not enable live trading",
        "does not place orders",
        "does not modify or cancel OCO",
        "does not close positions",
        "does not send Telegram",
        "does not mutate grid/fund/Earn/exchange state",
        "does not relax EntryDedup/DataFreshness/live policy"
    )
    missingRequirements = @($missingRequirements)
}

Write-Host "[trailing-stop-strategy-opt-in-execution] operator wrapper"
Write-Host "script=execute_trailing_stop_strategy_opt_in_ssh.ps1"
Write-Host "scope=CONTROLLED_STRATEGY_CONFIG_WRITE_ONLY; default is dry-run. Only -Execute with ConfirmText=$expectedConfirmText calls setTrailingStopOptIn. -Rollback targets enabled=false. No production env, deploy, scheduler, live trading, order, OCO, grid, fund, Earn, Telegram, external backfill/import, or exchange mutation is performed by this wrapper."
Write-Host "source_review_status=$reviewStatus"
Write-Host "source_review_decision=$reviewDecision"
Write-Host "source_post_opt_in_status=$postSourceStatus"
Write-Host "source_post_opt_in_decision=$postSourceDecision"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host "trailing_stop_improvement_pct=$trailingImprovementPct"
Write-Host "trailing_stop_delta_pnl=$trailingDeltaPnl"
Write-Host "trailing_stop_strategy_opt_in_execution_strategy_id=$StrategyId"
Write-Host "trailing_stop_strategy_opt_in_execution_rollback_requested=$($Rollback.IsPresent.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_strategy_opt_in_execution_target_enabled=$($targetEnabled.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_strategy_opt_in_execution_required_confirm_text=$expectedConfirmText"
Write-Host "trailing_stop_strategy_opt_in_execution_execute_requested=$($Execute.IsPresent.ToString().ToLowerInvariant())"
Write-Host "trailing_stop_strategy_opt_in_execution_write_performed=$((($Execute.IsPresent -and $writeStatus -eq 'OK')).ToString().ToLowerInvariant())"
Write-Host "trailing_stop_strategy_opt_in_execution_mcp_write_status=$writeStatus"
Write-Host "trailing_stop_strategy_opt_in_execution_mcp_write_trailing_enabled_confirmed=$writeTrailingEnabled"
Write-Host "trailing_stop_strategy_opt_in_execution_mcp_write_oco_writes_performed_false=$writeOcoFalse"
if (-not [string]::IsNullOrWhiteSpace($writeResult.Text)) { Write-Host $writeResult.Text }
Write-Host "trailing_stop_post_opt_in_readiness_status=$postStatus"
Write-Host "trailing_stop_post_opt_in_readiness_decision=$postDecision"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("trailing_stop_strategy_opt_in_execution_missing_requirements=" + (ConvertTo-Json -Compress -InputObject @($missingRequirements)))
Write-Host ("trailing_stop_strategy_opt_in_execution_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "trailing_stop_strategy_opt_in_execution_status=$status"
Write-Host "trailing_stop_strategy_opt_in_execution_decision=$decision"
Write-Host "[trailing-stop-strategy-opt-in-execution] check complete"

if ($RequireReady -and -not $ready) {
    throw "Trailing-stop strategy opt-in execution wrapper is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
