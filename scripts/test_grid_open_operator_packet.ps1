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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_open_operator_packet_ssh.ps1"
$readinessPath = Join-Path $PSScriptRoot "prepare_grid_open_readiness_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$readinessText = Get-Content -Raw -LiteralPath $readinessPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-open-operator-packet] read-only packet",
        "GRID_OPEN_OPERATOR_PACKET",
        "prepare_grid_open_readiness_packet_ssh.ps1",
        "grid_open_readiness_packet=",
        "READY_FOR_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION",
        "WAIT_FOR_GATE_CLEARANCE_OR_SEPARATE_OPERATOR_OVERRIDES",
        "PREPARE_SEPARATE_GRID_OPEN_OPERATOR_REVIEW",
        "grid_open_operator_packet",
        "grid_open_operator_status",
        "grid_open_operator_decision",
        "grid_open_operator_missing_requirements",
        "grid_open_operator_authorization_required",
        "trend gate cleared by SIDEWAYS evidence or separate operator trend override",
        "event-risk gate cleared by R0 evidence or separate operator risk override",
        "separate operator authorization for TRADING_OKX_ENABLED=true",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "OKX_EARN_TOPUP_ENABLED=false",
        "createGridReviewInputs",
        "postAuthorizationVerificationPlan",
        "production_env_change_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid open operator packet only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid open operator packet marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GRID_OPEN_READINESS_PACKET",
        "grid_open_gate_review",
        "grid_candidate_plan",
        "operatorAuthorizationRequired"
    )) {
    Assert-Contains -Name "grid readiness supports operator packet" -Text $readinessText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_grid_open_operator_packet_ssh.ps1",
        "GRID_OPEN_OPERATOR_PACKET",
        "grid_open_operator_packet",
        "grid open operator packet",
        "BLOCKED_GRID_OPEN_OPERATOR_REVIEW_NOT_MUTATION"
    )) {
    Assert-Contains -Name "docs mention grid open operator packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-open-operator-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe symbol" -Pattern "Symbol contains unsupported characters" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -Symbol "BTCUSDT';echo bad"
    }
    Assert-FailsWith -Name "bad lookback" -Pattern "LookbackHours must be between 24 and 720" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -LookbackHours 1
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-open-operator-packet-test] OK"
