[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repositoryRoot = Split-Path -Parent $PSScriptRoot
$contractPath = Join-Path $repositoryRoot 'docs\contracts\versioned-profit-start-activation-implementation-v1.json'

function Assert-Contract {
    param(
        [Parameter(Mandatory = $true)]
        [bool]$Condition,

        [Parameter(Mandatory = $true)]
        [string]$Message
    )

    if (-not $Condition) {
        throw "[activation-manifest] $Message"
    }
}

Assert-Contract (Test-Path -LiteralPath $contractPath -PathType Leaf) "Missing contract: $contractPath"
$contract = Get-Content -LiteralPath $contractPath -Raw | ConvertFrom-Json

Assert-Contract ($contract.contractVersion -eq 'VERSIONED_PROFIT_START_ACTIVATION_IMPLEMENTATION_MANIFEST_V1') 'Unexpected contractVersion.'
Assert-Contract ($contract.acceptanceContract -eq 'VERSIONED_PROFIT_START_ACCEPTANCE_V1') 'Unexpected acceptanceContract.'
Assert-Contract ($contract.deployedBaseCommit -eq '748a69ea5b9254e9bd79099e460cefc2ab9297dd') 'Deployed base commit drifted.'
Assert-Contract ($contract.status -eq 'SAFE_LOCAL_INTEGRATION_ACTIVATION_BLOCKED') 'Unexpected local integration status.'

Assert-Contract ($contract.currentState.acceptanceState -eq 'DEPLOYED_CODE_VERIFIED_ACTIVATION_BLOCKED') 'Acceptance state must remain activation-blocked.'
Assert-Contract ($contract.currentState.currentCohortId -eq 'NOT_STARTED') 'Cohort must remain NOT_STARTED.'
Assert-Contract ($contract.currentState.canonicalClosedEpisodeCount -eq 'NOT_MEASURABLE') 'Closed count must remain NOT_MEASURABLE before cohort start.'
Assert-Contract ([int]$contract.currentState.exactFeeEpisodeCount -eq 0) 'Exact-fee count must be zero.'
Assert-Contract ([int]$contract.currentState.positiveExactNetEpisodeCount -eq 0) 'Positive exact-net count must be zero.'
Assert-Contract ([bool]$contract.currentState.sessionMustRemainOpen) 'Acceptance session must remain open.'

Assert-Contract (-not [bool]$contract.globalSafety.productionMutationAllowed) 'Production mutation cannot be authorized by this manifest.'
Assert-Contract (-not [bool]$contract.globalSafety.environmentOrEffectiveFromMutationAllowed) 'Environment/effectiveFrom mutation cannot be authorized by this manifest.'
Assert-Contract (-not [bool]$contract.globalSafety.databaseMutationAllowed) 'Database mutation cannot be authorized by this manifest.'
Assert-Contract (-not [bool]$contract.globalSafety.providerOrCollectorEnablementAllowed) 'Provider/collector enablement cannot be authorized by this manifest.'
Assert-Contract (-not [bool]$contract.globalSafety.liveSchedulerOrderOcoGridFundEarnOrTelegramMutationAllowed) 'Trading mutations cannot be authorized by this manifest.'
Assert-Contract (-not [bool]$contract.globalSafety.grossOrEstimatedFeeMaySatisfyExactNet) 'Gross or estimated fees must never satisfy exact net.'
Assert-Contract (-not [bool]$contract.globalSafety.legacyRowsMayEnterCurrentCohortMetrics) 'Legacy rows must stay outside current cohort metrics.'
Assert-Contract (-not [bool]$contract.globalSafety.negativeCurrentCohortSamplesMayBeExcluded) 'Current-cohort losses must not be excludable.'
Assert-Contract ([bool]$contract.globalSafety.zeroClosedEpisodesMayPassTinyLiveReadiness) 'Zero closed episodes must be allowed for tiny-live readiness.'
Assert-Contract (-not [bool]$contract.globalSafety.thirtyClosedEpisodesRequiredForTinyLive) 'Thirty outcomes must not block tiny-live readiness.'

$expectedBlockers = @(
    'TINY_LIVE_HARD_GATE_SNAPSHOT_NOT_IMPLEMENTED',
    'CURRENT_COHORT_CANONICAL_METRIC_READER_NOT_IMPLEMENTED',
    'EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_IMPLEMENTED'
)
$actualBlockers = @($contract.blockers | ForEach-Object { [string]$_.id })
Assert-Contract ($actualBlockers.Count -eq $expectedBlockers.Count) 'Contract must contain exactly three activation blockers.'
foreach ($expectedBlocker in $expectedBlockers) {
    Assert-Contract ($actualBlockers -contains $expectedBlocker) "Missing blocker: $expectedBlocker"
}
Assert-Contract (($actualBlockers | Select-Object -Unique).Count -eq $actualBlockers.Count) 'Blocker ids must be unique.'

foreach ($blocker in @($contract.blockers)) {
    Assert-Contract (-not [string]::IsNullOrWhiteSpace([string]$blocker.implementationId)) "$($blocker.id) needs implementationId."
    Assert-Contract (@($blocker.acceptance).Count -gt 0) "$($blocker.id) needs acceptance clauses."
    Assert-Contract (@($blocker.stopConditions).Count -gt 0) "$($blocker.id) needs STOP clauses."
    Assert-Contract (@($blocker.fileScope.new).Count -gt 0) "$($blocker.id) needs a new-file scope."
    Assert-Contract (@($blocker.tests).Count -gt 0) "$($blocker.id) needs test clauses."
    Assert-Contract ($null -ne $blocker.migration.required) "$($blocker.id) must state whether a migration is required."
}

$hardGate = $contract.blockers | Where-Object { $_.id -eq 'TINY_LIVE_HARD_GATE_SNAPSHOT_NOT_IMPLEMENTED' }
$metricReader = $contract.blockers | Where-Object { $_.id -eq 'CURRENT_COHORT_CANONICAL_METRIC_READER_NOT_IMPLEMENTED' }
$exactFill = $contract.blockers | Where-Object { $_.id -eq 'EXACT_IMMUTABLE_ALL_FILL_SIGNED_FEE_BINDING_NOT_IMPLEMENTED' }

Assert-Contract ($hardGate.implementationStatus -eq 'LOCAL_CONTRACT_IMPLEMENTED_FRESH_RUNTIME_SNAPSHOT_STILL_REQUIRED') 'Hard-gate local implementation status drifted.'
Assert-Contract ($metricReader.implementationStatus -eq 'LOCAL_READER_IMPLEMENTED_EXACT_CLASSIFICATION_STILL_BLOCKED') 'Metric-reader local implementation status drifted.'
Assert-Contract ($exactFill.implementationStatus -eq 'NOT_IMPLEMENTED_OUTSIDE_FIXED_SCOPE') 'Exact evidence blocker must remain not implemented.'

Assert-Contract (-not [bool]$hardGate.migration.required) 'Hard-gate snapshot must not require a migration.'
Assert-Contract (-not [bool]$metricReader.migration.required) 'Canonical reader must not require its own migration.'
Assert-Contract ([bool]$exactFill.migration.required) 'Exact all-fill evidence requires an additive migration.'
Assert-Contract ($exactFill.migration.name -eq 'V3__immutable_trade_fill_evidence.sql') 'Unexpected exact-fill migration name.'

$metricReaderText = $metricReader | ConvertTo-Json -Depth 20 -Compress
Assert-Contract ($metricReaderText -notmatch 'FillFeeLedgerRepository') 'Canonical reader must not treat the V2 fee-only ledger as an exact fill source.'

$exactFillText = $exactFill | ConvertTo-Json -Depth 20 -Compress
Assert-Contract ($exactFillText -match 'estimated') 'Exact-fill contract must explicitly reject estimated fees.'
Assert-Contract ($exactFillText -match 'all entry and exit fill') 'Exact-fill contract must require all entry and exit fills.'
Assert-Contract ($exactFillText -match 'signed fee') 'Exact-fill contract must require signed fees.'
Assert-Contract ($exactFillText -match 'historical backfill') 'Exact-fill contract must prohibit historical backfill.'
Assert-Contract ($exactFillText -match 'append-only') 'Exact-fill contract must require append-only evidence.'

foreach ($authorization in @($contract.futureAuthorizationPackages)) {
    Assert-Contract (-not [bool]$authorization.issuableNow) "$($authorization.name) cannot be issuable before exact bindings exist."
    Assert-Contract (@($authorization.requiredBindings).Count -gt 0) "$($authorization.name) needs exact bindings."
    Assert-Contract (-not [string]::IsNullOrWhiteSpace([string]$authorization.scope)) "$($authorization.name) needs a bounded scope."
}

$hardGateSource = Join-Path $repositoryRoot 'src\main\java\com\agora\service\trading\VersionedProfitStartHardGateSnapshotService.java'
$assemblerSource = Join-Path $repositoryRoot 'src\main\java\com\agora\service\trading\VersionedProfitStartHardGateInputAssembler.java'
$readinessSource = Join-Path $repositoryRoot 'src\main\java\com\agora\service\trading\VersionedProfitStartActivationReadinessService.java'
$metricSource = Join-Path $repositoryRoot 'src\main\java\com\agora\service\trading\CurrentCohortCanonicalMetricReader.java'
$executionSource = Join-Path $repositoryRoot 'src\main\java\com\agora\service\tradingview\LocalTradingViewExecutionService.java'
$evidenceRepositorySource = Join-Path $repositoryRoot 'src\main\java\com\agora\repository\trading\RuntimeDecisionEvidenceRepository.java'
foreach ($source in @($hardGateSource, $assemblerSource, $readinessSource, $metricSource, $executionSource, $evidenceRepositorySource)) {
    Assert-Contract (Test-Path -LiteralPath $source -PathType Leaf) "Missing local integration source: $source"
}
Assert-Contract ((Get-Content -LiteralPath $executionSource -Raw) -match 'verifyAtOrderBoundary') 'Order boundary must verify snapshot expiry/hash drift.'
Assert-Contract ((Get-Content -LiteralPath $executionSource -Raw) -match 'savePreSubmitSnapshotEvidence') 'Order boundary must bind pre-submit snapshot evidence.'
Assert-Contract ((Get-Content -LiteralPath $evidenceRepositorySource -Raw) -match 'INNER JOIN bt_live_signal ls ON ls.id = e.live_signal_id') 'Canonical repository binding must join by explicit liveSignalId.'
Assert-Contract ((Get-Content -LiteralPath $evidenceRepositorySource -Raw) -match 'exchange_order_id AS providerOrderId') 'Canonical repository binding must expose provider order id.'
Assert-Contract ((Get-Content -LiteralPath $metricSource -Raw) -match 'EXACT_EVIDENCE_BINDING_IMPLEMENTED = false') 'Exact-net classification must remain disabled before V3.'

$declaredAuthorizationNames = @($contract.futureAuthorizationPackages | ForEach-Object { [string]$_.name })
foreach ($blockerAuthorization in @($contract.blockers | ForEach-Object { @($_.futureAuthorizations) })) {
    Assert-Contract ($declaredAuthorizationNames -contains [string]$blockerAuthorization) "Blocker authorization is missing from the bounded package list: $blockerAuthorization"
}

Write-Host '[activation-manifest] PASS'
