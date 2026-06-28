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
    [switch]$RequireBundleReady
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

function Get-PropertyOrNull {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
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
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open authorization bundle." }

$capitalOverrideScript = Join-Path $PSScriptRoot "prepare_grid_capital_override_review_packet_ssh.ps1"
if (-not (Test-Path -LiteralPath $capitalOverrideScript)) { throw "Missing grid capital override review packet script: $capitalOverrideScript" }

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
if ($AcceptAlreadyAppliedEnvDiff) {
    $commonArgs += "-AcceptAlreadyAppliedEnvDiff"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $capitalOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $capitalOverrideScript @commonArgs 2>&1
    $capitalExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$capitalText = ($capitalOutput | Out-String -Width 8192)
$capitalPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $capitalText -Prefix "grid_capital_override_review_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$bundleBlockers = [System.Collections.Generic.List[string]]::new()
if ($capitalExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid capital override review packet completed" }
if ($null -eq $capitalPacket) { Add-Unique -List $missingEvidence -Value "grid_capital_override_review_packet valid JSON" }

$createPacket = if ($null -ne $capitalPacket) { $capitalPacket.sourceCreateAuthorizationPreflightPacketSummary } else { $null }
$envPacket = if ($null -ne $createPacket) { $createPacket.sourceEnvDiffPacketSummary } else { $null }
$trendPacket = if ($null -ne $envPacket) { $envPacket.sourceTrendOverridePacketSummary } else { $null }

$trendReviewReady = if ($null -ne $trendPacket) { [bool]$trendPacket.trendOverrideReviewReady } else { $false }
$trendGateStatuses = Get-PropertyOrNull -Object $trendPacket -Name "gateStatuses"
$trendGate = Get-PropertyOrNull -Object $trendGateStatuses -Name "trendGate"
if ([string]::IsNullOrWhiteSpace($trendGate)) {
    $createGateStatuses = Get-PropertyOrNull -Object $createPacket -Name "gateStatuses"
    $trendGate = Get-PropertyOrNull -Object $createGateStatuses -Name "trendGate"
}
if ([string]::IsNullOrWhiteSpace($trendGate)) { $trendGate = "UNKNOWN" }
$trendGateClear = $trendGate -ne "UNKNOWN" -and $trendGate -notlike "BLOCKED_*"
$trendLaneReady = $trendReviewReady -or $trendGateClear
$capitalReviewReady = if ($null -ne $capitalPacket) { [bool]$capitalPacket.capitalOverrideReviewReady } else { $false }
$envDiffReviewReady = if ($null -ne $envPacket) { [bool]$envPacket.envDiffReviewReady } else { $false }
$createEvidenceComplete = if ($null -ne $createPacket) { @($createPacket.missingEvidence).Count -eq 0 } else { $false }
$candidatePlanComplete = if ($null -ne $createPacket) { [bool]$createPacket.candidatePlanComplete } else { $false }

if ($null -eq $trendPacket) { Add-Unique -List $missingEvidence -Value "source trend override review packet summary" }
if ($null -eq $envPacket) { Add-Unique -List $missingEvidence -Value "source env diff preflight packet summary" }
if ($null -eq $createPacket) { Add-Unique -List $missingEvidence -Value "source create authorization preflight packet summary" }
if (-not $trendLaneReady) { Add-Unique -List $bundleBlockers -Value "TREND_OVERRIDE_REVIEW_NOT_READY" }
if (-not $capitalReviewReady) { Add-Unique -List $bundleBlockers -Value "CAPITAL_OVERRIDE_REVIEW_NOT_READY" }
if (-not $envDiffReviewReady) { Add-Unique -List $bundleBlockers -Value "GRID_ENV_DIFF_REVIEW_NOT_READY" }
if (-not $createEvidenceComplete) { Add-Unique -List $bundleBlockers -Value "CREATEGRID_PREFLIGHT_EVIDENCE_INCOMPLETE" }
if (-not $candidatePlanComplete) { Add-Unique -List $bundleBlockers -Value "CANDIDATE_PLAN_INCOMPLETE" }

$bundleReady = (
    $missingEvidence.Count -eq 0 -and
    $bundleBlockers.Count -eq 0 -and
    $trendLaneReady -and
    $capitalReviewReady -and
    $envDiffReviewReady -and
    $createEvidenceComplete -and
    $candidatePlanComplete
)

$executionBlockers = @(
    "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE",
    "OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED",
    "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED",
    "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED",
    "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED"
)

$status = if ($bundleReady) {
    "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION"
} else {
    "BLOCKED_GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_MUTATION"
}
$decision = if ($bundleReady) {
    "PREPARE_SEPARATE_GRID_OPEN_OPERATOR_AUTHORIZATIONS"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_OPEN_AUTHORIZATION_BUNDLE_EVIDENCE"
} else {
    "RESOLVE_GRID_OPEN_AUTHORIZATION_BUNDLE_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceCapitalOverridePacket = "prepare_grid_capital_override_review_packet_ssh.ps1"
    sourceCapitalOverrideStatus = if ($null -ne $capitalPacket) { $capitalPacket.status } else { "UNKNOWN" }
    readinessSummary = [pscustomobject]@{
        trendOverrideReviewReady = $trendReviewReady
        trendGate = $trendGate
        trendGateClearanceAccepted = $trendGateClear
        trendLaneReady = $trendLaneReady
        capitalOverrideReviewReady = $capitalReviewReady
        envDiffReviewReady = $envDiffReviewReady
        createGridPreflightEvidenceComplete = $createEvidenceComplete
        candidatePlanComplete = $candidatePlanComplete
        bundleReadyForOperatorReview = $bundleReady
    }
    authorizationLanes = @(
        [pscustomobject]@{
            lane = "trend-regime-override"
            status = if ($trendReviewReady) { "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION" } elseif ($trendGateClear) { "CLEAR_BY_FRESH_TREND_GATE_NOT_MUTATION" } else { "NOT_READY" }
            requiredAuthorization = if ($trendGateClear) { "fresh trend gate clearance accepted; separate trend override not required unless the gate becomes blocked" } else { "separate written trend-regime override naming current trend and trendPct, or fresh trend gate clearance" }
            sourceStatus = if ($null -ne $trendPacket) { $trendPacket.status } else { "UNKNOWN" }
        },
        [pscustomobject]@{
            lane = "capital-cap-override"
            status = if ($capitalReviewReady) { "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
            requiredAuthorization = "separate written capital-cap override naming candidateCapitalUsdt and effectiveReviewCapitalCapUsdt"
            sourceStatus = if ($null -ne $capitalPacket) { $capitalPacket.status } else { "UNKNOWN" }
        },
        [pscustomobject]@{
            lane = "production-env-diff"
            status = if ($envDiffReviewReady) { "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION" } else { "NOT_READY" }
            requiredAuthorization = "separate written production env diff authorization for TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true"
            sourceStatus = if ($null -ne $envPacket) { $envPacket.status } else { "UNKNOWN" }
        },
        [pscustomobject]@{
            lane = "post-env-read-only-verification"
            status = "PENDING_ENV_DIFF_DEPLOY_NOT_MUTATION"
            requiredAuthorization = "separate deploy/restart authorization followed by split acceptance and refreshed grid packets"
            sourceStatus = "PENDING"
        },
        [pscustomobject]@{
            lane = "createGrid"
            status = if ($createEvidenceComplete -and $candidatePlanComplete) { "EVIDENCE_COMPLETE_BUT_AUTHORIZATION_PENDING_NOT_MUTATION" } else { "NOT_READY" }
            requiredAuthorization = "separate written createGrid authorization naming reviewedCreateGridInputs after post-env verification"
            sourceStatus = if ($null -ne $createPacket) { $createPacket.status } else { "UNKNOWN" }
        }
    )
    remainingExecutionBlockers = $executionBlockers
    missingEvidence = @($missingEvidence)
    bundleBlockers = @($bundleBlockers)
    reviewedCreateGridInputs = if ($null -ne $createPacket) { $createPacket.reviewedCreateGridInputs } else { $null }
    capitalOverrideRequest = if ($null -ne $capitalPacket) { $capitalPacket.capitalOverrideRequest } else { $null }
    proposedSeparateEnvDiff = if ($null -ne $envPacket) { $envPacket.proposedSeparateEnvDiff } else { @() }
    requiredOperatorAuthorizationSequence = @(
        "1. trend-regime override or fresh trend clearance",
        "2. capital-cap override if candidateCapitalUsdt remains above effectiveReviewCapitalCapUsdt",
        "3. production env diff authorization and deploy/restart",
        "4. post-env read-only split acceptance plus refreshed grid open packets",
        "5. createGrid authorization with freshly reviewed inputs"
    )
    postEnvReadOnlyVerification = @(
        "verify split acceptance",
        "refresh grid open decision snapshot",
        "refresh grid trend override review packet",
        "refresh grid env diff preflight packet",
        "refresh grid create authorization preflight packet",
        "refresh grid open authorization bundle",
        "confirm runtime log has no unexpected order/OCO/grid/Earn/fund mutation lines"
    )
    gridOpenAuthorizationBundleReady = $bundleReady
    trendOverrideAllowed = $false
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
    sourceCapitalOverridePacketSummary = $capitalPacket
    notAuthorization = "read-only grid open authorization bundle only; does not approve trend override, approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-open-authorization-bundle] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid capital override review packet only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_capital_override_packet=prepare_grid_capital_override_review_packet_ssh.ps1 exitCode=$capitalExitCode"
Write-Host "grid_open_authorization_bundle_status=$status"
Write-Host "grid_open_authorization_bundle_decision=$decision"
Write-Host "grid_open_authorization_bundle_ready=$($bundleReady.ToString().ToLowerInvariant())"
Write-Host "trend_override_allowed=false"
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
Write-Host ("grid_open_authorization_bundle_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_open_authorization_bundle_blockers=" + (ConvertTo-Json -Compress @($bundleBlockers)))
Write-Host ("grid_open_authorization_bundle_execution_blockers=" + (ConvertTo-Json -Compress @($executionBlockers)))
Write-Host ("grid_open_authorization_bundle_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid open authorization bundle only; does not approve trend override, approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-open-authorization-bundle] read-only check complete"

if ($RequireBundleReady -and -not $bundleReady) {
    throw "Grid open authorization bundle is not ready: $status; blockers=$(@($bundleBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
