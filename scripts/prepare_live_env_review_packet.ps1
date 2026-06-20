param(
    [string]$RuntimeProposalPath = "",
    [string]$BackgroundProposalPath = "",
    [string]$ProductionProposalPath = "",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-DocPath {
    param(
        [string]$Path,
        [string]$DefaultRelativePath
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        return (Join-Path (Split-Path -Parent $PSScriptRoot) $DefaultRelativePath)
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return (Join-Path (Get-Location) $Path)
}

function Get-DocText {
    param(
        [string]$Path,
        [string]$DisplayName
    )
    if (-not (Test-Path -LiteralPath $path)) {
        throw "Missing required document: $DisplayName"
    }
    Get-Content -Raw -LiteralPath $path
}

function Get-DotenvBlock {
    param(
        [string]$Text,
        [string]$Heading,
        [string]$Name
    )

    $pattern = '(?ms)^## ' + [regex]::Escape($Heading) + '\s+.*?```dotenv\s+(.*?)```'
    $match = [regex]::Match($Text, $pattern)
    if (-not $match.Success) {
        throw "Could not find dotenv block '$Heading' in $Name."
    }
    $match.Groups[1].Value
}

function Convert-DotenvBlock {
    param([string]$Block)

    $rows = @()
    foreach ($line in ($Block -split "`r?`n")) {
        $trimmed = $line.Trim()
        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }
        if ($trimmed -notmatch '^([A-Z0-9_]+)=(.+)$') {
            throw "Unsupported dotenv line in review proposal: $trimmed"
        }
        $rows += [pscustomobject]@{
            Key = $matches[1]
            Value = $matches[2].Trim()
            Line = $trimmed
        }
    }
    @($rows)
}

function Assert-SameSet {
    param(
        [string]$Name,
        [string[]]$Actual,
        [string[]]$Expected,
        [System.Collections.Generic.List[string]]$MissingRequirements
    )

    $actualText = @($Actual | Sort-Object -Unique) -join ","
    $expectedText = @($Expected | Sort-Object -Unique) -join ","
    if ($actualText -ne $expectedText) {
        $MissingRequirements.Add("$Name differs from expected set")
    }
}

function Assert-RequiredDocMarker {
    param(
        [string]$Name,
        [string]$Text,
        [string[]]$Markers,
        [System.Collections.Generic.List[string]]$MissingRequirements
    )

    foreach ($marker in $Markers) {
        if (-not $Text.Contains($marker)) {
            $MissingRequirements.Add("$Name missing required live packet marker: $marker")
        }
    }
}

$runtimePath = Resolve-DocPath -Path $RuntimeProposalPath -DefaultRelativePath "docs/live-runtime-evidence-env-proposal.md"
$backgroundPath = Resolve-DocPath -Path $BackgroundProposalPath -DefaultRelativePath "docs/live-background-automation-env-diff-proposal.md"
$productionPath = Resolve-DocPath -Path $ProductionProposalPath -DefaultRelativePath "docs/live-production-env-review-proposal.md"

$runtimeProposal = Get-DocText -Path $runtimePath -DisplayName "runtime evidence proposal"
$backgroundProposal = Get-DocText -Path $backgroundPath -DisplayName "background automation proposal"
$productionProposal = Get-DocText -Path $productionPath -DisplayName "production env review proposal"

$runtimeDiff = Convert-DotenvBlock (Get-DotenvBlock -Text $runtimeProposal -Heading "Proposed Evidence-Only Diff" -Name "runtime evidence proposal")
$backgroundDiff = Convert-DotenvBlock (Get-DotenvBlock -Text $backgroundProposal -Heading "Proposed Evidence-Only Diff" -Name "background automation proposal")
$productionEvidenceCandidate = Convert-DotenvBlock (Get-DotenvBlock -Text $productionProposal -Heading "Evidence-Only Candidate" -Name "production env review proposal")
$productionMustStayDisabled = Convert-DotenvBlock (Get-DotenvBlock -Text $productionProposal -Heading "Must Stay Disabled Until Live Approval" -Name "production env review proposal")

$expectedRuntimeDiff = @("TRADING_RUNTIME_EVIDENCE_ENABLED=true")
$expectedBackgroundDiff = @(
    "TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=false",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=false",
    "MARKET_WS_AUTO_SUBSCRIBE_ENABLED=false",
    "EVENT_SCAN_NOTIFICATION_ENABLED=false",
    "EXECUTION_EVENT_ENABLED=false",
    "TRADING_DAILY_TG_REPORT_ENABLED=false",
    "TRADING_AUTONOMOUS_DIGEST_ENABLED=false",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED=false",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED=false"
)
$expectedProductionCandidate = @("TRADING_RUNTIME_EVIDENCE_ENABLED=true")

$forbiddenTrueKeys = @(
    "TRADING_OKX_ENABLED",
    "TRADING_OCO_POLLER_ENABLED",
    "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED",
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED",
    "TRAILING_STOP_ENABLED",
    "POSITION_EXIT_MANAGER_ENABLED",
    "TRADING_GRID_ENABLED",
    "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED",
    "TRADING_FUNDING_ARB_ENABLED",
    "OKX_EARN_TOPUP_ENABLED",
    "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED",
    "TRADING_EXPLORATION_LOOP_PRODUCTION_ENABLED",
    "TRADING_EXPLORATION_ROLLOUT_AUTO_ENABLED",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_PRODUCTION_PROMOTION",
    "TRADING_EXPLORATION_ROLLOUT_ALLOW_CAP_INCREASE",
    "TRADING_AUTONOMOUS_DIGEST_TELEGRAM_ENABLED",
    "TRADING_LIVE_SIGNAL_RETRY_NOTIFICATION_ENABLED",
    "EVENT_SCAN_NOTIFICATION_ENABLED",
    "EXECUTION_EVENT_ENABLED",
    "TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED"
)

$missingRequirements = [System.Collections.Generic.List[string]]::new()
Assert-SameSet -Name "runtime evidence proposed diff" -Actual @($runtimeDiff.Line) -Expected $expectedRuntimeDiff -MissingRequirements $missingRequirements
Assert-SameSet -Name "production evidence-only candidate" -Actual @($productionEvidenceCandidate.Line) -Expected $expectedProductionCandidate -MissingRequirements $missingRequirements
Assert-SameSet -Name "background automation proposed diff" -Actual @($backgroundDiff.Line) -Expected $expectedBackgroundDiff -MissingRequirements $missingRequirements
Assert-RequiredDocMarker `
    -Name "runtime evidence proposal" `
    -Text $runtimeProposal `
    -Markers @(
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady",
        "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
        "packet_missing_requirements=[]",
        "live_review_packet_allowed=true",
        "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    ) `
    -MissingRequirements $missingRequirements
Assert-RequiredDocMarker `
    -Name "background automation proposal" `
    -Text $backgroundProposal `
    -Markers @(
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady",
        "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
        "packet_missing_requirements=[]",
        "live_review_packet_allowed=true",
        "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    ) `
    -MissingRequirements $missingRequirements
Assert-RequiredDocMarker `
    -Name "production env review proposal" `
    -Text $productionProposal `
    -Markers @(
        ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear",
        ".\scripts\smoke_live_readiness_bundle_ssh.ps1",
        ".\scripts\prepare_live_review_packet_ssh.ps1 -RequireReady",
        "packet_status=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED",
        "packet_missing_requirements=[]",
        "live_review_packet_allowed=true",
        "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
    ) `
    -MissingRequirements $missingRequirements

$runtimeTrueCandidates = @($runtimeDiff | Where-Object { $_.Value -eq "true" } | ForEach-Object { $_.Key })
$backgroundTrueCandidates = @($backgroundDiff | Where-Object { $_.Value -eq "true" } | ForEach-Object { $_.Key })
$productionTrueCandidates = @($productionEvidenceCandidate | Where-Object { $_.Value -eq "true" } | ForEach-Object { $_.Key })
$mustStayDisabledTrueCandidates = @($productionMustStayDisabled | Where-Object { $_.Value -eq "true" } | ForEach-Object { $_.Key })
$forbiddenTrueCandidates = @(
    @($runtimeTrueCandidates + $backgroundTrueCandidates + $productionTrueCandidates + $mustStayDisabledTrueCandidates) |
        Where-Object { $forbiddenTrueKeys -contains $_ } |
        Sort-Object -Unique
)

foreach ($row in $backgroundDiff) {
    if ($row.Value -ne "false") {
        $missingRequirements.Add("background automation candidate contains non-false value: $($row.Line)")
    }
}
foreach ($row in $productionMustStayDisabled) {
    if ($row.Value -ne "false") {
        $missingRequirements.Add("must-stay-disabled candidate contains non-false value: $($row.Line)")
    }
}
if ($forbiddenTrueCandidates.Count -gt 0) {
    $missingRequirements.Add("forbidden true candidates are present")
}

$packetReady = $missingRequirements.Count -eq 0

Write-Host "[live-env-review-packet-preflight] local review-only gate"
Write-Host "scope=LOCAL_DOCS_ONLY; reads review proposal docs only; no SSH, production env, deploy, restart, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, or policy state changed."
Write-Host "runtime_proposal_path=$runtimePath"
Write-Host "background_proposal_path=$backgroundPath"
Write-Host "production_proposal_path=$productionPath"
Write-Host ("runtime_evidence_candidate=" + (ConvertTo-Json -Compress @($runtimeDiff.Line)))
Write-Host ("background_disable_candidates=" + (ConvertTo-Json -Compress @($backgroundDiff.Line)))
Write-Host ("production_evidence_only_candidate=" + (ConvertTo-Json -Compress @($productionEvidenceCandidate.Line)))
Write-Host ("forbidden_true_candidates=" + (ConvertTo-Json -Compress @($forbiddenTrueCandidates)))
Write-Host ("env_review_missing_requirements=" + (ConvertTo-Json -Compress @($missingRequirements)))
Write-Host "notAuthorization=local review packet preflight only; does not authorize production env mutation, live trading, scheduler enablement, order/OCO/grid/fund/Earn/Telegram/exchange mutations, DB changes, external backfill/import, deploy, restart, or policy relaxation"

if ($packetReady) {
    Write-Host "env_review_packet_status=READY_FOR_OPERATOR_ENV_REVIEW_NOT_AUTHORIZED"
    Write-Host "env_review_next_action=Attach this local preflight plus fresh read-only SSH smokes to a separate operator env-change request; do not apply changes from this output."
} else {
    Write-Host "env_review_packet_status=NOT_READY"
    Write-Host "env_review_next_action=Fix the listed proposal drift before drafting an operator env-change request."
}

Write-Host "[live-env-review-packet-preflight] local check complete"

if ($RequireReady -and -not $packetReady) {
    throw "Live env review packet preflight is not ready: $(@($missingRequirements) -join '; ')"
}
