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
        throw "$Name contains unsupported characters for read-only smoke arguments."
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

function Convert-ToCanonicalMissingRequirement {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }

    $trimmed = $Value.Trim()
    if ($trimmed -eq "fresh replayCandidateId rows") {
        return "fresh DataFreshness replayCandidateId rows"
    }
    return $trimmed
}

function Add-MissingRequirement {
    param(
        [System.Collections.Generic.List[string]]$List,
        [string]$Value
    )
    $canonical = Convert-ToCanonicalMissingRequirement -Value $Value
    if ([string]::IsNullOrWhiteSpace($canonical)) {
        return
    }
    if ($List -notcontains $canonical) {
        $List.Add($canonical)
    }
}

function Test-OriginDeltaAcceptableForProfitReview {
    param([string]$Value)
    return ($Value -eq "CURRENT_ORIGIN_MAIN" -or $Value -eq "DOCS_TOOLING_ONLY_DRIFT")
}

function Test-OriginDeltaRequiresDeploy {
    param([string]$Value)
    return ($Value -eq "RUNTIME_DRIFT" -or $Value -eq "MISMATCH")
}

function Get-RuntimeDeltaImpact {
    param([string[]]$Paths)

    $impacts = [System.Collections.Generic.List[string]]::new()
    foreach ($path in @($Paths)) {
        if ([string]::IsNullOrWhiteSpace($path)) {
            continue
        }
        if ($path -eq "src/main/java/com/agora/service/backtest/DataFreshnessReplayCandidateIds.java") {
            Add-MissingRequirement -List $impacts -Value "DATAFRESHNESS_REPLAY_CANDIDATE_ID_RUNTIME_NOT_DEPLOYED"
        } elseif ($path -eq "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java") {
            Add-MissingRequirement -List $impacts -Value "LIVE_SIGNAL_EVALUATION_RUNTIME_NOT_DEPLOYED"
        } else {
            Add-MissingRequirement -List $impacts -Value "UNCLASSIFIED_RUNTIME_DRIFT"
        }
    }
    if ($impacts.Count -eq 0) {
        Add-MissingRequirement -List $impacts -Value "NO_RUNTIME_DRIFT_PATHS"
    }
    return @($impacts)
}

