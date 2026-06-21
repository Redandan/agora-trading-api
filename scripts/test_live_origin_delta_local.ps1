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
$scriptPath = Join-Path $PSScriptRoot "smoke_live_origin_delta_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $remediationPath
) -join "`n"

foreach ($pattern in @(
        '\[live-origin-delta-local\] read-only origin delta classifier',
        'scope=READ_ONLY',
        'smoke_live_deployment_metadata_ssh\.ps1',
        'git diff --name-only',
        'git cat-file -e',
        'src/test/*',
        'origin_delta_local_evidence=',
        'origin_delta_status=',
        'CURRENT_ORIGIN_MAIN',
        'DOCS_TOOLING_ONLY_DRIFT',
        'RUNTIME_DRIFT',
        'NO_LOCAL_EVIDENCE',
        'origin_delta_files=',
        'origin_docs_tooling_delta_files=',
        'origin_runtime_delta_files=',
        'origin_runtime_delta_paths=',
        'live_review_packet_allowed=false',
        'notAuthorization=read-only local classifier only',
        'does not authorize live trading',
        'no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, fetch, restart, or nginx state changed',
        'metadata-only/local classifier output is not live-readiness evidence',
        'Review docs/tooling-only drift',
        'Separately deploy and verify current origin/main'
    )) {
    Assert-Contains -Name "origin delta local script" -Text $scriptText -Pattern $pattern
}

foreach ($pattern in @(
        'systemctl',
        'nginx -t',
        'reload nginx',
        'git fetch',
        'git pull',
        'git reset',
        'SPRING_FLYWAY_ENABLED=true',
        'TRADING_OKX_ENABLED=true',
        'TELEGRAM_BOT_TOKEN='
    )) {
    if ($scriptText -match [regex]::Escape($pattern)) {
        throw "origin delta local script must not contain mutation marker: $pattern"
    }
}

foreach ($pattern in @(
        'smoke_live_origin_delta_local\.ps1',
        'origin[- ]delta',
        'DOCS_TOOLING_ONLY_DRIFT',
        'RUNTIME_DRIFT',
        'NO_LOCAL_EVIDENCE',
        'live_review_packet_allowed=false',
        'not live-readiness evidence'
    )) {
    Assert-Contains -Name "origin delta docs" -Text $docsText -Pattern $pattern
}

Write-Host "[live-origin-delta-local-test] OK"
