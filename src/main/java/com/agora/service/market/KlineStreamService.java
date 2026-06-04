package com.agora.service.market;

import com.agora.dto.market.KlineSubscriptionInfo;

import java.util.List;

/**
 * 即時 K 線串流訂閱介面（WebSocket 實作）。
 *
 * <p>目前實作：
 * <ul>
 *   <li>{@link BinanceWsKlineService} — Binance.us WebSocket（預設 provider）</li>
 *   <li>{@link OkxWsKlineService} — OKX v5 WebSocket（與交易所一致的價源）</li>
 * </ul>
 *
 * <p>Spring 透過 {@code market.ws.provider=binance|okx} 選擇載入哪個 bean。
 * 呼叫端（如 {@link com.agora.config.MarketWsAutoSubscriber}）只需注入此介面，無需關心 provider。
 */
public interface KlineStreamService {

    /** 訂閱指定 symbol + intervalCode 的即時 K 線（預設 SPOT）。冪等。 */
    KlineSubscriptionInfo subscribe(String symbol, String intervalCode);

    /** 訂閱並指定 marketType（SPOT / FUTURES）。 */
    KlineSubscriptionInfo subscribe(String symbol, String intervalCode, String marketType);

    /** 停止並移除訂閱。 */
    boolean unsubscribe(String symbol, String intervalCode);

    boolean unsubscribe(String symbol, String intervalCode, String marketType);

    /** 列出所有訂閱的即時狀態快照。 */
    List<KlineSubscriptionInfo> listSubscriptions();

    /** 此 provider 的識別字串（用於 log / 診斷）。 */
    String providerName();
}