function New-ProfitValidationReviewPlanEntry {
    param(
        [string]$Gate,
        [string]$State,
        [string]$Status,
        [string]$RiskCategory,
        [string]$Recommendation,
        [string]$AllowedFlagName,
        [string]$AllowedFlagValue,
        [object[]]$RequiredEvidence,
        [string]$NextAction
    )

    [pscustomobject]@{
        gate = $Gate
        state = $State
        status = $Status
        riskCategory = $RiskCategory
        recommendation = $Recommendation
        allowedFlag = $AllowedFlagName
        allowed = ($AllowedFlagValue -eq "true")
        requiredEvidence = @($RequiredEvidence)
        nextAction = $NextAction
        notAuthorization = "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
}

function Assert-ProfitValidationReviewPlanShape {
    param([object[]]$Plan)

    $items = @($Plan)
    if ($items.Count -ne 3) {
        throw "post-deploy profit validation review plan must contain 3 child gate entries, got $($items.Count)"
    }

    $expectedGates = @("auto-trading-review", "profit-loss-review", "profit-experiment-review")
    foreach ($gate in $expectedGates) {
        if (-not @($items.gate).Contains($gate)) {
            throw "post-deploy profit validation review plan missing gate: $gate"
        }
    }

    foreach ($item in $items) {
        foreach ($field in @("gate", "state", "status", "riskCategory", "recommendation", "allowedFlag", "allowed", "requiredEvidence", "nextAction", "notAuthorization")) {
            if ($null -eq $item.PSObject.Properties[$field]) {
                throw "post-deploy profit validation review plan entry missing field: $field"
            }
        }
        if (@($item.requiredEvidence).Count -eq 0) {
            throw "post-deploy profit validation review plan entry must preserve requiredEvidence: $($item.gate)"
        }
        if ($item.notAuthorization -notmatch "does not authorize live trading") {
            throw "post-deploy profit validation review plan entry must preserve no-live authorization text: $($item.gate)"
        }
    }
}

function New-ProfitValidationBlockerSummary {
    param(
        [object[]]$Plan,
        [object]$RuntimeDrift
    )

    $summary = [System.Collections.Generic.List[object]]::new()
    foreach ($item in @($Plan)) {
        if ($item.status -notmatch "^BLOCKED|^NO_EVIDENCE" -and @($item.requiredEvidence).Count -eq 0) {
            continue
        }
        $summary.Add([pscustomobject]@{
            gate = $item.gate
            status = $item.status
            riskCategory = $item.riskCategory
            allowedFlag = $item.allowedFlag
            allowed = $item.allowed
            requiredEvidenceCount = @($item.requiredEvidence).Count
            requiredEvidence = @($item.requiredEvidence)
            nextAction = $item.nextAction
            runtimeDrift = $RuntimeDrift
            notAuthorization = $item.notAuthorization
        })
    }
    return @($summary)
}

function Assert-ProfitValidationBlockerSummaryShape {
    param([object[]]$Summary)

    foreach ($item in @($Summary)) {
        foreach ($field in @("gate", "status", "riskCategory", "allowedFlag", "allowed", "requiredEvidenceCount", "requiredEvidence", "nextAction", "runtimeDrift", "notAuthorization")) {
            if ($null -eq $item.PSObject.Properties[$field]) {
                throw "post-deploy profit validation blocker summary entry missing field: $field"
            }
        }
        if ($item.requiredEvidenceCount -ne @($item.requiredEvidence).Count) {
            throw "post-deploy profit validation blocker summary requiredEvidenceCount mismatch: $($item.gate)"
        }
        if ($item.notAuthorization -notmatch "does not authorize live trading") {
            throw "post-deploy profit validation blocker summary entry must preserve no-live authorization text: $($item.gate)"
        }
        foreach ($field in @("originDeltaStatus", "serverWorktreeCommit", "originMainCommit", "runtimeDeltaFiles", "runtimeDeltaPaths", "runtimeDeltaImpact")) {
            if ($null -eq $item.runtimeDrift.PSObject.Properties[$field]) {
                throw "post-deploy profit validation blocker summary runtimeDrift missing field: $field"
            }
        }
        if ($item.runtimeDrift.runtimeDeltaFiles -ne @($item.runtimeDrift.runtimeDeltaPaths).Count) {
            throw "post-deploy profit validation blocker summary runtimeDeltaFiles mismatch: $($item.gate)"
        }
    }
}

function New-ProfitValidationReviewDecision {
    param(
        [string]$Status,
        [bool]$DeployRequired,
        [object[]]$ReviewPlan,
        [object[]]$BlockerSummary,
        [string[]]$MissingRequirements,
        [object]$RuntimeDrift,
        [string]$NextAction
    )

    $allowedReviewTypes = @($ReviewPlan | Where-Object { $_.allowed -eq $true } | ForEach-Object { $_.gate })
    $blockedGates = @($ReviewPlan | Where-Object { $_.status -match "^(BLOCKED|NO_EVIDENCE)" -or $_.allowed -ne $true } | ForEach-Object {
            [pscustomobject]@{
                gate = $_.gate
                status = $_.status
                riskCategory = $_.riskCategory
                recommendation = $_.recommendation
                allowedFlag = $_.allowedFlag
                allowed = $_.allowed
            }
        })
    [pscustomobject]@{
        decision = $Status
        canPrepareReviewPacket = ($Status -eq "READY_FOR_READ_ONLY_PROFIT_REVIEW_NOT_LIVE")
        deployRequired = $DeployRequired
        allowedReviewTypes = @($allowedReviewTypes)
        blockerCount = @($BlockerSummary).Count
        blockedGateCount = @($blockedGates).Count
        blockedGates = @($blockedGates)
        missingRequirementCount = @($MissingRequirements).Count
        missingRequirements = @($MissingRequirements)
        runtimeDrift = $RuntimeDrift
        nextAction = $NextAction
        notAuthorization = "read-only routing decision only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
    }
}

function Assert-ProfitValidationReviewDecisionShape {
    param([object]$Decision)

    foreach ($field in @("decision", "canPrepareReviewPacket", "deployRequired", "allowedReviewTypes", "blockerCount", "blockedGateCount", "blockedGates", "missingRequirementCount", "missingRequirements", "runtimeDrift", "nextAction", "notAuthorization")) {
        if ($null -eq $Decision.PSObject.Properties[$field]) {
            throw "post-deploy profit validation review decision missing field: $field"
        }
    }
    if ($Decision.blockedGateCount -ne @($Decision.blockedGates).Count) {
        throw "post-deploy profit validation review decision blockedGateCount mismatch"
    }
    foreach ($gate in @($Decision.blockedGates)) {
        foreach ($field in @("gate", "status", "riskCategory", "recommendation", "allowedFlag", "allowed")) {
            if ($null -eq $gate.PSObject.Properties[$field]) {
                throw "post-deploy profit validation review decision blocked gate missing field: $field"
            }
        }
    }
    if ($Decision.missingRequirementCount -ne @($Decision.missingRequirements).Count) {
        throw "post-deploy profit validation review decision missingRequirementCount mismatch"
    }
    if ($Decision.notAuthorization -notmatch "does not authorize live trading") {
        throw "post-deploy profit validation review decision must preserve no-live authorization text"
    }
    foreach ($field in @("originDeltaStatus", "serverWorktreeCommit", "originMainCommit", "runtimeDeltaFiles", "runtimeDeltaPaths", "runtimeDeltaImpact")) {
        if ($null -eq $Decision.runtimeDrift.PSObject.Properties[$field]) {
            throw "post-deploy profit validation review decision runtimeDrift missing field: $field"
        }
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
if ($TinyLiveHours -lt 1 -or $TinyLiveHours -gt 4320) {
    throw "TinyLiveHours must be between 1 and 4320."
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
    throw "Unable to find powershell or pwsh for post-deploy profit validation."
}

$autoGateScript = Join-Path $PSScriptRoot "prepare_auto_trading_review_gate_ssh.ps1"
$profitLossGateScript = Join-Path $PSScriptRoot "prepare_profit_loss_review_gate_ssh.ps1"
$profitExperimentGateScript = Join-Path $PSScriptRoot "prepare_profit_experiment_gate_ssh.ps1"
$originDeltaScript = Join-Path $PSScriptRoot "smoke_live_origin_delta_local.ps1"
foreach ($script in @($autoGateScript, $profitLossGateScript, $profitExperimentGateScript, $originDeltaScript)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Missing required read-only gate: $script"
    }
}

function Invoke-ChildGate {
    param(
        [string]$Name,
        [string]$ScriptPath,
        [string[]]$ExtraArguments
    )

    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $ScriptPath,
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir,
        "-EnvFile", $EnvFile,
        "-Symbol", $Symbol
    ) + $ExtraArguments

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source @arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "$Name failed with exit code $exitCode`n$text"
    }
    return $text
}

