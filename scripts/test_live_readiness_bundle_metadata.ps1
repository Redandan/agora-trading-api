Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LiveReadinessMetadataSummary {
    param([string]$DeploymentMetadata)

    $blockers = [System.Collections.Generic.List[string]]::new()
    $deploymentStatus = "UNKNOWN"
    $originStatus = "UNKNOWN"

    if ($DeploymentMetadata -match "liveBundleDeployStatus=([A-Z_]+)") {
        $deploymentStatus = $Matches[1]
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=([A-Z_]+)") {
        $originStatus = $Matches[1]
    }
    if ($DeploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)") {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=(WORKTREE_NOT_ORIGIN_MAIN|UNKNOWN_ORIGIN_MAIN)") {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }

    [pscustomobject]@{
        DeploymentStatus = $deploymentStatus
        OriginStatus = $originStatus
        Blockers = @($blockers | Select-Object -Unique)
    }
}

function Assert-MetadataCase {
    param(
        [string]$Name,
        [string]$DeploymentMetadata,
        [string]$ExpectedDeploymentStatus,
        [string]$ExpectedOriginStatus,
        [bool]$ExpectRuntimeCurrentBlocker
    )

    $summary = Get-LiveReadinessMetadataSummary -DeploymentMetadata $DeploymentMetadata
    if ($summary.DeploymentStatus -ne $ExpectedDeploymentStatus) {
        throw "$Name deployment status expected $ExpectedDeploymentStatus but got $($summary.DeploymentStatus)"
    }
    if ($summary.OriginStatus -ne $ExpectedOriginStatus) {
        throw "$Name origin status expected $ExpectedOriginStatus but got $($summary.OriginStatus)"
    }
    $hasBlocker = @($summary.Blockers) -contains "DEPLOYED_RUNTIME_NOT_CURRENT"
    if ($hasBlocker -ne $ExpectRuntimeCurrentBlocker) {
        throw "$Name DEPLOYED_RUNTIME_NOT_CURRENT expected $ExpectRuntimeCurrentBlocker but got $hasBlocker"
    }
}

function Assert-BundleFailureMarkers {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
    $readmePath = Join-Path $repoRoot "README.md"
    $runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
    $splitStatusPath = Join-Path $repoRoot "docs/split-acceptance-status.md"
    $progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
    $bundleText = Get-Content -Raw -LiteralPath $bundlePath
    $handoffText = @(
        Get-Content -Raw -LiteralPath $readmePath
        Get-Content -Raw -LiteralPath $runbookPath
        Get-Content -Raw -LiteralPath $splitStatusPath
        Get-Content -Raw -LiteralPath $progressPath
    ) -join "`n"

    foreach ($pattern in @(
            "Get-ReadOnlySshFailureClassification",
            "Assert-ReadOnlyCommandSucceeded",
            "SSH_AUTH_FAILED",
            "SSH_CONNECT_FAILED",
            "SSH_COMMAND_FAILED",
            "read_only_bundle_error=",
            'bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]',
            "bundle_verdict=NO_EVIDENCE",
            "not complete live-readiness evidence",
            "full live-readiness evidence was not collected"
        )) {
        if ($bundleText -notmatch [regex]::Escape($pattern)) {
            throw "live readiness bundle missing SSH failure marker: $pattern"
        }
    }

    foreach ($pattern in @(
            "SSH_AUTH_FAILED",
            "SSH_CONNECT_FAILED",
            "SSH_COMMAND_FAILED",
            "LIVE_READINESS_EVIDENCE_UNAVAILABLE",
            "bundle_verdict=NO_EVIDENCE",
            "not live-readiness evidence",
            "complete evidence"
        )) {
        if ($handoffText -notmatch [regex]::Escape($pattern)) {
            throw "live readiness handoff docs missing SSH failure marker: $pattern"
        }
    }

    if ($splitStatusPath -and ((Get-Content -Raw -LiteralPath $splitStatusPath) -notmatch "fix SSH access, key selection, or the\s+failing read-only smoke and rerun")) {
        throw "split acceptance status must explain SSH access failure next action"
    }
}

Assert-BundleFailureMarkers

Assert-MetadataCase `
    -Name "current runtime and origin" `
    -DeploymentMetadata @"
liveBundleOriginStatus=CURRENT_ORIGIN_MAIN
liveBundleDeployStatus=CURRENT
"@ `
    -ExpectedDeploymentStatus "CURRENT" `
    -ExpectedOriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $false

Assert-MetadataCase `
    -Name "docs tooling drift only and current origin" `
    -DeploymentMetadata @"
liveBundleOriginStatus=CURRENT_ORIGIN_MAIN
liveBundleDeployStatus=DOCS_TOOLING_ONLY_DRIFT
"@ `
    -ExpectedDeploymentStatus "DOCS_TOOLING_ONLY_DRIFT" `
    -ExpectedOriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $false

Assert-MetadataCase `
    -Name "runtime drift" `
    -DeploymentMetadata @"
liveBundleOriginStatus=CURRENT_ORIGIN_MAIN
liveBundleDeployStatus=RUNTIME_DRIFT
"@ `
    -ExpectedDeploymentStatus "RUNTIME_DRIFT" `
    -ExpectedOriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $true

Assert-MetadataCase `
    -Name "server worktree behind origin main" `
    -DeploymentMetadata @"
liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN
liveBundleDeployStatus=CURRENT
"@ `
    -ExpectedDeploymentStatus "CURRENT" `
    -ExpectedOriginStatus "WORKTREE_NOT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $true

Assert-MetadataCase `
    -Name "unknown origin" `
    -DeploymentMetadata @"
liveBundleOriginStatus=UNKNOWN_ORIGIN_MAIN
liveBundleDeployStatus=CURRENT
"@ `
    -ExpectedDeploymentStatus "CURRENT" `
    -ExpectedOriginStatus "UNKNOWN_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $true

Assert-MetadataCase `
    -Name "unknown deploy metadata" `
    -DeploymentMetadata @"
liveBundleOriginStatus=CURRENT_ORIGIN_MAIN
liveBundleDeployStatus=UNKNOWN_DEPLOY_METADATA
"@ `
    -ExpectedDeploymentStatus "UNKNOWN_DEPLOY_METADATA" `
    -ExpectedOriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $true

Write-Host "[live-bundle-metadata-test] OK"
