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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_readiness_packet_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "CandidateLookbackHours",
        "GRID_CANDIDATE_PLAN_READY_NOT_OPEN_APPROVAL",
        "GRID_CANDIDATE_PLAN_BLOCKED_BY_TREND_REGIME",
        "GRID_CANDIDATE_PLAN_REVIEW_STOP_BREAKS",
        "GRID_CANDIDATE_PLAN_UNAVAILABLE",
        "candidatePlanComplete",
        "candidatePlanStatus",
        "entryReferencePrice",
        "candidateLower",
        "candidateUpper",
        "candidateCapitalUsdt",
        "candidateHalfWidthPct",
        "candidateHalfWidthSource",
        "CandidateHalfWidthPct must be 0 or between 2.5 and 30",
        "stopOutPct",
        "stopLow",
        "stopHigh",
        "replayRows",
        "replayStart",
        "replayEnd",
        "insidePct",
        "stopBreakRows",
        "replayScore",
        "md_kline FORCE INDEX",
        "SPRING_DATASOURCE_URL",
        "read-only candidate plan only",
        "not createGrid input authorization",
        'Grid \u6700\u5927\u66dd\u96aa(\u5168 level \u586b\u6eff): $0.00',
        '\u6d3b\u8e8d Grid: 0 \u500b',
        "classify_sell_failed_lines",
        "historical_dust_sell_failed_count",
        "historical_material_sell_failed_count",
        "HISTORICAL_GRID_DUST_SELL_FAILED_REVIEW_NOT_BLOCKING",
        "HISTORICAL_GRID_SELL_FAILED_RECONCILIATION_REQUIRED",
        "grid_open_gate_review",
        "trendGate",
        "eventRiskGate",
        "okxGate",
        "BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE",
        "BLOCKED_EVENT_RISK_NOT_R0",
        "BLOCKED_OKX_ENV_AUTHORIZATION_REQUIRED",
        "grid_open_operator_authorization_required",
        "TRADING_GRID_ENABLED=true production env diff",
        "createGrid with reviewed candidate range/capital/stop",
        "NO_REPLAYABLE_GRID_CANDIDATE_PLAN"
    )) {
    Assert-Contains -Name "grid candidate plan packet markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($pathText in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "grid candidate docs mention candidate plan" -Text $pathText -Pattern "grid_candidate_plan"
    Assert-Contains -Name "grid candidate docs mention replayability" -Text $pathText -Pattern "replay"
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-candidate-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "bad candidate lookback" -Pattern "CandidateLookbackHours must be between 72 and 720" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -CandidateLookbackHours 24
    }
    Assert-FailsWith -Name "bad grid count" -Pattern "GridCount must be between 2 and 24" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCount 1
    }
    Assert-FailsWith -Name "bad per-level usdt" -Pattern "PerLevelUsdt must be between 5 and 1000" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -PerLevelUsdt 1
    }
    Assert-FailsWith -Name "bad stop-out pct" -Pattern "StopOutPct must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPct 0.5
    }
    Assert-FailsWith -Name "bad candidate half-width pct" -Pattern "CandidateHalfWidthPct must be 0 or between 2.5 and 30" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -CandidateHalfWidthPct 1
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-candidate-plan-packet-test] OK"
