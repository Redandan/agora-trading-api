package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
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

    public String report() {
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime dayStartUtc = nowUtc.toLocalDate().atStartOfDay();
        List<LotEvidence> cumulative = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNull().stream()
                .filter(lot -> !"SHORT".equals(lot.getSide()))
                .sorted(Comparator.comparing(BtLiveSignal::getExitTime)
                        .thenComparing(BtLiveSignal::getId))
                .map(this::evidence)
                .toList();
        List<LotEvidence> daily = cumulative.stream()
                .filter(lot -> !lot.exitTime().isBefore(dayStartUtc))
                .toList();

        StringBuilder out = new StringBuilder("REALIZED_SPOT_PNL_LEDGER\n")
                .append("dayUtc=").append(dayStartUtc.toLocalDate()).append('\n');
        appendWindow(out, "daily", daily);
        appendWindow(out, "cumulative", cumulative);
        out.append("maximumDrawdown=MISSING_PROOF_NO_MARK_TO_MARKET_EQUITY_SERIES\n")
                .append("comparableTotalPnl=MISSING_PROOF_OPEN_FEES_AND_GRID_LIFECYCLE_NOT_UNIFIED\n")
                .append("recordedPnlWarning=NOT_COMPARABLE_ACROSS_MIXED_BASIS\n")
                .append("asOf=").append(nowUtc.toInstant(ZoneOffset.UTC));
        return out.toString();
    }

    private LotEvidence evidence(BtLiveSignal lot) {
        String owner = BtcBasePositionStatePolicy.economicOwner(lot);
        if (!"DRA_V1".equals(owner)) {
            return new LotEvidence(
                    owner,
                    lot.getExitTime(),
                    lot.getRealizedPnl(),
                    false,
                    null,
                    basis(owner));
        }

        List<SpotExecutionAttempt> buys = attemptRepository
                .findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(lot.getId(), Side.BUY);
        List<SpotExecutionAttempt> sells = attemptRepository
                .findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(lot.getId(), Side.SELL);
        SpotEconomicLedgerEvidencePolicy.Evidence feeEvidence =
                SpotEconomicLedgerEvidencePolicy.evaluateDraLifecycle(buys, sells);
        boolean exactNet = feeEvidence.exactNet() && lot.getRealizedPnl() != null;
        return new LotEvidence(
                owner,
                lot.getExitTime(),
                lot.getRealizedPnl(),
                exactNet,
                exactNet ? feeEvidence.lifecycleFeeUsdt() : null,
                exactNet ? "EXACT_NET_PROVIDER_RECONCILED" : feeEvidence.reason());
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
                .append(" comparisonStatus=")
                .append(total.closedLots > 0 && total.exactNetLots == total.closedLots
                        ? "EXACT_NET_COMPLETE"
                        : "MISSING_PROOF_MIXED_OR_INCOMPLETE_BASIS")
                .append('\n');
    }

    private static String decimal(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private record LotEvidence(
            String owner,
            LocalDateTime exitTime,
            BigDecimal recordedRealizedPnl,
            boolean exactNet,
            BigDecimal exactLifecycleFeeUsdt,
            String basis) {
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
                exactNetRealizedPnl = exactNetRealizedPnl.add(lot.recordedRealizedPnl());
                exactLifecycleFees = exactLifecycleFees.add(lot.exactLifecycleFeeUsdt());
            }
        }

        private void append(StringBuilder out) {
            out.append("closedLots=").append(closedLots)
                    .append(" recordedPnlLots=").append(recordedPnlLots)
                    .append(" recordedRealizedPnl=")
                    .append(recordedPnlLots == 0 ? "N/A" : decimal(recordedRealizedPnl))
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
