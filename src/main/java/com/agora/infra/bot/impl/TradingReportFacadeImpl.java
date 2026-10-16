package com.agora.infra.bot.impl;

import com.agora.infra.bot.TradingReportFacade;
import com.agora.mcp.ExecutionSafetyMcpTools;
import com.agora.mcp.OkxNativeGridMcpTools;
import com.agora.mcp.StrategyCatalogMcpTools;
import com.agora.service.advice.BtcOrderAdviceService;
import com.agora.service.advice.BtcOrderAdviceFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
@Slf4j
class TradingReportFacadeImpl implements TradingReportFacade {

    private final ExecutionSafetyMcpTools executionSafetyMcpTools;
    private final StrategyCatalogMcpTools strategyCatalogMcpTools;
    private final BtcOrderAdviceService btcOrderAdvice;
    private final OkxNativeGridMcpTools nativeGrid;

    @Override
    public String currentSituation() {
        return TradingSituationFormatter.format(
                read("positions", executionSafetyMcpTools::getOpenSpotPositions),
                read("executionSafety", executionSafetyMcpTools::getExecutionSafetyStatus),
                read("runtimeCatalog", strategyCatalogMcpTools::getStrategyRuntimeCatalog),
                read("nativeGrid", () -> nativeGrid.getOkxNativeSpotGridStatus(false)));
    }

    @Override
    public String marketAnalysis() {
        return BtcOrderAdviceFormatter.format(btcOrderAdvice.current());
    }

    @Override
    public String weeklyReport() {
        return "Legacy AI/ML weekly trading report retired.\n"
                + strategyCatalogMcpTools.getStrategyRuntimeCatalog();
    }

    @Override
    public String marketSignalRiskDrillDown(Integer hours, String symbol, String detailType, Integer limit) {
        String type = normalizeDetailType(detailType);
        return switch (type) {
            case "current_position" -> executionSafetyMcpTools.getOpenSpotPositions();
            case "oco_status", "trailing_status" -> executionSafetyMcpTools.getExecutionSafetyStatus();
            default -> executionSafetyMcpTools.getExecutionSafetyStatus()
                    + "\n\n"
                    + strategyCatalogMcpTools.getOwner509RuntimeStatus();
        };
    }

    private static String normalizeDetailType(String detailType) {
        if (detailType == null || detailType.isBlank()) {
            return "full_summary";
        }
        String value = detailType.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (value) {
            case "market", "market_details", "details" -> "market_details";
            case "routes", "signal_routes", "routing" -> "signal_routes";
            case "position", "positions", "current_position", "current_positions" -> "current_position";
            case "oco", "oco_status", "oco_health" -> "oco_status";
            case "trailing", "trailing_status", "trailing_stop" -> "trailing_status";
            default -> "full_summary";
        };
    }

    private static String read(String section, Supplier<String> source) {
        try {
            return source.get();
        } catch (Exception e) {
            log.warn("[TradingReport] section={} unavailable errorType={}", section, e.getClass().getSimpleName());
            return "";
        }
    }
}
