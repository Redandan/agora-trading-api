param(
    [string]$LiveGateSemanticsDiffLogPath = "target/profit-review/entry-dedup-live-gate-semantics-diff-latest.log",
    [string]$BtLiveSignalRepositoryPath = "src/main/java/com/agora/repository/trading/BtLiveSignalRepository.java",
    [string]$LocalTradingViewExecutionServiceTestPath = "src/test/java/com/agora/service/tradingview/LocalTradingViewExecutionServiceTest.java",
    [string]$ReportPath = "target/profit-review/profit-optimization-report-20260705.md",
    [string]$RunbookPath = "docs/deploy-runbook.md",
    [int]$MaxAgeMinutes = 240,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path (Split-Path -Parent $PSScriptRoot) $PathValue
}

function Assert-PathTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^[A-Za-z0-9._:/\\-]+$") {
        throw "$Name contains unsupported characters."
    }
}

function Read-TextFile {
    param([string]$Name, [string]$PathValue)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name file not found: $resolved"
    }
    return Get-Content -Raw -LiteralPath $resolved
}

function Read-FreshLog {
    param([string]$Name, [string]$PathValue, [int]$MaxAge)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if (-not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log not found: $resolved"
    }
    $item = Get-Item -LiteralPath $resolved
    $age = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
    [pscustomobject]@{
        Name = $Name
        Path = $PathValue
        ResolvedPath = $resolved
        AgeMinutes = $age
        Fresh = $age -le $MaxAge
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object {
            $_.StartsWith($Prefix) -or $_.TrimStart().StartsWith($Prefix)
        } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    $valueLine = [string]$line
    if (-not $valueLine.StartsWith($Prefix)) {
        $valueLine = $valueLine.TrimStart()
    }
    return $valueLine.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-Missing {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Get-Prop {
    param([object]$Object, [string]$Name, [object]$Default = $null)
    if ($null -eq $Object) { return $Default }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $Default }
    if ($null -eq $property.Value) { return $Default }
    return $property.Value
}

function Get-NestedProp {
    param([object]$Object, [string[]]$Path, [object]$Default = $null)
    $current = $Object
    foreach ($part in $Path) {
        $current = Get-Prop -Object $current -Name $part -Default $null
        if ($null -eq $current) { return $Default }
    }
    return $current
}

function Get-BoolValue {
    param([object]$Value)
    if ($null -eq $Value) { return $false }
    if ($Value -is [bool]) { return [bool]$Value }
    return ([string]$Value).Trim().Equals("true", [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-IntValue {
    param([object]$Value)
    $parsed = 0
    if ($null -eq $Value) { return 0 }
    if ($Value -is [int]) { return $Value }
    if ($Value -is [long]) { return [int]$Value }
    if ($Value -is [double]) { return [int]$Value }
    if ($Value -is [decimal]) { return [int]$Value }
    if ([int]::TryParse(([string]$Value).Trim(), [ref]$parsed)) { return $parsed }
    return 0
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 10080) {
    throw "MaxAgeMinutes must be between 1 and 10080."
}

foreach ($path in @(
        $LiveGateSemanticsDiffLogPath,
        $BtLiveSignalRepositoryPath,
        $LocalTradingViewExecutionServiceTestPath,
        $ReportPath,
        $RunbookPath
    )) {
    Assert-PathTokenSafe -Name "Path" -Value $path
}

$diffLog = Read-FreshLog -Name "entry-dedup-live-gate-semantics-diff" -PathValue $LiveGateSemanticsDiffLogPath -MaxAge $MaxAgeMinutes
$repoSource = Read-TextFile -Name "BtLiveSignalRepository source" -PathValue $BtLiveSignalRepositoryPath
$localTvTestSource = Read-TextFile -Name "LocalTradingViewExecutionServiceTest source" -PathValue $LocalTradingViewExecutionServiceTestPath

$missing = [System.Collections.Generic.List[string]]::new()
if (-not $diffLog.Fresh) {
    Add-Missing -List $missing -Value "$($diffLog.Name) log fresh within $MaxAgeMinutes minutes"
}

$diffPacket = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $diffLog.Text -Prefix "entry_dedup_live_gate_semantics_diff_packet=")
if ($null -eq $diffPacket) { Add-Missing -List $missing -Value "live gate semantics diff packet JSON present" }

$diffStatus = [string](Get-Prop -Object $diffPacket -Name "status" -Default "UNKNOWN")
if ($diffStatus -ne "READY_FOR_ENTRY_DEDUP_LIVE_GATE_SEMANTICS_DIFF_REVIEW_NOT_LIVE") {
    Add-Missing -List $missing -Value "live gate semantics diff packet ready"
}

$liveSignalGateScope = [string](Get-NestedProp -Object $diffPacket -Path @("sourceEvidence", "liveSignalGateScope") -Default "UNKNOWN")
$stagedAddGateScope = [string](Get-NestedProp -Object $diffPacket -Path @("sourceEvidence", "stagedAddGateScope") -Default "UNKNOWN")
$scopeMismatchPresent = Get-BoolValue (Get-NestedProp -Object $diffPacket -Path @("sourceEvidence", "scopeMismatchPresent") -Default $false)
$explainsCurrentNoBuy = Get-BoolValue (Get-NestedProp -Object $diffPacket -Path @("blockerEvidence", "explainsCurrentNoBuy") -Default $false)
$remainingBlockerCount = Get-IntValue (Get-NestedProp -Object $diffPacket -Path @("blockerEvidence", "remainingBlockerCount") -Default 0)
$behaviorChangeAlreadyAllowed = Get-BoolValue (Get-NestedProp -Object $diffPacket -Path @("reviewEnvelope", "behaviorChangeAllowed") -Default $true)
$orderAlreadyAllowed = Get-BoolValue (Get-NestedProp -Object $diffPacket -Path @("reviewEnvelope", "orderAllowed") -Default $true)

$repositoryHasAutoTradedGateMethod = $repoSource.Contains("existsOpenAutoTradedPosition")
$localTvHasNonAutoIgnoredTest = $localTvTestSource.Contains("liveEnabledIgnoresNonAutoTradedOpenSignalWhenCheckingOpenPositionGate") `
    -and $localTvTestSource.Contains("existsOpenAutoTradedPosition")

$proposedConfigKey = "entryDedupOpenExposureScope"
$defaultScope = "ALL_OPEN_ROWS"
$requestedScope = "AUTO_TRADED_OPEN_ROWS"
$confirmText = "AUTHORIZE_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_AUTO_TRADED_ONLY_REVIEW"

$reportText = Read-TextFile -Name "profit optimization report" -PathValue $ReportPath
$runbookText = Read-TextFile -Name "deploy runbook" -PathValue $RunbookPath
$runbookNormalizedText = [regex]::Replace($runbookText, "\s+", " ")
$reportUpdated = $reportText.Contains("EntryDedup Live Gate Default-Off Change Request") -and $reportText.Contains($confirmText)
$runbookUpdated = $runbookText.Contains("prepare_entry_dedup_live_gate_default_off_change_request_packet.ps1") -and $runbookNormalizedText.Contains("default-off request is not authorization to change runtime behavior")

if ($liveSignalGateScope -ne "ALL_EXIT_TIME_NULL_ROWS") { Add-Missing -List $missing -Value "current live signal gate scope is all open rows" }
if ($stagedAddGateScope -ne "AUTO_TRADED_EXIT_TIME_NULL_ROWS") { Add-Missing -List $missing -Value "staged-add gate scope is auto-traded rows" }
if (-not $scopeMismatchPresent) { Add-Missing -List $missing -Value "gate scope mismatch is present" }
if (-not $explainsCurrentNoBuy) { Add-Missing -List $missing -Value "gate mismatch explains current no-buy blocker" }
if ($remainingBlockerCount -lt 1) { Add-Missing -List $missing -Value "remaining blockers still exist before any mutation" }
if ($behaviorChangeAlreadyAllowed) { Add-Missing -List $missing -Value "diff packet keeps behavior change disallowed" }
if ($orderAlreadyAllowed) { Add-Missing -List $missing -Value "diff packet keeps orders disallowed" }
if (-not $repositoryHasAutoTradedGateMethod) { Add-Missing -List $missing -Value "repository has auto-traded gate method" }
if (-not $localTvHasNonAutoIgnoredTest) { Add-Missing -List $missing -Value "LocalTradingView already proves non-auto rows are ignored by auto-traded gate" }
if (-not $reportUpdated) { Add-Missing -List $missing -Value "profit optimization report includes default-off change request" }
if (-not $runbookUpdated) { Add-Missing -List $missing -Value "deploy runbook includes default-off change request instructions" }

$ready = $missing.Count -eq 0
$status = if ($ready) {
    "READY_FOR_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_NOT_LIVE"
} else {
    "BLOCKED_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_INCOMPLETE_NOT_LIVE"
}
$decision = if ($ready) {
    "PREPARE_OPERATOR_REVIEW_FOR_DEFAULT_OFF_AUTO_TRADED_GATE_SCOPE_NOT_IMPLEMENTATION"
} else {
    "REFRESH_ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_EVIDENCE"
}

$packet = [ordered]@{
    packetType = "ENTRY_DEDUP_LIVE_GATE_DEFAULT_OFF_CHANGE_REQUEST_PACKET"
    status = $status
    decision = $decision
    proposedChange = [ordered]@{
        configKey = $proposedConfigKey
        defaultScope = $defaultScope
        requestedOptionalScope = $requestedScope
        confirmText = $confirmText
        noBehaviorChangeByDefault = $true
        requiresSeparateImplementationAuthorization = $true
        requiredTests = @(
            "default config keeps LiveSignalEvaluator all-open-row dedup behavior",
            "explicit AUTO_TRADED_OPEN_ROWS scope ignores non-auto zero-qty open rows",
            "explicit AUTO_TRADED_OPEN_ROWS scope still blocks auto-traded open positions",
            "audit context records selected gate scope and behavior-change flag"
        )
    }
    evidence = [ordered]@{
        liveSignalGateScope = $liveSignalGateScope
        stagedAddGateScope = $stagedAddGateScope
        scopeMismatchPresent = $scopeMismatchPresent
        explainsCurrentNoBuy = $explainsCurrentNoBuy
        repositoryHasAutoTradedGateMethod = $repositoryHasAutoTradedGateMethod
        localTradingViewHasNonAutoIgnoredTest = $localTvHasNonAutoIgnoredTest
    }
    reviewEnvelope = [ordered]@{
        reviewOnly = $true
        requestReady = $ready
        implementationAllowed = $false
        behaviorChangeAllowed = $false
        liveGateChangeAllowed = $false
        liveExecutionReady = $false
        mutationReady = $false
        collectorActivationAllowed = $false
        runtimeEvidenceWriteAllowed = $false
        entryDedupPolicyChangeAllowed = $false
        dataFreshnessPolicyChangeAllowed = $false
        livePolicyChangeAllowed = $false
        stagedAddExecutionAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        positionOrOcoMutationAllowed = $false
        telegramSendAllowed = $false
        deployOrEnvChangeAllowed = $false
        dbMutationAllowed = $false
        exchangeMutationAllowed = $false
    }
    missingRequirements = @($missing)
    nextAction = "Use this packet as an operator request only. A separate implementation authorization is required before adding the default-off LiveSignalEvaluator gate-scope option."
    notAuthorization = "read-only EntryDedup live gate default-off change request only; does not implement Java behavior, clear exposure, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
}

Write-Host "[entry-dedup-live-gate-default-off-change-request-packet] read-only packet"
Write-Host "scope=READ_ONLY; reads saved live-gate diff log plus local source/report/runbook only; no Java behavior, SSH, MCP, production env, DB, runtime evidence write, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "entry_dedup_live_gate_default_off_change_request_status=$status"
Write-Host "entry_dedup_live_gate_default_off_change_request_decision=$decision"
Write-Host "entry_dedup_live_gate_default_off_change_request_config_key=$proposedConfigKey"
Write-Host "entry_dedup_live_gate_default_off_change_request_default_scope=$defaultScope"
Write-Host "entry_dedup_live_gate_default_off_change_request_requested_optional_scope=$requestedScope"
Write-Host "entry_dedup_live_gate_default_off_change_request_confirm_text=$confirmText"
Write-Host "entry_dedup_live_gate_default_off_change_request_scope_mismatch_present=$($scopeMismatchPresent.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_default_off_change_request_explains_current_no_buy=$($explainsCurrentNoBuy.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_default_off_change_request_repository_has_auto_traded_gate_method=$($repositoryHasAutoTradedGateMethod.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_default_off_change_request_local_tv_has_non_auto_ignored_test=$($localTvHasNonAutoIgnoredTest.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_default_off_change_request_report_updated=$($reportUpdated.ToString().ToLowerInvariant())"
Write-Host "entry_dedup_live_gate_default_off_change_request_runbook_updated=$($runbookUpdated.ToString().ToLowerInvariant())"
Write-Host ("entry_dedup_live_gate_default_off_change_request_missing_requirements=" + (ConvertTo-Json -Compress @($missing)))
Write-Host ("entry_dedup_live_gate_default_off_change_request_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "request_ready=$($ready.ToString().ToLowerInvariant())"
Write-Host "implementation_allowed=false"
Write-Host "behavior_change_allowed=false"
Write-Host "live_gate_change_allowed=false"
Write-Host "live_execution_ready=false"
Write-Host "mutation_ready=false"
Write-Host "collector_activation_allowed=false"
Write-Host "runtime_evidence_write_allowed=false"
Write-Host "entry_dedup_policy_change_allowed=false"
Write-Host "data_freshness_policy_change_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "staged_add_execution_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "deploy_or_env_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "notAuthorization=read-only EntryDedup live gate default-off change request only; does not implement Java behavior, clear exposure, activate collectors, write runtime evidence, relax policy, deploy, change production env, enable live execution, place orders, modify OCO/grid/fund/Earn/Telegram/exchange state, mutate DB, or run external backfill/import"
Write-Host "[entry-dedup-live-gate-default-off-change-request-packet] read-only check complete"

if ($RequireReady -and -not $ready) {
    throw "EntryDedup live gate default-off change request packet is not ready: $status; missing=$(@($missing) -join '; ')"
}
