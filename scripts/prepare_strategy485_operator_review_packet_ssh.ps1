param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 485,
    [int]$Days = 30,
    [int]$PositionAgeWarnDays = 5,
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
    param(
        [string]$Text,
        [string]$Prefix
    )

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
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Test-JsonObjectHasProperty {
    param(
        [object]$Item,
        [string]$PropertyName
    )
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
if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}
if ($Days -lt 1 -or $Days -gt 180) {
    throw "Days must be between 1 and 180."
}
if ($PositionAgeWarnDays -lt 1 -or $PositionAgeWarnDays -gt 90) {
    throw "PositionAgeWarnDays must be between 1 and 90."
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
    throw "Unable to find powershell or pwsh for strategy 485 operator review packet preflight."
}

$gateScript = Join-Path $PSScriptRoot "prepare_strategy485_position_review_gate_ssh.ps1"
$gateArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $gateScript,
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol,
    "-StrategyId", [string]$StrategyId,
    "-Days", [string]$Days,
    "-PositionAgeWarnDays", [string]$PositionAgeWarnDays
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
$recommendation = Get-LastPrefixedValue -Text $gateText -Prefix "strategy485_position_risk_recommendation="
$decisionJson = Get-LastPrefixedValue -Text $gateText -Prefix "strategy485_position_review_decision="
$decision = Convert-JsonObjectOrNull -Value $decisionJson
$gateStatus = Get-LastPrefixedValue -Text $gateText -Prefix "strategy485_position_review_gate_status="
$gateNextAction = Get-LastPrefixedValue -Text $gateText -Prefix "strategy485_position_review_next_action="
$gateMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $gateText -Prefix "strategy485_review_missing_requirements=")
$operatorReviewAllowed = Get-LastPrefixedValue -Text $gateText -Prefix "operator_review_packet_allowed="
$mutationAllowed = Get-LastPrefixedValue -Text $gateText -Prefix "position_or_oco_mutation_allowed="
$deployRequired = Get-LastPrefixedValue -Text $gateText -Prefix "deploy_required_before_strategy485_review="

