package com.agora.dto.analytics;

import java.math.BigDecimal;

/**
 * Slot 每小時聚合查詢的 JPA Projection
 */
public interface SlotHourlyStats {
    Integer    getHour();
    Long       getTotalRounds();
    Long       getActivePlayers();
    BigDecimal getTotalBet();
}
