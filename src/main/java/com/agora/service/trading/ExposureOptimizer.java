package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only exposure decision layer for live entry candidates.
 *
 * <p>v0.1 deliberately does not place orders or mutate positions. It only turns
 * the old binary EntryDedup guard into an auditable decision with risk caps.</p>
 */
@Service
@RequiredArgsConstructor
public class ExposureOptimizer {

    public enum Decision {
        BLOCK_DUPLICATE,
        SHADOW_MICRO_ADD_CANDIDATE,
        ALLOW_STAGED_MICRO_ADD_ENTRY,
        ALLOW_NEW_ENTRY
    }

    public record Result(Decision decision, String reason, Map<String, Object> context) {
        public boolean blocksEntry() {
            return decision == Decision.BLOCK_DUPLICATE;
        }

        public boolean shadowOnly() {
            return decision == Decision.SHADOW_MICRO_ADD_CANDIDATE;
        }

        public boolean stagedMicroAddEntry() {
            return decision == Decision.ALLOW_STAGED_MICRO_ADD_ENTRY;
        }

        public double microAddNotionalCapUsdt() {
            Object value = context != null ? context.get("micro_add_notional_cap_usdt") : null;
            if (value instanceof Number n) return n.doubleValue();
            if (value instanceof String s && !s.isBlank()) {
                try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
            }
            return 0.0;
        }
    }

    private final BtLiveSignalRepository liveSignalRepository;

