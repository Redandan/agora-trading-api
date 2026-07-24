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
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Narrow read-only position and OCO safety surface.
 */
@Service
@RequiredArgsConstructor
public class ExecutionSafetyMcpTools {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OcoOrderStateInspector ocoOrderStateInspector;
    private final OkxTradingService okxTradingService;

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
    @Tool(description = "Read-only open BTC spot position inventory used by execution-safety reconciliation. Does not place, cancel, or modify orders.")
    public String getOpenSpotPositions() {
        List<BtLiveSignal> positions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                        .filter(position -> !"SHORT".equals(position.getSide()))
                        .toList();
        StringBuilder result = new StringBuilder("OPEN_SPOT_POSITIONS\n");
        if (positions.isEmpty()) {
            return result.append("count=0").toString();
        }
        for (BtLiveSignal position : positions) {
            result.append("- id=").append(position.getId())
                    .append(" strategyId=").append(position.getStrategyId())
                    .append(" symbol=").append(position.getSymbol())
                    .append(" qty=").append(decimal(position.getTradedQty()))
                    .append(" entry=").append(decimal(entryPrice(position)))
                    .append(" ocoAlgoId=").append(position.getOcoOrderListId())
                    .append(" managementState=")
                    .append(BtcBasePositionStatePolicy.managementState(position))
                    .append('\n');
        }
        result.append("count=").append(positions.size());
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
}
