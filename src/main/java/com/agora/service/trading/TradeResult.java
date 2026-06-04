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
}
