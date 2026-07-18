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

function Write-Fixture {
    param(
        [string]$Path,
        [string]$Content
    )

    [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

$perl = Resolve-PerlCommand
$parser = Join-Path $PSScriptRoot "schema_baseline_entity_table_parser.pl"
$fixtureDir = Join-Path ([System.IO.Path]::GetTempPath()) ("schema-parser-" + [guid]::NewGuid().ToString("N"))

try {
    New-Item -ItemType Directory -Path $fixtureDir | Out-Null

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

    Write-Host "[schema-parser-test] OK"
} finally {
    if (Test-Path -LiteralPath $fixtureDir) {
        Remove-Item -LiteralPath $fixtureDir -Recurse -Force
    }
}
