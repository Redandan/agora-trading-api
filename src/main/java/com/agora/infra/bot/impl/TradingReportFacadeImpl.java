package com.agora.infra.bot.impl;

import com.agora.infra.bot.TradingReportFacade;
import com.agora.mcp.MetaControlMcpTools;
import com.agora.mcp.PositionMcpTools;
import com.agora.service.backtest.TradingAnalysisService;
import com.agora.service.trading.TradingManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
class TradingReportFacadeImpl implements TradingReportFacade {

    private final TradingManagerService tradingManagerService;
    private final TradingAnalysisService tradingAnalysisService;
    private final MetaControlMcpTools metaControlMcpTools;
    private final PositionMcpTools positionMcpTools;

    @Override
    public String currentSituation() {
        return tradingManagerService.reportCurrentSituation();
    }

    @Override
    public String marketAnalysis() {
        return tradingAnalysisService.analyze();
    }

    @Override
    public String weeklyReport() {
        return tradingManagerService.reportWeekly();
    }

    @Override
    public String marketSignalRiskDrillDown(Integer hours, String symbol, String detailType, Integer limit) {
        String type = normalizeDetailType(detailType);
        return switch (type) {
            case "current_position" -> positionMcpTools.getOpenPositions();
            case "oco_status" -> positionMcpTools.getOcoHealth();
            case "trailing_status" -> positionMcpTools.getTrailingStopStatus();
            default -> metaControlMcpTools.getMarketSignalRiskDrillDown(hours, symbol, type, limit);
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
}
