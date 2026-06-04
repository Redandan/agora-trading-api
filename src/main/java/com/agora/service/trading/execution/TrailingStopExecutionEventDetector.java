package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TrailingStopExecutionEventDetector implements ExecutionEventDetector {

    private static final BigDecimal BREAKEVEN_TRIGGER_ATR_MULT = new BigDecimal("0.5");
    private static final BigDecimal BREAKEVEN_FEE_BUFFER = new BigDecimal("0.001");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;

    @Override
    public List<ExecutionEventService.Draft> detect(LocalDateTime now) {
        LocalDateTime detectedAt = now == null ? LocalDateTime.now() : now;
        List<ExecutionEventService.Draft> events = new ArrayList<>();
        for (BtLiveSignal pos : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull()) {
            try {
                detectPosition(pos, detectedAt).forEach(events::add);
            } catch (Exception e) {
                log.debug("[ExecutionEvent] trailing detector skipped position id={}: {}",
                        pos.getId(), e.getMessage());
            }
        }
        return events;
    }

    private List<ExecutionEventService.Draft> detectPosition(BtLiveSignal pos, LocalDateTime detectedAt) {
        if (pos.getActualEntryPrice() == null || pos.getTrailingAtr() == null) return List.of();
        if (pos.getSuggestedSl() == null || pos.getSuggestedTp() == null) return List.of();
        if (!"ENTERED".equalsIgnoreCase(pos.getTrailingState() == null ? "ENTERED" : pos.getTrailingState())) {
            return List.of();
        }

        BigDecimal current = okxTradingService.getLastPrice(pos.getSymbol());
        if (current == null || current.signum() <= 0) return List.of();

        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal trigger = breakevenTrigger(pos, isLong);
        boolean reached = isLong ? current.compareTo(trigger) >= 0 : current.compareTo(trigger) <= 0;
        if (!reached) return List.of();

        boolean trailingEnabled = isTrailingEnabled(pos.getStrategyId());
        ExecutionRecommendation recommendation = trailingEnabled
                ? ExecutionRecommendation.RISK_REDUCING_ONLY
                : ExecutionRecommendation.SUGGEST_ENABLE;
        ExecutionActionBoundary boundary = trailingEnabled
                ? ExecutionActionBoundary.RISK_REDUCING_ONLY
                : ExecutionActionBoundary.READ_ONLY;
        BigDecimal suggestedBreakevenSl = isLong
                ? pos.getActualEntryPrice().multiply(BigDecimal.ONE.add(BREAKEVEN_FEE_BUFFER))
                : pos.getActualEntryPrice().multiply(BigDecimal.ONE.subtract(BREAKEVEN_FEE_BUFFER));

        String title = trailingEnabled
                ? "Breakeven protection is eligible"
                : "Breakeven protection is eligible but trailing is disabled";
        String summary = trailingEnabled
                ? "Position has reached the breakeven trigger. Review risk-reducing OCO preview before any action."
                : "Position has reached the breakeven trigger. Consider enabling trailing/breakeven automation after preview.";

        String fingerprint = ExecutionEventService.fingerprint(
                ExecutionEventSource.TRAILING_STOP,
                ExecutionEventType.BREAKEVEN_ELIGIBLE,
                pos.getSymbol(),
                pos.getId(),
                "state=ENTERED");

        return List.of(new ExecutionEventService.Draft(
                ExecutionEventSource.TRAILING_STOP,
                ExecutionEventType.BREAKEVEN_ELIGIBLE,
                ExecutionEventSeverity.ACTIONABLE,
                recommendation,
                boundary,
                pos.getSymbol(),
                pos.getId(),
                pos.getStrategyId(),
                pos.getIntervalCode(),
                title,
                summary,
                evidenceJson(pos, current, trigger, suggestedBreakevenSl, trailingEnabled),
                fingerprint,
                detectedAt,
                detectedAt.plusMinutes(30)
        ));
    }

    private BigDecimal breakevenTrigger(BtLiveSignal pos, boolean isLong) {
        BigDecimal offset = pos.getTrailingAtr().multiply(BREAKEVEN_TRIGGER_ATR_MULT);
        return isLong
                ? pos.getActualEntryPrice().multiply(BigDecimal.ONE.add(offset))
                : pos.getActualEntryPrice().multiply(BigDecimal.ONE.subtract(offset));
    }

    private boolean isTrailingEnabled(Long strategyId) {
        if (strategyId == null) return false;
        return strategyRepository.findById(strategyId)
                .map(BtStrategy::getConfigJson)
                .map(this::parseConfig)
                .map(config -> Boolean.TRUE.equals(config.get("trailingStopEnabled")))
                .orElse(false);
    }

    private Map<String, Object> parseConfig(String configJson) {
        if (configJson == null || configJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(configJson, MAP_TYPE);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String evidenceJson(BtLiveSignal pos, BigDecimal current, BigDecimal trigger,
                                BigDecimal suggestedBreakevenSl, boolean trailingEnabled) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "positionId", pos.getId(),
                    "side", pos.getSide() == null ? "LONG" : pos.getSide(),
                    "entry", pos.getActualEntryPrice(),
                    "current", current,
                    "trigger", trigger,
                    "currentSl", pos.getSuggestedSl(),
                    "tp", pos.getSuggestedTp(),
                    "suggestedBreakevenSl", suggestedBreakevenSl,
                    "trailingStopEnabled", trailingEnabled
            ));
        } catch (Exception e) {
            return null;
        }
    }
}
