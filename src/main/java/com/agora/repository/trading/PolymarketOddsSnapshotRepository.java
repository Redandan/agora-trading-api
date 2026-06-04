package com.agora.repository.trading;

import com.agora.model.PolymarketOddsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public interface PolymarketOddsSnapshotRepository extends JpaRepository<PolymarketOddsSnapshot, Long> {

    /** Most recent snapshot for a market — used to compute volume_delta and prob_delta (single-market fallback) */
    Optional<PolymarketOddsSnapshot> findTopByMarketIdOrderBySnapshottedAtDesc(String marketId);

    // ── Batch queries (replace N+1 pattern in runSnapshot) ───────────────────────

    /**
     * Batch: one query returns the latest snapshot per market for all supplied IDs.
     * Uses MAX(id) as a proxy for "latest" (id is auto-increment; UNIQUE on market_id+snapshotted_at
     * from V059 means higher id = more recent tick).
     */
    @Query("SELECT s FROM PolymarketOddsSnapshot s " +
           "WHERE s.marketId IN :marketIds " +
           "  AND s.id IN (SELECT MAX(s2.id) FROM PolymarketOddsSnapshot s2 " +
           "               WHERE s2.marketId IN :marketIds GROUP BY s2.marketId)")
    List<PolymarketOddsSnapshot> findLatestForMarkets(@Param("marketIds") Set<String> marketIds);

    /**
     * Batch: snapshots within a time window for multiple markets, ordered DESC by snapshotted_at.
     * First result per marketId = latest in window (use putIfAbsent in Java to pick it).
     *
     * <p>Used for two lookups with different windows:
     * <ul>
     *   <li>1h-ago: from=now-75min, to=now-45min → latest in window ≈ prob 1h ago</li>
     *   <li>24h-ago: from=now-25h, to=now-23h → latest in window ≈ prob 24h ago</li>
     * </ul>
     */
    @Query("SELECT s FROM PolymarketOddsSnapshot s " +
           "WHERE s.marketId IN :marketIds " +
           "  AND s.snapshottedAt BETWEEN :from AND :to " +
           "ORDER BY s.marketId ASC, s.snapshottedAt DESC")
    List<PolymarketOddsSnapshot> findInWindowForMarkets(
            @Param("marketIds") Set<String> marketIds,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /**
     * Batch: rolling average of volume_delta_15m per market for the last N days.
     * Returns List of Object[]{marketId, avg} — map in Java.
     */
    @Query("SELECT s.marketId, AVG(s.volumeDelta15m) FROM PolymarketOddsSnapshot s " +
           "WHERE s.marketId IN :marketIds " +
           "  AND s.snapshottedAt > :since " +
           "  AND s.volumeDelta15m IS NOT NULL " +
           "  AND s.volumeDelta15m > 0 " +
           "GROUP BY s.marketId")
    List<Object[]> findRollingAvgForMarkets(
            @Param("marketIds") Set<String> marketIds,
            @Param("since") LocalDateTime since);

    // ── Legacy single-market queries (kept for backfill scheduler + tests) ────────

    /** Snapshot closest to 1h ago — single market (used by backfill tests / legacy paths) */
    @Query("SELECT s FROM PolymarketOddsSnapshot s " +
           "WHERE s.marketId = :marketId " +
           "  AND s.snapshottedAt BETWEEN :from AND :to " +
           "ORDER BY s.snapshottedAt DESC")
    List<PolymarketOddsSnapshot> findNear1hAgo(
            @Param("marketId") String marketId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    /** 7-day rolling average of volume_delta_15m — single market (used by backfill / tests) */
    @Query("SELECT AVG(s.volumeDelta15m) FROM PolymarketOddsSnapshot s " +
           "WHERE s.marketId = :marketId " +
           "  AND s.snapshottedAt > :since " +
           "  AND s.volumeDelta15m IS NOT NULL " +
           "  AND s.volumeDelta15m > 0")
    Optional<BigDecimal> findRollingAvgVolumeDelta(
            @Param("marketId") String marketId,
            @Param("since") LocalDateTime since);
}
