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
$scriptPath = Join-Path $PSScriptRoot "smoke_live_deployment_metadata_ssh.ps1"
$bundlePath = Join-Path $PSScriptRoot "smoke_live_readiness_bundle_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$bundleText = Get-Content -Raw -LiteralPath $bundlePath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$remediationText = Get-Content -Raw -LiteralPath $remediationPath

function Get-ClassifyPathCaseBody {
    param([string]$Text)
    $match = [regex]::Match($Text, '(?ms)classify_path\(\)\s*\{\s*case "\`\$1" in\s*(.*?)\s*\*\)\s*echo runtime')
    if (-not $match.Success) {
        throw "Could not extract classify_path case body."
    }
    ($match.Groups[1].Value -replace '\s+', ' ').Trim()
}

function Get-MetadataOnlyOutputSummary {
    param(
        [string]$DeploymentStatus,
        [string]$OriginStatus
    )

    $metadataCurrent = ($DeploymentStatus -eq "CURRENT" -or $DeploymentStatus -eq "DOCS_TOOLING_ONLY_DRIFT") `
        -and $OriginStatus -eq "CURRENT_ORIGIN_MAIN"

    [pscustomobject]@{
        MetadataCurrent = $metadataCurrent
        MetadataBlockers = if ($metadataCurrent) { @() } else { @("DEPLOYED_RUNTIME_NOT_CURRENT") }
        DeployRequiredBeforeLiveReview = if ($metadataCurrent) { "false" } else { "true" }
        LiveReviewPacketAllowed = "false"
        BundleVerdict = "NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY"
    }
}

function Assert-MetadataOnlyCase {
    param(
        [string]$Name,
        [string]$DeploymentStatus,
        [string]$OriginStatus,
        [bool]$ExpectedMetadataCurrent,
        [string[]]$ExpectedMetadataBlockers,
        [string]$ExpectedDeployRequiredBeforeLiveReview
    )

    $summary = Get-MetadataOnlyOutputSummary -DeploymentStatus $DeploymentStatus -OriginStatus $OriginStatus
    if ($summary.MetadataCurrent -ne $ExpectedMetadataCurrent) {
        throw "$Name metadata_current expected $ExpectedMetadataCurrent but got $($summary.MetadataCurrent)"
    }
    $actualBlockers = @($summary.MetadataBlockers | Sort-Object)
    $expectedBlockers = @($ExpectedMetadataBlockers | Sort-Object)
    if (($actualBlockers -join ",") -ne ($expectedBlockers -join ",")) {
        throw "$Name metadata_blockers expected [$($expectedBlockers -join ',')] but got [$($actualBlockers -join ',')]"
    }
    if ($summary.DeployRequiredBeforeLiveReview -ne $ExpectedDeployRequiredBeforeLiveReview) {
        throw "$Name deploy_required_before_live_review expected $ExpectedDeployRequiredBeforeLiveReview but got $($summary.DeployRequiredBeforeLiveReview)"
    }
    if ($summary.LiveReviewPacketAllowed -ne "false") {
        throw "$Name metadata-only output must never allow a live review packet"
    }
    if ($summary.BundleVerdict -ne "NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY") {
        throw "$Name metadata-only output must keep NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY"
    }
}

$metadataClassifyBody = Get-ClassifyPathCaseBody -Text $scriptText
$bundleClassifyBody = Get-ClassifyPathCaseBody -Text $bundleText
if ($metadataClassifyBody -ne $bundleClassifyBody) {
    throw "deployment metadata smoke classify_path allowlist drifted from live-readiness bundle."
}

foreach ($pattern in @(
        "refreshType=DEPLOYMENT_METADATA_ONLY",
        "scope=READ_ONLY",
        "liveBundleOriginStatus",
        "liveBundleDeployStatus",
        "origin_metadata_status",
        "deployment_metadata_status",
        "deploymentDocsToolingDeltaFiles",
        "deploymentRuntimeDeltaFiles",
        "metadata_blockers",
        "DEPLOYED_RUNTIME_NOT_CURRENT",
        "live_review_packet_allowed=false",
        "bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY",
        "metadata_boundary=metadata-only",
        "Get-ReadOnlyMetadataFailureClassification",
        "Assert-ReadOnlyMetadataSucceeded",
        "read_only_metadata_error=",
        "read_only_metadata_error_detail=deployment metadata smoke failed before metadata evidence could be collected",
        "read_only_metadata_error_boundary=not live-readiness evidence",
        'metadata_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]',
        "deploy_required_before_live_review=unknown",
        "SSH_AUTH_FAILED",
        "SSH_CONNECT_FAILED",
        "SSH_COMMAND_FAILED",
        "READ_ONLY_SMOKE_FAILED",
        "RequireCurrent",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "TrimStart([char]0xFEFF)",
        '$ErrorActionPreference = "Continue"',
        "git ls-remote origin refs/heads/main",
        "sed '1s/^\xEF\xBB\xBF//'",
        "tr -d",
        "bash -s"
    )) {
    Assert-Contains -Name "deployment metadata smoke script" -Text $scriptText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        '$RequireCurrent -and $text -match ''metadata_current=false''',
        'deployment metadata is not current; DEPLOYED_RUNTIME_NOT_CURRENT remains.'
    )) {
    Assert-Contains -Name "deployment metadata RequireCurrent guard" -Text $scriptText -Pattern ([regex]::Escape($pattern))
}

foreach ($pattern in @(
        "systemctl",
        "nginx -t",
        "reload nginx",
        "SPRING_FLYWAY_ENABLED=true",
        "TRADING_OKX_ENABLED=true",
        "TELEGRAM_BOT_TOKEN="
    )) {
    if ($scriptText -match [regex]::Escape($pattern)) {
        throw "deployment metadata smoke must not contain mutation marker: $pattern"
    }
}

foreach ($doc in @(
        @{ Name = "README"; Text = $readmeText },
        @{ Name = "deploy runbook"; Text = $runbookText },
        @{ Name = "remediation matrix"; Text = $remediationText }
    )) {
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "smoke_live_deployment_metadata_ssh\.ps1"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "DEPLOYMENT_METADATA_ONLY"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "metadata-only"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "DOCS_TOOLING_ONLY_DRIFT"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "metadata_current=true"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "live_review_packet_allowed=false"
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "not live-readiness evidence|not a substitute for"
}

foreach ($pattern in @(
        "deploymentDocsToolingDeltaFiles",
        "deploymentRuntimeDeltaFiles",
        "src/test/*",
        "DOCS_TOOLING_ONLY_DRIFT",
        "RUNTIME_DRIFT",
        "UNKNOWN_DEPLOY_METADATA",
        "CURRENT_ORIGIN_MAIN",
        "WORKTREE_NOT_ORIGIN_MAIN"
    )) {
    Assert-Contains -Name "bundle metadata parity marker" -Text $bundleText -Pattern ([regex]::Escape($pattern))
    Assert-Contains -Name "standalone metadata parity marker" -Text $scriptText -Pattern ([regex]::Escape($pattern))
}

Assert-MetadataOnlyCase `
    -Name "current metadata-only remains non-live evidence" `
    -DeploymentStatus "CURRENT" `
    -OriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectedMetadataCurrent $true `
    -ExpectedMetadataBlockers @() `
    -ExpectedDeployRequiredBeforeLiveReview "false"

Assert-MetadataOnlyCase `
    -Name "docs tooling drift metadata-only remains non-live evidence" `
    -DeploymentStatus "DOCS_TOOLING_ONLY_DRIFT" `
    -OriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectedMetadataCurrent $true `
    -ExpectedMetadataBlockers @() `
    -ExpectedDeployRequiredBeforeLiveReview "false"

Assert-MetadataOnlyCase `
    -Name "origin drift metadata-only stays blocked" `
    -DeploymentStatus "CURRENT" `
    -OriginStatus "WORKTREE_NOT_ORIGIN_MAIN" `
    -ExpectedMetadataCurrent $false `
    -ExpectedMetadataBlockers @("DEPLOYED_RUNTIME_NOT_CURRENT") `
    -ExpectedDeployRequiredBeforeLiveReview "true"

Assert-MetadataOnlyCase `
    -Name "runtime drift metadata-only stays blocked" `
    -DeploymentStatus "RUNTIME_DRIFT" `
    -OriginStatus "CURRENT_ORIGIN_MAIN" `
    -ExpectedMetadataCurrent $false `
    -ExpectedMetadataBlockers @("DEPLOYED_RUNTIME_NOT_CURRENT") `
    -ExpectedDeployRequiredBeforeLiveReview "true"

Assert-MetadataOnlyCase `
    -Name "unknown metadata-only stays blocked" `
    -DeploymentStatus "UNKNOWN_DEPLOY_METADATA" `
    -OriginStatus "UNKNOWN_ORIGIN_MAIN" `
    -ExpectedMetadataCurrent $false `
    -ExpectedMetadataBlockers @("DEPLOYED_RUNTIME_NOT_CURRENT") `
    -ExpectedDeployRequiredBeforeLiveReview "true"

Write-Host "[live-deployment-metadata-smoke-test] OK"