function Invoke-OriginDeltaClassifier {
    $arguments = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $originDeltaScript,
        "-SshHost", $SshHost,
        "-SshKey", $SshKey,
        "-AppDir", $AppDir
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source @arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "origin-delta-classifier failed with exit code $exitCode`n$text"
    }
    return $text
}

$originDeltaText = Invoke-OriginDeltaClassifier
$autoText = Invoke-ChildGate -Name "auto-trading-review-gate" -ScriptPath $autoGateScript -ExtraArguments @(
    "-ReviewDays", [string]$ReviewDays,
    "-TinyLiveHours", [string]$TinyLiveHours
)
$profitLossText = Invoke-ChildGate -Name "profit-loss-review-gate" -ScriptPath $profitLossGateScript -ExtraArguments @(
    "-ReviewDays", [string]$ReviewDays
)
$profitExperimentText = Invoke-ChildGate -Name "profit-experiment-gate" -ScriptPath $profitExperimentGateScript -ExtraArguments @(
    "-ReviewDays", [string]$ReviewDays,
    "-TinyLiveHours", [string]$TinyLiveHours
)

$autoOriginDelta = Get-LastPrefixedValue -Text $autoText -Prefix "origin_delta_status="
$profitLossOriginDelta = Get-LastPrefixedValue -Text $profitLossText -Prefix "origin_delta_status="
$profitExperimentOriginDelta = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "origin_delta_status="
$localOriginDelta = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_delta_status="
$serverWorktreeCommit = Get-LastPrefixedValue -Text $originDeltaText -Prefix "server_worktree_commit="
$originMainCommit = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_main_commit="
$originDeltaFiles = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_delta_files="
$originDocsToolingDeltaFiles = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_docs_tooling_delta_files="
$originRuntimeDeltaFiles = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_runtime_delta_files="
$originRuntimeDeltaPaths = Get-LastPrefixedValue -Text $originDeltaText -Prefix "origin_runtime_delta_paths="
$originRuntimeDeltaPathItems = Convert-JsonArrayOrEmpty -Value $originRuntimeDeltaPaths
$originDeltaValues = @($autoOriginDelta, $profitLossOriginDelta, $profitExperimentOriginDelta, $localOriginDelta) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique
$originDelta = if (@($originDeltaValues).Count -eq 1) { [string]@($originDeltaValues)[0] } elseif (@($originDeltaValues).Count -gt 1) { "MISMATCH" } else { "" }

