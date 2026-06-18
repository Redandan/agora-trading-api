Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-ToolCall {
    param(
        [string]$ScriptText,
        [string]$ToolName
    )

    Assert-Contains -Name "tiny-live RCA tool calls" -Text $ScriptText -Pattern ([regex]::Escape("call_tool(`"$ToolName`""))
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_tiny_live_loss_rca_ssh.ps1"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"
$dryRunPath = Join-Path $repoRoot "docs/live-dry-run-evidence-plan.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$remediationText = Get-Content -Raw -LiteralPath $remediationPath
$dryRunText = Get-Content -Raw -LiteralPath $dryRunPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath

foreach ($toolName in @(
        "listTinyLiveExecutionReadiness",
        "getTinyLiveAutoExecutionTriggerStatus",
        "previewTinyLiveAutoApproval",
        "previewTinyLiveAutoExecution",
        "listTinyLiveExecutions",
        "getAutonomousExecutionAttribution",
        "getAutonomousExplorationMonitorStatus",
        "getExplorationRolloutStatus",
        "getMissedOpportunityRegressionReport",
        "getNoBuyReasonTruthTable"
    )) {
    Assert-ToolCall -ScriptText $scriptText -ToolName $toolName
}

foreach ($marker in @(
        "hardStopDetected",
        "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES",
        "hardStopClearCriteria=maxConsecutiveTinyLiveLosses<2, current BUY candidate present, runtime evidence available, execution flags separately authorized",
        "completedTinyLiveSamples",
        "falsePositiveCount",
        "dailyLossBudgetBreached",
        "canEnableProduction",
        "canIncreaseDailyCap",
        "orderSent=false",
        "KEEP_DISABLED",
        "SCOPE: this smoke is read-only"
    )) {
    Assert-Contains -Name "tiny-live RCA script" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

Assert-Contains -Name "remediation matrix" -Text $remediationText -Pattern '\| `TINY_LIVE_LOSS_HARD_STOP` \| `\.\\scripts\\smoke_tiny_live_loss_rca_ssh\.ps1` \|'
foreach ($pattern in @(
        'hardStopDetected=false',
        'consecutive tiny-live losses are below policy limit',
        'current BUY/add candidate exists',
        'runtime evidence is available',
        'not live approval'
    )) {
    Assert-Contains -Name "remediation matrix" -Text $remediationText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES',
        'hardStopDetected=true',
        'rollout gates that cannot enable production',
        'smoke_tiny_live_loss_rca_ssh.ps1',
        'live loss hard-stop status'
    )) {
    Assert-Contains -Name "dry-run evidence plan" -Text $dryRunText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        'consecutive tiny-live losses below 2',
        'a current BUY candidate',
        'runtime\s+evidence available',
        'separately authorized env-change plan',
        'hardStopDetected=false',
        'not live approval'
    )) {
    if ($pattern -match '\\s') {
        Assert-Contains -Name "deploy runbook tiny-live RCA" -Text $runbookText -Pattern $pattern
    } else {
        Assert-Contains -Name "deploy runbook tiny-live RCA" -Text $runbookText -Pattern ([regex]::Escape($pattern))
    }
}

Write-Host "[tiny-live-hard-stop-plan-test] OK"
