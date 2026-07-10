package com.agora.service.market;

import com.agora.dto.market.KlineImportResponse;
import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BinanceKlineImportServiceTest {

    @Test
    void importsBinanceDailyBarsOnUtcBoundary() throws Exception {
        long openMs = Instant.parse("2026-07-09T00:00:00Z").toEpochMilli();
        long closeMs = Instant.parse("2026-07-09T23:59:59.999Z").toEpochMilli();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("[[" + openMs + ",\"62000\",\"64000\",\"61000\",\"63500\",\"100\","
                            + closeMs + ",\"0\",1,\"0\",\"0\",\"0\"]]"));

            MdKlineRepository repository = mock(MdKlineRepository.class);
            MdKlineInsertHelper insertHelper = mock(MdKlineInsertHelper.class);
            when(repository.findOpenTimesBetweenBySource(eq("BTCUSDT"), eq("1d"), eq("binance"),
                    any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(List.of());
            when(insertHelper.insertIgnore(any(MdKline.class))).thenReturn(true);
            BinanceKlineImportService service = new BinanceKlineImportService(
                    repository, insertHelper, new ObjectMapper(),
                    server.url("/api/v3/klines").toString(), new OkHttpClient());

            KlineImportResponse response = service.importHistorical(
                    "BTCUSDT", "1d",
                    LocalDateTime.parse("2026-07-09T00:00:00"),
                    LocalDateTime.parse("2026-07-10T00:00:00"));

            ArgumentCaptor<MdKline> captor = ArgumentCaptor.forClass(MdKline.class);
            verify(insertHelper).insertIgnore(captor.capture());
            assertThat(response.getImportedCount()).isEqualTo(1);
            assertThat(captor.getValue().getSource()).isEqualTo("binance");
            assertThat(captor.getValue().getOpenTime()).isEqualTo(LocalDateTime.parse("2026-07-09T00:00:00"));
            assertThat(captor.getValue().getCloseTime()).isEqualTo(LocalDateTime.parse("2026-07-09T23:59:59.999"));
            assertThat(server.takeRequest().getRequestUrl().queryParameter("endTime"))
                    .isEqualTo(String.valueOf(Instant.parse("2026-07-10T00:00:00Z").toEpochMilli() - 1L));
        }
    }

    @Test
    void fetchHistoricalExcludesFormingBarOutsideClosedExclusiveRange() throws Exception {
        long closedOpenMs = Instant.parse("2026-07-09T00:00:00Z").toEpochMilli();
        long closedCloseMs = Instant.parse("2026-07-09T23:59:59.999Z").toEpochMilli();
        long formingOpenMs = Instant.parse("2026-07-10T00:00:00Z").toEpochMilli();
        long formingCloseMs = Instant.parse("2026-07-10T23:59:59.999Z").toEpochMilli();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("[[" + closedOpenMs + ",\"62000\",\"64000\",\"61000\",\"63500\",\"100\"," +
                            closedCloseMs + ",\"0\",1,\"0\",\"0\",\"0\"],[" +
                            formingOpenMs + ",\"63500\",\"65000\",\"63000\",\"64300\",\"50\"," +
                            formingCloseMs + ",\"0\",1,\"0\",\"0\",\"0\"]]"));

            BinanceKlineImportService service = new BinanceKlineImportService(
                    mock(MdKlineRepository.class), mock(MdKlineInsertHelper.class), new ObjectMapper(),
                    server.url("/api/v3/klines").toString(), new OkHttpClient());

            List<MdKline> bars = service.fetchHistorical(
                    "BTCUSDT", "1d",
                    LocalDateTime.parse("2026-07-09T00:00:00"),
                    LocalDateTime.parse("2026-07-10T00:00:00"));

            assertThat(bars).extracting(MdKline::getOpenTime)
                    .containsExactly(LocalDateTime.parse("2026-07-09T00:00:00"));
        }
    }

    @Test
    void reimportDeletesOnlyTheExactExclusiveSourceRangeBeforeVisionImport() throws Exception {
        LocalDateTime start = LocalDateTime.parse("2026-07-09T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-07-10T00:00:00");
        long openMs = Instant.parse("2026-07-09T00:00:00Z").toEpochMilli();
        long closeMs = Instant.parse("2026-07-09T23:59:59.999Z").toEpochMilli();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("[[" + openMs + ",\"62000\",\"64000\",\"61000\",\"63500\",\"100\"," +
                            closeMs + ",\"0\",1,\"0\",\"0\",\"0\"]]"));

            MdKlineRepository repository = mock(MdKlineRepository.class);
            MdKlineInsertHelper insertHelper = mock(MdKlineInsertHelper.class);
            when(repository.deleteBySymbolAndIntervalCodeAndSourceAndOpenTimeRangeExclusive(
                    "BTCUSDT", "1d", "binance", start, end)).thenReturn(1);
            BinanceKlineImportService service = new BinanceKlineImportService(
                    repository, insertHelper, new ObjectMapper(),
                    server.url("/api/v3/klines").toString(), new OkHttpClient());

            KlineImportResponse response = service.reimportHistorical(
                    "BTCUSDT", "1d", "SPOT", start, end, "binance");

            assertThat(response.getImportedCount()).isEqualTo(1);
            verify(repository).deleteBySymbolAndIntervalCodeAndSourceAndOpenTimeRangeExclusive(
                    "BTCUSDT", "1d", "binance", start, end);
            verify(repository).saveAll(org.mockito.ArgumentMatchers.<MdKline>anyList());
            verify(repository).flush();
        }
    }

    @Test
    void reimportRejectsIncompleteProviderCoverageBeforeDelete() throws Exception {
        LocalDateTime start = LocalDateTime.parse("2026-07-09T00:00:00");
        LocalDateTime end = LocalDateTime.parse("2026-07-10T00:00:00");
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "application/json").setBody("[]"));
            MdKlineRepository repository = mock(MdKlineRepository.class);
            BinanceKlineImportService service = new BinanceKlineImportService(
                    repository, mock(MdKlineInsertHelper.class), new ObjectMapper(),
                    server.url("/api/v3/klines").toString(), new OkHttpClient());

            assertThatThrownBy(() -> service.reimportHistorical(
                    "BTCUSDT", "1d", "SPOT", start, end, "binance"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("expected=1 fetched=0");

            verify(repository, never()).deleteBySymbolAndIntervalCodeAndSourceAndOpenTimeRangeExclusive(
                    any(), any(), any(), any(), any());
            verify(repository, never()).saveAll(org.mockito.ArgumentMatchers.<MdKline>anyList());
        }
    }

    @Test
    void expectedBarCountRequiresAlignedExclusiveRange() {
        assertThat(BinanceKlineImportService.expectedBarCount(
                LocalDateTime.parse("2024-07-10T00:00:00"),
                LocalDateTime.parse("2026-07-10T00:00:00"), "1d"))
                .isEqualTo(730L);
        assertThatThrownBy(() -> BinanceKlineImportService.expectedBarCount(
                LocalDateTime.parse("2026-07-09T00:00:00"),
                LocalDateTime.parse("2026-07-09T12:00:00"), "1d"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("align exactly");
    }
}
