param(
    [Parameter(Mandatory = $true)]
    [string]$LiveAuditLog,
    [string]$RuntimeLogSmokeLog = "",
    [string]$GridPostEnvBundleLog = "",
    [string]$TrailingPostOptInLog = "",
    [string]$Symbol = "BTCUSDT",
    [switch]$RequireReady
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Read-RequiredLog {
    param([string]$Path, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$Name is required."
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Name not found: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Read-OptionalLog {
    param([string]$Path, [string]$Name)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Name not found: $Path"
    }
    return Get-Content -Raw -LiteralPath $Path
}

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return ""
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Convert-JsonArrayOrEmpty {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return @()
    }
    try {
        $value = $Text | ConvertFrom-Json
        return @($value | ForEach-Object { [string]$_ })
    } catch {
        return @("__JSON_PARSE_ERROR__:$Text")
    }
}

function Convert-JsonObjectOrNull {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    try {
        return ($Text | ConvertFrom-Json)
    } catch {
        return $null
    }
}

function Get-JsonPropertyString {
    param([object]$Object, [string]$Name)
    if ($null -eq $Object) {
        return ""
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        return ""
    }
    if ($property.Value -is [bool]) {
        return $property.Value.ToString().ToLowerInvariant()
    }
    return [string]$property.Value
}

function Add-Unique {
    param([System.Collections.Generic.List[string]]$List, [string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return
    }
    if ($List -notcontains $Value) {
        $List.Add($Value)
    }
}

function Get-RuntimeLogStatusFromSmoke {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) {
        return "UNKNOWN"
    }
    if ($Text -match "\[runtime-log-smoke\]\s+ERROR" -or $Text -match "runtime log smoke failed|unknown runtime WARN lines present|runtime ERROR lines present|high-risk operation-like log lines present") {
        return "FAIL"
    }
    if ($Text -match "runtime log smoke complete") {
        return "PASS"
    }
    return "UNKNOWN"
}

if ([string]::IsNullOrWhiteSpace($Symbol) -or $Symbol -notmatch "^[A-Za-z0-9._:-]{1,64}$") {
    throw "Symbol contains unsupported characters."
}

$auditText = Read-RequiredLog -Path $LiveAuditLog -Name "LiveAuditLog"
$runtimeSmokeText = Read-OptionalLog -Path $RuntimeLogSmokeLog -Name "RuntimeLogSmokeLog"
$gridText = Read-OptionalLog -Path $GridPostEnvBundleLog -Name "GridPostEnvBundleLog"
$trailingText = Read-OptionalLog -Path $TrailingPostOptInLog -Name "TrailingPostOptInLog"

$orderFlags = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $auditText -Prefix "order_capable_flags_true=")
$dryRunFlags = Convert-JsonObjectOrNull (Get-LastPrefixedValue -Text $auditText -Prefix "dry_run_flags=")
$auditBlockers = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $auditText -Prefix "blockers=")
$auditWarnings = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $auditText -Prefix "warnings=")
$auditRuntimeLogStatus = Get-LastPrefixedValue -Text $auditText -Prefix "runtime_log_status="
$auditVerdict = Get-LastPrefixedValue -Text $auditText -Prefix "verdict="
$runtimeSmokeStatus = Get-RuntimeLogStatusFromSmoke -Text $runtimeSmokeText
$effectiveRuntimeLogStatus = if ($runtimeSmokeStatus -ne "UNKNOWN") { $runtimeSmokeStatus } elseif (-not [string]::IsNullOrWhiteSpace($auditRuntimeLogStatus)) { $auditRuntimeLogStatus } else { "UNKNOWN" }
$runtimeEvidenceSource = if ($runtimeSmokeStatus -ne "UNKNOWN") { "RuntimeLogSmokeLog" } else { "LiveAuditLog" }

$gridStatus = Get-LastPrefixedValue -Text $gridText -Prefix "grid_post_env_read_only_verification_status="
$gridReady = Get-LastPrefixedValue -Text $gridText -Prefix "grid_post_env_read_only_verification_ready="
$gridBlockers = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $gridText -Prefix "grid_post_env_read_only_verification_blockers=")
$gridMissingEvidence = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $gridText -Prefix "grid_post_env_read_only_verification_missing_evidence=")

