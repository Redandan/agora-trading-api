param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ExecutionDays = 5,
    [int]$BlockedDays = 7,
    [int]$AccuracyDays = 14,
    [int]$CounterfactualReviewDays = 14,
    [int]$CounterfactualLimit = 200,
    [switch]$RequireReview
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
        throw "$Name contains unsupported characters for DataFreshness shadow candidate packet arguments."
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

function Get-RegexValue {
    param([string]$Text, [string]$Pattern, [string]$Default = "")
    $match = [regex]::Match($Text, $Pattern, [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if (-not $match.Success) {
        return $Default
    }
    return $match.Groups[1].Value.Trim()
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

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
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
        throw "Unable to find powershell or pwsh for DataFreshness shadow candidate packet."
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
if ($ExecutionDays -lt 1 -or $ExecutionDays -gt 90 -or $BlockedDays -lt 1 -or $BlockedDays -gt 90 -or $AccuracyDays -lt 1 -or $AccuracyDays -gt 90) {
    throw "ExecutionDays, BlockedDays, and AccuracyDays must be between 1 and 90."
}
if ($CounterfactualReviewDays -lt 1 -or $CounterfactualReviewDays -gt 90) {
    throw "CounterfactualReviewDays must be between 1 and 90."
}
if ($CounterfactualLimit -lt 1 -or $CounterfactualLimit -gt 1000) {
    throw "CounterfactualLimit must be between 1 and 1000."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$governanceArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ExecutionDays", [string]$ExecutionDays,
    "-BlockedDays", [string]$BlockedDays,
    "-AccuracyDays", [string]$AccuracyDays
)
$counterfactualArgs = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ReviewDays", [string]$CounterfactualReviewDays,
    "-Limit", [string]$CounterfactualLimit
)

$governance = Invoke-ReadOnlyScript -ScriptName "prepare_governance_relaxation_review_packet_ssh.ps1" -Arguments $governanceArgs
$counterfactual = Invoke-ReadOnlyScript -ScriptName "smoke_data_freshness_counterfactual_review_ssh.ps1" -Arguments $counterfactualArgs

$governanceText = $governance.Text
$counterfactualText = $counterfactual.Text
$governancePacketJson = Get-LastPrefixedValue -Text $governanceText -Prefix "governance_relaxation_review_packet="
$governancePacket = Convert-JsonObjectOrNull -Value $governancePacketJson
$governanceStatus = Get-LastPrefixedValue -Text $governanceText -Prefix "governance_relaxation_review_packet_status="
$governanceMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $governanceText -Prefix "governance_relaxation_missing_requirements=")

$counterfactualRows = Get-RegexValue -Text $counterfactualText -Pattern "data_freshness_counterfactual_rows=([0-9]+)" -Default "0"
$runtimeEvidenceRows = Get-RegexValue -Text $counterfactualText -Pattern "runtime_evidence_linked_rows=([0-9]+)" -Default "0"
$liveSignalRows = Get-RegexValue -Text $counterfactualText -Pattern "live_signal_linked_rows=([0-9]+)" -Default "0"
$replayCandidateIdRows = Get-RegexValue -Text $counterfactualText -Pattern "replay_candidate_id_rows=([0-9]+)" -Default "0"
$completeReplayableRows = Get-RegexValue -Text $counterfactualText -Pattern "complete_replayable_candidate_rows=([0-9]+)" -Default "0"
$previewOnlyRows = Get-RegexValue -Text $counterfactualText -Pattern "preview_only_input_rows=([0-9]+)" -Default "0"
$forwardRows = Get-RegexValue -Text $counterfactualText -Pattern "forward_24h_window_rows=([0-9]+)" -Default "0"
$positiveForwardRows = Get-RegexValue -Text $counterfactualText -Pattern "positive_forward_24h_rows=([0-9]+)" -Default "0"
$avgForwardPct = Get-RegexValue -Text $counterfactualText -Pattern "avg_forward_24h_pct=([^\r\n]+)" -Default "N/A"
$avgMfePct = Get-RegexValue -Text $counterfactualText -Pattern "avg_mfe_24h_pct=([^\r\n]+)" -Default "N/A"
$avgMaePct = Get-RegexValue -Text $counterfactualText -Pattern "avg_mae_24h_pct=([^\r\n]+)" -Default "N/A"
$replayInputStage = Get-RegexValue -Text $counterfactualText -Pattern "replay_input_stage=([^\r\n]+)" -Default "N/A"
$collectorStatusCounts = Get-RegexValue -Text $counterfactualText -Pattern "collector_status_counts=([^\r\n]+)" -Default "N/A"
$hardGatePreviewStatusCounts = Get-RegexValue -Text $counterfactualText -Pattern "hard_gate_preview_status_counts=([^\r\n]+)" -Default "N/A"
$replayInputNextAction = Get-RegexValue -Text $counterfactualText -Pattern "replay_input_next_action=([^\r\n]+)" -Default "N/A"
$counterfactualRecommendation = Get-RegexValue -Text $counterfactualText -Pattern "data_freshness_counterfactual_recommendation=([^\r\n]+)" -Default "N/A"
$missingCounterfactualFields = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $counterfactualText -Pattern "missing_counterfactual_fields=(\[[^\r\n]*\])" -Default "[]")
$previewOnlyMissingFields = Convert-JsonArrayOrEmpty -Value (Get-RegexValue -Text $counterfactualText -Pattern "preview_only_missing_counterfactual_fields=(\[[^\r\n]*\])" -Default "[]")

