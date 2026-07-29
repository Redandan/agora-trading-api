package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.SpotExecutionAttempt;
import com.agora.model.SpotExecutionAttempt.FeeReconciliationStatus;
import com.agora.model.SpotExecutionAttempt.Side;
import com.agora.model.SpotExecutionAttempt.State;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SpotExecutionAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DRA-only durable provider submission and cumulative-fill application.
 *
 * <p>Provider calls stay outside this class. Every method here is a narrow
 * database transaction so no network wait holds a row lock.</p>
 */
@Service
@RequiredArgsConstructor
public class BtcDraExecutionAttemptService {

    private static final String PROVIDER = "OKX";
    private static final BigDecimal DUST_QTY =
            new BigDecimal("0.00000001");

    private final SpotExecutionAttemptRepository attemptRepository;
    private final BtLiveSignalRepository liveSignalRepository;

    @Transactional
    public Reservation reserveBuy(
            Long liveSignalId,
            String strategyContract,
            LocalDateTime triggerBarOpenTime,
            BigDecimal requestedQuoteAmount) {
        BtLiveSignal reservation = liveSignalRepository
                .findByIdForUpdate(liveSignalId)
                .orElseThrow(() -> new IllegalStateException(
                        "DRA buy reservation not found"));
        requireDraRow(reservation);
        if (Boolean.TRUE.equals(reservation.getAutoTraded())
                || reservation.getExitTime() != null) {
            throw new IllegalStateException(
                    "DRA buy reservation is already terminal");
        }
        requirePositive(
                requestedQuoteAmount,
                "requestedQuoteAmount");
        Objects.requireNonNull(
                triggerBarOpenTime,
                "triggerBarOpenTime");

        Optional<SpotExecutionAttempt> existing =
                attemptRepository
                        .findTopByLiveSignalIdAndSideOrderByAttemptSequenceDesc(
                                liveSignalId,
                                Side.BUY);
        if (existing.isPresent()) {
            return new Reservation(
                    existing.get(),
                    false,
                    "DRA_BUY_ATTEMPT_ALREADY_RESERVED");
        }

        SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        attempt.setLiveSignalId(liveSignalId);
        attempt.setStrategyContract(strategyContract);
        attempt.setSide(Side.BUY);
        attempt.setAttemptSequence(1);
        attempt.setSignalBarOpenTime(reservation.getBarOpenTime());
        attempt.setTriggerBarOpenTime(triggerBarOpenTime);
        attempt.setClientOrderId(
                SpotExecutionAttemptPolicy.draBuyClientOrderId(
                        reservation.getBarOpenTime()));
        attempt.setProvider(PROVIDER);
        attempt.setState(State.RESERVED);
        attempt.setRequestedQuoteAmount(requestedQuoteAmount);
        attempt.setFeeReconciliationStatus(
                FeeReconciliationStatus.PENDING);
        attempt.setAppliedFillQuantity(BigDecimal.ZERO);
        attempt.setAppliedGrossQuoteAmount(BigDecimal.ZERO);
        attempt.setAppliedFeeUsdt(BigDecimal.ZERO);
        return new Reservation(
                attemptRepository.saveAndFlush(attempt),
                true,
                null);
    }

