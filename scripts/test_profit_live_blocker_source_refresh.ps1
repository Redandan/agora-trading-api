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
        "prepare_entry_dedup_semantics_operator_decision_packet.ps1",
        "prepare_data_freshness_replay_evidence_readiness_ssh.ps1",
        "prepare_strategy574_tiny_live_governance_preflight_review_packet.ps1",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "prepare_profit_live_blocker_audit_packet.ps1",
        "profit-live-blocker-audit-packet-latest.log",
        "ContinueOnStepFailure",
        "PlanOnly",
        "notAuthorization=read-only source refresh orchestration only"
    )) {
    Assert-Contains -Name "source refresh script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for source refresh test" }

$output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PlanOnly 2>&1
$exitCode = $LASTEXITCODE
$text = ($output | Out-String -Width 4096)
if ($exitCode -ne 0) {
    throw "source refresh plan failed:`n$text"
}

foreach ($marker in @(
        "profit_live_blocker_source_refresh_step_count=19",
        "profit_live_blocker_source_refresh_ssh_step_count=7",
        "profit_live_blocker_source_refresh_local_step_count=12",
        "profit_live_blocker_source_refresh_status=PLAN_ONLY_NOT_EXECUTED",
        '"packetType":"PROFIT_LIVE_BLOCKER_SOURCE_REFRESH_PLAN"',
        '"name":"profit-operator-action-brief"',
        '"name":"profit-live-blocker-audit"',
        '"name":"governance-relaxation-review"',
        '"script":"prepare_governance_relaxation_review_packet_ssh.ps1","arguments":[]',
        '"script":"prepare_governance_relaxation_preflight_review_packet.ps1","arguments":["-ReviewLogPath"',
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

Write-Host "[profit-live-blocker-source-refresh-test] OK"
