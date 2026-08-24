param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$ReleaseId = ([DateTimeOffset]::UtcNow.ToString("yyyyMMddTHHmmssZ")),
    [string]$MicrostructureForwardStartDay = $env:AGORA_MICROSTRUCTURE_FORWARD_START_DAY,
    [string]$MicrostructureDiagnosticId = $env:AGORA_MICROSTRUCTURE_DIAGNOSTIC_ID,
    [switch]$PreserveBoundDataPlane,
    [switch]$PackageOnly,
    [switch]$IncludeCarryDistribution
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
    if ($MicrostructureDiagnosticId -notmatch "^okx-btcusdt-microstructure-forward-v3r1-[0-9]{8}-r[0-9]+$") {
        throw "MicrostructureDiagnosticId is not an exact V3R1 diagnostic identity."
    }
    $expectedPrefix = "okx-btcusdt-microstructure-forward-v3r1-" + $parsedStartDay.ToString("yyyyMMdd") + "-"
    if (-not $MicrostructureDiagnosticId.StartsWith($expectedPrefix, [System.StringComparison]::Ordinal)) {
        throw "MicrostructureDiagnosticId does not match MicrostructureForwardStartDay."
    }
}
if ($PreserveBoundDataPlane -and $bindingRequested) {
    throw "PreserveBoundDataPlane rejects binding creation or replacement parameters."
}
if ($PreserveBoundDataPlane -and $PackageOnly) {
    throw "PreserveBoundDataPlane is an explicit installation mode, not a package-only mode."
}

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$gitCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $gitCommit -notmatch "^[0-9a-f]{40}$") {
    throw "Unable to resolve a valid source Git commit."
}
$gitBranchLines = @(& git -C $repoRoot branch --show-current)
if ($LASTEXITCODE -ne 0) { throw "Unable to resolve the source Git branch." }
if ($gitBranchLines.Count -eq 0) {
    $gitBranch = "DETACHED"
}
elseif ($gitBranchLines.Count -eq 1) {
    $gitBranch = ([string]$gitBranchLines[0]).Trim()
    if ([string]::IsNullOrWhiteSpace($gitBranch)) { $gitBranch = "DETACHED" }
}
else {
    throw "Source Git branch output is ambiguous."
}
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
$carryDistJarName = "agora-trading-api-1.0-SNAPSHOT-dra-crypto-carry-research.jar"
$jacksonNames = @(
    "jackson-annotations-2.18.3.jar",
    "jackson-core-2.18.3.jar",
    "jackson-databind-2.18.3.jar"
)
$jacksonHashes = @{
    "jackson-annotations-2.18.3.jar" = "8aa5740d80b5a5025508b41bbadbaa1fb3772267c628b2e30681a4f45f8b8931"
    "jackson-core-2.18.3.jar" = "056bc4d3e5e53ce821450fa97b3f9e0f8dde125cf6da6884353bb1f09582e1d9"
    "jackson-databind-2.18.3.jar" = "510bdda75a7a6186c5bf33b851239488a1450906ae5757121f2e1cc48a7e108f"
}
$carryClassFamilies = @(
    "OkxDraCryptoCarryForwardSource",
    "OkxDraCryptoCarryProducerEnvelopeV2",
    "OkxDraCryptoCarryCanonicalDropV2",
    "OkxDraCryptoCarryNetworkDeniedIntakeV2",
    "OkxDraCryptoCarryPhaseCli"
)

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
        $path.StartsWith("target/microstructure-dist/", [System.StringComparison]::Ordinal) -or
        ($IncludeCarryDistribution -and (
            $path -eq "target/dra-crypto-carry-dist" -or
            $path.StartsWith("target/dra-crypto-carry-dist/", [System.StringComparison]::Ordinal)
        ))
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

