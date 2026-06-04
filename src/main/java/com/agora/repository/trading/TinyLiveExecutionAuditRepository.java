package com.agora.repository.trading;

import com.agora.model.TinyLiveExecutionAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface TinyLiveExecutionAuditRepository extends JpaRepository<TinyLiveExecutionAudit, Long> {

    boolean existsByApprovalTokenHash(String approvalTokenHash);

    boolean existsByStatusAndCreatedAtAfter(String status, LocalDateTime since);

    long countByCreatedAtAfterAndOrderSentIsTrue(LocalDateTime since);

    long countByEventRiskOverrideUsedIsTrueAndCreatedAtAfter(LocalDateTime since);

    @Query("SELECT a FROM TinyLiveExecutionAudit a " +
           "WHERE a.createdAt >= :since " +
           "  AND (:symbol IS NULL OR a.symbol = :symbol) " +
           "ORDER BY a.createdAt DESC")
    List<TinyLiveExecutionAudit> findRecent(@Param("since") LocalDateTime since,
                                            @Param("symbol") String symbol,
                                            Pageable pageable);

    @Query("SELECT a FROM TinyLiveExecutionAudit a " +
           "WHERE (:symbol IS NULL OR a.symbol = :symbol) " +
           "ORDER BY a.createdAt DESC")
    List<TinyLiveExecutionAudit> findLatest(@Param("symbol") String symbol,
                                            Pageable pageable);
}
