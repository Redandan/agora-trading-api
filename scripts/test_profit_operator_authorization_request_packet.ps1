Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Pattern
    )

    if ($Text -notmatch $Pattern) {
        throw "$Name missing pattern: $Pattern"
    }
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) { return "" }
    return $line.Substring($Prefix.Length).Trim()
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_operator_authorization_request_packet.ps1"
$boardPath = Join-Path $PSScriptRoot "prepare_profit_operator_next_action_board.ps1"
$auditPath = Join-Path $PSScriptRoot "prepare_profit_live_blocker_audit_packet.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$boardText = Get-Content -Raw -LiteralPath $boardPath
$auditText = Get-Content -Raw -LiteralPath $auditPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[profit-operator-authorization-request] read-only packet",
        "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET",
        "READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE",
        "BLOCKED_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE_MISSING",
        "BLOCKED_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE",
        "AWAIT_SEPARATE_OPERATOR_REVIEW_AUTHORIZATION",
        "REFRESH_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE",
        "RESOLVE_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_BLOCKERS",
        "profit_operator_authorization_request_status",
        "profit_operator_authorization_request_ready",
        "profit_operator_authorization_request_next_authorization_required",
        "profit_operator_authorization_request_review_queue",
        "profit_operator_authorization_request_authorization_sequence",
        "profit_operator_authorization_request_authorization_lines",
        "TRAILING_STOP_DRY_RUN_REVIEW_AUTHORIZATION",
        "DATAFRESHNESS_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_AUTHORIZATION",
        "profit_live_readiness_conclusion",
        "livePolicyChangeAllowed = `$false",
        "schedulerEnablementAllowed = `$false",
        "orderAllowed = `$false",
        "positionOrOcoMutationAllowed = `$false",
        "deployOrEnvChangeAllowed = `$false",
        "telegramSendAllowed = `$false",
        "dbGridFundEarnExchangeMutationAllowed = `$false",
        "live_policy_change_allowed=false",
        "scheduler_enablement_allowed=false",
        "order_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "deploy_or_env_change_allowed=false",
        "telegram_send_allowed=false",
        "db_grid_fund_earn_exchange_mutation_allowed=false",
        "notAuthorization=read-only profit operator authorization request only",
        "RequireReady"
    )) {
    Assert-Contains -Name "profit operator authorization request script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @("PROFIT_OPERATOR_NEXT_ACTION_BOARD", "profit_operator_next_action_audit_review_queue")) {
    Assert-Contains -Name "next-action board supports authorization request" -Text $boardText -Pattern ([regex]::Escape($marker))
}
foreach ($marker in @("PROFIT_LIVE_BLOCKER_AUDIT_PACKET", "liveReadinessConclusion")) {
    Assert-Contains -Name "live blocker audit supports authorization request" -Text $auditText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram") {
    throw "profit operator authorization request must not write files or invoke SSH/MCP/trading mutation calls"
}

foreach ($marker in @(
        "prepare_profit_operator_authorization_request_packet.ps1",
        "PROFIT_OPERATOR_AUTHORIZATION_REQUEST_PACKET",
        "profit_operator_authorization_request_status",
        "profit_operator_authorization_request_next_authorization_required",
        "READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE",
        "live_policy_change_allowed=false",
        "order_allowed=false",
        "read-only"
    )) {
    Assert-Contains -Name "docs mention profit operator authorization request" -Text $docsText -Pattern ([regex]::Escape($marker))
}

$powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
if ($null -eq $powerShell) { $powerShell = Get-Command powershell -ErrorAction SilentlyContinue }
if ($null -eq $powerShell) { throw "Unable to find powershell or pwsh for profit operator authorization request test" }

$tempBoardLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-auth-request-board-" + [guid]::NewGuid().ToString("N") + ".log")
$tempAuditLog = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-auth-request-audit-" + [guid]::NewGuid().ToString("N") + ".log")

