Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot

$expectedObservedAt = "2026-06-19T12:15+08:00"
$expectedMetadataObservedAt = "2026-06-20T09:09+08:00"
$expectedServerCommit = "224f550478b20a329775f503b3eaa70ba6a2f6a8"
$expectedOriginCommit = "0eef3ce5c3964e2520c1c5aa16a57e87f0ba26a0"
$expectedMetadataOriginCommit = "37ea17174c646753448b37a2a7f73cc35dc8e41b"
$expectedBlockers = @(
    "LIVE_READINESS_NOT_READY",
    "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN",
    "EXECUTION_ELIGIBILITY_NOT_READY",
    "BACKGROUND_AUTOMATION_REVIEW",
    "RUNTIME_EVIDENCE_CONFIG_DISABLED",
    "RUNTIME_EVIDENCE_NO_SHADOW_INTENT",
    "TINY_LIVE_LOSS_HARD_STOP",
    "TINY_LIVE_ROLLOUT_NOT_READY",
    "SIGNAL_POLICY_REVIEW_GAPS",
    "DEPLOYED_RUNTIME_NOT_CURRENT"
)

function Read-RepoText {
    param([string]$RelativePath)
    Get-Content -Raw -LiteralPath (Join-Path $repoRoot $RelativePath)
}

function Assert-ContainsLiteral {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )
    if (-not $Text.Contains($Needle)) {
        throw "$Name missing literal: $Needle"
    }
}

function Assert-ContainsPattern {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Assert-BlockersPresent {
    param(
        [string]$Name,
        [string]$Text
    )
    foreach ($blocker in $expectedBlockers) {
        Assert-ContainsLiteral -Name $Name -Text $Text -Needle $blocker
    }
}

function Assert-BlockerJsonExact {
    param(
        [string]$Name,
        [string]$Text
    )
    $json = 'bundle_blockers=["' + ($expectedBlockers -join '","') + '"]'
    Assert-ContainsLiteral -Name $Name -Text $Text -Needle $json
}

$splitStatus = Read-RepoText "docs/split-acceptance-status.md"
$splitProgress = Read-RepoText "SPLIT_PROGRESS.md"
$remediation = Read-RepoText "docs/live-readiness-blocker-remediation.md"
$productionProposal = Read-RepoText "docs/live-production-env-review-proposal.md"

foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress },
        @{ Name = "live production env review proposal"; Text = $productionProposal }
    )) {
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedObservedAt
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedServerCommit
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedOriginCommit
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedMetadataObservedAt
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle $expectedMetadataOriginCommit
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "rerun"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "smoke_live_deployment_metadata_ssh.ps1"
    Assert-ContainsLiteral -Name $doc.Name -Text $doc.Text -Needle "metadata-only"
    Assert-BlockersPresent -Name $doc.Name -Text $doc.Text
}

Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle $expectedServerCommit
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle $expectedOriginCommit
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle $expectedMetadataObservedAt
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle $expectedMetadataOriginCommit
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "smoke_live_deployment_metadata_ssh.ps1"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "metadata-only"
Assert-BlockersPresent -Name "live readiness remediation" -Text $remediation

Assert-BlockerJsonExact -Name "live production env review proposal refreshed snapshot" -Text $productionProposal
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "Latest refreshed read-only bundle evidence supersedes the earlier 11:45"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "both remain historical evidence"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "become stale again"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "deploy_required_before_live_review=true"
Assert-ContainsLiteral -Name "live production env review proposal refreshed snapshot" -Text $productionProposal -Needle "predates the classified log smoke"
Assert-ContainsLiteral -Name "live readiness remediation" -Text $remediation -Needle "classified log smoke reached the deployed service"
Assert-ContainsLiteral -Name "live production env review proposal refreshed metadata" -Text $productionProposal -Needle "refreshType=DEPLOYMENT_METADATA_ONLY"
Assert-ContainsLiteral -Name "live production env review proposal refreshed metadata" -Text $productionProposal -Needle "bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY"

foreach ($doc in @(
        @{ Name = "split acceptance status"; Text = $splitStatus },
        @{ Name = "split progress"; Text = $splitProgress },
        @{ Name = "live readiness remediation"; Text = $remediation },
        @{ Name = "live production env review proposal"; Text = $productionProposal }
    )) {
    Assert-ContainsPattern -Name $doc.Name -Text $doc.Text -Pattern "stale\s+live-review evidence"
    Assert-ContainsPattern -Name $doc.Name -Text $doc.Text -Pattern "separately\s+authorized"
}

Write-Host "[live-readiness-snapshot-consistency-test] OK"
