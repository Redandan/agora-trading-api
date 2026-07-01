param(
    [string]$AggressivePacketLogPath = "target/profit-review/profit-aggressive-activation-operator-packet-latest.log",
    [string]$GridAuthorizationBundleLogPath = "target/profit-review/grid-open-authorization-bundle-microgrid-current.log",
    [int]$MaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$GridCount = 2,
    [decimal]$PerLevelUsdt = 5,
    [decimal]$StopOutPct = 5.0,
    [decimal]$CandidateHalfWidthPct = 10.0,
    [decimal]$MaxCapitalUsdt = 10,
    [switch]$AllowDirtyLocalWorktreeForReplay,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([string]::IsNullOrWhiteSpace($PathValue)) { return "" }
    if ([System.IO.Path]::IsPathRooted($PathValue)) { return $PathValue }
    return Join-Path $repoRoot $PathValue
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix, [string]$Default = "")
    if ([string]::IsNullOrWhiteSpace($Text)) { return $Default }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return $Default }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") { return $null }
    try { return ($Value | ConvertFrom-Json -ErrorAction Stop) } catch { return $null }
}

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) { return }
    if ($List -notcontains $Value) { $List.Add($Value) }
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

function Get-PropertyOrNull {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Get-DecimalOrNull {
    param($Value)
    if ($null -eq $Value) { return $null }
    try { return [decimal]$Value } catch { return $null }
}

function Get-StringArray {
    param($Values)
    $rows = [System.Collections.Generic.List[string]]::new()
    foreach ($value in @($Values)) {
        if ($null -eq $value) { continue }
        $text = [string]$value
        if (-not [string]::IsNullOrWhiteSpace($text)) { $rows.Add($text) }
    }
    return @($rows)
}

function Get-OptionById {
    param($Options, [string]$OptionId)
    foreach ($option in @($Options)) {
        if ((Get-PropertyValue -Object $option -Name "optionId") -eq $OptionId) {
            return $option
        }
    }
    return $null
}

function Read-PacketLog {
    param([string]$PathValue, [string]$Prefix)
    $resolved = Resolve-RepoPath -PathValue $PathValue
    $freshness = "MISSING"
    $ageMinutes = $null
    $text = ""
    $packet = $null
    if (-not [string]::IsNullOrWhiteSpace($resolved) -and (Test-Path -LiteralPath $resolved)) {
        $item = Get-Item -LiteralPath $resolved
        $ageMinutes = [math]::Round(((Get-Date) - $item.LastWriteTime).TotalMinutes, 2)
        $freshness = if ($ageMinutes -le $MaxAgeMinutes) { "FRESH" } else { "STALE" }
        $text = Get-Content -Raw -LiteralPath $resolved
        $packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix $Prefix)
    }
    return [pscustomobject]@{
        Path = $resolved
        Freshness = $freshness
        AgeMinutes = $ageMinutes
        Text = $text
        Packet = $packet
        ExitCode = 0
    }
}

function Invoke-AggressivePacket {
    $scriptPath = Join-Path $PSScriptRoot "prepare_profit_aggressive_activation_operator_packet.ps1"
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing aggressive activation packet script: $scriptPath"
    }

    $output = @()
    $exitCode = 0
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $scriptPath -Symbol $Symbol -MaxProbeNotionalUsdt $MaxCapitalUsdt -RequireReady *>&1
        $exitCode = if ($?) { 0 } else { 1 }
    } catch {
        $output += $_
        $exitCode = 1
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String -Width 8192)
    return [pscustomobject]@{
        Text = $text
        ExitCode = $exitCode
        Source = "prepare_profit_aggressive_activation_operator_packet.ps1"
        Freshness = "GENERATED"
        Packet = Convert-JsonObjectOrNull -Value (Get-LastPrefixedValue -Text $text -Prefix "profit_aggressive_activation_packet=")
    }
}

