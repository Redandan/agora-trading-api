Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_live_blocker_source_refresh.ps1"
$scriptText = Get-Content -Raw -LiteralPath $scriptPath

foreach ($marker in @(
        "PROFIT_LIVE_BLOCKER_SOURCE_REFRESH_PLAN",
        "prepare_profit_operator_action_brief_ssh.ps1",
        "prepare_profit_operator_priority_decision_brief.ps1",
        "prepare_trailing_stop_dry_run_operator_decision_packet.ps1",
        "prepare_strategy485_risk_reduction_operator_decision_packet.ps1",
        "prepare_strategy485_risk_escalation_brief.ps1",
        "prepare_entry_dedup_operator_decision_brief_ssh.ps1",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1",
        "prepare_no_buy_attention_flow_review_packet_ssh.ps1",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "prepare_profit_live_blocker_audit_packet.ps1",
        "prepare_profit_operator_next_action_board.ps1",
        "prepare_profit_operator_authorization_request_packet.ps1",
        "profit-live-blocker-audit-packet-latest.log",
        "profit-operator-next-action-board-latest.log",
        "profit-operator-authorization-request-latest.log",
        "ContinueOnStepFailure",
        "AllowBlockedStepFailures",
        "PlanOnly",
        "ReuseLatestProfitOperatorMatrix",
        "ForceFreshProfitOperatorMatrix",
        "StepTimeoutSeconds",
        "latest-profit-operator-matrix.path",
        "step_heartbeat",
        "step_timeout",
        "timedOut",
        "profit_live_blocker_source_refresh_reuse_latest_profit_operator_matrix=",
        "profit_live_blocker_source_refresh_auto_reused_fresh_profit_operator_matrix=",
        "profit_live_blocker_source_refresh_force_fresh_profit_operator_matrix=",
        "profit_live_blocker_source_refresh_allow_blocked_step_failures=",
        "profit_live_blocker_source_refresh_blocked_step_failures_allowed=",
        "entry_dedup_refresh_hours=",
        "entry_dedup_refresh_forward_hours=",
        "entry_dedup_refresh_limit=",
        "Test-Path -LiteralPath `$outputParent",
        "notAuthorization=read-only source refresh orchestration only"
    )) {
    Assert-Contains -Name "source refresh script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for source refresh test" }

$emptyReviewDir = Join-Path $repoRoot "target\profit-review-source-refresh-empty-test"
if (Test-Path -LiteralPath $emptyReviewDir) {
    Remove-Item -LiteralPath $emptyReviewDir -Recurse -Force
}

$output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PlanOnly -ReviewOutputDir $emptyReviewDir 2>&1
$exitCode = $LASTEXITCODE
$text = ($output | Out-String -Width 4096)
if ($exitCode -ne 0) {
    throw "source refresh plan failed:`n$text"
}

foreach ($marker in @(
        "profit_live_blocker_source_refresh_step_count=22",
        "profit_live_blocker_source_refresh_ssh_step_count=9",
        "profit_live_blocker_source_refresh_local_step_count=13",
        "profit_live_blocker_source_refresh_reuse_latest_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_auto_reused_fresh_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_force_fresh_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_allow_blocked_step_failures=False",
        "profit_live_blocker_source_refresh_step_timeout_seconds=5400",
        "profit_live_blocker_source_refresh_status=PLAN_ONLY_NOT_EXECUTED",
        '"packetType":"PROFIT_LIVE_BLOCKER_SOURCE_REFRESH_PLAN"',
        '"stepTimeoutSeconds":5400',
        '"autoReusedFreshProfitOperatorMatrix":false',
        '"forceFreshProfitOperatorMatrix":false',
        '"allowBlockedStepFailures":false',
        '"name":"profit-operator-action-brief"',
        '"name":"profit-live-blocker-audit"',
        '"name":"profit-operator-next-action-board"',
        '"name":"profit-operator-authorization-request"',
        '"name":"entry-dedup-semantics-decision"',
        '"name":"no-buy-attention-flow-review"',
        '"name":"governance-relaxation-review"',
        '"script":"prepare_entry_dedup_operator_decision_brief_ssh.ps1","arguments":["-Symbol","BTCUSDT","-StrategyId","508","-IntervalCode","1h","-Hours","720","-ForwardHours","24","-Limit","50","-RequireDecisionReady"]',
        '"entryDedupHours":720',
        '"entryDedupForwardHours":24',
        '"entryDedupLimit":50',
        "entry_dedup_refresh_hours=720",
        "entry_dedup_refresh_forward_hours=24",
        "entry_dedup_refresh_limit=50",
        '"script":"prepare_no_buy_attention_flow_review_packet_ssh.ps1","arguments":["-ReviewOutputDir"',
        '"script":"prepare_governance_relaxation_review_packet_ssh.ps1","arguments":[]',
        '"script":"prepare_governance_relaxation_preflight_review_packet.ps1","arguments":["-ReviewLogPath"',
        '"-NoBuyAttentionLogPath"',
        'no-buy-attention-flow-review-packet-latest.log',
        '"script":"prepare_profit_operator_next_action_board.ps1","arguments":["-ReviewOutputDir"',
        '"-PriorityDecisionLogPath"',
        'profit-operator-priority-decision-brief-latest.log',
        '"-AuditLogPath"',
        'profit-live-blocker-audit-packet-latest.log',
        '"-RequireAudit","-RequireReady"',
        '"script":"prepare_profit_operator_authorization_request_packet.ps1","arguments":["-BoardLogPath"',
        'profit-operator-next-action-board-latest.log',
        'profit-operator-authorization-request-latest.log',
        '"usesSsh":true',
        '"usesSsh":false',
        '"forbiddenActions":["deploy"',
        "notAuthorization=read-only source refresh orchestration only"
    )) {
    Assert-Contains -Name "source refresh plan output" -Text $text -Pattern ([regex]::Escape($marker))
}

