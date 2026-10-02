package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.State;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import com.agora.model.BtDecisionAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import static com.agora.service.trading.SpotFillReceiptEvidence.*;

/** Fail-closed provider-fee evidence policy for the read-only spot ledger. */
public final class SpotEconomicLedgerEvidencePolicy {

    private SpotEconomicLedgerEvidencePolicy() {
    }

    public static Evidence evaluateDraLifecycle(
            List<SpotExecutionAttempt> buyAttempts,
            List<SpotExecutionAttempt> sellAttempts) {
        List<SpotExecutionAttempt> buys = safe(buyAttempts);
        List<SpotExecutionAttempt> sells = safe(sellAttempts);
        if (buys.isEmpty()) return Evidence.missing("MISSING_BUY_ATTEMPT");
        if (sells.isEmpty()) return Evidence.missing("MISSING_SELL_ATTEMPT");

        List<SpotExecutionAttempt> all = new ArrayList<>(buys.size() + sells.size());
        all.addAll(buys);
        all.addAll(sells);
        if (all.stream().anyMatch(attempt -> !terminal(attempt.getState()))) {
            return Evidence.missing("NON_TERMINAL_ATTEMPT");
        }
        if (all.stream().anyMatch(a -> a.getState() == State.REJECTED
                && a.getAppliedFillQuantity() != null && a.getAppliedFillQuantity().signum() != 0)) {
            return Evidence.missing("REJECTED_ATTEMPT_HAS_APPLIED_FILL");
        }
        if (all.stream().anyMatch(a -> a.getState() != State.REJECTED
                && (a.getAppliedFillQuantity() == null || a.getAppliedFillQuantity().signum() <= 0))) {
            return Evidence.missing("RECONCILED_ATTEMPT_WITHOUT_APPLIED_FILL");
        }

        List<SpotExecutionAttempt> filledBuys = filled(buys);
        List<SpotExecutionAttempt> filledSells = filled(sells);
        if (filledBuys.isEmpty()) return Evidence.missing("MISSING_APPLIED_BUY_FILL");
        if (filledSells.isEmpty()) return Evidence.missing("MISSING_APPLIED_SELL_FILL");

        List<SpotExecutionAttempt> filled = new ArrayList<>(
                filledBuys.size() + filledSells.size());
        filled.addAll(filledBuys);
        filled.addAll(filledSells);
        if (filled.stream().anyMatch(attempt ->
                attempt.getFeeReconciliationStatus() != FeeReconciliationStatus.RECONCILED
                        || attempt.getAppliedFeeUsdt() == null
                        || attempt.getAppliedFeeUsdt().signum() < 0)) {
            return Evidence.missing("FEE_RECONCILIATION_INCOMPLETE");
        }

        List<CashFlow> flows = new ArrayList<>();
        try {
            Long lotId = filledBuys.getFirst().getLiveSignalId();
            for (SpotExecutionAttempt a : filled) {
                if (lotId == null || !lotId.equals(a.getLiveSignalId())
                        || !BtcDraPolicy.POLICY_MODE.equals(a.getStrategyContract())
                        || !"OKX".equals(a.getProvider()) || a.getSide() == null
                        || buys.contains(a) != (a.getSide() == SpotExecutionAttempt.Side.BUY)
                        || a.getProviderReceiptJson() == null || a.getProviderReceiptJson().isBlank()
                        || !near(a.getGrossFillQuantity(), a.getAppliedFillQuantity())
                        || !near(a.getGrossQuoteAmount(), a.getAppliedGrossQuoteAmount())
                        || !near(a.getFeeUsdt(), a.getAppliedFeeUsdt())
                        || a.getAveragePrice() == null || a.getGrossFillQuantity() == null
                        || !near(a.getAveragePrice().multiply(a.getGrossFillQuantity()), a.getGrossQuoteAmount())) {
                    return Evidence.missing("PROVIDER_APPLIED_FIELDS_MISMATCH");
                }
                flows.add(calculate(a.getProviderOrderId(), a.getSide().name(), a.getAveragePrice(),
                        a.getGrossFillQuantity(), a.getNetFillQuantity(), a.getSignedFeeAmount(),
                        a.getFeeCurrency(), a.getFeeUsdt(), a.getSide() == SpotExecutionAttempt.Side.BUY
                                ? a.getNetFillQuantity() : a.getGrossFillQuantity()));
            }
            return lifecycle(flows, false);
        } catch (IllegalArgumentException e) {
            return Evidence.missing(e.getMessage());
        }
    }

    public static Evidence evaluateTvLifecycle(List<BtDecisionAudit> rows, ObjectMapper mapper) {
        List<CashFlow> flows = new ArrayList<>();
        try {
            for (BtDecisionAudit row : rows) {
                if (!SCHEMA.equals(row.getEventType())) continue;
                flows.add(parse(mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                        .readTree(row.getContextJson())));
            }
            return lifecycle(flows, true);
        } catch (Exception e) {
            return Evidence.missing("INVALID_OR_INCOMPLETE_FILL_RECEIPT");
        }
    }

