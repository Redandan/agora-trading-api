package com.agora.service.impl;

import com.agora.config.TelegramBotConfig;
import com.agora.infra.notification.NotificationPort;
import com.agora.model.TgNotificationLog;
import com.agora.repository.system.TgNotificationLogRepository;
import com.agora.service.TelegramService;
import com.agora.service.TgTradingNotificationClassifier;
import com.agora.service.TgTradingNotificationClassifier.Bucket;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.Locale;
import java.util.regex.Pattern;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TelegramServiceImpl implements TelegramService, NotificationPort {

    @Override public void broadcast(String message) { sendMessage(message); }
    @Override public void broadcast(String message, boolean useHtml) { sendMessage(message, useHtml); }
    @Override public void alert(String message, boolean useHtml, String source, String level) {
        sendAlert(message, useHtml, source, level);
    }


    /** 每批最多合併幾條頻道訊息 */
    private static final int  DRAIN_BATCH  = 5;
    /** 每次 drain 間隔（ms），略超過 TG 的 1 msg/s 限制 */
    private static final long DRAIN_MS     = 1100;
    /** 佇列容量；超出時丟棄並記 WARN */
    private static final int  QUEUE_CAP    = 500;
    /** Telegram Bot API text limit is 4096 chars; keep chunks below it with room for part markers. */
    static final int TELEGRAM_MESSAGE_LIMIT = 4096;
    private static final int TELEGRAM_CHUNK_BODY_LIMIT = 3800;
    private static final int OPERATOR_LINE_LIMIT = 140;
    private static final Pattern MARKET_RISK_HEADER_PATTERN =
            Pattern.compile("\\[市場風險摘要]\\s*([^|\\n]+)(?:\\|\\s*([^\\n]+))?");
    private static final Pattern ATTENTION_HEADER_PATTERN =
            Pattern.compile("(?m)^\\s*(?:[^A-Za-z0-9\\n]+\\s*)?Attention:\\s*(.+)$");
    private static final Pattern ATTENTION_TRIGGER_PATTERN =
            Pattern.compile("(?m)^\\s*觸發:\\s*([^\\(\\n]+)(?:\\(([^\\n]+)\\))?");
    private static final Pattern CTX_PAIR_PATTERN =
            Pattern.compile("([A-Za-z0-9_:-]+)=([^\\s]+)");
    private static final Pattern BLOCKER_LIST_PATTERN =
            Pattern.compile("(?s)(?:主要阻擋|primaryBlockers|triggerBlockingSignals|阻擋|異常)\\s*=\\s*\\[(.*?)]");

    private record QueuedMessage(String message, boolean useHtml) {}
    record ChannelPayload(String message, boolean useHtml) {}

    private final LinkedBlockingQueue<QueuedMessage> channelQueue =
            new LinkedBlockingQueue<>(QUEUE_CAP);

    private final ScheduledExecutorService queueExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "tg-channel-queue");
                t.setDaemon(true);
                return t;
            });

    private final TelegramBotConfig telegramBotConfig;
    private final TelegramClient telegramClient;
    private final TgNotificationLogRepository notificationLogRepo;
    private final TgTradingNotificationClassifier notificationClassifier;

    // === Pinned messages (sendOrEditPinned) ===
    // key → Telegram message_id,在頻道內永遠只保留 1 則訊息 (如 "flutter-deploy-token")。
    // 持久化到檔案跨重啟,避免每次 deploy 都發一則新 TG。
    private final ConcurrentHashMap<String, Integer> pinnedStore = new ConcurrentHashMap<>();
    private final Path pinnedFile = Paths.get(
            System.getProperty("user.home"), ".agora-state", "pinned_messages.properties");

    public TelegramServiceImpl(TelegramBotConfig telegramBotConfig,
                              TgNotificationLogRepository notificationLogRepo) {
        this(telegramBotConfig, notificationLogRepo, new TgTradingNotificationClassifier());
    }

    @Autowired
    public TelegramServiceImpl(TelegramBotConfig telegramBotConfig,
                              TgNotificationLogRepository notificationLogRepo,
                              TgTradingNotificationClassifier notificationClassifier) {
        this(telegramBotConfig, notificationLogRepo, notificationClassifier, createTelegramClient(telegramBotConfig));
    }

    TelegramServiceImpl(TelegramBotConfig telegramBotConfig,
                        TgNotificationLogRepository notificationLogRepo,
                        TgTradingNotificationClassifier notificationClassifier,
                        TelegramClient telegramClient) {
        this.telegramBotConfig    = telegramBotConfig;
        this.telegramClient       = telegramClient;
        this.notificationLogRepo  = notificationLogRepo;
        this.notificationClassifier = notificationClassifier;
    }

    private static TelegramClient createTelegramClient(TelegramBotConfig config) {
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            log.warn("Telegram bot token is not configured. Telegram notifications will be logged only.");
            return null;
        }
        return new OkHttpTelegramClient(token);
    }

    private boolean telegramClientAvailable(String operation) {
        if (telegramClient != null) {
            return true;
        }
        log.warn("Telegram client unavailable, skipping {}", operation);
        return false;
    }

    /**
     * #449 — Vacation mute: regex pattern,訊息內容 match 即 skip 送 TG(仍寫 audit log)。
     * 設 env {@code TELEGRAM_VACATION_MUTE_PATTERNS} 為 regex(正規表達式),空 = no mute。
     *
     * <p>建議休假設定:
     * <pre>
     * TELEGRAM_VACATION_MUTE_PATTERNS=Polymarket|Market 指標翻轉|Market Flip 共識|KB Audit|ML Pipeline 每日 Digest|Gemini Market Advisor.*scheduled|持續警戒提醒|回歸正常
     * </pre>
     *
     * 不該被 mute 的(必收):BUY/SELL 信號 / OCO trigger / autoTrade / Daily Loss Guard / Backend down。
     */
    @Value("${telegram.vacation-mute-patterns:}")
    private String vacationMutePatternsRaw;
    private volatile Pattern vacationMuteRegex;

    @Value("${telegram.noise-reduction.enabled:true}")
    private boolean noiseReductionEnabled;
    @Value("${telegram.noise-reduction.market-signal-cooldown-minutes:60}")
    private long marketSignalCooldownMinutes;
    @Value("${telegram.noise-reduction.system-noise-cooldown-minutes:240}")
    private long systemNoiseCooldownMinutes;
    @Value("${telegram.noise-reduction.grid-incident-cooldown-minutes:30}")
    private long gridIncidentCooldownMinutes;

    private final ConcurrentHashMap<String, java.time.LocalDateTime> noiseBucketLastSent =
            new ConcurrentHashMap<>();

    @PostConstruct
    void initQueue() {
        loadPinnedStore();
        initVacationMute();
        queueExecutor.scheduleWithFixedDelay(
                this::drainChannelQueue, 0, DRAIN_MS, TimeUnit.MILLISECONDS);
        log.debug("[TgQueue] Channel message queue started (batchSize={}, interval={}ms)",
                DRAIN_BATCH, DRAIN_MS);
    }

    private void initVacationMute() {
        if (vacationMutePatternsRaw == null || vacationMutePatternsRaw.isBlank()) {
            log.info("[TgVacationMute] disabled (no patterns set)");
            return;
        }
        try {
            vacationMuteRegex = Pattern.compile(vacationMutePatternsRaw);
            log.info("[TgVacationMute] ENABLED — regex: {}", vacationMutePatternsRaw);
        } catch (Exception e) {
            log.warn("[TgVacationMute] invalid regex '{}': {}", vacationMutePatternsRaw, e.getMessage());
        }
    }

    /** 若 vacation mute 啟用且 message match → 返 true,不送 TG。 */
    private boolean shouldMute(String message) {
        Pattern p = vacationMuteRegex;
        return p != null && message != null && p.matcher(message).find();
    }

    private boolean shouldMuteMarketRiskSummary(String message, String source) {
        if ("MarketSignalRiskSummary".equalsIgnoreCase(source)) {
            return true;
        }
        if (message == null) {
            return false;
        }
        return message.contains("[市場風險摘要]")
                || (message.contains("【市場背景】")
                && message.contains("用途=風險背景，不是買賣指令")
                && message.contains("詳情=市場明細/MCP"));
    }

    private void loadPinnedStore() {
        if (!Files.exists(pinnedFile)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(pinnedFile)) {
            p.load(in);
            p.forEach((k, v) -> {
                try { pinnedStore.put(k.toString(), Integer.parseInt(v.toString())); }
                catch (NumberFormatException ignored) {}
            });
            log.info("[TgPinned] Loaded {} pinned message ids from {}", pinnedStore.size(), pinnedFile);
        } catch (IOException e) {
            log.warn("[TgPinned] Failed to load pinned message store: {}", e.getMessage());
        }
    }

    private synchronized void savePinnedStore() {
        try {
            Files.createDirectories(pinnedFile.getParent());
            Properties p = new Properties();
            pinnedStore.forEach((k, v) -> p.setProperty(k, String.valueOf(v)));
            try (var out = Files.newOutputStream(pinnedFile)) {
                p.store(out, "Agora TG pinned messages (key=message_id)");
            }
        } catch (IOException e) {
            log.warn("[TgPinned] Failed to save pinned store: {}", e.getMessage());
        }
    }

    @PreDestroy
    void stopQueue() {
        // flush remaining messages before shutdown
        drainChannelQueue();
        queueExecutor.shutdownNow();
    }

    // ── 頻道訊息（經 queue） ──────────────────────────────────────

    @Override
    public void sendMessage(String message) {
        sendMessage(message, false);
    }

    @Override
    public void sendMessage(String message, boolean useHtml) {
        String normalizedMessage = normalizeTradingMessage(message, "system", "INFO");
        String channelId = telegramBotConfig.getChannelId();
        if (channelId == null || channelId.isEmpty()) {
            log.warn("Telegram channel ID not configured, skipping message send");
            return;
        }
        if (shouldMuteMarketRiskSummary(normalizedMessage, "system")) {
            log.debug("[TgMarketRiskSummaryMute] muted channel message: {}",
                    normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, "system", "MUTED_MARKET");
            return;
        }
        // #449 vacation mute — 仍寫 audit log,但不 enqueue 送 TG
        if (shouldMute(normalizedMessage)) {
            log.debug("[TgVacationMute] muted: {}",
                    normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, "system", "MUTED");
            return;
        }
        if (shouldSuppressNoise(normalizedMessage, "system", "INFO")) {
            logAsync(normalizedMessage, useHtml, "system", "MUTED_NOISE");
            return;
        }
        if (!channelQueue.offer(new QueuedMessage(normalizedMessage, useHtml))) {
            log.warn("[TgQueue] Queue full (cap={}), dropping channel message", QUEUE_CAP);
        }
        // 基礎日誌（source/level 未知時用預設值）
        logAsync(normalizedMessage, useHtml, "system", "INFO");
    }

    @Override
    public void sendAlert(String message, boolean useHtml, String source, String level) {
        String normalizedMessage = normalizeTradingMessage(message, source, level);
        String channelId = telegramBotConfig.getChannelId();
        if (channelId == null || channelId.isEmpty()) {
            log.warn("Telegram channel ID not configured, skipping alert send");
            return;
        }
        if (shouldMuteMarketRiskSummary(normalizedMessage, source)) {
            log.debug("[TgMarketRiskSummaryMute] muted alert (source={}): {}",
                    source, normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, source, "MUTED_MARKET");
            return;
        }
        // #449 vacation mute — 同 sendMessage path
        if (shouldMute(normalizedMessage)) {
            log.debug("[TgVacationMute] muted alert (source={}): {}",
                    source, normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, source, "MUTED");
            return;
        }
        if (shouldSuppressNoise(normalizedMessage, source, level)) {
            logAsync(normalizedMessage, useHtml, source, "MUTED_NOISE");
            return;
        }
        if (!channelQueue.offer(new QueuedMessage(normalizedMessage, useHtml))) {
            log.warn("[TgQueue] Queue full (cap={}), dropping alert message", QUEUE_CAP);
        }
        logAsync(normalizedMessage, useHtml, source, level);
    }

    @Override
    public void sendChannelMessageWithKeyboard(String message, boolean useHtml, InlineKeyboardMarkup keyboard,
                                               String source, String level) {
        String normalizedMessage = normalizeTradingMessage(message, source, level);
        String channelId = telegramBotConfig.getChannelId();
        if (channelId == null || channelId.isEmpty()) {
            log.warn("Telegram channel ID not configured, skipping keyboard message send");
            return;
        }
        if (shouldMuteMarketRiskSummary(normalizedMessage, source)) {
            log.debug("[TgMarketRiskSummaryMute] muted keyboard message (source={}): {}",
                    source, normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, source, "MUTED_MARKET");
            return;
        }
        if (shouldMute(normalizedMessage)) {
            log.debug("[TgVacationMute] muted keyboard message (source={}): {}",
                    source, normalizedMessage.substring(0, Math.min(80, normalizedMessage.length())));
            logAsync(normalizedMessage, useHtml, source, "MUTED");
            return;
        }
        if (shouldSuppressNoise(normalizedMessage, source, level)) {
            logAsync(normalizedMessage, useHtml, source, "MUTED_NOISE");
            return;
        }

        try {
            doSendToChannel(channelId, normalizedMessage, useHtml, keyboard);
            log.info("Telegram keyboard message sent successfully to channel: {}", channelId);
        } catch (TelegramApiRequestException e) {
            Long newChatId = (e.getParameters() != null) ? e.getParameters().getMigrateToChatId() : null;
            if (newChatId != null) {
                log.warn("[群組升級] chatId={} 已升級為 Supergroup，新 chatId={}。" +
                        "請更新環境變數 TELEGRAM_CHANNEL_ID={}，正在用新 ID 重試...",
                        channelId, newChatId, newChatId);
                try {
                    doSendToChannel(newChatId.toString(), normalizedMessage, useHtml, keyboard);
                    log.info("重試成功：keyboard 訊息已發送至新 chatId={}", newChatId);
                } catch (TelegramApiException retryEx) {
                    log.error("重試 keyboard 訊息失敗 newChatId={}: {}", newChatId, retryEx.getMessage(), retryEx);
                    throw new RuntimeException("Failed to send Telegram keyboard message: " + retryEx.getMessage(), retryEx);
                }
            } else {
                log.error("Failed to send Telegram keyboard message to channel {}: {}", channelId, e.getMessage(), e);
                throw new RuntimeException("Failed to send Telegram keyboard message: " + e.getMessage(), e);
            }
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram keyboard message to channel {}: {}", channelId, e.getMessage(), e);
            throw new RuntimeException("Failed to send Telegram keyboard message: " + e.getMessage(), e);
        }
        logAsync(normalizedMessage, useHtml, source, level);
    }

    private String normalizeTradingMessage(String message, String source, String level) {
        if (message == null || message.isBlank()) return message;
        Bucket bucket = notificationClassifier.classify(message, source, level);

        String operatorSummary = compactOperatorSummary(message, bucket);
        if (operatorSummary != null) {
            return operatorSummary;
        }
        if (bucket == Bucket.OTHER) return message;

        String normalized = message.trim();
        if (!normalized.startsWith("【")) {
            normalized = "【" + bucketTitle(bucket) + "】\n" + normalized;
        }
        if (!normalized.contains("處置：")) {
            normalized += "\n處置：" + defaultAction(bucket);
        }
        if (!normalized.contains("標籤：")) {
            normalized += "\n標籤：" + defaultTags(bucket);
        }
        return normalized;
    }

    private String compactOperatorSummary(String message, Bucket bucket) {
        String plain = stripHtmlTags(message == null ? "" : message).trim();
        if (plain.isBlank()) {
            return null;
        }
        if (plain.contains("每日自動交易摘要失敗")) {
            String error = firstNonBlank(lineValue(plain, "錯誤"), "摘要產生失敗");
            return threeLines(
                    "【交易保護】每日自動交易摘要失敗",
                    "狀態=未送出摘要; 原因=" + shorten(error, 86),
                    "用途=提醒檢查服務日誌/MCP；不是買賣指令。");
        }
        if (plain.contains("每日自動交易摘要") || plain.contains("Autonomous Trading Digest severe state change")) {
            return compactDailyAutonomousDigest(plain);
        }
        if (plain.contains("CRITICAL_UNPROTECTED_")
                || plain.contains("SCORE_BUY ")
                || plain.contains("Tiny-live ")) {
            String execution = compactExecutionAlert(plain);
            if (execution != null) {
                return execution;
            }
        }
        if (plain.contains("Auto Exploration Rollout transition")) {
            return compactExplorationRollout(plain);
        }
        if (plain.contains("Autonomous Exploration Loop state changed")) {
            return compactExplorationLoop(plain);
        }
        if (bucket == Bucket.MARKET_SIGNAL && containsAttentionMarker(plain)) {
            return compactAttentionMarketSignal(plain);
        }
        if (bucket == Bucket.MARKET_SIGNAL && plain.contains("[市場風險摘要]")) {
            return compactMarketRiskSummary(plain);
        }
        return null;
    }

    private String compactDailyAutonomousDigest(String plain) {
        String symbol = firstNonBlank(tokenValue(plain, "標的"), "BTCUSDT");
        String strategy = tokenValue(plain, "策略");
        String side = tokenValue(plain, "方向");
        String verdict = firstNonBlank(tokenValue(plain, "結論"), "REVIEW");
        String human = firstNonBlank(tokenValue(plain, "需人工處理"), verdictNeedsHuman(verdict) ? "是" : "否");
        String orderSent = firstNonBlank(tokenValue(plain, "已下單"), "否");
        String primary = firstNonBlank(tokenValue(plain, "primaryNoBuyReason"),
                tokenValue(plain, "primary"),
                tokenValue(plain, "主要原因"),
                firstBlockers(plain),
                "無明確阻擋");
        String title = ("【交易保護】" + symbol + strategySuffix(strategy) + sideSuffix(side) + ": " + verdict).trim();
        String status = "狀態=" + ("是".equals(orderSent) ? "已下單" : "未下單")
                + "; 人工=" + human
                + "; 主因=" + humanizeReason(primary);
        return threeLines(
                title,
                status,
                "用途=只提醒 review，不是買賣指令；詳情=每日摘要 MCP。");
    }

    private String compactExecutionAlert(String plain) {
        if (plain.contains("CRITICAL_UNPROTECTED_")) {
            String symbol = symbolFromText(plain);
            String orderId = firstNonBlank(tokenValue(plain, "orderId"), "N/A");
            String qty = tokenValue(plain, "qty");
            String error = firstNonBlank(tailValue(plain, "error"), "OCO 掛載或稽核失敗");
            String detail = "訂單=" + orderId
                    + (qty == null ? "" : "; 數量=" + qty)
                    + "; 錯誤=" + shorten(error, 62);
            return threeLines(
                    "【交易保護】" + symbol + ": 已成交但 OCO 未掛上",
                    "狀態=高風險; " + detail,
                    "用途=立即檢查倉位/OCO；不是新買入建議。");
        }
        if (plain.contains("SCORE_BUY") && plain.contains(" executed.")) {
            String symbol = firstNonBlank(tokenValue(plain, "symbol"), "BTCUSDT");
            String strategy = tokenValue(plain, "strategyId");
            String notional = tokenValue(plain, "notional");
            String orderId = firstNonBlank(tokenValue(plain, "orderId"), "N/A");
            String ocoAlgoId = firstNonBlank(tokenValue(plain, "ocoAlgoId"), "N/A");
            return threeLines(
                    "【交易保護】" + symbol + ": 分批買入策略已成交並掛 OCO",
                    "策略=" + strategyLabel(strategy) + "; 金額=" + firstNonBlank(notional, "N/A")
                            + "; 訂單=" + orderId + "; OCO=" + ocoAlgoId,
                    "用途=成交回報；檢查 OCO/倉位，不是追加買入建議。");
        }
        if (plain.contains("Tiny-live") && plain.contains(" executed with ")) {
            String symbol = symbolFromText(plain);
            String mode = between(plain, "executed with ", ". orderId=");
            String orderId = firstNonBlank(tokenValue(plain, "orderId"), "N/A");
            String ocoAlgoId = firstNonBlank(tokenValue(plain, "ocoAlgoId"), "N/A");
            String notional = firstNonBlank(tokenValue(plain, "notional"), "N/A");
            return threeLines(
                    "【交易保護】" + symbol + ": Tiny-live 已成交並掛 OCO",
                    "模式=" + humanizeExecutionToken(mode) + "; 金額=" + notional
                            + "; 訂單=" + orderId + "; OCO=" + ocoAlgoId,
                    "用途=小額實盤成交回報；檢查 OCO/倉位，不是追加買入建議。");
        }
        if (plain.contains("Tiny-live event-risk override token created")) {
            String symbol = firstNonBlank(tokenValue(plain, "symbol"), "BTCUSDT");
            String strategy = tokenValue(plain, "strategyId");
            String reason = firstNonBlank(tailValue(plain, "reason"), "事件風險覆蓋 token 已建立");
            return threeLines(
                    "【交易保護】" + symbol + strategySuffix(strategy) + ": 事件風險覆蓋 token 已建立",
                    "狀態=等待人工使用; 原因=" + shorten(humanizeExecutionToken(reason), 82),
                    "用途=授權前置提醒；不代表已下單。");
        }
        if (plain.contains("Tiny-live event-risk override token consumed")) {
            String symbol = firstNonBlank(tokenValue(plain, "symbol"), "BTCUSDT");
            String strategy = tokenValue(plain, "strategyId");
            return threeLines(
                    "【交易保護】" + symbol + strategySuffix(strategy) + ": 事件風險覆蓋 token 已使用",
                    "狀態=授權已消耗; 預覽=" + firstNonBlank(tokenValue(plain, "previewHash"), "N/A"),
                    "用途=審計提醒；不代表新的買入建議。");
        }
        return null;
    }

    private String compactExplorationRollout(String plain) {
        String previous = firstNonBlank(lineValue(plain, "previousStage"), tokenValue(plain, "previousStage"), "N/A");
        String current = firstNonBlank(lineValue(plain, "currentStage"), tokenValue(plain, "currentStage"), "N/A");
        String reason = firstNonBlank(lineValue(plain, "reason"), tailValue(plain, "reason"), "階段狀態變更");
        String blockers = firstNonBlank(lineValue(plain, "blockers"), "[]");
        return threeLines(
                "【交易觀察】自動探索 Rollout: " + humanizeExecutionToken(previous)
                        + " -> " + humanizeExecutionToken(current),
                "原因=" + shorten(humanizeExecutionToken(reason), 92),
                "阻擋=" + shorten(humanizeBlockerList(blockers), 82) + "; 不是買賣指令。");
    }

    private String compactExplorationLoop(String plain) {
        String state = firstNonBlank(lineValue(plain, "state"), tokenValue(plain, "state"), "N/A");
        String previous = firstNonBlank(lineValue(plain, "previousState"), tokenValue(plain, "previousState"), "N/A");
        String wouldExecute = humanizeBoolean(firstNonBlank(lineValue(plain, "wouldExecuteNow"), tokenValue(plain, "wouldExecuteNow")));
        String production = humanizeBoolean(firstNonBlank(lineValue(plain, "productionEnabled"), tokenValue(plain, "productionEnabled")));
        String blockers = firstNonBlank(lineValue(plain, "blockers"), "[]");
        return threeLines(
                "【交易觀察】自動探索 Loop: " + humanizeExecutionToken(state),
                "前狀態=" + humanizeExecutionToken(previous) + "; 目前會下單=" + wouldExecute + "; 生產模式=" + production,
                "阻擋=" + shorten(humanizeBlockerList(blockers), 82) + "; 不是買賣指令。");
    }

    private String compactMarketRiskSummary(String plain) {
        String symbol = "ALL";
        String hours = "window";
        Matcher header = MARKET_RISK_HEADER_PATTERN.matcher(plain);
        if (header.find()) {
            symbol = firstNonBlank(header.group(1), symbol).trim();
            hours = firstNonBlank(header.group(2), hours).trim();
        }
        String status = humanizeMarketStatus(firstNonBlank(lineValue(plain, "狀態"), "WATCH"));
        String counts = humanizeMarketCounts(firstNonBlank(lineValue(plain, "摘要"), "MARKET_SIGNAL 0"));
        String reasons = humanizeMarketReasons(firstNonBlank(lineValue(plain, "原因"), "無"));
        return threeLines(
                "【市場背景】" + symbol + " " + hours + ": " + status,
                "訊號=" + shorten(counts, 56) + "; 原因=" + shorten(reasons, 58),
                "用途=風險背景，不是買賣指令；詳情=市場明細/MCP。");
    }

    private String compactAttentionMarketSignal(String plain) {
        Matcher header = ATTENTION_HEADER_PATTERN.matcher(plain);
        if (!header.find()) {
            return null;
        }
        String rawTitle = cleanToken(header.group(1));
        String symbol = firstNonBlank(lineValue(plain, "symbol"), "BTCUSDT");
        String trigger = "N/A";
        String threshold = "門檻=N/A";
        Matcher triggerMatcher = ATTENTION_TRIGGER_PATTERN.matcher(plain);
        if (triggerMatcher.find()) {
            trigger = cleanToken(triggerMatcher.group(1));
            threshold = humanizeThreshold(triggerMatcher.group(2));
        }
        String indicator = humanizeAttentionCtx(lineValue(plain, "ctx"));
        return threeLines(
                "【市場背景】" + symbol + ": " + humanizeAttentionTitle(rawTitle),
                "觸發值=" + trigger + "; " + threshold + "; " + indicator,
                "用途=觀察，不是買賣指令；詳情=市場背景/MCP。");
    }

    private static String humanizeAttentionTitle(String title) {
        if (title == null || title.isBlank()) {
            return "市場觀察";
        }
        String normalized = title.trim();
        if (normalized.contains("：")) {
            String detail = normalized.substring(normalized.indexOf("：") + 1).trim();
            if (normalized.contains("軋空")) {
                return "軋空觀察：" + humanizeInlineMarketTokens(detail);
            }
            return "市場觀察：" + humanizeInlineMarketTokens(detail);
        }
        if (normalized.contains(":")) {
            String detail = normalized.substring(normalized.indexOf(":") + 1).trim();
            if (normalized.contains("軋空")) {
                return "軋空觀察：" + humanizeInlineMarketTokens(detail);
            }
            return "市場觀察：" + humanizeInlineMarketTokens(detail);
        }
        return humanizeInlineMarketTokens(normalized.replace("Attention", "市場觀察"));
    }

    private static String humanizeThreshold(String rawThreshold) {
        if (rawThreshold == null || rawThreshold.isBlank()) {
            return "門檻=N/A";
        }
        String normalized = rawThreshold.trim()
                .replace("threshold >", "大於")
                .replace("threshold <", "小於")
                .replace("×", " 倍");
        return "門檻=" + normalized;
    }

    private static boolean containsAttentionMarker(String plain) {
        return plain != null && plain.toLowerCase(Locale.ROOT).contains("attention:");
    }

    private static String humanizeMarketStatus(String status) {
        if (status == null || status.isBlank()) {
            return "觀察";
        }
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "WATCH" -> "觀察";
            case "REVIEW", "REVIEW_POSITION" -> "檢查倉位";
            case "ACTIONABLE", "ACTIONABLE_TRADE" -> "有交易提醒";
            case "NO_ACTION" -> "無需操作";
            default -> humanizeInlineMarketTokens(status);
        };
    }

    private static String humanizeMarketCounts(String counts) {
        if (counts == null || counts.isBlank()) {
            return "市場訊號 0";
        }
        return humanizeInlineMarketTokens(counts)
                .replace(" / ", "；");
    }

    private static String humanizeMarketReasons(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return "無";
        }
        return humanizeInlineMarketTokens(reasons)
                .replace(" / ", "；");
    }

    private static String humanizeInlineMarketTokens(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        return value
                .replace("market-signal:risk-summary", "風險摘要")
                .replace("market-signal:market-flip", "市場翻轉")
                .replace("market-signal:polymarket", "Polymarket 背景")
                .replace("market-signal:put-call", "Put/Call")
                .replace("market-signal:macro", "宏觀/MEI")
                .replace("market-signal:whale", "鯨魚資金")
                .replace("market-signal:gemini-advisor", "AI 市場觀點")
                .replace("MARKET_SIGNAL", "市場訊號")
                .replace("ACTIONABLE_TRADE", "交易提醒")
                .replace("routes", "來源路由")
                .replace("PutCall", "Put/Call")
                .replace("Macro/MEI", "宏觀/MEI")
                .replace("REVIEW_POSITION", "檢查倉位")
                .replace("WATCH", "觀察")
                .replace("whale_buy", "鯨魚買單占比")
                .replace("OI", "未平倉量");
    }

    private static String humanizeAttentionCtx(String ctx) {
        if (ctx == null || ctx.isBlank()) {
            return "指標=N/A";
        }
        Matcher matcher = CTX_PAIR_PATTERN.matcher(ctx);
        if (!matcher.find()) {
            return "指標=" + shorten(ctx, 56);
        }
        String key = matcher.group(1);
        String value = matcher.group(2);
        return "指標=" + humanizeIndicatorKey(key) + "=" + formatIndicatorValue(key, value);
    }

    private static String humanizeIndicatorKey(String key) {
        return switch (key) {
            case "oi_change_pct_1h" -> "1 小時未平倉量變化";
            case "whale_buy_ratio" -> "鯨魚買單占比";
            case "long_short_ratio" -> "多空比";
            case "volume_ratio" -> "成交量倍率";
            default -> key.replace("mih:", "").replace('_', ' ');
        };
    }

    private static String formatIndicatorValue(String key, String rawValue) {
        try {
            double value = Double.parseDouble(rawValue);
            if (key.endsWith("_ratio") && Math.abs(value) <= 2.0) {
                return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
            }
            if (key.contains("_pct")) {
                return String.format(Locale.ROOT, "%.4f%%", value);
            }
            return String.format(Locale.ROOT, "%.4f", value);
        } catch (Exception ignored) {
            return rawValue;
        }
    }

    private static String threeLines(String line1, String line2, String line3) {
        return shorten(line1, OPERATOR_LINE_LIMIT)
                + "\n" + shorten(line2, OPERATOR_LINE_LIMIT)
                + "\n" + shorten(line3, OPERATOR_LINE_LIMIT);
    }

    private static String firstBlockers(String plain) {
        Matcher matcher = BLOCKER_LIST_PATTERN.matcher(plain);
        if (!matcher.find()) {
            return null;
        }
        String[] items = matcher.group(1).split(",");
        List<String> out = new ArrayList<>();
        for (String item : items) {
            String cleaned = cleanToken(item);
            if (!cleaned.isBlank()) {
                out.add(humanizeReason(cleaned));
            }
            if (out.size() >= 2) {
                break;
            }
        }
        return out.isEmpty() ? null : String.join("/", out);
    }

    private static String tokenValue(String text, String key) {
        Matcher matcher = Pattern.compile("(?m)(?:^|\\s)" + Pattern.quote(key) + "\\s*=\\s*([^\\s\\n]+)")
                .matcher(text);
        return matcher.find() ? cleanToken(matcher.group(1)) : null;
    }

    private static String lineValue(String text, String key) {
        Matcher matcher = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*[:：=]\\s*([^\\n]+)")
                .matcher(text);
        return matcher.find() ? cleanToken(matcher.group(1)) : null;
    }

    private static String tailValue(String text, String key) {
        Matcher matcher = Pattern.compile("(?is)(?:^|\\s)" + Pattern.quote(key) + "\\s*=\\s*(.+)$")
                .matcher(text);
        return matcher.find() ? cleanToken(matcher.group(1)) : null;
    }

    private static String symbolFromText(String text) {
        String explicit = tokenValue(text, "symbol");
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        Matcher matcher = Pattern.compile("\\b[A-Z0-9]{2,20}USDT\\b").matcher(text == null ? "" : text);
        return matcher.find() ? matcher.group() : "UNKNOWN";
    }

    private static String between(String text, String start, String end) {
        if (text == null || start == null || end == null) {
            return null;
        }
        int startIndex = text.indexOf(start);
        if (startIndex < 0) {
            return null;
        }
        int valueStart = startIndex + start.length();
        int endIndex = text.indexOf(end, valueStart);
        if (endIndex < 0) {
            return null;
        }
        return cleanToken(text.substring(valueStart, endIndex));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String humanizeBoolean(String value) {
        if (value == null || value.isBlank()) {
            return "未知";
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "是" -> "是";
            case "false", "no", "否" -> "否";
            default -> value.trim();
        };
    }

    private static String humanizeBlockerList(String blockers) {
        if (blockers == null || blockers.isBlank()) {
            return "無";
        }
        String cleaned = blockers.trim();
        if ("[]".equals(cleaned) || "N/A".equalsIgnoreCase(cleaned)) {
            return "無";
        }
        cleaned = cleaned.replace("[", "").replace("]", "");
        String[] parts = cleaned.split(",");
        List<String> out = new ArrayList<>();
        for (String part : parts) {
            String item = cleanToken(part);
            if (!item.isBlank()) {
                out.add(humanizeExecutionToken(humanizeReason(item)));
            }
            if (out.size() >= 3) {
                break;
            }
        }
        return out.isEmpty() ? "無" : String.join(" / ", out);
    }

    private static String humanizeExecutionToken(String value) {
        if (value == null || value.isBlank()) {
            return "未知";
        }
        String cleaned = cleanToken(value);
        String upper = cleaned.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "AUTO_APPROVED", "AUTO_APPROVED_SCORE_BUY_PRE_POSITION",
                    "AUTO_APPROVED_SCORE_BUY_CONFIRMED_DEPLOY",
                    "AUTO_APPROVED_SCORE_BUY_POST_SCOUT_ADD",
                    "AUTO_APPROVED_EVENT_RISK_OVERRIDE" -> "自動核准";
            case "HUMAN_APPROVAL_REQUIRED" -> "需要人工核准";
            case "READY_TO_EXPLORE" -> "可探索";
            case "AUTO_EXECUTE_TINY_LIVE" -> "準備執行 Tiny-live";
            case "WAIT_EVENT_RISK_OVERRIDE" -> "等待事件風險覆蓋";
            case "HALT_AND_NOTIFY" -> "暫停並通知";
            case "WAIT_SIGNAL_BUY" -> "等待買入訊號";
            case "WATCH_SIGNAL_NEAR_BUY_THRESHOLD" -> "接近買點但未過門檻";
            case "WAIT_OPEN_POSITION" -> "等待現有倉位結束";
            case "WAIT_DAILY_CAP_RESET" -> "等待每日額度重置";
            case "WAIT_EV_PASS" -> "等待 EV 通過";
            case "WAIT_OCO_HEALTH" -> "等待 OCO 健康檢查";
            case "WAIT_OUTCOME_MATURITY" -> "等待結果成熟";
            case "WATCH_GOVERNANCE_TOO_STRICT" -> "治理可能過嚴";
            case "WATCH_GOVERNANCE_TOO_LOOSE" -> "治理可能過鬆";
            case "ERROR_NEEDS_OPERATOR" -> "需要人工處理";
            case "WAIT FOR NEXT CANDIDATE WITH EV PASS" -> "等待下一個 EV 通過的候選";
            case "WAIT FOR A CURRENT BUY CANDIDATE BEFORE EVALUATING TINY-LIVE EXECUTION" -> "等待目前買入候選出現";
            case "WAIT FOR DAILY EXPLORATION CAP RESET" -> "等待每日探索額度重置";
            case "OPERATOR REVIEW REQUIRED" -> "需要人工檢查";
            case "NO AUTOMATIC TRADING/OCO/STRATEGY/GRID/FUND ACTION IS PERFORMED BY THIS MONITOR" -> "此監控不會自動改交易、OCO、策略、Grid 或資金";
            case "STAGE_HALTED", "HALTED" -> "已暫停";
            case "PRODUCTION_TINY_LIVE_1_PER_DAY" -> "生產 Tiny-live 每日 1 單";
            case "DISABLED" -> "已停用";
            default -> cleaned
                    .replace("CRITICAL_UNPROTECTED", "無保護成交高風險")
                    .replace("OCO_ATTACH_FAILED", "OCO 掛載失敗")
                    .replace("OCO_HEALTH_ABNORMAL", "OCO 健康異常")
                    .replace("OCO_PREFLIGHT_FAIL", "OCO 預檢失敗")
                    .replace("SYSTEM_HEALTH_CRITICAL", "系統健康異常")
                    .replace("MAX_TINY_LIVE_ORDERS_TODAY_REACHED", "今日 Tiny-live 額度已滿")
                    .replace("DUPLICATE_BAR", "同一根 K 線重複")
                    .replace("PREVIEW_NOT_READY", "預覽未就緒")
                    .replace("EVENT_RISK_OVERRIDE_TOKEN_INVALID", "事件風險覆蓋 token 無效")
                    .replace("APPROVAL_TOKEN_INVALID", "核准 token 無效")
                    .replace("APPROVAL_TOKEN_ALREADY_USED", "核准 token 已使用")
                    .replace("MAX_NOTIONAL_EXCEEDED", "金額超過上限")
                    .replace("SCOPE_NOT_ALLOWLISTED", "範圍不在允許清單")
                    .replace("SCORE_BUY", "SCORE_BUY")
                    .replace("TINY_LIVE", "Tiny-live")
                    .replace("ORDER_FAILED", "下單失敗")
                    .replace("EXECUTED_OCO_ATTACHED", "已成交並掛 OCO")
                    .replace("ORDER_PLACEMENT_STARTED", "開始下單")
                    .replace('_', ' ');
        };
    }

    private static String strategySuffix(String strategy) {
        return strategy == null || strategy.isBlank() ? "" : " #" + strategy.trim();
    }

    private static String strategyLabel(String strategy) {
        return strategy == null || strategy.isBlank() ? "N/A" : "#" + strategy.trim();
    }

    private static String sideSuffix(String side) {
        return side == null || side.isBlank() ? "" : " " + side.trim();
    }

    private static boolean verdictNeedsHuman(String verdict) {
        if (verdict == null) {
            return true;
        }
        String normalized = verdict.toUpperCase(Locale.ROOT);
        return normalized.contains("REVIEW") || normalized.contains("ACTION") || normalized.contains("HALT");
    }

    private static String humanizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "無明確阻擋";
        }
        String normalized = cleanToken(reason).toUpperCase(Locale.ROOT);
        if (normalized.contains("RUNTIME_EVIDENCE_NOT_AVAILABLE")) return "缺 runtime evidence";
        if (normalized.contains("PRE_POSITION_NOT_READY")) return "預備倉未就緒";
        if (normalized.contains("EXECUTION_POLICY_NOT_READY")) return "執行策略未就緒";
        if (normalized.contains("NO_OPEN_SCOUT")) return "無 scout 倉";
        if (normalized.contains("HARD_GATE_NOT_PASS")) return "硬門檻未過";
        if (normalized.contains("DAILY_SCORE_BUY_NOT_CONFIRMED")) return "日線未確認";
        if (normalized.contains("CONFIRMED_DEPLOY_NOT_READY")) return "日線部署未就緒";
        if (normalized.contains("NOTIONAL_BELOW_EXCHANGE_MIN")) return "低於交易所最小額";
        if (normalized.contains("WATCH_SIGNAL_NEAR_BUY_THRESHOLD")) return "接近買點但未過門檻";
        if (normalized.contains("OCO_ABNORMAL")) return "OCO 異常";
        if (normalized.contains("PRODUCTION_ENABLED")) return "生產模式已啟用";
        return shorten(cleanToken(reason), 72);
    }

    private static String cleanToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .replaceAll("^[\"'\\[]+", "")
                .replaceAll("[\"'\\].,;。]+$", "")
                .trim();
    }

    private static String shorten(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength - 3)).trim() + "...";
    }

    private String bucketTitle(Bucket bucket) {
        return switch (bucket) {
            case ACTIONABLE_TRADE, GRID_INCIDENT -> "交易保護";
            case MARKET_SIGNAL -> "市場背景";
            case OPS_AUDIT, SYSTEM_NOISE -> "系統雜訊";
            default -> "通知";
        };
    }

    private String defaultAction(Bucket bucket) {
        return switch (bucket) {
            case ACTIONABLE_TRADE -> "需要檢查";
            case GRID_INCIDENT -> "人工檢查";
            case MARKET_SIGNAL -> "觀察";
            case OPS_AUDIT, SYSTEM_NOISE -> "無需操作";
            default -> "觀察";
        };
    }

    private String defaultTags(Bucket bucket) {
        return switch (bucket) {
            case ACTIONABLE_TRADE -> "交易提醒 / 需檢查";
            case GRID_INCIDENT -> "網格異常 / 人工檢查";
            case MARKET_SIGNAL -> "市場背景 / 觀察";
            case OPS_AUDIT -> "系統稽核 / 無需操作";
            case SYSTEM_NOISE -> "系統雜訊 / 無需操作";
            default -> "OTHER";
        };
    }

    @Override
    public void sendMcpMasterApprovalRequest(String grantRequestId, String sessionShortHash,
                                             String firstToolName, Instant expiresAt) {
        String tool = firstToolName == null || firstToolName.isBlank() ? "未知工具" : firstToolName;
        String message = String.join("\n",
                "MCP 外部 AI 授權請求",
                "",
                "狀態: 等待人工批准",
                "工具: " + tool,
                "會話指紋: " + sessionShortHash,
                "授權請求 ID: " + grantRequestId,
                "到期時間: " + expiresAt,
                "",
                "請確認這是你正在 ChatGPT / External-AI MCP 發起的操作。",
                "批准後，符合相同授權範圍的呼叫可在 TTL 內重試。"
        );

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(List.of(new InlineKeyboardRow(List.of(
                        InlineKeyboardButton.builder()
                                .text("批准")
                                .callbackData("mcp_master_approve:" + grantRequestId)
                                .build(),
                        InlineKeyboardButton.builder()
                                .text("拒絕")
                                .callbackData("mcp_master_reject:" + grantRequestId)
                                .build()
                ))))
                .build();

        sendChannelMessageWithKeyboard(message, false, keyboard, "mcp-master-approval", "WARN");
    }

    private boolean shouldSuppressNoise(String message, String source, String level) {
        if (!noiseReductionEnabled || message == null) {
            return false;
        }
        Bucket bucket = notificationClassifier.classify(message, source, level);
        if (!notificationClassifier.isSuppressible(bucket)) {
            return false;
        }
        java.time.Duration cooldown;
        if (bucket == Bucket.MARKET_SIGNAL) {
            cooldown = java.time.Duration.ofMinutes(Math.max(0, marketSignalCooldownMinutes));
        } else if (bucket == Bucket.GRID_INCIDENT) {
            cooldown = java.time.Duration.ofMinutes(Math.max(0, gridIncidentCooldownMinutes));
        } else if (bucket == Bucket.OPS_AUDIT || bucket == Bucket.SYSTEM_NOISE) {
            cooldown = java.time.Duration.ofMinutes(Math.max(0, systemNoiseCooldownMinutes));
        } else {
            cooldown = java.time.Duration.ZERO;
        }
        if (cooldown.isZero() || cooldown.isNegative()) {
            return false;
        }
        String key = bucket + ":" + notificationClassifier.routingKey(message, source, level);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        java.time.LocalDateTime previous = noiseBucketLastSent.putIfAbsent(key, now);
        if (previous == null) {
            return false;
        }
        if (java.time.Duration.between(previous, now).compareTo(cooldown) >= 0) {
            noiseBucketLastSent.put(key, now);
            return false;
        }
        log.debug("[TgNoiseReduction] suppressed bucket={} key={} cooldown={}m",
                bucket, key, cooldown.toMinutes());
        return true;
    }

    private void logAsync(String message, boolean useHtml, String source, String level) {
        try {
            TgNotificationLog log2 = new TgNotificationLog(message, level, source, null, null, useHtml);
            notificationLogRepo.save(log2);
        } catch (Exception e) {
            log.warn("[TgLog] Failed to save notification log: {}", e.getMessage());
        }
    }

    /** 由背景執行緒定期呼叫；每次最多取 DRAIN_BATCH 條合併後送出。 */
    private void drainChannelQueue() {
        List<QueuedMessage> batch = new ArrayList<>(DRAIN_BATCH);
        channelQueue.drainTo(batch, DRAIN_BATCH);
        if (batch.isEmpty()) return;

        String channelId = telegramBotConfig.getChannelId();
        if (channelId == null || channelId.isEmpty()) return;

        boolean useHtml = batch.stream().anyMatch(QueuedMessage::useHtml);
        String text = batch.size() == 1
                ? batch.get(0).message()
                : batch.stream()
                       .map(QueuedMessage::message)
                       .collect(Collectors.joining("\n─────────────────────────\n"));

        List<ChannelPayload> payloads = toChannelPayloads(text, useHtml);

        try {
            sendChannelPayloads(channelId, payloads);
            if (batch.size() > 1) {
                log.info("[TgQueue] Merged {} messages into {} payload(s) → sent to channel: {}",
                        batch.size(), payloads.size(), channelId);
            } else if (payloads.size() > 1) {
                log.info("[TgQueue] Split long message length={} into {} payload(s) → sent to channel: {}",
                        text.length(), payloads.size(), channelId);
            } else {
                log.info("Telegram message sent successfully to channel: {}", channelId);
            }
        } catch (TelegramApiRequestException e) {
            Long newChatId = (e.getParameters() != null) ? e.getParameters().getMigrateToChatId() : null;
            if (newChatId != null) {
                log.warn("[群組升級] chatId={} 已升級為 Supergroup，新 chatId={}。" +
                        "請更新環境變數 TELEGRAM_CHANNEL_ID={}，正在用新 ID 重試...",
                        channelId, newChatId, newChatId);
                try {
                    sendChannelPayloads(newChatId.toString(), payloads);
                    log.info("重試成功：訊息已發送至新 chatId={}", newChatId);
                } catch (TelegramApiException retryEx) {
                    log.error("重試失敗 newChatId={}: {}", newChatId, retryEx.getMessage(), retryEx);
                }
            } else {
                log.error("Failed to send Telegram message to channel {}: {}", channelId, e.getMessage(), e);
            }
        } catch (Exception e) {
            log.error("[TgQueue] Failed to send channel message: {}", e.getMessage(), e);
        }
    }

    private void sendChannelPayloads(String channelId, List<ChannelPayload> payloads) throws TelegramApiException {
        for (ChannelPayload payload : payloads) {
            doSendToChannel(channelId, payload.message(), payload.useHtml());
        }
    }

    static List<ChannelPayload> toChannelPayloads(String message, boolean useHtml) {
        String deliverable = useHtml ? stripHtmlTags(message) : message;
        if (deliverable == null || deliverable.length() <= TELEGRAM_MESSAGE_LIMIT) {
            return List.of(new ChannelPayload(deliverable, false));
        }

        List<String> parts = splitPlainText(deliverable, TELEGRAM_CHUNK_BODY_LIMIT);
        if (parts.size() == 1 && parts.get(0).length() <= TELEGRAM_MESSAGE_LIMIT) {
            return List.of(new ChannelPayload(parts.get(0), false));
        }

        List<ChannelPayload> payloads = new ArrayList<>(parts.size());
        int total = parts.size();
        for (int i = 0; i < total; i++) {
            String prefix = String.format("(%d/%d)%n", i + 1, total);
            String text = prefix + parts.get(i);
            payloads.add(new ChannelPayload(text, false));
        }
        return payloads;
    }

    private static List<String> splitPlainText(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return List.of(text);
        }

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int hardEnd = Math.min(start + maxLen, text.length());
            int end = chooseChunkEnd(text, start, hardEnd);
            String part = text.substring(start, end).stripTrailing();
            if (!part.isEmpty()) {
                parts.add(part);
            }
            start = end;
            while (start < text.length() && (text.charAt(start) == '\n' || text.charAt(start) == '\r')) {
                start++;
            }
        }
        return parts;
    }

    private static int chooseChunkEnd(String text, int start, int hardEnd) {
        if (hardEnd >= text.length()) {
            return text.length();
        }
        int minUsefulBreak = start + Math.min(200, Math.max(1, (hardEnd - start) / 4));
        int newline = text.lastIndexOf('\n', hardEnd - 1);
        if (newline >= minUsefulBreak) {
            return newline + 1;
        }
        int space = text.lastIndexOf(' ', hardEnd - 1);
        if (space >= minUsefulBreak) {
            return space + 1;
        }
        return hardEnd;
    }

    private static String stripHtmlTags(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("<[^>]+>", "");
    }

    private void doSendToChannel(String chatId, String message, boolean useHtml) throws TelegramApiException {
        doSendToChannel(chatId, message, useHtml, null);
    }

    private void doSendToChannel(String chatId, String message, boolean useHtml,
                                 InlineKeyboardMarkup keyboard) throws TelegramApiException {
        if (!telegramClientAvailable("channel message send")) {
            return;
        }
        SendMessage.SendMessageBuilder builder = SendMessage.builder()
                .chatId(chatId)
                .text(message);
        if (useHtml) {
            builder.parseMode(ParseMode.HTML);
        }
        if (keyboard != null) {
            builder.replyMarkup(keyboard);
        }
        telegramClient.execute(builder.build());
    }
    
    @Override
    public void sendMessageToUser(Long chatId, String message) {
        sendMessageToUser(chatId, message, false);
    }
    
    @Override
    public void sendMessageToUser(Long chatId, String message, boolean useHtml) {
        try {
            if (chatId == null) {
                log.warn("Telegram chat ID is null, skipping message send");
                return;
            }
            if (!telegramClientAvailable("user message send")) {
                return;
            }
            
            SendMessage.SendMessageBuilder builder = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(message);
            if (useHtml) {
                builder.parseMode(ParseMode.HTML);
            }
            // 发送给用户
            telegramClient.execute(builder.build());
            
            log.info("Telegram message sent to user {} successfully", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send Telegram message to user {}: {}", chatId, e.getMessage(), e);
            throw new RuntimeException("Failed to send Telegram message: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void sendMessageWithKeyboard(Long chatId, String message, InlineKeyboardMarkup keyboard) {
        try {
            if (chatId == null) {
                log.warn("Telegram chat ID is null, skipping message send");
                return;
            }
            if (!telegramClientAvailable("user keyboard message send")) {
                return;
            }
            
            SendMessage sendMessage = SendMessage.builder()
                    .chatId(chatId.toString())
                    .text(message)
                    .parseMode(ParseMode.HTML)
                    .replyMarkup(keyboard)
                    .build();
            
            // 发送给用户
            telegramClient.execute(sendMessage);
            
            log.info("Message with keyboard sent to user: {}", chatId);
        } catch (TelegramApiException e) {
            log.error("Failed to send message with keyboard to user: {}", chatId, e);
            throw new RuntimeException("Failed to send message with keyboard: " + e.getMessage(), e);
        }
    }
    
    @Override
    public void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert) {
        try {
            if (!telegramClientAvailable("callback query answer")) {
                return;
            }
            AnswerCallbackQuery answer = AnswerCallbackQuery.builder()
                    .callbackQueryId(callbackQueryId)
                    .text(text)
                    .showAlert(showAlert)
                    .build();
            
            // 回答 callback query
            telegramClient.execute(answer);
            
            log.info("Callback query answered: {}", callbackQueryId);
        } catch (TelegramApiException e) {
            log.error("Failed to answer callback query: {}", callbackQueryId, e);
            // Callback query 失败通常不需要抛出异常，因为用户可能已经看到结果
        }
    }

    // ── Pinned channel messages (edit-in-place) ──────────────────────────
    // 同一 key 在頻道內永遠只保留 1 則,後續呼叫 edit 原訊息 (48h TG 限制 + 刪除
    // 容忍:fallback 到重發)。bypass queue 直接 execute,因為需要拿到回傳的
    // Message.messageId 來記錄。呼叫頻率極低 (deploy 或重啟時),不會壓到 TG rate limit。

    @Override
    public void sendOrEditPinned(String key, String message, boolean useHtml, boolean pinToTop) {
        String channelId = telegramBotConfig.getChannelId();
        if (channelId == null || channelId.isEmpty()) {
            log.warn("[TgPinned] channel ID not configured, key={}", key);
            return;
        }

        Integer existingId = pinnedStore.get(key);
        if (existingId != null) {
            try {
                if (!telegramClientAvailable("pinned message edit")) {
                    return;
                }
                EditMessageText.EditMessageTextBuilder<?, ?> b = EditMessageText.builder()
                        .chatId(channelId)
                        .messageId(existingId)
                        .text(message);
                if (useHtml) b.parseMode(ParseMode.HTML);
                telegramClient.execute(b.build());
                pinMessage(channelId, existingId, key, pinToTop);
                log.info("[TgPinned] Edited key={} msgId={}", key, existingId);
                return;
            } catch (TelegramApiException e) {
                // 常見失敗: "message is not modified" (內容完全相同) / "message to edit not found"
                // (訊息被刪 / > 48h) → fallback 發新的。"not modified" 實際上成功(訊息未變),
                // 但為簡化處理,一律 fallback 到重發。重發的新 id 覆寫 store。
                log.warn("[TgPinned] Edit failed for key={} msgId={}: {} → will send new",
                        key, existingId, e.getMessage());
            }
        }

        // Send new message and record its id
        try {
            if (!telegramClientAvailable("pinned message send")) {
                return;
            }
            SendMessage.SendMessageBuilder sb = SendMessage.builder()
                    .chatId(channelId)
                    .text(message);
            if (useHtml) sb.parseMode(ParseMode.HTML);
            Message sent = telegramClient.execute(sb.build());
            if (sent != null && sent.getMessageId() != null) {
                pinnedStore.put(key, sent.getMessageId());
                savePinnedStore();
                pinMessage(channelId, sent.getMessageId(), key, pinToTop);
                log.info("[TgPinned] Sent new message key={} msgId={}", key, sent.getMessageId());
            }
        } catch (TelegramApiException e) {
            log.error("[TgPinned] Send failed for key={}: {}", key, e.getMessage(), e);
        }
    }

    @Override
    public void removePinnedKeys(String... keys) {
        if (keys == null || keys.length == 0) {
            return;
        }

        boolean changed = false;
        for (String key : keys) {
            if (key == null || key.isBlank()) {
                continue;
            }
            Integer removed = pinnedStore.remove(key);
            if (removed != null) {
                changed = true;
                log.info("[TgPinned] Removed obsolete local key={} msgId={}", key, removed);
            }
        }

        if (changed) {
            savePinnedStore();
        }
    }

    private void pinMessage(String channelId, Integer messageId, String key, boolean pinToTop) {
        if (!pinToTop || messageId == null) {
            return;
        }

        try {
            if (!telegramClientAvailable("message pin")) {
                return;
            }
            PinChatMessage pin = PinChatMessage.builder()
                    .chatId(channelId)
                    .messageId(messageId)
                    .disableNotification(true)
                    .build();
            telegramClient.execute(pin);
            log.info("[TgPinned] Pinned key={} msgId={}", key, messageId);
        } catch (TelegramApiException e) {
            log.warn("[TgPinned] Pin failed for key={} msgId={}: {}", key, messageId, e.getMessage());
        }
    }

}
