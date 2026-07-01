param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [string]$IntervalCode = "1h",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$ChildTimeoutSeconds = 1800,
    [string]$SplitAcceptanceLogPath = "",
    [string]$BackgroundAutomationLogPath = "",
    [string]$RuntimeEvidenceRcaLogPath = "",
    [string]$LiveReadinessBundleLogPath = "",
    [string]$ProfitSourceRefreshLogPath = "",
    [string]$AggressivePacketLogPath = "",
    [string]$HandoffLogPath = "",
    [switch]$AllowDirtyLocalWorktreeForReplay,
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
        throw "$Name contains unsupported characters for read-only smoke arguments."
    }
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Read-LogFile {
    param([string]$PathValue, [string]$Name)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    if ([string]::IsNullOrWhiteSpace($resolved) -or -not (Test-Path -LiteralPath $resolved)) {
        throw "$Name log path not found: $resolved"
    }
    return [pscustomobject]@{
        Name = $Name
        Source = $resolved
        ExitCode = 0
        Text = Get-Content -Raw -LiteralPath $resolved
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Get-PropertyValue {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return "" }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) { return "" }
    if ($property.Value -is [bool]) { return $property.Value.ToString().ToLowerInvariant() }
    return [string]$property.Value
}

function Get-PropertyBool {
    param([object]$Object, [string]$Name)
    $value = Get-PropertyValue -Object $Object -Name $Name
    return $value.Trim().ToLowerInvariant() -eq "true"
}

function Invoke-ReadOnlyScript {
    param([string]$Name, [string]$ScriptPath, [string[]]$Arguments)

    if (-not (Test-Path -LiteralPath $ScriptPath)) {
        throw "Missing post-env read-only dependency script: $ScriptPath"
    }

    $scriptName = Split-Path -Leaf $ScriptPath
    Write-Host ("[profit-evidence-only-accelerator-post-env-bundle] child_start name={0} script={1} timeoutSeconds={2}" -f $Name, $scriptName, $ChildTimeoutSeconds)
    $startedAt = Get-Date
    $timedOut = $false
    $text = ""
    $exitCode = 1
    $job = $null
    try {
        $job = Start-Job -ScriptBlock {
            param(
                [string]$PowerShellSource,
                [string]$ChildScriptPath,
                [string]$WorkingDirectory,
                [object[]]$ChildArguments
            )
            $ErrorActionPreference = "Continue"
            Set-Location -LiteralPath $WorkingDirectory
            $childOutput = & $PowerShellSource -NoProfile -ExecutionPolicy Bypass -File $ChildScriptPath @ChildArguments 2>&1
            $childSuccess = $?
            $code = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($childSuccess) { 0 } else { 1 }
            [pscustomobject]@{
                Text = ($childOutput | Out-String -Width 8192)
                ExitCode = $code
            }
        } -ArgumentList @($script:PowerShell.Source, $ScriptPath, $repoRoot, (, @($Arguments)))

        $lastHeartbeatSeconds = 0
        while ($job.State -eq "Running") {
            $elapsedSeconds = [int]((Get-Date) - $startedAt).TotalSeconds
            if ($elapsedSeconds -ge $ChildTimeoutSeconds) {
                $timedOut = $true
                Stop-Job -Job $job -ErrorAction SilentlyContinue
                break
            }
            if ($elapsedSeconds -ge ($lastHeartbeatSeconds + 30)) {
                $lastHeartbeatSeconds = $elapsedSeconds
                Write-Host ("[profit-evidence-only-accelerator-post-env-bundle] child_heartbeat name={0} elapsedSeconds={1}" -f $Name, $elapsedSeconds)
            }
            Start-Sleep -Seconds 2
        }

        if ($timedOut) {
            $text = "timed out after $ChildTimeoutSeconds second(s)"
            $exitCode = 124
        } else {
            $result = Receive-Job -Job $job -ErrorAction SilentlyContinue
            if ($null -ne $result) {
                $text = [string]$result.Text
                $exitCode = [int]$result.ExitCode
            }
        }
    } finally {
        if ($null -ne $job) {
            Remove-Job -Job $job -Force -ErrorAction SilentlyContinue
        }
    }

    $elapsedTotal = [int]((Get-Date) - $startedAt).TotalSeconds
    Write-Host ("[profit-evidence-only-accelerator-post-env-bundle] child_complete name={0} exitCode={1} timedOut={2} elapsedSeconds={3}" -f $Name, $exitCode, $timedOut.ToString().ToLowerInvariant(), $elapsedTotal)

    return [pscustomobject]@{
        Name = $Name
        Source = $scriptName
        ExitCode = $exitCode
        Text = $text
    }
}

