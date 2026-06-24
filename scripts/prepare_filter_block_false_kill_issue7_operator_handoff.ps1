param(
    [string]$SourceLog = "target/profit-review/filter-block-false-kill-issue7-latest.log",
    [string]$ObservationLog = "target/profit-review/issue7-df-replay-observation-latest.log",
    [string]$ReadinessLogPath = "target/profit-review/data-freshness-replay-evidence-readiness-refresh.log",
    [string]$Symbol = "BTCUSDT",
    [int]$MaxAgeMinutes = 180,
    [switch]$RequireActionable
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { [void]$List.Add($Value) }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for issue #7 operator handoff arguments."
}

$closeScript = Join-Path $PSScriptRoot "prepare_filter_block_false_kill_issue7_close_readiness.ps1"
$preflightScript = Join-Path $PSScriptRoot "prepare_data_freshness_collector_activation_preflight_review_packet.ps1"
if (-not (Test-Path -LiteralPath $closeScript)) { throw "Missing issue #7 close-readiness script: $closeScript" }
if (-not (Test-Path -LiteralPath $preflightScript)) { throw "Missing DataFreshness collector activation preflight script: $preflightScript" }

$closeOutput = & $closeScript -SourceLog $SourceLog -ObservationLog $ObservationLog -MaxAgeMinutes $MaxAgeMinutes *>&1
$closeText = ($closeOutput | Out-String -Width 4096)
$closeJson = Get-LastPrefixedValue -Text $closeText -Prefix "issue7_close_readiness_packet="
$closePacket = $null
if (-not [string]::IsNullOrWhiteSpace($closeJson)) {
    $closePacket = $closeJson | ConvertFrom-Json -ErrorAction Stop
}

