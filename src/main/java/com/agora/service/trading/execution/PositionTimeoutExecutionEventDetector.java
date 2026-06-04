package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PositionTimeoutExecutionEventDetector implements ExecutionEventDetector {

    static final long POSITION_TIMEOUT_DAYS = 5;

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExecutionEventService.Draft> detect(LocalDateTime now) {
        LocalDateTime detectedAt = now == null ? LocalDateTime.now(ZoneOffset.UTC) : now;
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        for (BtLiveSignal pos : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
            try {
                if (pos.getCreatedAt() == null) continue;
                long ageHours = ChronoUnit.HOURS.between(pos.getCreatedAt(), detectedAt);
                if (ageHours < POSITION_TIMEOUT_DAYS * 24) continue;
                events.add(buildEvent(pos, detectedAt, ageHours));
            } catch (Exception e) {
                log.debug("[ExecutionEvent] position timeout detector skipped position id={}: {}",
                        pos.getId(), e.getMessage());
            }
        }
        return events;
    }

    private ExecutionEventService.Draft buildEvent(BtLiveSignal pos, LocalDateTime detectedAt, long ageHours) {
        String fingerprint = ExecutionEventService.fingerprint(
                ExecutionEventSource.POSITION_TIMEOUT,
                ExecutionEventType.POSITION_TIMEOUT,
                pos.getSymbol(),
                pos.getId(),
                "age-days=" + (ageHours / 24));

        return new ExecutionEventService.Draft(
                ExecutionEventSource.POSITION_TIMEOUT,
                ExecutionEventType.POSITION_TIMEOUT,
                ageHours >= 7 * 24 ? ExecutionEventSeverity.ACTIONABLE : ExecutionEventSeverity.WATCH,
                ExecutionRecommendation.REVIEW_ONLY,
                ExecutionActionBoundary.READ_ONLY,
                pos.getSymbol(),
                pos.getId(),
                pos.getStrategyId(),
                pos.getIntervalCode(),
                "Position has exceeded aging review threshold",
                "Open auto-traded position has been held for " + (ageHours / 24)
                        + " days. Review OCO, TP/SL distance, and current risk before any action.",
                evidenceJson(pos, ageHours),
                fingerprint,
                detectedAt,
                detectedAt.plusHours(6)
        );
    }

    private String evidenceJson(BtLiveSignal pos, long ageHours) {
        try {
            BigDecimal current = latestPriceOrNull(pos.getSymbol());
            BigDecimal refEntry = pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
            BigDecimal qty = pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty();

            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("positionId", pos.getId());
            evidence.put("symbol", nullToUnknown(pos.getSymbol()));
            evidence.put("side", nullToDefault(pos.getSide(), "LONG"));
            evidence.put("strategyId", pos.getStrategyId());
            evidence.put("intervalCode", nullToUnknown(pos.getIntervalCode()));
            evidence.put("ageHours", ageHours);
            evidence.put("ageDays", ageHours / 24);
            evidence.put("entry", refEntry);
            evidence.put("current", current);
            evidence.put("qty", qty);
            evidence.put("unrealizedPnlUsdt", unrealizedPnl(pos, refEntry, current, qty));
            evidence.put("tp", pos.getSuggestedTp());
            evidence.put("sl", pos.getSuggestedSl());
            evidence.put("ocoOrderListId", pos.getOcoOrderListId());
            evidence.put("operatorAction",
                    "REVIEW ONLY; use position/OCO drill-down before any OCO, trailing, or exit change");
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

    private BigDecimal unrealizedPnl(BtLiveSignal pos, BigDecimal entry, BigDecimal current, BigDecimal qty) {
        if (entry == null || current == null || qty == null) return null;
        boolean isShort = "SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal diff = isShort ? entry.subtract(current) : current.subtract(entry);
        return diff.multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private static String nullToUnknown(String value) {
        return nullToDefault(value, "UNKNOWN");
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
