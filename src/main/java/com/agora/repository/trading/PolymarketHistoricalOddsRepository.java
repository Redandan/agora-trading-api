package com.agora.repository.trading;

import com.agora.model.PolymarketHistoricalOdds;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PolymarketHistoricalOddsRepository extends JpaRepository<PolymarketHistoricalOdds, Long> {

    boolean existsByMarketIdAndEventTime(String marketId, LocalDateTime eventTime);

    /** Latest imported event_time for a market — used for incremental import */
    @Query("SELECT MAX(h.eventTime) FROM PolymarketHistoricalOdds h WHERE h.marketId = :marketId")
    LocalDateTime findMaxEventTime(@Param("marketId") String marketId);

    /** Backtest correlation: events where abs(prob_delta_1h) >= threshold and BTC label present */
    @Query("SELECT h FROM PolymarketHistoricalOdds h " +
           "WHERE ABS(h.probDelta1h) >= :minDelta " +
           "  AND h.btcChange4h IS NOT NULL " +
           "  AND h.eventTime >= :since " +
           "ORDER BY ABS(h.probDelta1h) DESC")
    List<PolymarketHistoricalOdds> findSignalEvents(
            @Param("minDelta") BigDecimal minDelta,
            @Param("since") LocalDateTime since);

    /**
     * Category-filtered variant — pushes category filter to DB, avoids loading all rows into Java.
     * Pass null for category to match all categories.
     */
    @Query("SELECT h FROM PolymarketHistoricalOdds h " +
           "WHERE ABS(h.probDelta1h) >= :minDelta " +
           "  AND h.btcChange4h IS NOT NULL " +
           "  AND h.probDelta1h IS NOT NULL " +
           "  AND h.eventTime >= :since " +
           "  AND (:category IS NULL OR h.marketCategory = :category) " +
           "ORDER BY ABS(h.probDelta1h) DESC")
    List<PolymarketHistoricalOdds> findSignalEventsFiltered(
            @Param("minDelta") BigDecimal minDelta,
            @Param("since") LocalDateTime since,
            @Param("category") String category);

    /** Count imported rows per market — for import status report */
    @Query("SELECT h.marketTitle, COUNT(h) FROM PolymarketHistoricalOdds h GROUP BY h.marketId, h.marketTitle")
    List<Object[]> countByMarket();
}
