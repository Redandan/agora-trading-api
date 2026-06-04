package com.agora.repository.system;

import com.agora.dto.analytics.DailyRegistrationStats;
import com.agora.dto.analytics.HourlyRegistrationStats;
import com.agora.dto.analytics.MethodRegistrationStats;
import com.agora.dto.analytics.PromoCodeRegistrationStats;
import com.agora.enums.system.UserStatusEnum;
import com.agora.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    /** 取所有 admin user(role='ADMIN'),供系統廣播用 */
    List<User> findByRole(String role);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    Page<User> findByStatusAndCreatedAtBetween(UserStatusEnum status, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Page<User> findByStatus(UserStatusEnum status, Pageable pageable);

    Page<User> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    Page<User> findByEmailContainingOrNameContaining(String email, String name, Pageable pageable);

    long countByCreatedAtAfter(LocalDateTime dateTime);

    List<User> findByCreatedAtBetween(LocalDateTime startTime, LocalDateTime endTime);

    // 推廣碼相關查詢方法
    Page<User> findByPromoCode(String promoCode, Pageable pageable);

    long countByPromoCode(String promoCode);

    boolean existsByPromoCode(String promoCode);

    long countByCreatedAtBetween(LocalDateTime start, LocalDateTime end);

    // ── 流量分析查詢 ────────────────────────────────────────────────────────────

    @Query(value =
        "SELECT DATE(u.created_at) AS registrationDate, COUNT(*) AS count" +
        "  FROM users u" +
        " WHERE u.created_at BETWEEN :start AND :end" +
        " GROUP BY DATE(u.created_at)" +
        " ORDER BY registrationDate ASC",
        nativeQuery = true)
    List<DailyRegistrationStats> countByDay(
        @Param("start") LocalDateTime start,
        @Param("end")   LocalDateTime end
    );

    @Query(value =
        "SELECT HOUR(u.created_at) AS hour, COUNT(*) AS count" +
        "  FROM users u" +
        " WHERE u.created_at BETWEEN :start AND :end" +
        " GROUP BY HOUR(u.created_at)" +
        " ORDER BY hour ASC",
        nativeQuery = true)
    List<HourlyRegistrationStats> countByHour(
        @Param("start") LocalDateTime start,
        @Param("end")   LocalDateTime end
    );

    @Query(value =
        "SELECT COALESCE(u.registration_method, 'UNKNOWN') AS method, COUNT(*) AS count" +
        "  FROM users u" +
        " WHERE u.created_at BETWEEN :start AND :end" +
        " GROUP BY u.registration_method" +
        " ORDER BY count DESC",
        nativeQuery = true)
    List<MethodRegistrationStats> countByMethod(
        @Param("start") LocalDateTime start,
        @Param("end")   LocalDateTime end
    );

    @Query(value =
        "SELECT COALESCE(u.promo_code, '(direct)') AS promoCode, COUNT(*) AS count" +
        "  FROM users u" +
        " WHERE u.created_at BETWEEN :start AND :end" +
        " GROUP BY u.promo_code" +
        " ORDER BY count DESC" +
        " LIMIT :topN",
        nativeQuery = true)
    List<PromoCodeRegistrationStats> countByPromoCodeInRange(
        @Param("start") LocalDateTime start,
        @Param("end")   LocalDateTime end,
        @Param("topN")  int topN
    );
} 