param(
    [string]$OutputDir = "target/schema-baseline"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $resolvedOutputDir = Join-Path (Get-Location) $OutputDir
    New-Item -ItemType Directory -Force -Path $resolvedOutputDir | Out-Null

    $explicitTables = New-Object System.Collections.Generic.List[string]
    $implicitEntities = New-Object System.Collections.Generic.List[string]
    $forbiddenMarketplaceTables = @(
        "cart",
        "cart_item",
        "carts",
        "delivery_order",
        "order",
        "order_item",
        "orders",
        "product",
        "products",
        "store",
        "stores",
        "user",
        "user_address",
        "user_wallet",
        "users",
        "wallet",
        "wallets"
    )

    $entityFiles = rg --files src/main/java/com/agora/model |
        Where-Object { $_ -like "*.java" } |
        Sort-Object
    foreach ($file in $entityFiles) {
        $content = Get-Content -Raw -Path $file
        if ($content -notmatch "@Entity\b") {
            continue
        }

        $tableMatch = [regex]::Match($content, '@Table\s*\(\s*name\s*=\s*"([^"]+)"')
        if ($tableMatch.Success) {
            $explicitTables.Add($tableMatch.Groups[1].Value)
            continue
        }

        $classMatch = [regex]::Match($content, '\bclass\s+([A-Za-z0-9_]+)')
        if ($classMatch.Success) {
            $implicitEntities.Add($classMatch.Groups[1].Value)
        } else {
            $implicitEntities.Add($file)
        }
    }

    $tablesPath = Join-Path $resolvedOutputDir "entity-tables.txt"
    $implicitPath = Join-Path $resolvedOutputDir "implicit-entities.txt"
    $forbiddenPath = Join-Path $resolvedOutputDir "forbidden-marketplace-tables.txt"
    $unsafePath = Join-Path $resolvedOutputDir "unsafe-table-names.txt"

    $uniqueTables = @($explicitTables | Sort-Object -Unique)
    $uniqueImplicitEntities = @($implicitEntities | Sort-Object -Unique)
    $forbiddenTables = @($uniqueTables | Where-Object { $forbiddenMarketplaceTables -contains $_ } | Sort-Object -Unique)
    $unsafeTables = @($uniqueTables | Where-Object { $_ -notmatch '^[A-Za-z0-9_]+$' } | Sort-Object -Unique)

    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllLines($tablesPath, [string[]]$uniqueTables, $utf8NoBom)
    [System.IO.File]::WriteAllLines($implicitPath, [string[]]$uniqueImplicitEntities, $utf8NoBom)
    [System.IO.File]::WriteAllLines($forbiddenPath, [string[]]$forbiddenTables, $utf8NoBom)
    [System.IO.File]::WriteAllLines($unsafePath, [string[]]$unsafeTables, $utf8NoBom)

    Write-Host "[schema-inventory] explicit entity tables: $($uniqueTables.Count) -> $tablesPath"
    Write-Host "[schema-inventory] implicit entity names: $($uniqueImplicitEntities.Count) -> $implicitPath"
    Write-Host "[schema-inventory] forbidden marketplace tables: $($forbiddenTables.Count) -> $forbiddenPath"
    Write-Host "[schema-inventory] unsafe table names: $($unsafeTables.Count) -> $unsafePath"
    if ($uniqueImplicitEntities.Count -gt 0) {
        throw "Schema inventory found entity class(es) without explicit @Table(name=...): $($uniqueImplicitEntities -join ', ')"
    }
    if ($forbiddenTables.Count -gt 0) {
        throw "Schema inventory found marketplace-owned table mapping(s): $($forbiddenTables -join ', ')"
    }
    if ($unsafeTables.Count -gt 0) {
        throw "Schema inventory found unsafe table name(s): $($unsafeTables -join ', ')"
    }
    Write-Host "[schema-inventory] read-only source inventory complete; no database or migration files changed"
} finally {
    Pop-Location
}
