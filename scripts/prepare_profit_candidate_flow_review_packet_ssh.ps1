param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 14,
    [int]$ReplayIdDays = 3,
    [int]$FollowupHours = 6,
    [int]$Limit = 20,
    [int]$MaxCandidateRows = 1000,
    [switch]$RequireActionable
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
        throw "$Name contains unsupported characters for profit candidate-flow packet arguments."
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

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) {
        return $parsed
    }
    return 0
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
        throw "Unable to find powershell or pwsh for profit candidate-flow packet."
    }

    Write-Host "[profit-candidate-flow-review-packet] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[profit-candidate-flow-review-packet] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        ScriptName = $ScriptName
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
if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) {
    throw "ReviewDays must be between 1 and 30."
}
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}
if ($FollowupHours -lt 1 -or $FollowupHours -gt 48) {
    throw "FollowupHours must be between 1 and 48."
}
if ($Limit -lt 1 -or $Limit -gt 50) {
    throw "Limit must be between 1 and 50."
}
if ($MaxCandidateRows -lt 1 -or $MaxCandidateRows -gt 2000) {
    throw "MaxCandidateRows must be between 1 and 2000."
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

$readiness = Invoke-ReadOnlyScript -ScriptName "prepare_data_freshness_replay_evidence_readiness_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", [string]$ReviewDays,
    "-ReplayIdDays", [string]$ReplayIdDays,
    "-Limit", [string]$MaxCandidateRows
))

$progression = Invoke-ReadOnlyScript -ScriptName "smoke_buy_like_candidate_progression_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", [string]$ReviewDays,
    "-FollowupHours", [string]$FollowupHours,
    "-Limit", [string]$Limit,
    "-MaxCandidateRows", [string]$MaxCandidateRows
))

$dataFreshnessStatus = Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_replay_evidence_readiness_status="
$dataFreshnessRecommendation = Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_replay_candidate_id_recommendation="
$dataFreshnessBlockers = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_replay_evidence_blockers=")
$dataFreshnessRequired = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_replay_evidence_required=")
$latestDataFreshnessRowTime = Get-LastPrefixedValue -Text $readiness.Text -Prefix "latest_data_freshness_row_time="
$latestDataFreshnessRowAgeHours = Get-LastPrefixedValue -Text $readiness.Text -Prefix "latest_data_freshness_row_age_hours="
$sampleGapRecommendation = Get-LastPrefixedValue -Text $readiness.Text -Prefix "data_freshness_sample_gap_rca_recommendation="
$completeReplayableRows = Get-LastPrefixedValue -Text $readiness.Text -Prefix "complete_replayable_candidate_rows="

$buyLikeRecommendation = Get-LastPrefixedValue -Text $progression.Text -Prefix "  buy_like_candidate_progression_recommendation="
$buyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  buy_like_candidate_rows=")
$sampledBuyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  sampled_buy_like_candidate_rows=")
$followupTerminalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  followup_terminal_event_rows=")
$noTerminalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  no_terminal_followup_rows=")
$filterBlockRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  filter_block_followup_rows=")
$entrySkipRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  entry_skip_followup_rows=")
$signalBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  signal_buy_rows=")
$autotradeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $progression.Text -Prefix "  autotrade_followup_rows=")

$blockers = [System.Collections.Generic.List[string]]::new()
$requiredEvidence = [System.Collections.Generic.List[string]]::new()
$reviewItems = [System.Collections.Generic.List[string]]::new()

if ($readiness.ExitCode -ne 0) {
    $blockers.Add("DATAFRESHNESS_REPLAY_READINESS_FAILED")
    $requiredEvidence.Add("prepare_data_freshness_replay_evidence_readiness_ssh.ps1 exitCode=0")
}
if ($progression.ExitCode -ne 0) {
    $blockers.Add("BUY_LIKE_CANDIDATE_PROGRESSION_FAILED")
    $requiredEvidence.Add("smoke_buy_like_candidate_progression_ssh.ps1 exitCode=0")
}
foreach ($item in @($dataFreshnessBlockers)) {
    if ($blockers -notcontains [string]$item) {
        $blockers.Add([string]$item)
    }
}
foreach ($item in @($dataFreshnessRequired)) {
    if ($requiredEvidence -notcontains [string]$item) {
        $requiredEvidence.Add([string]$item)
    }
}

