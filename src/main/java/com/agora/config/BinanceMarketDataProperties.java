package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binance 市場資料來源端點配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "market.binance")
public class BinanceMarketDataProperties {

    /**
     * SPOT REST Kline 端點。
     */
    private String spotRestBaseUrl = "https://api.binance.com/api/v3/klines";

    /**
     * FUTURES REST Kline 端點。
     */
    private String futuresRestBaseUrl = "https://fapi.binance.com/fapi/v1/klines";

    /**
     * SPOT WS stream 基礎端點（需以 / 結尾）。
     */
    private String spotWsBaseUrl = "wss://stream.binance.com:9443/ws/";

    /**
     * FUTURES WS stream 基礎端點（需以 / 結尾）。
     */
    private String futuresWsBaseUrl = "wss://fstream.binance.com/ws/";
}
