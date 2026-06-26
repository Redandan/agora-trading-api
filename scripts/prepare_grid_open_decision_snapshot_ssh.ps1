param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [decimal]$CandidateHalfWidthPct = 0,
    [decimal]$SidewaysTrendPctThreshold = 1.0,
    [switch]$RequireAuthorizationReady
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

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-ReadOnlyPacketScript {
    param([string]$ScriptPath, [string[]]$Arguments)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $script:PowerShell.Source -NoProfile -ExecutionPolicy Bypass -File $ScriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    [pscustomobject]@{
        ExitCode = $exitCode
        Text = ($output | Out-String -Width 8192)
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30." }
if ($SidewaysTrendPctThreshold -lt 0.1 -or $SidewaysTrendPctThreshold -gt 3.0) { throw "SidewaysTrendPctThreshold must be between 0.1 and 3.0." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$script:PowerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $script:PowerShell) { $script:PowerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $script:PowerShell) { throw "Unable to find powershell or pwsh for grid open decision snapshot." }

$watchScript = Join-Path $PSScriptRoot "prepare_grid_trend_clearance_watch_packet_ssh.ps1"
$coverageScript = Join-Path $PSScriptRoot "prepare_grid_mcp_tool_coverage_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $watchScript)) { throw "Missing grid trend clearance watch packet script: $watchScript" }
if (-not (Test-Path -LiteralPath $coverageScript)) { throw "Missing grid MCP tool coverage packet script: $coverageScript" }

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile
)
$watchArgs = @(
    $commonArgs +
    @(
        "-Symbol", $Symbol,
        "-LookbackHours", "$LookbackHours",
        "-CandidateLookbackHours", "$CandidateLookbackHours",
        "-GridCount", "$GridCount",
        "-PerLevelUsdt", "$PerLevelUsdt",
        "-StopOutPct", "$StopOutPct",
        "-CandidateHalfWidthPct", "$CandidateHalfWidthPct",
        "-SidewaysTrendPctThreshold", "$SidewaysTrendPctThreshold"
    )
)

$watchResult = Invoke-ReadOnlyPacketScript -ScriptPath $watchScript -Arguments $watchArgs
$coverageResult = Invoke-ReadOnlyPacketScript -ScriptPath $coverageScript -Arguments $commonArgs

$watchPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $watchResult.Text -Prefix "grid_trend_clearance_watch_packet=")
$coveragePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $coverageResult.Text -Prefix "grid_mcp_tool_coverage_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
if ($watchResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid trend clearance watch completed" }
if ($coverageResult.ExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid MCP tool coverage completed" }
if ($null -eq $watchPacket) { Add-Unique -List $missingEvidence -Value "grid_trend_clearance_watch_packet valid JSON" }
if ($null -eq $coveragePacket) { Add-Unique -List $missingEvidence -Value "grid_mcp_tool_coverage_packet valid JSON" }

$remainingBlockers = [System.Collections.Generic.List[string]]::new()
$requiredOperatorAuthorization = [System.Collections.Generic.List[string]]::new()
$trendStatus = if ($null -ne $watchPacket) { [string]$watchPacket.trendGateStatus } else { "UNKNOWN" }
$eventRiskStatus = if ($null -ne $watchPacket) { [string]$watchPacket.eventRiskGateStatus } else { "UNKNOWN" }
$okxGateStatus = if ($null -ne $watchPacket) { [string]$watchPacket.okxGateStatus } else { "UNKNOWN" }
$okxEnvPreflightStatus = if ($null -ne $watchPacket) { [string]$watchPacket.okxGridEnvPreflightStatus } else { "UNKNOWN" }
$toolCoverageStatus = if ($null -ne $coveragePacket) { [string]$coveragePacket.status } else { "UNKNOWN" }
$candidatePlanComplete = $false
if ($null -ne $watchPacket -and $null -ne $watchPacket.PSObject.Properties["sourceOperatorStatus"]) {
    $candidatePlanComplete = $watchPacket.sourceOperatorStatus -notlike "NOT_REVIEWABLE_*"
}

if ($trendStatus -like "BLOCKED_*") {
    Add-Unique -List $remainingBlockers -Value "GRID_UNFAVORABLE_TREND_REGIME"
    Add-Unique -List $requiredOperatorAuthorization -Value "separate written trend-regime override or fresh SIDEWAYS trend clearance"
}
if ($eventRiskStatus -like "BLOCKED_*") {
    Add-Unique -List $remainingBlockers -Value "GRID_EVENT_RISK_NOT_R0"
    Add-Unique -List $requiredOperatorAuthorization -Value "separate written event-risk override or fresh R0 event-risk evidence"
}
if ($okxGateStatus -like "BLOCKED_*" -or $okxEnvPreflightStatus -ne "ENV_PREFLIGHT_READY_NOT_GRID_APPROVAL") {
    Add-Unique -List $remainingBlockers -Value "GRID_OKX_ENV_NOT_AUTHORIZED"
    Add-Unique -List $requiredOperatorAuthorization -Value "separate production env diff authorization for TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true"
}
if ($toolCoverageStatus -ne "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION") {
    Add-Unique -List $remainingBlockers -Value "GRID_MCP_TOOL_COVERAGE_NOT_READY"
}
if ($null -ne $watchPacket) {
    foreach ($blocker in @($watchPacket.blockers)) {
        Add-Unique -List $remainingBlockers -Value ([string]$blocker)
    }
}
if ($null -ne $coveragePacket) {
    foreach ($missingTool in @($coveragePacket.missingRequiredTools)) {
        Add-Unique -List $remainingBlockers -Value ("MISSING_MCP_TOOL:" + [string]$missingTool)
    }
}

$authorizationReady = (
    $missingEvidence.Count -eq 0 -and
    $remainingBlockers.Count -eq 0 -and
    $toolCoverageStatus -eq "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION" -and
    $okxEnvPreflightStatus -eq "ENV_PREFLIGHT_READY_NOT_GRID_APPROVAL"
)
$status = if ($authorizationReady) {
    "READY_FOR_SEPARATE_GRID_OPEN_AUTHORIZATION_NOT_MUTATION"
} else {
    "BLOCKED_GRID_OPEN_DECISION_SNAPSHOT_NOT_MUTATION"
}
$decision = if ($authorizationReady) {
    "PREPARE_SEPARATE_ENV_AND_CREATEGRID_AUTHORIZATION"
} elseif ($trendStatus -like "BLOCKED_*") {
    "WAIT_FOR_TREND_CLEARANCE_OR_SEPARATE_TREND_OVERRIDE"
} elseif ($toolCoverageStatus -ne "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION") {
    "FIX_GRID_MCP_TOOL_COVERAGE_BEFORE_OPERATOR_REVIEW"
} else {
    "RESOLVE_REMAINING_GRID_OPEN_BLOCKERS_BEFORE_AUTHORIZATION"
}

$packet = [pscustomobject]@{
    packetType = "GRID_OPEN_DECISION_SNAPSHOT"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourcePackets = @(
        "prepare_grid_trend_clearance_watch_packet_ssh.ps1",
        "prepare_grid_mcp_tool_coverage_packet_ssh.ps1"
    )
    quantitativeReadiness = [pscustomobject]@{
        trend = if ($null -ne $watchPacket) { $watchPacket.trend } else { $null }
        trendPct = if ($null -ne $watchPacket) { $watchPacket.trendPct } else { $null }
        atrPct = if ($null -ne $watchPacket) { $watchPacket.atrPct } else { $null }
        trendDistanceToSidewaysPct = if ($null -ne $watchPacket) { $watchPacket.trendDistanceToSidewaysPct } else { $null }
        replayScore = if ($null -ne $watchPacket) { $watchPacket.replayScore } else { $null }
        stopBreakRows = if ($null -ne $watchPacket) { $watchPacket.stopBreakRows } else { $null }
        effectiveReviewCapitalCapUsdt = if ($null -ne $watchPacket) { $watchPacket.effectiveReviewCapitalCapUsdt } else { $null }
        mcpToolCount = if ($null -ne $coveragePacket) { $coveragePacket.toolCount } else { $null }
        missingRequiredToolCount = if ($null -ne $coveragePacket) { @($coveragePacket.missingRequiredTools).Count } else { $null }
    }
    gateStatuses = [pscustomobject]@{
        trendGate = $trendStatus
        eventRiskGate = $eventRiskStatus
        okxGate = $okxGateStatus
        okxGridEnvPreflight = $okxEnvPreflightStatus
        mcpToolCoverage = $toolCoverageStatus
    }
    candidatePlanComplete = $candidatePlanComplete
    remainingBlockers = @($remainingBlockers)
    missingEvidence = @($missingEvidence)
    requiredOperatorAuthorization = @($requiredOperatorAuthorization)
    requiredBeforeAnyGridOpen = @(
        "fresh GRID_OPEN_DECISION_SNAPSHOT with no remaining blockers",
        "separate written production env diff authorization",
        "separate written createGrid authorization using reviewed candidate inputs",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false unless separately authorized",
        "GRID_RECOVERY_ENABLED=false unless separately authorized",
        "OKX_EARN_TOPUP_ENABLED=false",
        "post-env deploy/server verification if env diff is applied",
        "refresh grid open readiness/operator packets immediately before createGrid"
    )
    gridOpenReadyForAuthorization = $authorizationReady
    gridOpenAllowed = $false
    productionEnvChangeAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceTrendWatchPacket = $watchPacket
    sourceMcpCoveragePacket = $coveragePacket
    notAuthorization = "read-only grid open decision snapshot only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
}

Write-Host "[grid-open-decision-snapshot] read-only packet"
Write-Host "scope=READ_ONLY; invokes existing grid trend-clearance watch and MCP tool coverage packets only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_trend_watch_packet=prepare_grid_trend_clearance_watch_packet_ssh.ps1 exitCode=$($watchResult.ExitCode)"
Write-Host "source_mcp_tool_coverage_packet=prepare_grid_mcp_tool_coverage_packet_ssh.ps1 exitCode=$($coverageResult.ExitCode)"
Write-Host "grid_open_decision_snapshot_status=$status"
Write-Host "grid_open_decision_snapshot_decision=$decision"
Write-Host "grid_open_ready_for_authorization=$($authorizationReady.ToString().ToLowerInvariant())"
Write-Host "grid_open_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_open_decision_snapshot_remaining_blockers=" + (ConvertTo-Json -Compress @($remainingBlockers)))
Write-Host ("grid_open_decision_snapshot_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_open_decision_snapshot_operator_authorization_required=" + (ConvertTo-Json -Compress @($requiredOperatorAuthorization)))
Write-Host ("grid_open_decision_snapshot_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid open decision snapshot only; does not authorize production env changes, createGrid, scheduler enablement, orders, OCO modification, Telegram send, DB/grid/fund/Earn/exchange mutation, deploy, restart, nginx reload, or live trading"
Write-Host "[grid-open-decision-snapshot] read-only check complete"

if ($RequireAuthorizationReady -and -not $authorizationReady) {
    throw "Grid open decision snapshot is not authorization-ready: $status; blockers=$(@($remainingBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
