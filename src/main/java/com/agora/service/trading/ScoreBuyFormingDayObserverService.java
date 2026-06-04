package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.IndicatorUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreBuyFormingDayObserverService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 485L;
    private static final int LOOKBACK_BARS = 260;
    private static final int RSI_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final int VOL_MA_PERIOD = 20;
    private static final BigDecimal MAX_PRE_POSITION_USDT = new BigDecimal("10.00");
    private static final BigDecimal PRE_POSITION_PCT = new BigDecimal("0.05");
    private static final BigDecimal MAX_RECOVERY_SCOUT_USDT = new BigDecimal("25.00");
    private static final BigDecimal RECOVERY_SCOUT_PCT = new BigDecimal("0.10");
    private static final BigDecimal SAME_THESIS_BUDGET_USDT = new BigDecimal("50.00");
    private static final BigDecimal EXCHANGE_MIN_NOTIONAL_USDT = new BigDecimal("5.00");

    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository klineRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;
    private final EventRiskLevelEngine eventRiskLevelEngine;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String getStatus(String symbol, Long strategyId) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        ObjectNode root = objectMapper.createObjectNode();
        root.put("tool", "getScoreBuyFormingDayStatus");
        root.put("boundary", "READ_ONLY; no order/OCO/strategy/grid/fund/Earn/Telegram/RuntimeEvidence write behavior changed.");
        LocalDateTime generatedAtUtc = LocalDateTime.now(ZoneOffset.UTC);
        root.put("generatedAtUtc", generatedAtUtc.toString());
        root.put("symbol", sym);
        root.put("strategyId", sid);
        root.put("orderSent", false);
        root.put("ocoModified", false);
        root.put("telegramSent", false);
        root.put("writesRuntimeEvidence", false);

        Optional<BtStrategy> strategyOpt = strategyRepository.findById(sid);
        if (strategyOpt.isEmpty()) {
            root.put("scoreBuyFormingState", "NONE");
            root.put("status", "STRATEGY_NOT_FOUND");
            root.put("recommendedAction", "NO_ACTION_STRATEGY_NOT_FOUND");
            return write(root);
        }

        BtStrategy strategy = strategyOpt.get();
        StrategyParams params = StrategyParams.from(readConfig(strategy.getConfigJson()));
        root.set("strategy", strategyJson(strategy, params));

        List<String> hardBlockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> riskScalers = new ArrayList<>();

        List<MdKline> confirmedDaily = loadBars(sym, "1d", LOOKBACK_BARS);
        List<MdKline> oneMinute = loadCurrentDay1m(sym);
        if (confirmedDaily.size() < params.minWarmupBars()) {
            hardBlockers.add("INSUFFICIENT_CONFIRMED_DAILY_BARS");
        }
        if (oneMinute.isEmpty()) {
            hardBlockers.add("FORMING_INTRADAY_DATA_MISSING");
        }

        MdKline forming = oneMinute.isEmpty() ? null : buildCurrentDayCandle(sym, oneMinute);
        List<MdKline> formingDailyBars = withCurrentDay(confirmedDaily, forming);
        FrameState formingDaily = evaluateFrame(formingDailyBars, "forming_1d", params);
        FrameState confirmedDailyState = evaluateFrame(confirmedDaily, "confirmed_1d", params);
        FrameState oneHour = evaluateFrame(loadBars(sym, "1h", LOOKBACK_BARS), "1h", params);
        List<MdKline> fifteenMinuteBars = synthesize15mFrom1m(sym);
        FrameState fifteenMinute = evaluateFrame(fifteenMinuteBars, "synthetic_15m_from_1m", params);
        IntradayReversal currentBarReversal = evaluateIntradayReversal(fifteenMinuteBars, oneHour);
        boolean latestSynthetic15mClosed = latestSynthetic15mClosed(fifteenMinuteBars, generatedAtUtc);
        List<MdKline> decisionFifteenMinuteBars = latestSynthetic15mClosed
                ? fifteenMinuteBars
                : closedSynthetic15mBars(fifteenMinuteBars, generatedAtUtc);
        IntradayReversal closedBarReversal = latestSynthetic15mClosed
                ? currentBarReversal
                : evaluateIntradayReversal(decisionFifteenMinuteBars, oneHour);
        boolean useClosedBarDecision = !latestSynthetic15mClosed
                && !"INSUFFICIENT_DATA".equals(closedBarReversal.status());
        IntradayReversal reversal = useClosedBarDecision ? closedBarReversal : currentBarReversal;
        RecoveryScout recoveryScout = evaluateRecoveryScout(fifteenMinuteBars, formingDaily, oneHour);

        EventRiskLevelEngine.Snapshot eventRisk = safeEventRisk(sym);
        double eventRiskMultiplier = eventRiskMultiplier(eventRisk.level());
        if (eventRisk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3)) {
            riskScalers.add("EVENT_RISK_R3_PRE_POSITION_MULTIPLIER_0_25");
            warnings.add("R3 is treated as a SCORE_BUY panic-bottom pre-position risk scaler only; it does not allow confirmed large deploy.");
        } else if (eventRisk.level().atLeast(EventRiskLevelEngine.RiskLevel.R2)) {
            riskScalers.add("EVENT_RISK_R2_PRE_POSITION_MULTIPLIER_0_50");
        }

        Invalidation invalidation = evaluateInvalidation(formingDailyBars, formingDaily, eventRisk);
        if (invalidation.invalidated()) {
            hardBlockers.add("FORMING_DAY_INVALIDATED");
        }

        CapitalSnapshot capital = readCapitalSnapshot(sym);
        SameThesisExposure sameThesis = readSameThesisExposure(sym, sid, formingDaily.close());
        BigDecimal remainingPrePositionBudget = SAME_THESIS_BUDGET_USDT.subtract(sameThesis.exposureUsdt())
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.DOWN);

        int watchingSignals = watchingSignalCount(formingDaily, oneHour, fifteenMinute, formingDailyBars);
        ScoreBuyFormingState state = classifyState(
                confirmedDailyState,
                formingDaily,
                reversal,
                recoveryScout,
                invalidation,
                hardBlockers,
                watchingSignals,
                sameThesis.openPositionCount() > 0);
        HoldingPolicy holdingPolicy = holdingPolicy(state, sameThesis, invalidation);
        if (holdingPolicy.holdBtcMode()) {
            hardBlockers.add("SCORE_BUY_HOLD_BTC_MODE_NO_AUTO_ADD");
            warnings.add("STRUCTURE_BROKEN_HOLD_BTC_NO_AUTO_SELL_KEEP_DISASTER_OCO");
        }

        BigDecimal recommendedNotional = recommendedNotional(state, capital.reserveAwareDeployableUsdt(), remainingPrePositionBudget, eventRiskMultiplier);
        boolean requiresEarnReserveTopUp = recommendedNotional.compareTo(capital.liquidAfterReserveUsdt()) > 0;
        if (requiresEarnReserveTopUp) {
            warnings.add("PRE_POSITION_REQUIRES_SCORE_BUY_RESERVE_TOP_UP_PREVIEW_ONLY:" + recommendedNotional);
        }
        ExecutionPreview execution = executionPreview(hardBlockers, recommendedNotional);
        String action = recommendedAction(state, hardBlockers, recommendedNotional, execution, holdingPolicy);

        root.put("scoreBuyFormingState", state.name());
        root.put("scoreBuyHoldingState", holdingPolicy.state());
        root.put("structureBroken", holdingPolicy.structureBroken());
        root.put("holdBtcMode", holdingPolicy.holdBtcMode());
        root.put("holdBtcReason", holdingPolicy.reason());
        root.put("autoSellAllowed", false);
        root.put("autoAddAllowed", autoAddAllowed(state, hardBlockers, holdingPolicy));
        root.put("disasterOcoMode", "KEEP_12PCT_HARD_OCO");
        root.put("scoutActive", sameThesis.openPositionCount() > 0);
        root.put("postScoutLifecycleState", postScoutLifecycleState(state, sameThesis));
        root.put("postScoutLifecycleAction", postScoutLifecycleAction(state, sameThesis));
        root.set("formingDailyRsi", finiteOrNull(formingDaily.rsi()));
        root.put("formingDailyNearLowerBb", formingDaily.nearLowerBollinger());
        root.set("formingDailyVolumeRatio", finiteOrNull(formingDaily.volumeRatio()));
        root.put("formingDailyDipGateState", formingDaily.dipGatePass() ? "PASS" : "FAILED:" + String.join(",", formingDaily.missingReasons()));
        root.put("intradayReversalStatus", reversal.status());
        root.put("intradayReversalEvaluationMode", reversalEvaluationMode(latestSynthetic15mClosed, useClosedBarDecision));
        root.put("latestSynthetic15mClosed", latestSynthetic15mClosed);
        root.put("intradayReversalDecisionUsesLastClosed15m", useClosedBarDecision);
        putTime(root, "intradayReversalDecisionBarOpenTime", latestOpenTime(decisionFifteenMinuteBars));
        putTime(root, "intradayReversalCurrentBarOpenTime", latestOpenTime(fifteenMinuteBars));
        putTime(root, "intradayReversalCurrentBarCloseTime", latestCloseTime(fifteenMinuteBars));
        root.put("missedOpportunityRisk", missedOpportunityRisk(state, eventRisk.level(), watchingSignals));
        root.put("recommendedAction", action);
        putMoney(root, "recommendedNotionalPreview", recommendedNotional);
        root.put("sizingCapitalMode", "RESERVE_AWARE_OBSERVED_CAPITAL_PREVIEW");
        root.put("requiresEarnReserveTopUpBeforeExecution", requiresEarnReserveTopUp);
        putMoney(root, "exchangeMinNotionalUsdt", EXCHANGE_MIN_NOTIONAL_USDT);
        root.put("belowExchangeMinimum", execution.belowExchangeMinimum());
        root.put("executionFeasible", execution.executionFeasible());
        root.put("executionReadiness", execution.executionReadiness());
        root.put("executionEvaluationMode", "READ_ONLY_OBSERVER_PREVIEW; not a full order/OCO/runtime-evidence preflight.");
        root.put("eventRiskLevel", eventRisk.level().name());
        root.put("eventRiskScore", eventRisk.score());
        root.put("eventRiskMultiplier", eventRiskMultiplier);
        root.put("sameThesisExposureUsedPct", percent(sameThesis.exposureUsdt(), SAME_THESIS_BUDGET_USDT));
        putMoney(root, "remainingPrePositionBudget", remainingPrePositionBudget);
        root.put("earnCapitalShownSeparately", true);
        root.put("watchingSignalCount", watchingSignals);
        root.put("watchingThreshold", "btc24h<=-2.0 OR btc4h<=-1.5 plus forming RSI/lowerBB/intraday RSI conditions; WATCHING never orders.");
        root.set("prePositionTrigger", prePositionTriggerJson(formingDaily, reversal));
        root.set("earlyRecoveryScout", recoveryScout.toJson(objectMapper));
        root.put("prePositionPolicy", "min(10 USDT, 5% reserve-aware deployable USDT, remaining same-thesis budget) * eventRiskMultiplier; Earn reserve top-up is only previewed here and requires a separate explicit write/control path.");
        root.put("earlyRecoveryScoutPolicy", "min(25 USDT, 10% reserve-aware deployable USDT, remaining same-thesis budget) * eventRiskMultiplier; captures recent 48h washout recovery before daily SCORE_BUY confirms.");
        root.put("confirmedDailyPolicy", "daily #485 remains 1d thesis; larger staged deploy needs daily confirmation, OCO, total exposure cap, budgets, and event-risk scaling.");
        root.put("waiSignal", "NOT_EVALUATED_V0");
        root.put("fearGreedSignal", "NOT_EVALUATED_V0");
        root.set("strategyDailyGate", confirmedDailyState.toJson(objectMapper));
        root.set("formingDailyFrame", formingDaily.toJson(objectMapper));
        root.set("intradayProxy1h", oneHour.toJson(objectMapper));
        root.set("intradayProxy15m", fifteenMinute.toJson(objectMapper));
        root.set("intradayReversal", reversal.toJson(objectMapper));
        root.set("intradayReversalCurrentBar", currentBarReversal.toJson(objectMapper));
        root.set("intradayReversalLastClosedBar", closedBarReversal.toJson(objectMapper));
        root.set("capitalSnapshot", capital.toJson(objectMapper));
        root.set("sameThesisExposure", sameThesis.toJson(objectMapper));
        root.set("eventRiskInputs", objectMapper.valueToTree(eventRisk.inputs()));
        root.set("observerHardBlockers", stringArray(hardBlockers));
        root.set("executionHardBlockers", stringArray(execution.executionHardBlockers()));
        root.set("futureWritePathRequiredChecks", stringArray(List.of(
                "OCO_PREFLIGHT_PASS",
                "OCO_HEALTH_OK",
                "RUNTIME_EVIDENCE_AVAILABLE",
                "DATA_FRESHNESS_OK",
                "SYSTEM_HEALTH_OK",
                "EXACT_DUPLICATE_OPPORTUNITY_FALSE",
                "MAX_LOSS_WITHIN_BUDGET",
                "CAPITAL_AND_RESERVE_CONSTRAINTS_OK"
        )));
        root.set("nextRearmConditions", stringArray(holdingPolicy.nextRearmConditions()));
        root.set("hardBlockers", stringArray(hardBlockers));
        root.set("riskScalers", stringArray(riskScalers));
        root.set("warnings", stringArray(warnings));
        root.put("invalidationReason", invalidation.reason());
        return write(root);
    }

    private ObjectNode strategyJson(BtStrategy strategy, StrategyParams params) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("id", strategy.getId());
        n.put("name", strategy.getName());
        n.put("type", strategy.getStrategyType());
        n.put("enabled", Boolean.TRUE.equals(strategy.getEnabled()));
        n.put("runIntervalCode", params.runIntervalCode());
        n.put("buyThreshold", params.buyThreshold());
        n.put("rsiOversold", params.rsiOversold());
        n.put("volumeBreakoutMultiplier", params.volumeBreakoutMultiplier());
        return n;
    }

    private List<MdKline> loadBars(String symbol, String intervalCode, int limit) {
        try {
            List<MdKline> rows = new ArrayList<>(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    symbol, intervalCode, PageRequest.of(0, limit)));
            rows.sort(Comparator.comparing(MdKline::getOpenTime));
            return rows;
        } catch (Exception e) {
            log.warn("[ScoreBuyFormingDay] load bars failed symbol={} interval={} error={}", symbol, intervalCode, e.getMessage());
            return List.of();
        }
    }

    private List<MdKline> loadCurrentDay1m(String symbol) {
        LocalDateTime start = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1);
        try {
            return klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(symbol, "1m", start, end);
        } catch (Exception e) {
            log.warn("[ScoreBuyFormingDay] load current 1m failed symbol={} error={}", symbol, e.getMessage());
            return List.of();
        }
    }

    private MdKline buildCurrentDayCandle(String symbol, List<MdKline> oneMinute) {
        MdKline first = oneMinute.get(0);
        MdKline last = oneMinute.get(oneMinute.size() - 1);
        MdKline k = new MdKline();
        k.setSymbol(symbol);
        k.setIntervalCode("1d");
        k.setSource("synthetic_forming_day_from_1m");
        k.setOpenTime(LocalDate.now(ZoneOffset.UTC).atStartOfDay());
        k.setCloseTime(last.getCloseTime());
        k.setOpenPrice(first.getOpenPrice());
        k.setClosePrice(last.getClosePrice());
        k.setHighPrice(oneMinute.stream().map(MdKline::getHighPrice).max(BigDecimal::compareTo).orElse(last.getHighPrice()));
        k.setLowPrice(oneMinute.stream().map(MdKline::getLowPrice).min(BigDecimal::compareTo).orElse(last.getLowPrice()));
        k.setVolume(oneMinute.stream().map(MdKline::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add));
        return k;
    }

    private List<MdKline> withCurrentDay(List<MdKline> confirmedDaily, MdKline forming) {
        List<MdKline> out = new ArrayList<>(confirmedDaily);
        if (forming == null) return out;
        out.removeIf(k -> k.getOpenTime() != null && k.getOpenTime().toLocalDate().equals(forming.getOpenTime().toLocalDate()));
        out.add(forming);
        out.sort(Comparator.comparing(MdKline::getOpenTime));
        return out;
    }

    private List<MdKline> synthesize15mFrom1m(String symbol) {
        List<MdKline> oneMinute = loadBars(symbol, "1m", LOOKBACK_BARS * 16);
        if (oneMinute.isEmpty()) return List.of();
        Map<LocalDateTime, Bucket> buckets = new LinkedHashMap<>();
        for (MdKline k : oneMinute) {
            LocalDateTime t = k.getOpenTime().truncatedTo(ChronoUnit.HOURS)
                    .plusMinutes((k.getOpenTime().getMinute() / 15) * 15L);
            buckets.computeIfAbsent(t, Bucket::new).add(k);
        }
        List<MdKline> out = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            out.add(bucket.toKline(symbol));
        }
        out.sort(Comparator.comparing(MdKline::getOpenTime));
        if (out.size() > LOOKBACK_BARS) {
            return new ArrayList<>(out.subList(out.size() - LOOKBACK_BARS, out.size()));
        }
        return out;
    }

    private FrameState evaluateFrame(List<MdKline> bars, String source, StrategyParams params) {
        if (bars == null || bars.size() < Math.min(params.minWarmupBars(), 40)) {
            return FrameState.insufficient(source, bars == null ? 0 : bars.size(), Math.min(params.minWarmupBars(), 40));
        }
        double[] close = bars.stream().mapToDouble(k -> safeDouble(k.getClosePrice())).toArray();
        double[] volume = bars.stream().mapToDouble(k -> safeDouble(k.getVolume())).toArray();
        double[] rsi = IndicatorUtils.rsi(close, RSI_PERIOD);
        double[] bollMid = IndicatorUtils.bollingerMiddle(close, BB_PERIOD);
        double[] bollLow = IndicatorUtils.bollingerLower(close, BB_PERIOD, 2.0);
        double[] volMa20 = IndicatorUtils.sma(volume, VOL_MA_PERIOD);
        int idx = bars.size() - 1;
        if (!valid(rsi, idx) || !valid(bollMid, idx) || !valid(bollLow, idx) || !valid(volMa20, idx)) {
            return FrameState.unavailable(source, bars.size());
        }
        double closeNow = close[idx];
        double bbTrigger = bollLow[idx] + (bollMid[idx] - bollLow[idx]) * 0.3;
        double volumeRatio = volMa20[idx] <= 0 ? Double.NaN : volume[idx] / volMa20[idx];
        boolean rsiOk = rsi[idx] < params.rsiOversold();
        boolean lowerBb = closeNow < bbTrigger;
        boolean volumeBreakout = Double.isFinite(volumeRatio) && volumeRatio >= params.volumeBreakoutMultiplier();
        List<String> missing = new ArrayList<>();
        if (!rsiOk) missing.add("RSI_NOT_OVERSOLD");
        if (!lowerBb) missing.add("NOT_NEAR_LOWER_BOLLINGER");
        if (!volumeBreakout) missing.add("NO_VOLUME_BREAKOUT");
        return new FrameState(source, "OK", bars.size(), bars.get(idx).getOpenTime(), closeNow, safeDouble(bars.get(idx).getLowPrice()),
                round(rsi[idx], 4), round(params.rsiOversold(), 4), round(bollLow[idx], 4), round(bollMid[idx], 4),
                round(bbTrigger, 4), round(volumeRatio, 4), rsiOk, lowerBb, volumeBreakout, rsiOk && lowerBb && volumeBreakout, missing);
    }

    private IntradayReversal evaluateIntradayReversal(List<MdKline> fifteenMinute, FrameState oneHour) {
        if (fifteenMinute == null || fifteenMinute.size() < 22 || !"OK".equals(oneHour.status())) {
            return new IntradayReversal("INSUFFICIENT_DATA", false, false, false, false);
        }
        MdKline last = fifteenMinute.get(fifteenMinute.size() - 1);
        MdKline prev1 = fifteenMinute.get(fifteenMinute.size() - 2);
        MdKline prev2 = fifteenMinute.get(fifteenMinute.size() - 3);
        boolean noNewLow = last.getLowPrice().compareTo(prev1.getLowPrice().min(prev2.getLowPrice())) >= 0;
        double range = safeDouble(last.getHighPrice().subtract(last.getLowPrice()));
        double lowerWickRatio = range <= 0 ? 0 : (Math.min(safeDouble(last.getOpenPrice()), safeDouble(last.getClosePrice())) - safeDouble(last.getLowPrice())) / range;
        double closePosition = range <= 0 ? 0 : (safeDouble(last.getClosePrice()) - safeDouble(last.getLowPrice())) / range;
        boolean wickRecovery = lowerWickRatio >= 0.35 && closePosition >= 0.55;
        double[] close = fifteenMinute.stream().mapToDouble(k -> safeDouble(k.getClosePrice())).toArray();
        double[] sma20 = IndicatorUtils.sma(close, 20);
        int idx = close.length - 1;
        boolean reclaimSma20 = valid(sma20, idx) && close[idx] > sma20[idx];
        boolean oversold = oneHour.rsi() <= 35.0 || evaluateFrame(fifteenMinute, "15m_reversal", StrategyParams.defaults()).rsi() <= 38.0;
        int pass = (noNewLow ? 1 : 0) + (wickRecovery ? 1 : 0) + (reclaimSma20 ? 1 : 0) + (oversold ? 1 : 0);
        String status = pass >= 2 ? "PASS" : pass == 1 ? "PARTIAL" : "FAILED";
        return new IntradayReversal(status, noNewLow, wickRecovery, reclaimSma20, oversold);
    }

    private boolean latestSynthetic15mClosed(List<MdKline> fifteenMinute, LocalDateTime nowUtc) {
        LocalDateTime closeTime = latestCloseTime(fifteenMinute);
        return closeTime != null && !closeTime.isAfter(nowUtc);
    }

    private List<MdKline> closedSynthetic15mBars(List<MdKline> fifteenMinute, LocalDateTime nowUtc) {
        if (fifteenMinute == null || fifteenMinute.isEmpty()) {
            return List.of();
        }
        List<MdKline> closed = fifteenMinute.stream()
                .filter(k -> k.getCloseTime() != null && !k.getCloseTime().isAfter(nowUtc))
                .toList();
        return closed.isEmpty() ? List.of() : new ArrayList<>(closed);
    }

    private String reversalEvaluationMode(boolean latestSynthetic15mClosed, boolean useClosedBarDecision) {
        if (useClosedBarDecision) {
            return "LAST_CLOSED_15M_WITH_FORMING_BAR_DIAGNOSTIC";
        }
        if (latestSynthetic15mClosed) {
            return "LATEST_CLOSED_15M";
        }
        return "LATEST_SYNTHETIC_15M_FALLBACK_INSUFFICIENT_CLOSED_BARS";
    }

    private LocalDateTime latestOpenTime(List<MdKline> bars) {
        if (bars == null || bars.isEmpty()) return null;
        return bars.get(bars.size() - 1).getOpenTime();
    }

    private LocalDateTime latestCloseTime(List<MdKline> bars) {
        if (bars == null || bars.isEmpty()) return null;
        return bars.get(bars.size() - 1).getCloseTime();
    }

    private Invalidation evaluateInvalidation(List<MdKline> formingDailyBars, FrameState formingDaily, EventRiskLevelEngine.Snapshot eventRisk) {
        if (formingDailyBars == null || formingDailyBars.size() < 2 || !"OK".equals(formingDaily.status())) {
            return new Invalidation(false, "NONE");
        }
        MdKline last = formingDailyBars.get(formingDailyBars.size() - 1);
        MdKline prev = formingDailyBars.get(formingDailyBars.size() - 2);
        BigDecimal invalidationLow = prev.getLowPrice().multiply(new BigDecimal("0.985"));
        if (last.getClosePrice().compareTo(invalidationLow) < 0) {
            return new Invalidation(true, "FORMING_CLOSE_BELOW_PREVIOUS_DAILY_LOW_1_5PCT");
        }
        if (eventRisk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3) && formingDaily.rsi() > 45.0 && !formingDaily.nearLowerBollinger()) {
            return new Invalidation(true, "EVENT_RISK_R3_AND_DAILY_FORMING_SETUP_LOST");
        }
        return new Invalidation(false, "NONE");
    }

    private ScoreBuyFormingState classifyState(FrameState confirmedDaily,
                                               FrameState formingDaily,
                                               IntradayReversal reversal,
                                               RecoveryScout recoveryScout,
                                               Invalidation invalidation,
                                               List<String> hardBlockers,
                                               int watchingSignals,
                                               boolean scoutActive) {
        if (invalidation.invalidated()) return ScoreBuyFormingState.INVALIDATED;
        if (confirmedDaily.dipGatePass()) return ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY;
        if (!hardBlockers.isEmpty()) return watchingSignals >= 3 ? ScoreBuyFormingState.WATCHING : ScoreBuyFormingState.NONE;
        if (isPrePositionSetup(formingDaily, reversal)) {
            return ScoreBuyFormingState.PRE_POSITION;
        }
        if (recoveryScout.pass()) {
            return ScoreBuyFormingState.EARLY_RECOVERY_SCOUT;
        }
        if ("OK".equals(formingDaily.status()) && formingDaily.rsi() <= 40.0 && formingDaily.nearLowerBollinger()) {
            return ScoreBuyFormingState.PRE_TRIGGER;
        }
        if (scoutActive) {
            return ScoreBuyFormingState.SCOUT_ACTIVE;
        }
        return watchingSignals >= 3 ? ScoreBuyFormingState.WATCHING : ScoreBuyFormingState.NONE;
    }

    private RecoveryScout evaluateRecoveryScout(List<MdKline> fifteenMinute, FrameState formingDaily, FrameState oneHour) {
        if (fifteenMinute == null || fifteenMinute.size() < 24 || !"OK".equals(formingDaily.status())) {
            return RecoveryScout.notReady("INSUFFICIENT_DATA");
        }
        int from = Math.max(0, fifteenMinute.size() - 192); // 48h on synthetic 15m bars.
        MdKline recentLowBar = null;
        for (int i = from; i < fifteenMinute.size(); i++) {
            MdKline row = fifteenMinute.get(i);
            if (recentLowBar == null || row.getLowPrice().compareTo(recentLowBar.getLowPrice()) < 0) {
                recentLowBar = row;
            }
        }
        MdKline last = fifteenMinute.get(fifteenMinute.size() - 1);
        double recentLow = safeDouble(recentLowBar == null ? null : recentLowBar.getLowPrice());
        double currentClose = safeDouble(last.getClosePrice());
        double reboundPct = recentLow > 0 ? (currentClose - recentLow) / recentLow * 100.0 : 0.0;
        double dailyBandReference = Double.isFinite(formingDaily.lowerBandTrigger())
                ? formingDaily.lowerBandTrigger()
                : formingDaily.bollLow();
        boolean recentLowNearDailyBand = Double.isFinite(dailyBandReference)
                && recentLow > 0
                && recentLow <= dailyBandReference * 1.025;
        boolean boundedRecovery = reboundPct >= 0.35 && reboundPct <= 5.0;
        boolean stillDiscounted = Double.isFinite(formingDaily.bollMid())
                && currentClose <= formingDaily.bollMid() * 1.01;
        boolean notOverbought = (!Double.isFinite(oneHour.rsi()) || oneHour.rsi() <= 65.0);
        boolean recoveredOffLow = recentLow > 0 && currentClose >= recentLow * 1.003;
        boolean pass = recentLowNearDailyBand && boundedRecovery && stillDiscounted && notOverbought && recoveredOffLow;

        List<String> missing = new ArrayList<>();
        if (!recentLowNearDailyBand) missing.add("RECENT_LOW_NOT_NEAR_DAILY_LOWER_BAND");
        if (!boundedRecovery) missing.add(reboundPct < 0.35 ? "RECOVERY_TOO_SMALL" : "RECOVERY_TOO_EXTENDED");
        if (!stillDiscounted) missing.add("PRICE_NO_LONGER_DISCOUNTED_VS_DAILY_MID_BAND");
        if (!notOverbought) missing.add("ONE_HOUR_RSI_OVERHEATED");
        if (!recoveredOffLow) missing.add("NO_RECOVERY_FROM_RECENT_LOW");

        return new RecoveryScout(pass ? "PASS" : "NOT_TRIGGERED",
                pass,
                recentLowBar == null ? null : recentLowBar.getOpenTime(),
                round(recentLow, 2),
                round(currentClose, 2),
                round(reboundPct, 4),
                round(dailyBandReference, 2),
                recentLowNearDailyBand,
                stillDiscounted,
                notOverbought,
                recoveredOffLow,
                missing);
    }

    private boolean isPrePositionSetup(FrameState formingDaily, IntradayReversal reversal) {
        if (!"OK".equals(formingDaily.status()) || !"PASS".equals(reversal.status())) {
            return false;
        }
        double reboundPct = reboundFromLowPct(formingDaily);
        boolean boundedRebound = reboundPct >= 0.8 && reboundPct <= 3.5;
        return formingDaily.rsi() <= 42.0
                && formingDaily.nearLowerBollinger()
                && boundedRebound;
    }

    private ObjectNode prePositionTriggerJson(FrameState formingDaily, IntradayReversal reversal) {
        ObjectNode node = objectMapper.createObjectNode();
        double reboundPct = reboundFromLowPct(formingDaily);
        boolean pass = isPrePositionSetup(formingDaily, reversal);
        node.put("status", pass ? "PASS" : "NOT_TRIGGERED");
        node.put("rule", "formingDailyNearLowerBb && intradayReversal PASS && formingDailyRsi<=42 && reboundFromDayLowPct between 0.8 and 3.5; volume breakout is not required for bounded pre-position.");
        node.put("reboundFromDayLowPct", round(reboundPct, 4));
        node.put("rsiRelaxedThreshold", 42.0);
        node.put("requiresVolumeBreakout", false);
        node.put("volumePolicy", "volume breakout is not required and high recovery volume is not a hard blocker for bounded pre-position; confirmed large deploy still requires daily confirmation.");
        ArrayNode missing = node.putArray("missingReasons");
        if (!"OK".equals(formingDaily.status())) missing.add("FORMING_DAILY_NOT_OK");
        if (!formingDaily.nearLowerBollinger()) missing.add("NOT_NEAR_LOWER_BOLLINGER");
        if (!"PASS".equals(reversal.status())) missing.add("INTRADAY_REVERSAL_NOT_PASS");
        if (formingDaily.rsi() > 42.0) missing.add("FORMING_RSI_ABOVE_RELAXED_PRE_POSITION_THRESHOLD");
        if (reboundPct < 0.8) missing.add("REBOUND_FROM_DAY_LOW_TOO_SMALL");
        if (reboundPct > 3.5) missing.add("REBOUND_FROM_DAY_LOW_TOO_EXTENDED");
        return node;
    }

    private double reboundFromLowPct(FrameState formingDaily) {
        if (!"OK".equals(formingDaily.status()) || formingDaily.low() <= 0 || formingDaily.close() <= 0) {
            return 0.0;
        }
        return (formingDaily.close() - formingDaily.low()) / formingDaily.low() * 100.0;
    }

    private int watchingSignalCount(FrameState formingDaily, FrameState oneHour, FrameState fifteenMinute, List<MdKline> formingDailyBars) {
        int count = 0;
        if ("OK".equals(formingDaily.status()) && formingDaily.rsi() <= 42.0) count++;
        if ("OK".equals(formingDaily.status()) && formingDaily.nearLowerBollinger()) count++;
        if ("OK".equals(oneHour.status()) && oneHour.rsi() <= 35.0) count++;
        if ("OK".equals(fifteenMinute.status()) && fifteenMinute.rsi() <= 38.0) count++;
        if (dailyChangePct(formingDailyBars) <= -2.0) count++;
        if (fourHourChangePct() <= -1.5) count++;
        return count;
    }

    private double dailyChangePct(List<MdKline> dailyBars) {
        if (dailyBars == null || dailyBars.size() < 2) return 0.0;
        MdKline prev = dailyBars.get(dailyBars.size() - 2);
        MdKline last = dailyBars.get(dailyBars.size() - 1);
        double prevClose = safeDouble(prev.getClosePrice());
        if (prevClose <= 0) return 0.0;
        return (safeDouble(last.getClosePrice()) - prevClose) / prevClose * 100.0;
    }

    private double fourHourChangePct() {
        List<MdKline> bars = loadBars(DEFAULT_SYMBOL, "1h", 5);
        if (bars.size() < 5) return 0.0;
        double start = safeDouble(bars.get(0).getClosePrice());
        double end = safeDouble(bars.get(bars.size() - 1).getClosePrice());
        return start <= 0 ? 0.0 : (end - start) / start * 100.0;
    }

    private BigDecimal recommendedNotional(ScoreBuyFormingState state, BigDecimal tradingUsdt, BigDecimal remainingBudget, double riskMultiplier) {
        if (state != ScoreBuyFormingState.EARLY_RECOVERY_SCOUT
                && state != ScoreBuyFormingState.PRE_POSITION
                && state != ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.DOWN);
        }
        BigDecimal pct = state == ScoreBuyFormingState.EARLY_RECOVERY_SCOUT ? RECOVERY_SCOUT_PCT : PRE_POSITION_PCT;
        BigDecimal max = state == ScoreBuyFormingState.EARLY_RECOVERY_SCOUT ? MAX_RECOVERY_SCOUT_USDT : MAX_PRE_POSITION_USDT;
        BigDecimal deployable = tradingUsdt == null ? BigDecimal.ZERO : tradingUsdt.multiply(pct);
        BigDecimal base = max.min(deployable).min(remainingBudget.max(BigDecimal.ZERO));
        return base.multiply(BigDecimal.valueOf(riskMultiplier)).setScale(2, RoundingMode.DOWN);
    }

    private ExecutionPreview executionPreview(List<String> observerHardBlockers, BigDecimal recommendedNotional) {
        List<String> executionHardBlockers = new ArrayList<>();
        executionHardBlockers.add("EXECUTION_NOT_EVALUATED_READ_ONLY");
        executionHardBlockers.addAll(observerHardBlockers);
        boolean belowMin = recommendedNotional != null
                && recommendedNotional.compareTo(BigDecimal.ZERO) > 0
                && recommendedNotional.compareTo(EXCHANGE_MIN_NOTIONAL_USDT) < 0;
        if (belowMin) {
            executionHardBlockers.add("BELOW_EXCHANGE_MIN_NOTIONAL");
        }
        String readiness;
        if (!observerHardBlockers.isEmpty()) {
            readiness = "NOT_EXECUTABLE_OBSERVER_HARD_BLOCKERS";
        } else if (belowMin) {
            readiness = "NOT_EXECUTABLE_BELOW_MIN_NOTIONAL";
        } else if (recommendedNotional == null || recommendedNotional.compareTo(BigDecimal.ZERO) <= 0) {
            readiness = "NOT_EXECUTABLE_NO_RECOMMENDED_NOTIONAL";
        } else {
            readiness = "NOT_EXECUTABLE_READ_ONLY_PREVIEW";
        }
        return new ExecutionPreview(belowMin, false, readiness, executionHardBlockers);
    }

    private String recommendedAction(ScoreBuyFormingState state,
                                     List<String> hardBlockers,
                                     BigDecimal notional,
                                     ExecutionPreview execution,
                                     HoldingPolicy holdingPolicy) {
        if (holdingPolicy.holdBtcMode()) return "HOLD_BTC_NO_AUTO_SELL_NO_MORE_ADD_KEEP_DISASTER_OCO";
        if (!hardBlockers.isEmpty()) return "NO_ACTION_HARD_BLOCKERS_PRESENT";
        if (execution.belowExchangeMinimum()) return "PRE_POSITION_PREVIEW_ONLY_BELOW_MIN_NOTIONAL";
        return switch (state) {
            case INVALIDATED -> "NO_ACTION_INVALIDATED_HOLD_BTC_IF_SCOUT_OPEN";
            case NONE -> "NO_ACTION";
            case WATCHING -> "OBSERVE_ONLY";
            case PRE_TRIGGER -> "PREPARE_ONLY_NO_ORDER";
            case SCOUT_ACTIVE -> "MONITOR_ACTIVE_SCOUT";
            case EARLY_RECOVERY_SCOUT -> notional.compareTo(BigDecimal.ZERO) > 0 ? "EARLY_RECOVERY_SCOUT_PREVIEW_ONLY" : "NO_ACTION_NO_REMAINING_BUDGET";
            case PRE_POSITION -> notional.compareTo(BigDecimal.ZERO) > 0 ? "PRE_POSITION_PREVIEW_ONLY" : "NO_ACTION_NO_REMAINING_BUDGET";
            case CONFIRMED_DAILY_SCORE_BUY -> "DAILY_SCORE_BUY_CONFIRMED_PREVIEW_ONLY";
        };
    }

    private boolean autoAddAllowed(ScoreBuyFormingState state,
                                   List<String> hardBlockers,
                                   HoldingPolicy holdingPolicy) {
        if (holdingPolicy.holdBtcMode() || !hardBlockers.isEmpty()) return false;
        return state == ScoreBuyFormingState.EARLY_RECOVERY_SCOUT
                || state == ScoreBuyFormingState.PRE_POSITION
                || state == ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY;
    }

    private String missedOpportunityRisk(ScoreBuyFormingState state, EventRiskLevelEngine.RiskLevel riskLevel, int watchingSignals) {
        if (state == ScoreBuyFormingState.EARLY_RECOVERY_SCOUT
                || state == ScoreBuyFormingState.PRE_POSITION
                || state == ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY) {
            return riskLevel.atLeast(EventRiskLevelEngine.RiskLevel.R3) ? "HIGH_BUT_R3_SCALED" : "HIGH";
        }
        if (state == ScoreBuyFormingState.SCOUT_ACTIVE) return "MONITORING_ACTIVE_SCOUT";
        if (state == ScoreBuyFormingState.PRE_TRIGGER || watchingSignals >= 4) return "MEDIUM";
        if (state == ScoreBuyFormingState.WATCHING) return "LOW";
        return "NONE";
    }

    private String postScoutLifecycleState(ScoreBuyFormingState state, SameThesisExposure sameThesis) {
        if (sameThesis.openPositionCount() <= 0) {
            return "NO_OPEN_SCOUT";
        }
        if (state == ScoreBuyFormingState.INVALIDATED) {
            return "STRUCTURE_BROKEN_HOLD_BTC";
        }
        if (state == ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY) {
            return "SCOUT_OPEN_DAILY_CONFIRMED";
        }
        if (state == ScoreBuyFormingState.EARLY_RECOVERY_SCOUT || state == ScoreBuyFormingState.PRE_POSITION) {
            return "SCOUT_OPEN_SETUP_STILL_ACTIVE";
        }
        if (state == ScoreBuyFormingState.SCOUT_ACTIVE) {
            return "SCOUT_ACTIVE_MONITORING";
        }
        return "SCOUT_OPEN_FORMING_STATE_" + state.name();
    }

    private String postScoutLifecycleAction(ScoreBuyFormingState state, SameThesisExposure sameThesis) {
        if (sameThesis.openPositionCount() <= 0) {
            return "NO_OPEN_SCOUT";
        }
        if (state == ScoreBuyFormingState.INVALIDATED) {
            return "HOLD_BTC_NO_AUTO_SELL_NO_MORE_ADD_KEEP_DISASTER_OCO";
        }
        if (state == ScoreBuyFormingState.CONFIRMED_DAILY_SCORE_BUY) {
            return "MONITOR_SCOUT_AND_WAIT_FOR_STAGED_DAILY_DEPLOY_CHECKS";
        }
        return "MONITOR_ACTIVE_SCOUT_NO_ADDITIONAL_ORDER_IMPLIED";
    }

    private HoldingPolicy holdingPolicy(ScoreBuyFormingState state,
                                        SameThesisExposure sameThesis,
                                        Invalidation invalidation) {
        if (sameThesis.openPositionCount() <= 0) {
            return new HoldingPolicy("NO_OPEN_SCORE_BUY_BTC", false, false,
                    "No open SCORE_BUY scout/pre-position exists.",
                    List.of("OPEN_SCORE_BUY_SCOUT_OR_PRE_POSITION"));
        }
        if (state == ScoreBuyFormingState.INVALIDATED) {
            return new HoldingPolicy("STRUCTURE_BROKEN_HOLD_BTC", true, true,
                    "SCORE_BUY forming structure is invalidated; keep existing BTC position, do not auto-sell, do not add exposure, keep 12% disaster OCO.",
                    List.of(
                            "DAILY_SCORE_BUY_RECONFIRMED",
                            "FORMING_DAY_INVALIDATION_CLEARED",
                            "OCO_HEALTH_OK",
                            "RUNTIME_EVIDENCE_AVAILABLE",
                            "SCORE_BUY_STAGED_BUDGET_AVAILABLE",
                            "OPERATOR_CAN_REVIEW_REARM_BEFORE_NEW_ADD"
                    ));
        }
        return new HoldingPolicy("ACCUMULATION_ACTIVE", false, false,
                "Open SCORE_BUY scout/pre-position exists and structure is not invalidated.",
                List.of(
                        "WAIT_FOR_PULLBACK_OR_DAILY_CONFIRMATION",
                        "RECHECK_OCO_HEALTH_AND_RUNTIME_EVIDENCE_BEFORE_ANY_ADD"
                ));
    }

    private EventRiskLevelEngine.Snapshot safeEventRisk(String symbol) {
        try {
            return eventRiskLevelEngine.evaluate(symbol);
        } catch (Exception e) {
            return new EventRiskLevelEngine.Snapshot(symbol, 0, EventRiskLevelEngine.RiskLevel.R0, List.of("event_risk_unavailable=" + e.getMessage()), Map.of(), LocalDateTime.now(ZoneOffset.UTC));
        }
    }

    private double eventRiskMultiplier(EventRiskLevelEngine.RiskLevel level) {
        if (level.atLeast(EventRiskLevelEngine.RiskLevel.R3)) return 0.25;
        if (level.atLeast(EventRiskLevelEngine.RiskLevel.R2)) return 0.50;
        return 1.0;
    }

    private CapitalSnapshot readCapitalSnapshot(String symbol) {
        try {
            CapitalAllocationPolicyPreviewService.CapitalAllocationSnapshot snapshot =
                    capitalAllocationPolicyPreviewService.snapshot(symbol);
            return new CapitalSnapshot(
                    snapshot.freeUsdt(),
                    snapshot.earnFlexibleUsdt(),
                    snapshot.totalObservedCapitalUsdt(),
                    snapshot.liquidAfterReserveUsdt(),
                    snapshot.deployableAfterPlannedRedeemUsdt(),
                    snapshot.scoreBuyReserveTargetUsdt(),
                    snapshot.scoreBuyRedeemNeededUsdt(),
                    snapshot.requiresEarnReserveTopUp(),
                    snapshot.warnings());
        } catch (Exception e) {
            return new CapitalSnapshot(
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    false,
                    List.of("capitalAllocationSnapshotReadFailed=" + e.getMessage()));
        }
    }

    private SameThesisExposure readSameThesisExposure(String symbol, long strategyId, double currentPrice) {
        BigDecimal exposure = BigDecimal.ZERO;
        int openCount = 0;
        try {
            for (BtLiveSignal p : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
                if (!symbol.equalsIgnoreCase(p.getSymbol())) continue;
                if (p.getStrategyId() == null || p.getStrategyId() != strategyId) continue;
                openCount++;
                BigDecimal qty = p.getOcoQty() != null ? p.getOcoQty() : p.getTradedQty();
                BigDecimal entry = p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
                BigDecimal mark = currentPrice > 0 ? BigDecimal.valueOf(currentPrice) : entry;
                if (qty != null && mark != null) {
                    exposure = exposure.add(qty.abs().multiply(mark));
                }
            }
        } catch (Exception e) {
            return new SameThesisExposure(BigDecimal.ZERO, 0, SAME_THESIS_BUDGET_USDT, List.of("sameThesisExposureReadFailed=" + e.getMessage()));
        }
        return new SameThesisExposure(exposure.setScale(2, RoundingMode.HALF_UP), openCount, SAME_THESIS_BUDGET_USDT, List.of());
    }

    private JsonNode readConfig(String configJson) {
        try {
            return objectMapper.readTree(configJson == null || configJson.isBlank() ? "{}" : configJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String write(ObjectNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private ArrayNode stringArray(List<String> values) {
        ArrayNode arr = objectMapper.createArrayNode();
        values.forEach(arr::add);
        return arr;
    }

    private static JsonNode finiteOrNull(double value) {
        if (!Double.isFinite(value)) return com.fasterxml.jackson.databind.node.NullNode.getInstance();
        return com.fasterxml.jackson.databind.node.DoubleNode.valueOf(value);
    }

    private static void putMoney(ObjectNode n, String field, BigDecimal value) {
        if (value == null) n.putNull(field);
        else n.put(field, value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }

    private static void putTime(ObjectNode n, String field, LocalDateTime value) {
        if (value == null) n.putNull(field);
        else n.put(field, value.toString());
    }

    private static String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean valid(double[] arr, int idx) {
        return arr != null && idx >= 0 && idx < arr.length && !Double.isNaN(arr[idx]) && Double.isFinite(arr[idx]);
    }

    private static double safeDouble(BigDecimal value) {
        return value == null ? 0.0 : value.doubleValue();
    }

    private static double round(double value, int scale) {
        if (!Double.isFinite(value)) return value;
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static double percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) return 0.0;
        return numerator.multiply(new BigDecimal("100")).divide(denominator, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static String nullTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private enum ScoreBuyFormingState {
        NONE,
        WATCHING,
        PRE_TRIGGER,
        SCOUT_ACTIVE,
        EARLY_RECOVERY_SCOUT,
        PRE_POSITION,
        CONFIRMED_DAILY_SCORE_BUY,
        INVALIDATED
    }

    private record StrategyParams(double buyThreshold,
                                  double rsiOversold,
                                  double volumeBreakoutMultiplier,
                                  int minWarmupBars,
                                  String runIntervalCode) {
        static StrategyParams from(JsonNode config) {
            return new StrategyParams(
                    config.path("buyThreshold").asDouble(0.70),
                    config.path("rsiOversold").asDouble(35.0),
                    config.path("volumeBreakoutMultiplier").asDouble(1.30),
                    config.path("minWarmupBars").asInt(200),
                    config.path("runIntervalCode").asText("1d")
            );
        }

        static StrategyParams defaults() {
            return new StrategyParams(0.70, 35.0, 1.30, 40, "1d");
        }
    }

    private record FrameState(String source,
                              String status,
                              int barsUsed,
                              LocalDateTime latestOpenTime,
                              double close,
                              double low,
                              double rsi,
                              double rsiThreshold,
                              double bollLow,
                              double bollMid,
                              double lowerBandTrigger,
                              double volumeRatio,
                              boolean rsiOk,
                              boolean nearLowerBollinger,
                              boolean volumeBreakout,
                              boolean dipGatePass,
                              List<String> missingReasons) {
        static FrameState insufficient(String source, int bars, int required) {
            return new FrameState(source, "INSUFFICIENT_BARS", bars, null, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    false, false, false, false, List.of("INSUFFICIENT_BARS required=" + required + " actual=" + bars));
        }

        static FrameState unavailable(String source, int bars) {
            return new FrameState(source, "INDICATOR_UNAVAILABLE", bars, null, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    false, false, false, false, List.of("INDICATOR_UNAVAILABLE"));
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("source", source);
            n.put("status", status);
            n.put("barsUsed", barsUsed);
            if (latestOpenTime != null) n.put("latestOpenTime", latestOpenTime.toString());
            putFinite(n, "close", close);
            putFinite(n, "low", low);
            putFinite(n, "rsi", rsi);
            putFinite(n, "rsiThreshold", rsiThreshold);
            putFinite(n, "bollLow", bollLow);
            putFinite(n, "bollMid", bollMid);
            putFinite(n, "lowerBandTrigger", lowerBandTrigger);
            putFinite(n, "volumeRatio", volumeRatio);
            n.put("rsiOk", rsiOk);
            n.put("nearLowerBollinger", nearLowerBollinger);
            n.put("volumeBreakout", volumeBreakout);
            n.put("dipGatePass", dipGatePass);
            ArrayNode arr = n.putArray("missingReasons");
            missingReasons.forEach(arr::add);
            return n;
        }

        private static void putFinite(ObjectNode n, String field, double value) {
            if (Double.isFinite(value)) n.put(field, value);
            else n.putNull(field);
        }
    }

    private record IntradayReversal(String status,
                                    boolean noNewLow,
                                    boolean lowerWickRecovery,
                                    boolean reclaimSma20,
                                    boolean intradayOversold) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("status", status);
            n.put("noNewLow", noNewLow);
            n.put("lowerWickRecovery", lowerWickRecovery);
            n.put("reclaimSma20", reclaimSma20);
            n.put("intradayOversold", intradayOversold);
            return n;
        }
    }

    private record Invalidation(boolean invalidated, String reason) {
    }

    private record HoldingPolicy(String state,
                                 boolean structureBroken,
                                 boolean holdBtcMode,
                                 String reason,
                                 List<String> nextRearmConditions) {
    }

    private record RecoveryScout(String status,
                                 boolean pass,
                                 LocalDateTime recentLowTime,
                                 double recentLow,
                                 double currentClose,
                                 double reboundFromRecentLowPct,
                                 double dailyBandReference,
                                 boolean recentLowNearDailyBand,
                                 boolean stillDiscounted,
                                 boolean notOverbought,
                                 boolean recoveredOffLow,
                                 List<String> missingReasons) {
        static RecoveryScout notReady(String reason) {
            return new RecoveryScout("NOT_READY", false, null, Double.NaN, Double.NaN, 0.0,
                    Double.NaN, false, false, false, false, List.of(reason));
        }

        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            n.put("status", status);
            n.put("pass", pass);
            if (recentLowTime != null) n.put("recentLowTime", recentLowTime.toString());
            putFinite(n, "recentLow", recentLow);
            putFinite(n, "currentClose", currentClose);
            putFinite(n, "reboundFromRecentLowPct", reboundFromRecentLowPct);
            putFinite(n, "dailyBandReference", dailyBandReference);
            n.put("recentLowNearDailyBand", recentLowNearDailyBand);
            n.put("stillDiscounted", stillDiscounted);
            n.put("notOverbought", notOverbought);
            n.put("recoveredOffLow", recoveredOffLow);
            n.put("rule", "recent 48h low near daily lower-band reference, rebound 0.35%-5.0%, still below daily mid-band+1%, 1h RSI<=65, and recovered off low.");
            ArrayNode arr = n.putArray("missingReasons");
            missingReasons.forEach(arr::add);
            return n;
        }

        private static void putFinite(ObjectNode n, String field, double value) {
            if (Double.isFinite(value)) n.put(field, value);
            else n.putNull(field);
        }
    }

    private record ExecutionPreview(boolean belowExchangeMinimum,
                                    boolean executionFeasible,
                                    String executionReadiness,
                                    List<String> executionHardBlockers) {
    }

    private record CapitalSnapshot(BigDecimal tradingUsdt,
                                   BigDecimal earnUsdt,
                                   BigDecimal observedTotalUsdt,
                                   BigDecimal liquidAfterReserveUsdt,
                                   BigDecimal reserveAwareDeployableUsdt,
                                   BigDecimal scoreBuyReserveTargetUsdt,
                                   BigDecimal scoreBuyRedeemNeededUsdt,
                                   boolean requiresEarnReserveTopUp,
                                   List<String> notes) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            putMoney(n, "deployableTradingUsdt", tradingUsdt);
            putMoney(n, "earnFlexibleUsdt", earnUsdt);
            putMoney(n, "observedTotalUsdt", observedTotalUsdt);
            putMoney(n, "liquidAfterReserveUsdt", liquidAfterReserveUsdt);
            putMoney(n, "reserveAwareDeployableUsdt", reserveAwareDeployableUsdt);
            putMoney(n, "scoreBuyReserveTargetUsdt", scoreBuyReserveTargetUsdt);
            putMoney(n, "scoreBuyRedeemNeededUsdt", scoreBuyRedeemNeededUsdt);
            n.put("requiresEarnReserveTopUp", requiresEarnReserveTopUp);
            n.put("earnAutoRedeemInObserver", false);
            ArrayNode arr = n.putArray("notes");
            notes.forEach(arr::add);
            return n;
        }
    }

    private record SameThesisExposure(BigDecimal exposureUsdt, int openPositionCount, BigDecimal budgetUsdt, List<String> notes) {
        ObjectNode toJson(ObjectMapper mapper) {
            ObjectNode n = mapper.createObjectNode();
            putMoney(n, "sameThesisExposureUsed", exposureUsdt);
            n.put("sameThesisOpenPositionCount", openPositionCount);
            putMoney(n, "sameThesisExposureLimit", budgetUsdt);
            n.put("sameThesisExposureUsedPct", percent(exposureUsdt, budgetUsdt));
            n.put("policy", "exact duplicate remains hard block; same thesis exposure should use staged add-budget check in future write path.");
            ArrayNode arr = n.putArray("notes");
            notes.forEach(arr::add);
            return n;
        }
    }

    private record Bucket(LocalDateTime openTime, List<MdKline> rows) {
        Bucket(LocalDateTime openTime) {
            this(openTime, new ArrayList<>());
        }

        void add(MdKline row) {
            rows.add(row);
        }

        MdKline toKline(String symbol) {
            rows.sort(Comparator.comparing(MdKline::getOpenTime));
            MdKline first = rows.get(0);
            MdKline last = rows.get(rows.size() - 1);
            MdKline k = new MdKline();
            k.setSymbol(symbol);
            k.setIntervalCode("15m");
            k.setSource("synthetic_from_1m");
            k.setOpenTime(openTime);
            k.setCloseTime(openTime.plusMinutes(15));
            k.setOpenPrice(first.getOpenPrice());
            k.setClosePrice(last.getClosePrice());
            k.setHighPrice(rows.stream().map(MdKline::getHighPrice).max(BigDecimal::compareTo).orElse(last.getHighPrice()));
            k.setLowPrice(rows.stream().map(MdKline::getLowPrice).min(BigDecimal::compareTo).orElse(last.getLowPrice()));
            k.setVolume(rows.stream().map(MdKline::getVolume).reduce(BigDecimal.ZERO, BigDecimal::add));
            return k;
        }
    }
}
