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

function Assert-FailsBeforeSsh {
    param(
        [string[]]$Arguments,
        [string]$ExpectedPattern
    )

    $script = Join-Path $PSScriptRoot "smoke_post_deploy_profit_validation_ssh.ps1"
    $powerShell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($null -eq $powerShell) {
        $powerShell = Get-Command pwsh -ErrorAction SilentlyContinue
    }
    if ($null -eq $powerShell) {
        throw "Unable to find powershell or pwsh for post-deploy profit validation test"
    }

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $powerShell.Source -NoProfile -ExecutionPolicy Bypass -File $script @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    $text = ($output | Out-String)
    if ($exitCode -eq 0) {
        throw "post-deploy profit validation accepted invalid input"
    }
    if ($text -notmatch $ExpectedPattern) {
        throw "post-deploy profit validation did not fail with expected pattern $ExpectedPattern`n$text"
    }
    if ($text -match "Could not resolve hostname|Connection timed out|Permission denied|remote command failed") {
        throw "post-deploy profit validation reached SSH before local input guard:`n$text"
    }
}

function Assert-ReviewPlanShape {
    param([string]$Json)

    $items = @($Json | ConvertFrom-Json -ErrorAction Stop | ForEach-Object { $_ })
    if ($items.Count -ne 3) {
        throw "post-deploy profit validation review plan must contain 3 child gate entries, got $($items.Count)"
    }

    $expectedGates = @("auto-trading-review", "profit-loss-review", "profit-experiment-review")
    foreach ($gate in $expectedGates) {
        if (-not @($items.gate).Contains($gate)) {
            throw "post-deploy profit validation review plan missing gate: $gate"
        }
    }

    foreach ($item in $items) {
        foreach ($field in @("gate", "state", "status", "riskCategory", "recommendation", "allowedFlag", "allowed", "requiredEvidence", "nextAction", "notAuthorization")) {
            if ($null -eq $item.PSObject.Properties[$field]) {
                throw "post-deploy profit validation review plan entry missing field: $field"
            }
        }
        if (@($item.requiredEvidence).Count -eq 0) {
            throw "post-deploy profit validation review plan entry must preserve requiredEvidence: $($item.gate)"
        }
        if ($item.notAuthorization -notmatch "does not authorize live trading") {
            throw "post-deploy profit validation review plan entry must preserve no-live authorization text: $($item.gate)"
        }
    }
}

