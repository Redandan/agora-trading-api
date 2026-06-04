package com.agora.infra.notification;

/**
 * Cross-domain notification port. Domain code (trading, meta, market, scheduler)
 * depends on this interface, not on TelegramService — so a future swap to email,
 * web push, or Slack costs one new adapter, not 76 consumer rewrites.
 *
 * Telegram-specific operations (user DM, inline keyboards, pinned messages,
 * callback queries) stay on TelegramService — they are bot mechanics, not
 * notifications.
 */
public interface NotificationPort {

    /** Plain-text broadcast to the default channel. */
    void broadcast(String message);

    /** Broadcast with optional HTML formatting. */
    void broadcast(String message, boolean useHtml);

    /**
     * Structured alert — persisted to {@code tg_notification_log} with source + level.
     *
     * @param level INFO / WARN / CRITICAL
     */
    void alert(String message, boolean useHtml, String source, String level);
}
