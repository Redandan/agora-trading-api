package com.agora.scheduler.trading;

import com.agora.config.MarketWsAutoSubscribeProperties;
import com.agora.config.WsSubscriptionResolver;
import com.agora.infra.notification.NotificationPort;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.market.MdKlineInsertHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KlineGapDetectorTest {

    @Test
    void detectsGapsFromResolvedSubscriptionsWhenYamlItemsAreEmpty() {
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        MarketWsAutoSubscribeProperties properties = new MarketWsAutoSubscribeProperties();
        properties.setEnabled(true);
        properties.setItems(new ArrayList<>());

        MarketWsAutoSubscribeProperties.Item item = new MarketWsAutoSubscribeProperties.Item();
        item.setSymbol("BTCUSDT");
        item.setIntervalCode("1h");
        item.setMarketType("SPOT");

        WsSubscriptionResolver resolver = mock(WsSubscriptionResolver.class);
        when(resolver.resolve()).thenReturn(List.of(item));
        when(klineRepository.findOpenTimesBetweenBySource(
                eq("BTCUSDT"), eq("1h"), eq("okx"),
                any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenAnswer(invocation -> hourlyBars(
                        invocation.getArgument(3, LocalDateTime.class),
                        invocation.getArgument(4, LocalDateTime.class)));

        MdKlineInsertHelper insertHelper = mock(MdKlineInsertHelper.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        KlineGapDetector detector = new KlineGapDetector(
                klineRepository,
                new ObjectMapper(),
                properties,
                mock(NotificationPort.class),
                eventPublisher,
                insertHelper,
                resolver,
                "http://127.0.0.1.invalid");

        detector.detectAndBackfill();

        verify(resolver).resolve();
        verify(klineRepository).findOpenTimesBetweenBySource(
                eq("BTCUSDT"), eq("1h"), eq("okx"),
                any(LocalDateTime.class), any(LocalDateTime.class));
        verifyNoInteractions(insertHelper, eventPublisher);
    }

    @Test
    void staysInertWhenMarketWsAutoSubscribeIsDisabled() {
        MdKlineRepository klineRepository = mock(MdKlineRepository.class);
        MarketWsAutoSubscribeProperties properties = new MarketWsAutoSubscribeProperties();
        properties.setEnabled(false);

        WsSubscriptionResolver resolver = mock(WsSubscriptionResolver.class);
        KlineGapDetector detector = new KlineGapDetector(
                klineRepository,
                new ObjectMapper(),
                properties,
                mock(NotificationPort.class),
                mock(ApplicationEventPublisher.class),
                mock(MdKlineInsertHelper.class),
                resolver,
                "http://127.0.0.1.invalid");

        detector.detectAndBackfill();

        verifyNoInteractions(resolver, klineRepository);
    }

    private static List<LocalDateTime> hourlyBars(LocalDateTime start, LocalDateTime end) {
        List<LocalDateTime> bars = new ArrayList<>();
        LocalDateTime cursor = start;
        while (!cursor.isAfter(end)) {
            bars.add(cursor);
            cursor = cursor.plusHours(1);
        }
        return bars;
    }
}
