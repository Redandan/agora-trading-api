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

function Get-CommandBlock {
    param(
        [string]$Text,
        [string]$Heading
    )

    $pattern = '(?ms)^## ' + [regex]::Escape($Heading) + '.*?```powershell\s+(.*?)```'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Could not find powershell command block under heading '$Heading'."
    }
    $match.Groups[1].Value
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$proposalPath = Join-Path $repoRoot "docs/live-production-env-review-proposal.md"
$proposalText = Get-Content -Raw -LiteralPath $proposalPath

$requiredEvidence = Get-CommandBlock -Text $proposalText -Heading "Required Evidence Before Review"
$postAuthorization = Get-CommandBlock -Text $proposalText -Heading "Post-Authorization Verification"

foreach ($scriptName in @(
        "audit_live_readiness_ssh.ps1",
        "smoke_live_background_automation_ssh.ps1",
        "smoke_runtime_evidence_rca_ssh.ps1",
        "smoke_tiny_live_loss_rca_ssh.ps1",
        "smoke_signal_correctness_ssh.ps1",
        "smoke_mcp_parity_ssh.ps1",
        "smoke_live_readiness_bundle_ssh.ps1"
    )) {
    Assert-Contains -Name "required evidence commands" -Text $requiredEvidence -Pattern ([regex]::Escape(".\scripts\$scriptName"))
    Assert-Contains -Name "post-authorization commands" -Text $postAuthorization -Pattern ([regex]::Escape(".\scripts\$scriptName"))
}

foreach ($pattern in @(
        'DEPLOYED_RUNTIME_NOT_CURRENT',
        'LIVE_READINESS_EVIDENCE_UNAVAILABLE',
        'bundle_verdict=NO_EVIDENCE',
        'origin_metadata_status=WORKTREE_NOT_ORIGIN_MAIN',
        'originMainCommit=',
        'bundle_verdict=NOT_READY',
        'stale\s+live-review evidence only',
        'stop the review and fix SSH access',
        'failing read-only smoke',
        'separately authorized deploy',
        'server worktree/runtime to `origin/main`',
        'rerun the full live-readiness bundle',
        'not authorization'
    )) {
    if ($pattern -match '\\s') {
        Assert-Contains -Name "production env review proposal currentness boundary" -Text $proposalText -Pattern $pattern
    } else {
        Assert-Contains -Name "production env review proposal currentness boundary" -Text $proposalText -Pattern ([regex]::Escape($pattern))
    }
}

Assert-Contains -Name "post-authorization bundle blocker expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`DEPLOYED_RUNTIME_NOT_CURRENT`'
Assert-Contains -Name "post-authorization no-evidence blocker expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`LIVE_READINESS_EVIDENCE_UNAVAILABLE`'
Assert-Contains -Name "post-authorization no-evidence verdict expectation" -Text $proposalText -Pattern 'smoke_live_readiness_bundle_ssh\.ps1` no longer reports\s+`bundle_verdict=NO_EVIDENCE`'
Assert-Contains -Name "post-authorization order-capable true-list expectation" -Text $proposalText -Pattern 'order_capable_flags_true=\[\]'

Write-Host "[live-production-env-review-plan-test] OK"
