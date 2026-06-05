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

    private record QueuedMessage(String message, boolean useHtml) {}

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
        this.telegramBotConfig    = telegramBotConfig;
        this.telegramClient       = createTelegramClient(telegramBotConfig);
        this.notificationLogRepo  = notificationLogRepo;
        this.notificationClassifier = notificationClassifier;
    }

    private TelegramClient createTelegramClient(TelegramBotConfig config) {
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
            case ACTIONABLE_TRADE -> "ACTIONABLE_TRADE / REVIEW";
            case GRID_INCIDENT -> "GRID_INCIDENT / MANUAL_CHECK";
            case MARKET_SIGNAL -> "MARKET_SIGNAL / WATCH";
            case OPS_AUDIT -> "OPS_AUDIT / NO_ACTION";
            case SYSTEM_NOISE -> "SYSTEM_NOISE / NO_ACTION";
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

        try {
            doSendToChannel(channelId, text, useHtml);
            if (batch.size() > 1) {
                log.info("[TgQueue] Merged {} messages → sent to channel: {}", batch.size(), channelId);
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
                    doSendToChannel(newChatId.toString(), text, useHtml);
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
