param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 7,
    [int]$FollowupHours = 6,
    [int]$Limit = 10,
    [int]$MaxRows = 500,
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
        throw "$Name contains unsupported characters for no-buy attention flow packet arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-IntValue {
    param([string]$Value)
    $parsed = 0
    if ([int]::TryParse($Value, [ref]$parsed)) { return $parsed }
    return 0
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
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
        throw "Unable to find powershell or pwsh for no-buy attention flow packet."
    }

    Write-Host "[no-buy-attention-flow-review-packet] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[no-buy-attention-flow-review-packet] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
if ($ReviewDays -lt 1 -or $ReviewDays -gt 30) { throw "ReviewDays must be between 1 and 30." }
if ($FollowupHours -lt 1 -or $FollowupHours -gt 48) { throw "FollowupHours must be between 1 and 48." }
if ($Limit -lt 1 -or $Limit -gt 50) { throw "Limit must be between 1 and 50." }
if ($MaxRows -lt 1 -or $MaxRows -gt 2000) { throw "MaxRows must be between 1 and 2000." }

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

$profitBlocker = Invoke-ReadOnlyScript -ScriptName "prepare_data_freshness_profit_blocker_brief_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", "$([Math]::Max($ReviewDays, 14))",
    "-SampleGapReviewDays", "$ReviewDays",
    "-SampleGapLongDays", "$([Math]::Max($ReviewDays, 30))",
    "-SampleGapLimit", "$Limit",
    "-Limit", "$([Math]::Min([Math]::Max($MaxRows, 1), 1000))"
))

$attention = Invoke-ReadOnlyScript -ScriptName "smoke_attention_hit_progression_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", "$ReviewDays",
    "-FollowupHours", "$FollowupHours",
    "-Limit", "$Limit",
    "-MaxAttentionRows", "$MaxRows"
))

$buyLike = Invoke-ReadOnlyScript -ScriptName "smoke_buy_like_candidate_progression_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", "$ReviewDays",
    "-FollowupHours", "$FollowupHours",
    "-Limit", "$Limit",
    "-MaxCandidateRows", "$MaxRows"
))

$profitBlockerStatus = Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "data_freshness_profit_blocker_status="
$sampleGapRcaRecommendation = Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "data_freshness_sample_gap_rca_recommendation="
$sampleGapBuyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "sample_gap_buy_like_rows_$($ReviewDays)d_review=")
$sampleGapAttentionRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "sample_gap_attention_hit_rows_$($ReviewDays)d_review=")
$sampleGapFilterBlockRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "sample_gap_filter_block_rows_$($ReviewDays)d_review=")
$sampleGapDataFreshnessRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "sample_gap_data_freshness_rows_$($ReviewDays)d_review=")
$dataFreshnessLatestRowAgeHours = Get-LastPrefixedValue -Text $profitBlocker.Text -Prefix "sample_gap_latest_data_freshness_row_age_hours="

$attentionRecommendation = Get-LastPrefixedValue -Text $attention.Text -Prefix "  attention_hit_progression_recommendation="
$attentionRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  attention_hit_rows=")
$attentionNoTerminalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  no_terminal_followup_rows=")
$attentionFilterRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  filter_block_followup_rows=")
$attentionEntrySkipRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  entry_skip_followup_rows=")
$attentionSignalBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  signal_buy_followup_rows=")
$attentionAutotradeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $attention.Text -Prefix "  autotrade_followup_rows=")

$buyLikeRecommendation = Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  buy_like_candidate_progression_recommendation="
$buyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  buy_like_candidate_rows=")
$buyLikeNoTerminalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  no_terminal_followup_rows=")
$buyLikeFilterRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  filter_block_followup_rows=")
$buyLikeEntrySkipRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  entry_skip_followup_rows=")
$buyLikeSignalBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  signal_buy_rows=")
$buyLikeAutotradeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $buyLike.Text -Prefix "  autotrade_followup_rows=")

$blockers = [System.Collections.Generic.List[string]]::new()
$reviewItems = [System.Collections.Generic.List[string]]::new()
$requiredEvidence = [System.Collections.Generic.List[string]]::new()

