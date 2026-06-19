param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [string]$EnvFile = "/home/ubuntu/.env.trading.secrets",
    [string]$Symbol = "BTCUSDT",
    [long]$StrategyId = 574,
    [string]$Side = "LONG",
    [string]$IntervalCode = "1h",
    [int]$RuntimeEvidenceMinutes = 43200,
    [int]$TinyLiveDays = 30,
    [int]$SignalExecutionDays = 5,
    [int]$SignalBlockedDays = 7,
    [int]$SignalAccuracyDays = 14,
    [switch]$RequireReady
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

if (-not (Get-Command ssh -ErrorAction SilentlyContinue)) {
    throw "ssh is not available on PATH."
}

if ($StrategyId -lt 1 -or $StrategyId -gt 999999999) {
    throw "StrategyId must be between 1 and 999999999."
}

if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) {
    throw "RuntimeEvidenceMinutes must be between 60 and 43200."
}

if ($TinyLiveDays -lt 1 -or $TinyLiveDays -gt 90 `
        -or $SignalExecutionDays -lt 1 -or $SignalExecutionDays -gt 90 `
        -or $SignalBlockedDays -lt 1 -or $SignalBlockedDays -gt 90 `
        -or $SignalAccuracyDays -lt 1 -or $SignalAccuracyDays -gt 90) {
    throw "TinyLiveDays and signal day windows must be between 1 and 90."
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

function Assert-SmokeTokenSafe {
    param([string]$Name, [string]$Value, [int]$MaxLength)
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt $MaxLength -or $Value -notmatch "^[A-Za-z0-9][A-Za-z0-9_-]*$") {
        throw "$Name contains unsupported characters for smoke invocation."
    }
}

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir
Assert-RemotePathSafe -Name "EnvFile" -Value $EnvFile
Assert-SmokeTokenSafe -Name "Symbol" -Value $Symbol -MaxLength 31
Assert-SmokeTokenSafe -Name "Side" -Value $Side -MaxLength 16
Assert-SmokeTokenSafe -Name "IntervalCode" -Value $IntervalCode -MaxLength 16

$scriptDir = $PSScriptRoot
$script:LiveReadinessDeploymentMetadataSnapshot = ""

function Get-ReadOnlySshFailureClassification {
    param(
        [int]$ExitCode,
        [string]$OutputText
    )

    if ($ExitCode -eq 255 -and $OutputText -match "Permission denied \(publickey\)|Permission denied") {
        return "SSH_AUTH_FAILED"
    }
    if ($ExitCode -eq 255 -and $OutputText -match "Connection timed out|Connection refused|Could not resolve hostname|No route to host|Operation timed out") {
        return "SSH_CONNECT_FAILED"
    }
    if ($OutputText -match "ssh:|remote command failed") {
        return "SSH_COMMAND_FAILED"
    }
    return "READ_ONLY_SMOKE_FAILED"
}

function Get-LiveReadinessDeployRequirement {
    param([string]$DeploymentMetadata)

    if ([string]::IsNullOrWhiteSpace($DeploymentMetadata)) {
        return "unknown"
    }
    if ($DeploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)" `
            -or $DeploymentMetadata -notmatch "liveBundleDeployStatus=(CURRENT|DOCS_TOOLING_ONLY_DRIFT)") {
        return "true"
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=(WORKTREE_NOT_ORIGIN_MAIN|UNKNOWN_ORIGIN_MAIN)" `
            -or $DeploymentMetadata -notmatch "liveBundleOriginStatus=CURRENT_ORIGIN_MAIN") {
        return "true"
    }
    return "false"
}

function Write-PartialDeploymentMetadata {
    param([string]$DeploymentMetadata)

    if ([string]::IsNullOrWhiteSpace($DeploymentMetadata)) {
        return
    }
    if ($DeploymentMetadata -match "liveBundleDeployStatus=([A-Z_]+)") {
        Write-Host ("deployment_metadata_status=" + $Matches[1])
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=([A-Z_]+)") {
        Write-Host ("origin_metadata_status=" + $Matches[1])
    }
}

