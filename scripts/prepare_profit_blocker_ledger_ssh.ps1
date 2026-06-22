param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$ReplayIdDays = 3,
    [int]$TinyLiveHours = 720,
    [int]$Limit = 200,
    [switch]$RequireActionable
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
        throw "$Name contains unsupported characters for profit ledger arguments."
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

function Convert-JsonArrayOrEmpty {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return @()
    }
    try {
        return @($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return @()
    }
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

function Add-UniqueString {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Add-LedgerItem {
    param(
        [System.Collections.Generic.List[object]]$List,
        [int]$Priority,
        [string]$Blocker,
        [string]$Source,
        [string]$Status,
        [string]$NextAction,
        [string[]]$RequiredCommands,
        [string[]]$EvidenceMarkers = @()
    )

    if ([string]::IsNullOrWhiteSpace($Blocker)) {
        return
    }
    $List.Add([pscustomobject]@{
            priority = $Priority
            blocker = $Blocker
            source = $Source
            status = $Status
            evidenceMarkers = @($EvidenceMarkers)
            nextAction = $NextAction
            requiredReadOnlyCommands = @($RequiredCommands)
        })
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
        throw "Unable to find powershell or pwsh for profit blocker ledger."
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    return [pscustomobject]@{
        ScriptName = $ScriptName
        Text = ($output | Out-String)
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
if ($ReviewDays -lt 1 -or $ReviewDays -gt 180) {
    throw "ReviewDays must be between 1 and 180."
}
if ($ReplayIdDays -lt 1 -or $ReplayIdDays -gt 30) {
    throw "ReplayIdDays must be between 1 and 30."
}
if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 720) {
    throw "TinyLiveHours must be between 1 and 720."
}
if ($Limit -lt 1 -or $Limit -gt 1000) {
    throw "Limit must be between 1 and 1000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$commonArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$runtimePacket = Invoke-ReadOnlyScript -ScriptName "prepare_profit_runtime_deploy_review_packet_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string]$ReviewDays,
        "-TinyLiveHours", [string]$TinyLiveHours
    ))
$shadowPacket = Invoke-ReadOnlyScript -ScriptName "prepare_profit_shadow_experiment_packet_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string]$ReviewDays,
        "-TinyLiveHours", [string]$TinyLiveHours
    ))
$strategy485Packet = Invoke-ReadOnlyScript -ScriptName "prepare_strategy485_operator_review_packet_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string]$ReviewDays
    ))
$replayObservation = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_replay_observation_bundle_ssh.ps1" -Arguments ($commonArgs + @(
        "-ReviewDays", [string]$ReviewDays,
        "-ReplayIdDays", [string]$ReplayIdDays,
        "-Limit", [string]$Limit
    ))

$ledgerItems = [System.Collections.Generic.List[object]]::new()
$missingRequirements = [System.Collections.Generic.List[string]]::new()

foreach ($result in @($runtimePacket, $shadowPacket, $strategy485Packet, $replayObservation)) {
    if ($result.ExitCode -ne 0) {
        Add-UniqueString -List $missingRequirements -Value "$($result.ScriptName) completed"
        Add-LedgerItem -List $ledgerItems -Priority 1 -Blocker "read-only evidence collection failed" -Source $result.ScriptName -Status "NO_EVIDENCE" -NextAction "Fix this read-only evidence source, then rerun the profit blocker ledger." -RequiredCommands @(".\scripts\$($result.ScriptName)")
    }
}

$runtimeStatus = Get-LastPrefixedValue -Text $runtimePacket.Text -Prefix "profit_runtime_deploy_packet_status="
$originDelta = Get-LastPrefixedValue -Text $runtimePacket.Text -Prefix "origin_delta_status="
$runtimeMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $runtimePacket.Text -Prefix "profit_runtime_deploy_packet_missing_requirements=")
$runtimePacketJson = Get-LastPrefixedValue -Text $runtimePacket.Text -Prefix "profit_runtime_deploy_review_packet="
$runtimePacketObject = Convert-JsonObjectOrNull -Value $runtimePacketJson

$shadowStatus = Get-LastPrefixedValue -Text $shadowPacket.Text -Prefix "profit_shadow_packet_status="
$shadowMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $shadowPacket.Text -Prefix "profit_shadow_packet_missing_requirements=")

$strategy485Status = Get-LastPrefixedValue -Text $strategy485Packet.Text -Prefix "strategy485_operator_packet_status="
$strategy485Missing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $strategy485Packet.Text -Prefix "strategy485_operator_packet_missing_requirements=")

