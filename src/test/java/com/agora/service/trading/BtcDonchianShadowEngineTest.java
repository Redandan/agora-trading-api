package com.agora.service.trading;

import com.agora.model.MdKline;
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
import java.util.Map;

import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_FIRST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_LAST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_PRICE_BAR_LEDGER_SHA256;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_ROW_COUNT;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BtcDonchianShadowEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final BtcDonchianShadowEngine engine = new BtcDonchianShadowEngine(objectMapper);

    @Test
    void officialResearchDatasetMatchesFrozenPowerShellLedgersWhenAvailable() throws Exception {
        Path csv = officialDatasetPath();
        assumeTrue(Files.isRegularFile(csv), "immutable OKX research dataset is not present");

        List<MdKline> bars = readCsv(csv);
        BtcDonchianShadowEngine.ReplayResult result = engine.replay(bars);
        JsonNode expectedCandidate = expectedCandidate(csv);

        assertThat(result.rowCount()).isEqualTo(GOLDEN_ROW_COUNT);
        assertThat(result.firstOpenTime()).isEqualTo(GOLDEN_FIRST_OPEN_TIME);
        assertThat(result.lastOpenTime()).isEqualTo(GOLDEN_LAST_OPEN_TIME);
        assertThat(engine.canonicalPriceBarLedgerSha256(bars)).isEqualTo(GOLDEN_PRICE_BAR_LEDGER_SHA256);
        assertNormalOrder11RawInputs(bars);
        assertRawCashCheckpoints(bars, NORMAL.name(), List.of(
                "3FE9DAD1E021C9CE", "3FEFAA50C3667D2F", "3FE78612E9A8B3B6", "3FF35C16A4971404",
                "3FF115FECB98FB83", "3FF382CD5F7018B0", "3FF136E80BC5261F", "3FF34F7C6E57EB0A",
                "3FF1848D190718FB", "3FF33773E16C4E69", "3FEFFEAE3CBEBF4A", "3FF3A1DAF24F4746"));
        assertLedgerRows(result.scenarios().get(NORMAL.name()), expectedCandidate.path("normal"));
        assertLedgerRows(result.scenarios().get(STRESS.name()), expectedCandidate.path("stress"));
        assertFrozenScenario(result.scenarios().get(NORMAL.name()), NORMAL);
        assertFrozenScenario(result.scenarios().get(STRESS.name()), STRESS);
    }

    @Test
    void rejectsMissingHourlyBarInsteadOfBridgingTheGap() {
        BtcDonchianShadowEngine.State state = engine.initialState();
        engine.step(state, bar(LocalDateTime.of(2026, 1, 1, 0, 0), "100", "101", "99", "100"));

        assertThatThrownBy(() -> engine.step(state,
                bar(LocalDateTime.of(2026, 1, 1, 2, 0), "100", "101", "99", "100")))
                .isInstanceOf(BtcDonchianShadowEngine.DataQualityException.class)
                .hasMessageContaining("BAR_LATTICE_GAP");
    }

    @Test
    void normalEntryUsesNextHourOpenAndStressUsesOneAdditionalDelayBar() {
        List<MdKline> bars = new ArrayList<>();
        LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int day = 0; day < 22; day++) {
            for (int hour = 0; hour < 24; hour++) {
                double close = day == 20 ? 130.0 : 100.0 + day;
                double open = hour == 0 ? close - 0.5 : close;
                bars.add(bar(start.plusHours((long) day * 24 + hour),
                        String.valueOf(open), String.valueOf(close + 1.0),
                        String.valueOf(open - 1.0), String.valueOf(close)));
            }
        }

        BtcDonchianShadowEngine.ReplayResult replay = engine.replay(bars);
        var normalSignals = replay.scenarios().get(NORMAL.name()).signalLedger();
        var stressSignals = replay.scenarios().get(STRESS.name()).signalLedger();
        var normalOrders = replay.scenarios().get(NORMAL.name()).orderLedger();
        var stressOrders = replay.scenarios().get(STRESS.name()).orderLedger();

        assertThat(normalSignals).hasSize(1);
        assertThat(stressSignals).hasSize(1);
        assertThat(normalSignals.get(0).get("scheduledExecutionTimeUtc"))
                .isEqualTo("2026-01-22T00:00:00Z");
        assertThat(stressSignals.get(0).get("scheduledExecutionTimeUtc"))
                .isEqualTo("2026-01-22T01:00:00Z");
        assertThat(normalOrders.get(0).get("executionTimeUtc")).isEqualTo("2026-01-22T00:00:00Z");
        assertThat(stressOrders.get(0).get("executionTimeUtc")).isEqualTo("2026-01-22T01:00:00Z");
    }

    private void assertFrozenScenario(BtcDonchianShadowEngine.ScenarioReplay actual,
                                      BtcDonchianShadowPolicy.Scenario expected) {
        assertThat(actual.signalLedger()).hasSize(expected.expectedSignals());
        assertThat(actual.orderLedger()).hasSize(expected.expectedOrders());
        assertThat(actual.tradeLedger()).hasSize(expected.expectedTrades());
        assertThat(actual.signalLedgerSha256()).isEqualTo(expected.expectedSignalLedgerSha256());
        assertThat(actual.orderLedgerSha256()).isEqualTo(expected.expectedOrderLedgerSha256());
        assertThat(actual.tradeLedgerSha256()).isEqualTo(expected.expectedTradeLedgerSha256());
    }

    private void assertLedgerRows(BtcDonchianShadowEngine.ScenarioReplay actual, JsonNode expected) throws Exception {
        assertRows("signal", actual.signalLedger(), expected.path("signalLedger"));
        assertRows("order", actual.orderLedger(), expected.path("orderLedger"));
        assertRows("trade", actual.tradeLedger(), expected.path("tradeLedger"));
    }

    private void assertRows(String ledger, List<Map<String, Object>> actual, JsonNode expected) throws Exception {
        assertThat(actual).as(ledger + " ledger row count").hasSize(expected.size());
        for (int i = 0; i < actual.size(); i++) {
            String actualJson = objectMapper.writeValueAsString(actual.get(i));
            String expectedJson = objectMapper.writeValueAsString(expected.get(i));
            assertThat(actualJson).as(ledger + " ledger row " + (i + 1)).isEqualTo(expectedJson);
        }
    }

    private JsonNode expectedCandidate(Path csv) throws Exception {
        Path report = csv.getParent().resolve("price-only-research-report-isolated-v2.json");
        JsonNode root = objectMapper.readTree(report.toFile());
        for (JsonNode candidate : root.path("results")) {
            if (BtcDonchianShadowPolicy.POLICY_MODE.equals(candidate.path("candidateId").asText())) {
                return candidate;
            }
        }
        throw new IllegalStateException("Frozen Donchian candidate missing from " + report);
    }

    private void assertRawCashCheckpoints(List<MdKline> bars, String scenario, List<String> expectedBits) {
        BtcDonchianShadowEngine.State state = engine.initialState();
        int previousOrders = 0;
        for (MdKline bar : bars) {
            engine.step(state, bar);
            BtcDonchianShadowEngine.ScenarioState scenarioState = state.getScenarios().get(scenario);
            if (scenarioState.getOrderCount() > previousOrders) {
                int sequence = scenarioState.getOrderCount();
                if (sequence <= expectedBits.size()) {
                    assertThat(Double.doubleToLongBits(scenarioState.getCash()))
                            .as("raw cash bits after " + scenario + " order " + sequence)
                            .isEqualTo(Long.parseUnsignedLong(expectedBits.get(sequence - 1), 16));
                } else {
                    return;
                }
                previousOrders = sequence;
            }
        }
        assertThat(previousOrders).isGreaterThanOrEqualTo(expectedBits.size());
    }

    private void assertNormalOrder11RawInputs(List<MdKline> bars) {
        BtcDonchianShadowEngine.State state = engine.initialState();
        for (MdKline bar : bars) {
            BtcDonchianShadowEngine.StepResult step = engine.step(state, bar);
            for (BtcDonchianShadowEngine.RuntimeEvent event : step.events()) {
                if (!NORMAL.name().equals(event.scenario()) || !"VIRTUAL_ENTRY_FILL".equals(event.eventType())
                        || !Integer.valueOf(11).equals(event.payload().get("sequence"))) continue;
                assertThat(event.payload().get("rawEquityAtOpen")).isEqualTo(1.2010382466877678);
                assertThat(event.payload().get("rawTargetExposure")).isEqualTo(0.1673537930609479);
                assertThat(event.payload().get("rawAtr")).isEqualTo(231.81428571428563);
                assertThat(event.payload().get("rawGrossNotional")).isEqualTo(0.20099830619446837);
                assertThat(event.payload().get("rawFee")).isEqualTo(0.00020099830619446838);
                assertThat(event.payload().get("rawQuantityAfter")).isEqualTo(2.5892235760514944E-05);
                return;
            }
        }
        throw new IllegalStateException("Normal order 11 not found");
    }

    private List<MdKline> readCsv(Path csv) throws Exception {
        List<MdKline> bars = new ArrayList<>(GOLDEN_ROW_COUNT);
        try (Reader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8);
             CSVParser parser = CSVFormat.DEFAULT.builder()
                     .setHeader()
                     .setSkipHeaderRecord(true)
                     .build()
                     .parse(reader)) {
            for (CSVRecord row : parser) {
                LocalDateTime openTime = OffsetDateTime.parse(row.get("open_time_utc")).toLocalDateTime();
                MdKline bar = new MdKline();
                bar.setSymbol("BTCUSDT");
                bar.setIntervalCode("1h");
                bar.setSource("okx");
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
        String configured = System.getProperty("donchian.golden.csv", "");
        if (!configured.isBlank()) return Path.of(configured).toAbsolutePath().normalize();
        return Path.of("target", "research", "okx-btc-usdt-1h-final-ledger-v2",
                "okx-btc-usdt-1h-20260713T090000Z", "btc-usdt-okx-1h.csv")
                .toAbsolutePath().normalize();
    }

    private MdKline bar(LocalDateTime openTime, String open, String high, String low, String close) {
        MdKline bar = new MdKline();
        bar.setSymbol("BTCUSDT");
        bar.setIntervalCode("1h");
        bar.setSource("okx");
        bar.setOpenTime(openTime);
        bar.setCloseTime(openTime.plusHours(1));
        bar.setOpenPrice(new BigDecimal(open));
        bar.setHighPrice(new BigDecimal(high));
        bar.setLowPrice(new BigDecimal(low));
        bar.setClosePrice(new BigDecimal(close));
        bar.setVolume(BigDecimal.ONE);
        return bar;
    }
}
