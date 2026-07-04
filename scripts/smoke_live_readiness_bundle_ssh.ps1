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
    [long]$LocalTradingViewStrategyId = 485,
    [string]$LocalTradingViewIntervalCode = "1d",
    [int]$LocalTradingViewDays = 90,
    [string]$LocalTradingViewSource = "okx",
    [switch]$ContinueWhenRuntimeStale,
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

if ($LocalTradingViewStrategyId -lt 1 -or $LocalTradingViewStrategyId -gt 999999999) {
    throw "LocalTradingViewStrategyId must be between 1 and 999999999."
}

if ($RuntimeEvidenceMinutes -lt 60 -or $RuntimeEvidenceMinutes -gt 43200) {
    throw "RuntimeEvidenceMinutes must be between 60 and 43200."
}

if ($TinyLiveDays -lt 1 -or $TinyLiveDays -gt 90 `
        -or $LocalTradingViewDays -lt 7 -or $LocalTradingViewDays -gt 730 `
        -or $SignalExecutionDays -lt 1 -or $SignalExecutionDays -gt 90 `
        -or $SignalBlockedDays -lt 1 -or $SignalBlockedDays -gt 90 `
        -or $SignalAccuracyDays -lt 1 -or $SignalAccuracyDays -gt 90) {
    throw "TinyLiveDays/signal day windows must be between 1 and 90; LocalTradingViewDays must be between 7 and 730."
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
Assert-SmokeTokenSafe -Name "LocalTradingViewIntervalCode" -Value $LocalTradingViewIntervalCode -MaxLength 16
Assert-SmokeTokenSafe -Name "LocalTradingViewSource" -Value $LocalTradingViewSource -MaxLength 32

$scriptDir = $PSScriptRoot
$script:LiveReadinessDeploymentMetadataSnapshot = ""

function Get-LastPrefixedValue {
    param([string]$Text, [string]$Prefix)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return $null
    }
    $line = @($Text -split "`r?`n" | Where-Object { $_.StartsWith($Prefix) } | Select-Object -Last 1)
    if (-not $line) {
        return $null
    }
    return $line.Substring($Prefix.Length).Trim()
}

function Get-DeltaPathKind {
    param([string]$Path)

    if ($Path -eq ".gitattributes" `
            -or $Path -eq ".gitignore" `
            -or $Path -eq "AGENTS.md" `
            -or $Path -eq "INTERNAL_API_TODO.md" `
            -or $Path -eq "README.md" `
            -or $Path -eq "SERVICE_BOUNDARY.md" `
            -or $Path -eq "SPLIT_PROGRESS.md" `
            -or $Path -like "src/test/*" `
            -or $Path -like "docs/*" `
            -or $Path -eq "deploy.sh" `
            -or $Path -like "scripts/*.ps1" `
            -or $Path -eq "scripts/install_nginx_path.sh" `
            -or $Path -eq "scripts/rewrite_nginx_trading_routes.awk" `
            -or $Path -eq "scripts/check_server_runtime_log.sh" `
            -or $Path -eq "scripts/verify_server.sh") {
        return "docs-tooling"
    }
    return "runtime"
}

function Test-GitCommitishSafe {
    param([string]$Value)

    return -not [string]::IsNullOrWhiteSpace($Value) -and $Value -match "^[a-fA-F0-9]{40}$"
}

