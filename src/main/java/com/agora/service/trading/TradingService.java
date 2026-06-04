package com.agora.service.trading;

import java.math.BigDecimal;

/**
 * 現貨自動交易介面，統一買入、OCO 掛單、取消 OCO、賣出的操作。
 *
 * <p>實作：
 * <ul>
 *   <li>{@link OkxTradingService}（@Primary，目前使用）</li>
 *   <li>{@link BinanceTradingService}（備用，美國 IP 不可用）</li>
 * </ul>
 * </p>
 */
public interface TradingService {

    /**
     * 市價買入，以 USDT 金額計。
     *
     * @param symbol      交易對，例如 "BTCUSDT"
     * @param usdtAmount  花費 USDT 金額
     * @return 成交結果（orderId、均價、數量）
     */
    TradeResult placeMarketBuy(String symbol, double usdtAmount);

    /**
     * 掛 OCO 止盈止損單（需先完成買入）。
     *
     * @param symbol 交易對，例如 "BTCUSDT"
     * @param qty    賣出數量（來自 placeMarketBuy 的 qty）
     * @param tp     止盈觸發價
     * @param sl     止損觸發價
     * @return OCO / Algo 訂單 ID（用於後續取消）
     */
    Long placeOco(String symbol, BigDecimal qty, BigDecimal tp, BigDecimal sl);

    /**
     * 取消 OCO / Algo 訂單。出場前必須先取消，否則市價賣出後原掛單仍有效。
     * 若訂單已成交或不存在，實作應拋出 RuntimeException（供 autoSell 判斷是否已被觸發）。
     *
     * @param symbol  交易對，例如 "BTCUSDT"
     * @param ocoId   來自 placeOco 的回傳值
     */
    void cancelOco(String symbol, Long ocoId);

    /**
     * 市價賣出，以數量計。
     *
     * @param symbol 交易對，例如 "BTCUSDT"
     * @param qty    賣出數量（來自 placeMarketBuy 的 qty）
     * @return 實際成交均價
     */
    BigDecimal placeMarketSell(String symbol, BigDecimal qty);
}
