Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-PerlCommand {
    $fromPath = Get-Command perl -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) {
        return $fromPath.Source
    }

    foreach ($candidate in @(
        "C:\Program Files\Git\usr\bin\perl.exe",
        "C:\Program Files (x86)\Git\usr\bin\perl.exe"
    )) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "perl is required for schema baseline entity table parser regression tests"
}

function Resolve-BashCommand {
    $fromPath = Get-Command bash -ErrorAction SilentlyContinue
    if ($null -ne $fromPath) {
        return $fromPath.Source
    }

    foreach ($candidate in @(
        "C:\Program Files\Git\bin\bash.exe",
        "C:\Program Files\Git\usr\bin\bash.exe",
        "C:\Program Files (x86)\Git\bin\bash.exe"
    )) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "bash is required for schema baseline compare regression tests"
}

function Write-Fixture {
    param(
        [string]$Path,
        [string]$Content
    )

    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

$perl = Resolve-PerlCommand
$bash = Resolve-BashCommand
$parser = Join-Path $PSScriptRoot "schema_baseline_entity_table_parser.pl"
$fixtureDir = Join-Path ([System.IO.Path]::GetTempPath()) ("schema-parser-" + [guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $fixtureDir | Out-Null

    foreach ($mode in @("tables", "implicit")) {
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $zeroSourceOutput = @(& $perl $parser $mode 2>&1)
            $zeroSourceExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
        if ($zeroSourceExitCode -eq 0) {
            throw "parser mode $mode accepted zero source files"
        }
        if (($zeroSourceOutput -join "`n") -notmatch "usage:") {
            throw "parser mode $mode zero-source failure did not report usage"
        }
    }

    $mappedSuperclass = Join-Path $fixtureDir "AppendOnlyEvidence.java"
    $realEntity = Join-Path $fixtureDir "RealEntity.java"
    $entityWithoutTable = Join-Path $fixtureDir "EntityWithoutTable.java"
    $tableWithoutEntity = Join-Path $fixtureDir "TableWithoutEntity.java"

    Write-Fixture -Path $mappedSuperclass -Content @'
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;

@MappedSuperclass
abstract class AppendOnlyEvidence {
    @Column(name = "dedupe_key", nullable = false)
    private String dedupeKey;
}
'@
    Write-Fixture -Path $realEntity -Content @'
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "real_trade_table")
class RealEntity {}
'@
    Write-Fixture -Path $entityWithoutTable -Content @'
import jakarta.persistence.Entity;

@Entity
class EntityWithoutTable {}
'@
    Write-Fixture -Path $tableWithoutEntity -Content @'
import jakarta.persistence.Table;

@Table(name = "cross_file_false_table")
class TableWithoutEntity {}
'@

    $tables = @(& $perl $parser tables $mappedSuperclass $realEntity $entityWithoutTable $tableWithoutEntity)
    if ($LASTEXITCODE -ne 0) {
        throw "table parser failed with exit code $LASTEXITCODE"
    }
    if ($tables.Count -ne 1 -or $tables[0] -ne "real_trade_table") {
        throw "expected only real_trade_table; actual=$($tables -join ',')"
    }
    if ($tables -contains "dedupe_key") {
        throw "@MappedSuperclass @Column dedupe_key was misclassified as a table"
    }
    if ($tables -contains "cross_file_false_table") {
        throw "@Entity and @Table were paired across input file boundaries"
    }

    $implicit = @(& $perl $parser implicit $mappedSuperclass $realEntity $entityWithoutTable $tableWithoutEntity)
    if ($LASTEXITCODE -ne 0) {
        throw "implicit entity parser failed with exit code $LASTEXITCODE"
    }
    if ($implicit.Count -ne 1 -or $implicit[0] -ne $entityWithoutTable) {
        throw "expected only the explicit entity-without-table path; actual=$($implicit -join ',')"
    }

    $emptyApp = Join-Path $fixtureDir "empty-model-app"
    $emptyModelDir = Join-Path $emptyApp "src/main/java/com/agora/model"
    $emptyScriptsDir = Join-Path $emptyApp "scripts"
    $mockBinDir = Join-Path $fixtureDir "mock-bin"
    $outputDir = Join-Path $fixtureDir "compare-output"
    $envFile = Join-Path $fixtureDir "compare.env"
    $mysqlMarker = Join-Path $fixtureDir "mysql-invoked.txt"
    New-Item -ItemType Directory -Path $emptyModelDir, $emptyScriptsDir, $mockBinDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "schema_baseline_compare_server.sh") -Destination $emptyScriptsDir
    Copy-Item -LiteralPath $parser -Destination $emptyScriptsDir
    Write-Fixture -Path $envFile -Content @'
SPRING_DATASOURCE_URL=jdbc:mysql://127.0.0.1:3306/agora_market
SPRING_DATASOURCE_USERNAME=test
SPRING_DATASOURCE_PASSWORD=test
'@
    Write-Fixture -Path (Join-Path $mockBinDir "mysql") -Content @'
#!/usr/bin/env bash
printf 'invoked\n' > "$MYSQL_MARKER"
exit 99
'@

    $previousAppDir = $env:APP_DIR
    $previousEnvFile = $env:ENV_FILE
    $previousOutputDir = $env:OUTPUT_DIR
    $previousMockBin = $env:MOCK_BIN
    $previousMysqlMarker = $env:MYSQL_MARKER
    try {
        $env:APP_DIR = $emptyApp.Replace('\', '/')
        $env:ENV_FILE = $envFile.Replace('\', '/')
        $env:OUTPUT_DIR = $outputDir.Replace('\', '/')
        $env:MOCK_BIN = $mockBinDir.Replace('\', '/')
        $env:MYSQL_MARKER = $mysqlMarker.Replace('\', '/')
        $previousErrorActionPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = "Continue"
            $emptyInventoryOutput = @(& $bash -lc 'PATH="$MOCK_BIN:$PATH" bash "$APP_DIR/scripts/schema_baseline_compare_server.sh"' 2>&1)
            $emptyInventoryExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousErrorActionPreference
        }
    } finally {
        $env:APP_DIR = $previousAppDir
        $env:ENV_FILE = $previousEnvFile
        $env:OUTPUT_DIR = $previousOutputDir
        $env:MOCK_BIN = $previousMockBin
        $env:MYSQL_MARKER = $previousMysqlMarker
    }
    if ($emptyInventoryExitCode -eq 0) {
        throw "schema baseline compare accepted an empty model inventory"
    }
    if (($emptyInventoryOutput -join "`n") -notmatch "found no Java model source files") {
        throw "empty model inventory did not fail at the source inventory guard: $($emptyInventoryOutput -join '`n')"
    }
    if (Test-Path -LiteralPath $mysqlMarker) {
        throw "schema baseline compare invoked mysql before rejecting an empty model inventory"
    }

    Write-Host "[schema-parser-test] OK"
} finally {
    if (Test-Path -LiteralPath $fixtureDir) {
        Remove-Item -LiteralPath $fixtureDir -Recurse -Force
    }
}