$replayRecommendation = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  replay_observation_bundle_recommendation="
$replayItems = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  replay_observation_review_items=")
$replayInputStage = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  replay_input_stage="
$replayInputNextAction = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  replay_input_next_action="
$latestDataFreshnessRowTime = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  latest_data_freshness_row_time="
$latestDataFreshnessRowAgeHours = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  latest_data_freshness_row_age_hours="
$dataFreshnessRows3d = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  data_freshness_rows_3d="
$completeReplayRows = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  complete_replayable_candidate_rows="
$missingCounterfactualFields = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  missing_counterfactual_fields="
$collectorStatusCounts = Get-LastPrefixedValue -Text $replayObservation.Text -Prefix "  collector_status_counts="

foreach ($item in @($runtimeMissing + $shadowMissing + $strategy485Missing)) {
    Add-UniqueString -List $missingRequirements -Value ([string]$item)
}

if ($runtimeStatus -eq "READY_FOR_DEPLOY_REVIEW_NOT_DEPLOYED" -or $originDelta -eq "RUNTIME_DRIFT") {
    Add-LedgerItem -List $ledgerItems -Priority 1 -Blocker "deployed runtime current" -Source "prepare_profit_runtime_deploy_review_packet_ssh.ps1" -Status $runtimeStatus -NextAction "Request separate deploy authorization, deploy current origin/main, then rerun post-deploy profit validation." -RequiredCommands @(
        ".\scripts\prepare_profit_runtime_deploy_review_packet_ssh.ps1 -RequireReady",
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_post_deploy_profit_validation_ssh.ps1"
    )
}

if (@($replayItems) -contains "DEPLOY_CURRENT_RUNTIME_BEFORE_REPLAY_OBSERVATION") {
    Add-LedgerItem -List $ledgerItems -Priority 2 -Blocker "fresh DataFreshness replayCandidateId rows" -Source "smoke_data_freshness_replay_observation_bundle_ssh.ps1" -Status $replayRecommendation -NextAction "Deploy current runtime before waiting for new DataFreshness replayCandidateId evidence." -RequiredCommands @(
        ".\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        ".\scripts\smoke_data_freshness_replay_candidate_id_ssh.ps1 -RequireObserved"
    ) -EvidenceMarkers @(
        "replay_input_stage=$replayInputStage",
        "latest_data_freshness_row_time=$latestDataFreshnessRowTime",
        "latest_data_freshness_row_age_hours=$latestDataFreshnessRowAgeHours",
        "data_freshness_rows_3d=$dataFreshnessRows3d"
    )
}
if (@($replayItems) -contains "COLLECT_ENTRY_TP_SL_EV_OCO_REPLAY_SNAPSHOTS" -or @($shadowMissing) -contains "complete DataFreshness replayable candidate rows") {
    Add-LedgerItem -List $ledgerItems -Priority 3 -Blocker "complete DataFreshness replayable candidate rows" -Source "prepare_profit_shadow_experiment_packet_ssh.ps1" -Status $shadowStatus -NextAction "Collect replayable entry/TP/SL, EV, OCO, and hard-gate snapshots before any shadow experiment review." -RequiredCommands @(
        ".\scripts\smoke_data_freshness_replay_observation_bundle_ssh.ps1",
        ".\scripts\prepare_profit_shadow_experiment_packet_ssh.ps1 -RequireReady"
    ) -EvidenceMarkers @(
        "replay_input_stage=$replayInputStage",
        "replay_input_next_action=$replayInputNextAction",
        "collector_status_counts=$collectorStatusCounts",
        "complete_replayable_candidate_rows=$completeReplayRows",
        "missing_counterfactual_fields=$missingCounterfactualFields"
    )
}
if ($strategy485Status -eq "BLOCKED_OCO_PROTECTION_FIRST" -or @($strategy485Missing) -contains "current strategy 485 OCO health") {
    Add-LedgerItem -List $ledgerItems -Priority 4 -Blocker "current strategy 485 OCO health" -Source "prepare_strategy485_operator_review_packet_ssh.ps1" -Status $strategy485Status -NextAction "Refresh strategy 485 read-only OCO/EV evidence before any operator review; this does not authorize position or OCO mutation." -RequiredCommands @(
        ".\scripts\prepare_strategy485_operator_review_packet_ssh.ps1 -RequireReady"
    )
}

