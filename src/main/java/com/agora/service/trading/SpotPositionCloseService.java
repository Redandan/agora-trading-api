package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared fail-closed spot close flow used by scoped time-exit policies. */
@Service
@RequiredArgsConstructor
@Slf4j
public class SpotPositionCloseService {

    private static final BigDecimal PARTIAL_TOLERANCE = new BigDecimal("0.01");
    private static final BigDecimal DUST_QTY = new BigDecimal("0.00000001");

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final Set<Long> closingPositionIds = ConcurrentHashMap.newKeySet();

    public boolean isClosing(Long positionId) {
        return positionId != null && closingPositionIds.contains(positionId);
    }

    @Transactional
    public CloseResult closeAtMarket(Long positionId, String exitReason) {
        if (positionId == null || !closingPositionIds.add(positionId)) {
            return CloseResult.busy(positionId);
        }
        try {
            BtLiveSignal position = liveSignalRepository.findByIdForUpdate(positionId).orElse(null);
            if (position == null) return CloseResult.failed(positionId, "POSITION_NOT_FOUND");
            if (position.getExitTime() != null) return CloseResult.alreadyClosed(position);
            if (!Boolean.TRUE.equals(position.getAutoTraded()) || "SHORT".equalsIgnoreCase(position.getSide())) {
                return CloseResult.failed(positionId, "NOT_OPEN_AUTO_TRADED_SPOT_LONG");
            }

            OcoCancelResult cancel = cancelOcoFailClosed(position);
            if (cancel == OcoCancelResult.ALREADY_FILLED) {
                return CloseResult.waitingForOcoReconciliation(position);
            }
            if (cancel == OcoCancelResult.FAILED) {
                return CloseResult.failed(positionId, "OCO_CANCEL_NOT_CONFIRMED");
            }

            // The poller may have reconciled a fill between our initial load and cancel.
            position = liveSignalRepository.findById(positionId).orElse(null);
            if (position == null) return CloseResult.failed(positionId, "POSITION_DISAPPEARED_AFTER_OCO_CANCEL");
            if (position.getExitTime() != null) return CloseResult.alreadyClosed(position);

            BigDecimal trackedQty = positive(position.getTradedQty())
                    ? position.getTradedQty() : position.getOcoQty();
            if (!positive(trackedQty)) return CloseResult.failed(positionId, "TRACKED_QTY_MISSING");
            BigDecimal available = availableBase(position.getSymbol());
            if (!positive(available)) {
                return failAfterReprotect(position, trackedQty,
                        "AVAILABLE_BASE_QTY_ZERO_AFTER_OCO_CANCEL");
            }
            BigDecimal requestedQty = trackedQty.min(available);
            TradeResult sell;
            try {
                sell = okxTradingService.placeMarketSellWithFill(position.getSymbol(), requestedQty);
            } catch (Exception e) {
                return failAfterReprotect(position, trackedQty,
                        "MARKET_SELL_FAILED:" + truncate(e.getMessage(), 180));
            }

            BigDecimal soldQty = positive(sell.getGrossQty()) ? sell.getGrossQty() : sell.getQty();
            if (!positive(soldQty) || !positive(sell.getAvgPrice())) {
                return failAfterReprotect(position, trackedQty, "MARKET_SELL_FILL_INVALID");
            }
            BigDecimal remaining = trackedQty.subtract(soldQty).max(BigDecimal.ZERO);
            BigDecimal tolerance = trackedQty.multiply(PARTIAL_TOLERANCE).max(DUST_QTY);
            BigDecimal entry = positive(position.getActualEntryPrice())
                    ? position.getActualEntryPrice() : position.getEntryPrice();
            BigDecimal grossPnl = positive(entry)
                    ? sell.getAvgPrice().subtract(entry).multiply(soldQty).setScale(8, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            if (remaining.compareTo(tolerance) > 0) {
                position.setTradedQty(remaining);
                position.setOcoQty(remaining);
                position.setOcoOrderListId(null);
                liveSignalRepository.save(position);
                Long replacement = reattachOco(position, remaining);
                log.warn("[SpotClose] partial market sell position={} requested={} sold={} remaining={} replacementOco={}",
                        positionId, requestedQty, soldQty, remaining, replacement);
                return CloseResult.partial(position, sell, requestedQty, soldQty, remaining, grossPnl, replacement);
            }

            position.setExitPrice(sell.getAvgPrice());
            position.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
            position.setExitReason(exitReason == null || exitReason.isBlank() ? "TIME_EXIT_24H" : exitReason);
            position.setRealizedPnl(grossPnl);
            position.setOcoOrderListId(null);
            position.setOcoQty(soldQty);
            liveSignalRepository.save(position);
            log.info("[SpotClose] closed position={} reason={} qty={} avgPrice={} grossPnl={}",
                    positionId, position.getExitReason(), soldQty, sell.getAvgPrice(), grossPnl);
            return CloseResult.closed(position, sell, requestedQty, soldQty, grossPnl);
        } finally {
            closingPositionIds.remove(positionId);
        }
    }

    private OcoCancelResult cancelOcoFailClosed(BtLiveSignal position) {
        if (position.getOcoOrderListId() == null) return OcoCancelResult.NOT_PRESENT;
        if (ocoFilled(position)) return OcoCancelResult.ALREADY_FILLED;
        try {
            okxTradingService.cancelOco(position.getSymbol(), position.getOcoOrderListId());
            position.setOcoOrderListId(null);
            liveSignalRepository.save(position);
            return OcoCancelResult.CANCELLED;
        } catch (Exception e) {
            if (ocoFilled(position)) return OcoCancelResult.ALREADY_FILLED;
            log.error("[SpotClose] OCO cancel not confirmed position={} algoId={} error={}",
                    position.getId(), position.getOcoOrderListId(), e.getMessage());
            return OcoCancelResult.FAILED;
        }
    }

    private boolean ocoFilled(BtLiveSignal position) {
        try {
            JsonNode algo = okxTradingService.getAlgoOrder(position.getSymbol(), position.getOcoOrderListId());
            if ("filled".equalsIgnoreCase(algo.path("state").asText())) return true;
            String childOrderId = algo.path("ordIdList").path(0).asText("");
            if (childOrderId.isBlank()) return false;
            JsonNode child = okxTradingService.querySpotOrderDetail(position.getSymbol(), childOrderId);
            return "filled".equalsIgnoreCase(child.path("state").asText());
        } catch (Exception e) {
            log.warn("[SpotClose] OCO state check failed position={} algoId={} error={}",
                    position.getId(), position.getOcoOrderListId(), e.getMessage());
            return false;
        }
    }

    private Long reattachOco(BtLiveSignal position, BigDecimal remainingQty) {
        if (!positive(remainingQty) || !positive(position.getSuggestedTp()) || !positive(position.getSuggestedSl())) {
            return null;
        }
        try {
            Long algoId = okxTradingService.placeOco(position.getSymbol(), remainingQty,
                    position.getSuggestedTp(), position.getSuggestedSl());
            position.setOcoOrderListId(algoId);
            position.setOcoQty(remainingQty);
            liveSignalRepository.save(position);
            return algoId;
        } catch (Exception e) {
            log.error("[SpotClose] failed to reattach OCO after sell failure/partial position={} qty={} error={}",
                    position.getId(), remainingQty, e.getMessage());
            return null;
        }
    }

    private CloseResult failAfterReprotect(BtLiveSignal position,
                                           BigDecimal quantity,
                                           String reason) {
        Long replacement = reattachOco(position, quantity);
        String protection = replacement == null ? "UNPROTECTED" : "REPROTECTED";
        return CloseResult.failed(position.getId(), reason + ":" + protection, replacement);
    }

    private BigDecimal availableBase(String symbol) {
        String base = symbol == null ? "" : symbol.replace("USDT", "");
        return okxTradingService.getFreshSpotHoldings().stream()
                .filter(holding -> base.equalsIgnoreCase(holding.ccy))
                .map(holding -> holding.availBal)
                .findFirst().orElse(BigDecimal.ZERO);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }

    private enum OcoCancelResult {
        NOT_PRESENT,
        CANCELLED,
        ALREADY_FILLED,
        FAILED
    }

    public record CloseResult(Long positionId,
                              String status,
                              String reason,
                              BigDecimal requestedQty,
                              BigDecimal soldQty,
                              BigDecimal remainingQty,
                              BigDecimal exitPrice,
                              BigDecimal grossPnlUsdt,
                              BigDecimal exitFeeUsdt,
                              String exitFeeCurrency,
                              Long replacementOcoId) {
        static CloseResult busy(Long id) {
            return new CloseResult(id, "BUSY", "CLOSE_ALREADY_IN_PROGRESS", null, null,
                    null, null, null, null, null, null);
        }

        static CloseResult failed(Long id, String reason) {
            return new CloseResult(id, "FAILED", reason, null, null,
                    null, null, null, null, null, null);
        }

        static CloseResult failed(Long id, String reason, Long replacementOcoId) {
            return new CloseResult(id, "FAILED", reason, null, null,
                    null, null, null, null, null, replacementOcoId);
        }

        static CloseResult alreadyClosed(BtLiveSignal position) {
            return new CloseResult(position.getId(), "ALREADY_CLOSED", position.getExitReason(), null,
                    position.getTradedQty(), BigDecimal.ZERO, position.getExitPrice(), position.getRealizedPnl(),
                    null, null, null);
        }

        static CloseResult waitingForOcoReconciliation(BtLiveSignal position) {
            return new CloseResult(position.getId(), "OCO_ALREADY_FILLED", "WAIT_OCO_POLLER_RECONCILIATION",
                    null, null, null, null, null, null, null, position.getOcoOrderListId());
        }

        static CloseResult partial(BtLiveSignal position, TradeResult sell, BigDecimal requested,
                                   BigDecimal sold, BigDecimal remaining, BigDecimal grossPnl, Long replacement) {
            return new CloseResult(position.getId(), "PARTIAL", "PARTIAL_FILL_REPROTECTED", requested,
                    sold, remaining, sell.getAvgPrice(), grossPnl, sell.getFeeUsdt(), sell.getFeeCurrency(), replacement);
        }

        static CloseResult closed(BtLiveSignal position, TradeResult sell, BigDecimal requested,
                                  BigDecimal sold, BigDecimal grossPnl) {
            return new CloseResult(position.getId(), "CLOSED", position.getExitReason(), requested,
                    sold, BigDecimal.ZERO, sell.getAvgPrice(), grossPnl,
                    sell.getFeeUsdt(), sell.getFeeCurrency(), null);
        }

        public boolean closedSuccessfully() {
            return "CLOSED".equals(status) || "ALREADY_CLOSED".equals(status);
        }
    }
}