$autoStatus = Get-LastPrefixedValue -Text $autoText -Prefix "auto_trading_review_gate_status="
$profitLossStatus = Get-LastPrefixedValue -Text $profitLossText -Prefix "profit_loss_review_gate_status="
$profitExperimentStatus = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "profit_experiment_gate_status="
$autoDeployRequired = Get-LastPrefixedValue -Text $autoText -Prefix "deploy_required_before_auto_trading_review="
$profitLossDeployRequired = Get-LastPrefixedValue -Text $profitLossText -Prefix "deploy_required_before_profit_loss_review="
$profitExperimentDeployRequired = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "deploy_required_before_profit_experiment="
$monthlyPnlTotalUsdt = Get-LastPrefixedValue -Text $profitLossText -Prefix "monthlyPnlTotalUsdt="
$profitRecommendation = Get-LastPrefixedValue -Text $profitLossText -Prefix "profit_candidate_review_recommendation="
$topProfitCandidate = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "top_profit_improvement_candidate="
$autoRecommendation = Get-LastPrefixedValue -Text $autoText -Prefix "auto_trading_review_recommendation="
$operatorReviewAllowed = Get-LastPrefixedValue -Text $autoText -Prefix "operator_review_packet_allowed="
$lossSourceReviewAllowed = Get-LastPrefixedValue -Text $profitLossText -Prefix "loss_source_review_allowed="
$shadowReviewAllowed = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "shadow_experiment_review_allowed="
$autoNextAction = Get-LastPrefixedValue -Text $autoText -Prefix "auto_trading_review_next_action="
$profitLossNextAction = Get-LastPrefixedValue -Text $profitLossText -Prefix "profit_loss_review_next_action="
$profitExperimentNextAction = Get-LastPrefixedValue -Text $profitExperimentText -Prefix "profit_experiment_next_action="

$autoMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $autoText -Prefix "auto_trading_review_missing_requirements=")
$profitLossMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $profitLossText -Prefix "profit_loss_review_missing_requirements=")
$profitExperimentMissing = Convert-JsonArrayOrEmpty -Value (Get-LastPrefixedValue -Text $profitExperimentText -Prefix "profit_experiment_missing_requirements=")

