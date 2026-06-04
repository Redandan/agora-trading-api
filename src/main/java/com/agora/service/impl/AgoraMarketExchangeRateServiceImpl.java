package com.agora.service.impl;

import com.agora.config.AgoraMarketExchangeRateProperties;
import com.agora.dto.ExchangeRateInfo;
import com.agora.internal.client.AgoraMarketClientException;
import com.agora.internal.client.AgoraMarketInternalClientProperties;
import com.agora.internal.client.ExchangeRateInternalClient;
import com.agora.internal.client.HttpExchangeRateInternalClient;
import com.agora.service.ExchangeRateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Slf4j
@Service
public class AgoraMarketExchangeRateServiceImpl implements ExchangeRateService {

    private final StaticExchangeRateServiceImpl fallback;
    private final ExchangeRateInternalClient client;

    public AgoraMarketExchangeRateServiceImpl(
            AgoraMarketExchangeRateProperties properties,
            StaticExchangeRateServiceImpl fallback) {
        this.fallback = fallback;
        this.client = createClient(properties);
    }

    @Override
    public List<ExchangeRateInfo> getAllUsdtRates() {
        if (client == null) {
            return fallback.getAllUsdtRates();
        }
        try {
            return client.getUsdtRates().stream()
                    .map(this::toDto)
                    .toList();
        } catch (AgoraMarketClientException e) {
            log.warn("[ExchangeRate] AgoraMarket internal API unavailable, using static fallback: {}", e.getMessage());
            return fallback.getAllUsdtRates();
        }
    }

    @Override
    public void refreshAllRates() {
        if (client == null) {
            fallback.refreshAllRates();
            return;
        }
        getAllUsdtRates();
    }

    @Override
    public boolean needsUpdate() {
        return false;
    }

    @Override
    public ExchangeRateInfo getRateByCurrency(String currency) {
        if (client == null) {
            return fallback.getRateByCurrency(currency);
        }
        try {
            return toDto(client.getUsdtRate(currency));
        } catch (AgoraMarketClientException e) {
            log.warn("[ExchangeRate] AgoraMarket internal API unavailable for currency={}, using static fallback: {}",
                    currency, e.getMessage());
            return fallback.getRateByCurrency(currency);
        }
    }

    @Override
    public BigDecimal getDefaultRate(String currency) {
        return fallback.getDefaultRate(currency);
    }

    @Override
    public ExchangeRateInfo getCachedRate(String currency) {
        return getRateByCurrency(currency);
    }

    private ExchangeRateInternalClient createClient(AgoraMarketExchangeRateProperties properties) {
        if (!properties.isConfigured()) {
            log.info("[ExchangeRate] AGORA_MARKET_INTERNAL_API_KEY not configured; using static fallback rates");
            return null;
        }
        return new HttpExchangeRateInternalClient(new AgoraMarketInternalClientProperties(
                properties.baseUrl(),
                properties.internalApiKey(),
                properties.timeout()
        ));
    }

    private ExchangeRateInfo toDto(com.agora.internal.client.ExchangeRateInfo info) {
        if (info == null) {
            return fallback.getRateByCurrency("USD");
        }
        return ExchangeRateInfo.builder()
                .fromCurrency(info.getFromCurrency())
                .toCurrency(info.getToCurrency())
                .rate(info.getRate())
                .symbol(info.getSymbol())
                .currencyName(info.getCurrencyName())
                .lastUpdated(info.getLastUpdated())
                .build();
    }
}