if ($MaxAgeMinutes -lt 1 -or $MaxAgeMinutes -gt 1440) { throw "MaxAgeMinutes must be between 1 and 1440." }
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for grid10 order path handoff arguments."
}
if ($GridCount -lt 2 -or $GridCount -gt 24) { throw "GridCount must be between 2 and 24." }
if ($PerLevelUsdt -lt 5 -or $PerLevelUsdt -gt 1000) { throw "PerLevelUsdt must be between 5 and 1000." }
if ($StopOutPct -lt 1 -or $StopOutPct -gt 20) { throw "StopOutPct must be between 1 and 20." }
if ($CandidateHalfWidthPct -lt 2.5 -or $CandidateHalfWidthPct -gt 30) { throw "CandidateHalfWidthPct must be between 2.5 and 30." }
if ($MaxCapitalUsdt -lt 5 -or $MaxCapitalUsdt -gt 1000) { throw "MaxCapitalUsdt must be between 5 and 1000." }

$repoRoot = Split-Path -Parent $PSScriptRoot
$selectedOptionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
$missingRequirements = [System.Collections.Generic.List[string]]::new()

$headCommit = (& git -C $repoRoot rev-parse HEAD).Trim()
$originCommit = (& git -C $repoRoot rev-parse origin/main).Trim()
$aheadCount = [int]((& git -C $repoRoot rev-list --count "origin/main..HEAD").Trim())
$behindCount = [int]((& git -C $repoRoot rev-list --count "HEAD..origin/main").Trim())
$worktreeStatus = ((& git -C $repoRoot status --short) -join "`n").Trim()
$worktreeClean = [string]::IsNullOrWhiteSpace($worktreeStatus)

$aggressiveLogSource = Read-PacketLog -PathValue $AggressivePacketLogPath -Prefix "profit_aggressive_activation_packet="
$usingAggressiveLog = $aggressiveLogSource.Freshness -ne "MISSING"
$aggressiveSource = if ($usingAggressiveLog) { $aggressiveLogSource } else { Invoke-AggressivePacket }
$gridBundleSource = Read-PacketLog -PathValue $GridAuthorizationBundleLogPath -Prefix "grid_open_authorization_bundle_packet="

