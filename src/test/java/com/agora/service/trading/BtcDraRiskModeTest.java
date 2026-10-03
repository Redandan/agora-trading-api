package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.config.properties.BtcDraRuntimeProperties.RiskMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.validation.ValidationBindHandler;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BtcDraRiskModeTest {
    @Test void missingSelectionDefaultsToAggressiveWithoutEnablingLive() {
        var props = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("trading.btc-dra", Bindable.of(BtcDraRuntimeProperties.class));
        assertEquals("AGGRESSIVE", props.riskMode());
        assertEquals(new BigDecimal("30.00"), props.newBuyNotionalUsdt());
        assertFalse(props.liveOrderEnabled());
        assertFalse(props.enabled());
    }

    @Test void explicitConservativeSurvivesBindingAndKeepsTheSharedCeiling() {
        var props = bind("CONSERVATIVE");
        assertEquals("CONSERVATIVE", props.riskMode());
        assertEquals(new BigDecimal("15.00"), props.newBuyNotionalUsdt());
        assertEquals(new BigDecimal("30.00"), props.maxLiveExposureUsdt());
        assertEquals(new BigDecimal("30.00"), props.liveNotionalUsdt());
    }

    @Test void invalidOrExplicitlyBlankModeCannotSilentlyBecomeAggressive() {
        assertThrows(Exception.class, () -> bind("AGRESSIVE"));
        assertThrows(Exception.class, () -> bind(""));
        assertThrows(Exception.class, () -> bind(" "));
        assertThrows(Exception.class, () -> bind("conservative"));
    }

    @Test void neitherModeCanAuthorizeExpandedCapitalOrMissingMode() {
        for (RiskMode mode : RiskMode.values()) {
            assertTrue(service(properties(mode, "30", "30")).executionArmed());
            assertFalse(service(properties(mode, "60", "30")).executionArmed());
            assertFalse(service(properties(mode, "30", "60")).executionArmed());
            assertTrue(service(properties(mode, "60", "30")).profitExitArmed());
            assertTrue(service(properties(mode, "30", "60")).profitExitArmed());
        }
        assertFalse(service(properties(null, "30", "30")).executionArmed());
    }

    @Test void currentAllocationIsDistinctFromTheFrozenHistoricalReplayContract() {
        var old = BtcDraExecutionContract.description();
        for (RiskMode mode : RiskMode.values()) {
            var props = properties(mode, "30", "30");
            var current = BtcDraExecutionContract.description(props.riskMode(), props.newBuyNotionalUsdt(),
                    props.liveNotionalUsdt(), props.maxLiveExposureUsdt());
            assertEquals(BtcDraExecutionContract.RISK_PROFILE, current.get("profile"));
            assertEquals(props.newBuyNotionalUsdt(), current.get("liveNotionalUsdt"));
            assertEquals(1, current.get("maximumLiveLots"));
            assertEquals(false, current.get("automaticRiskModeSwitch"));
            assertEquals(false, current.get("profitExitIsExpectedReturn"));
            assertEquals("OBSERVE_NO_AUTOMATIC_REBALANCE_OR_LOSS_EXIT", current.get("lossAndDrawdownResponse"));
            assertEquals(BtcDraPolicy.NET_PROFIT_TRIGGER, current.get("profitExitNetReturn"));
        }
        assertEquals(old, BtcDraExecutionContract.description());
    }

    private BtcDraRuntimeProperties bind(String mode) {
        try (var validator = new LocalValidatorFactoryBean()) {
            validator.afterPropertiesSet();
            return new Binder(new MapConfigurationPropertySource(Map.of(
                "trading.btc-dra.mode", "LIVE", "trading.btc-dra.risk-mode", mode)))
                .bind("trading.btc-dra", Bindable.of(BtcDraRuntimeProperties.class),
                        new ValidationBindHandler(validator)).get();
        }
    }

    static BtcDraRuntimeProperties properties(RiskMode mode, String base, String cap) {
        return new BtcDraRuntimeProperties(BtcDraRuntimeProperties.Mode.LIVE,
                new BigDecimal(base), new BigDecimal(cap), 15, mode);
    }

    private BtcDraLiveExecutionService service(BtcDraRuntimeProperties props) {
        var okx = new OkxTradingProperties(); okx.setEnabled(true);
        okx.setApiKey("fixture"); okx.setSecretKey("fixture"); okx.setPassphrase("fixture");
        return new BtcDraLiveExecutionService(props, okx, null, null, null, null, null,
                new StrategyRuntimeCatalog(), null);
    }
}
