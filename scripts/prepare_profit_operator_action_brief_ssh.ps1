param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [int]$ReplayDays = 30,
    [int]$ReplayLimit = 500,
    [int]$ChildTimeoutSeconds = 900,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 64 -or $Value -notmatch "^[A-Za-z0-9._:-]+$") {
        throw "$Name contains unsupported characters for profit operator action brief arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for profit operator action brief."
    }

    Write-Host "[profit-operator-action-brief] child_start script=$ScriptName"
    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
    Write-Host "[profit-operator-action-brief] child_complete script=$ScriptName exitCode=$exitCode"
    return [pscustomobject]@{
        Text = ($output | Out-String -Width 4096)
        ExitCode = $exitCode
    }
}

if ([string]::IsNullOrWhiteSpace($SshHost)) {
    throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST."
}
if ([string]::IsNullOrWhiteSpace($SshKey)) {
    throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY."
}
if (-not (Test-Path -LiteralPath $SshKey)) {
    throw "SSH key not found: $SshKey"
}
if ($ReplayDays -lt 1 -or $ReplayDays -gt 90) {
    throw "ReplayDays must be between 1 and 90."
}
if ($ReplayLimit -lt 1 -or $ReplayLimit -gt 500) {
    throw "ReplayLimit must be between 1 and 500."
}
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) {
    throw "ChildTimeoutSeconds must be between 60 and 3600."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$matrix = Invoke-ReadOnlyScript -ScriptName "prepare_profit_operator_review_matrix_ssh.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId",
    "-ReplayDays", "$ReplayDays",
    "-ReplayLimit", "$ReplayLimit",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds",
    "-RequireReviewItems"
)

$matrixStatus = Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_status="
$matrixPacket = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_packet=")
$matrixNextAction = Get-LastPrefixedValue -Text $matrix.Text -Prefix "profit_operator_review_matrix_next_action="

$actionItems = [System.Collections.Generic.List[object]]::new()
$blockedItems = [System.Collections.Generic.List[object]]::new()
$decisionLanes = [System.Collections.Generic.List[object]]::new()
$exitSideReady = $false

if ($null -ne $matrixPacket -and $null -ne $matrixPacket.reviewItems) {
    foreach ($item in @($matrixPacket.reviewItems)) {
        $lane = [string]$item.lane
        $ready = $item.readyForOperatorReview -eq $true
        $recommendation = "KEEP_COLLECTING_EVIDENCE"
        $actionClass = "BLOCKED_OR_PENDING"
        $operatorAction = [string]$item.nextAction
        if ($lane -eq "exit-side" -and $ready) {
            $exitSideReady = $true
            $recommendation = "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION"
            $actionClass = "OPERATOR_REVIEW_READY_NOT_LIVE"
        } elseif ($lane -eq "entry-filter") {
            $recommendation = "DO_NOT_RELAX_ENTRY_FILTERS_KEEP_GOVERNANCE_REVIEW"
        } elseif ($lane -eq "data-freshness-replay") {
            $recommendation = "COLLECT_DATAFRESHNESS_REPLAY_SNAPSHOTS_BEFORE_POLICY_REVIEW"
        }

        $briefItem = [pscustomobject]@{
            lane = $lane
            priority = [string]$item.priority
            status = [string]$item.status
            readyForOperatorReview = $ready
            actionClass = $actionClass
            recommendation = $recommendation
            evidenceMarkers = @($item.evidenceMarkers)
            missingRequirements = @($item.missingRequirements)
            operatorAction = $operatorAction
            notAuthorization = "this action item is read-only review routing only and does not authorize live trading, policy relaxation, orders, OCO, close-position, DB/grid/fund/Earn/Telegram/exchange mutation, deploy, restart, or production env changes"
        }
        $actionItems.Add($briefItem)
        if (-not $ready) {
            $blockedItems.Add($briefItem)
        }

        $decisionClass = "EVIDENCE_COLLECTION"
        $separateAuthorizationRequired = @("separate operator review before any live or mutation change")
        $allowedFromThisBrief = @("route read-only evidence")
        $forbiddenFromThisBrief = @("enable live trading", "change production env", "deploy", "place orders", "modify OCO", "close positions")
        if ($lane -eq "exit-side") {
            $decisionClass = if ($ready) { "EXIT_SIDE_REVIEW_READY_NOT_LIVE" } else { "EXIT_SIDE_REVIEW_BLOCKED" }
            $separateAuthorizationRequired = @("enable trailing scheduler or trailing live mode", "change strategy opt-in or exit policy", "close any position", "modify or cancel OCO", "deploy runtime changes")
            $allowedFromThisBrief = if ($ready) { @("prepare separate exit-side operator review", "attach trailing and strategy 485 evidence") } else { @("collect exit-side replay and position-risk evidence") }
            $forbiddenFromThisBrief = @("enable trailing scheduler", "enable live trading", "close positions", "modify OCO", "change production env", "deploy")
        } elseif ($lane -eq "entry-filter") {
            $decisionClass = if ($ready) { "ENTRY_FILTER_REVIEW_READY_NOT_LIVE" } else { "ENTRY_FILTER_POLICY_BLOCKED" }
            $separateAuthorizationRequired = @("relax EntryDedup/DataFreshness/live policy", "enable live entry policy changes", "approve governance relaxation")
            $allowedFromThisBrief = @("keep entry/filter policy unchanged", "route governance and missed-opportunity evidence")
            $forbiddenFromThisBrief = @("relax EntryDedup", "relax DataFreshnessGuard", "enable TinyLive or entry execution", "change live policy")
        } elseif ($lane -eq "data-freshness-replay") {
            $decisionClass = if ($ready) { "DATAFRESHNESS_REPLAY_REVIEW_READY_NOT_LIVE" } else { "DATAFRESHNESS_REPLAY_BLOCKED" }
            $separateAuthorizationRequired = @("approve DataFreshness shadow/replay policy", "enable replay collector", "relax DataFreshnessGuard", "enable live entry policy changes")
            $allowedFromThisBrief = @("keep DataFreshnessGuard strict", "collect replayCandidateId and counterfactual snapshots", "route DataFreshness replay evidence")
            $forbiddenFromThisBrief = @("relax DataFreshnessGuard", "create live signals", "send Telegram", "place orders", "modify OCO", "change scheduler/live policy")
        }
        $decisionLanes.Add([pscustomobject]@{
            lane = $lane
            priority = [string]$item.priority
            decisionClass = $decisionClass
            status = [string]$item.status
            readyForOperatorReview = $ready
            recommendation = $recommendation
            evidenceMarkers = @($item.evidenceMarkers)
            missingRequirements = @($item.missingRequirements)
            separateAuthorizationRequired = @($separateAuthorizationRequired)
            allowedFromThisBrief = @($allowedFromThisBrief)
            forbiddenFromThisBrief = @($forbiddenFromThisBrief)
            nextAction = $operatorAction
        })
    }
}