function Get-ResultFromLogOrScript {
    param(
        [string]$Name,
        [string]$LogPath,
        [string]$ScriptName,
        [string[]]$Arguments
    )
    if (-not [string]::IsNullOrWhiteSpace($LogPath)) {
        return Read-LogFile -PathValue $LogPath -Name $Name
    }
    $scriptPath = Join-Path $PSScriptRoot $ScriptName
    return Invoke-ReadOnlyScript -Name $Name -ScriptPath $scriptPath -Arguments $Arguments
}

function Test-SplitAcceptanceOk {
    param([string]$Text)
    return ($Text -match "\[split-acceptance\]\s+OK" -or $Text -match "split_acceptance_status=OK" -or $Text -match "verify_split_acceptance_status=OK")
}

function Test-BackgroundAutomationOk {
    param([string]$Text)
    return ($Text -match "backgroundAutomationClear=true" -and ($Text -match "background_automation_blockers=\[\]" -or $Text -match "verdict=OK_BACKGROUND_AUTOMATION_DISABLED"))
}

function Test-RuntimeEvidenceOk {
    param([string]$Text)
    if ($Text -match "orderSentEvidence=([1-9][0-9]*)") { return $false }
    return (
        $Text -match "diagnosis=CANONICAL_SHADOW_READY" -and
        $Text -match "env\.TRADING_RUNTIME_EVIDENCE_ENABLED=true" -and
        $Text -match "shadowIntentCount=([1-9][0-9]*)" -and
        $Text -match "orderSentEvidence=0" -and
        ($Text -match "missing_runtime_evidence_fields=\[\]" -or $Text -match '"missing_runtime_evidence_fields"\s*:\s*\[\]')
    )
}

function Test-LiveReadinessBundleExecuted {
    param([string]$Text)
    if ($Text -match "orderSentEvidence=([1-9][0-9]*)") { return $false }
    return (
        $Text -match "\[live-readiness-bundle\]" -and
        $Text -match "scope=READ_ONLY" -and
        $Text -match "bundle_verdict=(READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED|NOT_READY)"
    )
}

function Test-ProfitSourceRefreshOk {
    param([string]$Text)
    return (
        $Text -match "profit_live_blocker_source_refresh_status=COMPLETE_REFRESHED_SOURCES_NOT_LIVE_READY" -or
        $Text -match "profit_live_blocker_source_refresh_status=COMPLETE_REFRESHED_SOURCES_WITH_BLOCKED_LANES_NOT_LIVE_READY"
    )
}

function Test-AggressivePacketReady {
    param([string]$Text)
    return (
        $Text -match "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE" -and
        $Text -match "order_allowed=false" -and
        $Text -match "deploy_or_env_change_allowed=false" -and
        $Text -match "live_policy_change_allowed=false"
    )
}

function Test-HandoffReady {
    param([string]$Text)
    return (
        $Text -match "profit_evidence_only_accelerator_env_deploy_handoff_status=READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_ENV_DEPLOY_HANDOFF_NOT_MUTATION" -and
        $Text -match "production_env_change_allowed=false" -and
        $Text -match "deploy_allowed=false" -and
        $Text -match "order_allowed=false"
    )
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$missingRequirements = [System.Collections.Generic.List[string]]::new()

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) { throw "StrategyId must be between 1 and 999999999." }
if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) { throw "RuntimeEvidenceMinutes must be between 60 and 43200." }
if ($ChildTimeoutSeconds -lt 60 -or $ChildTimeoutSeconds -gt 7200) { throw "ChildTimeoutSeconds must be between 60 and 7200." }
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol
Assert-SmokeTokenSafe -Name "Side" -Value $Side
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode

$allLogsSupplied = @(
    $SplitAcceptanceLogPath,
    $BackgroundAutomationLogPath,
    $RuntimeEvidenceRcaLogPath,
    $LiveReadinessBundleLogPath,
    $ProfitSourceRefreshLogPath,
    $AggressivePacketLogPath,
    $HandoffLogPath
) | Where-Object { [string]::IsNullOrWhiteSpace($_) }
$usingReplayOnly = @($allLogsSupplied).Count -eq 0

