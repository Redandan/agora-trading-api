Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $templatePath = ".env.trading.secrets.example"
    if (-not (Test-Path $templatePath)) {
        throw "Env template missing: $templatePath"
    }

    $templateKeys = [ordered]@{}
    Get-Content $templatePath | ForEach-Object {
        $line = $_.Trim()
        if ($line -eq "" -or $line.StartsWith("#")) {
            return
        }
        if ($line -notmatch "^([A-Z0-9_]+)=(.*)$") {
            throw "Invalid env template line: $_"
        }
        $templateKeys[$Matches[1]] = $Matches[2]
    }

    $requiredByScripts = [ordered]@{}
    foreach ($scriptPath in @("deploy.sh", "scripts/preflight_server.sh", "scripts/verify_server.sh")) {
        $content = Get-Content -Raw $scriptPath
        [regex]::Matches($content, "require_env_key\s+([A-Z0-9_]+)") | ForEach-Object {
            $key = $_.Groups[1].Value
            if (-not $requiredByScripts.Contains($key)) {
                $requiredByScripts[$key] = New-Object System.Collections.Generic.List[string]
            }
            $requiredByScripts[$key].Add($scriptPath)
        }
    }

    foreach ($key in $requiredByScripts.Keys) {
        if (-not $templateKeys.Contains($key)) {
            throw "Env template missing required key from server scripts: $key used by $($requiredByScripts[$key] -join ', ')"
        }
    }

    foreach ($key in @("AGORA_MARKET_INTERNAL_TIMEOUT_MS", "SPRING_JPA_HIBERNATE_DDL_AUTO", "SPRING_FLYWAY_ENABLED", "PORT")) {
        if (-not $templateKeys.Contains($key)) {
            throw "Env template missing deploy default key: $key"
        }
    }

    foreach ($key in @("TRADING_ADMIN_KEY", "TRADING_MCP_KEY", "AGORA_MARKET_INTERNAL_API_KEY", "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
        if ($templateKeys[$key] -ne "") {
            throw "Secret-like env template key must stay empty: $key"
        }
    }

    if ($templateKeys["AGORA_MARKET_BASE_URL"] -ne "http://127.0.0.1:8082") {
        throw "AGORA_MARKET_BASE_URL should point at local AgoraMarketAPI dependency in the template"
    }
    if ($templateKeys["SPRING_JPA_HIBERNATE_DDL_AUTO"] -ne "update") {
        throw "Template must keep temporary bootstrap-only schema mode until Flyway baseline exists"
    }
    if ($templateKeys["SPRING_FLYWAY_ENABLED"] -ne "false") {
        throw "Template must keep Flyway disabled until baseline exists"
    }
    if ($templateKeys["PORT"] -ne "8084") {
        throw "Template must default to the blue-green 8084 port"
    }

    $ignore = Get-Content -Raw ".gitignore"
    if ($ignore -notmatch "(?m)^!\.env\.trading\.secrets\.example$") {
        throw ".gitignore must allow tracking .env.trading.secrets.example"
    }

    Write-Host "[env-template] OK $templatePath covers $($requiredByScripts.Keys.Count) required server env key(s)"
} finally {
    Pop-Location
}
