Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
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
$scriptPath = Join-Path $PSScriptRoot "prepare_profit_grid10_order_path_handoff.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"
$profitPlanPath = Join-Path $repoRoot "docs/profit-execution-plan.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
foreach ($marker in @(
        "PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET",
        "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION",
        "BLOCKED_PROFIT_GRID10_ORDER_PATH_HANDOFF_REQUIREMENTS_MISSING",
        "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH",
        "GRID10_TREND_REGIME_OVERRIDE_REVIEW",
        "GRID10_CAPITAL_CAP_OVERRIDE_REVIEW",
        "GRID10_ENV_DIFF_REVIEW",
        "GRID10_CREATEGRID_REVIEW",
        "TRADING_OKX_ENABLED=true",
        "TRADING_GRID_ENABLED=true",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false",
        "prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
        "prepare_grid_open_blocker_priority_board_ssh.ps1",
        "prepare_grid_open_authorization_bundle_ssh.ps1",
        "grid10_execution_now_allowed=false",
        "grid10_env_deploy_request_allowed=false",
        "productionEnvChangeAllowed = `$false",
        "deployAllowed = `$false",
        "createGridAllowed = `$false",
        "gridMutationAllowed = `$false",
        "orderAllowed = `$false",
        "telegramSendAllowed = `$false",
        "read-only profit grid10 order path handoff packet only"
    )) {
    Assert-Contains -Name "profit grid10 order path handoff script marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

if ($scriptText -match "Set-Content|Add-Content|Out-File|tools/call|createGrid\(|pauseGrid\(|resumeGrid\(|closeGrid\(|placeOrder|modifyOco|cancelOco|sendTelegram" -or $scriptText -match "(?m)^\s*ssh\s") {
    throw "profit grid10 order path handoff must not write files or invoke raw SSH/MCP/trading mutation calls"
}

$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
    Get-Content -Raw -LiteralPath $profitPlanPath
) -join "`n"
foreach ($marker in @(
        "prepare_profit_grid10_order_path_handoff.ps1",
        "PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET",
        "READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION",
        "grid10_exact_authorization_texts",
        "grid10_execution_now_allowed=false",
        "grid10_env_deploy_request_allowed=false",
        "order_allowed=false"
    )) {
    Assert-Contains -Name "profit grid10 order path handoff docs marker" -Text $docsText -Pattern ([regex]::Escape($marker))
}
Assert-Contains -Name "verify local runs profit grid10 order path handoff test" -Text (Get-Content -Raw -LiteralPath $verifyPath) -Pattern "test_profit_grid10_order_path_handoff.ps1"

$tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("profit-grid10-order-path-handoff-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
try {
    $aggressiveLog = Join-Path $tempDir "aggressive.log"
    $bundleLog = Join-Path $tempDir "bundle.log"
    $gridConfirmationText = "I explicitly authorize SEPARATE_GRID10_ORDER_PATH_REVIEW for BTCUSDT with existing-grid activation risk accepted, TRADING_OKX_ENABLED=true reviewed separately, and no createGrid/order execution until the grid authorization bundle and post-env verification are current."
    $gridOption = [pscustomobject]@{
        optionId = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
        priority = 2
        risk = "MEDIUM_HIGH"
        recommendedNow = $false
        status = "SEPARATE_GRID_AUTHORIZATION_AND_POST_ENV_VERIFICATION_REQUIRED"
        maxCapitalUsdt = 10
        proposedEnvDiff = @(
            "TRADING_OKX_ENABLED=true",
            "TRADING_GRID_ENABLED=true",
            "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
            "GRID_RECOVERY_ENABLED=false",
            "OKX_EARN_TOPUP_ENABLED=false",
            "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false",
            "EVENT_SCAN_NOTIFICATION_ENABLED=false",
            "EXECUTION_EVENT_ENABLED=false"
        )
        riskAcceptanceConditions = @("operator accepts existing active Grid #10 order-path activation risk")
        requiredBeforeExecution = @("fresh grid authorization bundle", "post-env read-only verification", "separate createGrid authorization")
        postEnvReadOnlyVerificationCommands = @(".\scripts\verify_split_acceptance_ssh.ps1")
        killSwitchEnvDiff = @("TRADING_OKX_ENABLED=false", "TRADING_GRID_ENABLED=false")
        rollbackCommands = @("apply grid kill switch env diff")
        confirmationText = $gridConfirmationText
    }
    $aggressivePacket = [pscustomobject]@{
        packetType = "PROFIT_AGGRESSIVE_ACTIVATION_OPERATOR_PACKET"
        scope = "READ_ONLY"
        status = "READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
        decision = "REVIEW_HIGH_RISK_MICRO_PROBE_OR_EVIDENCE_ACCELERATOR_SEPARATELY"
        selectedAggressivePath = "EVIDENCE_ONLY_ACCELERATOR"
        mostAggressiveOrderCapableCandidate = "GRID10_EXISTING_ACTIVE_GRID_ORDER_PATH"
        orderCapableExecutionNowAllowed = $false
        orderAllowed = $false
        deployOrEnvChangeAllowed = $false
        livePolicyChangeAllowed = $false
        aggressiveOptions = @($gridOption)
    }
    $bundlePacket = [pscustomobject]@{
        packetType = "GRID_OPEN_AUTHORIZATION_BUNDLE_PACKET"
        scope = "READ_ONLY"
        status = "READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION"
        decision = "PREPARE_SEPARATE_GRID_OPEN_OPERATOR_AUTHORIZATIONS"
        symbol = "BTCUSDT"
        readinessSummary = [pscustomobject]@{
            trendOverrideReviewReady = $true
            trendGate = "BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE"
            trendGateClearanceAccepted = $false
            trendLaneReady = $true
            capitalOverrideReviewReady = $true
            envDiffReviewReady = $true
            createGridPreflightEvidenceComplete = $true
            candidatePlanComplete = $true
            bundleReadyForOperatorReview = $true
            existingActiveGridOrderPathActivationRisk = $false
        }
        authorizationLanes = @(
            [pscustomobject]@{ lane = "trend-regime-override"; status = "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION"; requiredAuthorization = "separate written trend-regime override naming current trend and trendPct" },
            [pscustomobject]@{ lane = "capital-cap-override"; status = "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION"; requiredAuthorization = "separate written capital-cap override naming candidateCapitalUsdt and effectiveReviewCapitalCapUsdt" },
            [pscustomobject]@{ lane = "production-env-diff"; status = "READY_FOR_SEPARATE_OPERATOR_REVIEW_NOT_MUTATION"; requiredAuthorization = "separate written production env diff authorization for TRADING_OKX_ENABLED=true and TRADING_GRID_ENABLED=true" },
            [pscustomobject]@{ lane = "createGrid"; status = "EVIDENCE_COMPLETE_BUT_AUTHORIZATION_PENDING_NOT_MUTATION"; requiredAuthorization = "separate written createGrid authorization naming reviewedCreateGridInputs after post-env verification" }
        )
        remainingExecutionBlockers = @(
            "OPERATOR_TREND_REGIME_OVERRIDE_REQUIRED_OR_TREND_GATE_CLEARANCE",
            "OPERATOR_CAPITAL_CAP_OVERRIDE_REQUIRED",
            "OPERATOR_PRODUCTION_ENV_DIFF_AUTHORIZATION_REQUIRED",
            "DEPLOY_RESTART_AND_READ_ONLY_POST_ENV_VERIFICATION_REQUIRED",
            "OPERATOR_CREATEGRID_AUTHORIZATION_REQUIRED"
        )
        missingEvidence = @()
        bundleBlockers = @()
        reviewedCreateGridInputs = [pscustomobject]@{
            symbol = "BTCUSDT"
            gridCount = 2
            perLevelUsdt = 5
            candidateCapitalUsdt = 10
            stopOutPct = 5
            candidateHalfWidthPct = 10
            replayScore = 80
        }
        capitalOverrideRequest = [pscustomobject]@{
            candidateCapitalUsdt = 10
            effectiveReviewCapitalCapUsdt = 5
            requiredCapRaiseUsdt = 5
            requestedMaximumReviewCapitalCapUsdt = 10
        }
        proposedSeparateEnvDiff = @("TRADING_OKX_ENABLED=true", "TRADING_GRID_ENABLED=true")
        requiredOperatorAuthorizationSequence = @(
            "1. trend-regime override or fresh trend clearance",
            "2. capital-cap override if candidateCapitalUsdt remains above effectiveReviewCapitalCapUsdt",
            "3. production env diff authorization and deploy/restart",
            "4. post-env read-only split acceptance plus refreshed grid open packets",
            "5. createGrid authorization with freshly reviewed inputs"
        )
        postEnvReadOnlyVerification = @("verify split acceptance", "refresh grid open authorization bundle")
        gridOpenAuthorizationBundleReady = $true
        trendOverrideAllowed = $false
        capitalOverrideAllowed = $false
        productionEnvChangeAllowed = $false
        deployAllowed = $false
        createGridAllowed = $false
        gridOpenAllowed = $false
        gridMutationAllowed = $false
        schedulerEnablementAllowed = $false
        orderAllowed = $false
        ocoMutationAllowed = $false
        telegramSendAllowed = $false
    }

    @(
        ("profit_aggressive_activation_packet=" + (ConvertTo-Json -Compress -Depth 14 $aggressivePacket)),
        "profit_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE"
    ) | Set-Content -LiteralPath $aggressiveLog -Encoding UTF8
    @(
        ("grid_open_authorization_bundle_packet=" + (ConvertTo-Json -Compress -Depth 18 $bundlePacket)),
        "grid_open_authorization_bundle_status=READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION"
    ) | Set-Content -LiteralPath $bundleLog -Encoding UTF8

    $readyOutput = & $scriptPath `
        -AggressivePacketLogPath $aggressiveLog `
        -GridAuthorizationBundleLogPath $bundleLog `
        -GridCount 2 `
        -PerLevelUsdt 5 `
        -StopOutPct 5 `
        -CandidateHalfWidthPct 10 `
        -MaxCapitalUsdt 10 `
        -AllowDirtyLocalWorktreeForReplay `
        -RequireReady *>&1
    $readyText = $readyOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_order_path_handoff_packet=",
            '"packetType":"PROFIT_GRID10_ORDER_PATH_HANDOFF_PACKET"',
            "profit_grid10_order_path_handoff_status=READY_FOR_PROFIT_GRID10_ORDER_PATH_OPERATOR_REVIEW_NOT_MUTATION",
            "profit_grid10_order_path_handoff_decision=REVIEW_SEPARATE_GRID10_ORDER_PATH_AUTHORIZATIONS_BUT_DO_NOT_DEPLOY",
            "source_aggressive_activation_status=READY_FOR_AGGRESSIVE_ACTIVATION_OPERATOR_REVIEW_NOT_LIVE",
            "source_grid_authorization_bundle_status=READY_FOR_GRID_OPEN_OPERATOR_AUTHORIZATION_BUNDLE_NOT_MUTATION",
            "grid10_order_path_bundle_ready=true",
            "grid10_candidate_capital_usdt=10",
            "grid10_effective_review_capital_cap_usdt=5",
            "grid10_grid_count=2",
            "grid10_per_level_usdt=5",
            "grid10_trend_gate=BLOCKED_WAIT_SIDEWAYS_OR_OPERATOR_TREND_OVERRIDE",
            "GRID10_TREND_REGIME_OVERRIDE_REVIEW",
            "GRID10_CAPITAL_CAP_OVERRIDE_REVIEW",
            "GRID10_ENV_DIFF_REVIEW",
            "GRID10_CREATEGRID_REVIEW",
            "grid10_post_env_read_only_verification=.*prepare_grid_post_env_read_only_verification_bundle_ssh.ps1",
            "grid10_kill_switch_env_diff=.*TRADING_OKX_ENABLED=false",
            "grid10_order_path_handoff_ready=true",
            "grid10_execution_now_allowed=false",
            "grid10_env_deploy_request_allowed=false",
            "production_env_change_allowed=false",
            "deploy_allowed=false",
            "live_policy_change_allowed=false",
            "scheduler_enablement_allowed=false",
            "order_allowed=false",
            "position_or_oco_mutation_allowed=false",
            "create_grid_allowed=false",
            "grid_mutation_allowed=false",
            "telegram_send_allowed=false",
            "db_grid_fund_earn_exchange_mutation_allowed=false",
            "notAuthorization=read-only profit grid10 order path handoff packet only"
        )) {
        Assert-Contains -Name "ready profit grid10 order path handoff replay" -Text $readyText -Pattern $marker
    }
    if ($readyText -match "Could not resolve hostname|Permission denied|remote command failed|mcp_write_status=OK") {
        throw "ready profit grid10 order path handoff replay unexpectedly invoked SSH or MCP write:`n$readyText"
    }

    $packetJson = Get-LastPrefixedValue -Text $readyText -Prefix "profit_grid10_order_path_handoff_packet="
    $packet = $packetJson | ConvertFrom-Json -ErrorAction Stop
    if ([bool]$packet.orderAllowed -or [bool]$packet.deployAllowed -or [bool]$packet.productionEnvChangeAllowed -or [bool]$packet.grid10ExecutionNowAllowed) {
        throw "profit grid10 order path handoff must keep execution/deploy flags false"
    }
    if ([decimal]$packet.candidateCapitalUsdt -ne 10) {
        throw "profit grid10 order path handoff should preserve candidateCapitalUsdt=10"
    }
    if (@(@($packet.exactOperatorAuthorizationTexts) -match "GRID10_CREATEGRID_REVIEW").Count -eq 0) {
        throw "profit grid10 order path handoff should expose final createGrid review text"
    }

    $blockedBundle = $bundlePacket.PSObject.Copy()
    $blockedBundle.gridOpenAuthorizationBundleReady = $false
    $blockedBundle.bundleBlockers = @("CAPITAL_OVERRIDE_REVIEW_NOT_READY")
    $blockedLog = Join-Path $tempDir "bundle-blocked.log"
    @(
        ("grid_open_authorization_bundle_packet=" + (ConvertTo-Json -Compress -Depth 18 $blockedBundle)),
        "grid_open_authorization_bundle_status=BLOCKED_GRID_OPEN_AUTHORIZATION_BUNDLE_NOT_MUTATION"
    ) | Set-Content -LiteralPath $blockedLog -Encoding UTF8

    $blockedOutput = & $scriptPath `
        -AggressivePacketLogPath $aggressiveLog `
        -GridAuthorizationBundleLogPath $blockedLog `
        -AllowDirtyLocalWorktreeForReplay *>&1
    $blockedText = $blockedOutput -join "`n"
    foreach ($marker in @(
            "profit_grid10_order_path_handoff_status=BLOCKED_PROFIT_GRID10_ORDER_PATH_HANDOFF_REQUIREMENTS_MISSING",
            "grid open authorization bundle blockers empty",
            "grid10_order_path_handoff_ready=false",
            "order_allowed=false"
        )) {
        Assert-Contains -Name "blocked profit grid10 order path handoff replay" -Text $blockedText -Pattern ([regex]::Escape($marker))
    }
} finally {
    if (Test-Path -LiteralPath $tempDir) {
        Remove-Item -LiteralPath $tempDir -Recurse -Force
    }
}

Write-Host "[profit-grid10-order-path-handoff-test] OK"
