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
    [switch]$AcceptAlreadyAppliedEnvDiff,
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
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -ne 0 -and ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30)) { throw "CandidateHalfWidthPct must be 0 or between 2.5 and 30." }

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid create authorization preflight packet." }

$operatorScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$envDiffScript = Join-Path $PSScriptRoot "prepare_grid_env_diff_preflight_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $operatorScript)) { throw "Missing grid open operator packet script: $operatorScript" }
if (-not (Test-Path -LiteralPath $envDiffScript)) { throw "Missing grid env diff preflight packet script: $envDiffScript" }

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
    "-StopOutPct", "$StopOutPct",
    "-CandidateHalfWidthPct", "$CandidateHalfWidthPct"
)
$envDiffArgs = @($commonArgs)
if ($AcceptAlreadyAppliedEnvDiff) {
    $envDiffArgs += "-AcceptAlreadyAppliedEnvDiff"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $operatorOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $operatorScript @commonArgs 2>&1
    $operatorExitCode = $LASTEXITCODE
    $envDiffOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $envDiffScript @envDiffArgs 2>&1
    $envDiffExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$operatorText = ($operatorOutput | Out-String -Width 8192)
$envDiffText = ($envDiffOutput | Out-String -Width 8192)
$operatorPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $operatorText -Prefix "grid_open_operator_packet=")
$envDiffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $envDiffText -Prefix "grid_env_diff_preflight_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$reviewBlockers = [System.Collections.Generic.List[string]]::new()
$operatorAuthorizationRequired = [System.Collections.Generic.List[string]]::new()
if ($operatorExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open operator packet completed" }
if ($envDiffExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid env diff preflight packet completed" }
if ($null -eq $operatorPacket) { Add-Unique -List $missingEvidence -Value "grid_open_operator_packet valid JSON" }
if ($null -eq $envDiffPacket) { Add-Unique -List $missingEvidence -Value "grid_env_diff_preflight_packet valid JSON" }

$candidatePlanComplete = if ($null -ne $operatorPacket) { [bool]$operatorPacket.candidatePlanComplete } else { $false }
$candidatePlanStatus = if ($null -ne $operatorPacket) { [string]$operatorPacket.candidatePlanStatus } else { "UNKNOWN" }
$createGridReviewInputs = if ($null -ne $operatorPacket) { $operatorPacket.createGridReviewInputs } else { $null }
$combinedOverride = if ($null -ne $operatorPacket) { $operatorPacket.combinedOverrideRiskEnvelope } else { $null }
$gateStatuses = if ($null -ne $operatorPacket) { $operatorPacket.gateStatuses } else { $null }
$eventRiskGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.eventRiskGate } else { "UNKNOWN" }
$trendGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.trendGate } else { "UNKNOWN" }
$okxGate = if ($null -ne $gateStatuses) { [string]$gateStatuses.okxGate } else { "UNKNOWN" }
$envDiffReviewReady = if ($null -ne $envDiffPacket) { [bool]$envDiffPacket.envDiffReviewReady } else { $false }
$candidateCapitalUsdt = if ($null -ne $createGridReviewInputs) { Get-DecimalOrNull $createGridReviewInputs.candidateCapitalUsdt } else { $null }
$effectiveReviewCapitalCapUsdt = if ($null -ne $combinedOverride) { Get-DecimalOrNull $combinedOverride.effectiveReviewCapitalCapUsdt } else { $null }
$replayScore = if ($null -ne $createGridReviewInputs) { Get-DecimalOrNull $createGridReviewInputs.replayScore } else { $null }

if (-not $candidatePlanComplete) {
    Add-Unique -List $reviewBlockers -Value "CANDIDATE_PLAN_INCOMPLETE_FOR_CREATEGRID_REVIEW"
}
if ($null -eq $createGridReviewInputs) {
    Add-Unique -List $missingEvidence -Value "createGridReviewInputs from grid open operator packet"
}
if ($eventRiskGate -ne "CLEAR_EVENT_RISK_R0") {
    Add-Unique -List $reviewBlockers -Value "EVENT_RISK_NOT_R0_FOR_CREATEGRID_REVIEW"
}
if ($okxGate -eq "UNKNOWN") {
    Add-Unique -List $missingEvidence -Value "okx gate status from grid open operator packet"
}
if (-not $envDiffReviewReady) {
    Add-Unique -List $reviewBlockers -Value "GRID_ENV_DIFF_REVIEW_NOT_READY_FOR_CREATEGRID"
}
if ($null -eq $candidateCapitalUsdt) {
    Add-Unique -List $missingEvidence -Value "candidateCapitalUsdt from createGridReviewInputs"
}
if ($null -eq $effectiveReviewCapitalCapUsdt) {
    Add-Unique -List $missingEvidence -Value "effectiveReviewCapitalCapUsdt from combinedOverrideRiskEnvelope"
}
if ($null -ne $candidateCapitalUsdt -and $null -ne $effectiveReviewCapitalCapUsdt -and $candidateCapitalUsdt -gt $effectiveReviewCapitalCapUsdt) {
    Add-Unique -List $reviewBlockers -Value "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP"
}
if ($null -eq $replayScore) {
    Add-Unique -List $missingEvidence -Value "replayScore from createGridReviewInputs"
} elseif ($replayScore -lt 70) {
    Add-Unique -List $reviewBlockers -Value "REPLAY_SCORE_BELOW_CREATEGRID_REVIEW_FLOOR"
}