if (-not $worktreeClean -and -not (($usingAggressiveLog -or $gridBundleSource.Freshness -ne "MISSING") -and $AllowDirtyLocalWorktreeForReplay.IsPresent)) {
    Add-MissingRequirement -List $missingRequirements -Value "local worktree clean before grid10 order path handoff"
}
if ($behindCount -gt 0) {
    Add-MissingRequirement -List $missingRequirements -Value "local branch not behind origin/main"
}
if ($aggressiveSource.Freshness -eq "STALE") { Add-MissingRequirement -List $missingRequirements -Value "profit aggressive activation packet log fresh" }
if ($aggressiveSource.ExitCode -ne 0) { Add-MissingRequirement -List $missingRequirements -Value "profit aggressive activation packet completed" }
if ($null -eq $aggressiveSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "profit_aggressive_activation_packet valid JSON" }
if ($gridBundleSource.Freshness -eq "MISSING") { Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundle log present" }
if ($gridBundleSource.Freshness -eq "STALE") { Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundle log fresh" }
if ($gridBundleSource.Freshness -ne "MISSING" -and $null -eq $gridBundleSource.Packet) { Add-MissingRequirement -List $missingRequirements -Value "grid_open_authorization_bundle_packet valid JSON" }

$aggressivePacket = $aggressiveSource.Packet
$bundlePacket = $gridBundleSource.Packet

$sourceAggressiveStatus = Get-PropertyValue -Object $aggressivePacket -Name "status"
$sourceAggressiveDecision = Get-PropertyValue -Object $aggressivePacket -Name "decision"
if ([string]::IsNullOrWhiteSpace($sourceAggressiveStatus)) {
    $sourceAggressiveStatus = Get-LastPrefixedValue -Text $aggressiveSource.Text -Prefix "profit_aggressive_activation_status=" -Default "UNKNOWN"
}
if ($sourceAggressiveStatus -ne "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive activation packet ready"
}
foreach ($flagName in @("orderAllowed", "deployOrEnvChangeAllowed", "livePolicyChangeAllowed")) {
    if (Get-PropertyBool -Object $aggressivePacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "aggressive packet keeps $flagName=false"
    }
}
if (Get-PropertyBool -Object $aggressivePacket -Name "orderCapableExecutionNowAllowed") {
    Add-MissingRequirement -List $missingRequirements -Value "aggressive packet keeps orderCapableExecutionNowAllowed=false"
}

$aggressiveOptions = if ($null -ne $aggressivePacket -and $null -ne $aggressivePacket.PSObject.Properties["aggressiveOptions"]) {
    $aggressivePacket.aggressiveOptions
} else {
    @()
}
$gridOption = Get-OptionById -Options $aggressiveOptions -OptionId $selectedOptionId
if ($null -eq $gridOption) {
    Add-MissingRequirement -List $missingRequirements -Value "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH option present"
}
$gridOptionStatus = Get-PropertyValue -Object $gridOption -Name "status"
$gridOptionRecommended = Get-PropertyBool -Object $gridOption -Name "recommendedNow"
$gridOptionMaxCapital = Get-DecimalOrNull (Get-PropertyValue -Object $gridOption -Name "maxCapitalUsdt")
$gridOptionConfirmationText = Get-PropertyValue -Object $gridOption -Name "confirmationText"
$gridOptionRequiredBeforeExecution = if ($null -ne $gridOption -and $null -ne $gridOption.PSObject.Properties["requiredBeforeExecution"]) { Get-StringArray $gridOption.requiredBeforeExecution } else { @() }
$gridOptionProposedEnvDiff = if ($null -ne $gridOption -and $null -ne $gridOption.PSObject.Properties["proposedEnvDiff"]) { Get-StringArray $gridOption.proposedEnvDiff } else { @() }
$gridOptionPostEnvReadOnlyVerification = if ($null -ne $gridOption -and $null -ne $gridOption.PSObject.Properties["postEnvReadOnlyVerificationCommands"]) { Get-StringArray $gridOption.postEnvReadOnlyVerificationCommands } else { @() }
$gridOptionKillSwitchEnvDiff = if ($null -ne $gridOption -and $null -ne $gridOption.PSObject.Properties["killSwitchEnvDiff"]) { Get-StringArray $gridOption.killSwitchEnvDiff } else { @() }
$gridOptionRollbackCommands = if ($null -ne $gridOption -and $null -ne $gridOption.PSObject.Properties["rollbackCommands"]) { Get-StringArray $gridOption.rollbackCommands } else { @() }

if ($gridOptionStatus -ne "SEPARATE_GRID_AUTHORIZATION_AND_POST_ENV_VERIFICATION_REQUIRED") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 option remains separate authorization and post-env verification required"
}
if ($gridOptionRecommended) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 option recommendedNow remains false until separate authorization"
}
if ($null -eq $gridOptionMaxCapital -or $gridOptionMaxCapital -gt $MaxCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 option maxCapitalUsdt <= MaxCapitalUsdt"
}
foreach ($line in @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true", "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false", "GRID_RECOVERY_ENABLED=false", "OKX_EARN_TOPUP_ENABLED=false", "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false", "EVENT_SCAN_NOTIFICATION_ENABLED=false", "EXECUTION_EVENT_ENABLED=false")) {
    if ($gridOptionProposedEnvDiff -notcontains $line) {
        Add-MissingRequirement -List $missingRequirements -Value "grid10 option proposed env diff contains $line"
    }
}
if (@($gridOptionRequiredBeforeExecution).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 requiredBeforeExecution present" }
if (@($gridOptionPostEnvReadOnlyVerification).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 post-env read-only verification commands present" }
if (@($gridOptionKillSwitchEnvDiff).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 kill-switch env diff present" }
if (@($gridOptionRollbackCommands).Count -eq 0) { Add-MissingRequirement -List $missingRequirements -Value "grid10 rollback commands present" }
if ($gridOptionConfirmationText -notmatch "SEPARATE_GRID10_ORDER_PATH_REVIEW") {
    Add-MissingRequirement -List $missingRequirements -Value "grid10 exact risk acceptance text present"
}

$bundleStatus = Get-PropertyValue -Object $bundlePacket -Name "status"
$bundleDecision = Get-PropertyValue -Object $bundlePacket -Name "decision"
$readinessSummary = Get-PropertyOrNull -Object $bundlePacket -Name "readinessSummary"
$reviewedCreateGridInputs = Get-PropertyOrNull -Object $bundlePacket -Name "reviewedCreateGridInputs"
$capitalOverrideRequest = Get-PropertyOrNull -Object $bundlePacket -Name "capitalOverrideRequest"
$remainingExecutionBlockers = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["remainingExecutionBlockers"]) { Get-StringArray $bundlePacket.remainingExecutionBlockers } else { @() }
$requiredOperatorAuthorizationSequence = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["requiredOperatorAuthorizationSequence"]) { Get-StringArray $bundlePacket.requiredOperatorAuthorizationSequence } else { @() }
$bundlePostEnvVerification = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["postEnvReadOnlyVerification"]) { Get-StringArray $bundlePacket.postEnvReadOnlyVerification } else { @() }
$authorizationLanes = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["authorizationLanes"]) { @($bundlePacket.authorizationLanes) } else { @() }
$bundleReady = Get-PropertyBool -Object $bundlePacket -Name "gridOpenAuthorizationBundleReady"
if (-not $bundleReady -and $null -ne $readinessSummary) {
    $bundleReady = Get-PropertyBool -Object $readinessSummary -Name "bundleReadyForOperatorReview"
}
$bundleBlockers = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["bundleBlockers"]) { Get-StringArray $bundlePacket.bundleBlockers } else { @() }
$bundleMissingEvidence = if ($null -ne $bundlePacket -and $null -ne $bundlePacket.PSObject.Properties["missingEvidence"]) { Get-StringArray $bundlePacket.missingEvidence } else { @() }
$trendGate = Get-PropertyValue -Object $readinessSummary -Name "trendGate"
$trendLaneReady = Get-PropertyBool -Object $readinessSummary -Name "trendLaneReady"
$capitalOverrideReviewReady = Get-PropertyBool -Object $readinessSummary -Name "capitalOverrideReviewReady"
$envDiffReviewReady = Get-PropertyBool -Object $readinessSummary -Name "envDiffReviewReady"
$createPreflightComplete = Get-PropertyBool -Object $readinessSummary -Name "createGridPreflightEvidenceComplete"
$candidatePlanComplete = Get-PropertyBool -Object $readinessSummary -Name "candidatePlanComplete"
$existingActiveGridOrderPathActivationRisk = Get-PropertyBool -Object $readinessSummary -Name "existingActiveGridOrderPathActivationRisk"

