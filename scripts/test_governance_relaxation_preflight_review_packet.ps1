Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_governance_relaxation_preflight_review_packet.ps1"
$sourcePath = Join-Path $PSScriptRoot "prepare_governance_relaxation_review_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$sourceText = Get-Content -Raw -LiteralPath $sourcePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[governance-relaxation-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE",
        "PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW",
        "PREPARE_REVIEW_ONLY_GOVERNANCE_SHADOW_REVIEW",
        "BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE",
        "governance_relaxation_preflight_review_packet",
        "governance_relaxation_preflight_status",
        "source_missing_requirements",
        "source_next_action",
        "NoBuyAttentionLogPath",
        "no_buy_attention_status",
        "no_buy_attention_ready",
        "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE",
        "no_buy_attention_next_action",
        "no_buy_signal_eval_near_threshold_gap_count",
        "noBuyAttentionReady",
        "noBuySignalEvalNearThresholdGapCount",
        "governance_relaxation_review_allowed=true",
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "staged_add_execution_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only governance relaxation preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "governance relaxation preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GOVERNANCE_RELAXATION_REVIEW",
        "REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE",
        "governance_relaxation_review_packet"
    )) {
    Assert-Contains -Name "governance relaxation source supports preflight" -Text $sourceText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_governance_relaxation_preflight_review_packet.ps1",
        "GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET",
        "governance_relaxation_preflight_review_packet",
        "Governance relaxation preflight review packet",
        "READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention governance relaxation preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $sourcePacket = [pscustomobject]@{
        packetType = "GOVERNANCE_RELAXATION_REVIEW"
        status = "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
        symbol = "BTCUSDT"
        signalPolicyClear = "false"
        governanceMode = "TOO_STRICT"
        missedOpportunityStatus = "WARN"
        relaxationCandidateCount = 2
        relaxationCandidates = @(
            [pscustomobject]@{ blocker = "NO_BUY_REASON_FILTER_TOO_STRICT"; detail = "review only" }
        )
        shadowGovernanceReviewAllowed = $false
        livePolicyChangeAllowed = $false
        tinyLiveOrderAllowed = $false
        missingRequirements = @("signalPolicyClear=true before governance relaxation review can be marked ready")
        nextAction = "Review candidates but keep policy unchanged."
    }
    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[governance-relaxation-review-packet] read-only packet",
        "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only",
        "governance_relaxation_review_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        ("governance_relaxation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $sourcePacket)),
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "notAuthorization=read-only governance relaxation review packet only"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for governance relaxation preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewLogPath $tempLogPath -NoBuyAttentionLogPath "$tempLogPath.no-buy-missing" -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "Governance relaxation preflight failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "source_review_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE",
            "source_signal_policy_clear=false",
            "source_governance_mode=TOO_STRICT",
            "source_missed_opportunity_status=WARN",
            "source_relaxation_candidate_count=2",
            "source_shadow_governance_review_allowed=false",
            "governance_relaxation_preflight_decision=PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW",
            "governance_relaxation_preflight_status=READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE",
            '"packetType":"GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET"',
            '"preflightDecision":"PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW"',
            '"governanceRelaxationReviewAllowed":true',
            '"livePolicyChangeAllowed":false',
            '"tinyLiveOrderAllowed":false',
            '"entryDedupPolicyChangeAllowed":false',
            '"dataFreshnessPolicyChangeAllowed":false',
            '"telegramSendAllowed":false',
            "live_policy_change_allowed=false",
            "tiny_live_order_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only governance relaxation preflight review packet only"
        )) {
        Assert-Contains -Name "governance relaxation preflight temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Governance relaxation preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) { Remove-Item -LiteralPath $tempLogPath -Force }
}

$blockedLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-blocked-" + [guid]::NewGuid().ToString("N") + ".log")
$blockedNoBuyPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-no-buy-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $blockedSourcePacket = [pscustomobject]@{
        packetType = "GOVERNANCE_RELAXATION_REVIEW"
        status = "NO_EVIDENCE"
        symbol = "BTCUSDT"
        signalPolicyClear = "false"
        governanceMode = "INSUFFICIENT_DATA"
        missedOpportunityStatus = "PASS"
        relaxationCandidateCount = 0
        relaxationCandidates = @()
        shadowGovernanceReviewAllowed = $false
        livePolicyChangeAllowed = $false
        tinyLiveOrderAllowed = $false
        missingRequirements = @(
            "DataFreshness current snapshot clean",
            "governance relaxation candidates present"
        )
        nextAction = "Recent DataFreshnessGuard rows are absent because no BUY-style candidates appeared in the sample-gap review window; review signal generation and no-buy/attention progression before any DataFreshness policy review."
    }
    $blockedNoBuyPacket = [pscustomobject]@{
        packetType = "NO_BUY_ATTENTION_FLOW_REVIEW_PACKET"
        status = "READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        attentionFlow = [pscustomobject]@{
            candidateInterpretation = "ATTENTION_HITS_ARE_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES"
        }
        signalEvalNoBuyGeneration = [pscustomobject]@{
            recommendation = "NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT"
            nearThresholdGapCount = 1
            closestThresholdGap = [pscustomobject]@{
                strategyId = "574"
                intervalCode = "1h"
                indicator = "market_entropy_index"
                minBuyGap = 1.0000
            }
        }
        reviewItems = @(
            "SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT",
            "SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW",
            "ATTENTION_HITS_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES"
        )
        blockers = @(
            "NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW",
            "NO_RECENT_DATAFRESHNESS_ROWS"
        )
        nextAction = "Treat current ATTENTION_HIT rows as macro/watch-only non-trading evidence, then review strategy threshold gap evidence before governance relaxation."
    }
    Set-Content -LiteralPath $blockedLogPath -Encoding UTF8 -Value @(
        "[governance-relaxation-review-packet] read-only packet",
        "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only",
        "governance_relaxation_review_packet_status=NO_EVIDENCE",
        ("governance_relaxation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $blockedSourcePacket)),
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "notAuthorization=read-only governance relaxation review packet only"
    )
    Set-Content -LiteralPath $blockedNoBuyPath -Encoding UTF8 -Value @(
        "[no-buy-attention-flow-review-packet] read-only packet",
        "scope=READ_ONLY; no mutation",
        "no_buy_attention_flow_review_status=READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE",
        ("no_buy_attention_flow_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $blockedNoBuyPacket)),
        "notAuthorization=read-only no-buy attention flow review packet only"
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewLogPath $blockedLogPath -NoBuyAttentionLogPath $blockedNoBuyPath 2>&1
        $blockedExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $blockedText = ($blockedOutput | Out-String)
    if ($blockedExitCode -ne 0) {
        throw "Governance relaxation preflight failed blocked temp-log reuse:`n$blockedText"
    }
    foreach ($marker in @(
            "source_review_packet_status=NO_EVIDENCE",
            "source_signal_policy_clear=false",
            "source_governance_mode=INSUFFICIENT_DATA",
            "source_missed_opportunity_status=PASS",
            "source_relaxation_candidate_count=0",
            "source_shadow_governance_review_allowed=false",
            'source_missing_requirements=["DataFreshness current snapshot clean","governance relaxation candidates present"]',
            "source_next_action=Recent DataFreshnessGuard rows are absent because no BUY-style candidates appeared in the sample-gap review window; review signal generation and no-buy/attention progression before any DataFreshness policy review.",
            "no_buy_attention_status=READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE",
            "no_buy_attention_ready=true",
            "no_buy_attention_next_action=Treat current ATTENTION_HIT rows as macro/watch-only non-trading evidence, then review strategy threshold gap evidence before governance relaxation.",
            'no_buy_attention_review_items=["SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT","SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW","ATTENTION_HITS_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES"]',
            'no_buy_attention_blockers=["NO_BUY_LIKE_CANDIDATES_IN_REVIEW_WINDOW","NO_RECENT_DATAFRESHNESS_ROWS"]',
            "no_buy_signal_eval_recommendation=NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT",
            "no_buy_signal_eval_near_threshold_gap_count=1",
            '"strategyId":"574"',
            "no_buy_attention_candidate_interpretation=ATTENTION_HITS_ARE_MACRO_WATCH_ONLY_NOT_TRADING_CANDIDATES",
            "governance_relaxation_preflight_decision=BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE",
            "governance_relaxation_preflight_status=NOT_READY",
            "governance_relaxation_preflight_next_action=Treat current ATTENTION_HIT rows as macro/watch-only non-trading evidence, then review strategy threshold gap evidence before governance relaxation.",
            '"sourceReviewPacketStatus":"NO_EVIDENCE"',
            '"noBuyAttentionStatus":"READY_FOR_ATTENTION_NO_BUY_FLOW_REVIEW_NOT_LIVE"',
            '"noBuyAttentionReady":true',
            '"noBuySignalEvalRecommendation":"NO_BUY_LIKE_SIGNAL_EVAL_STRATEGY_THRESHOLDS_NOT_HIT"',
            '"noBuySignalEvalNearThresholdGapCount":1',
            '"sourceMissingRequirements":["DataFreshness current snapshot clean","governance relaxation candidates present"]',
            '"preflightDecision":"BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE"',
            '"livePolicyChangeAllowed":false',
            '"tinyLiveOrderAllowed":false',
            "live_policy_change_allowed=false",
            "tiny_live_order_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only governance relaxation preflight review packet only"
        )) {
        Assert-Contains -Name "governance relaxation preflight blocked source routing" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
    if ($blockedText -match "governance_relaxation_preflight_next_action=Refresh the governance relaxation review packet") {
        throw "Governance relaxation preflight incorrectly routed parsed NO_EVIDENCE packet to refresh-only action:`n$blockedText"
    }
    if ($blockedText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Governance relaxation preflight unexpectedly invoked SSH or a fresh child run for blocked source path:`n$blockedText"
    }
} finally {
    if (Test-Path -LiteralPath $blockedLogPath) { Remove-Item -LiteralPath $blockedLogPath -Force }
    if (Test-Path -LiteralPath $blockedNoBuyPath) { Remove-Item -LiteralPath $blockedNoBuyPath -Force }
}

$currentStatusBlockedLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-current-status-blocked-" + [guid]::NewGuid().ToString("N") + ".log")
$currentStatusNoBuyPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-current-status-no-buy-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $currentStatusSourcePacket = [pscustomobject]@{
        packetType = "GOVERNANCE_RELAXATION_REVIEW"
        status = "NO_EVIDENCE"
        symbol = "BTCUSDT"
        signalPolicyClear = "false"
        governanceMode = "INSUFFICIENT_DATA"
        missedOpportunityStatus = "PASS"
        relaxationCandidateCount = 0
        relaxationCandidates = @()
        shadowGovernanceReviewAllowed = $false
        livePolicyChangeAllowed = $false
        tinyLiveOrderAllowed = $false
        missingRequirements = @(
            "DataFreshness current snapshot clean",
            "governance relaxation candidates present",
            "signalPolicyClear=true before governance relaxation review can be marked ready",
            "governance drift resolved"
        )
        nextAction = "Recent DataFreshnessGuard rows are absent; review no-buy attention flow first."
    }
    $currentStatusNoBuyPacket = [pscustomobject]@{
        packetType = "NO_BUY_ATTENTION_FLOW_REVIEW_PACKET"
        status = "READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        attentionFlow = [pscustomobject]@{
            candidateInterpretation = "ATTENTION_HITS_MIXED_MACRO_AND_STRATEGY_ROWS"
        }
        signalEvalNoBuyGeneration = [pscustomobject]@{
            recommendation = "BUY_LIKE_SIGNAL_EVAL_PRESENT_REVIEW_PROGRESS_PATH"
            nearThresholdGapCount = 1
            closestThresholdGap = [pscustomobject]@{
                strategyId = "574"
                intervalCode = "1h"
                indicator = "market_entropy_index"
                minBuyGap = 1.0000
            }
        }
        reviewItems = @(
            "SIGNAL_EVAL_NEAR_THRESHOLD_GAP_REVIEW",
            "NO_ATTENTION_ROWS_REACHED_SIGNAL_BUY_OR_AUTOTRADE"
        )
        blockers = @("NO_RECENT_DATAFRESHNESS_ROWS")
        nextAction = "Review attention-hit terminal follow-up distribution before routing to EntryDedup, filter-block, or strategy activation work."
    }
    Set-Content -LiteralPath $currentStatusBlockedLogPath -Encoding UTF8 -Value @(
        "[governance-relaxation-review-packet] read-only packet",
        "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only",
        "governance_relaxation_review_packet_status=NO_EVIDENCE",
        ("governance_relaxation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $currentStatusSourcePacket)),
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "notAuthorization=read-only governance relaxation review packet only"
    )
    Set-Content -LiteralPath $currentStatusNoBuyPath -Encoding UTF8 -Value @(
        "[no-buy-attention-flow-review-packet] read-only packet",
        "scope=READ_ONLY; no mutation",
        "no_buy_attention_flow_review_status=READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE",
        ("no_buy_attention_flow_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $currentStatusNoBuyPacket)),
        "notAuthorization=read-only no-buy attention flow review packet only"
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $currentStatusOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewLogPath $currentStatusBlockedLogPath -NoBuyAttentionLogPath $currentStatusNoBuyPath 2>&1
        $currentStatusExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $currentStatusText = ($currentStatusOutput | Out-String)
    if ($currentStatusExitCode -ne 0) {
        throw "Governance relaxation preflight failed current no-buy status routing:`n$currentStatusText"
    }
    foreach ($marker in @(
            "no_buy_attention_status=READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE",
            "no_buy_attention_ready=true",
            "source_relaxation_candidate_count=0",
            "governance_relaxation_preflight_decision=BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE",
            "governance_relaxation_preflight_status=NOT_READY",
            "governance_relaxation_preflight_next_action=Review attention-hit terminal follow-up distribution before routing to EntryDedup, filter-block, or strategy activation work.",
            '"noBuyAttentionStatus":"READY_FOR_ATTENTION_FLOW_REVIEW_NOT_LIVE"',
            '"noBuyAttentionReady":true',
            '"sourceReviewPacketStatus":"NO_EVIDENCE"',
            '"sourceRelaxationCandidateCount":0',
            '"preflightDecision":"BLOCKED_SOURCE_GOVERNANCE_RELAXATION_EVIDENCE"',
            '"livePolicyChangeAllowed":false',
            '"tinyLiveOrderAllowed":false',
            "order_allowed=false",
            "telegram_send_allowed=false"
        )) {
        Assert-Contains -Name "governance relaxation preflight current no-buy status routing" -Text $currentStatusText -Pattern ([regex]::Escape($marker))
    }
    if ($currentStatusText -match "governance_relaxation_preflight_status=READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE") {
        throw "Governance relaxation preflight incorrectly became ready when source candidates were absent:`n$currentStatusText"
    }
    if ($currentStatusText -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Governance relaxation preflight unexpectedly invoked SSH or a fresh child run for current no-buy status path:`n$currentStatusText"
    }
} finally {
    if (Test-Path -LiteralPath $currentStatusBlockedLogPath) { Remove-Item -LiteralPath $currentStatusBlockedLogPath -Force }
    if (Test-Path -LiteralPath $currentStatusNoBuyPath) { Remove-Item -LiteralPath $currentStatusNoBuyPath -Force }
}

Write-Host "[governance-relaxation-preflight-review-packet-test] OK"
