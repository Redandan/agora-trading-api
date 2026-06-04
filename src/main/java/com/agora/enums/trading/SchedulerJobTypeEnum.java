package com.agora.enums.trading;

import lombok.Getter;

/**
 * 定時任務類型枚舉
 */
@Getter
public enum SchedulerJobTypeEnum {
    
    /**
     * 質押每日結算
     */
    STAKING_SETTLEMENT("質押每日結算", "每日下午3點執行，處理質押收益發放"),
    
    /**
     * 充值過期處理
     */
    EXPIRED_RECHARGES("充值過期處理", "每15分鐘執行一次，標記過期的充值記錄"),
    
    /**
     * 訂單自動關閉
     */
    CLOSE_EXPIRED_ORDERS("訂單自動關閉", "每天凌晨2點執行，關閉過期訂單");
    
    /**
     * 任務名稱
     */
    private final String displayName;
    
    /**
     * 任務描述
     */
    private final String description;
    
    SchedulerJobTypeEnum(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }
}
