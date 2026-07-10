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

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_strategy485_position_risk_ssh.ps1"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$readmePath = Join-Path $repoRoot "README.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$progressText = Get-Content -Raw -LiteralPath $progressPath

foreach ($toolName in @(
        "getTradingManagerDigest",
        "listOpenPositions",
        "listRecentClosed",
        "getOcoHealth",
        "listExecutionEvents",
        "getPositionDefenseStatus",
        "previewPositionDefensePlan",
        "analyzeTpStretchProtection",
        "analyzeStopSweepRisk",
        "getMonthlyPnlOverview",
        "reassessActivePositionEv"
    )) {
    Assert-Contains -Name "strategy485 smoke MCP calls" -Text $scriptText -Pattern ([regex]::Escape("call_tool(`"$toolName`""))
}

foreach ($marker in @(
        "scope=READ_ONLY",
        "server-local /api/mcp only",
        "no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed",
        "Open Strategy 485 Positions",
        "missing Strategy ID marker",
        "Position EV",
        "TP Stretch / Aging",
        "Recent Closed / PnL",
        "Stop Sweep",
        "strategy485_position_risk_recommendation",
        "strategy485_position_review_decision",
        "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY",
        "WATCH_NEGATIVE_EV_WITH_OCO_PROTECTED",
        "WATCH_TP_STRETCH",
        "FIX_OCO_PROTECTION_FIRST",
        "canDraftOperatorReviewPacket",
        "positionOrOcoMutationAllowed",
        "negativeEvPositionCount",
        "closeOrModifySuggestionCount",
        "positionTimeoutEventCount",
        "tpStretchWatchCount",
        "requiredEvidence",
        "nextAction",
        "notAuthorization",
        "OK read-only check complete"
    )) {
    Assert-Contains -Name "strategy485 smoke markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "position EV read-only boundary",
        "healthy OCO marker",
        "execution events read-only boundary",
        "position defense no order marker",
        "position defense no OCO marker",
        "position defense plan no order marker",
        "position defense plan no OCO marker",
        "TP stretch read-only boundary",
        "stop sweep read-only boundary",
        "def count_before_marker",
        "def oco_health_ok",
        "def no_open_auto_trade_position",
        "assert oco_health_ok",
        "assert no_open_auto_trade_position",
        "SYNC_ERROR",
        "OCO active",
        "無開倉中的自動交易倉位",
        "0 異常",
        "0 abnormal"
    )) {
    Assert-Contains -Name "strategy485 smoke hard-fail markers" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($path in @($runbookText, $readmeText, $progressText)) {
    Assert-Contains -Name "strategy485 docs mention smoke" -Text $path -Pattern "smoke_strategy485_position_risk_ssh\.ps1"
    Assert-Contains -Name "strategy485 docs mention read-only" -Text $path -Pattern "read-only"
}

Write-Host "[strategy485-position-risk-smoke-test] OK"
