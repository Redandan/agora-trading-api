Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$verifier = Join-Path $PSScriptRoot "verify_custom_grid_runtime_removed.ps1"
$pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
$tempRoot = Join-Path ([IO.Path]::GetTempPath()) ("custom-grid-runtime-removal-" + [guid]::NewGuid().ToString("N"))

function Write-FixtureFile {
    param([string]$RelativePath, [string]$Content)
    $path = Join-Path $tempRoot $RelativePath
    New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null
    Set-Content -LiteralPath $path -Encoding UTF8 -Value $Content
}

try {
    Write-FixtureFile "src/main/java/com/agora/mcp/OkxNativeGridMcpTools.java" "class OkxNativeGridMcpTools {}"
    Write-FixtureFile "src/main/java/com/agora/service/trading/OkxNativeGridExecutionService.java" "class OkxNativeGridExecutionService {}"
    Write-FixtureFile "src/main/java/com/agora/config/properties/OkxNativeGridProperties.java" "record OkxNativeGridProperties() {}"
    Write-FixtureFile ".env.trading.secrets.example" "TRADING_OKX_NATIVE_GRID_ENABLED=false"
    New-Item -ItemType Directory -Path (Join-Path $tempRoot "scripts") -Force | Out-Null

    $passOutput = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -RootPath $tempRoot -RequirePass 2>&1
    if ($LASTEXITCODE -ne 0 -or ($passOutput | Out-String) -notmatch "PASS_CUSTOM_GRID_RUNTIME_REMOVED_LOCAL_COMPONENT_ONLY") {
        throw "Clean fixture did not pass: $($passOutput | Out-String)"
    }

    Write-FixtureFile "src/main/java/com/agora/mcp/GridMcpTools.java" @"
class GridMcpTools {
  void createGrid() { gridRepository.save(new Object()); }
}
"@
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $verifier -RootPath $tempRoot -RequirePass 2>&1
        $blockedExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    $blockedText = $blockedOutput | Out-String
    foreach ($marker in @("CUSTOM_GRID_RUNTIME_PRESENT", "CUSTOM_RUNTIME_FILE_PRESENT", "CUSTOM_MUTATION_METHOD", "CUSTOM_REPOSITORY_WRITE")) {
        if ($blockedText -notmatch [regex]::Escape($marker)) {
            throw "Blocked fixture missing marker $marker"
        }
    }
    if ($blockedExit -eq 0) { throw "Blocked fixture did not fail closed." }
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "[custom-grid-runtime-removed-test] OK"