if ($profitBlocker.ExitCode -ne 0) { Add-Unique -List $blockers -Value "DATAFRESHNESS_PROFIT_BLOCKER_BRIEF_FAILED" }
if ($attention.ExitCode -ne 0) { Add-Unique -List $blockers -Value "ATTENTION_HIT_PROGRESSION_FAILED" }
if ($buyLike.ExitCode -ne 0) { Add-Unique -List $blockers -Value "BUY_LIKE_CANDIDATE_PROGRESSION_FAILED" }

if ($buyLikeRows -eq 0) {
    Add-Unique -List $blockers -Value "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW"
    Add-Unique -List $requiredEvidence -Value "fresh BUY-like SIGNAL_EVAL or SIGNAL_BUY candidates"
}
if ($attentionRows -gt 0 -and $attentionNoTerminalRows -eq $attentionRows) {
    Add-Unique -List $reviewItems -Value "ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES"
    Add-Unique -List $requiredEvidence -Value "attention-hit rows must map to a strategy/interval terminal follow-up or be excluded from trading-candidate evidence"
}
if ($sampleGapRcaRecommendation -eq "NO_RECENT_BUY_STYLE_CANDIDATES") {
    Add-Unique -List $reviewItems -Value "SIGNAL_GENERATION_OR_ATTENTION_PIPELINE_REVIEW"
}
if ($sampleGapDataFreshnessRows -eq 0) {
    Add-Unique -List $blockers -Value "NO_RECENT_DATAFRESHNESS_ROWS"
}
if ($attentionRows -gt 0 -and $attentionSignalBuyRows + $attentionAutotradeRows -eq 0) {
    Add-Unique -List $reviewItems -Value "NO_ATTENTION_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE"
}

$status = if ($profitBlocker.ExitCode -ne 0 -or $attention.ExitCode -ne 0 -or $buyLike.ExitCode -ne 0) {
    "NO_EVIDENCE"
} elseif ($buyLikeRows -eq 0 -and $attentionRows -gt 0 -and $attentionNoTerminalRows -eq $attentionRows) {
    "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE"
} elseif ($buyLikeRows -eq 0) {
    "PENDING_BUY_LIKE_CANDIDATES"
} elseif ($attentionRows -gt 0) {
    "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE"
} else {
    "NO_ATTENTION_OR_BUY_LIKE_FLOW_EVIDENCE"
}

$nextAction = if ($status -eq "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE") {
    "Review why ATTENTION_HIT rows are macro/non-strategy rows with no terminal follow-up and why no BUY-like candidates were generated; do not relax DataFreshnessGuard, EntryDedup, or live policy from this packet."
} elseif ($status -eq "PENDING_BUY_LIKE_CANDIDATES") {
    "Wait for fresh BUY-like candidates or inspect signal-generation thresholds before any entry/filter policy experiment."
} elseif ($status -eq "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE") {
    "Review attention-hit terminal follow-up distribution before routing to EntryDedup, filter-block, or strategy activation work."
} else {
    "Refresh read-only evidence and inspect child outputs before proposing a no-buy or signal-generation experiment."
}