$preflightOutput = & $preflightScript -ReadinessLogPath $ReadinessLogPath -Symbol $Symbol *>&1
$preflightText = ($preflightOutput | Out-String -Width 4096)
$preflightJson = Get-LastPrefixedValue -Text $preflightText -Prefix "data_freshness_collector_activation_preflight_review_packet="
$preflightPacket = $null
if (-not [string]::IsNullOrWhiteSpace($preflightJson)) {
    $preflightPacket = $preflightJson | ConvertFrom-Json -ErrorAction Stop
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($null -eq $closePacket) { Add-MissingRequirement -List $missingRequirements -Value "issue #7 close-readiness packet valid JSON" }
if ($null -eq $preflightPacket) { Add-MissingRequirement -List $missingRequirements -Value "DataFreshness collector activation preflight packet valid JSON" }

$closeAllowed = $false
$closeStatus = ""
$preflightStatus = ""
$preflightDecision = ""
$evidenceCollectorReviewAllowed = $false
if ($null -ne $closePacket) {
    $closeAllowed = [bool]$closePacket.closeAllowed
    $closeStatus = [string]$closePacket.status
}
if ($null -ne $preflightPacket) {
    $preflightStatus = [string]$preflightPacket.status
    $preflightDecision = [string]$preflightPacket.preflightDecision
    if ($null -ne $preflightPacket.reviewEnvelope) {
        $evidenceCollectorReviewAllowed = [bool]$preflightPacket.reviewEnvelope.evidenceOnlyCollectorReviewAllowed
    }
}

if ($null -ne $closePacket -and [bool]$closePacket.liveRelaxationAllowed) {
    Add-MissingRequirement -List $missingRequirements -Value "issue #7 close packet remains not live relaxation"
}
if ($null -ne $preflightPacket) {
    if ($preflightStatus -ne "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_PREFLIGHT_REVIEW_NOT_LIVE") {
        Add-MissingRequirement -List $missingRequirements -Value "DataFreshness collector activation preflight ready"
    }
    if ($preflightDecision -ne "PREPARE_REVIEW_ONLY_EVIDENCE_COLLECTOR_ACTIVATION") {
        Add-MissingRequirement -List $missingRequirements -Value "preflight decision prepares review-only evidence collector activation"
    }
    if (-not $evidenceCollectorReviewAllowed) {
        Add-MissingRequirement -List $missingRequirements -Value "evidence-only collector review allowed"
    }
    if ([bool]$preflightPacket.reviewEnvelope.collectorActivationAllowed -or
        [bool]$preflightPacket.reviewEnvelope.deployOrEnvChangeAllowed -or
        [bool]$preflightPacket.reviewEnvelope.orderAllowed -or
        [bool]$preflightPacket.reviewEnvelope.liveTradingAllowed) {
        Add-MissingRequirement -List $missingRequirements -Value "preflight remains review-only with no live/env/order authorization"
    }
}

$status = "BLOCKED_REPLAY_EVIDENCE_AND_COLLECTOR_REVIEW_NOT_READY"
$handoffDecision = "REFRESH_ISSUE7_EVIDENCE"
$nextAction = "Refresh issue #7 close-readiness and DataFreshness collector activation preflight evidence."
if ($closeAllowed) {
    $status = "READY_TO_CLOSE_NOT_LIVE_RELAXATION"
    $handoffDecision = "CLOSE_ISSUE_REVIEW_ONLY"
    $nextAction = "Close issue #7 as replay evidence ready; live relaxation still requires a separate authorization gate."
} elseif ($missingRequirements.Count -eq 0) {
    $status = "READY_FOR_EVIDENCE_COLLECTOR_REVIEW_NOT_CLOSEABLE"
    $handoffDecision = "PREPARE_SEPARATE_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW"
    $nextAction = "Keep issue #7 open and attach this handoff to a separate evidence-only collector activation review; require explicit deploy/env authorization before any production change."
}

$packet = [ordered]@{
    packetType = "ISSUE7_OPERATOR_HANDOFF_PACKET"
    status = $status
    handoffDecision = $handoffDecision
    symbol = $Symbol
    closeAllowed = $closeAllowed
    liveRelaxationAllowed = $false
    sourceCloseReadinessStatus = $closeStatus
    sourceCollectorPreflightStatus = $preflightStatus
    sourceCollectorPreflightDecision = $preflightDecision
    evidenceOnlyCollectorReviewAllowed = $evidenceCollectorReviewAllowed
    sourceFalseKillPct = if ($null -ne $closePacket) { $closePacket.falseKillPct } else { $null }
    sourceActionableFalseKillPct = if ($null -ne $closePacket) { $closePacket.actionableFalseKillPct } else { $null }
    sourceExpectedValueProjectedActionableFalseKillPctAfterReview = if ($null -ne $closePacket) { $closePacket.expectedValueProjectedActionableFalseKillPctAfterReview } else { $null }
    sourceReplayCandidateRows = if ($null -ne $closePacket) { $closePacket.replayCandidateRows } else { $null }
    sourceCompleteReplayableCandidateRows = if ($null -ne $closePacket) { $closePacket.completeReplayableCandidateRows } else { $null }
    sourceMissingCounterfactualFields = if ($null -ne $closePacket) { $closePacket.missingCounterfactualFields } else { "" }
    requiredBeforeIssueClose = @(
        "stable replayCandidateId rows",
        "complete replayable candidate snapshots",
        "entry/TP/SL candidate plan",
        "EV snapshot",
        "OCO preflight snapshot",
        "missing_counterfactual_fields=[]"
    )
    requiredBeforeCollectorActivation = if ($null -ne $preflightPacket) { $preflightPacket.requiredBeforeAnyFutureActivation } else { @() }
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    notAuthorization = "read-only issue #7 operator handoff only; does not close issue when closeAllowed=false, activate collector, deploy, change production env, relax DataFreshnessGuard, allow DataFreshness shadow review, enable live trading, staged-add/tiny-live execution, scheduler enablement, orders, OCO modification, close-position, Telegram send, DB/grid/fund/Earn/exchange mutation, or external backfill/import"
}

Write-Host "[issue7-operator-handoff] read-only packet"
Write-Host "scope=READ_ONLY; invokes issue #7 close-readiness and DataFreshness collector activation preflight only; no SSH fresh run unless source scripts do so, no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host $closeText
Write-Host $preflightText
Write-Host "issue7_operator_handoff_packet=$($packet | ConvertTo-Json -Compress -Depth 12)"
Write-Host "issue7_operator_handoff_status=$status"
Write-Host "issue7_operator_handoff_decision=$handoffDecision"
Write-Host "issue7_close_allowed=$($closeAllowed.ToString().ToLowerInvariant())"
Write-Host "issue7_live_relaxation_allowed=false"
Write-Host "evidence_only_collector_review_allowed=$($evidenceCollectorReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "collector_activation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "issue7_operator_handoff_missing_requirements=$($missingRequirements -join '; ')"
Write-Host "issue7_operator_handoff_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireActionable -and $status -eq "BLOCKED_REPLAY_EVIDENCE_AND_COLLECTOR_REVIEW_NOT_READY") {
    throw "Issue #7 operator handoff is not actionable: $status; missing=$(@($missingRequirements) -join '; ')"
}
