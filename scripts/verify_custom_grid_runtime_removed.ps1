param(
    [string]$RootPath = (Split-Path -Parent $PSScriptRoot),
    [switch]$RequirePass
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$resolvedRoot = (Resolve-Path -LiteralPath $RootPath).Path
$blockers = [Collections.Generic.List[object]]::new()

function Add-Blocker {
    param([string]$Category, [string]$Path, [string]$Evidence)
    $blockers.Add([ordered]@{
        category = $Category
        path = $Path.Replace('\', '/')
        evidence = $Evidence
    })
}

function Get-RelativePath {
    param([string]$Path)
    return [IO.Path]::GetRelativePath($resolvedRoot, $Path)
}

$requiredNativeFiles = @(
    "src/main/java/com/agora/mcp/OkxNativeGridMcpTools.java",
    "src/main/java/com/agora/service/trading/OkxNativeGridExecutionService.java",
    "src/main/java/com/agora/config/properties/OkxNativeGridProperties.java"
)
foreach ($relative in $requiredNativeFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $resolvedRoot $relative))) {
        Add-Blocker -Category "NATIVE_REPLACEMENT_MISSING" -Path $relative -Evidence "required OKX-native runtime file is absent"
    }
}

$forbiddenRuntimeFiles = @(
    "src/main/java/com/agora/mcp/GridMcpTools.java",
    "src/main/java/com/agora/service/trading/GridManagerService.java",
    "src/main/java/com/agora/scheduler/trading/GridManagerScheduler.java",
    "src/main/java/com/agora/scheduler/trading/GridAutoRebalanceScheduler.java",
    "src/main/java/com/agora/scheduler/trading/GridOrphanRecoveryScanner.java",
    "src/main/java/com/agora/service/trading/execution/GridExecutionEventDetector.java",
    "src/main/java/com/agora/config/properties/TradingGridProperties.java",
    "src/main/java/com/agora/config/properties/GridRecoveryProperties.java"
)
foreach ($relative in $forbiddenRuntimeFiles) {
    if (Test-Path -LiteralPath (Join-Path $resolvedRoot $relative)) {
        Add-Blocker -Category "CUSTOM_RUNTIME_FILE_PRESENT" -Path $relative -Evidence "custom Grid executable runtime family still exists"
    }
}

$sourceRoot = Join-Path $resolvedRoot "src/main"
if (Test-Path -LiteralPath $sourceRoot) {
    $sourceFiles = Get-ChildItem -LiteralPath $sourceRoot -Recurse -File |
        Where-Object { $_.Extension -in @(".java", ".yml", ".yaml", ".properties") }
    $forbiddenSourcePatterns = [ordered]@{
        "CUSTOM_MCP_OR_SERVICE_WIRING" = "\b(?:GridMcpTools|GridManagerService|GridManagerScheduler|GridAutoRebalanceScheduler|GridOrphanRecoveryScanner|GridExecutionEventDetector|TradingGridProperties|GridRecoveryProperties)\b"
        "CUSTOM_MUTATION_METHOD" = "\b(?:createGrid|resumeGrid|pauseGrid|closeGrid|enableGridAutoRebalance)\s*\("
        "CUSTOM_REPOSITORY_WRITE" = "\b(?:gridRepository|levelRepository|gridRepo|levelRepo|btGridRepository|btGridLevelRepository)\s*\.\s*(?:save|saveAll|delete|deleteAll)\s*\("
        "CUSTOM_RUNTIME_PROPERTY" = "(?:TRADING_GRID_(?:ENABLED|CUSTOM_CREATE_RESUME_ENABLED|LEGACY_RETIREMENT_ENABLED|LEGACY_RETIREMENT_LIVE_ACTION_ENABLED|AUTO_REBALANCE_SCHEDULER_ENABLED)|(?:trading\.grid|grid\.recovery)\.)"
    }
    foreach ($file in $sourceFiles) {
        $text = Get-Content -LiteralPath $file.FullName -Raw
        foreach ($entry in $forbiddenSourcePatterns.GetEnumerator()) {
            $match = [regex]::Match($text, $entry.Value)
            if ($match.Success) {
                Add-Blocker -Category $entry.Key -Path (Get-RelativePath $file.FullName) -Evidence $match.Value
            }
        }
    }
}

