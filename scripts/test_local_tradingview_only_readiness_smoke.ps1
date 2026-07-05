Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "smoke_local_tradingview_only_readiness_ssh.ps1"
$text = Get-Content -Raw -LiteralPath $scriptPath

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Pattern
    )
    if ($text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

foreach ($pattern in @(
        "local-tradingview-only-readiness",
        "scope=READ_ONLY",
        "legacy_tiny_scorebuy_runtime_evidence_not_evaluated=true",
        "smoke_live_deployment_metadata_ssh.ps1",
        "audit_live_readiness_ssh.ps1",
        "smoke_live_background_automation_ssh.ps1",
        "smoke_local_tradingview_candidate_ssh.ps1",
        "deployment_metadata_status",
        "origin_metadata_status",
        "metadata_current",
        "deployment_metadata_effective_status",
        "metadata_effective_current",
        "origin_delta_files",
        "order_capable_flags_unexpected",
        "backgroundAutomationClear",
        "background_automation_unreviewed_true",
        "local_tradingview_current_candidate_status",
        "local_tradingview_readiness",
        "local_tradingview_live_micro_armed",
        "local_tradingview_execution_path_armed",
        "local_tradingview_oco_lifecycle_tracked",
        "local_tradingview_pre_execution_evidence_status",
        "local_tradingview_pre_execution_readiness",
        "local_tradingview_pre_execution_blockers",
        "local_tradingview_scope_allowed",
        "local_tradingview_source_allowed",
        "local_tradingview_okx_auto_trade_enabled",
        "local_tradingview_okx_private_credentials_configured",
        "local_tradingview_notional_accepted",
        "local_tradingview_effective_notional_usdt",
        "local_tradingview_exchange_min_notional_usdt",
        "local_tradingview_oco_plan_valid",
        "local_tradingview_signal_stale",
        "local_tradingview_daily_cap_available",
        "local_tradingview_open_position_cap_available",
        "local_tradingview_open_exact_position_exists",
        "local_tradingview_duplicate_bar_exists",
        "local_tradingview_bar_cap_allows_at_least_one",
        "local_tradingview_only_status",
        "local_tradingview_only_blockers",
        "local_tradingview_only_health_warnings",
        "local_tradingview_only_legacy_blockers_excluded=true",
        "WAIT_BUY",
        "READY_CURRENT_BUY_CANDIDATE_LIVE_MICRO_ARMED",
        "BLOCKED",
        "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE",
        "ORDER_CAPABLE_FLAGS_REVIEW",
        "BACKGROUND_AUTOMATION_REVIEW",
        "DEPLOYED_RUNTIME_NOT_CURRENT",
        "DOCS_TOOLING_ONLY_DRIFT_NOT_DEPLOYED",
        "RUNTIME_LOG_NOT_CLEAN",
        "EVENT_RISK_NOT_BASELINE",
        "LOCAL_TRADINGVIEW_CANDIDATE_BLOCKERS_MISSING",
        "LOCAL_TRADINGVIEW_PRE_EXECUTION_BLOCKERS_MISSING",
        "LOCAL_TRADINGVIEW_PRE_EXECUTION_DB_EVIDENCE_UNAVAILABLE",
        "LOCAL_TRADINGVIEW_DAILY_CAP_REACHED",
        "LOCAL_TRADINGVIEW_OPEN_POSITION_CAP_REACHED",
        "LOCAL_TRADINGVIEW_OPEN_POSITION_EXISTS",
        "LOCAL_TRADINGVIEW_DUPLICATE_BAR",
        "LOCAL_TRADINGVIEW_SIGNAL_STALE",
        "Test-DocsToolingOnlyPath",
        "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED",
        "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED",
        "RequireReady",
        "RequireCurrentCandidate",
        "Assert-RemotePathSafe",
        "Assert-SshHostSafe",
        "Assert-SmokeTokenSafe",
        "notAuthorization=read-only LOCAL_TRADINGVIEW-only readiness evidence",
        "read-only check complete")) {
    Assert-Contains -Name "local TradingView-only readiness smoke" -Pattern $pattern
}

foreach ($forbidden in @(
        "smoke_runtime_evidence_rca_ssh.ps1",
        "smoke_tiny_live_loss_rca_ssh.ps1",
        "smoke_signal_correctness_ssh.ps1",
        "executeTinyLive",
        "placeMarketBuy",
        "placeOco",
        "sendAlert",
        "closeGrid",
        "resumeGrid",
        "pauseGrid")) {
    if ($text -match [regex]::Escape($forbidden)) {
        throw "local TradingView-only readiness smoke must not include forbidden marker: $forbidden"
    }
}

Write-Host "[local-tradingview-only-readiness-smoke-test] OK"
