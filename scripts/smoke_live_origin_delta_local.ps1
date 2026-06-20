param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param(
        [string]$Text,
        [string]$Prefix
    )

    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return $null
    }
    return $line.Substring($Prefix.Length)
}

function Get-DeltaPathKind {
    param([string]$Path)

    if ($Path -eq ".gitattributes" `
            -or $Path -eq ".gitignore" `
            -or $Path -eq "AGENTS.md" `
            -or $Path -eq "INTERNAL_API_TODO.md" `
            -or $Path -eq "README.md" `
            -or $Path -eq "SERVICE_BOUNDARY.md" `
            -or $Path -eq "SPLIT_PROGRESS.md" `
            -or $Path -like "docs/*" `
            -or $Path -eq "deploy.sh" `
            -or $Path -like "scripts/*.ps1" `
            -or $Path -eq "scripts/install_nginx_path.sh" `
            -or $Path -eq "scripts/rewrite_nginx_trading_routes.awk" `
            -or $Path -eq "scripts/check_server_runtime_log.sh" `
            -or $Path -eq "scripts/verify_server.sh") {
        return "docs-tooling"
    }
    return "runtime"
}

function Assert-GitCommitishSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[a-fA-F0-9]{40}$") {
        throw "$Name is not a full git commit hash."
    }
}

$metadataScript = Join-Path $PSScriptRoot "smoke_live_deployment_metadata_ssh.ps1"
if (-not (Test-Path -LiteralPath $metadataScript)) {
    throw "Missing metadata smoke script: $metadataScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for origin delta smoke."
}

$metadataArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $metadataScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $metadataOutput = & $powerShell.Source @metadataArgs 2>&1
    $metadataExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$metadataText = ($metadataOutput | Out-String)
$serverWorktreeCommit = Get-LastPrefixedValue -Text $metadataText -Prefix "worktreeCommit="
$originMainCommit = Get-LastPrefixedValue -Text $metadataText -Prefix "originMainCommit="
$originMetadataStatus = Get-LastPrefixedValue -Text $metadataText -Prefix "origin_metadata_status="
$deploymentMetadataStatus = Get-LastPrefixedValue -Text $metadataText -Prefix "deployment_metadata_status="

Write-Host "[live-origin-delta-local] read-only origin delta classifier"
Write-Host "scope=READ_ONLY; runs metadata-only SSH smoke and local git diff only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, fetch, restart, or nginx state changed."
Write-Host "source_smoke=smoke_live_deployment_metadata_ssh.ps1"
Write-Host "source_smoke_exit_code=$metadataExitCode"
Write-Host "server_worktree_commit=$serverWorktreeCommit"
Write-Host "origin_main_commit=$originMainCommit"
Write-Host "deployment_metadata_status=$deploymentMetadataStatus"
Write-Host "origin_metadata_status=$originMetadataStatus"

$classification = "NO_LOCAL_EVIDENCE"
$deltaFiles = @()
$docsToolingDeltaFiles = @()
$runtimeDeltaFiles = @()
$localEvidence = $false

if ($metadataExitCode -eq 0 -and $serverWorktreeCommit -eq $originMainCommit -and -not [string]::IsNullOrWhiteSpace($serverWorktreeCommit)) {
    $classification = "CURRENT_ORIGIN_MAIN"
    $localEvidence = $true
} elseif ($metadataExitCode -eq 0) {
    try {
        Assert-GitCommitishSafe -Name "server_worktree_commit" -Value $serverWorktreeCommit
        Assert-GitCommitishSafe -Name "origin_main_commit" -Value $originMainCommit
        git cat-file -e "$serverWorktreeCommit^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "server worktree commit is not available in local git object database"
        }
        git cat-file -e "$originMainCommit^{commit}" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "origin main commit is not available in local git object database"
        }

        $deltaFiles = @(git diff --name-only $serverWorktreeCommit $originMainCommit)
        foreach ($path in $deltaFiles) {
            if ([string]::IsNullOrWhiteSpace($path)) {
                continue
            }
            if ((Get-DeltaPathKind -Path $path) -eq "runtime") {
                $runtimeDeltaFiles += $path
            } else {
                $docsToolingDeltaFiles += $path
            }
        }
        $localEvidence = $true
        if ($runtimeDeltaFiles.Count -gt 0) {
            $classification = "RUNTIME_DRIFT"
        } else {
            $classification = "DOCS_TOOLING_ONLY_DRIFT"
        }
    } catch {
        Write-Host "origin_delta_local_error=$($_.Exception.Message)"
        $classification = "NO_LOCAL_EVIDENCE"
    }
}

Write-Host "origin_delta_local_evidence=$($localEvidence.ToString().ToLowerInvariant())"
Write-Host "origin_delta_status=$classification"
Write-Host "origin_delta_files=$($deltaFiles.Count)"
Write-Host "origin_docs_tooling_delta_files=$($docsToolingDeltaFiles.Count)"
Write-Host "origin_runtime_delta_files=$($runtimeDeltaFiles.Count)"
Write-Host ("origin_runtime_delta_paths=" + (ConvertTo-Json -Compress @($runtimeDeltaFiles)))
Write-Host "live_review_packet_allowed=false"
Write-Host "notAuthorization=read-only local classifier only; does not authorize live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, production env mutation, DB changes, external backfill/import, deploy, restart, or policy relaxation"

if ($classification -eq "CURRENT_ORIGIN_MAIN") {
    Write-Host "origin_delta_next_action=Run the full read-only live-readiness bundle; metadata-only/local classifier output is not live-readiness evidence."
} elseif ($classification -eq "DOCS_TOOLING_ONLY_DRIFT") {
    Write-Host "origin_delta_next_action=Review docs/tooling-only drift, then rerun the full read-only live-readiness bundle before drafting any live review packet."
} elseif ($classification -eq "RUNTIME_DRIFT") {
    Write-Host "origin_delta_next_action=Separately deploy and verify current origin/main, then rerun the full read-only live-readiness bundle before drafting any live review packet."
} else {
    Write-Host "origin_delta_next_action=Refresh local git evidence or rerun metadata smoke; do not use this classifier as live-readiness evidence."
}

Write-Host "[live-origin-delta-local] read-only check complete"
