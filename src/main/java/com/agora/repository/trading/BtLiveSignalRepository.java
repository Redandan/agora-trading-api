package com.agora.repository.trading;

import com.agora.model.BtLiveSignal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BtLiveSignalRepository extends JpaRepository<BtLiveSignal, Long> {

    /**
     * 去重判斷：同一根 bar 是否已成功通知（notifiedAt != null）。
     * notifiedAt = null 表示 TG 失敗，允許 RetryScheduler 重試。
     */
    boolean existsByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTimeAndNotifiedAtIsNotNull(
            Long strategyId, String symbol, String intervalCode, LocalDateTime barOpenTime);

    /**
     * 找出所有 TG 尚未成功送出且超過指定時間的記錄（供 RetryScheduler 使用）。
     */
    List<BtLiveSignal> findByNotifiedAtIsNullAndCreatedAtBefore(LocalDateTime cutoff);

    /**
     * 找出指定 symbol+interval 下最近一筆尚未出場（exitTime = null）且已通知的買入訊號。
     * 用於 SELL 訊號觸發時查找對應的開倉記錄。
     */
    List<BtLiveSignal> findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
            Long strategyId, String symbol, String intervalCode);

    /** 統計指定時間後新增的訊號數，供每日健康摘要使用 */
    long countByCreatedAtAfter(LocalDateTime since);

    /** 取出指定時間後新增的所有訊號（供每日摘要分 side 計數使用）。 */
    List<BtLiveSignal> findByCreatedAtAfter(LocalDateTime since);

    /** 找出未出場的開倉訊號總數 */
    long countByExitTimeIsNull();

    /** 當日/指定時間後新增的真實自動交易數，供 ExposureOptimizer daily cap 使用。 */
    long countByAutoTradedIsTrueAndCreatedAtAfter(LocalDateTime since);

    /** 指定策略/交易對/週期的 shadow/notifyOnly LONG 訊號數，供 shadow learning cap 使用。 */
    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND ls.intervalCode = :intervalCode " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.autoTraded IS NULL OR ls.autoTraded = false)")
    long countShadowLongSignalsSince(@Param("strategyId") Long strategyId,
                                     @Param("symbol") String symbol,
                                     @Param("intervalCode") String intervalCode,
                                     @Param("since") LocalDateTime since);

    /** 最近已通知的買入訊號（分頁），供 TradingAnalysisService 讀取歷史紀錄 */
    Page<BtLiveSignal> findByNotifiedAtIsNotNullOrderByCreatedAtDesc(Pageable pageable);

    /** 自動交易中尚未出場的倉位總數（用於 maxOpenPositions 上限檢查）。 */
    long countByAutoTradedIsTrueAndExitTimeIsNull();

    /** 指定 symbol 下是否已有自動交易未出場的倉位（用於 allowConcurrentOnSameSymbol 檢查）。 */
    boolean existsBySymbolAndAutoTradedIsTrueAndExitTimeIsNull(String symbol);

    /** #332 — 同 strategy + symbol + side + interval 是否已有未出場 entry（含 shadow path,不限 autoTraded）。 */
    boolean existsByStrategyIdAndSymbolAndSideAndIntervalCodeAndExitTimeIsNull(
            Long strategyId, String symbol, String side, String intervalCode);

    /** 同 strategy + symbol + side + interval 是否已有真實自動交易未出場倉位。 */
    @Query("SELECT CASE WHEN COUNT(ls) > 0 THEN true ELSE false END FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = UPPER(:side)) " +
           "  AND ls.intervalCode = :intervalCode " +
           "  AND ls.autoTraded = true " +
           "  AND ls.exitTime IS NULL")
    boolean existsOpenAutoTradedPosition(@Param("strategyId") Long strategyId,
                                         @Param("symbol") String symbol,
                                         @Param("side") String side,
                                         @Param("intervalCode") String intervalCode);

    /** 所有自動交易中、尚未出場、且有 OCO 掛單的倉位（供 OcoPositionPollerScheduler 輪詢使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull();

    /** 依 algoId 查詢尚未出場的開倉（供 OKX WS push 即時觸發使用）。 */
    java.util.Optional<BtLiveSignal> findFirstByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListId(Long ocoOrderListId);

    /** 所有自動交易中、尚未出場、且無 OCO 掛單的倉位（供 OcoPositionPollerScheduler 自動補掛使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull();

    /** 所有自動交易尚未出場的倉位（供 TradingManagerService 報告使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNull();

    /** 指定策略下，自動交易尚未出場的倉位（供停用策略前安全檢查使用）。 */
    List<BtLiveSignal> findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(Long strategyId);

    /** 指定策略累計自動交易筆數(OPEN + CLOSED),供 scorecard 顯示「live trades」欄位。 */
    long countByStrategyIdAndAutoTradedIsTrue(Long strategyId);

    long countByStrategyIdAndAutoTradedIsTrueAndCreatedAtAfter(Long strategyId, LocalDateTime since);

    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.intervalCode = 'SB_PRE' " +
           "       OR COALESCE(ls.filterReason, '') = 'SCORE_BUY_EARLY_RECOVERY_SCOUT' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'SCORE_BUY_PRE:%')")
    long countScoreBuyPrePositionAutoTradesSince(@Param("strategyId") Long strategyId,
                                                 @Param("symbol") String symbol,
                                                 @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.intervalCode = 'SB_ADD' " +
           "       OR COALESCE(ls.filterReason, '') LIKE 'SCORE_BUY_POST_SCOUT_ADD%' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'SCORE_BUY_ADD:%')")
    long countScoreBuyPostScoutAddTradesSince(@Param("strategyId") Long strategyId,
                                              @Param("symbol") String symbol,
                                              @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.intervalCode = 'SB_ADD' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'SCORE_BUY_ADD:%') " +
           "  AND COALESCE(ls.filterReason, '') LIKE :filterReasonLike")
    long countScoreBuyPostScoutAddTradesByFilterReasonLikeSince(@Param("strategyId") Long strategyId,
                                                                @Param("symbol") String symbol,
                                                                @Param("since") LocalDateTime since,
                                                                @Param("filterReasonLike") String filterReasonLike);

    @Query("SELECT ls FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.intervalCode = 'SB_ADD' " +
           "       OR COALESCE(ls.filterReason, '') LIKE 'SCORE_BUY_POST_SCOUT_ADD%' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'SCORE_BUY_ADD:%') " +
           "ORDER BY ls.createdAt DESC")
    List<BtLiveSignal> findRecentScoreBuyPostScoutAddTradesSince(@Param("strategyId") Long strategyId,
                                                                 @Param("symbol") String symbol,
                                                                 @Param("since") LocalDateTime since,
                                                                 Pageable pageable);

    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND (ls.side IS NULL OR UPPER(ls.side) = 'LONG') " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (ls.intervalCode = 'SB_CONF' " +
           "       OR COALESCE(ls.filterReason, '') LIKE 'SCORE_BUY_CONFIRMED_DEPLOY%' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'SCORE_BUY_CONF:%')")
    long countScoreBuyConfirmedDeployTradesSince(@Param("strategyId") Long strategyId,
                                                 @Param("symbol") String symbol,
                                                 @Param("since") LocalDateTime since);

    @Query("SELECT COUNT(ls) FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND ls.autoTraded = true " +
           "  AND ls.createdAt >= :since " +
           "  AND (COALESCE(ls.filterReason, '') LIKE '%TINY_LIVE%' " +
           "       OR COALESCE(ls.exchangeOrderId, '') LIKE 'TINY_LIVE:%')")
    long countTinyLiveAutoTradesSince(@Param("strategyId") Long strategyId,
                                      @Param("symbol") String symbol,
                                      @Param("since") LocalDateTime since);

    /**
     * 指定策略累計 fired 訊號筆數(無論是否 auto_traded)。
     * scorecard 用「fired/traded」雙計顯示:fired = 策略真的觸發訊號,
     * traded = 觸發且真的下了單(filter / F&G veto 沒擋掉的)。
     * fired > traded 表示策略有東西可看但被閘道擋掉。
     */
    long countByStrategyId(Long strategyId);

    long countByStrategyIdAndCreatedAtAfter(Long strategyId, LocalDateTime since);

    /** 指定時間後已出場的自動交易倉位（供週報使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(LocalDateTime since);

    /** 指定策略在某時間之後產生的所有買入訊號（供啟動驗證使用）。 */
    List<BtLiveSignal> findByStrategyIdAndCreatedAtAfter(Long strategyId, LocalDateTime since);

    /** 刪除指定策略的所有訊號記錄（供 deleteStrategy 使用）。 */
    void deleteByStrategyId(Long strategyId);

    /** 查詢所有指定 side 的訊號（供歷史驗證分析使用）。 */
    List<BtLiveSignal> findBySideOrderByCreatedAtDesc(String side);

    /**
     * 指定策略 + 幣 + K 線區間內已自動下單的 signal(供 Meta-Control attribution
     * 計算 actual_pnl 使用)。注意:PAUSE 期間 LiveSignalEvaluator 會短路,通常此
     * 結果為空,但保留查詢以便 Phase 2 HINT override 類型使用(hint 不阻擋交易)。
     */
    List<BtLiveSignal> findByStrategyIdAndSymbolAndAutoTradedIsTrueAndBarOpenTimeBetween(
            Long strategyId, String symbol, LocalDateTime start, LocalDateTime end);

    /**
     * 近 N 天已平倉但尚未 annotate 的倉位(供 SessionBrief 顯示 annotation backlog)。
     * 依 exit_time 遞減排序,由呼叫端再截前 N 筆。
     */
    @Query("SELECT ls FROM BtLiveSignal ls " +
           "WHERE ls.autoTraded = true " +
           "  AND ls.exitTime IS NOT NULL " +
           "  AND ls.exitTime >= :since " +
           "  AND NOT EXISTS (SELECT 1 FROM PositionAnnotation pa " +
           "                    WHERE pa.liveSignalId = ls.id) " +
           "ORDER BY ls.exitTime DESC")
    List<BtLiveSignal> findClosedWithoutAnnotationSince(@Param("since") LocalDateTime since);

    /**
     * 最近已平倉的<strong>真實成交</strong>訊號(進場頻率冷卻檢查用)。
     * 用於判斷距離上次平倉的時間,防止 SIDEWAYS 環境過度交易。
     *
     * <p><b>auto_traded = 1 filter</b>(2026-05-05 修):shadow 訊號(auto_traded NULL/0)
     * 不算進冷卻。原本沒這 filter,ShadowSignalCleanupScheduler 把過期 shadow set exit_time
     * 後會被誤當「剛平倉」鎖 60min cooldown,造成 alpha 真訊號被擋(#441 root cause)。
     */
    @Query(value = "SELECT * FROM bt_live_signal " +
                   "WHERE strategy_id = :strategyId AND symbol = :symbol " +
                   "  AND exit_time IS NOT NULL " +
                   "  AND auto_traded = 1 " +
                   "ORDER BY exit_time DESC " +
                   "LIMIT 1", nativeQuery = true)
    java.util.Optional<BtLiveSignal> findLastClosedByStrategyIdAndSymbol(
            @Param("strategyId") Long strategyId,
            @Param("symbol") String symbol);

    @Query("SELECT ls FROM BtLiveSignal ls " +
           "WHERE ls.strategyId = :strategyId " +
           "  AND ls.symbol = :symbol " +
           "  AND ls.autoTraded = true " +
           "  AND ls.exitTime IS NOT NULL " +
           "  AND ls.exitTime >= :since " +
           "  AND (ls.filterReason LIKE '%TINY_LIVE%' OR ls.exchangeOrderId LIKE 'TINY_LIVE:%') " +
           "ORDER BY ls.exitTime DESC")
    List<BtLiveSignal> findClosedTinyLiveSince(@Param("strategyId") Long strategyId,
                                               @Param("symbol") String symbol,
                                               @Param("since") LocalDateTime since);
}
