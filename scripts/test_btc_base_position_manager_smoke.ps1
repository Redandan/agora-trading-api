Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) { throw "$Name missing pattern: $Pattern" }
}

function Assert-NotContains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -match $Pattern) { throw "$Name contains forbidden pattern: $Pattern" }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$paths = @{
    Service = Join-Path $repoRoot "src/main/java/com/agora/service/trading/BtcBasePositionManagerService.java"
    Mcp = Join-Path $repoRoot "src/main/java/com/agora/mcp/BtcBasePositionManagerMcpTools.java"
    Config = Join-Path $repoRoot "src/main/java/com/agora/mcp/McpToolsConfig.java"
    JavaTest = Join-Path $repoRoot "src/test/java/com/agora/service/trading/BtcBasePositionManagerServiceTest.java"
    LocalSmoke = Join-Path $PSScriptRoot "smoke_local_health.ps1"
    LocalParity = Join-Path $PSScriptRoot "smoke_mcp_parity.ps1"
    SshParity = Join-Path $PSScriptRoot "smoke_mcp_parity_ssh.ps1"
}
$text = @{}
foreach ($entry in $paths.GetEnumerator()) {
    if (-not (Test-Path -LiteralPath $entry.Value)) { throw "Missing $($entry.Key): $($entry.Value)" }
    $text[$entry.Key] = Get-Content -Raw -LiteralPath $entry.Value
}

foreach ($marker in @(
        'POLICY_MODE = "BTC_BASE_POSITION_MANAGER_V1"',
        'STAGE = "READ_ONLY_SHADOW_PREVIEW"',
        'POSITION_IDS_REQUIRED',
        'BTCUSDT_ONLY_V1',
        'ACTIVE_OCO_REQUIRED',
        'TRADED_QTY_OCO_QTY_MISMATCH',
        'OCO_CHILD_FILLED_RECONCILIATION_REQUIRED',
        'HEURISTIC_EV_NOT_STATISTICALLY_CALIBRATED',
        'RECOVERY_REVIEW_TTL_IS_RISK_GOVERNANCE_NOT_A_PROVEN_PROFIT_EDGE',
        'SpotPositionCloseService.closeAtMarket after a separate protected live authorization',
        'databaseMutated", false',
        'orderSent", false',
        'ocoCancelled", false',
        'ocoModified", false',
        'telegramSent", false',
        'walletBalanceUsedForOwnership", false')) {
    Assert-Contains -Name "read-only manager contract" -Text $text.Service -Pattern ([regex]::Escape($marker))
}

foreach ($forbidden in @(
        'liveSignalRepository\.save\s*\(',
        'spotPositionCloseService\.closeAtMarket\s*\(',
        'okxTradingService\.cancelOco\s*\(',
        'okxTradingService\.placeOco\s*\(',
        'okxTradingService\.placeMarketSell',
        'TelegramService|NotificationPort|sendAlert\s*\(')) {
    Assert-NotContains -Name "manager mutation boundary" -Text $text.Service -Pattern $forbidden
}

foreach ($tool in @(
        "getBtcBasePositionManagerStatus",
        "previewBtcBasePositionAdoption",
        "previewBtcBasePositionDisposition")) {
    Assert-Contains -Name "MCP surface" -Text $text.Mcp -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "local registry smoke" -Text $text.LocalSmoke -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "local parity" -Text $text.LocalParity -Pattern ([regex]::Escape($tool))
    Assert-Contains -Name "SSH parity" -Text $text.SshParity -Pattern ([regex]::Escape($tool))
}
Assert-Contains -Name "MCP OPS auth" -Text $text.Mcp -Pattern 'McpAuth\(McpAuthLevel\.OPS\)'
Assert-Contains -Name "MCP config provider" -Text $text.Config -Pattern 'btcBasePositionManagerMcpToolCallbacks'

foreach ($marker in @(
        "260,260,261,262",
        "RETIRE_CLOSE_REVIEW",
        "TRADED_QTY_OCO_QTY_MISMATCH",
        "filledSecondOcoChildFailsClosed",
        "assertNoMutation")) {
    Assert-Contains -Name "focused Java coverage" -Text $text.JavaTest -Pattern ([regex]::Escape($marker))
}

Write-Host "[btc-base-position-manager-smoke-test] OK"
