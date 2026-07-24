package com.agora.service;

import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;

public interface TelegramService {
    /**
     * 發送消息到 Telegram 頻道（純文本）
     *
     * @param message 要發送的消息
     */
    void sendMessage(String message);
    
    /**
     * 發送消息到 Telegram 頻道（支持 HTML 格式）
     *
     * @param message 要發送的消息
     * @param useHtml 是否使用 HTML 格式（true: HTML, false: 純文本）
     */
    void sendMessage(String message, boolean useHtml);

    /**
     * 發送告警消息並記錄到 tg_notification_log（CMI Framework 專用）。
     *
     * @param message 訊息內容
     * @param useHtml 是否 HTML 格式
     * @param source  發送方，例如 "SqiIndicator" / "ShortSqueezeAlert"
     * @param level   告警級別：INFO / WARN / CRITICAL
     */
    void sendAlert(String message, boolean useHtml, String source, String level);

    /**
     * 發送帶 inline keyboard 的消息到預設 Telegram 頻道。
     *
     * <p>這是 Telegram bot mechanic，專供需要 callback buttons 的通知使用。
     *
     * @param message 訊息內容
     * @param useHtml 是否 HTML 格式
     * @param keyboard Inline 鍵盤
     * @param source  發送方
     * @param level   告警級別
     */
    void sendChannelMessageWithKeyboard(String message, boolean useHtml, InlineKeyboardMarkup keyboard,
                                        String source, String level);

    /**
     * 發送消息到 Telegram 用戶（私聊）
     *
     * @param chatId Telegram 用戶的 Chat ID
     * @param message 要發送的消息
     * @param useHtml 是否使用 HTML 格式
     */
    void sendMessageToUser(Long chatId, String message, boolean useHtml);
    
    /**
     * 發送消息到 Telegram 用戶（私聊，純文本）
     *
     * @param chatId Telegram 用戶的 Chat ID
     * @param message 要發送的消息
     */
    void sendMessageToUser(Long chatId, String message);
    
    /**
     * 發送帶鍵盤的消息到 Telegram 用戶
     *
     * @param chatId Telegram 用戶的 Chat ID
     * @param message 要發送的消息
     * @param keyboard Inline 鍵盤
     */
    void sendMessageWithKeyboard(Long chatId, String message, InlineKeyboardMarkup keyboard);
    
    /**
     * 回答 callback query
     *
     * @param callbackQueryId Callback Query ID
     * @param text 回答文本
     * @param showAlert 是否顯示為警告
     */
    void answerCallbackQuery(String callbackQueryId, String text, boolean showAlert);

    /**
     * 發送或編輯「釘子訊息」— 同一 key 在頻道只保留 1 則,後續呼叫會 edit 原訊息。
     *
     * <p>實作:message_id 持久化到 {@code ~/.agora-state/pinned_messages.properties},
     * 啟動時載入;edit 失敗 (TG 限制 48h 內可編輯 / 訊息被刪 / 文本未變) 自動 fallback 重發。
     *
     * @param key 識別鍵,例如 {@code "startup-status"}
     * @param message 要發送 / 編輯的最新內容
     * @param useHtml 是否以 HTML 解析
     * @param pinToTop 是否在送出 / 編輯後自動設為頻道置頂訊息
     */
    void sendOrEditPinned(String key, String message, boolean useHtml, boolean pinToTop);

    /**
     * 從本地 pinned message store 移除已廢棄的 key。
     *
     * <p>只清理本機追蹤狀態,不會刪除 Telegram 頻道中既有訊息。
     *
     * @param keys 要移除的舊 key
     */
    void removePinnedKeys(String... keys);

}
