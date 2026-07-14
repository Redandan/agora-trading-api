[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$DatasetDirectory,
    [string]$ResearchPolicyPath,
    [string]$OutputPath,
    [switch]$AllowFixtureDataset
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Sha256Text {
    param([Parameter(Mandatory = $true)][string]$Text)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
    $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return ([System.BitConverter]::ToString($hash)).Replace("-", "").ToLowerInvariant()
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Text
    )
    [System.IO.File]::WriteAllText($Path, $Text, [System.Text.UTF8Encoding]::new($false))
}

function Resolve-DatasetChild {
    param(
        [Parameter(Mandatory = $true)][string]$Root,
        [Parameter(Mandatory = $true)][string]$RelativePath
    )
    $rootFull = [System.IO.Path]::GetFullPath($Root).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $rootFull $RelativePath))
    $prefix = $rootFull + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Dataset manifest path escapes dataset directory: $RelativePath"
    }
    return $candidate
}

function Get-DoubleInvariant {
    param([Parameter(Mandatory = $true)]$Value)
    $parsed = [double]::NaN
    $ok = [double]::TryParse(
        [string]$Value,
        [System.Globalization.NumberStyles]::Float,
        [System.Globalization.CultureInfo]::InvariantCulture,
        [ref]$parsed)
    if (-not $ok -or [double]::IsNaN($parsed) -or [double]::IsInfinity($parsed)) {
        throw "Invalid numeric value: $Value"
    }
    return $parsed
}

function Assert-FrozenResearchPolicyContract {
    param([Parameter(Mandatory = $true)]$Policy)
    $violations = [System.Collections.Generic.List[string]]::new()
    if ([string]$Policy.dataset.instrument -ne "BTC-USDT") { $violations.Add("dataset.instrument") }
    if ([string]$Policy.dataset.bar -ne "1H") { $violations.Add("dataset.bar") }
    if ([string]$Policy.dataset.source -ne "okx_spot") { $violations.Add("dataset.source") }
    if ([string]$Policy.dataset.timezone -ne "UTC") { $violations.Add("dataset.timezone") }
    if (-not [bool]$Policy.dataset.confirmedBarsOnly) { $violations.Add("dataset.confirmedBarsOnly") }
    if ([string]$Policy.execution.signalExecution -ne "NEXT_1H_OPEN") { $violations.Add("execution.signalExecution") }
    if ([bool]$Policy.execution.shortingAllowed) { $violations.Add("execution.shortingAllowed") }
    if ([bool]$Policy.execution.leverageAllowed) { $violations.Add("execution.leverageAllowed") }
    if ([double]$Policy.execution.maximumExposure -le 0.0 -or
            [double]$Policy.execution.maximumExposure -gt 1.0) {
        $violations.Add("execution.maximumExposure")
    }
    foreach ($scenario in @("normal", "stress")) {
        $cost = $Policy.execution.$scenario
        if ([double]$cost.feeRatePerSide -lt 0.0) { $violations.Add("execution.$scenario.feeRatePerSide") }
        if ([double]$cost.adverseSlippageRatePerSide -lt 0.0) {
            $violations.Add("execution.$scenario.adverseSlippageRatePerSide")
        }
        if ([int]$cost.signalDelayBars -lt 0) { $violations.Add("execution.$scenario.signalDelayBars") }
    }
    if ([int]$Policy.execution.normal.signalDelayBars -ne 0) { $violations.Add("execution.normal.signalDelayBars") }
    if ([double]$Policy.execution.stress.feeRatePerSide -lt [double]$Policy.execution.normal.feeRatePerSide) {
        $violations.Add("execution.stress.feeRatePerSide")
    }
    if ([double]$Policy.execution.stress.adverseSlippageRatePerSide -lt
            [double]$Policy.execution.normal.adverseSlippageRatePerSide) {
        $violations.Add("execution.stress.adverseSlippageRatePerSide")
    }
    if ([int]$Policy.execution.stress.signalDelayBars -le [int]$Policy.execution.normal.signalDelayBars) {
        $violations.Add("execution.stress.signalDelayBars")
    }
    if ([string]$Policy.validation.foldEvaluationMode -ne "ISOLATED_FIXED_PARAMETER_FORWARD_FOLDS") {
        $violations.Add("validation.foldEvaluationMode")
    }
    if ([int]$Policy.validation.foldCount -ne 5) { $violations.Add("validation.foldCount") }
    if ([int]$Policy.validation.minimumCompletedRoundTrips -lt 30) {
        $violations.Add("validation.minimumCompletedRoundTrips")
    }
    if ([int]$Policy.validation.minimumCompletedRoundTripsPerFold -lt 1) {
        $violations.Add("validation.minimumCompletedRoundTripsPerFold")
    }
    if (-not [bool]$Policy.validation.requireAllPeriodNormalReturnPositive) {
        $violations.Add("validation.requireAllPeriodNormalReturnPositive")
    }
    if (-not [bool]$Policy.validation.requireAllPeriodStressReturnNonNegative) {
        $violations.Add("validation.requireAllPeriodStressReturnNonNegative")
    }
    if ([bool]$Policy.validation.parameterSweepAllowed) { $violations.Add("validation.parameterSweepAllowed") }
    if (-not [bool]$Policy.validation.evaluateFrozenCandidatesTogether) {
        $violations.Add("validation.evaluateFrozenCandidatesTogether")
    }
    if ([int]$Policy.validation.maximumShadowPromotionCandidatesPerRound -ne 1) {
        $violations.Add("validation.maximumShadowPromotionCandidatesPerRound")
    }
    $candidates = @($Policy.candidates)
    if ($candidates.Count -ne 3) { $violations.Add("candidates.count") }
    $expectedTypes = [ordered]@{
        BTC_WEEKLY_TSMOM_V1 = "WEEKLY_TSMOM"
        BTC_DONCHIAN_20D_10D_V1 = "DONCHIAN_BREAKOUT"
        BTC_VOL_MANAGED_LONG_V1 = "VOL_MANAGED_LONG"
    }
    foreach ($entry in $expectedTypes.GetEnumerator()) {
        $candidate = @($candidates | Where-Object { [string]$_.id -eq [string]$entry.Key })
        if ($candidate.Count -ne 1 -or [string]$candidate[0].type -ne [string]$entry.Value) {
            $violations.Add("candidates.$($entry.Key).type")
            continue
        }
        if ($entry.Key -eq "BTC_WEEKLY_TSMOM_V1" -and [string]$candidate[0].rebalanceUtc -ne "MONDAY_00:00") {
            $violations.Add("candidates.$($entry.Key).rebalanceUtc")
        }
        if ($entry.Key -eq "BTC_VOL_MANAGED_LONG_V1" -and [string]$candidate[0].rebalanceUtc -ne "MONDAY_00:00") {
            $violations.Add("candidates.$($entry.Key).rebalanceUtc")
        }
        if ($entry.Key -eq "BTC_DONCHIAN_20D_10D_V1") {
            if ([string]$candidate[0].signalUtc -ne "DAILY_CLOSE_23:00") {
                $violations.Add("candidates.$($entry.Key).signalUtc")
            }
            if ([string]$candidate[0].stopExecution -ne "STOP_PRICE_OR_GAP_OPEN_WHICHEVER_IS_WORSE") {
                $violations.Add("candidates.$($entry.Key).stopExecution")
            }
        }
    }
    if ($violations.Count -gt 0) {
        throw "Frozen research policy contract violation: $($violations -join ', ')"
    }
}

function Get-MaxDrawdownPct {
    param(
        [Parameter(Mandatory = $true)][double[]]$EquityAfter,
        [Parameter(Mandatory = $true)][int]$StartIndex,
        [Parameter(Mandatory = $true)][double]$InitialEquity
    )
    $peak = $InitialEquity
    $maximum = 0.0
    for ($i = $StartIndex; $i -lt $EquityAfter.Length; $i++) {
        $value = $EquityAfter[$i]
        if ($value -gt $peak) { $peak = $value }
        if ($peak -gt 0) {
            $drawdown = (($peak - $value) / $peak) * 100.0
            if ($drawdown -gt $maximum) { $maximum = $drawdown }
        }
    }
    return $maximum
}

function Get-FoldMetrics {
    param(
        [Parameter(Mandatory = $true)][object[]]$Bars,
        [Parameter(Mandatory = $true)][double[]]$EquityAfter,
        [Parameter(Mandatory = $true)][int]$StartIndex,
        [Parameter(Mandatory = $true)][double]$InitialEquity,
        [Parameter(Mandatory = $true)][int]$FoldCount
    )
    $folds = [System.Collections.Generic.List[object]]::new()
    $length = $Bars.Count - $StartIndex
    for ($fold = 0; $fold -lt $FoldCount; $fold++) {
        $foldStart = $StartIndex + [int][Math]::Floor(($length * $fold) / $FoldCount)
        $foldEndExclusive = $StartIndex + [int][Math]::Floor(($length * ($fold + 1)) / $FoldCount)
        $foldEnd = [Math]::Max($foldStart, $foldEndExclusive - 1)
        $startEquity = if ($foldStart -eq $StartIndex) {
            $InitialEquity
        } else {
            $EquityAfter[$foldStart - 1]
        }
        $endEquity = $EquityAfter[$foldEnd]
        $returnPct = if ($startEquity -gt 0) {
            (($endEquity / $startEquity) - 1.0) * 100.0
        } else {
            -100.0
        }
        $folds.Add([ordered]@{
                fold = $fold + 1
                startUtc = $Bars[$foldStart].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                endUtc = $Bars[$foldEnd].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                returnPct = [Math]::Round($returnPct, 6)
                positive = $returnPct -gt 0.0
            })
    }
    return @($folds)
}

