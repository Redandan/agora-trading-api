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

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-DecimalOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    try {
        return [decimal]$Value
    } catch {
        return $null
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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid capital override review packet." }

$createAuthScript = Join-Path $PSScriptRoot "prepare_grid_create_authorization_preflight_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $createAuthScript)) { throw "Missing grid create authorization preflight packet script: $createAuthScript" }

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct"
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $createAuthOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $createAuthScript @commonArgs 2>&1
    $createAuthExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$createAuthText = ($createAuthOutput | Out-String -Width 8192)
$createAuthPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $createAuthText -Prefix "grid_create_authorization_preflight_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$hardBlockers = [System.Collections.Generic.List[string]]::new()
$reviewWarnings = [System.Collections.Generic.List[string]]::new()
$operatorAuthorizationRequired = [System.Collections.Generic.List[string]]::new()
if ($createAuthExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid create authorization preflight packet completed" }
if ($null -eq $createAuthPacket) { Add-Unique -List $missingEvidence -Value "grid_create_authorization_preflight_packet valid JSON" }

$capCheck = if ($null -ne $createAuthPacket) { $createAuthPacket.capitalCapCheck } else { $null }
$reviewedInputs = if ($null -ne $createAuthPacket) { $createAuthPacket.reviewedCreateGridInputs } else { $null }
$gateStatuses = if ($null -ne $createAuthPacket) { $createAuthPacket.gateStatuses } else { $null }
$sourceEnvDiff = if ($null -ne $createAuthPacket) { $createAuthPacket.sourceEnvDiffPacketSummary } else { $null }
$sourceTrendOverride = if ($null -ne $sourceEnvDiff) { $sourceEnvDiff.sourceTrendOverridePacketSummary } else { $null }
$quant = if ($null -ne $sourceTrendOverride) { $sourceTrendOverride.quantitativeOverrideEvidence } else { $null }

$candidateCapitalUsdt = if ($null -ne $capCheck) { Get-DecimalOrNull $capCheck.candidateCapitalUsdt } else { $null }
$effectiveReviewCapitalCapUsdt = if ($null -ne $capCheck) { Get-DecimalOrNull $capCheck.effectiveReviewCapitalCapUsdt } else { $null }
$replayScore = if ($null -ne $reviewedInputs) { Get-DecimalOrNull $reviewedInputs.replayScore } else { $null }
$stopBreakRows = if ($null -ne $quant) { [int](Get-DecimalOrNull $quant.stopBreakRows) } else { $null }
$trendRiskGrade = if ($null -ne $sourceTrendOverride) { [string]$sourceTrendOverride.riskGrade } else { "UNKNOWN" }
$eventRiskGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.eventRiskGate } else { "UNKNOWN" }
$trendGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.trendGate } else { "UNKNOWN" }
$envDiffReviewReady = if ($null -ne $gateStatuses) { [bool]$gateStatuses.envDiffReviewReady } else { $false }
$capStatus = if ($null -ne $capCheck) { [string]$capCheck.status } else { "UNKNOWN" }

if ($null -eq $reviewedInputs) { Add-Unique -List $missingEvidence -Value "reviewedCreateGridInputs from create authorization preflight packet" }
if ($null -eq $candidateCapitalUsdt) { Add-Unique -List $missingEvidence -Value "candidateCapitalUsdt from capitalCapCheck" }
if ($null -eq $effectiveReviewCapitalCapUsdt) { Add-Unique -List $missingEvidence -Value "effectiveReviewCapitalCapUsdt from capitalCapCheck" }
if ($null -eq $replayScore) { Add-Unique -List $missingEvidence -Value "replayScore from reviewedCreateGridInputs" }
if ($null -eq $stopBreakRows) { Add-Unique -List $missingEvidence -Value "stopBreakRows from trend override quantitative evidence" }

if ($trendRiskGrade -eq "HIGH") { Add-Unique -List $hardBlockers -Value "HIGH_TREND_OVERRIDE_RISK_NOT_CAP_OVERRIDE_REVIEWABLE" }
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") { Add-Unique -List $hardBlockers -Value "EVENT_RISK_NOT_R0_FOR_CAPITAL_OVERRIDE_REVIEW" }
if (-not $envDiffReviewReady) { Add-Unique -List $hardBlockers -Value "GRID_ENV_DIFF_REVIEW_NOT_READY_FOR_CAPITAL_OVERRIDE" }
if ($null -ne $stopBreakRows -and $stopBreakRows -gt 0) { Add-Unique -List $hardBlockers -Value "STOP_BREAK_ROWS_NOT_ZERO_FOR_CAPITAL_OVERRIDE" }
if ($null -ne $replayScore -and $replayScore -lt 70) { Add-Unique -List $hardBlockers -Value "REPLAY_SCORE_BELOW_CAPITAL_OVERRIDE_FLOOR" }
if ($capStatus -ne "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP") {
    Add-Unique -List $reviewWarnings -Value "CAPITAL_OVERRIDE_NOT_REQUIRED_BY_CREATE_PREFLIGHT"
}

$requiredCapRaiseUsdt = if ($null -ne $candidateCapitalUsdt -and $null -ne $effectiveReviewCapitalCapUsdt) {
    [math]::Round([double]($candidateCapitalUsdt - $effectiveReviewCapitalCapUsdt), 2)
} else {
    $null
}
$requiredCapMultiplier = if ($null -ne $candidateCapitalUsdt -and $null -ne $effectiveReviewCapitalCapUsdt -and $effectiveReviewCapitalCapUsdt -gt 0) {
    [math]::Round([double]($candidateCapitalUsdt / $effectiveReviewCapitalCapUsdt), 4)
} else {
    $null
}

