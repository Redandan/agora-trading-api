param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$ReleaseId = ([DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")),
    [string]$MicrostructureForwardStartDay = $env:AGORA_MICROSTRUCTURE_FORWARD_START_DAY,
    [string]$MicrostructureDiagnosticId = $env:AGORA_MICROSTRUCTURE_DIAGNOSTIC_ID,
    [switch]$PackageOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (-not $PackageOnly) {
    if ([string]::IsNullOrWhiteSpace($SshHost) -or $SshHost.StartsWith("-") -or $SshHost -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "A safe SshHost is required."
    }
    if ([string]::IsNullOrWhiteSpace($SshKey) -or -not (Test-Path -LiteralPath $SshKey -PathType Leaf)) {
        throw "A valid SshKey is required."
    }
}
if ($ReleaseId -notmatch "^[A-Za-z0-9][A-Za-z0-9._-]*$") {
    throw "ReleaseId contains unsupported characters."
}
$bindingRequested = -not [string]::IsNullOrWhiteSpace($MicrostructureForwardStartDay)
$diagnosticProvided = -not [string]::IsNullOrWhiteSpace($MicrostructureDiagnosticId)
if ($bindingRequested -ne $diagnosticProvided) {
    throw "Microstructure binding parameters must be supplied together."
}
if ($bindingRequested) {
    $parsedStartDay = [DateTime]::MinValue
    $parsed = [DateTime]::TryParseExact(
        $MicrostructureForwardStartDay,
        "yyyy-MM-dd",
        [System.Globalization.CultureInfo]::InvariantCulture,
        [System.Globalization.DateTimeStyles]::AssumeUniversal,
        [ref]$parsedStartDay
    )
    if (-not $parsed -or $parsedStartDay.Date -le [DateTime]::UtcNow.Date) {
        throw "MicrostructureForwardStartDay must be a strictly future UTC day."
    }
    if ($MicrostructureDiagnosticId -notmatch "^[a-z0-9][a-z0-9-]{2,79}$") {
        throw "MicrostructureDiagnosticId is invalid."
    }
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
if ($gitStatus.Count -ne 0) {
    throw "Research Worker release packaging requires a clean Git worktree."
}
$gitDirty = "false"
$committedRoots = @(
    "research_pipeline",
    "research_mcp",
    "research_source",
    "research",
    "scripts/research-worker",
    "docs/autonomous-research-charter.md"
)
$expectedTopLevels = @("docs", "research", "research_mcp", "research_pipeline", "research_source", "scripts", "target")
$distJarName = "agora-trading-api-1.0-SNAPSHOT-microstructure-research.jar"

function Test-ForbiddenPackagePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $normalized = $RelativePath.Replace('\', '/')
    while ($normalized.StartsWith("./", [System.StringComparison]::Ordinal)) {
        $normalized = $normalized.Substring(2)
    }
    if ([string]::IsNullOrEmpty($normalized)) { return $false }
    if ($normalized -match '[\x00-\x1f\x7f]') { return $true }
    if ($normalized.StartsWith("/", [System.StringComparison]::Ordinal) -or $normalized -match "^[A-Za-z]:") {
        return $true
    }
    $segments = @($normalized.Split('/', [System.StringSplitOptions]::RemoveEmptyEntries))
    if ($segments -contains "..") { return $true }
    foreach ($segment in $segments) {
        if ($segment -in @(".git", ".research-state", "__pycache__")) { return $true }
    }
    $name = $segments[-1]
    return $name -match '(?i)\.(pyc|pyo|pyd|pem|p12|pfx|key)$' -or
        $name -match '(?i)^\.env(?:\.|$)' -or
        $name -match '(?i)(?:^|[._-])(secret|secrets|credential|credentials)(?:[._-]|$)'
}

function Test-AllowedPackagePath {
    param([Parameter(Mandatory = $true)][string]$RelativePath)

    $path = $RelativePath.Replace('\', '/').TrimEnd('/')
    foreach ($root in @("research_pipeline", "research_mcp", "research_source", "research")) {
        if ($path -eq $root -or $path.StartsWith($root + "/", [System.StringComparison]::Ordinal)) {
            return $true
        }
    }
    return $path -eq "scripts" -or
        $path -eq "scripts/research-worker" -or
        $path.StartsWith("scripts/research-worker/", [System.StringComparison]::Ordinal) -or
        $path -eq "docs" -or
        $path -eq "docs/autonomous-research-charter.md" -or
        $path -eq "target" -or
        $path -eq "target/microstructure-dist" -or
        $path.StartsWith("target/microstructure-dist/", [System.StringComparison]::Ordinal)
}

function Assert-ExactNames {
    param(
        [Parameter(Mandatory = $true)][string[]]$Actual,
        [Parameter(Mandatory = $true)][string[]]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $expectedSorted = @($Expected | Sort-Object -CaseSensitive)
    $actualSorted = @($Actual | Sort-Object -CaseSensitive)
    $difference = @(Compare-Object -ReferenceObject $expectedSorted -DifferenceObject $actualSorted -CaseSensitive)
    if ($difference.Count -ne 0) {
        throw "$Label differs from the frozen closure."
    }
}

function Assert-ExactDistribution {
    param([Parameter(Mandatory = $true)][string]$DistributionRoot)

    if (-not (Test-Path -LiteralPath $DistributionRoot -PathType Container)) {
        throw "Narrow microstructure distribution is missing."
    }
    $entries = @(Get-ChildItem -LiteralPath $DistributionRoot -Force -Recurse)
    foreach ($entry in $entries) {
        if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Microstructure distribution contains a symlink or reparse point."
        }
    }
    $rootNames = @(Get-ChildItem -LiteralPath $DistributionRoot -Force | ForEach-Object Name)
    Assert-ExactNames -Actual $rootNames -Expected @($distJarName, "lib") -Label "Microstructure distribution root"
    $jar = Join-Path $DistributionRoot $distJarName
    $lib = Join-Path $DistributionRoot "lib"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf) -or -not (Test-Path -LiteralPath $lib -PathType Container)) {
        throw "Narrow microstructure distribution has the wrong entry types."
    }
    $libraryEntries = @(Get-ChildItem -LiteralPath $lib -Force)
    if (@($libraryEntries | Where-Object PSIsContainer).Count -ne 0 -or $libraryEntries.Count -ne 3) {
        throw "Microstructure distribution must contain exactly three runtime library files."
    }
    $libraryNames = [string[]]@($libraryEntries | ForEach-Object Name)
    [System.Array]::Sort($libraryNames, [System.StringComparer]::Ordinal)
    if ($libraryNames[0] -notlike "jackson-annotations-*.jar" -or
            $libraryNames[1] -notlike "jackson-core-*.jar" -or
            $libraryNames[2] -notlike "jackson-databind-*.jar") {
        throw "Microstructure distribution contains unexpected runtime libraries."
    }
}

function Assert-PackageTree {
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    $rootEntries = @(Get-ChildItem -LiteralPath $PackageRoot -Force)
    Assert-ExactNames -Actual @($rootEntries | ForEach-Object Name) -Expected $expectedTopLevels -Label "Package top-level inventory"
    if (@($rootEntries | Where-Object { -not $_.PSIsContainer }).Count -ne 0) {
        throw "Package top level must contain directories only."
    }
    Assert-ExactNames -Actual @(
        Get-ChildItem -LiteralPath (Join-Path $PackageRoot "scripts") -Force | ForEach-Object Name
    ) -Expected @("research-worker") -Label "Package scripts inventory"
    Assert-ExactNames -Actual @(
        Get-ChildItem -LiteralPath (Join-Path $PackageRoot "target") -Force | ForEach-Object Name
    ) -Expected @("microstructure-dist") -Label "Package target inventory"
    Assert-ExactNames -Actual @(
        Get-ChildItem -LiteralPath (Join-Path $PackageRoot "docs") -Force | ForEach-Object Name
    ) -Expected @("autonomous-research-charter.md") -Label "Package documentation inventory"
    $charter = Get-Item -LiteralPath (Join-Path $PackageRoot "docs/autonomous-research-charter.md") -Force
    if ($charter.PSIsContainer -or
            ($charter.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "Research charter must be a regular non-reparse file."
    }

    foreach ($entry in @(Get-ChildItem -LiteralPath $PackageRoot -Force -Recurse)) {
        $relative = $entry.FullName.Substring($PackageRoot.Length + 1).Replace('\', '/')
        if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Package contains a symlink or reparse point: $relative"
        }
        if ((Test-ForbiddenPackagePath $relative) -or -not (Test-AllowedPackagePath $relative)) {
            throw "Package contains a forbidden path: $relative"
        }
    }
    Assert-ExactDistribution -DistributionRoot (Join-Path $PackageRoot "target/microstructure-dist")
}

function Get-PackageManifestLines {
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    $result = [string[]]@(
        Get-ChildItem -LiteralPath $PackageRoot -Force -Recurse -File |
            ForEach-Object {
                $relative = $_.FullName.Substring($PackageRoot.Length + 1).Replace('\', '/')
                if ((Test-ForbiddenPackagePath $relative) -or -not (Test-AllowedPackagePath $relative)) {
                    throw "Manifest source contains a forbidden path: $relative"
                }
                $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
                "$hash  $relative"
            }
    )
    [System.Array]::Sort($result, [System.StringComparer]::Ordinal)
    return $result
}

$tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\', '/')
$tempDir = Join-Path $tempRoot ("agora-research-upgrade-" + [Guid]::NewGuid().ToString("N"))
[System.IO.Directory]::CreateDirectory($tempDir) | Out-Null

try {
    $archive = Join-Path $tempDir "source.tar.gz"
    $manifest = Join-Path $tempDir "source.sha256"
    $headArchive = Join-Path $tempDir "head.tar"
    $packageRoot = Join-Path $tempDir "package"
    $roundtripRoot = Join-Path $tempDir "roundtrip"
    [System.IO.Directory]::CreateDirectory($packageRoot) | Out-Null
    [System.IO.Directory]::CreateDirectory($roundtripRoot) | Out-Null
    $targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
    $distDir = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "microstructure-dist"))
    if (-not $distDir.StartsWith($targetRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Microstructure distribution escaped target."
    }
    if ([System.IO.Directory]::Exists($distDir)) {
        [System.IO.Directory]::Delete($distDir, $true)
    }
    $mavenLog = Join-Path $tempDir "maven-build.log"
    Push-Location $repoRoot
    try {
        & mvn -o -Pmicrostructure-research-dist -DskipTests package *> $mavenLog
        if ($LASTEXITCODE -ne 0) { throw "offline microstructure distribution build failed" }
    }
    finally { Pop-Location }
    Assert-ExactDistribution -DistributionRoot $distDir

    foreach ($root in $committedRoots) {
        & git -C $repoRoot cat-file -e ("HEAD:{0}" -f $root)
        if ($LASTEXITCODE -ne 0) { throw "Required committed worker root is missing from HEAD: $root" }
    }
    & git -c core.autocrlf=false -C $repoRoot archive --format=tar -o $headArchive HEAD -- @committedRoots
    if ($LASTEXITCODE -ne 0) { throw "HEAD-only worker archive failed" }
    $headEntries = @(& tar -tf $headArchive)
    if ($LASTEXITCODE -ne 0) { throw "HEAD-only worker archive inventory failed" }
    foreach ($entry in $headEntries) {
        $normalized = $entry.Replace('\', '/').TrimEnd('/')
        if ($normalized -and ((Test-ForbiddenPackagePath $normalized) -or -not (Test-AllowedPackagePath $normalized))) {
            throw "HEAD archive contains a forbidden path: $normalized"
        }
    }
    & tar -xf $headArchive -C $packageRoot
    if ($LASTEXITCODE -ne 0) { throw "HEAD-only worker archive extraction failed" }

    $packageDist = Join-Path $packageRoot "target/microstructure-dist"
    $packageLib = Join-Path $packageDist "lib"
    [System.IO.Directory]::CreateDirectory($packageLib) | Out-Null
    [System.IO.File]::Copy((Join-Path $distDir $distJarName), (Join-Path $packageDist $distJarName), $false)
    foreach ($library in @(Get-ChildItem -LiteralPath (Join-Path $distDir "lib") -File -Force)) {
        [System.IO.File]::Copy($library.FullName, (Join-Path $packageLib $library.Name), $false)
    }

    Assert-PackageTree -PackageRoot $packageRoot
    $lines = @(Get-PackageManifestLines -PackageRoot $packageRoot)
    if ($lines.Count -eq 0) { throw "Package manifest cannot be empty." }
    $manifestText = [string]::Join("`n", [string[]]$lines) + "`n"
    [System.IO.File]::WriteAllText($manifest, $manifestText, [System.Text.UTF8Encoding]::new($false))

    & tar -czf $archive -C $packageRoot .
    if ($LASTEXITCODE -ne 0) { throw "source archive failed" }
    $roundtripEntries = @(& tar -tzf $archive)
    if ($LASTEXITCODE -ne 0) { throw "source archive inventory failed" }
    foreach ($entry in $roundtripEntries) {
        $normalized = $entry.Replace('\', '/')
        while ($normalized.StartsWith("./", [System.StringComparison]::Ordinal)) {
            $normalized = $normalized.Substring(2)
        }
        $normalized = $normalized.TrimEnd('/')
        if ($normalized -and ((Test-ForbiddenPackagePath $normalized) -or -not (Test-AllowedPackagePath $normalized))) {
            throw "Source archive contains a forbidden path: $normalized"
        }
    }
    & tar -xzf $archive -C $roundtripRoot
    if ($LASTEXITCODE -ne 0) { throw "source archive roundtrip extraction failed" }
    Assert-PackageTree -PackageRoot $roundtripRoot
    $roundtripLines = @(Get-PackageManifestLines -PackageRoot $roundtripRoot)
    if (@(Compare-Object -ReferenceObject $lines -DifferenceObject $roundtripLines -SyncWindow 0 -CaseSensitive).Count -ne 0) {
        throw "Source archive bytes differ from the manifest after roundtrip extraction."
    }

    $manifestHash = (Get-FileHash -LiteralPath $manifest -Algorithm SHA256).Hash.ToLowerInvariant()
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "RESEARCH_WORKER_PACKAGE=VALID"
    Write-Output "SOURCE_GIT_COMMIT=$gitCommit"
    Write-Output "SOURCE_FILE_COUNT=$($lines.Count)"
    Write-Output "SOURCE_MANIFEST_SHA256=$manifestHash"
    Write-Output "SOURCE_ARCHIVE_SHA256=$archiveHash"
    if ($PackageOnly) {
        Write-Output "PACKAGE_ONLY=COMPLETE_NO_NETWORK"
        return
    }

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
STAGING_DIR='$stage' RELEASE_ID='$ReleaseId' SOURCE_GIT_COMMIT='$gitCommit' SOURCE_GIT_BRANCH='$gitBranch' SOURCE_GIT_DIRTY='$gitDirty'$(if ($bindingRequested) { " MICROSTRUCTURE_FORWARD_START_DAY='$MicrostructureForwardStartDay' MICROSTRUCTURE_DIAGNOSTIC_ID='$MicrostructureDiagnosticId'" }) bash source/scripts/research-worker/install-upgrade.sh
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
