Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) { throw "$Name contains forbidden pattern: $Pattern" }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$paths = @{
    Policy = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitPolicy.java"
    Candidate = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitCandidateService.java"
    Lane = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitLaneService.java"
    Strategy = Join-Path $repoRoot "src/main/java/com/agora/service/backtest/OiFundingDivergenceStrategy.java"
    Collector = Join-Path $repoRoot "src/main/java/com/agora/scheduler/trading/MarketIndicatorHistoryCollector.java"
    IndicatorRepository = Join-Path $repoRoot "src/main/java/com/agora/repository/trading/MarketIndicatorHistoryRepository.java"
    Outcome = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitOutcomeService.java"
    Readiness = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitReadinessService.java"
    Attribution = Join-Path $repoRoot "src/main/java/com/agora/service/trading/StrategyNetPnlAttributionService.java"
    Close = Join-Path $repoRoot "src/main/java/com/agora/service/trading/SpotPositionCloseService.java"
    Mcp = Join-Path $repoRoot "src/main/java/com/agora/mcp/Strategy508TimeExitMcpTools.java"
    MetaMcp = Join-Path $repoRoot "src/main/java/com/agora/mcp/MetaControlMcpTools.java"
    App = Join-Path $repoRoot "src/main/resources/application.yml"
    Env = Join-Path $repoRoot ".env.trading.secrets.example"
    SshSmoke = Join-Path $PSScriptRoot "smoke_strategy508_time_exit_ssh.ps1"
    Runbook = Join-Path $repoRoot "docs/deploy-runbook.md"
    ProfitPlan = Join-Path $repoRoot "docs/profit-execution-plan.md"
}
$text = @{}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { throw "Missing $($entry.Key): $($entry.Value)" }
    $text[$entry.Key] = Get-Content -Raw -LiteralPath $entry.Value
}

