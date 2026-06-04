package com.agora.service.backtest;

import com.agora.model.MdKline;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Collections;
import java.util.Map;

/**
 * #305 OpenPositionSnapshot — 策略可透過 context.getOpenPosition() 存取當前持倉狀態，
 * 支援動態 TP/SL 邏輯（如「清算量萎縮 + 浮盈 > 1.5%」）而不需要 ThreadLocal hack。
 */
@Getter
public class StrategyContext {

    private final int index;
    private final MdKline current;
    private final MdKline previous;
    private final List<MdKline> klines;
    private final Map<String, double[]> indicators;
    private final Map<String, List<MdKline>> timeframeKlines;
    private final Map<String, Integer> timeframeIndices;
    private final Map<String, Map<String, double[]>> indicatorsByTimeframe;

    /** 當前持倉快照（null = 無持倉）。由 BacktestEngine 在每根 bar 更新。 */
    @Setter private OpenPositionSnapshot openPosition;

    /** 進場時的指標快照，供策略讀取（key = indicator name，value = 進場時的數值）。 */
    @Getter
    public static class OpenPositionSnapshot {
        public final double entryPrice;
        public final LocalDateTime entryTime;
        public final double unrealizedPnlPct; // 當前浮盈百分比
        public final Map<String, Double> entryIndicators; // 進場時的指標值

        public OpenPositionSnapshot(double entryPrice, LocalDateTime entryTime,
                                     double unrealizedPnlPct, Map<String, Double> entryIndicators) {
            this.entryPrice = entryPrice;
            this.entryTime = entryTime;
            this.unrealizedPnlPct = unrealizedPnlPct;
            this.entryIndicators = entryIndicators != null ? entryIndicators : Map.of();
        }
    }

    public StrategyContext(int index,
                           MdKline current,
                           MdKline previous,
                           List<MdKline> klines,
                           Map<String, double[]> indicators) {
        this(index, current, previous, klines, indicators,
                Collections.singletonMap("1h", klines),
                Collections.singletonMap("1h", index),
                Collections.singletonMap("1h", indicators));
    }

    public StrategyContext(int index,
                           MdKline current,
                           MdKline previous,
                           List<MdKline> klines,
                           Map<String, double[]> indicators,
                           Map<String, List<MdKline>> timeframeKlines,
                           Map<String, Integer> timeframeIndices,
                           Map<String, Map<String, double[]>> indicatorsByTimeframe) {
        this.index = index;
        this.current = current;
        this.previous = previous;
        this.klines = klines;
        this.indicators = indicators;
        this.timeframeKlines = timeframeKlines;
        this.timeframeIndices = timeframeIndices;
        this.indicatorsByTimeframe = indicatorsByTimeframe;
    }

    public MdKline getCurrent(String timeframe) {
        Integer i = timeframeIndices.get(timeframe);
        List<MdKline> list = timeframeKlines.get(timeframe);
        if (i == null || list == null || i < 0 || i >= list.size()) {
            return null;
        }
        return list.get(i);
    }

    public Integer getIndex(String timeframe) {
        return timeframeIndices.get(timeframe);
    }

    public double[] getIndicator(String timeframe, String name) {
        Map<String, double[]> tfIndicators = indicatorsByTimeframe.get(timeframe);
        if (tfIndicators == null) {
            return null;
        }
        return tfIndicators.get(name);
    }
}
