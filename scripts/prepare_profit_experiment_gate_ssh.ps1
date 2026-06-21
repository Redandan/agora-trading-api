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

function Convert-JsonObjectOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    try {
        return ($Value | ConvertFrom-Json -ErrorAction Stop)
    } catch {
        return $null
    }
}

function Get-RequiredEvidence {
    param([object]$Candidate)
    if ($null -eq $Candidate -or $null -eq $Candidate.PSObject.Properties["requiredEvidence"]) {
        return @()
    }
    return @($Candidate.requiredEvidence)
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

function Add-DecisionMissingRequirements {
    param(
        [System.Collections.Generic.List[string]]$List,
        [object]$Decision
    )
    if ($null -eq $Decision -or $null -eq $Decision.PSObject.Properties["missingRequirements"]) {
        return
    }
    foreach ($required in @($Decision.missingRequirements)) {
        Add-MissingRequirement -List $List -Value ([string]$required)
    }
}

function Add-DataFreshnessCounterfactualMissingRequirements {
    param(
        [System.Collections.Generic.List[string]]$List,
        [object[]]$Scorecard
    )

    $candidate = @($Scorecard | Where-Object { $_.candidate -eq "DataFreshness false-kill counterfactual" } | Select-Object -First 1)
    if ($candidate.Count -eq 0 -or $null -eq $candidate[0].PSObject.Properties["evidence"]) {
        return
    }

    $evidence = $candidate[0].evidence
    $counterfactual = if ($null -ne $evidence.PSObject.Properties["counterfactual"]) { [string]$evidence.counterfactual } else { "" }
    $replayableRows = 0
    if ($null -ne $evidence.PSObject.Properties["completeReplayableCandidateRows"]) {
        [void][int]::TryParse([string]$evidence.completeReplayableCandidateRows, [ref]$replayableRows)
    }

    if ($counterfactual -and $counterfactual -ne "REVIEW_COUNTERFACTUAL_REPLAY_CANDIDATES") {
        Add-MissingRequirement -List $List -Value "DataFreshness counterfactual replay candidates reviewable"
    }
    if ($replayableRows -le 0) {
        Add-MissingRequirement -List $List -Value "complete DataFreshness replayable candidate rows"
    }
    if ($null -ne $evidence.PSObject.Properties["missingCounterfactualFields"]) {
        foreach ($field in @($evidence.missingCounterfactualFields)) {
            Add-MissingRequirement -List $List -Value "DataFreshness counterfactual field: $field"
        }
    }
}

function New-ProfitExperimentBlockerItems {
    param(
        [object[]]$Scorecard,
        [object]$ReviewDecision,
        [string]$OriginDelta,
        [string]$TopCandidate,
        [string]$GateStatus,
        [string[]]$MissingRequirements
    )

    $items = [System.Collections.Generic.List[object]]::new()
    $dataFreshnessCandidate = @($Scorecard | Where-Object { $_.candidate -eq "DataFreshness false-kill counterfactual" } | Select-Object -First 1)
    if ($dataFreshnessCandidate.Count -gt 0) {
        $candidate = $dataFreshnessCandidate[0]
        $evidence = if ($null -ne $candidate.PSObject.Properties["evidence"]) { $candidate.evidence } else { $null }
        $missingFields = @()
        if ($null -ne $evidence -and $null -ne $evidence.PSObject.Properties["missingCounterfactualFields"]) {
            $missingFields = @($evidence.missingCounterfactualFields)
        }
        $items.Add([pscustomobject]@{
            lane = "data-freshness-counterfactual"
            priority = if ($null -ne $candidate.PSObject.Properties["priority"]) { [string]$candidate.priority } else { "P1" }
            status = if ($null -ne $candidate.PSObject.Properties["status"]) { [string]$candidate.status } else { "UNKNOWN" }
            topCandidate = ($TopCandidate -eq "DataFreshness false-kill counterfactual")
            requiredEvidence = @(
                "fresh replayCandidateId rows",
                "entry/TP/SL candidate snapshot",
                "EV and OCO preflight snapshots",
                "shadow replay removing only DataFreshnessGuard"
            )
            missingCounterfactualFields = @($missingFields)
            completeReplayableCandidateRows = if ($null -ne $evidence -and $null -ne $evidence.PSObject.Properties["completeReplayableCandidateRows"]) { [string]$evidence.completeReplayableCandidateRows } else { "N/A" }
            recommendation = if ($null -ne $evidence -and $null -ne $evidence.PSObject.Properties["counterfactual"]) { [string]$evidence.counterfactual } else { "COLLECT_COUNTERFACTUAL_EVIDENCE" }
            nextAction = "Collect replayable DataFreshness candidate snapshots before any shadow or policy review."
            notAuthorization = "does not authorize DataFreshnessGuard relaxation, live trading, deploy, order/OCO mutation, or production env change"
        })
    }

    $strategy485Candidate = @($Scorecard | Where-Object { $_.candidate -eq "Strategy 485 aged negative-EV open positions" } | Select-Object -First 1)
    if ($strategy485Candidate.Count -gt 0) {
        $candidate = $strategy485Candidate[0]
        $evidence = if ($null -ne $candidate.PSObject.Properties["evidence"]) { $candidate.evidence } else { $null }
        $review = if ($null -ne $evidence -and $null -ne $evidence.PSObject.Properties["reviewDecision"]) { $evidence.reviewDecision } else { $null }
        $items.Add([pscustomobject]@{
            lane = "strategy485-risk-reduction"
            priority = if ($null -ne $candidate.PSObject.Properties["priority"]) { [string]$candidate.priority } else { "P1" }
            status = if ($null -ne $candidate.PSObject.Properties["status"]) { [string]$candidate.status } else { "UNKNOWN" }
            topCandidate = ($TopCandidate -eq "Strategy 485 aged negative-EV open positions")
            requiredEvidence = @(
                "current OCO health",
                "position EV reassessment",
                "TP stretch and timeout events",
                "operator-approved risk-reducing action before any mutation"
            )
            openPositionCount = if ($null -ne $review -and $null -ne $review.PSObject.Properties["openPositionCount"]) { [string]$review.openPositionCount } else { "N/A" }
            negativeEvPositionCount = if ($null -ne $review -and $null -ne $review.PSObject.Properties["negativeEvPositionCount"]) { [string]$review.negativeEvPositionCount } else { "N/A" }
            closeOrModifySuggestionCount = if ($null -ne $review -and $null -ne $review.PSObject.Properties["closeOrModifySuggestionCount"]) { [string]$review.closeOrModifySuggestionCount } else { "N/A" }
            recommendation = if ($null -ne $review -and $null -ne $review.PSObject.Properties["decision"]) { [string]$review.decision } else { "REVIEW_POSITION_RISK_READ_ONLY" }
            nextAction = "Attach read-only strategy 485 risk evidence to a separate operator review."
            notAuthorization = "does not authorize close-position, OCO modification, live trading, deploy, or production env change"
        })
    }

    if ($OriginDelta -eq "RUNTIME_DRIFT") {
        $items.Insert(0, [pscustomobject]@{
            lane = "runtime-currentness"
            priority = "P0"
            status = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
            topCandidate = $false
            requiredEvidence = @("deployed runtime current", "server verification after separate deploy authorization")
            recommendation = "DEPLOY_CURRENT_RUNTIME_THEN_RERUN_READ_ONLY_GATE"
            nextAction = "Deploy only after separate authorization, then rerun this read-only gate."
            notAuthorization = "does not authorize deploy from this gate"
        })
    }

    if ($items.Count -eq 0 -and @($MissingRequirements).Count -gt 0) {
        $items.Add([pscustomobject]@{
            lane = "unclassified-profit-experiment-blocker"
            priority = "P2"
            status = $GateStatus
            topCandidate = $false
            requiredEvidence = @($MissingRequirements)
            recommendation = if ($null -ne $ReviewDecision -and $null -ne $ReviewDecision.PSObject.Properties["recommendation"]) { [string]$ReviewDecision.recommendation } else { "COLLECT_MISSING_EVIDENCE" }
            nextAction = "Resolve the listed missing evidence, then rerun this gate."
            notAuthorization = "does not authorize live trading, policy relaxation, deploy, order/OCO mutation, or production env change"
        })
    }

    return @($items)
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
    throw "Unable to find powershell or pwsh for profit experiment gate."
}

$bundleScript = Join-Path $PSScriptRoot "smoke_profit_improvement_review_bundle_ssh.ps1"
if (-not (Test-Path -LiteralPath $bundleScript)) {
    throw "Missing profit improvement bundle: $bundleScript"
}

$bundleArgs = @(
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", $bundleScript,
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
    $bundleOutput = & $powerShell.Source @bundleArgs 2>&1
    $bundleExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}

$bundleText = ($bundleOutput | Out-String)
$originDelta = Get-LastPrefixedValue -Text $bundleText -Prefix "  origin_delta_status="
$monthlyPnlTotalUsdt = Get-LastPrefixedValue -Text $bundleText -Prefix "  monthlyPnlTotalUsdt="
$topCandidate = Get-LastPrefixedValue -Text $bundleText -Prefix "  top_profit_improvement_candidate="
$bundleRecommendation = Get-LastPrefixedValue -Text $bundleText -Prefix "  profit_improvement_bundle_recommendation="
$scorecardJson = Get-LastPrefixedValue -Text $bundleText -Prefix "  profit_improvement_candidate_scorecard="
$reviewDecisionJson = Get-LastPrefixedValue -Text $bundleText -Prefix "  profit_improvement_review_decision="
$scorecard = Convert-JsonArrayOrEmpty -Value $scorecardJson
$reviewDecision = Convert-JsonObjectOrNull -Value $reviewDecisionJson
$strategy485ReviewDecisionJson = "null"
if ($null -ne $reviewDecision -and $null -ne $reviewDecision.PSObject.Properties["strategy485ReviewDecision"] -and $null -ne $reviewDecision.strategy485ReviewDecision) {
    $strategy485ReviewDecisionJson = ConvertTo-Json -Compress -Depth 8 -InputObject $reviewDecision.strategy485ReviewDecision
}
$top = @($scorecard | Select-Object -First 1)
$topStatus = ""
$topPriority = ""
if ($top.Count -gt 0) {
    if ($null -ne $top[0].PSObject.Properties["status"]) {
        $topStatus = [string]$top[0].status
    }
    if ($null -ne $top[0].PSObject.Properties["priority"]) {
        $topPriority = [string]$top[0].priority
    }
}

$missingRequirements = [System.Collections.Generic.List[string]]::new()
if ($bundleExitCode -ne 0) {
    Add-MissingRequirement -List $missingRequirements -Value "profit improvement bundle exited non-zero"
}
if ([string]::IsNullOrWhiteSpace($scorecardJson) -or $scorecard.Count -eq 0) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_improvement_candidate_scorecard is missing or empty"
}
if ($null -eq $reviewDecision) {
    Add-MissingRequirement -List $missingRequirements -Value "profit_improvement_review_decision is missing or invalid"
}
if ([string]::IsNullOrWhiteSpace($topCandidate) -or $topCandidate -eq "NONE") {
    Add-MissingRequirement -List $missingRequirements -Value "top_profit_improvement_candidate is missing"
}
if ($originDelta -eq "RUNTIME_DRIFT") {
    Add-MissingRequirement -List $missingRequirements -Value "deployed runtime current"
}
if ($topStatus -eq "BLOCKED_WAIT_DEPLOY_AND_REPLAY_EVIDENCE" -or $topStatus -eq "BLOCKED_WAIT_REPLAY_EVIDENCE") {
    foreach ($required in Get-RequiredEvidence -Candidate $top[0]) {
        Add-MissingRequirement -List $missingRequirements -Value ([string]$required)
    }
}
if ($topStatus -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY") {
    Add-MissingRequirement -List $missingRequirements -Value "separate operator approval before any position/OCO mutation"
}
if ($topStatus -eq "WAIT_THRESHOLD_CROSS_KEEP_HARD_GATES") {
    Add-MissingRequirement -List $missingRequirements -Value "current BUY candidate and hard-gate pass evidence"
}
Add-DecisionMissingRequirements -List $missingRequirements -Decision $reviewDecision
Add-DataFreshnessCounterfactualMissingRequirements -List $missingRequirements -Scorecard @($scorecard)

