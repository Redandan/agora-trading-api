package com.agora.service.trading;

import com.agora.model.BtFundingArb;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtFundingArbRepository;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * #400 — aggregates symbol-level reserved/open quantities across grid levels,
 * live-signal positions, and funding-arb spot legs. Used by AdminOcoController
 * to validate manual market-sell qty against committed exposure before placing
 * an order, so a typo can't accidentally dump locked BTC.
 *
 * <p>Lifted out of AdminOcoController in plan §2 — controllers must not depend
 * on repositories directly (skips @Transactional boundary).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SymbolExposureService {

    /** Grid level statuses that hold real BTC and must NOT be sold by orphan cleanup. */
    private static final List<String> GRID_RESERVED_STATUSES = List.of(
            "HOLDING", "PENDING_OKX", "SELLING_OKX", "SELL_FAILED", "SELL_PARTIAL");
    /** FundingArb statuses where the spot leg is still on the OKX account. */
    private static final List<String> FUNDING_ARB_OPEN_STATUSES = List.of(
            "OPEN", "OPENING", "CLOSING");

    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtFundingArbRepository fundingArbRepository;

    /**
     * Sum of {@code filled_qty} across all active grid levels (status in
     * {@link #GRID_RESERVED_STATUSES}) for the given symbol. SELL_PARTIAL stores
     * the leftover qty in {@code filled_qty} (#399) so the same SUM gives the
     * correct number for both HOLDING and partial-leftover cases.
     */
    public BigDecimal sumGridReservedQty(String symbol) {
        BigDecimal sum = BigDecimal.ZERO;
        for (var grid : gridRepository.findAll()) {
            if (grid.getClosedAt() != null) continue;
            if (!symbol.equalsIgnoreCase(grid.getSymbol())) continue;
            List<BtGridLevel> levels = gridLevelRepository
                    .findByGridIdAndStatusIn(grid.getId(), GRID_RESERVED_STATUSES);
            for (BtGridLevel lv : levels) {
                if (lv.getFilledQty() != null) {
                    sum = sum.add(lv.getFilledQty());
                }
            }
        }
        return sum;
    }

    /**
     * Sum of {@code traded_qty} across open auto-traded BtLiveSignal positions for symbol.
     *
     * <p>#403 — Positions with non-null {@code oco_order_list_id} have their BTC
     * locked inside an OKX OCO bracket; OKX's {@code availBal} already excludes
     * that quantity. Adding them to {@code reserved} would double-count, making
     * {@code sellable = availBal - reserved} go negative whenever any active OCO
     * exists. Treat OCO-bracketed positions as "outside availBal" and skip them.
     */
    public BigDecimal sumLiveSignalOpenQty(String symbol) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BtLiveSignal pos : liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()) {
            if (!symbol.equalsIgnoreCase(pos.getSymbol())) continue;
            if (pos.getOcoOrderListId() != null) continue;
            if (pos.getTradedQty() != null) {
                sum = sum.add(pos.getTradedQty());
            }
        }
        return sum;
    }

    /** Sum of {@code spot_qty} across FundingArb positions whose spot leg is still on OKX. */
    public BigDecimal sumFundingArbSpotQty(String symbol) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BtFundingArb arb : fundingArbRepository.findByStatusIn(FUNDING_ARB_OPEN_STATUSES)) {
            if (!symbol.equalsIgnoreCase(arb.getSymbol())) continue;
            if (arb.getSpotQty() != null) {
                sum = sum.add(arb.getSpotQty());
            }
        }
        return sum;
    }
}
