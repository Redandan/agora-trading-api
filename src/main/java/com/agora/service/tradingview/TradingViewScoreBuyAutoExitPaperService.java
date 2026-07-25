package com.agora.service.tradingview;

import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties.ExecutionMode;
import com.agora.model.BtDecisionAudit;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.strategy.StrategyLifecycleMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import com.agora.service.backtest.TradingViewScoreBuyModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Durable PAPER lane for score-buy entries with per-lot automatic profit exits.
 *
 * <p>State is restored from verified runtime-evidence snapshots. A transaction
 * scoped strategy-row lock plus a persisted decision-audit key serializes the
 * lane across restarts and multiple service instances. This service has no
 * dependency on {@code TradingService}, OCO, Telegram, or live positions.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingViewScoreBuyAutoExitPaperService {

    static final String POLICY_MODE = "TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_PAPER_V2";
    static final String EVENT_TYPE = "LOCAL_TV_V2_PAPER";
    static final String BLOCK_EVENT_TYPE = "LOCAL_TV_V2_PAPER_BLOCK";
    static final String EVIDENCE_SCHEMA_VERSION = "TV_BTC_DAILY_SCORE_BUY_AUTO_EXIT_PAPER_STATE_V2";
    private static final String EXECUTION_MODE = "PAPER";
    private static final int STATE_RESTORE_SCAN_LIMIT = 50;

    private final Set<String> evaluatingBars = ConcurrentHashMap.newKeySet();

    private final TradingViewLocalSignalProperties properties;
    private final BtStrategyRepository strategyRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final ObjectMapper objectMapper;
    private final TradingViewScoreBuyAutoExitPaperEngine engine;
    private final StrategyRuntimeCatalog strategyRuntimeCatalog;

    public boolean isEnabled() {
        return properties.executionMode() == ExecutionMode.BTC_BASE_PAPER
                && strategyRuntimeCatalog.isMode(
                TradingViewScoreBuyAutoExitStrategyContract.KEY, StrategyLifecycleMode.PAPER);
    }

    @Transactional
    public void evaluate(BtStrategy strategy,
                         MdKline eventKline,
                         String source,
                         List<TradingViewScoreBuyAutoExitPaperEngine.PaperBar> evaluatedBars) {
        if (!isEnabled() || strategy == null || eventKline == null || eventKline.getOpenTime() == null) {
            return;
        }
        if (!contractMatches(strategy, eventKline, source)) {
            log.warn("[LocalTradingViewPaper] scope mismatch strategyId={} symbol={} interval={} source={}",
                    strategy.getId(), eventKline.getSymbol(), eventKline.getIntervalCode(), source);
            return;
        }

        String key = strategy.getId() + "|" + eventKline.getOpenTime();
        if (!evaluatingBars.add(key)) {
            log.debug("[LocalTradingViewPaper] concurrent duplicate ignored: {}", key);
            return;
        }
        try {
            evaluateLocked(strategy, eventKline, evaluatedBars);
        } finally {
            evaluatingBars.remove(key);
        }
    }

    @Transactional
    public void recordBlocked(BtStrategy strategy,
                              MdKline eventKline,
                              String source,
                              String blocker) {
        if (!isEnabled() || strategy == null || eventKline == null || eventKline.getOpenTime() == null) {
            return;
        }
        if (!contractMatches(strategy, eventKline, source)) {
            return;
        }
        strategyRepository.findByIdForBootstrapReservation(strategy.getId())
                .orElseThrow(() -> new IllegalStateException("paper strategy row is unavailable"));
        if (decisionAuditRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                strategy.getId(),
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                eventKline.getOpenTime(),
                BLOCK_EVENT_TYPE)) {
            return;
        }
        persistBlocked(strategy, eventKline, safeCode(blocker), null, 0);
    }

    private void evaluateLocked(BtStrategy strategy,
                                MdKline eventKline,
                                List<TradingViewScoreBuyAutoExitPaperEngine.PaperBar> evaluatedBars) {
        strategyRepository.findByIdForBootstrapReservation(strategy.getId())
                .orElseThrow(() -> new IllegalStateException("paper strategy row is unavailable"));
        if (decisionAuditRepository.existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndEventType(
                strategy.getId(),
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                eventKline.getOpenTime(),
                EVENT_TYPE)) {
            log.debug("[LocalTradingViewPaper] persisted duplicate ignored openTime={}",
                    eventKline.getOpenTime());
            return;
        }

        List<TradingViewScoreBuyAutoExitPaperEngine.PaperBar> bars = evaluatedBars == null
                ? new ArrayList<>()
                : new ArrayList<>(evaluatedBars);
        bars.sort(Comparator.comparing(TradingViewScoreBuyAutoExitPaperEngine.PaperBar::openTime,
                Comparator.nullsLast(Comparator.naturalOrder())));
        if (bars.isEmpty()
                || !eventKline.getOpenTime().equals(bars.get(bars.size() - 1).openTime())) {
            persistBlocked(strategy, eventKline, "PAPER_REPLAY_EVALUATION_INCOMPLETE", null, bars.size());
            return;
        }

        RestoredState restored = restoreLatestState(strategy.getId());
        TradingViewScoreBuyAutoExitPaperEngine.State state = restored.state();
        boolean bootstrap = state == null;
        if (bootstrap) {
            if (!TradingViewScoreBuyModel.BTCUSDT_1D_REPLAY_START_UTC.equals(bars.get(0).openTime())) {
                persistBlocked(strategy, eventKline, "PAPER_BOOTSTRAP_ANCHOR_MISSING", null, bars.size());
                return;
            }
        } else {
            LocalDateTime lastProcessed = state.lastProcessedBarOpenTime();
            bars.removeIf(bar -> bar.openTime() == null || !bar.openTime().isAfter(lastProcessed));
            if (bars.isEmpty()) {
                return;
            }
            if (!lastProcessed.plusDays(1).equals(bars.get(0).openTime())) {
                persistBlocked(strategy, eventKline, "PAPER_CATCH_UP_HISTORY_INCOMPLETE",
                        state, bars.size());
                return;
            }
        }

        TradingViewScoreBuyAutoExitPaperEngine.StepResult current = null;
        try {
            TradingViewScoreBuyAutoExitPaperEngine.Policy policy =
                    TradingViewScoreBuyAutoExitPaperEngine.Policy.paperDefault(
                            properties.defaultNotionalUsdt(),
                            properties.maxNotionalUsdt(),
                            properties.btcBaseMaxExposureUsdt());
            for (TradingViewScoreBuyAutoExitPaperEngine.PaperBar bar : bars) {
                current = engine.step(state, bar, policy);
                state = current.state();
            }
        } catch (TradingViewScoreBuyAutoExitPaperEngine.DataQualityException
                 | IllegalArgumentException e) {
            persistBlocked(strategy, eventKline, safeCode(e.getMessage()), state, bars.size());
            return;
        }
        if (current == null
                || !eventKline.getOpenTime().equals(current.state().lastProcessedBarOpenTime())) {
            persistBlocked(strategy, eventKline, "PAPER_CURRENT_STATE_NOT_REACHED", state, bars.size());
            return;
        }
        persistObserved(
                strategy,
                eventKline,
                current,
                bootstrap,
                !bootstrap && bars.size() > 1,
                bars.size(),
                restored.invalidRowsScanned());
    }

    private void persistObserved(BtStrategy strategy,
                                 MdKline bar,
                                 TradingViewScoreBuyAutoExitPaperEngine.StepResult step,
                                 boolean bootstrap,
                                 boolean catchUp,
                                 int batchBars,
                                 int invalidRowsScanned) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String stateHash = stateSha256(step.state());
        String selectedAction = selectedAction(step.events());

        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        auditContext.put("entryContractKey", TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY);
        auditContext.put("strategyOwnerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("executionMode", EXECUTION_MODE);
        auditContext.put("executionTiming", TradingViewScoreBuyAutoExitStrategyContract.PAPER_EXECUTION_TIMING);
        auditContext.put("bootstrap", bootstrap);
        auditContext.put("catchUp", catchUp);
        auditContext.put("batchBars", batchBars);
        auditContext.put("invalidStateRowsScanned", invalidRowsScanned);
        auditContext.put("selectedAction", selectedAction);
        auditContext.put("eventTypes", step.events().stream()
                .map(TradingViewScoreBuyAutoExitPaperEngine.PaperEvent::type).toList());
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("buyFillCount", step.state().buyFillCount());
        auditContext.put("sellFillCount", step.state().sellFillCount());
        auditContext.put("winningExitLotCount", step.state().winningExitLotCount());
        auditContext.put("openLotCount", step.state().openLots().size());
        auditContext.put("totalBuyNotionalUsdt", step.state().totalBuyNotionalUsdt());
        auditContext.put("realizedPnlUsdt", step.state().realizedPnlUsdt());
        auditContext.put("inventoryQty", step.state().inventoryQty());
        auditContext.put("unrealizedPnlUsdt", step.state().unrealizedPnlUsdt());
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);

        BtDecisionAudit audit = saveAudit(
                strategy,
                bar,
                now,
                EVENT_TYPE,
                step.events().isEmpty() ? "INFO" : "PASS",
                null,
                selectedAction,
                auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        snapshot.put("entryContractKey", TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY);
        snapshot.put("strategyOwnerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("barOpenTime", text(bar.getOpenTime()));
        snapshot.put("barCloseTime", text(bar.getCloseTime()));
        snapshot.put("bootstrap", bootstrap);
        snapshot.put("catchUp", catchUp);
        snapshot.put("batchBars", batchBars);
        snapshot.put("invalidStateRowsScanned", invalidRowsScanned);
        snapshot.put("executionTiming", TradingViewScoreBuyAutoExitStrategyContract.PAPER_EXECUTION_TIMING);
        snapshot.put("feeRate", TradingViewScoreBuyAutoExitStrategyContract.PAPER_FEE_RATE);
        snapshot.put("slippageRate", TradingViewScoreBuyAutoExitStrategyContract.PAPER_ADVERSE_SLIPPAGE_RATE);
        snapshot.put("strategyExitPolicy", TradingViewScoreBuyAutoExitStrategyContract.EXIT_POLICY);
        snapshot.put("netProfitTrigger", TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER);
        snapshot.put("minRealizedNetProfit", TradingViewScoreBuyAutoExitStrategyContract.MIN_REALIZED_NET_PROFIT);
        snapshot.put("stateAfterSha256", stateHash);
        snapshot.put("stateAfter", step.state());
        snapshot.put("events", step.events());
        snapshot.put("orderSent", false);
        snapshot.put("ocoModified", false);
        snapshot.put("telegramSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(strategy, audit, now);
        evidence.setFeaturesSnapshotJson(writeJson(snapshot));
        evidence.setFreshnessState(catchUp
                ? "CAUSAL_DAILY_CATCH_UP_COMPLETE"
                : bootstrap ? "CAUSAL_DAILY_BOOTSTRAP_COMPLETE" : "CURRENT_CLOSED_DAILY_BAR");
        evidence.setSelectedAction(selectedAction);
        evidence.setReason(eventReason(step.events()));
        evidence.setFinalOutcome("PAPER_OBSERVED");
        boolean sellIntent = step.events().stream()
                .anyMatch(event -> "PAPER_EXIT_QUEUED_NEXT_DAILY_OPEN".equals(event.type()));
        boolean buyIntent = step.events().stream()
                .anyMatch(event -> "PAPER_BUY_INTENT_QUEUED_NEXT_DAILY_OPEN".equals(event.type()));
        evidence.setDecision(sellIntent ? "SELL_SIGNAL" : buyIntent ? "BUY_SIGNAL" : "HOLD");
        evidence.setIntentCreated(sellIntent || buyIntent);
        evidence.setPolicyInputsJson(policyInputsJson());
        evidence.setExecutionPreviewJson(writeJson(Map.of(
                "events", step.events(),
                "paperExecutionOnly", true,
                "nextDailyOpenFill", true,
                "orderSent", false,
                "stateAfterSha256", stateHash)));
        evidenceRepository.save(evidence);
    }

    private void persistBlocked(BtStrategy strategy,
                                MdKline bar,
                                String blocker,
                                TradingViewScoreBuyAutoExitPaperEngine.State state,
                                int batchBars) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String safeBlocker = safeCode(blocker);
        String stateHash = state == null ? "" : stateSha256(state);
        Map<String, Object> auditContext = new LinkedHashMap<>();
        auditContext.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        auditContext.put("policyMode", POLICY_MODE);
        auditContext.put("executionMode", EXECUTION_MODE);
        auditContext.put("terminalBlocker", safeBlocker);
        auditContext.put("batchBars", batchBars);
        auditContext.put("stateAfterSha256", stateHash);
        auditContext.put("orderSent", false);
        auditContext.put("liveImplementationPresent", false);
        BtDecisionAudit audit = saveAudit(
                strategy,
                bar,
                now,
                BLOCK_EVENT_TYPE,
                "BLOCKED",
                safeBlocker,
                "LOCAL_TRADINGVIEW_V2_PAPER_BLOCKED",
                auditContext);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("evidenceSchemaVersion", EVIDENCE_SCHEMA_VERSION);
        snapshot.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        snapshot.put("policyMode", POLICY_MODE);
        snapshot.put("barOpenTime", text(bar.getOpenTime()));
        snapshot.put("terminalBlocker", safeBlocker);
        snapshot.put("batchBars", batchBars);
        snapshot.put("stateAfterSha256", stateHash);
        if (state != null) {
            snapshot.put("stateAfter", state);
        }
        snapshot.put("events", List.of());
        snapshot.put("orderSent", false);
        snapshot.put("liveImplementationPresent", false);

        RuntimeDecisionEvidence evidence = baseEvidence(strategy, audit, now);
        evidence.setFeaturesSnapshotJson(writeJson(snapshot));
        evidence.setFreshnessState("INCOMPLETE_FAIL_CLOSED");
        evidence.setSelectedAction("LOCAL_TRADINGVIEW_V2_PAPER_BLOCKED");
        evidence.setReason(safeBlocker);
        evidence.setFinalOutcome("BLOCKED_DATA_QUALITY");
        evidence.setDecision("HOLD");
        evidence.setBlockerReason(safeBlocker);
        evidence.setTerminalBlocker(safeBlocker);
        evidence.setIntentCreated(false);
        evidence.setPolicyInputsJson(policyInputsJson());
        evidence.setExecutionPreviewJson(writeJson(Map.of(
                "paperExecutionOnly", true,
                "orderSent", false,
                "blocked", true,
                "blocker", safeBlocker)));
        evidenceRepository.save(evidence);
    }

    private RestoredState restoreLatestState(Long strategyId) {
        List<RuntimeDecisionEvidence> rows = evidenceRepository
                .findByPolicyModeAndSymbolAndIntervalCodeOrderByIdDesc(
                        POLICY_MODE,
                        TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL,
                        TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL,
                        PageRequest.of(0, STATE_RESTORE_SCAN_LIMIT));
        if (rows == null || rows.isEmpty()) {
            return new RestoredState(null, 0);
        }
        int invalid = 0;
        for (RuntimeDecisionEvidence row : rows) {
            try {
                if (!strategyId.equals(row.getStrategyId())
                        || Boolean.TRUE.equals(row.getOrderSent())
                        || !EXECUTION_MODE.equals(row.getExecutionMode())) {
                    invalid++;
                    continue;
                }
                JsonNode root = objectMapper.readTree(row.getFeaturesSnapshotJson());
                JsonNode stateNode = root.path("stateAfter");
                String expectedHash = root.path("stateAfterSha256").asText("");
                if (stateNode.isMissingNode() || stateNode.isNull() || expectedHash.isBlank()) {
                    invalid++;
                    continue;
                }
                TradingViewScoreBuyAutoExitPaperEngine.State state = objectMapper.treeToValue(
                        stateNode, TradingViewScoreBuyAutoExitPaperEngine.State.class);
                if (!expectedHash.equals(stateSha256(state))) {
                    invalid++;
                    continue;
                }
                return new RestoredState(state, invalid);
            } catch (Exception ignored) {
                invalid++;
            }
        }
        return new RestoredState(null, invalid);
    }

    private BtDecisionAudit saveAudit(BtStrategy strategy,
                                      MdKline bar,
                                      LocalDateTime now,
                                      String eventType,
                                      String outcome,
                                      String blocker,
                                      String reason,
                                      Map<String, Object> context) {
        BtDecisionAudit audit = new BtDecisionAudit();
        audit.setEventTime(now);
        audit.setStrategyId(strategy.getId());
        audit.setSymbol(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL);
        audit.setIntervalCode(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL);
        audit.setBarOpenTime(bar.getOpenTime());
        audit.setEventType(eventType);
        audit.setOutcome(outcome);
        audit.setBlocker(blocker == null ? null : truncate(blocker, 64));
        audit.setReason(truncate(reason, 500));
        audit.setContextJson(writeJson(context));
        return decisionAuditRepository.save(audit);
    }

    private RuntimeDecisionEvidence baseEvidence(BtStrategy strategy,
                                                 BtDecisionAudit audit,
                                                 LocalDateTime now) {
        RuntimeDecisionEvidence evidence = new RuntimeDecisionEvidence();
        evidence.setDecisionId(audit.getId());
        evidence.setEvidenceTime(now);
        evidence.setSymbol(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL);
        evidence.setSide("LONG");
        evidence.setStrategyId(strategy.getId());
        evidence.setIntervalCode(TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL);
        evidence.setSignalSource("LOCAL_TRADINGVIEW");
        evidence.setPolicyMode(POLICY_MODE);
        evidence.setPolicyReason("Frozen TradingView parity BUY intents; per-lot +5% net-profit trigger; next-daily-open PAPER exits with +1% execution floor");
        evidence.setExecutionMode(EXECUTION_MODE);
        evidence.setOrderSent(false);
        evidence.setSuppressionReason("PAPER_MODE_NO_ORDER_CAPABILITY");
        evidence.setOcoPlanCreated(false);
        return evidence;
    }

    private boolean contractMatches(BtStrategy strategy, MdKline eventKline, String source) {
        return strategy.getId() != null
                && strategy.getId() == TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID
                && TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL.equalsIgnoreCase(
                normalizeSymbol(eventKline.getSymbol()))
                && TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL.equalsIgnoreCase(
                eventKline.getIntervalCode())
                && TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SOURCE.equalsIgnoreCase(source)
                && closedAndFresh(eventKline);
    }

    private boolean closedAndFresh(MdKline eventKline) {
        LocalDateTime closeTime = eventKline.getCloseTime();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (closeTime == null || closeTime.isAfter(now.plusMinutes(1))) {
            return false;
        }
        if (properties.maxSignalAgeHours() <= 0) {
            return true;
        }
        long ageHours = Math.max(0L, Duration.between(closeTime, now).toHours());
        return ageHours <= properties.maxSignalAgeHours();
    }

    private String policyInputsJson() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("strategyContractKey", TradingViewScoreBuyAutoExitStrategyContract.KEY);
        policy.put("entryContractKey", TradingViewScoreBuyAutoExitStrategyContract.ENTRY_CONTRACT_KEY);
        policy.put("strategyOwnerAlias", TradingViewScoreBuyAutoExitStrategyContract.OWNER_ALIAS);
        policy.put("databaseStrategyId", TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID);
        policy.put("signalSource", TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SOURCE);
        policy.put("symbol", TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_SYMBOL);
        policy.put("intervalCode", TradingViewScoreBuyAutoExitStrategyContract.SIGNAL_INTERVAL);
        policy.put("executionTiming", TradingViewScoreBuyAutoExitStrategyContract.PAPER_EXECUTION_TIMING);
        policy.put("baseNotionalUsdt", properties.defaultNotionalUsdt());
        policy.put("maxOrderNotionalUsdt", properties.maxNotionalUsdt());
        policy.put("maxExposureUsdt", properties.btcBaseMaxExposureUsdt());
        policy.put("feeRate", TradingViewScoreBuyAutoExitStrategyContract.PAPER_FEE_RATE);
        policy.put("slippageRate", TradingViewScoreBuyAutoExitStrategyContract.PAPER_ADVERSE_SLIPPAGE_RATE);
        policy.put("exitPolicy", TradingViewScoreBuyAutoExitStrategyContract.EXIT_POLICY);
        policy.put("netProfitTrigger", TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER);
        policy.put("minRealizedNetProfit", TradingViewScoreBuyAutoExitStrategyContract.MIN_REALIZED_NET_PROFIT);
        policy.put("orderAllowed", false);
        return writeJson(policy);
    }

    private String selectedAction(List<TradingViewScoreBuyAutoExitPaperEngine.PaperEvent> events) {
        if (events.stream().anyMatch(event -> "PAPER_SELL_FILL_NEXT_DAILY_OPEN".equals(event.type()))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_SELL_FILL";
        }
        if (events.stream().anyMatch(event -> "PAPER_BUY_FILL_NEXT_DAILY_OPEN".equals(event.type()))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_BUY_FILL";
        }
        if (events.stream().anyMatch(event -> "PAPER_EXIT_QUEUED_NEXT_DAILY_OPEN".equals(event.type()))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_EXIT_INTENT";
        }
        if (events.stream().anyMatch(event -> "PAPER_BUY_INTENT_QUEUED_NEXT_DAILY_OPEN".equals(event.type()))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_BUY_INTENT";
        }
        if (events.stream().anyMatch(event -> event.type().contains("BLOCKED"))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_INTENT_BLOCKED";
        }
        if (events.stream().anyMatch(event -> event.type().contains("DEFERRED"))) {
            return "LOCAL_TRADINGVIEW_V2_PAPER_EXIT_DEFERRED";
        }
        return "LOCAL_TRADINGVIEW_V2_PAPER_STATE_ADVANCE";
    }

    private String eventReason(List<TradingViewScoreBuyAutoExitPaperEngine.PaperEvent> events) {
        if (events.isEmpty()) {
            return "NO_SIGNAL_STATE_ADVANCED";
        }
        return events.stream()
                .map(TradingViewScoreBuyAutoExitPaperEngine.PaperEvent::type)
                .distinct()
                .reduce((left, right) -> left + "," + right)
                .orElse("NO_SIGNAL_STATE_ADVANCED");
    }

    private String stateSha256(TradingViewScoreBuyAutoExitPaperEngine.State state) {
        try {
            byte[] json = objectMapper.writeValueAsString(state).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (Exception e) {
            throw new IllegalStateException("unable to hash paper state", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("unable to serialize paper evidence", e);
        }
    }

    private String normalizeSymbol(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim().toUpperCase();
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < value.length()) {
            value = value.substring(colon + 1);
        }
        return value.replace("-", "").replace("/", "").replace("_", "");
    }

    private String safeCode(String value) {
        if (value == null || value.isBlank()) {
            return "PAPER_UNKNOWN_BLOCKER";
        }
        return truncate(value.trim().toUpperCase().replaceAll("[^A-Z0-9_]+", "_"), 128);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private record RestoredState(
            TradingViewScoreBuyAutoExitPaperEngine.State state,
            int invalidRowsScanned
    ) {
    }
}
