package com.agora.repository.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BtGridLevelRepository extends JpaRepository<BtGridLevel, Long> {

    List<BtGridLevel> findByGridId(Long gridId);

    /** 取 PENDING level(等買入觸發)。 */
    List<BtGridLevel> findByGridIdAndStatus(Long gridId, String status);

    /** 取 PENDING 或 FILLED(當前仍活躍的 level)。 */
    List<BtGridLevel> findByGridIdAndStatusIn(Long gridId, List<String> statuses);

    /** 取全平時用。 */
    long countByGridIdAndStatus(Long gridId, String status);

    /**
     * 彙總所有 active grid(closed_at IS NULL)下**實際持有現貨**的 level qty 總和。
     *
     * <p>包含 FILLED(等待 sell 觸發)和 FAILED(sell 失敗但 buy 已成交,BTC 仍在 OKX)。
     * 判定依據:filledQty IS NOT NULL 代表 buy 已成交。即使 sell 失敗(status=FAILED),
     * 帳戶仍持有該 qty,reconcileHoldings 必須納入 expected,否則每 10min 誤報 orphan。
     *
     * <p>CLOSED(buy+sell 都完成)和 PENDING(尚未 buy)不會有 filledQty → 自然排除。
     */
    /**
     * 彙總所有 active grid 下**實際持有現貨**的 level qty 總和。
     * HOLDING(等賣)+ SELL_FAILED(賣失敗但 BTC 仍在)+ SELL_PARTIAL(#399 partial fill leftover)
     * 都算 — reconcileHoldings 才不會把 partial-fill leftover 誤報 orphan。
     */
    @Query("SELECT g.symbol, COALESCE(SUM(l.filledQty), 0) " +
           "FROM BtGridLevel l, BtGrid g " +
           "WHERE l.gridId = g.id " +
           "  AND l.status IN ('HOLDING', 'SELL_FAILED', 'SELL_PARTIAL') " +
           "  AND l.filledQty IS NOT NULL " +
           "  AND g.closedAt IS NULL " +
           "GROUP BY g.symbol")
    List<Object[]> sumFilledQtyBySymbolForActiveGrids();

    /**
     * 彙總已關閉 grid 中仍留在 DB 的現貨 residual。
     * 這些 row 已不是 active grid automation 的一部分,但仍是已知 DB
     * inventory; OCO poller 現貨對帳不能把它們誤報成手動/未追蹤倉位。
     */
    @Query("SELECT g.symbol, COALESCE(SUM(l.filledQty), 0) " +
           "FROM BtGridLevel l, BtGrid g " +
           "WHERE l.gridId = g.id " +
           "  AND l.status IN ('HOLDING', 'SELL_FAILED', 'SELL_PARTIAL') " +
           "  AND l.filledQty IS NOT NULL " +
           "  AND g.closedAt IS NOT NULL " +
           "GROUP BY g.symbol")
    List<Object[]> sumResidualFilledQtyBySymbolForClosedGrids();
}
