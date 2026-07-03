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

function Assert-NotContains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -match $Pattern) {
        throw "$Name unexpectedly matched pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$auditPath = Join-Path $repoRoot "scripts/audit_live_readiness_ssh.ps1"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"

$auditText = Get-Content -Raw -LiteralPath $auditPath
$remediationText = Get-Content -Raw -LiteralPath $remediationPath

Assert-Contains -Name "audit runtime gap label health" -Text $auditText -Pattern 'gap_labels\.append\("health"\)'
Assert-Contains -Name "audit runtime gap label runtime log" -Text $auditText -Pattern 'gap_labels\.append\("runtime log"\)'
Assert-Contains -Name "audit runtime gap label event risk" -Text $auditText -Pattern 'gap_labels\.append\("event-risk baseline"\)'
Assert-Contains -Name "audit runtime gap action joins labels" -Text $auditText -Pattern '"/"\.join\(gap_labels'
Assert-Contains -Name "audit runtime gap default" -Text $auditText -Pattern '"runtime health"'
Assert-NotContains -Name "audit runtime gap old generic action" -Text $auditText -Pattern 'Fix health/log/event-risk gaps before any live operator review\.'
Assert-Contains -Name "audit readiness detail missing helper" -Text $auditText -Pattern 'def missing_readiness_detail_fields'
Assert-Contains -Name "audit readiness detail missing output" -Text $auditText -Pattern 'missing_readiness_detail_fields='
Assert-Contains -Name "audit readiness detail missing blocker" -Text $auditText -Pattern 'READINESS_DETAILS_MISSING_FIELDS'
Assert-Contains -Name "audit readiness detail required tiny" -Text $auditText -Pattern '"tinyLive": \["executionEligible", "wouldExecute", "previewStatus", "runtimeEvidenceStatus"\]'
Assert-Contains -Name "audit readiness detail required scorebuy" -Text $auditText -Pattern '"scoreBuyPostScoutAdd": \["enabled", "dryRun", "orderSent", "executionEligible"\]'
Assert-Contains -Name "audit live authorized switch" -Text $auditText -Pattern '\[switch\]\$LiveAuthorized'
Assert-Contains -Name "audit live authorized output" -Text $auditText -Pattern 'live_authorized='
Assert-Contains -Name "audit live authorized verdict" -Text $auditText -Pattern 'verdict=LIVE_AUTHORIZED_MONITORING'
Assert-Contains -Name "audit live authorized order flag warning" -Text $auditText -Pattern 'LIVE_AUTHORIZED_ORDER_FLAGS_TRUE'
Assert-Contains -Name "audit local tradingview flags output" -Text $auditText -Pattern 'local_tradingview_flags='
Assert-Contains -Name "audit local tradingview execution mode flag" -Text $auditText -Pattern 'TRADINGVIEW_LOCAL_EXECUTION_MODE'
Assert-Contains -Name "audit local tradingview execution enabled flag" -Text $auditText -Pattern 'TRADINGVIEW_LOCAL_EXECUTION_ENABLED'
Assert-Contains -Name "audit local tradingview dry run flag" -Text $auditText -Pattern 'TRADINGVIEW_LOCAL_EXECUTION_DRY_RUN'
Assert-Contains -Name "audit local tradingview live order flag" -Text $auditText -Pattern 'TRADINGVIEW_LOCAL_EXECUTION_LIVE_ORDER_ENABLED'
Assert-Contains -Name "audit live authorized tiny trigger enabled proof" -Text $auditText -Pattern 'TINY_TRIGGER_NOT_ENABLED_IN_LIVE_AUTHORIZED_MODE'
Assert-Contains -Name "audit live authorized tiny hard scope proof" -Text $auditText -Pattern 'hardScope=BTCUSDT/574/LONG/5USDT'
Assert-Contains -Name "audit default still blocks enabled tiny trigger" -Text $auditText -Pattern 'TINY_TRIGGER_ALREADY_ENABLED_OR_MARKER_MISSING'
Assert-Contains -Name "audit live authorized high risk log allow" -Text $auditText -Pattern 'ALLOW_HIGH_RISK_LOG"\] = "1" if live_authorized else "0"'

Assert-Contains -Name "remediation precise runtime gap guidance" -Text $remediationText -Pattern 'Fix the specific health, runtime log, and/or event-risk evidence named in the audit'
Assert-Contains -Name "remediation readiness detail missing guidance" -Text $remediationText -Pattern 'missing_readiness_detail_fields=\[\]'

Write-Host "[live-readiness-audit-next-actions-test] OK"
