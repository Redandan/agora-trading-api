package com.agora.enums.trading;

/**
 * Meta-Control attribution 計算狀態。
 *
 * <p>Attribution scheduler 每小時掃剛結束的 override,對每筆嘗試算
 * counterfactual backtest。失敗或不適用的情況以此 enum 標註,供後續排錯與
 * 樣本篩選使用。
 */
public enum AttributionStatusEnum {
    /** 計算成功,alpha_contribution 欄位有效 */
    SUCCESS,

    /** Override window 短於 1 根 K 線,counterfactual backtest 無意義 */
    INSUFFICIENT_DATA,

    /** Override.symbol = NULL(適用所有 symbol),v1 不處理跨幣種 backtest 複雜度 */
    SCOPE_TOO_BROAD,

    /** BacktestService 拋 exception(K 線缺口 / 策略參數錯 / ...),詳見 error_message */
    BACKTEST_FAILED
}