    @Transactional
    public Reservation reserveSell(
            Long liveSignalId,
            String strategyContract,
            LocalDateTime triggerBarOpenTime,
            BigDecimal requestedQuantity) {
        BtLiveSignal lot = requireOwnedOpenLot(liveSignalId);
        requirePositive(requestedQuantity, "requestedQuantity");
        Objects.requireNonNull(
                triggerBarOpenTime,
                "triggerBarOpenTime");
        if (requestedQuantity.compareTo(lot.getTradedQty()) > 0) {
            throw new IllegalArgumentException(
                    "requestedQuantity exceeds DRA-owned lot");
        }

        Optional<SpotExecutionAttempt> latestOptional =
                attemptRepository
                        .findTopByLiveSignalIdAndSideOrderByAttemptSequenceDesc(
                                liveSignalId,
                                Side.SELL);
        if (latestOptional.isPresent()) {
            SpotExecutionAttempt latest = latestOptional.get();
            if (isOutstanding(latest)) {
                return new Reservation(
                        latest,
                        false,
                        "DRA_SELL_ATTEMPT_ALREADY_OUTSTANDING");
            }
            if (latest.getTriggerBarOpenTime().equals(
                    triggerBarOpenTime)) {
                return new Reservation(
                        latest,
                        false,
                        "DRA_SELL_ATTEMPT_ALREADY_FINAL_ON_BAR");
            }
            if (latest.getState() == State.RECONCILED_FILLED) {
                return new Reservation(
                        latest,
                        false,
                        "DRA_LOT_ALREADY_FULLY_RECONCILED");
            }
        }

        int sequence = latestOptional
                .map(SpotExecutionAttempt::getAttemptSequence)
                .orElse(0) + 1;
        SpotExecutionAttempt attempt = new SpotExecutionAttempt();
        attempt.setLiveSignalId(liveSignalId);
        attempt.setStrategyContract(strategyContract);
        attempt.setSide(Side.SELL);
        attempt.setAttemptSequence(sequence);
        attempt.setSignalBarOpenTime(lot.getBarOpenTime());
        attempt.setTriggerBarOpenTime(triggerBarOpenTime);
        attempt.setClientOrderId(
                SpotExecutionAttemptPolicy.draSellClientOrderId(
                        lot.getBarOpenTime(),
                        sequence));
        attempt.setProvider(PROVIDER);
        attempt.setState(State.RESERVED);
        attempt.setRequestedBaseQuantity(requestedQuantity);
        attempt.setFeeReconciliationStatus(
                FeeReconciliationStatus.PENDING);
        attempt.setAppliedFillQuantity(BigDecimal.ZERO);
        attempt.setAppliedGrossQuoteAmount(BigDecimal.ZERO);
        attempt.setAppliedFeeUsdt(BigDecimal.ZERO);
        attempt.setRemainingLotQuantity(lot.getTradedQty());
        return new Reservation(
                attemptRepository.saveAndFlush(attempt),
                true,
                null);
    }

    @Transactional
    public boolean claimForSubmission(
            Long attemptId,
            LocalDateTime submittedAt) {
        return attemptRepository.claimForSubmission(
                attemptId,
                submittedAt) == 1;
    }

    @Transactional
    public void markSubmissionUnknown(
            Long attemptId,
            String error) {
        SpotExecutionAttempt attempt = requireAttemptForUpdate(
                attemptId);
        if (attempt.getState() == State.SUBMITTING) {
            attempt.setState(State.SUBMISSION_UNKNOWN);
        }
        attempt.setLastReconciliationError(safe(error, 500));
        attemptRepository.saveAndFlush(attempt);
    }

    @Transactional
    public void markLookupBlocked(
            Long attemptId,
            String error) {
        SpotExecutionAttempt attempt = requireAttemptForUpdate(
                attemptId);
        attempt.setLastReconciliationError(safe(error, 500));
        attemptRepository.saveAndFlush(attempt);
    }

    @Transactional
    public void rejectStaleUnsubmittedBuy(
            Long attemptId,
            String reason) {
        SpotExecutionAttempt preview = requireAttempt(attemptId);
        BtLiveSignal reservation = liveSignalRepository
                .findByIdForUpdate(preview.getLiveSignalId())
                .orElseThrow(() -> new IllegalStateException(
                        "DRA buy reservation not found"));
        SpotExecutionAttempt attempt = requireAttemptForUpdate(
                attemptId);
        if (attempt.getSide() != Side.BUY
                || attempt.getState() != State.RESERVED) {
            throw new IllegalStateException(
                    "only a RESERVED BUY may expire unsubmitted");
        }
        requireSameLot(preview, attempt);
        requireDraRow(reservation);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        attempt.setState(State.REJECTED);
        attempt.setFeeReconciliationStatus(
                FeeReconciliationStatus.NOT_APPLICABLE);
        attempt.setReconciledAt(now);
        attempt.setLastReconciliationError(safe(reason, 500));
        attemptRepository.saveAndFlush(attempt);

        reservation.setExitTime(now);
        reservation.setExitReason("DRA_BUY_RESERVATION_EXPIRED");
        reservation.setFilterReason(
                BtcDraLiveExecutionService.POSITION_PREFIX
                        + "BUY_RESERVATION_EXPIRED:CL="
                        + attempt.getClientOrderId());
        liveSignalRepository.saveAndFlush(reservation);
    }

