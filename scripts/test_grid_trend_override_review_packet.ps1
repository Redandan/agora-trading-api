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

function Assert-FailsWith {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [string]$Pattern
    )

    $failed = $false
    try {
        & $Action
    } catch {
        $failed = $true
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Name failed with unexpected message: $($_.Exception.Message)"
        }
    }

    if (-not $failed) {
        throw "$Name did not fail"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_trend_override_review_packet_ssh.ps1"
$snapshotPath = Join-Path $PSScriptRoot "prepare_grid_open_decision_snapshot_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$snapshotText = Get-Content -Raw -LiteralPath $snapshotPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-trend-override-review] read-only packet",
        "GRID_TREND_OVERRIDE_REVIEW_PACKET",
        "prepare_grid_open_decision_snapshot_ssh.ps1",
        "grid_open_decision_snapshot_packet=",
        "READY_FOR_GRID_TREND_OVERRIDE_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_TREND_OVERRIDE_REVIEW_NOT_MUTATION",
        "PREPARE_SEPARATE_TREND_OVERRIDE_REVIEW",
        "WAIT_FOR_TREND_CLEARANCE_OR_RESOLVE_OVERRIDE_HARD_BLOCKERS",
        "NO_TREND_OVERRIDE_NEEDED_REFRESH_GRID_OPEN_DECISION_SNAPSHOT",
        "quantitativeOverrideEvidence",
        "riskGradeSource",
        "riskGradeConsistency",
        "sourceEnvelopeRiskGrade",
        "directTrendPctRiskGrade",
        "grid_trend_override_review_risk_grade_source",
        "grid_trend_override_review_risk_grade_consistency",
        "SOURCE_OPERATOR_ENVELOPE_DIFFERS_FROM_DIRECT_THRESHOLD",
        "trendDistanceToSidewaysPct",
        "effectiveReviewCapitalCapUsdt",
        "mcpMissingRequiredToolCount",
        "hardBlockers",
        "reviewConditions",
        "requiredOperatorAuthorization",
        "abortCriteria",
        "followUpVerification",
        "REPLAY_STOP_BREAK_ROWS_PRESENT",
        "EVENT_RISK_NOT_R0_FOR_TREND_OVERRIDE",
        "GRID_MCP_COVERAGE_NOT_READY_FOR_TREND_OVERRIDE",
        "REPLAY_SCORE_BELOW_MINIMUM_FOR_TREND_OVERRIDE",
        "NO_EFFECTIVE_REVIEW_CAPITAL_CAP",
        "separate written trend-regime override naming current trend and trendPct",
        "separate written maximum review capital cap",
        "grid_trend_override_review_status",
        "grid_trend_override_review_ready",
        "trendOverrideReviewReady",
        "trendOverrideAllowed = `$false",
        "trend_override_allowed=false",
        "grid_open_allowed=false",
        "production_env_change_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid trend override review packet only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid trend override review script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GRID_OPEN_DECISION_SNAPSHOT",
        "grid_open_decision_snapshot_packet",
        "quantitativeReadiness",
        "gateStatuses"
    )) {
    Assert-Contains -Name "decision snapshot supports trend override review" -Text $snapshotText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid trend override review packet must not invoke grid or MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_trend_override_review_packet_ssh.ps1",
        "GRID_TREND_OVERRIDE_REVIEW_PACKET",
        "grid_trend_override_review_status",
        "trend_override_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid trend override review packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-trend-override-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad threshold high" -Pattern "SidewaysTrendPctThreshold must be between 0.1 and 3.0" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -SidewaysTrendPctThreshold 5
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-trend-override-review-packet-test] OK"
