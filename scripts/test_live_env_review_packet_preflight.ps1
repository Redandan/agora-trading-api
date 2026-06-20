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
$scriptPath = Join-Path $PSScriptRoot "prepare_live_env_review_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$productionProposalPath = Join-Path $repoRoot "docs/live-production-env-review-proposal.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $productionProposalPath
) -join "`n"

foreach ($pattern in @(
        '\[live-env-review-packet-preflight\] local review-only gate',
        'LOCAL_DOCS_ONLY',
        'runtime_evidence_candidate=',
        'background_disable_candidates=',
        'production_evidence_only_candidate=',
        'forbidden_true_candidates=',
        'env_review_missing_requirements=',
        'env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED',
        'env_review_packet_status=NOT_READY',
        'notAuthorization=local review packet preflight only',
        'TRADING_RUNTIME_EVIDENCE_ENABLED=true',
        'TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false',
        'EVENT_SCAN_NOTIFICATION_ENABLED=false',
        'EXECUTION_EVENT_ENABLED=false',
        'TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false',
        'TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false',
        'TRADING_OKX_ENABLED',
        'MCP_GUARDIAN_LIVE_ACTIONS_ENABLED',
        'forbidden true candidates are present',
        'no SSH, production env, deploy, restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed',
        'RequireReady'
    )) {
    Assert-Contains -Name "env review preflight script" -Text $scriptText -Pattern $pattern
}

foreach ($pattern in @(
        'prepare_live_env_review_packet\.ps1',
        'READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED',
        'not authorization',
        'local review packet preflight',
        'fresh read-only SSH smokes',
        'do not apply changes from this output'
    )) {
    Assert-Contains -Name "env review preflight docs" -Text $docsText -Pattern $pattern
}

$powerShell = Get-Command powershell -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for env review preflight test"
}

$output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -RequireReady 2>&1
$exitCode = $LASTEXITCODE
$outputText = ($output | Out-String)
if ($exitCode -ne 0) {
    throw "env review preflight failed unexpectedly:`n$outputText"
}
Assert-Contains -Name "env review preflight output" -Text $outputText -Pattern 'env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED'
Assert-Contains -Name "env review preflight output" -Text $outputText -Pattern 'forbidden_true_candidates=\[\]'
Assert-Contains -Name "env review preflight output" -Text $outputText -Pattern 'env_review_missing_requirements=\[\]'

Write-Host "[live-env-review-packet-preflight-test] OK"