    @Transactional(readOnly = true)
    public Optional<SpotExecutionAttempt> findOutstandingSell() {
        return attemptRepository
                .findByStrategyContractAndSideOrderByCreatedAtAsc(
                        BtcDraPolicy.POLICY_MODE,
                        Side.SELL)
                .stream()
                .filter(this::isOutstanding)
                .findFirst();
    }

    @Transactional(readOnly = true)
    public Optional<SpotExecutionAttempt> findOutstandingBuy() {
        return attemptRepository
                .findByStrategyContractAndSideOrderByCreatedAtAsc(
                        BtcDraPolicy.POLICY_MODE,
                        Side.BUY)
                .stream()
                .filter(this::isOutstanding)
                .findFirst();
    }

    @Transactional
    public ApplyResult applyBuySnapshot(
            Long attemptId,
            ProviderFillSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SpotExecutionAttempt preview = requireAttempt(attemptId);
        BtLiveSignal lot = liveSignalRepository
                .findByIdForUpdate(preview.getLiveSignalId())
                .orElseThrow(() -> new IllegalStateException(
                        "DRA buy lot not found"));
        SpotExecutionAttempt attempt = requireAttemptForUpdate(
                attemptId);
        requireSameLot(preview, attempt);
        if (attempt.getSide() != Side.BUY) {
            throw new IllegalStateException(
                    "DRA execution attempt is not BUY");
        }
        if (attempt.getProviderOrderId() != null
                && !attempt.getProviderOrderId().equals(
                snapshot.providerOrderId())) {
            throw new IllegalStateException(
                    "provider order id changed for execution attempt");
        }
        requireDraRow(lot);

        BigDecimal cumulativeGross = nonNegative(
                snapshot.cumulativeGrossQuantity(),
                "cumulativeGrossQuantity");
        BigDecimal averagePrice = snapshot.averagePrice();
        if (cumulativeGross.signum() > 0) {
            requirePositive(averagePrice, "averagePrice");
        }
        BigDecimal grossQuote = cumulativeGross.signum() == 0
                ? BigDecimal.ZERO
                : averagePrice.multiply(cumulativeGross);
        BigDecimal feeUsdt = snapshot.feeUsdt() == null
                ? BigDecimal.ZERO
                : nonNegative(snapshot.feeUsdt(), "feeUsdt");
        BigDecimal netQuantity = snapshot.netQuantity() == null
                ? cumulativeGross
                : nonNegative(snapshot.netQuantity(), "netQuantity");
        BigDecimal fillDelta = cumulativeGross.subtract(
                zero(attempt.getAppliedFillQuantity()));
        if (fillDelta.signum() < 0) {
            throw new IllegalArgumentException(
                    "provider cumulative buy fill moved backwards");
        }
        BigDecimal grossQuoteDelta = grossQuote.subtract(
                zero(attempt.getAppliedGrossQuoteAmount()));
        BigDecimal feeDelta = feeUsdt.subtract(
                zero(attempt.getAppliedFeeUsdt()));
        State resultingState = buyProviderState(
                snapshot.providerState(),
                cumulativeGross);

        attempt.setProviderOrderId(snapshot.providerOrderId());
        attempt.setProviderAcceptedAt(
                attempt.getProviderAcceptedAt() == null
                        ? snapshot.providerAt()
                        : attempt.getProviderAcceptedAt());
        attempt.setAveragePrice(averagePrice);
        attempt.setGrossFillQuantity(cumulativeGross);
        attempt.setNetFillQuantity(netQuantity);
        attempt.setGrossQuoteAmount(grossQuote);
        attempt.setSignedFeeAmount(snapshot.signedFeeAmount());
        attempt.setFeeCurrency(snapshot.feeCurrency());
        attempt.setFeeUsdt(snapshot.feeUsdt());
        attempt.setFeeReconciliationStatus(
                snapshot.feeCurrency() == null
                        || snapshot.feeCurrency().isBlank()
                        ? FeeReconciliationStatus.PENDING
                        : FeeReconciliationStatus.RECONCILED);
        attempt.setAppliedFillQuantity(cumulativeGross);
        attempt.setAppliedGrossQuoteAmount(grossQuote);
        attempt.setAppliedFeeUsdt(feeUsdt);
        attempt.setRemainingLotQuantity(netQuantity);
        attempt.setProviderReceiptJson(snapshot.providerReceiptJson());
        attempt.setLastReconciliationError(null);
        attempt.setState(resultingState);
        if (resultingState == State.REJECTED
                && cumulativeGross.signum() == 0) {
            attempt.setFeeReconciliationStatus(
                    FeeReconciliationStatus.NOT_APPLICABLE);
        }
        if (isTerminal(resultingState)) {
            attempt.setReconciledAt(
                    LocalDateTime.now(ZoneOffset.UTC));
        }
        attemptRepository.saveAndFlush(attempt);

        BigDecimal previousRealizedPnl =
                zero(lot.getRealizedPnl());
        if ((resultingState == State.RECONCILED_FILLED
                || resultingState == State.RECONCILED_PARTIAL)
                && netQuantity.signum() > 0) {
            BigDecimal effectiveEntry =
                    SpotExecutionAttemptPolicy.effectiveBuyEntryPrice(
                            averagePrice,
                            cumulativeGross,
                            netQuantity,
                            feeUsdt,
                            snapshot.feeCurrency());
            lot.setEntryPrice(effectiveEntry);
            lot.setSuggestedTp(requiredExitPrice(effectiveEntry));
            lot.setActualEntryPrice(averagePrice);
            lot.setTradedQty(netQuantity);
            lot.setOcoQty(netQuantity);
            lot.setAutoTraded(true);
            lot.setExchangeOrderId(
                    "OKX:" + snapshot.providerOrderId());
            lot.setFilterReason(
                    BtcDraLiveExecutionService.POSITION_PREFIX
                            + "OPEN:CL="
                            + attempt.getClientOrderId()
                            + ":ORDER="
                            + snapshot.providerOrderId());
        } else if (resultingState == State.REJECTED) {
            lot.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
            lot.setExitReason("DRA_BUY_REJECTED");
            lot.setFilterReason(
                    BtcDraLiveExecutionService.POSITION_PREFIX
                            + "BUY_REJECTED:CL="
                            + attempt.getClientOrderId());
        }
        liveSignalRepository.saveAndFlush(lot);
        return new ApplyResult(
                attempt.getId(),
                resultingState,
                fillDelta,
                grossQuoteDelta,
                feeDelta,
                zero(lot.getRealizedPnl()).subtract(
                        previousRealizedPnl),
                netQuantity,
                attempt.getFeeReconciliationStatus());
    }

