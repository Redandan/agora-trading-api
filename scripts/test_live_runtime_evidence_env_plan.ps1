Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SameSet {
    param(
        [string]$Name,
        [string[]]$Actual,
        [string[]]$Expected
    )

    $actualText = @($Actual | Sort-Object -Unique) -join ","
    $expectedText = @($Expected | Sort-Object -Unique) -join ","
    if ($actualText -ne $expectedText) {
        throw "$Name [$actualText] differs from expected [$expectedText]"
    }
}

function Get-EnvBlockKeys {
    param(
        [string]$Text,
        [string]$Heading
    )

    $pattern = '(?ms)^## ' + [regex]::Escape($Heading) + '\s+.*?```dotenv\s+(.*?)```'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Could not find dotenv block under heading '$Heading'."
    }
    @(
        [regex]::Matches($match.Groups[1].Value, '^([A-Z0-9_]+)=', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
            ForEach-Object { $_.Groups[1].Value }
    )
}

function Get-AuditOrderFlags {
    param([string]$Text)

    $match = [regex]::Match($Text, 'order_flags\s*=\s*\{(.*?)\}', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    if (-not $match.Success) {
        throw "Could not find audit order_flags block."
    }
    @(
        [regex]::Matches($match.Groups[1].Value, '"([A-Z0-9_]+)"\s*:', [System.Text.RegularExpressions.RegexOptions]::Multiline) |
            ForEach-Object { $_.Groups[1].Value }
    )
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$auditPath = Join-Path $PSScriptRoot "audit_live_readiness_ssh.ps1"
$proposalPath = Join-Path $repoRoot "docs/live-runtime-evidence-env-proposal.md"
$dryRunPlanPath = Join-Path $repoRoot "docs/live-dry-run-evidence-plan.md"

$auditText = Get-Content -Raw -LiteralPath $auditPath
$proposalText = Get-Content -Raw -LiteralPath $proposalPath
$dryRunPlanText = Get-Content -Raw -LiteralPath $dryRunPlanPath

$auditOrderFlags = Get-AuditOrderFlags -Text $auditText
$proposalOnlyDiff = Get-EnvBlockKeys -Text $proposalText -Heading "Proposed Evidence-Only Diff"
$proposalDisabled = Get-EnvBlockKeys -Text $proposalText -Heading "Must Stay Disabled"
$dryRunDisabled = Get-EnvBlockKeys -Text $dryRunPlanText -Heading "Must Remain Disabled"

$expectedBackgroundFlags = @(
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_DAILY_TG_REPORT_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED"
)

$expectedDryRunGuardFlags = @(
    "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN",
    "TRAILING_STOP_DRY_RUN",
    "POSITION_EXIT_MANAGER_DRY_RUN"
)

$expectedEvidencePhaseExtraDisabled = @(
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
    "TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED",
    "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE",
    "TRADING_SCORE_BUY_FORMING_DAY_NOTIFICATION_TELEGRAM_ENABLED",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_NOTIFICATION_TELEGRAM_ENABLED"
)

Assert-SameSet -Name "runtime evidence only proposed env diff" -Actual $proposalOnlyDiff -Expected @("TRADING_RUNTIME_EVIDENCE_ENABLED")

foreach ($flag in $auditOrderFlags) {
    if ($proposalText -notmatch [regex]::Escape("$flag=false")) {
        throw "Runtime evidence proposal must keep audit order-capable flag disabled: $flag"
    }
    if ($dryRunPlanText -notmatch [regex]::Escape("$flag=false")) {
        throw "Dry-run evidence plan must keep audit order-capable flag disabled: $flag"
    }
}

$expectedProposalDisabled = @(
    $auditOrderFlags +
    $expectedBackgroundFlags
)
Assert-SameSet -Name "runtime evidence proposal disabled env keys" -Actual $proposalDisabled -Expected $expectedProposalDisabled

$expectedDryRunDisabled = @(
    $auditOrderFlags +
    $expectedDryRunGuardFlags +
    $expectedBackgroundFlags +
    $expectedEvidencePhaseExtraDisabled
)
Assert-SameSet -Name "dry-run evidence plan disabled env keys" -Actual $dryRunDisabled -Expected $expectedDryRunDisabled

foreach ($flag in $expectedBackgroundFlags) {
    if ($dryRunPlanText -notmatch [regex]::Escape("$flag=false")) {
        throw "Dry-run evidence plan must keep background automation disabled: $flag"
    }
}

foreach ($flag in $expectedDryRunGuardFlags) {
    if ($dryRunPlanText -notmatch [regex]::Escape("$flag=true")) {
        throw "Dry-run evidence plan must keep dry-run guard enabled: $flag"
    }
}

foreach ($marker in @(
        "recorded read-only runtime evidence RCA reported",
        "This RCA output is historical",
        "latest recorded live-readiness bundle on",
        "2026-06-19T11:12+08:00",
        "514a3d1e0cf800e1c84a83368ad69ae6193cc32d",
        "Treat both records as stale live-review evidence",
        "separately authorized",
        "RUNTIME_EVIDENCE_NO_CANONICAL_ROWS",
        "RUNTIME_EVIDENCE_REVIEW_REQUIRED",
        "RUNTIME_EVIDENCE_ORDER_SENT",
        "CANONICAL_ROWS_NO_SHADOW_INTENT",
        "order-sent evidence in the bounded",
        'Missing or `N/A` shadow-intent evidence stays blocked',
        "Missing or unrecognized runtime-evidence diagnosis stays blocked"
    )) {
    if ($proposalText -notmatch [regex]::Escape($marker)) {
        throw "Runtime evidence proposal missing blocker marker: $marker"
    }
}

foreach ($marker in @(
        'full-bundle `bundle_blockers=[]`',
        '`bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED`'
    )) {
    if ($proposalText -notmatch [regex]::Escape($marker)) {
        throw "Runtime evidence proposal missing live proposal boundary marker: $marker"
    }
    if ($dryRunPlanText -notmatch [regex]::Escape($marker)) {
        throw "Dry-run evidence plan missing live proposal boundary marker: $marker"
    }
}

Write-Host "[live-runtime-evidence-env-plan-test] OK"
