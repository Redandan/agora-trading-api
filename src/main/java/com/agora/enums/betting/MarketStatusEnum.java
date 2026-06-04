package com.agora.enums.betting;

/**
 * 市場狀態枚舉
 */
public enum MarketStatusEnum {
    /**
     * 開放中 - 可以下注
     */
    OPEN,
    
    /**
     * 已關閉 - 不能再下注，等待結算
     */
    CLOSED,
    
    /**
     * 已結算 - 已經確定結果並分配獎金
     */
    RESOLVED,
    
    /**
     * 已取消 - 市場無效，退款給所有參與者
     */
    CANCELLED
}
