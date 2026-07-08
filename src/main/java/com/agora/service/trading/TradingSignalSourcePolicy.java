package com.agora.service.trading;

import com.agora.config.properties.TradingSignalSourceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TradingSignalSourcePolicy {

    private static final String TRADINGVIEW = "TRADINGVIEW";
    private static final String LOCAL_TRADINGVIEW = "LOCAL_TRADINGVIEW";
    private static final String LEGACY = "LEGACY";

    private final TradingSignalSourceProperties props;

    public boolean shouldRunLegacyLiveEvaluator() {
        return LEGACY.equals(primary()) && props.legacyLiveEvaluatorEnabled();
    }

    public boolean shouldRunAnyLegacyLiveEvaluator() {
        return shouldRunLegacyLiveEvaluator() || shouldRunSecondaryLegacyEvaluator();
    }

    public boolean shouldRunLegacyLiveEvaluatorForStrategy(Long strategyId) {
        if (shouldRunLegacyLiveEvaluator()) {
            return true;
        }
        return shouldRunSecondaryLegacyEvaluator() && strategyId != null
                && legacySecondaryAllowedStrategyIds().contains(strategyId);
    }

    public boolean shouldRunSecondaryLegacyEvaluator() {
        return !LEGACY.equals(primary())
                && props.legacySecondaryEvaluatorEnabled()
                && !legacySecondaryAllowedStrategyIds().isEmpty();
    }

    public double legacySecondaryMaxNotionalUsdtForStrategy(Long strategyId) {
        if (!shouldRunSecondaryLegacyEvaluator()
                || strategyId == null
                || !legacySecondaryAllowedStrategyIds().contains(strategyId)) {
            return 0.0;
        }
        BigDecimal cap = props.legacySecondaryMaxNotionalUsdt();
        if (cap == null || cap.signum() <= 0) {
            return 0.0;
        }
        return cap.doubleValue();
    }

    public boolean shouldRunLocalTradingViewEvaluator() {
        return LOCAL_TRADINGVIEW.equals(primary());
    }

    public String primary() {
        String value = props.primary();
        if (value == null || value.isBlank()) {
            return TRADINGVIEW;
        }
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    public Map<String, Object> status() {
        boolean legacyAllowed = shouldRunLegacyLiveEvaluator();
        boolean localTradingViewAllowed = shouldRunLocalTradingViewEvaluator();
        boolean secondaryAllowed = shouldRunSecondaryLegacyEvaluator();
        return Map.of(
                "primary", primary(),
                "localTradingViewEvaluatorAllowed", localTradingViewAllowed,
                "localTradingViewEvaluatorReason", localTradingViewAllowed
                        ? "LOCAL_TRADINGVIEW_PRIMARY"
                        : "PRIMARY_IS_NOT_LOCAL_TRADINGVIEW",
                "legacyLiveEvaluatorEnabled", props.legacyLiveEvaluatorEnabled(),
                "legacyLiveEvaluatorAllowed", legacyAllowed,
                "legacyLiveEvaluatorReason", legacyAllowed
                        ? "LEGACY_PRIMARY_AND_EXPLICITLY_ENABLED"
                        : "TRADINGVIEW_PRIMARY_OR_LEGACY_DISABLED",
                "legacySecondaryEvaluatorEnabled", props.legacySecondaryEvaluatorEnabled(),
                "legacySecondaryEvaluatorAllowed", secondaryAllowed,
                "legacySecondaryAllowedStrategyIds", legacySecondaryAllowedStrategyIds(),
                "legacySecondaryMaxNotionalUsdt", props.legacySecondaryMaxNotionalUsdt() == null
                        ? BigDecimal.ZERO
                        : props.legacySecondaryMaxNotionalUsdt()
        );
    }

    public Set<Long> legacySecondaryAllowedStrategyIds() {
        String csv = props.legacySecondaryAllowedStrategyIds();
        if (csv == null || csv.isBlank()) {
            return Collections.emptySet();
        }
        Set<Long> ids = new HashSet<>();
        for (String token : csv.split(",")) {
            String value = token == null ? "" : token.trim();
            if (value.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // Invalid tokens are ignored so a typo fails closed for that id.
            }
        }
        return Collections.unmodifiableSet(ids);
    }
}
