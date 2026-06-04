package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binance 現貨自動交易配置。
 *
 * <p>所有欄位皆可透過 application.yml 的 trading.binance.* 覆寫。
 * 預設 enabled=false，必須明確開啟才會自動下單。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.binance")
public class BinanceTradingProperties {

    /**
     * 是否啟用自動交易。預設關閉，確認 API Key 正確後再開啟。
     */
    private boolean enabled = false;

    /**
     * Binance US 或 Binance 的 API Key。
     */
    private String apiKey = "";

    /**
     * Binance US 或 Binance 的 Secret Key（用於 HMAC-SHA256 簽名）。
     */
    private String secretKey = "";

    /**
     * 現貨交易 REST Base URL（不含路徑）。
     * 預設 Binance US；若使用國際版改為 https://api.binance.com
     */
    private String spotRestBaseUrl = "https://api.binance.us";

    /**
     * 每次買入金額（USDT）。
     */
    private double tradeAmountUsdt = 100.0;

    /**
     * 最多同時持有的自動交易倉位數（跨所有 symbol）。
     */
    private int maxOpenPositions = 3;

    /**
     * 是否允許同一 symbol 同時持有多筆倉位。
     * false（預設）：同 symbol 已有開倉時不再買入。
     */
    private boolean allowConcurrentOnSameSymbol = false;
}
