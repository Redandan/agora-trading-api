package com.agora.dto.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Slot 每日聚合查詢的 JPA Projection
 */
public interface SlotDailyStats {
    LocalDate  getStatDate();
    Long       getTotalRounds();
    Long       getActivePlayers();
    BigDecimal getTotalBet();
    BigDecimal getTotalPayout();
}
