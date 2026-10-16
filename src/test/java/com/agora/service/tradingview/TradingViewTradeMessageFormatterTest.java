package com.agora.service.tradingview;

import com.agora.service.trading.TradeResult;
import com.agora.service.TgTradingNotificationClassifier;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradingViewTradeMessageFormatterTest {
    @Test void partialSellPreservesRemainingInventoryAndDoesNotClaimExactNet() {
        TradeResult fill = new TradeResult();
        fill.setAvgPrice(new BigDecimal("90000")); fill.setOrderId("sample");
        fill.setFeeAmount(new BigDecimal("-0.009")); fill.setFeeCurrency("USDT");
        String text = TradingViewTradeMessageFormatter.sellFilled(fill, new BigDecimal("0.0001"),
                new BigDecimal("0.0002"), new BigDecimal("0.5"));
        assertTrue(text.contains("成交金額（未扣費）：9.0000 USDT"));
        assertTrue(text.contains("本次已記錄損益：+0.5000 USDT"));
        assertTrue(text.contains("部分成交，剩餘持倉保留"));
        assertTrue(text.contains("賣出手續費：0.009 USDT"));
        assertFalse(text.contains("已核對淨利"));
    }

    @Test void unknownReceiptNeverClaimsFailureOrNoOrder() {
        String unknown = TradingViewTradeMessageFormatter.unconfirmed("BUY", "sample", "timeout");
        assertTrue(unknown.contains("尚未確認交易所是否接受或成交"));
        assertTrue(unknown.contains("請勿重複送單"));
        assertFalse(unknown.contains("本次未送出訂單"));
        String receipt = TradingViewTradeMessageFormatter.reconciliationPending("SELL", "sample", "client", "database");
        assertTrue(receipt.contains("已取得成交回報"));
        assertFalse(receipt.contains("本次未送出訂單"));
        assertTrue(TradingViewTradeMessageFormatter.notSubmitted("SELL", "database").contains("本次未送出訂單"));
    }

    @Test void translatedAlertsKeepExistingRoutingBucket() {
        var classifier = new TgTradingNotificationClassifier();
        String source = "TradingView509Live";
        String[] translated = {
                TradingViewTradeMessageFormatter.notSubmitted("BUY", "database"),
                TradingViewTradeMessageFormatter.unconfirmed("SELL", "client", "timeout"),
                TradingViewTradeMessageFormatter.reconciliationPending("BUY", "order", "client", "database")};
        String[] original = {"509 BUY reservation failed before order; no order sent. database",
                "509 SELL submission unconfirmed; automatic retry disabled. clOrdId=client error=timeout",
                "509 BUY receipt awaits reconciliation. orderId=order clOrdId=client error=database"};
        for (int i = 0; i < original.length; i++) {
            assertEquals(classifier.classify(original[i], source, "CRITICAL"), classifier.classify(translated[i], source, "CRITICAL"));
            assertEquals(classifier.routingKey(original[i], source, "CRITICAL"), classifier.routingKey(translated[i], source, "CRITICAL"));
        }
    }
}