if ($bundleStatus -ne "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION") {
    Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundle ready status"
}
if (-not $bundleReady) { Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundleReadyForOperatorReview=true" }
if (@($bundleBlockers).Count -gt 0) { Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundle blockers empty" }
if (@($bundleMissingEvidence).Count -gt 0) { Add-MissingRequirement -List $missingRequirements -Value "grid open authorization bundle missingEvidence empty" }
if (-not $trendLaneReady) { Add-MissingRequirement -List $missingRequirements -Value "grid trend lane ready or fresh trend clearance accepted" }
if (-not $capitalOverrideReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "grid capital override review ready" }
if (-not $envDiffReviewReady) { Add-MissingRequirement -List $missingRequirements -Value "grid env diff review ready" }
if (-not $createPreflightComplete) { Add-MissingRequirement -List $missingRequirements -Value "grid createGrid preflight evidence complete" }
if (-not $candidatePlanComplete) { Add-MissingRequirement -List $missingRequirements -Value "grid candidate plan complete" }
foreach ($flagName in @("productionEnvChangeAllowed", "deployAllowed", "createGridAllowed", "gridOpenAllowed", "gridMutationAllowed", "schedulerEnablementAllowed", "orderAllowed", "ocoMutationAllowed", "telegramSendAllowed")) {
    if (Get-PropertyBool -Object $bundlePacket -Name $flagName) {
        Add-MissingRequirement -List $missingRequirements -Value "grid authorization bundle keeps $flagName=false"
    }
}

$candidateGridCount = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "gridCount")
$candidatePerLevelUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "perLevelUsdt")
$candidateCapitalUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "candidateCapitalUsdt")
$candidateStopOutPct = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "stopOutPct")
$candidateHalfWidthPct = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "candidateHalfWidthPct")
$replayScore = Get-DecimalOrNull (Get-PropertyValue -Object $reviewedCreateGridInputs -Name "replayScore")
$effectiveReviewCapitalCapUsdt = Get-DecimalOrNull (Get-PropertyValue -Object $capitalOverrideRequest -Name "effectiveReviewCapitalCapUsdt")
if ($null -eq $reviewedCreateGridInputs) { Add-MissingRequirement -List $missingRequirements -Value "reviewedCreateGridInputs present in grid bundle" }
if ($candidateGridCount -ne $GridCount) { Add-MissingRequirement -List $missingRequirements -Value "reviewed gridCount matches requested GridCount" }
if ($candidatePerLevelUsdt -ne $PerLevelUsdt) { Add-MissingRequirement -List $missingRequirements -Value "reviewed perLevelUsdt matches requested PerLevelUsdt" }
if ($candidateStopOutPct -ne $StopOutPct) { Add-MissingRequirement -List $missingRequirements -Value "reviewed stopOutPct matches requested StopOutPct" }
if ($candidateHalfWidthPct -ne $CandidateHalfWidthPct) { Add-MissingRequirement -List $missingRequirements -Value "reviewed candidateHalfWidthPct matches requested CandidateHalfWidthPct" }
if ($null -eq $candidateCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "reviewed candidateCapitalUsdt present"
} elseif ($candidateCapitalUsdt -gt $MaxCapitalUsdt) {
    Add-MissingRequirement -List $missingRequirements -Value "reviewed candidateCapitalUsdt <= MaxCapitalUsdt"
}
if ($null -eq $effectiveReviewCapitalCapUsdt) { Add-MissingRequirement -List $missingRequirements -Value "effectiveReviewCapitalCapUsdt present" }
if ($null -eq $replayScore -or $replayScore -lt 70) { Add-MissingRequirement -List $missingRequirements -Value "grid replayScore >= 70" }
foreach ($blocker in @("OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED", "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED", "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED", "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED")) {
    if ($remainingExecutionBlockers -notcontains $blocker) {
        Add-MissingRequirement -List $missingRequirements -Value "grid bundle remainingExecutionBlockers contains $blocker"
    }
}