    @Transactional
    public ApplyResult applySellSnapshot(
            Long attemptId,
            ProviderFillSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        SpotExecutionAttempt preview = requireAttempt(attemptId);
        BtLiveSignal lot = liveSignalRepository
                .findByIdForUpdate(preview.getLiveSignalId())
                .orElseThrow(() -> new IllegalStateException(
                        "DRA lot not found"));
        SpotExecutionAttempt attempt = requireAttemptForUpdate(
                attemptId);
        requireSameLot(preview, attempt);
        if (attempt.getSide() != Side.SELL) {
            throw new IllegalStateException(
                    "DRA execution attempt is not SELL");
        }
        if (attempt.getProviderOrderId() != null
                && !attempt.getProviderOrderId().equals(
                snapshot.providerOrderId())) {
            throw new IllegalStateException(
                    "provider order id changed for execution attempt");
        }

        BigDecimal cumulativeFilled = nonNegative(
                snapshot.cumulativeGrossQuantity(),
                "cumulativeGrossQuantity");
        BigDecimal averagePrice = snapshot.averagePrice();
        if (cumulativeFilled.signum() > 0) {
            requirePositive(averagePrice, "averagePrice");
        }
        BigDecimal cumulativeGrossQuote =
                cumulativeFilled.signum() == 0
                        ? BigDecimal.ZERO
                        : averagePrice.multiply(cumulativeFilled);
        BigDecimal cumulativeFeeUsdt =
                snapshot.feeUsdt() == null
                        ? BigDecimal.ZERO
                        : nonNegative(snapshot.feeUsdt(), "feeUsdt");
        BigDecimal remainingBeforeApply =
                lot.getExitTime() == null
                        ? nonNegative(lot.getTradedQty(), "lot.tradedQty")
                        : BigDecimal.ZERO;

        SpotExecutionAttemptPolicy.ReconciliationDelta delta =
                SpotExecutionAttemptPolicy.reconciliationDelta(
                        cumulativeFilled,
                        zero(attempt.getAppliedFillQuantity()),
                        remainingBeforeApply,
                        cumulativeGrossQuote,
                        zero(attempt.getAppliedGrossQuoteAmount()),
                        cumulativeFeeUsdt,
                        zero(attempt.getAppliedFeeUsdt()));

        BigDecimal entryPrice = lot.getEntryPrice();
        requirePositive(entryPrice, "lot.entryPrice");
        BigDecimal realizedDelta = delta.grossQuoteAmount()
                .subtract(entryPrice.multiply(delta.fillQuantity()))
                .subtract(delta.feeUsdt())
                .setScale(8, RoundingMode.HALF_UP);
        lot.setRealizedPnl(
                zero(lot.getRealizedPnl()).add(realizedDelta));

        BigDecimal remaining = remainingBeforeApply
                .subtract(delta.fillQuantity())
                .max(BigDecimal.ZERO);
        attempt.setProviderOrderId(snapshot.providerOrderId());
        attempt.setProviderAcceptedAt(
                attempt.getProviderAcceptedAt() == null
                        ? snapshot.providerAt()
                        : attempt.getProviderAcceptedAt());
        attempt.setAveragePrice(averagePrice);
        attempt.setGrossFillQuantity(cumulativeFilled);
        attempt.setNetFillQuantity(snapshot.netQuantity());
        attempt.setGrossQuoteAmount(cumulativeGrossQuote);
        attempt.setSignedFeeAmount(snapshot.signedFeeAmount());
        attempt.setFeeCurrency(snapshot.feeCurrency());
        attempt.setFeeUsdt(snapshot.feeUsdt());
        attempt.setFeeReconciliationStatus(
                snapshot.feeCurrency() == null
                        || snapshot.feeCurrency().isBlank()
                        ? FeeReconciliationStatus.PENDING
                        : FeeReconciliationStatus.RECONCILED);
        attempt.setAppliedFillQuantity(cumulativeFilled);
        attempt.setAppliedGrossQuoteAmount(cumulativeGrossQuote);
        attempt.setAppliedFeeUsdt(cumulativeFeeUsdt);
        attempt.setRemainingLotQuantity(remaining);
        attempt.setProviderReceiptJson(snapshot.providerReceiptJson());
        attempt.setLastReconciliationError(null);

        State resultingState = providerState(
                snapshot.providerState(),
                cumulativeFilled,
                remaining);
        attempt.setState(resultingState);
        if (resultingState == State.REJECTED
                && cumulativeFilled.signum() == 0) {
            attempt.setFeeReconciliationStatus(
                    FeeReconciliationStatus.NOT_APPLICABLE);
        }
        if (isTerminal(resultingState)) {
            attempt.setReconciledAt(
                    LocalDateTime.now(ZoneOffset.UTC));
        }
        attemptRepository.saveAndFlush(attempt);

        if (resultingState == State.RECONCILED_FILLED) {
            lot.setExitPrice(weightedExitPrice(lot.getId()));
            if (lot.getExitTime() == null) {
                lot.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
                lot.setExitReason("DRA_AUTO_NET_PROFIT");
                lot.setFilterReason(
                        BtcDraLiveExecutionService.POSITION_PREFIX
                                + "CLOSED:CL="
                                + attempt.getClientOrderId()
                                + ":ORDER="
                                + snapshot.providerOrderId());
            }
        } else if (delta.fillQuantity().signum() > 0) {
            lot.setTradedQty(remaining);
            lot.setOcoQty(remaining);
            lot.setFilterReason(BtcDraLiveExecutionService.POSITION_PREFIX
                    + "OPEN_PARTIAL:CL=" + attempt.getClientOrderId()
                    + ":ORDER=" + snapshot.providerOrderId());
        }
        liveSignalRepository.saveAndFlush(lot);
        return new ApplyResult(
                attempt.getId(),
                resultingState,
                delta.fillQuantity(),
                delta.grossQuoteAmount(),
                delta.feeUsdt(),
                realizedDelta,
                remaining,
                attempt.getFeeReconciliationStatus());
    }

