package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.model.SpotExecutionAttempt;
import com.agora.service.meta.DecisionAuditWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Receipt-only maintenance. Never creates, claims, retries, cancels or submits an order. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BtcDraOrderReconciliationService {
    private final OkxTradingProperties okxProperties;
    private final OkxTradingService provider;
    private final BtcDraExecutionAttemptService attempts;
    private final DecisionAuditWriter audit;

    /** Runs even when the entry lane is OFF, stale, corrupt or allocation-blocked. */
    public synchronized void reconcile() {
        if (!okxProperties.isEnabled() || !okxProperties.hasPrivateCredentials()) return;
        // Isolate the sides so a broken buy receipt cannot starve sell reconciliation.
        reconcileSide(SpotExecutionAttempt.Side.BUY);
        reconcileSide(SpotExecutionAttempt.Side.SELL);
    }

    private void reconcileSide(SpotExecutionAttempt.Side side) {
        try {
            var pending = side == SpotExecutionAttempt.Side.BUY
                    ? attempts.findOutstandingBuy() : attempts.findOutstandingSell();
            if (pending.isEmpty()) return;
            var attempt = pending.get();
            if (attempt.getState() == SpotExecutionAttempt.State.RESERVED) return;
            reconcileAttempt(attempt, side);
        } catch (Exception e) {
            log.warn("[DRA-Reconciliation] side={} receipt maintenance failed errorType={}",
                    side, e.getClass().getSimpleName());
        }
    }

    private void reconcileAttempt(SpotExecutionAttempt attempt, SpotExecutionAttempt.Side side) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("schema", "DRA_RECEIPT_MAINTENANCE_V1");
        context.put("executionAttemptId", attempt.getId());
        context.put("clientOrderId", attempt.getClientOrderId());
        context.put("side", side.name());
        context.put("orderSent", false);
        try {
            if (!BtcDraPolicy.POLICY_MODE.equals(attempt.getStrategyContract()) || side != attempt.getSide()) {
                throw new IllegalStateException("DRA_ATTEMPT_OWNERSHIP_MISMATCH");
            }
            var lookup = provider.lookupSpotOrderByClientOrderId("BTC-USDT", attempt.getClientOrderId());
            if (lookup.status() == OkxTradingService.SpotOrderLookupStatus.NOT_FOUND) {
                attempts.markLookupBlocked(attempt.getId(), "PROVIDER_ORDER_NOT_FOUND_NO_RETRY");
                return;
            }
            var snapshot = BtcDraLiveExecutionService.providerSnapshot(lookup.snapshot(), side.name());
            var result = side == SpotExecutionAttempt.Side.BUY
                    ? attempts.applyBuySnapshot(attempt.getId(), snapshot)
                    : attempts.applySellSnapshot(attempt.getId(), snapshot);
            context.put("providerOrderId", snapshot.providerOrderId());
            context.put("providerState", snapshot.providerState());
            context.put("attemptState", result.state().name());
            context.put("feeStatus", result.feeStatus().name());
            context.put("appliedQuantityDelta", result.appliedFillQuantity());
            context.put("appliedFeeDeltaUsdt", result.appliedFeeUsdt());
            context.put("realizedPnlDeltaUsdt", result.realizedPnlDelta());
            if (result.appliedFillQuantity().signum() > 0) {
                if (side == SpotExecutionAttempt.Side.SELL) {
                    audit.logExit(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID, "BTCUSDT", attempt.getLiveSignalId(),
                            "DRA_RECEIPT_RECONCILED", context);
                } else {
                    audit.logAutoTradeOk(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID, "BTCUSDT", attempt.getLiveSignalId(), context);
                }
            }
            // The transactional attempt retains every cumulative provider receipt, including fee-only updates.
            // Do not rewrite an old strategy decision to pretend a new bar was evaluated.
        } catch (Exception e) {
            attempts.markLookupBlocked(attempt.getId(), "RECEIPT_MAINTENANCE:" + e.getClass().getSimpleName());
            audit.logAutoTradeFail(BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID, "BTCUSDT",
                    "DRA_RECEIPT_MAINTENANCE_UNRESOLVED", context);
        }
    }
}
