param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$ReleaseId = ([DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ"))
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
    throw "A safe SshHost is required."
}
if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
    throw "A valid SshKey is required."
}
if ($ReleaseId -notmatch "^[A-Za-z0-9][A-Za-z0-9._-]*$") {
    throw "ReleaseId contains unsupported characters."
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $gitCommit -notmatch "^[0-9a-f]{40}$") {
    throw "Unable to resolve a valid source Git commit."
}
$gitBranch = (& git -C $repoRoot branch --show-current).Trim()
if ([string]::IsNullOrWhiteSpace($gitBranch)) { $gitBranch = "DETACHED" }
if ($gitBranch -notmatch "^[A-Za-z0-9][A-Za-z0-9._/-]*$") {
    throw "Source Git branch contains unsupported characters."
}
$gitStatus = @(& git -C $repoRoot status --porcelain)
if ($LASTEXITCODE -ne 0) { throw "Unable to inspect source Git status." }
$gitDirty = if ($gitStatus.Count -eq 0) { "false" } else { "true" }
$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempDir = Join-Path $tempRoot ("agora-research-upgrade-" + [Guid]::NewGuid().ToString("N"))
[System.IO.Directory]::CreateDirectory($tempDir) | Out-Null

try {
    $archive = Join-Path $tempDir "source.tar.gz"
    $manifest = Join-Path $tempDir "source.sha256"
    $roots = @("research_pipeline", "research_mcp", "research_source", "research", "docs", "src", "scripts/research-worker", "pom.xml")
    foreach ($root in $roots) {
        if (-not (Test-Path -LiteralPath (Join-Path $repoRoot $root))) {
            throw "Required worker source is missing: $root"
        }
    }

    Push-Location $repoRoot
    try {
        & tar -czf $archive --exclude=__pycache__ --exclude=*.pyc --exclude=target @roots
        if ($LASTEXITCODE -ne 0) { throw "source archive failed" }
    }
    finally { Pop-Location }

    $topLevel = @("research_pipeline", "research_mcp", "research_source", "research", "docs", "src", "scripts\research-worker")
    $lines = Get-ChildItem -LiteralPath $repoRoot -Recurse -File |
        Where-Object {
            $relative = $_.FullName.Substring($repoRoot.Length + 1)
            $included = $relative -eq "pom.xml"
            foreach ($root in $topLevel) {
                if ($relative -eq $root -or $relative.StartsWith($root + '\', [System.StringComparison]::OrdinalIgnoreCase)) {
                    $included = $true
                    break
                }
            }
            $included -and $relative -notmatch '(^|\\)__pycache__(\\|$)' -and
                $relative -notmatch '\.pyc$' -and $relative -notmatch '(^|\\)target(\\|$)'
        } |
        ForEach-Object {
            $relative = $_.FullName.Substring($repoRoot.Length + 1).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        } | Sort-Object
    [System.IO.File]::WriteAllLines($manifest, [string[]]$lines, [System.Text.UTF8Encoding]::new($false))

    $stage = "/home/ubuntu/.cache/agora-research-upgrade/$ReleaseId"
    & ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "set -euo pipefail; umask 077; mkdir -p '$stage/source'"
    if ($LASTEXITCODE -ne 0) { throw "remote staging failed" }
    foreach ($file in @($archive, $manifest)) {
        & scp -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $file "${SshHost}:$stage/"
        if ($LASTEXITCODE -ne 0) { throw "upload failed" }
    }

    $remote = @"
set -euo pipefail
cd '$stage'
tar -xzf source.tar.gz -C source
STAGING_DIR='$stage' RELEASE_ID='$ReleaseId' SOURCE_GIT_COMMIT='$gitCommit' SOURCE_GIT_BRANCH='$gitBranch' SOURCE_GIT_DIRTY='$gitDirty' bash source/scripts/research-worker/install-upgrade.sh
rm -f -- source.tar.gz source.sha256
rm -rf -- source
printf 'RESEARCH_WORKER_RELEASE=%s\n' '$ReleaseId'
printf 'SOURCE_GIT_COMMIT=%s\n' '$gitCommit'
printf 'SOURCE_GIT_DIRTY=%s\n' '$gitDirty'
"@
    $remote | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s"
    if ($LASTEXITCODE -ne 0) { throw "remote Research Worker upgrade failed" }
}
finally {
    $resolved = [System.IO.Path]::GetFullPath($tempDir)
    if ($resolved.StartsWith($tempRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase) -and
            [System.IO.Directory]::Exists($resolved)) {
        [System.IO.Directory]::Delete($resolved, $true)
    }
}
