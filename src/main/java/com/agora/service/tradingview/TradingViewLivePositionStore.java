package com.agora.service.tradingview;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.SpotExecutionAttemptPolicy;
import com.agora.service.trading.SpotFillReceiptEvidence;
import com.agora.service.trading.TradeResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Atomic position bookkeeping only. No exchange calls, notifications or strategy decisions. */
@Service
@RequiredArgsConstructor
public class TradingViewLivePositionStore {
    private static final String PREFIX = BtcBasePositionStatePolicy.TV509_POSITION_PREFIX;
    private final BtLiveSignalRepository positions;
    private final BtStrategyRepository strategies;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reserveSell(Long strategyId, List<BtLiveSignal> expected, String clientId, BigDecimal requested) {
        lockStrategy(strategyId);
        if (positions.findByStrategyIdAndSymbol(strategyId, "BTCUSDT").stream()
                .anyMatch(lot -> clientId.equals(clientId(lot)))) {
            throw new IllegalStateException("TV509_SELL_BAR_ALREADY_RESERVED");
        }
        List<BtLiveSignal> locked = new ArrayList<>();
        for (BtLiveSignal before : expected.stream().sorted(Comparator.comparing(BtLiveSignal::getId)).toList()) {
            BtLiveSignal lot = positions.findByIdForUpdate(before.getId()).orElseThrow();
            requireOwner(lot, strategyId);
            if (!open(lot) || lot.getTradedQty().compareTo(before.getTradedQty()) != 0
                    || lot.getEntryPrice().compareTo(before.getEntryPrice()) != 0) {
                throw new IllegalStateException("TV509_LOT_CHANGED_BEFORE_RESERVATION");
            }
            locked.add(lot);
        }
        if (locked.isEmpty() || requested == null || requested.signum() <= 0
                || requested.compareTo(locked.stream().map(BtLiveSignal::getTradedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add)) > 0) {
            throw new IllegalArgumentException("TV509_INVALID_SELL_RESERVATION");
        }
        String marker = PREFIX + "SELL_RESERVED:V=2:CL=" + clientId
                + ":N=" + locked.size() + ":Q=" + requested.toPlainString();
        for (BtLiveSignal lot : locked) lot.setFilterReason(marker);
        positions.saveAllAndFlush(locked);
    }

