param(
    [string]$ReviewOutputDir = "target/profit-review",
    [int]$MatrixMaxAgeMinutes = 180,
    [string]$Symbol = "BTCUSDT",
    [int]$StrategyId = 485,
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

if ([string]::IsNullOrWhiteSpace($ReviewOutputDir)) {
    throw "ReviewOutputDir is required."
}
if ($MatrixMaxAgeMinutes -lt 1 -or $MatrixMaxAgeMinutes -gt 1440) {
    throw "MatrixMaxAgeMinutes must be between 1 and 1440."
}
if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol.Length -gt 64 -or $Symbol -notmatch "^[A-Za-z0-9._:-]+$") {
    throw "Symbol contains unsupported characters for profit operator review summary arguments."
}
if ($StrategyId -lt 1 -or $StrategyId -gt 1000000) {
    throw "StrategyId must be between 1 and 1000000."
}

$latestScript = Join-Path $PSScriptRoot "prepare_profit_operator_latest_action_brief.ps1"
if (-not (Test-Path -LiteralPath $latestScript)) {
    throw "Missing latest action brief script: $latestScript"
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) {
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
}
if ($null -eq $powerShell) {
    throw "Unable to find powershell or pwsh for profit operator review summary."
}

$latestArgs = @(
    "-ReviewOutputDir", $ReviewOutputDir,
    "-MatrixMaxAgeMinutes", "$MatrixMaxAgeMinutes",
    "-Symbol", $Symbol,
    "-StrategyId", "$StrategyId"
)
if ($RequireReady) {
    $latestArgs += "-RequireReady"
}

Write-Host "[profit-operator-review-summary] read-only summary"
Write-Host "scope=READ_ONLY; invokes prepare_profit_operator_latest_action_brief.ps1 only; no SSH fresh matrix, production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed; does not deploy."

$latestOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $latestScript @latestArgs 2>&1
$latestExitCode = if ($null -ne $LASTEXITCODE) { [int]$LASTEXITCODE } elseif ($?) { 0 } else { 1 }
$latestText = ($latestOutput | Out-String -Width 4096)
Write-Host $latestText
if ($latestExitCode -ne 0) {
    throw "Profit operator latest action brief failed with exit code $latestExitCode"
}

$packetJson = Get-LastPrefixedValue -Text $latestText -Prefix "profit_operator_action_brief_packet="
if ([string]::IsNullOrWhiteSpace($packetJson)) {
    throw "profit_operator_action_brief_packet missing from latest action brief output."
}

$packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
$decisionLanes = @($packet.decisionLanes)
$exitSideActionProposals = @($packet.exitSideActionProposals)
$readyLanes = @($decisionLanes | Where-Object { $_.readyForOperatorReview -eq $true })
$blockedLanes = @($decisionLanes | Where-Object { $_.readyForOperatorReview -ne $true })
$requiredEvidence = @($blockedLanes | ForEach-Object {
        $lane = $_.lane
        @($_.missingRequirements) | ForEach-Object {
            if (-not [string]::IsNullOrWhiteSpace([string]$_)) {
                [pscustomobject]@{
                    lane = $lane
                    requirement = [string]$_
                }
            }
        }
    })

$summary = [pscustomobject]@{
    packetType = "PROFIT_OPERATOR_REVIEW_SUMMARY"
    status = [string]$packet.status
    symbol = [string]$packet.symbol
    matrixStatus = [string]$packet.matrixStatus
    primaryRecommendation = [string]$packet.primaryRecommendation
    recommendedNextReview = [string]$packet.recommendedNextReview
    readyLaneCount = $readyLanes.Count
    blockedLaneCount = $blockedLanes.Count
    exitSideProposalCount = $exitSideActionProposals.Count
    readyLanes = @($readyLanes | ForEach-Object {
            [pscustomobject]@{
                lane = [string]$_.lane
                priority = [string]$_.priority
                decisionClass = [string]$_.decisionClass
                recommendation = [string]$_.recommendation
                nextAction = [string]$_.nextAction
                separateAuthorizationRequired = @($_.separateAuthorizationRequired)
            }
        })
    exitSideActionProposals = @($exitSideActionProposals | ForEach-Object {
            [pscustomobject]@{
                proposalId = [string]$_.proposalId
                lane = [string]$_.lane
                proposalClass = [string]$_.proposalClass
                status = [string]$_.status
                reviewContract = [string]$_.reviewContract
                requiredFreshEvidence = @($_.requiredFreshEvidence)
                allowedProposalOutput = @($_.allowedProposalOutput)
                forbiddenActions = @($_.forbiddenActions)
                nextAction = [string]$_.nextAction
                notAuthorization = [string]$_.notAuthorization
            }
        })
    blockedLanes = @($blockedLanes | ForEach-Object {
            [pscustomobject]@{
                lane = [string]$_.lane
                priority = [string]$_.priority
                decisionClass = [string]$_.decisionClass
                status = [string]$_.status
                missingRequirements = @($_.missingRequirements)
                nextAction = [string]$_.nextAction
            }
        })
    requiredEvidence = @($requiredEvidence)
    doNotActions = @($packet.doNotActions)
    nextAction = [string]$packet.nextAction
    notAuthorization = "read-only profit operator review summary only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
}

Write-Host ("profit_operator_review_summary_packet=" + (ConvertTo-Json -Compress -Depth 10 $summary))
Write-Host ("profit_operator_review_summary_ready_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($summary.readyLanes)))
Write-Host ("profit_operator_review_summary_exit_side_proposals=" + (ConvertTo-Json -Compress -Depth 8 @($summary.exitSideActionProposals)))
Write-Host ("profit_operator_review_summary_blocked_lanes=" + (ConvertTo-Json -Compress -Depth 8 @($summary.blockedLanes)))
Write-Host ("profit_operator_review_summary_required_evidence=" + (ConvertTo-Json -Compress -Depth 8 @($summary.requiredEvidence)))
Write-Host "profit_operator_review_summary_status=$($summary.status)"
Write-Host "profit_operator_review_summary_next_action=$($summary.nextAction)"
Write-Host "notAuthorization=read-only profit operator review summary only; does not deploy, restart, reload nginx, change production env, enable live trading, relax EntryDedup/DataFreshness/live policy, enable trailing scheduler, place orders, modify OCO, close positions, mutate DB/grid/fund/Earn/Telegram/exchange/external backfill state, or authorize strategy changes"
Write-Host "[profit-operator-review-summary] read-only check complete"

if ($RequireReady -and $summary.readyLaneCount -lt 1) {
    throw "Profit operator review summary has no ready lanes."
}
