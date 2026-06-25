param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$AgoraMarketApiToolsDir = "C:\Users\Redan\IdeaProjects\AgoraMarketAPI\tools\codex",
    [string]$Symbol = "BTCUSDT",
    [int]$LookbackHours = 72,
    [int]$CandidateLookbackHours = 168,
    [int]$GridCount = 8,
    [decimal]$PerLevelUsdt = 10,
    [decimal]$StopOutPct = 3.0,
    [int]$ChildTimeoutSeconds = 1200,
    [string]$GridReadinessWatchLog = "",
    [string]$OriginDeltaLog = "",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return (Join-Path (Split-Path -Parent $PSScriptRoot) $Path)
}

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
        throw "$Name contains unsupported characters for grid split-acceptance deploy handoff arguments."
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return $null }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Invoke-ReadOnlyScript {
    param([string]$ScriptName, [string[]]$Arguments)

    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing read-only script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for grid split-acceptance deploy handoff." }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    [pscustomobject]@{
        Text = ($output | Out-String -Width 8192)
        ExitCode = $exitCode
    }
}

function Read-LogOrInvoke {
    param(
        [string]$LogPath,
        [string]$ScriptName,
        [string[]]$Arguments
    )

    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        $resolved = Resolve-RepoPath $LogPath
        if (-not (Test-Path -LiteralPath $resolved)) {
            throw "Log not found: $resolved"
        }
        return [pscustomobject]@{
            Text = (Get-Content -Raw -LiteralPath $resolved)
            ExitCode = 0
            Source = $resolved
        }
    }

    $result = Invoke-ReadOnlyScript -ScriptName $ScriptName -Arguments $Arguments
    $result | Add-Member -NotePropertyName Source -NotePropertyValue $ScriptName
    return $result
}

if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 3600) { throw "ChildTimeoutSeconds must be between 60 and 3600." }
if ($LookbackHours -lt 24 -or $LookbackHours -gt 720) { throw "LookbackHours must be between 24 and 720." }
if ($CandidateLookbackHours -lt 72 -or $CandidateLookbackHours -gt 720) { throw "CandidateLookbackHours must be between 72 and 720." }
if ($GridCount -lt 4 -or $GridCount -gt 24) { throw "GridCount must be between 4 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }

$usesLiveRefresh = [string]::IsNullOrWhiteSpace($GridReadinessWatchLog) -or [string]::IsNullOrWhiteSpace($OriginDeltaLog)
if ($usesLiveRefresh) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
}
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$repoRoot = Split-Path -Parent $PSScriptRoot
$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$watchArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-AgoraMarketApiToolsDir", $AgoraMarketApiToolsDir,
    "-Symbol", $Symbol,
    "-MaxAttempts", "1",
    "-SleepSeconds", "0",
    "-LookbackHours", "$LookbackHours",
    "-CandidateLookbackHours", "$CandidateLookbackHours",
    "-GridCount", "$GridCount",
    "-PerLevelUsdt", "$PerLevelUsdt",
    "-StopOutPct", "$StopOutPct",
    "-ChildTimeoutSeconds", "$ChildTimeoutSeconds"
)
$originArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)

$watchResult = Read-LogOrInvoke -LogPath $GridReadinessWatchLog -ScriptName "watch_grid_open_readiness_ssh.ps1" -Arguments $watchArgs
$originResult = Read-LogOrInvoke -LogPath $OriginDeltaLog -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments $originArgs