    public Result evaluateLongEntry(BtStrategy strategy,
                                    Map<String, Object> config,
                                    String symbol,
                                    String intervalCode,
                                    double expectedR,
                                    double candidateStopLossPct,
                                    boolean sameStrategyOpenLong) {
        List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        BigDecimal actualExposure = open.stream()
                .map(this::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal openMaxLoss = open.stream()
                .map(this::maxLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sameStrategyExposure = open.stream()
                .filter(p -> sameStrategyPosition(p, strategy, symbol, intervalCode))
                .map(this::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        int dailyCap = getInt(config, "exposureOptimizerDailyNewEntryCap", 1);
        LocalDateTime dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        long todayEntries = liveSignalRepository.countByAutoTradedIsTrueAndCreatedAtAfter(dayStart);
        double capitalUsdt = getDouble(config, "exposureOptimizerCapitalUsdt", 0.0);
        double exposureCapPct = getDouble(config, "exposureOptimizerMaxActualExposurePct", 50.0);
        double maxLossCap = getDouble(config, "exposureOptimizerOpenMaxLossCapUsdt", 7.0);
        String dedupMode = getString(config, "entryDedupDecisionMode", "BLOCK").trim().toUpperCase();
        double microAddMinExpectedR = getDouble(config, "microAddMinExpectedR", 0.20);
        double microAddNotional = getDouble(config, "microAddNotionalUsdt", 5.0);
        double microAddMaxSameStrategyExposure = getDouble(config, "microAddMaxSameStrategyExposureUsdt", 75.0);
        double candidateMaxLoss = Math.max(0.0, microAddNotional) * Math.max(0.0, candidateStopLossPct);
        boolean microAddLiveEnabled = getBoolean(config, "microAddLiveEnabled", false)
                || "ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE".equals(dedupMode);
        boolean notifyOnly = getBoolean(config, "notifyOnly", false);
        int shadowDailyCap = shadowDailyCap(config, dailyCap);
        long shadowTodayEntries = notifyOnly
                ? liveSignalRepository.countShadowLongSignalsSince(strategy.getId(), symbol, intervalCode, dayStart)
                : 0L;

        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("decision_layer", "ExposureOptimizerV0_1");
        ctx.put("strategy_id", strategy.getId());
        ctx.put("symbol", symbol);
        ctx.put("interval", intervalCode);
        ctx.put("same_strategy_open_long", sameStrategyOpenLong);
        ctx.put("expected_r", round(expectedR));
        ctx.put("micro_add_min_expected_r", microAddMinExpectedR);
        ctx.put("micro_add_notional_cap_usdt", microAddNotional);
        ctx.put("micro_add_live_enabled", microAddLiveEnabled);
        ctx.put("micro_add_expected_r_positive", expectedR > 0);
        ctx.put("micro_add_expected_r_threshold_passed", expectedR >= microAddMinExpectedR);
        ctx.put("micro_add_candidate_max_loss_usdt", round(candidateMaxLoss));
        ctx.put("same_strategy_exposure_usdt", plain(sameStrategyExposure));
        ctx.put("same_strategy_exposure_cap_usdt", microAddMaxSameStrategyExposure);
        ctx.put("entry_dedup_decision_mode", dedupMode);
        ctx.put("open_auto_position_count", open.size());
        ctx.put("actual_exposure_usdt", plain(actualExposure));
        ctx.put("open_max_loss_usdt", plain(openMaxLoss));
        ctx.put("daily_new_auto_entries", todayEntries);
        ctx.put("daily_new_entry_cap", dailyCap);
        ctx.put("shadow_daily_new_entries", shadowTodayEntries);
        ctx.put("shadow_daily_new_entry_cap", shadowDailyCap);
        ctx.put("daily_cap_scope", notifyOnly ? "SHADOW_NOTIFY_ONLY" : "LIVE_AUTO_TRADE");
        ctx.put("capital_usdt", capitalUsdt > 0 ? capitalUsdt : "unconfigured");
        ctx.put("actual_exposure_cap_pct", exposureCapPct);
        ctx.put("open_max_loss_cap_usdt", maxLossCap);
        ctx.put("notify_only", notifyOnly);
        ctx.put("write_mode", false);

        if (notifyOnly && shadowDailyCap > 0 && shadowTodayEntries >= shadowDailyCap) {
            return new Result(Decision.BLOCK_DUPLICATE,
                    "shadow daily learning cap reached", ctx);
        }

        if (notifyOnly && !sameStrategyOpenLong) {
            return new Result(Decision.ALLOW_NEW_ENTRY,
                    "notifyOnly candidate remains observable; exposure caps protect auto-trade path", ctx);
        }

        if (capitalUsdt > 0) {
            BigDecimal cap = BigDecimal.valueOf(capitalUsdt)
                    .multiply(BigDecimal.valueOf(exposureCapPct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            ctx.put("actual_exposure_cap_usdt", plain(cap));
            if (actualExposure.compareTo(cap) >= 0) {
                return new Result(Decision.BLOCK_DUPLICATE,
                        "actual exposure is at or above configured cap", ctx);
            }
        }

        if (maxLossCap > 0 && openMaxLoss.compareTo(BigDecimal.valueOf(maxLossCap)) >= 0) {
            return new Result(Decision.BLOCK_DUPLICATE,
                    "open max loss is at or above configured cap", ctx);
        }

        if (!notifyOnly && dailyCap > 0 && todayEntries >= dailyCap) {
            return new Result(Decision.BLOCK_DUPLICATE,
                    "daily new auto-entry cap reached", ctx);
        }

        if (sameStrategyOpenLong) {
            boolean positiveEvMicroAddMode = "ALLOW_MICRO_ADD_IF_EV_POSITIVE".equals(dedupMode)
                    || "ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE".equals(dedupMode);
            if (positiveEvMicroAddMode && expectedR > 0) {
                if (microAddNotional <= 0) {
                    return new Result(Decision.BLOCK_DUPLICATE,
                            "micro-add notional cap is not positive", ctx);
                }
                if (sameStrategyExposure.compareTo(BigDecimal.valueOf(microAddMaxSameStrategyExposure)) >= 0) {
                    return new Result(Decision.BLOCK_DUPLICATE,
                            "same-strategy staged add exposure budget exhausted", ctx);
                }
                if (maxLossCap > 0 && openMaxLoss.add(BigDecimal.valueOf(candidateMaxLoss))
                        .compareTo(BigDecimal.valueOf(maxLossCap)) > 0) {
                    return new Result(Decision.BLOCK_DUPLICATE,
                            "micro-add would exceed open max loss cap", ctx);
                }
                if (!notifyOnly && microAddLiveEnabled) {
                    return new Result(Decision.ALLOW_STAGED_MICRO_ADD_ENTRY,
                            "same-strategy exposure exists; staged live micro-add is allowed because expectedR is positive and budgets pass", ctx);
                }
                return new Result(Decision.SHADOW_MICRO_ADD_CANDIDATE,
                        "same-strategy exposure exists; candidate stays shadow-only because expectedR is positive but live micro-add is disabled", ctx);
            }
            return new Result(Decision.BLOCK_DUPLICATE,
                    "same strategy/symbol/interval LONG exposure already exists", ctx);
        }

        return new Result(Decision.ALLOW_NEW_ENTRY, "risk caps passed for new entry candidate", ctx);
    }

    private BigDecimal notional(BtLiveSignal p) {
        BigDecimal entry = entryPrice(p);
        BigDecimal qty = quantity(p);
        if (entry == null || qty == null) return BigDecimal.ZERO;
        return entry.multiply(qty).abs();
    }

    private BigDecimal maxLoss(BtLiveSignal p) {
        BigDecimal entry = entryPrice(p);
        BigDecimal qty = quantity(p);
        BigDecimal sl = p.getSuggestedSl();
        if (entry == null || qty == null || sl == null) return BigDecimal.ZERO;
        boolean isShort = "SHORT".equalsIgnoreCase(p.getSide());
        BigDecimal diff = isShort ? sl.subtract(entry) : entry.subtract(sl);
        return diff.signum() > 0 ? diff.multiply(qty).abs() : BigDecimal.ZERO;
    }

    private BigDecimal entryPrice(BtLiveSignal p) {
        return p.getActualEntryPrice() != null ? p.getActualEntryPrice() : p.getEntryPrice();
    }

    private BigDecimal quantity(BtLiveSignal p) {
        if (p.getOcoQty() != null && p.getOcoQty().signum() > 0) return p.getOcoQty();
        return p.getTradedQty();
    }

    private boolean sameStrategyPosition(BtLiveSignal p, BtStrategy strategy, String symbol, String intervalCode) {
        return p.getStrategyId() != null
                && strategy.getId() != null
                && p.getStrategyId().equals(strategy.getId())
                && symbol.equalsIgnoreCase(p.getSymbol())
                && intervalCode.equalsIgnoreCase(p.getIntervalCode())
                && "LONG".equalsIgnoreCase(p.getSide());
    }

    private static String plain(BigDecimal value) {
        if (value == null) return "N/A";
        return value.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }

    private static int getInt(Map<String, Object> config, String key, int def) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static double getDouble(Map<String, Object> config, String key, double def) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static String getString(Map<String, Object> config, String key, String def) {
        Object v = config != null ? config.get(key) : null;
        return v != null ? String.valueOf(v) : def;
    }

    private static boolean getBoolean(Map<String, Object> config, String key, boolean def) {
        Object v = config != null ? config.get(key) : null;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        if (v instanceof String s && !s.isBlank()) {
            return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
        }
        return def;
    }

    private static int shadowDailyCap(Map<String, Object> config, int liveDailyCap) {
        int configured = getInt(config, "exposureOptimizerShadowDailyNewEntryCap",
                getInt(config, "shadowDailyNewEntryCap", Integer.MIN_VALUE));
        if (configured != Integer.MIN_VALUE) {
            return configured;
        }
        return Math.max(liveDailyCap, 2);
    }
}
