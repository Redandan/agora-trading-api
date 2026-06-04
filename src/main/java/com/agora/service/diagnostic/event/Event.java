package com.agora.service.diagnostic.event;

import java.time.LocalDateTime;

/**
 * #337 統一事件抽取 — 各種來源（TG / mih threshold / decision audit / ml inference / live signal）
 * 都先 normalize 成這個 record，後續 PriceLookup + verify 邏輯共用。
 *
 * @param ts            事件時間（UTC, LocalDateTime — 與 market_indicator_history 比對請注意 #323 TZ）
 * @param direction     LONG / SHORT / NEUTRAL — 預期該事件後價格走向
 * @param payloadValue  觸發時的指標值（Double.NaN 代表無/不適用）
 * @param label         顯示用，如 "sqi=52" / "v19:BLOCK p=0.42"
 */
public record Event(
        LocalDateTime ts,
        String direction,
        double payloadValue,
        String label
) {
    public static final String LONG = "LONG";
    public static final String SHORT = "SHORT";
    public static final String NEUTRAL = "NEUTRAL";
}