$watchText = [string]$watchResult.Text
$originText = [string]$originResult.Text
$watchStatus = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_status=" -Default "UNKNOWN"
$watchReason = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_reason=" -Default "UNKNOWN"
$watchOpenable = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_openable=" -Default "false"
$watchScore = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_score_pct=" -Default ""
$watchPassedGates = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_passed_gates=" -Default ""
$watchTopBlocker = Get-LastPrefixedValue -Text $watchText -Prefix "grid_open_readiness_watch_top_blocker=" -Default ""
$originDeltaStatus = Get-LastPrefixedValue -Text $originText -Prefix "origin_delta_status=" -Default "UNKNOWN"
$originRuntimeDeltaFiles = Get-LastPrefixedValue -Text $originText -Prefix "origin_runtime_delta_files=" -Default ""
$deploymentMetadataStatus = Get-LastPrefixedValue -Text $originText -Prefix "deployment_metadata_status=" -Default "UNKNOWN"
$originMetadataStatus = Get-LastPrefixedValue -Text $originText -Prefix "origin_metadata_status=" -Default "UNKNOWN"
$serverWorktreeCommit = Get-LastPrefixedValue -Text $originText -Prefix "server_worktree_commit=" -Default "UNKNOWN"
$originMainCommitFromMetadata = Get-LastPrefixedValue -Text $originText -Prefix "origin_main_commit=" -Default "UNKNOWN"
$runtimeDeltaPaths = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $originText -Prefix "origin_runtime_delta_paths=" -Default "[]")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if (-not $worktreeClean) { Add-Unique -List $missingRequirements -Value "local worktree clean before grid split-acceptance deploy handoff" }
if ($behindCount -gt 0) { Add-Unique -List $missingRequirements -Value "local branch not behind origin/main" }
if ($watchResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "grid open readiness watch completed" }
if ($originResult.ExitCode -ne 0) { Add-Unique -List $missingRequirements -Value "origin delta metadata classifier completed" }
if ($watchStatus -eq "UNKNOWN") { Add-Unique -List $missingRequirements -Value "grid_open_readiness_watch_status evidence" }
if ($originDeltaStatus -eq "UNKNOWN") { Add-Unique -List $missingRequirements -Value "origin_delta_status evidence" }
if ($watchTopBlocker -ne "SPLIT_ACCEPTANCE_NOT_PASSING") { Add-Unique -List $missingRequirements -Value "grid top blocker is SPLIT_ACCEPTANCE_NOT_PASSING" }
if ($watchOpenable -ne "false") { Add-Unique -List $missingRequirements -Value "grid remains not openable before deploy handoff" }

$deployCurrentRuntimeRequired = ($watchTopBlocker -eq "SPLIT_ACCEPTANCE_NOT_PASSING" -and $originDeltaStatus -in @("RUNTIME_DRIFT", "DOCS_TOOLING_ONLY_DRIFT", "CURRENT_ORIGIN_MAIN"))
$runtimeDrift = ($originDeltaStatus -eq "RUNTIME_DRIFT" -or $deploymentMetadataStatus -eq "RUNTIME_DRIFT")
$metadataCurrent = ($originDeltaStatus -eq "CURRENT_ORIGIN_MAIN" -and $deploymentMetadataStatus -in @("CURRENT", "DOCS_TOOLING_ONLY_DRIFT"))

$status = "NOT_READY_GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_NOT_MUTATION"
$decision = "REFRESH_GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_EVIDENCE"
$nextAction = "Refresh grid readiness watch and origin-delta metadata before requesting deploy authorization."
if ($missingRequirements.Count -eq 0 -and $runtimeDrift) {
    $status = "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION"
    $decision = "REQUEST_SEPARATE_DEPLOY_CURRENT_MAIN_AND_READ_ONLY_GRID_VERIFICATION"
    $nextAction = "Request separate deploy/restart authorization for current origin/main only, then rerun split acceptance and grid open readiness watch."
} elseif ($missingRequirements.Count -eq 0 -and $metadataCurrent) {
    $status = "DEPLOY_HANDOFF_NOT_NEEDED_RERUN_SPLIT_ACCEPTANCE_GRID_WATCH"
    $decision = "RERUN_SPLIT_ACCEPTANCE_AND_GRID_READINESS"
    $nextAction = "Runtime metadata appears current; rerun split acceptance and grid readiness watch before asking for deploy."
} elseif ($missingRequirements.Count -eq 0) {
    $status = "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_REVIEW_NEEDED_NOT_MUTATION"
    $decision = "REVIEW_METADATA_CLASSIFICATION_BEFORE_DEPLOY_AUTHORIZATION"
    $nextAction = "Review origin-delta metadata classification, then decide whether a separate deploy authorization is needed."
}

