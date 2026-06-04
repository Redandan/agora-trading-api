package com.agora.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Service;
import org.hibernate.exception.JDBCConnectionException;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 數據庫連接錯誤告警服務
 * 用於檢測和處理數據庫連接失敗的情況，並發送 Telegram 告警通知
 */
@Slf4j
@Service
public class DatabaseConnectionAlertService {

    private final ObjectProvider<TelegramService> telegramServiceProvider;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public DatabaseConnectionAlertService(ObjectProvider<TelegramService> telegramServiceProvider) {
        this.telegramServiceProvider = telegramServiceProvider;
    }
    
    // 防止重複告警的機制：記錄上次告警時間
    private volatile long lastAlertTime = 0;
    private static final long ALERT_COOLDOWN_MS = 5 * 60 * 1000; // 5分鐘內不重複告警

    /**
     * 檢測是否為數據庫連接錯誤
     *
     * @param exception 異常對象
     * @return 如果是數據庫連接錯誤返回 true
     */
    public boolean isDatabaseConnectionError(Throwable exception) {
        if (exception == null) {
            return false;
        }

        // 檢查異常類型
        if (exception instanceof DataAccessResourceFailureException) {
            return true;
        }

        if (exception instanceof JDBCConnectionException) {
            return true;
        }

        // 檢查異常消息內容
        String message = exception.getMessage();
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            if (lowerMessage.contains("unable to acquire jdbc connection") ||
                lowerMessage.contains("connection refused") ||
                lowerMessage.contains("communications link failure") ||
                lowerMessage.contains("no connection available") ||
                lowerMessage.contains("connection is closed") ||
                lowerMessage.contains("connection timeout") ||
                lowerMessage.contains("driver has not received any packets")) {
                return true;
            }
        }

        // 遞歸檢查 cause
        Throwable cause = exception.getCause();
        if (cause != null && cause != exception) {
            return isDatabaseConnectionError(cause);
        }

        return false;
    }

    /**
     * 發送數據庫連接錯誤告警到 Telegram
     *
     * @param exception 異常對象
     * @param context   錯誤發生時的上下文信息（如服務名、方法名等）
     */
    public void sendDatabaseConnectionAlert(Throwable exception, String context) {
        // 防止重複告警：檢查冷卻時間
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastAlertTime < ALERT_COOLDOWN_MS) {
            log.debug("告警在冷卻期內，跳過發送。距離上次告警: {} 秒", 
                (currentTime - lastAlertTime) / 1000);
            return;
        }

        try {
            String errorMessage = extractErrorMessage(exception);
            String stackTrace = extractStackTrace(exception);

            String alertMessage = buildAlertMessage(errorMessage, stackTrace, context);

            // 發送告警（使用 HTML 格式）
            TelegramService telegramService = telegramServiceProvider.getObject();
            telegramService.sendMessage(alertMessage, true);
            
            // 更新上次告警時間
            lastAlertTime = currentTime;
            
            log.warn("數據庫連接錯誤告警已發送到 Telegram - Context: {}", context);
        } catch (Exception e) {
            log.error("發送數據庫連接錯誤告警失敗", e);
        }
    }

    /**
     * 提取錯誤消息
     */
    private String extractErrorMessage(Throwable exception) {
        if (exception == null) {
            return "未知錯誤";
        }

        // 優先使用根異常的消息
        Throwable rootCause = getRootCause(exception);
        String message = rootCause.getMessage();
        
        if (message == null || message.trim().isEmpty()) {
            message = rootCause.getClass().getSimpleName();
        }

        return message;
    }

    /**
     * 提取堆棧跟踪（只取前幾行）
     */
    private String extractStackTrace(Throwable exception) {
        if (exception == null) {
            return "";
        }

        StackTraceElement[] stackTrace = exception.getStackTrace();
        if (stackTrace == null || stackTrace.length == 0) {
            return "";
        }

        // 只取前 5 行堆棧跟踪
        StringBuilder sb = new StringBuilder();
        int maxLines = Math.min(5, stackTrace.length);
        for (int i = 0; i < maxLines; i++) {
            sb.append(stackTrace[i].toString()).append("\n");
        }

        return sb.toString();
    }

    /**
     * 獲取根異常
     */
    private Throwable getRootCause(Throwable exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }

    /**
     * 構建告警消息（使用 HTML 格式）
     */
    private String buildAlertMessage(String errorMessage, String stackTrace, String context) {
        String timestamp = LocalDateTime.now().format(FORMATTER);
        
        // 轉義 HTML 特殊字符
        String escapedContext = escapeHtml(context != null ? context : "未知");
        String escapedErrorMessage = escapeHtml(
            errorMessage.length() > 500 ? errorMessage.substring(0, 500) + "..." : errorMessage
        );
        
        StringBuilder message = new StringBuilder();
        message.append("<b>🚨 數據庫連接錯誤告警</b>\n\n");
        message.append("⏰ <b>時間:</b> ").append(escapeHtml(timestamp)).append("\n");
        message.append("📍 <b>位置:</b> ").append(escapedContext).append("\n\n");
        message.append("<b>❌ 錯誤信息:</b>\n");
        message.append("<pre>").append(escapedErrorMessage).append("</pre>\n\n");
        
        if (stackTrace != null && !stackTrace.trim().isEmpty()) {
            String escapedStackTrace = escapeHtml(
                stackTrace.length() > 300 ? stackTrace.substring(0, 300) + "..." : stackTrace
            );
            message.append("<b>📋 堆棧跟踪:</b>\n");
            message.append("<pre>").append(escapedStackTrace).append("</pre>\n\n");
        }
        
        message.append("<b>⚠️ 請立即檢查數據庫連接狀態！</b>");

        return message.toString();
    }
    
    /**
     * 轉義 HTML 特殊字符
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}
