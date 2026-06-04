package com.agora.enums.betting;

/**
 * 市場選項狀態枚舉
 * 用於時間驅動的狀態管理
 */
public enum MarketOptionStatusEnum {
    /**
     * 開放中 - 可以下注
     */
    OPEN,
    
    /**
     * 鎖定 - 時間已過，不可再下注
     */
    LOCKED,
    
    /**
     * 已結算為獲勝
     */
    RESOLVED_YES,
    
    /**
     * 已結算為失敗
     */
    RESOLVED_NO
}
