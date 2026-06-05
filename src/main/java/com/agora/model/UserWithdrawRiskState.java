package com.agora.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "user_withdraw_risk_state")
@Schema(description = "用戶提款風控狀態（滾動計數器）")
public class UserWithdrawRiskState {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "daily_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal dailyAmount = BigDecimal.ZERO;

    @Column(name = "monthly_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal monthlyAmount = BigDecimal.ZERO;

    @Column(name = "successful_count", nullable = false)
    private Integer successfulCount = 0;

    @Column(name = "flagged_count", nullable = false)
    private Integer flaggedCount = 0;

    @Column(name = "first_withdraw_at")
    private LocalDateTime firstWithdrawAt;

    @Column(name = "last_withdraw_at")
    private LocalDateTime lastWithdrawAt;

    @Column(name = "cooldown_until")
    private LocalDateTime cooldownUntil;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