function Assert-BlockerSummaryShape {
    param([string]$Json)

    $items = @($Json | ConvertFrom-Json -ErrorAction Stop | ForEach-Object { $_ })
    if ($items.Count -ne 3) {
        throw "post-deploy profit validation blocker summary must contain 3 blocked child gate entries, got $($items.Count)"
    }

    foreach ($item in $items) {
        foreach ($field in @("gate", "status", "riskCategory", "allowedFlag", "allowed", "requiredEvidenceCount", "requiredEvidence", "nextAction", "runtimeDrift", "notAuthorization")) {
            if ($null -eq $item.PSObject.Properties[$field]) {
                throw "post-deploy profit validation blocker summary entry missing field: $field"
            }
        }
        if ($item.requiredEvidenceCount -ne @($item.requiredEvidence).Count) {
            throw "post-deploy profit validation blocker summary requiredEvidenceCount mismatch: $($item.gate)"
        }
        if ($item.notAuthorization -notmatch "does not authorize live trading") {
            throw "post-deploy profit validation blocker summary entry must preserve no-live authorization text: $($item.gate)"
        }
        foreach ($field in @("originDeltaStatus", "serverWorktreeCommit", "originMainCommit", "runtimeDeltaFiles", "runtimeDeltaPaths")) {
            if ($null -eq $item.runtimeDrift.PSObject.Properties[$field]) {
                throw "post-deploy profit validation blocker summary runtimeDrift missing field: $field"
            }
        }
        if ($item.runtimeDrift.runtimeDeltaFiles -ne @($item.runtimeDrift.runtimeDeltaPaths).Count) {
            throw "post-deploy profit validation blocker summary runtimeDeltaFiles mismatch: $($item.gate)"
        }
    }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$scriptPath = Join-Path $PSScriptRoot "smoke_post_deploy_profit_validation_ssh.ps1"
$verifyPath = Join-Path $PSScriptRoot "verify_local.ps1"
$readmePath = Join-Path $repoRoot "README.md"
$runbookPath = Join-Path $repoRoot "docs/deploy-runbook.md"
$progressPath = Join-Path $repoRoot "SPLIT_PROGRESS.md"

$scriptText = Get-Content -Raw -LiteralPath $scriptPath
$verifyText = Get-Content -Raw -LiteralPath $verifyPath
$docsText = @(
    Get-Content -Raw -LiteralPath $readmePath
    Get-Content -Raw -LiteralPath $runbookPath
    Get-Content -Raw -LiteralPath $progressPath
) -join "`n"

foreach ($marker in @(
        "[post-deploy-profit-validation] read-only validation bundle",
        "scope=READ_ONLY",
        "prepare_auto_trading_review_gate_ssh.ps1",
        "prepare_profit_loss_review_gate_ssh.ps1",
        "prepare_profit_experiment_gate_ssh.ps1",
        "smoke_live_origin_delta_local.ps1",
        "origin_delta_status",
        "server_worktree_commit",
        "origin_main_commit",
        "origin_delta_files",
        "origin_docs_tooling_delta_files",
        "origin_runtime_delta_files",
        "origin_runtime_delta_paths",
        "monthlyPnlTotalUsdt",
        "auto_trading_review_gate_status",
        "profit_loss_review_gate_status",
        "profit_experiment_gate_status",
        "deploy_required_before_post_deploy_profit_validation",
        "operator_review_packet_allowed",
        "loss_source_review_allowed",
        "shadow_experiment_review_allowed",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false",
        "post_deploy_profit_validation_missing_requirements",
        "post_deploy_profit_validation_review_plan",
        "post_deploy_profit_validation_blocker_summary",
        "post_deploy_profit_validation_status",
        "post_deploy_profit_validation_next_action",
        "BLOCKED_DEPLOY_CURRENT_RUNTIME",
        "READY_FOR_READ_ONLY_PROFIT_REVIEW_NOT_LIVE",
        "OBSERVE_NO_ACTION_FROM_CURRENT_EVIDENCE",
        "BLOCKED_COLLECT_READ_ONLY_EVIDENCE",
        "NO_EVIDENCE",
        "RequireReady",
        "notAuthorization=read-only validation only",
        "Assert-SshHostSafe",
        "Assert-RemotePathSafe",
        "Assert-SmokeTokenSafe",
        "Convert-JsonArrayOrEmpty",
        "Add-MissingRequirement",
        "Test-OriginDeltaAcceptableForProfitReview",
        "Invoke-OriginDeltaClassifier",
        "New-ProfitValidationReviewPlanEntry",
        "Assert-ProfitValidationReviewPlanShape",
        "New-ProfitValidationBlockerSummary",
        "Assert-ProfitValidationBlockerSummaryShape",
        "riskCategory",
        "runtimeDrift",
        "originDeltaStatus",
        "runtimeDeltaFiles",
        "runtimeDeltaPaths",
        "requiredEvidence",
        "requiredEvidenceCount",
        "nextAction",
        "post-deploy profit validation review plan must contain 3 child gate entries",
        "post-deploy profit validation review plan entry missing field",
        "post-deploy profit validation review plan entry must preserve requiredEvidence",
        "post-deploy profit validation review plan entry must preserve no-live authorization text",
        "post-deploy profit validation blocker summary entry missing field",
        "post-deploy profit validation blocker summary requiredEvidenceCount mismatch",
        "post-deploy profit validation blocker summary entry must preserve no-live authorization text",
        "post-deploy profit validation blocker summary runtimeDrift missing field",
        "post-deploy profit validation blocker summary runtimeDeltaFiles mismatch",
        "operator_review_packet_allowed",
        "loss_source_review_allowed",
        "shadow_experiment_review_allowed",
        "position-risk-and-live-execution-readiness",
        "loss-source-and-datafreshness-counterfactual",
        "shadow-experiment-and-policy-counterfactual",
        "CURRENT_ORIGIN_MAIN",
        "DOCS_TOOLING_ONLY_DRIFT",
        "origin_delta_classifier missing",
        "origin_runtime_delta_paths missing",
        "origin_delta_status must be CURRENT_ORIGIN_MAIN or DOCS_TOOLING_ONLY_DRIFT"
    )) {
    Assert-Contains -Name "post-deploy profit validation marker" -Text $scriptText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "test_post_deploy_profit_validation.ps1",
        "smoke_post_deploy_profit_validation_ssh.ps1"
    )) {
    Assert-Contains -Name "verify_local includes post-deploy profit validation" -Text $verifyText -Pattern ([regex]::Escape($marker))
}

