package com.agora.infra.bot.impl;

import com.agora.mcp.MetaControlMcpTools;
import com.agora.mcp.PositionMcpTools;
import com.agora.service.backtest.TradingAnalysisService;
import com.agora.service.trading.TradingManagerService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TradingReportFacadeImplTest {

    private final TradingManagerService tradingManagerService = mock(TradingManagerService.class);
    private final TradingAnalysisService tradingAnalysisService = mock(TradingAnalysisService.class);
    private final MetaControlMcpTools metaControlMcpTools = mock(MetaControlMcpTools.class);
    private final PositionMcpTools positionMcpTools = mock(PositionMcpTools.class);
    private final TradingReportFacadeImpl facade = new TradingReportFacadeImpl(
            tradingManagerService,
            tradingAnalysisService,
            metaControlMcpTools,
            positionMcpTools);

    @Test
    void marketSignalButtonsRouteMarketDetailsToMetaControlDrillDown() {
        when(metaControlMcpTools.getMarketSignalRiskDrillDown(24, "BTCUSDT", "market_details", 12))
                .thenReturn("market rows");

        String result = facade.marketSignalRiskDrillDown(24, "BTCUSDT", "market_details", 12);

        assertEquals("market rows", result);
        verify(positionMcpTools, never()).getOpenPositions();
        verify(positionMcpTools, never()).getOcoHealth();
        verify(positionMcpTools, never()).getTrailingStopStatus();
    }

    @Test
    void marketSignalButtonsRoutePositionOcoAndTrailingToReadOnlyPositionTools() {
        when(positionMcpTools.getOpenPositions()).thenReturn("positions");
        when(positionMcpTools.getOcoHealth()).thenReturn("oco");
        when(positionMcpTools.getTrailingStopStatus()).thenReturn("trailing");

        assertEquals("positions", facade.marketSignalRiskDrillDown(24, "BTCUSDT", "current_position", 12));
        assertEquals("oco", facade.marketSignalRiskDrillDown(24, "BTCUSDT", "oco_status", 12));
        assertEquals("trailing", facade.marketSignalRiskDrillDown(24, "BTCUSDT", "trailing_status", 12));

        verify(metaControlMcpTools, never()).getMarketSignalRiskDrillDown(24, "BTCUSDT", "current_position", 12);
        verify(metaControlMcpTools, never()).getMarketSignalRiskDrillDown(24, "BTCUSDT", "oco_status", 12);
        verify(metaControlMcpTools, never()).getMarketSignalRiskDrillDown(24, "BTCUSDT", "trailing_status", 12);
    }
}
