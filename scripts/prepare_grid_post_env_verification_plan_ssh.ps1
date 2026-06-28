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
    [switch]$RequirePlanReady
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
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid post-env verification plan." }

$requestScript = Join-Path $PSScriptRoot "prepare_grid_open_operator_authorization_request_ssh.ps1"
if (-not (Test-Path -LiteralPath $requestScript)) { throw "Missing grid open operator authorization request script: $requestScript" }

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
    $requestOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $requestScript @commonArgs 2>&1
    $requestExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$requestText = ($requestOutput | Out-String -Width 8192)
$requestPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $requestText -Prefix "grid_open_operator_authorization_request_packet=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$planBlockers = [System.Collections.Generic.List[string]]::new()
if ($requestExitCode -ne 0) { Add-Unique -List $missingEvidence -Value "grid open operator authorization request completed" }
if ($null -eq $requestPacket) { Add-Unique -List $missingEvidence -Value "grid_open_operator_authorization_request_packet valid JSON" }

$requestReady = if ($null -ne $requestPacket) { [bool]$requestPacket.authorizationRequestReady } else { $false }
$createInputs = if ($null -ne $requestPacket) { $requestPacket.reviewedCreateGridInputs } else { $null }
$envDiff = if ($null -ne $requestPacket) { @($requestPacket.proposedSeparateEnvDiff) } else { @() }
$postEnvChecks = if ($null -ne $requestPacket) { @($requestPacket.postEnvReadOnlyVerification) } else { @() }
$authorizationRequestBlockers = if ($null -ne $requestPacket) { Get-StringArray $requestPacket.requestBlockers } else { @() }

if (-not $requestReady) { Add-Unique -List $planBlockers -Value "GRID_OPEN_OPERATOR_AUTHORIZATION_REQUEST_NOT_READY" }
Add-UniqueValues -List $planBlockers -Values $authorizationRequestBlockers
if ($null -eq $createInputs) { Add-Unique -List $missingEvidence -Value "reviewedCreateGridInputs from operator authorization request" }
if ($envDiff.Count -eq 0) { Add-Unique -List $missingEvidence -Value "proposedSeparateEnvDiff from operator authorization request" }
if ($postEnvChecks.Count -eq 0) { Add-Unique -List $missingEvidence -Value "postEnvReadOnlyVerification from operator authorization request" }

$requiredPostEnvCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_grid_open_decision_snapshot_ssh.ps1",
    ".\scripts\prepare_grid_trend_override_review_packet_ssh.ps1",
    ".\scripts\prepare_grid_env_diff_preflight_packet_ssh.ps1",
    ".\scripts\prepare_grid_create_authorization_preflight_packet_ssh.ps1",
    ".\scripts\prepare_grid_open_authorization_bundle_ssh.ps1",
    ".\scripts\prepare_grid_open_operator_authorization_request_ssh.ps1"
)

$planReady = (
    $missingEvidence.Count -eq 0 -and
    $planBlockers.Count -eq 0 -and
    $requestReady
)
$status = if ($planReady) {
    "READY_FOR_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION"
} else {
    "BLOCKED_GRID_POST_ENV_VERIFICATION_PLAN_NOT_MUTATION"
}
$decision = if ($planReady) {
    "AWAIT_ENV_DIFF_AUTHORIZATION_AND_DEPLOY_BEFORE_RUNNING_VERIFICATION"
} elseif ($missingEvidence.Count -gt 0) {
    "REFRESH_GRID_POST_ENV_VERIFICATION_PLAN_EVIDENCE"
} else {
    "RESOLVE_GRID_POST_ENV_VERIFICATION_PLAN_BLOCKERS"
}

$packet = [pscustomobject]@{
    packetType = "GRID_POST_ENV_VERIFICATION_PLAN_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    sourceAuthorizationRequest = "prepare_grid_open_operator_authorization_request_ssh.ps1"
    sourceAuthorizationRequestStatus = if ($null -ne $requestPacket) { $requestPacket.status } else { "UNKNOWN" }
    sourceAuthorizationRequestReady = $requestReady
    proposedSeparateEnvDiff = $envDiff
    requiredPostEnvCommands = $requiredPostEnvCommands
    postEnvReadOnlyVerification = $postEnvChecks
    refreshedCreateGridInputsMustMatch = $createInputs
    postEnvPassCriteria = @(
        "split acceptance passes",
        "runtime log has no unexpected order/OCO/grid/Earn/fund mutation lines",
        "grid env diff remains TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true",
        "scheduler/recovery/Earn remain disabled unless separately authorized",
        "fresh createGrid preflight has missingEvidence=[]",
        "fresh authorization request remains ready before any createGrid authorization"
    )
    postEnvAbortCriteria = @(
        "split acceptance fails",
        "runtime log smoke fails or shows unexpected high-risk operation lines",
        "candidate createGrid inputs drift without a refreshed operator request",
        "event risk is not CLEAR_EVENT_RISK_R0",
        "trend risk becomes HIGH without a new review",
        "scheduler/recovery/Earn become enabled without separate authorization"
    )
    planBlockers = @($planBlockers)
    authorizationRequestBlockers = @($authorizationRequestBlockers)
    missingEvidence = @($missingEvidence)
    postEnvVerificationPlanReady = $planReady
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    sourceAuthorizationRequestPacketSummary = $requestPacket
    notAuthorization = "read-only grid post-env verification plan only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
}

Write-Host "[grid-post-env-verification-plan] read-only packet"
Write-Host "scope=READ_ONLY; invokes grid open operator authorization request only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_authorization_request=prepare_grid_open_operator_authorization_request_ssh.ps1 exitCode=$requestExitCode"
Write-Host "grid_post_env_verification_plan_status=$status"
Write-Host "grid_post_env_verification_plan_decision=$decision"
Write-Host "grid_post_env_verification_plan_ready=$($planReady.ToString().ToLowerInvariant())"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_post_env_verification_required_commands=" + (ConvertTo-Json -Compress @($requiredPostEnvCommands)))
Write-Host ("grid_post_env_verification_plan_blockers=" + (ConvertTo-Json -Compress @($planBlockers)))
Write-Host ("grid_post_env_verification_plan_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("grid_post_env_verification_plan_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "notAuthorization=read-only grid post-env verification plan only; does not authorize env changes, deploy, restart, createGrid, grid/scheduler/recovery enablement, orders, OCO, Telegram, or DB/grid/fund/Earn/exchange mutation"
Write-Host "[grid-post-env-verification-plan] read-only check complete"

if ($RequirePlanReady -and -not $planReady) {
    throw "Grid post-env verification plan is not ready: $status; blockers=$(@($planBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