Add-Unique -List $operatorAuthorizationRequired -Value "separate written production env diff authorization and deploy/restart before createGrid"
Add-Unique -List $operatorAuthorizationRequired -Value "fresh post-env read-only grid open operator packet"
Add-Unique -List $operatorAuthorizationRequired -Value "separate written createGrid authorization with reviewed inputs"
Add-Unique -List $operatorAuthorizationRequired -Value "keep scheduler/recovery/Earn disabled unless separately authorized"

$reviewReady = (
    $missingEvidence.Count -eq 0 -and
    $reviewBlockers.Count -eq 0 -and
    $candidatePlanComplete -and
    $envDiffReviewReady
)
$status = if ($reviewReady) {
    "READY_FOR_GRID_CREATE_AUTHORIZATION_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_GRID_CREATE_AUTHORIZATION_PREFLIGHT_NOT_MUTATION"
}
$decision = if ($reviewReady) {
    "PREPARE_SEPARATE_CREATEGRID_AUTHORIZATION_AFTER_ENV_DIFF"
} elseif ($reviewBlockers -contains "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP") {
    "ADJUST_CREATEGRID_INPUTS_OR_SEPARATE_CAP_OVERRIDE"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_CREATE_AUTHORIZATION_PREFLIGHT_EVIDENCE"
} else {
    "RESOLVE_GRID_CREATE_AUTHORIZATION_PREFLIGHT_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceOperatorPacket = "prepare_grid_open_operator_packet_ssh.ps1"
    sourceEnvDiffPacket = "prepare_grid_env_diff_preflight_packet_ssh.ps1"
    sourceOperatorStatus = if ($null -ne $operatorPacket) { $operatorPacket.status } else { "UNKNOWN" }
    sourceEnvDiffStatus = if ($null -ne $envDiffPacket) { $envDiffPacket.status } else { "UNKNOWN" }
    candidatePlanComplete = $candidatePlanComplete
    candidatePlanStatus = $candidatePlanStatus
    reviewedCreateGridInputs = $createGridReviewInputs
    capitalCapCheck = [pscustomobject]@{
        candidateCapitalUsdt = $candidateCapitalUsdt
        effectiveReviewCapitalCapUsdt = $effectiveReviewCapitalCapUsdt
        status = if ($null -eq $candidateCapitalUsdt -or $null -eq $effectiveReviewCapitalCapUsdt) {
            "MISSING_CAPITAL_CAP_EVIDENCE"
        } elseif ($candidateCapitalUsdt -gt $effectiveReviewCapitalCapUsdt) {
            "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP"
        } else {
            "CAPITAL_WITHIN_EFFECTIVE_REVIEW_CAP"
        }
    }
    replayCheck = [pscustomobject]@{
        replayScore = $replayScore
        status = if ($null -eq $replayScore) {
            "MISSING_REPLAY_SCORE"
        } elseif ($replayScore -lt 70) {
            "REPLAY_SCORE_BELOW_CREATEGRID_REVIEW_FLOOR"
        } else {
            "REPLAY_SCORE_ACCEPTABLE_FOR_CREATEGRID_REVIEW"
        }
    }
    gateStatuses = [pscustomobject]@{
        trendGate = $trendGate
        eventRiskGate = $eventRiskGate
        okxGate = $okxGate
        envDiffReviewReady = $envDiffReviewReady
    }
    requiredBeforeCreateGrid = @(
        "fresh GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET",
        "production env diff applied only after separate authorization",
        "deploy/restart completed only after separate authorization",
        "post-env read-only split acceptance and grid MCP verification",
        "separate written createGrid authorization naming reviewedCreateGridInputs",
        "scheduler/recovery/Earn remain disabled unless separately authorized"
    )
    postCreateReadOnlyVerification = @(
        "listGrids",
        "getGridStats",
        "getGridPriceAlignment",
        "getCurrentExposure",
        "verify split acceptance",
        "check runtime log for unexpected order/OCO/grid/Earn/fund mutation lines",
        "confirm scheduler/recovery/Earn remain disabled unless separately authorized"
    )
    reviewBlockers = @($reviewBlockers)
    missingEvidence = @($missingEvidence)
    operatorAuthorizationRequired = @($operatorAuthorizationRequired)
    createAuthorizationReviewReady = $reviewReady
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceEnvDiffPacketSummary = $envDiffPacket
    notAuthorization = "read-only grid create authorization preflight only; does not change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-create-authorization-preflight] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid open operator and grid env diff preflight packets only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_operator_packet=prepare_grid_open_operator_packet_ssh.ps1 exitCode=$operatorExitCode"
Write-Host "source_env_diff_preflight_packet=prepare_grid_env_diff_preflight_packet_ssh.ps1 exitCode=$envDiffExitCode"
Write-Host "grid_create_authorization_preflight_status=$status"
Write-Host "grid_create_authorization_preflight_decision=$decision"
Write-Host "grid_create_authorization_review_ready=$($reviewReady.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_create_authorization_preflight_blockers=" + (ConvertTo-Json -Compress @($reviewBlockers)))
Write-Host ("grid_create_authorization_preflight_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_create_authorization_preflight_required_authorization=" + (ConvertTo-Json -Compress @($operatorAuthorizationRequired)))
Write-Host ("grid_create_authorization_preflight_capital_cap_check=" + (ConvertTo-Json -Compress $packet.capitalCapCheck))
Write-Host ("grid_create_authorization_preflight_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid create authorization preflight only; does not change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-create-authorization-preflight] read-only check complete"

if ($RequireReviewReady -and -not $reviewReady) {
    throw "Grid create authorization preflight is not ready: $status; blockers=$(@($reviewBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
