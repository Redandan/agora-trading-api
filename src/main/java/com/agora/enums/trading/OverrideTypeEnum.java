package com.agora.enums.trading;

/**
 * Meta-Control override 類型。attribution 表用此 enum + override_id 定位原始 override。
 *
 * <p>Phase 1 只處理 {@link #STRATEGY_PAUSE};{@link #HINT_OVERRIDE} 預留,
 * scheduler 尚未實作對應邏輯。
 */
public enum OverrideTypeEnum {
    /** strategy_override 表中 action = 'PAUSE' 的紀錄 */
    STRATEGY_PAUSE,

    /** hint_override 表(Phase 2) */
    HINT_OVERRIDE
}