$packet = [pscustomobject]@{
    packetType = "NO_BUY_ATTENTION_FLOW_REVIEW_PACKET"
    status = $status
    symbol = $Symbol
    reviewDays = $ReviewDays
    followupHours = $FollowupHours
    dataFreshnessProfitBlockerStatus = $profitBlockerStatus
    sampleGapRcaRecommendation = $sampleGapRcaRecommendation
    sampleGapBuyLikeRows = $sampleGapBuyLikeRows
    sampleGapAttentionHitRows = $sampleGapAttentionRows
    sampleGapFilterBlockRows = $sampleGapFilterBlockRows
    sampleGapDataFreshnessRows = $sampleGapDataFreshnessRows
    dataFreshnessLatestRowAgeHours = $dataFreshnessLatestRowAgeHours
    attentionFlow = [pscustomobject]@{
        recommendation = $attentionRecommendation
        attentionHitRows = $attentionRows
        noTerminalFollowupRows = $attentionNoTerminalRows
        filterBlockFollowupRows = $attentionFilterRows
        entrySkipFollowupRows = $attentionEntrySkipRows
        signalBuyFollowupRows = $attentionSignalBuyRows
        autotradeFollowupRows = $attentionAutotradeRows
    }
    buyLikeFlow = [pscustomobject]@{
        recommendation = $buyLikeRecommendation
        buyLikeCandidateRows = $buyLikeRows
        noTerminalFollowupRows = $buyLikeNoTerminalRows
        filterBlockFollowupRows = $buyLikeFilterRows
        entrySkipFollowupRows = $buyLikeEntrySkipRows
        signalBuyRows = $buyLikeSignalBuyRows
        autotradeFollowupRows = $buyLikeAutotradeRows
    }
    reviewItems = @($reviewItems)
    blockers = @($blockers)
    requiredEvidence = @($requiredEvidence)
    childExitCodes = @{
        dataFreshnessProfitBlockerBrief = $profitBlocker.ExitCode
        attentionHitProgression = $attention.ExitCode
        buyLikeCandidateProgression = $buyLike.ExitCode
    }
    nextAction = $nextAction
    notAuthorization = "read-only no-buy attention flow review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[no-buy-attention-flow-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes DataFreshness profit blocker brief, ATTENTION_HIT progression, and BUY-like progression only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_data_freshness_profit_blocker_brief=prepare_data_freshness_profit_blocker_brief_ssh.ps1 exitCode=$($profitBlocker.ExitCode)"
Write-Host "source_attention_hit_progression=smoke_attention_hit_progression_ssh.ps1 exitCode=$($attention.ExitCode)"
Write-Host "source_buy_like_candidate_progression=smoke_buy_like_candidate_progression_ssh.ps1 exitCode=$($buyLike.ExitCode)"
Write-Host "data_freshness_profit_blocker_status=$profitBlockerStatus"
Write-Host "data_freshness_sample_gap_rca_recommendation=$sampleGapRcaRecommendation"
Write-Host "sample_gap_buy_like_rows_$($ReviewDays)d_review=$sampleGapBuyLikeRows"
Write-Host "sample_gap_attention_hit_rows_$($ReviewDays)d_review=$sampleGapAttentionRows"
Write-Host "sample_gap_filter_block_rows_$($ReviewDays)d_review=$sampleGapFilterBlockRows"
Write-Host "sample_gap_data_freshness_rows_$($ReviewDays)d_review=$sampleGapDataFreshnessRows"
Write-Host "data_freshness_latest_row_age_hours=$dataFreshnessLatestRowAgeHours"
Write-Host "attention_hit_progression_recommendation=$attentionRecommendation"
Write-Host "attention_hit_rows=$attentionRows"
Write-Host "attention_no_terminal_followup_rows=$attentionNoTerminalRows"
Write-Host "attention_filter_block_followup_rows=$attentionFilterRows"
Write-Host "attention_entry_skip_followup_rows=$attentionEntrySkipRows"
Write-Host "attention_signal_buy_followup_rows=$attentionSignalBuyRows"
Write-Host "attention_autotrade_followup_rows=$attentionAutotradeRows"
Write-Host "buy_like_candidate_progression_recommendation=$buyLikeRecommendation"
Write-Host "buy_like_candidate_rows=$buyLikeRows"
Write-Host "buy_like_no_terminal_followup_rows=$buyLikeNoTerminalRows"
Write-Host "buy_like_filter_block_followup_rows=$buyLikeFilterRows"
Write-Host "buy_like_entry_skip_followup_rows=$buyLikeEntrySkipRows"
Write-Host "buy_like_signal_buy_rows=$buyLikeSignalBuyRows"
Write-Host "buy_like_autotrade_followup_rows=$buyLikeAutotradeRows"
Write-Host ("no_buy_attention_flow_review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host ("no_buy_attention_flow_blockers=" + (ConvertTo-Json -Compress @($blockers)))
Write-Host ("no_buy_attention_flow_required_evidence=" + (ConvertTo-Json -Compress @($requiredEvidence)))
Write-Host ("no_buy_attention_flow_review_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "no_buy_attention_flow_review_status=$status"
Write-Host "no_buy_attention_flow_next_action=$nextAction"
Write-Host "notAuthorization=read-only no-buy attention flow review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[no-buy-attention-flow-review-packet] read-only check complete"

if ($RequireReviewReady -and $status -notin @("READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE", "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE")) {
    throw "No-buy attention flow review packet is not review-ready: $status"
}
