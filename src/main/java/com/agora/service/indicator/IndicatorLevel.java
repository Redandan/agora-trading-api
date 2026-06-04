package com.agora.service.indicator;

/**
 * CMI Framework 標準分級。
 * 每個 CompositeIndicator 可自訂各層的閾值，但必須使用這四個等級。
 */
public enum IndicatorLevel {
    NORMAL("正常", "🟢"),
    ALERT("關注", "🟡"),    // LOG only，不發 TG
    WARNING("警告", "🟠"),  // 發 TG WARN
    CRITICAL("緊急", "🔴"); // 發 TG CRITICAL，豁免所有過濾

    public final String label;
    public final String emoji;

    IndicatorLevel(String label, String emoji) {
        this.label = label;
        this.emoji = emoji;
    }
}
