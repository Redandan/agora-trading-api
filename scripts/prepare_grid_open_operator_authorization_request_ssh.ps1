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
    [switch]$RequireRequestReady
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

function Add-UniqueValues {
    param([System.Collections.Generic.List[string]]$List, $Values)
    foreach ($value in @($Values)) {
        Add-Unique -List $List -Value ([string]$value)
    }
}

function Get-StringArray {
    param($Values)
    $list = [System.Collections.Generic.List[string]]::new()
    if ($null -eq $Values) { return @() }
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        if ($value -is [pscustomobject] -and @($value.PSObject.Properties).Count -eq 0) { continue }
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) { $list.Add($text) }
    }
    return @($list)
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
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid open operator authorization request." }

$bundleScript = Join-Path $PSScriptRoot "prepare_grid_open_authorization_bundle_ssh.ps1"
if (-not (Test-Path -LiteralPath $bundleScript)) { throw "Missing grid open authorization bundle script: $bundleScript" }

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
    $bundleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $bundleScript @commonArgs 2>&1
    $bundleExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$bundleText = ($bundleOutput | Out-String -Width 8192)
$bundlePacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $bundleText -Prefix "grid_open_authorization_bundle_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$requestBlockers = [System.Collections.Generic.List[string]]::new()
if ($bundleExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open authorization bundle completed" }
if ($null -eq $bundlePacket) { Add-Unique -List $missingEvidence -Value "grid_open_authorization_bundle_packet valid JSON" }

$bundleReady = if ($null -ne $bundlePacket) { [bool]$bundlePacket.gridOpenAuthorizationBundleReady } else { $false }
$createInputs = if ($null -ne $bundlePacket) { $bundlePacket.reviewedCreateGridInputs } else { $null }
$capitalRequest = if ($null -ne $bundlePacket) { $bundlePacket.capitalOverrideRequest } else { $null }
$envDiff = if ($null -ne $bundlePacket) { @($bundlePacket.proposedSeparateEnvDiff) } else { @() }
$executionBlockers = if ($null -ne $bundlePacket) { @($bundlePacket.remainingExecutionBlockers) } else { @() }
$bundleBlockers = if ($null -ne $bundlePacket) { Get-StringArray $bundlePacket.bundleBlockers } else { @() }
$capitalPacket = if ($null -ne $bundlePacket) { $bundlePacket.sourceCapitalOverridePacketSummary } else { $null }
$capitalReviewReady = if ($null -ne $capitalPacket) { [bool]$capitalPacket.capitalOverrideReviewReady } else { $false }
$capitalHardBlockers = if ($null -ne $capitalPacket) { Get-StringArray $capitalPacket.hardBlockers } else { @() }
$createPacket = if ($null -ne $capitalPacket) { $capitalPacket.sourceCreateAuthorizationPreflightPacketSummary } else { $null }
$createReviewBlockers = if ($null -ne $createPacket) { Get-StringArray $createPacket.reviewBlockers } else { @() }
$coveredCreateReviewBlockers = [System.Collections.Generic.List[string]]::new()
$uncoveredCreateReviewBlockers = [System.Collections.Generic.List[string]]::new()
foreach ($blocker in @($createReviewBlockers)) {
    if ($blocker -eq "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP" -and $capitalReviewReady) {
        Add-Unique -List $coveredCreateReviewBlockers -Value $blocker
    } else {
        Add-Unique -List $uncoveredCreateReviewBlockers -Value $blocker
    }
}
$envPacket = if ($null -ne $createPacket) { $createPacket.sourceEnvDiffPacketSummary } else { $null }
$envReviewBlockers = if ($null -ne $envPacket) { Get-StringArray $envPacket.reviewBlockers } else { @() }
$trendPacket = if ($null -ne $envPacket) { $envPacket.sourceTrendOverridePacketSummary } else { $null }
$trendHardBlockers = if ($null -ne $trendPacket) { Get-StringArray $trendPacket.hardBlockers } else { @() }

if (-not $bundleReady) { Add-Unique -List $requestBlockers -Value "GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_READY" }
Add-UniqueValues -List $requestBlockers -Values $bundleBlockers
Add-UniqueValues -List $requestBlockers -Values $capitalHardBlockers
Add-UniqueValues -List $requestBlockers -Values $envReviewBlockers
Add-UniqueValues -List $requestBlockers -Values $uncoveredCreateReviewBlockers
Add-UniqueValues -List $requestBlockers -Values $trendHardBlockers
if ($null -eq $createInputs) { Add-Unique -List $missingEvidence -Value "reviewedCreateGridInputs from grid open authorization bundle" }
if ($null -eq $capitalRequest) { Add-Unique -List $missingEvidence -Value "capitalOverrideRequest from grid open authorization bundle" }
if ($envDiff.Count -eq 0) { Add-Unique -List $missingEvidence -Value "proposedSeparateEnvDiff from grid open authorization bundle" }
if ($executionBlockers.Count -eq 0) { Add-Unique -List $missingEvidence -Value "remainingExecutionBlockers from grid open authorization bundle" }

$trendText = "I authorize a separate trend-regime override for $Symbol only if the fresh bundle remains review-ready, trend risk is not HIGH, event risk remains CLEAR_EVENT_RISK_R0, replay score remains >= 70, and stopBreakRows remains 0."
$capitalTextLine = if ($null -ne $capitalRequest) {
    "I authorize a separate capital-cap override for $Symbol from effectiveReviewCapitalCapUsdt=$($capitalRequest.effectiveReviewCapitalCapUsdt) to requestedMaximumReviewCapitalCapUsdt=$($capitalRequest.requestedMaximumReviewCapitalCapUsdt), requiredCapRaiseUsdt=$($capitalRequest.requiredCapRaiseUsdt), requiredCapMultiplier=$($capitalRequest.requiredCapMultiplier)."
} else {
    "I authorize a separate capital-cap override for $Symbol using the refreshed capitalOverrideRequest values."
}
$envText = "I authorize a separate production env diff for $Symbol grid review only: $($envDiff -join '; '); scheduler/recovery/Earn remain disabled unless separately authorized."
$deployText = "I authorize deploy/restart only after the env diff approval, followed by read-only split acceptance, refreshed grid packets, and runtime-log verification before any createGrid request."
$createText = if ($null -ne $createInputs) {
    "I authorize a separate createGrid review only after post-env verification, using symbol=$($createInputs.symbol), priceLower=$($createInputs.priceLower), priceUpper=$($createInputs.priceUpper), gridCount=$($createInputs.gridCount), perLevelUsdt=$($createInputs.perLevelUsdt), candidateCapitalUsdt=$($createInputs.candidateCapitalUsdt), stopLow=$($createInputs.stopLow), stopHigh=$($createInputs.stopHigh), stopOutPct=$($createInputs.stopOutPct)."
} else {
    "I authorize a separate createGrid review only after post-env verification using freshly refreshed reviewedCreateGridInputs."
}

$requestReady = (
    $missingEvidence.Count -eq 0 -and
    $requestBlockers.Count -eq 0 -and
    $bundleReady
)
$status = if ($requestReady) {
    "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION"
} else {
    "BLOCKED_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_MUTATION"
}
$decision = if ($requestReady) {
    "AWAIT_SEPARATE_OPERATOR_AUTHORIZATIONS"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE"
} else {
    "RESOLVE_GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceAuthorizationBundle = "prepare_grid_open_authorization_bundle_ssh.ps1"
    sourceAuthorizationBundleStatus = if ($null -ne $bundlePacket) { $bundlePacket.status } else { "UNKNOWN" }
    sourceAuthorizationBundleReady = $bundleReady
    authorizationRequestReady = $requestReady
    authorizationRequestLines = @(
        $trendText,
        $capitalTextLine,
        $envText,
        $deployText,
        $createText
    )
    requiredOperatorAuthorizationSequence = if ($null -ne $bundlePacket) { $bundlePacket.requiredOperatorAuthorizationSequence } else { @() }
    remainingExecutionBlockers = $executionBlockers
    reviewedCreateGridInputs = $createInputs
    capitalOverrideRequest = $capitalRequest
    proposedSeparateEnvDiff = $envDiff
    postEnvReadOnlyVerification = if ($null -ne $bundlePacket) { $bundlePacket.postEnvReadOnlyVerification } else { @() }
    requestBlockers = @($requestBlockers)
    bundleBlockers = @($bundleBlockers)
    capitalHardBlockers = @($capitalHardBlockers)
    envReviewBlockers = @($envReviewBlockers)
    createReviewBlockers = @($createReviewBlockers)
    coveredCreateReviewBlockers = @($coveredCreateReviewBlockers)
    uncoveredCreateReviewBlockers = @($uncoveredCreateReviewBlockers)
    trendHardBlockers = @($trendHardBlockers)
    missingEvidence = @($missingEvidence)
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
    sourceAuthorizationBundlePacketSummary = $bundlePacket
    notAuthorization = "read-only grid open operator authorization request only; does not approve trend override, approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[grid-open-operator-authorization-request] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid open authorization bundle only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_authorization_bundle=prepare_grid_open_authorization_bundle_ssh.ps1 exitCode=$bundleExitCode"
Write-Host "grid_open_operator_authorization_request_status=$status"
Write-Host "grid_open_operator_authorization_request_decision=$decision"
Write-Host "grid_open_operator_authorization_request_ready=$($requestReady.ToString().ToLowerInvariant())"
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
Write-Host ("grid_open_operator_authorization_request_lines=" + (ConvertTo-Json -Compress @($packet.authorizationRequestLines)))
Write-Host ("grid_open_operator_authorization_request_blockers=" + (ConvertTo-Json -Compress @($requestBlockers)))
Write-Host ("grid_open_operator_authorization_request_covered_create_review_blockers=" + (ConvertTo-Json -Compress @($coveredCreateReviewBlockers)))
Write-Host ("grid_open_operator_authorization_request_uncovered_create_review_blockers=" + (ConvertTo-Json -Compress @($uncoveredCreateReviewBlockers)))
Write-Host ("grid_open_operator_authorization_request_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_open_operator_authorization_request_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid open operator authorization request only; does not approve trend override, approve capital override, change production env, deploy, restart, call createGrid, enable grid/scheduler/recovery, place orders, modify OCO, send Telegram, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[grid-open-operator-authorization-request] read-only check complete"

if ($RequireRequestReady -and -not $requestReady) {
    throw "Grid open operator authorization request is not ready: $status; blockers=$(@($requestBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
