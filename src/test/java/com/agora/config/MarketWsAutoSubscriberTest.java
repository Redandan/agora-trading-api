package com.agora.config;

import com.agora.config.properties.TradingSignalSourceProperties;
import com.agora.dto.market.KlineSubscriptionInfo;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ServerStartupService;
import com.agora.service.backtest.LiveSignalEvaluator;
import com.agora.service.market.KlineStreamService;
import com.agora.service.trading.TradingSignalSourcePolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketWsAutoSubscriberTest {

    @Test
    void tradingViewPrimarySkipsLegacyWarmUpEvaluation() {
        Fixture fixture = fixture(props("TRADINGVIEW", false));

        fixture.subscriber.subscribeOnStartup();

        verify(fixture.liveSignalEvaluator, never()).evaluate("BTCUSDT", "1D");
        verify(fixture.serverStartupService).recordFirstEval(1L);
    }

    @Test
    void legacyPrimaryWithExplicitEnableRunsWarmUpEvaluation() {
        Fixture fixture = fixture(props("LEGACY", true));

        fixture.subscriber.subscribeOnStartup();

        verify(fixture.liveSignalEvaluator).evaluate("BTCUSDT", "1D");
        verify(fixture.serverStartupService).recordFirstEval(1L);
    }

    @Test
    void secondaryLegacyAllowlistRunsWarmUpThroughFilteredEvaluator() {
        Fixture fixture = fixture(new TradingSignalSourceProperties(
                "LOCAL_TRADINGVIEW", false, true, "508", new BigDecimal("10.0")));

        fixture.subscriber.subscribeOnStartup();

        verify(fixture.liveSignalEvaluator).evaluate("BTCUSDT", "1D");
        verify(fixture.serverStartupService).recordFirstEval(1L);
    }

    private Fixture fixture(TradingSignalSourceProperties signalSourceProperties) {
        KlineStreamService streamService = mock(KlineStreamService.class);
        when(streamService.providerName()).thenReturn("okx");
        when(streamService.subscribe("BTCUSDT", "1D", "SPOT")).thenReturn(subscription("BTCUSDT", "1D"));
        when(streamService.listSubscriptions()).thenReturn(List.of(subscription("BTCUSDT", "1D")));

        MarketWsAutoSubscribeProperties properties = new MarketWsAutoSubscribeProperties();
        properties.setEnabled(true);
        properties.setWarmUpEnabled(true);

        MarketWsAutoSubscribeProperties.Item item = new MarketWsAutoSubscribeProperties.Item();
        item.setSymbol("BTCUSDT");
        item.setIntervalCode("1D");
        item.setMarketType("SPOT");

        WsSubscriptionResolver resolver = mock(WsSubscriptionResolver.class);
        when(resolver.resolve()).thenReturn(List.of(item));

        ServerStartupService serverStartupService = mock(ServerStartupService.class);
        when(serverStartupService.recordStarted()).thenReturn(1L);

        LiveSignalEvaluator liveSignalEvaluator = mock(LiveSignalEvaluator.class);
        MarketWsAutoSubscriber subscriber = new MarketWsAutoSubscriber(
                List.of(streamService),
                mock(NotificationPort.class),
                properties,
                liveSignalEvaluator,
                new TradingSignalSourcePolicy(signalSourceProperties),
                serverStartupService,
                resolver);

        return new Fixture(subscriber, liveSignalEvaluator, serverStartupService);
    }

    private TradingSignalSourceProperties props(String primary, boolean legacyLiveEvaluatorEnabled) {
        return new TradingSignalSourceProperties(primary, legacyLiveEvaluatorEnabled, false, "", BigDecimal.ZERO);
    }

    private KlineSubscriptionInfo subscription(String symbol, String intervalCode) {
        KlineSubscriptionInfo info = new KlineSubscriptionInfo();
        info.setSymbol(symbol);
        info.setIntervalCode(intervalCode);
        info.setMarketType("SPOT");
        info.setStatus("RUNNING");
        info.setSource("okx");
        return info;
    }

    private record Fixture(MarketWsAutoSubscriber subscriber,
                           LiveSignalEvaluator liveSignalEvaluator,
                           ServerStartupService serverStartupService) {
    }
}
