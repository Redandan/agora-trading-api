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

Assert-Contains -Name "remediation precise runtime gap guidance" -Text $remediationText -Pattern 'Fix the specific health, runtime log, and/or event-risk evidence named in the audit'

Write-Host "[live-readiness-audit-next-actions-test] OK"
