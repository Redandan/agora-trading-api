param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [int]$ReviewDays = 30,
    [int]$TinyLiveHours = 720
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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

function Assert-RemotePathSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch "^/[A-Za-z0-9._/-]+$") {
        throw "$Name contains unsupported characters for remote shell embedding."
    }
}

function Assert-SshHostSafe {
    param([string]$Name, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 255 -or $Value.StartsWith("-") -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9._@:-]*$") {
        throw "$Name contains unsupported characters for ssh target."
    }
}

function Assert-McpSmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-McpSmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31

$scriptDir = $PSScriptRoot

function Invoke-Smoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [string[]]$Arguments
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if (-not $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if (-not $powerShell) {
        throw "PowerShell is not available for child smoke invocation."
    }

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath @Arguments 2>&1
    $exit = $LASTEXITCODE
    $text = ($output | Out-String).Trim()

    Write-Host ""
    Write-Host "===== $Name ====="
    if ($text.Length -gt 5000) {
        Write-Host ($text.Substring(0, 5000) + "`n...[truncated]")
    } else {
        Write-Host $text
    }
    Write-Host "===== $Name exitCode=$exit ====="

    if ($exit -ne 0) {
        throw "$Name failed with exit code $exit"
    }
    return $text
}

function Get-Marker {
    param([string]$Text, [string]$Prefix)
    $matches = [regex]::Matches($Text, "(?m)^$([regex]::Escape($Prefix))(.+)$")
    if ($matches.Count -eq 0) {
        return ""
    }
    return $matches[$matches.Count - 1].Groups[1].Value.Trim()
}

function Convert-MarkerNumber {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    $clean = $Value.Trim().TrimEnd("%")
    $parsed = 0.0
    if ([double]::TryParse($clean, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$parsed)) {
        return $parsed
    }
    return $null
}

function Convert-MarkerJsonOrNull {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return $null
    }
    try {
        return $Value | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "failed to parse marker JSON: $($_.Exception.Message)"
    }
}

