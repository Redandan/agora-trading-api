package com.agora.service.trading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.agora.service.trading.BtcDraPolicy.*;

/** Shared, side-effect-free description and arithmetic of the existing LIVE contract. */
public final class BtcDraExecutionContract {
    public static final String PROFILE = "DRA_V1_VIRTUAL250_SINGLE30_CURRENT_QUOTE";
    private BtcDraExecutionContract() { }

    public static BtcDraShadowEngine.RuntimeEvent entryEvent(BtcDraShadowEngine.StepResult step) {
        return step.events().stream().filter(e -> "VIRTUAL_ENTRY_QUEUED".equals(e.eventType()))
                .findFirst().orElse(null);
    }

    public static BigDecimal estimatedNetReturn(BigDecimal quantity, BigDecimal effectiveEntry,
                                                BigDecimal currentPrice) {
        BigDecimal estimatedSellPrice = currentPrice.multiply(BigDecimal.ONE.subtract(ADVERSE_SLIPPAGE_RATE_PER_SIDE));
        BigDecimal estimatedNet = estimatedSellPrice.multiply(quantity).multiply(BigDecimal.ONE.subtract(FEE_RATE_PER_SIDE));
        BigDecimal cost = effectiveEntry.multiply(quantity);
        if (cost.signum() <= 0) return BigDecimal.valueOf(-1);
        return estimatedNet.subtract(cost).divide(cost, 12, RoundingMode.HALF_UP);
    }

    public static Map<String, Object> description() {
        return Map.of(
                "profile", PROFILE,
                "signalSource", "VIRTUAL_ENTRY_QUEUED",
                "signalReferenceCapUsdt", MAX_OPEN_COST_USDT,
                "liveNotionalUsdt", BASE_NOTIONAL_USDT,
                "maximumLiveLots", 1,
                "cooldownBasis", "VIRTUAL_QUEUED_SIGNAL_INCLUDING_LIVE_BLOCKED_SIGNALS",
                "liveExitBasis", "CURRENT_PROVIDER_QUOTE_ON_FRESH_CLOSED_HOURLY_EVALUATION",
                "referenceExitBasis", "CLOSED_BAR_TRIGGER_NEXT_OPEN_WITH_ONE_PERCENT_FLOOR",
                "sameEvaluationExitAndEntry", false,
                "referencePerformanceIsLivePerformance", false);
    }

    /** Describes this evaluated bar, not a prediction or permission to submit an order. */
    public static Map<String, Object> entryDecision(BtcDraShadowEngine.StepResult step,
                                                     LocalDateTime barOpen, boolean bootstrap, boolean catchUp) {
        boolean queued = entryEvent(step) != null;
        boolean capBlocked = step.events().stream().anyMatch(e -> "VIRTUAL_ENTRY_BLOCKED".equals(e.eventType()));
        LocalDateTime last = step.state().lastEntrySignalBarOpenTime();
        LocalDateTime cooldownUntil = last == null ? null : last.plusDays(ENTRY_COOLDOWN_DAYS);
        String stage;
        if (bootstrap) stage = "BOOTSTRAP_NO_LIVE_EXECUTION";
        else if (catchUp) stage = "CATCH_UP_NO_LIVE_EXECUTION";
        else if (queued) stage = "QUEUED_AWAITING_LIVE_GATES";
        else if (capBlocked) stage = "VIRTUAL_REFERENCE_CAPACITY_BLOCKED";
        else if (!step.signal().ready()) stage = "INDICATORS_NOT_READY";
        else if (!step.signal().dailyDecision()) stage = "WAITING_FOR_UTC_DAILY_CLOSE";
        else if (!step.signal().dailyReversalConfirmed()) stage = "DAILY_CONDITIONS_NOT_MET";
        else if (cooldownUntil != null && barOpen.isBefore(cooldownUntil)) stage = "VIRTUAL_SIGNAL_COOLDOWN";
        else if (barOpen.equals(step.state().armedAt())) stage = "REARMED_WAITING_FOR_LATER_DAILY_CLOSE";
        else stage = "NO_QUEUED_ENTRY";
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", "DRA_ENTRY_DECISION_V1");
        out.put("profile", PROFILE);
        out.put("barOpenUtc", DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(barOpen));
        out.put("stage", stage);
        out.put("queuedCandidate", queued);
        out.put("virtualCapacityBlocked", capBlocked);
        out.put("armedAfterEvaluation", step.state().armedAt() != null);
        out.put("lastVirtualSignalBarOpenUtc", last == null ? null : last.toString());
        out.put("cooldownUntilBarOpenUtc", cooldownUntil == null ? null : cooldownUntil.toString());
        out.put("liveExecutionStatus", "SEPARATE_EXECUTION_EVIDENCE_REQUIRED");
        return out;
    }
}
