package com.agora.service.trading;

import com.agora.config.properties.EventRiskControlProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtStrategy;
import com.agora.model.MarketIndicatorHistory;
import com.agora.model.TgNotificationLog;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.TgTradingNotificationClassifier;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventRiskActionOrchestratorTest {

    private final MarketIndicatorHistoryRepository indicatorRepo = mock(MarketIndicatorHistoryRepository.class);
    private final TgNotificationLogRepository tgNotificationLogRepo = mock(TgNotificationLogRepository.class);
    private final NotificationPort notificationPort = mock(NotificationPort.class);

    @Test
    void r2RiskBlocksNewEntriesAndAddsAuditContextWithoutNotificationByDefault() {
        EventRiskActionOrchestrator orchestrator = orchestrator(
                new EventRiskControlProperties(true, true, false, 4, 60, "", ""));
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);
        givenIndicator("BTCUSDT", "btc_change_pct_1h", "5.5");
        givenIndicator("BTCUSDT", "sqi", "80");

        var decision = orchestrator.assessNewEntry(strategy, Map.of(), "BTCUSDT", "1h", "LONG", 99L);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.snapshot().level()).isEqualTo(EventRiskLevelEngine.RiskLevel.R2);
        assertThat(decision.snapshot().score()).isEqualTo(60);
        assertThat(decision.reason()).contains("EventRiskControl: R2");
        assertThat(decision.auditContext())
                .containsEntry("riskLevel", "R2")
                .containsEntry("riskScore", 60)
                .containsEntry("strategy_id", 485L)
                .containsEntry("interval", "1h")
                .containsEntry("side", "LONG")
                .containsEntry("live_signal_id", 99L)
                .containsEntry("policy", "block_new_entries_at_R2_R3");
        verify(notificationPort, never()).broadcast(any(), eq(true));
    }

    @Test
    void r3AllowlistCanKeepNewEntryEvaluationAllowed() {
        EventRiskActionOrchestrator orchestrator = orchestrator(
                new EventRiskControlProperties(true, true, false, 4, 60, "", "574"));
        BtStrategy strategy = new BtStrategy();
        strategy.setId(574L);
        givenIndicator("BTCUSDT", "btc_change_pct_1h", "5.5");
        givenIndicator("BTCUSDT", "btc_change_pct_24h", "11.0");

        var decision = orchestrator.assessNewEntry(strategy, Map.of(), "BTCUSDT", "1h", "LONG", 100L);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.snapshot().level()).isEqualTo(EventRiskLevelEngine.RiskLevel.R3);
        assertThat(decision.reason()).contains("strategy allowlisted for R3");
        assertThat(decision.auditContext()).isEmpty();
        verify(notificationPort, never()).broadcast(any(), eq(true));
    }

    @Test
    void marketSignalConfluenceCanEscalateToR3AndBlockNewEntries() {
        EventRiskActionOrchestrator orchestrator = orchestrator(
                new EventRiskControlProperties(true, true, false, 4, 60, "", ""),
                List.of(
                        tg("BTC polymarket extreme downside risk", "CRITICAL", "PolymarketMonitor", "BTCUSDT"),
                        tg("BTC market flip critical do_not_add", "CRITICAL", "MarketFlipDetector", "BTCUSDT")));
        BtStrategy strategy = new BtStrategy();
        strategy.setId(485L);

        var decision = orchestrator.assessNewEntry(strategy, Map.of(), "BTCUSDT", "1h", "LONG", 101L);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.snapshot().level()).isEqualTo(EventRiskLevelEngine.RiskLevel.R3);
        assertThat(decision.snapshot().score()).isEqualTo(75);
        assertThat(decision.snapshot().reasons())
                .anySatisfy(reason -> assertThat(reason).contains("market_flip_external_confluence"))
                .anySatisfy(reason -> assertThat(reason).contains("market_signal_route_confluence=2"))
                .anySatisfy(reason -> assertThat(reason).contains("severe_external_market_signal"));
        assertThat(decision.snapshot().inputs())
                .containsEntry("market_signal_count_4h", 2);
        assertThat(decision.reason()).contains("EventRiskControl: R3");
        assertThat(decision.auditContext())
                .containsEntry("riskLevel", "R3")
                .containsEntry("riskScore", 75)
                .containsEntry("strategy_id", 485L);
        verify(notificationPort, never()).broadcast(any(), eq(true));
    }

    private EventRiskActionOrchestrator orchestrator(EventRiskControlProperties properties) {
        return orchestrator(properties, List.of());
    }

    private EventRiskActionOrchestrator orchestrator(EventRiskControlProperties properties,
                                                     List<TgNotificationLog> logs) {
        when(tgNotificationLogRepo.search(any(LocalDateTime.class), any(), any(), any(), any(), any()))
                .thenReturn(logs);
        EventRiskLevelEngine engine = new EventRiskLevelEngine(
                indicatorRepo,
                tgNotificationLogRepo,
                new TgTradingNotificationClassifier(),
                properties);
        return new EventRiskActionOrchestrator(engine, properties, notificationPort);
    }

    private void givenIndicator(String symbol, String indicator, String value) {
        MarketIndicatorHistory row = new MarketIndicatorHistory();
        row.setSymbol(symbol);
        row.setIndicator(indicator);
        row.setValue(new BigDecimal(value));
        row.setCapturedAt(LocalDateTime.parse("2026-06-16T00:00:00"));
        when(indicatorRepo.findTopCleanBySymbolAndIndicator(symbol, indicator))
                .thenReturn(Optional.of(row));
    }

    private TgNotificationLog tg(String message, String level, String source, String symbol) {
        TgNotificationLog log = new TgNotificationLog();
        log.setMessage(message);
        log.setLevel(level);
        log.setSource(source);
        log.setSymbol(symbol);
        log.setSentAt(LocalDateTime.parse("2026-06-16T00:00:00"));
        return log;
    }
}