$briefStatus = "NO_REVIEW_READY_ITEMS"
$primaryRecommendation = "CONTINUE_READ_ONLY_EVIDENCE_COLLECTION"
if ($matrix.ExitCode -ne 0 -or $null -eq $matrixPacket) {
    $briefStatus = "NO_EVIDENCE"
    $primaryRecommendation = "FIX_PROFIT_MATRIX_COLLECTION"
} elseif ($exitSideReady) {
    $briefStatus = "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE"
    $primaryRecommendation = "REVIEW_EXIT_SIDE_TRAILING_AND_STRATEGY485_NOT_MUTATION"
} elseif ($matrixStatus -eq "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    $briefStatus = "HAS_REVIEW_READY_ITEMS_NOT_LIVE"
    $primaryRecommendation = "REVIEW_READY_READ_ONLY_ITEMS_SEPARATELY"
}

$brief = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_ACTION_BRIEF"
    status = $briefStatus
    symbol = $Symbol
    matrixStatus = $matrixStatus
    primaryRecommendation = $primaryRecommendation
    recommendedNextReview = if ($exitSideReady) { "EXIT_SIDE_OPERATOR_REVIEW" } else { "READ_ONLY_EVIDENCE_COLLECTION" }
    decisionLanes = @($decisionLanes)
    actionItems = @($actionItems)
    blockedItems = @($blockedItems)
    doNotActions = @(
        "do not enable live trading from this brief",
        "do not enable trailing scheduler from this brief",
        "do not close positions or modify OCO from this brief",
        "do not relax EntryDedup/DataFreshness/live policy from this brief",
        "do not deploy or change production env from this brief"
    )
    sourceMatrix = "prepare_profit_operator_review_matrix_ssh.ps1"
    sourceMatrixExitCode = $matrix.ExitCode
    nextAction = if ($exitSideReady) { "Prepare a separate exit-side operator review using the attached read-only evidence; keep entry/filter and DataFreshness lanes blocked until their evidence clears." } else { $matrixNextAction }
    notAuthorization = "read-only profit operator action brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host "[profit-operator-action-brief] read-only brief"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_review_matrix_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_matrix=prepare_profit_operator_review_matrix_ssh.ps1 exitCode=$($matrix.ExitCode)"
Write-Host "profit_operator_review_matrix_status=$matrixStatus"
Write-Host "profit_operator_action_primary_recommendation=$primaryRecommendation"
Write-Host ("profit_operator_decision_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($decisionLanes)))
Write-Host ("profit_operator_action_items=" + (ConvertTo-Json -Compress -Depth 8 @($actionItems)))
Write-Host ("profit_operator_action_blocked_items=" + (ConvertTo-Json -Compress -Depth 8 @($blockedItems)))
Write-Host ("profit_operator_action_brief_packet=" + (ConvertTo-Json -Compress -Depth 10 $brief))
Write-Host "profit_operator_action_brief_status=$briefStatus"
Write-Host "profit_operator_action_next_action=$($brief.nextAction)"
Write-Host "notAuthorization=read-only profit operator action brief only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-action-brief] read-only check complete"

if ($RequireReady -and $briefStatus -ne "READY_FOR_EXIT_SIDE_REVIEW_NOT_LIVE" -and $briefStatus -ne "HAS_REVIEW_READY_ITEMS_NOT_LIVE") {
    throw "Profit operator action brief has no review-ready items."
}
