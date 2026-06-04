package com.agora.service.impl;

import com.agora.dto.ExchangeRateInfo;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Component
public class StaticExchangeRateServiceImpl {

    // TODO(split-repo): replace this fallback with an AgoraMarket-backed client.
    // Trading should read authoritative FX rates from the marketplace service once the repo split is deployed.
    private static final Map<String, BigDecimal> DEFAULT_RATES = Map.of(
            "USD", BigDecimal.ONE,
            "USDT", BigDecimal.ONE,
            "TWD", new BigDecimal("32.00")
    );

    public List<ExchangeRateInfo> getAllUsdtRates() {
        return DEFAULT_RATES.keySet().stream()
                .map(this::getRateByCurrency)
                .toList();
    }

    public void refreshAllRates() {
        // Trading service keeps this as a static fallback until a market-data FX source is added.
    }

    public boolean needsUpdate() {
        return false;
    }

    public ExchangeRateInfo getRateByCurrency(String currency) {
        String normalized = normalize(currency);
        return ExchangeRateInfo.builder()
                .fromCurrency("USDT")
                .toCurrency(normalized)
                .rate(getDefaultRate(normalized))
                .symbol(symbol(normalized))
                .currencyName(normalized)
                .lastUpdated(LocalDateTime.now())
                .build();
    }

    public BigDecimal getDefaultRate(String currency) {
        return DEFAULT_RATES.getOrDefault(normalize(currency), BigDecimal.ONE);
    }

    public ExchangeRateInfo getCachedRate(String currency) {
        return getRateByCurrency(currency);
    }

    private String normalize(String currency) {
        if (currency == null || currency.isBlank()) {
            return "USD";
        }
        return currency.trim().toUpperCase();
    }

    private String symbol(String currency) {
        return switch (currency) {
            case "TWD" -> "NT$";
            case "USD", "USDT" -> "$";
            default -> currency;
        };
    }
}
