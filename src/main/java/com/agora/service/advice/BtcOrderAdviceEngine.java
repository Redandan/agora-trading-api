package com.agora.service.advice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** Pure advisory calculations. No repository, network, execution or notification authority. */
public final class BtcOrderAdviceEngine {
    public static final String VERSION = "BTC_ORDER_ADVICE_V1";
    private static final BigDecimal ZERO = BigDecimal.ZERO, ONE = BigDecimal.ONE;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    public record Bar(Instant openAt, BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {}
    public record PendingOrder(String id, String type, String side, BigDecimal price,
                               BigDecimal remainingQuantity, String state) {}
    public record Input(Instant asOf, Instant quoteAt, BigDecimal bid, BigDecimal ask, BigDecimal last,
                        List<Bar> bars, BigDecimal fee, BigDecimal minQuantity, BigDecimal quantityStep,
                        BigDecimal priceTick, BigDecimal legacyQuantity, BigDecimal legacyCost,
                        BigDecimal availableBtc, BigDecimal protectedBtc, List<PendingOrder> pendingOrders,
                        String inventoryKey, List<String> blockers) {}
    public record Policy(BigDecimal tradeFraction, BigDecimal cashShare, int validMinutes) {
        public Policy {
            if (tradeFraction == null || tradeFraction.signum() <= 0 || tradeFraction.compareTo(ONE) >= 0
                    || cashShare == null || cashShare.signum() <= 0 || cashShare.compareTo(ONE) >= 0
                    || validMinutes < 5 || validMinutes > 120) throw new IllegalArgumentException("Invalid advice policy");
        }
        public static Policy defaults() { return new Policy(new BigDecimal("0.25"), HALF, 60); }
    }
    public record Market(BigDecimal last, BigDecimal atr, BigDecimal low24h, BigDecimal high24h,
                         BigDecimal ema24, BigDecimal ema72, BigDecimal invalidateBelow, BigDecimal invalidateAbove) {}
    public record Leg(String side, BigDecimal price, BigDecimal quantity, String condition) {}
    public record Economics(BigDecimal saleNetUsdt, BigDecimal rebuyBudgetUsdt,
                            BigDecimal retainedUsdt, BigDecimal additionalBtc, BigDecimal cycleFeesUsdt,
                            BigDecimal salePnlBeforeHistoricalBuyFees) {}
    public record Advice(String policyVersion, Instant asOf, Instant validUntil, String status, String regime,
                         String reason, Market market, BigDecimal eligibleBtc, BigDecimal retainedBtc,
                         Policy policy, List<Leg> orders, Economics economics,
                         String inventoryKey, List<PendingOrder> existingOrders) {
        public boolean actionable() { return "PLAN".equals(status); }
    }

    public Advice evaluate(Input in, Policy policy) {
        String invalid = validate(in);
        if (invalid != null) return waitAdvice(in, policy, "DATA_UNAVAILABLE", "UNKNOWN", invalid, null);
        List<Bar> bars = in.bars();
        BigDecimal atr = ZERO;
        for (int i = bars.size() - 14; i < bars.size(); i++) {
            Bar b = bars.get(i);
            BigDecimal previous = bars.get(i - 1).close();
            atr = atr.add(b.high().subtract(b.low()).max(b.high().subtract(previous).abs()).max(b.low().subtract(previous).abs()));
        }
        atr = divide(atr, new BigDecimal("14"));
        if (atr.signum() == 0) return waitAdvice(in, policy, "WAIT", "RANGE", "波動不足，暫無可用價差", null);
        BigDecimal low = bars.subList(bars.size() - 24, bars.size()).stream().map(Bar::low).min(BigDecimal::compareTo).orElseThrow();
        BigDecimal high = bars.subList(bars.size() - 24, bars.size()).stream().map(Bar::high).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal fast = ema(bars, 24), slow = ema(bars, 72);
        String regime = fast.subtract(slow).compareTo(atr) > 0 ? "UPTREND"
                : slow.subtract(fast).compareTo(atr) > 0 ? "DOWNTREND" : "RANGE";
        Market market = new Market(in.last(), atr, low, high, fast, slow,
                low.subtract(atr).max(in.priceTick()), high.add(atr));
        if (divide(in.ask().subtract(in.bid()), in.last()).compareTo(new BigDecimal("0.002")) > 0)
            return waitAdvice(in, policy, "WAIT", regime, "買賣價差過大，暫不建立掛單計畫", market);
        if (in.last().compareTo(market.invalidateBelow()) < 0 || in.last().compareTo(market.invalidateAbove()) > 0)
            return waitAdvice(in, policy, "WAIT", regime, "即時價格已突破小時區間，等待新收盤資料確認", market);
        if ("DOWNTREND".equals(regime))
            return waitAdvice(in, policy, "WAIT", regime, "下跌趨勢，暫停新增來回掛單；等待趨勢或區間重新成立", market);

        BigDecimal sell = high.max(in.ask().add(atr.multiply(HALF)));
        BigDecimal buy = low.min(in.bid().subtract(atr.multiply(HALF)));
        if ("UPTREND".equals(regime)) buy = fast.min(in.bid().subtract(atr));
        sell = round(sell, in.priceTick(), RoundingMode.CEILING);
        buy = round(buy, in.priceTick(), RoundingMode.FLOOR);
        if (buy.signum() <= 0) return waitAdvice(in, policy, "WAIT", regime, "有效買價不足", market);
        if (sell.compareTo(market.invalidateAbove()) >= 0 || buy.compareTo(market.invalidateBelow()) <= 0)
            return waitAdvice(in, policy, "WAIT", regime, "掛價接近區間失效邊界，等待新收盤資料重算", market);
        if (!in.pendingOrders().isEmpty()) {
            // Levels are for review only: do not add capacity while unowned orders may already reserve it.
            return new Advice(VERSION, in.asOf(), in.asOf().plus(policy.validMinutes(), ChronoUnit.MINUTES),
                    "REVIEW_EXISTING_ORDERS", regime, "已有 BTC 掛單，先核對原單；以下為更新參考價，不能疊加下單", market,
                    in.legacyQuantity(), in.legacyQuantity(), policy,
                    List.of(new Leg("SELL", sell, ZERO, "REFERENCE_ONLY"), new Leg("BUY", buy, ZERO, "REFERENCE_ONLY")),
                    null, in.inventoryKey(), in.pendingOrders());
        }
        BigDecimal free = in.availableBtc().subtract(in.protectedBtc()).max(ZERO).min(in.legacyQuantity());
        BigDecimal quantity = round(in.legacyQuantity().multiply(policy.tradeFraction()).min(free), in.quantityStep(), RoundingMode.FLOOR);
        if (quantity.compareTo(in.minQuantity()) < 0)
            return waitAdvice(in, policy, "WAIT", regime, "可用舊持倉不足最小下單量，保留原持倉", market);
        BigDecimal afterFee = ONE.subtract(in.fee());
        BigDecimal saleNet = quantity.multiply(sell).multiply(afterFee);
        BigDecimal replaceCost = divide(quantity, afterFee).multiply(buy);
        BigDecimal surplus = saleNet.subtract(replaceCost);
        if (surplus.compareTo(quantity.multiply(sell).multiply(new BigDecimal("0.002"))) <= 0)
            return waitAdvice(in, policy, "WAIT", regime, "扣除雙邊費用後，價差不足 0.2% 緩衝", market);
        BigDecimal rebuyBudget = saleNet.subtract(surplus.multiply(policy.cashShare()));
        BigDecimal rebuyQuantity = round(divide(rebuyBudget, buy), in.quantityStep(), RoundingMode.FLOOR);
        BigDecimal retained = saleNet.subtract(rebuyQuantity.multiply(buy));
        BigDecimal gainedBtc = rebuyQuantity.multiply(afterFee).subtract(quantity);
        BigDecimal salePnl = saleNet.subtract(quantity.multiply(divide(in.legacyCost(), in.legacyQuantity())));
        if (salePnl.signum() <= 0 || gainedBtc.signum() <= 0 || retained.signum() <= 0 || rebuyQuantity.compareTo(in.minQuantity()) < 0)
            return waitAdvice(in, policy, "WAIT", regime, "扣費或整手限制後不能同時保留現金與增加 BTC", market);
        Economics economics = new Economics(saleNet, rebuyBudget, retained, gainedBtc,
                quantity.multiply(sell).add(rebuyQuantity.multiply(buy)).multiply(in.fee()), salePnl);
        return new Advice(VERSION, in.asOf(), in.asOf().plus(policy.validMinutes(), ChronoUnit.MINUTES), "PLAN", regime,
                "UPTREND".equals(regime) ? "上升趨勢：保留底倉，小部分在延伸價掛賣、回落後重新評估接回"
                        : "區間市況：小部分在區間上緣掛賣，成交後重新評估下緣接回", market,
                in.legacyQuantity(), in.legacyQuantity().subtract(quantity), policy,
                List.of(new Leg("SELL", sell, quantity, "MANUAL_LIMIT_ORDER"),
                        new Leg("BUY", buy, rebuyQuantity, "ONLY_AFTER_SELL_FILL_AND_FRESH_REVIEW")),
                economics, in.inventoryKey(), in.pendingOrders());
    }

    public Advice unavailable(Instant now, Policy policy, String reason) {
        return new Advice(VERSION, now, now.plusSeconds(300), "DATA_UNAVAILABLE", "UNKNOWN", reason,
                null, ZERO, ZERO, policy, List.of(), null, "UNAVAILABLE", List.of());
    }
    private Advice waitAdvice(Input in, Policy p, String status, String regime, String reason, Market market) {
        return new Advice(VERSION, in.asOf(), in.asOf().plus(p.validMinutes(), ChronoUnit.MINUTES), status, regime, reason,
                market, in.legacyQuantity(), in.legacyQuantity(), p, List.of(), null, in.inventoryKey(), in.pendingOrders());
    }
    private String validate(Input in) {
        if (!in.blockers().isEmpty()) return String.join("；", in.blockers());
        if (in.quoteAt() == null || Duration.between(in.quoteAt(), in.asOf()).getSeconds() > 60
                || in.quoteAt().isAfter(in.asOf().plusSeconds(5))) return "即時報價已過期或時間不正確";
        if (!positive(in.last()) || !positive(in.bid()) || !positive(in.ask()) || in.bid().compareTo(in.ask()) >= 0
                || !positive(in.minQuantity()) || !positive(in.quantityStep()) || !positive(in.priceTick())
                || in.fee() == null || in.fee().signum() < 0 || in.fee().compareTo(new BigDecimal("0.02")) >= 0)
            return "報價、費率或交易規格不完整";
        if (in.legacyQuantity() == null || in.legacyCost() == null || in.availableBtc() == null || in.protectedBtc() == null
                || in.legacyQuantity().signum() < 0 || in.legacyCost().signum() < 0
                || in.availableBtc().signum() < 0 || in.protectedBtc().signum() < 0
                || (in.legacyQuantity().signum() > 0 && in.legacyCost().signum() <= 0)) return "持倉成本或可用量不完整";
        if (in.bars() == null || in.bars().size() != 168) return "需要連續 168 根已收盤 OKX 小時線";
        Instant expected = in.asOf().truncatedTo(ChronoUnit.HOURS).minus(168, ChronoUnit.HOURS);
        for (Bar b : in.bars()) {
            if (!expected.equals(b.openAt()) || !positive(b.open()) || !positive(b.high()) || !positive(b.low()) || !positive(b.close())
                    || b.high().compareTo(b.open().max(b.close())) < 0 || b.low().compareTo(b.open().min(b.close())) > 0)
                return "小時線存在缺口、重複、未收盤或無效價格";
            expected = expected.plus(1, ChronoUnit.HOURS);
        }
        return null;
    }
    private static BigDecimal ema(List<Bar> bars, int period) {
        BigDecimal alpha = divide(new BigDecimal("2"), BigDecimal.valueOf(period + 1L));
        BigDecimal value = bars.getFirst().close();
        for (int i = 1; i < bars.size(); i++) value = bars.get(i).close().multiply(alpha).add(value.multiply(ONE.subtract(alpha))).setScale(12, RoundingMode.HALF_UP);
        return value.setScale(12, RoundingMode.HALF_UP);
    }
    public static BigDecimal round(BigDecimal value, BigDecimal step, RoundingMode mode) {
        return value.divide(step, 0, mode).multiply(step);
    }
    private static BigDecimal divide(BigDecimal a, BigDecimal b) { return a.divide(b, 16, RoundingMode.HALF_UP); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
}
