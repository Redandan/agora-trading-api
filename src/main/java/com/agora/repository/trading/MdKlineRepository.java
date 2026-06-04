package com.agora.repository.trading;

import com.agora.model.MdKline;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface MdKlineRepository extends JpaRepository<MdKline, Long> {

    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM md_kline " +
            "WHERE symbol = :symbol AND interval_code = :intervalCode " +
            "AND open_time BETWEEN :startTime AND :endTime ORDER BY open_time ASC",
            nativeQuery = true)
    List<MdKline> findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
            @Param("symbol") String symbol,
            @Param("intervalCode") String intervalCode,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 批次查詢指定範圍內已存在的 openTime，供匯入時去重使用（避免 N 次 EXISTS 查詢）。
     */
    @Query("SELECT k.openTime FROM MdKline k " +
           "WHERE k.symbol = :symbol AND k.intervalCode = :intervalCode " +
           "AND k.openTime BETWEEN :start AND :end")
    List<LocalDateTime> findOpenTimesBetween(@Param("symbol") String symbol,
                                             @Param("intervalCode") String intervalCode,
                                             @Param("start") LocalDateTime start,
                                             @Param("end") LocalDateTime end);

    /** Source-aware 版本：供 KlineGapDetector 僅偵測特定 source 的缺口使用。 */
    @Query("SELECT k.openTime FROM MdKline k " +
           "WHERE k.symbol = :symbol AND k.intervalCode = :intervalCode " +
           "AND k.source = :source " +
           "AND k.openTime BETWEEN :start AND :end")
    List<LocalDateTime> findOpenTimesBetweenBySource(@Param("symbol") String symbol,
                                                      @Param("intervalCode") String intervalCode,
                                                      @Param("source") String source,
                                                      @Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

    /**
     * 用於 WS 即時存入前的重複確認（不分 source，用於向後相容）。
     */
    boolean existsBySymbolAndIntervalCodeAndOpenTime(String symbol,
                                                     String intervalCode,
                                                     LocalDateTime openTime);

    /**
     * source-aware 存在檢查：允許同 (symbol, interval, openTime) 兩個源各存一筆。
     * 新的 WS 寫入應使用這個方法，避免誤擋另一個 source 的 bar。
     */
    boolean existsBySymbolAndIntervalCodeAndOpenTimeAndSource(String symbol,
                                                               String intervalCode,
                                                               LocalDateTime openTime,
                                                               String source);

    /**
     * 查詢同 (symbol, interval, openTime) 在指定 source 的單筆 K 線（供即時偏差比對）。
     * KlineDivergenceListener 在收到一邊收盤後，用此查另一邊是否已到達。
     */
    Optional<MdKline> findFirstBySymbolAndIntervalCodeAndOpenTimeAndSource(
            String symbol, String intervalCode, LocalDateTime openTime, String source);

    /**
     * 查詢指定 source 的 K 線區間（回測/驗證使用）。
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM md_kline " +
            "WHERE symbol = :symbol AND interval_code = :intervalCode AND source = :source " +
            "AND open_time BETWEEN :startTime AND :endTime ORDER BY open_time ASC",
            nativeQuery = true)
    List<MdKline> findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
            @Param("symbol") String symbol,
            @Param("intervalCode") String intervalCode,
            @Param("source") String source,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 取最近 N 根 K 線（降序），搭配 PageRequest.of(0, limit) 使用，
     * 取出後需自行 Collections.reverse() 轉為升序供圖表展示。
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM md_kline " +
            "WHERE symbol = :symbol AND interval_code = :intervalCode ORDER BY open_time DESC",
            nativeQuery = true)
    List<MdKline> findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
            @Param("symbol") String symbol, @Param("intervalCode") String intervalCode, Pageable pageable);

    /** Source-aware 版本：供實時信號評估讀取特定資料源的 K 線。 */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ * FROM md_kline " +
            "WHERE symbol = :symbol AND interval_code = :intervalCode AND source = :source " +
            "ORDER BY open_time DESC",
            nativeQuery = true)
    List<MdKline> findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
            @Param("symbol") String symbol, @Param("intervalCode") String intervalCode,
            @Param("source") String source, Pageable pageable);

    /**
     * V041：啟動時驗證每個 enabled 策略是否擁有足夠的 kline 樣本供指標計算。
     * 計算特定 (symbol, interval, source) 組合的 bar 數。
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) FROM md_kline " +
            "WHERE symbol = :symbol AND interval_code = :intervalCode AND source = :source",
            nativeQuery = true)
    long countBySymbolAndIntervalCodeAndSource(@Param("symbol") String symbol,
                                               @Param("intervalCode") String intervalCode,
                                               @Param("source") String source);

    /**
     * 雙源對比查詢：同 (symbol, interval, openTime) 的 binance vs okx 兩筆併排，供 divergence monitor 使用。
     * 回傳欄位：[openTime, binance_close, okx_close, binance_vol, okx_vol]
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ " +
           "b.open_time, b.close_price, o.close_price, b.volume, o.volume " +
           "FROM md_kline b JOIN md_kline o " +
           "  ON b.symbol = o.symbol AND b.interval_code = o.interval_code AND b.open_time = o.open_time " +
           " AND b.source = 'binance' AND o.source = 'okx' " +
           "WHERE b.symbol = :symbol AND b.interval_code = :intervalCode " +
           "  AND b.open_time BETWEEN :start AND :end " +
           "ORDER BY b.open_time DESC",
           nativeQuery = true)
    List<Object[]> findDualSourcePairs(@Param("symbol") String symbol,
                                        @Param("intervalCode") String intervalCode,
                                        @Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    /**
     * 刪除指定時間範圍內的 K 線，回傳刪除筆數（供資料修復使用）。
     */
    @Modifying
    @Query("DELETE FROM MdKline k " +
           "WHERE k.symbol = :symbol AND k.intervalCode = :intervalCode " +
           "AND k.openTime BETWEEN :start AND :end")
    int deleteBySymbolAndIntervalCodeAndOpenTimeBetween(@Param("symbol") String symbol,
                                                        @Param("intervalCode") String intervalCode,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    /**
     * 刪除指定 interval 在指定時間前的所有 K 線。用於 1min 的 30 天滑動窗口清理。
     */
    @Modifying
    @Query("DELETE FROM MdKline k WHERE k.intervalCode = :intervalCode AND k.openTime < :cutoff")
    int deleteByIntervalCodeAndOpenTimeBefore(@Param("intervalCode") String intervalCode,
                                               @Param("cutoff") LocalDateTime cutoff);

    /**
     * Source-aware delete：只刪指定 source 的 K 線（雙寫架構下 reimport 必須限定源,避免誤刪另一源）。
     */
    @Modifying
    @Query("DELETE FROM MdKline k " +
           "WHERE k.symbol = :symbol AND k.intervalCode = :intervalCode " +
           "AND k.source = :source " +
           "AND k.openTime BETWEEN :start AND :end")
    int deleteBySymbolAndIntervalCodeAndSourceAndOpenTimeBetween(@Param("symbol") String symbol,
                                                                  @Param("intervalCode") String intervalCode,
                                                                  @Param("source") String source,
                                                                  @Param("start") LocalDateTime start,
                                                                  @Param("end") LocalDateTime end);

    /**
     * 查詢指定 symbol+interval 在時間範圍內的量能統計（用於驗證資料質量）。
     * List 大小固定為 1，每個 Object[] = {count, firstBar, lastBar, minVol, maxVol, avgVol}
     */
    @Query(value = "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*), MIN(open_time), MAX(open_time), " +
           "MIN(volume), MAX(volume), AVG(volume) " +
           "FROM md_kline " +
           "WHERE symbol = :symbol AND interval_code = :intervalCode " +
           "AND open_time BETWEEN :start AND :end",
           nativeQuery = true)
    List<Object[]> volumeStats(@Param("symbol") String symbol,
                               @Param("intervalCode") String intervalCode,
                               @Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    /**
     * 查詢 DB 中所有不重複的交易對（供前端下拉選單使用）。
     */
    @Query("SELECT DISTINCT k.symbol FROM MdKline k ORDER BY k.symbol ASC")
    List<String> findDistinctSymbols();

    /**
     * 查詢指定 symbol 下所有不重複的 intervalCode。
     */
    @Query("SELECT DISTINCT k.intervalCode FROM MdKline k WHERE k.symbol = :symbol")
    List<String> findDistinctIntervalsBySymbol(@Param("symbol") String symbol);
}
