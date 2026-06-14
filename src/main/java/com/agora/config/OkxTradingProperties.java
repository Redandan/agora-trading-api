package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OKX 現貨自動交易配置。
 * 對應 application.yml 的 trading.okx.* 區塊。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.okx")
public class OkxTradingProperties {

    /** 主開關，預設關閉。設定好 API Key 並測試無誤後再開啟。 */
    private boolean enabled = false;

    /** OKX API Key */
    private String apiKey = "";

    /** OKX Secret Key（用於 HMAC-SHA256 簽名） */
    private String secretKey = "";

    /** OKX API Passphrase（建立 API Key 時自行設定的密碼） */
    private String passphrase = "";

    /** OKX REST Base URL（不含路徑） */
    private String baseUrl = "https://www.okx.com";

    /** 每次買入金額（USDT） */
    private double tradeAmountUsdt = 100.0;

    /** Position sizing shadow mode: only compute/report suggested size; live order amount is unchanged. */
    private boolean positionSizingShadowEnabled = true;

    /** Position sizing live mode: when true, autoTrade may use the risk-sized amount. Keep false until shadow is reviewed. */
    private boolean positionSizingLiveEnabled = false;

    /** Minimum spot order notional considered by the sizing engine. */
    private double positionSizingMinNotionalUsdt = 50.0;

    /** Hard cap for a single auto-trade notional. */
    private double positionSizingMaxNotionalUsdt = 150.0;

    /** Hard cap on expected loss at SL for one auto-trade. */
    private double positionSizingHardMaxRiskUsdt = 5.0;

    /** Free USDT buffer preserved before recommending larger position size. */
    private double positionSizingFreeUsdtBuffer = 20.0;

    /** 最多同時持有的自動交易倉位數（跨所有 symbol） */
    private int maxOpenPositions = 3;

    /** 是否允許同一 symbol 同時持有多筆倉位 */
    private boolean allowConcurrentOnSameSymbol = false;

    /** SWAP 合約槓桿倍數（預設 3x，需在 OKX 帳戶預先設定相同值） */
    private int swapLeverage = 3;

    /** SWAP 合約使用的保證金模式：cross 或 isolated */
    private String swapTdMode = "cross";

    /**
     * 每日虧損熔斷：當日（UTC）已平倉 PnL 低於此 USDT 值時，停止開新倉（既有倉位仍可平倉）。
     * 預設 -15 USDT（當日累計虧損超過 15 USDT 就休兵到隔日 00:00 UTC）。
     * 設為 0 或正值可停用熔斷。
     */
    private double dailyLossLimitUsdt = -15.0;

    public boolean hasPrivateCredentials() {
        return hasText(apiKey) && hasText(secretKey) && hasText(passphrase);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
