Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

    throw "bash is required for nginx route rewrite test; install Git Bash or put bash on PATH"
}

function Assert-Contains {
    param(
        [string]$Text,
        [string]$Needle,
        [string]$Description
    )

    if (-not $Text.Contains($Needle)) {
        throw "Missing expected nginx rewrite output: $Description"
    }
}

function Assert-NotContains {
    param(
        [string]$Text,
        [string]$Needle,
        [string]$Description
    )

    if ($Text.Contains($Needle)) {
        throw "Unexpected nginx rewrite output: $Description"
    }
}

Push-Location (Resolve-Path "$PSScriptRoot\..")
try {
    $bash = Resolve-BashCommand
    $workDir = Join-Path (Resolve-Path ".") "target\nginx-route-rewrite-test"
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null
    $input = Join-Path $workDir "input.conf"
    $insertInput = Join-Path $workDir "insert-input.conf"
    $output = Join-Path $workDir "output.conf"
    $secondOutput = Join-Path $workDir "output-second.conf"
    $insertOutput = Join-Path $workDir "insert-output.conf"

    @'
server {
    listen 80;
    server_name agoratradingapi.purrtechllc.com;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl;
    server_name agoratradingapi.purrtechllc.com;

    location /.well-known/acme-challenge/ {
        root /var/www/html;
    }

    location = /api/mcp {
        limit_req zone=mcp burst=10 nodelay;
        proxy_pass http://127.0.0.1:8085/api/trading/mcp;
        proxy_read_timeout 3600;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8085/api/trading/;
        proxy_set_header Host $host;
    }
}

server {
    listen 443 ssl;
    server_name agoramarketapi.purrtechllc.com;

    location = /api/mcp {
        proxy_pass http://127.0.0.1:8080/api/mcp;
    }

    location /api/trading/ {
        proxy_pass http://127.0.0.1:8085;
        proxy_set_header Host $host;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
    }
}
'@ | Set-Content -LiteralPath $input -Encoding ascii

    @'
server {
    listen 443 ssl;
    server_name agoramarketapi.purrtechllc.com;

    location = /api/mcp {
        proxy_pass http://127.0.0.1:8080/api/mcp;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080/api/;
    }
}
'@ | Set-Content -LiteralPath $insertInput -Encoding ascii

    & $bash -lc "awk -v port=8084 -f scripts/rewrite_nginx_trading_routes.awk target/nginx-route-rewrite-test/input.conf > target/nginx-route-rewrite-test/output.conf"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx route rewrite awk failed with exit code $LASTEXITCODE"
    }

    & $bash -lc "awk -v port=8084 -f scripts/rewrite_nginx_trading_routes.awk target/nginx-route-rewrite-test/output.conf > target/nginx-route-rewrite-test/output-second.conf"
    if ($LASTEXITCODE -ne 0) {
        throw "second nginx route rewrite awk pass failed with exit code $LASTEXITCODE"
    }

    & $bash -lc "awk -v port=8084 -v insert_trading_path=1 -f scripts/rewrite_nginx_trading_routes.awk target/nginx-route-rewrite-test/insert-input.conf > target/nginx-route-rewrite-test/insert-output.conf"
    if ($LASTEXITCODE -ne 0) {
        throw "nginx route rewrite insertion mode failed with exit code $LASTEXITCODE"
    }

    $rewritten = Get-Content -LiteralPath $output -Raw
    $second = Get-Content -LiteralPath $secondOutput -Raw
    $inserted = Get-Content -LiteralPath $insertOutput -Raw

    Assert-Contains -Text $rewritten -Needle "location = /api/mcp {" -Description "dedicated MCP exact location remains explicit"
    Assert-Contains -Text $rewritten -Needle "MCP is internal-only. Public dedicated host must not expose /api/mcp." -Description "dedicated MCP public block comment"
    Assert-Contains -Text $rewritten -Needle "Trading MCP is internal-only. Public shared host must not expose /api/trading/mcp." -Description "shared Trading MCP public block comment"
    Assert-Contains -Text $rewritten -Needle "proxy_pass http://127.0.0.1:8084/api/;" -Description "dedicated /api/ upstream follows active port"
    Assert-Contains -Text $rewritten -Needle "proxy_pass http://127.0.0.1:8084;" -Description "shared /api/trading/ upstream follows active port"
    Assert-NotContains -Text $rewritten -Needle "proxy_pass http://127.0.0.1:8084/api/trading/mcp;" -Description "dedicated public MCP must not proxy to Trading MCP"
    Assert-NotContains -Text $rewritten -Needle "proxy_pass http://127.0.0.1:8085/api/trading/mcp;" -Description "stale dedicated public MCP proxy is removed"

    $sharedMcpCount = ([regex]::Matches($second, 'location\s*=\s*/api/trading/mcp')).Count
    if ($sharedMcpCount -ne 1) {
        throw "nginx route rewrite must be idempotent for shared /api/trading/mcp block; count=$sharedMcpCount"
    }

    $dedicatedMcpReturnCount = ([regex]::Matches($second, 'location\s*=\s*/api/mcp\s*\{\s*return 404;', [System.Text.RegularExpressions.RegexOptions]::Singleline)).Count
    if ($dedicatedMcpReturnCount -ne 1) {
        throw "nginx route rewrite must keep exactly one dedicated /api/mcp return 404 block; count=$dedicatedMcpReturnCount"
    }

    Assert-Contains -Text $inserted -Needle "location = /api/trading/mcp {" -Description "insertion mode creates shared Trading MCP block"
    Assert-Contains -Text $inserted -Needle "location /api/trading/ {" -Description "insertion mode creates shared Trading path"
    Assert-Contains -Text $inserted -Needle "proxy_pass http://127.0.0.1:8084;" -Description "insertion mode uses requested active port"

    Write-Host "[nginx-route-rewrite-test] OK"
} finally {
    Pop-Location
}