function Get-OriginDeltaLocalClassification {
    param([string]$DeploymentMetadata)

    $serverWorktreeCommit = Get-LastPrefixedValue -Text $DeploymentMetadata -Prefix "worktreeCommit="
    $originMainCommit = Get-LastPrefixedValue -Text $DeploymentMetadata -Prefix "originMainCommit="
    $repoRoot = Split-Path -Parent $scriptDir
    $classification = "NO_LOCAL_EVIDENCE"
    $localEvidence = $false
    $deltaFiles = @()
    $docsToolingDeltaFiles = @()
    $runtimeDeltaFiles = @()
    $errorText = ""

    if ($serverWorktreeCommit -eq $originMainCommit -and -not [string]::IsNullOrWhiteSpace($serverWorktreeCommit)) {
        $classification = "CURRENT_ORIGIN_MAIN"
        $localEvidence = $true
    } else {
        try {
            if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
                throw "git is not available on PATH"
            }
            if (-not (Test-GitCommitishSafe -Value $serverWorktreeCommit)) {
                throw "server worktree commit is not a full git commit hash"
            }
            if (-not (Test-GitCommitishSafe -Value $originMainCommit)) {
                throw "origin main commit is not a full git commit hash"
            }

            & git -C $repoRoot cat-file -e "$serverWorktreeCommit^{commit}" 2>$null
            if ($LASTEXITCODE -ne 0) {
                throw "server worktree commit is not available in local git object database"
            }
            & git -C $repoRoot cat-file -e "$originMainCommit^{commit}" 2>$null
            if ($LASTEXITCODE -ne 0) {
                throw "origin main commit is not available in local git object database"
            }

            $deltaFiles = @(& git -C $repoRoot diff --name-only $serverWorktreeCommit $originMainCommit)
            foreach ($path in @($deltaFiles)) {
                if ([string]::IsNullOrWhiteSpace($path)) {
                    continue
                }
                if ((Get-DeltaPathKind -Path $path) -eq "runtime") {
                    $runtimeDeltaFiles += $path
                } else {
                    $docsToolingDeltaFiles += $path
                }
            }
            $localEvidence = $true
            if ($runtimeDeltaFiles.Count -gt 0) {
                $classification = "RUNTIME_DRIFT"
            } else {
                $classification = "DOCS_TOOLING_ONLY_DRIFT"
            }
        } catch {
            $classification = "NO_LOCAL_EVIDENCE"
            $errorText = $_.Exception.Message
        }
    }

    return [pscustomobject]@{
        status = $classification
        localEvidence = $localEvidence
        deltaFiles = @($deltaFiles)
        docsToolingDeltaFiles = @($docsToolingDeltaFiles)
        runtimeDeltaFiles = @($runtimeDeltaFiles)
        error = $errorText
    }
}

function Add-OriginDeltaLocalClassification {
    param([string]$DeploymentMetadata)

    $classification = Get-OriginDeltaLocalClassification -DeploymentMetadata $DeploymentMetadata
    Write-Host ""
    Write-Host "[live-readiness-bundle] origin delta local classifier"
    if (-not [string]::IsNullOrWhiteSpace($classification.error)) {
        Write-Host "origin_delta_local_error=$($classification.error)"
    }
    Write-Host "origin_delta_local_evidence=$($classification.localEvidence.ToString().ToLowerInvariant())"
    Write-Host "origin_delta_status=$($classification.status)"
    Write-Host "origin_delta_files=$(@($classification.deltaFiles).Count)"
    Write-Host "origin_docs_tooling_delta_files=$(@($classification.docsToolingDeltaFiles).Count)"
    Write-Host "origin_runtime_delta_files=$(@($classification.runtimeDeltaFiles).Count)"
    Write-Host ("origin_runtime_delta_paths=" + (ConvertTo-Json -Compress @($classification.runtimeDeltaFiles)))

    $extra = @(
        "origin_delta_local_evidence=$($classification.localEvidence.ToString().ToLowerInvariant())",
        "origin_delta_status=$($classification.status)",
        "origin_delta_files=$(@($classification.deltaFiles).Count)",
        "origin_docs_tooling_delta_files=$(@($classification.docsToolingDeltaFiles).Count)",
        "origin_runtime_delta_files=$(@($classification.runtimeDeltaFiles).Count)",
        ("origin_runtime_delta_paths=" + (ConvertTo-Json -Compress @($classification.runtimeDeltaFiles)))
    )
    if (-not [string]::IsNullOrWhiteSpace($classification.error)) {
        $extra = @("origin_delta_local_error=$($classification.error)") + $extra
    }
    return (($DeploymentMetadata, ($extra -join "`n")) -join "`n")
}

