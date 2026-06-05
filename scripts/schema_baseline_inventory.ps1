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

    $explicitTables |
        Sort-Object -Unique |
        Set-Content -Encoding UTF8 -Path $tablesPath

    $implicitEntities |
        Sort-Object -Unique |
        Set-Content -Encoding UTF8 -Path $implicitPath

    Write-Host "[schema-inventory] explicit entity tables: $($explicitTables.Count) -> $tablesPath"
    Write-Host "[schema-inventory] implicit entity names: $($implicitEntities.Count) -> $implicitPath"
    Write-Host "[schema-inventory] read-only source inventory complete; no database or migration files changed"
} finally {
    Pop-Location
}
