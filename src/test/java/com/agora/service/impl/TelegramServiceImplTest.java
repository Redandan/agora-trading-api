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

    private TelegramServiceImpl newService() {
        TelegramBotConfig config = new TelegramBotConfig();
        config.setChannelId("-100test");
        return new TelegramServiceImpl(
                config,
                mock(TgNotificationLogRepository.class),
                new TgTradingNotificationClassifier(),
                mock(TelegramClient.class)
        );
    }

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
    void sendsQueuedHtmlAsPlainTextEvenWhenShort() {
        String html = "<b>risk</b>\nprice <> threshold";

        List<TelegramServiceImpl.ChannelPayload> payloads =
                TelegramServiceImpl.toChannelPayloads(html, true);

        assertThat(payloads).hasSize(1);
        assertThat(payloads.getFirst().useHtml()).isFalse();
        assertThat(payloads.getFirst().message()).doesNotContain("<b>", "</b>");
        assertThat(payloads.getFirst().message()).contains("price <> threshold");
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

    @Test
    void compactsDailyAutonomousDigestIntoThreeIntentLines() {
        TelegramServiceImpl service = newService();
        String raw = """
                <b>每日自動交易摘要</b>
                標的=BTCUSDT 策略=574 方向=LONG
                結論=REVIEW_PROMOTION 需人工處理=是 已下單=否
                SCORE_BUY預備倉=策略=BLOCKED 狀態=PRE_POSITION holdingState=NO_OPEN_SCORE_BUY_BTC primaryNoBuyReason=PRE_POSITION_NOT_READY:PRE_POSITION 主要阻擋=[ "RUNTIME_EVIDENCE_NOT_AVAILABLE_FOR_SCORE_BUY", "PRE_POSITION_NOT_READY:NOT_READY", "EXECUTION_POLICY_NOT_READY:BLOCKED" ]
                post-scout排程=installed=true 已啟用=false dry-run=true tickCount=0
                下一步=Review production promotion/cap recommendation; digest does not change config.
                邊界=只讀摘要；不會下單、不會修改 OCO/策略/Grid/資金/Earn。
                """;

        String normalized = ReflectionTestUtils.invokeMethod(
                service, "normalizeTradingMessage", raw, "DailyAutonomousTradingDigest", "INFO");

        assertThat(normalized).isNotNull();
        assertThat(normalized.split("\\R")).hasSize(3);
        assertThat(normalized)
                .contains("【交易保護】BTCUSDT #574 LONG: REVIEW_PROMOTION")
                .contains("狀態=未下單; 人工=是; 主因=預備倉未就緒")
                .contains("不是買賣指令")
                .doesNotContain("post-scout排程")
                .doesNotContain("標籤：");
    }

    @Test
    void compactsMarketRiskSummaryIntoThreeIntentLines() {
        TelegramServiceImpl service = newService();
        String raw = """
                [市場風險摘要] BTCUSDT | 24h
                時間：2026-06-29 16:10 台北
                狀態：REVIEW_POSITION
                摘要：MARKET_SIGNAL 4 / ACTIONABLE_TRADE 1 / routes 2
                原因：market-signal:risk-summary 3 / Macro/MEI 1
                建議：先看是否已有交易/風控訊息；不要只靠市場訊號加倉。
                非交易指令：這張卡不是 BUY/SELL；Polymarket、MEI、Market Flip 只當風險背景。
                下一步：用下方按鈕查市場明細 / 訊號分層 / 倉位 / OCO / Trailing。
                """;

        String normalized = ReflectionTestUtils.invokeMethod(
                service, "normalizeTradingMessage", raw, "MarketSignalRiskSummary", "INFO");

        assertThat(normalized).isNotNull();
        assertThat(normalized.split("\\R")).hasSize(3);
        assertThat(normalized)
                .contains("【市場背景】BTCUSDT 24h: 檢查倉位")
                .contains("訊號=市場訊號 4；交易提醒 1；來源路由 2")
                .contains("原因=風險摘要 3；宏觀/MEI 1")
                .contains("用途=風險背景，不是買賣指令")
                .doesNotContain("時間：")
                .doesNotContain("標籤：")
                .doesNotContain("REVIEW_POSITION")
                .doesNotContain("MARKET_SIGNAL")
                .doesNotContain("ACTIONABLE_TRADE")
                .doesNotContain("market-signal:risk-summary")
                .doesNotContain("routes");
    }

    @Test
    void compactsSingleAttentionMarketSignalIntoThreeChineseIntentLines() {
        TelegramServiceImpl service = newService();
        String raw = """
                ⚠️ <b>Attention: 軋空扳機-1：空頭主動平倉 OI -1.5%/h</b>
                觸發: -1.7110 (threshold < -1.5000, 1.14× 觸發)
                symbol: BTCUSDT
                ctx: oi_change_pct_1h=-1.7110
                """;

        String normalized = ReflectionTestUtils.invokeMethod(
                service, "normalizeTradingMessage", raw, "AttentionRuleEvaluator", "WARN");

        assertThat(normalized).isNotNull();
        assertThat(normalized.split("\\R")).hasSize(3);
        assertThat(normalized)
                .contains("【市場背景】BTCUSDT: 軋空觀察：空頭主動平倉 未平倉量 -1.5%/h")
                .contains("觸發值=-1.7110; 門檻=小於 -1.5000, 1.14 倍 觸發")
                .contains("指標=1 小時未平倉量變化=-1.7110%")
                .contains("用途=觀察，不是買賣指令")
                .doesNotContain("Attention:")
                .doesNotContain("threshold <")
                .doesNotContain("symbol:")
                .doesNotContain("ctx:")
                .doesNotContain("oi_change_pct_1h");
    }

    @Test
    void compactsAlreadyWrappedAttentionMarketSignalWithoutRawTags() {
        TelegramServiceImpl service = newService();
        String raw = """
                【市場背景】
                ⚠️ Attention: 軋空扳機-1：空頭主動平倉 OI -1.5%/h
                觸發: -1.7211 (threshold < -1.5000, 1.15× 觸發)
                symbol: BTCUSDT
                ctx: oi_change_pct_1h=-1.7211
                處置：觀察
                標籤：MARKET_SIGNAL / WATCH
                """;

        String normalized = ReflectionTestUtils.invokeMethod(
                service, "normalizeTradingMessage", raw, "AttentionRuleEvaluator", "WARN");

        assertThat(normalized).isNotNull();
        assertThat(normalized.split("\\R")).hasSize(3);
        assertThat(normalized)
                .contains("【市場背景】BTCUSDT: 軋空觀察：空頭主動平倉 未平倉量 -1.5%/h")
                .contains("觸發值=-1.7211; 門檻=小於 -1.5000, 1.15 倍 觸發")
                .contains("指標=1 小時未平倉量變化=-1.7211%")
                .doesNotContain("Attention:")
                .doesNotContain("threshold <")
                .doesNotContain("symbol:")
                .doesNotContain("ctx:")
                .doesNotContain("標籤：")
                .doesNotContain("MARKET_SIGNAL")
                .doesNotContain("WATCH");
    }

    @Test
    void compactsWhaleAttentionMarketSignalWithPercentIndicator() {
        TelegramServiceImpl service = newService();
        String raw = """
                ⚠️ <b>Attention: 軋空扳機-3：鯨魚買單主導 whale_buy > 75%</b>
                觸發: 0.7757 (threshold > 0.7500, 1.03× 超標)
                symbol: BTCUSDT
                ctx: whale_buy_ratio=0.7757
                """;

        String normalized = ReflectionTestUtils.invokeMethod(
                service, "normalizeTradingMessage", raw, "AttentionRuleEvaluator", "WARN");

        assertThat(normalized).isNotNull();
        assertThat(normalized.split("\\R")).hasSize(3);
        assertThat(normalized)
                .contains("【市場背景】BTCUSDT: 軋空觀察：鯨魚買單主導 鯨魚買單占比 > 75%")
                .contains("觸發值=0.7757; 門檻=大於 0.7500, 1.03 倍 超標")
                .contains("指標=鯨魚買單占比=77.57%")
                .contains("用途=觀察，不是買賣指令")
                .doesNotContain("Attention:")
                .doesNotContain("threshold >")
                .doesNotContain("symbol:")
                .doesNotContain("ctx:")
                .doesNotContain("whale_buy_ratio");
    }
}