function Assert-ReadOnlyCommandSucceeded {
    param(
        [string]$Name,
        [int]$ExitCode,
        [object[]]$Output
    )

    if ($ExitCode -eq 0) {
        return
    }

    $outputText = ($Output -join "`n")
    $classification = Get-ReadOnlySshFailureClassification -ExitCode $ExitCode -OutputText $outputText
    $deployRequirement = Get-LiveReadinessDeployRequirement -DeploymentMetadata $script:LiveReadinessDeploymentMetadataSnapshot
    Write-Host "read_only_bundle_error=$classification"
    Write-Host "read_only_bundle_error_detail=$Name failed before full live-readiness evidence could be collected"
    Write-Host "read_only_bundle_error_boundary=not complete live-readiness evidence; fix SSH access, key selection, or the failing read-only smoke and rerun the bundle"
    Write-PartialDeploymentMetadata -DeploymentMetadata $script:LiveReadinessDeploymentMetadataSnapshot
    if ($deployRequirement -eq "true") {
        Write-Host 'bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE","DEPLOYED_RUNTIME_NOT_CURRENT"]'
    } else {
        Write-Host 'bundle_blockers=["LIVE_READINESS_EVIDENCE_UNAVAILABLE"]'
    }
    Write-Host "live_review_packet_allowed=false"
    if ($deployRequirement -eq "unknown") {
        Write-Host "deploy_required_before_live_review=unknown"
    } else {
        Write-Host "deploy_required_before_live_review=$deployRequirement"
    }
    Write-Host "bundle_verdict=NO_EVIDENCE"
    Write-Host "next_action=Fix SSH access, key selection, or the failing read-only smoke and rerun this bundle before drawing any server/live conclusion."
    throw "$Name failed with exit code $ExitCode ($classification); full live-readiness evidence was not collected."
}

function Invoke-ReadOnlySmoke {
    param(
        [string]$Name,
        [string]$ScriptName,
        [hashtable]$Arguments
    )

    $scriptPath = Join-Path $scriptDir $ScriptName
    if (-not (Test-Path -LiteralPath $scriptPath)) {
        throw "Missing smoke script: $scriptPath"
    }

    Write-Host ""
    Write-Host "===== BEGIN $Name ====="
    $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for read-only smoke invocation"
    }

    $processArgs = @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File", $scriptPath)
    foreach ($key in @($Arguments.Keys | Sort-Object)) {
        $processArgs += "-$key"
        $processArgs += [string]$Arguments[$key]
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source @processArgs 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $output | ForEach-Object { Write-Host $_ }
    Write-Host "===== END $Name exit=$exitCode ====="
    Assert-ReadOnlyCommandSucceeded -Name $Name -ExitCode $exitCode -Output $output
    return ($output -join "`n")
}

