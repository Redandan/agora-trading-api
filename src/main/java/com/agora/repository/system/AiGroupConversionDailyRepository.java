package com.agora.repository.system;

import com.agora.model.AiGroupConversionDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiGroupConversionDailyRepository extends JpaRepository<AiGroupConversionDaily, Long> {

    Optional<AiGroupConversionDaily> findByGroupIdAndStatDate(Long groupId, LocalDate statDate);

    @Query("SELECT d FROM AiGroupConversionDaily d WHERE d.statDate >= :from AND d.statDate <= :to " +
           "ORDER BY d.groupId, d.statDate")
    List<AiGroupConversionDaily> findByDateRange(
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("SELECT d FROM AiGroupConversionDaily d WHERE d.groupId = :groupId " +
           "AND d.statDate >= :from AND d.statDate <= :to ORDER BY d.statDate")
    List<AiGroupConversionDaily> findByGroupIdAndDateRange(
            @Param("groupId") Long groupId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