    /** All rows transition together. A replay observes completed markers and applies nothing. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Applied applySell(Long strategyId, String clientId, TradeResult fill, LocalDateTime filledAt) {
        lockStrategy(strategyId);
        List<BtLiveSignal> group = positions.findByStrategyIdAndSymbol(strategyId, "BTCUSDT").stream()
                .filter(lot -> clientId.equals(clientId(lot)))
                .sorted(Comparator.comparing(BtLiveSignal::getId))
                .map(lot -> positions.findByIdForUpdate(lot.getId()).orElseThrow()).toList();
        if (group.isEmpty()) throw new IllegalStateException("TV509_SELL_RESERVATION_MISSING");
        if (group.stream().noneMatch(TradingViewLivePositionStore::pendingV2)) return Applied.empty();
        if (group.stream().anyMatch(lot -> !pendingV2(lot))) {
            throw new IllegalStateException("TV509_MIXED_SELL_RESERVATION");
        }
        String marker = group.get(0).getFilterReason();
        if (Integer.parseInt(field(marker, "N")) != group.size()
                || group.stream().anyMatch(lot -> !marker.equals(lot.getFilterReason()))) {
            throw new IllegalStateException("TV509_INCOMPLETE_SELL_GROUP");
        }
        BigDecimal sold = fill.getGrossQty();
        BigDecimal requested = new BigDecimal(field(marker, "Q"));
        if (sold == null || sold.signum() < 0 || sold.compareTo(requested) > 0) {
            throw new IllegalArgumentException("TV509_SELL_FILL_EXCEEDS_RESERVATION");
        }
        for (BtLiveSignal lot : group) {
            requireOwner(lot, strategyId);
            if (lot.getExitTime() != null || !Boolean.TRUE.equals(lot.getAutoTraded())
                    || lot.getTradedQty() == null || lot.getTradedQty().signum() <= 0
                    || lot.getEntryPrice() == null || lot.getEntryPrice().signum() <= 0) {
                throw new IllegalStateException("TV509_INVALID_RESERVED_LOT");
            }
        }
        BigDecimal owned = group.stream().map(BtLiveSignal::getTradedQty).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (sold.compareTo(owned) > 0) throw new IllegalArgumentException("TV509_SELL_FILL_EXCEEDS_OWNERSHIP");
        if (sold.signum() > 0) requireFeeExact("SELL", fill);
        BigDecimal remaining = sold;
        BigDecimal pnlTotal = BigDecimal.ZERO;
        List<Allocation> allocations = new ArrayList<>();
        for (BtLiveSignal lot : group) {
            BigDecimal allocated = lot.getTradedQty().min(remaining);
            if (allocated.signum() == 0) {
                lot.setFilterReason(PREFIX + "OPEN:CL=" + clientId + ":ORDER=" + fill.getOrderId());
                continue;
            }
            var flow = SpotFillReceiptEvidence.calculate(fill.getOrderId(), "SELL", fill.getAvgPrice(),
                    sold, fill.getQty(), fill.getFeeAmount(), fill.getFeeCurrency(), fill.getFeeUsdt(), allocated);
            BigDecimal pnl = flow.cashUsdt().subtract(lot.getEntryPrice().multiply(allocated))
                    .setScale(8, RoundingMode.HALF_UP);
            lot.setRealizedPnl((lot.getRealizedPnl() == null ? BigDecimal.ZERO : lot.getRealizedPnl()).add(pnl));
            BigDecimal residual = lot.getTradedQty().subtract(allocated);
            remaining = remaining.subtract(allocated);
            pnlTotal = pnlTotal.add(pnl);
            if (residual.signum() == 0) {
                lot.setExitTime(filledAt);
                lot.setExitPrice(fill.getAvgPrice());
                lot.setExitReason("TV509_AUTO_NET_PROFIT");
                lot.setFilterReason(PREFIX + "CLOSED:CL=" + clientId + ":ORDER=" + fill.getOrderId());
            } else {
                lot.setTradedQty(residual);
                lot.setOcoQty(residual);
                lot.setFilterReason(PREFIX + "OPEN_PARTIAL:CL=" + clientId + ":ORDER=" + fill.getOrderId());
            }
            allocations.add(new Allocation(lot.getId(), allocated));
        }
        if (remaining.signum() != 0) throw new IllegalStateException("TV509_UNALLOCATED_SELL_FILL");
        positions.saveAllAndFlush(group);
        return new Applied(pnlTotal, allocations);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Applied applyBuy(Long strategyId, Long lotId, String clientId, TradeResult fill, LocalDateTime filledAt) {
        lockStrategy(strategyId);
        BtLiveSignal lot = positions.findByIdForUpdate(lotId).orElseThrow();
        requireOwner(lot, strategyId);
        if (!clientId.equals(clientId(lot))) throw new IllegalStateException("TV509_BUY_CLIENT_ID_MISMATCH");
        if (!pendingV2(lot)) return Applied.empty();
        if (!lot.getFilterReason().startsWith(PREFIX + "BUY_RESERVED:")) {
            throw new IllegalStateException("TV509_NOT_BUY_RESERVATION");
        }
        if (fill.getGrossQty().signum() == 0) {
            lot.setExitTime(filledAt);
            lot.setExitReason("TV509_BUY_CANCELED_UNFILLED");
            lot.setFilterReason(PREFIX + "BUY_REJECTED:CL=" + clientId + ":ORDER=" + fill.getOrderId());
            positions.saveAndFlush(lot);
            return Applied.empty();
        }
        requireFeeExact("BUY", fill);
        // The existing position columns store 8 decimals. Never let DB rounding borrow another owner's BTC.
        BigDecimal ownedQty = fill.getQty().setScale(8, RoundingMode.DOWN);
        if (ownedQty.signum() <= 0) throw new IllegalArgumentException("TV509_NET_QUANTITY_BELOW_STORAGE_PRECISION");
        BigDecimal effective = SpotExecutionAttemptPolicy.effectiveBuyEntryPrice(fill.getAvgPrice(),
                fill.getGrossQty(), ownedQty, fill.getFeeUsdt(), fill.getFeeCurrency());
        lot.setEntryPrice(effective);
        lot.setActualEntryPrice(fill.getAvgPrice());
        lot.setSuggestedTp(effective.multiply(BigDecimal.ONE.add(TradingViewScoreBuyAutoExitStrategyContract.NET_PROFIT_TRIGGER))
                .divide(new BigDecimal("0.999"), 8, RoundingMode.HALF_UP));
        lot.setTradedQty(ownedQty); lot.setOcoQty(ownedQty); lot.setAutoTraded(true);
        lot.setExchangeOrderId("OKX:" + fill.getOrderId());
        lot.setFilterReason(PREFIX + "OPEN:CL=" + clientId + ":ORDER=" + fill.getOrderId());
        positions.saveAndFlush(lot);
        return new Applied(BigDecimal.ZERO, List.of(new Allocation(lotId, fill.getQty())));
    }

    public static void requireFeeExact(String side, TradeResult fill) {
        SpotFillReceiptEvidence.calculate(fill.getOrderId(), side, fill.getAvgPrice(), fill.getGrossQty(),
                fill.getQty(), fill.getFeeAmount(), fill.getFeeCurrency(), fill.getFeeUsdt(),
                "BUY".equals(side) ? fill.getQty() : fill.getGrossQty());
    }

    public static boolean pendingV2(BtLiveSignal lot) {
        return BtcBasePositionStatePolicy.isTv509Position(lot) && lot.getExitTime() == null
                && (lot.getFilterReason().startsWith(PREFIX + "BUY_RESERVED:V=2:")
                || lot.getFilterReason().startsWith(PREFIX + "SELL_RESERVED:V=2:"));
    }

    public static boolean open(BtLiveSignal lot) {
        return BtcBasePositionStatePolicy.isTv509Position(lot) && lot.getExitTime() == null
                && Boolean.TRUE.equals(lot.getAutoTraded())
                && (lot.getFilterReason().startsWith(PREFIX + "OPEN:")
                || lot.getFilterReason().startsWith(PREFIX + "OPEN_PARTIAL:"));
    }

    public static String clientId(BtLiveSignal lot) {
        return BtcBasePositionStatePolicy.isTv509Position(lot) ? field(lot.getFilterReason(), "CL") : null;
    }

    private static String field(String marker, String name) {
        if (marker == null) return null;
        for (String segment : marker.split(":")) {
            if (segment.startsWith(name + "=")) return segment.substring(name.length() + 1);
        }
        return null;
    }

    private void lockStrategy(Long id) {
        if (id == null || id != TradingViewScoreBuyAutoExitStrategyContract.CURRENT_DATABASE_STRATEGY_ID)
            throw new IllegalArgumentException("TV509_STRATEGY_SCOPE_MISMATCH");
        strategies.findByIdForBootstrapReservation(id).orElseThrow();
    }

    private void requireOwner(BtLiveSignal lot, Long strategyId) {
        if (!BtcBasePositionStatePolicy.isTv509Position(lot) || !strategyId.equals(lot.getStrategyId())
                || !"BTCUSDT".equals(lot.getSymbol()) || !"1d".equals(lot.getIntervalCode())
                || !"LONG".equals(lot.getSide()) || lot.getOcoOrderListId() != null) {
            throw new IllegalArgumentException("TV509_POSITION_SCOPE_MISMATCH");
        }
    }

    public record Allocation(Long lotId, BigDecimal quantity) { }
    public record Applied(BigDecimal realizedPnl, List<Allocation> allocations) {
        static Applied empty() { return new Applied(BigDecimal.ZERO, List.of()); }
    }
}
