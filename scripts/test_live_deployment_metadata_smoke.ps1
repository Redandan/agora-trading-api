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
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$remediationPath = Join-Path $repoRoot "docs/live-readiness-blocker-remediation.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$readmeText = Get-Content -Raw -LiteralPath $readmePath
$runbookText = Get-Content -Raw -LiteralPath $runbookPath
$remediationText = Get-Content -Raw -LiteralPath $remediationPath

foreach ($pattern in @(
        "refreshType=DEPLOYMENT_METADATA_ONLY",
        "scope=READ_ONLY",
        "liveBundleOriginStatus",
        "liveBundleDeployStatus",
        "origin_metadata_status",
        "deployment_metadata_status",
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

Write-Host "[live-deployment-metadata-smoke-test] OK"
