package com.agora.config;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Single source of truth for Polymarket BTC-relevant market keywords.
 *
 * <p>Used by both {@code PolymarketMonitorService} (live polling) and
 * {@code PolymarketHistoricalImportService} (historical import) to guarantee
 * consistent keyword coverage.
 *
 * <p>keyword → relevanceTag: HIGH = direct crypto/macro signal; MEDIUM = geopolitical
 * <p>keyword → category:     trade-war / crypto / macro / geopolitical
 */
@Component
public class PolymarketKeywords {

    /** keyword → relevance tag (HIGH / MEDIUM) */
    public static final Map<String, String> KEYWORD_RELEVANCE = new LinkedHashMap<>() {{
        put("trump tariff",       "HIGH");
        put("trade deal",         "HIGH");
        put("trade war",          "HIGH");
        put("bitcoin ETF",        "HIGH");
        put("crypto regulation",  "HIGH");
        put("federal reserve",    "HIGH");
        put("iran",               "MEDIUM");
        put("ukraine war",        "MEDIUM");
    }};

    /** keyword → market category (for backtest signal analysis) */
    public static final Map<String, String> KEYWORD_CATEGORY = new LinkedHashMap<>() {{
        put("trump tariff",       "trade-war");
        put("trade deal",         "trade-war");
        put("trade war",          "trade-war");
        put("bitcoin ETF",        "crypto");
        put("crypto regulation",  "crypto");
        put("federal reserve",    "macro");
        put("iran",               "geopolitical");
        put("ukraine war",        "geopolitical");
    }};
}
