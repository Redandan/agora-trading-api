package com.agora.repository.trading;

import com.agora.model.PolymarketAlertLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

@Repository
public interface PolymarketAlertLogRepository extends JpaRepository<PolymarketAlertLog, Long> {

    /** Dedup check: did we already alert on this market within the last hour? (single-market) */
    boolean existsByMarketIdAndNotifiedAtAfter(String marketId, LocalDateTime since);

    /** 4h backfill: find alerts fired ~4h ago that still need btc_price_4h_later filled */
    List<PolymarketAlertLog> findByNotifiedAtBetweenAndBtcPrice4hLaterIsNull(
            LocalDateTime from, LocalDateTime to);

    /** Orphan scan: alerts older than cutoff with no BTC label — backfill from kline history */
    List<PolymarketAlertLog> findByBtcPrice4hLaterIsNullAndNotifiedAtBefore(LocalDateTime before);

    /**
     * Batch: most recent alert per market for strength-aware dedup.
     * Results ordered DESC by notifiedAt; use putIfAbsent in Java to keep first (= most recent) per market.
     * Only fetches alerts within the dedup window (last 60 min typically).
     */
    @Query("SELECT a FROM PolymarketAlertLog a " +
           "WHERE a.marketId IN :marketIds " +
           "  AND a.notifiedAt > :since " +
           "ORDER BY a.notifiedAt DESC")
    List<PolymarketAlertLog> findRecentAlertsForMarkets(
            @Param("marketIds") Set<String> marketIds,
            @Param("since") LocalDateTime since);
}
