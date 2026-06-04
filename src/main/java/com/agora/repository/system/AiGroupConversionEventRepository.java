package com.agora.repository.system;

import com.agora.model.AiGroupConversionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AiGroupConversionEventRepository extends JpaRepository<AiGroupConversionEvent, Long> {

    @Query("SELECT e FROM AiGroupConversionEvent e WHERE e.groupId = :groupId " +
           "AND e.createdAt >= :from AND e.createdAt < :to ORDER BY e.createdAt DESC")
    List<AiGroupConversionEvent> findByGroupIdAndDateRange(
            @Param("groupId") Long groupId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);
}