function Get-EvaluationStartIndex {
    param(
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][object[]]$Bars,
        [Parameter(Mandatory = $true)][object[]]$DailyBars,
        [Parameter(Mandatory = $true)][int]$SignalDelayBars
    )
    switch ([string]$Candidate.type) {
        "WEEKLY_TSMOM" {
            $lookbackHours = [int]$Candidate.lookbackWeeks * 7 * 24
            for ($i = $lookbackHours; $i -lt $Bars.Count; $i++) {
                if ($Bars[$i].Time.UtcDateTime.DayOfWeek -eq [DayOfWeek]::Sunday `
                        -and $Bars[$i].Time.UtcDateTime.Hour -eq 23) {
                    $start = $i + 1 + $SignalDelayBars
                    if ($start -lt $Bars.Count) { return $start }
                }
            }
        }
        "VOL_MANAGED_LONG" {
            $lookbackHours = [int]$Candidate.realizedVolLookbackDays * 24
            for ($i = $lookbackHours; $i -lt $Bars.Count; $i++) {
                if ($Bars[$i].Time.UtcDateTime.DayOfWeek -eq [DayOfWeek]::Sunday `
                        -and $Bars[$i].Time.UtcDateTime.Hour -eq 23) {
                    $start = $i + 1 + $SignalDelayBars
                    if ($start -lt $Bars.Count) { return $start }
                }
            }
        }
        "DONCHIAN_BREAKOUT" {
            $minimumDailyIndex = [Math]::Max(
                [int]$Candidate.entryLookbackDays,
                [int]$Candidate.atrLookbackDays - 1)
            if ($DailyBars.Count -gt $minimumDailyIndex) {
                $start = [int]$DailyBars[$minimumDailyIndex].CloseBarIndex + 1 + $SignalDelayBars
                if ($start -lt $Bars.Count) { return $start }
            }
        }
        default { throw "Unsupported candidate type: $($Candidate.type)" }
    }
    return -1
}

function Get-StandardDeviation {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Length -lt 2) { return 0.0 }
    $sum = 0.0
    foreach ($value in $Values) { $sum += $value }
    $mean = $sum / $Values.Length
    $squared = 0.0
    foreach ($value in $Values) {
        $delta = $value - $mean
        $squared += $delta * $delta
    }
    return [Math]::Sqrt($squared / ($Values.Length - 1))
}

function Get-Median {
    param([Parameter(Mandatory = $true)][double[]]$Values)
    if ($Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $middle = [int][Math]::Floor($sorted.Count / 2)
    if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
    return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Invoke-HodlSimulation {
    param(
        [Parameter(Mandatory = $true)][object[]]$Bars,
        [Parameter(Mandatory = $true)][int]$StartIndex,
        [Parameter(Mandatory = $true)]$Cost,
        [Parameter(Mandatory = $true)][int]$FoldCount
    )
    $feeRate = [double]$Cost.feeRatePerSide
    $slippage = [double]$Cost.adverseSlippageRatePerSide
    $equityAfter = New-Object double[] $Bars.Count
    for ($i = 0; $i -lt $StartIndex; $i++) { $equityAfter[$i] = 1.0 }
    $cash = 1.0
    $buyGross = $cash / (1.0 + $feeRate)
    $buyFee = $buyGross * $feeRate
    $quantity = $buyGross / ($Bars[$StartIndex].Open * (1.0 + $slippage))
    $cash -= $buyGross + $buyFee
    $fees = $buyFee
    for ($i = $StartIndex; $i -lt $Bars.Count; $i++) {
        $equityAfter[$i] = $cash + ($quantity * $Bars[$i].Close)
    }
    $last = $Bars.Count - 1
    $sellGross = $quantity * $Bars[$last].Close * (1.0 - $slippage)
    $sellFee = $sellGross * $feeRate
    $cash += $sellGross - $sellFee
    $fees += $sellFee
    $quantity = 0.0
    $equityAfter[$last] = $cash
    $folds = Get-FoldMetrics -Bars $Bars -EquityAfter $equityAfter -StartIndex $StartIndex `
        -InitialEquity 1.0 -FoldCount $FoldCount
    return [ordered]@{
        returnPct = [Math]::Round((($cash - 1.0) * 100.0), 6)
        maximumDrawdownPct = [Math]::Round((Get-MaxDrawdownPct -EquityAfter $equityAfter `
                    -StartIndex $StartIndex -InitialEquity 1.0), 6)
        totalFeesPctOfInitialCapital = [Math]::Round(($fees * 100.0), 6)
        positiveFolds = @($folds | Where-Object { $_.positive }).Count
        folds = $folds
    }
}