    private BtLiveSignal requireOwnedOpenLot(Long liveSignalId) {
        BtLiveSignal lot = liveSignalRepository
                .findByIdForUpdate(liveSignalId)
                .orElseThrow(() -> new IllegalStateException(
                        "DRA lot not found"));
        requireDraRow(lot);
        if (!Boolean.TRUE.equals(lot.getAutoTraded())
                || lot.getExitTime() != null
                || lot.getFilterReason() == null) {
            throw new IllegalStateException(
                    "row is not an open DRA-owned lot");
        }
        requirePositive(lot.getTradedQty(), "lot.tradedQty");
        return lot;
    }

    private void requireDraRow(BtLiveSignal lot) {
        if (lot.getStrategyId() == null
                || lot.getStrategyId()
                != BtcDraPolicy.RUNTIME_LEDGER_STRATEGY_ID
                || lot.getFilterReason() == null
                || !lot.getFilterReason().startsWith(
                BtcDraLiveExecutionService.POSITION_PREFIX)) {
            throw new IllegalStateException(
                    "row is not DRA-owned");
        }
    }

    private SpotExecutionAttempt requireAttemptForUpdate(
            Long attemptId) {
        return attemptRepository.findByIdForUpdate(attemptId)
                .orElseThrow(() -> new IllegalStateException(
                "execution attempt not found"));
    }

