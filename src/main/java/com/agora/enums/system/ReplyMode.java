package com.agora.enums.system;

/**
 * 群組 AI 回覆模式
 */
public enum ReplyMode {

    /** 積極模式：每 N 條訊息現身一次，但單次不超過 M 分鐘內重複發文 */
    ACTIVE,

    /** 被動模式：只有被 @mention 才回覆 */
    PASSIVE,

    /** 關閉：完全靜音，連 @mention 也不回 */
    DISABLED
}
