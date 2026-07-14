package com.agora.service.trading;

import com.agora.model.MdKline;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static com.agora.service.trading.BtcDonchianShadowPolicy.ATR_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.ENTRY_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.EQUITY_RISK_PER_TRADE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.EXIT_LOOKBACK_DAYS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INITIAL_STOP_ATR_MULTIPLE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.MAXIMUM_EXPOSURE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STATE_SCHEMA_VERSION;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;

/** Deterministic one-hour state machine shared by research parity and runtime SHADOW evidence. */
@Service
@RequiredArgsConstructor
public class BtcDonchianShadowEngine {

    private static final double EPSILON = 0.0000000001;
    private static final double FLAT_EPSILON = 0.000000000000001;
    private static final int RETAINED_DAILY_BARS = ENTRY_LOOKBACK_DAYS;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");
    private static final Pattern POWER_SHELL_FIXED_POINT_EXPONENT = Pattern.compile("-?\\d+(?:\\.\\d+)?E-4");
    private static final Pattern SINGLE_DIGIT_JSON_EXPONENT = Pattern.compile("E([+-])(\\d)(?=[,}\\]])");

    private final ObjectMapper objectMapper;

    public State initialState() {
        State state = new State();
        state.setSchemaVersion(STATE_SCHEMA_VERSION);
        state.setCompletedDays(new ArrayList<>());
        Map<String, ScenarioState> scenarios = new LinkedHashMap<>();
        scenarios.put(NORMAL.name(), ScenarioState.initial(NORMAL.name()));
        scenarios.put(STRESS.name(), ScenarioState.initial(STRESS.name()));
        state.setScenarios(scenarios);
        return state;
    }

    public StepResult step(State state, MdKline bar) {
        if (state == null) throw new DataQualityException("STATE_MISSING");
        validateState(state);
        validateBar(state, bar);

        Map<String, List<Map<String, Object>>> signals = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> orders = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> trades = new LinkedHashMap<>();
        List<RuntimeEvent> events = new ArrayList<>();
        for (BtcDonchianShadowPolicy.Scenario scenario : List.of(NORMAL, STRESS)) {
            signals.put(scenario.name(), new ArrayList<>());
            orders.put(scenario.name(), new ArrayList<>());
            trades.put(scenario.name(), new ArrayList<>());
            processAtOpen(state.getScenarios().get(scenario.name()), scenario, bar,
                    orders.get(scenario.name()), trades.get(scenario.name()), events);
        }

        DailyBar completedDay = updateDailyState(state, bar);
        if (completedDay != null) {
            for (BtcDonchianShadowPolicy.Scenario scenario : List.of(NORMAL, STRESS)) {
                evaluateDailyClose(state, state.getScenarios().get(scenario.name()), scenario,
                        completedDay, bar, signals.get(scenario.name()), events);
            }
            appendCompletedDay(state, completedDay);
        }

        for (ScenarioState scenario : state.getScenarios().values()) {
            scenario.setCurrentEquity(scenario.getCash() + (scenario.getQuantity() * bar.getClosePrice().doubleValue()));
        }
        state.setLastProcessedBarOpenTime(bar.getOpenTime());
        state.setProcessedBars(state.getProcessedBars() + 1);
        return new StepResult(state, events, signals, orders, trades);
    }

