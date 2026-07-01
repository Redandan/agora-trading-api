Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_high_risk_micro_live_probe_activation_source_refresh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PLAN",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PACKET",
        "READY_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_AUTHORIZATION_REVIEW_NOT_MUTATION",
        "BLOCKED_REFRESHED_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_REQUIREMENTS_MISSING",
        "INCOMPLETE_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_FAILED_STEPS",
        "prepare_profit_high_risk_micro_live_probe_handoff.ps1",
        "prepare_profit_aggressive_activation_operator_packet.ps1",
        "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1",
        "prepare_tp_sl_oco_feasibility_preflight_review_packet.ps1",
        "prepare_profit_high_risk_micro_live_probe_preflight_review_packet.ps1",
        "prepare_profit_high_risk_micro_live_probe_activation_authorization_bundle.ps1",
        "prepare_live_review_packet_ssh.ps1",
        "smoke_runtime_evidence_rca_ssh.ps1",
        "profit-high-risk-micro-live-probe-handoff-latest.log",
        "profit-aggressive-activation-operator-packet-latest.log",
        "profit-high-risk-micro-live-probe-preflight-review-latest.log",
        "profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log",
        "RefreshLiveReviewFromSsh",
        "RefreshRuntimeEvidenceFromSsh",
        "PlanOnly",
        "ContinueOnStepFailure",
        "AllowDirtyLocalWorktreeForReplay",
        "Set-Content -LiteralPath `$outputPath",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "livePolicyChangeAllowed = `$false",
        "orderAllowed = `$false",
        "gridMutationAllowed = `$false",
        "telegramSendAllowed = `$false",
        "read-only activation source refresh only"
    )) {
    Assert-Contains -Name "micro live probe activation source refresh script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}
if ($scriptText -match "tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "activation source refresh must not invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_high_risk_micro_live_probe_activation_source_refresh.ps1",
        "PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PACKET",
        "profit_micro_probe_activation_source_refresh_status",
        "profit-high-risk-micro-live-probe-handoff-latest.log",
        "profit-high-risk-micro-live-probe-preflight-review-latest.log",
        "profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log",
        "order_allowed=false",
        "deploy_allowed=false",
        "live_policy_change_allowed=false"
    )) {
    Assert-Contains -Name "micro live probe activation source refresh docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs micro live probe activation source refresh test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_high_risk_micro_live_probe_activation_source_refresh.ps1"

$planOutput = & $scriptPath -PlanOnly -ReviewOutputDir "target/profit-review-test" *>&1
$planText = $planOutput -join "`n"
foreach ($marker in @(
        "[profit-high-risk-micro-live-probe-activation-source-refresh] read-only refresh orchestration",
        "profit_micro_probe_activation_source_refresh_step_count=6",
        "profit_micro_probe_activation_source_refresh_ssh_step_count=0",
        "profit_micro_probe_activation_source_refresh_local_step_count=6",
        "profit_micro_probe_activation_source_refresh_live_review_log=target\profit-review-test\live-review-packet-latest.log",
        "profit_micro_probe_activation_source_refresh_runtime_evidence_log=target\profit-review-test\runtime-evidence-rca-post-deploy-current.log",
        "profit_micro_probe_activation_source_refresh_activation_log=target\profit-review-test\profit-high-risk-micro-live-probe-activation-authorization-bundle-latest.log",
        "profit_micro_probe_activation_source_refresh_plan=",
        '"packetType":"PROFIT_HIGH_RISK_MICRO_LIVE_PROBE_ACTIVATION_SOURCE_REFRESH_PLAN"',
        "profit_micro_probe_activation_source_refresh_status=PLAN_ONLY_NOT_EXECUTED",
        "notAuthorization=read-only activation source refresh only"
    )) {
    Assert-Contains -Name "micro live probe activation source refresh plan replay" -Text $planText -Pattern ([regex]::Escape($marker))
}
if ($planText -match "step_start|Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
    throw "PlanOnly should not execute refresh steps:`n$planText"
}

Write-Host "[profit-high-risk-micro-live-probe-activation-source-refresh-test] OK"
