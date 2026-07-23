package com.agora.service.trading;

import com.agora.model.BtFundingArb;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtFundingArbRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * #400 — aggregates symbol-level reserved/open quantities across
 * live-signal positions and funding-arb spot legs. Used by AdminOcoController
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

    /** FundingArb statuses where the spot leg is still on the OKX account. */
    private static final List<String> FUNDING_ARB_OPEN_STATUSES = List.of(
            "OPEN", "OPENING", "CLOSING");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtFundingArbRepository fundingArbRepository;

    /** Compatibility surface after removal of the executable custom Grid runtime. */
    public BigDecimal sumGridReservedQty(String symbol) {
        return BigDecimal.ZERO;
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