    public ReplayResult replay(List<MdKline> inputBars) {
        if (inputBars == null || inputBars.isEmpty()) {
            throw new DataQualityException("NO_BARS");
        }
        List<MdKline> bars = new ArrayList<>(inputBars);
        bars.sort(Comparator.comparing(MdKline::getOpenTime));
        State state = initialState();
        Map<String, List<Map<String, Object>>> signals = ledgerMap();
        Map<String, List<Map<String, Object>>> orders = ledgerMap();
        Map<String, List<Map<String, Object>>> trades = ledgerMap();
        for (MdKline bar : bars) {
            StepResult step = step(state, bar);
            for (BtcDonchianShadowPolicy.Scenario scenario : List.of(NORMAL, STRESS)) {
                signals.get(scenario.name()).addAll(step.signalLedgers().get(scenario.name()));
                orders.get(scenario.name()).addAll(step.orderLedgers().get(scenario.name()));
                trades.get(scenario.name()).addAll(step.tradeLedgers().get(scenario.name()));
            }
        }
        Map<String, ScenarioReplay> scenarios = new LinkedHashMap<>();
        for (BtcDonchianShadowPolicy.Scenario scenario : List.of(NORMAL, STRESS)) {
            List<Map<String, Object>> signalRows = List.copyOf(signals.get(scenario.name()));
            List<Map<String, Object>> orderRows = List.copyOf(orders.get(scenario.name()));
            List<Map<String, Object>> tradeRows = List.copyOf(trades.get(scenario.name()));
            scenarios.put(scenario.name(), new ScenarioReplay(
                    scenario.name(), signalRows, orderRows, tradeRows,
                    sha256PowerShellLedger(signalRows), sha256PowerShellLedger(orderRows),
                    sha256PowerShellLedger(tradeRows),
                    state.getScenarios().get(scenario.name())));
        }
        return new ReplayResult(state, scenarios, bars.get(0).getOpenTime(),
                bars.get(bars.size() - 1).getOpenTime(), bars.size());
    }

    public String stateSha256(State state) {
        return sha256Json(state);
    }

