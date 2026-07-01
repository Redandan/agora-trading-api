Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LiveReadinessMetadataSummary {
    param([string]$DeploymentMetadata)

    $blockers = [System.Collections.Generic.List[string]]::new()
    $deploymentStatus = "UNKNOWN"
    $originStatus = "UNKNOWN"
    $originDeltaStatus = "UNKNOWN"

    if ($DeploymentMetadata -match "liveBundleDeployStatus=([A-Z_]+)") {
        $deploymentStatus = $Matches[1]
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=([A-Z_]+)") {
        $originStatus = $Matches[1]
    }
    if ($DeploymentMetadata -match "origin_delta_status=([A-Z_]+)") {
        $originDeltaStatus = $Matches[1]
    }
    if ($DeploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)" `
            -or $DeploymentMetadata -notmatch "liveBundleDeployStatus=(CURRENT|DOCS_TOOLING_ONLY_DRIFT)") {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=CURRENT_ORIGIN_MAIN") {
    } elseif ($DeploymentMetadata -match "liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN" -and $originDeltaStatus -eq "DOCS_TOOLING_ONLY_DRIFT") {
    } else {
        $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
    }

    [pscustomobject]@{
        DeploymentStatus = $deploymentStatus
        OriginStatus = $originStatus
        OriginDeltaStatus = $originDeltaStatus
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
            "Get-LiveReadinessDeployRequirement",
            "Write-PartialDeploymentMetadata",
            "Assert-DeploymentMetadataCurrentOrStop",
            "ContinueWhenRuntimeStale",
            "stale deployment metadata",
            "read_only_bundle_error=DEPLOYED_RUNTIME_NOT_CURRENT",
            "child smokes were skipped",
            'Use -ContinueWhenRuntimeStale only for diagnostic stale-runtime child-smoke output.',
            "Assert-ReadOnlyCommandSucceeded",
            "TrimStart([char]0xFEFF)",
            '$ErrorActionPreference = "Continue"',
            "sed '1s/^\xEF\xBB\xBF//'",
            "SSH_AUTH_FAILED",
            "SSH_CONNECT_FAILED",
            "SSH_COMMAND_FAILED",
            "READ_ONLY_SMOKE_FAILED",
            "read_only_bundle_error=",
            '$partialBlockers = @("LIVE_READINESS_EVIDENCE_UNAVAILABLE")',
            '$partialBlockers = @("LIVE_READINESS_EVIDENCE_UNAVAILABLE", "DEPLOYED_RUNTIME_NOT_CURRENT")',
            'bundle_blockers=',
            'bundle_blocker_summary=',
            'New-BlockerSummary -Blockers $partialBlockers',
            "deployment_metadata_status=",
            "origin_metadata_status=",
            "origin_delta_status=",
            "origin_runtime_delta_files=",
            "live_review_packet_allowed=false",
            "deploy_required_before_live_review=unknown",
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
            "READ_ONLY_SMOKE_FAILED",
            "DEPLOYED_RUNTIME_NOT_CURRENT",
            "LIVE_READINESS_EVIDENCE_UNAVAILABLE",
            "live_review_packet_allowed=false",
            "bundle_verdict=NO_EVIDENCE",
            "ContinueWhenRuntimeStale",
            "not live-readiness evidence",
            "complete evidence"
        )) {
        if ($handoffText -notmatch [regex]::Escape($pattern)) {
            throw "live readiness handoff docs missing SSH failure marker: $pattern"
        }
    }

    if ($splitStatusPath -and ((Get-Content -Raw -LiteralPath $splitStatusPath) -notmatch "fix SSH access, key selection, or the\s+failing\s+read-only smoke and rerun")) {
        throw "split acceptance status must explain SSH access failure next action"
    }
}

function Assert-RequireReadyGuard {
    $bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
    $bundleText = Get-Content -Raw -LiteralPath $bundlePath

    foreach ($pattern in @(
            '[switch]$RequireReady',
            '$uniqueBlockers = @($blockers | Select-Object -Unique)',
            'if ($uniqueBlockers.Count -eq 0)',
            'bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
            'live_review_packet_allowed=true',
            'live_review_packet_allowed=false',
            'deploy_required_before_live_review=true',
            'deploy_required_before_live_review=false',
            'bundle_verdict=NOT_READY',
            'if ($RequireReady)',
            'Live readiness bundle is not ready:'
        )) {
        if ($bundleText -notmatch [regex]::Escape($pattern)) {
            throw "live readiness bundle missing RequireReady guard marker: $pattern"
        }
    }

    $summaryMatch = [regex]::Match(
        $bundleText,
        'if \(\$uniqueBlockers\.Count -eq 0\) \{[\s\S]*?bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED[\s\S]*?\} else \{[\s\S]*?bundle_verdict=NOT_READY[\s\S]*?if \(\$RequireReady\) \{[\s\S]*?Live readiness bundle is not ready:',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $summaryMatch.Success) {
        throw "RequireReady must throw only from the NOT_READY branch after blocker computation"
    }

    $readyMarkers = [regex]::Match(
        $bundleText,
        'if \(\$uniqueBlockers\.Count -eq 0\) \{[\s\S]*?live_review_packet_allowed=true[\s\S]*?deploy_required_before_live_review=false[\s\S]*?bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $readyMarkers.Success) {
        throw "ready branch must explicitly allow a review packet and clear deploy requirement"
    }

    $notReadyMarkers = [regex]::Match(
        $bundleText,
        '\} else \{[\s\S]*?live_review_packet_allowed=false[\s\S]*?DEPLOYED_RUNTIME_NOT_CURRENT[\s\S]*?deploy_required_before_live_review=true[\s\S]*?bundle_verdict=NOT_READY',
        [System.Text.RegularExpressions.RegexOptions]::Multiline
    )
    if (-not $notReadyMarkers.Success) {
        throw "not-ready branch must explicitly block review packets and require deploy when runtime is not current"
    }
}

Assert-BundleFailureMarkers
Assert-RequireReadyGuard

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
    -Name "server worktree behind origin main docs tooling only" `
    -DeploymentMetadata @"
liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN
liveBundleDeployStatus=CURRENT
origin_delta_status=DOCS_TOOLING_ONLY_DRIFT
"@ `
    -ExpectedDeploymentStatus "CURRENT" `
    -ExpectedOriginStatus "WORKTREE_NOT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $false

Assert-MetadataCase `
    -Name "server worktree behind origin main runtime delta" `
    -DeploymentMetadata @"
liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN
liveBundleDeployStatus=CURRENT
origin_delta_status=RUNTIME_DRIFT
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

Assert-MetadataCase `
    -Name "missing deploy metadata fails closed" `
    -DeploymentMetadata @"
liveBundleOriginStatus=CURRENT_ORIGIN_MAIN
"@ `
    -ExpectedDeploymentStatus "UNKNOWN" `
    -ExpectedOriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectRuntimeCurrentBlocker $true

Assert-MetadataCase `
    -Name "missing origin metadata fails closed" `
    -DeploymentMetadata @"
liveBundleDeployStatus=CURRENT
"@ `
    -ExpectedDeploymentStatus "CURRENT" `
    -ExpectedOriginStatus "UNKNOWN" `
    -ExpectRuntimeCurrentBlocker $true

Assert-MetadataCase `
    -Name "empty metadata fails closed" `
    -DeploymentMetadata "" `
    -ExpectedDeploymentStatus "UNKNOWN" `
    -ExpectedOriginStatus "UNKNOWN" `
    -ExpectRuntimeCurrentBlocker $true

Write-Host "[live-bundle-metadata-test] OK"
