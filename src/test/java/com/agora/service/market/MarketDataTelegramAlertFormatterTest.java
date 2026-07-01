package com.agora.service.market;

import com.agora.config.properties.KlineDivergenceProperties;
import com.agora.infra.notification.NotificationPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class MarketDataTelegramAlertFormatterTest {

    @Test
    void klineDivergenceAlertUsesChineseDataSourceLabels() {
        NotificationPort notificationPort = mock(NotificationPort.class);
        KlineDivergenceAlerter alerter = new KlineDivergenceAlerter(
                notificationPort,
                new KlineDivergenceProperties(true, 3, 0.5, 1.0, 60, true, 1.0, 1000.0)
        );

        String sample = alerter.formatSample(
                "BTCUSDT",
                "1h",
                LocalDateTime.of(2026, 7, 1, 12, 0),
                new BigDecimal("62000"),
                new BigDecimal("61800"),
                0.324,
                new BigDecimal("123.45"),
                new BigDecimal("120")
        );
        alerter.sendBatchAlert("WARN", List.of(sample), 0, List.of());

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationPort).broadcast(messageCaptor.capture(), eq(true));
        String message = messageCaptor.getValue();

        assertThat(message)
                .contains("【資料來源告警】")
                .contains("K 線跨源偏差：警告(WARN)")
                .contains("標的：BTCUSDT 1h")
                .contains("收盤價：Binance 62000.00 / OKX 61800.00")
                .contains("成交量：Binance 123.45 / OKX 120.00")
                .contains("處置：檢查資料來源與流動性，不是買賣指令")
                .contains("標籤：MARKET_DATA / WATCH")
                .doesNotContain("bin=")
                .doesNotContain("okx=")
                .doesNotContain("binVol=")
                .doesNotContain("okxVol=")
                .doesNotContain("Binance vs OKX close");
    }

    @Test
    void wsAlertsUseChineseLabelsAndTradingBoundary() {
        String startup = MarketDataTelegramAlertFormatter.wsStartupFailure(
                "SPOT", "BTCUSDT", "1m", "handshake <failed>");
        String stopped = MarketDataTelegramAlertFormatter.wsStopped(
                "okx", "SPOT", "BTCUSDT", "1m", "max reconnect attempts reached");

        assertReadableWsAlert(startup)
                .contains("K 線資料流啟動訂閱失敗")
                .contains("來源：AUTO_SUBSCRIBE")
                .contains("原因：handshake <failed>");
        assertReadableWsAlert(stopped)
                .contains("OKX K 線資料流停止")
                .contains("來源：OKX")
                .contains("原因：max reconnect attempts reached");
    }

    private static org.assertj.core.api.AbstractStringAssert<?> assertReadableWsAlert(String message) {
        return assertThat(message)
                .contains("【資料來源告警】")
                .contains("標的：BTCUSDT 1m")
                .contains("市場：SPOT")
                .contains("影響：該來源 K 線可能暫停更新")
                .contains("處置：檢查 WS 狀態/重連，不是交易指令")
                .contains("標籤：MARKET_DATA / CHECK")
                .doesNotContain("marketType=")
                .doesNotContain("symbol=")
                .doesNotContain("interval=")
                .doesNotContain("reason=");
    }
}