$trailingStatus = Get-LastPrefixedValue -Text $trailingText -Prefix "trailing_stop_post_opt_in_readiness_status="
$trailingGlobalDryRun = Get-LastPrefixedValue -Text $trailingText -Prefix "trailing_stop_post_opt_in_current_global_dry_run="
$trailingMissing = Convert-JsonArrayOrEmpty (Get-LastPrefixedValue -Text $trailingText -Prefix "trailing_stop_post_opt_in_missing_requirements=")

$missingEvidence = [System.Collections.Generic.List[string]]::new()
$reviewBlockers = [System.Collections.Generic.List[string]]::new()
$riskItems = [System.Collections.Generic.List[string]]::new()

if ($orderFlags -contains "__JSON_PARSE_ERROR__") { Add-Unique -List $missingEvidence -Value "order_capable_flags_true parseable JSON" }
if ($auditBlockers -contains "__JSON_PARSE_ERROR__") { Add-Unique -List $missingEvidence -Value "audit blockers parseable JSON" }
if ($effectiveRuntimeLogStatus -ne "PASS") { Add-Unique -List $reviewBlockers -Value "RUNTIME_LOG_SMOKE_NOT_PASS" }
if ($auditText -notmatch "order_capable_flags_true=") { Add-Unique -List $missingEvidence -Value "live audit order_capable_flags_true marker" }
if ($auditText -notmatch "dry_run_flags=") { Add-Unique -List $missingEvidence -Value "live audit dry_run_flags marker" }

$coverageRows = [System.Collections.Generic.List[object]]::new()
$hasGridFlag = $orderFlags -contains "TRADING_GRID_ENABLED"
$hasTrailingFlag = $orderFlags -contains "TRAILING_STOP_ENABLED"
$hasTinyFlag = $orderFlags -contains "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED"
$hasScoreBuyExecutionFlag = @(
    "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED",
    "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED",
    "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED"
) | Where-Object { $orderFlags -contains $_ }

