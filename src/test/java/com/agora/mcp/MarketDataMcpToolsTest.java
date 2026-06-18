package com.agora.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataMcpToolsTest {

    @Test
    void signalAccuracyReportCarriesReadOnlyBoundary() {
        String output = MarketDataMcpTools.buildSignalAccuracyReport(7, List.of());

        assertThat(output)
                .contains("mode=READ_ONLY")
                .contains("no signal/order/OCO/strategy/grid/fund/Earn/Telegram behavior changed");
    }
}
