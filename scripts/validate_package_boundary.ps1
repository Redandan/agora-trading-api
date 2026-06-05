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

    $mainRoot = "src/main/java/com/agora"
    $mainPackages = Get-ChildItem -Directory $mainRoot | Select-Object -ExpandProperty Name
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

    $testRoot = "src/test/java/com/agora"
    if (Test-Path $testRoot) {
        $testPackages = Get-ChildItem -Directory $testRoot | Select-Object -ExpandProperty Name
        foreach ($packageName in $testPackages) {
            if (-not $allowedMainPackages.Contains($packageName)) {
                throw "Unexpected top-level test package in trading split: com.agora.$packageName"
            }
        }
    }

    Write-Host "[package-boundary] OK top-level com.agora packages are trading-owned"
} finally {
    Pop-Location
}
