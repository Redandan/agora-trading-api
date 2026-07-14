package com.agora.service.trading;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_DATASET_ID;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_DATASET_SHA256;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_FIRST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_LAST_OPEN_TIME;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_PRICE_BAR_LEDGER_SHA256;
import static com.agora.service.trading.BtcDonchianShadowPolicy.GOLDEN_ROW_COUNT;
import static com.agora.service.trading.BtcDonchianShadowPolicy.INTERVAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.NORMAL;
import static com.agora.service.trading.BtcDonchianShadowPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SOURCE;
import static com.agora.service.trading.BtcDonchianShadowPolicy.STRESS;
import static com.agora.service.trading.BtcDonchianShadowPolicy.SYMBOL;

/** Read-only DB replay against the immutable official research ledger contract. */
@Service
@RequiredArgsConstructor
public class BtcDonchianShadowGoldenParityService {

    private final MdKlineRepository klineRepository;
    private final BtcDonchianShadowEngine engine;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public String report(String requestedSymbol) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(analyzeNode(requestedSymbol));
        } catch (Exception e) {
            ObjectNode failed = baseReport();
            failed.put("status", "REPORT_FAILED_FAIL_CLOSED");
            failed.put("goldenParityPassed", false);
            failed.put("failure", safeMessage(e));
            return failed.toPrettyString();
        }
    }

    @Transactional(readOnly = true)
    public ObjectNode analyzeNode(String requestedSymbol) {
        ObjectNode report = baseReport();
        String symbol = normalizeSymbol(requestedSymbol);
        report.put("requestedSymbol", symbol);
        if (!SYMBOL.equals(symbol)) {
            report.put("status", "UNSUPPORTED_SYMBOL_FAIL_CLOSED");
            report.put("goldenParityPassed", false);
            report.withArray("blockers").add("UNSUPPORTED_SYMBOL");
            return report;
        }

        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        SYMBOL, INTERVAL, SOURCE, GOLDEN_FIRST_OPEN_TIME, GOLDEN_LAST_OPEN_TIME);
        report.put("actualRowCount", bars == null ? 0 : bars.size());
        report.put("actualFirstOpenTimeUtc", firstOpenTime(bars));
        report.put("actualLastOpenTimeUtc", lastOpenTime(bars));
        ArrayNode blockers = report.withArray("blockers");
        if (bars == null || bars.size() != GOLDEN_ROW_COUNT) blockers.add("GOLDEN_ROW_COUNT_MISMATCH");
        if (bars == null || bars.isEmpty() || !GOLDEN_FIRST_OPEN_TIME.equals(bars.get(0).getOpenTime())) {
            blockers.add("GOLDEN_FIRST_OPEN_TIME_MISMATCH");
        }
        if (bars == null || bars.isEmpty()
                || !GOLDEN_LAST_OPEN_TIME.equals(bars.get(bars.size() - 1).getOpenTime())) {
            blockers.add("GOLDEN_LAST_OPEN_TIME_MISMATCH");
        }
        if (!blockers.isEmpty()) {
            report.put("status", "GOLDEN_DATASET_INCOMPLETE_FAIL_CLOSED");
            report.put("goldenParityPassed", false);
            return report;
        }

        try {
            BtcDonchianShadowEngine.ReplayResult replay = engine.replay(bars);
            String actualPriceBarLedgerSha256 = engine.canonicalPriceBarLedgerSha256(bars);
            boolean priceBarPassed = GOLDEN_PRICE_BAR_LEDGER_SHA256.equals(actualPriceBarLedgerSha256);
            boolean normalPassed = addScenario(report.with("normal"), replay.scenarios().get(NORMAL.name()), NORMAL);
            boolean stressPassed = addScenario(report.with("stress"), replay.scenarios().get(STRESS.name()), STRESS);
            report.put("expectedPriceBarLedgerSha256", GOLDEN_PRICE_BAR_LEDGER_SHA256);
            report.put("actualPriceBarLedgerSha256", actualPriceBarLedgerSha256);
            report.put("canonicalPriceBarParityPassed", priceBarPassed);
            report.put("runtimeStateSha256", engine.stateSha256(replay.state()));
            report.put("hourlyLattice", "EXACT_CONTIGUOUS_UTC_1H");
            report.put("confirmedBarsOnly", true);
            report.put("semanticRowParityPassed", normalPassed && stressPassed);
            boolean passed = priceBarPassed && normalPassed && stressPassed;
            report.put("goldenParityPassed", passed);
            report.put("status", passed
                    ? "PASS_EXACT_RESEARCH_RUNTIME_GOLDEN_PARITY"
                    : "LEDGER_PARITY_MISMATCH_FAIL_CLOSED");
            if (!priceBarPassed) blockers.add("GOLDEN_PRICE_BAR_LEDGER_MISMATCH");
            if (!normalPassed) blockers.add("NORMAL_LEDGER_PARITY_MISMATCH");
            if (!stressPassed) blockers.add("STRESS_LEDGER_PARITY_MISMATCH");
        } catch (BtcDonchianShadowEngine.DataQualityException e) {
            blockers.add("DATA_QUALITY:" + safeMessage(e));
            report.put("status", "DATA_QUALITY_FAIL_CLOSED");
            report.put("goldenParityPassed", false);
        }
        return report;
    }

    private boolean addScenario(ObjectNode node,
                                BtcDonchianShadowEngine.ScenarioReplay actual,
                                BtcDonchianShadowPolicy.Scenario expected) {
        if (actual == null) {
            node.put("status", "MISSING");
            return false;
        }
        node.put("expectedSignalCount", expected.expectedSignals());
        node.put("actualSignalCount", actual.signalLedger().size());
        node.put("expectedOrderCount", expected.expectedOrders());
        node.put("actualOrderCount", actual.orderLedger().size());
        node.put("expectedTradeCount", expected.expectedTrades());
        node.put("actualTradeCount", actual.tradeLedger().size());
        node.put("expectedSignalLedgerSha256", expected.expectedSignalLedgerSha256());
        node.put("actualSignalLedgerSha256", actual.signalLedgerSha256());
        node.put("expectedOrderLedgerSha256", expected.expectedOrderLedgerSha256());
        node.put("actualOrderLedgerSha256", actual.orderLedgerSha256());
        node.put("expectedTradeLedgerSha256", expected.expectedTradeLedgerSha256());
        node.put("actualTradeLedgerSha256", actual.tradeLedgerSha256());
        boolean passed = actual.signalLedger().size() == expected.expectedSignals()
                && actual.orderLedger().size() == expected.expectedOrders()
                && actual.tradeLedger().size() == expected.expectedTrades()
                && expected.expectedSignalLedgerSha256().equals(actual.signalLedgerSha256())
                && expected.expectedOrderLedgerSha256().equals(actual.orderLedgerSha256())
                && expected.expectedTradeLedgerSha256().equals(actual.tradeLedgerSha256());
        node.put("passed", passed);
        return passed;
    }

    private ObjectNode baseReport() {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("tool", "analyzeBtcDonchianShadowGoldenParity");
        report.put("boundary", "READ_ONLY_REPLAY_NO_ORDER_NO_OCO_NO_TELEGRAM_NO_BACKFILL");
        report.put("policyMode", POLICY_MODE);
        report.put("symbol", SYMBOL);
        report.put("intervalCode", INTERVAL);
        report.put("source", SOURCE);
        report.put("goldenDatasetId", GOLDEN_DATASET_ID);
        report.put("declaredGoldenDatasetSha256", GOLDEN_DATASET_SHA256);
        report.put("expectedPriceBarLedgerSha256", GOLDEN_PRICE_BAR_LEDGER_SHA256);
        report.put("expectedRowCount", GOLDEN_ROW_COUNT);
        report.put("expectedFirstOpenTimeUtc", GOLDEN_FIRST_OPEN_TIME.toString());
        report.put("expectedLastOpenTimeUtc", GOLDEN_LAST_OPEN_TIME.toString());
        report.put("liveImplementationPresent", false);
        report.put("liveOrderAllowed", false);
        report.put("orderSent", false);
        report.put("ocoModified", false);
        report.put("telegramSent", false);
        report.put("externalBackfillPerformed", false);
        report.set("blockers", objectMapper.createArrayNode());
        return report;
    }

    private String firstOpenTime(List<MdKline> bars) {
        return bars == null || bars.isEmpty() ? null : bars.get(0).getOpenTime().toString();
    }

    private String lastOpenTime(List<MdKline> bars) {
        return bars == null || bars.isEmpty() ? null : bars.get(bars.size() - 1).getOpenTime().toString();
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return SYMBOL;
        return symbol.toUpperCase().replace("-", "").replace("/", "").replace("_", "");
    }

    private String safeMessage(Exception e) {
        String value = e == null ? "UNKNOWN" : e.getMessage();
        if (value == null || value.isBlank()) return e == null ? "UNKNOWN" : e.getClass().getSimpleName();
        return value.length() <= 300 ? value : value.substring(0, 300);
    }
}
