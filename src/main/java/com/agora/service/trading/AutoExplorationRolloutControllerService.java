package com.agora.service.trading;

import com.agora.mcp.DiagnosticMcpTools;
import com.agora.mcp.MarketDataMcpTools;
import com.agora.model.AutoExplorationRolloutTransition;
import com.agora.repository.trading.AutoExplorationRolloutTransitionRepository;
import com.agora.service.TelegramService;
import com.agora.service.TgNotificationDeduper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoExplorationRolloutControllerService {

    private static final String SYMBOL = "BTCUSDT";
    private static final long STRATEGY_ID = 574L;
    private static final String SIDE = "LONG";

    private final AutoExplorationRolloutStateService stateService;
    private final AutoExplorationRolloutTransitionRepository transitionRepository;
    private final ExplorationRolloutService explorationRolloutService;
    private final AutonomousExplorationLoopService loopService;
    private final MarketDataMcpTools marketDataMcpTools;
    private final DiagnosticMcpTools diagnosticMcpTools;
    private final TelegramService telegramService;
    private final TgNotificationDeduper tgNotificationDeduper;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String getAutoExplorationRolloutStatus(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).render();
    }

    @Transactional
    public Status evaluateAndAdvance(String symbol, Long strategyId, String side) {
        Status status = evaluate(symbol, strategyId, side);
        if (!stateService.autoEnabled()) {
            return status;
        }
        if (!status.currentStage().equals(status.recommendedStage())) {
            persistTransition(status);
            maybeNotify(status);
            return evaluate(symbol, strategyId, side);
        }
        return status;
    }

    @Transactional(readOnly = true)
    public Status evaluate(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);

        Optional<AutoExplorationRolloutTransition> latest =
                transitionRepository.findFirstBySymbolAndStrategyIdAndSideOrderByGeneratedAtDesc(sym, sid, normalizedSide);
        String currentStage = latest.map(AutoExplorationRolloutTransition::getCurrentStage)
                .orElse(AutoExplorationRolloutStateService.STAGE_DISABLED);
        String previousStage = latest.map(AutoExplorationRolloutTransition::getPreviousStage).orElse("NONE");
        Instant lastTransitionAt = latest.map(row -> row.getGeneratedAt().toInstant(ZoneOffset.UTC)).orElse(null);

        ExplorationRolloutService.Status rollout = explorationRolloutService.evaluate(sym, sid, normalizedSide);
        AutonomousExplorationLoopService.Status loop = loopService.evaluateStatus(sym, sid, normalizedSide);
        String systemHealth = safe("systemHealth", marketDataMcpTools::getSystemHealth);
        String startup = safe("startupLogIssues", () -> diagnosticMcpTools.getCurrentStartupLogIssues(80));

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>(rollout.warnings());
        double outcomeCoverage = pctFirst(loop.monitor().outcomeLabelRaw(), "matureLabelCoveragePct", "labelCoveragePct");
        String governanceMode = value(loop.monitor().governanceDriftRaw(), "governanceMode");

        boolean systemHealthOk = !containsText(systemHealth, "❌") && !containsText(systemHealth, "⛔");
        boolean startupOk = value(startup, "ERROR").startsWith("0");
        boolean ocoOk = !containsAny(rollout.blockers(), "OCO_HEALTH_ABNORMAL", "OCO_PREFLIGHT_FAIL", "CRITICAL_UNPROTECTED");
        boolean runtimeEvidenceAvailable = containsText(loop.readiness().preview().runtimeEvidenceStatus(), "AVAILABLE_CANONICAL");

        if (!systemHealthOk) blockers.add("SYSTEM_HEALTH_NOT_OK");
        if (!startupOk) blockers.add("STARTUP_ERROR");
        if (!value(startup, "WARN").startsWith("0")) warnings.add("startupWarningsPresentNonBlocking");
        if (!ocoOk) blockers.add("OCO_HEALTH_OR_PREFLIGHT_NOT_OK");
        if (!runtimeEvidenceAvailable) blockers.add("RUNTIME_EVIDENCE_MISSING");
        if (outcomeCoverage < 80.0) blockers.add("OUTCOME_LABEL_COVERAGE_LT_80");
        if ("TOO_LOOSE".equals(governanceMode)) blockers.add("GOVERNANCE_TOO_LOOSE");

        boolean halt = haltRequired(rollout, loop, governanceMode);
        String recommendedStage = currentStage;
        String recommendation = "No stage change recommended.";
        boolean canAutoPromote = false;

        if (halt) {
            recommendedStage = AutoExplorationRolloutStateService.STAGE_HALTED;
            recommendation = "Halt rollout; safety boundary failed.";
        } else if (AutoExplorationRolloutStateService.STAGE_HALTED.equals(currentStage)) {
            if (blockers.isEmpty()) {
                recommendedStage = AutoExplorationRolloutStateService.STAGE_LOOP_DRY_RUN;
                recommendation = "Recover from HALTED to LOOP_DRY_RUN after safety blockers cleared.";
                canAutoPromote = stateService.autoEnabled();
            } else {
                recommendation = "Remain HALTED until safety blockers clear.";
            }
        } else if (AutoExplorationRolloutStateService.STAGE_DISABLED.equals(currentStage)) {
            if (blockers.isEmpty()) {
                recommendedStage = AutoExplorationRolloutStateService.STAGE_LOOP_DRY_RUN;
                recommendation = "Auto-promote to LOOP_DRY_RUN.";
                canAutoPromote = stateService.autoEnabled();
            }
        } else if (AutoExplorationRolloutStateService.STAGE_LOOP_DRY_RUN.equals(currentStage)) {
            if (stateService.allowProductionPromotion() && rollout.canEnableProduction()) {
                recommendedStage = AutoExplorationRolloutStateService.STAGE_PRODUCTION_TINY_LIVE_1_PER_DAY;
                recommendation = "Auto-promote to production tiny-live 1/day.";
                canAutoPromote = stateService.autoEnabled();
            } else if (!stateService.allowProductionPromotion()) {
                blockers.add("PRODUCTION_PROMOTION_CONFIG_DISABLED");
            } else {
                blockers.addAll(rollout.blockers());
            }
        } else if (AutoExplorationRolloutStateService.STAGE_PRODUCTION_TINY_LIVE_1_PER_DAY.equals(currentStage)) {
            if (stateService.allowCapIncrease()
                    && rollout.canIncreaseDailyCap()
                    && outcomeCoverage >= 90.0
                    && !"TOO_LOOSE".equals(governanceMode)) {
                recommendedStage = AutoExplorationRolloutStateService.STAGE_PRODUCTION_TINY_LIVE_2_PER_DAY;
                recommendation = "Auto-promote daily exploration cap to 2/day.";
                canAutoPromote = stateService.autoEnabled();
            } else if (!stateService.allowCapIncrease()) {
                blockers.add("CAP_INCREASE_CONFIG_DISABLED");
            } else {
                blockers.addAll(rollout.blockers());
                if (outcomeCoverage < 90.0) blockers.add("OUTCOME_LABEL_COVERAGE_LT_90");
            }
        }

        return new Status(
                Instant.now(),
                currentStage,
                previousStage,
                lastTransitionAt,
                Instant.now().plus(Duration.ofMinutes(5)),
                recommendedStage,
                canAutoPromote,
                blockers.stream().distinct().toList(),
                warnings.stream().distinct().toList(),
                stateService.autoEnabled(),
                stateService.allowProductionPromotion(),
                stateService.allowCapIncrease(),
                stateService.effectiveLoopEnabled(sym, sid, normalizedSide),
                stateService.effectiveProductionEnabled(sym, sid, normalizedSide),
                stateService.effectiveMaxOrdersPerDay(sym, sid, normalizedSide),
                rollout.completedTinyLiveSamples(),
                rollout.ocoAttachRate(),
                outcomeCoverage,
                rollout.falsePositiveCount(),
                governanceMode,
                recommendation,
                false,
                rollout,
                loop);
    }

    private boolean haltRequired(ExplorationRolloutService.Status rollout,
                                 AutonomousExplorationLoopService.Status loop,
                                 String governanceMode) {
        return "TOO_LOOSE".equals(governanceMode)
                || rollout.dailyLossBudgetBreached()
                || containsAny(rollout.blockers(), "OCO_HEALTH_ABNORMAL", "CRITICAL_UNPROTECTED",
                "SYSTEM_HEALTH_CRITICAL", "DATA_FRESHNESS_HARD_FAIL", "RUNTIME_EVIDENCE_MISSING")
                || containsAny(loop.blockers(), "OCO_HEALTH_ABNORMAL", "CRITICAL_UNPROTECTED",
                "SYSTEM_HEALTH_CRITICAL", "DATA_FRESHNESS_HARD_FAIL", "RUNTIME_EVIDENCE_MISSING");
    }

    private void persistTransition(Status status) {
        AutoExplorationRolloutTransition row = new AutoExplorationRolloutTransition();
        row.setGeneratedAt(LocalDateTime.now(ZoneOffset.UTC));
        row.setSymbol(SYMBOL);
        row.setStrategyId(STRATEGY_ID);
        row.setSide(SIDE);
        row.setPreviousStage(status.currentStage());
        row.setCurrentStage(status.recommendedStage());
        row.setReason(truncate(status.recommendation(), 500));
        row.setBlockersJson(json(status.promotionBlockers()));
        row.setWarningsJson(json(status.promotionWarnings()));
        transitionRepository.save(row);
    }

    private void maybeNotify(Status status) {
        String key = "AutoExplorationRollout:" + status.currentStage() + "->" + status.recommendedStage();
        TgNotificationDeduper.Severity severity = AutoExplorationRolloutStateService.STAGE_HALTED.equals(status.recommendedStage())
                ? TgNotificationDeduper.Severity.WARN
                : TgNotificationDeduper.Severity.FYI;
        if (tgNotificationDeduper.shouldSend(key, Duration.ofHours(6), severity)) {
            telegramService.sendAlert("Auto Exploration Rollout transition\n"
                            + "previousStage=" + status.currentStage() + "\n"
                            + "currentStage=" + status.recommendedStage() + "\n"
                            + "reason=" + status.recommendation() + "\n"
                            + "blockers=" + status.promotionBlockers(),
                    false, key, AutoExplorationRolloutStateService.STAGE_HALTED.equals(status.recommendedStage()) ? "WARN" : "INFO");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String safe(String section, SupplierWithException supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            return section + "Error=" + e.getMessage();
        }
    }

    private String value(String text, String key) {
        if (text == null) {
            return "N/A";
        }
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*:?= ?([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + ":\\s*([^\\n]+)");
        matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "N/A";
    }

    private double pct(String text, String key) {
        String raw = value(text, key).replace("%", "").trim();
        try {
            return Double.parseDouble(raw);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private double pctFirst(String text, String preferredKey, String fallbackKey) {
        String raw = value(text, preferredKey);
        if (!raw.equals("N/A")) {
            try {
                return Double.parseDouble(raw.replace("%", "").trim());
            } catch (Exception ignored) {
            }
        }
        return pct(text, fallbackKey);
    }

    private boolean containsAny(List<String> values, String... needles) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            for (String needle : needles) {
                if (containsText(value, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean containsText(String value, String needle) {
        return value != null && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? SIDE : upper;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record Status(Instant generatedAt,
                         String currentStage,
                         String previousStage,
                         Instant lastTransitionAt,
                         Instant nextEvaluationAt,
                         String recommendedStage,
                         boolean canAutoPromote,
                         List<String> promotionBlockers,
                         List<String> promotionWarnings,
                         boolean autoEnabled,
                         boolean allowProductionPromotion,
                         boolean allowCapIncrease,
                         boolean loopEnabled,
                         boolean productionEnabled,
                         long maxOrdersPerDay,
                         int completedTinyLiveSamples,
                         BigDecimal ocoAttachRate,
                         double outcomeLabelCoverage,
                         int falsePositiveCount,
                         String governanceMode,
                         String recommendation,
                         boolean orderSent,
                         ExplorationRolloutService.Status rollout,
                         AutonomousExplorationLoopService.Status loop) {
        public String render() {
            return """
                    === Auto Exploration Rollout Controller v0 ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    generatedAt=%s
                    currentStage=%s
                    previousStage=%s
                    lastTransitionAt=%s
                    nextEvaluationAt=%s
                    canAutoPromote=%s
                    recommendedStage=%s
                    promotionBlockers=%s
                    promotionWarnings=%s
                    autoEnabled=%s
                    allowProductionPromotion=%s
                    allowCapIncrease=%s
                    loopEnabled=%s
                    productionEnabled=%s
                    maxOrdersPerDay=%d
                    completedTinyLiveSamples=%d
                    ocoAttachRate=%s
                    outcomeLabelCoverage=%.2f%%
                    falsePositiveCount=%d
                    governanceMode=%s
                    recommendation=%s
                    orderSent=false
                    """.formatted(generatedAt, currentStage, previousStage,
                    lastTransitionAt == null ? "N/A" : lastTransitionAt,
                    nextEvaluationAt, canAutoPromote, recommendedStage,
                    promotionBlockers, promotionWarnings,
                    autoEnabled, allowProductionPromotion, allowCapIncrease,
                    loopEnabled, productionEnabled,
                    maxOrdersPerDay, completedTinyLiveSamples, ocoAttachRate,
                    outcomeLabelCoverage, falsePositiveCount, governanceMode, recommendation);
        }
    }

    @FunctionalInterface
    private interface SupplierWithException {
        String get() throws Exception;
    }
}