    /** Hashes every signal-relevant source bar without relying on database decimal scale formatting. */
    public String canonicalPriceBarLedgerSha256(List<MdKline> inputBars) {
        if (inputBars == null || inputBars.isEmpty()) {
            throw new DataQualityException("NO_BARS");
        }
        try {
            List<MdKline> bars = new ArrayList<>(inputBars);
            bars.sort(Comparator.comparing(MdKline::getOpenTime));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < bars.size(); i++) {
                MdKline bar = bars.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("sequence", i + 1);
                row.put("symbol", normalizeSymbol(bar.getSymbol()));
                row.put("intervalCode", bar.getIntervalCode().toLowerCase(Locale.ROOT));
                row.put("source", bar.getSource().toLowerCase(Locale.ROOT));
                row.put("openTimeUtc", utc(bar.getOpenTime()));
                row.put("closeTimeUtc", utc(bar.getCloseTime()));
                row.put("open", canonicalDecimal(bar.getOpenPrice()));
                row.put("high", canonicalDecimal(bar.getHighPrice()));
                row.put("low", canonicalDecimal(bar.getLowPrice()));
                row.put("close", canonicalDecimal(bar.getClosePrice()));
                digest.update(objectMapper.writeValueAsBytes(row));
                digest.update((byte) '\n');
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (DataQualityException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash canonical Donchian price bars", e);
        }
    }

    private Map<String, List<Map<String, Object>>> ledgerMap() {
        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        result.put(NORMAL.name(), new ArrayList<>());
        result.put(STRESS.name(), new ArrayList<>());
        return result;
    }

    private void processAtOpen(ScenarioState state,
                               BtcDonchianShadowPolicy.Scenario scenario,
                               MdKline bar,
                               List<Map<String, Object>> orderLedger,
                               List<Map<String, Object>> tradeLedger,
                               List<RuntimeEvent> events) {
        PendingAction pending = state.getPendingAction();
        if (pending != null) {
            if (pending.getExecutionTime().isBefore(bar.getOpenTime())) {
                throw new DataQualityException("PENDING_EXECUTION_MISSED:" + scenario.name());
            }
            if (pending.getExecutionTime().equals(bar.getOpenTime())) {
                executePending(state, scenario, pending, bar, orderLedger, tradeLedger, events);
                state.setPendingAction(null);
            }
        }

        if (state.getQuantity() > 0.0 && state.getStopPrice() != null
                && bar.getLowPrice().doubleValue() <= state.getStopPrice()) {
            executeStop(state, scenario, bar, orderLedger, tradeLedger, events);
        }
    }

    private void executePending(ScenarioState state,
                                BtcDonchianShadowPolicy.Scenario scenario,
                                PendingAction action,
                                MdKline bar,
                                List<Map<String, Object>> orderLedger,
                                List<Map<String, Object>> tradeLedger,
                                List<RuntimeEvent> events) {
        double open = bar.getOpenPrice().doubleValue();
        double targetExposure = action.getTargetExposure();
        if ("DONCHIAN_ENTRY".equals(action.getKind())) {
            double stopDistance = action.getAtr() * INITIAL_STOP_ATR_MULTIPLE;
            double stopDistancePct = open > 0.0 ? stopDistance / open : 1.0;
            targetExposure = stopDistancePct > 0.0
                    ? Math.min(MAXIMUM_EXPOSURE, EQUITY_RISK_PER_TRADE / stopDistancePct)
                    : 0.0;
        }
        targetExposure = Math.max(0.0, Math.min(MAXIMUM_EXPOSURE, targetExposure));
        double equityAtOpen = state.getCash() + (state.getQuantity() * open);
        double currentNotional = state.getQuantity() * open;
        double targetNotional = Math.max(0.0, equityAtOpen * targetExposure);
        double delta = targetNotional - currentNotional;
        if (delta > EPSILON && state.getCash() > 0.0) {
            executeBuy(state, scenario, action, bar, targetExposure, equityAtOpen,
                    delta, orderLedger, events);
        } else if (delta < -EPSILON && state.getQuantity() > 0.0) {
            executeSell(state, scenario, action, bar, targetExposure,
                    orderLedger, tradeLedger, events);
        }
    }

    private void executeBuy(ScenarioState state,
                            BtcDonchianShadowPolicy.Scenario scenario,
                            PendingAction action,
                            MdKline bar,
                            double targetExposure,
                            double equityAtOpen,
                            double delta,
                            List<Map<String, Object>> orderLedger,
                            List<RuntimeEvent> events) {
        double gross = Math.min(delta, state.getCash() / (1.0 + scenario.feeRatePerSide()));
        if (gross <= 0.0) return;
        double fillPrice = bar.getOpenPrice().doubleValue() * (1.0 + scenario.adverseSlippageRatePerSide());
        double bought = gross / fillPrice;
        double fee = gross * scenario.feeRatePerSide();
        boolean wasFlat = state.getQuantity() <= FLAT_EPSILON;
        double cashBefore = state.getCash();
        double quantityBefore = state.getQuantity();
        state.setCash(state.getCash() - (gross + fee));
        state.setQuantity(state.getQuantity() + bought);
        state.setFees(state.getFees() + fee);
        state.setTurnover(state.getTurnover() + gross);
        state.setOrderCount(state.getOrderCount() + 1);
        if (wasFlat) {
            state.setEntryCount(state.getEntryCount() + 1);
            state.setActiveEntrySignalId(action.getSignalId());
            state.setActiveTradeStartEquity(equityAtOpen);
            state.setActiveTradeEntryTime(bar.getOpenTime());
        }
        if ("DONCHIAN_ENTRY".equals(action.getKind()) && wasFlat) {
            state.setStopPrice(fillPrice - (action.getAtr() * INITIAL_STOP_ATR_MULTIPLE));
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", state.getOrderCount());
        row.put("signalId", action.getSignalId());
        row.put("executionTimeUtc", utc(bar.getOpenTime()));
        row.put("side", "BUY");
        row.put("reason", action.getReason());
        row.put("midPrice", rounded(bar.getOpenPrice().doubleValue(), 8));
        row.put("fillPrice", rounded(fillPrice, 8));
        row.put("baseQuantity", rounded(bought, 12));
        row.put("grossNotionalEquityUnits", rounded(gross, 12));
        row.put("feeEquityUnits", rounded(fee, 12));
        row.put("cashBefore", rounded(cashBefore, 15));
        row.put("cashAfter", rounded(state.getCash(), 15));
        row.put("positionQuantityBefore", rounded(quantityBefore, 15));
        row.put("positionQuantityAfter", rounded(state.getQuantity(), 15));
        row.put("targetExposure", rounded(targetExposure, 8));
        row.put("stopPrice", state.getStopPrice() == null ? null : rounded(state.getStopPrice(), 8));
        orderLedger.add(row);
        Map<String, Object> eventPayload = new LinkedHashMap<>(row);
        eventPayload.put("rawEquityAtOpen", equityAtOpen);
        eventPayload.put("rawTargetExposure", targetExposure);
        eventPayload.put("rawAtr", action.getAtr());
        eventPayload.put("rawGrossNotional", gross);
        eventPayload.put("rawFee", fee);
        eventPayload.put("rawCashAfter", state.getCash());
        eventPayload.put("rawQuantityAfter", state.getQuantity());
        events.add(new RuntimeEvent(scenario.name(), "VIRTUAL_ENTRY_FILL", action.getSignalId(),
                bar.getOpenTime(), eventPayload));
    }

    private void executeSell(ScenarioState state,
                             BtcDonchianShadowPolicy.Scenario scenario,
                             PendingAction action,
                             MdKline bar,
                             double targetExposure,
                             List<Map<String, Object>> orderLedger,
                             List<Map<String, Object>> tradeLedger,
                             List<RuntimeEvent> events) {
        double open = bar.getOpenPrice().doubleValue();
        double equityAtOpen = state.getCash() + (state.getQuantity() * open);
        double targetNotional = Math.max(0.0, equityAtOpen * targetExposure);
        double delta = targetNotional - (state.getQuantity() * open);
        double sellQuantity = Math.min(state.getQuantity(), (-delta) / open);
        if (targetExposure == 0.0) sellQuantity = state.getQuantity();
        if (sellQuantity <= 0.0) return;

        double fillPrice = open * (1.0 - scenario.adverseSlippageRatePerSide());
        double gross = sellQuantity * fillPrice;
        double fee = gross * scenario.feeRatePerSide();
        double cashBefore = state.getCash();
        double quantityBefore = state.getQuantity();
        state.setCash(state.getCash() + (gross - fee));
        state.setQuantity(state.getQuantity() - sellQuantity);
        state.setFees(state.getFees() + fee);
        state.setTurnover(state.getTurnover() + gross);
        state.setOrderCount(state.getOrderCount() + 1);
        boolean fullyClosed = state.getQuantity() <= FLAT_EPSILON;

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", state.getOrderCount());
        row.put("signalId", action.getSignalId());
        row.put("executionTimeUtc", utc(bar.getOpenTime()));
        row.put("side", "SELL");
        row.put("reason", action.getReason());
        row.put("midPrice", rounded(open, 8));
        row.put("fillPrice", rounded(fillPrice, 8));
        row.put("baseQuantity", rounded(sellQuantity, 12));
        row.put("grossNotionalEquityUnits", rounded(gross, 12));
        row.put("feeEquityUnits", rounded(fee, 12));
        row.put("cashBefore", rounded(cashBefore, 15));
        row.put("cashAfter", rounded(state.getCash(), 15));
        row.put("positionQuantityBefore", rounded(quantityBefore, 15));
        row.put("positionQuantityAfter", rounded(state.getQuantity(), 15));
        row.put("targetExposure", rounded(targetExposure, 8));
        row.put("stopPrice", null);
        orderLedger.add(row);
        events.add(new RuntimeEvent(scenario.name(), "VIRTUAL_EXIT_FILL", action.getSignalId(),
                bar.getOpenTime(), row));
        if (fullyClosed) {
            closeTrade(state, action.getSignalId(), action.getReason(), bar.getOpenTime(),
                    tradeLedger, events, scenario.name());
        }
    }

    private void executeStop(ScenarioState state,
                             BtcDonchianShadowPolicy.Scenario scenario,
                             MdKline bar,
                             List<Map<String, Object>> orderLedger,
                             List<Map<String, Object>> tradeLedger,
                             List<RuntimeEvent> events) {
        double rawStopFill = Math.min(state.getStopPrice(), bar.getOpenPrice().doubleValue());
        double fillPrice = rawStopFill * (1.0 - scenario.adverseSlippageRatePerSide());
        double sellQuantity = state.getQuantity();
        double gross = sellQuantity * fillPrice;
        double fee = gross * scenario.feeRatePerSide();
        double cashBefore = state.getCash();
        double quantityBefore = state.getQuantity();
        state.setCash(state.getCash() + (gross - fee));
        state.setTurnover(state.getTurnover() + gross);
        state.setFees(state.getFees() + fee);
        state.setQuantity(0.0);
        state.setStopPrice(null);
        state.setOrderCount(state.getOrderCount() + 1);
        state.setExitCount(state.getExitCount() + 1);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", state.getOrderCount());
        row.put("signalId", state.getActiveEntrySignalId());
        row.put("executionTimeUtc", utc(bar.getOpenTime()));
        row.put("side", "SELL");
        row.put("reason", "ATR_STOP");
        row.put("midPrice", rounded(rawStopFill, 8));
        row.put("fillPrice", rounded(fillPrice, 8));
        row.put("baseQuantity", rounded(sellQuantity, 12));
        row.put("grossNotionalEquityUnits", rounded(gross, 12));
        row.put("feeEquityUnits", rounded(fee, 12));
        row.put("cashBefore", rounded(cashBefore, 15));
        row.put("cashAfter", rounded(state.getCash(), 15));
        row.put("positionQuantityBefore", rounded(quantityBefore, 15));
        row.put("positionQuantityAfter", 0.0);
        row.put("targetExposure", 0.0);
        row.put("stopPrice", rounded(rawStopFill, 8));
        orderLedger.add(row);
        events.add(new RuntimeEvent(scenario.name(), "ATR_STOP_EXIT", state.getActiveEntrySignalId(),
                bar.getOpenTime(), row));
        closeTrade(state, state.getActiveEntrySignalId(), "ATR_STOP", bar.getOpenTime(),
                tradeLedger, events, scenario.name());
    }

    private void closeTrade(ScenarioState state,
                            String exitSignalId,
                            String reason,
                            LocalDateTime exitTime,
                            List<Map<String, Object>> tradeLedger,
                            List<RuntimeEvent> events,
                            String scenarioName) {
        if (state.getActiveTradeStartEquity() == null || state.getActiveEntrySignalId() == null) {
            throw new DataQualityException("ACTIVE_TRADE_STATE_MISSING:" + scenarioName);
        }
        state.setTradeCount(state.getTradeCount() + 1);
        double tradePnl = state.getCash() - state.getActiveTradeStartEquity();
        double tradeReturnPct = state.getActiveTradeStartEquity() > 0.0
                ? (tradePnl / state.getActiveTradeStartEquity()) * 100.0 : -100.0;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", state.getTradeCount());
        row.put("entrySignalId", state.getActiveEntrySignalId());
        row.put("entryTimeUtc", utc(state.getActiveTradeEntryTime()));
        row.put("exitSignalId", exitSignalId);
        row.put("exitTimeUtc", utc(exitTime));
        row.put("exitReason", reason);
        row.put("startEquity", rounded(state.getActiveTradeStartEquity(), 15));
        row.put("endEquity", rounded(state.getCash(), 15));
        row.put("profitLossEquityUnits", rounded(tradePnl, 15));
        row.put("returnPct", rounded(tradeReturnPct, 10));
        tradeLedger.add(row);
        events.add(new RuntimeEvent(scenarioName, "VIRTUAL_TRADE_CLOSED", exitSignalId, exitTime, row));
        state.setQuantity(0.0);
        state.setStopPrice(null);
        state.setActiveEntrySignalId(null);
        state.setActiveTradeStartEquity(null);
        state.setActiveTradeEntryTime(null);
        if (!"ATR_STOP".equals(reason)) state.setExitCount(state.getExitCount() + 1);
    }

    private DailyBar updateDailyState(State state, MdKline bar) {
        LocalDate date = bar.getOpenTime().toLocalDate();
        int hour = bar.getOpenTime().getHour();
        DailyAccumulator day = state.getCurrentDay();
        if (day == null) {
            if (hour != 0) throw new DataQualityException("UTC_DAY_START_MISSING:" + bar.getOpenTime());
            day = new DailyAccumulator(date, 0, bar.getOpenPrice().doubleValue(),
                    bar.getHighPrice().doubleValue(), bar.getLowPrice().doubleValue(),
                    bar.getClosePrice().doubleValue());
            state.setCurrentDay(day);
        } else {
            if (!date.equals(day.getDate()) || hour != day.getHourCount()) {
                throw new DataQualityException("UTC_DAY_NOT_CONTIGUOUS:" + bar.getOpenTime());
            }
            day.setHigh(Math.max(day.getHigh(), bar.getHighPrice().doubleValue()));
            day.setLow(Math.min(day.getLow(), bar.getLowPrice().doubleValue()));
            day.setClose(bar.getClosePrice().doubleValue());
        }
        day.setHourCount(day.getHourCount() + 1);
        if (hour != 23) return null;
        if (day.getHourCount() != 24) throw new DataQualityException("UTC_DAY_BAR_COUNT_NOT_24:" + date);

        double trueRange = day.getHigh() - day.getLow();
        List<DailyBar> completed = state.getCompletedDays();
        if (!completed.isEmpty()) {
            double previousClose = completed.get(completed.size() - 1).getClose();
            trueRange = Math.max(trueRange,
                    Math.max(Math.abs(day.getHigh() - previousClose), Math.abs(day.getLow() - previousClose)));
        }
        DailyBar result = new DailyBar(day.getDate(), day.getOpen(), day.getHigh(), day.getLow(),
                day.getClose(), trueRange, state.getCompletedDayCount());
        state.setCurrentDay(null);
        return result;
    }

    private void evaluateDailyClose(State state,
                                    ScenarioState scenarioState,
                                    BtcDonchianShadowPolicy.Scenario scenario,
                                    DailyBar day,
                                    MdKline closeBar,
                                    List<Map<String, Object>> signalLedger,
                                    List<RuntimeEvent> events) {
        List<DailyBar> prior = state.getCompletedDays();
        LocalDateTime executionTime = closeBar.getOpenTime().plusHours(1L + scenario.signalDelayBars());
        if (scenarioState.getQuantity() <= FLAT_EPSILON
                && state.getCompletedDayCount() >= ENTRY_LOOKBACK_DAYS
                && state.getCompletedDayCount() >= ATR_LOOKBACK_DAYS - 1L) {
            double priorHigh = prior.subList(prior.size() - ENTRY_LOOKBACK_DAYS, prior.size()).stream()
                    .mapToDouble(DailyBar::getHigh).max().orElseThrow();
            if (day.getClose() > priorHigh) {
                double atrSum = day.getTrueRange();
                for (int i = prior.size() - (ATR_LOOKBACK_DAYS - 1); i < prior.size(); i++) {
                    atrSum += prior.get(i).getTrueRange();
                }
                double atr = atrSum / ATR_LOOKBACK_DAYS;
                String signalId = nextSignalId(scenarioState, scenario.name());
                Map<String, Object> indicators = new LinkedHashMap<>();
                indicators.put("currentDailyClose", rounded(day.getClose(), 8));
                indicators.put("prior20DayHigh", rounded(priorHigh, 8));
                indicators.put("atr14", rounded(atr, 8));
                Map<String, Object> row = signalRow(scenarioState.getSignalCount(), signalId, closeBar,
                        executionTime, "DONCHIAN_20D_BREAKOUT_ENTRY", null, indicators);
                signalLedger.add(row);
                events.add(new RuntimeEvent(scenario.name(), "ENTRY_SIGNAL", signalId,
                        closeBar.getOpenTime().plusHours(1), row));
                scenarioState.setPendingAction(new PendingAction(executionTime, "DONCHIAN_ENTRY",
                        0.0, atr, signalId, "DONCHIAN_20D_BREAKOUT_ENTRY"));
            }
        } else if (scenarioState.getQuantity() > 0.0 && state.getCompletedDayCount() >= EXIT_LOOKBACK_DAYS) {
            double priorLow = prior.subList(prior.size() - EXIT_LOOKBACK_DAYS, prior.size()).stream()
                    .mapToDouble(DailyBar::getLow).min().orElseThrow();
            if (day.getClose() < priorLow) {
                String signalId = nextSignalId(scenarioState, scenario.name());
                Map<String, Object> indicators = new LinkedHashMap<>();
                indicators.put("currentDailyClose", rounded(day.getClose(), 8));
                indicators.put("prior10DayLow", rounded(priorLow, 8));
                Map<String, Object> row = signalRow(scenarioState.getSignalCount(), signalId, closeBar,
                        executionTime, "DONCHIAN_10D_BREAKDOWN_EXIT", 0.0, indicators);
                signalLedger.add(row);
                events.add(new RuntimeEvent(scenario.name(), "EXIT_SIGNAL", signalId,
                        closeBar.getOpenTime().plusHours(1), row));
                scenarioState.setPendingAction(new PendingAction(executionTime, "DONCHIAN_EXIT",
                        0.0, 0.0, signalId, "DONCHIAN_10D_BREAKDOWN_EXIT"));
            }
        }
    }

    private Map<String, Object> signalRow(int sequence,
                                          String signalId,
                                          MdKline closeBar,
                                          LocalDateTime executionTime,
                                          String signalKind,
                                          Double targetExposure,
                                          Map<String, Object> indicators) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("sequence", sequence);
        row.put("signalId", signalId);
        row.put("sourceBarOpenTimeUtc", utc(closeBar.getOpenTime()));
        row.put("signalAvailableAtUtc", utc(closeBar.getOpenTime().plusHours(1)));
        row.put("scheduledExecutionTimeUtc", utc(executionTime));
        row.put("signalKind", signalKind);
        row.put("targetExposure", targetExposure);
        row.put("indicatorValues", indicators);
        return row;
    }

    private String nextSignalId(ScenarioState state, String scenarioName) {
        state.setSignalCount(state.getSignalCount() + 1);
        return POLICY_MODE + "|" + scenarioName + "|" + String.format(Locale.ROOT, "%04d", state.getSignalCount());
    }

    private void appendCompletedDay(State state, DailyBar day) {
        state.getCompletedDays().add(day);
        state.setCompletedDayCount(state.getCompletedDayCount() + 1);
        while (state.getCompletedDays().size() > RETAINED_DAILY_BARS) {
            state.getCompletedDays().remove(0);
        }
    }

    private void validateState(State state) {
        if (!STATE_SCHEMA_VERSION.equals(state.getSchemaVersion())) {
            throw new DataQualityException("STATE_SCHEMA_MISMATCH");
        }
        if (state.getCompletedDays() == null || state.getScenarios() == null
                || !state.getScenarios().keySet().containsAll(List.of(NORMAL.name(), STRESS.name()))) {
            throw new DataQualityException("STATE_CONTENT_INCOMPLETE");
        }
    }

    private void validateBar(State state, MdKline bar) {
        if (bar == null || bar.getOpenTime() == null) throw new DataQualityException("BAR_MISSING");
        if (!SYMBOL.equalsIgnoreCase(normalizeSymbol(bar.getSymbol()))
                || !INTERVAL.equalsIgnoreCase(bar.getIntervalCode())
                || !SOURCE.equalsIgnoreCase(bar.getSource())) {
            throw new DataQualityException("BAR_SCOPE_MISMATCH:" + bar.getOpenTime());
        }
        if (bar.getOpenTime().getMinute() != 0 || bar.getOpenTime().getSecond() != 0
                || bar.getOpenTime().getNano() != 0) {
            throw new DataQualityException("BAR_OFF_UTC_HOUR_GRID:" + bar.getOpenTime());
        }
        if (state.getLastProcessedBarOpenTime() == null) {
            if (bar.getOpenTime().getHour() != 0) {
                throw new DataQualityException("FIRST_BAR_NOT_UTC_DAY_START:" + bar.getOpenTime());
            }
        } else if (!state.getLastProcessedBarOpenTime().plusHours(1).equals(bar.getOpenTime())) {
            throw new DataQualityException("BAR_LATTICE_GAP:" + state.getLastProcessedBarOpenTime()
                    + "->" + bar.getOpenTime());
        }
        if (bar.getCloseTime() != null && !bar.getOpenTime().plusHours(1).equals(bar.getCloseTime())) {
            throw new DataQualityException("BAR_CLOSE_TIME_MISMATCH:" + bar.getOpenTime());
        }
        if (bar.getOpenPrice() == null || bar.getHighPrice() == null || bar.getLowPrice() == null
                || bar.getClosePrice() == null || bar.getVolume() == null) {
            throw new DataQualityException("BAR_VALUE_MISSING:" + bar.getOpenTime());
        }
        BigDecimal open = bar.getOpenPrice();
        BigDecimal high = bar.getHighPrice();
        BigDecimal low = bar.getLowPrice();
        BigDecimal close = bar.getClosePrice();
        if (open.signum() <= 0 || high.signum() <= 0 || low.signum() <= 0 || close.signum() <= 0
                || bar.getVolume().signum() < 0
                || high.compareTo(open.max(close)) < 0 || low.compareTo(open.min(close)) > 0
                || high.compareTo(low) < 0) {
            throw new DataQualityException("BAR_OHLC_INVARIANT_FAILED:" + bar.getOpenTime());
        }
    }

    private String sha256Json(Object value) {
        try {
            byte[] bytes = objectMapper.writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash Donchian ledger", e);
        }
    }

    private String sha256PowerShellLedger(Object value) {
        try {
            String json = powerShellLedgerJson(value);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash PowerShell-compatible Donchian ledger", e);
        }
    }

    String powerShellLedgerJson(Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            // PowerShell/Newtonsoft keeps exponent -4 in fixed notation and pads smaller single-digit exponents.
            json = POWER_SHELL_FIXED_POINT_EXPONENT.matcher(json)
                    .replaceAll(match -> new BigDecimal(match.group()).toPlainString());
            return SINGLE_DIGIT_JSON_EXPONENT.matcher(json)
                    .replaceAll(match -> "E" + match.group(1) + "0" + match.group(2));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to serialize PowerShell-compatible Donchian ledger", e);
        }
    }

