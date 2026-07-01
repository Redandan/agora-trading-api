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

function Assert-FailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern
    )

    $script = Join-Path $PSScriptRoot "prepare_live_review_packet_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for preflight guard test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "preflight accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "preflight did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "preflight reached SSH before local input guard:`n$text"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_live_review_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
) -join "`n"

foreach ($pattern in @(
        '\[live-review-packet-preflight\] read-only evidence gate',
        'scope=READ_ONLY',
        'smoke_live_readiness_bundle_ssh.ps1',
        'origin_delta_classifier=smoke_live_origin_delta_local.ps1',
        'source_smoke_exit_code=',
        'deployment_metadata_status=',
        'origin_metadata_status=',
        'origin_delta_status=',
        'origin_runtime_delta_files=',
        'origin_docs_tooling_delta_files=',
        'bundle_blockers=',
        'bundle_blocker_summary=',
        'packet_bundle_blocker_summary=',
        'live_review_packet_allowed=',
        'deploy_required_before_live_review=',
        'bundle_verdict=',
        'packet_missing_requirements=',
        'packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
        'packet_status=NO_EVIDENCE',
        'packet_status=NOT_READY',
        'DEPLOYED_RUNTIME_NOT_CURRENT',
        'smoke_live_origin_delta_local.ps1',
        'origin_delta_status=RUNTIME_DRIFT',
        'origin_delta_status=DOCS_TOOLING_ONLY_DRIFT',
        'origin_delta_status=NO_LOCAL_EVIDENCE',
        'separately deploy and verify current origin/main',
        'attach classifier evidence separately',
        'refresh local git evidence or rerun metadata smoke',
        'Fix SSH/read-only smoke collection',
        'notAuthorization=read-only preflight only',
        'RequireReady',
        'full bundle exited non-zero',
        'bundle_blocker_summary is missing',
        'bundle_blocker_summary is not valid JSON',
        'bundle_blocker_summary missing blocker:',
        'bundle_blocker_summary has blocker not present in bundle_blockers:',
        'bundle_blocker_summary entry missing field:',
        'bundle_blockers is non-empty',
        'live_review_packet_allowed is not true',
        'deploy_required_before_live_review is not false',
        'bundle_verdict is not READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
        'deployment_metadata_status is not current',
        'origin_metadata_status is not CURRENT_ORIGIN_MAIN or DOCS_TOOLING_ONLY_DRIFT',
        'originCurrentEnough',
        'no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed',
        'Assert-SshHostSafe',
        'Assert-RemotePathSafe',
        'Assert-SmokeTokenSafe',
        'Convert-JsonArrayOrNull',
        'Test-JsonObjectHasProperty',
        'RuntimeEvidenceMinutes must be between 60 and 43200',
        'TinyLiveDays must be between 1 and 90',
        'SignalExecutionDays, SignalBlockedDays, and SignalAccuracyDays must be between 1 and 90'
    )) {
    Assert-Contains -Name "preflight script" -Text $scriptText -Pattern $pattern
}

foreach ($pattern in @(
        'prepare_live_review_packet_ssh.ps1 -RequireReady',
        'live review packet preflight',
        'read-only',
        'bundle_blockers=\[\]',
        'bundle_blocker_summary',
        'packet_bundle_blocker_summary',
        'packet_bundle_blocker_summary=\[\]',
        'requiredEvidence',
        'evidenceMarkers',
        'nextAction',
        'live_review_packet_allowed=true',
        'deploy_required_before_live_review=false',
        'bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
        'packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
        'packet_missing_requirements=\[\]',
        'NOT_READY',
        'NO_EVIDENCE',
        'not live approval',
        'does not\s+authorize production env\s+changes'
    )) {
    Assert-Contains -Name "preflight docs" -Text $docsText -Pattern $pattern
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-RuntimeEvidenceMinutes", "1") `
    -ExpectedPattern "RuntimeEvidenceMinutes must be between 60 and 43200"

Write-Host "[live-review-packet-preflight-test] OK"
