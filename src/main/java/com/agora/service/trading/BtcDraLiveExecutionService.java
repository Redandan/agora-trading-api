package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.model.RuntimeDecisionEvidence;
import com.agora.model.SpotExecutionAttempt;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.strategy.StrategyLifecycleMode;
import com.agora.service.strategy.StrategyRuntimeCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.agora.service.trading.BtcDraPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraPolicy.BASE_NOTIONAL_USDT;
import static com.agora.service.trading.BtcDraPolicy.EXECUTION_SYMBOL;
import static com.agora.service.trading.BtcDraPolicy.FEE_RATE_PER_SIDE;
import static com.agora.service.trading.BtcDraPolicy.INTERVAL;
import static com.agora.service.trading.BtcDraPolicy.NET_PROFIT_TRIGGER;
import static com.agora.service.trading.BtcDraPolicy.POLICY_MODE;
import static com.agora.service.trading.BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID;
import static com.agora.service.trading.BtcDraPolicy.SOURCE;
import static com.agora.service.trading.BtcDraPolicy.SYMBOL;

/**
 * Bounded LIVE adapter for the authorized DRA V1 30 USDT canary.
 *
 * <p>The canonical DRA lane commits state and evidence before this adapter is
 * called. This adapter then enforces only mechanical execution correctness:
 * fresh current bar, exact 30 USDT configuration, one durable strategy-owned
 * lot, deterministic OKX client order ids, fill persistence, and no retry of
 * ambiguous submissions. It never creates OCO, changes Grid, moves funds, or
 * sells another strategy's BTC.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BtcDraLiveExecutionService {

    static final String POSITION_PREFIX = BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX;
    static final String EXECUTION_MODE = "OKX_SPOT_LIVE_CANARY";

    private static final String SIDE = "LONG";

    private final BtcDraRuntimeProperties properties;
    private final OkxTradingProperties okxProperties;
    private final OkxTradingService okxTradingService;
    private final BtcDraExecutionAttemptService executionAttemptService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final RuntimeDecisionEvidenceRepository evidenceRepository;
    private final DecisionAuditWriter auditWriter;
    private final StrategyRuntimeCatalog strategyRuntimeCatalog;
    private final ObjectMapper objectMapper;

    public boolean executionArmed() {
        return strategyRuntimeCatalog.isMode(POLICY_MODE, StrategyLifecycleMode.LIVE)
                && properties.liveOrderEnabled()
                && exactCanaryConfiguration()
                && okxProperties.isEnabled()
                && okxProperties.hasPrivateCredentials();
    }

    /**
     * Serialized inside one JVM. Database uniqueness plus deterministic OKX
     * client ids provide restart/concurrent-delivery duplicate protection.
     */
    public synchronized void evaluate(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        if (!properties.liveOrderEnabled() || observation == null) {
            return;
        }
        String blocker = scopeBlocker(observation);
        if (blocker != null) {
            if (hasEntrySignal(observation)) {
                auditSkip(observation, blocker, null);
            }
            return;
        }

        if (reconcileOutstandingBuyAttempt(observation)) {
            return;
        }
        boolean exitHandledOrPending =
                executeEligibleExit(observation);
        if (!exitHandledOrPending
                && hasEntrySignal(observation)) {
            executeBuy(observation);
        }
    }

    private String scopeBlocker(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        if (!executionArmed()) return "DRA_LIVE_NOT_ARMED";
        if (!observation.exactFreshSingleBar()) {
            return "DRA_LIVE_REQUIRES_EXACT_FRESH_SINGLE_BAR";
        }
        MdKline bar = observation.bar();
        if (bar == null
                || !SYMBOL.equals(normalizeSymbol(bar.getSymbol()))
                || !INTERVAL.equalsIgnoreCase(bar.getIntervalCode())
                || !SOURCE.equalsIgnoreCase(bar.getSource())) {
            return "DRA_LIVE_SCOPE_NOT_ALLOWLISTED";
        }
        LocalDateTime closeTime = bar.getCloseTime();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (closeTime == null
                || !bar.getOpenTime().plusHours(1).equals(closeTime)
                || closeTime.isAfter(now.plusMinutes(1))) {
            return "DRA_LIVE_BAR_NOT_CONFIRMED_CLOSED";
        }
        long ageMinutes = Math.max(0L, Duration.between(closeTime, now).toMinutes());
        if (ageMinutes > properties.liveMaxSignalAgeMinutes()) {
            return "DRA_LIVE_SIGNAL_STALE_" + ageMinutes + "_MINUTES";
        }
        return null;
    }

    private boolean executeBuy(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        BtcDraShadowEngine.RuntimeEvent entryEvent = observation.step().events().stream()
                .filter(event -> "VIRTUAL_ENTRY_QUEUED".equals(event.eventType()))
                .findFirst()
                .orElse(null);
        if (entryEvent == null) return false;

        String blocker = buyBlocker(observation, entryEvent);
        if (blocker != null) {
            auditSkip(observation, blocker, null);
            return false;
        }

        String clientOrderId =
                SpotExecutionAttemptPolicy.draBuyClientOrderId(
                        entryEvent.signalBarOpenTime());
        Map<String, Object> context = baseContext(observation);
        context.put("action", "BUY");
        context.put("clientOrderId", clientOrderId);
        context.put("signalBarOpenTime", entryEvent.signalBarOpenTime());
        context.put("requestedNotionalUsdt", properties.liveNotionalUsdt());
        context.put("entryReason", entryEvent.reason());

        BtLiveSignal reservation;
        try {
            reservation = reserveBuy(observation.bar(), entryEvent, clientOrderId);
        } catch (DataIntegrityViolationException e) {
            auditSkip(observation, "DRA_DUPLICATE_SIGNAL_RESERVATION", null);
            return false;
        } catch (Exception e) {
            auditFailure("DRA_BUY_RESERVATION_FAILED", context, e);
            return false;
        }

        context.put("liveSignalId", reservation.getId());
        BtcDraExecutionAttemptService.Reservation attemptReservation;
        try {
            attemptReservation = executionAttemptService.reserveBuy(
                    reservation.getId(),
                    POLICY_MODE,
                    observation.bar().getOpenTime(),
                    properties.liveNotionalUsdt());
        } catch (Exception e) {
            markReservationState(
                    reservation,
                    "BUY_ATTEMPT_RESERVATION_FAILED:CL="
                            + clientOrderId);
            auditFailure(
                    "DRA_BUY_ATTEMPT_RESERVATION_FAILED",
                    context,
                    e);
            return false;
        }
        SpotExecutionAttempt attempt =
                attemptReservation.attempt();
        context.put("executionAttemptId", attempt.getId());
        context.put("attemptSequence", attempt.getAttemptSequence());
        if (!updateEvidence(
                observation,
                reservation.getId(),
                "DRA_LIVE_BUY_RESERVED",
                "LIVE_BUY_RESERVED",
                false,
                context)) {
            markReservationState(
                    reservation,
                    "BUY_EVIDENCE_RESERVATION_FAILED:CL=" + clientOrderId);
            auditFailure(
                    "DRA_BUY_EVIDENCE_RESERVATION_FAILED",
                    context,
                    new IllegalStateException("DRA_EVIDENCE_UPDATE_FAILED"));
            return false;
        }

        OkxTradingService.SpotOrderLookup lookup;
        try {
            lookup = okxTradingService.lookupSpotOrderByClientOrderId(
                    EXECUTION_SYMBOL,
                    clientOrderId);
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "PRE_SUBMIT_LOOKUP:"
                            + safe(e.getMessage(), 420));
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    reservation.getId(),
                    "DRA_LIVE_BUY_LOOKUP_UNRESOLVED",
                    "LIVE_BUY_LOOKUP_UNRESOLVED",
                    null,
                    context);
            auditFailure(
                    "DRA_BUY_LOOKUP_UNRESOLVED",
                    context,
                    e);
            return false;
        }
        if (lookup.status()
                == OkxTradingService.SpotOrderLookupStatus.FOUND) {
            return applyProviderBuySnapshot(
                    observation,
                    attempt,
                    providerSnapshot(lookup.snapshot(), "buy"),
                    context,
                    false,
                    "DRA_LIVE_BUY_PROVIDER_FOUND");
        }
        if (!executionAttemptService.claimForSubmission(
                attempt.getId(),
                LocalDateTime.now(ZoneOffset.UTC))) {
            auditSkip(
                    observation,
                    "DRA_BUY_SUBMISSION_CLAIM_LOST",
                    reservation.getId());
            return false;
        }

        TradeResult fill;
        try {
            fill = okxTradingService.placeMarketBuy(
                    EXECUTION_SYMBOL,
                    properties.liveNotionalUsdt()
                            .setScale(2, RoundingMode.HALF_UP)
                            .doubleValue(),
                    clientOrderId);
            requireValidFill(fill);
        } catch (Exception e) {
            executionAttemptService.markSubmissionUnknown(
                    attempt.getId(),
                    "BUY_SUBMISSION:"
                            + safe(e.getMessage(), 420));
            markSubmissionUnconfirmed(reservation, "BUY", clientOrderId, e);
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    reservation.getId(),
                    "DRA_LIVE_BUY_SUBMISSION_UNCONFIRMED",
                    "LIVE_BUY_SUBMISSION_UNCONFIRMED",
                    null,
                    context);
            auditFailure("DRA_BUY_SUBMISSION_UNCONFIRMED", context, e);
            return false;
        }

        return applyProviderBuySnapshot(
                observation,
                attempt,
                providerSnapshot(fill),
                context,
                true,
                "DRA_LIVE_BUY_FILLED");
    }

    private boolean reconcileOutstandingBuyAttempt(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        Optional<SpotExecutionAttempt> outstanding =
                executionAttemptService.findOutstandingBuy();
        if (outstanding.isEmpty()) return false;
        SpotExecutionAttempt attempt = outstanding.get();
        if (attempt.getState() == SpotExecutionAttempt.State.RESERVED) {
            if (attempt.getTriggerBarOpenTime().equals(
                    observation.bar().getOpenTime())) {
                return true;
            }
            try {
                executionAttemptService.rejectStaleUnsubmittedBuy(
                        attempt.getId(),
                        "STALE_RESERVED_BUY_NOT_SUBMITTED");
            } catch (Exception e) {
                auditFailure(
                        "DRA_STALE_BUY_RESERVATION_REJECT_FAILED",
                        baseContext(observation),
                        e);
            }
            return true;
        }

        Map<String, Object> context = baseContext(observation);
        context.put("action", "BUY_RECONCILIATION");
        context.put("executionAttemptId", attempt.getId());
        context.put("attemptSequence", attempt.getAttemptSequence());
        context.put("clientOrderId", attempt.getClientOrderId());
        context.put("liveSignalId", attempt.getLiveSignalId());
        try {
            OkxTradingService.SpotOrderLookup lookup =
                    okxTradingService.lookupSpotOrderByClientOrderId(
                            EXECUTION_SYMBOL,
                            attempt.getClientOrderId());
            if (lookup.status()
                    == OkxTradingService.SpotOrderLookupStatus.NOT_FOUND) {
                executionAttemptService.markLookupBlocked(
                        attempt.getId(),
                        "PROVIDER_ORDER_NOT_FOUND_NO_RETRY");
                context.put("providerOrderFound", false);
                context.put("submissionAmbiguous", true);
                updateEvidence(
                        observation,
                        attempt.getLiveSignalId(),
                        "DRA_LIVE_BUY_SUBMISSION_UNRESOLVED",
                        "LIVE_BUY_SUBMISSION_UNRESOLVED",
                        null,
                        context);
                return true;
            }
            return applyProviderBuySnapshot(
                    observation,
                    attempt,
                    providerSnapshot(lookup.snapshot(), "buy"),
                    context,
                    false,
                    "DRA_LIVE_BUY_RECONCILED");
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "BUY_RECONCILIATION_LOOKUP:"
                            + safe(e.getMessage(), 420));
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    "DRA_LIVE_BUY_RECONCILIATION_UNRESOLVED",
                    "LIVE_BUY_RECONCILIATION_UNRESOLVED",
                    null,
                    context);
            auditFailure(
                    "DRA_BUY_RECONCILIATION_UNRESOLVED",
                    context,
                    e);
            return true;
        }
    }

    private boolean applyProviderBuySnapshot(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            SpotExecutionAttempt attempt,
            BtcDraExecutionAttemptService.ProviderFillSnapshot snapshot,
            Map<String, Object> context,
            boolean orderSentNow,
            String action) {
        try {
            BtcDraExecutionAttemptService.ApplyResult result =
                    executionAttemptService.applyBuySnapshot(
                            attempt.getId(),
                            snapshot);
            context.put("providerOrderId", snapshot.providerOrderId());
            context.put("providerState", snapshot.providerState());
            context.put("avgPrice", snapshot.averagePrice());
            context.put("grossQty", snapshot.cumulativeGrossQuantity());
            context.put("netQty", snapshot.netQuantity());
            context.put("feeAmount", snapshot.signedFeeAmount());
            context.put("feeCurrency", snapshot.feeCurrency());
            context.put("feeUsdt", snapshot.feeUsdt());
            context.put("attemptState", result.state().name());
            context.put("feeStatus", result.feeStatus().name());
            boolean evidenceUpdated = updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    action,
                    result.state().name(),
                    orderSentNow,
                    context);
            if (result.appliedFillQuantity().signum() > 0
                    && (result.state()
                    == SpotExecutionAttempt.State.RECONCILED_FILLED
                    || result.state()
                    == SpotExecutionAttempt.State.RECONCILED_PARTIAL)) {
                auditSuccess(
                        "BUY",
                        attempt.getLiveSignalId(),
                        context);
            }
            if (!evidenceUpdated) {
                log.error(
                        "[DRA-LIVE] BUY reconciliation evidence failed "
                                + "attempt={} orderId={} clOrdId={}",
                        attempt.getId(),
                        snapshot.providerOrderId(),
                        attempt.getClientOrderId());
            }
            return true;
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "BUY_FILL_APPLY:"
                            + safe(e.getMessage(), 420));
            context.put("providerOrderId", snapshot.providerOrderId());
            context.put("orderSent", orderSentNow);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    "DRA_LIVE_BUY_FILL_APPLY_UNRESOLVED",
                    "LIVE_BUY_FILL_APPLY_UNRESOLVED",
                    orderSentNow ? true : null,
                    context);
            auditFailure(
                    "DRA_BUY_FILL_APPLY_UNRESOLVED",
                    context,
                    e);
            return false;
        }
    }

    private boolean executeEligibleExit(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        Optional<SpotExecutionAttempt> outstanding =
                executionAttemptService.findOutstandingSell();
        if (outstanding.isPresent()
                && outstanding.get().getState()
                != SpotExecutionAttempt.State.RESERVED) {
            reconcileOutstandingSellAttempt(
                    observation,
                    outstanding.get());
            return true;
        }

        List<BtLiveSignal> openLots = ownedOpenRows().stream()
                .filter(row -> Boolean.TRUE.equals(row.getAutoTraded()))
                .filter(row -> row.getFilterReason() != null
                        && (row.getFilterReason().contains(":OPEN:")
                        || row.getFilterReason().contains(":OPEN_PARTIAL:")))
                .toList();
        if (openLots.isEmpty()) return false;
        if (openLots.size() != 1) {
            auditSkip(observation, "DRA_SINGLE_LOT_INVARIANT_BROKEN", openLots.get(0).getId());
            return false;
        }
        BtLiveSignal lot = openLots.get(0);
        if (!positive(lot.getEntryPrice()) || !positive(lot.getTradedQty())) {
            auditSkip(observation, "DRA_OPEN_LOT_ACCOUNTING_INCOMPLETE", lot.getId());
            return false;
        }

        BigDecimal currentPrice;
        try {
            currentPrice = okxTradingService.getLastPrice(EXECUTION_SYMBOL);
        } catch (Exception e) {
            return false;
        }
        if (!positive(currentPrice)
                || estimatedNetReturn(lot, currentPrice).compareTo(NET_PROFIT_TRIGGER) < 0) {
            return false;
        }

        OkxTradingService.SpotInstrumentRules rules;
        try {
            rules = okxTradingService.getSpotInstrumentRules(EXECUTION_SYMBOL);
        } catch (Exception e) {
            auditFailure("DRA_EXIT_INSTRUMENT_RULES_UNAVAILABLE",
                    baseContext(observation), e);
            return false;
        }
        BigDecimal requestedQty = floorToLot(lot.getTradedQty(), rules.lotSize());
        if (!positive(requestedQty) || requestedQty.compareTo(rules.minSize()) < 0) {
            auditSkip(observation, "DRA_EXIT_MINIMUM_SIZE_NOT_MET", lot.getId());
            return false;
        }
        BigDecimal availableBtc;
        try {
            availableBtc = availableBtc();
        } catch (Exception e) {
            auditFailure("DRA_EXIT_BTC_BALANCE_UNAVAILABLE",
                    baseContext(observation), e);
            return false;
        }
        if (availableBtc.compareTo(requestedQty) < 0) {
            auditSkip(observation, "DRA_OWNED_BTC_NOT_FULLY_AVAILABLE", lot.getId());
            return false;
        }

        BtcDraExecutionAttemptService.Reservation reservation;
        try {
            reservation = executionAttemptService.reserveSell(
                    lot.getId(),
                    POLICY_MODE,
                    observation.bar().getOpenTime(),
                    requestedQty);
        } catch (Exception e) {
            auditFailure(
                    "DRA_SELL_ATTEMPT_RESERVATION_FAILED",
                    baseContext(observation),
                    e);
            return false;
        }
        SpotExecutionAttempt attempt = reservation.attempt();
        if (attempt.getState() != SpotExecutionAttempt.State.RESERVED) {
            reconcileOutstandingSellAttempt(observation, attempt);
            return true;
        }

        String clientOrderId = attempt.getClientOrderId();
        Map<String, Object> context = baseContext(observation);
        context.put("action", "SELL");
        context.put("executionAttemptId", attempt.getId());
        context.put("attemptSequence", attempt.getAttemptSequence());
        context.put("clientOrderId", clientOrderId);
        context.put("liveSignalId", lot.getId());
        context.put("currentPrice", currentPrice);
        context.put("requestedQty", requestedQty);
        context.put("estimatedNetReturn", estimatedNetReturn(lot, currentPrice));

        if (!updateEvidence(
                observation,
                lot.getId(),
                "DRA_LIVE_SELL_RESERVED",
                "LIVE_SELL_RESERVED",
                false,
                context)) {
            auditFailure(
                    "DRA_SELL_EVIDENCE_RESERVATION_FAILED",
                    context,
                    new IllegalStateException("DRA_EVIDENCE_UPDATE_FAILED"));
            return false;
        }

        OkxTradingService.SpotOrderLookup lookup;
        try {
            lookup = okxTradingService.lookupSpotOrderByClientOrderId(
                    EXECUTION_SYMBOL,
                    clientOrderId);
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "PRE_SUBMIT_LOOKUP:" + safe(e.getMessage(), 420));
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    lot.getId(),
                    "DRA_LIVE_SELL_LOOKUP_UNRESOLVED",
                    "LIVE_SELL_LOOKUP_UNRESOLVED",
                    null,
                    context);
            auditFailure(
                    "DRA_SELL_LOOKUP_UNRESOLVED",
                    context,
                    e);
            return false;
        }
        if (lookup.status()
                == OkxTradingService.SpotOrderLookupStatus.FOUND) {
            return applyProviderSellSnapshot(
                    observation,
                    attempt,
                    providerSnapshot(lookup.snapshot()),
                    context,
                    false,
                    "DRA_LIVE_SELL_PROVIDER_FOUND");
        }

        if (!executionAttemptService.claimForSubmission(
                attempt.getId(),
                LocalDateTime.now(ZoneOffset.UTC))) {
            auditSkip(
                    observation,
                    "DRA_SELL_SUBMISSION_CLAIM_LOST",
                    lot.getId());
            return false;
        }

        TradeResult fill;
        try {
            fill = okxTradingService.placeMarketSellWithFill(
                    EXECUTION_SYMBOL,
                    requestedQty,
                    clientOrderId);
            requireValidFill(fill);
        } catch (Exception e) {
            executionAttemptService.markSubmissionUnknown(
                    attempt.getId(),
                    "SELL_SUBMISSION:" + safe(e.getMessage(), 420));
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    lot.getId(),
                    "DRA_LIVE_SELL_SUBMISSION_UNCONFIRMED",
                    "LIVE_SELL_SUBMISSION_UNCONFIRMED",
                    null,
                    context);
            auditFailure("DRA_SELL_SUBMISSION_UNCONFIRMED", context, e);
            return false;
        }

        return applyProviderSellSnapshot(
                observation,
                attempt,
                providerSnapshot(fill),
                context,
                true,
                "DRA_LIVE_SELL_FILLED");
    }

    private void reconcileOutstandingSellAttempt(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            SpotExecutionAttempt attempt) {
        Map<String, Object> context = baseContext(observation);
        context.put("action", "SELL_RECONCILIATION");
        context.put("executionAttemptId", attempt.getId());
        context.put("attemptSequence", attempt.getAttemptSequence());
        context.put("clientOrderId", attempt.getClientOrderId());
        context.put("liveSignalId", attempt.getLiveSignalId());
        try {
            OkxTradingService.SpotOrderLookup lookup =
                    okxTradingService.lookupSpotOrderByClientOrderId(
                            EXECUTION_SYMBOL,
                            attempt.getClientOrderId());
            if (lookup.status()
                    == OkxTradingService.SpotOrderLookupStatus.NOT_FOUND) {
                executionAttemptService.markLookupBlocked(
                        attempt.getId(),
                        "PROVIDER_ORDER_NOT_FOUND_NO_RETRY");
                context.put("providerOrderFound", false);
                context.put("submissionAmbiguous", true);
                updateEvidence(
                        observation,
                        attempt.getLiveSignalId(),
                        "DRA_LIVE_SELL_SUBMISSION_UNRESOLVED",
                        "LIVE_SELL_SUBMISSION_UNRESOLVED",
                        null,
                        context);
                return;
            }
            applyProviderSellSnapshot(
                    observation,
                    attempt,
                    providerSnapshot(lookup.snapshot()),
                    context,
                    false,
                    "DRA_LIVE_SELL_RECONCILED");
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "RECONCILIATION_LOOKUP:"
                            + safe(e.getMessage(), 420));
            context.put("submissionAmbiguous", true);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    "DRA_LIVE_SELL_RECONCILIATION_UNRESOLVED",
                    "LIVE_SELL_RECONCILIATION_UNRESOLVED",
                    null,
                    context);
            auditFailure(
                    "DRA_SELL_RECONCILIATION_UNRESOLVED",
                    context,
                    e);
        }
    }

    private boolean applyProviderSellSnapshot(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            SpotExecutionAttempt attempt,
            BtcDraExecutionAttemptService.ProviderFillSnapshot snapshot,
            Map<String, Object> context,
            boolean orderSentNow,
            String action) {
        try {
            BtcDraExecutionAttemptService.ApplyResult result =
                    executionAttemptService.applySellSnapshot(
                            attempt.getId(),
                            snapshot);
            context.put("providerOrderId", snapshot.providerOrderId());
            context.put("providerState", snapshot.providerState());
            context.put("avgPrice", snapshot.averagePrice());
            context.put(
                    "providerCumulativeGrossQty",
                    snapshot.cumulativeGrossQuantity());
            context.put(
                    "appliedSoldQty",
                    result.appliedFillQuantity());
            context.put(
                    "remainingQty",
                    result.remainingLotQuantity());
            context.put(
                    "appliedFeeUsdt",
                    result.appliedFeeUsdt());
            context.put(
                    "realizedPnlDeltaUsdt",
                    result.realizedPnlDelta());
            context.put("attemptState", result.state().name());
            context.put("feeStatus", result.feeStatus().name());
            boolean evidenceUpdated = updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    action,
                    result.state().name(),
                    orderSentNow,
                    context);
            if (result.appliedFillQuantity().signum() > 0) {
                auditSuccess(
                        "SELL",
                        attempt.getLiveSignalId(),
                        context);
            }
            if (!evidenceUpdated) {
                log.error(
                        "[DRA-LIVE] SELL reconciliation evidence failed "
                                + "attempt={} orderId={} clOrdId={}",
                        attempt.getId(),
                        snapshot.providerOrderId(),
                        attempt.getClientOrderId());
            }
            return true;
        } catch (Exception e) {
            executionAttemptService.markLookupBlocked(
                    attempt.getId(),
                    "FILL_APPLY:" + safe(e.getMessage(), 420));
            context.put("providerOrderId", snapshot.providerOrderId());
            context.put("orderSent", orderSentNow);
            context.put("error", safe(e.getMessage(), 320));
            updateEvidence(
                    observation,
                    attempt.getLiveSignalId(),
                    "DRA_LIVE_SELL_FILL_APPLY_UNRESOLVED",
                    "LIVE_SELL_FILL_APPLY_UNRESOLVED",
                    orderSentNow ? true : null,
                    context);
            auditFailure(
                    "DRA_SELL_FILL_APPLY_UNRESOLVED",
                    context,
                    e);
            return false;
        }
    }

    private BtcDraExecutionAttemptService.ProviderFillSnapshot
            providerSnapshot(TradeResult fill) {
        BigDecimal grossQuantity = positive(fill.getGrossQty())
                ? fill.getGrossQty()
                : fill.getQty();
        return new BtcDraExecutionAttemptService.ProviderFillSnapshot(
                fill.getOrderId(),
                "filled",
                fill.getAvgPrice(),
                grossQuantity,
                fill.getNetQty() == null
                        ? fill.getQty()
                        : fill.getNetQty(),
                fill.getFeeAmount(),
                fill.getFeeCurrency(),
                fill.getFeeUsdt(),
                receiptJson(fill),
                LocalDateTime.now(ZoneOffset.UTC));
    }

    private BtcDraExecutionAttemptService.ProviderFillSnapshot
            providerSnapshot(
                    OkxTradingService.SpotOrderSnapshot snapshot) {
        return providerSnapshot(snapshot, "sell");
    }

    private BtcDraExecutionAttemptService.ProviderFillSnapshot
            providerSnapshot(
                    OkxTradingService.SpotOrderSnapshot snapshot,
                    String expectedSide) {
        if (!expectedSide.equalsIgnoreCase(snapshot.side())) {
            throw new IllegalStateException(
                    "DRA provider order side mismatch");
        }
        return new BtcDraExecutionAttemptService.ProviderFillSnapshot(
                snapshot.providerOrderId(),
                snapshot.providerState(),
                snapshot.averagePrice(),
                snapshot.cumulativeGrossQuantity(),
                snapshot.netQuantity(),
                snapshot.signedFeeAmount(),
                snapshot.feeCurrency(),
                snapshot.feeUsdt(),
                snapshot.providerReceiptJson(),
                snapshot.providerAt());
    }

    private String receiptJson(TradeResult fill) {
        try {
            return objectMapper.writeValueAsString(fill);
        } catch (Exception e) {
            return null;
        }
    }

    private String buyBlocker(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            BtcDraShadowEngine.RuntimeEvent entryEvent) {
        if (BASE_NOTIONAL_USDT.compareTo(properties.liveNotionalUsdt()) != 0
                || BASE_NOTIONAL_USDT.compareTo(properties.maxLiveExposureUsdt()) != 0) {
            return "DRA_CANARY_CONFIGURATION_NOT_EXACT_30_USDT";
        }
        if (entryEvent.signalBarOpenTime() == null) {
            return "DRA_SIGNAL_TIME_MISSING";
        }
        if (liveSignalRepository.findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime(
                RUNTIME_LEDGER_STRATEGY_ID,
                SYMBOL,
                INTERVAL,
                entryEvent.signalBarOpenTime()).isPresent()) {
            return "DRA_DUPLICATE_SIGNAL_ALREADY_RESERVED";
        }
        List<BtLiveSignal> open = ownedOpenRows();
        if (open.stream().anyMatch(row -> !Boolean.TRUE.equals(row.getAutoTraded()))) {
            return "DRA_UNRESOLVED_ORDER_RESERVATION";
        }
        if (!open.isEmpty()) {
            return "DRA_SINGLE_LOT_ALREADY_OPEN";
        }
        BigDecimal availableUsdt;
        try {
            availableUsdt = new BigDecimal(okxTradingService.getUsdtBalance());
        } catch (Exception e) {
            return "DRA_OKX_USDT_BALANCE_UNAVAILABLE";
        }
        if (availableUsdt.compareTo(properties.liveNotionalUsdt()) < 0) {
            return "DRA_INSUFFICIENT_AVAILABLE_USDT";
        }
        try {
            BigDecimal current = okxTradingService.getLastPrice(EXECUTION_SYMBOL);
            OkxTradingService.SpotInstrumentRules rules =
                    okxTradingService.getSpotInstrumentRules(EXECUTION_SYMBOL);
            if (!positive(current) || !positive(rules.minSize())) {
                return "DRA_OKX_INSTRUMENT_RULES_UNAVAILABLE";
            }
            BigDecimal estimatedQty = properties.liveNotionalUsdt()
                    .divide(current, 12, RoundingMode.DOWN);
            if (estimatedQty.compareTo(rules.minSize()) < 0) {
                return "DRA_OKX_MINIMUM_SIZE_NOT_MET";
            }
        } catch (Exception e) {
            return "DRA_OKX_PREFLIGHT_UNAVAILABLE:" + safe(e.getMessage(), 100);
        }
        return null;
    }

    private BtLiveSignal reserveBuy(
            MdKline bar,
            BtcDraShadowEngine.RuntimeEvent entryEvent,
            String clientOrderId) {
        BtLiveSignal signal = new BtLiveSignal();
        signal.setStrategyId(RUNTIME_LEDGER_STRATEGY_ID);
        signal.setSymbol(SYMBOL);
        signal.setIntervalCode(INTERVAL);
        signal.setBarOpenTime(entryEvent.signalBarOpenTime());
        signal.setEntryPrice(bar.getClosePrice());
        signal.setSuggestedTp(requiredExitPrice(bar.getClosePrice()));
        signal.setSuggestedSl(null);
        signal.setScore(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP));
        signal.setNnOutput(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        signal.setNotifiedAt(LocalDateTime.now(ZoneOffset.UTC));
        signal.setAutoTraded(false);
        signal.setExchangeOrderId(safe("PENDING:" + clientOrderId, 50));
        signal.setSide(SIDE);
        signal.setFilterReason(positionReason(
                "BUY_RESERVED:CL=" + clientOrderId
                        + ":REASON=" + safe(entryEvent.reason(), 240)));
        return liveSignalRepository.saveAndFlush(signal);
    }

    private List<BtLiveSignal> ownedOpenRows() {
        return liveSignalRepository
                .findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
                        RUNTIME_LEDGER_STRATEGY_ID,
                        SYMBOL,
                        INTERVAL)
                .stream()
                .filter(row -> row.getFilterReason() != null
                        && row.getFilterReason().startsWith(POSITION_PREFIX))
                .toList();
    }

    private boolean updateEvidence(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            Long liveSignalId,
            String action,
            String finalOutcome,
            Boolean orderSent,
            Map<String, Object> liveContext) {
        try {
            RuntimeDecisionEvidence evidence = evidenceRepository
                    .findById(observation.evidenceId())
                    .orElseThrow(() -> new IllegalStateException("DRA_EVIDENCE_NOT_FOUND"));
            if (!POLICY_MODE.equals(evidence.getPolicyMode())) {
                throw new IllegalStateException("DRA_EVIDENCE_POLICY_MISMATCH");
            }
            ObjectNode snapshot =
                    (ObjectNode) objectMapper.readTree(evidence.getFeaturesSnapshotJson());
            snapshot.put("liveImplementationPresent", true);
            if (orderSent == null) {
                snapshot.putNull("orderSent");
            } else {
                snapshot.put("orderSent", orderSent);
            }
            snapshot.put("liveSignalId", liveSignalId);
            snapshot.put("liveAction", action);
            snapshot.set("liveExecution", objectMapper.valueToTree(liveContext));

            evidence.setFeaturesSnapshotJson(objectMapper.writeValueAsString(snapshot));
            evidence.setExecutionMode(EXECUTION_MODE);
            evidence.setOrderSent(orderSent);
            evidence.setLiveSignalId(liveSignalId);
            evidence.setSelectedAction(action);
            evidence.setDecision(action.contains("SELL") ? "SELL" : "BUY");
            evidence.setFinalOutcome(finalOutcome);
            evidence.setReason(action);
            evidence.setSuppressionReason(
                    Boolean.TRUE.equals(orderSent)
                            ? null
                            : orderSent == null
                            ? "PROVIDER_SUBMISSION_OUTCOME_UNCONFIRMED"
                            : "DURABLE_RESERVATION_BEFORE_PROVIDER_SUBMISSION");
            evidence.setExecutionPreviewJson(objectMapper.writeValueAsString(liveContext));
            evidenceRepository.saveAndFlush(evidence);
            return true;
        } catch (Exception e) {
            log.error("[DRA-LIVE] evidence update failed evidenceId={} action={} error={}",
                    observation.evidenceId(), action, e.getMessage(), e);
            return false;
        }
    }

    private void markSubmissionUnconfirmed(
            BtLiveSignal lot,
            String side,
            String clientOrderId,
            Exception error) {
        markReservationState(
                lot,
                side + "_SUBMISSION_UNCONFIRMED:CL=" + clientOrderId
                        + ":ERR=" + safe(error.getMessage(), 220));
    }

    private void markReservationState(BtLiveSignal lot, String state) {
        try {
            lot.setFilterReason(positionReason(state));
            liveSignalRepository.saveAndFlush(lot);
        } catch (Exception persistError) {
            log.error("[DRA-LIVE] failed to persist reservation state liveSignal={} "
                            + "error={}",
                    lot == null ? null : lot.getId(),
                    persistError.getMessage(),
                    persistError);
        }
    }

    private void auditSkip(
            BtcDraRuntimeLaneService.RuntimeObservation observation,
            String blocker,
            Long liveSignalId) {
        Map<String, Object> context = baseContext(observation);
        context.put("blocker", blocker);
        auditWriter.logEntrySkip(
                RUNTIME_LEDGER_STRATEGY_ID,
                SYMBOL,
                INTERVAL,
                observation.bar().getOpenTime(),
                safe(blocker, 64),
                blocker,
                context,
                liveSignalId);
    }

    private void auditSuccess(
            String action,
            Long liveSignalId,
            Map<String, Object> context) {
        if ("SELL".equals(action)) {
            auditWriter.logExit(
                    RUNTIME_LEDGER_STRATEGY_ID,
                    SYMBOL,
                    liveSignalId,
                    "DRA_LIVE_SELL_FILLED",
                    context);
        } else {
            auditWriter.logAutoTradeOk(
                    RUNTIME_LEDGER_STRATEGY_ID,
                    SYMBOL,
                    liveSignalId,
                    context);
        }
    }

    private void auditFailure(
            String reason,
            Map<String, Object> context,
            Exception error) {
        Map<String, Object> copy = new LinkedHashMap<>(
                context == null ? Map.of() : context);
        copy.put("error", safe(error == null ? null : error.getMessage(), 320));
        auditWriter.logAutoTradeFail(
                RUNTIME_LEDGER_STRATEGY_ID,
                SYMBOL,
                reason,
                copy);
    }

    private Map<String, Object> baseContext(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("ownerAlias", "DRA");
        context.put("strategyContract", POLICY_MODE);
        context.put("runtimeLedgerStrategyId", RUNTIME_LEDGER_STRATEGY_ID);
        context.put("executionMode", EXECUTION_MODE);
        context.put("signalVenue", SOURCE);
        context.put("executionVenue", "okx");
        context.put("barOpenTime", observation.bar().getOpenTime());
        context.put("barCloseTime", observation.bar().getCloseTime());
        context.put("evidenceId", observation.evidenceId());
        context.put("liveNotionalUsdt", properties.liveNotionalUsdt());
        context.put("maxLiveExposureUsdt", properties.maxLiveExposureUsdt());
        context.put("singleLot", true);
        context.put("ocoModified", false);
        context.put("gridModified", false);
        context.put("telegramSent", false);
        return context;
    }

    private boolean hasEntrySignal(
            BtcDraRuntimeLaneService.RuntimeObservation observation) {
        return observation.step().events().stream()
                .anyMatch(event -> "VIRTUAL_ENTRY_QUEUED".equals(event.eventType()));
    }

    private boolean exactCanaryConfiguration() {
        return BASE_NOTIONAL_USDT.compareTo(properties.liveNotionalUsdt()) == 0
                && BASE_NOTIONAL_USDT.compareTo(properties.maxLiveExposureUsdt()) == 0;
    }

    private BigDecimal estimatedNetReturn(BtLiveSignal lot, BigDecimal currentPrice) {
        BigDecimal estimatedSellPrice = currentPrice
                .multiply(BigDecimal.ONE.subtract(ADVERSE_SLIPPAGE_RATE_PER_SIDE));
        BigDecimal estimatedNet = estimatedSellPrice
                .multiply(lot.getTradedQty())
                .multiply(BigDecimal.ONE.subtract(FEE_RATE_PER_SIDE));
        BigDecimal cost = lot.getEntryPrice().multiply(lot.getTradedQty());
        if (!positive(cost)) return BigDecimal.valueOf(-1);
        return estimatedNet.subtract(cost).divide(cost, 12, RoundingMode.HALF_UP);
    }

    private BigDecimal availableBtc() {
        return okxTradingService.getFreshSpotHoldings().stream()
                .filter(holding -> "BTC".equalsIgnoreCase(holding.ccy))
                .map(holding -> holding.availBal)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal floorToLot(BigDecimal quantity, BigDecimal lotSize) {
        if (!positive(quantity) || !positive(lotSize)) return BigDecimal.ZERO;
        return quantity.divide(lotSize, 0, RoundingMode.DOWN)
                .multiply(lotSize)
                .stripTrailingZeros();
    }

    private BigDecimal requiredExitPrice(BigDecimal effectiveEntry) {
        if (!positive(effectiveEntry)) return effectiveEntry;
        BigDecimal afterFeeAndSlippage = BigDecimal.ONE
                .subtract(FEE_RATE_PER_SIDE)
                .multiply(BigDecimal.ONE.subtract(ADVERSE_SLIPPAGE_RATE_PER_SIDE));
        return effectiveEntry
                .multiply(BigDecimal.ONE.add(NET_PROFIT_TRIGGER))
                .divide(afterFeeAndSlippage, 8, RoundingMode.HALF_UP);
    }

    private void requireValidFill(TradeResult fill) {
        if (fill == null
                || fill.getOrderId() == null
                || fill.getOrderId().isBlank()
                || !positive(fill.getAvgPrice())
                || !positive(fill.getQty())) {
            throw new IllegalStateException("DRA_OKX_FILL_INCOMPLETE");
        }
    }

    private String positionReason(String suffix) {
        return safe(POSITION_PREFIX + suffix, 500);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null ? "" : symbol.toUpperCase(Locale.ROOT)
                .replace("-", "")
                .replace("/", "")
                .replace("_", "");
    }

    private String safe(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