function Assert-ExactOptInDistribution {
    param(
        [Parameter(Mandatory = $true)][string]$DistributionRoot,
        [Parameter(Mandatory = $true)][ValidateSet("Microstructure", "Carry")][string]$Kind
    )

    if (-not (Test-Path -LiteralPath $DistributionRoot -PathType Container)) {
        throw "$Kind distribution is missing."
    }
    foreach ($entry in @(Get-ChildItem -LiteralPath $DistributionRoot -Force -Recurse)) {
        if (($entry.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Kind distribution contains a symlink or reparse point."
        }
    }

    $jarName = if ($Kind -eq "Microstructure") { $distJarName } else { $carryDistJarName }
    Assert-ExactNames -Actual @(
        Get-ChildItem -LiteralPath $DistributionRoot -Force | ForEach-Object Name
    ) -Expected @($jarName, "lib") -Label "$Kind distribution root"
    $jar = Join-Path $DistributionRoot $jarName
    $lib = Join-Path $DistributionRoot "lib"
    if (-not (Test-Path -LiteralPath $jar -PathType Leaf) -or -not (Test-Path -LiteralPath $lib -PathType Container)) {
        throw "$Kind distribution has the wrong entry types."
    }

    $libraryEntries = @(Get-ChildItem -LiteralPath $lib -Force)
    if (@($libraryEntries | Where-Object PSIsContainer).Count -ne 0) {
        throw "$Kind distribution libraries must be regular files."
    }
    Assert-ExactNames -Actual @($libraryEntries | ForEach-Object Name) -Expected $jacksonNames -Label "$Kind Jackson inventory"
    foreach ($library in $libraryEntries) {
        if (($library.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "$Kind distribution contains a linked Jackson library."
        }
        $actualHash = (Get-FileHash -LiteralPath $library.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $jacksonHashes[$library.Name]) {
            throw "$Kind distribution contains an unexpected Jackson byte identity."
        }
    }

    $jarEntries = @(& tar -tf $jar)
    if ($LASTEXITCODE -ne 0) { throw "$Kind classifier jar inventory failed." }
    $classes = [System.Collections.Generic.List[string]]::new()
    foreach ($entry in $jarEntries) {
        $normalized = $entry.Replace('\', '/').TrimStart('./')
        if ($normalized.StartsWith("BOOT-INF/", [System.StringComparison]::Ordinal)) {
            throw "$Kind classifier jar contains BOOT-INF."
        }
        if ($normalized.EndsWith(".class", [System.StringComparison]::Ordinal)) {
            $classes.Add($normalized)
        }
    }
    if ($classes.Count -eq 0) { throw "$Kind classifier jar contains no classes." }

    if ($Kind -eq "Microstructure") {
        foreach ($class in $classes) {
            if ($class -notmatch '^com/agora/research/OkxMicrostructure[^/]*\.class$') {
                throw "Microstructure classifier jar contains an unexpected class: $class"
            }
        }
    }
    else {
        foreach ($class in $classes) {
            $allowed = $false
            foreach ($family in $carryClassFamilies) {
                $familyPath = "com/agora/research/$family"
                if ($class -eq ($familyPath + ".class") -or
                        ($class.StartsWith($familyPath + '$', [System.StringComparison]::Ordinal) -and
                            $class.EndsWith(".class", [System.StringComparison]::Ordinal))) {
                    $allowed = $true
                    break
                }
            }
            if (-not $allowed) { throw "Carry classifier jar contains an unexpected class: $class" }
        }
        foreach ($family in $carryClassFamilies) {
            if (-not $classes.Contains("com/agora/research/$family.class")) {
                throw "Carry classifier jar is missing a required class family: $family"
            }
        }
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
    $expectedTargetInventory = if ($IncludeCarryDistribution) {
        @("microstructure-dist", "dra-crypto-carry-dist")
    }
    else {
        @("microstructure-dist")
    }
    Assert-ExactNames -Actual @(
        Get-ChildItem -LiteralPath (Join-Path $PackageRoot "target") -Force | ForEach-Object Name
    ) -Expected $expectedTargetInventory -Label "Package target inventory"
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
    Assert-CandidatePoolEvidenceClosure -PackageRoot $PackageRoot
    if ($IncludeCarryDistribution) {
        Assert-ExactOptInDistribution -DistributionRoot (Join-Path $PackageRoot "target/microstructure-dist") -Kind Microstructure
        Assert-ExactOptInDistribution -DistributionRoot (Join-Path $PackageRoot "target/dra-crypto-carry-dist") -Kind Carry
    }
    else {
        Assert-ExactDistribution -DistributionRoot (Join-Path $PackageRoot "target/microstructure-dist")
    }
}

function Assert-CandidatePoolEvidenceClosure {
    param([Parameter(Mandatory = $true)][string]$PackageRoot)

    $catalogPath = Join-Path $PackageRoot "research_pipeline/pre-candidate-pool.v1.json"
    if (-not (Test-Path -LiteralPath $catalogPath -PathType Leaf)) {
        throw "Candidate pool catalog is missing from the staged package."
    }
    try {
        $catalog = Get-Content -Raw -LiteralPath $catalogPath | ConvertFrom-Json -Depth 100
    }
    catch {
        throw "Candidate pool catalog is not valid JSON: $($_.Exception.Message)"
    }

    $packageRootFull = [System.IO.Path]::GetFullPath($PackageRoot).TrimEnd('\', '/')
    $bindings = @(
        @($catalog.families) + @($catalog.closed_families) |
            ForEach-Object { @($_.evidence_bindings) }
    )
    if ($bindings.Count -eq 0) {
        throw "Candidate pool catalog has no evidence bindings."
    }
    foreach ($binding in $bindings) {
        $relative = ([string]$binding.path).Replace('\', '/')
        $expectedHash = ([string]$binding.sha256).ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($relative) -or
                [System.IO.Path]::IsPathRooted($relative) -or
                $relative -match '(^|/)\.\.(/|$)' -or
                $relative.StartsWith("/", [System.StringComparison]::Ordinal)) {
            throw "Candidate pool evidence binding escaped the package: $relative"
        }
        if ($expectedHash -notmatch '^[0-9a-f]{64}$') {
            throw "Candidate pool evidence binding has an invalid SHA-256: $relative"
        }
        $resolved = [System.IO.Path]::GetFullPath(
            (Join-Path $packageRootFull $relative.Replace('/', [System.IO.Path]::DirectorySeparatorChar))
        )
        if (-not $resolved.StartsWith(
                $packageRootFull + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Candidate pool evidence binding escaped the package: $relative"
        }
        if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) {
            throw "Candidate pool evidence binding is not packaged: $relative"
        }
        $item = Get-Item -LiteralPath $resolved -Force
        if ($item.PSIsContainer -or
                ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
            throw "Candidate pool evidence binding is not a regular file: $relative"
        }
        $actualHash = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $expectedHash) {
            throw "Candidate pool evidence binding hash mismatch: $relative"
        }
    }
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
    $carryDistDir = [System.IO.Path]::GetFullPath((Join-Path $targetRoot "dra-crypto-carry-dist"))
    if (-not $distDir.StartsWith($targetRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Microstructure distribution escaped target."
    }
    if (-not $carryDistDir.StartsWith($targetRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Carry distribution escaped target."
    }
    if ([System.IO.Directory]::Exists($distDir)) {
        [System.IO.Directory]::Delete($distDir, $true)
    }
    if ($IncludeCarryDistribution -and [System.IO.Directory]::Exists($carryDistDir)) {
        [System.IO.Directory]::Delete($carryDistDir, $true)
    }
    $mavenLog = Join-Path $tempDir "maven-build.log"
    Push-Location $repoRoot
    try {
        if ($IncludeCarryDistribution) {
            & mvn -o "-Pmicrostructure-research-dist,dra-crypto-carry-research-dist" -DskipTests package *> $mavenLog
            if ($LASTEXITCODE -ne 0) { throw "offline dual research distribution build failed" }
        }
        else {
            & mvn -o -Pmicrostructure-research-dist -DskipTests package *> $mavenLog
            if ($LASTEXITCODE -ne 0) { throw "offline microstructure distribution build failed" }
        }
    }
    finally { Pop-Location }
    if ($IncludeCarryDistribution) {
        Assert-ExactOptInDistribution -DistributionRoot $distDir -Kind Microstructure
        Assert-ExactOptInDistribution -DistributionRoot $carryDistDir -Kind Carry
    }
    else {
        Assert-ExactDistribution -DistributionRoot $distDir
    }

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
    if ($IncludeCarryDistribution) {
        $packageCarryDist = Join-Path $packageRoot "target/dra-crypto-carry-dist"
        $packageCarryLib = Join-Path $packageCarryDist "lib"
        [System.IO.Directory]::CreateDirectory($packageCarryLib) | Out-Null
        [System.IO.File]::Copy(
            (Join-Path $carryDistDir $carryDistJarName),
            (Join-Path $packageCarryDist $carryDistJarName),
            $false
        )
        foreach ($library in @(Get-ChildItem -LiteralPath (Join-Path $carryDistDir "lib") -File -Force)) {
            [System.IO.File]::Copy($library.FullName, (Join-Path $packageCarryLib $library.Name), $false)
        }
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
    if ($IncludeCarryDistribution) {
        Write-Output "CARRY_DISTRIBUTION=INCLUDED"
    }
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

    $installCarryCapability = if ($IncludeCarryDistribution -and $PreserveBoundDataPlane) { "1" } else { "0" }
    $expectedCarryState = if ($IncludeCarryDistribution) { "inactive" } else { "auto" }
    $expectedCarryRelease = if ($IncludeCarryDistribution) { $ReleaseId } else { "" }
    $remote = @"
set -euo pipefail
cd '$stage'
tar -xzf source.tar.gz -C source
preserve_mode='$(if ($PreserveBoundDataPlane) { "1" } else { "0" })'
expected_data_release='$ReleaseId'
expected_source='disabled'
expected_intake_preflight='0'
if [ "`$preserve_mode" = 1 ]; then
  [ -L /opt/agora-research-worker/current ] || { echo 'data-current symlink missing' >&2; exit 1; }
  data_current="`$(readlink -f /opt/agora-research-worker/current)"
  case "`$data_current" in /opt/agora-research-worker/releases/*) ;; *) echo 'data-current escaped immutable releases' >&2; exit 1 ;; esac
  expected_data_release="`${data_current##*/}"
  source_state="`$(systemctl is-active agora-research-microstructure-source.service 2>/dev/null || true)"
  case "`$source_state" in
    active) expected_source='active'; expected_intake_preflight='1' ;;
    inactive) expected_source='disabled' ;;
    failed) expected_source='failed-preserved' ;;
    *) echo 'microstructure source has an unsupported pre-install state' >&2; exit 1 ;;
  esac
fi
STAGING_DIR='$stage' RELEASE_ID='$ReleaseId' SOURCE_GIT_COMMIT='$gitCommit' SOURCE_GIT_BRANCH='$gitBranch' SOURCE_GIT_DIRTY='$gitDirty' PRESERVE_BOUND_DATA_PLANE="`$preserve_mode" INSTALL_CARRY_CAPABILITY='$installCarryCapability'$(if ($bindingRequested) { " MICROSTRUCTURE_FORWARD_START_DAY='$MicrostructureForwardStartDay' MICROSTRUCTURE_DIAGNOSTIC_ID='$MicrostructureDiagnosticId'" }) bash source/scripts/research-worker/install-upgrade.sh
EXPECTED_CONTROL_RELEASE_ID='$ReleaseId' EXPECTED_DATA_RELEASE_ID="`$expected_data_release" EXPECTED_CARRY_RELEASE_ID='$expectedCarryRelease' EXPECT_MICROSTRUCTURE_SOURCE="`$expected_source" EXPECT_CARRY_SOURCE='$expectedCarryState' MICROSTRUCTURE_INTAKE_PREFLIGHT="`$expected_intake_preflight" bash /opt/agora-research-worker/control-current/scripts/research-worker/verify-worker.sh
rm -f -- source.tar.gz source.sha256
rm -rf -- source
printf 'RESEARCH_WORKER_CONTROL_RELEASE=%s\n' '$ReleaseId'
printf 'RESEARCH_WORKER_DATA_RELEASE=%s\n' "`$expected_data_release"
$(if ($IncludeCarryDistribution) { "printf 'RESEARCH_WORKER_CARRY_RELEASE=%s\n' '$ReleaseId'" })
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
