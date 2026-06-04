package com.agora.repository.trading;

import com.agora.model.BtFundingArb;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BtFundingArbRepository extends JpaRepository<BtFundingArb, Long> {

    /** 查指定 symbol 當前是否有 active position(OPEN / OPENING / CLOSING)。 */
    List<BtFundingArb> findBySymbolAndStatusIn(String symbol, List<String> statuses);

    /** 取所有 active(非 CLOSED / FAILED)position 供 scheduler reconcile。 */
    List<BtFundingArb> findByStatusIn(List<String> statuses);

    /** 最近已平倉 N 筆(供 stats 計算)。 */
    List<BtFundingArb> findByStatusAndClosedAtAfterOrderByClosedAtDesc(
            String status, LocalDateTime since);

    /** 查單筆 OPEN(Phase 1 single-position policy 可能用到)。 */
    Optional<BtFundingArb> findFirstBySymbolAndStatusOrderByOpenedAtDesc(String symbol, String status);
}
