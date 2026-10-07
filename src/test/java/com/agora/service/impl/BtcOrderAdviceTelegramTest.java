package com.agora.service.impl;

import com.agora.config.TelegramBotConfig;
import com.agora.service.TgTradingNotificationClassifier;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceTelegramTest {
    @Test void reportsSuccessOnlyForProviderAcknowledgement() {
        var config = new TelegramBotConfig(); config.setChannelId("test-only-destination");
        var receipt = new Message(); receipt.setMessageId(42);
        var client = (TelegramClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TelegramClient.class},
                (p, m, a) -> receipt);
        var service = new TelegramServiceImpl(config, null, new TgTradingNotificationClassifier(), client);
        assertTrue(service.sendBtcOrderAdvice("BTC 掛單建議（離線測試）"));
    }
    @Test void failureAndMissingDestinationNeverBecomeSuccessfulDelivery() {
        var config = new TelegramBotConfig(); config.setChannelId("test-only-destination");
        AtomicInteger calls = new AtomicInteger();
        var client = (TelegramClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TelegramClient.class},
                (p, m, a) -> { calls.incrementAndGet(); throw new TelegramApiException("offline simulated failure"); });
        var service = new TelegramServiceImpl(config, null, new TgTradingNotificationClassifier(), client);
        assertFalse(service.sendBtcOrderAdvice("BTC 掛單建議")); assertEquals(1, calls.get());
        config.setChannelId(""); assertFalse(service.sendBtcOrderAdvice("BTC 掛單建議")); assertEquals(1, calls.get());
    }
    @Test void oversizeMessageIsNotPartiallyDelivered() {
        var config = new TelegramBotConfig(); config.setChannelId("test-only-destination");
        var client = (TelegramClient) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{TelegramClient.class},
                (p, m, a) -> { throw new AssertionError("Must not send an incomplete plan"); });
        assertFalse(new TelegramServiceImpl(config, null, new TgTradingNotificationClassifier(), client).sendBtcOrderAdvice("x".repeat(4097)));
    }
}
