Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Write-Host "[split-boundary] checking schema baseline source inventory"
& "$PSScriptRoot\schema_baseline_inventory.ps1"

Write-Host "[split-boundary] checking pom split dependency boundary"
& "$PSScriptRoot\validate_pom_boundary.ps1"

Write-Host "[split-boundary] checking package split boundary"
& "$PSScriptRoot\validate_package_boundary.ps1"

Write-Host "[split-boundary] checking server env template contract"
& "$PSScriptRoot\validate_env_template.ps1"

Write-Host "[split-boundary] OK"
