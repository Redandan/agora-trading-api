package com.agora.scheduler.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.PositionMutationGuard;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * #439 — Trailing-stop scheduler.
 *
 * <p>Per-position state machine:
 * <pre>
 * ENTERED          (initial; OCO already at entry-time SL/TP)
 *   ↓ price ≥ entry + 0.5 × ATR_1h
 * BREAKEVEN_LOCKED (SL → entry × 1.001, cover taker fees)
 *   ↓ price ≥ entry + 1.0 × ATR_1h
 * TRAILING         (SL → trailing_high × (1 - 1.0 × ATR_1h); only ratchets up)
 * </pre>
 *
 * <p>SHORT side mirrors: trail tracks lowest-low + 1×ATR above.
 *
 * <p>Strategy opt-in via config flag {@code trailingStopEnabled=true}. Default off.
 *
 * <p>Global scheduler opt-in (env {@code trailing-stop.enabled=false}, default false)
 * keeps split-service deploys from automatically writing trailing state or touching OCO.
 *
 * <p>Global dry-run (env {@code trailing-stop.dry-run=true}, default true): logs
 * what it would do but does NOT call modifyOco. Run for ≥ 1 week observing
 * dry-run behavior before enabling per-strategy real OCO updates.
 *
 * <p>Tick interval: 30s. modifyOco only fires when SL would meaningfully change
 * (≥ 0.05% delta). Failures are logged + alerted, not propagated.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trailing-stop.enabled", havingValue = "true", matchIfMissing = false)
public class TrailingStopScheduler {

    private static final BigDecimal BREAKEVEN_TRIGGER_ATR_MULT = new BigDecimal("0.5");
    private static final BigDecimal TRAILING_TRIGGER_ATR_MULT  = new BigDecimal("1.0");
    private static final BigDecimal TRAILING_DISTANCE_ATR_MULT = new BigDecimal("1.0");
    private static final BigDecimal BREAKEVEN_FEE_BUFFER       = new BigDecimal("0.001"); // 0.1% taker
    private static final BigDecimal MIN_SL_DELTA_PCT           = new BigDecimal("0.0005"); // 0.05%
    private static final int MODIFY_OCO_MAX_ATTEMPTS           = 3;

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final OkxTradingService okxTradingService;
    private final AiStrategyDiscoveryService discoveryService;
    private final OcoManagementService ocoManagementService;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;

    @Value("${trailing-stop.enabled:false}")
    private boolean schedulerEnabled;

    @Value("${trailing-stop.dry-run:true}")
    private boolean globalDryRun;

    @PostConstruct
    void logConfig() {
        log.info("[TrailingStop] config: enabled={} dryRun={}", schedulerEnabled, globalDryRun);
    }

    @Scheduled(fixedDelay = 30_000L, initialDelay = 60_000L)
    public void tick() {
        if (!schedulerEnabled) return;
        try {
            List<BtLiveSignal> open = liveSignalRepository
                    .findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull();
            if (open.isEmpty()) return;
            for (BtLiveSignal pos : open) {
                try {
                    processPositionGuarded(pos);
                } catch (Exception e) {
                    log.warn("[TrailingStop] position id={} processing failed: {}",
                            pos.getId(), e.getMessage());
                }
            }
        } catch (Throwable t) {
            log.error("[TrailingStop] tick failed: {}", t.getMessage(), t);
        }
    }

    private void processPositionGuarded(BtLiveSignal candidate) {
        if (candidate == null || candidate.getId() == null) return;
        try (PositionMutationGuard.Lease lease = PositionMutationGuard.tryAcquire(
                candidate.getId(), "TRAILING_STOP")) {
            if (!lease.acquired()) return;
            BtLiveSignal fresh = liveSignalRepository.findById(candidate.getId()).orElse(null);
            if (fresh == null || fresh.getExitTime() != null || fresh.getOcoOrderListId() == null) return;
            processPosition(fresh);
        }
    }

