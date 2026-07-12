package com.agora.service.backtest;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OiFundingDivergenceStrategyTest {

    @Mock
    private MarketIndicatorHistoryRepository indicatorRepository;

    private OiFundingDivergenceStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new OiFundingDivergenceStrategy(indicatorRepository);
        when(indicatorRepository.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                eq("BTCUSDT"), any(), any())).thenReturn(List.of());
    }

    @Test
    void seesCollectorRowsAddedAfterInitialCacheLoadWithoutRestart() {
        LocalDateTime barHour = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        AtomicBoolean collectorCompleted = new AtomicBoolean(false);
        when(indicatorRepository.findTopCleanInCapturedAtWindow(
                eq("BTCUSDT"), any(), any(), any())).thenAnswer(invocation -> {
            if (!collectorCompleted.get()) {
                return Optional.empty();
            }
            String indicator = invocation.getArgument(1);
            if ("funding_rate".equals(indicator)) {
                return Optional.of(indicator(indicator, barHour.plusMinutes(1), 0.0));
            }
            if ("oi_change_pct_1h".equals(indicator)) {
                return Optional.of(indicator(indicator, barHour.plusMinutes(1), 0.5));
            }
            return Optional.empty();
        });

        StrategyContext context = context(barHour);

        assertThat(strategy.evaluate(context, new HashMap<>())).isEqualTo(StrategySignal.HOLD);

        collectorCompleted.set(true);

        assertThat(strategy.evaluate(context, new HashMap<>())).isEqualTo(StrategySignal.BUY);
        verify(indicatorRepository, times(2)).findTopCleanInCapturedAtWindow(
                "BTCUSDT", "funding_rate", barHour.minusHours(1), barHour.plusHours(1));
    }

    @Test
    void historicalMissCoveredByBulkSnapshotDoesNotQueryPerBar() {
        LocalDateTime historicalHour = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(30).truncatedTo(ChronoUnit.HOURS);

        assertThat(strategy.evaluate(context(historicalHour), new HashMap<>()))
                .isEqualTo(StrategySignal.HOLD);

        verify(indicatorRepository, never()).findTopCleanInCapturedAtWindow(any(), any(), any(), any());
    }

    private StrategyContext context(LocalDateTime barHour) {
        List<MdKline> bars = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            MdKline bar = new MdKline();
            bar.setSymbol("BTCUSDT");
            bar.setIntervalCode("4h");
            bar.setOpenTime(barHour.minusHours((20L - i) * 4));
            bar.setCloseTime(bar.getOpenTime().plusHours(4));
            bar.setOpenPrice(BigDecimal.valueOf(100));
            bar.setHighPrice(BigDecimal.valueOf(102));
            bar.setLowPrice(BigDecimal.valueOf(99));
            bar.setClosePrice(BigDecimal.valueOf(101));
            bar.setVolume(BigDecimal.valueOf(200));
            bars.add(bar);
        }

        double[] volumeMa = filled(21, 100);
        double[] sma200 = filled(21, 90);
        double[] rsi = filled(21, 40);
        Map<String, double[]> indicators = Map.of(
                "volumeMa", volumeMa,
                "sma200", sma200,
                "rsi", rsi);
        return new StrategyContext(20, bars.get(20), bars.get(19), bars, indicators);
    }

    private MarketIndicatorHistory indicator(String name, LocalDateTime capturedAt, double value) {
        MarketIndicatorHistory row = new MarketIndicatorHistory();
        row.setSymbol("BTCUSDT");
        row.setIndicator(name);
        row.setCapturedAt(capturedAt);
        row.setValue(BigDecimal.valueOf(value));
        return row;
    }

    private double[] filled(int size, double value) {
        double[] values = new double[size];
        for (int i = 0; i < size; i++) {
            values[i] = value;
        }
        return values;
    }
}