if (-not $usingReplayOnly) {
    if ([string]::IsNullOrWhiteSpace($SshHost)) { throw "SshHost is required. Pass -SshHost or set AGORA_SSH_HOST." }
    if ([string]::IsNullOrWhiteSpace($SshKey)) { throw "SshKey is required. Pass -SshKey or set AGORA_SSH_KEY." }
    if (-not (Test-Path -LiteralPath $SshKey)) { throw "SSH key not found: $SshKey" }
    Assert-SshHostSafe -Name "SshHost" -Value $SshHost
    Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
    Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
    $script:PowerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $script:PowerShell) { $script:PowerShell = Get-Command powershell -ErrorAction SilentlyContinue }
    if ($null -eq $script:PowerShell) { throw "Unable to find powershell or pwsh for profit evidence-only post-env bundle." }
}

$splitArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-TradingAppDir", $AppDir, "-EnvFile", $EnvFile)
$backgroundArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-RequireClear")
$runtimeArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-Side", $Side, "-Minutes", "$RuntimeEvidenceMinutes", "-RequireReady")
$liveBundleArgs = @("-SshHost", $SshHost, "-SshKey", $SshKey, "-AppDir", $AppDir, "-EnvFile", $EnvFile, "-Symbol", $Symbol, "-StrategyId", "$StrategyId", "-Side", $Side, "-IntervalCode", $IntervalCode, "-RuntimeEvidenceMinutes", "$RuntimeEvidenceMinutes")
$sourceRefreshArgs = @("-ReuseLatestProfitOperatorMatrix")
$aggressiveArgs = @("-RequireReady")
$handoffArgs = @("-RequireReady")
if ($AllowDirtyLocalWorktreeForReplay.IsPresent) {
    $handoffArgs += "-AllowDirtyLocalWorktreeForReplay"
}

Write-Host "[profit-evidence-only-accelerator-post-env-bundle] read-only packet"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local evidence checks or consumes supplied replay logs; no deploy, restart, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host "symbol=$Symbol strategyId=$StrategyId side=$Side interval=$IntervalCode runtimeEvidenceMinutes=$RuntimeEvidenceMinutes"
Write-Host "replay_only=$($usingReplayOnly.ToString().ToLowerInvariant())"

$results = @(
    Get-ResultFromLogOrScript -Name "splitAcceptance" -LogPath $SplitAcceptanceLogPath -ScriptName "verify_split_acceptance_ssh.ps1" -Arguments $splitArgs
    Get-ResultFromLogOrScript -Name "backgroundAutomation" -LogPath $BackgroundAutomationLogPath -ScriptName "smoke_live_background_automation_ssh.ps1" -Arguments $backgroundArgs
    Get-ResultFromLogOrScript -Name "runtimeEvidenceRca" -LogPath $RuntimeEvidenceRcaLogPath -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments $runtimeArgs
    Get-ResultFromLogOrScript -Name "liveReadinessBundle" -LogPath $LiveReadinessBundleLogPath -ScriptName "smoke_live_readiness_bundle_ssh.ps1" -Arguments $liveBundleArgs
    Get-ResultFromLogOrScript -Name "profitSourceRefresh" -LogPath $ProfitSourceRefreshLogPath -ScriptName "prepare_profit_live_blocker_source_refresh.ps1" -Arguments $sourceRefreshArgs
    Get-ResultFromLogOrScript -Name "aggressivePacket" -LogPath $AggressivePacketLogPath -ScriptName "prepare_profit_aggressive_activation_operator_packet.ps1" -Arguments $aggressiveArgs
    Get-ResultFromLogOrScript -Name "handoff" -LogPath $HandoffLogPath -ScriptName "prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1" -Arguments $handoffArgs
)

foreach ($result in $results) {
    if ([int]$result.ExitCode -ne 0) {
        Add-MissingRequirement -List $missingRequirements -Value "$($result.Name) exited 0"
    }
}