Add-Unique -List $operatorAuthorizationRequired -Value "separate written trend-regime override remains required while trendGate is blocked"
Add-Unique -List $operatorAuthorizationRequired -Value "separate written capital-cap override naming candidateCapitalUsdt and effectiveReviewCapitalCapUsdt"
Add-Unique -List $operatorAuthorizationRequired -Value "separate written production env diff authorization and deploy/restart before createGrid"
Add-Unique -List $operatorAuthorizationRequired -Value "fresh post-env read-only create authorization preflight packet"
Add-Unique -List $operatorAuthorizationRequired -Value "separate written createGrid authorization after post-env verification"

$reviewReady = (
    $missingEvidence.Count -eq 0 -and
    $hardBlockers.Count -eq 0 -and
    $capStatus -eq "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP" -and
    $trendRiskGrade -ne "HIGH" -and
    $eventRiskGate -eq "CLEAR_EVENT_RISK_R0"
)
$status = if ($reviewReady) {
    "READY_FOR_GRID_CAPITAL_OVERRIDE_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_GRID_CAPITAL_OVERRIDE_REVIEW_NOT_MUTATION"
}
$decision = if ($reviewReady) {
    "PREPARE_SEPARATE_CAPITAL_CAP_OVERRIDE_AUTHORIZATION"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_CAPITAL_OVERRIDE_EVIDENCE"
} else {
    "RESOLVE_GRID_CAPITAL_OVERRIDE_REVIEW_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_CAPITAL_OVERRIDE_REVIEW_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceCreateAuthorizationPreflightPacket = "prepare_grid_create_authorization_preflight_packet_ssh.ps1"
    sourceCreateAuthorizationPreflightStatus = if ($null -ne $createAuthPacket) { $createAuthPacket.status } else { "UNKNOWN" }
    gateStatuses = [pscustomobject]@{
        trendGate = $trendGate
        eventRiskGate = $eventRiskGate
        envDiffReviewReady = $envDiffReviewReady
        trendRiskGrade = $trendRiskGrade
    }
    reviewedCreateGridInputs = $reviewedInputs
    capitalOverrideRequest = [pscustomobject]@{
        candidateCapitalUsdt = $candidateCapitalUsdt
        effectiveReviewCapitalCapUsdt = $effectiveReviewCapitalCapUsdt
        requiredCapRaiseUsdt = $requiredCapRaiseUsdt
        requiredCapMultiplier = $requiredCapMultiplier
        requestedMaximumReviewCapitalCapUsdt = $candidateCapitalUsdt
        sourceStatus = $capStatus
    }
    quantitativeEvidence = [pscustomobject]@{
        replayScore = $replayScore
        stopBreakRows = $stopBreakRows
        trendRiskGrade = $trendRiskGrade
        eventRiskGate = $eventRiskGate
    }
    approvalConditions = @(
        "operator explicitly accepts capital-cap override from effectiveReviewCapitalCapUsdt to candidateCapitalUsdt",
        "trend risk grade remains not HIGH",
        "event-risk gate remains CLEAR_EVENT_RISK_R0",
        "stopBreakRows remains 0",
        "replayScore remains >= 70",
        "env diff, deploy/restart, and createGrid authorization remain separate steps"
    )
    abortCriteria = @(
        "trend risk grade becomes HIGH",
        "event-risk gate is not CLEAR_EVENT_RISK_R0",
        "stopBreakRows becomes greater than 0",
        "replayScore drops below 70",
        "candidate createGrid inputs change without refreshing this packet",
        "scheduler/recovery/Earn are enabled without separate authorization"
    )
    postApprovalReadOnlyVerification = @(
        "rerun prepare_grid_create_authorization_preflight_packet_ssh.ps1 immediately before env diff",
        "rerun this capital override review packet after any candidate/risk change",
        "after separately authorized env diff deploy/restart, verify split acceptance",
        "refresh grid open operator packet and create authorization preflight packet before createGrid"
    )
    hardBlockers = @($hardBlockers)
    reviewWarnings = @($reviewWarnings)
    missingEvidence = @($missingEvidence)
    operatorAuthorizationRequired = @($operatorAuthorizationRequired)
    capitalOverrideReviewReady = $reviewReady
    capitalOverrideAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceCreateAuthorizationPreflightPacketSummary = $createAuthPacket
    notAuthorization = "read-only grid capital override review packet only; does not approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-capital-override-review] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid create authorization preflight packet only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_create_authorization_preflight_packet=prepare_grid_create_authorization_preflight_packet_ssh.ps1 exitCode=$createAuthExitCode"
Write-Host "grid_capital_override_review_status=$status"
Write-Host "grid_capital_override_review_decision=$decision"
Write-Host "grid_capital_override_review_ready=$($reviewReady.ToString().ToLowerInvariant())"
Write-Host "capital_override_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_capital_override_review_hard_blockers=" + (ConvertTo-Json -Compress @($hardBlockers)))
Write-Host ("grid_capital_override_review_warnings=" + (ConvertTo-Json -Compress @($reviewWarnings)))
Write-Host ("grid_capital_override_review_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_capital_override_review_required_authorization=" + (ConvertTo-Json -Compress @($operatorAuthorizationRequired)))
Write-Host ("grid_capital_override_review_request=" + (ConvertTo-Json -Compress $packet.capitalOverrideRequest))
Write-Host ("grid_capital_override_review_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid capital override review packet only; does not approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-capital-override-review] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "Grid capital override review is not ready: $status; hardBlockers=$(@($hardBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
