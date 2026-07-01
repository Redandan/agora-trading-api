Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "prepare_live_order_capable_scope_review_packet.ps1"
if (-not (Test-Path -LiteralPath $scriptPath)) {
    throw "Missing script under test: $scriptPath"
}

function Assert-Contains {
    param([string]$Name, [string]$Text, [string]$Pattern)
    if ($Text -notmatch $Pattern) {
        throw "[$Name] expected pattern not found: $Pattern`n--- output ---`n$Text"
    }
}

function Write-Fixture {
    param([string]$Path, [string]$Text)
    Set-Content -LiteralPath $Path -Value $Text -Encoding ASCII
}

function Invoke-Packet {
    param(
        [string]$LiveAuditLog,
        [string]$RuntimeLogSmokeLog = "",
        [string]$GridPostEnvBundleLog = "",
        [string]$TrailingPostOptInLog = ""
    )

    $outputPath = [System.IO.Path]::GetTempFileName()
    try {
        & $scriptPath `
            -LiveAuditLog $LiveAuditLog `
            -RuntimeLogSmokeLog $RuntimeLogSmokeLog `
            -GridPostEnvBundleLog $GridPostEnvBundleLog `
            -TrailingPostOptInLog $TrailingPostOptInLog `
            -Symbol "BTCUSDT" *> $outputPath
        $lastExit = Get-Variable -Name LASTEXITCODE -ValueOnly -ErrorAction SilentlyContinue
        $exit = if ($null -eq $lastExit) { 0 } else { $lastExit }
        return [pscustomobject]@{
            ExitCode = $exit
            Text = (Get-Content -Raw -LiteralPath $outputPath)
        }
    } finally {
        Remove-Item -LiteralPath $outputPath -Force -ErrorAction SilentlyContinue
    }
}

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("live-order-scope-test-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempRoot | Out-Null
try {
    $auditPath = Join-Path $tempRoot "audit.log"
    $runtimePath = Join-Path $tempRoot "runtime.log"
    $gridPath = Join-Path $tempRoot "grid.log"
    $trailingPath = Join-Path $tempRoot "trailing.log"

    Write-Fixture -Path $auditPath -Text @'
runtime_log_status=FAIL
order_capable_flags_true=["TRADING_OKX_ENABLED","TRADING_GRID_ENABLED","TRAILING_STOP_ENABLED"]
dry_run_flags={"TRADING_TINY_LIVE_AUTO_EXECUTION_DRY_RUN":false,"TRADING_SCORE_BUY_PRE_POSITION_EXECUTION_DRY_RUN":true,"TRADING_SCORE_BUY_CONFIRMED_DEPLOY_EXECUTION_DRY_RUN":true,"TRADING_SCORE_BUY_POST_SCOUT_ADD_EXECUTION_DRY_RUN":true,"TRAILING_STOP_DRY_RUN":true,"POSITION_EXIT_MANAGER_DRY_RUN":true}
warnings=["ORDER_CAPABLE_FLAGS_ALREADY_TRUE_REVIEW_BEFORE_LIVE"]
blockers=["ORDER_CAPABLE_FLAGS_ALREADY_TRUE:TRADING_OKX_ENABLED,TRADING_GRID_ENABLED,TRAILING_STOP_ENABLED","RUNTIME_LOG_SMOKE_FAILED"]
verdict=NOT_READY
'@

    Write-Fixture -Path $runtimePath -Text @'
[runtime-log-smoke] OK runtime WARN lines match known baseline: total_warn=32
[runtime-log-smoke] OK WARN baseline category flyway_mysql_version=1 startup_bean_timing=9 cglib_proxy=2 open_in_view=1 thegraph_optional_key=18 autonomous_digest_severe=0 okx_ws_connection_reset=0 okx_ws_transient=0 scorebuy_ml_schema_mismatch=0 pyth_network_transient=0 etherscan_token_supply=1 mcp_auth_denied=0 unknown=0
[runtime-log-smoke] OK runtime log smoke complete
'@

    Write-Fixture -Path $gridPath -Text @'
grid_post_env_read_only_verification_status=READY_FOR_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION
grid_post_env_read_only_verification_ready=true
grid_post_env_read_only_verification_blockers=[]
grid_post_env_read_only_verification_missing_evidence=[]
'@

    Write-Fixture -Path $trailingPath -Text @'
trailing_stop_post_opt_in_current_global_dry_run=true
trailing_stop_post_opt_in_missing_requirements=[]
trailing_stop_post_opt_in_readiness_status=TRAILING_STOP_DRY_RUN_ALREADY_ACTIVE_READ_ONLY_VERIFY
'@

    $ready = Invoke-Packet -LiveAuditLog $auditPath -RuntimeLogSmokeLog $runtimePath -GridPostEnvBundleLog $gridPath -TrailingPostOptInLog $trailingPath
    Assert-Contains -Name "ready status" -Text $ready.Text -Pattern "live_order_capable_scope_review_status=READY_FOR_ORDER_CAPABLE_SCOPE_OPERATOR_REVIEW_NOT_MUTATION"
    Assert-Contains -Name "runtime override note" -Text $ready.Text -Pattern "AUDIT_USED_STALE_SERVER_CHECKER_LOCAL_CLASSIFIER_PASSED"
    Assert-Contains -Name "grid activation risk" -Text $ready.Text -Pattern "EXISTING_GRID_ORDER_PATH_ACTIVATION_RISK"
    Assert-Contains -Name "read only boundary" -Text $ready.Text -Pattern "order_allowed=false"
    Assert-Contains -Name "rollback env diff" -Text $ready.Text -Pattern "TRADING_OKX_ENABLED=false"

    $missingGrid = Invoke-Packet -LiveAuditLog $auditPath -RuntimeLogSmokeLog $runtimePath -TrailingPostOptInLog $trailingPath
    Assert-Contains -Name "missing grid status" -Text $missingGrid.Text -Pattern "live_order_capable_scope_review_status=BLOCKED_ORDER_CAPABLE_SCOPE_REVIEW_NOT_MUTATION"
    Assert-Contains -Name "missing grid blocker" -Text $missingGrid.Text -Pattern "TRADING_GRID_ENABLED_SCOPE_EVIDENCE_MISSING"

    $blockedGridPath = Join-Path $tempRoot "grid-blocked.log"
    Write-Fixture -Path $blockedGridPath -Text @'
grid_post_env_read_only_verification_status=BLOCKED_GRID_POST_ENV_READ_ONLY_VERIFICATION_NOT_MUTATION
grid_post_env_read_only_verification_ready=false
grid_post_env_read_only_verification_blockers=["SPLIT_ACCEPTANCE_FAILED_OR_INCOMPLETE"]
grid_post_env_read_only_verification_missing_evidence=[]
'@

    $blockedGrid = Invoke-Packet -LiveAuditLog $auditPath -RuntimeLogSmokeLog $runtimePath -GridPostEnvBundleLog $blockedGridPath -TrailingPostOptInLog $trailingPath
    Assert-Contains -Name "blocked grid status" -Text $blockedGrid.Text -Pattern "live_order_capable_scope_review_status=BLOCKED_ORDER_CAPABLE_SCOPE_REVIEW_NOT_MUTATION"
    Assert-Contains -Name "blocked grid blocker" -Text $blockedGrid.Text -Pattern "GRID_SCOPE_REVIEW_NOT_READY"
    Assert-Contains -Name "okx grid not ready blocker" -Text $blockedGrid.Text -Pattern "TRADING_OKX_ENABLED_GRID_SCOPE_NOT_READY"

    $badRuntimePath = Join-Path $tempRoot "runtime-fail.log"
    Write-Fixture -Path $badRuntimePath -Text @'
[runtime-log-smoke] ERROR unknown runtime WARN lines present: count=1 total_warn=31
'@

    $badRuntime = Invoke-Packet -LiveAuditLog $auditPath -RuntimeLogSmokeLog $badRuntimePath -GridPostEnvBundleLog $gridPath -TrailingPostOptInLog $trailingPath
    Assert-Contains -Name "bad runtime status" -Text $badRuntime.Text -Pattern "live_order_capable_scope_review_status=BLOCKED_ORDER_CAPABLE_SCOPE_REVIEW_NOT_MUTATION"
    Assert-Contains -Name "bad runtime blocker" -Text $badRuntime.Text -Pattern "RUNTIME_LOG_SMOKE_NOT_PASS"

    Write-Host "[live-order-capable-scope-review-packet-test] OK"
} finally {
    Remove-Item -LiteralPath $tempRoot -Recurse -Force -ErrorAction SilentlyContinue
}