foreach ($flag in $orderFlags) {
    $covered = $false
    $source = "none"
    $reviewStatus = "MISSING"
    $risk = "unreviewed order-capable flag"

    switch ($flag) {
        "TRADING_OKX_ENABLED" {
            $covered = (-not [string]::IsNullOrWhiteSpace($gridStatus)) -or $hasTinyFlag -or @($hasScoreBuyExecutionFlag).Count -gt 0
            $source = if (-not [string]::IsNullOrWhiteSpace($gridStatus)) { "GridPostEnvBundleLog" } else { "live audit only" }
            $reviewStatus = if ($covered) { "OKX_SCOPE_REVIEW_PRESENT" } else { "OKX_SCOPE_REVIEW_MISSING" }
            $risk = "global OKX exchange client is enabled; only separately reviewed order paths should remain enabled"
            if (-not $covered) { Add-Unique -List $reviewBlockers -Value "TRADING_OKX_ENABLED_SCOPE_EVIDENCE_MISSING" }
            if ($hasGridFlag -and -not [string]::IsNullOrWhiteSpace($gridStatus) -and $gridStatus -ne "READY_FOR_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION") {
                Add-Unique -List $reviewBlockers -Value "TRADING_OKX_ENABLED_GRID_SCOPE_NOT_READY"
            }
        }
        "TRADING_GRID_ENABLED" {
            $covered = -not [string]::IsNullOrWhiteSpace($gridStatus)
            $source = "GridPostEnvBundleLog"
            $reviewStatus = if ($covered) { $gridStatus } else { "GRID_POST_ENV_REVIEW_MISSING" }
            $risk = "existing active grid order path can place market buy/sell on price-cross events when OKX is enabled"
            if (-not $covered) { Add-Unique -List $reviewBlockers -Value "TRADING_GRID_ENABLED_SCOPE_EVIDENCE_MISSING" }
            if ($covered -and $gridStatus -ne "READY_FOR_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION") { Add-Unique -List $reviewBlockers -Value "GRID_SCOPE_REVIEW_NOT_READY" }
            if ($covered -and $gridReady -ne "true") { Add-Unique -List $reviewBlockers -Value "GRID_SCOPE_READY_MARKER_NOT_TRUE" }
            if (@($gridMissingEvidence).Count -gt 0) { Add-Unique -List $reviewBlockers -Value "GRID_SCOPE_MISSING_EVIDENCE" }
        }
        "TRAILING_STOP_ENABLED" {
            $covered = -not [string]::IsNullOrWhiteSpace($trailingStatus)
            $source = "TrailingPostOptInLog"
            $reviewStatus = if ($covered) { $trailingStatus } else { "TRAILING_POST_OPT_IN_REVIEW_MISSING" }
            $risk = "trailing-stop global flag is enabled; dry-run must remain true before any OCO mutation review"
            if (-not $covered) { Add-Unique -List $reviewBlockers -Value "TRAILING_STOP_ENABLED_SCOPE_EVIDENCE_MISSING" }
            if ($covered -and $trailingStatus -ne "TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY" -and $trailingStatus -ne "READY_FOR_TRAILING_STOP_DRY_RUN_ENV_DIFF_OPERATOR_REVIEW_NOT_MUTATION") { Add-Unique -List $reviewBlockers -Value "TRAILING_SCOPE_REVIEW_NOT_READY" }
            if ($covered -and $trailingGlobalDryRun -ne "true") { Add-Unique -List $reviewBlockers -Value "TRAILING_STOP_DRY_RUN_NOT_TRUE" }
            if (@($trailingMissing).Count -gt 0) { Add-Unique -List $reviewBlockers -Value "TRAILING_SCOPE_MISSING_REQUIREMENTS" }
        }
        default {
            $covered = $false
            $source = "live audit only"
            $reviewStatus = "NO_SCOPE_REVIEW_PACKET"
            if ($flag -eq "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED") {
                $risk = "TinyLive execution flag is enabled and requires the exact micro-live activation authorization bundle"
            } elseif ($flag -match "SCORE_BUY") {
                $risk = "ScoreBuy execution flag is enabled and requires a dedicated execution policy packet"
            } elseif ($flag -eq "TRADING_OCO_POLLER_ENABLED" -or $flag -eq "POSITION_EXIT_MANAGER_ENABLED") {
                $risk = "OCO or position-exit mutation capability is enabled and requires reconciliation/close authority review"
            } elseif ($flag -eq "OKX_EARN_TOPUP_ENABLED" -or $flag -eq "TRADING_FUNDING_ARB_ENABLED") {
                $risk = "funding/Earn mutation capability is enabled and requires a separate treasury-risk review"
            } elseif ($flag -eq "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED") {
                $risk = "MCP guardian live actions are enabled and require write-tool approval evidence"
            }
            Add-Unique -List $reviewBlockers -Value "$flag`_SCOPE_REVIEW_MISSING"
        }
    }

    $coverageRows.Add([pscustomobject]@{
        flag = $flag
        covered = $covered
        source = $source
        reviewStatus = $reviewStatus
        risk = $risk
    })
}

if ($hasGridFlag -and ($orderFlags -contains "TRADING_OKX_ENABLED")) {
    Add-Unique -List $riskItems -Value "EXISTING_GRID_ORDER_PATH_ACTIVATION_RISK"
}
if ($hasTrailingFlag) {
    Add-Unique -List $riskItems -Value "TRAILING_STOP_DRY_RUN_OBSERVATION_ONLY_NOT_OCO_MUTATION_APPROVAL"
}
if ((Get-JsonPropertyString -Object $dryRunFlags -Name "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN") -eq "false" -and -not $hasTinyFlag) {
    Add-Unique -List $riskItems -Value "TINY_LIVE_DRY_RUN_FALSE_WHILE_EXECUTION_DISABLED"
}

$hasOrderFlags = @($orderFlags).Count -gt 0 -and -not ($orderFlags -contains "__JSON_PARSE_ERROR__")
$reviewPacketReady = $hasOrderFlags -and $missingEvidence.Count -eq 0 -and $reviewBlockers.Count -eq 0

$status = if (-not $hasOrderFlags) {
    "NO_ORDER_CAPABLE_FLAGS_TRUE_NOT_MUTATION"
} elseif ($reviewPacketReady) {
    "READY_FOR_ORDER_CAPABLE_SCOPE_OPERATOR_REVIEW_NOT_MUTATION"
} else {
    "BLOCKED_ORDER_CAPABLE_SCOPE_REVIEW_NOT_MUTATION"
}

