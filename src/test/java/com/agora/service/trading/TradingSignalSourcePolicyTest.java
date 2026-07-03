package com.agora.service.trading;

import com.agora.config.properties.TradingSignalSourceProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TradingSignalSourcePolicyTest {

    @Test
    void defaultsToTradingViewPrimaryAndBlocksLegacyLiveEvaluator() {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties(null, false));

        assertThat(policy.primary()).isEqualTo("TRADINGVIEW");
        assertThat(policy.shouldRunLegacyLiveEvaluator()).isFalse();
        assertThat(policy.status()).containsEntry("legacyLiveEvaluatorAllowed", false);
    }

    @Test
    void legacyLiveEvaluatorRequiresLegacyPrimaryAndExplicitEnable() {
        assertThat(new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties("LEGACY", false))
                .shouldRunLegacyLiveEvaluator()).isFalse();

        assertThat(new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties("TRADINGVIEW", true))
                .shouldRunLegacyLiveEvaluator()).isFalse();

        assertThat(new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties("LEGACY", true))
                .shouldRunLegacyLiveEvaluator()).isTrue();
    }

    @Test
    void localTradingViewPrimaryRunsLocalEvaluatorOnly() {
        TradingSignalSourcePolicy policy = new TradingSignalSourcePolicy(
                new TradingSignalSourceProperties("local-tradingview", true));

        assertThat(policy.primary()).isEqualTo("LOCAL_TRADINGVIEW");
        assertThat(policy.shouldRunLocalTradingViewEvaluator()).isTrue();
        assertThat(policy.shouldRunLegacyLiveEvaluator()).isFalse();
        assertThat(policy.status())
                .containsEntry("localTradingViewEvaluatorAllowed", true)
                .containsEntry("localTradingViewEvaluatorReason", "LOCAL_TRADINGVIEW_PRIMARY");
    }
}