    private void processPosition(BtLiveSignal pos) {
        if (BtcBasePositionStatePolicy.isBtcBase(pos)) return;
        if (!isTrailingEnabled(pos.getStrategyId())) return;
        if (pos.getActualEntryPrice() == null) return;
        BigDecimal entry = pos.getActualEntryPrice();
        if (entry.signum() <= 0) return;

        BigDecimal currentPrice;
        try {
            currentPrice = okxTradingService.getLastPrice(pos.getSymbol());
        } catch (Exception e) {
            log.debug("[TrailingStop] price fetch failed for {}: {}", pos.getSymbol(), e.getMessage());
            return;
        }
        if (currentPrice == null || currentPrice.signum() <= 0) return;

        // Snapshot ATR + initial high if first encounter.
        if (pos.getTrailingAtr() == null) {
            BigDecimal atr = fetchAtrFraction(pos.getSymbol(), pos.getIntervalCode());
            if (atr == null || atr.signum() <= 0) return;
            pos.setTrailingAtr(atr);
            pos.setTrailingHigh(currentPrice);
            if (pos.getTrailingState() == null || pos.getTrailingState().isBlank()) {
                pos.setTrailingState("ENTERED");
            }
            liveSignalRepository.save(pos);
            log.info("[TrailingStop] init id={} {} entry={} atr={} high={}",
                    pos.getId(), pos.getSymbol(), entry, atr, currentPrice);
            return;
        }

        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal atr = pos.getTrailingAtr();
        String state = pos.getTrailingState() != null ? pos.getTrailingState() : "ENTERED";
        BigDecimal high = pos.getTrailingHigh() != null ? pos.getTrailingHigh() : currentPrice;

        // Update trailing extreme: LONG tracks high, SHORT tracks low (stored in same column).
        boolean newExtreme = isLong
                ? currentPrice.compareTo(high) > 0
                : currentPrice.compareTo(high) < 0;
        if (newExtreme) {
            pos.setTrailingHigh(currentPrice);
            high = currentPrice;
        }

        BigDecimal targetSl = null;
        String newState = state;

        // Compute trigger thresholds based on entry + ATR.
        BigDecimal breakevenTrigger = isLong
                ? entry.multiply(BigDecimal.ONE.add(atr.multiply(BREAKEVEN_TRIGGER_ATR_MULT)))
                : entry.multiply(BigDecimal.ONE.subtract(atr.multiply(BREAKEVEN_TRIGGER_ATR_MULT)));
        BigDecimal trailingTrigger = isLong
                ? entry.multiply(BigDecimal.ONE.add(atr.multiply(TRAILING_TRIGGER_ATR_MULT)))
                : entry.multiply(BigDecimal.ONE.subtract(atr.multiply(TRAILING_TRIGGER_ATR_MULT)));

        if ("ENTERED".equals(state)) {
            boolean reached = isLong
                    ? currentPrice.compareTo(breakevenTrigger) >= 0
                    : currentPrice.compareTo(breakevenTrigger) <= 0;
            if (reached) {
                newState = "BREAKEVEN_LOCKED";
                BigDecimal breakevenStop = isLong
                        ? entry.multiply(BigDecimal.ONE.add(BREAKEVEN_FEE_BUFFER))
                        : entry.multiply(BigDecimal.ONE.subtract(BREAKEVEN_FEE_BUFFER));
                targetSl = protectiveStop(pos.getSuggestedSl(), breakevenStop, isLong);
            }
        }
        if ("BREAKEVEN_LOCKED".equals(newState)) {
            boolean reached = isLong
                    ? currentPrice.compareTo(trailingTrigger) >= 0
                    : currentPrice.compareTo(trailingTrigger) <= 0;
            if (reached) {
                newState = "TRAILING";
                BigDecimal trailDistance = high.multiply(atr.multiply(TRAILING_DISTANCE_ATR_MULT));
                BigDecimal trailingStop = isLong
                        ? high.subtract(trailDistance)
                        : high.add(trailDistance);
                targetSl = protectiveStop(targetSl, trailingStop, isLong);
            }
        }
        if ("TRAILING".equals(newState) && newExtreme) {
            BigDecimal trailDistance = high.multiply(atr.multiply(TRAILING_DISTANCE_ATR_MULT));
            BigDecimal candidate = isLong
                    ? high.subtract(trailDistance)
                    : high.add(trailDistance);
            // Only ratchet (LONG: SL only moves up; SHORT: SL only moves down).
            BigDecimal currentSl = pos.getSuggestedSl();
            if (currentSl != null) {
            boolean ratchet = isLong
                    ? candidate.compareTo(currentSl) > 0
                    : candidate.compareTo(currentSl) < 0;
                if (ratchet) targetSl = protectiveStop(targetSl, candidate, isLong);
            } else {
                targetSl = protectiveStop(targetSl, candidate, isLong);
            }
        }

        if (newState.equals(state) && targetSl == null) {
            // Just persist new high if changed, no OCO action.
            if (newExtreme) liveSignalRepository.save(pos);
            return;
        }

        // Skip OCO modify if delta below threshold (avoid burning quota on noise).
        BigDecimal currentSl = pos.getSuggestedSl();
        if (targetSl != null && currentSl != null) {
            BigDecimal deltaPct = targetSl.subtract(currentSl).abs()
                    .divide(currentSl, 6, RoundingMode.HALF_UP);
            if (deltaPct.compareTo(MIN_SL_DELTA_PCT) < 0 && newState.equals(state)) {
                if (newExtreme) {
                    liveSignalRepository.save(pos);
                }
                return;
            }
        }

        BigDecimal newTp = pos.getSuggestedTp();  // TP unchanged
        log.info("[TrailingStop] id={} {} state {}→{} price={} sl {}→{} (dryRun={})",
                pos.getId(), pos.getSymbol(), state, newState,
                currentPrice, currentSl, targetSl, globalDryRun);

        if (globalDryRun) {
            // dry-run: persist state + high but do NOT modifyOco
            if (!newState.equals(state)) {
                pos.setTrailingLastTransitionAt(LocalDateTime.now());
            }
            pos.setTrailingState(newState);
            liveSignalRepository.save(pos);
            return;
        }

        try {
            if (targetSl != null && newTp != null) {
                modifyOcoWithRetry(pos, targetSl, newTp);
            }
            if (!newState.equals(state)) {
                pos.setTrailingLastTransitionAt(LocalDateTime.now());
            }
            pos.setTrailingState(newState);
            pos.setSuggestedSl(targetSl);
            liveSignalRepository.save(pos);
            try {
                notificationPort.broadcast(String.format(
                        "🪜 <b>Trailing %s [%s]</b>\nstate %s→%s\nSL %s → %s (price=%s)",
                        pos.getSymbol(), pos.getIntervalCode(),
                        state, newState,
                        currentSl != null ? currentSl.toPlainString() : "n/a",
                        targetSl != null ? targetSl.toPlainString() : "n/a",
                        currentPrice.toPlainString()), true);
            } catch (Exception ignore) {}
        } catch (Exception e) {
            log.error("[TrailingStop] modifyOco failed id={}: {}", pos.getId(), e.getMessage());
            try {
                notificationPort.broadcast(String.format(
                        "⚠️ <b>Trailing modifyOco 失敗 [pos=%d %s]</b>\n%s",
                        pos.getId(), pos.getSymbol(), e.getMessage()), true);
            } catch (Exception ignore) {}
        }
    }

