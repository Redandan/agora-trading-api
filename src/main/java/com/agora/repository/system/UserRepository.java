package com.agora.repository.system;

import com.agora.enums.system.UserStatusEnum;
import com.agora.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
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

} 