if ($text -match "step_start|child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
    throw "PlanOnly unexpectedly executed refresh steps:`n$text"
}

$reuseReviewDir = Join-Path $repoRoot "target\profit-review-source-refresh-test"
New-Item -ItemType Directory -Force -Path $reuseReviewDir | Out-Null
$matrixPath = Join-Path $reuseReviewDir "profit-operator-matrix-test.log"
Set-Content -LiteralPath $matrixPath -Encoding UTF8 -Value @"
profit_operator_review_matrix_status=NO_REVIEW_READY_ITEMS
profit_operator_review_matrix_next_action=Continue read-only evidence collection.
"@
$relativeMatrixPath = "target\profit-review-source-refresh-test\profit-operator-matrix-test.log"
Set-Content -LiteralPath (Join-Path $reuseReviewDir "latest-profit-operator-matrix.path") -Encoding UTF8 -Value $relativeMatrixPath

$autoReuseOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PlanOnly -ReviewOutputDir $reuseReviewDir 2>&1
$autoReuseExitCode = $LASTEXITCODE
$autoReuseText = ($autoReuseOutput | Out-String -Width 4096)
if ($autoReuseExitCode -ne 0) {
    throw "source refresh auto-reuse plan failed:`n$autoReuseText"
}

foreach ($marker in @(
        "profit_live_blocker_source_refresh_reuse_latest_profit_operator_matrix=True",
        "profit_live_blocker_source_refresh_auto_reused_fresh_profit_operator_matrix=True",
        "profit_live_blocker_source_refresh_force_fresh_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_reused_profit_operator_matrix_path=$matrixPath",
        '"reuseLatestProfitOperatorMatrix":true',
        '"autoReusedFreshProfitOperatorMatrix":true',
        '"forceFreshProfitOperatorMatrix":false',
        '"-MatrixOutputPath"',
        '"-MatrixMaxAgeMinutes"',
        "profit_live_blocker_source_refresh_status=PLAN_ONLY_NOT_EXECUTED"
    )) {
    Assert-Contains -Name "source refresh auto-reuse plan output" -Text $autoReuseText -Pattern ([regex]::Escape($marker))
}

if ($autoReuseText -match "step_start|child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
    throw "Auto-reuse PlanOnly unexpectedly executed refresh steps:`n$autoReuseText"
}

$reuseOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PlanOnly -ReviewOutputDir $reuseReviewDir -ReuseLatestProfitOperatorMatrix 2>&1
$reuseExitCode = $LASTEXITCODE
$reuseText = ($reuseOutput | Out-String -Width 4096)
if ($reuseExitCode -ne 0) {
    throw "source refresh reuse plan failed:`n$reuseText"
}

foreach ($marker in @(
        "profit_live_blocker_source_refresh_reuse_latest_profit_operator_matrix=True",
        "profit_live_blocker_source_refresh_auto_reused_fresh_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_force_fresh_profit_operator_matrix=False",
        "profit_live_blocker_source_refresh_reused_profit_operator_matrix_path=$matrixPath",
        '"reuseLatestProfitOperatorMatrix":true',
        '"autoReusedFreshProfitOperatorMatrix":false',
        '"forceFreshProfitOperatorMatrix":false',
        '"reusedProfitOperatorMatrixPath":"',
        '"-MatrixOutputPath"',
        '"-MatrixMaxAgeMinutes"',
        '"prepare_profit_operator_action_brief_ssh.ps1"',
        "profit_live_blocker_source_refresh_status=PLAN_ONLY_NOT_EXECUTED"
    )) {
    Assert-Contains -Name "source refresh reuse plan output" -Text $reuseText -Pattern ([regex]::Escape($marker))
}

if ($reuseText -match "step_start|child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
    throw "Reuse PlanOnly unexpectedly executed refresh steps:`n$reuseText"
}

Write-Host "[profit-live-blocker-source-refresh-test] OK"
