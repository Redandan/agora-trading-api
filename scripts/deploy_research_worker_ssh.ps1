param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$StateDir = (Join-Path $PSScriptRoot "..\.research-state"),
    [string]$ReleaseId = ([DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ"))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "SshHost contains unsupported characters."
    }
}

function Assert-ReleaseIdSafe {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._-]*$") {
        throw "ReleaseId contains unsupported characters."
    }
}

function Invoke-CheckedNative {
    param(
        [string]$Command,
        [string[]]$Arguments
    )
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Command failed with exit code $LASTEXITCODE"
    }
}

function Write-HashManifest {
    param(
        [string]$BaseDir,
        [string]$Output,
        [scriptblock]$Include
    )
    $base = [System.IO.Path]::GetFullPath($BaseDir).TrimEnd('\', '/')
    $lines = Get-ChildItem -LiteralPath $base -Recurse -File |
        Where-Object $Include |
        ForEach-Object {
            $relative = $_.FullName.Substring($base.Length + 1).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        } |
        Sort-Object
    if (-not $lines) {
        throw "No files selected for manifest: $BaseDir"
    }
    [System.IO.File]::WriteAllLines(
        $Output,
        [string[]]$lines,
        [System.Text.UTF8Encoding]::new($false)
    )
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}
Assert-SshHostSafe -Value $SshHost
Assert-ReleaseIdSafe -Value $ReleaseId

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$resolvedState = [System.IO.Path]::GetFullPath($StateDir)
if (-not (Test-Path -LiteralPath $resolvedState -PathType Container)) {
    throw "StateDir does not exist: $resolvedState"
}
if (Test-Path -LiteralPath (Join-Path $resolvedState "pipeline.lock")) {
    throw "Research state is locked; refusing migration snapshot."
}

foreach ($required in @("tar", "ssh", "scp")) {
    if (-not (Get-Command $required -ErrorAction SilentlyContinue)) {
        throw "$required is not available on PATH."
    }
}

$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempDir = Join-Path $tempRoot ("agora-research-deploy-" + [Guid]::NewGuid().ToString("N"))
[System.IO.Directory]::CreateDirectory($tempDir) | Out-Null

try {
    $sourceArchive = Join-Path $tempDir "source.tar.gz"
    $stateArchive = Join-Path $tempDir "state.tar.gz"
    $sourceManifest = Join-Path $tempDir "source.sha256"
    $stateManifest = Join-Path $tempDir "state.sha256"

    $sourceRoots = @("research_pipeline", "research", "docs", "src", "scripts/research-worker", "pom.xml")
    foreach ($sourceRoot in $sourceRoots) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $sourceRoot))) {
            throw "Required worker source is missing: $sourceRoot"
        }
    }

    Push-Location $repoRoot
    try {
        Invoke-CheckedNative -Command "tar" -Arguments @(
            "-czf", $sourceArchive,
            "--exclude=__pycache__", "--exclude=*.pyc", "--exclude=target",
            "research_pipeline", "research", "docs", "src", "scripts/research-worker", "pom.xml"
        )
    }
    finally {
        Pop-Location
    }

    Push-Location $resolvedState
    try {
        Invoke-CheckedNative -Command "tar" -Arguments @(
            "-czf", $stateArchive,
            "--exclude=pipeline.lock", "--exclude=authority.json", "."
        )
    }
    finally {
        Pop-Location
    }

    $sourceTopLevel = @("research_pipeline", "research", "docs", "src", "scripts\research-worker")
    Write-HashManifest -BaseDir $repoRoot -Output $sourceManifest -Include {
        $relative = $_.FullName.Substring($repoRoot.Length + 1)
        $inTree = $false
        foreach ($root in $sourceTopLevel) {
            if ($relative -eq $root -or $relative.StartsWith($root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
                $inTree = $true
                break
            }
        }
        ($inTree -or $relative -eq "pom.xml") -and
            $relative -notmatch '(^|\\)__pycache__(\\|$)' -and
            $relative -notmatch '\.pyc$' -and
            $relative -notmatch '(^|\\)target(\\|$)'
    }
    Write-HashManifest -BaseDir $resolvedState -Output $stateManifest -Include {
        $_.Name -ne "pipeline.lock" -and $_.Name -ne "authority.json"
    }

    $remoteStage = "/home/ubuntu/.cache/agora-research-deploy/$ReleaseId"
    $prepare = "set -euo pipefail; umask 077; mkdir -p '$remoteStage/source' '$remoteStage/state'"
    Invoke-CheckedNative -Command "ssh" -Arguments @(
        "-i", $SshKey, "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
        $SshHost, $prepare
    )

    foreach ($file in @($sourceArchive, $stateArchive, $sourceManifest, $stateManifest)) {
        Invoke-CheckedNative -Command "scp" -Arguments @(
            "-i", $SshKey, "-o", "BatchMode=yes", "-o", "ConnectTimeout=10",
            $file, "${SshHost}:$remoteStage/"
        )
    }

    $remoteInstall = @"
set -euo pipefail
cd '$remoteStage'
tar -xzf source.tar.gz -C source
tar -xzf state.tar.gz -C state
STAGING_DIR='$remoteStage' RELEASE_ID='$ReleaseId' bash source/scripts/research-worker/install-release.sh
rm -f -- source.tar.gz state.tar.gz source.sha256 state.sha256 authority.json
rm -rf -- source state
printf 'RESEARCH_WORKER_RELEASE=%s\n' '$ReleaseId'
printf 'RESEARCH_WORKER_TIMER=INSTALLED_DISABLED\n'
"@
    $remoteInstall | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) {
        throw "Remote Research Worker installation failed with exit code $LASTEXITCODE"
    }
}
finally {
    $resolvedTemp = [System.IO.Path]::GetFullPath($tempDir)
    if ($resolvedTemp.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) -and
            [System.IO.Directory]::Exists($resolvedTemp)) {
        [System.IO.Directory]::Delete($resolvedTemp, $true)
    }
}
