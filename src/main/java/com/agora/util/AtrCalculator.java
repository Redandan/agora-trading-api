package com.agora.util;

import com.agora.model.MdKline;

import java.util.List;

/**
 * #434 — Wilder's ATR(14) calculator. Stateless static helpers; caller passes
 * in the kline window. Used by {@code BtcPriceMoveIndicatorCollector} to size
 * BTC price moves in ATR-units (volatility-normalized) rather than raw %.
 *
 * <p><b>Why ATR-units</b>: in low-volatility regimes a 2% move is already
 * extreme; in high-vol regimes 2% is daily noise. Pure-% thresholds either
 * fire constantly (vol regime) or never (calm regime). ATR-normalized
 * thresholds adapt automatically.
 *
 * <p><b>Formula</b>:
 * <pre>
 *   TR_i  = max(high - low, |high - prev_close|, |low - prev_close|)
 *   ATR_0 = average(TR_1..TR_14)
 *   ATR_i = (ATR_{i-1} * 13 + TR_i) / 14   for i ≥ 14
 * </pre>
 */
public final class AtrCalculator {

    private AtrCalculator() {}

    /**
     * Compute Wilder's ATR(14) over the supplied kline series.
     * Caller passes klines ordered ascending by openTime.
     * Returns {@code 0.0} if too few klines.
     */
    public static double wildersAtr14(List<MdKline> klines) {
        if (klines == null || klines.size() < 15) return 0.0;

        double sumTR = 0;
        for (int i = 1; i <= 14; i++) {
            sumTR += trueRange(klines.get(i), klines.get(i - 1));
        }
        double atr = sumTR / 14.0;

        for (int i = 15; i < klines.size(); i++) {
            double tr = trueRange(klines.get(i), klines.get(i - 1));
            atr = (atr * 13.0 + tr) / 14.0;
        }
        return atr;
    }

    private static double trueRange(MdKline current, MdKline prev) {
        double high      = current.getHighPrice().doubleValue();
        double low       = current.getLowPrice().doubleValue();
        double prevClose = prev.getClosePrice().doubleValue();
        return Math.max(
                high - low,
                Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }
}
