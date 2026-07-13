package com.agora.repository.trading;

import com.agora.model.MarketIndicatorHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MarketIndicatorHistoryRepository
        extends JpaRepository<MarketIndicatorHistory, Long> {

    /** 取 (symbol, indicator) 近 N 小時的時間序列,供 getIndicatorHistory MCP 使用。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator " +
           "AND captured_at > :since ORDER BY captured_at ASC",
           nativeQuery = true)
    List<MarketIndicatorHistory> findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("since") LocalDateTime since);

    /** Cheap startup/backfill coverage check without materializing historical rows. */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator AND captured_at > :since",
           nativeQuery = true)
    long countBySymbolAndIndicatorAndCapturedAtAfter(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("since") LocalDateTime since);

    /** 取近 N 天所有指標,供 getIndicatorAnomalies 掃異常點。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE captured_at > :since ORDER BY captured_at ASC",
           nativeQuery = true)
    List<MarketIndicatorHistory> findByCapturedAtAfterOrderByCapturedAtAsc(@Param("since") LocalDateTime since);

    /**
     * 取最新一筆 (symbol, indicator) 紀錄;供 MarketIndicatorHistoryCollector 計算
     * 1h delta（例如 {@code oi_change_pct_1h} = (新OI - 舊OI) / 舊OI × 100）。
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator ORDER BY captured_at DESC LIMIT 1",
           nativeQuery = true)
    Optional<MarketIndicatorHistory> findTopBySymbolAndIndicatorOrderByCapturedAtDesc(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator);

    /** 歷史回算用：取 capturedAt <= at 的最近一筆（不超過給定時間點）。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator " +
           "AND captured_at <= :at ORDER BY captured_at DESC LIMIT 1",
           nativeQuery = true)
    Optional<MarketIndicatorHistory> findTopBySymbolAndIndicatorAndCapturedAtLessThanEqualOrderByCapturedAtDesc(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("at") LocalDateTime at);

    /** OI backfill 去重：確認某個時間點的 indicator 是否已存在。 */
    boolean existsBySymbolAndIndicatorAndCapturedAt(
            String symbol, String indicator, LocalDateTime capturedAt);

    /**
     * Atomic idempotent write for scheduler collectors.
     * Returns 1 when inserted, 0 when the unique key already exists.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT IGNORE INTO market_indicator_history
            (captured_at, symbol, indicator, value, error_flag)
            VALUES (:capturedAt, :symbol, :indicator, :value, 0)
            """, nativeQuery = true)
    int insertIgnore(@Param("symbol") String symbol,
                     @Param("indicator") String indicator,
                     @Param("capturedAt") LocalDateTime capturedAt,
                     @Param("value") java.math.BigDecimal value);

    /** Atomic idempotent collector write that also preserves provider provenance metadata. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT IGNORE INTO market_indicator_history
            (captured_at, symbol, indicator, value, metadata_json, error_flag)
            VALUES (:capturedAt, :symbol, :indicator, :value, :metadataJson, 0)
            """, nativeQuery = true)
    int insertIgnoreWithMetadata(@Param("symbol") String symbol,
                                 @Param("indicator") String indicator,
                                 @Param("capturedAt") LocalDateTime capturedAt,
                                 @Param("value") java.math.BigDecimal value,
                                 @Param("metadataJson") String metadataJson);

    /** 刪除指定 symbol+indicator 在某時間點之後的所有記錄（SQI 重算用）。 */
    @Modifying
    @Transactional
    @Query("DELETE FROM MarketIndicatorHistory m WHERE m.symbol = :symbol AND m.indicator = :indicator AND m.capturedAt > :since")
    void deleteBySymbolAndIndicatorAfter(@Param("symbol") String symbol,
                                         @Param("indicator") String indicator,
                                         @Param("since") LocalDateTime since);

    /** 取最新兩筆 (symbol, indicator)，供環比計算（路徑 B 用）。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator ORDER BY captured_at DESC LIMIT 2",
           nativeQuery = true)
    List<MarketIndicatorHistory> findTop2BySymbolAndIndicatorOrderByCapturedAtDesc(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator);

    /** 30天 95 分位數，供動態閾值計算。回傳 null 表示資料不足。MySQL 8.0+ PERCENT_RANK。 */
    @Query(value = """
            SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ value FROM (
                SELECT value,
                       PERCENT_RANK() OVER (ORDER BY value) AS prank
                FROM market_indicator_history
                WHERE symbol = :symbol AND indicator = :indicator
                AND captured_at > :since AND (error_flag IS NULL OR error_flag = 0)
            ) t WHERE t.prank >= 0.95 ORDER BY t.prank LIMIT 1
            """, nativeQuery = true)
    Double findPercentile95(@Param("symbol") String symbol,
                            @Param("indicator") String indicator,
                            @Param("since") LocalDateTime since);

    // ─── error_flag-aware variants (#327) ────────────────────────────────────
    // 用於 indicator calculator — 排除 error_flag=1 的污染數據避免下游污染。

    /** {@code findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc} 但過濾 error_flag。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator " +
           "AND captured_at > :since AND error_flag = 0 ORDER BY captured_at ASC",
           nativeQuery = true)
    List<MarketIndicatorHistory> findCleanBySymbolAndIndicatorAndCapturedAtAfter(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("since") LocalDateTime since);

    /** {@code findTopBySymbolAndIndicatorOrderByCapturedAtDesc} 但過濾 error_flag。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history WHERE symbol = :symbol AND indicator = :indicator " +
           "AND error_flag = 0 ORDER BY captured_at DESC LIMIT 1", nativeQuery = true)
    Optional<MarketIndicatorHistory> findTopCleanBySymbolAndIndicator(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator);

    /** {@code findTopBySymbolAndIndicatorAndCapturedAtLessThanEqualOrderByCapturedAtDesc} 但過濾 error_flag。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history WHERE symbol = :symbol AND indicator = :indicator " +
           "AND captured_at <= :at AND error_flag = 0 ORDER BY captured_at DESC LIMIT 1", nativeQuery = true)
    Optional<MarketIndicatorHistory> findTopCleanBySymbolAndIndicatorAndCapturedAtLessThanEqual(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("at") LocalDateTime at);

    /**
     * 取限定時間窗內最新的乾淨指標。策略的長生命週期 cache miss 會使用此查詢，
     * 讓 collector 在 cache 初次載入後新增的資料不必等到服務重啟才可見。
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM market_indicator_history " +
           "WHERE symbol = :symbol AND indicator = :indicator " +
           "AND captured_at >= :fromInclusive AND captured_at < :toExclusive " +
           "AND error_flag = 0 ORDER BY captured_at DESC LIMIT 1", nativeQuery = true)
    Optional<MarketIndicatorHistory> findTopCleanInCapturedAtWindow(
            @Param("symbol") String symbol,
            @Param("indicator") String indicator,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * #429 — count clean (non-error) rows since a cutoff for zombie audit.
     * Returns 0 when an indicator stops being collected (e.g. VDI freeze),
     * letting WeeklyScorecardDigest flag attention rules pointing at it.
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) FROM market_indicator_history WHERE symbol = :symbol " +
           "AND indicator = :indicator AND captured_at > :since AND (error_flag IS NULL OR error_flag = 0)",
           nativeQuery = true)
    long countCleanSince(@Param("symbol") String symbol,
                         @Param("indicator") String indicator,
                         @Param("since") LocalDateTime since);

    /** #234: Mark a data point as erroneous to exclude from ML training. */
    @Modifying
    @Transactional
    @Query("UPDATE MarketIndicatorHistory m SET m.errorFlag = true, m.errorReason = :reason " +
           "WHERE m.symbol = :symbol AND m.indicator = :indicator AND m.capturedAt = :capturedAt")
    int flagAsError(@Param("symbol") String symbol,
                    @Param("indicator") String indicator,
                    @Param("capturedAt") LocalDateTime capturedAt,
                    @Param("reason") String reason);
}
