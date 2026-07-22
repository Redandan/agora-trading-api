package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OkxTradingServiceGridRetirementQuantityTest {

    @Test
    void subtractsSignedBaseFeeAndRoundsDownWithoutUsingOtherAccountBtc() {
        OkxTradingService.GridRetirementQuantity result =
                OkxTradingService.calculateGridRetirementQuantity(
                        "BTC-USDT", "3707656681529860098",
                        new BigDecimal("0.00008096"), new BigDecimal("0.00008096"),
                        new BigDecimal("-0.00000008096"), "BTC",
                        new BigDecimal("0.00000001"));

        assertThat(result.netAttributableQty()).isEqualByComparingTo("0.00008087904");
        assertThat(result.sellQuantity()).isEqualByComparingTo("0.00008087");
        assertThat(result.attributionDust()).isEqualByComparingTo("0.00000000904");
    }

    @Test
    void failsClosedWhenDatabaseAndProviderGrossQuantitiesDoNotMatch() {
        assertThatThrownBy(() -> OkxTradingService.calculateGridRetirementQuantity(
                "BTC-USDT", "buy-1",
                new BigDecimal("0.00009096"), new BigDecimal("0.00008096"),
                new BigDecimal("-0.00000008096"), "BTC",
                new BigDecimal("0.00000001")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB/provider gross quantity mismatch");
    }
}
