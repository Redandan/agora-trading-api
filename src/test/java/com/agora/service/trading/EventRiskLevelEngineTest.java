package com.agora.service.trading;

import com.agora.config.properties.EventRiskControlProperties;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.TgTradingNotificationClassifier;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EventRiskLevelEngineTest {

    @Test
    void renderIncludesReadOnlyOperatorControlsForPhaseB() {
        EventRiskLevelEngine engine = new EventRiskLevelEngine(
                mock(MarketIndicatorHistoryRepository.class),
                mock(TgNotificationLogRepository.class),
                mock(TgTradingNotificationClassifier.class),
                new EventRiskControlProperties(true, true, false, 4, 60, "485", "574"));
        EventRiskLevelEngine.Snapshot snapshot = new EventRiskLevelEngine.Snapshot(
                "BTCUSDT",
                75,
                EventRiskLevelEngine.RiskLevel.R3,
                List.of("severe_external_market_signal(+40)", "market_flip_external_confluence(+25)"),
                Map.of("market_signal_count_4h", 3),
                LocalDateTime.parse("2026-06-16T00:00:00"));

        String output = engine.render(snapshot);

        assertThat(output).contains("boundary=READ_ONLY");
        assertThat(output).contains("statusNotifyEnabled=false");
        assertThat(output).contains("statusNotifyCooldownMinutes=60");
        assertThat(output).contains("tgWindowHours=4");
        assertThat(output).contains("r2AllowlistStrategyIds=485");
        assertThat(output).contains("r3AllowlistStrategyIds=574");
        assertThat(output).contains("policy=R3 blocks new entries except explicit R3 allowlist");
        assertThat(output).contains("operatorControls=CONFIG_ONLY_NO_RUNTIME_MUTATION");
    }
}
