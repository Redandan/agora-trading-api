package com.agora.service.market;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KlineQualityValidatorTest {

    @Test
    void validatesBinanceDbBarsAgainstBinanceRestOnUtcClosedBoundary() {
        LocalDateTime now = LocalDateTime.parse("2026-07-10T13:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-07-10T00:00:00");
        LocalDateTime start = LocalDateTime.parse("2026-07-09T00:00:00");
        MdKline bar = bar(start, LocalDateTime.parse("2026-07-09T23:59:59.999"));

        MdKlineRepository repository = mock(MdKlineRepository.class);
        BinanceKlineImportService binance = mock(BinanceKlineImportService.class);
        when(repository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                "BTCUSDT", "1d", "binance", start, end.minusNanos(1L)))
                .thenReturn(List.of(bar));
        when(binance.fetchHistorical("BTCUSDT", "1d", start, end)).thenReturn(List.of(bar));
        KlineQualityValidator validator = new KlineQualityValidator(repository, new ObjectMapper(), binance);

        KlineQualityValidator.ValidationReport report =
                validator.validate("BTCUSDT", "1d", 1, "binance", now);

        assertThat(report.referenceSource()).isEqualTo("binance");
        assertThat(report.dbBarCount()).isEqualTo(1);
        assertThat(report.referenceBarCount()).isEqualTo(1);
        assertThat(report.missingInDb()).isZero();
        assertThat(report.phantomInDb()).isZero();
        assertThat(report.priceDivergences()).isZero();
        verify(binance).fetchHistorical("BTCUSDT", "1d", start, end);
    }

    @Test
    void closedRangeEndFloorsToUtcIntervalBoundary() {
        assertThat(KlineQualityValidator.closedRangeEnd(
                "1d", LocalDateTime.parse("2026-07-10T13:42:17")))
                .isEqualTo(LocalDateTime.parse("2026-07-10T00:00:00"));
        assertThat(KlineQualityValidator.closedRangeEnd(
                "4h", LocalDateTime.parse("2026-07-10T13:42:17")))
                .isEqualTo(LocalDateTime.parse("2026-07-10T12:00:00"));
    }

    private static MdKline bar(LocalDateTime openTime, LocalDateTime closeTime) {
        MdKline bar = new MdKline();
        bar.setOpenTime(openTime);
        bar.setCloseTime(closeTime);
        bar.setOpenPrice(new BigDecimal("62000"));
        bar.setHighPrice(new BigDecimal("64000"));
        bar.setLowPrice(new BigDecimal("61000"));
        bar.setClosePrice(new BigDecimal("63500"));
        bar.setVolume(new BigDecimal("100"));
        return bar;
    }
}
