package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** On-demand read-only realized-PnL ledger; it never writes or schedules work. */
@Service
@RequiredArgsConstructor
public class SpotEconomicLedgerService {

    private static final List<String> OWNER_ORDER = List.of(
            "DRA_V1", "TV509", "LEGACY_BTC_BASE", "BTC_BASE_OTHER", "UNATTRIBUTED");

    private final BtLiveSignalRepository liveSignalRepository;
    private final SpotExecutionAttemptRepository attemptRepository;
    private final BtDecisionAuditRepository auditRepository;
    private final ObjectMapper mapper;

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public String report() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime dayStartUtc = nowUtc.toLocalDate().atStartOfDay();
        var tvReceipts = auditRepository.findByEventTypeAndStrategyIdOrderByIdAsc(
                SpotFillReceiptEvidence.SCHEMA,
                com.agora.service.tradingview.TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID);
        List<LotEvidence> cumulative = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNull().stream()
                .filter(lot -> !"SHORT".equals(lot.getSide()))
                .sorted(Comparator.comparing(BtLiveSignal::getExitTime)
                        .thenComparing(BtLiveSignal::getId))
                .map(lot -> evidence(lot, tvReceipts))
                .toList();
        List<LotEvidence> daily = cumulative.stream()
                .filter(lot -> !lot.exitTime().isBefore(dayStartUtc))
                .toList();

