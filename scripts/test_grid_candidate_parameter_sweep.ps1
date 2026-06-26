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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_candidate_parameter_sweep_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($marker in @(
        "GRID_CANDIDATE_PARAMETER_SWEEP_PACKET",
        "prepare_grid_open_operator_packet_ssh.ps1",
        "grid_candidate_parameter_sweep_rows",
        "grid_candidate_parameter_sweep_best_candidate",
        "grid_candidate_parameter_sweep_best_quality_candidate",
        "grid_candidate_parameter_sweep_remaining_blockers",
        "grid_candidate_parameter_sweep_packet",
        "grid_candidate_parameter_sweep_status",
        "grid_candidate_parameter_sweep_quality_candidate_count",
        "CandidateHalfWidthPctValues",
        "candidateHalfWidthPct",
        "observedCandidateHalfWidthPct",
        "candidateHalfWidthSource",
        "READY_GRID_CANDIDATE_PARAMETER_FOUND_NOT_MUTATION",
        "BLOCKED_GRID_CANDIDATE_REPLAY_QUALITY_READY_CAPITAL_OR_TREND_NOT_MUTATION",
        "BLOCKED_NO_GRID_CANDIDATE_PARAMETER_FOUND_NOT_MUTATION",
        "NO_PARAMETER_REPLAY_SCORE_AT_LEAST_70",
        "NO_PARAMETER_CAPITAL_WITHIN_EFFECTIVE_REVIEW_CAP",
        "TREND_GATE_REMAINS_BLOCKED_OR_REQUIRES_SEPARATE_OVERRIDE_REVIEW",
        "production_env_change_allowed=false",
        "grid_mutation_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid candidate parameter sweep only"
    )) {
    Assert-Contains -Name "grid candidate parameter sweep markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($pathText in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "grid candidate sweep docs" -Text $pathText -Pattern "prepare_grid_candidate_parameter_sweep_ssh.ps1"
    Assert-Contains -Name "grid candidate sweep docs mention read-only" -Text $pathText -Pattern "read-only"
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-sweep-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "bad grid count in sweep" -Pattern "GridCounts values must be between 4 and 24" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCounts 2
    }
    Assert-FailsWith -Name "bad per-level in sweep" -Pattern "PerLevelUsdtValues values must be between 5 and 1000" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -PerLevelUsdtValues 1
    }
    Assert-FailsWith -Name "bad stop-out in sweep" -Pattern "StopOutPctValues values must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPctValues 0.5
    }
    Assert-FailsWith -Name "bad candidate half-width in sweep" -Pattern "CandidateHalfWidthPctValues values must be 0 or between 2.5 and 30" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -CandidateHalfWidthPctValues 1
    }
    Assert-FailsWith -Name "too many sweep combinations" -Pattern "exceeds MaxCombinations" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCounts 4,5,6 -PerLevelUsdtValues 5,10 -StopOutPctValues 1,2 -MaxCombinations 1
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-candidate-parameter-sweep-test] OK"