function Invoke-ReadOnlyDeploymentMetadata {
    $remoteScript = @"
set -euo pipefail
APP_DIR='$AppDir'
cd "`$APP_DIR"

classify_path() {
  case "`$1" in
    .gitattributes|.gitignore|AGENTS.md|INTERNAL_API_TODO.md|README.md|SERVICE_BOUNDARY.md|SPLIT_PROGRESS.md|docs/*|deploy.sh|scripts/*.ps1|scripts/install_nginx_path.sh|scripts/rewrite_nginx_trading_routes.awk|scripts/check_server_runtime_log.sh|scripts/verify_server.sh)
      echo docs-tooling
      ;;
    *)
      echo runtime
      ;;
  esac
}

HEAD_COMMIT="`$(git rev-parse HEAD 2>/dev/null || true)"
ORIGIN_MAIN_COMMIT="`$(git ls-remote origin refs/heads/main 2>/dev/null | awk '{print `$1}' || true)"
DEPLOYED_COMMIT=""
if [ -f app.commit ]; then
  DEPLOYED_COMMIT="`$(tr -d '[:space:]' < app.commit)"
fi

echo "[deployment-metadata] read-only server commit probe"
echo "worktreeCommit=`${HEAD_COMMIT:-UNKNOWN}"
echo "originMainCommit=`${ORIGIN_MAIN_COMMIT:-UNKNOWN}"
echo "deployedCommit=`${DEPLOYED_COMMIT:-MISSING}"

if [ -z "`$ORIGIN_MAIN_COMMIT" ]; then
  echo "liveBundleOriginStatus=UNKNOWN_ORIGIN_MAIN"
elif [ "`$HEAD_COMMIT" = "`$ORIGIN_MAIN_COMMIT" ]; then
  echo "liveBundleOriginStatus=CURRENT_ORIGIN_MAIN"
else
  echo "liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN"
fi

if [ -z "`$HEAD_COMMIT" ] || [ -z "`$DEPLOYED_COMMIT" ]; then
  echo "liveBundleDeployStatus=UNKNOWN_DEPLOY_METADATA"
  exit 0
fi

if [ "`$HEAD_COMMIT" = "`$DEPLOYED_COMMIT" ]; then
  echo "liveBundleDeployStatus=CURRENT"
  echo "deploymentDeltaFiles=0"
  exit 0
fi

if ! git cat-file -e "`$DEPLOYED_COMMIT^{commit}" 2>/dev/null; then
  echo "liveBundleDeployStatus=UNKNOWN_DEPLOY_METADATA"
  exit 0
fi

docs_tooling_delta=0
runtime_delta=0
total_delta=0
while IFS= read -r path; do
  [ -n "`$path" ] || continue
  total_delta=`$((total_delta + 1))
  case "`$(classify_path "`$path")" in
    docs-tooling) docs_tooling_delta=`$((docs_tooling_delta + 1)) ;;
    runtime) runtime_delta=`$((runtime_delta + 1)) ;;
  esac
done <<EOF
`$(git diff --name-only "`$DEPLOYED_COMMIT"..HEAD || true)
EOF

echo "deploymentDeltaFiles=`$total_delta"
echo "deploymentDocsToolingDeltaFiles=`$docs_tooling_delta"
echo "deploymentRuntimeDeltaFiles=`$runtime_delta"
if [ "`$runtime_delta" -gt 0 ]; then
  echo "liveBundleDeployStatus=RUNTIME_DRIFT"
else
  echo "liveBundleDeployStatus=DOCS_TOOLING_ONLY_DRIFT"
fi
"@

    Write-Host ""
    Write-Host "===== BEGIN deployment-metadata ====="
    $output = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s" 2>&1
    $exitCode = $LASTEXITCODE
    $output | ForEach-Object { Write-Host $_ }
    Write-Host "===== END deployment-metadata exit=$exitCode ====="
    Assert-ReadOnlyCommandSucceeded -Name "deployment metadata probe" -ExitCode $exitCode -Output $output
    return ($output -join "`n")
}

function Get-AuditReadinessDetails {
    param([string]$AuditText)

    $line = @($AuditText -split "`r?`n" | Where-Object { $_ -like "readiness_details=*" } | Select-Object -Last 1)
    if (-not $line) {
        return $null
    }

    try {
        return $line.Substring("readiness_details=".Length) | ConvertFrom-Json -ErrorAction Stop
    } catch {
        return $null
    }
}

function Test-ReadinessSectionPresent {
    param(
        [object]$Details,
        [string]$Name
    )

    return $null -ne $Details -and $null -ne $Details.PSObject.Properties[$Name]
}

function Test-ExecutionEligibleTrue {
    param(
        [object]$Details,
        [string]$Name
    )

    if (-not (Test-ReadinessSectionPresent -Details $Details -Name $Name)) {
        return $false
    }

    $section = $Details.PSObject.Properties[$Name].Value
    if ($null -eq $section -or $null -eq $section.PSObject.Properties["executionEligible"]) {
        return $false
    }

    $value = $section.PSObject.Properties["executionEligible"].Value
    return $value -eq $true -or [string]::Equals([string]$value, "true", [System.StringComparison]::OrdinalIgnoreCase)
}

$common = @{
    SshHost = $SshHost
    SshKey = $SshKey
    AppDir = $AppDir
    EnvFile = $EnvFile
}

Write-Host "[live-readiness-bundle] read-only SSH smoke bundle"
Write-Host "scope=READ_ONLY; invokes existing read-only SSH smokes only; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, or external backfill/import state changed."
Write-Host "symbol=$Symbol strategyId=$StrategyId side=$Side interval=$IntervalCode"

$deploymentMetadata = Invoke-ReadOnlyDeploymentMetadata
$script:LiveReadinessDeploymentMetadataSnapshot = $deploymentMetadata
$audit = Invoke-ReadOnlySmoke -Name "live-readiness-audit" -ScriptName "audit_live_readiness_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
    })
$background = Invoke-ReadOnlySmoke -Name "live-background-automation" -ScriptName "smoke_live_background_automation_ssh.ps1" -Arguments $common
$runtimeEvidence = Invoke-ReadOnlySmoke -Name "runtime-evidence-rca" -ScriptName "smoke_runtime_evidence_rca_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        StrategyId = $StrategyId
        Side = $Side
        Minutes = $RuntimeEvidenceMinutes
    })
$tinyLive = Invoke-ReadOnlySmoke -Name "tiny-live-loss-rca" -ScriptName "smoke_tiny_live_loss_rca_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        StrategyId = $StrategyId
        Side = $Side
        Days = $TinyLiveDays
    })
$signal = Invoke-ReadOnlySmoke -Name "signal-correctness" -ScriptName "smoke_signal_correctness_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        ExecutionDays = $SignalExecutionDays
        BlockedDays = $SignalBlockedDays
        AccuracyDays = $SignalAccuracyDays
    })
$mcpParity = Invoke-ReadOnlySmoke -Name "mcp-parity" -ScriptName "smoke_mcp_parity_ssh.ps1" -Arguments ($common + @{
        Symbol = $Symbol
        IntervalCode = $IntervalCode
    })
$readinessDetails = Get-AuditReadinessDetails -AuditText $audit

$blockers = [System.Collections.Generic.List[string]]::new()
if ($audit -match "verdict=NOT_READY" `
        -or $audit -notmatch "verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED") {
    $blockers.Add("LIVE_READINESS_NOT_READY")
}
if ($audit -match "ORDER_CAPABLE_FLAGS_ALREADY_TRUE" `
        -or $audit -match "order_capable_flags_true=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $audit -notmatch "order_capable_flags_true=\[\]") {
    $blockers.Add("ORDER_CAPABLE_FLAGS_REVIEW")
}
if ($audit -match "OKX_CREDENTIALS_NOT_SET|MCP_KEY_MISSING|ENV_FILE_MISSING" `
        -or $audit -notmatch '"TRADING_OKX_API_KEY":\s*"SET"' `
        -or $audit -notmatch '"TRADING_OKX_SECRET_KEY":\s*"SET"' `
        -or $audit -notmatch '"TRADING_OKX_PASSPHRASE":\s*"SET"') {
    $blockers.Add("SECRET_PREREQUISITES_MISSING")
}
if ($audit -match "HEALTH_NOT_UP|RUNTIME_LOG_SMOKE_FAILED|RUNTIME_LOG_SMOKE_EXCEPTION" `
        -or $audit -notmatch 'health=.*"status"\s*:\s*"UP"' `
        -or $audit -notmatch "runtime_log_status=PASS") {
    $blockers.Add("RUNTIME_HEALTH_OR_LOG_NOT_CLEAN")
}
if ($audit -match "EVENT_RISK_NOT_R0" `
        -or $audit -notmatch "riskLevel=R0") {
    $blockers.Add("EVENT_RISK_NOT_BASELINE")
}
if ($audit -match "MCP_TOOL_ERROR:" `
        -or $audit -match "READINESS_DETAILS_MISSING_FIELDS" `
        -or $audit -match "missing_readiness_detail_fields=\[[^\]]*[A-Za-z0-9_.]+[^\]]*\]" `
        -or $audit -notmatch "missing_readiness_detail_fields=\[\]" `
        -or -not (Test-ReadinessSectionPresent -Details $readinessDetails -Name "tinyLive") `
        -or -not (Test-ReadinessSectionPresent -Details $readinessDetails -Name "autonomousOpportunity") `
        -or -not (Test-ReadinessSectionPresent -Details $readinessDetails -Name "scoreBuyPrePosition") `
        -or -not (Test-ReadinessSectionPresent -Details $readinessDetails -Name "scoreBuyConfirmedDeploy") `
        -or -not (Test-ReadinessSectionPresent -Details $readinessDetails -Name "scoreBuyPostScoutAdd")) {
    $blockers.Add("MCP_AUDIT_TOOL_ERROR")
}
if ($audit -match "_NOT_EXECUTION_ELIGIBLE" `
        -or -not (Test-ExecutionEligibleTrue -Details $readinessDetails -Name "tinyLive") `
        -or -not (Test-ExecutionEligibleTrue -Details $readinessDetails -Name "scoreBuyPrePosition") `
        -or -not (Test-ExecutionEligibleTrue -Details $readinessDetails -Name "scoreBuyConfirmedDeploy") `
        -or -not (Test-ExecutionEligibleTrue -Details $readinessDetails -Name "scoreBuyPostScoutAdd")) {
    $blockers.Add("EXECUTION_ELIGIBILITY_NOT_READY")
}
if ($background -match "blocker=HIGH_RISK_BACKGROUND_AUTOMATION_TRUE" `
        -or $background -match "blocker=MISSING_BACKGROUND_AUTOMATION_FLAG" `
        -or $background -match "missing_background_automation_flags=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $background -match "high_risk_background_automation_true=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $background -match "NOT_READY_BACKGROUND_AUTOMATION_REVIEW" `
        -or $background -notmatch "verdict=OK_BACKGROUND_AUTOMATION_DISABLED" `
        -or $background -notmatch "high_risk_background_automation_true=\[\]") {
    $blockers.Add("BACKGROUND_AUTOMATION_REVIEW")
}
if ($runtimeEvidence -match "diagnosis=CONFIG_DISABLED") {
    $blockers.Add("RUNTIME_EVIDENCE_CONFIG_DISABLED")
}
if ($runtimeEvidence -match "diagnosis=NO_CANONICAL_ROWS") {
    $blockers.Add("RUNTIME_EVIDENCE_NO_CANONICAL_ROWS")
}
if ($runtimeEvidence -match "diagnosis=REVIEW_RUNTIME_EVIDENCE_STATUS" `
        -or $runtimeEvidence -match "missing_runtime_evidence_fields=\[[^\]]*[A-Za-z0-9_]+[^\]]*\]" `
        -or $runtimeEvidence -notmatch "diagnosis=CANONICAL_SHADOW_READY|diagnosis=CONFIG_DISABLED|diagnosis=NO_CANONICAL_ROWS|diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT|diagnosis=REVIEW_RUNTIME_EVIDENCE_STATUS") {
    $blockers.Add("RUNTIME_EVIDENCE_REVIEW_REQUIRED")
}
if ($runtimeEvidence -notmatch "shadowIntentCount=([1-9][0-9]*)") {
    $blockers.Add("RUNTIME_EVIDENCE_NO_SHADOW_INTENT")
}
if ($runtimeEvidence -match "orderSentEvidence=([1-9][0-9]*)") {
    $blockers.Add("RUNTIME_EVIDENCE_ORDER_SENT")
} elseif ($runtimeEvidence -notmatch "orderSentEvidence=0") {
    $blockers.Add("RUNTIME_EVIDENCE_REVIEW_REQUIRED")
}
if ($tinyLive -notmatch "hardStopDetected=false" `
        -or $tinyLive -match "hardStopDetected=true" `
        -or $tinyLive -match "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES" `
        -or $tinyLive -match "missing_tiny_live_hard_stop_fields=\[[^\]]*[A-Za-z0-9_]+[^\]]*\]") {
    $blockers.Add("TINY_LIVE_LOSS_HARD_STOP")
}
if ($tinyLive -notmatch "canEnableProduction=true" `
        -or $tinyLive -match "missing_tiny_live_rollout_fields=\[[^\]]*[A-Za-z0-9_]+[^\]]*\]") {
    $blockers.Add("TINY_LIVE_ROLLOUT_NOT_READY")
}
if ($signal -match "REVIEW_POLICY_GAPS" `
        -or $signal -match "missing_signal_policy_fields=\[[^\]]*[A-Za-z0-9_]+[^\]]*\]" `
        -or $signal -match "7d Governance Drift:\s*`r?`n\s*governanceMode=(TOO_STRICT|TOO_LOOSE|INSUFFICIENT_DATA)" `
        -or $signal -match "Missed Opportunity Regression:\s*`r?`n\s*overallStatus=(FAIL|WARN)" `
        -or $signal -notmatch "7d Governance Drift:\s*`r?`n\s*governanceMode=" `
        -or $signal -notmatch "Missed Opportunity Regression:\s*`r?`n\s*overallStatus=PASS") {
    $blockers.Add("SIGNAL_POLICY_REVIEW_GAPS")
}
if ($mcpParity -notmatch "\[mcp-parity-ssh\] OK") {
    $blockers.Add("MCP_PARITY_NOT_PROVEN")
}
if ($deploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)" `
        -or $deploymentMetadata -notmatch "liveBundleDeployStatus=(CURRENT|DOCS_TOOLING_ONLY_DRIFT)") {
    $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
}
if ($deploymentMetadata -match "liveBundleOriginStatus=(WORKTREE_NOT_ORIGIN_MAIN|UNKNOWN_ORIGIN_MAIN)" `
        -or $deploymentMetadata -notmatch "liveBundleOriginStatus=CURRENT_ORIGIN_MAIN") {
    $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
}

$uniqueBlockers = @($blockers | Select-Object -Unique)
Write-Host ""
Write-Host "[live-readiness-bundle] summary"
if ($deploymentMetadata -match "liveBundleDeployStatus=([A-Z_]+)") {
    Write-Host ("deployment_metadata_status=" + $Matches[1])
}
if ($deploymentMetadata -match "liveBundleOriginStatus=([A-Z_]+)") {
    Write-Host ("origin_metadata_status=" + $Matches[1])
}
Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress $uniqueBlockers))
if ($uniqueBlockers.Count -eq 0) {
    Write-Host "live_review_packet_allowed=true"
    Write-Host "deploy_required_before_live_review=false"
    Write-Host "bundle_verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED"
} else {
    Write-Host "live_review_packet_allowed=false"
    if (@($uniqueBlockers) -contains "DEPLOYED_RUNTIME_NOT_CURRENT") {
        Write-Host "deploy_required_before_live_review=true"
    } else {
        Write-Host "deploy_required_before_live_review=false"
    }
    Write-Host "bundle_verdict=NOT_READY"
    Write-Host "next_action=Do not enable live; address or separately authorize the listed blockers, then rerun this bundle."
    if ($RequireReady) {
        throw "Live readiness bundle is not ready: $($uniqueBlockers -join ', ')"
    }
}
Write-Host "[live-readiness-bundle] read-only check complete"
