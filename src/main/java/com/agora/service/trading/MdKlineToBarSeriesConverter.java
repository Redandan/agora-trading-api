package com.agora.service.trading;

import com.agora.model.MdKline;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.DoubleNum;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Converts a sorted list of {@link MdKline} records into a ta4j {@link BarSeries}.
 *
 * <p>Used as input for ta4j indicators (RSI, ADX, ATR, Bollinger Bands, etc.)
 * and for composing strategy signals via ta4j {@code Rule} interface.
 *
 * <p>Interval code → Duration mapping covers all intervals used in the system.
 * Unknown intervals fall back to 1 hour.
 */
@Component
public class MdKlineToBarSeriesConverter {

    private static final Map<String, Duration> INTERVAL_DURATIONS = Map.of(
            "1m",  Duration.ofMinutes(1),
            "5m",  Duration.ofMinutes(5),
            "15m", Duration.ofMinutes(15),
            "30m", Duration.ofMinutes(30),
            "1h",  Duration.ofHours(1),
            "4h",  Duration.ofHours(4),
            "1d",  Duration.ofDays(1)
    );

    /**
     * Converts K-lines to a ta4j BarSeries.
     *
     * @param klines sorted ascending by openTime (oldest first)
     * @param name   series identifier, e.g. "BTCUSDT-1h"
     * @return BarSeries ready for ta4j indicator computation
     */
    public BarSeries convert(List<MdKline> klines, String name) {
        BarSeries series = new BaseBarSeries(name, DoubleNum::valueOf);
        series.setMaximumBarCount(Integer.MAX_VALUE);

        for (MdKline k : klines) {
            Duration duration = barDuration(k.getIntervalCode());
            ZonedDateTime endTime = k.getCloseTime().atZone(ZoneOffset.UTC);

            Bar bar = new BaseBar(
                    duration,
                    endTime,
                    k.getOpenPrice().doubleValue(),
                    k.getHighPrice().doubleValue(),
                    k.getLowPrice().doubleValue(),
                    k.getClosePrice().doubleValue(),
                    k.getVolume().doubleValue()
            );
            series.addBar(bar);
        }
        return series;
    }

    private static Duration barDuration(String intervalCode) {
        return INTERVAL_DURATIONS.getOrDefault(
                intervalCode != null ? intervalCode.toLowerCase() : "1h",
                Duration.ofHours(1));
    }
}