try {
    $auditCounts = [pscustomobject]@{
        laneCount = 3
        readyReviewCount = 2
        noActionCount = 1
        blockedCount = 0
        missingEvidenceCount = 0
        staleEvidenceCount = 0
        incompleteEvidenceCount = 0
    }
    $boardPacket = [pscustomobject]@{
        packetType = "PROFIT_OPERATOR_NEXT_ACTION_BOARD"
        status = "READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE"
        symbol = "BTCUSDT"
        sourceAuditStatus = "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT"
        auditLiveReadinessConclusion = "NOT_READY_FOR_LIVE_ENABLEMENT"
        sourcePriorityMatrixFreshnessStatus = "FRESH"
        sourcePriorityMatrixAgeMinutes = 12
        sourcePriorityBoardMaxAgeMinutes = 180
        auditCounts = $auditCounts
        primaryFocus = "trailing-stop-dry-run-operator-review"
        auditReviewQueue = @(
            [pscustomobject]@{
                rank = 1
                lane = "trailing-stop-dry-run"
                decisionFocus = "TRAILING_STOP_DRY_RUN_REVIEW"
                sourceStatus = "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE"
                classification = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
                liveReady = $false
                missingRequirements = @()
                nextAction = "Attach trailing-stop dry-run decision packet to operator review."
            },
            [pscustomobject]@{
                rank = 4
                lane = "data-freshness-collector-activation"
                decisionFocus = "DATAFRESHNESS_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW"
                sourceStatus = "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE"
                classification = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
                liveReady = $false
                missingRequirements = @()
                nextAction = "Prepare separate evidence-only collector activation review."
            }
        )
        missingRequirements = @()
    }
    $auditPacket = [pscustomobject]@{
        packetType = "PROFIT_LIVE_BLOCKER_AUDIT_PACKET"
        status = "BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT"
        symbol = "BTCUSDT"
        liveReadinessConclusion = "NOT_READY_FOR_LIVE_ENABLEMENT"
        laneCount = 3
        readyReviewCount = 2
        noActionCount = 1
        blockedCount = 0
        missingEvidenceCount = 0
        staleEvidenceCount = 0
        incompleteEvidenceCount = 0
        lanes = @(
            [pscustomobject]@{
                lane = "trailing-stop-dry-run"
                sourceStatus = "READY_FOR_TRAILING_DRY_RUN_OPERATOR_DECISION_NOT_LIVE"
                classification = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
                readyForOperatorReview = $true
                noActionRequired = $false
                liveReady = $false
                missingRequirements = @()
                nextAction = "Attach trailing-stop dry-run decision packet to operator review."
            },
            [pscustomobject]@{
                lane = "data-freshness-collector-activation"
                sourceStatus = "READY_FOR_DATAFRESHNESS_COLLECTOR_ACTIVATION_OPERATOR_DECISION_NOT_LIVE"
                classification = "READY_FOR_OPERATOR_REVIEW_NOT_LIVE"
                readyForOperatorReview = $true
                noActionRequired = $false
                liveReady = $false
                missingRequirements = @()
                nextAction = "Prepare separate evidence-only collector activation review."
            },
            [pscustomobject]@{
                lane = "governance-relaxation"
                sourceStatus = "NO_GOVERNANCE_RELAXATION_CANDIDATES_NOT_LIVE"
                classification = "NO_ACTION_REQUIRED_NOT_LIVE"
                readyForOperatorReview = $false
                noActionRequired = $true
                liveReady = $false
                missingRequirements = @()
                nextAction = "No governance relaxation candidate is present."
            }
        )
        primaryBlockers = @("separate explicit operator authorization is required before any live/order/scheduler/env/Telegram/policy mutation")
        missingRequirements = @("separate explicit operator authorization is required before any live/order/scheduler/env/Telegram/policy mutation")
    }

    Set-Content -LiteralPath $tempBoardLog -Encoding UTF8 -Value @(
        "[profit-operator-next-action-board] read-only board",
        ("profit_operator_next_action_board_packet=" + (ConvertTo-Json -Compress -Depth 12 $boardPacket)),
        "profit_operator_next_action_board_status=READY_FOR_PROFIT_OPERATOR_NEXT_ACTION_REVIEW_NOT_LIVE"
    )
    Set-Content -LiteralPath $tempAuditLog -Encoding UTF8 -Value @(
        "[profit-live-blocker-audit-packet] read-only audit",
        ("profit_live_blocker_audit_packet=" + (ConvertTo-Json -Compress -Depth 12 $auditPacket)),
        "profit_live_blocker_audit_status=BLOCKED_NOT_READY_FOR_LIVE_ENABLEMENT"
    )

    $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -BoardLogPath $tempBoardLog -AuditLogPath $tempAuditLog -MaxAgeMinutes 180 -RequireReady 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($output | Out-String -Width 4096)
    if ($exitCode -ne 0) {
        throw "profit operator authorization request ready case failed:`n$text"
    }

    foreach ($marker in @(
            "[profit-operator-authorization-request] read-only packet",
            "profit_operator_authorization_request_status=READY_FOR_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_NOT_LIVE",
            "profit_operator_authorization_request_ready=true",
            "profit_operator_authorization_request_next_authorization_required=TRAILING_STOP_DRY_RUN_REVIEW_AUTHORIZATION",
            "TRAILING_STOP_DRY_RUN_REVIEW_AUTHORIZATION",
            "DATAFRESHNESS_EVIDENCE_COLLECTOR_ACTIVATION_REVIEW_AUTHORIZATION",
            "profit_live_readiness_conclusion=NOT_READY_FOR_LIVE_ENABLEMENT",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "deploy_or_env_change_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only profit operator authorization request only"
        )) {
        Assert-Contains -Name "profit operator authorization request ready output" -Text $text -Pattern ([regex]::Escape($marker))
    }

    $packetJson = Get-LastPrefixedValue -Text $text -Prefix "profit_operator_authorization_request_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if (-not [bool]$packet.authorizationRequestReady) { throw "authorization request packet should be ready" }
    if (@($packet.reviewQueue).Count -ne 2) { throw "authorization request packet should have 2 review queue items" }
    if ([bool]$packet.orderAllowed -or [bool]$packet.livePolicyChangeAllowed -or [bool]$packet.deployOrEnvChangeAllowed) {
        throw "authorization request packet must keep mutation flags false"
    }

    (Get-Item -LiteralPath $tempBoardLog).LastWriteTime = (Get-Date).AddMinutes(-20)
    $staleOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -BoardLogPath $tempBoardLog -AuditLogPath $tempAuditLog -MaxAgeMinutes 5 2>&1
    $staleExitCode = $LASTEXITCODE
    $staleText = ($staleOutput | Out-String -Width 4096)
    if ($staleExitCode -ne 0) {
        throw "profit operator authorization request stale non-require case should not fail:`n$staleText"
    }
    foreach ($marker in @(
            "profit_operator_authorization_request_status=BLOCKED_PROFIT_OPERATOR_AUTHORIZATION_REQUEST_EVIDENCE_MISSING",
            "profit_operator_authorization_request_ready=false",
            "profit operator next-action board log fresh"
        )) {
        Assert-Contains -Name "profit operator authorization request stale output" -Text $staleText -Pattern ([regex]::Escape($marker))
    }

    $failedRequireReady = $false
    $staleRequireOutput = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $scriptPath -BoardLogPath $tempBoardLog -AuditLogPath $tempAuditLog -MaxAgeMinutes 5 -RequireReady 2>&1
    $staleRequireExitCode = $LASTEXITCODE
    $staleRequireText = ($staleRequireOutput | Out-String -Width 4096)
    if ($staleRequireExitCode -ne 0) { $failedRequireReady = $true }
    if (-not $failedRequireReady) {
        throw "profit operator authorization request stale RequireReady case did not fail"
    }
    Assert-Contains -Name "profit operator authorization request stale require output" -Text $staleRequireText -Pattern "Profit operator authorization request is not ready"
} finally {
    Remove-Item -LiteralPath $tempBoardLog -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $tempAuditLog -Force -ErrorAction SilentlyContinue
}

Write-Host "[profit-operator-authorization-request-test] OK"
