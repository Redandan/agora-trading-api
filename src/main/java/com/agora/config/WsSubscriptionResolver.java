package com.agora.config;

import com.agora.config.properties.BtcDonchianShadowProperties;
import com.agora.service.strategy.StrategyLifecycleMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import com.agora.service.strategy.StrategyRuntimeDefinition;
import com.agora.service.trading.BtcDonchianShadowPolicy;
import com.agora.service.tradingview.TradingViewDailyStrategyContract;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Exact market-data requirements derived from the versioned runtime catalog.
 *
 * <p>Database {@code bt_strategy.enabled} rows are research inventory and
 * cannot create runtime subscriptions. Each active lane contributes exactly
 * one source/symbol/interval requirement. Owner 508 retains its Binance daily
 * feed for PAPER readiness; Donchian contributes its OKX hourly feed only
 * while its explicit SHADOW switch is enabled.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsSubscriptionResolver {

    private static final String DEFAULT_MARKET_TYPE = "SPOT";

    private final StrategyRuntimeCatalog strategyRuntimeCatalog;
    private final BtcDonchianShadowProperties donchianProperties;

    public List<MarketWsAutoSubscribeProperties.Item> resolve() {
        Set<String> seenKeys = new LinkedHashSet<>();
        List<MarketWsAutoSubscribeProperties.Item> items = new ArrayList<>();

        StrategyRuntimeDefinition owner508 =
                strategyRuntimeCatalog.require(TradingViewDailyStrategyContract.KEY);
        if (owner508.mode() == StrategyLifecycleMode.PAPER) {
            addDefinition(items, seenKeys, owner508);
        }

        if (donchianProperties.enabled()
                && strategyRuntimeCatalog.isMode(
                        BtcDonchianShadowPolicy.POLICY_MODE,
                        StrategyLifecycleMode.SHADOW)) {
            addDefinition(
                    items,
                    seenKeys,
                    strategyRuntimeCatalog.require(BtcDonchianShadowPolicy.POLICY_MODE));
        }

        log.info("[WsSubResolver] Resolved {} exact catalog requirement(s): {}",
                items.size(),
                items.stream()
                        .map(item -> item.getProvider() + ":" + item.getSymbol() + "@"
                                + item.getIntervalCode())
                        .toList());
        return List.copyOf(items);
    }

    private void addDefinition(List<MarketWsAutoSubscribeProperties.Item> items,
                               Set<String> seenKeys,
                               StrategyRuntimeDefinition definition) {
        String provider = normalize(definition.source());
        String symbol = normalizeUpper(definition.symbol());
        String interval = normalize(definition.interval());
        if (provider.isEmpty() || symbol.isEmpty() || interval.isEmpty()) {
            throw new IllegalStateException(
                    definition.versionedKey() + " has incomplete market-data scope");
        }
        String key = provider + ":" + DEFAULT_MARKET_TYPE + ":" + symbol + ":" + interval;
        if (!seenKeys.add(key)) return;
        MarketWsAutoSubscribeProperties.Item item = new MarketWsAutoSubscribeProperties.Item();
        item.setProvider(provider);
        item.setSymbol(symbol);
        item.setIntervalCode(interval);
        item.setMarketType(DEFAULT_MARKET_TYPE);
        items.add(item);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeUpper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}