$missingRequirements = [System.Collections.Generic.List[string]]::new()
foreach ($value in @($autoMissing + $profitLossMissing + $profitExperimentMissing)) {
    Add-MissingRequirement -List $missingRequirements -Value ([string]$value)
}
foreach ($statusName in @(
        @{ Name = "auto_trading_review_gate_status"; Value = $autoStatus },
        @{ Name = "profit_loss_review_gate_status"; Value = $profitLossStatus },
        @{ Name = "profit_experiment_gate_status"; Value = $profitExperimentStatus }
    )) {
    if ([string]::IsNullOrWhiteSpace([string]$statusName.Value)) {
        Add-MissingRequirement -List $missingRequirements -Value "$($statusName.Name) missing"
    }
}
if ([string]::IsNullOrWhiteSpace($originDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_status missing"
}
if ([string]::IsNullOrWhiteSpace($localOriginDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_classifier missing"
}
if ([string]::IsNullOrWhiteSpace($originRuntimeDeltaPaths)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_runtime_delta_paths missing"
}
if ((-not [string]::IsNullOrWhiteSpace($originDelta)) -and -not (Test-OriginDeltaAcceptableForProfitReview -Value $originDelta) -and -not (Test-OriginDeltaRequiresDeploy -Value $originDelta)) {
    Add-MissingRequirement -List $missingRequirements -Value "origin_delta_status must be CURRENT_ORIGIN_MAIN or DOCS_TOOLING_ONLY_DRIFT"
}
if ((Test-OriginDeltaRequiresDeploy -Value $originDelta) -or $autoDeployRequired -eq "true" -or $profitLossDeployRequired -eq "true" -or $profitExperimentDeployRequired -eq "true") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}

$deployRequired = @($missingRequirements) -contains "deployed runtime current"
$readyStatuses = @(
    "READY_FOR_OPERATOR_POSITION_REVIEW_NOT_MUTATION",
    "READY_FOR_LOSS_SOURCE_REVIEW_NOT_LIVE",
    "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE",
    "NO_OPERATOR_ACTION_FROM_BUNDLE",
    "NO_LOSS_SOURCE_ACTION_FROM_CURRENT_EVIDENCE",
    "OBSERVE_CANDIDATES_NO_LOSS_PACKET"
)
$readyForProfitReview = (
    -not $deployRequired -and
    (Test-OriginDeltaAcceptableForProfitReview -Value $originDelta) -and
    $readyStatuses -contains $autoStatus -and
    $readyStatuses -contains $profitLossStatus -and
    $readyStatuses -contains $profitExperimentStatus
)

$status = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun post-deploy profit validation."
if ($deployRequired) {
    $status = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun post-deploy profit validation."
} elseif ([string]::IsNullOrWhiteSpace($autoStatus) -or [string]::IsNullOrWhiteSpace($profitLossStatus) -or [string]::IsNullOrWhiteSpace($profitExperimentStatus)) {
    $status = "NO_EVIDENCE"
    $nextAction = "Fix child gate output before drawing a profit validation conclusion."
} elseif ($readyForProfitReview -and ($operatorReviewAllowed -eq "true" -or $lossSourceReviewAllowed -eq "true" -or $shadowReviewAllowed -eq "true")) {
    $status = "READY_FOR_READ_ONLY_PROFIT_REVIEW_NOT_LIVE"
    $nextAction = "Prepare a read-only review packet for the allowed candidate type; no live mutation is authorized."
} elseif ($readyForProfitReview) {
    $status = "OBSERVE_NO_ACTION_FROM_CURRENT_EVIDENCE"
    $nextAction = "Continue read-only monitoring; no post-deploy profit action is routed by current evidence."
} else {
    $status = "BLOCKED_COLLECT_READ_ONLY_EVIDENCE"
    $nextAction = "Collect the listed child-gate evidence before any review packet."
}

$reviewPlan = @(
    New-ProfitValidationReviewPlanEntry `
        -Gate "auto-trading-review" `
        -State $(if ($operatorReviewAllowed -eq "true") { "READY" } elseif ($autoStatus -match "^BLOCKED|^NO_EVIDENCE") { "BLOCKED" } else { "OBSERVE" }) `
        -Status $autoStatus `
        -RiskCategory "position-risk-and-live-execution-readiness" `
        -Recommendation $autoRecommendation `
        -AllowedFlagName "operator_review_packet_allowed" `
        -AllowedFlagValue $operatorReviewAllowed `
        -RequiredEvidence @($autoMissing) `
        -NextAction $autoNextAction
    New-ProfitValidationReviewPlanEntry `
        -Gate "profit-loss-review" `
        -State $(if ($lossSourceReviewAllowed -eq "true") { "READY" } elseif ($profitLossStatus -match "^BLOCKED|^NO_EVIDENCE") { "BLOCKED" } else { "OBSERVE" }) `
        -Status $profitLossStatus `
        -RiskCategory "loss-source-and-datafreshness-counterfactual" `
        -Recommendation $profitRecommendation `
        -AllowedFlagName "loss_source_review_allowed" `
        -AllowedFlagValue $lossSourceReviewAllowed `
        -RequiredEvidence @($profitLossMissing) `
        -NextAction $profitLossNextAction
    New-ProfitValidationReviewPlanEntry `
        -Gate "profit-experiment-review" `
        -State $(if ($shadowReviewAllowed -eq "true") { "READY" } elseif ($profitExperimentStatus -match "^BLOCKED|^NO_EVIDENCE") { "BLOCKED" } else { "OBSERVE" }) `
        -Status $profitExperimentStatus `
        -RiskCategory "shadow-experiment-and-policy-counterfactual" `
        -Recommendation $topProfitCandidate `
        -AllowedFlagName "shadow_experiment_review_allowed" `
        -AllowedFlagValue $shadowReviewAllowed `
        -RequiredEvidence @($profitExperimentMissing) `
        -NextAction $profitExperimentNextAction
)
Assert-ProfitValidationReviewPlanShape -Plan $reviewPlan
$runtimeDrift = [pscustomobject]@{
    originDeltaStatus = $originDelta
    serverWorktreeCommit = $serverWorktreeCommit
    originMainCommit = $originMainCommit
    runtimeDeltaFiles = if ([string]::IsNullOrWhiteSpace($originRuntimeDeltaFiles)) { 0 } else { [int]$originRuntimeDeltaFiles }
    runtimeDeltaPaths = @($originRuntimeDeltaPathItems)
    runtimeDeltaImpact = @(Get-RuntimeDeltaImpact -Paths @($originRuntimeDeltaPathItems))
}
$blockerSummary = New-ProfitValidationBlockerSummary -Plan $reviewPlan -RuntimeDrift $runtimeDrift
Assert-ProfitValidationBlockerSummaryShape -Summary $blockerSummary
$reviewDecision = New-ProfitValidationReviewDecision `
    -Status $status `
    -DeployRequired $deployRequired `
    -ReviewPlan $reviewPlan `
    -BlockerSummary $blockerSummary `
    -MissingRequirements @($missingRequirements) `
    -RuntimeDrift $runtimeDrift `
    -NextAction $nextAction
Assert-ProfitValidationReviewDecisionShape -Decision $reviewDecision

Write-Host "[post-deploy-profit-validation] read-only validation bundle"
Write-Host "scope=READ_ONLY; runs auto-trading, profit-loss, and profit-experiment gates only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_gate=prepare_auto_trading_review_gate_ssh.ps1"
Write-Host "source_gate=prepare_profit_loss_review_gate_ssh.ps1"
Write-Host "source_gate=prepare_profit_experiment_gate_ssh.ps1"
Write-Host "source_smoke=smoke_live_origin_delta_local.ps1"
Write-Host "origin_delta_status=$originDelta"
Write-Host "server_worktree_commit=$serverWorktreeCommit"
Write-Host "origin_main_commit=$originMainCommit"
Write-Host "origin_delta_files=$originDeltaFiles"
Write-Host "origin_docs_tooling_delta_files=$originDocsToolingDeltaFiles"
Write-Host "origin_runtime_delta_files=$originRuntimeDeltaFiles"
Write-Host "origin_runtime_delta_paths=$originRuntimeDeltaPaths"
Write-Host ("origin_runtime_delta_impact=" + (ConvertTo-Json -Compress @($runtimeDrift.runtimeDeltaImpact)))
Write-Host "monthlyPnlTotalUsdt=$monthlyPnlTotalUsdt"
Write-Host "auto_trading_review_gate_status=$autoStatus"
Write-Host "profit_loss_review_gate_status=$profitLossStatus"
Write-Host "profit_experiment_gate_status=$profitExperimentStatus"
Write-Host "auto_trading_review_recommendation=$autoRecommendation"
Write-Host "profit_candidate_review_recommendation=$profitRecommendation"
Write-Host "top_profit_improvement_candidate=$topProfitCandidate"
Write-Host "deploy_required_before_post_deploy_profit_validation=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "operator_review_packet_allowed=$operatorReviewAllowed"
Write-Host "loss_source_review_allowed=$lossSourceReviewAllowed"
Write-Host "shadow_experiment_review_allowed=$shadowReviewAllowed"
Write-Host "live_policy_change_allowed=false"
Write-Host "position_or_oco_mutation_allowed=false"
Write-Host "tiny_live_order_allowed=false"
Write-Host ("post_deploy_profit_validation_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("post_deploy_profit_validation_review_plan=" + (ConvertTo-Json -Compress -Depth 5 @($reviewPlan)))
Write-Host ("post_deploy_profit_validation_blocker_summary=" + (ConvertTo-Json -Compress -Depth 5 @($blockerSummary)))
Write-Host ("post_deploy_profit_validation_review_decision=" + (ConvertTo-Json -Compress -Depth 5 $reviewDecision))
Write-Host "post_deploy_profit_validation_status=$status"
Write-Host "post_deploy_profit_validation_next_action=$nextAction"
Write-Host "notAuthorization=read-only validation only; does not authorize DataFreshnessGuard relaxation, close-position, OCO modification, pre-buying, TinyLive order execution, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[post-deploy-profit-validation] read-only check complete"

if ($RequireReady -and $status -ne "READY_FOR_READ_ONLY_PROFIT_REVIEW_NOT_LIVE" -and $status -ne "OBSERVE_NO_ACTION_FROM_CURRENT_EVIDENCE") {
    throw "Post-deploy profit validation is not ready: $status; missing=$(@($missingRequirements) -join '; ')"
}