function Test-OriginDeltaRequiresDeploy {
    param([string]$DeploymentMetadata)

    if ($DeploymentMetadata -match "liveBundleOriginStatus=CURRENT_ORIGIN_MAIN") {
        return $false
    }
    if ($DeploymentMetadata -match "liveBundleOriginStatus=WORKTREE_NOT_ORIGIN_MAIN" `
            -and $DeploymentMetadata -match "origin_delta_status=DOCS_TOOLING_ONLY_DRIFT") {
        return $false
    }
    return $true
}

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
    if (Test-OriginDeltaRequiresDeploy -DeploymentMetadata $DeploymentMetadata) {
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
    if ($DeploymentMetadata -match "origin_delta_status=([A-Z_]+)") {
        Write-Host ("origin_delta_status=" + $Matches[1])
    }
    if ($DeploymentMetadata -match "origin_runtime_delta_files=([0-9]+)") {
        Write-Host ("origin_runtime_delta_files=" + $Matches[1])
    }
}

function Assert-DeploymentMetadataCurrentOrStop {
    param([string]$DeploymentMetadata)

    $deployRequirement = Get-LiveReadinessDeployRequirement -DeploymentMetadata $DeploymentMetadata
    if ($deployRequirement -ne "true" -or $ContinueWhenRuntimeStale) {
        return
    }

    Write-Host ""
    Write-Host "[live-readiness-bundle] stale deployment metadata"
    Write-Host "read_only_bundle_error=DEPLOYED_RUNTIME_NOT_CURRENT"
    Write-Host "read_only_bundle_error_detail=deployment metadata is stale; child smokes were skipped because stale runtime output is not complete live-readiness evidence"
    Write-Host "read_only_bundle_error_boundary=not complete live-readiness evidence; deploy and verify separately, then rerun the full bundle"
    Write-PartialDeploymentMetadata -DeploymentMetadata $DeploymentMetadata
    $partialBlockers = @("LIVE_READINESS_EVIDENCE_UNAVAILABLE", "DEPLOYED_RUNTIME_NOT_CURRENT")
    Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress $partialBlockers))
    Write-Host ("bundle_blocker_summary=" + (ConvertTo-Json -Compress -Depth 4 @(New-BlockerSummary -Blockers $partialBlockers)))
    Write-Host "live_review_packet_allowed=false"
    Write-Host "deploy_required_before_live_review=true"
    Write-Host "bundle_verdict=NO_EVIDENCE"
    Write-Host "next_action=Deploy and verify the current origin/main separately, then rerun this full read-only bundle. Use -ContinueWhenRuntimeStale only for diagnostic stale-runtime child-smoke output."
    throw "deployment metadata is stale; full live-readiness evidence was not collected."
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
        $partialBlockers = @("LIVE_READINESS_EVIDENCE_UNAVAILABLE", "DEPLOYED_RUNTIME_NOT_CURRENT")
    } else {
        $partialBlockers = @("LIVE_READINESS_EVIDENCE_UNAVAILABLE")
    }
    Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress $partialBlockers))
    Write-Host ("bundle_blocker_summary=" + (ConvertTo-Json -Compress -Depth 4 @(New-BlockerSummary -Blockers $partialBlockers)))
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
    .gitattributes|.gitignore|AGENTS.md|INTERNAL_API_TODO.md|README.md|SERVICE_BOUNDARY.md|SPLIT_PROGRESS.md|src/test/*|docs/*|deploy.sh|scripts/*.ps1|scripts/install_nginx_path.sh|scripts/rewrite_nginx_trading_routes.awk|scripts/check_server_runtime_log.sh|scripts/verify_server.sh)
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
    $remoteScriptForSsh = $remoteScript.TrimStart([char]0xFEFF)
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = $remoteScriptForSsh | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "sed '1s/^\xEF\xBB\xBF//' | tr -d '\r' | bash -s" 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
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

function New-BlockerSummary {
    param([string[]]$Blockers)

    $items = [System.Collections.Generic.List[object]]::new()
    foreach ($blocker in @($Blockers | Select-Object -Unique)) {
        $category = "review"
        $requiredEvidence = ".\scripts\smoke_live_readiness_bundle_ssh.ps1"
        $nextAction = "Review the blocker remediation matrix and rerun the full read-only bundle after the blocker is addressed."
        $evidenceMarkers = @("bundle_blockers includes $blocker")

        switch ($blocker) {
            "LIVE_READINESS_NOT_READY" {
                $category = "audit"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("verdict=NOT_READY", "missing verdict=READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED")
                $nextAction = "Wait for audit verdict READY_FOR_OPERATOR_REVIEW_NOT_LIVE_ENABLED; this is not live approval."
            }
            "ORDER_CAPABLE_FLAGS_REVIEW" {
                $category = "safety"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("order_capable_flags_true is non-empty", "missing order_capable_flags_true=[]")
                $nextAction = "Reconcile order-capable flags before any live proposal."
            }
            "SECRET_PREREQUISITES_MISSING" {
                $category = "ops"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("masked OKX credential presence is not SET")
                $nextAction = "Fix masked secret prerequisites through a separately authorized ops change."
            }
            "RUNTIME_HEALTH_OR_LOG_NOT_CLEAN" {
                $category = "runtime"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1; scripts/check_server_runtime_log.sh"
                $evidenceMarkers = @("health is not UP", "runtime_log_status is not PASS")
                $nextAction = "Investigate health/log failures before any live proposal."
            }
            "EVENT_RISK_NOT_BASELINE" {
                $category = "risk"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("riskLevel is not R0", "EVENT_RISK_NOT_R0")
                $nextAction = "Wait for riskLevel=R0 or get separate event-risk operating approval."
            }
            "MCP_AUDIT_TOOL_ERROR" {
                $category = "mcp"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("MCP_TOOL_ERROR", "READINESS_DETAILS_MISSING_FIELDS", "missing_readiness_detail_fields is non-empty")
                $nextAction = "Fix MCP readiness-detail evidence before trusting bundle output."
            }
            "EXECUTION_ELIGIBILITY_NOT_READY" {
                $category = "execution-gate"
                $requiredEvidence = ".\scripts\audit_live_readiness_ssh.ps1"
                $evidenceMarkers = @("*_NOT_EXECUTION_ELIGIBLE", "readiness_details executionEligible is not true")
                $nextAction = "Keep live disabled until tiny-live and ScoreBuy execution gates are explicitly eligible."
            }
            "BACKGROUND_AUTOMATION_REVIEW" {
                $category = "background-automation"
                $requiredEvidence = ".\scripts\smoke_live_background_automation_ssh.ps1 -RequireClear"
                $evidenceMarkers = @("backgroundAutomationClear=false", "high_risk_background_automation_true is non-empty", "background_automation_blockers is non-empty", "background_automation_review_plan missing or still has TRUE/MISSING entries")
                $nextAction = "Review or separately authorize production background automation env diff; do not apply from this bundle."
            }
            "RUNTIME_EVIDENCE_CONFIG_DISABLED" {
                $category = "runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("diagnosis=CONFIG_DISABLED")
                $nextAction = "Collect evidence through a separately authorized evidence-only change before any live execution flag."
            }
            "RUNTIME_EVIDENCE_NO_CANONICAL_ROWS" {
                $category = "runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("diagnosis=NO_CANONICAL_ROWS")
                $nextAction = "Continue evidence collection until canonical runtime rows exist."
            }
            "RUNTIME_EVIDENCE_NO_SHADOW_INTENT" {
                $category = "runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("shadowIntentCount is 0 or missing", "diagnosis=CANONICAL_ROWS_NO_SHADOW_INTENT")
                $nextAction = "Require canonical shadow intent evidence with orderSentEvidence=0."
            }
            "RUNTIME_EVIDENCE_ORDER_SENT" {
                $category = "runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("orderSentEvidence is greater than 0")
                $nextAction = "Stop live review and investigate why order-sent evidence exists."
            }
            "RUNTIME_EVIDENCE_REVIEW_REQUIRED" {
                $category = "runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("diagnosis=REVIEW_RUNTIME_EVIDENCE_STATUS", "missing_runtime_evidence_fields is non-empty", "missing orderSentEvidence=0", "runtime_evidence_review_plan has BLOCKED/HARD_BLOCKED entries")
                $nextAction = "Review unrecognized or incomplete runtime-evidence status before any live proposal."
            }
            "TINY_LIVE_LOSS_HARD_STOP" {
                $category = "tiny-live"
                $requiredEvidence = ".\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear"
                $evidenceMarkers = @("hardStopDetected=true", "AUTO_APPROVAL_DISABLED_CONSECUTIVE_TINY_LIVE_LOSSES", "missing_tiny_live_hard_stop_fields is non-empty")
                $nextAction = "Clear consecutive-loss hard stop with fresh read-only evidence before live review."
            }
            "TINY_LIVE_ROLLOUT_NOT_READY" {
                $category = "tiny-live"
                $requiredEvidence = ".\scripts\smoke_tiny_live_loss_rca_ssh.ps1 -RequireClear"
                $evidenceMarkers = @("canEnableProduction is not true", "missing_tiny_live_rollout_fields is non-empty")
                $nextAction = "Wait for rollout gates such as completed samples, false-positive count, and canEnableProduction."
            }
            "SIGNAL_POLICY_REVIEW_GAPS" {
                $category = "signal-policy"
                $requiredEvidence = ".\scripts\smoke_signal_correctness_ssh.ps1 -RequireClear"
                $evidenceMarkers = @("REVIEW_POLICY_GAPS", "governanceMode=TOO_STRICT/TOO_LOOSE/INSUFFICIENT_DATA", "missed opportunity overallStatus is WARN or FAIL", "signalPolicyClear is not true", "signal_policy_review_plan missing or still has BLOCKED/REVIEW entries")
                $nextAction = "Resolve signal governance and missed-opportunity gaps in shadow/tiny-live caps only."
            }
            "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE" {
                $category = "local-tradingview"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireCurrentCandidate"
                $evidenceMarkers = @("currentCandidateStatus is not HAS_CURRENT_BUY_CANDIDATE", "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
                $nextAction = "Wait for the latest closed bar to emit a LOCAL_TRADINGVIEW parity BUY before any live plan."
            }
            "LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_NOT_ARMED" {
                $category = "local-tradingview"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireDryRunArmed"
                $evidenceMarkers = @("localTradingViewExecutionDryRunArmed=false", "LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED")
                $nextAction = "Use the read-only dry-run receipt env handoff packet and obtain separate exact env/deploy authorization before changing production env."
            }
            "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED" {
                $category = "local-tradingview"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireLiveMicroArmed"
                $evidenceMarkers = @("executionMode=LIVE_MICRO", "localTradingViewLiveMicroArmed=true")
                $nextAction = "Fix LOCAL_TRADINGVIEW LIVE_MICRO source/execution env through a separately authorized env plan."
            }
            "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED" {
                $category = "local-tradingview-oco"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1 -RequireOcoLifecycleTracked"
                $evidenceMarkers = @("localTradingViewOcoLifecycleTracked=false", "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED", "TRADING_OCO_POLLER_ENABLED=false")
                $nextAction = "Do not rely on LIVE_MICRO buys until OCO close detection is reviewed and separately authorized."
            }
            "LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE" {
                $category = "local-tradingview"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1"
                $evidenceMarkers = @("primary is not LOCAL_TRADINGVIEW", "localEnabled=false", "LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE")
                $nextAction = "Keep live disabled and fix LOCAL_TRADINGVIEW source/evaluator env through a separately authorized env plan."
            }
            "LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK" {
                $category = "local-tradingview"
                $requiredEvidence = ".\scripts\smoke_local_tradingview_candidate_ssh.ps1"
                $evidenceMarkers = @("coverage is not OK/WARN", "LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK")
                $nextAction = "Fix local TradingView parity data coverage before treating candidate evidence as valid."
            }
            "LOCAL_TRADINGVIEW_OCO_PREFLIGHT_FAILED" {
                $category = "local-tradingview-oco"
                $requiredEvidence = ".\scripts\smoke_live_readiness_bundle_ssh.ps1; .\scripts\smoke_local_tradingview_candidate_ssh.ps1"
                $evidenceMarkers = @("OCO_PREFLIGHT_FAILED is present without the pending-until-buy-candidate marker")
                $nextAction = "Stop live review and inspect TP/SL/OCO feasibility before any live order path."
            }
            "LOCAL_TRADINGVIEW_ORDER_SENT_EVIDENCE" {
                $category = "local-tradingview-runtime-evidence"
                $requiredEvidence = ".\scripts\smoke_runtime_evidence_rca_ssh.ps1 -RequireReady"
                $evidenceMarkers = @("orderSentEvidence is greater than 0")
                $nextAction = "Stop live review and investigate why order-sent evidence exists in the evidence-only window."
            }
            "MCP_PARITY_NOT_PROVEN" {
                $category = "mcp"
                $requiredEvidence = ".\scripts\smoke_mcp_parity_ssh.ps1"
                $evidenceMarkers = @("missing required_tools list", "missing_required_tools is non-empty", "missing [mcp-parity-ssh] OK")
                $nextAction = "Restore required read-only Trading MCP tools on server-local /api/mcp."
            }
            "DEPLOYED_RUNTIME_NOT_CURRENT" {
                $category = "deployment-metadata"
                $requiredEvidence = ".\scripts\smoke_live_deployment_metadata_ssh.ps1; .\scripts\smoke_live_origin_delta_local.ps1; .\scripts\smoke_live_readiness_bundle_ssh.ps1"
                $evidenceMarkers = @("liveBundleDeployStatus is not CURRENT/DOCS_TOOLING_ONLY_DRIFT", "origin_delta_status is RUNTIME_DRIFT/NO_LOCAL_EVIDENCE", "liveBundleOriginStatus is UNKNOWN_ORIGIN_MAIN")
                $nextAction = "If origin_delta_status=RUNTIME_DRIFT, deploy and verify current origin/main separately. If origin_delta_status=DOCS_TOOLING_ONLY_DRIFT, rerun/read the full bundle output instead of treating currentness as a runtime deploy blocker."
            }
            "LIVE_READINESS_EVIDENCE_UNAVAILABLE" {
                $category = "evidence"
                $requiredEvidence = ".\scripts\smoke_live_readiness_bundle_ssh.ps1"
                $evidenceMarkers = @("SSH_AUTH_FAILED", "SSH_CONNECT_FAILED", "SSH_COMMAND_FAILED", "READ_ONLY_SMOKE_FAILED")
                $nextAction = "Fix SSH access or the failing child smoke before drawing a live-readiness conclusion."
            }
        }

        $items.Add([pscustomobject]@{
                blocker = $blocker
                category = $category
                requiredEvidence = $requiredEvidence
                evidenceMarkers = $evidenceMarkers
                nextAction = $nextAction
            })
    }
    return @($items)
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
$deploymentMetadata = Add-OriginDeltaLocalClassification -DeploymentMetadata $deploymentMetadata
$script:LiveReadinessDeploymentMetadataSnapshot = $deploymentMetadata
Assert-DeploymentMetadataCurrentOrStop -DeploymentMetadata $deploymentMetadata
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
$localTradingView = Invoke-ReadOnlySmoke -Name "local-tradingview-candidate" -ScriptName "smoke_local_tradingview_candidate_ssh.ps1" -Arguments ($common + @{
        StrategyId = $LocalTradingViewStrategyId
        Symbol = $Symbol
        IntervalCode = $LocalTradingViewIntervalCode
        Days = $LocalTradingViewDays
        Source = $LocalTradingViewSource
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
        -or $background -match "backgroundAutomationClear=false" `
        -or $background -match "background_automation_blockers=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $background -match "missing_background_automation_flags=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $background -match "high_risk_background_automation_true=\[[^\]]*[A-Z0-9_]+[^\]]*\]" `
        -or $background -match "NOT_READY_BACKGROUND_AUTOMATION_REVIEW" `
        -or $background -notmatch "background_automation_review_plan=" `
        -or ($background -match "backgroundAutomationClear=true" -and $background -match '"state"\s*:\s*"TRUE"') `
        -or ($background -match "backgroundAutomationClear=true" -and $background -match '"state"\s*:\s*"MISSING"') `
        -or $background -notmatch "verdict=OK_BACKGROUND_AUTOMATION_DISABLED" `
        -or $background -notmatch "backgroundAutomationClear=true" `
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
        -or $runtimeEvidence -notmatch "runtime_evidence_review_plan=" `
        -or ($runtimeEvidence -match "diagnosis=CANONICAL_SHADOW_READY" -and $runtimeEvidence -match '"state"\s*:\s*"HARD_BLOCKED"') `
        -or ($runtimeEvidence -match "diagnosis=CANONICAL_SHADOW_READY" -and $runtimeEvidence -match '"state"\s*:\s*"BLOCKED"') `
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
        -or $signal -notmatch "signalPolicyClear=true" `
        -or $signal -notmatch "signal_policy_review_plan=" `
        -or ($signal -match "signalPolicyClear=true" -and $signal -match '"state"\s*:\s*"BLOCKED"') `
        -or ($signal -match "signalPolicyClear=true" -and $signal -match '"state"\s*:\s*"REVIEW"') `
        -or $signal -notmatch "7d Governance Drift:\s*`r?`n\s*governanceMode=" `
        -or $signal -notmatch "Missed Opportunity Regression:\s*`r?`n\s*overallStatus=PASS") {
    $blockers.Add("SIGNAL_POLICY_REVIEW_GAPS")
}
if ($localTradingView -match "LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE" `
        -or $localTradingView -notmatch "currentCandidateStatus=HAS_CURRENT_BUY_CANDIDATE") {
    $blockers.Add("LOCAL_TRADINGVIEW_NO_CURRENT_BUY_CANDIDATE")
}
$localTradingViewLiveMicroMode = $localTradingView -match "executionMode=LIVE_MICRO"
if ($localTradingViewLiveMicroMode) {
    if ($localTradingView -match "LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED" `
            -or $localTradingView -notmatch "localTradingViewLiveMicroArmed=true") {
        $blockers.Add("LOCAL_TRADINGVIEW_LIVE_MICRO_NOT_ARMED")
    }
    if ($localTradingView -match "LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED" `
            -or $localTradingView -notmatch "localTradingViewOcoLifecycleTracked=true") {
        $blockers.Add("LOCAL_TRADINGVIEW_OCO_LIFECYCLE_NOT_ARMED")
    }
} else {
    if ($localTradingView -match "LOCAL_TRADINGVIEW_DRY_RUN_NOT_ARMED" `
            -or $localTradingView -notmatch "localTradingViewExecutionDryRunArmed=true") {
        $blockers.Add("LOCAL_TRADINGVIEW_DRY_RUN_RECEIPT_NOT_ARMED")
    }
}
if ($localTradingView -match "LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE" `
        -or $localTradingView -notmatch "localTradingViewEvaluatorActive=true") {
    $blockers.Add("LOCAL_TRADINGVIEW_EVALUATOR_NOT_ACTIVE")
}
if ($localTradingView -match "LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK") {
    $blockers.Add("LOCAL_TRADINGVIEW_DATA_COVERAGE_NOT_OK")
}
if ($localTradingView -match "orderSentAllowed=true|liveOrderMutationAllowed=true") {
    $blockers.Add("LOCAL_TRADINGVIEW_ORDER_SENT_EVIDENCE")
}
if ($runtimeEvidence -match "orderSentEvidence=([1-9][0-9]*)") {
    $blockers.Add("LOCAL_TRADINGVIEW_ORDER_SENT_EVIDENCE")
}
if (($audit -match "OCO_PREFLIGHT_FAILED" -or $tinyLive -match "OCO_PREFLIGHT_FAILED" -or $signal -match "OCO_PREFLIGHT_FAILED") `
        -and $audit -notmatch "ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL" `
        -and $tinyLive -notmatch "ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL" `
        -and $signal -notmatch "ocoPreflightPendingUntilBuyCandidate=NOT_READY_MISSING_ENTRY_TP_SL") {
    $blockers.Add("LOCAL_TRADINGVIEW_OCO_PREFLIGHT_FAILED")
}
if ($mcpParity -notmatch "\[mcp-parity-ssh\] OK" `
        -or $mcpParity -notmatch "required_tools=\[[^\]]+\]" `
        -or $mcpParity -match "missing_required_tools=\[[^\]]*[A-Za-z0-9_]+[^\]]*\]" `
        -or $mcpParity -notmatch "missing_required_tools=\[\]") {
    $blockers.Add("MCP_PARITY_NOT_PROVEN")
}
if ($deploymentMetadata -match "liveBundleDeployStatus=(RUNTIME_DRIFT|UNKNOWN_DEPLOY_METADATA)" `
        -or $deploymentMetadata -notmatch "liveBundleDeployStatus=(CURRENT|DOCS_TOOLING_ONLY_DRIFT)") {
    $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
}
if (Test-OriginDeltaRequiresDeploy -DeploymentMetadata $deploymentMetadata) {
    $blockers.Add("DEPLOYED_RUNTIME_NOT_CURRENT")
}

$uniqueBlockers = @($blockers | Select-Object -Unique)
$blockerSummary = @(New-BlockerSummary -Blockers $uniqueBlockers)
Write-Host ""
Write-Host "[live-readiness-bundle] summary"
if ($deploymentMetadata -match "liveBundleDeployStatus=([A-Z_]+)") {
    Write-Host ("deployment_metadata_status=" + $Matches[1])
}
if ($deploymentMetadata -match "liveBundleOriginStatus=([A-Z_]+)") {
    Write-Host ("origin_metadata_status=" + $Matches[1])
}
if ($deploymentMetadata -match "origin_delta_status=([A-Z_]+)") {
    Write-Host ("origin_delta_status=" + $Matches[1])
}
if ($deploymentMetadata -match "origin_runtime_delta_files=([0-9]+)") {
    Write-Host ("origin_runtime_delta_files=" + $Matches[1])
}
if ($deploymentMetadata -match "origin_docs_tooling_delta_files=([0-9]+)") {
    Write-Host ("origin_docs_tooling_delta_files=" + $Matches[1])
}
if ($localTradingView -match "currentCandidateStatus=([A-Z0-9_]+)") {
    Write-Host ("local_tradingview_current_candidate_status=" + $Matches[1])
}
if ($localTradingView -match "localTradingViewExecutionDryRunArmed=(true|false)") {
    Write-Host ("local_tradingview_dry_run_receipt_armed=" + $Matches[1])
}
if ($localTradingView -match "localTradingViewLiveMicroArmed=(true|false)") {
    Write-Host ("local_tradingview_live_micro_armed=" + $Matches[1])
}
if ($localTradingView -match "localTradingViewExecutionPathArmed=(true|false)") {
    Write-Host ("local_tradingview_execution_path_armed=" + $Matches[1])
}
if ($localTradingView -match "localTradingViewOcoLifecycleTracked=(true|false)") {
    Write-Host ("local_tradingview_oco_lifecycle_tracked=" + $Matches[1])
}
if ($localTradingView -match "localTradingViewOcoLifecycleStatus=([A-Z0-9_]+)") {
    Write-Host ("local_tradingview_oco_lifecycle_status=" + $Matches[1])
}
if ($localTradingView -match "executionMode=([A-Z0-9_]+)") {
    Write-Host ("local_tradingview_execution_mode=" + $Matches[1])
}
if ($runtimeEvidence -match "orderSentEvidence=([0-9]+)") {
    Write-Host ("runtime_order_sent_evidence=" + $Matches[1])
}
if ($audit -match "ocoPreflightPendingUntilBuyCandidate=([^`"'\]\s,]+)" `
        -or $tinyLive -match "ocoPreflightPendingUntilBuyCandidate=([^`"'\]\s,]+)" `
        -or $signal -match "ocoPreflightPendingUntilBuyCandidate=([^`"'\]\s,]+)") {
    Write-Host ("oco_preflight_pending_until_buy_candidate=" + $Matches[1])
}
Write-Host ("bundle_blockers=" + (ConvertTo-Json -Compress $uniqueBlockers))
Write-Host ("bundle_blocker_summary=" + (ConvertTo-Json -Compress -Depth 4 $blockerSummary))
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
