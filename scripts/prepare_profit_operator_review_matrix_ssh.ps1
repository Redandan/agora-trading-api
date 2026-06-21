param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RequireReviewItems
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
        throw "$Name contains unsupported characters for profit operator matrix arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator review matrix."
    }

    Write-Host "[profit-operator-review-matrix] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[profit-operator-review-matrix] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
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
if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) {
    throw "ChildTimeoutSeconds must be between 60 and 3600."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$readiness = Invoke-ReadOnlyScript -ScriptName "prepare_profit_readiness_brief_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds"
))

$evidenceWatch = Invoke-ReadOnlyScript -ScriptName "watch_profit_evidence_readiness_ssh.ps1" -Arguments ($commonArgs + @(
    "-MaxAttempts", "1",
    "-SleepSeconds", "0",
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds"
))

$exitSide = Invoke-ReadOnlyScript -ScriptName "prepare_exit_side_profit_review_packet_ssh.ps1" -Arguments ($commonArgs + @(
    "-StrategyId", "$StrategyId",
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit"
))

$readinessStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "profit_readiness_brief_status="
$entryLaneStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "entry_filter_lane_status="
$exitLaneStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "exit_lane_status="
$signalPolicyClear = Get-LastPrefixedValue -Text $readiness.Text -Prefix "signal_policy_clear="
$dataFreshnessStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_current_status="
$trailingAcceptance = Get-LastPrefixedValue -Text $readiness.Text -Prefix "trailing_stop_acceptance="
$readinessMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $readiness.Text -Prefix "profit_readiness_missing_requirements=")

$watchStatus = Get-LastPrefixedValue -Text $evidenceWatch.Text -Prefix "profit_evidence_watch_status="
$watchReason = Get-LastPrefixedValue -Text $evidenceWatch.Text -Prefix "profit_evidence_watch_reason="
$watchReplayRecommendation = Get-LastPrefixedValue -Text $evidenceWatch.Text -Prefix "profit_evidence_watch_replay_recommendation="

$exitStatus = Get-LastPrefixedValue -Text $exitSide.Text -Prefix "exit_side_profit_review_packet_status="
$exitPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $exitSide.Text -Prefix "exit_side_profit_review_packet=")
$exitMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $exitSide.Text -Prefix "exit_side_profit_review_missing_requirements=")

$reviewItems = [System.Collections.Generic.List[object]]::new()

$reviewItems.Add([pscustomobject]@{
    lane = "exit-side"
    candidate = "Trailing stop + strategy 485 aged negative-EV review"
    status = $exitStatus
    priority = "P1"
    readyForOperatorReview = ($exitStatus -eq "READY_FOR_EXIT_SIDE_OPERATOR_REVIEW_NOT_MUTATION")
    evidenceMarkers = @("trailing_stop_acceptance=$trailingAcceptance", "exit_side_profit_review_packet_status=$exitStatus")
    missingRequirements = @($exitMissing)
    nextAction = "Attach exit-side packet to a separate operator review; do not enable trailing, close positions, or modify OCO without separate approval."
})

$reviewItems.Add([pscustomobject]@{
    lane = "entry-filter"
    candidate = "Governance and missed-opportunity entry/filter review"
    status = $entryLaneStatus
    priority = "P2"
    readyForOperatorReview = ($entryLaneStatus -notmatch "^BLOCKED")
    evidenceMarkers = @("signal_policy_clear=$signalPolicyClear", "data_freshness_current_status=$dataFreshnessStatus")
    missingRequirements = @($readinessMissing | Where-Object { $_ -match "signal policy|governance|missed|DataFreshness current" })
    nextAction = "Keep EntryDedup/DataFreshness/live policy unchanged until signal policy and current DataFreshness evidence are clear."
})

$reviewItems.Add([pscustomobject]@{
    lane = "data-freshness-replay"
    candidate = "DataFreshness false-kill shadow/counterfactual replay"
    status = $watchStatus
    priority = "P2"
    readyForOperatorReview = ($watchStatus -eq "EVIDENCE_READY_FOR_REVIEW_NOT_LIVE")
    evidenceMarkers = @("profit_evidence_watch_reason=$watchReason", "profit_evidence_watch_replay_recommendation=$watchReplayRecommendation")
    missingRequirements = @($readinessMissing | Where-Object { $_ -match "replay|candidate|EV|OCO|shadow|counterfactual|DataFreshness" })
    nextAction = "Rerun bounded evidence watch after new DataFreshnessGuard rows are expected; do not relax DataFreshnessGuard from historical proxy alone."
})

$readyItems = @($reviewItems | Where-Object { $_.readyForOperatorReview -eq $true })
$matrixStatus = if ($readyItems.Count -gt 0) { "HAS_REVIEW_READY_ITEMS_NOT_LIVE" } else { "NO_REVIEW_READY_ITEMS" }
$nextAction = if ($readyItems.Count -gt 0) {
    "Review ready read-only items separately; keep all live/order/OCO/policy mutations disabled unless separately authorized."
} else {
    "Continue read-only evidence collection; no candidate is ready for operator review."
}

$matrix = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_REVIEW_MATRIX"
    status = $matrixStatus
    symbol = $Symbol
    readinessStatus = $readinessStatus
    evidenceWatchStatus = $watchStatus
    exitSideStatus = $exitStatus
    reviewItems = @($reviewItems)
    sourceScripts = @(
        "prepare_profit_readiness_brief_ssh.ps1",
        "watch_profit_evidence_readiness_ssh.ps1",
        "prepare_exit_side_profit_review_packet_ssh.ps1"
    )
    childExitCodes = @{
        profitReadinessBrief = $readiness.ExitCode
        profitEvidenceWatch = $evidenceWatch.ExitCode
        exitSideProfitReview = $exitSide.ExitCode
    }
    nextAction = $nextAction
    notAuthorization = "read-only profit operator review matrix only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-review-matrix] read-only matrix"
Write-Host "scope=READ_ONLY; invokes prepare_profit_readiness_brief_ssh.ps1, watch_profit_evidence_readiness_ssh.ps1, and prepare_exit_side_profit_review_packet_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "profit_readiness_brief_status=$readinessStatus"
Write-Host "profit_evidence_watch_status=$watchStatus"
Write-Host "exit_side_profit_review_packet_status=$exitStatus"
Write-Host "entry_filter_lane_status=$entryLaneStatus"
Write-Host "exit_lane_status=$exitLaneStatus"
Write-Host "data_freshness_current_status=$dataFreshnessStatus"
Write-Host "trailing_stop_acceptance=$trailingAcceptance"
Write-Host ("profit_operator_review_items=" + (ConvertTo-Json -Compress -Depth 8 @($reviewItems)))
Write-Host ("profit_operator_review_matrix_packet=" + (ConvertTo-Json -Compress -Depth 10 $matrix))
Write-Host "profit_operator_review_matrix_status=$matrixStatus"
Write-Host "profit_operator_review_matrix_next_action=$nextAction"
Write-Host "notAuthorization=read-only profit operator review matrix only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-review-matrix] read-only check complete"

if ($RequireReviewItems -and $matrixStatus -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator review matrix has no review-ready items."
}