    private BigDecimal protectiveStop(BigDecimal currentStop, BigDecimal candidate, boolean isLong) {
        if (candidate == null) return currentStop;
        if (currentStop == null) return candidate;
        return isLong ? currentStop.max(candidate) : currentStop.min(candidate);
    }

    private void modifyOcoWithRetry(BtLiveSignal pos, BigDecimal targetSl, BigDecimal newTp) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MODIFY_OCO_MAX_ATTEMPTS; attempt++) {
            try {
                ocoManagementService.modifyOco(pos.getId(), targetSl, newTp);
                if (attempt > 1) {
                    log.info("[TrailingStop] modifyOco retry recovered id={} attempt={}/{}",
                            pos.getId(), attempt, MODIFY_OCO_MAX_ATTEMPTS);
                }
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < MODIFY_OCO_MAX_ATTEMPTS) {
                    log.warn("[TrailingStop] modifyOco retry failed id={} attempt={}/{}: {}",
                            pos.getId(), attempt, MODIFY_OCO_MAX_ATTEMPTS, e.getMessage());
                }
            }
        }
        throw last != null ? last : new RuntimeException("modifyOco failed without exception detail");
    }

    private boolean isTrailingEnabled(Long strategyId) {
        if (strategyId == null) return false;
        BtStrategy s = strategyRepository.findById(strategyId).orElse(null);
        if (s == null || s.getConfigJson() == null) {
            return false;
        }
        boolean enabled = false;
        try {
            JsonNode node = objectMapper.readTree(s.getConfigJson());
            JsonNode flag = node.path("trailingStopEnabled");
            enabled = flag.isBoolean() ? flag.asBoolean()
                    : flag.isNumber() ? flag.asInt() != 0
                    : flag.isTextual() && "true".equalsIgnoreCase(flag.asText());
        } catch (Exception e) {
            log.debug("[TrailingStop] strategy {} config parse failed: {}", strategyId, e.getMessage());
        }
        return enabled;
    }

    private BigDecimal fetchAtrFraction(String symbol, String intervalCode) {
        try {
            AiStrategyDiscoveryService.MarketSnapshot snap =
                    discoveryService.buildMarketSnapshot(symbol, intervalCode);
            double atrPct = snap.atrPct();  // percentage form, e.g. 1.18 = 1.18%
            if (atrPct <= 0) return null;
            return BigDecimal.valueOf(atrPct / 100.0)
                    .setScale(6, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.debug("[TrailingStop] ATR fetch failed for {}@{}: {}",
                    symbol, intervalCode, e.getMessage());
            return null;
        }
    }
}
