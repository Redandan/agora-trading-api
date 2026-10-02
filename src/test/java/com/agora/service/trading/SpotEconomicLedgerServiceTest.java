package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.model.SpotExecutionAttempt.State;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotEconomicLedgerServiceTest {

    @Test
    void noTradesIsNotAnAccountingFailureOrAZeroProfitClaim() {
        BtLiveSignalRepository signals = proxy(BtLiveSignalRepository.class, (method, args) -> List.of());
        SpotExecutionAttemptRepository attempts = proxy(SpotExecutionAttemptRepository.class,
                (method, args) -> { throw new AssertionError("No attempt read needed for empty inventory"); });
        String report = service(signals, attempts).report();
        assertTrue(report.contains("exactCoverage=0/0 exactNetScope=NO_CLOSED_LOTS comparableRealizedNetPnl=N/A comparisonStatus=NO_CLOSED_LOTS"));
    }

    @Test
    void reconciledReceiptsWithoutRecordedPnlRemainAnExplicitGap() {
        BtLiveSignal lot = closedLot(264L, BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "CLOSED",
                "0", LocalDateTime.now(ZoneOffset.UTC));
        lot.setRealizedPnl(null);
        BtLiveSignalRepository signals = proxy(BtLiveSignalRepository.class, (method, args) -> List.of(lot));
        SpotExecutionAttemptRepository attempts = proxy(SpotExecutionAttemptRepository.class,
                (method, args) -> List.of(filled((Side) args[1], "0.01")));
        String report = service(signals, attempts).report();
        assertTrue(report.contains("liveSignalId=264 owner=DRA_V1 reason=MISSING_RECORDED_PNL"));
        assertTrue(report.contains("exactCoverage=0/1 exactNetScope=VERIFIED_SUBSET_ONLY comparableRealizedNetPnl=N/A"));
    }

    @Test
    void keepsExactDraAndGrossLegacyEvidenceSeparate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        BtLiveSignal dra = closedLot(
                263L,
                BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "CLOSED",
                "1.25",
                now.minusHours(1));
        BtLiveSignal legacy = closedLot(
                260L,
                BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX + "123",
                "2.50",
                now.minusHours(2));

        BtLiveSignalRepository signals = proxy(
                BtLiveSignalRepository.class,
                (method, args) -> {
                    if (method.equals("findByAutoTradedIsTrueAndExitTimeIsNotNull")) {
                        return List.of(legacy, dra);
                    }
                    throw new UnsupportedOperationException(method);
                });
        SpotExecutionAttemptRepository attempts = proxy(
                SpotExecutionAttemptRepository.class,
                (method, args) -> {
                    if (method.equals("findByLiveSignalIdAndSideOrderByAttemptSequenceAsc")) {
                        Side side = (Side) args[1];
                        return List.of(filled(side, side == Side.BUY ? "0.01" : "0.02"));
                    }
                    throw new UnsupportedOperationException(method);
                });

        String report = service(signals, attempts).report();

        assertTrue(report.contains(
                "owner=DRA_V1 closedLots=1 recordedPnlLots=1 recordedRealizedPnl=1.25 "
                        + "exactNetLots=1 exactNetRealizedPnl=1.25 exactLifecycleFees=0.03 "
                        + "basis=EXACT_NET_PROVIDER_RECONCILED"));
        assertTrue(report.contains(
                "owner=LEGACY_BTC_BASE closedLots=1 recordedPnlLots=1 "
                        + "recordedRealizedPnl=2.5 exactNetLots=0 exactNetRealizedPnl=N/A "
                        + "exactLifecycleFees=N/A basis=GROSS_RECORDED_EXCLUDES_FEES"));
        assertTrue(report.contains("recordedRealizedPnl=NOT_COMPARABLE_MIXED_BASIS"));
        assertTrue(report.contains("exactCoverage=1/2 exactNetScope=VERIFIED_SUBSET_ONLY comparableRealizedNetPnl=N/A comparisonStatus=MISSING_PROOF_MIXED_OR_INCOMPLETE_BASIS"));
        assertTrue(report.contains("liveSignalId=260 owner=LEGACY_BTC_BASE reason=GROSS_RECORDED_EXCLUDES_FEES"));
        assertTrue(report.contains("maximumDrawdown=MISSING_PROOF_NO_MARK_TO_MARKET_EQUITY_SERIES"));
    }

    @Test void recordedProfitMismatchCannotBorrowReceiptCompleteness() {
        var lot = closedLot(263L, BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "CLOSED",
                "99", LocalDateTime.now(ZoneOffset.UTC));
        var signals = proxy(BtLiveSignalRepository.class, (method, args) -> List.of(lot));
        var attempts = proxy(SpotExecutionAttemptRepository.class,
                (method, args) -> List.of(filled((Side) args[1], args[1] == Side.BUY ? "0.01" : "0.02")));
        var result = service(signals, attempts).report();
        assertTrue(result.contains("RECORDED_PROVIDER_PNL_MISMATCH"));
        assertTrue(result.contains("exactCoverage=0/1"));
    }

    private static BtLiveSignal closedLot(
            long id, String reason, String pnl, LocalDateTime exitTime) {
        BtLiveSignal lot = new BtLiveSignal();
        lot.setId(id);
        lot.setSide("LONG");
        lot.setFilterReason(reason);
        lot.setExitTime(exitTime);
        lot.setRealizedPnl(new BigDecimal(pnl));
        return lot;
    }

    private static SpotExecutionAttempt filled(Side side, String feeUsdt) {
        SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        attempt.setSide(side);
        attempt.setLiveSignalId(263L);
        attempt.setStrategyContract(BtcDraPolicy.POLICY_MODE);
        attempt.setProvider("OKX");
        attempt.setProviderOrderId(side.name());
        attempt.setProviderReceiptJson("{\"ordId\":\"" + side.name() + "\"}");
        attempt.setAveragePrice(new BigDecimal(side == Side.BUY ? "10" : "11.28"));
        attempt.setGrossFillQuantity(BigDecimal.ONE);
        attempt.setNetFillQuantity(BigDecimal.ONE);
        attempt.setGrossQuoteAmount(attempt.getAveragePrice());
        attempt.setAppliedGrossQuoteAmount(attempt.getAveragePrice());
        attempt.setFeeCurrency("USDT");
        attempt.setSignedFeeAmount(new BigDecimal(feeUsdt).negate());
        attempt.setFeeUsdt(new BigDecimal(feeUsdt));
        attempt.setState(State.RECONCILED_FILLED);
        attempt.setAppliedFillQuantity(BigDecimal.ONE);
        attempt.setAppliedFeeUsdt(new BigDecimal(feeUsdt));
        attempt.setFeeReconciliationStatus(FeeReconciliationStatus.RECONCILED);
        return attempt;
    }

    private static SpotEconomicLedgerService service(BtLiveSignalRepository signals, SpotExecutionAttemptRepository attempts) {
        return new SpotEconomicLedgerService(signals, attempts,
                proxy(com.agora.repository.trading.BtDecisionAuditRepository.class, (method, args) -> List.of()),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> invocation.invoke(method.getName(), args));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