$counterfactualEvidenceClass = "INCOMPLETE_REPLAY_INPUT"
if ([int]$completeReplayableRows -gt 0 -and @($missingCounterfactualFields).Count -eq 0 -and $counterfactualRecommendation -eq "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
    $counterfactualEvidenceClass = "COMPLETE_REPLAYABLE_CANDIDATE_SNAPSHOT"
} elseif ($replayInputStage -eq "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE") {
    $counterfactualEvidenceClass = "PRE_REPLAY_COLLECTOR_HISTORICAL_SAMPLE"
} elseif ($replayInputStage -eq "COLLECTOR_DISABLED_TRACE_ONLY") {
    $counterfactualEvidenceClass = "COLLECTOR_DISABLED_TRACE_ONLY"
} elseif ([int]$previewOnlyRows -gt 0 -or $collectorStatusCounts -match "PREVIEW_ONLY|NOT_REPLAYABLE") {
    $counterfactualEvidenceClass = "PREVIEW_ONLY_NOT_REPLAYABLE"
}

$replayInputEvidenceMarkers = @(
    "counterfactualEvidenceClass=$counterfactualEvidenceClass",
    "replay_input_stage=$replayInputStage",
    "collector_status_counts=$collectorStatusCounts",
    "hard_gate_preview_status_counts=$hardGatePreviewStatusCounts",
    "replay_input_next_action=$replayInputNextAction",
    "complete_replayable_candidate_rows=$completeReplayableRows",
    "preview_only_input_rows=$previewOnlyRows",
    "missing_counterfactual_fields=$(ConvertTo-Json -Compress @($missingCounterfactualFields))",
    "preview_only_missing_counterfactual_fields=$(ConvertTo-Json -Compress @($previewOnlyMissingFields))"
)

