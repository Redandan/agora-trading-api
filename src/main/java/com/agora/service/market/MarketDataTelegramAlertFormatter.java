package com.agora.service.market;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public final class MarketDataTelegramAlertFormatter {

    private MarketDataTelegramAlertFormatter() {
    }

    public static String klineDivergenceSample(String symbol, String intervalCode, LocalDateTime openTime,
                                               BigDecimal binClose, BigDecimal okxClose, double diff,
                                               BigDecimal binVol, BigDecimal okxVol) {
        return String.format(
                "標的：%s %s；時間：%s；收盤價：Binance %s / OKX %s；差異：%.3f%%；成交量：Binance %s / OKX %s",
                safe(symbol),
                safe(intervalCode),
                openTime == null ? "N/A" : openTime,
                decimal(binClose),
                decimal(okxClose),
                diff,
                decimal(binVol),
                decimal(okxVol)
        );
    }

    public static String wsStartupFailure(String marketType, String symbol, String intervalCode, String reason) {
        return wsAlert("⚠️", "K 線資料流啟動訂閱失敗", "AUTO_SUBSCRIBE", marketType, symbol, intervalCode, reason);
    }

    public static String wsStopped(String provider, String marketType, String symbol, String intervalCode, String reason) {
        return wsAlert("🚫", providerDisplay(provider) + " K 線資料流停止",
                providerDisplay(provider), marketType, symbol, intervalCode, reason);
    }

    private static String wsAlert(String icon, String title, String provider, String marketType,
                                  String symbol, String intervalCode, String reason) {
        return String.join("\n",
                "【資料來源告警】",
                icon + " " + title,
                "標的：" + safe(symbol) + " " + safe(intervalCode)
                        + "；市場：" + safe(marketType)
                        + "；來源：" + safe(provider),
                "原因：" + safe(reason),
                "影響：該來源 K 線可能暫停更新",
                "處置：檢查 WS 狀態/重連，不是交易指令",
                "標籤：MARKET_DATA / CHECK"
        );
    }

    private static String providerDisplay(String provider) {
        if (provider == null || provider.isBlank()) {
            return "資料來源";
        }
        if ("binance".equalsIgnoreCase(provider)) {
            return "Binance";
        }
        if ("okx".equalsIgnoreCase(provider)) {
            return "OKX";
        }
        return provider.trim();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "N/A" : String.format("%.2f", value);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "N/A" : value.trim();
    }
}
