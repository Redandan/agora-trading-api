Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $allowedMainPackages = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    @(
        "annotation",
        "aspect",
        "config",
        "dto",
        "enums",
        "event",
        "exception",
        "infra",
        "mcp",
        "metrics",
        "model",
        "repository",
        "scheduler",
        "security",
        "service",
        "trading",
        "util",
        "validation"
    ) | ForEach-Object { [void]$allowedMainPackages.Add($_) }

    $forbiddenNames = @(
        "address",
        "admin",
        "auth",
        "cart",
        "chat",
        "delivery",
        "flutter",
        "imageaudit",
        "issue",
        "logistics",
        "oauth",
        "order",
        "post",
        "product",
        "promo",
        "realtime",
        "search",
        "slot",
        "staking",
        "store",
        "user",
        "wallet",
        "webpush"
    )
    $allowedForbiddenSegmentPaths = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    @(
        "src/main/java/com/agora/mcp/auth",
        "src/test/java/com/agora/mcp/auth"
    ) | ForEach-Object { [void]$allowedForbiddenSegmentPaths.Add($_) }

    $trackedJavaFiles = git ls-files "src/main/java/com/agora/*.java" "src/main/java/com/agora/**/*.java" "src/test/java/com/agora/*.java" "src/test/java/com/agora/**/*.java"
    if ($LASTEXITCODE -ne 0) {
        throw "git ls-files failed while checking package boundary"
    }

    $mainPackages = $trackedJavaFiles |
        Where-Object { $_ -like "src/main/java/com/agora/*" } |
        ForEach-Object { ($_ -replace "\\", "/").Split("/")[5] } |
        Sort-Object -Unique
    foreach ($packageName in $mainPackages) {
        if (-not $allowedMainPackages.Contains($packageName)) {
            throw "Unexpected top-level main package in trading split: com.agora.$packageName"
        }
    }

    foreach ($forbidden in $forbiddenNames) {
        if ($mainPackages -contains $forbidden) {
            throw "Forbidden marketplace-style top-level package in trading split: com.agora.$forbidden"
        }
    }

    $testPackages = $trackedJavaFiles |
        Where-Object { $_ -like "src/test/java/com/agora/*" } |
        ForEach-Object { ($_ -replace "\\", "/").Split("/")[5] } |
        Sort-Object -Unique
    foreach ($packageName in $testPackages) {
        if (-not $allowedMainPackages.Contains($packageName)) {
            throw "Unexpected top-level test package in trading split: com.agora.$packageName"
        }
    }

    foreach ($file in $trackedJavaFiles) {
        $relativePath = ($file -replace "\\", "/")
        $directoryPath = Split-Path $relativePath -Parent
        if (-not $allowedForbiddenSegmentPaths.Contains($directoryPath)) {
            $segments = $directoryPath.Split("/")
            foreach ($segment in $segments) {
                if ($forbiddenNames -contains $segment.ToLowerInvariant()) {
                    throw "Forbidden marketplace-style package segment in tracked trading source: $directoryPath"
                }
            }
        }
    }

    Write-Host "[package-boundary] OK top-level and nested com.agora packages are trading-owned"
} finally {
    Pop-Location
}
