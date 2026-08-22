package com.agora.repository.trading;

import com.agora.model.BtLiveSignal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

@Repository
public interface BtLiveSignalRepository extends JpaRepository<BtLiveSignal, Long> {

    Optional<BtLiveSignal> findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime(
            Long strategyId, String symbol, String intervalCode, LocalDateTime barOpenTime);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ls FROM BtLiveSignal ls WHERE ls.id = :id")
    Optional<BtLiveSignal> findByIdForUpdate(@Param("id") Long id);

    /**
     * 找出指定 symbol+interval 下最近一筆尚未出場（exitTime = null）且已通知的買入訊號。
     * 用於 SELL 訊號觸發時查找對應的開倉記錄。
     */
    List<BtLiveSignal> findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull(
            Long strategyId, String symbol, String intervalCode);

    /** 所有自動交易中、尚未出場、且有 OCO 掛單的倉位（供 OcoPositionPollerScheduler 輪詢使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull();

    /** 依 algoId 查詢尚未出場的開倉（供 OKX WS push 即時觸發使用）。 */
    java.util.Optional<BtLiveSignal> findFirstByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListId(Long ocoOrderListId);

    /**
     * 所有自動交易中、尚未出場、且無 OCO 掛單的倉位（供 OcoPositionPollerScheduler 自動補掛使用）。
     * BTC_BASE 底倉累積模式刻意不掛 OCO，不能被視為漏掛 OCO。
     */
    @Query("SELECT ls FROM BtLiveSignal ls " +
           "WHERE ls.autoTraded = true " +
           "  AND ls.exitTime IS NULL " +
           "  AND ls.ocoOrderListId IS NULL " +
           "  AND COALESCE(ls.filterReason, '') NOT LIKE 'LOCAL_TRADINGVIEW_BTC_BASE:%'")
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull();

    /** 所有自動交易尚未出場的倉位（供 TradingManagerService 報告使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNull();

    /** 指定策略下，自動交易尚未出場的倉位（供停用策略前安全檢查使用）。 */
    List<BtLiveSignal> findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(Long strategyId);

    /** 指定時間後已出場的自動交易倉位（供週報使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(LocalDateTime since);

    /** 所有已出場的自動交易倉位（供唯讀經濟帳本使用）。 */
    List<BtLiveSignal> findByAutoTradedIsTrueAndExitTimeIsNotNull();

    /** 刪除指定策略的所有訊號記錄（供 deleteStrategy 使用）。 */
    void deleteByStrategyId(Long strategyId);

}