    private SpotExecutionAttempt requireAttempt(Long attemptId) {
        return attemptRepository.findById(attemptId)
                .orElseThrow(() -> new IllegalStateException(
                        "execution attempt not found"));
    }

    private void requireSameLot(
            SpotExecutionAttempt preview,
            SpotExecutionAttempt locked) {
        if (!Objects.equals(
                preview.getLiveSignalId(),
                locked.getLiveSignalId())) {
            throw new IllegalStateException(
                    "execution attempt lot changed");
        }
    }

    private boolean isOutstanding(SpotExecutionAttempt attempt) {
        return switch (attempt.getState()) {
            case RESERVED,
                    SUBMITTING,
                    SUBMISSION_UNKNOWN,
                    PROVIDER_ACCEPTED -> true;
            case RECONCILED_FILLED,
                    RECONCILED_PARTIAL,
                    REJECTED ->
                    attempt.getFeeReconciliationStatus()
                            == FeeReconciliationStatus.PENDING
                            && attempt.getProviderOrderId() != null;
        };
    }

    private boolean isTerminal(State state) {
        return state == State.RECONCILED_FILLED
                || state == State.RECONCILED_PARTIAL
                || state == State.REJECTED;
    }

    private State providerState(
            String providerState,
            BigDecimal cumulativeFilled,
            BigDecimal remaining) {
        String normalized = providerState == null
                ? ""
                : providerState.toLowerCase();
        return switch (normalized) {
            case "filled" -> remaining.compareTo(DUST_QTY) <= 0
                    ? State.RECONCILED_FILLED
                    : State.RECONCILED_PARTIAL;
            case "canceled", "mmp_canceled" ->
                    cumulativeFilled.signum() > 0
                            ? State.RECONCILED_PARTIAL
                            : State.REJECTED;
            case "live", "partially_filled" ->
                    State.PROVIDER_ACCEPTED;
            default -> throw new IllegalArgumentException(
                    "unsupported provider order state: "
                            + providerState);
        };
    }

