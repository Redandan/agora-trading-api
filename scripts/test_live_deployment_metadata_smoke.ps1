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
        "RequireCurrent",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "git ls-remote origin refs/heads/main",
        "tr -d",
        "bash -s"
    )) {
    Assert-Contains -Name "deployment metadata smoke script" -Text $scriptText -Pattern ([regex]::Escape($pattern))
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
    Assert-Contains -Name $doc.Name -Text $doc.Text -Pattern "not live-readiness evidence|not a substitute for"
}

foreach ($pattern in @(
        "deploymentDocsToolingDeltaFiles",
        "deploymentRuntimeDeltaFiles",
        "DOCS_TOOLING_ONLY_DRIFT",
        "RUNTIME_DRIFT",
        "UNKNOWN_DEPLOY_METADATA",
        "CURRENT_ORIGIN_MAIN",
        "WORKTREE_NOT_ORIGIN_MAIN"
    )) {
    Assert-Contains -Name "bundle metadata parity marker" -Text $bundleText -Pattern ([regex]::Escape($pattern))
    Assert-Contains -Name "standalone metadata parity marker" -Text $scriptText -Pattern ([regex]::Escape($pattern))
}

Write-Host "[live-deployment-metadata-smoke-test] OK"
