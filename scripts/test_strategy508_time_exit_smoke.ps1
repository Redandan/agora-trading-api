Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$paths = @{
    Policy = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitPolicy.java"
    Candidate = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitCandidateService.java"
    Lane = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitLaneService.java"
    Outcome = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitOutcomeService.java"
    Readiness = Join-Path $repoRoot "src/main/java/com/agora/service/trading/Strategy508TimeExitReadinessService.java"
    Close = Join-Path $repoRoot "src/main/java/com/agora/service/trading/SpotPositionCloseService.java"
    Mcp = Join-Path $repoRoot "src/main/java/com/agora/mcp/Strategy508TimeExitMcpTools.java"
    App = Join-Path $repoRoot "src/main/resources/application.yml"
    Env = Join-Path $repoRoot ".env.trading.secrets.example"
    SshSmoke = Join-Path $PSScriptRoot "smoke_strategy508_time_exit_ssh.ps1"
    Runbook = Join-Path $repoRoot "docs/deploy-runbook.md"
    ProfitPlan = Join-Path $repoRoot "docs/profit-execution-plan.md"
}
$text = @{}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { throw "Missing $($entry.Key): $($entry.Value)" }
    $text[$entry.Key] = Get-Content -Raw -LiteralPath $entry.Value
}

foreach ($marker in @(
        'POLICY_MODE = "STRATEGY_508_4H_24H_V1"',
        'INTERVAL = "4h"',
        'HOLD_HOURS = 24',
        'NOTIONAL_USDT = new BigDecimal("10.00")',
        'TAKE_PROFIT_PCT = new BigDecimal("0.06")',
        'STOP_LOSS_PCT = new BigDecimal("0.12")',
        'MAX_CUMULATIVE_LOSS_USDT = new BigDecimal("3.00")',
        'MAX_ORDERS_PER_DAY = 1',
        'MAX_PILOT_ORDERS = 5')) {
    Assert-Contains -Name "fixed policy" -Text $text.Policy -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'FIRST_1M_OPEN_AT_OR_AFTER_4H_CLOSE',
        'AMBIGUOUS_SAME_MINUTE',
        'INSUFFICIENT_1M_COVERAGE',
        'benchmark72PairedSample',
        'REJECTED_NO_LIVE_NO_MORE_PARAMETER_TUNING',
        'livePromotionAllowed',
        'databaseMutated')) {
    Assert-Contains -Name "candidate analyzer" -Text $text.Candidate -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @(
        'STRATEGY_508_TIME_EXIT_SHADOW_BLOCKED',
        'expectedValueGateObservation',
        'tradeQualityObservation',
        'NOT_EVALUATED_ISOLATED_RAW_SIGNAL_LANE',
        'blocksEntry',
        'ENTRY_OCO_ATTACH_FAILED',
        'CRITICAL_ORDER_SENT_FILL_UNCONFIRMED')) {
    Assert-Contains -Name "isolated lane" -Text $text.Lane -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "position isolation" -Text $text.Outcome -Pattern 'POLICY_POSITION_TAG_MISMATCH'
Assert-Contains -Name "database position lock" -Text $text.Close -Pattern 'findByIdForUpdate'
Assert-Contains -Name "per-position in-process lock" -Text $text.Close -Pattern 'closingPositionIds'
Assert-Contains -Name "fresh post-cancel balance" -Text $text.Close -Pattern 'getFreshSpotHoldings'
Assert-Contains -Name "failed close reprotection" -Text $text.Close -Pattern 'failAfterReprotect'
Assert-Contains -Name "partial fee fail closed" -Text $text.Outcome -Pattern 'partialExitFeeCoverageComplete'
Assert-Contains -Name "single probe gate" -Text $text.Readiness -Pattern 'FIRST_PROBE_EXECUTION_NOT_VERIFIED'
foreach ($tool in @("analyzeStrategy508TimeExitCandidate", "getStrategy508TimeExitReadiness", "getStrategyNetPnlAttribution")) {
    Assert-Contains -Name "MCP tools" -Text $text.Mcp -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "SSH smoke" -Text $text.SshSmoke -Pattern ([regex]::Escape($tool))
}
Assert-Contains -Name "application mode env" -Text $text.App -Pattern 'TRADING_508_TIME_EXIT_MODE:OFF'
Assert-Contains -Name "application live flag" -Text $text.App -Pattern 'TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED:false'
Assert-Contains -Name "env mode default" -Text $text.Env -Pattern 'TRADING_508_TIME_EXIT_MODE=OFF'
Assert-Contains -Name "env live default" -Text $text.Env -Pattern 'TRADING_508_TIME_EXIT_LIVE_ORDER_ENABLED=false'
Assert-Contains -Name "runbook smoke" -Text $text.Runbook -Pattern 'smoke_strategy508_time_exit_ssh\.ps1'
Assert-Contains -Name "profit plan rejection" -Text $text.ProfitPlan -Pattern '19/19'

$tokens = $null
$errors = $null
[System.Management.Automation.Language.Parser]::ParseFile($paths.SshSmoke, [ref]$tokens, [ref]$errors) | Out-Null
if ($errors.Count -gt 0) { throw "strategy 508 SSH smoke PowerShell parse failed: $($errors -join '; ')" }

Write-Host "[strategy508-time-exit-smoke-test] OK"
