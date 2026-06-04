package com.agora.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * #367 — Persisted IP risk score. Restored into in-memory cache on startup
 * (see {@code SecurityAuditServiceImpl} hydrate() ).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ip_risk_score")
public class IpRiskScore {

    @Id
    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private Integer score;

    @Column(name = "activity_count", nullable = false)
    private Integer activityCount;

    @Column(name = "last_updated", insertable = false, updatable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "decay_at")
    private LocalDateTime decayAt;
}