$packet = [ordered]@{
    packetType = "GRID_SPLIT_ACCEPTANCE_DEPLOY_HANDOFF_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol
    localHeadCommit = $headCommit
    localOriginMainCommit = $originCommit
    localAheadCount = $aheadCount
    localBehindCount = $behindCount
    localWorktreeClean = $worktreeClean
    gridReadinessWatchSource = $watchResult.Source
    originDeltaSource = $originResult.Source
    gridReadinessWatchStatus = $watchStatus
    gridReadinessWatchReason = $watchReason
    gridOpenableNow = $false
    gridOpenReadinessScorePct = $watchScore
    gridOpenReadinessPassedGates = $watchPassedGates
    gridOpenTopBlocker = $watchTopBlocker
    deploymentMetadataStatus = $deploymentMetadataStatus
    originMetadataStatus = $originMetadataStatus
    originDeltaStatus = $originDeltaStatus
    originRuntimeDeltaFiles = $originRuntimeDeltaFiles
    originRuntimeDeltaPaths = @($runtimeDeltaPaths)
    serverWorktreeCommit = $serverWorktreeCommit
    originMainCommitFromMetadata = $originMainCommitFromMetadata
    deployCurrentRuntimeRequired = $deployCurrentRuntimeRequired
    requiredSeparateAuthorization = @(
        "deploy/restart current origin/main only",
        "no production env diff in the same step unless separately named",
        "read-only post-deploy verification only"
    )
    requiredPostDeployReadOnlyVerification = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1",
        ".\scripts\watch_grid_open_readiness_ssh.ps1 -MaxAttempts 1 -SleepSeconds 0",
        ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1"
    )
    forbiddenActions = @(
        "change production env",
        "call createGrid",
        "enable grid/scheduler/recovery",
        "enable live trading/TinyLive",
        "place orders",
        "modify OCO",
        "close positions",
        "send Telegram",
        "mutate DB/grid/fund/Earn/exchange/external backfill state"
    )
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    createGridAllowed = $false
    gridOpenAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    telegramSendAllowed = $false
    notAuthorization = "read-only grid split-acceptance deploy handoff packet only; does not push, deploy, restart, reload nginx, change production env, call createGrid, enable grid/scheduler/recovery/live/TinyLive, place orders, modify OCO, close positions, send Telegram, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[grid-split-acceptance-deploy-handoff] read-only packet"
Write-Host "scope=READ_ONLY; consumes grid readiness watch and origin-delta metadata only; no push, deploy, restart, nginx reload, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("grid_split_acceptance_deploy_handoff_packet=" + ($packet | ConvertTo-Json -Compress -Depth 10))
Write-Host "grid_split_acceptance_deploy_handoff_status=$status"
Write-Host "grid_split_acceptance_deploy_handoff_decision=$decision"
Write-Host "grid_split_acceptance_deploy_handoff_next_action=$nextAction"
Write-Host "local_head_commit=$headCommit"
Write-Host "origin_main_commit=$originCommit"
Write-Host "local_ahead_count=$aheadCount"
Write-Host "local_behind_count=$behindCount"
Write-Host "local_worktree_clean=$($worktreeClean.ToString().ToLowerInvariant())"
Write-Host "grid_open_readiness_watch_status=$watchStatus"
Write-Host "grid_open_readiness_watch_openable=false"
Write-Host "grid_open_readiness_watch_score_pct=$watchScore"
Write-Host "grid_open_readiness_watch_passed_gates=$watchPassedGates"
Write-Host "grid_open_readiness_watch_top_blocker=$watchTopBlocker"
Write-Host "origin_delta_status=$originDeltaStatus"
Write-Host "deployment_metadata_status=$deploymentMetadataStatus"
Write-Host "origin_runtime_delta_files=$originRuntimeDeltaFiles"
Write-Host "server_worktree_commit=$serverWorktreeCommit"
Write-Host "origin_main_commit_from_metadata=$originMainCommitFromMetadata"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_open_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host ("grid_split_acceptance_deploy_handoff_missing_requirements=" + (@($missingRequirements) -join "; "))
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_SEPARATE_GRID_SPLIT_ACCEPTANCE_DEPLOY_AUTHORIZATION_NOT_MUTATION") {
    throw "Grid split-acceptance deploy handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