    private double rounded(double value, int scale) {
        double factor = Math.pow(10.0, scale);
        return Math.rint(value * factor) / factor;
    }

    private String utc(LocalDateTime value) {
        return value.format(UTC);
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT)
                .replace("-", "").replace("/", "").replace("_", "");
    }

    private String canonicalDecimal(BigDecimal value) {
        if (value == null) throw new DataQualityException("BAR_VALUE_MISSING_FOR_HASH");
        return value.stripTrailingZeros().toPlainString();
    }

    public record StepResult(
            State state,
            List<RuntimeEvent> events,
            Map<String, List<Map<String, Object>>> signalLedgers,
            Map<String, List<Map<String, Object>>> orderLedgers,
            Map<String, List<Map<String, Object>>> tradeLedgers
    ) {
    }

    public record RuntimeEvent(
            String scenario,
            String eventType,
            String eventId,
            LocalDateTime eventTime,
            Map<String, Object> payload
    ) {
    }

    public record ReplayResult(
            State state,
            Map<String, ScenarioReplay> scenarios,
            LocalDateTime firstOpenTime,
            LocalDateTime lastOpenTime,
            int rowCount
    ) {
    }

    public record ScenarioReplay(
            String scenario,
            List<Map<String, Object>> signalLedger,
            List<Map<String, Object>> orderLedger,
            List<Map<String, Object>> tradeLedger,
            String signalLedgerSha256,
            String orderLedgerSha256,
            String tradeLedgerSha256,
            ScenarioState finalState
    ) {
    }

    @Data
    @NoArgsConstructor
    public static class State {
        private String schemaVersion;
        private LocalDateTime lastProcessedBarOpenTime;
        private long processedBars;
        private long completedDayCount;
        private DailyAccumulator currentDay;
        private List<DailyBar> completedDays;
        private Map<String, ScenarioState> scenarios;
    }

    @Data
    @NoArgsConstructor
    public static class ScenarioState {
        private String scenario;
        private double cash;
        private double quantity;
        private Double stopPrice;
        private PendingAction pendingAction;
        private int signalCount;
        private int orderCount;
        private int entryCount;
        private int exitCount;
        private int tradeCount;
        private double fees;
        private double turnover;
        private double currentEquity;
        private String activeEntrySignalId;
        private Double activeTradeStartEquity;
        private LocalDateTime activeTradeEntryTime;

        static ScenarioState initial(String scenario) {
            ScenarioState state = new ScenarioState();
            state.setScenario(scenario);
            state.setCash(1.0);
            state.setCurrentEquity(1.0);
            return state;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PendingAction {
        private LocalDateTime executionTime;
        private String kind;
        private double targetExposure;
        private double atr;
        private String signalId;
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyAccumulator {
        private LocalDate date;
        private int hourCount;
        private double open;
        private double high;
        private double low;
        private double close;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DailyBar {
        private LocalDate date;
        private double open;
        private double high;
        private double low;
        private double close;
        private double trueRange;
        private long sequence;
    }

    public static class DataQualityException extends IllegalStateException {
        public DataQualityException(String message) {
            super(message);
        }
    }
}
