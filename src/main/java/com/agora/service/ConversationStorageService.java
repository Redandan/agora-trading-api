package com.agora.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 對話存儲服務
 *
 * <p>使用 Caffeine 本地快取存儲 Telegram Bot 對話狀態與頻率限制。
 * 資料存放於 JVM 記憶體，應用重啟後狀態將清除。
 */
@Slf4j
@Service
public class ConversationStorageService {

    /** 對話狀態快取：30 分鐘無操作後自動過期 */
    private final Cache<String, Object> conversationCache = Caffeine.newBuilder()
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .maximumSize(1000)
            .build();

    /** 頻率限制快取：24 小時後自動過期 */
    private final Cache<String, Long> rateLimitCache = Caffeine.newBuilder()
            .expireAfterWrite(24, TimeUnit.HOURS)
            .maximumSize(1000)
            .build();

    private static final String CONVERSATION_PREFIX = "bot:conversation:";
    private static final String RATE_LIMIT_PREFIX   = "bot:ratelimit:";

    /**
     * 保存對話狀態（使用預設 30 分鐘過期）
     */
    public void saveConversation(Long chatId, Object conversation) {
        conversationCache.put(CONVERSATION_PREFIX + chatId, conversation);
        log.debug("Saved conversation for chatId: {}", chatId);
    }

    /**
     * 保存對話狀態（自定義過期時間，本地快取模式下忽略 ttl，使用預設值）
     */
    public void saveConversation(Long chatId, Object conversation, Duration ttl) {
        saveConversation(chatId, conversation);
    }

    /**
     * 獲取對話狀態
     */
    public <T> T getConversation(Long chatId, Class<T> type) {
        Object value = conversationCache.getIfPresent(CONVERSATION_PREFIX + chatId);
        if (value == null) {
            log.debug("No conversation found for chatId: {}", chatId);
            return null;
        }
        try {
            return type.cast(value);
        } catch (ClassCastException e) {
            log.error("Failed to cast conversation for chatId: {}, expected type: {}",
                    chatId, type.getName(), e);
            return null;
        }
    }

    /**
     * 檢查對話是否存在
     */
    public boolean hasConversation(Long chatId) {
        return conversationCache.getIfPresent(CONVERSATION_PREFIX + chatId) != null;
    }

    /**
     * 刪除對話狀態
     */
    public void deleteConversation(Long chatId) {
        conversationCache.invalidate(CONVERSATION_PREFIX + chatId);
        log.debug("Deleted conversation for chatId: {}", chatId);
    }

    /**
     * 延長對話過期時間（本地快取模式下重新寫入以重置 TTL）
     */
    public boolean extendConversation(Long chatId, Duration ttl) {
        Object value = conversationCache.getIfPresent(CONVERSATION_PREFIX + chatId);
        if (value == null) {
            return false;
        }
        conversationCache.put(CONVERSATION_PREFIX + chatId, value);
        return true;
    }

    /**
     * 清理過期對話（主動觸發 Caffeine 內部清理）
     */
    public int cleanupExpiredConversations() {
        conversationCache.cleanUp();
        log.debug("Triggered Caffeine cleanup for conversations");
        return 0;
    }

    /**
     * 獲取活躍對話數量（估算值）
     */
    public long getActiveConversationCount() {
        return conversationCache.estimatedSize();
    }

    // ── 頻率限制 ──────────────────────────────────────────────────────────────

    public void saveRateLimit(Long chatId, Long timestamp) {
        rateLimitCache.put(RATE_LIMIT_PREFIX + chatId, timestamp);
        log.debug("Saved rate limit for chatId: {}", chatId);
    }

    public Long getRateLimit(Long chatId) {
        return rateLimitCache.getIfPresent(RATE_LIMIT_PREFIX + chatId);
    }

    public void deleteRateLimit(Long chatId) {
        rateLimitCache.invalidate(RATE_LIMIT_PREFIX + chatId);
    }
}
