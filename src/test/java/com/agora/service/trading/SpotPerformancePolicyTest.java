package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpotPerformancePolicyTest {
    @Test void acceptsOnlyCurrentConfirmedOkxHourlyBar() {
        MdKline bar = bar(); LocalDateTime close = bar.getCloseTime();
        assertTrue(SpotPerformancePolicy.freshBar(bar, close.plusSeconds(30)));
        assertFalse(SpotPerformancePolicy.freshBar(bar, close.minusSeconds(1)));
        assertFalse(SpotPerformancePolicy.freshBar(bar, close.plusMinutes(3)));
        bar.setSource("binance"); assertFalse(SpotPerformancePolicy.freshBar(bar, close));
    }
    @Test void partialRealizedAndOpenInventoryBothContributeWithoutAdoptingLegacyLots() {
        BtLiveSignal open = lot("DRA_V1", "0.1", "100", "2");
        BtLiveSignal legacy = lot("LEGACY", "999", "1", "1000");
        BtLiveSignal closed = lot("DRA_V1", "0.2", "10", "3");
        closed.setExitTime(bar().getCloseTime());
        var result = SpotPerformancePolicy.snapshot("DRA_V1", List.of(open, closed, legacy),
                new BigDecimal("30"), bar(), bar().getCloseTime());
        assertEquals("OBSERVED", result.get("status"));
        assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) result.get("totalBeforeExitCostsUsdt")));
        assertEquals(0, new BigDecimal("36").compareTo((BigDecimal) result.get("referenceEquityUsdt")));
        assertEquals(1, result.get("openLots"));
    }
    @Test void unresolvedOrMissingCostNeverPublishesPartialEquityAsComplete() {
        BtLiveSignal row = lot("DRA_V1", "1", "100", "0"); row.setEntryPrice(null);
        var result = SpotPerformancePolicy.snapshot("DRA_V1", List.of(row), new BigDecimal("30"), bar(), bar().getCloseTime());
        assertEquals("MISSING_PROOF", result.get("status"));
        assertFalse(result.containsKey("referenceEquityUsdt"));
        row.setAutoTraded(false);
        assertEquals(1, SpotPerformancePolicy.snapshot("DRA_V1", List.of(row), new BigDecimal("30"),
                bar(), bar().getCloseTime()).get("unresolvedReservations"));
    }
    static MdKline bar() {
        var bar = new MdKline(); bar.setSymbol("BTCUSDT"); bar.setSource("okx"); bar.setIntervalCode("1h");
        bar.setOpenTime(LocalDateTime.of(2026, 10, 2, 11, 0)); bar.setCloseTime(bar.getOpenTime().plusHours(1));
        bar.setClosePrice(new BigDecimal("110")); return bar;
    }
    private BtLiveSignal lot(String owner, String qty, String price, String pnl) {
        var row = new BtLiveSignal(); row.setSymbol("BTCUSDT"); row.setSide("LONG"); row.setAutoTraded(true);
        row.setFilterReason("DRA_V1".equals(owner) ? BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "OPEN"
                : BtcBasePositionStatePolicy.ADOPTED_FROM_OCO_PREFIX + "1");
        row.setTradedQty(new BigDecimal(qty)); row.setEntryPrice(new BigDecimal(price)); row.setRealizedPnl(new BigDecimal(pnl));
        row.setCreatedAt(bar().getOpenTime()); return row;
    }
}
