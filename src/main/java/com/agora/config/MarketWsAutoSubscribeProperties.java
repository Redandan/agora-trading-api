package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Binance WS 自動訂閱配置。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "market.ws.auto-subscribe")
public class MarketWsAutoSubscribeProperties {

    /**
     * 是否啟用啟動時自動訂閱。
     */
    private boolean enabled = false;

    /**
     * 啟動時需要自動訂閱的清單。
     */
    private List<Item> items = new ArrayList<>();

    /**
     * 啟動後是否立即跑策略評估暖機。
     */
    private boolean warmUpEnabled = false;

    @Data
    public static class Item {
        private String symbol;
        private String intervalCode;
        private String marketType = "SPOT";
    }
}
