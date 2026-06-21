param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$Limit = 200,
    [switch]$RequireObserved
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}

if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}

if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}

if ($ReviewDays -lt 1 -or $ReviewDays -gt 90) {
    throw "ReviewDays must be between 1 and 90."
}

if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}

if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$scriptDir = $PSScriptRoot

function Invoke-Smoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments,
        [bool]$AllowFailure = $false
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if (-not $powerShell) {
        throw "PowerShell is not available for child smoke invocation."
    }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exit = $LASTEXITCODE
    $text = ($output | Out-String).Trim()

    Write-Host ""
    Write-Host "===== $Name ====="
    if ($text.Length -gt 5000) {
        Write-Host ($text.Substring(0, 5000) + "`n...[truncated]")
    } else {
        Write-Host $text
    }
    Write-Host "===== $Name exitCode=$exit ====="

    if ($exit -ne 0 -and -not $AllowFailure) {
        throw "$Name failed with exit code $exit"
    }

    return [PSCustomObject]@{
        Text = $text
        ExitCode = $exit
    }
}

function Get-Marker {
    param([string]$Text, [string]$Prefix)
    $matches = [regex]::Matches($Text, "(?m)^$([regex]::Escape($Prefix))(.+)$")
    if ($matches.Count -eq 0) {
        return ""
    }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

Write-Host "[data-freshness-replay-observation-bundle] read-only review bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol reviewDays=$ReviewDays replayIdDays=$ReplayIdDays limit=$Limit requireObserved=$($RequireObserved.IsPresent.ToString().ToLowerInvariant())"

$common = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$origin = Invoke-Smoke -Name "origin-delta" -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile
)

$originDelta = Get-Marker -Text $origin.Text -Prefix "origin_delta_status="
$serverWorktreeCommit = Get-Marker -Text $origin.Text -Prefix "server_worktree_commit="

$replayIdArgs = $common + @("-ReviewDays", "$ReplayIdDays", "-Limit", "$([Math]::Min($Limit, 500))")
if ($originDelta -eq "DOCS_TOOLING_ONLY_DRIFT" -and -not [string]::IsNullOrWhiteSpace($serverWorktreeCommit)) {
    $replayIdArgs += @("-ExpectedCommit", $serverWorktreeCommit)
}
if ($RequireObserved) {
    $replayIdArgs += "-RequireObserved"
}
$replayId = Invoke-Smoke -Name "replay-candidate-id" -ScriptName "smoke_data_freshness_replay_candidate_id_ssh.ps1" -Arguments $replayIdArgs

$counterfactual = Invoke-Smoke -Name "counterfactual-review" -ScriptName "smoke_data_freshness_counterfactual_review_ssh.ps1" -Arguments ($common + @("-ReviewDays", "$ReviewDays", "-Limit", "$Limit"))

$runtimeCurrent = Get-Marker -Text $replayId.Text -Prefix "deployment_runtime_current_for_replay_id="
$replayIdRecommendation = Get-Marker -Text $replayId.Text -Prefix "  data_freshness_replay_candidate_id_recommendation="
$replayIdRows = Get-Marker -Text $replayId.Text -Prefix "  replay_candidate_id_rows="
$counterfactualRecommendation = Get-Marker -Text $counterfactual.Text -Prefix "  data_freshness_counterfactual_recommendation="
$completeReplayRows = Get-Marker -Text $counterfactual.Text -Prefix "  complete_replayable_candidate_rows="
$missingCounterfactualFields = Get-Marker -Text $counterfactual.Text -Prefix "  missing_counterfactual_fields="

$reviewItems = New-Object System.Collections.Generic.List[string]
if ($originDelta -eq "RUNTIME_DRIFT" -or $runtimeCurrent -eq "false" -or $replayIdRecommendation -eq "DEPLOYED_RUNTIME_NOT_CURRENT") {
    $reviewItems.Add("DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_OBSERVATION")
}
if ($replayIdRecommendation -eq "PENDING_NO_NEW_DATAFRESHNESS_ROWS") {
    $reviewItems.Add("WAIT_FOR_NEW_DATAFRESHNESS_SAMPLE")
}
if ($replayIdRecommendation -eq "REPLAY_CANDIDATE_ID_EVIDENCE_INCOMPLETE") {
    $reviewItems.Add("FIX_REPLAY_CANDIDATE_ID_EVIDENCE")
}
if ($replayIdRecommendation -eq "REPLAY_CANDIDATE_ID_EVIDENCE_OK") {
    $reviewItems.Add("REPLAY_CANDIDATE_ID_EVIDENCE_COLLECTED")
}
if ($counterfactualRecommendation -eq "COUNTERFACTUAL_NOT_REPLAYABLE_CANDIDATE_SNAPSHOT_MISSING") {
    $reviewItems.Add("COLLECT_ENTRY_TP_SL_EV_OCO_REPLAY_SNAPSHOTS")
}
if ($counterfactualRecommendation -eq "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
    $reviewItems.Add("REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES")
}

if ($reviewItems -contains "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_OBSERVATION") {
    $recommendation = "DEPLOY_CURRENT_RUNTIME_THEN_OBSERVE_REPLAY_ID"
} elseif ($reviewItems -contains "FIX_REPLAY_CANDIDATE_ID_EVIDENCE") {
    $recommendation = "FIX_REPLAY_ID_EVIDENCE_BEFORE_POLICY_REVIEW"
} elseif ($reviewItems -contains "COLLECT_ENTRY_TP_SL_EV_OCO_REPLAY_SNAPSHOTS") {
    $recommendation = "COLLECT_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW"
} elseif ($reviewItems -contains "WAIT_FOR_NEW_DATAFRESHNESS_SAMPLE") {
    $recommendation = "WAIT_FOR_NEW_DATAFRESHNESS_SAMPLE"
} elseif ($reviewItems -contains "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
    $recommendation = "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES"
} else {
    $recommendation = "NO_REPLAY_OBSERVATION_ACTION"
}

Write-Host ""
Write-Host "Replay Observation Bundle Summary:"
Write-Host "  origin_delta_status=$originDelta"
Write-Host "  deployment_runtime_current_for_replay_id=$runtimeCurrent"
Write-Host "  data_freshness_replay_candidate_id_recommendation=$replayIdRecommendation"
Write-Host "  replay_candidate_id_rows=$replayIdRows"
Write-Host "  data_freshness_counterfactual_recommendation=$counterfactualRecommendation"
Write-Host "  complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "  missing_counterfactual_fields=$missingCounterfactualFields"
Write-Host ("  replay_observation_review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host "  replay_observation_bundle_recommendation=$recommendation"
Write-Host "  notAuthorization=read-only review evidence only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host ""
Write-Host "[data-freshness-replay-observation-bundle] OK read-only check complete"
