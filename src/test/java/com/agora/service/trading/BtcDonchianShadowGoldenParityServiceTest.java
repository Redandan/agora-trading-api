package com.agora.service.trading;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_FIRST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_LAST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcDonchianShadowGoldenParityServiceTest {

    @Test
    void missingDatabaseWindowFailsClosed() {
        Fixture fixture = fixture();
        when(fixture.repository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYMBOL, INTERVAL, SOURCE, GOLDEN_FIRST_OPEN_TIME, GOLDEN_LAST_OPEN_TIME))
                .thenReturn(List.of());

        JsonNode report = fixture.service.analyzeNode(SYMBOL);

        assertThat(report.path("status").asText()).isEqualTo("GOLDEN_DATASET_INCOMPLETE_FAIL_CLOSED");
        assertThat(report.path("goldenParityPassed").asBoolean()).isFalse();
        assertThat(report.path("blockers").toString()).contains("GOLDEN_ROW_COUNT_MISMATCH");
        assertThat(report.path("liveOrderAllowed").asBoolean(true)).isFalse();
    }

    @Test
    void unsupportedSymbolDoesNotQueryMarketData() {
        Fixture fixture = fixture();

        JsonNode report = fixture.service.analyzeNode("ETHUSDT");

        assertThat(report.path("status").asText()).isEqualTo("UNSUPPORTED_SYMBOL_FAIL_CLOSED");
        verify(fixture.repository, never())
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void officialDatasetPassesAllFrozenLedgerHashesWhenAvailable() throws Exception {
        Path csv = officialDatasetPath();
        assumeTrue(Files.isRegularFile(csv), "immutable OKX research dataset is not present");
        Fixture fixture = fixture();
        List<MdKline> bars = readCsv(csv);
        when(fixture.repository.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                SYMBOL, INTERVAL, SOURCE, GOLDEN_FIRST_OPEN_TIME, GOLDEN_LAST_OPEN_TIME))
                .thenReturn(bars);

        JsonNode report = fixture.service.analyzeNode(SYMBOL);

        assertThat(report.path("status").asText()).isEqualTo("PASS_EXACT_RESEARCH_RUNTIME_GOLDEN_PARITY");
        assertThat(report.path("goldenParityPassed").asBoolean()).isTrue();
        assertThat(report.path("canonicalPriceBarParityPassed").asBoolean()).isTrue();
        assertThat(report.path("normal").path("passed").asBoolean()).isTrue();
        assertThat(report.path("stress").path("passed").asBoolean()).isTrue();
        assertThat(report.path("blockers")).isEmpty();
        assertThat(report.path("orderSent").asBoolean(true)).isFalse();

        bars.get(0).setOpenPrice(bars.get(0).getOpenPrice().add(new BigDecimal("0.1")));
        JsonNode altered = fixture.service.analyzeNode(SYMBOL);
        assertThat(altered.path("normal").path("passed").asBoolean()).isTrue();
        assertThat(altered.path("stress").path("passed").asBoolean()).isTrue();
        assertThat(altered.path("canonicalPriceBarParityPassed").asBoolean()).isFalse();
        assertThat(altered.path("goldenParityPassed").asBoolean()).isFalse();
        assertThat(altered.path("blockers").toString()).contains("GOLDEN_PRICE_BAR_LEDGER_MISMATCH");
    }

    private Fixture fixture() {
        MdKlineRepository repository = mock(MdKlineRepository.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        BtcDonchianShadowEngine engine = new BtcDonchianShadowEngine(objectMapper);
        return new Fixture(repository,
                new BtcDonchianShadowGoldenParityService(repository, engine, objectMapper));
    }

    private List<MdKline> readCsv(Path csv) throws Exception {
        List<MdKline> bars = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                     .build().parse(reader)) {
            for (CSVRecord row : parser) {
                LocalDateTime openTime = OffsetDateTime.parse(row.get("open_time_utc")).toLocalDateTime();
                MdKline bar = new MdKline();
                bar.setSymbol(SYMBOL);
                bar.setIntervalCode(INTERVAL);
                bar.setSource(SOURCE);
                bar.setOpenTime(openTime);
                bar.setCloseTime(openTime.plusHours(1));
                bar.setOpenPrice(new BigDecimal(row.get("open")));
                bar.setHighPrice(new BigDecimal(row.get("high")));
                bar.setLowPrice(new BigDecimal(row.get("low")));
                bar.setClosePrice(new BigDecimal(row.get("close")));
                bar.setVolume(new BigDecimal(row.get("volume")));
                bars.add(bar);
            }
        }
        return bars;
    }

    private Path officialDatasetPath() {
        return Path.of("target", "research", "okx-btc-usdt-1h-final-ledger-v2",
                "okx-btc-usdt-1h-20260713T090000Z", "btc-usdt-okx-1h.csv")
                .toAbsolutePath().normalize();
    }

    private record Fixture(
            MdKlineRepository repository,
            BtcDonchianShadowGoldenParityService service
    ) {
    }
}