$decision = if ($status -eq "READY_FOR_ORDER_CAPABLE_SCOPE_OPERATOR_REVIEW_NOT_MUTATION") {
    "REQUEST_SEPARATE_RECONCILE_OR_ROLLBACK_DECISION"
} elseif ($status -eq "NO_ORDER_CAPABLE_FLAGS_TRUE_NOT_MUTATION") {
    "KEEP_STANDARD_LIVE_REVIEW_GATE"
} elseif ($reviewBlockers -contains "RUNTIME_LOG_SMOKE_NOT_PASS") {
    "FIX_RUNTIME_LOG_EVIDENCE_BEFORE_SCOPE_REVIEW"
} elseif ($reviewBlockers -contains "GRID_SCOPE_REVIEW_NOT_READY" -or $reviewBlockers -contains "TRADING_OKX_ENABLED_GRID_SCOPE_NOT_READY" -or $reviewBlockers -contains "GRID_SCOPE_READY_MARKER_NOT_TRUE") {
    "FIX_GRID_POST_ENV_REVIEW_OR_ROLLBACK_GRID_OKX_SCOPE"
} else {
    "REFRESH_ORDER_CAPABLE_SCOPE_EVIDENCE"
}

$packet = [pscustomobject]@{
    packetType = "LIVE_ORDER_CAPABLE_SCOPE_REVIEW_PACKET"
    scope = "READ_ONLY"
    status = $status
    decision = $decision
    symbol = $Symbol.ToUpperInvariant()
    sourceLogs = [pscustomobject]@{
        liveAudit = $LiveAuditLog
        runtimeLogSmoke = $RuntimeLogSmokeLog
        gridPostEnvBundle = $GridPostEnvBundleLog
        trailingPostOptIn = $TrailingPostOptInLog
    }
    liveAuditVerdict = $auditVerdict
    auditRuntimeLogStatus = $auditRuntimeLogStatus
    effectiveRuntimeLogStatus = $effectiveRuntimeLogStatus
    runtimeEvidenceSource = $runtimeEvidenceSource
    runtimeLogClassifierNote = if ($auditRuntimeLogStatus -eq "FAIL" -and $runtimeSmokeStatus -eq "PASS") { "AUDIT_USED_STALE_SERVER_CHECKER_LOCAL_CLASSIFIER_PASSED" } else { "" }
    orderCapableFlagsTrue = @($orderFlags)
    auditWarnings = @($auditWarnings)
    auditBlockers = @($auditBlockers)
    dryRunReview = [pscustomobject]@{
        tinyLiveDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN"
        scoreBuyPrePositionDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN"
        scoreBuyConfirmedDeployDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN"
        scoreBuyPostScoutAddDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN"
        trailingStopDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "TRAILING_STOP_DRY_RUN"
        positionExitDryRun = Get-JsonPropertyString -Object $dryRunFlags -Name "POSITION_EXIT_MANAGER_DRY_RUN"
    }
    sourceReviewStatus = [pscustomobject]@{
        gridPostEnvStatus = $gridStatus
        gridPostEnvReady = $gridReady
        gridPostEnvBlockers = @($gridBlockers)
        gridPostEnvMissingEvidence = @($gridMissingEvidence)
        trailingPostOptInStatus = $trailingStatus
        trailingGlobalDryRun = $trailingGlobalDryRun
        trailingMissingRequirements = @($trailingMissing)
    }
    flagCoverage = @($coverageRows)
    riskItems = @($riskItems)
    missingEvidence = @($missingEvidence)
    reviewBlockers = @($reviewBlockers)
    requiredSeparateAuthorization = @(
        "operator explicitly reviews the existing order-capable scope before any additional live relaxation",
        "TRADING_OKX_ENABLED=true current state is accepted only for the named grid/trailing scope or rolled back",
        "TRADING_GRID_ENABLED=true current state is accepted only with existing active grid order-path activation risk acknowledged or rolled back",
        "TRAILING_STOP_ENABLED=true current state remains dry-run observation only with TRAILING_STOP_DRY_RUN=true",
        "keep TinyLive, ScoreBuy execution, OCO poller, position-exit, funding arb, Earn top-up, MCP guardian live actions, and Telegram-send disabled unless separately authorized"
    )
    postDecisionReadOnlyVerification = @(
        ".\scripts\audit_live_readiness_ssh.ps1 -Symbol $($Symbol.ToUpperInvariant())",
        ".\scripts\prepare_grid_post_env_read_only_verification_bundle_ssh.ps1 -Symbol $($Symbol.ToUpperInvariant()) -GridCount 2 -PerLevelUsdt 5 -StopOutPct 5 -CandidateHalfWidthPct 10",
        ".\scripts\prepare_trailing_stop_post_opt_in_readiness_packet_ssh.ps1 -Symbol $($Symbol.ToUpperInvariant()) -ExpectedOptInStrategyId 574",
        "stream local scripts/check_server_runtime_log.sh to the server or deploy tooling before relying on runtime_log_status",
        "verify split acceptance remains OK and no order/OCO/grid/fund/Earn/Telegram high-risk log lines appear unexpectedly"
    )
    rollbackEnvDiff = @(
        "TRADING_OKX_ENABLED=false",
        "TRADING_GRID_ENABLED=false",
        "TRAILING_STOP_ENABLED=false",
        "TRAILING_STOP_DRY_RUN=true",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_ENABLED=false",
        "TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN=true",
        "TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_ENABLED=false",
        "TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_ENABLED=false",
        "TRADING_OCO_POLLER_ENABLED=false",
        "POSITION_EXIT_MANAGER_ENABLED=false",
        "TRADING_GRID_AUTO_REBALANCE_SCHEDULER_ENABLED=false",
        "GRID_RECOVERY_ENABLED=false",
        "TRADING_FUNDING_ARB_ENABLED=false",
        "OKX_EARN_TOPUP_ENABLED=false",
        "MCP_GUARDIAN_LIVE_ACTIONS_ENABLED=false"
    )
    productionEnvChangeAllowed = $false
    deployAllowed = $false
    livePolicyChangeAllowed = $false
    orderAllowed = $false
    ocoMutationAllowed = $false
    gridMutationAllowed = $false
    schedulerEnablementAllowed = $false
    telegramSendAllowed = $false
    dbMutationAllowed = $false
    exchangeMutationAllowed = $false
    notAuthorization = "read-only order-capable scope review packet only; does not change production env, deploy, restart, enable live trading, place orders, modify OCO, create/resize/rebalance grid, enable scheduler, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
}