$deployRequired = ($originDelta -eq "RUNTIME_DRIFT" -or @($missingRequirements) -contains "deployed runtime current")
$shadowReviewAllowed = $false
$gateStatus = "BLOCKED"
$nextAction = "Resolve missing read-only evidence, then rerun this gate."

if ($null -ne $reviewDecision) {
    $deployRequired = [bool]$reviewDecision.deployRequired
    $shadowReviewAllowed = [bool]$reviewDecision.canDraftShadowExperimentReview
}

if ($bundleExitCode -ne 0 -or $scorecard.Count -eq 0 -or $null -eq $reviewDecision) {
    $gateStatus = "NO_EVIDENCE"
    $nextAction = "Fix read-only profit bundle collection before drawing any experiment conclusion."
} elseif ($deployRequired) {
    $gateStatus = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    $nextAction = "Separately deploy and verify current origin/main, then rerun replay observation and profit experiment gate."
} elseif ($topStatus -eq "READY_FOR_COUNTERFACTUAL_POLICY_REVIEW") {
    $shadowReviewAllowed = $true
    $gateStatus = "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE"
    $nextAction = "Draft a separate shadow-only experiment proposal with capped notional, preserved hard gates, and no live policy change."
} elseif ($topStatus -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY") {
    $gateStatus = "OPERATOR_REVIEW_REQUIRED_READ_ONLY"
    $nextAction = "Review read-only position-risk evidence; this gate does not authorize close or OCO modification."
} else {
    $gateStatus = "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE"
    $nextAction = "Collect the listed replay, EV, OCO, and hard-gate evidence before any shadow/small experiment review."
}

