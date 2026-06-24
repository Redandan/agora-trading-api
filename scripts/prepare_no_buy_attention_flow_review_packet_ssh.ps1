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

function Get-AttentionStrategyDistribution {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $inSection = $false
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -eq "attention_hit_strategy_distribution:") {
            $inSection = $true
            continue
        }
        if (-not $inSection) { continue }
        if ($line -notmatch "^\s+-\s+") { break }
        $match = [regex]::Match($line, "strategy=([^\s]+)\s+interval=([^\s]+)\s+count=([0-9]+)")
        if (-not $match.Success) { continue }
        $items.Add([pscustomobject]@{
            strategyId = $match.Groups[1].Value
            intervalCode = $match.Groups[2].Value
            count = [int]$match.Groups[3].Value
        })
    }
    return @($items)
}

function Get-SignalEvalThresholdGapDistribution {
    param([string]$Text)
    $items = [System.Collections.Generic.List[object]]::new()
    $inSection = $false
    foreach ($line in ($Text -split "`r?`n")) {
        if ($line -eq "signal_eval_threshold_gap_distribution:") {
            $inSection = $true
            continue
        }
        if (-not $inSection) { continue }
        if ($line -notmatch "^\s+-\s+") { break }
        $match = [regex]::Match($line, "strategy=([^\s]+)\s+interval=([^\s]+)\s+indicator=([^\s]+)\s+count=([0-9]+)\s+avg_mih_value=(-?[0-9.]+)\s+avg_buy_threshold=(-?[0-9.]+)\s+avg_buy_gap=(-?[0-9.]+)\s+min_buy_gap=(-?[0-9.]+)")
        if (-not $match.Success) { continue }
        $items.Add([pscustomobject]@{
            strategyId = $match.Groups[1].Value
            intervalCode = $match.Groups[2].Value
            indicator = $match.Groups[3].Value
            count = [int]$match.Groups[4].Value
            avgMihValue = [decimal]::Parse($match.Groups[5].Value, [System.Globalization.CultureInfo]::InvariantCulture)
            avgBuyThreshold = [decimal]::Parse($match.Groups[6].Value, [System.Globalization.CultureInfo]::InvariantCulture)
            avgBuyGap = [decimal]::Parse($match.Groups[7].Value, [System.Globalization.CultureInfo]::InvariantCulture)
            minBuyGap = [decimal]::Parse($match.Groups[8].Value, [System.Globalization.CultureInfo]::InvariantCulture)
        })
    }
    return @($items)
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

$signalEvalNoBuy = Invoke-ReadOnlyScript -ScriptName "smoke_signal_eval_no_buy_generation_ssh.ps1" -Arguments ($commonArgs + @(
    "-ReviewDays", "$ReviewDays",
    "-Limit", "$Limit"
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
$attentionStrategyDistribution = Get-AttentionStrategyDistribution -Text $attention.Text
$attentionMacroWatchOnlyRows = 0
foreach ($item in $attentionStrategyDistribution) {
    if ($item.strategyId -eq "-1" -and $item.intervalCode -eq "N/A") {
        $attentionMacroWatchOnlyRows += [int]$item.count
    }
}
$attentionMacroWatchOnlyDominates = ($attentionRows -gt 0 -and $attentionMacroWatchOnlyRows -eq $attentionRows)
$attentionCandidateInterpretation = if ($attentionMacroWatchOnlyDominates) {
    "ATTENTION_HITS_ARE_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES"
} elseif ($attentionRows -gt 0 -and $attentionMacroWatchOnlyRows -gt 0) {
    "ATTENTION_HITS_MIXED_MACRO_AND_STRATEGY_ROWS"
} elseif ($attentionRows -gt 0) {
    "ATTENTION_HITS_STRATEGY_SCOPED_REVIEW_TERMINAL_FOLLOWUP"
} else {
    "NO_ATTENTION_HITS_IN_REVIEW_WINDOW"
}

$signalEvalNoBuyRecommendation = Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  signal_eval_no_buy_generation_recommendation="
$signalEvalRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  signal_eval_rows=")
$signalEvalBuyLikeRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  buy_like_signal_eval_rows=")
$signalEvalNoBuyRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  no_buy_signal_eval_rows=")
$signalEvalHoldReasonRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  hold_reason_rows=")
$signalEvalV2ContextRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  v2_context_rows=")
$signalEvalStrategyDecisionContextRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  strategy_decision_context_rows=")
$signalEvalExecutionHoldRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  execution_hold_rows=")
$signalEvalMacroRows = Get-IntValue -Value (Get-LastPrefixedValue -Text $signalEvalNoBuy.Text -Prefix "  macro_or_unknown_strategy_rows=")
$signalEvalThresholdGaps = Get-SignalEvalThresholdGapDistribution -Text $signalEvalNoBuy.Text
$signalEvalNearThresholdGaps = @($signalEvalThresholdGaps | Where-Object { $_.minBuyGap -ge 0 -and $_.minBuyGap -le 2 })
$signalEvalClosestThresholdGap = @($signalEvalThresholdGaps | Sort-Object @{ Expression = { [math]::Abs([decimal]$_.minBuyGap) } }, @{ Expression = { -1 * $_.count } } | Select-Object -First 1)

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
if ($signalEvalNoBuy.ExitCode -ne 0) { Add-Unique -List $blockers -Value "SIGNAL_EVAL_NO_BUY_GENERATION_FAILED" }
if ($buyLike.ExitCode -ne 0) { Add-Unique -List $blockers -Value "BUY_LIKE_CANDIDATE_PROGRESSION_FAILED" }

if ($buyLikeRows -eq 0) {
    Add-Unique -List $blockers -Value "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW"
    Add-Unique -List $requiredEvidence -Value "fresh BUY-like SIGNAL_EVAL or SIGNAL_BUY candidates"
}
if ($signalEvalRows -gt 0 -and $signalEvalBuyLikeRows -eq 0) {
    Add-Unique -List $reviewItems -Value "SIGNAL_EVAL_NO_BUY_GENERATION_REVIEW"
    Add-Unique -List $requiredEvidence -Value "signal-eval reason/context distribution must explain why no BUY-like candidates were generated"
}
if ($signalEvalNoBuyRecommendation -eq "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT") {
    Add-Unique -List $reviewItems -Value "SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT"
    Add-Unique -List $requiredEvidence -Value "strategy threshold gap evidence must be reviewed before strategy-threshold or activation changes"
}
if (@($signalEvalNearThresholdGaps).Count -gt 0) {
    Add-Unique -List $reviewItems -Value "SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW"
}
if ($signalEvalRows -eq 0) {
    Add-Unique -List $blockers -Value "NO_SIGNAL_EVAL_IN_REVIEW_WINDOW"
}
if ($attentionRows -gt 0 -and $attentionNoTerminalRows -eq $attentionRows) {
    Add-Unique -List $reviewItems -Value "ATTENTION_HIT_NO_TERMINAL_FOLLOWUP_DOMINATES"
    Add-Unique -List $requiredEvidence -Value "attention-hit rows must map to a strategy/interval terminal follow-up or be explicitly classified as macro/watch-only non-trading evidence"
}
if ($attentionMacroWatchOnlyDominates) {
    Add-Unique -List $reviewItems -Value "ATTENTION_HITS_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES"
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

$status = if ($profitBlocker.ExitCode -ne 0 -or $attention.ExitCode -ne 0 -or $signalEvalNoBuy.ExitCode -ne 0 -or $buyLike.ExitCode -ne 0) {
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

$nextAction = if ($signalEvalNoBuyRecommendation -eq "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT") {
    if ($attentionMacroWatchOnlyDominates) {
        "Treat current ATTENTION_HIT rows as macro/watch-only non-trading evidence, then review strategy threshold gap evidence for why SIGNAL_EVAL rows did not become BUY-like candidates before any strategy-threshold, DataFreshnessGuard, EntryDedup, or live policy change."
    } else {
        "Review strategy threshold gap evidence, especially near-threshold strategies, before any strategy-threshold, DataFreshnessGuard, EntryDedup, or live policy change."
    }
} elseif ($status -eq "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE") {
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
        macroWatchOnlyRows = $attentionMacroWatchOnlyRows
        strategyDistribution = @($attentionStrategyDistribution)
        candidateInterpretation = $attentionCandidateInterpretation
    }
    signalEvalNoBuyGeneration = [pscustomobject]@{
        recommendation = $signalEvalNoBuyRecommendation
        signalEvalRows = $signalEvalRows
        buyLikeSignalEvalRows = $signalEvalBuyLikeRows
        noBuySignalEvalRows = $signalEvalNoBuyRows
        holdReasonRows = $signalEvalHoldReasonRows
        v2ContextRows = $signalEvalV2ContextRows
        strategyDecisionContextRows = $signalEvalStrategyDecisionContextRows
        executionHoldRows = $signalEvalExecutionHoldRows
        macroOrUnknownStrategyRows = $signalEvalMacroRows
        thresholdGapDistribution = @($signalEvalThresholdGaps)
        nearThresholdGapCount = @($signalEvalNearThresholdGaps).Count
        closestThresholdGap = if ($signalEvalClosestThresholdGap) { $signalEvalClosestThresholdGap[0] } else { $null }
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
        signalEvalNoBuyGeneration = $signalEvalNoBuy.ExitCode
        buyLikeCandidateProgression = $buyLike.ExitCode
    }
    nextAction = $nextAction
    notAuthorization = "read-only no-buy attention flow review packet only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
}

Write-Host "[no-buy-attention-flow-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; invokes DataFreshness profit blocker brief, ATTENTION_HIT progression, SIGNAL_EVAL no-buy generation, and BUY-like progression only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_data_freshness_profit_blocker_brief=prepare_data_freshness_profit_blocker_brief_ssh.ps1 exitCode=$($profitBlocker.ExitCode)"
Write-Host "source_attention_hit_progression=smoke_attention_hit_progression_ssh.ps1 exitCode=$($attention.ExitCode)"
Write-Host "source_signal_eval_no_buy_generation=smoke_signal_eval_no_buy_generation_ssh.ps1 exitCode=$($signalEvalNoBuy.ExitCode)"
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
Write-Host "attention_macro_watch_only_rows=$attentionMacroWatchOnlyRows"
Write-Host "attention_candidate_interpretation=$attentionCandidateInterpretation"
Write-Host ("attention_strategy_distribution=" + (ConvertTo-Json -Compress @($attentionStrategyDistribution)))
Write-Host "signal_eval_no_buy_generation_recommendation=$signalEvalNoBuyRecommendation"
Write-Host "signal_eval_rows=$signalEvalRows"
Write-Host "signal_eval_buy_like_rows=$signalEvalBuyLikeRows"
Write-Host "signal_eval_no_buy_rows=$signalEvalNoBuyRows"
Write-Host "signal_eval_hold_reason_rows=$signalEvalHoldReasonRows"
Write-Host "signal_eval_v2_context_rows=$signalEvalV2ContextRows"
Write-Host "signal_eval_strategy_decision_context_rows=$signalEvalStrategyDecisionContextRows"
Write-Host "signal_eval_execution_hold_rows=$signalEvalExecutionHoldRows"
Write-Host "signal_eval_macro_or_unknown_strategy_rows=$signalEvalMacroRows"
Write-Host "signal_eval_threshold_gap_count=$(@($signalEvalThresholdGaps).Count)"
Write-Host "signal_eval_near_threshold_gap_count=$(@($signalEvalNearThresholdGaps).Count)"
if ($signalEvalClosestThresholdGap) {
    Write-Host "signal_eval_closest_threshold_gap_strategy=$($signalEvalClosestThresholdGap[0].strategyId)"
    Write-Host "signal_eval_closest_threshold_gap_interval=$($signalEvalClosestThresholdGap[0].intervalCode)"
    Write-Host "signal_eval_closest_threshold_gap_indicator=$($signalEvalClosestThresholdGap[0].indicator)"
    Write-Host "signal_eval_closest_threshold_gap_min_buy_gap=$($signalEvalClosestThresholdGap[0].minBuyGap)"
}
Write-Host ("signal_eval_threshold_gap_distribution=" + (ConvertTo-Json -Compress @($signalEvalThresholdGaps)))
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