function Get-IsolatedCandidateFoldMetrics {
    param(
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][object[]]$Bars,
        [Parameter(Mandatory = $true)][int]$EvaluationStartIndex,
        [Parameter(Mandatory = $true)]$Cost,
        [Parameter(Mandatory = $true)][int]$FoldCount,
        [Parameter(Mandatory = $true)][string]$ScenarioName,
        [Parameter(Mandatory = $true)][double]$GlobalMaximumExposure
    )
    $warmupHours = switch ([string]$Candidate.type) {
        "WEEKLY_TSMOM" { ([int]$Candidate.lookbackWeeks * 7 * 24) + 1 }
        "VOL_MANAGED_LONG" { ([int]$Candidate.realizedVolLookbackDays * 24) + 1 }
        "DONCHIAN_BREAKOUT" {
            ([Math]::Max([int]$Candidate.entryLookbackDays, [int]$Candidate.atrLookbackDays) + 1) * 24
        }
        default { throw "Unsupported candidate type for isolated folds: $($Candidate.type)" }
    }
    $folds = [System.Collections.Generic.List[object]]::new()
    $length = $Bars.Count - $EvaluationStartIndex
    for ($fold = 0; $fold -lt $FoldCount; $fold++) {
        $foldStart = $EvaluationStartIndex + [int][Math]::Floor(($length * $fold) / $FoldCount)
        $foldEndExclusive = $EvaluationStartIndex + [int][Math]::Floor(($length * ($fold + 1)) / $FoldCount)
        $foldEnd = [Math]::Max($foldStart, $foldEndExclusive - 1)
        $sliceStart = [Math]::Max(0, $foldStart - $warmupHours)
        $sliceBars = @($Bars[$sliceStart..$foldEnd])
        $dailyBundle = Build-DailyBars -Bars $sliceBars
        $foldResult = Invoke-CandidateSimulation -Candidate $Candidate -Bars $sliceBars `
            -DailyBars @($dailyBundle.Bars) -DailyIndexByCloseBar ([hashtable]$dailyBundle.IndexByCloseBar) `
            -Cost $Cost -FoldCount $FoldCount -ScenarioName ("$ScenarioName-FOLD-$($fold + 1)") `
            -GlobalMaximumExposure $GlobalMaximumExposure -SkipFoldMetrics
        $requestedStart = $Bars[$foldStart].Time.ToUniversalTime()
        if ($foldResult.status -eq "SIMULATED" -and
                ([DateTimeOffset]$foldResult.evaluationStartUtc).ToUniversalTime() -lt $requestedStart) {
            throw "Isolated fold evaluated before its requested boundary: candidate=$($Candidate.id) fold=$($fold + 1)"
        }
        $folds.Add([ordered]@{
                fold = $fold + 1
                mode = "ISOLATED_FIXED_PARAMETER_FORWARD_FOLD"
                requestedStartUtc = $Bars[$foldStart].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                requestedEndUtc = $Bars[$foldEnd].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                actualEvaluationStartUtc = $foldResult.evaluationStartUtc
                status = [string]$foldResult.status
                returnPct = $foldResult.returnPct
                maximumDrawdownPct = $foldResult.maximumDrawdownPct
                completedRoundTrips = [int]$foldResult.completedRoundTrips
                orders = [int]$foldResult.orders
                positive = $foldResult.status -eq "SIMULATED" -and [double]$foldResult.returnPct -gt 0.0
                causalBoundaryPassed = $foldResult.status -ne "SIMULATED" -or
                    ([DateTimeOffset]$foldResult.evaluationStartUtc).ToUniversalTime() -ge $requestedStart
            })
    }
    return @($folds)
}

function Invoke-CandidateSimulation {
    param(
        [Parameter(Mandatory = $true)]$Candidate,
        [Parameter(Mandatory = $true)][object[]]$Bars,
        [Parameter(Mandatory = $true)][object[]]$DailyBars,
        [Parameter(Mandatory = $true)][hashtable]$DailyIndexByCloseBar,
        [Parameter(Mandatory = $true)]$Cost,
        [Parameter(Mandatory = $true)][int]$FoldCount,
        [Parameter(Mandatory = $true)][string]$ScenarioName,
        [Parameter(Mandatory = $true)][double]$GlobalMaximumExposure,
        [switch]$SkipFoldMetrics
    )
    $signalDelayBars = [int]$Cost.signalDelayBars
    $evaluationStart = Get-EvaluationStartIndex -Candidate $Candidate -Bars $Bars `
        -DailyBars $DailyBars -SignalDelayBars $signalDelayBars
    if ($evaluationStart -lt 0) {
        return [ordered]@{
            status = "INSUFFICIENT_WARMUP"
            evaluationStartUtc = $null
            evaluationEndUtc = $Bars[-1].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
            historyDays = 0.0
            returnPct = $null
            maximumDrawdownPct = $null
            orders = 0
            entries = 0
            exits = 0
            completedRoundTrips = 0
            winningTrades = 0
            losingTrades = 0
            profitFactor = $null
            medianTradeReturnPct = $null
            totalFeesPctOfInitialCapital = $null
            turnoverPctOfInitialCapital = $null
            positiveFolds = 0
            folds = @()
            buyAndHold = $null
            signalLedger = @()
            orderLedger = @()
            tradeLedger = @()
            signalLedgerSha256 = Get-Sha256Text "[]"
            orderLedgerSha256 = Get-Sha256Text "[]"
            tradeLedgerSha256 = Get-Sha256Text "[]"
            ledgerIntegrityChecks = [ordered]@{
                orderCountMatches = $true
                feeSumMatches = $true
                turnoverSumMatches = $true
                signalIdsUnique = $true
                linkedSignalIdsExist = $true
                signalTimingCausal = $true
                scheduledOrderTimingMatches = $true
                orderSequenceMatches = $true
                perOrderFeeFormulaMatches = $true
                perOrderQuantityNotionalMatches = $true
                adverseSlippageMatches = $true
                cashConservationMatches = $true
                positionConservationMatches = $true
                tradeCountMatches = $true
            }
            ledgerIntegrityPassed = $true
        }
    }

    $feeRate = [double]$Cost.feeRatePerSide
    $slippage = [double]$Cost.adverseSlippageRatePerSide
    $cash = 1.0
    $quantity = 0.0
    $stopPrice = [double]::NaN
    $fees = 0.0
    $turnover = 0.0
    $orders = 0
    $entries = 0
    $exits = 0
    $evaluationInitialEquity = 1.0
    $pending = @{}
    $signalLedger = [System.Collections.Generic.List[object]]::new()
    $orderLedger = [System.Collections.Generic.List[object]]::new()
    $tradeLedger = [System.Collections.Generic.List[object]]::new()
    $signalSequence = 0
    $tradeSequence = 0
    $activeEntrySignalId = $null
    $activeTradeStartEquity = [double]::NaN
    $activeTradeEntryTimeUtc = $null
    $equityAfter = New-Object double[] $Bars.Count
    for ($i = 0; $i -lt $Bars.Count; $i++) {
        $bar = $Bars[$i]
        $equityBeforeAction = $cash + ($quantity * $bar.Open)
        if ($i -eq $evaluationStart) { $evaluationInitialEquity = $equityBeforeAction }

        $action = $null
        if ($pending.ContainsKey($i)) {
            $action = $pending[$i]
            $pending.Remove($i)
            $targetExposure = [double]$action.TargetExposure
            if ($action.Kind -eq "DONCHIAN_ENTRY") {
                $stopDistance = [double]$action.Atr * [double]$Candidate.initialStopAtrMultiple
                $stopDistancePct = if ($bar.Open -gt 0) { $stopDistance / $bar.Open } else { 1.0 }
                $targetExposure = if ($stopDistancePct -gt 0) {
                    [Math]::Min([double]$Candidate.maximumExposure,
                        [double]$Candidate.equityRiskPerTrade / $stopDistancePct)
                } else {
                    0.0
                }
            }
            $targetExposure = [Math]::Max(0.0, [Math]::Min($GlobalMaximumExposure, $targetExposure))
            $equityAtOpen = $cash + ($quantity * $bar.Open)
            $currentNotional = $quantity * $bar.Open
            $targetNotional = [Math]::Max(0.0, $equityAtOpen * $targetExposure)
            $delta = $targetNotional - $currentNotional
            if ($delta -gt 0.0000000001 -and $cash -gt 0.0) {
                $gross = [Math]::Min($delta, $cash / (1.0 + $feeRate))
                if ($gross -gt 0.0) {
                    $fillPrice = $bar.Open * (1.0 + $slippage)
                    $bought = $gross / $fillPrice
                    $fee = $gross * $feeRate
                    $wasFlat = $quantity -le 0.000000000000001
                    $cashBeforeOrder = $cash
                    $quantityBeforeOrder = $quantity
                    $cash -= $gross + $fee
                    $quantity += $bought
                    $fees += $fee
                    $turnover += $gross
                    $orders++
                    if ($wasFlat) {
                        $entries++
                        $activeEntrySignalId = [string]$action.SignalId
                        $activeTradeStartEquity = $equityAtOpen
                        $activeTradeEntryTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    }
                    if ($action.Kind -eq "DONCHIAN_ENTRY" -and $wasFlat) {
                        $stopPrice = $fillPrice - ([double]$action.Atr * [double]$Candidate.initialStopAtrMultiple)
                    }
                    $orderLedger.Add([ordered]@{
                            sequence = $orders
                            signalId = [string]$action.SignalId
                            executionTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            side = "BUY"
                            reason = [string]$action.Reason
                            midPrice = [Math]::Round($bar.Open, 8)
                            fillPrice = [Math]::Round($fillPrice, 8)
                            baseQuantity = [Math]::Round($bought, 12)
                            grossNotionalEquityUnits = [Math]::Round($gross, 12)
                            feeEquityUnits = [Math]::Round($fee, 12)
                            cashBefore = [Math]::Round($cashBeforeOrder, 15)
                            cashAfter = [Math]::Round($cash, 15)
                            positionQuantityBefore = [Math]::Round($quantityBeforeOrder, 15)
                            positionQuantityAfter = [Math]::Round($quantity, 15)
                            targetExposure = [Math]::Round($targetExposure, 8)
                            stopPrice = if ([double]::IsNaN($stopPrice)) { $null } else { [Math]::Round($stopPrice, 8) }
                        })
                }
            }
            elseif ($delta -lt -0.0000000001 -and $quantity -gt 0.0) {
                $sellQuantity = [Math]::Min($quantity, (-$delta) / $bar.Open)
                if ($targetExposure -eq 0.0) { $sellQuantity = $quantity }
                if ($sellQuantity -gt 0.0) {
                    $fillPrice = $bar.Open * (1.0 - $slippage)
                    $gross = $sellQuantity * $fillPrice
                    $fee = $gross * $feeRate
                    $cashBeforeOrder = $cash
                    $quantityBeforeOrder = $quantity
                    $cash += $gross - $fee
                    $quantity -= $sellQuantity
                    $fees += $fee
                    $turnover += $gross
                    $orders++
                    $fullyClosed = $quantity -le 0.000000000000001
                    $orderLedger.Add([ordered]@{
                            sequence = $orders
                            signalId = [string]$action.SignalId
                            executionTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            side = "SELL"
                            reason = [string]$action.Reason
                            midPrice = [Math]::Round($bar.Open, 8)
                            fillPrice = [Math]::Round($fillPrice, 8)
                            baseQuantity = [Math]::Round($sellQuantity, 12)
                            grossNotionalEquityUnits = [Math]::Round($gross, 12)
                            feeEquityUnits = [Math]::Round($fee, 12)
                            cashBefore = [Math]::Round($cashBeforeOrder, 15)
                            cashAfter = [Math]::Round($cash, 15)
                            positionQuantityBefore = [Math]::Round($quantityBeforeOrder, 15)
                            positionQuantityAfter = [Math]::Round($quantity, 15)
                            targetExposure = [Math]::Round($targetExposure, 8)
                            stopPrice = $null
                        })
                    if ($fullyClosed) {
                        $tradeSequence++
                        $tradePnl = $cash - $activeTradeStartEquity
                        $tradeReturnPct = if ($activeTradeStartEquity -gt 0.0) {
                            ($tradePnl / $activeTradeStartEquity) * 100.0
                        } else { -100.0 }
                        $tradeLedger.Add([ordered]@{
                                sequence = $tradeSequence
                                entrySignalId = $activeEntrySignalId
                                entryTimeUtc = $activeTradeEntryTimeUtc
                                exitSignalId = [string]$action.SignalId
                                exitTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                                exitReason = [string]$action.Reason
                                startEquity = [Math]::Round($activeTradeStartEquity, 15)
                                endEquity = [Math]::Round($cash, 15)
                                profitLossEquityUnits = [Math]::Round($tradePnl, 15)
                                returnPct = [Math]::Round($tradeReturnPct, 10)
                            })
                        $quantity = 0.0
                        $stopPrice = [double]::NaN
                        $activeEntrySignalId = $null
                        $activeTradeStartEquity = [double]::NaN
                        $activeTradeEntryTimeUtc = $null
                        $exits++
                    }
                }
            }
        }

        if ([string]$Candidate.type -eq "DONCHIAN_BREAKOUT" `
                -and $quantity -gt 0.0 -and -not [double]::IsNaN($stopPrice) `
                -and $bar.Low -le $stopPrice) {
            $rawStopFill = [Math]::Min($stopPrice, $bar.Open)
            $fillPrice = $rawStopFill * (1.0 - $slippage)
            $sellQuantity = $quantity
            $gross = $sellQuantity * $fillPrice
            $fee = $gross * $feeRate
            $cashBeforeOrder = $cash
            $quantityBeforeOrder = $quantity
            $cash += $gross - $fee
            $turnover += $gross
            $fees += $fee
            $quantity = 0.0
            $stopPrice = [double]::NaN
            $orders++
            $exits++
            $orderLedger.Add([ordered]@{
                    sequence = $orders
                    signalId = $activeEntrySignalId
                    executionTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    side = "SELL"
                    reason = "ATR_STOP"
                    midPrice = [Math]::Round($rawStopFill, 8)
                    fillPrice = [Math]::Round($fillPrice, 8)
                    baseQuantity = [Math]::Round($sellQuantity, 12)
                    grossNotionalEquityUnits = [Math]::Round($gross, 12)
                    feeEquityUnits = [Math]::Round($fee, 12)
                    cashBefore = [Math]::Round($cashBeforeOrder, 15)
                    cashAfter = [Math]::Round($cash, 15)
                    positionQuantityBefore = [Math]::Round($quantityBeforeOrder, 15)
                    positionQuantityAfter = 0.0
                    targetExposure = 0.0
                    stopPrice = [Math]::Round($rawStopFill, 8)
                })
            $tradeSequence++
            $tradePnl = $cash - $activeTradeStartEquity
            $tradeReturnPct = if ($activeTradeStartEquity -gt 0.0) {
                ($tradePnl / $activeTradeStartEquity) * 100.0
            } else { -100.0 }
            $tradeLedger.Add([ordered]@{
                    sequence = $tradeSequence
                    entrySignalId = $activeEntrySignalId
                    entryTimeUtc = $activeTradeEntryTimeUtc
                    exitSignalId = $activeEntrySignalId
                    exitTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                    exitReason = "ATR_STOP"
                    startEquity = [Math]::Round($activeTradeStartEquity, 15)
                    endEquity = [Math]::Round($cash, 15)
                    profitLossEquityUnits = [Math]::Round($tradePnl, 15)
                    returnPct = [Math]::Round($tradeReturnPct, 10)
                })
            $activeEntrySignalId = $null
            $activeTradeStartEquity = [double]::NaN
            $activeTradeEntryTimeUtc = $null
        }

        $equityAfter[$i] = $cash + ($quantity * $bar.Close)

        if ([string]$Candidate.type -eq "WEEKLY_TSMOM") {
            $lookbackHours = [int]$Candidate.lookbackWeeks * 7 * 24
            if ($i -ge $lookbackHours -and $bar.Time.UtcDateTime.DayOfWeek -eq [DayOfWeek]::Sunday `
                    -and $bar.Time.UtcDateTime.Hour -eq 23) {
                $momentum = ($bar.Close / $Bars[$i - $lookbackHours].Close) - 1.0
                $target = if ($momentum -gt 0.0) {
                    [double]$Candidate.longExposureWhenPositive
                } else {
                    [double]$Candidate.cashExposureWhenNonPositive
                }
                $executionIndex = $i + 1 + $signalDelayBars
                if ($executionIndex -lt $Bars.Count) {
                    $signalSequence++
                    $signalId = "{0}|{1}|{2:D4}" -f $Candidate.id, $ScenarioName, $signalSequence
                    $reason = if ($momentum -gt 0.0) { "TSMOM_26W_POSITIVE" } else { "TSMOM_26W_NON_POSITIVE" }
                    $signalLedger.Add([ordered]@{
                            sequence = $signalSequence
                            signalId = $signalId
                            sourceBarOpenTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalAvailableAtUtc = $bar.Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            scheduledExecutionTimeUtc = $Bars[$executionIndex].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalKind = $reason
                            targetExposure = [Math]::Round($target, 8)
                            indicatorValues = [ordered]@{ momentum26Week = [Math]::Round($momentum, 10) }
                        })
                    $pending[$executionIndex] = [pscustomobject]@{
                        Kind = "TARGET"; TargetExposure = $target; Atr = 0.0
                        SignalId = $signalId; Reason = $reason
                    }
                }
            }
        }
        elseif ([string]$Candidate.type -eq "VOL_MANAGED_LONG") {
            $lookbackHours = [int]$Candidate.realizedVolLookbackDays * 24
            if ($i -ge $lookbackHours -and $bar.Time.UtcDateTime.DayOfWeek -eq [DayOfWeek]::Sunday `
                    -and $bar.Time.UtcDateTime.Hour -eq 23) {
                $returns = New-Object double[] $lookbackHours
                for ($j = 0; $j -lt $lookbackHours; $j++) {
                    $right = $i - $lookbackHours + 1 + $j
                    $returns[$j] = [Math]::Log($Bars[$right].Close / $Bars[$right - 1].Close)
                }
                $annualizedVol = (Get-StandardDeviation $returns) * [Math]::Sqrt([double]$Candidate.annualizationHours)
                $target = if ($annualizedVol -gt 0.0) {
                    [double]$Candidate.targetAnnualizedVol / $annualizedVol
                } else {
                    [double]$Candidate.maximumExposure
                }
                $target = [Math]::Max([double]$Candidate.minimumExposure,
                    [Math]::Min([double]$Candidate.maximumExposure, $target))
                $executionIndex = $i + 1 + $signalDelayBars
                if ($executionIndex -lt $Bars.Count) {
                    $signalSequence++
                    $signalId = "{0}|{1}|{2:D4}" -f $Candidate.id, $ScenarioName, $signalSequence
                    $signalLedger.Add([ordered]@{
                            sequence = $signalSequence
                            signalId = $signalId
                            sourceBarOpenTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalAvailableAtUtc = $bar.Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            scheduledExecutionTimeUtc = $Bars[$executionIndex].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalKind = "VOL_TARGET_REBALANCE"
                            targetExposure = [Math]::Round($target, 8)
                            indicatorValues = [ordered]@{
                                realizedAnnualizedVol = [Math]::Round($annualizedVol, 10)
                                targetAnnualizedVol = [double]$Candidate.targetAnnualizedVol
                            }
                        })
                    $pending[$executionIndex] = [pscustomobject]@{
                        Kind = "TARGET"; TargetExposure = $target; Atr = 0.0
                        SignalId = $signalId; Reason = "VOL_TARGET_REBALANCE"
                    }
                }
            }
        }
        elseif ([string]$Candidate.type -eq "DONCHIAN_BREAKOUT" -and $DailyIndexByCloseBar.ContainsKey($i)) {
            $dailyIndex = [int]$DailyIndexByCloseBar[$i]
            $day = $DailyBars[$dailyIndex]
            $entryLookback = [int]$Candidate.entryLookbackDays
            $exitLookback = [int]$Candidate.exitLookbackDays
            $atrLookback = [int]$Candidate.atrLookbackDays
            $executionIndex = $i + 1 + $signalDelayBars
            if ($executionIndex -lt $Bars.Count -and $quantity -le 0.000000000000001 `
                    -and $dailyIndex -ge $entryLookback -and $dailyIndex -ge ($atrLookback - 1)) {
                $priorHigh = [double]::MinValue
                for ($j = $dailyIndex - $entryLookback; $j -lt $dailyIndex; $j++) {
                    if ($DailyBars[$j].High -gt $priorHigh) { $priorHigh = $DailyBars[$j].High }
                }
                if ($day.Close -gt $priorHigh) {
                    $atrSum = 0.0
                    for ($j = $dailyIndex - $atrLookback + 1; $j -le $dailyIndex; $j++) {
                        $atrSum += $DailyBars[$j].TrueRange
                    }
                    $atr = $atrSum / $atrLookback
                    $signalSequence++
                    $signalId = "{0}|{1}|{2:D4}" -f $Candidate.id, $ScenarioName, $signalSequence
                    $signalLedger.Add([ordered]@{
                            sequence = $signalSequence
                            signalId = $signalId
                            sourceBarOpenTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalAvailableAtUtc = $bar.Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            scheduledExecutionTimeUtc = $Bars[$executionIndex].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalKind = "DONCHIAN_20D_BREAKOUT_ENTRY"
                            targetExposure = $null
                            indicatorValues = [ordered]@{
                                currentDailyClose = [Math]::Round($day.Close, 8)
                                prior20DayHigh = [Math]::Round($priorHigh, 8)
                                atr14 = [Math]::Round($atr, 8)
                            }
                        })
                    $pending[$executionIndex] = [pscustomobject]@{
                        Kind = "DONCHIAN_ENTRY"; TargetExposure = 0.0; Atr = $atr
                        SignalId = $signalId; Reason = "DONCHIAN_20D_BREAKOUT_ENTRY"
                    }
                }
            }
            elseif ($executionIndex -lt $Bars.Count -and $quantity -gt 0.0 -and $dailyIndex -ge $exitLookback) {
                $priorLow = [double]::MaxValue
                for ($j = $dailyIndex - $exitLookback; $j -lt $dailyIndex; $j++) {
                    if ($DailyBars[$j].Low -lt $priorLow) { $priorLow = $DailyBars[$j].Low }
                }
                if ($day.Close -lt $priorLow) {
                    $signalSequence++
                    $signalId = "{0}|{1}|{2:D4}" -f $Candidate.id, $ScenarioName, $signalSequence
                    $signalLedger.Add([ordered]@{
                            sequence = $signalSequence
                            signalId = $signalId
                            sourceBarOpenTimeUtc = $bar.Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalAvailableAtUtc = $bar.Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            scheduledExecutionTimeUtc = $Bars[$executionIndex].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                            signalKind = "DONCHIAN_10D_BREAKDOWN_EXIT"
                            targetExposure = 0.0
                            indicatorValues = [ordered]@{
                                currentDailyClose = [Math]::Round($day.Close, 8)
                                prior10DayLow = [Math]::Round($priorLow, 8)
                            }
                        })
                    $pending[$executionIndex] = [pscustomobject]@{
                        Kind = "DONCHIAN_EXIT"; TargetExposure = 0.0; Atr = 0.0
                        SignalId = $signalId; Reason = "DONCHIAN_10D_BREAKDOWN_EXIT"
                    }
                }
            }
        }
    }

    $lastIndex = $Bars.Count - 1
    if ($quantity -gt 0.0) {
        $fillPrice = $Bars[$lastIndex].Close * (1.0 - $slippage)
        $sellQuantity = $quantity
        $gross = $sellQuantity * $fillPrice
        $fee = $gross * $feeRate
        $cashBeforeOrder = $cash
        $quantityBeforeOrder = $quantity
        $cash += $gross - $fee
        $turnover += $gross
        $fees += $fee
        $quantity = 0.0
        $orders++
        $exits++
        $orderLedger.Add([ordered]@{
                sequence = $orders
                signalId = $activeEntrySignalId
                executionTimeUtc = $Bars[$lastIndex].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                side = "SELL"
                reason = "FINAL_LIQUIDATION"
                midPrice = [Math]::Round($Bars[$lastIndex].Close, 8)
                fillPrice = [Math]::Round($fillPrice, 8)
                baseQuantity = [Math]::Round($sellQuantity, 12)
                grossNotionalEquityUnits = [Math]::Round($gross, 12)
                feeEquityUnits = [Math]::Round($fee, 12)
                cashBefore = [Math]::Round($cashBeforeOrder, 15)
                cashAfter = [Math]::Round($cash, 15)
                positionQuantityBefore = [Math]::Round($quantityBeforeOrder, 15)
                positionQuantityAfter = 0.0
                targetExposure = 0.0
                stopPrice = $null
            })
        $tradeSequence++
        $tradePnl = $cash - $activeTradeStartEquity
        $tradeReturnPct = if ($activeTradeStartEquity -gt 0.0) {
            ($tradePnl / $activeTradeStartEquity) * 100.0
        } else { -100.0 }
        $tradeLedger.Add([ordered]@{
                sequence = $tradeSequence
                entrySignalId = $activeEntrySignalId
                entryTimeUtc = $activeTradeEntryTimeUtc
                exitSignalId = $activeEntrySignalId
                exitTimeUtc = $Bars[$lastIndex].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
                exitReason = "FINAL_LIQUIDATION"
                startEquity = [Math]::Round($activeTradeStartEquity, 15)
                endEquity = [Math]::Round($cash, 15)
                profitLossEquityUnits = [Math]::Round($tradePnl, 15)
                returnPct = [Math]::Round($tradeReturnPct, 10)
            })
        $activeEntrySignalId = $null
        $activeTradeStartEquity = [double]::NaN
        $activeTradeEntryTimeUtc = $null
        $equityAfter[$lastIndex] = $cash
    }
    $finalEquity = $equityAfter[$lastIndex]
    $returnPct = if ($evaluationInitialEquity -gt 0.0) {
        (($finalEquity / $evaluationInitialEquity) - 1.0) * 100.0
    } else {
        -100.0
    }
    $folds = @()
    if (-not $SkipFoldMetrics) {
        $folds = @(Get-IsolatedCandidateFoldMetrics -Candidate $Candidate -Bars $Bars `
                -EvaluationStartIndex $evaluationStart -Cost $Cost -FoldCount $FoldCount `
                -ScenarioName $ScenarioName -GlobalMaximumExposure $GlobalMaximumExposure)
    }
    $hodl = Invoke-HodlSimulation -Bars $Bars -StartIndex $evaluationStart -Cost $Cost -FoldCount $FoldCount
    $historyDays = ($Bars[-1].Time.AddHours(1) - $Bars[$evaluationStart].Time).TotalDays
    $cagrPct = if ($historyDays -gt 0.0 -and $evaluationInitialEquity -gt 0.0 -and $finalEquity -gt 0.0) {
        ([Math]::Pow(($finalEquity / $evaluationInitialEquity), (365.25 / $historyDays)) - 1.0) * 100.0
    } else {
        -100.0
    }
    $signalLedgerRows = @($signalLedger)
    $orderLedgerRows = @($orderLedger)
    $tradeLedgerRows = @($tradeLedger)
    $signalLedgerJson = ConvertTo-Json -InputObject $signalLedgerRows -Depth 10 -Compress
    $orderLedgerJson = ConvertTo-Json -InputObject $orderLedgerRows -Depth 10 -Compress
    $tradeLedgerJson = ConvertTo-Json -InputObject $tradeLedgerRows -Depth 10 -Compress
    $signalById = @{}
    $duplicateSignalIds = 0
    $signalTimingViolations = 0
    foreach ($signal in $signalLedgerRows) {
        $signalId = [string]$signal.signalId
        if ($signalById.ContainsKey($signalId)) {
            $duplicateSignalIds++
        } else {
            $signalById[$signalId] = $signal
        }
        $availableAt = [DateTimeOffset]::Parse(
            [string]$signal.signalAvailableAtUtc,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AssumeUniversal)
        $scheduledAt = [DateTimeOffset]::Parse(
            [string]$signal.scheduledExecutionTimeUtc,
            [System.Globalization.CultureInfo]::InvariantCulture,
            [System.Globalization.DateTimeStyles]::AssumeUniversal)
        if ($scheduledAt -lt $availableAt) { $signalTimingViolations++ }
    }
    $linkedSignalViolations = 0
    $scheduledOrderTimingViolations = 0
    $orderSequenceViolations = 0
    $perOrderFeeViolations = 0
    $perOrderQuantityNotionalViolations = 0
    $adverseSlippageViolations = 0
    $cashConservationViolations = 0
    $positionConservationViolations = 0
    $reconstructedCash = 1.0
    $reconstructedQuantity = 0.0
    $orderFormulaTolerance = 0.000001
    $expectedSequence = 0
    foreach ($order in $orderLedgerRows) {
        $expectedSequence++
        if ([int]$order.sequence -ne $expectedSequence) { $orderSequenceViolations++ }
        $signalId = [string]$order.signalId
        if ([string]::IsNullOrWhiteSpace($signalId) -or -not $signalById.ContainsKey($signalId)) {
            $linkedSignalViolations++
        } else {
            $signal = $signalById[$signalId]
            $executionAt = [DateTimeOffset]::Parse([string]$order.executionTimeUtc)
            $availableAt = [DateTimeOffset]::Parse([string]$signal.signalAvailableAtUtc)
            if ($executionAt -lt $availableAt) { $scheduledOrderTimingViolations++ }
            if ([string]$order.reason -notin @("ATR_STOP", "FINAL_LIQUIDATION") -and
                    [string]$order.executionTimeUtc -ne [string]$signal.scheduledExecutionTimeUtc) {
                $scheduledOrderTimingViolations++
            }
        }
        $gross = [double]$order.grossNotionalEquityUnits
        $fee = [double]$order.feeEquityUnits
        $fillPrice = [double]$order.fillPrice
        $midPrice = [double]$order.midPrice
        $baseQuantity = [double]$order.baseQuantity
        $cashBefore = [double]$order.cashBefore
        $cashAfter = [double]$order.cashAfter
        $positionBefore = [double]$order.positionQuantityBefore
        $positionAfter = [double]$order.positionQuantityAfter
        if ([Math]::Abs($fee - ($gross * $feeRate)) -gt $orderFormulaTolerance) {
            $perOrderFeeViolations++
        }
        if ([Math]::Abs($gross - ($baseQuantity * $fillPrice)) -gt $orderFormulaTolerance) {
            $perOrderQuantityNotionalViolations++
        }
        $expectedFill = if ([string]$order.side -eq "BUY") {
            $midPrice * (1.0 + $slippage)
        } else {
            $midPrice * (1.0 - $slippage)
        }
        $fillTolerance = [Math]::Max($orderFormulaTolerance, [Math]::Abs($expectedFill) * 0.00000001)
        if ([Math]::Abs($fillPrice - $expectedFill) -gt $fillTolerance) {
            $adverseSlippageViolations++
        }
        if ([Math]::Abs($cashBefore - $reconstructedCash) -gt $orderFormulaTolerance) {
            $cashConservationViolations++
        }
        if ([Math]::Abs($positionBefore - $reconstructedQuantity) -gt $orderFormulaTolerance) {
            $positionConservationViolations++
        }
        if ([string]$order.side -eq "BUY") {
            $expectedCashAfter = $cashBefore - $gross - $fee
            $expectedPositionAfter = $positionBefore + $baseQuantity
        } elseif ([string]$order.side -eq "SELL") {
            $expectedCashAfter = $cashBefore + $gross - $fee
            $expectedPositionAfter = $positionBefore - $baseQuantity
        } else {
            $cashConservationViolations++
            $positionConservationViolations++
            $expectedCashAfter = $cashAfter
            $expectedPositionAfter = $positionAfter
        }
        if ([Math]::Abs($cashAfter - $expectedCashAfter) -gt $orderFormulaTolerance) {
            $cashConservationViolations++
        }
        if ([Math]::Abs($positionAfter - $expectedPositionAfter) -gt $orderFormulaTolerance) {
            $positionConservationViolations++
        }
        $reconstructedCash = $cashAfter
        $reconstructedQuantity = $positionAfter
    }
    $ledgerFeeSum = [double](($orderLedgerRows | ForEach-Object {
                    [double]$_['feeEquityUnits']
                } | Measure-Object -Sum).Sum)
    $ledgerTurnoverSum = [double](($orderLedgerRows | ForEach-Object {
                    [double]$_['grossNotionalEquityUnits']
                } | Measure-Object -Sum).Sum)
    $accountingTolerance = 0.00000001
    $tradeLedgerFormulaViolations = 0
    $previousTradeEndEquity = 1.0
    foreach ($trade in $tradeLedgerRows) {
        $startEquity = [double]$trade.startEquity
        $endEquity = [double]$trade.endEquity
        $pnl = [double]$trade.profitLossEquityUnits
        $tradeReturn = [double]$trade.returnPct
        $expectedTradeReturn = if ($startEquity -gt 0.0) {
            (($endEquity - $startEquity) / $startEquity) * 100.0
        } else { -100.0 }
        if ([Math]::Abs($startEquity - $previousTradeEndEquity) -gt $orderFormulaTolerance -or
                [Math]::Abs($pnl - ($endEquity - $startEquity)) -gt $orderFormulaTolerance -or
                [Math]::Abs($tradeReturn - $expectedTradeReturn) -gt $orderFormulaTolerance -or
                [DateTimeOffset]::Parse([string]$trade.exitTimeUtc) -lt
                [DateTimeOffset]::Parse([string]$trade.entryTimeUtc)) {
            $tradeLedgerFormulaViolations++
        }
        $previousTradeEndEquity = $endEquity
    }
    $winningPnl = [double](($tradeLedgerRows | Where-Object {
                    [double]$_['profitLossEquityUnits'] -gt $accountingTolerance
                } | ForEach-Object { [double]$_['profitLossEquityUnits'] } | Measure-Object -Sum).Sum)
    $losingPnlMagnitude = [Math]::Abs([double](($tradeLedgerRows | Where-Object {
                        [double]$_['profitLossEquityUnits'] -lt -$accountingTolerance
                    } | ForEach-Object { [double]$_['profitLossEquityUnits'] } | Measure-Object -Sum).Sum))
    $winningTrades = @($tradeLedgerRows | Where-Object {
            [double]$_['profitLossEquityUnits'] -gt $accountingTolerance
        }).Count
    $losingTrades = @($tradeLedgerRows | Where-Object {
            [double]$_['profitLossEquityUnits'] -lt -$accountingTolerance
        }).Count
    $profitFactor = if ($losingPnlMagnitude -gt $accountingTolerance) {
        $winningPnl / $losingPnlMagnitude
    } else { $null }
    $tradeReturns = [double[]]@($tradeLedgerRows | ForEach-Object { [double]$_['returnPct'] })
    $medianTradeReturn = if ($tradeReturns.Count -eq 0) { $null } else { Get-Median -Values $tradeReturns }
    $ledgerIntegrityChecks = [ordered]@{
        orderCountMatches = $orderLedgerRows.Count -eq $orders
        feeSumMatches = [Math]::Abs($ledgerFeeSum - $fees) -le $accountingTolerance
        turnoverSumMatches = [Math]::Abs($ledgerTurnoverSum - $turnover) -le $accountingTolerance
        signalIdsUnique = $duplicateSignalIds -eq 0
        linkedSignalIdsExist = $linkedSignalViolations -eq 0
        signalTimingCausal = $signalTimingViolations -eq 0
        scheduledOrderTimingMatches = $scheduledOrderTimingViolations -eq 0
        orderSequenceMatches = $orderSequenceViolations -eq 0
        perOrderFeeFormulaMatches = $perOrderFeeViolations -eq 0
        perOrderQuantityNotionalMatches = $perOrderQuantityNotionalViolations -eq 0
        adverseSlippageMatches = $adverseSlippageViolations -eq 0
        cashConservationMatches = $cashConservationViolations -eq 0
        positionConservationMatches = $positionConservationViolations -eq 0
        tradeCountMatches = $tradeLedgerRows.Count -eq [Math]::Min($entries, $exits)
        tradeLedgerFormulaMatches = $tradeLedgerFormulaViolations -eq 0
    }
    $ledgerIntegrityPassed = @($ledgerIntegrityChecks.Values | Where-Object { -not $_ }).Count -eq 0
    return [ordered]@{
        status = "SIMULATED"
        evaluationStartUtc = $Bars[$evaluationStart].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        evaluationEndUtc = $Bars[-1].Time.AddHours(1).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        historyDays = [Math]::Round($historyDays, 3)
        returnPct = [Math]::Round($returnPct, 6)
        cagrPct = [Math]::Round($cagrPct, 6)
        maximumDrawdownPct = [Math]::Round((Get-MaxDrawdownPct -EquityAfter $equityAfter `
                    -StartIndex $evaluationStart -InitialEquity $evaluationInitialEquity), 6)
        orders = $orders
        entries = $entries
        exits = $exits
        completedRoundTrips = [Math]::Min($entries, $exits)
        winningTrades = $winningTrades
        losingTrades = $losingTrades
        profitFactor = if ($null -eq $profitFactor) { $null } else { [Math]::Round($profitFactor, 6) }
        profitFactorStatus = if ($null -eq $profitFactor -and $winningTrades -gt 0 -and $losingTrades -eq 0) {
            "NO_LOSING_TRADES"
        } elseif ($null -eq $profitFactor) { "UNDEFINED_NO_REALIZED_EDGE" } else { "FINITE" }
        medianTradeReturnPct = if ($null -eq $medianTradeReturn) { $null } else { [Math]::Round($medianTradeReturn, 6) }
        totalFeesPctOfInitialCapital = [Math]::Round(($fees * 100.0), 6)
        turnoverPctOfInitialCapital = [Math]::Round(($turnover * 100.0), 6)
        positiveFolds = @($folds | Where-Object { $_.positive }).Count
        worstFoldReturnPct = if ($folds.Count -eq 0) {
            $null
        } else {
            [Math]::Round([double](($folds | ForEach-Object {
                                [double]$_['returnPct']
                            } | Measure-Object -Minimum).Minimum), 6)
        }
        foldEvaluationMode = if ($SkipFoldMetrics) { "SKIPPED_NESTED" } else { "ISOLATED_FIXED_PARAMETER_FORWARD_FOLDS" }
        folds = $folds
        buyAndHold = $hodl
        signalLedger = $signalLedgerRows
        orderLedger = $orderLedgerRows
        tradeLedger = $tradeLedgerRows
        signalLedgerSha256 = Get-Sha256Text $signalLedgerJson
        orderLedgerSha256 = Get-Sha256Text $orderLedgerJson
        tradeLedgerSha256 = Get-Sha256Text $tradeLedgerJson
        ledgerIntegrityChecks = $ledgerIntegrityChecks
        ledgerIntegrityPassed = $ledgerIntegrityPassed
    }
}

