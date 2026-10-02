package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Recorded-basis hourly observations. These are not fee-exact account equity. */
public final class SpotPerformancePolicy {
    public static final String EVENT = "SPOT_PERFORMANCE_V1";
    public static final String BASIS = "RECORDED_COST_UNVERIFIED_FEES_EXCLUDES_EXIT_COST";
    private SpotPerformancePolicy() { }

    public static boolean freshBar(MdKline bar, LocalDateTime now) {
        return bar != null && "BTCUSDT".equals(bar.getSymbol()) && "okx".equals(bar.getSource())
                && "1h".equals(bar.getIntervalCode()) && bar.getOpenTime() != null
                && bar.getOpenTime().getMinute() == 0 && bar.getOpenTime().getSecond() == 0
                && bar.getOpenTime().getNano() == 0
                && bar.getOpenTime().plusHours(1).equals(bar.getCloseTime())
                && !bar.getCloseTime().isAfter(now) && !bar.getCloseTime().isBefore(now.minusMinutes(2))
                && positive(bar.getClosePrice());
    }

    public static Map<String, Object> snapshot(String owner, List<BtLiveSignal> rows,
                                              BigDecimal capital, MdKline bar, LocalDateTime now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schema", EVENT);
        out.put("owner", owner);
        out.put("basis", BASIS);
        out.put("markSource", "okx:BTCUSDT:1h:CLOSE");
        out.put("markTimeUtc", bar.getCloseTime().toString());
        out.put("observedAtUtc", now.toString());
        out.put("referenceCapitalUsdt", capital);
        out.put("markPrice", bar.getClosePrice());
        BigDecimal cost = BigDecimal.ZERO, quantity = BigDecimal.ZERO, realized = BigDecimal.ZERO;
        int open = 0, closed = 0, unresolved = 0;
        boolean complete = positive(capital);
        LocalDateTime oldest = null;
        boolean ageComplete = true;
        for (BtLiveSignal row : rows) {
            if (!"BTCUSDT".equals(row.getSymbol()) || "SHORT".equals(row.getSide())
                    || !owner.equals(BtcBasePositionStatePolicy.economicOwner(row))) continue;
            if (BtcBasePositionStatePolicy.executionUnresolved(row) || !Boolean.TRUE.equals(row.getAutoTraded())) {
                if (row.getExitTime() == null) unresolved++;
                continue;
            }
            if (row.getRealizedPnl() != null) realized = realized.add(row.getRealizedPnl());
            if (row.getExitTime() != null) {
                closed++;
                complete &= row.getRealizedPnl() != null;
            } else {
                open++;
                if (!positive(row.getEntryPrice()) || !positive(row.getTradedQty())) {
                    complete = false;
                } else {
                    cost = cost.add(row.getEntryPrice().multiply(row.getTradedQty()));
                    quantity = quantity.add(row.getTradedQty());
                }
                LocalDateTime created = row.getCreatedAt();
                if (created == null || created.isAfter(now)) ageComplete = false;
                else if (oldest == null || created.isBefore(oldest)) oldest = created;
            }
        }
        out.put("openLots", open);
        out.put("closedLots", closed);
        out.put("unresolvedReservations", unresolved);
        out.put("status", complete && unresolved == 0 ? "OBSERVED" : "MISSING_PROOF");
        out.put("holdingAgeBasis", "DATABASE_RECORD_CREATION_NOT_PROVIDER_FILL_TIME");
        out.put("oldestOpenAgeHours", ageComplete && oldest != null ? Duration.between(oldest, now).toHours() : null);
        if (complete && unresolved == 0) {
            BigDecimal unrealized = quantity.multiply(bar.getClosePrice()).subtract(cost);
            BigDecimal total = realized.add(unrealized);
            out.put("recordedOpenCostUsdt", cost);
            out.put("recordedRealizedPnlUsdt", realized);
            out.put("unrealizedBeforeExitCostsUsdt", unrealized);
            out.put("totalBeforeExitCostsUsdt", total);
            out.put("referenceEquityUsdt", capital.add(total));
            out.put("capitalUtilization", cost.divide(capital, 12, RoundingMode.HALF_UP));
        }
        return out;
    }

    static boolean positive(BigDecimal n) { return n != null && n.signum() > 0; }
}