$split = @($results | Where-Object { $_.Name -eq "splitAcceptance" } | Select-Object -First 1)[0]
$background = @($results | Where-Object { $_.Name -eq "backgroundAutomation" } | Select-Object -First 1)[0]
$runtime = @($results | Where-Object { $_.Name -eq "runtimeEvidenceRca" } | Select-Object -First 1)[0]
$liveBundle = @($results | Where-Object { $_.Name -eq "liveReadinessBundle" } | Select-Object -First 1)[0]
$sourceRefresh = @($results | Where-Object { $_.Name -eq "profitSourceRefresh" } | Select-Object -First 1)[0]
$aggressive = @($results | Where-Object { $_.Name -eq "aggressivePacket" } | Select-Object -First 1)[0]
$handoff = @($results | Where-Object { $_.Name -eq "handoff" } | Select-Object -First 1)[0]

$splitOk = Test-SplitAcceptanceOk -Text $split.Text
$backgroundOk = Test-BackgroundAutomationOk -Text $background.Text
$runtimeOk = Test-RuntimeEvidenceOk -Text $runtime.Text
$liveBundleExecuted = Test-LiveReadinessBundleExecuted -Text $liveBundle.Text
$sourceRefreshOk = Test-ProfitSourceRefreshOk -Text $sourceRefresh.Text
$aggressiveReady = Test-AggressivePacketReady -Text $aggressive.Text
$handoffReady = Test-HandoffReady -Text $handoff.Text

if (-not $splitOk) { Add-MissingRequirement -List $missingRequirements -Value "split acceptance OK marker" }
if (-not $backgroundOk) { Add-MissingRequirement -List $missingRequirements -Value "background automation clear" }
if (-not $runtimeOk) { Add-MissingRequirement -List $missingRequirements -Value "canonical runtime evidence with shadowIntentCount>0 and orderSentEvidence=0" }
if (-not $liveBundleExecuted) { Add-MissingRequirement -List $missingRequirements -Value "live readiness bundle executed read-only without order-sent evidence" }
if (-not $sourceRefreshOk) { Add-MissingRequirement -List $missingRequirements -Value "profit live blocker source refresh completed" }
if (-not $aggressiveReady) { Add-MissingRequirement -List $missingRequirements -Value "aggressive activation packet still ready and non-mutating" }
if (-not $handoffReady) { Add-MissingRequirement -List $missingRequirements -Value "evidence-only accelerator env/deploy handoff still ready and non-mutating" }

$sourceRefreshStatus = Get-LastPrefixedValue -Text $sourceRefresh.Text -Prefix "profit_live_blocker_source_refresh_status=" -Default "UNKNOWN"
$runtimeDiagnosis = if ($runtime.Text -match "diagnosis=([A-Z0-9_]+)") { $Matches[1] } else { "UNKNOWN" }
$runtimeOrderSentEvidence = if ($runtime.Text -match "orderSentEvidence=([0-9]+)") { $Matches[1] } else { "UNKNOWN" }
$runtimeShadowIntentCount = if ($runtime.Text -match "shadowIntentCount=([0-9]+)") { $Matches[1] } else { "UNKNOWN" }
$liveBundleVerdict = Get-LastPrefixedValue -Text $liveBundle.Text -Prefix "bundle_verdict=" -Default "UNKNOWN"
$aggressiveStatus = Get-LastPrefixedValue -Text $aggressive.Text -Prefix "profit_aggressive_activation_status=" -Default "UNKNOWN"
$handoffStatus = Get-LastPrefixedValue -Text $handoff.Text -Prefix "profit_evidence_only_accelerator_env_deploy_handoff_status=" -Default "UNKNOWN"

$status = if ($missingRequirements.Count -eq 0) {
    "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE"
} else {
    "BLOCKED_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_REQUIREMENTS_MISSING"
}
$decision = if ($status -eq "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE") {
    "CONTINUE_EVIDENCE_COLLECTION_NO_LIVE_RELAXATION"
} else {
    "REFRESH_POST_ENV_EVIDENCE_BEFORE_PROFIT_REVIEW"
}
$nextAction = if ($status -eq "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE") {
    "Keep live execution disabled, continue evidence collection, and use the refreshed packets for a separate live/tiny-live operator review."
} else {
    "Do not relax live policy; resolve the missing post-env evidence requirements and rerun this read-only bundle."
}

