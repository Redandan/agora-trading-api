Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

$scriptPath = Join-Path $PSScriptRoot "prepare_legacy_grid_retirement_preflight_ssh.ps1"
$scriptText = Get-Content -LiteralPath $scriptPath -Raw
foreach ($marker in @(
        "LEGACY_GRID_RETIREMENT_EXECUTION_PREFLIGHT_V1",
        "READ_ONLY_NO_ENV_DB_ORDER_GRID_OR_BOT_MUTATION",
        '"execute": False',
        '"providerOrderAttempted": False',
        '"databaseMutationAttempted": False',
        '"productionMutationAttempted": False',
        "READY_FOR_EXACT_AUTHORIZATION_NOT_EXECUTION",
        "notAuthorization=read-only preflight only"
    )) {
    Assert-Contains -Name "legacy retirement preflight source" -Text $scriptText -Pattern ([regex]::Escape($marker))
}
if ($scriptText -match '"execute"\s*:\s*True|tools/call.*execute.*true|Set-Content.*ENV_FILE|Add-Content.*ENV_FILE|systemctl\s+restart|bash\s+deploy\.sh') {
    throw "Legacy retirement preflight contains a forbidden Production mutation path."
}

$commit = "3401f96d723fb2e49c55a810b137dc77e1bfad5f"
$state = "c494cbaabe568a90913cd71791eabcbf25fac81620b21e13d7e4ea16fd577be6"
$confirmation = "AUTHORIZE_LEGACY_GRID_RETIREMENT|gridId=11|symbol=BTCUSDT|disposition=CLOSE_NO_HOLDING|holdingCount=0|totalSellQty=0|stateSha256=$state"
$readyPacket = [ordered]@{
    packetType = "LEGACY_GRID_RETIREMENT_EXECUTION_PREFLIGHT_V1"
    boundary = "READ_ONLY_NO_ENV_DB_ORDER_GRID_OR_BOT_MUTATION"
    checkedAtUtc = "2026-07-22T11:35:44Z"
    gridId = 11
    disposition = "CLOSE_NO_HOLDING"
    expectedCommit = $commit
    serverHeadCommit = $commit
    runningCommit = $commit.Substring(0, 12)
    serverWorktreeDirty = $false
    health = "UP"
    environmentFileSha256 = ("a" * 64)
    legacyRetirementFeatureEnabled = $false
    legacyRetirementLiveActionEnabled = $false
    okxMasterTradingEnabled = $true
    activeNativeGridBotCount = 0
    balances = @{ BTC = @{ available = "0.001"; cash = "0.001" }; USDT = @{ available = "10"; cash = "10" } }
    dryRun = @{ stateSha256 = $state; totalSellQty = "0"; blockers = "[]" }
    exactConfirmation = $confirmation
    providerOrderAttempted = $false
    databaseMutationAttempted = $false
    productionMutationAttempted = $false
    blockers = @()
    status = "READY_FOR_EXACT_AUTHORIZATION_NOT_EXECUTION"
}

$tempPath = Join-Path ([IO.Path]::GetTempPath()) ("legacy-grid-retirement-preflight-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $readyJson = ConvertTo-Json -InputObject $readyPacket -Compress -Depth 12
    Set-Content -LiteralPath $tempPath -Encoding UTF8 -Value @(
        ("legacy_grid_retirement_preflight=" + $readyJson)
        "scope=READ_ONLY"
    )
    $pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $pwsh) { $pwsh = Get-Command powershell -ErrorAction Stop }
    $output = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
        -GridId 11 `
        -ExpectedCommit $commit `
        -ExpectedStateSha256 $state `
        -ExpectedTotalSellQty 0 `
        -SourceLogPath $tempPath `
        -RequireReady 2>&1
    if ($LASTEXITCODE -ne 0) { throw "Ready fixture failed: $($output | Out-String)" }
    $text = $output | Out-String
    foreach ($marker in @(
            "legacy_grid_retirement_preflight_status=READY_FOR_EXACT_AUTHORIZATION_NOT_EXECUTION",
            "legacy_grid_retirement_exact_confirmation=$confirmation",
            "notAuthorization=read-only preflight only"
        )) {
        Assert-Contains -Name "legacy retirement preflight ready fixture" -Text $text -Pattern ([regex]::Escape($marker))
    }

    $blockedPacket = $readyPacket | ConvertTo-Json -Depth 12 | ConvertFrom-Json -Depth 12
    $blockedPacket.blockers = @("STATE_SHA256_MISMATCH")
    $blockedPacket.status = "BLOCKED_NOT_READY"
    Set-Content -LiteralPath $tempPath -Encoding UTF8 -Value (
        "legacy_grid_retirement_preflight=" + (ConvertTo-Json -InputObject $blockedPacket -Compress -Depth 12)
    )
    $previous = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $blockedOutput = & $pwsh.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath `
            -GridId 11 `
            -ExpectedCommit $commit `
            -ExpectedStateSha256 $state `
            -ExpectedTotalSellQty 0 `
            -SourceLogPath $tempPath `
            -RequireReady 2>&1
        $blockedExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previous
    }
    if ($blockedExit -eq 0 -or ($blockedOutput | Out-String) -notmatch "STATE_SHA256_MISMATCH") {
        throw "Blocked fixture did not fail closed."
    }
} finally {
    Remove-Item -LiteralPath $tempPath -Force -ErrorAction SilentlyContinue
}

Write-Host "[legacy-grid-retirement-preflight-test] OK"