$blockerItems = New-ProfitExperimentBlockerItems `
    -Scorecard @($scorecard) `
    -ReviewDecision $reviewDecision `
    -OriginDelta $originDelta `
    -TopCandidate $topCandidate `
    -GateStatus $gateStatus `
    -MissingRequirements @($missingRequirements)

Write-Host "[profit-experiment-gate] read-only evidence gate"
Write-Host "scope=READ_ONLY; runs the profit-improvement review bundle only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "source_smoke=smoke_profit_improvement_review_bundle_ssh.ps1"
Write-Host "source_smoke_exit_code=$bundleExitCode"
Write-Host "origin_delta_status=$originDelta"
Write-Host "monthlyPnlTotalUsdt=$monthlyPnlTotalUsdt"
Write-Host "top_profit_improvement_candidate=$topCandidate"
Write-Host "top_profit_improvement_candidate_priority=$topPriority"
Write-Host "top_profit_improvement_candidate_status=$topStatus"
Write-Host "profit_improvement_bundle_recommendation=$bundleRecommendation"
Write-Host "profit_improvement_review_decision=$reviewDecisionJson"
Write-Host "strategy485_position_review_decision=$strategy485ReviewDecisionJson"
Write-Host "deploy_required_before_profit_experiment=$($deployRequired.ToString().ToLowerInvariant())"
Write-Host "shadow_experiment_review_allowed=$($shadowReviewAllowed.ToString().ToLowerInvariant())"
Write-Host "live_policy_change_allowed=false"
Write-Host ("data_freshness_counterfactual_gate_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements | Where-Object { [string]$_ -like "DataFreshness counterfactual*" -or [string]$_ -eq "complete DataFreshness replayable candidate rows" })))
Write-Host ("profit_experiment_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host ("profit_experiment_blocker_items=" + (ConvertTo-Json -Compress -Depth 8 @($blockerItems)))
Write-Host "profit_experiment_gate_status=$gateStatus"
Write-Host "profit_experiment_next_action=$nextAction"
Write-Host "notAuthorization=read-only gate only; does not authorize DataFreshnessGuard relaxation, closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host "[profit-experiment-gate] read-only check complete"

if ($RequireReady -and -not $shadowReviewAllowed) {
    throw "Profit experiment gate is not ready: $gateStatus; missing=$(@($missingRequirements) -join '; ')"
}