$trendAuthorizationText = if ($trendGate -like "BLOCKED_*") {
    "I explicitly authorize GRID10_TREND_REGIME_OVERRIDE_REVIEW for $Symbol with trendGate=$trendGate for the attached $GridCount x $PerLevelUsdt USDT grid candidate; this is not createGrid authorization."
} else {
    "I accept fresh GRID10_TREND_GATE_CLEARANCE for $Symbol; separate trend-regime override is not required unless the gate becomes blocked again."
}
$capitalAuthorizationText = "I explicitly authorize GRID10_CAPITAL_CAP_OVERRIDE_REVIEW for $Symbol from effectiveReviewCapitalCapUsdt=$effectiveReviewCapitalCapUsdt to candidateCapitalUsdt=$candidateCapitalUsdt; this is not createGrid authorization."
$envAuthorizationText = "I explicitly authorize GRID10_ENV_DIFF_REVIEW for $Symbol with TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true while TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false, GRID_RECOVERY_ENABLED=false, OKX_EARN_TOPUP_ENABLED=false, EVENT_SCAN_NOTIFICATION_ENABLED=false, and EXECUTION_EVENT_ENABLED=false."
$postEnvAuthorizationText = "I explicitly authorize GRID10_DEPLOY_RESTART_AND_POST_ENV_READ_ONLY_VERIFICATION for $Symbol; post-env verification must pass before createGrid is reviewed."
$createGridAuthorizationText = "After post-env read-only verification passes, I explicitly authorize GRID10_CREATEGRID_REVIEW for $Symbol with gridCount=$GridCount, perLevelUsdt=$PerLevelUsdt, candidateCapitalUsdt=$candidateCapitalUsdt, stopOutPct=$StopOutPct, candidateHalfWidthPct=$CandidateHalfWidthPct, replayScore=$replayScore, and I accept this can lose money."