function Add-UniqueRequirement {
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

function New-ProfitImprovementReviewDecision {
    param(
        [string]$OriginDelta,
        [string]$TopCandidate,
        [object[]]$CandidateScorecard,
        [string]$Recommendation
    )

    $deployRequired = ($OriginDelta -eq "RUNTIME_DRIFT")
    $allowedReviewTypes = New-Object System.Collections.Generic.List[string]
    $missingRequirements = New-Object System.Collections.Generic.List[string]
    $decision = "NO_PROFIT_IMPROVEMENT_ACTION_FROM_BUNDLE"

    if ($deployRequired) {
        Add-UniqueRequirement -List $missingRequirements -Value "deployed runtime current"
    }

    foreach ($candidate in @($CandidateScorecard)) {
        $name = [string]$candidate.candidate
        $status = [string]$candidate.status

        if ($status -eq "READY_FOR_COUNTERFACTUAL_POLICY_REVIEW" -and -not $deployRequired) {
            Add-UniqueRequirement -List $allowedReviewTypes -Value "data-freshness-counterfactual-shadow-review"
            continue
        }

        if ($status -eq "BLOCKED_WAIT_DEPLOY_AND_REPLAY_EVIDENCE") {
            foreach ($required in @($candidate.requiredEvidence)) {
                Add-UniqueRequirement -List $missingRequirements -Value ([string]$required)
            }
        } elseif ($status -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY") {
            Add-UniqueRequirement -List $missingRequirements -Value "separate operator approval before any position/OCO mutation"
        } elseif ($status -eq "WAIT_THRESHOLD_CROSS_KEEP_HARD_GATES") {
            Add-UniqueRequirement -List $missingRequirements -Value "current BUY candidate and hard-gate pass evidence"
        } elseif (-not [string]::IsNullOrWhiteSpace($name) -and [string]::IsNullOrWhiteSpace($status)) {
            Add-UniqueRequirement -List $missingRequirements -Value "$name status evidence"
        }
    }

    if (@($CandidateScorecard).Count -eq 0 -or [string]::IsNullOrWhiteSpace($TopCandidate) -or $TopCandidate -eq "NONE") {
        $decision = "NO_PROFIT_IMPROVEMENT_ACTION_FROM_BUNDLE"
        Add-UniqueRequirement -List $missingRequirements -Value "profit_improvement_candidate_scorecard is missing or empty"
    } elseif ($deployRequired) {
        $decision = "BLOCKED_DEPLOY_CURRENT_RUNTIME"
    } elseif ($allowedReviewTypes.Count -gt 0) {
        $decision = "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE"
    } elseif (@($CandidateScorecard | Where-Object { $_.status -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY" }).Count -gt 0) {
        $decision = "OPERATOR_REVIEW_REQUIRED_READ_ONLY"
    } else {
        $decision = "BLOCKED_COLLECT_COUNTERFACTUAL_EVIDENCE"
    }

    $rankedEvidenceRefs = @($CandidateScorecard | ForEach-Object {
            [pscustomobject]@{
                rank = $_.rank
                candidate = $_.candidate
                status = $_.status
                evidence = $_.evidence
            }
        })
    $strategy485ReviewDecision = $null
    $strategy485Evidence = @($rankedEvidenceRefs | Where-Object { $_.candidate -eq "Strategy 485 aged negative-EV open positions" } | Select-Object -First 1)
    if ($strategy485Evidence.Count -gt 0 -and $null -ne $strategy485Evidence[0].evidence) {
        $candidateEvidence = $strategy485Evidence[0].evidence
        if ($candidateEvidence -is [System.Collections.IDictionary] -and $candidateEvidence.Contains("reviewDecision")) {
            $strategy485ReviewDecision = $candidateEvidence["reviewDecision"]
        } elseif ($null -ne $candidateEvidence.PSObject.Properties["reviewDecision"]) {
            $strategy485ReviewDecision = $candidateEvidence.reviewDecision
        }
    }

    [pscustomobject]@{
        decision = $decision
        canDraftShadowExperimentReview = ($decision -eq "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE")
        deployRequired = $deployRequired
        allowedReviewTypes = @($allowedReviewTypes)
        topCandidate = $TopCandidate
        recommendation = $Recommendation
        rankedEvidenceRefs = @($rankedEvidenceRefs)
        strategy485ReviewDecision = $strategy485ReviewDecision
        missingRequirementCount = @($missingRequirements).Count
        missingRequirements = @($missingRequirements)
        nextAction = if ($decision -eq "BLOCKED_DEPLOY_CURRENT_RUNTIME") {
            "Separately deploy and verify current origin/main, then rerun profit-improvement review."
        } elseif ($decision -eq "READY_FOR_SHADOW_EXPERIMENT_REVIEW_NOT_LIVE") {
            "Draft a separate shadow-only experiment review preserving hard gates and no live mutation."
        } elseif ($decision -eq "OPERATOR_REVIEW_REQUIRED_READ_ONLY") {
            "Review read-only position-risk evidence; this bundle does not authorize close or OCO modification."
        } else {
            "Collect the listed replay, EV, OCO, and hard-gate evidence before any shadow/small experiment review."
        }
        notAuthorization = "read-only profit-improvement routing decision only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, close-position, OCO modification, or external backfill/import"
    }
}

function Assert-ProfitImprovementReviewDecisionShape {
    param([object]$Decision)

    foreach ($field in @("decision", "canDraftShadowExperimentReview", "deployRequired", "allowedReviewTypes", "topCandidate", "recommendation", "rankedEvidenceRefs", "strategy485ReviewDecision", "missingRequirementCount", "missingRequirements", "nextAction", "notAuthorization")) {
        if ($null -eq $Decision.PSObject.Properties[$field]) {
            throw "profit improvement review decision missing field: $field"
        }
    }
    foreach ($ref in @($Decision.rankedEvidenceRefs)) {
        foreach ($field in @("rank", "candidate", "status", "evidence")) {
            if ($null -eq $ref.PSObject.Properties[$field]) {
                throw "profit improvement review decision ranked evidence ref missing field: $field"
            }
        }
    }
    if ($Decision.missingRequirementCount -ne @($Decision.missingRequirements).Count) {
        throw "profit improvement review decision missingRequirementCount mismatch"
    }
    if ($Decision.notAuthorization -notmatch "does not authorize live trading") {
        throw "profit improvement review decision must preserve no-live authorization text"
    }
}

Write-Host "[profit-improvement-review-bundle] read-only review bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH/local smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "symbol=$Symbol reviewDays=$ReviewDays tinyLiveHours=$TinyLiveHours"

$common = @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir,
    "-EnvFile", $EnvFile,
    "-Symbol", $Symbol
)

$origin = Invoke-Smoke -Name "origin-delta" -ScriptName "smoke_live_origin_delta_local.ps1" -Arguments @(
    "-SshHost", $SshHost,
    "-SshKey", $SshKey,
    "-AppDir", $AppDir
)
$profitCandidate = Invoke-Smoke -Name "profit-candidate-review" -ScriptName "smoke_profit_candidate_review_ssh.ps1" -Arguments ($common + @("-ReviewDays", "$ReviewDays"))
$dataFreshnessFalseKill = Invoke-Smoke -Name "data-freshness-false-kill" -ScriptName "smoke_data_freshness_false_kill_review_ssh.ps1" -Arguments ($common + @("-LongDays", "14", "-ReviewDays", "7"))
$dataFreshnessExecutability = Invoke-Smoke -Name "data-freshness-executability" -ScriptName "smoke_data_freshness_executability_review_ssh.ps1" -Arguments $common
$strategy485 = Invoke-Smoke -Name "strategy485-position-risk" -ScriptName "smoke_strategy485_position_risk_ssh.ps1" -Arguments ($common + @("-Days", "$ReviewDays"))
$strategy574 = Invoke-Smoke -Name "strategy574-signal-governance" -ScriptName "smoke_strategy574_signal_governance_ssh.ps1" -Arguments $common
$tinyLive = Invoke-Smoke -Name "tiny-live-post-trade" -ScriptName "smoke_tiny_live_post_trade_ssh.ps1" -Arguments ($common + @("-Hours", "$TinyLiveHours"))

$originDelta = Get-Marker -Text $origin -Prefix "origin_delta_status="
$profitCandidateRecommendation = Get-Marker -Text $profitCandidate -Prefix "  profit_candidate_review_recommendation="
$dataFreshnessFalseKillRecommendation = Get-Marker -Text $dataFreshnessFalseKill -Prefix "  data_freshness_false_kill_recommendation="
$dataFreshnessExecutabilityRecommendation = Get-Marker -Text $dataFreshnessExecutability -Prefix "  data_freshness_executability_recommendation="
$strategy485Recommendation = Get-Marker -Text $strategy485 -Prefix "  strategy485_position_risk_recommendation="
$strategy485DecisionJson = Get-Marker -Text $strategy485 -Prefix "  strategy485_position_review_decision="
$strategy485Decision = Convert-MarkerJsonOrNull -Value $strategy485DecisionJson
$strategy574Recommendation = Get-Marker -Text $strategy574 -Prefix "  policy_change_recommendation="
$tinyLiveStatus = Get-Marker -Text $tinyLive -Prefix "post_trade_status="
$monthlyPnlTotalUsdt = Get-Marker -Text $profitCandidate -Prefix "  monthlyPnlTotalUsdt="
$dataFreshnessFalseKillPct = Convert-MarkerNumber -Value (Get-Marker -Text $dataFreshnessFalseKill -Prefix "  dataFreshnessFalseKillPct=")
$dataFreshnessAvgRetPct = Convert-MarkerNumber -Value (Get-Marker -Text $dataFreshnessFalseKill -Prefix "  dataFreshnessAvgRetPct=")
$negativeEvPositions = Convert-MarkerNumber -Value (Get-Marker -Text $strategy485 -Prefix "  negativeEvPositions=")
if ($null -ne $strategy485Decision -and $null -ne $strategy485Decision.PSObject.Properties["negativeEvPositionCount"]) {
    $negativeEvPositions = $strategy485Decision.negativeEvPositionCount
}
$strategy574NearBuy = Get-Marker -Text $strategy574 -Prefix "  strategy574_near_buy="
$strategy574TerminalReason = Get-Marker -Text $strategy574 -Prefix "  strategy574_terminal_reason="

$reviewItems = New-Object System.Collections.Generic.List[string]
if ($originDelta -eq "RUNTIME_DRIFT") {
    $reviewItems.Add("DEPLOY_CURRENT_RUNTIME_BEFORE_PROFIT_REVIEW")
}
if ($profitCandidateRecommendation -eq "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY") {
    $reviewItems.Add("REVIEW_DATAFRESHNESS_FALSE_KILL")
}
if ($dataFreshnessFalseKillRecommendation -eq "REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE") {
    $reviewItems.Add("REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE")
}
if ($dataFreshnessExecutabilityRecommendation -eq "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY") {
    $reviewItems.Add("COLLECT_EXECUTABILITY_COUNTERFACTUAL_BEFORE_POLICY_CHANGE")
}
if ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $reviewItems.Add("REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS")
}
if ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    $reviewItems.Add("KEEP_STRATEGY574_HARD_GATES_WAIT_THRESHOLD_CROSS")
}
if ($tinyLiveStatus -eq "PENDING_NO_NEW_TINY_LIVE_EXECUTION") {
    $reviewItems.Add("WAIT_FOR_NEW_TINYLIVE_EXECUTION_SAMPLE")
}

