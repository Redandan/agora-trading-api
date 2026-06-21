param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$TinyLiveHours = 720,
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
        throw "$Name contains unsupported characters for read-only packet arguments."
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

function Add-MissingRequirement {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Test-JsonObjectHasProperty {
    param([object]$Item, [string]$PropertyName)
    return $null -ne $Item -and $null -ne $Item.PSObject.Properties[$PropertyName]
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
if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 720) {
    throw "TinyLiveHours must be between 1 and 720."
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for profit shadow experiment packet preflight."
}

$gateScript = Join-Path $PSScriptRoot "prepare_profit_experiment_gate_ssh.ps1"
$gateArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $gateScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-ReviewDays", [string]$ReviewDays,
    "-TinyLiveHours", [string]$TinyLiveHours
)

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $gateOutput = & $powerShell.Source @gateArgs 2>&1
    $gateExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$gateText = ($gateOutput | Out-String)
$originDelta = Get-LastPrefixedValue -Text $gateText -Prefix "origin_delta_status="
$topCandidate = Get-LastPrefixedValue -Text $gateText -Prefix "top_profit_improvement_candidate="
$topStatus = Get-LastPrefixedValue -Text $gateText -Prefix "top_profit_improvement_candidate_status="
$decisionJson = Get-LastPrefixedValue -Text $gateText -Prefix "profit_improvement_review_decision="
$decision = Convert-JsonObjectOrNull -Value $decisionJson
$gateStatus = Get-LastPrefixedValue -Text $gateText -Prefix "profit_experiment_gate_status="
$gateNextAction = Get-LastPrefixedValue -Text $gateText -Prefix "profit_experiment_next_action="
$deployRequired = Get-LastPrefixedValue -Text $gateText -Prefix "deploy_required_before_profit_experiment="
$shadowAllowed = Get-LastPrefixedValue -Text $gateText -Prefix "shadow_experiment_review_allowed="
$livePolicyAllowed = Get-LastPrefixedValue -Text $gateText -Prefix "live_policy_change_allowed="
$gateMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $gateText -Prefix "profit_experiment_missing_requirements=")
$dataFreshnessMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $gateText -Prefix "data_freshness_counterfactual_gate_missing_requirements=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($item in @($gateMissing)) {
    Add-MissingRequirement -List $missingRequirements -Value ([string]$item)
}
if ($gateExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "profit experiment gate completed"
}
if ([string]::IsNullOrWhiteSpace($gateStatus)) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_experiment_gate_status present"
}
if ([string]::IsNullOrWhiteSpace($decisionJson)) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_improvement_review_decision present"
}
if ($null -eq $decision) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_improvement_review_decision valid JSON"
}
if ($deployRequired -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($shadowAllowed -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "shadow_experiment_review_allowed true"
}
if ($livePolicyAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "live_policy_change_allowed false"
}

if ($null -ne $decision) {
    foreach ($field in @("decision", "canDraftShadowExperimentReview", "deployRequired", "allowedReviewTypes", "topCandidate", "recommendation", "rankedEvidenceRefs", "missingRequirementCount", "missingRequirements", "nextAction", "notAuthorization")) {
        if (-not (Test-JsonObjectHasProperty -Item $decision -PropertyName $field)) {
            Add-MissingRequirement -List $missingRequirements -Value "profit review decision missing field: $field"
        }
    }
    if ($decision.canDraftShadowExperimentReview -ne $true) {
        Add-MissingRequirement -List $missingRequirements -Value "profit decision cannot draft shadow experiment review"
    }
    if ($decision.deployRequired -ne $false) {
        Add-MissingRequirement -List $missingRequirements -Value "profit decision deployRequired false"
    }
    if ([string]$decision.notAuthorization -notmatch "does not authorize live trading") {
        Add-MissingRequirement -List $missingRequirements -Value "profit decision missing no-live authorization text"
    }
}

$packetReady = $missingRequirements.Count -eq 0 -and $gateStatus -eq "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE"
$packetStatus = "NOT_READY"
$packetNextAction = "Resolve missing read-only evidence, then rerun this preflight."
if ($gateExitCode -ne 0 -or $gateStatus -eq "NO_EVIDENCE") {
    $packetStatus = "NO_EVIDENCE"
    $packetNextAction = "Fix read-only profit gate collection before drafting any shadow experiment packet."
} elseif ($deployRequired -eq "true" -or $originDelta -eq "RUNTIME_DRIFT" -or $gateStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME") {
    $packetStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $packetNextAction = "Separately deploy and verify current origin/main, then rerun this packet preflight."
} elseif ($packetReady) {
    $packetStatus = "READY_FOR_SHADOW_EXPERIMENT_PACKET_NOT_LIVE"
    $packetNextAction = "Attach the emitted packet to a separate shadow-only experiment review; this is not live policy approval."
} elseif ($gateStatus -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY") {
    $packetStatus = "OPERATOR_REVIEW_REQUIRED_READ_ONLY"
    $packetNextAction = "Route the strategy 485 operator packet path; this shadow packet does not authorize position or OCO mutation."
} elseif ($gateStatus -eq "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE") {
    $packetStatus = "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE"
    $packetNextAction = "Collect replayable DataFreshness candidate snapshots before any shadow experiment packet."
}

$packet = [pscustomobject]@{
    packetType = "PROFIT_SHADOW_EXPERIMENT_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    originDeltaStatus = $originDelta
    topCandidate = $topCandidate
    topCandidateStatus = $topStatus
    gateStatus = $gateStatus
    deployRequired = ($deployRequired -eq "true")
    shadowExperimentReviewAllowed = ($shadowAllowed -eq "true")
    livePolicyChangeAllowed = $false
    positionOrOcoMutationAllowed = $false
    sourceGate = "prepare_profit_experiment_gate_ssh.ps1"
    requiredFreshEvidence = @("deployed runtime current", "fresh replayCandidateId rows", "entry/TP/SL candidate snapshot", "EV and OCO preflight snapshots", "shadow replay removing only DataFreshnessGuard", "hard-gate preservation review")
    dataFreshnessCounterfactualMissingRequirements = @($dataFreshnessMissing)
    profitImprovementReviewDecision = $decision
    missingRequirements = @($missingRequirements)
    nextAction = $packetNextAction
    notAuthorization = "read-only shadow experiment packet preflight only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutation, close-position, OCO modification, DB changes, deploy, restart, production env mutation, external backfill/import, or policy relaxation"
}

Write-Host "[profit-shadow-experiment-packet] read-only packet preflight"
Write-Host "scope=READ_ONLY; runs prepare_profit_experiment_gate_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_gate=prepare_profit_experiment_gate_ssh.ps1"
Write-Host "source_gate_exit_code=$gateExitCode"
Write-Host "origin_delta_status=$originDelta"
Write-Host "symbol=$Symbol"
Write-Host "top_profit_improvement_candidate=$topCandidate"
Write-Host "top_profit_improvement_candidate_status=$topStatus"
Write-Host "profit_improvement_review_decision=$decisionJson"
Write-Host "deploy_required_before_profit_shadow_packet=$deployRequired"
Write-Host "shadow_experiment_review_allowed=$shadowAllowed"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "profit_experiment_gate_status=$gateStatus"
Write-Host ("profit_shadow_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_shadow_experiment_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet))
Write-Host "profit_shadow_packet_status=$packetStatus"
Write-Host "profit_shadow_packet_next_action=$packetNextAction"
Write-Host "notAuthorization=read-only packet preflight only; does not authorize DataFreshnessGuard relaxation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, closing positions, OCO modification, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[profit-shadow-experiment-packet] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Profit shadow experiment packet is not ready: $packetStatus; missing=$(@($missingRequirements) -join '; ')"
}