$exactAuthorizationTexts = @(
    $trendAuthorizationText,
    $capitalAuthorizationText,
    $envAuthorizationText,
    $postEnvAuthorizationText,
    $createGridAuthorizationText
)
$postEnvReadOnlyVerificationCommands = @(
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -GridCount $GridCount -PerLevelUsdt $PerLevelUsdt -StopOutPct $StopOutPct -CandidateHalfWidthPct $CandidateHalfWidthPct -RequireVerificationReady",
    ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -GridCount $GridCount -PerLevelUsdt $PerLevelUsdt -StopOutPct $StopOutPct -CandidateHalfWidthPct $CandidateHalfWidthPct -RequireBoardReady",
    ".\scripts\prepare_grid_open_authorization_bundle_ssh.ps1 -GridCount $GridCount -PerLevelUsdt $PerLevelUsdt -StopOutPct $StopOutPct -CandidateHalfWidthPct $CandidateHalfWidthPct -AcceptAlreadyAppliedEnvDiff -RequireBundleReady",
    ".\scripts\prepare_profit_grid10_order_path_handoff.ps1 -RequireReady"
)
$killSwitchEnvDiff = @(
    "TRADING_OKX_ENABLED=false",
    "TRADING_GRID_ENABLED=false",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
    "GRID_RECOVERY_ENABLED=false",
    "OKX_EARN_TOPUP_ENABLED=false",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false"
)
$rollbackCommands = @(
    "apply the grid10 killSwitchEnvDiff through the approved deploy runbook",
    ".\scripts\verify_split_acceptance_ssh.ps1",
    ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -GridCount $GridCount -PerLevelUsdt $PerLevelUsdt -StopOutPct $StopOutPct -CandidateHalfWidthPct $CandidateHalfWidthPct -RequireVerificationReady",
    ".\scripts\prepare_grid_open_blocker_priority_board_ssh.ps1 -GridCount $GridCount -PerLevelUsdt $PerLevelUsdt -StopOutPct $StopOutPct -CandidateHalfWidthPct $CandidateHalfWidthPct -RequireBoardReady"
)

$handoffReady = $missingRequirements.Count -eq 0
$status = if ($handoffReady) {
    "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_PROFIT_GRID10_ORDER_PATH_HANDOFF_REQUIREMENTS_MISSING"
}
$decision = if ($handoffReady) {
    "REVIEW_SEPARATE_GRID10_ORDER_PATH_AUTHORIZATIONS_BUT_DO_NOT_DEPLOY"
} else {
    "REFRESH_GRID10_ORDER_PATH_EVIDENCE_BEFORE_OPERATOR_REVIEW"
}
$nextAction = if ($handoffReady) {
    "Do not deploy from this packet. If the operator chooses grid10 later, collect exact trend/capital/env/deploy/createGrid authorization in order and rerun post-env read-only verification immediately before any createGrid call."
} else {
    "Resolve missing grid10 handoff requirements or refresh the micro-grid authorization bundle before reviewing the order-capable lane."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    selectedOptionId = $selectedOptionId
    sourceAggressivePacketStatus = $sourceAggressiveStatus
    sourceAggressivePacketDecision = $sourceAggressiveDecision
    sourceGridAuthorizationBundleStatus = $bundleStatus
    sourceGridAuthorizationBundleDecision = $bundleDecision
    symbol = $Symbol
    gridCount = $GridCount
    perLevelUsdt = $PerLevelUsdt
    stopOutPct = $StopOutPct
    candidateHalfWidthPct = $CandidateHalfWidthPct
    maxCapitalUsdt = $MaxCapitalUsdt
    candidateCapitalUsdt = $candidateCapitalUsdt
    effectiveReviewCapitalCapUsdt = $effectiveReviewCapitalCapUsdt
    replayScore = $replayScore
    trendGate = $trendGate
    trendLaneReady = $trendLaneReady
    capitalOverrideReviewReady = $capitalOverrideReviewReady
    envDiffReviewReady = $envDiffReviewReady
    createGridPreflightEvidenceComplete = $createPreflightComplete
    candidatePlanComplete = $candidatePlanComplete
    existingActiveGridOrderPathActivationRisk = $existingActiveGridOrderPathActivationRisk
    authorizationLanes = @($authorizationLanes)
    requiredOperatorAuthorizationSequence = @($requiredOperatorAuthorizationSequence)
    remainingExecutionBlockers = @($remainingExecutionBlockers)
    exactOperatorAuthorizationTexts = @($exactAuthorizationTexts)
    proposedEnvDiff = @($gridOptionProposedEnvDiff)
    postEnvReadOnlyVerificationCommands = @($postEnvReadOnlyVerificationCommands)
    sourceBundlePostEnvReadOnlyVerification = @($bundlePostEnvVerification)
    killSwitchEnvDiff = @($killSwitchEnvDiff)
    rollbackCommands = @($rollbackCommands)
    headCommit = $headCommit
    originMainCommit = $originCommit
    aheadCount = $aheadCount
    behindCount = $behindCount
    worktreeClean = $worktreeClean
    missingRequirements = @($missingRequirements)
    nextAction = $nextAction
    grid10OrderPathHandoffReady = $handoffReady
    grid10ExecutionNowAllowed = $false
    grid10EnvDeployRequestAllowed = $false
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    schedulerEnablementAllowed = $false
    orderAllowed = $false
    positionOrOcoMutationAllowed = $false
    createGridAllowed = $false
    gridMutationAllowed = $false
    telegramSendAllowed = $false
    dbGridFundEarnExchangeMutationAllowed = $false
    notAuthorization = "read-only profit grid10 order path handoff packet only; does not push, deploy, restart, reload nginx, change production env, approve trend override, approve capital override, enable TRADING_OKX, call createGrid, enable scheduler/recovery/Earn, send Telegram, place orders, modify OCO, relax policy, or mutate DB/grid/fund/Earn/exchange/external backfill state"
}

