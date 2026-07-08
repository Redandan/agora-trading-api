package com.agora.service.trading;

import com.agora.config.properties.TradingSignalSourceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TradingSignalSourcePolicyTest {

    @Test
    void defaultsToTradingViewPrimaryAndBlocksLegacyLiveEvaluator() {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                props(null, false));

        assertThat(policy.primary()).isEqualTo("TRADINGVIEW");
        assertThat(policy.shouldRunLegacyLiveEvaluator()).isFalse();
        assertThat(policy.status()).containsEntry("legacyLiveEvaluatorAllowed", false);
    }

    @Test
    void legacyLiveEvaluatorRequiresLegacyPrimaryAndExplicitEnable() {
        assertThat(new TradingSignalSourcePolicy(
                props("LEGACY", false))
                .shouldRunLegacyLiveEvaluator()).isFalse();

        assertThat(new TradingSignalSourcePolicy(
                props("TRADINGVIEW", true))
                .shouldRunLegacyLiveEvaluator()).isFalse();

        assertThat(new TradingSignalSourcePolicy(
                props("LEGACY", true))
                .shouldRunLegacyLiveEvaluator()).isTrue();
    }

    @Test
    void localTradingViewPrimaryRunsLocalEvaluatorOnly() {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                props("local-tradingview", true));

        assertThat(policy.primary()).isEqualTo("LOCAL_TRADINGVIEW");
        assertThat(policy.shouldRunLocalTradingViewEvaluator()).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluator()).isFalse();
        assertThat(policy.status())
                .containsEntry("localTradingViewEvaluatorAllowed", true)
                .containsEntry("localTradingViewEvaluatorReason", "LOCAL_TRADINGVIEW_PRIMARY");
    }

    @Test
    void localTradingViewPrimaryCanRunSecondaryLegacyAllowlistOnlyForNamedStrategies() {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties(
                        "local-tradingview", false, true, "508, nope, 574", new BigDecimal("10.0")));

        assertThat(policy.shouldRunLocalTradingViewEvaluator()).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluator()).isFalse();
        assertThat(policy.shouldRunAnyLegacyLiveEvaluator()).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluatorForStrategy(508L)).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluatorForStrategy(574L)).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluatorForStrategy(485L)).isFalse();
        assertThat(policy.legacySecondaryMaxNotionalUsdtForStrategy(508L)).isEqualTo(10.0);
        assertThat(policy.legacySecondaryMaxNotionalUsdtForStrategy(485L)).isZero();
        assertThat(policy.status())
                .containsEntry("legacySecondaryEvaluatorEnabled", true)
                .containsEntry("legacySecondaryEvaluatorAllowed", true);
    }

    private TradingSignalSourceProperties props(String primary, boolean legacyLiveEvaluatorEnabled) {
        return new TradingSignalSourceProperties(primary, legacyLiveEvaluatorEnabled, false, "", BigDecimal.ZERO);
    }
}