$missingRequirements = [System.Collections.Generic.List[string]]::new()
$gateMissingIsBlocking = ($gateStatus -ne "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION")
if ($gateMissingIsBlocking) {
    foreach ($item in @($gateMissing)) {
        Add-MissingRequirement -List $missingRequirements -Value ([string]$item)
    }
}
if ($gateExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485 position review gate completed"
}
if ([string]::IsNullOrWhiteSpace($gateStatus)) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485_position_review_gate_status present"
}
if ([string]::IsNullOrWhiteSpace($decisionJson)) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485_position_review_decision present"
}
if ($null -eq $decision) {
    Add-MissingRequirement -List $missingRequirements -Value "strategy485_position_review_decision valid JSON"
}
if ($deployRequired -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($operatorReviewAllowed -ne "true") {
    Add-MissingRequirement -List $missingRequirements -Value "operator_review_packet_allowed true"
}
if ($mutationAllowed -ne "false") {
    Add-MissingRequirement -List $missingRequirements -Value "position_or_oco_mutation_allowed false"
}

if ($null -ne $decision) {
    foreach ($field in @("decision", "canDraftOperatorReviewPacket", "positionOrOcoMutationAllowed", "ocoHealthOk", "openPositionCount", "negativeEvPositionCount", "closeOrModifySuggestionCount", "positionTimeoutEventCount", "tpStretchWatchCount", "tpStretchStretchedCount", "positions", "requiredEvidence", "nextAction", "notAuthorization")) {
        if (-not (Test-JsonObjectHasProperty -Item $decision -PropertyName $field)) {
            Add-MissingRequirement -List $missingRequirements -Value "strategy485 decision missing field: $field"
        }
    }
    if ($decision.canDraftOperatorReviewPacket -ne $true) {
        Add-MissingRequirement -List $missingRequirements -Value "strategy485 decision cannot draft operator packet"
    }
    if ($decision.positionOrOcoMutationAllowed -ne $false) {
        Add-MissingRequirement -List $missingRequirements -Value "strategy485 decision must block position/OCO mutation"
    }
    if ([string]$decision.notAuthorization -notmatch "does not authorize close-position") {
        Add-MissingRequirement -List $missingRequirements -Value "strategy485 decision missing no-mutation authorization text"
    }
}

$packetReady = $missingRequirements.Count -eq 0 -and $gateStatus -eq "READY_FOR_OPERATOR_REVIEW_NOT_MUTATION"
$packetStatus = "NOT_READY"
$packetNextAction = "Resolve missing read-only evidence, then rerun this preflight."
if ($gateExitCode -ne 0 -or $gateStatus -eq "NO_EVIDENCE") {
    $packetStatus = "NO_EVIDENCE"
    $packetNextAction = "Fix read-only SSH smoke collection before drafting any strategy 485 operator packet."
} elseif ($deployRequired -eq "true" -or $originDelta -eq "RUNTIME_DRIFT" -or $gateStatus -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME") {
    $packetStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $packetNextAction = "Separately deploy and verify current origin/main, then rerun this packet preflight."
} elseif ($packetReady) {
    $packetStatus = "READY_FOR_OPERATOR_PACKET_NOT_MUTATION"
    $packetNextAction = "Attach the emitted packet to a separate operator review; this is not close-position or OCO-modification approval."
} elseif ($gateStatus -eq "BLOCKED_OCO_PROTECTION_FIRST") {
    $packetStatus = "BLOCKED_OCO_PROTECTION_FIRST"
    $packetNextAction = "Review OCO protection through a separately authorized safety path before profit optimization."
} elseif ($gateStatus -eq "WATCH_ONLY" -or $gateStatus -eq "NO_POSITION_RISK_ACTION") {
    $packetStatus = $gateStatus
    $packetNextAction = "No operator action packet is routed from current strategy 485 evidence."
}

$packet = [pscustomobject]@{
    packetType = "STRATEGY485_AGED_NEGATIVE_EV_OPERATOR_REVIEW"
    status = $packetStatus
    symbol = $Symbol
    strategyId = $StrategyId
    originDeltaStatus = $originDelta
    recommendation = $recommendation
    gateStatus = $gateStatus
    deployRequired = ($deployRequired -eq "true")
    operatorReviewPacketAllowed = ($operatorReviewAllowed -eq "true")
    positionOrOcoMutationAllowed = $false
    sourceGate = "prepare_strategy485_position_review_gate_ssh.ps1"
    reviewPlan = "docs/strategy485-aged-position-review-plan.md"
    requiredFreshEvidence = @("fresh OCO health", "active-position EV reassessment", "TP stretch and aging evidence", "stop-sweep policy review", "recent-closed PnL context", "monthly PnL context", "separate operator approval before any close-position or OCO mutation")
    strategy485PositionReviewDecision = $decision
    missingRequirements = @($missingRequirements)
    nextAction = $packetNextAction
    notAuthorization = "read-only operator packet preflight only; does not authorize close-position, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutation, DB changes, deploy, restart, production env mutation, external backfill/import, or policy relaxation"
}

Write-Host "[strategy485-operator-review-packet] read-only packet preflight"
Write-Host "scope=READ_ONLY; runs prepare_strategy485_position_review_gate_ssh.ps1 only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_gate=prepare_strategy485_position_review_gate_ssh.ps1"
Write-Host "review_plan=docs/strategy485-aged-position-review-plan.md"
Write-Host "source_gate_exit_code=$gateExitCode"
Write-Host "origin_delta_status=$originDelta"
Write-Host "strategyId=$StrategyId"
Write-Host "symbol=$Symbol"
Write-Host "strategy485_position_risk_recommendation=$recommendation"
Write-Host "strategy485_position_review_decision=$decisionJson"
Write-Host "deploy_required_before_strategy485_packet=$deployRequired"
Write-Host "operator_review_packet_allowed=$operatorReviewAllowed"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "strategy485_position_review_gate_status=$gateStatus"
Write-Host ("strategy485_operator_packet_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("strategy485_operator_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $packet))
Write-Host "strategy485_operator_packet_status=$packetStatus"
Write-Host "strategy485_operator_packet_next_action=$packetNextAction"
Write-Host "notAuthorization=read-only packet preflight only; does not authorize closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[strategy485-operator-review-packet] read-only check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Strategy 485 operator review packet is not ready: $packetStatus; missing=$(@($missingRequirements) -join '; ')"
}