Write-Host "[profit-grid10-order-path-handoff] read-only packet"
Write-Host "scope=READ_ONLY; reads local aggressive activation and grid authorization bundle logs only; no push, deploy, restart, nginx reload, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host ("profit_grid10_order_path_handoff_packet=" + (ConvertTo-Json -Compress -Depth 18 $packet))
Write-Host "profit_grid10_order_path_handoff_status=$status"
Write-Host "profit_grid10_order_path_handoff_decision=$decision"
Write-Host "source_aggressive_activation_status=$sourceAggressiveStatus"
Write-Host "source_grid_authorization_bundle_status=$bundleStatus"
Write-Host "grid10_order_path_bundle_ready=$($bundleReady.ToString().ToLowerInvariant())"
Write-Host "grid10_candidate_capital_usdt=$candidateCapitalUsdt"
Write-Host "grid10_effective_review_capital_cap_usdt=$effectiveReviewCapitalCapUsdt"
Write-Host "grid10_grid_count=$GridCount"
Write-Host "grid10_per_level_usdt=$PerLevelUsdt"
Write-Host "grid10_stop_out_pct=$StopOutPct"
Write-Host "grid10_candidate_half_width_pct=$CandidateHalfWidthPct"
Write-Host "grid10_replay_score=$replayScore"
Write-Host "grid10_trend_gate=$trendGate"
Write-Host ("grid10_authorization_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($authorizationLanes)))
Write-Host ("grid10_required_authorization_sequence=" + (ConvertTo-Json -Compress @($requiredOperatorAuthorizationSequence)))
Write-Host ("grid10_execution_blockers=" + (ConvertTo-Json -Compress @($remainingExecutionBlockers)))
Write-Host ("grid10_exact_authorization_texts=" + (ConvertTo-Json -Compress @($exactAuthorizationTexts)))
Write-Host ("grid10_post_env_read_only_verification=" + (ConvertTo-Json -Compress @($postEnvReadOnlyVerificationCommands)))
Write-Host ("grid10_kill_switch_env_diff=" + (ConvertTo-Json -Compress @($killSwitchEnvDiff)))
Write-Host ("grid10_rollback_commands=" + (ConvertTo-Json -Compress @($rollbackCommands)))
Write-Host ("grid10_handoff_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "grid10_order_path_handoff_ready=$($handoffReady.ToString().ToLowerInvariant())"
Write-Host "grid10_execution_now_allowed=false"
Write-Host "grid10_env_deploy_request_allowed=false"
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "order_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "create_grid_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_grid_fund_earn_exchange_mutation_allowed=false"
Write-Host "grid10_handoff_next_action=$nextAction"
Write-Host "notAuthorization=$($packet.notAuthorization)"

if ($RequireReady -and $status -ne "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION") {
    throw "Profit grid10 order path handoff is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
