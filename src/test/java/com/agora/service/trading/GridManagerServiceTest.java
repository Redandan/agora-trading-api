package com.agora.service.trading;

import com.agora.config.properties.TradingGridProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtGrid;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.service.meta.DecisionAuditWriter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GridManagerServiceTest {

    private final BtGridRepository gridRepository = mock(BtGridRepository.class);
    private final BtGridLevelRepository levelRepository = mock(BtGridLevelRepository.class);
    private final OkxTradingService okxTradingService = mock(OkxTradingService.class);
    private final OkxEarnService okxEarnService = mock(OkxEarnService.class);
    private final GeminiMarketHintRepository hintRepository = mock(GeminiMarketHintRepository.class);
    private final NotificationPort notificationPort = mock(NotificationPort.class);
    private final DecisionAuditWriter auditWriter = mock(DecisionAuditWriter.class);
    private final TradingGridProperties props = new TradingGridProperties(
            true, false, 24, 300000, true, new BigDecimal("5.0"));

    @Test
    void manualPauseSkipsGridWithoutFetchingPriceOrAutoResuming() {
        BtGrid grid = activeGrid();
        LocalDateTime pausedAt = LocalDateTime.of(2026, 7, 4, 20, 0);
        grid.setPausedAt(pausedAt);
        grid.setPausedReason("manual retirement for LOCAL_TRADINGVIEW only");
        when(gridRepository.findByEnabledTrueAndClosedAtIsNull()).thenReturn(List.of(grid));

        service().checkAllGrids();

        verify(okxTradingService, never()).getLastPrice("BTCUSDT");
        verify(gridRepository, never()).save(grid);
        assertThat(grid.getPausedAt()).isEqualTo(pausedAt);
        assertThat(grid.getPausedReason()).isEqualTo("manual retirement for LOCAL_TRADINGVIEW only");
    }

    private GridManagerService service() {
        return new GridManagerService(
                gridRepository,
                levelRepository,
                okxTradingService,
                okxEarnService,
                hintRepository,
                notificationPort,
                props,
                auditWriter);
    }

    private static BtGrid activeGrid() {
        BtGrid grid = new BtGrid();
        grid.setId(10L);
        grid.setSymbol("BTCUSDT");
        grid.setPriceLower(new BigDecimal("52301.13"));
        grid.setPriceUpper(new BigDecimal("66565.07"));
        grid.setGridCount(4);
        grid.setPerLevelUsdt(new BigDecimal("5.00"));
        grid.setEnabled(true);
        grid.setHintGated(true);
        grid.setRegimeWhitelist("SIDEWAYS,VOLATILE,RECOVERY");
        grid.setTotalRealizedPnl(BigDecimal.ZERO);
        grid.setClosedPairCount(0);
        return grid;
    }
}
