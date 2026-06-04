package com.agora.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * #367 — Persisted security audit event. Replaces in-memory only logging
 * in {@code SecurityAuditServiceImpl}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "security_audit_event", indexes = {
        @Index(name = "idx_sae_ip_created", columnList = "ip_address,created_at"),
        @Index(name = "idx_sae_email_created", columnList = "email,created_at"),
        @Index(name = "idx_sae_event_created", columnList = "event_type,created_at")
})
public class SecurityAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PASSWORD_RESET / VERIFICATION_CODE / LOCKOUT / SUSPICIOUS. */
    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 120)
    private String email;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    /** NULL when not applicable (e.g. lockout). */
    private Boolean success;

    @Column(length = 255)
    private String reason;

    /** HIGH / MEDIUM / LOW (only for SUSPICIOUS events). */
    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(name = "score_delta")
    private Integer scoreDelta;

    @Column(name = "created_at", nullable = false, updatable = false,
            insertable = false)  // DB DEFAULT CURRENT_TIMESTAMP(3)
    private LocalDateTime createdAt;
}
