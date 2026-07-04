package com.agora.mcp;

import com.agora.model.TgNotificationLog;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.service.TelegramService;
import com.agora.service.TgTradingNotificationClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MetaControlMcpToolsTest {

    @Test
    void tgNotificationHistoryFrequencySummaryUsesSameFiltersAsShownRows() {
        TgNotificationLogRepository repo = mock(TgNotificationLogRepository.class);
        MetaControlMcpTools tools = newTools(repo, mock(TelegramService.class));
        TgNotificationLog muted = log(
                "【市場背景】BTCUSDT 24h: 觀察\n"
                        + "訊號=市場訊號 6；交易提醒 0；來源路由 2\n"
                        + "用途=風險背景，不是買賣指令；詳情=市場明細/MCP。",
                "MUTED_MARKET",
                "MarketSignalRiskSummary");
        when(repo.search(any(LocalDateTime.class), isNull(), eq("MUTED_MARKET"), isNull(), isNull(),
                any(Pageable.class))).thenReturn(List.of(muted));
        when(repo.countBySourceAndLevel(any(LocalDateTime.class), isNull(), eq("MUTED_MARKET"), isNull(), isNull()))
                .thenReturn(List.<Object[]>of(new Object[]{"MarketSignalRiskSummary", "MUTED_MARKET", 1L}));

        String output = tools.getTgNotificationHistory(8, null, "MUTED_MARKET", null, 50);

        assertThat(output)
                .contains("MarketSignalRiskSummary             [MUTED_MARKET] × 1")
                .doesNotContain("system                              [INFO]");
    }

    @Test
    void manualMarketSignalRiskCardReportsAuditOnlyInsteadOfSent() {
        TgNotificationLogRepository repo = mock(TgNotificationLogRepository.class);
        TelegramService telegramService = mock(TelegramService.class);
        MetaControlMcpTools tools = newTools(repo, telegramService);
        when(repo.search(any(LocalDateTime.class), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class))).thenReturn(List.of(marketSignalLog()));

        String output = tools.sendMarketSignalRiskCard(24, "BTCUSDT", false);

        assertThat(output)
                .startsWith("AUDIT_ONLY:")
                .contains("source=MarketSignalRiskSummary")
                .contains("MUTED_MARKET")
                .doesNotStartWith("SENT:");
        verify(telegramService).sendChannelMessageWithKeyboard(
                anyString(), eq(false), any(), eq("MarketSignalRiskSummary"), eq("INFO"));
    }

    @Test
    void scheduledMarketSignalRiskCardKeepsFingerprintDedupAfterAuditOnlySend() {
        TgNotificationLogRepository repo = mock(TgNotificationLogRepository.class);
        TelegramService telegramService = mock(TelegramService.class);
        MetaControlMcpTools tools = newTools(repo, telegramService);
        when(repo.search(any(LocalDateTime.class), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class))).thenReturn(List.of(marketSignalLog()));

        String first = tools.sendScheduledMarketSignalRiskCard(24, "BTCUSDT", 1, 1, true, false);
        String second = tools.sendScheduledMarketSignalRiskCard(24, "BTCUSDT", 1, 1, true, false);

        assertThat(first).isEqualTo("AUDIT_ONLY");
        assertThat(second).startsWith("SKIPPED: repeated market-signal risk fingerprint=");
        verify(telegramService, times(1)).sendChannelMessageWithKeyboard(
                anyString(), eq(false), any(), eq("MarketSignalRiskSummary"), eq("INFO"));
    }

    private static MetaControlMcpTools newTools(TgNotificationLogRepository repo,
                                                TelegramService telegramService) {
        return new MetaControlMcpTools(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                telegramService,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                repo,
                new TgTradingNotificationClassifier(),
                null,
                null,
                new ObjectMapper());
    }

    private static TgNotificationLog marketSignalLog() {
        return log(
                "【市場背景】BTCUSDT: 軋空觀察\n"
                        + "觸發值=-3.5; 用途=觀察，不是買賣指令；詳情=市場背景/MCP。",
                "INFO",
                "system");
    }

    private static TgNotificationLog log(String message, String level, String source) {
        return new TgNotificationLog(message, level, source, "BTCUSDT", null, false);
    }
}
