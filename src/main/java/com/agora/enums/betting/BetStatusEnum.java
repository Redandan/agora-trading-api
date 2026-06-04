package com.agora.enums.betting;

/**
 * 投注狀態枚舉
 */
public enum BetStatusEnum {
    /**
     * 活躍中 - 等待市場結算
     */
    ACTIVE,
    
    /**
     * 已獲勝 - 投注選項獲勝，已發放獎金
     */
    WON,
    
    /**
     * 已失敗 - 投注選項未獲勝
     */
    LOST,
    
    /**
     * 已退款 - 市場取消，已退款
     */
    REFUNDED
}
