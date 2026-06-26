package com.agora.service.trading;

import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PanicBottomContextPreviewServiceTest {

    private final MdKlineRepository klineRepository = mock(MdKlineRepository.class);
    private final MarketIndicatorHistoryRepository indicatorRepository = mock(MarketIndicatorHistoryRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PanicBottomContextPreviewService service =
            new PanicBottomContextPreviewService(klineRepository, indicatorRepository, objectMapper);

    @Test
    void highPanicScoreWithOcoOrBearishTrendOnlyAllowsScout() throws Exception {
        stubMarket("BTCUSDT", true);

        String output = service.preview("BTCUSDT", "OCO Health: 2 OK | 1 SYNC_ERROR");
        JsonNode root = objectMapper.readTree(output);

        assertThat(root.path("boundary").asText()).isEqualTo("READ_ONLY");
        assertThat(root.path("orderAllowed").asBoolean()).isFalse();
        assertThat(root.path("gridMutationAllowed").asBoolean()).isFalse();
        assertThat(root.path("panicBottomScore").asInt()).isGreaterThanOrEqualTo(75);
        assertThat(root.path("phase").asText()).isEqualTo("PANIC_BOTTOM_CANDIDATE");
        assertThat(root.path("confirmedDeployBlocked").asBoolean()).isTrue();
        assertThat(root.path("suggestedAction").asText()).isEqualTo("SCOUT_PRE_POSITION");
        assertThat(root.path("fearGreed").path("classification").asText()).isEqualTo("EXTREME_FEAR");
        assertThat(root.path("twoHundredWma").path("priceVs200wmaPct").asDouble()).isLessThan(10.0);
    }

    @Test
    void highPanicScoreWithoutSafetyBlockersCanReachConfirmedDeployReviewLabelOnly() throws Exception {
        stubMarket("BTCUSDT", false);

        String output = service.preview("BTCUSDT", "OCO Health: 3 OK | 0 abnormal");
        JsonNode root = objectMapper.readTree(output);

        assertThat(root.path("confirmedDeployBlocked").asBoolean()).isFalse();
        assertThat(root.path("suggestedAction").asText()).isEqualTo("CONFIRMED_DEPLOY_REVIEW");
        assertThat(root.path("safetyNotes").toString()).contains("never places orders");
        assertThat(root.path("orderAllowed").asBoolean()).isFalse();
        assertThat(root.path("gridMutationAllowed").asBoolean()).isFalse();
    }

    private void stubMarket(String symbol, boolean bearishIntraday) {
        List<MdKline> daily = descending(dailyPanicBars(symbol));
        List<MdKline> weekly = descending(flatBars(symbol, "1w", 200, new BigDecimal("50000"), LocalDateTime.parse("2022-01-03T00:00:00")));
        List<MdKline> oneHour = descending(trendBars(symbol, "1h", bearishIntraday));
        List<MdKline> fourHour = descending(trendBars(symbol, "4h", bearishIntraday));

        when(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(eq(symbol), eq("1d"), any(Pageable.class)))
                .thenReturn(daily);
        when(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(eq(symbol), eq("1w"), any(Pageable.class)))
                .thenReturn(weekly);
        when(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(eq(symbol), eq("1h"), any(Pageable.class)))
                .thenReturn(oneHour);
        when(klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(eq(symbol), eq("4h"), any(Pageable.class)))
                .thenReturn(fourHour);

        MarketIndicatorHistory fear = new MarketIndicatorHistory();
        fear.setSymbol(symbol);
        fear.setIndicator("fear_greed");
        fear.setValue(new BigDecimal("12"));
        fear.setCapturedAt(LocalDateTime.now().minusHours(6));
        when(indicatorRepository.findTopCleanBySymbolAndIndicator(symbol, "fear_greed"))
                .thenReturn(Optional.of(fear));
    }

    private List<MdKline> dailyPanicBars(String symbol) {
        List<MdKline> rows = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-04-01T00:00:00");
        BigDecimal price = new BigDecimal("80000");
        for (int i = 0; i < 60; i++) {
            BigDecimal close = price.subtract(BigDecimal.valueOf(i * 475L));
            rows.add(kline(symbol, "1d", start.plusDays(i), close.add(new BigDecimal("300")),
                    close.add(new BigDecimal("800")), close.subtract(new BigDecimal("500")), close));
        }
        return rows;
    }

    private List<MdKline> trendBars(String symbol, String intervalCode, boolean bearish) {
        List<MdKline> rows = new ArrayList<>();
        LocalDateTime start = LocalDateTime.parse("2026-06-01T00:00:00");
        int count = "1h".equals(intervalCode) ? 72 : 60;
        for (int i = 0; i < count; i++) {
            BigDecimal close = bearish
                    ? new BigDecimal("60000").subtract(BigDecimal.valueOf(i * 30L))
                    : new BigDecimal("60000").add(BigDecimal.valueOf(i * 5L));
            rows.add(kline(symbol, intervalCode, start.plusHours((long) i * ("1h".equals(intervalCode) ? 1 : 4)),
                    close, close.add(BigDecimal.TEN), close.subtract(BigDecimal.TEN), close));
        }
        return rows;
    }

    private List<MdKline> flatBars(String symbol, String intervalCode, int count, BigDecimal close, LocalDateTime start) {
        List<MdKline> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(kline(symbol, intervalCode, start.plusWeeks(i), close, close, close, close));
        }
        return rows;
    }

    private MdKline kline(String symbol, String intervalCode, LocalDateTime openTime,
                          BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close) {
        MdKline k = new MdKline();
        k.setSymbol(symbol);
        k.setIntervalCode(intervalCode);
        k.setOpenTime(openTime);
        k.setCloseTime(openTime.plusHours(1));
        k.setOpenPrice(open);
        k.setHighPrice(high);
        k.setLowPrice(low);
        k.setClosePrice(close);
        k.setVolume(BigDecimal.ONE);
        k.setSource("okx");
        return k;
    }

    private List<MdKline> descending(List<MdKline> rows) {
        List<MdKline> copy = new ArrayList<>(rows);
        copy.sort((a, b) -> b.getOpenTime().compareTo(a.getOpenTime()));
        return Collections.unmodifiableList(copy);
    }
}
