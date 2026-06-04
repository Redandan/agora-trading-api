package com.agora.util;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * #440 — 把 Gemini regime / hint 之類 IT-jargon 翻成自然中文,
 * 給 user-facing TG 訊息使用。內部 enum / log / DB 仍用原 code。
 *
 * <pre>
 * regime code      中文
 * TRENDING_UP      上升趨勢
 * TRENDING_DOWN    下降趨勢
 * SIDEWAYS         橫盤
 * VOLATILE         高波動
 * RECOVERY         回升期
 * BREAKDOWN        跌破
 * </pre>
 */
public final class RegimeI18n {

    private RegimeI18n() {}

    /** 單一 regime code → 中文。Unknown / null 透傳原值。 */
    public static String regime(String code) {
        if (code == null) return "未知";
        switch (code.trim().toUpperCase()) {
            case "TRENDING_UP":   return "上升趨勢";
            case "TRENDING_DOWN": return "下降趨勢";
            case "SIDEWAYS":      return "橫盤";
            case "VOLATILE":      return "高波動";
            case "RECOVERY":      return "回升期";
            case "BREAKDOWN":     return "跌破";
            default:              return code;
        }
    }

    /**
     * "SIDEWAYS,VOLATILE,RECOVERY" → "橫盤 / 高波動 / 回升期"。
     * 用 " / " 分隔比逗號自然(逗號與「市場形態」並列易讀)。
     */
    public static String regimeList(String csv) {
        if (csv == null || csv.isBlank()) return "";
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(RegimeI18n::regime)
                .collect(Collectors.joining(" / "));
    }
}