$hasDataFreshnessCandidate = $false
$dataFreshnessCandidate = $null
if ($null -ne $governancePacket) {
    foreach ($candidate in @($governancePacket.relaxationCandidates)) {
        if ($null -ne $candidate -and [string]$candidate.blocker -match "DataFreshness") {
            $hasDataFreshnessCandidate = $true
            $dataFreshnessCandidate = $candidate
            break
        }
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($item in @($governanceMissing)) {
    Add-MissingRequirement -List $missingRequirements -Value ([string]$item)
}
if ($governance.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "governance relaxation packet completed"
}
if ($counterfactual.ExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness counterfactual review completed"
}
if ($null -eq $governancePacket) {
    Add-MissingRequirement -List $missingRequirements -Value "governance_relaxation_review_packet valid JSON"
}
if (-not $hasDataFreshnessCandidate) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness relaxation candidate present"
}
if ([int]$counterfactualRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness counterfactual sample rows"
}
if ([int]$completeReplayableRows -le 0) {
    Add-MissingRequirement -List $missingRequirements -Value "complete DataFreshness replayable candidate rows"
}
foreach ($field in @($missingCounterfactualFields)) {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness counterfactual field: $field"
}
if ($counterfactualRecommendation -ne "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
    Add-MissingRequirement -List $missingRequirements -Value "DataFreshness counterfactual replay candidates reviewable"
}

$packetStatus = "NO_EVIDENCE"
$shadowCandidateAllowed = $false
$nextAction = "Fix read-only DataFreshness governance/counterfactual evidence collection before drafting a shadow candidate packet."
if ($governance.ExitCode -eq 0 -and $counterfactual.ExitCode -eq 0 -and $hasDataFreshnessCandidate) {
    if ($missingRequirements.Count -eq 0 -and $governanceStatus -eq "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE") {
        $packetStatus = "READY_FOR_DATAFRESHNESS_SHADOW_CANDIDATE_NOT_LIVE"
        $shadowCandidateAllowed = $true
        $nextAction = "Attach this packet to a separate shadow-only DataFreshness replay review; this is not live policy approval."
    } else {
        $packetStatus = "BLOCKED_COUNTERFACTUAL_REPLAY_INPUT_MISSING"
        $nextAction = "Collect complete replayable candidate rows and clear governance/missed-opportunity blockers before any DataFreshness shadow review."
    }
}

$packet = [pscustomobject]@{
    packetType = "DATA_FRESHNESS_SHADOW_CANDIDATE"
    status = $packetStatus
    symbol = $Symbol
    sourceGovernancePacket = "prepare_governance_relaxation_review_packet_ssh.ps1"
    sourceCounterfactualSmoke = "smoke_data_freshness_counterfactual_review_ssh.ps1"
    governanceStatus = $governanceStatus
    signalPolicyClear = if ($null -ne $governancePacket) { $governancePacket.signalPolicyClear } else { "N/A" }
    governanceMode = if ($null -ne $governancePacket) { $governancePacket.governanceMode } else { "N/A" }
    missedOpportunityStatus = if ($null -ne $governancePacket) { $governancePacket.missedOpportunityStatus } else { "N/A" }
    dataFreshnessCandidate = $dataFreshnessCandidate
    counterfactualRows = $counterfactualRows
    runtimeEvidenceLinkedRows = $runtimeEvidenceRows
    liveSignalLinkedRows = $liveSignalRows
    replayCandidateIdRows = $replayCandidateIdRows
    completeReplayableCandidateRows = $completeReplayableRows
    previewOnlyInputRows = $previewOnlyRows
    forward24hWindowRows = $forwardRows
    positiveForward24hRows = $positiveForwardRows
    avgForward24hPct = $avgForwardPct
    avgMfe24hPct = $avgMfePct
    avgMae24hPct = $avgMaePct
    replayInputStage = $replayInputStage
    collectorStatusCounts = $collectorStatusCounts
    hardGatePreviewStatusCounts = $hardGatePreviewStatusCounts
    replayInputNextAction = $replayInputNextAction
    counterfactualEvidenceClass = $counterfactualEvidenceClass
    replayInputEvidenceMarkers = @($replayInputEvidenceMarkers)
    counterfactualRecommendation = $counterfactualRecommendation
    missingCounterfactualFields = @($missingCounterfactualFields)
    previewOnlyMissingCounterfactualFields = @($previewOnlyMissingFields)
    shadowCandidateReviewAllowed = $shadowCandidateAllowed
    dataFreshnessPolicyRelaxationAllowed = $false
    tinyLiveOrderAllowed = $false
    livePolicyChangeAllowed = $false
    missingRequirements = @($missingRequirements)
    requiredOperatorChecks = @(
        "confirm DataFreshness candidate remains present in fresh governance relaxation scan",
        "confirm complete replayable candidate rows exist",
        "confirm EV/OCO/hard-gate snapshots are present and not preview-only placeholders",
        "confirm replay removes only DataFreshnessGuard and keeps all other hard gates intact"
    )
    nextAction = $nextAction
    notAuthorization = "read-only DataFreshness shadow candidate packet only; does not authorize DataFreshnessGuard relaxation, live trading, tiny-live order execution, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutation, DB changes, deploy, restart, production env mutation, external backfill/import, or strategy/filter changes"
}

Write-Host "[data-freshness-shadow-candidate-packet] read-only packet"
Write-Host "scope=READ_ONLY; runs prepare_governance_relaxation_review_packet_ssh.ps1 and smoke_data_freshness_counterfactual_review_ssh.ps1 only; no production env, DB writes, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_governance_packet=prepare_governance_relaxation_review_packet_ssh.ps1"
Write-Host "source_counterfactual_smoke=smoke_data_freshness_counterfactual_review_ssh.ps1"
Write-Host "source_governance_exit_code=$($governance.ExitCode)"
Write-Host "source_counterfactual_exit_code=$($counterfactual.ExitCode)"
Write-Host "governance_relaxation_review_packet_status=$governanceStatus"
Write-Host "symbol=$Symbol"
Write-Host "dataFreshnessCandidatePresent=$($hasDataFreshnessCandidate.ToString().ToLowerInvariant())"
Write-Host "signalPolicyClear=$($packet.signalPolicyClear)"
Write-Host "governanceMode=$($packet.governanceMode)"
Write-Host "missedOpportunityStatus=$($packet.missedOpportunityStatus)"
Write-Host "data_freshness_counterfactual_recommendation=$counterfactualRecommendation"
Write-Host "data_freshness_counterfactual_rows=$counterfactualRows"
Write-Host "runtime_evidence_linked_rows=$runtimeEvidenceRows"
Write-Host "live_signal_linked_rows=$liveSignalRows"
Write-Host "replay_candidate_id_rows=$replayCandidateIdRows"
Write-Host "complete_replayable_candidate_rows=$completeReplayableRows"
Write-Host "preview_only_input_rows=$previewOnlyRows"
Write-Host "forward_24h_window_rows=$forwardRows"
Write-Host "positive_forward_24h_rows=$positiveForwardRows"
Write-Host "avg_forward_24h_pct=$avgForwardPct"
Write-Host "replay_input_stage=$replayInputStage"
Write-Host "collector_status_counts=$collectorStatusCounts"
Write-Host "hard_gate_preview_status_counts=$hardGatePreviewStatusCounts"
Write-Host "replay_input_next_action=$replayInputNextAction"
Write-Host "counterfactual_evidence_class=$counterfactualEvidenceClass"
Write-Host ("replay_input_evidence_markers=" + (ConvertTo-Json -Compress @($replayInputEvidenceMarkers)))
Write-Host ("missing_counterfactual_fields=" + (ConvertTo-Json -Compress @($missingCounterfactualFields)))
Write-Host ("preview_only_missing_counterfactual_fields=" + (ConvertTo-Json -Compress @($previewOnlyMissingFields)))
Write-Host "shadow_candidate_review_allowed=$($shadowCandidateAllowed.ToString().ToLowerInvariant())"
Write-Host "data_freshness_policy_relaxation_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host ("data_freshness_shadow_candidate_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("data_freshness_shadow_candidate_packet=" + (ConvertTo-Json -Compress -Depth 10 $packet))
Write-Host "data_freshness_shadow_candidate_packet_status=$packetStatus"
Write-Host "data_freshness_shadow_candidate_next_action=$nextAction"
Write-Host "notAuthorization=read-only DataFreshness shadow candidate packet only; does not deploy, restart, reload nginx, change production env, enable live trading, execute tiny-live orders, relax DataFreshnessGuard, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy/filter changes"
Write-Host "[data-freshness-shadow-candidate-packet] read-only check complete"

if ($RequireReview -and $packetStatus -eq "NO_EVIDENCE") {
    throw "DataFreshness shadow candidate packet has no evidence: missing=$(@($missingRequirements) -join '; ')"
}
