package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.service.trading.MdKlineToBarSeriesConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.indicators.volume.ChaikinMoneyFlowIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;

import java.util.List;

/**
 * Ta4j-based Phase1 condition evaluator for experimental indicators.
 *
 * <p>IndicatorUtils covers RSI/ADX/ATR/BB but lacks:
 * <ul>
 *   <li>CMF (Chaikin Money Flow) — volume-weighted buying/selling pressure</li>
 *   <li>VWAP — volume-weighted average price for intra-period reference</li>
 *   <li>EMA crossovers via ta4j Rule composition</li>
 * </ul>
 *
 * <p>Usage: call {@link #evaluate(List, int)} at a given bar index during
 * backtest to check experimental Phase1 conditions. Compare win-rate with
 * and without each condition to decide if it improves strategy quality.
 *
 * <p>All indicators are computed lazily from the k-line list; results are
 * returned as a record for easy logging and storage in diagnostic JSON.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Ta4jPhaseOneEvaluator {

    private final MdKlineToBarSeriesConverter converter;

    public record Phase1Snapshot(
            double cmf20,           // Chaikin Money Flow (20-period)
            double vwap,            // VWAP from series start
            double ema9,            // EMA(9) — fast trend
            double ema21,           // EMA(21) — slow trend
            boolean ema9AboveEma21, // fast > slow → uptrend
            boolean cmfPositive,    // CMF > 0 → net buying pressure
            boolean closeAboveVwap  // close > VWAP → price strength
    ) {
        /** One-line summary for diagnostic logs. */
        public String summary() {
            return String.format("cmf=%.3f vwap=%.1f ema9=%.1f ema21=%.1f " +
                            "ema9>21=%b cmf+=%b close>vwap=%b",
                    cmf20, vwap, ema9, ema21,
                    ema9AboveEma21, cmfPositive, closeAboveVwap);
        }
    }

    /**
     * Compute experimental Phase1 indicators at {@code index} in the given k-lines.
     * Requires at least 21 bars before {@code index} for valid results.
     *
     * @param klines sorted ascending by openTime
     * @param index  the bar to evaluate (0-based)
     * @return snapshot of experimental indicator values, or null if insufficient data
     */
    public Phase1Snapshot evaluate(List<MdKline> klines, int index) {
        if (klines == null || index < 21 || index >= klines.size()) return null;

        BarSeries series = converter.convert(klines, "phase1-eval");
        ClosePriceIndicator close = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        ChaikinMoneyFlowIndicator cmf = new ChaikinMoneyFlowIndicator(series, 20);
        VWAPIndicator vwap   = new VWAPIndicator(series, index + 1);
        EMAIndicator ema9    = new EMAIndicator(close, 9);
        EMAIndicator ema21   = new EMAIndicator(close, 21);

        double cmfVal  = cmf.getValue(index).doubleValue();
        double vwapVal = vwap.getValue(index).doubleValue();
        double ema9Val = ema9.getValue(index).doubleValue();
        double ema21Val = ema21.getValue(index).doubleValue();
        double closeVal = close.getValue(index).doubleValue();

        return new Phase1Snapshot(
                cmfVal,
                vwapVal,
                ema9Val,
                ema21Val,
                ema9Val > ema21Val,
                cmfVal > 0,
                closeVal > vwapVal
        );
    }
}
