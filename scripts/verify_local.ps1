Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    Write-Host "[verify] mvn test"
    mvn test

    Write-Host "[verify] checking source boundary markers"
    $forbidden = rg "FlutterDeployment|FlutterAppDeployment|AppVersion|flutter/deployment" src/main/java src/main/resources/application.yml
    if ($LASTEXITCODE -eq 0) {
        Write-Error "Forbidden Flutter/AppVersion residue found:`n$forbidden"
    }
    if ($LASTEXITCODE -gt 1) {
        throw "rg failed with exit code $LASTEXITCODE"
    }

    Write-Host "[verify] OK"
} finally {
    Pop-Location
}