$orderedLedger = @($ledgerItems | Sort-Object priority, blocker)
$ledgerStatus = "NO_ACTIONABLE_BLOCKERS"
$nextAction = "No read-only profit blockers were identified by the ledger; inspect source packet outputs before changing policy."
if (@($orderedLedger).Count -gt 0) {
    $ledgerStatus = "ACTIONABLE_READ_ONLY_BLOCKERS"
    $nextAction = [string]$orderedLedger[0].nextAction
}
if (@($orderedLedger | Where-Object { $_.blocker -eq "deployed runtime current" }).Count -gt 0) {
    $ledgerStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Runtime currentness is the first blocker; attach this ledger and the deploy review packet to a separate deploy authorization request."
}
if (@($missingRequirements).Count -gt 0 -and @($orderedLedger).Count -eq 0) {
    $ledgerStatus = "MISSING_REQUIREMENTS_WITHOUT_PRIORITIZED_LEDGER"
    $nextAction = "Review missing requirements and extend the ledger classifier before operator action."
}

$ledger = [pscustomobject]@{
    packetType = "PROFIT_BLOCKER_LEDGER"
    status = $ledgerStatus
    symbol = $Symbol
    originDeltaStatus = $originDelta
    runtimeDeployPacketStatus = $runtimeStatus
    profitShadowPacketStatus = $shadowStatus
    strategy485OperatorPacketStatus = $strategy485Status
    replayObservationRecommendation = $replayRecommendation
    monthlyPnlTotalUsdt = if ($null -ne $runtimePacketObject -and $null -ne $runtimePacketObject.PSObject.Properties["monthlyPnlTotalUsdt"]) { $runtimePacketObject.monthlyPnlTotalUsdt } else { "" }
    topProfitImprovementCandidate = if ($null -ne $runtimePacketObject -and $null -ne $runtimePacketObject.PSObject.Properties["topProfitImprovementCandidate"]) { $runtimePacketObject.topProfitImprovementCandidate } else { "" }
    blockerCount = @($orderedLedger).Count
    missingRequirementCount = @($missingRequirements).Count
    missingRequirements = @($missingRequirements)
    ledgerItems = @($orderedLedger)
    nextAction = $nextAction
    notAuthorization = "read-only profit blocker ledger only; does not deploy, restart, reload nginx, change production env, enable live trading, relax policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/DataFreshness policy changes"
}

Write-Host "[profit-blocker-ledger] read-only ledger"
Write-Host "scope=READ_ONLY; invokes read-only packet/smoke scripts only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_packet=prepare_profit_runtime_deploy_review_packet_ssh.ps1 exitCode=$($runtimePacket.ExitCode)"
Write-Host "source_packet=prepare_profit_shadow_experiment_packet_ssh.ps1 exitCode=$($shadowPacket.ExitCode)"
Write-Host "source_packet=prepare_strategy485_operator_review_packet_ssh.ps1 exitCode=$($strategy485Packet.ExitCode)"
Write-Host "source_packet=smoke_data_freshness_replay_observation_bundle_ssh.ps1 exitCode=$($replayObservation.ExitCode)"
Write-Host "origin_delta_status=$originDelta"
Write-Host "profit_runtime_deploy_packet_status=$runtimeStatus"
Write-Host "profit_shadow_packet_status=$shadowStatus"
Write-Host "strategy485_operator_packet_status=$strategy485Status"
Write-Host "replay_observation_bundle_recommendation=$replayRecommendation"
Write-Host "replay_input_stage=$replayInputStage"
Write-Host "replay_input_next_action=$replayInputNextAction"
Write-Host "latest_data_freshness_row_time=$latestDataFreshnessRowTime"
Write-Host "latest_data_freshness_row_age_hours=$latestDataFreshnessRowAgeHours"
Write-Host "complete_replayable_candidate_rows=$completeReplayRows"
Write-Host "missing_counterfactual_fields=$missingCounterfactualFields"
Write-Host ("profit_blocker_ledger_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_blocker_ledger_items=" + (ConvertTo-Json -Compress -Depth 8 @($orderedLedger)))
Write-Host ("profit_blocker_ledger_packet=" + (ConvertTo-Json -Compress -Depth 10 $ledger))
Write-Host "profit_blocker_ledger_status=$ledgerStatus"
Write-Host "profit_blocker_ledger_next_action=$nextAction"
Write-Host "notAuthorization=read-only profit blocker ledger only; does not deploy, restart, reload nginx, change production env, enable live trading, relax policy, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/DataFreshness policy changes"
Write-Host "[profit-blocker-ledger] read-only check complete"

if ($RequireActionable -and $ledgerStatus -eq "NO_ACTIONABLE_BLOCKERS") {
    throw "Profit blocker ledger found no actionable blockers; inspect source packet outputs before proceeding."
}
