package com.agora.repository.system;

import com.agora.model.SecurityAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SecurityAuditEventRepository extends JpaRepository<SecurityAuditEvent, Long> {

    List<SecurityAuditEvent> findTop100ByIpAddressOrderByCreatedAtDesc(String ipAddress);

    List<SecurityAuditEvent> findTop100ByEmailOrderByCreatedAtDesc(String email);

    /** #367 retention — purge events older than N days. */
    @Modifying
    @Transactional
    @Query("DELETE FROM SecurityAuditEvent e WHERE e.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
