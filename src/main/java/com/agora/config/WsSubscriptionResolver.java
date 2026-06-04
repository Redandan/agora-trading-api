package com.agora.config;

import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtStrategyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Derives the WS kline subscription list from enabled strategies + grids at
 * runtime, replacing the yaml-hardcoded {@link MarketWsAutoSubscribeProperties}
 * (which stays as a fallback when DB has no active owners).
 *
 * <h3>Rationale</h3>
 * Before this class, adding a new trading pair (e.g. {@code SOLUSDT}) meant:
 * <ol>
 *   <li>Enable a strategy in DB.</li>
 *   <li>Edit {@code application.yml} to add the subscription item.</li>
 *   <li>Redeploy.</li>
 * </ol>
 * Step 2 often drifted (disabling a strategy left an orphan yaml item still
 * consuming bandwidth). With this resolver, step 1 is sufficient — a
 * {@code StrategyEnabledEvent} triggers a resync, or the periodic
 * reconciler picks it up within 5 minutes.
 *
 * <h3>Interval choice</h3>
 * For every (symbol) we subscribe 1h AND 4h. Rationale: most MTF strategies
 * need both, and the marginal cost of one extra WS stream per symbol is
 * trivial ({@code < 1 msg/sec} per pair). Finer-grained per-strategy interval
 * derivation would require parsing {@code bt_strategy.config_json} which is
 * free-form and brittle.
 *
 * <h3>Source (binance vs okx)</h3>
 * Not the resolver's concern: the subscriber loops over every
 * {@code KlineStreamService} bean, so each item is subscribed on every
 * provider. The {@code strategy.kline_source} column only decides which
 * source {@code LiveSignalEvaluator} reads from when evaluating — both WS
 * streams keep writing {@code md_kline} rows (dual-write).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WsSubscriptionResolver {

    /** Intervals to subscribe per symbol: 1m (real-time price), 1h/4h (MTF strategies), 1d (daily strategies e.g. SCORE_BUY_V2). */
    private static final List<String> DEFAULT_INTERVALS = List.of("1m", "1h", "4h", "1d");
    private static final String DEFAULT_MARKET_TYPE = "SPOT";

    private final BtStrategyRepository strategyRepository;
    private final BtGridRepository gridRepository;
    private final MarketWsAutoSubscribeProperties fallbackProperties;

    /**
     * Resolve the current desired subscription list.
     *
     * @return never null; empty list possible if both DB and yaml are empty.
     */
    public List<MarketWsAutoSubscribeProperties.Item> resolve() {
        Set<String> seenKeys = new LinkedHashSet<>();
        List<MarketWsAutoSubscribeProperties.Item> items = new ArrayList<>();

        int strategyCount = 0;
        for (var strategy : strategyRepository.findByEnabled(true)) {
            strategyCount++;
            String symbols = strategy.getSymbols();
            if (symbols == null || symbols.isBlank()) continue;
            for (String raw : symbols.split(",")) {
                String sym = raw.trim().toUpperCase();
                if (sym.isEmpty()) continue;
                for (String iv : DEFAULT_INTERVALS) {
                    addItem(items, seenKeys, sym, iv, DEFAULT_MARKET_TYPE);
                }
            }
        }

        int gridCount = 0;
        for (var grid : gridRepository.findByEnabledTrueAndClosedAtIsNull()) {
            gridCount++;
            String sym = grid.getSymbol() != null ? grid.getSymbol().toUpperCase() : null;
            if (sym == null || sym.isBlank()) continue;
            for (String iv : DEFAULT_INTERVALS) {
                addItem(items, seenKeys, sym, iv, DEFAULT_MARKET_TYPE);
            }
        }

        if (!items.isEmpty()) {
            log.info("[WsSubResolver] Resolved {} items from DB ({} strategies + {} grids)",
                    items.size(), strategyCount, gridCount);
            return items;
        }

        log.warn("[WsSubResolver] No enabled strategy or grid; falling back to yaml items");
        List<MarketWsAutoSubscribeProperties.Item> yaml = fallbackProperties.getItems();
        return yaml != null ? yaml : List.of();
    }

    private void addItem(List<MarketWsAutoSubscribeProperties.Item> items,
                         Set<String> seenKeys, String symbol, String interval, String marketType) {
        String key = marketType + ":" + symbol + ":" + interval;
        if (!seenKeys.add(key)) return;  // dedup
        MarketWsAutoSubscribeProperties.Item item = new MarketWsAutoSubscribeProperties.Item();
        item.setSymbol(symbol);
        item.setIntervalCode(interval);
        item.setMarketType(marketType);
        items.add(item);
    }

    /**
     * Build a stable string key used for diffing two subscription sets.
     */
    public static String keyOf(MarketWsAutoSubscribeProperties.Item item) {
        return Objects.requireNonNullElse(item.getMarketType(), DEFAULT_MARKET_TYPE).toUpperCase()
                + ":" + Objects.requireNonNullElse(item.getSymbol(), "").toUpperCase()
                + ":" + Objects.requireNonNullElse(item.getIntervalCode(), "");
    }
}