if ($reviewItems -contains "COLLECT_EXECUTABILITY_COUNTERFACTUAL_BEFORE_POLICY_CHANGE") {
    $recommendation = "COLLECT_DATAFRESHNESS_COUNTERFACTUAL_EVIDENCE"
} elseif ($reviewItems -contains "REVIEW_STRATEGY485_AGED_NEGATIVE_EV_POSITIONS") {
    $recommendation = "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK"
} elseif ($reviewItems -contains "WAIT_FOR_NEW_TINYLIVE_EXECUTION_SAMPLE") {
    $recommendation = "CONTINUE_TINYLIVE_MONITORING"
} elseif ($reviewItems.Count -gt 0) {
    $recommendation = "REVIEW_PROFIT_IMPROVEMENT_ITEMS"
} else {
    $recommendation = "NO_PROFIT_IMPROVEMENT_ACTION_FROM_BUNDLE"
}

$candidateScorecard = New-Object System.Collections.Generic.List[object]
if ($profitCandidateRecommendation -eq "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY" -or $dataFreshnessFalseKillRecommendation -eq "REVIEW_COLLECTOR_CADENCE_SHADOW_REPLAY_KEEP_HARD_GATE") {
    $status = "BLOCKED_WAIT_DEPLOY_AND_REPLAY_EVIDENCE"
    if ($originDelta -ne "RUNTIME_DRIFT" -and $dataFreshnessExecutabilityRecommendation -ne "ALPHA_NOT_EXECUTABILITY_PROVEN_COLLECT_SHADOW_REPLAY") {
        $status = "READY_FOR_COUNTERFACTUAL_POLICY_REVIEW"
    }
    $candidateScorecard.Add([ordered]@{
        rank = 1
        candidate = "DataFreshness false-kill counterfactual"
        priority = "P1"
        status = $status
        evidence = @{
            falseKillPct = $dataFreshnessFalseKillPct
            avgForwardRetPct = $dataFreshnessAvgRetPct
            executability = $dataFreshnessExecutabilityRecommendation
            originDelta = $originDelta
        }
        requiredEvidence = @(
            "deployed runtime current",
            "fresh replayCandidateId rows",
            "entry/TP/SL candidate snapshot",
            "EV and OCO preflight snapshots",
            "shadow replay removing only DataFreshnessGuard"
        )
        allowedNextAction = "deploy current runtime only after separate deploy authorization, then collect read-only replay evidence"
    })
}
if ($strategy485Recommendation -eq "REVIEW_AGED_NEGATIVE_EV_POSITIONS_READ_ONLY") {
    $candidateScorecard.Add([ordered]@{
        rank = 2
        candidate = "Strategy 485 aged negative-EV open positions"
        priority = "P1"
        status = "OPERATOR_REVIEW_REQUIRED_READ_ONLY"
        evidence = @{
            negativeEvPositions = $negativeEvPositions
            closeOrModifySuggestionCount = if ($null -ne $strategy485Decision) { $strategy485Decision.closeOrModifySuggestionCount } else { $null }
            positionTimeoutEventCount = if ($null -ne $strategy485Decision) { $strategy485Decision.positionTimeoutEventCount } else { $null }
            ocoHealthOk = if ($null -ne $strategy485Decision) { $strategy485Decision.ocoHealthOk } else { $null }
            recommendation = $strategy485Recommendation
            reviewDecision = $strategy485Decision
        }
        requiredEvidence = @(
            "current OCO health",
            "position EV reassessment",
            "TP stretch and timeout events",
            "operator-approved risk-reducing action before any mutation"
        )
        allowedNextAction = "review position-risk evidence; no close or OCO modification from this bundle"
    })
}
if ($strategy574Recommendation -eq "KEEP_HARD_GATES_AND_OBSERVE_TINY_LIVE_THRESHOLD_CROSS") {
    $candidateScorecard.Add([ordered]@{
        rank = 3
        candidate = "Strategy 574 TinyLive near-BUY governance"
        priority = "P2"
        status = "WAIT_THRESHOLD_CROSS_KEEP_HARD_GATES"
        evidence = @{
            nearBuy = $strategy574NearBuy
            terminalReason = $strategy574TerminalReason
            tinyLivePostTrade = $tinyLiveStatus
        }
        requiredEvidence = @(
            "current BUY candidate",
            "OCO preflight pass",
            "EV pass sample",
            "post-trade OCO protection evidence"
        )
        allowedNextAction = "continue read-only high-frequency observation; do not pre-buy before hard gates pass"
    })
}