Write-Host "[live-order-capable-scope-review-packet] read-only packet"
Write-Host "scope=READ_ONLY; parses saved read-only evidence logs only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
Write-Host "live_order_capable_scope_review_status=$status"
Write-Host "live_order_capable_scope_review_decision=$decision"
Write-Host "live_order_capable_runtime_log_effective_status=$effectiveRuntimeLogStatus"
Write-Host "live_order_capable_runtime_evidence_source=$runtimeEvidenceSource"
Write-Host ("live_order_capable_flags_true=" + (ConvertTo-Json -Compress @($orderFlags)))
Write-Host ("live_order_capable_scope_review_blockers=" + (ConvertTo-Json -Compress @($reviewBlockers)))
Write-Host ("live_order_capable_scope_missing_evidence=" + (ConvertTo-Json -Compress @($missingEvidence)))
Write-Host ("live_order_capable_scope_risk_items=" + (ConvertTo-Json -Compress @($riskItems)))
Write-Host "production_env_change_allowed=false"
Write-Host "deploy_allowed=false"
Write-Host "live_policy_change_allowed=false"
Write-Host "order_allowed=false"
Write-Host "oco_mutation_allowed=false"
Write-Host "grid_mutation_allowed=false"
Write-Host "scheduler_enablement_allowed=false"
Write-Host "telegram_send_allowed=false"
Write-Host "db_mutation_allowed=false"
Write-Host "exchange_mutation_allowed=false"
Write-Host ("live_order_capable_scope_review_packet=" + (ConvertTo-Json -Compress -Depth 16 $packet))
Write-Host "notAuthorization=read-only order-capable scope review packet only; does not change production env, deploy, restart, enable live trading, place orders, modify OCO, create/resize/rebalance grid, enable scheduler, send Telegram, relax policy, or mutate DB/grid/fund/Earn/exchange state"
Write-Host "[live-order-capable-scope-review-packet] read-only check complete"

if ($RequireReady -and -not $reviewPacketReady) {
    throw "Order-capable scope review packet is not ready: $status; blockers=$(@($reviewBlockers) -join '; '); missingEvidence=$(@($missingEvidence) -join '; ')"
}