    public static Evidence evaluateTvLifecycle(Long lotId, List<BtDecisionAudit> allRows, ObjectMapper mapper) {
        var lotRows = allRows.stream().filter(r -> lotId.equals(r.getLiveSignalId())).toList();
        Evidence result = evaluateTvLifecycle(lotRows, mapper);
        if (!result.exactNet()) return result;
        try {
            var reader = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            var relevantOrders = new java.util.HashSet<String>();
            for (BtDecisionAudit row : lotRows) {
                if (SCHEMA.equals(row.getEventType())) relevantOrders.add(reader.readTree(row.getContextJson())
                        .path("providerOrderId").asText());
            }
            var common = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            var allocated = new LinkedHashMap<String, BigDecimal>();
            var unique = new LinkedHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            for (BtDecisionAudit row : allRows) {
                if (!SCHEMA.equals(row.getEventType())) continue;
                var n = reader.readTree(row.getContextJson());
                String order = n.path("providerOrderId").asText();
                if (!relevantOrders.contains(order)) continue;
                if (row.getLiveSignalId() == null) return Evidence.missing("MISSING_ALLOCATION_OWNER");
                CashFlow flow = parse(n);
                var old = unique.putIfAbsent(order + ":" + row.getLiveSignalId(), n);
                if (old != null) {
                    if (!old.equals(n)) return Evidence.missing("CONFLICTING_LOT_ALLOCATION");
                    continue;
                }
                var providerFields = ((com.fasterxml.jackson.databind.node.ObjectNode) n).deepCopy();
                providerFields.remove("allocatedQuantity");
                var prior = common.putIfAbsent(order, providerFields);
                if (prior != null && !prior.equals(providerFields)) return Evidence.missing("CONFLICTING_PROVIDER_RECEIPT");
                allocated.merge(order, flow.quantity(), BigDecimal::add);
            }
            for (String order : relevantOrders) {
                var n = common.get(order);
                BigDecimal providerQuantity = n.path("BUY".equals(n.path("side").asText())
                        ? "netQuantity" : "grossQuantity").decimalValue();
                if (!near(providerQuantity, allocated.get(order))) {
                    return Evidence.missing("INCOMPLETE_OR_EXCESS_PROVIDER_ORDER_ALLOCATION");
                }
            }
            return result;
        } catch (Exception e) {
            return Evidence.missing("INVALID_GLOBAL_ALLOCATION_EVIDENCE");
        }
    }

    static Evidence lifecycle(List<CashFlow> flows, boolean allowIdenticalAuditDuplicates) {
        var unique = new LinkedHashMap<String, CashFlow>();
        for (CashFlow flow : flows) {
            CashFlow old = unique.putIfAbsent(flow.orderId(), flow);
            if (old != null && (!allowIdenticalAuditDuplicates || !sameFlow(old, flow))) {
                return Evidence.missing("DUPLICATE_OR_CONFLICTING_PROVIDER_ORDER");
            }
        }
        BigDecimal bought = BigDecimal.ZERO, sold = BigDecimal.ZERO, cost = BigDecimal.ZERO,
                proceeds = BigDecimal.ZERO, fees = BigDecimal.ZERO;
        for (CashFlow flow : unique.values()) {
            if ("BUY".equals(flow.side())) {
                bought = bought.add(flow.quantity());
                cost = cost.add(flow.cashUsdt());
            } else {
                sold = sold.add(flow.quantity());
                proceeds = proceeds.add(flow.cashUsdt());
            }
            fees = fees.add(flow.feeUsdt());
        }
        if (bought.signum() == 0) return Evidence.missing("MISSING_BUY_ATTEMPT");
        if (sold.signum() == 0) return Evidence.missing("MISSING_SELL_ATTEMPT");
        if (!near(bought, sold)) return Evidence.missing("UNRECONCILED_BASE_QUANTITY_OR_DUST:residualBtc="
                + bought.subtract(sold).stripTrailingZeros().toPlainString());
        return new Evidence(true, fees, "PROVIDER_CASH_FLOW_RECONCILED", proceeds.subtract(cost),
                cost, proceeds, unique.size());
    }

    private static boolean sameFlow(CashFlow a, CashFlow b) {
        return a.side().equals(b.side()) && near(a.quantity(), b.quantity())
                && near(a.cashUsdt(), b.cashUsdt()) && near(a.feeUsdt(), b.feeUsdt());
    }

    private static List<SpotExecutionAttempt> safe(List<SpotExecutionAttempt> attempts) {
        return attempts == null ? List.of() : attempts;
    }

    private static List<SpotExecutionAttempt> filled(List<SpotExecutionAttempt> attempts) {
        return attempts.stream()
                .filter(attempt -> attempt.getAppliedFillQuantity() != null
                        && attempt.getAppliedFillQuantity().signum() > 0)
                .toList();
    }

    private static boolean terminal(State state) {
        return state == State.RECONCILED_FILLED
                || state == State.RECONCILED_PARTIAL
                || state == State.REJECTED;
    }

    public record Evidence(boolean exactNet, BigDecimal lifecycleFeeUsdt, String reason,
                           BigDecimal providerNetPnl, BigDecimal cashCostUsdt,
                           BigDecimal netProceedsUsdt, int receiptCount) {
        private static Evidence missing(String reason) {
            return new Evidence(false, null, reason, null, null, null, 0);
        }
    }
}