    private State buyProviderState(
            String providerState,
            BigDecimal cumulativeFilled) {
        String normalized = providerState == null
                ? ""
                : providerState.toLowerCase();
        return switch (normalized) {
            case "filled" -> State.RECONCILED_FILLED;
            case "canceled", "mmp_canceled" ->
                    cumulativeFilled.signum() > 0
                            ? State.RECONCILED_PARTIAL
                            : State.REJECTED;
            case "live", "partially_filled" ->
                    State.PROVIDER_ACCEPTED;
            default -> throw new IllegalArgumentException(
                    "unsupported provider order state: "
                            + providerState);
        };
    }

    private BigDecimal requiredExitPrice(BigDecimal effectiveEntry) {
        BigDecimal afterFeeAndSlippage = BigDecimal.ONE
                .subtract(BtcDraPolicy.FEE_RATE_PER_SIDE)
                .multiply(BigDecimal.ONE.subtract(
                        BtcDraPolicy.ADVERSE_SLIPPAGE_RATE_PER_SIDE));
        return effectiveEntry
                .multiply(BigDecimal.ONE.add(
                        BtcDraPolicy.NET_PROFIT_TRIGGER))
                .divide(
                        afterFeeAndSlippage,
                        8,
                        RoundingMode.HALF_UP);
    }

    private BigDecimal weightedExitPrice(Long liveSignalId) {
        List<SpotExecutionAttempt> attempts =
                attemptRepository
                        .findByLiveSignalIdAndSideOrderByAttemptSequenceAsc(
                                liveSignalId,
                                Side.SELL);
        BigDecimal quantity = attempts.stream()
                .map(SpotExecutionAttempt::getAppliedFillQuantity)
                .map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal quote = attempts.stream()
                .map(SpotExecutionAttempt::getAppliedGrossQuoteAmount)
                .map(this::zero)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        requirePositive(quantity, "weightedExitQuantity");
        return quote.divide(quantity, 8, RoundingMode.HALF_UP);
    }

    private BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal nonNegative(
            BigDecimal value,
            String field) {
        Objects.requireNonNull(value, field);
        if (value.signum() < 0) {
            throw new IllegalArgumentException(
                    field + " must not be negative");
        }
        return value;
    }

    private void requirePositive(
            BigDecimal value,
            String field) {
        if (value == null || value.signum() <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive");
        }
    }

    private String safe(String value, int max) {
        if (value == null) return "";
        return value.length() <= max
                ? value
                : value.substring(0, max);
    }

    public record Reservation(
            SpotExecutionAttempt attempt,
            boolean created,
            String blocker) {
    }

    public record ProviderFillSnapshot(
            String providerOrderId,
            String providerState,
            BigDecimal averagePrice,
            BigDecimal cumulativeGrossQuantity,
            BigDecimal netQuantity,
            BigDecimal signedFeeAmount,
            String feeCurrency,
            BigDecimal feeUsdt,
            String providerReceiptJson,
            LocalDateTime providerAt) {
    }

    public record ApplyResult(
            Long attemptId,
            State state,
            BigDecimal appliedFillQuantity,
            BigDecimal appliedGrossQuoteAmount,
            BigDecimal appliedFeeUsdt,
            BigDecimal realizedPnlDelta,
            BigDecimal remainingLotQuantity,
            FeeReconciliationStatus feeStatus) {
    }
}