foreach ($marker in @(
        'POLICY_MODE = "STRATEGY_508_4H_24H_V1"',
        'COHORT_SCHEMA_VERSION = "STRATEGY_508_TIME_EXIT_COHORT_V1"',
        'RAW_COUNTERFACTUAL_COHORT = "RAW_SIGNAL_COUNTERFACTUAL"',
        'EXECUTABLE_SHADOW_COHORT = "EXECUTABLE_SHADOW"',
        'INTERVAL = "4h"',
        'HOLD_HOURS = 24',
        'NOTIONAL_USDT = new BigDecimal("10.00")',
        'TAKE_PROFIT_PCT = new BigDecimal("0.06")',
        'STOP_LOSS_PCT = new BigDecimal("0.12")',
        'MAX_CUMULATIVE_LOSS_USDT = new BigDecimal("3.00")',
        'MAX_ORDERS_PER_DAY = 1',
        'MAX_PILOT_ORDERS = 5',
        'ENTRY_MAX_DELAY_MINUTES = 2',
        'MARKET_FEATURE_MAX_AGE_MINUTES = 90',
        'EXACT_LIVE_FILL_EVIDENCE_IMPLEMENTED = false',
        'effectiveConfigSha256',
        'applyMarketFeatureFreshnessPolicy')) {
    Assert-Contains -Name "fixed policy" -Text $text.Policy -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'FIRST_1M_OPEN_AT_OR_AFTER_4H_CLOSE',
        'AMBIGUOUS_SAME_MINUTE',
        'INSUFFICIENT_1M_COVERAGE',
        'SKIPPED_DAILY_ORDER_CAP',
        'FIXED_NON_OVERLAPPING_CALENDAR_FOLDS_NO_REFIT',
        'HISTORICAL_SAMPLE_UNTRUSTED',
        'EXACT_UTC_MINUTE_GRID_DISTINCT_TIMESTAMP_AND_OHLC_INVARIANTS',
        'effectivePolicyConfigSha256',
        'benchmark72PairedSample',
        'REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING',
        'livePromotionAllowed',
        'databaseMutated')) {
    Assert-Contains -Name "candidate analyzer" -Text $text.Candidate -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED',
        'expectedValueGateObservation',
        'tradeQualityObservation',
        'NOT_EVALUATED_ISOLATED_RAW_SIGNAL_LANE',
        'blocksEntry',
        'ENTRY_OCO_ATTACH_FAILED',
        'CRITICAL_ORDER_SENT_FILL_UNCONFIRMED',
        'effectivePolicyConfigSha256',
        'rawSignalCounterfactualEligible',
        'counterfactualOutcomeTracked',
        'executableCohortEligible',
        'hardBlockedOutcomeAffectsPromotion')) {
    Assert-Contains -Name "isolated lane" -Text $text.Lane -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "lane freshness policy" -Text $text.Lane -Pattern 'applyMarketFeatureFreshnessPolicy'
Assert-Contains -Name "candidate freshness policy" -Text $text.Candidate -Pattern 'applyMarketFeatureFreshnessPolicy'
foreach ($marker in @(
        'marketFeatureFreshnessFailClosed',
        'marketFeatureReferenceTimeMode',
        'feature_freshness_blockers',
        'publishMarketFeature("funding_rate"',
        'publishMarketFeature("oi_change_pct_1h"',
        '"_captured_at"',
        '"_available_at"',
        '"_age_minutes"',
        '"_age_seconds"',
        'feature_volume_freshness',
        'feature_sma200_freshness')) {
    Assert-Contains -Name "strategy feature provenance" -Text $text.Strategy -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'getCurrentFundingRateObservation',
        'getOpenInterestObservation',
        'effectiveCapturedAt()',
        'availableAt',
        'sourceTimestampMs',
        'sameOiProvider',
        'DERIVED_OI_CHANGE:',
        'THE_GRAPH_UNISWAP_V3_WBTC_USDC',
        'DERIVED_OKX_HYPERLIQUID_FUNDING_SPREAD')) {
    Assert-Contains -Name "collector provider metadata" -Text $text.Collector -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "atomic provider metadata insert" -Text $text.IndicatorRepository -Pattern 'insertIgnoreWithMetadata'
Assert-Contains -Name "decision context bar identity" -Text $text.MetaMcp -Pattern 'bar_open_time:'
Assert-Contains -Name "hold audit decision time" -Text $text.Lane -Pattern 'context\.put\("decisionTime"'
Assert-Contains -Name "position isolation" -Text $text.Outcome -Pattern 'POLICY_POSITION_TAG_MISMATCH'
Assert-Contains -Name "database position lock" -Text $text.Close -Pattern 'findByIdForUpdate'
Assert-Contains -Name "per-position in-process lock" -Text $text.Close -Pattern 'closingPositionIds'
Assert-Contains -Name "fresh post-cancel balance" -Text $text.Close -Pattern 'getFreshSpotHoldings'
Assert-Contains -Name "failed close reprotection" -Text $text.Close -Pattern 'failAfterReprotect'
Assert-Contains -Name "partial fee fail closed" -Text $text.Outcome -Pattern 'partialExitFeeCoverageComplete'
Assert-Contains -Name "blocked shadow outcome tracking" -Text $text.Outcome -Pattern 'SHADOW_COUNTERFACTUAL_OUTCOME_FINALIZED_HARD_BLOCKED'
Assert-Contains -Name "single probe gate" -Text $text.Readiness -Pattern 'FIRST_PROBE_EXECUTION_NOT_VERIFIED'
foreach ($marker in @(
        'duplicateEventRows',
        'malformedContextRows',
        'canonicalAdmittedEntryEvents',
        'canonicalTimingGapRows',
        'outcomeContextMismatchRows',
        'effectiveConfigMismatchRows',
        'configCohortResetAtUtc',
        'CONTIGUOUS_SUFFIX_AFTER_LAST_DIFFERENT_EFFECTIVE_CONFIG_HASH',
        'rawSignalCounterfactual',
        'unboundPotentialExecutableRows',
        'cohortBindingMismatchRows',
        'hardGateBlockedEventsAffectPromotion',
        'LIVE_EXACT_FILL_PROVENANCE_NOT_IMPLEMENTED',
        'fillAggregationComplete',
        'FIRST_PROBE_NET_NOT_POSITIVE')) {
    Assert-Contains -Name "readiness fail-closed evidence" -Text $text.Readiness -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'excludedNonPolicyPositions',
        'policyTaggedPositions',
        'nonPolicyPositions',
        'NONE_GENERIC_TOOL_NEVER_HIDES_MATCHING_POSITIONS',
        'fillAggregationComplete',
        'feeSignPreserved',
        'netPnlEvidenceMatches',
        'STALE_OR_MISSING_FAIL_CLOSED',
        'POSITIVE_COST_NEGATIVE_REBATE',
        'NO_POSITIONS')) {
    Assert-Contains -Name "generic PnL attribution" -Text $text.Attribution -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "raw minute source fail closed" -Text $text.Candidate -Pattern 'INVALID_1M_SOURCE_ROW'
Assert-Contains -Name "raw minute rejection count" -Text $text.Candidate -Pattern 'rejectedMinuteRows'
Assert-Contains -Name "exact producer blocks PnL" -Text $text.Attribution -Pattern 'EXACT_FEE_ATTRIBUTION_BLOCKED_PRODUCER_UNAVAILABLE'
foreach ($tool in @("analyzeStrategy508TimeExitCandidate", "getStrategy508TimeExitReadiness", "getStrategyNetPnlAttribution")) {
    Assert-Contains -Name "MCP tools" -Text $text.Mcp -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "SSH smoke" -Text $text.SshSmoke -Pattern ([regex]::Escape($tool))
}
Assert-NotContains -Name "SSH smoke rejects untrusted historical sample" -Text $text.SshSmoke `
    -Pattern 'HISTORICAL_SAMPLE_UNTRUSTED'
Assert-Contains -Name "application mode env" -Text $text.App -Pattern 'TRADING_508_TIME_EXIT_MODE:OFF'
Assert-Contains -Name "application live flag" -Text $text.App -Pattern 'TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED:false'
Assert-Contains -Name "env mode default" -Text $text.Env -Pattern 'TRADING_508_TIME_EXIT_MODE=OFF'
Assert-Contains -Name "env live default" -Text $text.Env -Pattern 'TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED=false'
Assert-Contains -Name "runbook smoke" -Text $text.Runbook -Pattern 'smoke_strategy508_time_exit_ssh\.ps1'
Assert-Contains -Name "profit plan rejection" -Text $text.ProfitPlan -Pattern '19/19'

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($paths.SshSmoke, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { throw "strategy 508 SSH smoke PowerShell parse failed: $($errors -join '; ')" }

Write-Host "[strategy508-time-exit-smoke-test] OK"
