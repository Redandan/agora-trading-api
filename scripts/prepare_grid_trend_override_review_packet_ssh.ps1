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

function Convert-ReviewNumber {
    param($Value, [decimal]$Default = 0)
    if ($null -eq $Value) { return $Default }
    $text = ([string]$Value).Trim().TrimEnd("%")
    try {
        return [decimal]::Parse($text, [System.Globalization.CultureInfo]::InvariantCulture)
    } catch {
        return $Default
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
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

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid trend override review packet." }

$snapshotScript = Join-Path $PSScriptRoot "prepare_grid_open_decision_snapshot_ssh.ps1"
if (-not (Test-Path -LiteralPath $snapshotScript)) { throw "Missing grid open decision snapshot script: $snapshotScript" }

$snapshotArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct",
    "-CandidateHalfWidthPct", "$CandidateHalfWidthPct",
    "-SidewaysTrendPctThreshold", "$SidewaysTrendPctThreshold"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $snapshotOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $snapshotScript @snapshotArgs 2>&1
    $snapshotExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$snapshotText = ($snapshotOutput | Out-String -Width 8192)
$snapshotPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $snapshotText -Prefix "grid_open_decision_snapshot_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$hardBlockers = [System.Collections.Generic.List[string]]::new()
$reviewConditions = [System.Collections.Generic.List[string]]::new()
$requiredAuthorization = [System.Collections.Generic.List[string]]::new()
if ($snapshotExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open decision snapshot completed" }
if ($null -eq $snapshotPacket) { Add-Unique -List $missingEvidence -Value "grid_open_decision_snapshot_packet valid JSON" }

$quant = if ($null -ne $snapshotPacket) { $snapshotPacket.quantitativeReadiness } else { $null }
$gates = if ($null -ne $snapshotPacket) { $snapshotPacket.gateStatuses } else { $null }
$trend = if ($null -ne $quant) { [string]$quant.trend } else { "UNKNOWN" }
$trendPct = if ($null -ne $quant) { Convert-ReviewNumber $quant.trendPct } else { [decimal]0 }
$distance = if ($null -ne $quant) { Convert-ReviewNumber $quant.trendDistanceToSidewaysPct } else { [decimal]0 }
$replayScore = if ($null -ne $quant) { Convert-ReviewNumber $quant.replayScore } else { [decimal]0 }
$stopBreakRows = if ($null -ne $quant) { [int](Convert-ReviewNumber $quant.stopBreakRows) } else { 0 }
$capitalCap = if ($null -ne $quant) { Convert-ReviewNumber $quant.effectiveReviewCapitalCapUsdt } else { [decimal]0 }
$missingToolCount = if ($null -ne $quant) { [int](Convert-ReviewNumber $quant.missingRequiredToolCount) } else { 0 }
$trendGate = if ($null -ne $gates) { [string]$gates.trendGate } else { "UNKNOWN" }
$eventRiskGate = if ($null -ne $gates) { [string]$gates.eventRiskGate } else { "UNKNOWN" }
$okxEnvPreflight = if ($null -ne $gates) { [string]$gates.okxGridEnvPreflight } else { "UNKNOWN" }
$mcpCoverage = if ($null -ne $gates) { [string]$gates.mcpToolCoverage } else { "UNKNOWN" }

if ($stopBreakRows -gt 0) { Add-Unique -List $hardBlockers -Value "REPLAY_STOP_BREAK_ROWS_PRESENT" }
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") { Add-Unique -List $hardBlockers -Value "EVENT_RISK_NOT_R0_FOR_TREND_OVERRIDE" }
if ($mcpCoverage -ne "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION" -or $missingToolCount -gt 0) { Add-Unique -List $hardBlockers -Value "GRID_MCP_COVERAGE_NOT_READY_FOR_TREND_OVERRIDE" }
if ($capitalCap -le 0) { Add-Unique -List $hardBlockers -Value "NO_EFFECTIVE_REVIEW_CAPITAL_CAP" }
if ($replayScore -lt 70) { Add-Unique -List $hardBlockers -Value "REPLAY_SCORE_BELOW_MINIMUM_FOR_TREND_OVERRIDE" }
if ($trendGate -notlike "BLOCKED_*") { Add-Unique -List $reviewConditions -Value "trend gate is already clear; override packet is not needed" }
if ($okxEnvPreflight -ne "ENV_PREFLIGHT_READY_NOT_GRID_APPROVAL") {
    Add-Unique -List $reviewConditions -Value "env diff remains separate and must not be bundled into trend override"
}

Add-Unique -List $requiredAuthorization -Value "separate written trend-regime override naming current trend and trendPct"
Add-Unique -List $requiredAuthorization -Value "separate written maximum review capital cap no greater than effectiveReviewCapitalCapUsdt"
Add-Unique -List $requiredAuthorization -Value "separate written production env diff authorization before enabling OKX/grid"
Add-Unique -List $requiredAuthorization -Value "separate written createGrid authorization using a freshly refreshed candidate plan"

$reviewReady = (
    $missingEvidence.Count -eq 0 -and
    $hardBlockers.Count -eq 0 -and
    $trendGate -like "BLOCKED_*" -and
    $eventRiskGate -eq "CLEAR_EVENT_RISK_R0" -and
    $mcpCoverage -eq "READY_GRID_MCP_TOOL_COVERAGE_NOT_MUTATION"
)
$riskGrade = if ([math]::Abs([double]$trendPct) -ge 6.0 -or $replayScore -lt 70 -or $stopBreakRows -gt 0) {
    "HIGH"
} elseif ([math]::Abs([double]$trendPct) -ge 3.0 -or $replayScore -lt 80) {
    "MEDIUM"
} else {
    "LOW"
}
$status = if ($reviewReady) {
    "READY_FOR_GRID_TREND_OVERRIDE_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_GRID_TREND_OVERRIDE_REVIEW_NOT_MUTATION"
}
$decision = if ($reviewReady) {
    "PREPARE_SEPARATE_TREND_OVERRIDE_REVIEW"
} elseif ($trendGate -notlike "BLOCKED_*") {
    "NO_TREND_OVERRIDE_NEEDED_REFRESH_GRID_OPEN_DECISION_SNAPSHOT"
} else {
    "WAIT_FOR_TREND_CLEARANCE_OR_RESOLVE_OVERRIDE_HARD_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_TREND_OVERRIDE_REVIEW_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceDecisionSnapshot = "prepare_grid_open_decision_snapshot_ssh.ps1"
    sourceDecisionSnapshotStatus = if ($null -ne $snapshotPacket) { $snapshotPacket.status } else { "UNKNOWN" }
    riskGrade = $riskGrade
    quantitativeOverrideEvidence = [pscustomobject]@{
        trend = $trend
        trendPct = $trendPct
        sidewaysTrendPctThreshold = $SidewaysTrendPctThreshold
        trendDistanceToSidewaysPct = $distance
        replayScore = $replayScore
        stopBreakRows = $stopBreakRows
        effectiveReviewCapitalCapUsdt = $capitalCap
        mcpMissingRequiredToolCount = $missingToolCount
    }
    gateStatuses = [pscustomobject]@{
        trendGate = $trendGate
        eventRiskGate = $eventRiskGate
        okxGridEnvPreflight = $okxEnvPreflight
        mcpToolCoverage = $mcpCoverage
    }
    hardBlockers = @($hardBlockers)
    reviewConditions = @($reviewConditions)
    missingEvidence = @($missingEvidence)
    requiredOperatorAuthorization = @($requiredAuthorization)
    abortCriteria = @(
        "stopBreakRows becomes greater than 0",
        "event-risk gate is not CLEAR_EVENT_RISK_R0",
        "MCP tool coverage is missing any required grid review/action/boundary tool",
        "trend override risk grade becomes HIGH",
        "effectiveReviewCapitalCapUsdt becomes 0 or missing",
        "fresh decision snapshot is not collected immediately before env/createGrid authorization"
    )
    followUpVerification = @(
        "rerun prepare_grid_open_decision_snapshot_ssh.ps1 before any operator decision",
        "rerun prepare_grid_trend_override_review_packet_ssh.ps1 after trend changes",
        "if operator approves trend override, prepare a separate env diff packet",
        "if env diff is separately applied, deploy/server verification and split acceptance are required",
        "refresh grid open readiness/operator packet immediately before any createGrid request"
    )
    trendOverrideReviewReady = $reviewReady
    trendOverrideAllowed = $false
    gridOpenAllowed = $false
    productionEnvChangeAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceDecisionSnapshotPacket = $snapshotPacket
    notAuthorization = "read-only grid trend override review packet only; does not approve trend override, change production env, createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, deploy, restart, nginx reload, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-trend-override-review] read-only packet"
Write-Host "scope=READ_ONLY; invokes prepare_grid_open_decision_snapshot_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host $snapshotText
Write-Host "source_decision_snapshot=prepare_grid_open_decision_snapshot_ssh.ps1 exitCode=$snapshotExitCode"
Write-Host "grid_trend_override_review_status=$status"
Write-Host "grid_trend_override_review_decision=$decision"
Write-Host "grid_trend_override_review_risk_grade=$riskGrade"
Write-Host "grid_trend_override_review_ready=$($reviewReady.ToString().ToLowerInvariant())"
Write-Host "trend_override_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_trend_override_review_hard_blockers=" + (ConvertTo-Json -Compress @($hardBlockers)))
Write-Host ("grid_trend_override_review_conditions=" + (ConvertTo-Json -Compress @($reviewConditions)))
Write-Host ("grid_trend_override_review_operator_authorization_required=" + (ConvertTo-Json -Compress @($requiredAuthorization)))
Write-Host ("grid_trend_override_review_packet=" + (ConvertTo-Json -Compress -Depth 20 $packet))
Write-Host "notAuthorization=read-only grid trend override review packet only; does not approve trend override, change production env, createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, deploy, restart, nginx reload, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-trend-override-review] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "Grid trend override review is not ready: $status; hardBlockers=$(@($hardBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
