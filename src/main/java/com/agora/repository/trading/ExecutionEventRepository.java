package com.agora.repository.trading;

import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.model.ExecutionEvent;
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
public interface ExecutionEventRepository extends JpaRepository<ExecutionEvent, Long> {

    Optional<ExecutionEvent> findByFingerprint(String fingerprint);

    @Query("SELECT e FROM ExecutionEvent e " +
            "WHERE e.status = :status " +
            "AND (:symbol IS NULL OR e.symbol = :symbol) " +
            "AND (:positionId IS NULL OR e.positionId = :positionId) " +
            "AND (e.positionId IS NULL OR NOT EXISTS (" +
            "   SELECT 1 FROM BtLiveSignal pos " +
            "   WHERE pos.id = e.positionId AND pos.exitTime IS NOT NULL" +
            ")) " +
            "AND (e.expiresAt IS NULL OR e.expiresAt > :now) " +
            "ORDER BY e.detectedAt DESC")
    List<ExecutionEvent> findActive(
            @Param("status") ExecutionEventStatus status,
            @Param("symbol") String symbol,
            @Param("positionId") Long positionId,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    @Query("SELECT e FROM ExecutionEvent e " +
            "WHERE e.type = :type AND e.status = :status " +
            "AND (e.positionId IS NULL OR NOT EXISTS (" +
            "   SELECT 1 FROM BtLiveSignal pos " +
            "   WHERE pos.id = e.positionId AND pos.exitTime IS NOT NULL" +
            ")) " +
            "AND (e.expiresAt IS NULL OR e.expiresAt > :now) " +
            "ORDER BY e.detectedAt DESC")
    List<ExecutionEvent> findActiveByType(
            @Param("type") ExecutionEventType type,
            @Param("status") ExecutionEventStatus status,
            @Param("now") LocalDateTime now,
            Pageable pageable);

    @Modifying
    @Query("UPDATE ExecutionEvent e SET e.status = :expired, e.updatedAt = :now " +
            "WHERE e.status = :active AND e.expiresAt IS NOT NULL AND e.expiresAt <= :now")
    int expireStale(@Param("now") LocalDateTime now,
                    @Param("active") ExecutionEventStatus active,
                    @Param("expired") ExecutionEventStatus expired);

    @Modifying
    @Query("UPDATE ExecutionEvent e SET e.status = :resolved, e.updatedAt = :now, e.resolvedAt = :now " +
            "WHERE e.status = :active " +
            "AND e.positionId IS NOT NULL " +
            "AND EXISTS (" +
            "   SELECT 1 FROM BtLiveSignal pos " +
            "   WHERE pos.id = e.positionId AND pos.exitTime IS NOT NULL" +
            ")")
    int resolveClosedPositionEvents(@Param("now") LocalDateTime now,
                                    @Param("active") ExecutionEventStatus active,
                                    @Param("resolved") ExecutionEventStatus resolved);
}
