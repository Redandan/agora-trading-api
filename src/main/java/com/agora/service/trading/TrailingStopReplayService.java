package com.agora.service.trading;

import com.agora.model.BtBacktestTrade;
import com.agora.model.MdKline;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

@Service
public class TrailingStopReplayService {

    private static final BigDecimal BREAKEVEN_TRIGGER_ATR_MULT = new BigDecimal("0.5");
    private static final BigDecimal TRAILING_TRIGGER_ATR_MULT = new BigDecimal("1.0");
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.001");

    public ReplayResult replayBacktestTrade(BtBacktestTrade trade, List<MdKline> bars) {
        if (trade == null || bars == null || bars.isEmpty()) {
            return ReplayResult.unreplayable("missing_trade_or_bars");
        }
        BigDecimal entry = trade.getEntryPrice();
        BigDecimal qty = trade.getQuantity();
        BigDecimal atr = normalizeAtrFraction(trade.getAtrPct());
        LocalDateTime start = trade.getEntryTime();
        LocalDateTime end = trade.getExitTime();
        BigDecimal originalNet = trade.getNetPnl();
        if (entry == null || qty == null || atr == null || start == null || end == null || originalNet == null) {
            return ReplayResult.unreplayable("missing_entry_qty_atr_time_or_original_pnl");
        }
        boolean isLong = trade.getSide() == null || trade.getSide() == BtBacktestTrade.Side.LONG;
        BigDecimal feeRate = trade.getBacktest() != null && trade.getBacktest().getFeeRate() != null
                ? trade.getBacktest().getFeeRate()
                : DEFAULT_FEE_RATE;
        BigDecimal borrowingCost = trade.getBorrowingCost() != null ? trade.getBorrowingCost() : BigDecimal.ZERO;

        String state = "ENTERED";
        BigDecimal extreme = null;
        BigDecimal stop = null;
        BigDecimal breakevenTrigger = trigger(entry, atr, BREAKEVEN_TRIGGER_ATR_MULT, isLong);
        BigDecimal trailingTrigger = trigger(entry, atr, TRAILING_TRIGGER_ATR_MULT, isLong);
        int usedBars = 0;
        boolean sameBarTransition = false;

        for (MdKline bar : bars) {
            if (bar == null || bar.getOpenTime() == null) continue;
            if (bar.getOpenTime().isBefore(start) || bar.getOpenTime().isAfter(end)) continue;
            BigDecimal high = bar.getHighPrice();
            BigDecimal low = bar.getLowPrice();
            if (high == null || low == null) continue;
            usedBars++;

            BigDecimal previousExtreme = extreme;
            extreme = updateExtreme(extreme, high, low, isLong);
            boolean newExtreme = previousExtreme != null && (isLong
                    ? extreme.compareTo(previousExtreme) > 0
                    : extreme.compareTo(previousExtreme) < 0);

            boolean touchedBreakeven = isLong
                    ? high.compareTo(breakevenTrigger) >= 0
                    : low.compareTo(breakevenTrigger) <= 0;
            boolean touchedTrailing = isLong
                    ? high.compareTo(trailingTrigger) >= 0
                    : low.compareTo(trailingTrigger) <= 0;
            boolean transitionedThisBar = false;
            boolean ratchetedThisBar = false;

            if ("ENTERED".equals(state) && touchedBreakeven) {
                state = "BREAKEVEN_LOCKED";
                stop = protectiveStop(stop, feeAdjustedBreakeven(entry, isLong), isLong);
                transitionedThisBar = true;
            }
            if ("BREAKEVEN_LOCKED".equals(state) && touchedTrailing) {
                state = "TRAILING";
                stop = protectiveStop(stop, trailingStop(extreme, atr, isLong), isLong);
                sameBarTransition = transitionedThisBar;
            } else if ("TRAILING".equals(state)) {
                BigDecimal previousStop = stop;
                stop = protectiveStop(stop, trailingStop(extreme, atr, isLong), isLong);
                ratchetedThisBar = newExtreme && previousStop != null && stop != null
                        && stop.compareTo(previousStop) != 0;
            }

            if (stop != null && stopHit(stop, high, low, isLong)) {
                BigDecimal trailingNet = netPnl(entry, stop, qty, feeRate, borrowingCost, isLong);
                BigDecimal delta = trailingNet.subtract(originalNet).setScale(8, RoundingMode.HALF_UP);
                return new ReplayResult(
                        true,
                        true,
                        sameBarTransition || transitionedThisBar || ratchetedThisBar,
                        "TRAILING_STOP",
                        originalNet,
                        trailingNet,
                        delta,
                        improvementPct(delta, originalNet),
                        bar.getOpenTime(),
                        stop,
                        state,
                        usedBars,
                        null);
            }
        }

        if (usedBars == 0) {
            return ReplayResult.unreplayable("no_bars_in_trade_window");
        }
        return new ReplayResult(
                true,
                false,
                sameBarTransition,
                "ORIGINAL_EXIT",
                originalNet,
                originalNet,
                BigDecimal.ZERO.setScale(8, RoundingMode.HALF_UP),
                BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP),
                trade.getExitTime(),
                trade.getExitPrice(),
                state,
                usedBars,
                null);
    }

    private BigDecimal normalizeAtrFraction(BigDecimal atrPct) {
        if (atrPct == null || atrPct.signum() <= 0) return null;
        return atrPct.compareTo(new BigDecimal("0.5")) > 0
                ? atrPct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP)
                : atrPct;
    }

    private BigDecimal trigger(BigDecimal entry, BigDecimal atr, BigDecimal multiple, boolean isLong) {
        BigDecimal offset = atr.multiply(multiple);
        return isLong
                ? entry.multiply(BigDecimal.ONE.add(offset))
                : entry.multiply(BigDecimal.ONE.subtract(offset));
    }

    private BigDecimal updateExtreme(BigDecimal current, BigDecimal high, BigDecimal low, boolean isLong) {
        BigDecimal candidate = isLong ? high : low;
        if (current == null) return candidate;
        return isLong ? current.max(candidate) : current.min(candidate);
    }

    private BigDecimal feeAdjustedBreakeven(BigDecimal entry, boolean isLong) {
        return isLong
                ? entry.multiply(new BigDecimal("1.001"))
                : entry.multiply(new BigDecimal("0.999"));
    }

    private BigDecimal trailingStop(BigDecimal extreme, BigDecimal atr, boolean isLong) {
        if (extreme == null || atr == null) return null;
        BigDecimal distance = extreme.multiply(atr);
        return isLong ? extreme.subtract(distance) : extreme.add(distance);
    }

    private BigDecimal protectiveStop(BigDecimal currentStop, BigDecimal candidate, boolean isLong) {
        if (candidate == null) return currentStop;
        if (currentStop == null) return candidate;
        return isLong ? currentStop.max(candidate) : currentStop.min(candidate);
    }

    private boolean stopHit(BigDecimal stop, BigDecimal high, BigDecimal low, boolean isLong) {
        return isLong ? low.compareTo(stop) <= 0 : high.compareTo(stop) >= 0;
    }

    private BigDecimal netPnl(BigDecimal entry, BigDecimal exit, BigDecimal qty, BigDecimal feeRate,
                              BigDecimal borrowingCost, boolean isLong) {
        BigDecimal gross = isLong
                ? exit.subtract(entry).multiply(qty)
                : entry.subtract(exit).multiply(qty);
        BigDecimal entryFee = entry.multiply(qty).multiply(feeRate);
        BigDecimal exitFee = exit.multiply(qty).multiply(feeRate);
        return gross.subtract(entryFee).subtract(exitFee).subtract(borrowingCost)
                .setScale(8, RoundingMode.HALF_UP);
    }

    private BigDecimal improvementPct(BigDecimal delta, BigDecimal originalNet) {
        if (originalNet == null || originalNet.signum() == 0) return null;
        return delta.divide(originalNet.abs(), 6, RoundingMode.HALF_UP);
    }

    public record ReplayResult(
            boolean replayed,
            boolean exitedByTrailing,
            boolean ambiguousSameBar,
            String exitReason,
            BigDecimal originalNetPnl,
            BigDecimal trailingNetPnl,
            BigDecimal deltaPnl,
            BigDecimal improvementPct,
            LocalDateTime exitTime,
            BigDecimal exitPrice,
            String finalState,
            int bars,
            String skipReason) {

        private static ReplayResult unreplayable(String reason) {
            return new ReplayResult(false, false, false, "UNREPLAYABLE",
                    null, null, null, null, null, null, "UNKNOWN", 0, reason);
        }
    }
}