if ($buyLikeRows -eq 0) {
    $blockers.Add("NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW")
    $requiredEvidence.Add("fresh BUY-like SIGNAL_EVAL or SIGNAL_BUY candidates")
} elseif ($entrySkipRows -ge [Math]::Max($filterBlockRows, $noTerminalRows) -and $entrySkipRows -gt 0) {
    $reviewItems.Add("ENTRY_SKIP_DOMINATES_BUY_LIKE_CANDIDATE_FLOW")
    $requiredEvidence.Add("EntryDedup/ShadowExecutionIntent row-level review before any entry-policy experiment")
} elseif ($noTerminalRows -ge [Math]::Max($entrySkipRows, $filterBlockRows) -and $noTerminalRows -gt 0) {
    $reviewItems.Add("NO_TERMINAL_FOLLOWUP_REVIEW")
    $requiredEvidence.Add("candidate-to-terminal follow-up RCA")
} elseif ($filterBlockRows -gt 0) {
    $reviewItems.Add("FILTER_BLOCK_DOMINATES_BUY_LIKE_CANDIDATE_FLOW")
    $requiredEvidence.Add("dominant filter-block family review")
}

if ($signalBuyRows + $autotradeRows -eq 0 -and $buyLikeRows -gt 0) {
    $reviewItems.Add("NO_BUY_LIKE_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE")
}

$status = if ($readiness.ExitCode -ne 0 -or $progression.ExitCode -ne 0) {
    "NO_EVIDENCE"
} elseif ($buyLikeRows -eq 0) {
    "PENDING_BUY_LIKE_CANDIDATES"
} elseif ($entrySkipRows -ge [Math]::Max($filterBlockRows, $noTerminalRows) -and $entrySkipRows -gt 0) {
    "READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE"
} elseif ($noTerminalRows -ge [Math]::Max($entrySkipRows, $filterBlockRows) -and $noTerminalRows -gt 0) {
    "READY_FOR_NO_TERMINAL_FOLLOWUP_REVIEW_NOT_LIVE"
} elseif ($filterBlockRows -gt 0) {
    "READY_FOR_FILTER_BLOCK_CANDIDATE_FLOW_REVIEW_NOT_LIVE"
} else {
    "CANDIDATE_FLOW_REVIEW_INCONCLUSIVE"
}