foreach ($marker in @(
        "smoke_post_deploy_profit_validation_ssh.ps1",
        "post-deploy profit validation",
        "read-only",
        "deploy_required_before_post_deploy_profit_validation",
        "origin_runtime_delta_paths",
        "post_deploy_profit_validation_status",
        "post_deploy_profit_validation_missing_requirements",
        "post_deploy_profit_validation_review_plan",
        "post_deploy_profit_validation_blocker_summary",
        "live_policy_change_allowed=false",
        "position_or_oco_mutation_allowed=false",
        "tiny_live_order_allowed=false"
    )) {
    Assert-Contains -Name "operator docs mention post-deploy profit validation" -Text $docsText -Pattern ([regex]::Escape($marker))
}

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "-oProxyCommand=bad", "-SshKey", ".\README.md") `
    -ExpectedPattern "SshHost contains unsupported characters for ssh target"

Assert-FailsBeforeSsh `
    -Arguments @("-SshHost", "example.invalid", "-SshKey", ".\README.md", "-ReviewDays", "0") `
    -ExpectedPattern "ReviewDays must be between 1 and 180"

$reviewPlanFixture = @'
[
  {
    "gate": "auto-trading-review",
    "state": "BLOCKED",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "position-risk-and-live-execution-readiness",
    "recommendation": "OPERATOR_REVIEW_STRATEGY485_POSITION_RISK",
    "allowedFlag": "operator_review_packet_allowed",
    "allowed": false,
    "requiredEvidence": ["deployed runtime current", "current strategy 485 OCO health"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun the auto-trading review gate.",
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  },
  {
    "gate": "profit-loss-review",
    "state": "BLOCKED",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "loss-source-and-datafreshness-counterfactual",
    "recommendation": "REVIEW_DATAFRESHNESS_FALSE_KILL_WITH_SHADOW_REPLAY",
    "allowedFlag": "loss_source_review_allowed",
    "allowed": false,
    "requiredEvidence": ["deployed runtime current", "fresh DataFreshness replayCandidateId rows"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun profit loss review gate.",
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  },
  {
    "gate": "profit-experiment-review",
    "state": "BLOCKED",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "shadow-experiment-and-policy-counterfactual",
    "recommendation": "DataFreshness false-kill counterfactual",
    "allowedFlag": "shadow_experiment_review_allowed",
    "allowed": false,
    "requiredEvidence": ["deployed runtime current", "entry/TP/SL candidate snapshot"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun replay observation and profit experiment gate.",
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  }
]
'@
Assert-ReviewPlanShape -Json $reviewPlanFixture

$blockerSummaryFixture = @'
[
  {
    "gate": "auto-trading-review",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "position-risk-and-live-execution-readiness",
    "allowedFlag": "operator_review_packet_allowed",
    "allowed": false,
    "requiredEvidenceCount": 2,
    "requiredEvidence": ["deployed runtime current", "current strategy 485 OCO health"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun the auto-trading review gate.",
    "runtimeDrift": {
      "originDeltaStatus": "RUNTIME_DRIFT",
      "serverWorktreeCommit": "ca8d1f24c35872a83b20c40dbb6626e4b8458f23",
      "originMainCommit": "78bff14d64e114e0c714a7934ae098fefc1d1e3e",
      "runtimeDeltaFiles": 2,
      "runtimeDeltaPaths": ["src/main/java/com/agora/service/backtest/DataFreshnessReplayCandidateIds.java", "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java"]
    },
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  },
  {
    "gate": "profit-loss-review",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "loss-source-and-datafreshness-counterfactual",
    "allowedFlag": "loss_source_review_allowed",
    "allowed": false,
    "requiredEvidenceCount": 2,
    "requiredEvidence": ["deployed runtime current", "fresh DataFreshness replayCandidateId rows"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun profit loss review gate.",
    "runtimeDrift": {
      "originDeltaStatus": "RUNTIME_DRIFT",
      "serverWorktreeCommit": "ca8d1f24c35872a83b20c40dbb6626e4b8458f23",
      "originMainCommit": "78bff14d64e114e0c714a7934ae098fefc1d1e3e",
      "runtimeDeltaFiles": 2,
      "runtimeDeltaPaths": ["src/main/java/com/agora/service/backtest/DataFreshnessReplayCandidateIds.java", "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java"]
    },
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  },
  {
    "gate": "profit-experiment-review",
    "status": "BLOCKED_DEPLOY_CURRENT_RUNTIME",
    "riskCategory": "shadow-experiment-and-policy-counterfactual",
    "allowedFlag": "shadow_experiment_review_allowed",
    "allowed": false,
    "requiredEvidenceCount": 2,
    "requiredEvidence": ["deployed runtime current", "entry/TP/SL candidate snapshot"],
    "nextAction": "Separately deploy and verify current origin/main, then rerun replay observation and profit experiment gate.",
    "runtimeDrift": {
      "originDeltaStatus": "RUNTIME_DRIFT",
      "serverWorktreeCommit": "ca8d1f24c35872a83b20c40dbb6626e4b8458f23",
      "originMainCommit": "78bff14d64e114e0c714a7934ae098fefc1d1e3e",
      "runtimeDeltaFiles": 2,
      "runtimeDeltaPaths": ["src/main/java/com/agora/service/backtest/DataFreshnessReplayCandidateIds.java", "src/main/java/com/agora/service/backtest/LiveSignalEvaluator.java"]
    },
    "notAuthorization": "read-only routing evidence only; does not authorize live trading, policy relaxation, deploy, restart, production env mutation, DB changes, order/OCO/grid/fund/Earn/Telegram/exchange mutation, or external backfill/import"
  }
]
'@
Assert-BlockerSummaryShape -Json $blockerSummaryFixture

Write-Host "[post-deploy-profit-validation-test] OK"