$topCandidate = "NONE"
if ($candidateScorecard.Count -gt 0) {
    $topCandidate = $candidateScorecard[0].candidate
}
$candidateScorecardJson = ConvertTo-Json -Compress -Depth 8 -InputObject @($candidateScorecard.ToArray())
$reviewDecision = New-ProfitImprovementReviewDecision `
    -OriginDelta $originDelta `
    -TopCandidate $topCandidate `
    -CandidateScorecard @($candidateScorecard.ToArray()) `
    -Recommendation $recommendation
Assert-ProfitImprovementReviewDecisionShape -Decision $reviewDecision
$reviewDecisionJson = ConvertTo-Json -Compress -Depth 8 -InputObject $reviewDecision

Write-Host ""
Write-Host "Profit Improvement Bundle Summary:"
Write-Host "  origin_delta_status=$originDelta"
Write-Host "  monthlyPnlTotalUsdt=$monthlyPnlTotalUsdt"
Write-Host "  profit_candidate_review_recommendation=$profitCandidateRecommendation"
Write-Host "  data_freshness_false_kill_recommendation=$dataFreshnessFalseKillRecommendation"
Write-Host "  data_freshness_executability_recommendation=$dataFreshnessExecutabilityRecommendation"
Write-Host "  strategy485_position_risk_recommendation=$strategy485Recommendation"
Write-Host "  strategy485_position_review_decision=$strategy485DecisionJson"
Write-Host "  strategy574_policy_change_recommendation=$strategy574Recommendation"
Write-Host "  tiny_live_post_trade_status=$tinyLiveStatus"
Write-Host ("  profit_improvement_review_items=" + (ConvertTo-Json -Compress @($reviewItems)))
Write-Host "  profit_improvement_candidate_scorecard=$candidateScorecardJson"
Write-Host "  profit_improvement_review_decision=$reviewDecisionJson"
Write-Host "  top_profit_improvement_candidate=$topCandidate"
Write-Host "  profit_improvement_bundle_recommendation=$recommendation"
Write-Host "  notAuthorization=read-only review evidence only; does not authorize DataFreshnessGuard relaxation, closing positions, OCO modification, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, deploy, restart, production env changes, external backfill/import, or policy relaxation"
Write-Host ""
Write-Host "[profit-improvement-review-bundle] OK read-only check complete"
