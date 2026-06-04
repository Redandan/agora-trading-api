package com.agora.service.trading.execution;

import com.agora.config.properties.TradingGridProperties;
import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class GridExecutionEventDetector implements ExecutionEventDetector {

    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository levelRepository;
    private final OkxTradingService okxTradingService;
    private final TradingGridProperties gridProperties;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExecutionEventService.Draft> detect(LocalDateTime now) {
        LocalDateTime detectedAt = now == null ? LocalDateTime.now() : now;
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        for (BtGrid grid : gridRepository.findByEnabledTrueAndClosedAtIsNull()) {
            try {
                events.addAll(detectGrid(grid, detectedAt));
            } catch (Exception e) {
                log.debug("[ExecutionEvent] grid detector skipped grid id={}: {}",
                        grid.getId(), e.getMessage());
            }
        }
        return events;
    }

    private List<ExecutionEventService.Draft> detectGrid(BtGrid grid, LocalDateTime detectedAt) {
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        BigDecimal markPrice = latestPriceOrNull(grid.getSymbol());
        events.addAll(detectStaleSellFailures(grid, markPrice, detectedAt));
        detectOutOfRange(grid, markPrice, detectedAt, events);
        detectRebalanceLimit(grid, detectedAt, events);
        return events;
    }

    private List<ExecutionEventService.Draft> detectStaleSellFailures(BtGrid grid, BigDecimal markPrice,
                                                                      LocalDateTime detectedAt) {
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        BigDecimal threshold = gridProperties.minSellNotionalUsdt();
        for (BtGridLevel level : levelRepository.findByGridIdAndStatus(grid.getId(), "SELL_FAILED")) {
            int retries = level.getRetryCount() == null ? 0 : level.getRetryCount();
            if (retries < 3) continue;
            BigDecimal referencePrice = level.getPairedSellPrice() != null ? level.getPairedSellPrice() : markPrice;
            BigDecimal notional = estimateNotional(level, referencePrice);
            boolean dust = notional != null && notional.compareTo(threshold) < 0;
            String fingerprint = ExecutionEventService.fingerprint(
                    ExecutionEventSource.GRID,
                    ExecutionEventType.GRID_SELL_FAILED_STALE,
                    grid.getSymbol(),
                    grid.getId(),
                    "level=" + level.getLevelIndex() + "|retry=3|max|class=" + (dust ? "dust" : "material"));

            events.add(new ExecutionEventService.Draft(
                    ExecutionEventSource.GRID,
                    ExecutionEventType.GRID_SELL_FAILED_STALE,
                    dust ? ExecutionEventSeverity.WATCH : ExecutionEventSeverity.ACTIONABLE,
                    ExecutionRecommendation.REVIEW_ONLY,
                    ExecutionActionBoundary.READ_ONLY,
                    grid.getSymbol(),
                    null,
                    grid.getId(),
                    "grid",
                    "Grid SELL_FAILED level is stale",
                    "Grid #" + grid.getId() + " level " + level.getLevelIndex()
                            + " is SELL_FAILED with retry=3/3. Review dust/materiality before any manual sell or grid resize.",
                    evidenceJson(grid, level, markPrice, notional, threshold, "SELL_FAILED_STALE"),
                    fingerprint,
                    detectedAt,
                    detectedAt.plusHours(6)
            ));
        }
        return events;
    }

    private void detectOutOfRange(BtGrid grid, BigDecimal markPrice, LocalDateTime detectedAt,
                                  List<ExecutionEventService.Draft> events) {
        if (markPrice == null || grid.getPriceLower() == null || grid.getPriceUpper() == null) return;
        boolean below = markPrice.compareTo(grid.getPriceLower()) < 0;
        boolean above = markPrice.compareTo(grid.getPriceUpper()) > 0;
        if (!below && !above) return;

        BigDecimal boundary = below ? grid.getPriceLower() : grid.getPriceUpper();
        BigDecimal distancePct = markPrice.subtract(boundary).abs()
                .divide(boundary, 6, RoundingMode.HALF_UP);
        double trigger = grid.getRebalanceTriggerPct() == null ? 0.015 : grid.getRebalanceTriggerPct();
        boolean triggerReached = distancePct.compareTo(BigDecimal.valueOf(trigger)) >= 0;
        String fingerprint = ExecutionEventService.fingerprint(
                ExecutionEventSource.GRID,
                ExecutionEventType.GRID_OUT_OF_RANGE,
                grid.getSymbol(),
                grid.getId(),
                below ? "below" : "above");

        events.add(new ExecutionEventService.Draft(
                ExecutionEventSource.GRID,
                ExecutionEventType.GRID_OUT_OF_RANGE,
                triggerReached ? ExecutionEventSeverity.ACTIONABLE : ExecutionEventSeverity.WATCH,
                ExecutionRecommendation.REVIEW_ONLY,
                ExecutionActionBoundary.READ_ONLY,
                grid.getSymbol(),
                null,
                grid.getId(),
                "grid",
                "Grid price is outside configured range",
                "Grid #" + grid.getId() + " mark price is " + (below ? "below" : "above")
                        + " range. Review range/rebalance status before changing grid capital.",
                evidenceJson(grid, null, markPrice, null, gridProperties.minSellNotionalUsdt(), "OUT_OF_RANGE"),
                fingerprint,
                detectedAt,
                detectedAt.plusHours(2)
        ));
    }

    private void detectRebalanceLimit(BtGrid grid, LocalDateTime detectedAt, List<ExecutionEventService.Draft> events) {
        if (!Boolean.TRUE.equals(grid.getAutoRebalance())) return;
        int count = grid.getRebalanceCount() == null ? 0 : grid.getRebalanceCount();
        int max = grid.getMaxRebalanceCount() == null ? 5 : grid.getMaxRebalanceCount();
        if (count < Math.max(0, max - 1)) return;
        boolean exhausted = count >= max;
        String fingerprint = ExecutionEventService.fingerprint(
                ExecutionEventSource.GRID,
                ExecutionEventType.GRID_REBALANCE_LIMIT,
                grid.getSymbol(),
                grid.getId(),
                "count=" + count + "|max=" + max);

        events.add(new ExecutionEventService.Draft(
                ExecutionEventSource.GRID,
                ExecutionEventType.GRID_REBALANCE_LIMIT,
                exhausted ? ExecutionEventSeverity.ACTIONABLE : ExecutionEventSeverity.WATCH,
                ExecutionRecommendation.REVIEW_ONLY,
                ExecutionActionBoundary.READ_ONLY,
                grid.getSymbol(),
                null,
                grid.getId(),
                "grid",
                exhausted ? "Grid auto-rebalance limit reached" : "Grid auto-rebalance limit is near",
                "Grid #" + grid.getId() + " rebalance count is " + count + "/" + max
                        + ". Review market regime before allowing additional range changes.",
                evidenceJson(grid, null, null, null, gridProperties.minSellNotionalUsdt(), "REBALANCE_LIMIT"),
                fingerprint,
                detectedAt,
                detectedAt.plusHours(6)
        ));
    }

    private String evidenceJson(BtGrid grid, BtGridLevel level, BigDecimal markPrice,
                                BigDecimal notional, BigDecimal threshold, String reason) {
        try {
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("reason", reason);
            evidence.put("gridId", grid.getId());
            evidence.put("symbol", grid.getSymbol());
            evidence.put("priceLower", grid.getPriceLower());
            evidence.put("priceUpper", grid.getPriceUpper());
            evidence.put("markPrice", markPrice);
            evidence.put("autoRebalance", grid.getAutoRebalance());
            evidence.put("rebalanceCount", grid.getRebalanceCount());
            evidence.put("maxRebalanceCount", grid.getMaxRebalanceCount());
            evidence.put("levelIndex", level == null ? null : level.getLevelIndex());
            evidence.put("levelStatus", level == null ? null : level.getStatus());
            evidence.put("filledQty", level == null ? null : level.getFilledQty());
            evidence.put("pairedSellPrice", level == null ? null : level.getPairedSellPrice());
            evidence.put("retryCount", level == null ? null : level.getRetryCount());
            evidence.put("estNotional", notional);
            evidence.put("minSellNotionalUsdt", threshold);
            evidence.put("operatorAction",
                    "REVIEW ONLY; use grid/OCO/position drill-down before any manual sell, grid resize, or capital increase");
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal latestPriceOrNull(String symbol) {
        try {
            return symbol == null ? null : okxTradingService.getLastPrice(symbol);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal estimateNotional(BtGridLevel level, BigDecimal referencePrice) {
        if (level == null || level.getFilledQty() == null || referencePrice == null) return null;
        return level.getFilledQty().multiply(referencePrice).setScale(8, RoundingMode.HALF_UP);
    }
}
