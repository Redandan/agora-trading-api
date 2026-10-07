package com.agora.service.advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import static com.agora.service.advice.BtcOrderAdviceEngine.*;

public final class BtcOrderAdviceFormatter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.of("Asia/Taipei"));
    private BtcOrderAdviceFormatter() {}
    public static String format(BtcOrderAdviceService.View view) {
        var state = view.state(); var a = state.advice();
        StringBuilder s = new StringBuilder("BTC 掛單建議 #").append(state.revision())
                .append("｜").append(TIME.format(view.checkedAt())).append(" 台北\n")
                .append(a.reason()).append("\n");
        if (state.revision() > 1) s.append("更新：").append(changeLabel(state.change())).append("；取代上一版建議，已掛的單需另行核對。\n");
        if (state.revision() > 1) s.append("原建議：").append(state.previousPlan()).append("\n");
        if (view.latestMarket() != null) {
            var m = view.latestMarket();
            s.append("參考現價 ").append(price(m.last())).append(" USDT；24h 區間 ")
                    .append(price(m.low24h())).append("–").append(price(m.high24h()))
                    .append("；小時 ATR14 ").append(price(m.atr())).append("\n");
        }
        if (!"DATA_UNAVAILABLE".equals(a.status()))
            s.append("適用舊持倉 ").append(qty(a.eligibleBtc())).append(" BTC；預計保留 ").append(qty(a.retainedBtc())).append(" BTC\n");
        else s.append("可用持倉尚無法確認，請勿沿用舊版價量。\n");
        s.append("配置假設：最多使用舊持倉 ").append(price(a.policy().tradeFraction().multiply(new BigDecimal("100"))))
                .append("%；來回淨價差的 ").append(price(a.policy().cashShare().multiply(new BigDecimal("100"))))
                .append("% 留作 USDT。\n");
        if (!a.orders().isEmpty()) s.append(orderSummary(a)).append("\n");
        if (a.economics() != null) {
            var e = a.economics();
            s.append("僅在兩邊完整成交的估算：保留 ").append(money(e.retainedUsdt())).append(" USDT，BTC 增加 ")
                    .append(qty(e.additionalBtc())).append("；雙邊費用約 ").append(money(e.cycleFeesUsdt())).append(" USDT。\n")
                    .append("賣出淨入帳 ").append(money(e.saleNetUsdt())).append(" USDT（含本金）；回補預算上限 ")
                    .append(money(e.rebuyBudgetUsdt())).append(" USDT。\n")
                    .append("買單須在賣出成交後，依實收款與最新行情重新確認；部分成交重算，不能現在同時掛兩邊。\n")
                    .append("舊買入費用尚未完整核對，以上不是歷史持倉的確切淨利。\n");
        }
        if (!a.existingOrders().isEmpty()) {
            s.append("現有 BTC 掛單 ").append(a.existingOrders().size()).append(" 筆：");
            a.existingOrders().stream().limit(5).forEach(o -> s.append(o.id()).append(" ").append(o.side()).append(" ")
                    .append(qty(o.remainingQuantity())).append(" BTC @ ").append(o.price() == null ? "條件價" : price(o.price())).append("；"));
            s.append("\n先核對原單歸屬，再決定是否調整；本系統不撤單。\n");
        }
        s.append("有效至 ").append(TIME.format(a.validUntil())).append("；持倉／掛單／費率改變即需重算。");
        if (a.market() != null && !a.orders().isEmpty()) s.append("價格低於 ").append(price(a.market().invalidateBelow()))
                .append(" 或高於 ").append(price(a.market().invalidateAbove())).append(" 時原計畫失效。");
        s.append("\n只提供建議，未送出任何委託。可能賣出後續漲或買回後續跌；收益優勢尚待驗證。");
        return s.toString();
    }
    public static String orderSummary(Advice a) {
        if (a.orders().isEmpty()) return "暫無新增掛單：" + a.reason();
        StringBuilder s = new StringBuilder();
        for (Leg leg : a.orders()) {
            if (!s.isEmpty()) s.append("；");
            s.append("SELL".equals(leg.side()) ? "賣出" : "成交後回補").append(" @ ").append(price(leg.price()));
            if (leg.quantity().signum() > 0) s.append(" × ").append(qty(leg.quantity())).append(" BTC");
            else s.append("（僅供原單核對，無新增數量）");
        }
        return s.toString();
    }
    private static String changeLabel(String value) {
        return switch (value) {
            case "STATUS_CHANGED" -> "可用條件改變";
            case "ACCOUNT_OR_ORDERS_CHANGED" -> "持倉、掛單或費率變更";
            case "MARKET_REGIME_CHANGED" -> "市場趨勢改變";
            case "PRICE_INVALIDATED" -> "價格突破原有效區間";
            case "VOLATILITY_CHANGED" -> "波動明顯改變";
            case "ORDER_LEVEL_CHANGED", "ORDER_PLAN_CHANGED" -> "掛單價量調整";
            case "ORDER_PRICE_REACHED" -> "已觸及原掛價，先確認是否成交";
            case "VALIDITY_REFRESH" -> "原建議到期，已重新核對";
            case "POLICY_CHANGED" -> "配置變更";
            default -> "初始建議";
        };
    }
    private static String price(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(); }
    private static String money(BigDecimal value) { return value.setScale(6, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
    private static String qty(BigDecimal value) { return value == null ? "未知" : value.setScale(10, RoundingMode.DOWN).stripTrailingZeros().toPlainString(); }
}