$nextAction = if ($status -eq "READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE") {
    "Run EntryDedup/ShadowExecutionIntent row-level review and TP/SL/OCO shadow feasibility; do not relax EntryDedup/DataFreshness/live policy."
} elseif ($status -eq "READY_FOR_NO_TERMINAL_FOLLOWUP_REVIEW_NOT_LIVE") {
    "Inspect why BUY-like candidates have no terminal follow-up before changing filters or live execution."
} elseif ($status -eq "READY_FOR_FILTER_BLOCK_CANDIDATE_FLOW_REVIEW_NOT_LIVE") {
    "Review dominant filter-block families with missed-opportunity and signal-policy evidence before any policy experiment."
} elseif ($status -eq "PENDING_BUY_LIKE_CANDIDATES") {
    "Wait for new BUY-like candidates or inspect upstream signal generation; do not relax DataFreshnessGuard from missing sample evidence."
} else {
    "Refresh read-only evidence and inspect child outputs before proposing a profit experiment."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_CANDIDATE_FLOW_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    reviewDays = $ReviewDays
    replayIdDays = $ReplayIdDays
    followupHours = $FollowupHours
    dataFreshness = [pscustomobject]@{
        status = $dataFreshnessStatus
        replayCandidateRecommendation = $dataFreshnessRecommendation
        sampleGapRcaRecommendation = $sampleGapRecommendation
        latestRowTime = $latestDataFreshnessRowTime
        latestRowAgeHours = $latestDataFreshnessRowAgeHours
        completeReplayableCandidateRows = $completeReplayableRows
        blockers = @($dataFreshnessBlockers)
        requiredEvidence = @($dataFreshnessRequired)
    }
    buyLikeCandidateFlow = [pscustomobject]@{
        recommendation = $buyLikeRecommendation
        buyLikeCandidateRows = $buyLikeRows
        sampledBuyLikeCandidateRows = $sampledBuyLikeRows
        followupTerminalEventRows = $followupTerminalRows
        noTerminalFollowupRows = $noTerminalRows
        filterBlockFollowupRows = $filterBlockRows
        entrySkipFollowupRows = $entrySkipRows
        signalBuyRows = $signalBuyRows
        autotradeFollowupRows = $autotradeRows
    }
    reviewItems = @($reviewItems)
    blockers = @($blockers)
    requiredEvidence = @($requiredEvidence)
    sourceScripts = @(
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "smoke_buy_like_candidate_progression_ssh.ps1"
    )
    childExitCodes = @{
        dataFreshnessReplayEvidenceReadiness = $readiness.ExitCode
        buyLikeCandidateProgression = $progression.ExitCode
    }
    nextAction = $nextAction
    notAuthorization = "read-only profit candidate-flow review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[profit-candidate-flow-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes DataFreshness replay readiness and BUY-like candidate progression only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_data_freshness_replay_evidence_readiness=prepare_data_freshness_replay_evidence_readiness_ssh.ps1 exitCode=$($readiness.ExitCode)"
Write-Host "source_buy_like_candidate_progression=smoke_buy_like_candidate_progression_ssh.ps1 exitCode=$($progression.ExitCode)"
Write-Host "data_freshness_replay_evidence_readiness_status=$dataFreshnessStatus"
Write-Host "data_freshness_replay_candidate_id_recommendation=$dataFreshnessRecommendation"
Write-Host "data_freshness_sample_gap_rca_recommendation=$sampleGapRecommendation"
Write-Host "latest_data_freshness_row_time=$latestDataFreshnessRowTime"
Write-Host "latest_data_freshness_row_age_hours=$latestDataFreshnessRowAgeHours"
Write-Host "complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host "buy_like_candidate_progression_recommendation=$buyLikeRecommendation"
Write-Host "buy_like_candidate_rows=$buyLikeRows"
Write-Host "sampled_buy_like_candidate_rows=$sampledBuyLikeRows"
Write-Host "followup_terminal_event_rows=$followupTerminalRows"
Write-Host "no_terminal_followup_rows=$noTerminalRows"
Write-Host "filter_block_followup_rows=$filterBlockRows"
Write-Host "entry_skip_followup_rows=$entrySkipRows"
Write-Host "signal_buy_rows=$signalBuyRows"
Write-Host "autotrade_followup_rows=$autotradeRows"
Write-Host ("profit_candidate_flow_review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host ("profit_candidate_flow_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host ("profit_candidate_flow_required_evidence=" + (ConvertTo-Json -Compress @($requiredEvidence)))
Write-Host ("profit_candidate_flow_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "profit_candidate_flow_review_status=$status"
Write-Host "profit_candidate_flow_next_action=$nextAction"
Write-Host "notAuthorization=read-only profit candidate-flow review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[profit-candidate-flow-review-packet] read-only check complete"

if ($RequireActionable -and $status -notin @("READY_FOR_ENTRY_SKIP_CANDIDATE_FLOW_REVIEW_NOT_LIVE", "READY_FOR_NO_TERMINAL_FOLLOWUP_REVIEW_NOT_LIVE", "READY_FOR_FILTER_BLOCK_CANDIDATE_FLOW_REVIEW_NOT_LIVE")) {
    throw "Profit candidate-flow review packet is not actionable: $status"
}
