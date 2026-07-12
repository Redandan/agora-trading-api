package com.agora.service.trading;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 市價單成交結果（交易所通用）。
 */
@Data
public class TradeResult {
    /** 交易所訂單 ID */
    private String orderId;
    /** 成交均價 */
    private BigDecimal avgPrice;
    /** 成交數量（以標的幣計，例如 BTC） */
    private BigDecimal qty;

    /** 交易所回報的成交毛數量。買單的 qty 可能因 base fee 小於 grossQty。 */
    private BigDecimal grossQty;

    /** 明確保存呼叫端可使用的淨數量；與 qty 相同，供 fee-aware 報表使用。 */
    private BigDecimal netQty;

    /** 交易所原始 signed fee；OKX 通常以負數表示扣費。 */
    private BigDecimal feeAmount;

    /** feeAmount 的幣別，例如 BTC 或 USDT。 */
    private String feeCurrency;

    /** 換算為正數 USDT 成本；無法可靠換算時為 null。 */
    private BigDecimal feeUsdt;
}
