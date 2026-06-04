package com.agora.dto.analytics;

import java.math.BigDecimal;

/**
 * SlotRound 流量聚合查詢的 JPA Projection（整體統計）
 */
public interface SlotTrafficStats {
    Long    getTotalRounds();
    Long    getWinRounds();
    Long    getActivePlayers();
    BigDecimal getTotalBet();
    BigDecimal getTotalPayout();
    Integer getMaxMultiplier();
}
