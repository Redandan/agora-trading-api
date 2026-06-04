package com.agora.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telegram Bot 錯誤處理配置
 * 用於簡化和統一 Telegram Bot 連接錯誤的日誌輸出
 */
@Slf4j
@Configuration
public class TelegramErrorHandler {

    // 錯誤計數器（避免重複記錄相同錯誤）
    private final AtomicLong connectionErrorCount = new AtomicLong(0);
    private volatile long lastLogTime = 0;

    /**
     * 標記 JVM 進入 shutdown 階段。
     * 由 init() 註冊的 ShutdownHook 翻轉。
     *
     * <p>Shutdown 期間 background thread(OkHttp WS / HikariPool closer)嘗試
     * load 已被 ClassLoader unload 的 class → throws {@code NoClassDefFoundError}
     * /{@code ClassNotFoundException}。這是 graceful shutdown 經典 race,**無害**。
     * 用此 flag 把 ERROR 降到 DEBUG,清掉每次 deploy 的 ~10 條 log noise。
     */
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    // 日誌冷卻期（毫秒）- 相同錯誤在此期間內只記錄一次
    private static final long LOG_COOLDOWN_MS = 5 * 60 * 1000; // 5 分鐘

    @PostConstruct
    public void init() {
        // Track JVM shutdown so uncaughtException can downgrade harmless class-load races.
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> shuttingDown.set(true), "telegram-error-handler-shutdown-flag"));

        // 設置 JVM 全局未捕獲異常處理器
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (isTelegramConnectionError(throwable)) {
                logTelegramConnectionError(thread.getName(), throwable);
            } else if (isShutdownClassLoadingRace(throwable)) {
                // Background thread (OkHttp/Hikari/etc.) tried to load a class
                // mid-shutdown. Benign race; no functional impact. DEBUG only.
                log.debug("Shutdown-time class-load race in thread [{}]: {}",
                        thread.getName(), throwable.getMessage());
            } else {
                log.error("Uncaught exception in thread [{}]: {}", thread.getName(), throwable.getMessage(), throwable);
            }
        });

        log.info("Telegram error handler initialized - connection errors will be logged concisely");
    }

    /**
     * Detect the shutdown-time class-loading race documented above.
     * Conservative: requires BOTH conditions — only downgrade if shutdown actually
     * in progress AND exception is class-loader-related. A genuine missing-class
     * bug at runtime (no shutdown) keeps its ERROR severity.
     */
    private boolean isShutdownClassLoadingRace(Throwable t) {
        if (t == null || !shuttingDown.get()) return false;
        if (t instanceof NoClassDefFoundError || t instanceof ClassNotFoundException) return true;
        Throwable cause = t.getCause();
        return cause instanceof NoClassDefFoundError || cause instanceof ClassNotFoundException;
    }
    
    /**
     * 判斷是否為 Telegram 連接錯誤
     */
    private boolean isTelegramConnectionError(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        
        String message = throwable.getMessage();
        if (message == null) {
            message = "";
        }
        
        // 檢查錯誤類型和消息
        return throwable instanceof java.net.ConnectException
                || (message.contains("api.telegram.org") && message.contains("Network is unreachable"))
                || (message.contains("api.telegram.org") && message.contains("connect failed"))
                || throwable.getClass().getName().contains("HttpHostConnectException");
    }
    
    /**
     * 記錄簡化的 Telegram 連接錯誤
     */
    private void logTelegramConnectionError(String threadName, Throwable throwable) {
        long currentTime = System.currentTimeMillis();
        long errorCount = connectionErrorCount.incrementAndGet();
        
        // 檢查是否在冷卻期內
        if (currentTime - lastLogTime < LOG_COOLDOWN_MS) {
            // 冷卻期內，只增加計數，不記錄日誌
            return;
        }
        
        // 記錄簡化的錯誤信息
        lastLogTime = currentTime;
        
        String rootCause = getRootCauseMessage(throwable);
        
        log.warn("⚠️ Telegram Bot 連接失敗 [Thread: {}] - {} (錯誤次數: {}, 最近 {} 分鐘內)", 
                threadName, 
                rootCause, 
                errorCount,
                LOG_COOLDOWN_MS / 60000);
        
        log.debug("Telegram 連接錯誤詳情: ", throwable); // DEBUG 級別才輸出完整堆棧
    }
    
    /**
     * 獲取根本原因消息
     */
    private String getRootCauseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        
        String message = cause.getMessage();
        if (message == null || message.isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        
        return message;
    }
    
    /**
     * 手動記錄 Telegram API 異常（供外部調用）
     */
    public static void logTelegramApiException(String operation, TelegramApiException e) {
        log.warn("⚠️ Telegram API 操作失敗 [{}]: {}", operation, e.getMessage());
        log.debug("Telegram API 異常詳情: ", e);
    }
    
    /**
     * 重置錯誤計數器
     */
    public void resetErrorCount() {
        long count = connectionErrorCount.getAndSet(0);
        if (count > 0) {
            log.info("✅ Telegram Bot 連接恢復正常，已重置錯誤計數器（之前累積 {} 次錯誤）", count);
        }
    }
}
