package com.agora.service.tradingview;

import com.agora.service.trading.TradeResult;
import java.math.BigDecimal;
import java.util.Locale;

/** Presentation only. Never queries a provider or changes execution state. */
public final class TradingViewTradeMessageFormatter {
    private TradingViewTradeMessageFormatter() { }

    public static String sellFilled(TradeResult fill, BigDecimal soldQty,
                                    BigDecimal requestedQty, BigDecimal recordedPnl) {
        StringBuilder out = new StringBuilder("【BTC 賣出成交｜509 策略】\n平台：OKX 現貨")
                .append("\n成交均價：").append(money(fill.getAvgPrice(), 2)).append(" USDT/BTC")
                .append("\n賣出數量：").append(plain(soldQty)).append(" BTC")
                .append("\n成交金額（未扣費）：")
                .append(money(fill.getAvgPrice() == null || soldQty == null
                        ? null : fill.getAvgPrice().multiply(soldQty), 4)).append(" USDT")
                .append("\n本次已記錄損益：").append(signed(recordedPnl)).append(" USDT");
        if (fill.getFeeAmount() != null && fill.getFeeCurrency() != null && !fill.getFeeCurrency().isBlank()) {
            out.append(fill.getFeeAmount().signum() > 0 ? "\n賣出返佣：" : "\n賣出手續費：")
                    .append(plain(fill.getFeeAmount().abs())).append(' ').append(fill.getFeeCurrency());
        } else {
            out.append("\n賣出手續費：待交易所確認");
        }
        out.append("\n\n賣出原因：送單前預估淨報酬達策略出場門檻")
                .append("\n損益依據：本次成交收入減已記錄成本；完整淨利以費用核對結果為準");
        if (soldQty != null && requestedQty != null && soldQty.compareTo(requestedQty) < 0) {
            out.append("\n成交狀態：部分成交，剩餘持倉保留");
        }
        return out.append("\n訂單編號：").append(fill.getOrderId()).toString();
    }

    public static String notSubmitted(String side, String error) {
        return "【" + action(side) + "未送出｜509 策略】"
                + "\n狀態：送出前記錄失敗，本次未送出訂單"
                + "\n處理：請檢查系統紀錄，確認原因後再處理"
                + "\n錯誤摘要：" + safe(error);
    }

    public static String unconfirmed(String side, String clientId, String error) {
        return "【" + action(side) + "狀態待確認｜509 策略】"
                + "\n狀態：尚未確認交易所是否接受或成交"
                + "\n系統處理：保留訂單紀錄，自動重送已停用"
                + "\n請勿重複送單，先核對原訂單狀態"
                + "\n追蹤編號：" + clientId + "\n錯誤摘要：" + safe(error);
    }

    public static String reconciliationPending(String side, String orderId, String clientId, String error) {
        return "【" + action(side) + "回報待核對｜509 策略】"
                + "\n狀態：已取得成交回報，持倉或憑證尚未完成核對"
                + "\n處理：先核對原訂單，請勿重複送單"
                + "\n訂單編號：" + orderId + "\n追蹤編號：" + clientId
                + "\n錯誤摘要：" + safe(error);
    }

    private static String action(String side) { return "SELL".equals(side) ? "賣出" : "買入"; }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "未提供";
        String text = value.replace('\n', ' ').replace('\r', ' ');
        return text.substring(0, Math.min(240, text.length()));
    }
    private static String plain(BigDecimal value) { return value == null ? "待確認" : value.stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value, int scale) {
        return value == null ? "待確認" : String.format(Locale.ROOT, "%,." + scale + "f", value);
    }
    private static String signed(BigDecimal value) {
        return value == null ? "待確認" : (value.signum() > 0 ? "+" : "") + money(value, 4);
    }
}
