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
$scriptPath = Join-Path $PSScriptRoot "prepare_grid_capital_override_review_packet_ssh.ps1"
$createAuthPath = Join-Path $PSScriptRoot "prepare_grid_create_authorization_preflight_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$createAuthText = Get-Content -Raw -LiteralPath $createAuthPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[grid-capital-override-review] read-only packet",
        "GRID_CAPITAL_OVERRIDE_REVIEW_PACKET",
        "prepare_grid_create_authorization_preflight_packet_ssh.ps1",
        "READY_FOR_GRID_CAPITAL_OVERRIDE_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_GRID_CAPITAL_OVERRIDE_REVIEW_NOT_MUTATION",
        "PREPARE_SEPARATE_CAPITAL_CAP_OVERRIDE_AUTHORIZATION",
        "REFRESH_GRID_CAPITAL_OVERRIDE_EVIDENCE",
        "RESOLVE_GRID_CAPITAL_OVERRIDE_REVIEW_BLOCKERS",
        "capitalOverrideRequest",
        "requiredCapRaiseUsdt",
        "requiredCapMultiplier",
        "requestedMaximumReviewCapitalCapUsdt",
        "HIGH_TREND_OVERRIDE_RISK_NOT_CAP_OVERRIDE_REVIEWABLE",
        "EVENT_RISK_NOT_R0_FOR_CAPITAL_OVERRIDE_REVIEW",
        "GRID_ENV_DIFF_REVIEW_NOT_READY_FOR_CAPITAL_OVERRIDE",
        "STOP_BREAK_ROWS_NOT_ZERO_FOR_CAPITAL_OVERRIDE",
        "REPLAY_SCORE_BELOW_CAPITAL_OVERRIDE_FLOOR",
        "approvalConditions",
        "abortCriteria",
        "postApprovalReadOnlyVerification",
        "capitalOverrideReviewReady",
        "capitalOverrideAllowed = `$false",
        "createGridAllowed = `$false",
        "gridOpenAllowed = `$false",
        "gridMutationAllowed = `$false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "capital_override_allowed=false",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "grid_mutation_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "oco_mutation_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only grid capital override review packet only",
        "RequireReviewReady"
    )) {
    Assert-Contains -Name "grid capital override review script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("GRID_CREATE_AUTHORIZATION_PREFLIGHT_PACKET", "capitalCapCheck", "CAPITAL_ABOVE_EFFECTIVE_REVIEW_CAP")) {
    Assert-Contains -Name "create authorization packet supports capital override review" -Text $createAuthText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|enableGridAutoRebalance\(") {
    throw "grid capital override review packet must not write files or invoke grid/MCP mutation calls"
}

foreach ($marker in @(
        "prepare_grid_capital_override_review_packet_ssh.ps1",
        "GRID_CAPITAL_OVERRIDE_REVIEW_PACKET",
        "grid_capital_override_review_status",
        "grid_capital_override_review_ready",
        "capital_override_allowed=false",
        "create_grid_allowed=false",
        "grid_open_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention grid capital override review packet" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempKey = Join-Path ([System.IO.Path]::GetTempPath()) ("agora-grid-capital-override-key-" + [Guid]::NewGuid().ToString("N"))
Set-Content -LiteralPath $tempKey -Value "dummy" -NoNewline
try {
    Assert-FailsWith -Name "unsafe ssh host" -Pattern "SshHost contains unsupported characters" -Action {
        & $scriptPath -SshHost "-oProxyCommand=bad" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets"
    }
    Assert-FailsWith -Name "bad grid count" -Pattern "GridCount must be between 4 and 24" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -GridCount 2
    }
    Assert-FailsWith -Name "bad stop out" -Pattern "StopOutPct must be between 1 and 20" -Action {
        & $scriptPath -SshHost "ubuntu@example.com" -SshKey $tempKey -AppDir "/home/ubuntu/agora-trading-api" -EnvFile "/home/ubuntu/.env.trading.secrets" -StopOutPct 50
    }
} finally {
    Remove-Item -LiteralPath $tempKey -Force -ErrorAction SilentlyContinue
}

Write-Host "[grid-capital-override-review-packet-test] OK"
