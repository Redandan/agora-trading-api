package com.agora.enums.trading;

/**
 * Meta-Control attribution 計算狀態。
 *
 * <p>保留給既有 attribution 歷史列做 schema 相容性。原 attribution
 * scheduler 與 counterfactual backtest runtime 已退役。
 */
public enum AttributionStatusEnum {
    /** 計算成功,alpha_contribution 欄位有效 */
    SUCCESS,

    /** Override window 短於 1 根 K 線,counterfactual backtest 無意義 */
    INSUFFICIENT_DATA,

    /** Override.symbol = NULL(適用所有 symbol),v1 不處理跨幣種 backtest 複雜度 */
    SCOPE_TOO_BROAD,

    /** 歷史 counterfactual backtest 失敗；詳見既有 error_message */
    BACKTEST_FAILED
}
