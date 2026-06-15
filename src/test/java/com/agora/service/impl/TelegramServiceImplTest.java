package com.agora.service.impl;

import com.agora.config.TelegramBotConfig;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.service.TgTradingNotificationClassifier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TelegramServiceImplTest {

    @Test
    void splitsLongPlainTextIntoBoundedPlainChunks() {
        String longText = "plain-line\n".repeat(900);

        List<TelegramServiceImpl.ChannelPayload> payloads =
                TelegramServiceImpl.toChannelPayloads(longText, false);

        assertThat(payloads).hasSizeGreaterThan(1);
        assertThat(payloads)
                .allSatisfy(payload -> {
                    assertThat(payload.message()).hasSizeLessThanOrEqualTo(TelegramServiceImpl.TELEGRAM_MESSAGE_LIMIT);
                    assertThat(payload.useHtml()).isFalse();
                });
        assertThat(payloads.getFirst().message()).startsWith("(1/");
    }

    @Test
    void splitsLongHtmlIntoPlainTextChunksWithoutTags() {
        String longHtml = "<b>" + "risk-line\n".repeat(900) + "</b>";

        List<TelegramServiceImpl.ChannelPayload> payloads =
                TelegramServiceImpl.toChannelPayloads(longHtml, true);

        assertThat(payloads).hasSizeGreaterThan(1);
        assertThat(payloads)
                .allSatisfy(payload -> {
                    assertThat(payload.message()).hasSizeLessThanOrEqualTo(TelegramServiceImpl.TELEGRAM_MESSAGE_LIMIT);
                    assertThat(payload.useHtml()).isFalse();
                    assertThat(payload.message()).doesNotContain("<b>", "</b>");
                });
    }

    @Test
    void drainQueueSendsLongMessageAsMultipleTelegramMessages() throws Exception {
        TelegramBotConfig config = new TelegramBotConfig();
        config.setChannelId("-100test");

        TgNotificationLogRepository notificationLogRepo = mock(TgNotificationLogRepository.class);
        TelegramClient telegramClient = mock(TelegramClient.class);
        TelegramServiceImpl service = new TelegramServiceImpl(
                config,
                notificationLogRepo,
                new TgTradingNotificationClassifier(),
                telegramClient
        );

        service.sendAlert("risk-line\n".repeat(900), true, "DailyAutonomousTradingDigest", "WARN");
        ReflectionTestUtils.invokeMethod(service, "drainChannelQueue");

        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient, atLeast(2)).execute(captor.capture());

        assertThat(captor.getAllValues()).hasSizeGreaterThan(1);
        assertThat(captor.getAllValues())
                .allSatisfy(message -> {
                    assertThat(message.getChatId()).isEqualTo("-100test");
                    assertThat(message.getText()).hasSizeLessThanOrEqualTo(TelegramServiceImpl.TELEGRAM_MESSAGE_LIMIT);
                    assertThat(message.getParseMode()).isNull();
                });
        verify(notificationLogRepo).save(any());
    }
}
