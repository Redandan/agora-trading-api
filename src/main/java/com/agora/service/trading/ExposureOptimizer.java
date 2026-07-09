package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
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
        return evaluateLongEntry(strategy, config, symbol, intervalCode, expectedR, candidateStopLossPct,
                sameStrategyOpenLong, null, null, null, null, null);
    }

    public Result evaluateLongEntry(BtStrategy strategy,
                                    Map<String, Object> config,
                                    String symbol,
                                    String intervalCode,
                                    double expectedR,
                                    double candidateStopLossPct,
                                    boolean sameStrategyOpenLong,
                                    BigDecimal candidateEntry,
                                    BigDecimal candidateTp,
                                    BigDecimal candidateSl,
                                    LocalDateTime candidateBarOpenTime,
                                    Double minExpectedR) {
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
        BigDecimal sameStrategyAnyIntervalExposure = open.stream()
                .filter(p -> sameStrategySymbolLongPosition(p, strategy, symbol))
                .map(this::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sameSymbolLongExposure = open.stream()
                .filter(p -> sameSymbolLongPosition(p, symbol))
                .map(this::notional)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean sameSymbolOpenLong = sameSymbolLongExposure.signum() > 0;
        boolean sameStrategyAnyIntervalOpenLong = sameStrategyAnyIntervalExposure.signum() > 0;

        int dailyCap = getInt(config, "exposureOptimizerDailyNewEntryCap", 1);
        int stagedAddMaxOrdersPerDay = getInt(config, "stagedAddMaxOrdersPerDay",
                getInt(config, "microAddMaxOrdersPerDay", dailyCap));
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
        BigDecimal candidateMaxLossMoney = BigDecimal.valueOf(candidateMaxLoss);
        BigDecimal projectedOpenMaxLoss = openMaxLoss.add(candidateMaxLossMoney);
        boolean blockSameSymbolLong = getBoolean(config, "exposureOptimizerBlockSameSymbolLong", true);
        boolean allowSameStrategyCrossIntervalStagedAdd = getBoolean(config,
                "stagedAddAllowSameStrategyCrossInterval",
                getBoolean(config, "microAddAllowSameStrategyCrossInterval", false));
        boolean sameStrategyCrossIntervalOpenLong = sameStrategyAnyIntervalOpenLong && !sameStrategyOpenLong;
        boolean effectiveSameStrategyOpenLong = sameStrategyOpenLong
                || (allowSameStrategyCrossIntervalStagedAdd && sameStrategyAnyIntervalOpenLong);
        BigDecimal effectiveSameStrategyExposure = allowSameStrategyCrossIntervalStagedAdd
                ? sameStrategyAnyIntervalExposure
                : sameStrategyExposure;
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
        ctx.put("same_strategy_interval_open_long", sameStrategyOpenLong);
        ctx.put("same_strategy_any_interval_open_long", sameStrategyAnyIntervalOpenLong);
        ctx.put("same_strategy_cross_interval_open_long", sameStrategyCrossIntervalOpenLong);
        ctx.put("same_strategy_staged_add_open_long", effectiveSameStrategyOpenLong);
        ctx.put("staged_add_allow_same_strategy_cross_interval", allowSameStrategyCrossIntervalStagedAdd);
        ctx.put("expected_r", round(expectedR));
        ctx.put("micro_add_min_expected_r", microAddMinExpectedR);
        ctx.put("micro_add_notional_cap_usdt", microAddNotional);
        ctx.put("micro_add_live_enabled", microAddLiveEnabled);
        ctx.put("micro_add_expected_r_positive", expectedR > 0);
        ctx.put("micro_add_expected_r_threshold_passed", expectedR >= microAddMinExpectedR);
        ctx.put("micro_add_candidate_max_loss_usdt", round(candidateMaxLoss));
        ctx.put("same_strategy_interval_exposure_usdt", plain(sameStrategyExposure));
        ctx.put("same_strategy_any_interval_exposure_usdt", plain(sameStrategyAnyIntervalExposure));
        ctx.put("same_strategy_exposure_usdt", plain(effectiveSameStrategyExposure));
        ctx.put("same_strategy_exposure_scope", allowSameStrategyCrossIntervalStagedAdd
                ? "ANY_INTERVAL"
                : "CURRENT_INTERVAL");
        ctx.put("same_strategy_exposure_cap_usdt", microAddMaxSameStrategyExposure);
        ctx.put("same_symbol_open_long", sameSymbolOpenLong);
        ctx.put("same_symbol_long_exposure_usdt", plain(sameSymbolLongExposure));
        ctx.put("exposure_optimizer_block_same_symbol_long", blockSameSymbolLong);
        ctx.put("entry_dedup_decision_mode", dedupMode);
        ctx.put("open_auto_position_count", open.size());
        ctx.put("actual_exposure_usdt", plain(actualExposure));
        ctx.put("actualExposureUsdt", plain(actualExposure));
        ctx.put("open_max_loss_usdt", plain(openMaxLoss));
        ctx.put("openMaxLoss", plain(openMaxLoss));
        ctx.put("openMaxLossUsdt", plain(openMaxLoss));
        ctx.put("daily_new_auto_entries", todayEntries);
        ctx.put("daily_new_entry_cap", dailyCap);
        ctx.put("dailyCapUsed", todayEntries);
        ctx.put("dailyCapLimit", dailyCap);
        ctx.put("dailyCapRemaining", remainingCount(dailyCap, todayEntries));
        ctx.put("staged_add_orders_today", todayEntries);
        ctx.put("staged_add_max_orders_per_day", stagedAddMaxOrdersPerDay);
        ctx.put("stagedAddOrdersToday", todayEntries);
        ctx.put("stagedAddMaxOrdersPerDay", stagedAddMaxOrdersPerDay);
        ctx.put("stagedAddDailyRemaining", remainingCount(stagedAddMaxOrdersPerDay, todayEntries));
        ctx.put("shadow_daily_new_entries", shadowTodayEntries);
        ctx.put("shadow_daily_new_entry_cap", shadowDailyCap);
        ctx.put("shadowDailyCapUsed", shadowTodayEntries);
        ctx.put("shadowDailyCapLimit", shadowDailyCap);
        ctx.put("shadowDailyCapRemaining", remainingCount(shadowDailyCap, shadowTodayEntries));
        ctx.put("daily_cap_scope", notifyOnly ? "SHADOW_NOTIFY_ONLY" : "LIVE_AUTO_TRADE");
        ctx.put("dailyCapScope", notifyOnly ? "SHADOW_NOTIFY_ONLY" : "LIVE_AUTO_TRADE");
        ctx.put("dailyCapCountSinceUtc", dayStart.toString());
        ctx.put("dailyCapSnapshot", dailyCapSnapshot(notifyOnly, dailyCap, todayEntries,
                shadowDailyCap, shadowTodayEntries, dayStart));
        ctx.put("capital_usdt", capitalUsdt > 0 ? capitalUsdt : "unconfigured");
        ctx.put("capitalUsdt", capitalUsdt > 0 ? capitalUsdt : "unconfigured");
        ctx.put("actual_exposure_cap_pct", exposureCapPct);
        ctx.put("actualExposureCapPct", exposureCapPct);
        ctx.put("open_max_loss_cap_usdt", maxLossCap);
        ctx.put("openMaxLossCapUsdt", maxLossCap);
        ctx.put("candidateMaxLossUsdt", round(candidateMaxLoss));
        ctx.put("maxLossIfWrongUsdt", round(candidateMaxLoss));
        ctx.put("projectedOpenMaxLossUsdt", plain(projectedOpenMaxLoss));
        ctx.put("maxLossCapRemainingUsdt", maxLossCapRemaining(maxLossCap, openMaxLoss));
        ctx.put("maxLossSnapshot", maxLossSnapshot(maxLossCap, openMaxLoss, candidateMaxLossMoney,
                projectedOpenMaxLoss));
        ctx.put("notify_only", notifyOnly);
        ctx.put("write_mode", false);
        enrichCandidateRuntimeSnapshot(ctx, strategy, symbol, intervalCode, expectedR,
                minExpectedR == null ? microAddMinExpectedR : minExpectedR,
                candidateEntry, candidateTp, candidateSl, candidateBarOpenTime);

        if (notifyOnly && shadowDailyCap > 0 && shadowTodayEntries >= shadowDailyCap) {
            return result(Decision.BLOCK_DUPLICATE, "shadow daily learning cap reached", ctx);
        }

        if (notifyOnly && !effectiveSameStrategyOpenLong) {
            return result(Decision.ALLOW_NEW_ENTRY,
                    "notifyOnly candidate remains observable; exposure caps protect auto-trade path", ctx);
        }

        if (blockSameSymbolLong && sameSymbolOpenLong && !effectiveSameStrategyOpenLong) {
            return result(Decision.BLOCK_DUPLICATE,
                    "same-symbol LONG exposure already exists across strategy boundary", ctx);
        }

        if (capitalUsdt > 0) {
            BigDecimal cap = BigDecimal.valueOf(capitalUsdt)
                    .multiply(BigDecimal.valueOf(exposureCapPct))
                    .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
            ctx.put("actual_exposure_cap_usdt", plain(cap));
            if (actualExposure.compareTo(cap) >= 0) {
                return result(Decision.BLOCK_DUPLICATE,
                        "actual exposure is at or above configured cap", ctx);
            }
        }

        if (maxLossCap > 0 && openMaxLoss.compareTo(BigDecimal.valueOf(maxLossCap)) >= 0) {
            return result(Decision.BLOCK_DUPLICATE,
                    "open max loss is at or above configured cap", ctx);
        }

        if (!notifyOnly && dailyCap > 0 && todayEntries >= dailyCap && !effectiveSameStrategyOpenLong) {
            return result(Decision.BLOCK_DUPLICATE,
                    "daily new auto-entry cap reached", ctx);
        }

        if (effectiveSameStrategyOpenLong) {
            boolean positiveEvMicroAddMode = "ALLOW_MICRO_ADD_IF_EV_POSITIVE".equals(dedupMode)
                    || "ALLOW_STAGED_MICRO_ADD_LIVE_IF_EV_POSITIVE".equals(dedupMode);
            if (positiveEvMicroAddMode && expectedR > 0) {
                if (microAddNotional <= 0) {
                    return result(Decision.BLOCK_DUPLICATE,
                            "micro-add notional cap is not positive", ctx);
                }
                if (!notifyOnly && stagedAddMaxOrdersPerDay > 0 && todayEntries >= stagedAddMaxOrdersPerDay) {
                    return result(Decision.BLOCK_DUPLICATE,
                            "staged micro-add daily cap reached", ctx);
                }
                if (effectiveSameStrategyExposure.compareTo(BigDecimal.valueOf(microAddMaxSameStrategyExposure)) >= 0) {
                    return result(Decision.BLOCK_DUPLICATE,
                            "same-strategy staged add exposure budget exhausted", ctx);
                }
                if (maxLossCap > 0 && openMaxLoss.add(BigDecimal.valueOf(candidateMaxLoss))
                        .compareTo(BigDecimal.valueOf(maxLossCap)) > 0) {
                    return result(Decision.BLOCK_DUPLICATE,
                            "micro-add would exceed open max loss cap", ctx);
                }
                String exposureReason = sameStrategyCrossIntervalOpenLong && allowSameStrategyCrossIntervalStagedAdd
                        ? "same-strategy cross-interval exposure exists"
                        : "same-strategy exposure exists";
                if (!notifyOnly && microAddLiveEnabled) {
                    return result(Decision.ALLOW_STAGED_MICRO_ADD_ENTRY,
                            exposureReason + "; staged live micro-add is allowed because expectedR is positive and budgets pass", ctx);
                }
                return result(Decision.SHADOW_MICRO_ADD_CANDIDATE,
                        exposureReason + "; candidate stays shadow-only because expectedR is positive but live micro-add is disabled", ctx);
            }
            return result(Decision.BLOCK_DUPLICATE,
                    "same strategy/symbol/interval LONG exposure already exists", ctx);
        }

        return result(Decision.ALLOW_NEW_ENTRY, "risk caps passed for new entry candidate", ctx);
    }

    private Result result(Decision decision, String reason, Map<String, Object> ctx) {
        ctx.put("exposureOptimizerDecision", decision.name());
        ctx.put("exposureOptimizerReason", reason);
        if (decision == Decision.BLOCK_DUPLICATE || decision == Decision.SHADOW_MICRO_ADD_CANDIDATE) {
            ctx.put("candidateSnapshotCollectorStatus", "SHADOW_RUNTIME_SNAPSHOT_READY_NOT_LIVE");
            ctx.put("candidateSnapshotCollectorBoundary", "EVIDENCE_ONLY_NO_ORDER_NO_POLICY_CHANGE");
            ctx.put("executionMode", "SHADOW");
            ctx.put("selectedAction", "ENTRY_DEDUP_SHADOW_CANDIDATE_SNAPSHOT");
            ctx.put("decision", "SUPPRESS_ORDER");
            ctx.put("intentCreated", true);
            ctx.put("ocoPlanCreated", Boolean.TRUE.equals(ctx.get("ocoCapable")));
            ctx.put("orderSent", false);
            ctx.put("orderAllowed", false);
            ctx.put("gridMutationAllowed", false);
            ctx.put("schedulerEnablementAllowed", false);
            ctx.put("telegramSendAllowed", false);
            ctx.put("livePolicyRelaxationAllowed", false);
            ctx.put("suppressionReason", "SHADOW_MODE");
            ctx.put("runtimeEvidencePolicyMode", "BLOCK");
            ctx.put("runtimeEvidencePolicyReason", "ExposureOptimizer/EntryDedup kept original block: " + reason);
            ctx.put("candidateContinuedToEv", true);
            ctx.put("candidateContinuedToTqs", true);
            ctx.put("gate_enabled", true);
            ctx.put("riskGateResult", "ENTRY_DEDUP_OR_EXPOSURE_BLOCK_WITH_CANDIDATE_SNAPSHOT");
        }
        return new Result(decision, reason, ctx);
    }

    private void enrichCandidateRuntimeSnapshot(Map<String, Object> ctx,
                                                BtStrategy strategy,
                                                String symbol,
                                                String intervalCode,
                                                double expectedR,
                                                double minExpectedR,
                                                BigDecimal entry,
                                                BigDecimal tp,
                                                BigDecimal sl,
                                                LocalDateTime barOpenTime) {
        ctx.put("min_expected_r", round(minExpectedR));
        ctx.put("ev_reason", expectedR >= minExpectedR ? "pass" : "expectedR<minExpectedR");
        ctx.put("side", "LONG");
        ctx.put("candidate_side", "LONG");
        ctx.put("signalSource", "LiveSignalEvaluator");
        if (barOpenTime != null) {
            ctx.put("candidateBarOpenTime", barOpenTime.toString());
        }
        putMoneyAliases(ctx, "entry", "entryPrice", "candidateEntry", entry);
        putMoneyAliases(ctx, "tp", "tpPrice", "candidateTp", tp);
        putMoneyAliases(ctx, "sl", "slPrice", "candidateSl", sl);
        boolean ocoCapable = entry != null && tp != null && sl != null;
        ctx.put("ocoCapable", ocoCapable);
        String hash = shortHash("edsr1", strategy.getId(), symbol, intervalCode, barOpenTime,
                plain(entry), plain(tp), plain(sl), round(expectedR), round(minExpectedR));
        ctx.put("duplicateCandidateHash", hash);
        ctx.put("replayCandidateId", "edsr1_" + hash);
        ctx.put("replayCandidateVersion", "edsr1");
        ctx.put("replayCandidateStatus", ocoCapable
                ? "CANDIDATE_PLAN_AVAILABLE_NOT_LIVE"
                : "CANDIDATE_PLAN_INCOMPLETE_NOT_LIVE");
    }

    private void putMoneyAliases(Map<String, Object> ctx,
                                 String shortKey,
                                 String priceKey,
                                 String candidateKey,
                                 BigDecimal value) {
        if (value == null) {
            return;
        }
        ctx.put(shortKey, value);
        ctx.put(priceKey, value);
        ctx.put(candidateKey, value);
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

    private boolean sameStrategySymbolLongPosition(BtLiveSignal p, BtStrategy strategy, String symbol) {
        return p.getStrategyId() != null
                && strategy.getId() != null
                && p.getStrategyId().equals(strategy.getId())
                && symbol.equalsIgnoreCase(p.getSymbol())
                && "LONG".equalsIgnoreCase(p.getSide());
    }

    private boolean sameSymbolLongPosition(BtLiveSignal p, String symbol) {
        return symbol.equalsIgnoreCase(p.getSymbol())
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

    private static long remainingCount(int limit, long used) {
        if (limit <= 0) {
            return -1L;
        }
        return Math.max(0L, (long) limit - used);
    }

    private static String dailyCapSnapshot(boolean notifyOnly,
                                           int liveLimit,
                                           long liveUsed,
                                           int shadowLimit,
                                           long shadowUsed,
                                           LocalDateTime countSinceUtc) {
        return "scope=" + (notifyOnly ? "SHADOW_NOTIFY_ONLY" : "LIVE_AUTO_TRADE")
                + ";liveUsed=" + liveUsed
                + ";liveLimit=" + liveLimit
                + ";liveRemaining=" + remainingCount(liveLimit, liveUsed)
                + ";shadowUsed=" + shadowUsed
                + ";shadowLimit=" + shadowLimit
                + ";shadowRemaining=" + remainingCount(shadowLimit, shadowUsed)
                + ";countSinceUtc=" + countSinceUtc;
    }

    private static String maxLossCapRemaining(double maxLossCap, BigDecimal openMaxLoss) {
        if (maxLossCap <= 0) {
            return "unlimited";
        }
        return plain(BigDecimal.valueOf(maxLossCap).subtract(openMaxLoss).max(BigDecimal.ZERO));
    }

    private static String maxLossSnapshot(double maxLossCap,
                                          BigDecimal openMaxLoss,
                                          BigDecimal candidateMaxLoss,
                                          BigDecimal projectedOpenMaxLoss) {
        return "open=" + plain(openMaxLoss)
                + ";cap=" + (maxLossCap > 0 ? BigDecimal.valueOf(maxLossCap).stripTrailingZeros().toPlainString() : "unlimited")
                + ";candidate=" + plain(candidateMaxLoss)
                + ";projected=" + plain(projectedOpenMaxLoss)
                + ";remaining=" + maxLossCapRemaining(maxLossCap, openMaxLoss);
    }

    private static String shortHash(Object... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object part : parts) {
                digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) '|');
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
