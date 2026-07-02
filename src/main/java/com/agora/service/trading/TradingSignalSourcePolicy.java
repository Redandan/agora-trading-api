package com.agora.service.trading;

import com.agora.config.properties.TradingSignalSourceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TradingSignalSourcePolicy {

    private static final String TRADINGVIEW = "TRADINGVIEW";
    private static final String LEGACY = "LEGACY";

    private final TradingSignalSourceProperties props;

    public boolean shouldRunLegacyLiveEvaluator() {
        return LEGACY.equals(primary()) && props.legacyLiveEvaluatorEnabled();
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
        return Map.of(
                "primary", primary(),
                "legacyLiveEvaluatorEnabled", props.legacyLiveEvaluatorEnabled(),
                "legacyLiveEvaluatorAllowed", legacyAllowed,
                "legacyLiveEvaluatorReason", legacyAllowed
                        ? "LEGACY_PRIMARY_AND_EXPLICITLY_ENABLED"
                        : "TRADINGVIEW_PRIMARY_OR_LEGACY_DISABLED"
        );
    }
}
