Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_governance_relaxation_preflight_review_packet.ps1"
$sourcePath = Join-Path $PSScriptRoot "prepare_governance_relaxation_review_packet_ssh.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$sourceText = Get-Content -Raw -LiteralPath $sourcePath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[governance-relaxation-preflight-review-packet] read-only packet",
        "scope=READ_ONLY",
        "prepare_governance_relaxation_review_packet_ssh.ps1",
        "GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET",
        "READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE",
        "PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW",
        "PREPARE_REVIEW_ONLY_GOVERNANCE_SHADOW_REVIEW",
        "governance_relaxation_preflight_review_packet",
        "governance_relaxation_preflight_status",
        "governance_relaxation_review_allowed=true",
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "entry_dedup_policy_change_allowed=false",
        "data_freshness_policy_change_allowed=false",
        "staged_add_execution_allowed=false",
        "scheduler_enablement_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "order_allowed=false",
        "telegram_send_allowed=false",
        "notAuthorization=read-only governance relaxation preflight review packet only",
        "RequireReady"
    )) {
    Assert-Contains -Name "governance relaxation preflight marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "GOVERNANCE_RELAXATION_REVIEW",
        "REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        "READY_FOR_GOVERNANCE_SHADOW_REVIEW_NOT_LIVE",
        "governance_relaxation_review_packet"
    )) {
    Assert-Contains -Name "governance relaxation source supports preflight" -Text $sourceText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "prepare_governance_relaxation_preflight_review_packet.ps1",
        "GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET",
        "governance_relaxation_preflight_review_packet",
        "Governance relaxation preflight review packet",
        "READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE"
    )) {
    Assert-Contains -Name "docs mention governance relaxation preflight" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$tempLogPath = Join-Path ([System.IO.Path]::GetTempPath()) ("governance-relaxation-preflight-" + [guid]::NewGuid().ToString("N") + ".log")
try {
    $sourcePacket = [pscustomobject]@{
        packetType = "GOVERNANCE_RELAXATION_REVIEW"
        status = "REVIEW_REQUIRED_NOT_POLICY_CHANGE"
        symbol = "BTCUSDT"
        signalPolicyClear = "false"
        governanceMode = "TOO_STRICT"
        missedOpportunityStatus = "WARN"
        relaxationCandidateCount = 2
        relaxationCandidates = @(
            [pscustomobject]@{ blocker = "NO_BUY_REASON_FILTER_TOO_STRICT"; detail = "review only" }
        )
        shadowGovernanceReviewAllowed = $false
        livePolicyChangeAllowed = $false
        tinyLiveOrderAllowed = $false
        missingRequirements = @("signalPolicyClear=true before governance relaxation review can be marked ready")
        nextAction = "Review candidates but keep policy unchanged."
    }
    Set-Content -LiteralPath $tempLogPath -Encoding UTF8 -Value @(
        "[governance-relaxation-review-packet] read-only packet",
        "scope=READ_ONLY; runs smoke_signal_correctness_ssh.ps1 only",
        "governance_relaxation_review_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE",
        ("governance_relaxation_review_packet=" + (ConvertTo-Json -Compress -Depth 8 $sourcePacket)),
        "live_policy_change_allowed=false",
        "tiny_live_order_allowed=false",
        "notAuthorization=read-only governance relaxation review packet only"
    )

    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) { $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue }
    if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for governance relaxation preflight test" }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -ReviewLogPath $tempLogPath -RequireReady 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -ne 0) {
        throw "Governance relaxation preflight failed temp-log reuse:`n$text"
    }
    foreach ($marker in @(
            "source_review_packet_status=REVIEW_REQUIRED_NOT_POLICY_CHANGE",
            "source_signal_policy_clear=false",
            "source_governance_mode=TOO_STRICT",
            "source_missed_opportunity_status=WARN",
            "source_relaxation_candidate_count=2",
            "source_shadow_governance_review_allowed=false",
            "governance_relaxation_preflight_decision=PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW",
            "governance_relaxation_preflight_status=READY_FOR_GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_NOT_LIVE",
            '"packetType":"GOVERNANCE_RELAXATION_PREFLIGHT_REVIEW_PACKET"',
            '"preflightDecision":"PREPARE_BLOCKED_GOVERNANCE_RELAXATION_REVIEW"',
            '"governanceRelaxationReviewAllowed":true',
            '"livePolicyChangeAllowed":false',
            '"tinyLiveOrderAllowed":false',
            '"entryDedupPolicyChangeAllowed":false',
            '"dataFreshnessPolicyChangeAllowed":false',
            '"telegramSendAllowed":false',
            "live_policy_change_allowed=false",
            "tiny_live_order_allowed=false",
            "order_allowed=false",
            "telegram_send_allowed=false",
            "notAuthorization=read-only governance relaxation preflight review packet only"
        )) {
        Assert-Contains -Name "governance relaxation preflight temp log reuse" -Text $text -Pattern ([regex]::Escape($marker))
    }
    if ($text -match "child_start|Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "Governance relaxation preflight unexpectedly invoked SSH or a fresh child run:`n$text"
    }
} finally {
    if (Test-Path -LiteralPath $tempLogPath) { Remove-Item -LiteralPath $tempLogPath -Force }
}

Write-Host "[governance-relaxation-preflight-review-packet-test] OK"
