package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.OcoOrderStateInspector;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.SpotEconomicLedgerService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Narrow read-only position and OCO safety surface.
 */
@Service
@RequiredArgsConstructor
public class ExecutionSafetyMcpTools {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final OkxTradingService okxTradingService;
    private final SpotEconomicLedgerService spotEconomicLedgerService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "Read-only OCO execution safety status. Does not retry, cancel, modify, close, or place orders.")
    public String getExecutionSafetyStatus() {
        List<BtLiveSignal> positions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        if (positions.isEmpty()) {
            return "OCO_EXECUTION_SAFETY\nopenPositions=0\nstatus=OK";
        }

        StringBuilder result = new StringBuilder("OCO_EXECUTION_SAFETY\n");
        int protectedCount = 0;
        int intentionalNoOco = 0;
        int issueCount = 0;
        for (BtLiveSignal position : positions) {
            result.append("- id=").append(position.getId())
                    .append(" symbol=").append(position.getSymbol())
                    .append(" side=").append(position.getSide());
            if (position.getOcoOrderListId() == null) {
                if (BtcBasePositionStatePolicy.isBtcBase(position)) {
                    result.append(" state=INTENTIONAL_BTC_BASE_NO_OCO");
                    intentionalNoOco++;
                } else {
                    result.append(" state=UNPROTECTED");
                    issueCount++;
                }
                result.append('\n');
                continue;
            }
            try {
                OcoOrderStateInspector.Inspection inspection = "SHORT".equals(position.getSide())
                        ? ocoOrderStateInspector.inspectSwap(
                                position.getSymbol(), position.getOcoOrderListId())
                        : ocoOrderStateInspector.inspectSpot(
                                position.getSymbol(), position.getOcoOrderListId());
                result.append(" algoId=").append(position.getOcoOrderListId())
                        .append(" state=").append(inspection.effectiveState())
                        .append(" active=").append(inspection.active())
                        .append(" filled=").append(inspection.filled())
                        .append(" queryComplete=").append(inspection.queryComplete());
                if (inspection.active()) {
                    protectedCount++;
                } else {
                    issueCount++;
                }
                if (!inspection.errors().isEmpty()) {
                    result.append(" errors=").append(String.join(",", inspection.errors()));
                }
            } catch (Exception e) {
                result.append(" state=QUERY_FAILED error=")
                        .append(e.getClass().getSimpleName());
                issueCount++;
            }
            result.append('\n');
        }
        result.append("protected=").append(protectedCount)
                .append(" intentionalNoOco=").append(intentionalNoOco)
                .append(" issues=").append(issueCount)
                .append("\nstatus=").append(issueCount == 0 ? "OK" : "REVIEW");
        return result.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "Read-only open BTC spot inventory with ownership and gross mark-to-market PnL plus a fail-closed daily/cumulative realized ledger. Exact-net coverage is reported only for reconciled provider receipts. Does not place, cancel, or modify orders.")
    public String getOpenSpotPositions() {
        List<BtLiveSignal> positions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                        .filter(position -> !"SHORT".equals(position.getSide()))
                        .toList();
        StringBuilder result = new StringBuilder("OPEN_SPOT_POSITIONS\n");
        if (positions.isEmpty()) {
            return result.append("count=0\n\n")
                    .append(spotEconomicLedgerService.report())
                    .toString();
        }

        Map<String, BigDecimal> markPrices = new HashMap<>();
        Set<String> unavailableMarks = new HashSet<>();
        BigDecimal totalEntryCost = BigDecimal.ZERO;
        BigDecimal totalMarkedValue = BigDecimal.ZERO;
        BigDecimal totalGrossUnrealizedPnl = BigDecimal.ZERO;
        int pricedCount = 0;

        for (BtLiveSignal position : positions) {
            BigDecimal quantity = position.getTradedQty();
            BigDecimal entry = entryPrice(position);
            BigDecimal mark = markPrice(position.getSymbol(), markPrices, unavailableMarks);
            BigDecimal entryCost = multiply(entry, quantity);
            BigDecimal markedValue = multiply(mark, quantity);
            BigDecimal grossUnrealizedPnl = subtract(markedValue, entryCost);
            BigDecimal grossUnrealizedReturn = divide(grossUnrealizedPnl, entryCost);

            result.append("- id=").append(position.getId())
                    .append(" strategyId=").append(position.getStrategyId())
                    .append(" symbol=").append(position.getSymbol())
                    .append(" qty=").append(decimal(quantity))
                    .append(" entry=").append(decimal(entry))
                    .append(" ocoAlgoId=").append(position.getOcoOrderListId())
                    .append(" managementState=")
                    .append(BtcBasePositionStatePolicy.managementState(position))
                    .append(" automaticExitPolicy=")
                    .append(BtcBasePositionStatePolicy.automaticExitPolicy(position))
                    .append(" mark=").append(decimal(mark))
                    .append(" entryCost=").append(decimal(entryCost))
                    .append(" markedValue=").append(decimal(markedValue))
                    .append(" grossUnrealizedPnl=").append(decimal(grossUnrealizedPnl))
                    .append(" grossUnrealizedReturn=").append(decimal(grossUnrealizedReturn))
                    .append('\n');

            if (entryCost != null && markedValue != null && grossUnrealizedPnl != null) {
                totalEntryCost = totalEntryCost.add(entryCost);
                totalMarkedValue = totalMarkedValue.add(markedValue);
                totalGrossUnrealizedPnl = totalGrossUnrealizedPnl.add(grossUnrealizedPnl);
                pricedCount++;
            }
        }
        result.append("count=").append(positions.size())
                .append(" pricedCount=").append(pricedCount)
                .append(" valuationComplete=").append(pricedCount == positions.size())
                .append("\npricedEntryCost=").append(decimal(totalEntryCost))
                .append(" pricedMarkedValue=").append(decimal(totalMarkedValue))
                .append(" pricedGrossUnrealizedPnl=").append(decimal(totalGrossUnrealizedPnl))
                .append(" pricedGrossUnrealizedReturn=")
                .append(decimal(divide(totalGrossUnrealizedPnl, totalEntryCost)))
                .append("\nprofitBasis=GROSS_MARK_TO_MARKET_EXCLUDES_ENTRY_AND_EXIT_FEES")
                .append("\nasOf=").append(Instant.now())
                .append("\n\n")
                .append(spotEconomicLedgerService.report());
        return result.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "Read-only OKX spot and funding account snapshot. Earn is intentionally excluded. Does not move funds or place orders.")
    public String getExchangeAccountSafetySnapshot() {
        StringBuilder result = new StringBuilder("OKX_ACCOUNT_SAFETY_SNAPSHOT\n");
        appendHoldings(result, "trading", okxTradingService.getSpotHoldings());
        appendHoldings(result, "funding", okxTradingService.getFundingHoldings());
        return result.toString();
    }

    private void appendHoldings(StringBuilder result, String account,
                                List<OkxTradingService.SpotHolding> holdings) {
        result.append(account).append(":\n");
        if (holdings == null || holdings.isEmpty()) {
            result.append("- empty\n");
            return;
        }
        for (OkxTradingService.SpotHolding holding : holdings) {
            result.append("- ccy=").append(holding.ccy)
                    .append(" total=").append(decimal(holding.cashBal))
                    .append(" available=").append(decimal(holding.availBal))
                    .append(" usd=").append(decimal(holding.eqUsd))
                    .append('\n');
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private BigDecimal entryPrice(BtLiveSignal position) {
        return position.getActualEntryPrice() != null
                ? position.getActualEntryPrice()
                : position.getEntryPrice();
    }

    private BigDecimal markPrice(String symbol, Map<String, BigDecimal> markPrices,
                                 Set<String> unavailableMarks) {
        if (symbol == null || unavailableMarks.contains(symbol)) {
            return null;
        }
        if (markPrices.containsKey(symbol)) {
            return markPrices.get(symbol);
        }
        try {
            BigDecimal mark = okxTradingService.getLastPrice(symbol);
            if (mark == null || mark.signum() <= 0) {
                unavailableMarks.add(symbol);
                return null;
            }
            markPrices.put(symbol, mark);
            return mark;
        } catch (Exception ignored) {
            unavailableMarks.add(symbol);
            return null;
        }
    }

    private BigDecimal multiply(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.multiply(right);
    }

    private BigDecimal subtract(BigDecimal left, BigDecimal right) {
        return left == null || right == null ? null : left.subtract(right);
    }

    private BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.signum() == 0) {
            return null;
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }
}