$summary = @(
    [pscustomobject]@{ name = "splitAcceptance"; source = $split.Source; exitCode = $split.ExitCode; ok = $splitOk },
    [pscustomobject]@{ name = "backgroundAutomation"; source = $background.Source; exitCode = $background.ExitCode; ok = $backgroundOk },
    [pscustomobject]@{ name = "runtimeEvidenceRca"; source = $runtime.Source; exitCode = $runtime.ExitCode; ok = $runtimeOk },
    [pscustomobject]@{ name = "liveReadinessBundle"; source = $liveBundle.Source; exitCode = $liveBundle.ExitCode; ok = $liveBundleExecuted },
    [pscustomobject]@{ name = "profitSourceRefresh"; source = $sourceRefresh.Source; exitCode = $sourceRefresh.ExitCode; ok = $sourceRefreshOk },
    [pscustomobject]@{ name = "aggressivePacket"; source = $aggressive.Source; exitCode = $aggressive.ExitCode; ok = $aggressiveReady },
    [pscustomobject]@{ name = "handoff"; source = $handoff.Source; exitCode = $handoff.ExitCode; ok = $handoffReady }
)

$packet = [pscustomobject]@{
    packetType = "PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_READ_ONLY_BUNDLE"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    ready = ($missingRequirements.Count -eq 0)
    symbol = $Symbol
    strategyId = $StrategyId
    side = $Side
    intervalCode = $IntervalCode
    runtimeEvidenceMinutes = $RuntimeEvidenceMinutes
    replayOnly = $usingReplayOnly
    postEnvReadOnlyCommands = @(
        ".\scripts\verify_split_acceptance_ssh.ps1",
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady",
        ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
        ".\scripts\prepare_profit_live_blocker_source_refresh.ps1 -ReuseLatestProfitOperatorMatrix",
        ".\scripts\prepare_profit_aggressive_activation_operator_packet.ps1 -RequireReady",
        ".\scripts\prepare_profit_evidence_only_accelerator_env_deploy_handoff.ps1 -RequireReady"
    )
    checks = @($summary)
    splitAcceptanceOk = $splitOk
    backgroundAutomationClear = $backgroundOk
    runtimeEvidenceReady = $runtimeOk
    liveReadinessBundleExecuted = $liveBundleExecuted
    profitSourceRefreshStatus = $sourceRefreshStatus
    runtimeEvidenceDiagnosis = $runtimeDiagnosis
    runtimeShadowIntentCount = $runtimeShadowIntentCount
    runtimeOrderSentEvidence = $runtimeOrderSentEvidence
    liveReadinessBundleVerdict = $liveBundleVerdict
    aggressiveActivationStatus = $aggressiveStatus
    evidenceOnlyHandoffStatus = $handoffStatus
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only profit evidence-only accelerator post-env bundle only; does not push, deploy, restart, reload nginx, change production env, enable TRADING_OKX, enable TinyLive/ScoreBuy execution, enable OCO/grid/fund/Earn actions, enable scheduler, send Telegram, place orders, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host ("profit_evidence_only_accelerator_post_env_bundle_packet=" + (ConvertTo-Json -Compress -Depth 12 $packet))
Write-Host "profit_evidence_only_post_env_bundle_status=$status"
Write-Host "profit_evidence_only_post_env_bundle_decision=$decision"
Write-Host "profit_evidence_only_post_env_bundle_ready=$($packet.ready.ToString().ToLowerInvariant())"
Write-Host ("profit_evidence_only_post_env_bundle_checks=" + (ConvertTo-Json -Compress -Depth 5 @($summary)))
Write-Host ("profit_evidence_only_post_env_bundle_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "runtime_evidence_diagnosis=$runtimeDiagnosis"
Write-Host "runtime_shadow_intent_count=$runtimeShadowIntentCount"
Write-Host "runtime_order_sent_evidence=$runtimeOrderSentEvidence"
Write-Host "live_readiness_bundle_verdict=$liveBundleVerdict"
Write-Host "profit_live_blocker_source_refresh_status=$sourceRefreshStatus"
Write-Host "source_aggressive_activation_status=$aggressiveStatus"
Write-Host "source_evidence_only_handoff_status=$handoffStatus"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "notAuthorization=$($packet.notAuthorization)"
Write-Host "[profit-evidence-only-accelerator-post-env-bundle] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_EVIDENCE_ONLY_ACCELERATOR_POST_ENV_REVIEW_NOT_LIVE") {
    throw "Profit evidence-only accelerator post-env bundle is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
