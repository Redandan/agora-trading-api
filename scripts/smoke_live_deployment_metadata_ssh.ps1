param(
    [string]$SshHost = $env:AGORA_SSH_HOST,
    [string]$SshKey = $env:AGORA_SSH_KEY,
    [string]$AppDir = "/home/ubuntu/agora-trading-api",
    [switch]$RequireCurrent
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

Assert-SshHostSafe -Name "SshHost" -Value $SshHost
Assert-RemotePathSafe -Name "AppDir" -Value $AppDir

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

echo "[live-deployment-metadata] read-only server commit probe"
echo "scope=READ_ONLY; no production env, DB, order, OCO, grid, fund, Earn, Telegram, scheduler, exchange, external backfill/import, deploy, restart, or nginx state changed."
echo "refreshType=DEPLOYMENT_METADATA_ONLY"
echo "worktreeCommit=`${HEAD_COMMIT:-UNKNOWN}"
echo "originMainCommit=`${ORIGIN_MAIN_COMMIT:-UNKNOWN}"
echo "deployedCommit=`${DEPLOYED_COMMIT:-MISSING}"

origin_status="UNKNOWN_ORIGIN_MAIN"
if [ -n "`$ORIGIN_MAIN_COMMIT" ]; then
  if [ "`$HEAD_COMMIT" = "`$ORIGIN_MAIN_COMMIT" ]; then
    origin_status="CURRENT_ORIGIN_MAIN"
  else
    origin_status="WORKTREE_NOT_ORIGIN_MAIN"
  fi
fi
echo "liveBundleOriginStatus=`$origin_status"
echo "origin_metadata_status=`$origin_status"

deploy_status="UNKNOWN_DEPLOY_METADATA"
runtime_delta=0
docs_tooling_delta=0
if [ -n "`$HEAD_COMMIT" ] && [ -n "`$DEPLOYED_COMMIT" ]; then
  if [ "`$HEAD_COMMIT" = "`$DEPLOYED_COMMIT" ]; then
    deploy_status="CURRENT"
    echo "deploymentDeltaFiles=0"
  elif git cat-file -e "`$DEPLOYED_COMMIT^{commit}" 2>/dev/null; then
    while IFS= read -r path; do
      [ -z "`$path" ] && continue
      kind="`$(classify_path "`$path")"
      if [ "`$kind" = "runtime" ]; then
        runtime_delta=`$((runtime_delta + 1))
      else
        docs_tooling_delta=`$((docs_tooling_delta + 1))
      fi
    done <<EOF
`$(git diff --name-only "`$DEPLOYED_COMMIT" "`$HEAD_COMMIT")
EOF
    echo "deploymentDeltaFiles=`$((runtime_delta + docs_tooling_delta))"
    echo "deploymentDeltaRuntimeFiles=`$runtime_delta"
    echo "deploymentDeltaDocsToolingFiles=`$docs_tooling_delta"
    if [ "`$runtime_delta" -gt 0 ]; then
      deploy_status="RUNTIME_DRIFT"
    else
      deploy_status="DOCS_TOOLING_ONLY_DRIFT"
    fi
  fi
fi

echo "liveBundleDeployStatus=`$deploy_status"
echo "deployment_metadata_status=`$deploy_status"

if [ "`$deploy_status" = "CURRENT" ] || [ "`$deploy_status" = "DOCS_TOOLING_ONLY_DRIFT" ]; then
  deploy_current="true"
else
  deploy_current="false"
fi
if [ "`$origin_status" = "CURRENT_ORIGIN_MAIN" ]; then
  origin_current="true"
else
  origin_current="false"
fi

if [ "`$deploy_current" = "true" ] && [ "`$origin_current" = "true" ]; then
  echo "metadata_current=true"
  echo "metadata_blockers=[]"
  echo "deploy_required_before_live_review=false"
else
  echo "metadata_current=false"
  echo 'metadata_blockers=["DEPLOYED_RUNTIME_NOT_CURRENT"]'
  echo "deploy_required_before_live_review=true"
fi

echo "live_review_packet_allowed=false"
echo "bundle_verdict=NO_EVIDENCE_FOR_LIVE_REVIEW_METADATA_ONLY"
echo "metadata_boundary=metadata-only; rerun the full live-readiness bundle after deploy before drawing any live-readiness conclusion."
echo "[live-deployment-metadata] read-only check complete"
"@

$output = $remoteScript | ssh -i $SshKey -o BatchMode=yes -o ConnectTimeout=10 $SshHost "tr -d '\r' | bash -s"
$exitCode = $LASTEXITCODE
$output | ForEach-Object { Write-Host $_ }
if ($exitCode -ne 0) {
    throw "deployment metadata smoke failed with exit code $exitCode"
}

$text = ($output -join "`n")
if ($RequireCurrent -and $text -match 'metadata_current=false') {
    throw "deployment metadata is not current; DEPLOYED_RUNTIME_NOT_CURRENT remains."
}
