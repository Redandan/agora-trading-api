package com.agora.service.trading;

import com.agora.model.TinyLiveExecutionAudit;
import com.agora.repository.trading.TinyLiveExecutionAuditRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ExplorationRolloutService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";

    private final AutonomousExplorationLoopService loopService;
    private final TinyLiveExecutionAuditRepository executionAuditRepository;
    private final AutoExplorationRolloutStateService rolloutStateService;
    private final Environment env;

    @Transactional(readOnly = true)
    public String getExplorationRolloutStatus(String symbol, Long strategyId, String side) {
        return evaluate(symbol, strategyId, side).render();
    }

    @Transactional(readOnly = true)
    public Status evaluate(String symbol, Long strategyId, String side) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);

        AutonomousExplorationLoopService.Status loop = loopService.evaluateStatus(sym, sid, normalizedSide);
        ExplorationPolicyService.Decision readiness = loop.readiness();
        int consecutiveReadyTicks = loopService.consecutiveReadyTicks(sym, sid, normalizedSide);
        ExecutionMetrics executions = executionMetrics(sym, sid, normalizedSide);

        long currentMaxOrdersPerDay = rolloutStateService.effectiveMaxOrdersPerDay(sym, sid, normalizedSide);
        boolean dailyLossBudgetBreached = readiness.explorationBudgetRemaining().compareTo(BigDecimal.ZERO) <= 0
                || containsAny(loop.blockers(), "DAILY_EXPLORATION_LOSS_BUDGET_EXCEEDED", "EXPLORATION_BUDGET_INSUFFICIENT");
        boolean ocoAbnormal = containsAny(loop.blockers(), "OCO_HEALTH_ABNORMAL", "OCO_PREFLIGHT_FAIL", "CRITICAL_UNPROTECTED")
                || containsNonZeroTail(loop.monitor().ocoHealthSummary(), "SYNC_ERROR")
                || containsNonZeroTail(loop.monitor().ocoHealthSummary(), "異常")
                || containsText(loop.monitor().ocoHealthSummary(), "UNPROTECTED");
        boolean hardCritical = containsAny(loop.blockers(), "SYSTEM_HEALTH_CRITICAL", "DATA_FRESHNESS_HARD_FAIL",
                "RUNTIME_EVIDENCE_MISSING", "EV_FAIL", "OCO_PREFLIGHT_FAIL", "OPEN_TINY_LIVE_POSITION",
                "DAILY_EXPLORATION_CAP_REACHED", "DUPLICATE_BAR_SAME_OPPORTUNITY", "EXPOSURE_CAP_HIT");

        boolean canEnableProduction = AutonomousExplorationLoopService.STATE_READY_TO_EXPLORE.equals(loop.currentState())
                && consecutiveReadyTicks >= 3
                && !loop.productionEnabled()
                && loop.blockers().isEmpty()
                && readiness.eligible()
                && (readiness.openTinyLivePositions() == 0 || readiness.openTinyLiveWait().staleSlotReleaseEligible())
                && readiness.ordersToday() < currentMaxOrdersPerDay
                && eventRiskAtMostR2(readiness.preview().eventRiskStatus())
                && runtimeEvidenceAvailable(readiness.preview().runtimeEvidenceStatus())
                && dataFreshnessOk(readiness)
                && tqsAtLeastProbe(readiness.evidence().tqsBand())
                && !ocoAbnormal
                && !hardCritical
                && !dailyLossBudgetBreached;

        int completedTinyLiveSamples = readiness.samples().closedTinyLiveCount();
        boolean canIncreaseDailyCap = completedTinyLiveSamples >= 3
                && executions.ocoAttachRatePct().compareTo(new BigDecimal("100.00")) == 0
                && !dailyLossBudgetBreached
                && !ocoAbnormal
                && readiness.samples().falsePositiveCount() <= 1;

        long recommendedMaxOrdersPerDay = canIncreaseDailyCap && currentMaxOrdersPerDay < 2
                ? 2
                : currentMaxOrdersPerDay;

        List<String> blockers = new ArrayList<>();
        if (!canEnableProduction && !loop.productionEnabled()) {
            if (!AutonomousExplorationLoopService.STATE_READY_TO_EXPLORE.equals(loop.currentState())) {
                blockers.add("LOOP_NOT_READY:" + loop.currentState());
            }
            if (consecutiveReadyTicks < 3) {
                blockers.add("READY_TICKS_LT_3");
            }
            if (!loop.blockers().isEmpty()) {
                blockers.addAll(loop.blockers());
            }
        }
        if (!canIncreaseDailyCap && currentMaxOrdersPerDay < 2) {
            if (completedTinyLiveSamples < 3) {
                blockers.add("COMPLETED_TINY_LIVE_SAMPLES_LT_3");
            }
            if (executions.orderSentCount() > 0
                    && executions.ocoAttachRatePct().compareTo(new BigDecimal("100.00")) < 0) {
                blockers.add("OCO_ATTACH_RATE_LT_100");
            }
            if (readiness.samples().falsePositiveCount() > 1) {
                blockers.add("FALSE_POSITIVE_COUNT_GT_1");
            }
            if (dailyLossBudgetBreached) {
                blockers.add("DAILY_LOSS_BUDGET_BREACHED");
            }
        }

        List<String> warnings = new ArrayList<>(loop.warnings());
        if (loop.productionEnabled() && consecutiveReadyTicks < 3) {
            warnings.add("productionEnabled=true but execution is still held until consecutiveReadyTicks>=3");
        }
        if (!rolloutStateService.allowProductionPromotion()) {
            warnings.add("allowProductionPromotion=false; recommendation only, no silent production promotion");
        }
        if (currentMaxOrdersPerDay > 2) {
            warnings.add("currentMaxOrdersPerDay_above_aggressive_v1_cap");
        }

        return new Status(
                Instant.now(),
                rolloutMode(),
                loop.loopEnabled(),
                loop.productionEnabled(),
                rolloutStateService.allowProductionPromotion(),
                consecutiveReadyTicks,
                completedTinyLiveSamples,
                executions.ocoAttachRatePct(),
                readiness.samples().falsePositiveCount(),
                parseRate(loop.monitor().governanceDriftRaw(), "falseBlockRate"),
                dailyLossBudgetBreached,
                currentMaxOrdersPerDay,
                recommendedMaxOrdersPerDay,
                canEnableProduction,
                canIncreaseDailyCap,
                blockers.stream().distinct().toList(),
                warnings.stream().distinct().toList(),
                loop.currentState(),
                loop.readinessSummary(),
                loop.governanceDriftSummary(),
                false);
    }

    private ExecutionMetrics executionMetrics(String symbol, long strategyId, String side) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(30);
        List<TinyLiveExecutionAudit> rows = executionAuditRepository.findRecent(since, symbol, PageRequest.of(0, 100))
                .stream()
                .filter(row -> row.getStrategyId() != null && row.getStrategyId() == strategyId)
                .filter(row -> side.equalsIgnoreCase(row.getSide()))
                .filter(row -> Boolean.TRUE.equals(row.getOrderSent()))
                .toList();
        long attached = rows.stream().filter(row -> Boolean.TRUE.equals(row.getOcoAttached())).count();
        BigDecimal attachRate = rows.isEmpty()
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(attached)
                .multiply(new BigDecimal("100"))
                .divide(BigDecimal.valueOf(rows.size()), 2, RoundingMode.HALF_UP);
        return new ExecutionMetrics(rows.size(), attachRate);
    }

    private String rolloutMode() {
        return env.getProperty("trading.exploration.rollout.mode", "AGGRESSIVE_BOUNDED");
    }

    private long longProperty(String key, long fallback) {
        try {
            return Long.parseLong(env.getProperty(key, String.valueOf(fallback)));
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean eventRiskAtMostR2(String eventRiskStatus) {
        String risk = eventRiskLevel(eventRiskStatus);
        return "R0".equals(risk) || "R1".equals(risk) || "R2".equals(risk) || "UNKNOWN".equals(risk);
    }

    private String eventRiskLevel(String eventRiskStatus) {
        if (eventRiskStatus == null || eventRiskStatus.isBlank()) {
            return "UNKNOWN";
        }
        return eventRiskStatus.trim().split("\\s+")[0].toUpperCase(Locale.ROOT);
    }

    private boolean runtimeEvidenceAvailable(String status) {
        return status != null && status.startsWith("AVAILABLE_CANONICAL");
    }

    private boolean dataFreshnessOk(ExplorationPolicyService.Decision readiness) {
        return !containsText(readiness.evidence().freshnessState(), "BLOCKED")
                && !containsText(readiness.evidence().freshnessState(), "HARD_FAIL");
    }

    private boolean tqsAtLeastProbe(String tqsBand) {
        if (tqsBand == null) {
            return false;
        }
        String band = tqsBand.trim().toUpperCase(Locale.ROOT);
        return "PROBE_DRY_RUN".equals(band)
                || "SMALL_DRY_RUN".equals(band)
                || "CAPPED_SMALL_DRY_RUN".equals(band);
    }

    private String parseRate(String text, String key) {
        if (text == null) {
            return "N/A";
        }
        Pattern pattern = Pattern.compile("(?m)^" + Pattern.quote(key) + "=([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "N/A";
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

    private boolean containsNonZeroTail(String text, String label) {
        if (text == null) {
            return false;
        }
        Pattern pattern = Pattern.compile("(\\d+)\\s+" + Pattern.quote(label));
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) > 0) {
                return true;
            }
        }
        return false;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        if (side == null || side.isBlank()) return DEFAULT_SIDE;
        String upper = side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(upper) ? DEFAULT_SIDE : upper;
    }

    public record Status(Instant generatedAt,
                         String rolloutMode,
                         boolean loopEnabled,
                         boolean productionEnabled,
                         boolean allowProductionPromotion,
                         int consecutiveReadyTicks,
                         int completedTinyLiveSamples,
                         BigDecimal ocoAttachRate,
                         int falsePositiveCount,
                         String falseBlockRate,
                         boolean dailyLossBudgetBreached,
                         long currentMaxOrdersPerDay,
                         long recommendedMaxOrdersPerDay,
                         boolean canEnableProduction,
                         boolean canIncreaseDailyCap,
                         List<String> blockers,
                         List<String> warnings,
                         String loopState,
                         String readinessSummary,
                         String governanceDriftSummary,
                         boolean orderSent) {
        public String render() {
            return """
                    === Exploration Rollout Status v1 ===
                    boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn behavior changed.
                    generatedAt=%s
                    rolloutMode=%s
                    loopEnabled=%s
                    productionEnabled=%s
                    allowProductionPromotion=%s
                    loopState=%s
                    consecutiveReadyTicks=%d
                    completedTinyLiveSamples=%d
                    ocoAttachRate=%s
                    falsePositiveCount=%d
                    falseBlockRate=%s
                    dailyLossBudgetBreached=%s
                    currentMaxOrdersPerDay=%d
                    recommendedMaxOrdersPerDay=%d
                    canEnableProduction=%s
                    canIncreaseDailyCap=%s
                    blockers=%s
                    warnings=%s
                    readinessSummary=%s
                    governanceDriftSummary=%s
                    orderSent=false
                    """.formatted(generatedAt, rolloutMode, loopEnabled, productionEnabled,
                    allowProductionPromotion, loopState, consecutiveReadyTicks,
                    completedTinyLiveSamples, ocoAttachRate, falsePositiveCount, falseBlockRate,
                    dailyLossBudgetBreached, currentMaxOrdersPerDay, recommendedMaxOrdersPerDay,
                    canEnableProduction, canIncreaseDailyCap, blockers, warnings,
                    readinessSummary, governanceDriftSummary);
        }
    }

    private record ExecutionMetrics(int orderSentCount,
                                    BigDecimal ocoAttachRatePct) {
    }
}