function Build-DailyBars {
    param([Parameter(Mandatory = $true)][object[]]$Bars)
    $daily = [System.Collections.Generic.List[object]]::new()
    $indexByClose = @{}
    $cursor = 0
    $previousClose = [double]::NaN
    while ($cursor -lt $Bars.Count) {
        $date = $Bars[$cursor].Time.UtcDateTime.Date
        $start = $cursor
        while ($cursor -lt $Bars.Count -and $Bars[$cursor].Time.UtcDateTime.Date -eq $date) { $cursor++ }
        $end = $cursor - 1
        $count = $end - $start + 1
        if ($count -ne 24 -or $Bars[$start].Time.UtcDateTime.Hour -ne 0 `
                -or $Bars[$end].Time.UtcDateTime.Hour -ne 23) {
            continue
        }
        $high = [double]::MinValue
        $low = [double]::MaxValue
        for ($i = $start; $i -le $end; $i++) {
            if ($Bars[$i].High -gt $high) { $high = $Bars[$i].High }
            if ($Bars[$i].Low -lt $low) { $low = $Bars[$i].Low }
        }
        $close = $Bars[$end].Close
        $trueRange = $high - $low
        if (-not [double]::IsNaN($previousClose)) {
            $trueRange = [Math]::Max($trueRange,
                [Math]::Max([Math]::Abs($high - $previousClose), [Math]::Abs($low - $previousClose)))
        }
        $record = [pscustomobject]@{
            Date = $date
            Open = $Bars[$start].Open
            High = $high
            Low = $low
            Close = $close
            TrueRange = $trueRange
            CloseBarIndex = $end
        }
        $indexByClose[$end] = $daily.Count
        $daily.Add($record)
        $previousClose = $close
    }
    return [pscustomobject]@{ Bars = @($daily); IndexByCloseBar = $indexByClose }
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($ResearchPolicyPath)) {
    $ResearchPolicyPath = Join-Path $PSScriptRoot "btc_price_only_research_policy.json"
}
$datasetRoot = [System.IO.Path]::GetFullPath($DatasetDirectory)
if (-not (Test-Path -LiteralPath $datasetRoot -PathType Container)) {
    throw "DatasetDirectory not found: $datasetRoot"
}
$manifestPath = Join-Path $datasetRoot "manifest.json"
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) { throw "manifest.json not found" }
if (-not (Test-Path -LiteralPath $ResearchPolicyPath -PathType Leaf)) { throw "Research policy not found" }
$manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
$policy = Get-Content -Raw -LiteralPath $ResearchPolicyPath | ConvertFrom-Json
if ([string]$manifest.schemaVersion -ne "OKX_BTC_RESEARCH_DATASET_V1") {
    throw "Unsupported dataset manifest schema: $($manifest.schemaVersion)"
}
if ([string]$policy.schemaVersion -ne "BTC_PRICE_ONLY_RESEARCH_POLICY_V1") {
    throw "Unsupported research policy schema: $($policy.schemaVersion)"
}
Assert-FrozenResearchPolicyContract -Policy $policy
if ([string]$manifest.status -ne "IMMUTABLE_DATASET_READY") {
    if (-not $AllowFixtureDataset -or [string]$manifest.status -ne "FIXTURE_DATASET_READY_NOT_EXTERNAL_EVIDENCE") {
        throw "Dataset status is not eligible for research: $($manifest.status)"
    }
}
$manifestContractViolations = [System.Collections.Generic.List[string]]::new()
if ([string]$manifest.request.instrument -ne [string]$policy.dataset.instrument) {
    $manifestContractViolations.Add("request.instrument")
}
if ([string]$manifest.request.bar -ne [string]$policy.dataset.bar) {
    $manifestContractViolations.Add("request.bar")
}
if ([bool]$manifest.tlsCertificateValidationSkipped) {
    $manifestContractViolations.Add("tlsCertificateValidationSkipped")
}
try {
    $endpointUri = [uri][string]$manifest.officialEndpoint
    $endpointHost = $endpointUri.DnsSafeHost.ToLowerInvariant()
    if ($endpointUri.Scheme -ne "https" -or
            ($endpointHost -ne "okx.com" -and -not $endpointHost.EndsWith(".okx.com")) -or
            $endpointUri.AbsolutePath -ne "/api/v5/market/history-candles") {
        $manifestContractViolations.Add("officialEndpoint")
    }
}
catch {
    $manifestContractViolations.Add("officialEndpoint")
}
if ([string]$manifest.sourceMode -eq "OKX_OFFICIAL_API") {
    if ([string]$manifest.status -ne "IMMUTABLE_DATASET_READY") {
        $manifestContractViolations.Add("officialSourceStatus")
    }
}
elseif (-not $AllowFixtureDataset -or [string]$manifest.sourceMode -ne "LOCAL_RAW_PAGE_FIXTURE") {
    $manifestContractViolations.Add("sourceMode")
}
$requestStart = ([DateTimeOffset]$manifest.request.requestedStartUtc).ToUniversalTime()
$requestEnd = ([DateTimeOffset]$manifest.request.requestedEndUtcExclusive).ToUniversalTime()
$asOf = ([DateTimeOffset]$manifest.asOfUtc).ToUniversalTime()
if ($requestEnd -ne $asOf) { $manifestContractViolations.Add("asOfUtc") }
$expectedDatasetId = "okx-btc-usdt-1h-" + $requestEnd.ToString(
    "yyyyMMddTHHmmssZ", [System.Globalization.CultureInfo]::InvariantCulture)
if ([string]$manifest.datasetId -ne $expectedDatasetId) { $manifestContractViolations.Add("datasetId") }
if ($manifestContractViolations.Count -gt 0) {
    throw "Dataset manifest contract violation: $($manifestContractViolations -join ', ')"
}
$policyHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ResearchPolicyPath).Hash.ToLowerInvariant()
if ($policyHash -ne [string]$manifest.provenance.researchPolicySha256) {
    throw "Research policy hash mismatch: dataset=$($manifest.provenance.researchPolicySha256) current=$policyHash"
}
$rawCanonicalByTimestamp = @{}
foreach ($page in @($manifest.rawPages)) {
    $pagePath = Resolve-DatasetChild -Root $datasetRoot -RelativePath ([string]$page.file)
    if (-not (Test-Path -LiteralPath $pagePath -PathType Leaf)) { throw "Raw page missing: $pagePath" }
    $actual = (Get-FileHash -Algorithm SHA256 -LiteralPath $pagePath).Hash.ToLowerInvariant()
    if ($actual -ne [string]$page.sha256) { throw "Raw page hash mismatch: $($page.file)" }
    $rawResponse = Get-Content -Raw -LiteralPath $pagePath | ConvertFrom-Json
    if ([string]$rawResponse.code -ne "0" -or $null -eq $rawResponse.data) {
        throw "Raw page response contract failure: $($page.file)"
    }
    $rawRows = @($rawResponse.data)
    if ($rawRows.Count -ne [int]$page.responseRows) {
        throw "Raw page row count mismatch: $($page.file)"
    }
    $rawTimestamps = @($rawRows | ForEach-Object { [int64]$_[0] })
    if ($rawTimestamps.Count -gt 0) {
        if ([int64](($rawTimestamps | Measure-Object -Minimum).Minimum) -ne [int64]$page.minimumTimestampMs -or
                [int64](($rawTimestamps | Measure-Object -Maximum).Maximum) -ne [int64]$page.maximumTimestampMs) {
            throw "Raw page timestamp bounds mismatch: $($page.file)"
        }
    }
    foreach ($rawRow in $rawRows) {
        if (@($rawRow).Count -lt 9) { throw "Malformed candle row in raw page: $($page.file)" }
        $rawTimestamp = [int64]$rawRow[0]
        $rawTime = [DateTimeOffset]::FromUnixTimeMilliseconds($rawTimestamp)
        if ([string]$rawRow[8] -ne "1" -or $rawTime -lt $requestStart -or $rawTime -ge $requestEnd) {
            continue
        }
        $rawKey = [string]$rawTimestamp
        if ($rawCanonicalByTimestamp.ContainsKey($rawKey)) {
            throw "Duplicate eligible raw timestamp: $rawKey"
        }
        $rawCanonicalByTimestamp[$rawKey] = @(
            [string]$rawRow[1], [string]$rawRow[2], [string]$rawRow[3],
            [string]$rawRow[4], [string]$rawRow[5])
    }
}
$csvPath = Resolve-DatasetChild -Root $datasetRoot -RelativePath ([string]$manifest.canonical.file)
if (-not (Test-Path -LiteralPath $csvPath -PathType Leaf)) { throw "Canonical CSV missing: $csvPath" }
$csvHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $csvPath).Hash.ToLowerInvariant()
if ($csvHash -ne [string]$manifest.canonical.sha256) { throw "Canonical CSV hash mismatch" }

$csvRows = @(Import-Csv -LiteralPath $csvPath)
if ($csvRows.Count -ne [int]$manifest.canonical.rowCount) {
    throw "Canonical CSV row count mismatch: actual=$($csvRows.Count) manifest=$($manifest.canonical.rowCount)"
}
if ($rawCanonicalByTimestamp.Count -ne $csvRows.Count) {
    throw "Raw/canonical eligible row count mismatch: raw=$($rawCanonicalByTimestamp.Count) canonical=$($csvRows.Count)"
}
$rawCanonicalMismatchCount = 0
foreach ($row in $csvRows) {
    $canonicalTime = [DateTimeOffset]::ParseExact(
        [string]$row.open_time_utc,
        "yyyy-MM-ddTHH:mm:ss'Z'",
        [System.Globalization.CultureInfo]::InvariantCulture,
        ([System.Globalization.DateTimeStyles]::AssumeUniversal -bor
            [System.Globalization.DateTimeStyles]::AdjustToUniversal))
    $rawKey = [string]$canonicalTime.ToUnixTimeMilliseconds()
    if (-not $rawCanonicalByTimestamp.ContainsKey($rawKey)) {
        $rawCanonicalMismatchCount++
        continue
    }
    $rawValues = @($rawCanonicalByTimestamp[$rawKey])
    $canonicalValues = @([string]$row.open, [string]$row.high, [string]$row.low,
        [string]$row.close, [string]$row.volume)
    for ($valueIndex = 0; $valueIndex -lt $canonicalValues.Count; $valueIndex++) {
        if ($canonicalValues[$valueIndex] -ne $rawValues[$valueIndex]) {
            $rawCanonicalMismatchCount++
            break
        }
    }
    if ([string]$row.confirm -ne "1" -or [string]$row.source -ne [string]$policy.dataset.source -or
            [string]$row.inst_id -ne [string]$policy.dataset.instrument -or
            [string]$row.bar -ne [string]$policy.dataset.bar) {
        $rawCanonicalMismatchCount++
    }
}
if ($rawCanonicalMismatchCount -ne 0) {
    throw "Raw/canonical reconstruction mismatch rows: $rawCanonicalMismatchCount"
}
$barsList = [System.Collections.Generic.List[object]]::new()
$lastTimestamp = [int64]::MinValue
foreach ($row in $csvRows) {
    $time = [DateTimeOffset]::ParseExact(
        [string]$row.open_time_utc,
        "yyyy-MM-ddTHH:mm:ss'Z'",
        [System.Globalization.CultureInfo]::InvariantCulture,
        ([System.Globalization.DateTimeStyles]::AssumeUniversal -bor
            [System.Globalization.DateTimeStyles]::AdjustToUniversal))
    $timestamp = $time.ToUnixTimeMilliseconds()
    if (($timestamp % 3600000) -ne 0) { throw "Off-grid canonical timestamp: $($row.open_time_utc)" }
    if ($lastTimestamp -ne [int64]::MinValue -and $timestamp -ne ($lastTimestamp + 3600000)) {
        throw "Canonical timestamp gap or duplicate at $($row.open_time_utc)"
    }
    $open = Get-DoubleInvariant $row.open
    $high = Get-DoubleInvariant $row.high
    $low = Get-DoubleInvariant $row.low
    $close = Get-DoubleInvariant $row.close
    $volume = Get-DoubleInvariant $row.volume
    if ($open -le 0 -or $high -le 0 -or $low -le 0 -or $close -le 0 -or $volume -lt 0 `
            -or $high -lt [Math]::Max($open, $close) -or $low -gt [Math]::Min($open, $close) `
            -or $high -lt $low -or [string]$row.confirm -ne "1") {
        throw "Canonical OHLC/confirm invariant failure at $($row.open_time_utc)"
    }
    $barsList.Add([pscustomobject]@{
            Time = $time.ToUniversalTime()
            Open = $open
            High = $high
            Low = $low
            Close = $close
            Volume = $volume
        })
    $lastTimestamp = $timestamp
}
$bars = @($barsList)
if ($bars.Count -lt 2) { throw "At least two canonical bars are required" }
$dailyBundle = Build-DailyBars -Bars $bars
$dailyBars = @($dailyBundle.Bars)
$dailyIndexByCloseBar = [hashtable]$dailyBundle.IndexByCloseBar
$foldCount = [int]$policy.validation.foldCount
$globalMaximumExposure = [double]$policy.execution.maximumExposure
$results = [System.Collections.Generic.List[object]]::new()
foreach ($candidate in @($policy.candidates)) {
    $normal = Invoke-CandidateSimulation -Candidate $candidate -Bars $bars -DailyBars $dailyBars `
        -DailyIndexByCloseBar $dailyIndexByCloseBar -Cost $policy.execution.normal `
        -FoldCount $foldCount -ScenarioName "NORMAL" -GlobalMaximumExposure $globalMaximumExposure
    $stress = Invoke-CandidateSimulation -Candidate $candidate -Bars $bars -DailyBars $dailyBars `
        -DailyIndexByCloseBar $dailyIndexByCloseBar -Cost $policy.execution.stress `
        -FoldCount $foldCount -ScenarioName "STRESS" -GlobalMaximumExposure $globalMaximumExposure
    $gateChecks = [ordered]@{
        normalSimulated = $normal.status -eq "SIMULATED"
        stressSimulated = $stress.status -eq "SIMULATED"
        minimumHistoryDays = $normal.status -eq "SIMULATED" -and
            [double]$normal.historyDays -ge [double]$policy.validation.minimumHistoryDays
        minimumCompletedRoundTrips = $normal.status -eq "SIMULATED" -and
            [int]$normal.completedRoundTrips -ge [int]$policy.validation.minimumCompletedRoundTrips -and
            $stress.status -eq "SIMULATED" -and
            [int]$stress.completedRoundTrips -ge [int]$policy.validation.minimumCompletedRoundTrips
        normalEveryFoldMinimumRoundTrips = $normal.status -eq "SIMULATED" -and
            @($normal.folds).Count -eq $foldCount -and
            @($normal.folds | Where-Object {
                    [int]$_.completedRoundTrips -lt [int]$policy.validation.minimumCompletedRoundTripsPerFold
                }).Count -eq 0
        stressEveryFoldMinimumRoundTrips = $stress.status -eq "SIMULATED" -and
            @($stress.folds).Count -eq $foldCount -and
            @($stress.folds | Where-Object {
                    [int]$_.completedRoundTrips -lt [int]$policy.validation.minimumCompletedRoundTripsPerFold
                }).Count -eq 0
        normalLedgerIntegrity = $normal.status -eq "SIMULATED" -and [bool]$normal.ledgerIntegrityPassed
        stressLedgerIntegrity = $stress.status -eq "SIMULATED" -and [bool]$stress.ledgerIntegrityPassed
        normalReturnPositive = $normal.status -eq "SIMULATED" -and [double]$normal.returnPct -gt 0.0
        stressReturnNonNegative = $stress.status -eq "SIMULATED" -and [double]$stress.returnPct -ge 0.0
        normalCagr = $normal.status -eq "SIMULATED" -and
            [double]$normal.cagrPct -ge [double]$policy.validation.minimumNormalCagrPct
        stressCagr = $stress.status -eq "SIMULATED" -and
            [double]$stress.cagrPct -ge [double]$policy.validation.minimumStressCagrPct
        normalProfitFactor = $normal.status -eq "SIMULATED" -and
            (($normal.profitFactorStatus -eq "NO_LOSING_TRADES" -and [int]$normal.winningTrades -gt 0) -or
            ($null -ne $normal.profitFactor -and
                [double]$normal.profitFactor -ge [double]$policy.validation.minimumNormalProfitFactor))
        stressProfitFactor = $stress.status -eq "SIMULATED" -and
            (($stress.profitFactorStatus -eq "NO_LOSING_TRADES" -and [int]$stress.winningTrades -gt 0) -or
            ($null -ne $stress.profitFactor -and
                [double]$stress.profitFactor -ge [double]$policy.validation.minimumStressProfitFactor))
        normalPositiveFolds = $normal.status -eq "SIMULATED" -and
            [int]$normal.positiveFolds -ge [int]$policy.validation.minimumNormalPositiveFolds
        stressPositiveFolds = $stress.status -eq "SIMULATED" -and
            [int]$stress.positiveFolds -ge [int]$policy.validation.minimumStressPositiveFolds
        normalWorstFoldReturn = $normal.status -eq "SIMULATED" -and
            [double]$normal.worstFoldReturnPct -ge [double]$policy.validation.minimumWorstNormalFoldReturnPct
        stressWorstFoldReturn = $stress.status -eq "SIMULATED" -and
            [double]$stress.worstFoldReturnPct -ge [double]$policy.validation.minimumWorstStressFoldReturnPct
        normalDrawdownWithinLimit = $normal.status -eq "SIMULATED" -and
            [double]$normal.maximumDrawdownPct -le [double]$policy.validation.maximumNormalDrawdownPct
        stressDrawdownWithinLimit = $stress.status -eq "SIMULATED" -and
            [double]$stress.maximumDrawdownPct -le [double]$policy.validation.maximumStressDrawdownPct
    }
    $gatePassed = @($gateChecks.Values | Where-Object { -not $_ }).Count -eq 0
    $results.Add([ordered]@{
            candidateId = [string]$candidate.id
            candidateType = [string]$candidate.type
            fixedParameters = $candidate
            normal = $normal
            stress = $stress
            gateChecks = $gateChecks
            gatePassed = $gatePassed
            verdict = if ($gatePassed) {
                "PASSED_PRICE_ONLY_RESEARCH_GATE_NOT_LIVE"
            } else {
                "REJECTED_NO_SHADOW_PROMOTION"
            }
        })
}

$passing = @($results | Where-Object { $_.gatePassed } | Sort-Object {
        if ($null -eq $_.stress.returnPct) { [double]::MinValue } else { [double]$_.stress.returnPct }
    } -Descending)
$maximumPromotions = [int]$policy.validation.maximumShadowPromotionCandidatesPerRound
$recommended = @($passing | Select-Object -First $maximumPromotions | ForEach-Object { $_.candidateId })
$overallVerdict = if ($recommended.Count -gt 0) {
    "READY_FOR_ONE_PRICE_ONLY_SHADOW_CANDIDATE_REVIEW_NOT_DEPLOYED"
} else {
    "REJECTED_NO_PRICE_ONLY_EDGE_NO_LIVE"
}
$analyzerHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $PSCommandPath).Hash.ToLowerInvariant()
$deterministicPayload = [ordered]@{
    datasetSha256 = $csvHash
    researchPolicySha256 = $policyHash
    analyzerSha256 = $analyzerHash
    results = @($results)
    recommendedShadowCandidates = $recommended
    verdict = $overallVerdict
}
$deterministicJson = $deterministicPayload | ConvertTo-Json -Depth 30 -Compress
$report = [ordered]@{
    schemaVersion = "BTC_PRICE_ONLY_RESEARCH_REPORT_V1"
    tool = "analyze_btc_price_only_candidates"
    boundary = "LOCAL_READ_ONLY"
    generatedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    dataset = [ordered]@{
        datasetId = [string]$manifest.datasetId
        status = [string]$manifest.status
        asOfUtc = ([DateTimeOffset]$manifest.asOfUtc).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        sourceMode = [string]$manifest.sourceMode
        canonicalSha256 = $csvHash
        rowCount = $bars.Count
        firstOpenTimeUtc = $bars[0].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        lastOpenTimeUtc = $bars[-1].Time.ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'")
        rawPageCount = @($manifest.rawPages).Count
        timestampLattice = "EXACT_CONTIGUOUS_UTC_1H"
        confirmedBarsOnly = $true
        manifestContractValidated = $true
        rawCanonicalReconstructionMatched = $true
        trustBoundary = "LOCAL_TAMPER_EVIDENT_HASH_CHAIN_NOT_EXTERNALLY_SIGNED"
        datasetBuilderSha256 = [string]$manifest.provenance.datasetBuilderSha256
        analyzerSha256AtDatasetBuild = [string]$manifest.provenance.analyzerSha256AtBuild
        analyzerVersionMatchesDatasetBuild =
            [string]$manifest.provenance.analyzerSha256AtBuild -eq $analyzerHash
    }
    researchPolicySha256 = $policyHash
    analyzerSha256 = $analyzerHash
    strategy508Baseline = [ordered]@{
        status = "NOT_COMPARABLE_NO_CANONICAL_CAUSAL_SIGNAL_LEDGER"
        reason = "Price-only candles cannot reconstruct historical predicted funding and provider-specific OI snapshots."
    }
    validationSemantics = [ordered]@{
        foldMode = [string]$policy.validation.foldEvaluationMode
        parametersRetunedPerFold = $false
        positionsCarriedAcrossFolds = $false
        eachFoldStartsFromCash = $true
        candidateSelectionObjective = "ABSOLUTE_NET_POSITIVE_WITH_FIXED_COSTS_AND_DRAWDOWN_CAP"
        hodlOutperformanceRequired = $false
        reasonHodlNotGate = "The research target is a lower-drawdown executable strategy, not maximum long-only beta capture."
    }
    results = @($results)
    passingCandidateCount = $passing.Count
    recommendedShadowCandidates = $recommended
    verdict = $overallVerdict
    deterministicResultSha256 = Get-Sha256Text $deterministicJson
    safety = [ordered]@{
        livePromotionAllowed = $false
        productionDatabaseWritten = $false
        externalBackfillPerformed = $false
        orderSent = $false
        ocoModified = $false
        telegramSent = $false
        productionEnvironmentChanged = $false
    }
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $datasetRoot "price-only-research-report.json"
}
$outputFull = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $outputFull) { throw "Output report already exists: $outputFull" }
$outputParent = Split-Path -Parent $outputFull
if (-not (Test-Path -LiteralPath $outputParent)) { New-Item -ItemType Directory -Path $outputParent -Force | Out-Null }
Write-Utf8NoBom -Path $outputFull -Text ($report | ConvertTo-Json -Depth 30)
Write-Output ([ordered]@{
        status = "RESEARCH_COMPLETE"
        reportPath = $outputFull
        verdict = $overallVerdict
        passingCandidateCount = $passing.Count
        recommendedShadowCandidates = $recommended
        deterministicResultSha256 = $report.deterministicResultSha256
    } | ConvertTo-Json -Compress)
