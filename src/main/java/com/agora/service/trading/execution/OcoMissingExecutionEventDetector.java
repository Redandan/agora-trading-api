package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.trading.OcoManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class OcoMissingExecutionEventDetector implements ExecutionEventDetector {

    private final BtLiveSignalRepository liveSignalRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExecutionEventService.Draft> detect(LocalDateTime now) {
        LocalDateTime detectedAt = now == null ? LocalDateTime.now() : now;
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        for (BtLiveSignal pos : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull()) {
            if (isSoftExitNoHardSl(pos)) {
                continue;
            }
            try {
                events.add(buildEvent(pos, detectedAt));
            } catch (Exception e) {
                log.debug("[ExecutionEvent] OCO missing detector skipped position id={}: {}",
                        pos.getId(), e.getMessage());
            }
        }
        return events;
    }

    private ExecutionEventService.Draft buildEvent(BtLiveSignal pos, LocalDateTime detectedAt) {
        String fingerprint = ExecutionEventService.fingerprint(
                ExecutionEventSource.GUARDIAN,
                ExecutionEventType.OCO_MISSING,
                pos.getSymbol(),
                pos.getId(),
                "open-auto-traded-missing-oco");

        return new ExecutionEventService.Draft(
                ExecutionEventSource.GUARDIAN,
                ExecutionEventType.OCO_MISSING,
                ExecutionEventSeverity.CRITICAL,
                ExecutionRecommendation.REVIEW_ONLY,
                ExecutionActionBoundary.READ_ONLY,
                pos.getSymbol(),
                pos.getId(),
                pos.getStrategyId(),
                pos.getIntervalCode(),
                "Open position has no OCO protection",
                "Auto-traded open position has no OCO order id. Review OCO poller/retryOco status before taking action.",
                evidenceJson(pos),
                fingerprint,
                detectedAt,
                detectedAt.plusMinutes(15)
        );
    }

    private String evidenceJson(BtLiveSignal pos) {
        try {
            LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("positionId", pos.getId());
            evidence.put("symbol", nullToUnknown(pos.getSymbol()));
            evidence.put("side", nullToDefault(pos.getSide(), "LONG"));
            evidence.put("strategyId", pos.getStrategyId());
            evidence.put("intervalCode", nullToUnknown(pos.getIntervalCode()));
            evidence.put("entry", pos.getActualEntryPrice());
            evidence.put("qty", pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty());
            evidence.put("sl", pos.getSuggestedSl());
            evidence.put("tp", pos.getSuggestedTp());
            evidence.put("ocoOrderListId", "NULL");
            evidence.put("operatorAction",
                    "REVIEW OCO poller/retryOco; do not place duplicate manual OCO without checking exchange state");
            return objectMapper.writeValueAsString(evidence);
        } catch (Exception e) {
            return null;
        }
    }

    private static String nullToUnknown(String value) {
        return nullToDefault(value, "UNKNOWN");
    }

    private static String nullToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean isSoftExitNoHardSl(BtLiveSignal pos) {
        return pos != null
                && pos.getFilterReason() != null
                && pos.getFilterReason().startsWith(OcoManagementService.SOFT_EXIT_NO_HARD_SL_MARKER);
    }
}
