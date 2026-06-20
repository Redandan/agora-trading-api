param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$TinyLiveDays = 30,
    [int]$SignalExecutionDays = 5,
    [int]$SignalBlockedDays = 7,
    [int]$SignalAccuracyDays = 14,
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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Get-LastPrefixedValue {
    param(
        [string]$Text,
        [string]$Prefix
    )

    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return $null
    }
    return $line.Substring($Prefix.Length)
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        $parsed = $Value | ConvertFrom-Json -ErrorAction Stop
        return @($parsed)
    } catch {
        return @()
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) {
    throw "RuntimeEvidenceMinutes must be between 60 and 43200."
}
if ($TinyLiveDays -lt 1 -or $TinyLiveDays -gt 90) {
    throw "TinyLiveDays must be between 1 and 90."
}
if ($SignalExecutionDays -lt 1 -or $SignalExecutionDays -gt 90 -or $SignalBlockedDays -lt 1 -or $SignalBlockedDays -gt 90 -or $SignalAccuracyDays -lt 1 -or $SignalAccuracyDays -gt 90) {
    throw "SignalExecutionDays, SignalBlockedDays, and SignalAccuracyDays must be between 1 and 90."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for live review packet preflight."
}

$bundleScript = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
$bundleArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $bundleScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-RuntimeEvidenceMinutes", [string]$RuntimeEvidenceMinutes,
    "-TinyLiveDays", [string]$TinyLiveDays,
    "-SignalExecutionDays", [string]$SignalExecutionDays,
    "-SignalBlockedDays", [string]$SignalBlockedDays,
    "-SignalAccuracyDays", [string]$SignalAccuracyDays
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $bundleOutput = & $powerShell.Source @bundleArgs 2>&1
    $bundleExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$bundleText = ($bundleOutput | Out-String)
$blockers = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $bundleText -Prefix "bundle_blockers=")
$deploymentMetadataStatus = Get-LastPrefixedValue -Text $bundleText -Prefix "deployment_metadata_status="
$originMetadataStatus = Get-LastPrefixedValue -Text $bundleText -Prefix "origin_metadata_status="
$bundleBlockerSummary = Get-LastPrefixedValue -Text $bundleText -Prefix "bundle_blocker_summary="
$liveAllowed = Get-LastPrefixedValue -Text $bundleText -Prefix "live_review_packet_allowed="
$deployRequired = Get-LastPrefixedValue -Text $bundleText -Prefix "deploy_required_before_live_review="
$bundleVerdict = Get-LastPrefixedValue -Text $bundleText -Prefix "bundle_verdict="

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ([string]::IsNullOrWhiteSpace($bundleBlockerSummary)) {
    $missingRequirements.Add("bundle_blocker_summary is missing")
}
if ($bundleExitCode -ne 0) {
    $missingRequirements.Add("full bundle exited non-zero")
}
if ($blockers.Count -gt 0) {
    $missingRequirements.Add("bundle_blockers is non-empty")
}
if ($liveAllowed -ne "true") {
    $missingRequirements.Add("live_review_packet_allowed is not true")
}
if ($deployRequired -ne "false") {
    $missingRequirements.Add("deploy_required_before_live_review is not false")
}
if ($bundleVerdict -ne "READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED") {
    $missingRequirements.Add("bundle_verdict is not READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED")
}
if ($deploymentMetadataStatus -ne "CURRENT" -and $deploymentMetadataStatus -ne "DOCS_TOOLING_ONLY_DRIFT") {
    $missingRequirements.Add("deployment_metadata_status is not current")
}
if ($originMetadataStatus -ne "CURRENT_ORIGIN_MAIN") {
    $missingRequirements.Add("origin_metadata_status is not CURRENT_ORIGIN_MAIN")
}

$packetReady = $missingRequirements.Count -eq 0
$runtimeStale = @($blockers) -contains "DEPLOYED_RUNTIME_NOT_CURRENT" -or $originMetadataStatus -ne "CURRENT_ORIGIN_MAIN" -or $deployRequired -eq "true"

Write-Host "[live-review-packet-preflight] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs the full live-readiness bundle only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_live_readiness_bundle_ssh.ps1"
Write-Host "origin_delta_classifier=smoke_live_origin_delta_local.ps1"
Write-Host "source_smoke_exit_code=$bundleExitCode"
Write-Host "deployment_metadata_status=$deploymentMetadataStatus"
Write-Host "origin_metadata_status=$originMetadataStatus"
Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host "packet_bundle_blocker_summary=$bundleBlockerSummary"
Write-Host "live_review_packet_allowed=$liveAllowed"
Write-Host "deploy_required_before_live_review=$deployRequired"
Write-Host "bundle_verdict=$bundleVerdict"
Write-Host ("packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "notAuthorization=read-only preflight only; does not authorize live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, production env mutation, DB changes, external backfill/import, deploy, restart, or policy relaxation"

if ($packetReady) {
    Write-Host "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    Write-Host "packet_next_action=Attach the full bundle output to a separate operator decision; this is not live approval."
} elseif ($bundleVerdict -eq "NO_EVIDENCE" -or $bundleVerdict -eq "NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY" -or $bundleExitCode -ne 0) {
    Write-Host "packet_status=NO_EVIDENCE"
    if ($runtimeStale) {
        Write-Host "packet_next_action=Run smoke_live_origin_delta_local.ps1 to classify current-origin delta. If origin_delta_status=RUNTIME_DRIFT, separately deploy and verify current origin/main. If origin_delta_status=DOCS_TOOLING_ONLY_DRIFT, review and attach classifier evidence separately. If origin_delta_status=NO_LOCAL_EVIDENCE, refresh local git evidence or rerun metadata smoke. In all cases, rerun the full read-only live-readiness bundle before drafting any live review packet."
    } else {
        Write-Host "packet_next_action=Fix SSH/read-only smoke collection, then rerun before drafting any live review packet."
    }
} else {
    Write-Host "packet_status=NOT_READY"
    Write-Host "packet_next_action=Resolve or separately authorize the listed blockers, then rerun the full bundle before drafting any live review packet."
}

Write-Host "[live-review-packet-preflight] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Live review packet preflight is not ready: $(@($missingRequirements) -join '; ')"
}
