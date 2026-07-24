package com.agora.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binance WS 自動訂閱配置。
 */
@Data
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "market.ws.auto-subscribe")
public class MarketWsAutoSubscribeProperties {

    /**
     * 是否啟用啟動時自動訂閱。
     */
    private boolean enabled = false;

    /**
     * Providers allowed for automatic subscriptions. Empty means all available
     * KlineStreamService beans. Catalog items still route to one exact provider.
     */
    private List<String> providers = List.of();

    @PostConstruct
    void logConfig() {
        log.info("[MarketWS] auto-subscribe config: enabled={} providers={}",
                enabled, providerSummary());
    }

    public boolean isProviderEnabled(String providerName) {
        Set<String> allowed = normalizedProviders();
        return allowed.isEmpty() || allowed.contains(normalizeProvider(providerName));
    }

    public Set<String> normalizedProviders() {
        if (providers == null || providers.isEmpty()) {
            return Set.of();
        }
        return providers.stream()
                .map(MarketWsAutoSubscribeProperties::normalizeProvider)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String providerSummary() {
        Set<String> allowed = normalizedProviders();
        return allowed.isEmpty() ? "all" : String.join(",", allowed);
    }

    private static String normalizeProvider(String providerName) {
        return providerName == null ? "" : providerName.trim().toLowerCase(Locale.ROOT);
    }

    @Data
    public static class Item {
        private String provider;
        private String symbol;
        private String intervalCode;
        private String marketType = "SPOT";
    }
}