$downstreamFiles = @(
    "src/main/java/com/agora/config/WsSubscriptionResolver.java",
    "src/main/java/com/agora/scheduler/trading/DailyReportScheduler.java",
    "src/main/java/com/agora/scheduler/trading/OcoPositionPollerScheduler.java",
    "src/main/java/com/agora/service/trading/SymbolExposureService.java",
    "src/main/java/com/agora/service/trading/CapitalAllocationPolicyPreviewService.java",
    "src/main/java/com/agora/service/trading/TinyLiveMinimumOrderPreviewService.java",
    "src/main/java/com/agora/service/trading/TradingManagerService.java",
    "src/main/java/com/agora/service/trading/OpportunityScannerService.java",
    "src/main/java/com/agora/service/trading/PriceScenarioSimulationService.java",
    "src/main/java/com/agora/service/backtest/TradingAnalysisService.java"
)
foreach ($relative in $downstreamFiles) {
    $path = Join-Path $resolvedRoot $relative
    if (-not (Test-Path -LiteralPath $path)) { continue }
    $text = Get-Content -LiteralPath $path -Raw
    if ($text -match "\b(?:BtGrid|BtGridLevel|BtGridRepository|BtGridLevelRepository)\b") {
        Add-Blocker -Category "CUSTOM_DOWNSTREAM_COUPLING" -Path $relative -Evidence $Matches[0]
    }
}

$envTemplate = Join-Path $resolvedRoot ".env.trading.secrets.example"
if (Test-Path -LiteralPath $envTemplate) {
    $text = Get-Content -LiteralPath $envTemplate -Raw
    foreach ($key in @(
            "TRADING_GRID_ENABLED",
            "TRADING_GRID_CUSTOM_CREATE_RESUME_ENABLED",
            "TRADING_GRID_LEGACY_RETIREMENT_ENABLED",
            "TRADING_GRID_LEGACY_RETIREMENT_LIVE_ACTION_ENABLED",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
            "GRID_RECOVERY_ENABLED"
        )) {
        if ($text -match "(?m)^$([regex]::Escape($key))=") {
            Add-Blocker -Category "CUSTOM_ENV_WIRING" -Path ".env.trading.secrets.example" -Evidence $key
        }
    }
}

$operatorScriptPattern = "^(?:prepare|execute|watch|smoke)_(?:profit_)?(?:grid|closed_grid)|^profit_grid"
$scriptsRoot = Join-Path $resolvedRoot "scripts"
if (Test-Path -LiteralPath $scriptsRoot) {
    Get-ChildItem -LiteralPath $scriptsRoot -File -Filter "*.ps1" |
        Where-Object {
            $_.BaseName -match $operatorScriptPattern -and
            $_.Name -notmatch "^(?:verify|test)_custom_grid_runtime_removed\.ps1$" -and
            $_.Name -notmatch "^prepare_legacy_grid_(?:retirement|archive)_" -and
            $_.Name -notmatch "okx_native"
        } |
        ForEach-Object {
            Add-Blocker -Category "CUSTOM_OPERATOR_WORKFLOW_PRESENT" -Path (Get-RelativePath $_.FullName) -Evidence "legacy custom Grid operator workflow remains executable"
        }
}

$packet = [ordered]@{
    packetType = "CUSTOM_GRID_RUNTIME_REMOVAL_STATIC_ACCEPTANCE_V1"
    boundary = "READ_ONLY_REPOSITORY_STATIC_VERIFICATION"
    checkedAtUtc = [DateTimeOffset]::UtcNow.ToString("o")
    rootPath = $resolvedRoot.Replace('\', '/')
    requiredNativeFiles = $requiredNativeFiles
    blockerCount = $blockers.Count
    blockers = @($blockers)
    productionVerified = $false
    archiveVerified = $false
    databaseTablesDeleted = $false
    status = if ($blockers.Count -eq 0) { "PASS_CUSTOM_GRID_RUNTIME_REMOVED_LOCAL_COMPONENT_ONLY" } else { "CUSTOM_GRID_RUNTIME_PRESENT" }
    notProven = @(
        "PASS_OKX_NATIVE_GRID_FUNCTIONAL",
        "Production post-deploy runtime removal",
        "legacy archive reconciliation",
        "PASS_CUSTOM_GRID_FULLY_DELETED",
        "MIGRATION_ACCEPTED"
    )
}

$json = ConvertTo-Json -InputObject $packet -Depth 12 -Compress
Write-Output "custom_grid_runtime_removal_static_acceptance=$json"
Write-Output "custom_grid_runtime_removal_status=$($packet.status)"
Write-Output "notAuthorization=read-only local static verification; no code deletion, deploy, restart, provider request, Bot action, Grid disposition, DB mutation, archive write, or Production mutation"

if ($RequirePass -and $blockers.Count -ne 0) {
    throw "Custom Grid runtime removal has $($blockers.Count) blocker(s)."
}