        StringBuilder out = new StringBuilder("REALIZED_SPOT_PNL_LEDGER\n")
                .append("dayUtc=").append(dayStartUtc.toLocalDate()).append('\n');
        appendWindow(out, "daily", daily);
        appendWindow(out, "cumulative", cumulative);
        out.append("providerCashFlows (latest 50 verified lots):\n");
        cumulative.reversed().stream().filter(LotEvidence::exactNet).limit(50)
                .forEach(lot -> out.append("- liveSignalId=").append(lot.id())
                        .append(" owner=").append(lot.owner())
                        .append(" cashCostUsdt=").append(decimal(lot.cashCost()))
                        .append(" netProceedsUsdt=").append(decimal(lot.netProceeds()))
                        .append(" providerNetPnl=").append(decimal(lot.providerNetPnl()))
                        .append(" recordedPnlDelta=").append(decimal(lot.recordedRealizedPnl().subtract(lot.providerNetPnl())))
                        .append('\n'));
        out.append("evidenceGaps (latest 50 incomplete lots):\n");
        cumulative.reversed().stream().filter(lot -> !lot.exactNet()).limit(50)
                .forEach(lot -> out.append("- liveSignalId=").append(lot.id())
                        .append(" owner=").append(lot.owner())
                        .append(" reason=").append(lot.basis()).append('\n'));
        out.append("maximumDrawdown=MISSING_PROOF_NO_MARK_TO_MARKET_EQUITY_SERIES\n")
                .append("maximumDrawdownScope=FEE_EXACT_ACCOUNT_EQUITY;forwardRecordedSeries=SEE_FORWARD_SPOT_PERFORMANCE\n")
                .append("comparableTotalPnl=MISSING_PROOF_OPEN_FEES_AND_GRID_LIFECYCLE_NOT_UNIFIED\n")
                .append("recordedPnlWarning=NOT_COMPARABLE_ACROSS_MIXED_BASIS\n")
                .append("asOf=").append(nowUtc.toInstant(ZoneOffset.UTC));
        return out.toString();
    }

    private LotEvidence evidence(BtLiveSignal lot, List<com.agora.model.BtDecisionAudit> tvReceipts) {
        String owner = BtcBasePositionStatePolicy.economicOwner(lot);
        if (!"DRA_V1".equals(owner) && !"TV509".equals(owner)) {
            return new LotEvidence(
                    lot.getId(),
                    owner,
                    lot.getExitTime(),
                    lot.getRealizedPnl(),
                    false,
                    null,
                    basis(owner), null, null, null);
        }

        SpotEconomicLedgerEvidencePolicy.Evidence feeEvidence;
        if ("DRA_V1".equals(owner)) {
            List<SpotExecutionAttempt> buys = attemptRepository
                    .findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(lot.getId(), Side.BUY);
            List<SpotExecutionAttempt> sells = attemptRepository
                    .findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(lot.getId(), Side.SELL);
            feeEvidence = SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(buys, sells);
        } else {
            feeEvidence = SpotEconomicLedgerEvidencePolicy.evaluateTvLifecycle(
                    lot.getId(), tvReceipts, mapper);
        }
        // Runtime PnL is rounded to 8 decimals per application; compare independently computed cash.
        BigDecimal tolerance = new BigDecimal("0.00000001")
                .multiply(BigDecimal.valueOf(Math.max(1, feeEvidence.receiptCount())));
        boolean matches = feeEvidence.exactNet() && lot.getRealizedPnl() != null
                && lot.getRealizedPnl().subtract(feeEvidence.providerNetPnl()).abs().compareTo(tolerance) <= 0;
        boolean exactNet = feeEvidence.exactNet() && matches;
        return new LotEvidence(
                lot.getId(),
                owner,
                lot.getExitTime(),
                lot.getRealizedPnl(),
                exactNet,
                exactNet ? feeEvidence.lifecycleFeeUsdt() : null,
                exactNet ? "EXACT_NET_PROVIDER_RECONCILED"
                        : !feeEvidence.exactNet() ? feeEvidence.reason()
                        : lot.getRealizedPnl() == null ? "MISSING_RECORDED_PNL" : "RECORDED_PROVIDER_PNL_MISMATCH",
                feeEvidence.providerNetPnl(), feeEvidence.cashCostUsdt(), feeEvidence.netProceedsUsdt());
    }

    private static String basis(String owner) {
        return switch (owner) {
            case "TV509" -> "NET_RECORDED_FEE_EXACTNESS_UNPROVEN";
            case "LEGACY_BTC_BASE", "BTC_BASE_OTHER" -> "GROSS_RECORDED_EXCLUDES_FEES";
            default -> "UNKNOWN_PNL_BASIS";
        };
    }

    private static void appendWindow(StringBuilder out, String name, List<LotEvidence> lots) {
        out.append(name).append(":\n");
        Map<String, Bucket> buckets = new LinkedHashMap<>();
        for (String owner : OWNER_ORDER) buckets.put(owner, new Bucket());
        for (LotEvidence lot : lots) {
            buckets.computeIfAbsent(lot.owner(), ignored -> new Bucket()).add(lot);
        }
        boolean wroteOwner = false;
        for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
            if (entry.getValue().closedLots == 0) continue;
            wroteOwner = true;
            out.append("- owner=").append(entry.getKey()).append(' ');
            entry.getValue().append(out);
            out.append('\n');
        }
        if (!wroteOwner) out.append("- none\n");

        Bucket total = new Bucket();
        lots.forEach(total::add);
        out.append(name).append("Summary ");
        total.append(out);
        out.append(" exactCoverage=")
                .append(total.exactNetLots).append('/').append(total.closedLots)
                .append(" exactNetScope=")
                .append(total.closedLots == 0 ? "NO_CLOSED_LOTS"
                        : total.exactNetLots == total.closedLots ? "ALL_CLOSED_LOTS" : "VERIFIED_SUBSET_ONLY")
                .append(" comparableRealizedNetPnl=")
                .append(total.closedLots > 0 && total.exactNetLots == total.closedLots
                        ? decimal(total.exactNetRealizedPnl) : "N/A")
                .append(" comparisonStatus=")
                .append(total.closedLots == 0 ? "NO_CLOSED_LOTS" : total.exactNetLots == total.closedLots
                        ? "EXACT_NET_COMPLETE"
                        : "MISSING_PROOF_MIXED_OR_INCOMPLETE_BASIS")
                .append('\n');
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private record LotEvidence(
            Long id,
            String owner,
            LocalDateTime exitTime,
            BigDecimal recordedRealizedPnl,
            boolean exactNet,
            BigDecimal exactLifecycleFeeUsdt,
            String basis, BigDecimal providerNetPnl, BigDecimal cashCost, BigDecimal netProceeds) {
    }

    private static final class Bucket {
        private int closedLots;
        private int recordedPnlLots;
        private int exactNetLots;
        private BigDecimal recordedRealizedPnl = BigDecimal.ZERO;
        private BigDecimal exactNetRealizedPnl = BigDecimal.ZERO;
        private BigDecimal exactLifecycleFees = BigDecimal.ZERO;
        private final Set<String> bases = new LinkedHashSet<>();

        private void add(LotEvidence lot) {
            closedLots++;
            bases.add(lot.basis());
            if (lot.recordedRealizedPnl() != null) {
                recordedPnlLots++;
                recordedRealizedPnl = recordedRealizedPnl.add(lot.recordedRealizedPnl());
            }
            if (lot.exactNet()) {
                exactNetLots++;
                exactNetRealizedPnl = exactNetRealizedPnl.add(lot.providerNetPnl());
                exactLifecycleFees = exactLifecycleFees.add(lot.exactLifecycleFeeUsdt());
            }
        }

        private void append(StringBuilder out) {
            out.append("closedLots=").append(closedLots)
                    .append(" recordedPnlLots=").append(recordedPnlLots)
                    .append(" recordedRealizedPnl=")
                    .append(recordedPnlLots == 0 ? "N/A"
                            : bases.size() > 1 ? "NOT_COMPARABLE_MIXED_BASIS" : decimal(recordedRealizedPnl))
                    .append(" exactNetLots=").append(exactNetLots)
                    .append(" exactNetRealizedPnl=")
                    .append(exactNetLots == 0 ? "N/A" : decimal(exactNetRealizedPnl))
                    .append(" exactLifecycleFees=")
                    .append(exactNetLots == 0 ? "N/A" : decimal(exactLifecycleFees))
                    .append(" basis=")
                    .append(bases.isEmpty() ? "NONE" : String.join(",", bases));
        }
    }
}
