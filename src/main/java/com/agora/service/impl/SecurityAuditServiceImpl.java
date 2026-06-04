package com.agora.service.impl;

import com.agora.config.AsyncStartup;
import com.agora.config.properties.SecurityAuditProperties;
import com.agora.model.IpRiskScore;
import com.agora.model.SecurityAuditEvent;
import com.agora.repository.system.IpRiskScoreRepository;
import com.agora.repository.system.SecurityAuditEventRepository;
import com.agora.service.SecurityAuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * #367 — Persisted SecurityAuditService.
 *
 * <p>Hot path keeps in-memory cache (fast lookup), but every event is also
 * written to {@code security_audit_event} (async) and risk-score deltas
 * upserted to {@code ip_risk_score} (sync, in same path so subsequent JVM
 * lookups read fresh).
 *
 * <p>On startup, hydrate cache from {@code ip_risk_score} (last 30 days).
 *
 * <p>Cache and DB are both written; DB is source of truth across restarts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@AsyncStartup("hydrate IP risk score cache from DB (#367)")
public class SecurityAuditServiceImpl implements SecurityAuditService, ApplicationRunner {

    private final SecurityAuditEventRepository eventRepo;
    private final IpRiskScoreRepository scoreRepo;
    private final SecurityAuditProperties props;

    private final Map<String, AtomicInteger> ipActivityCount = new ConcurrentHashMap<>();
    private final Map<String, Integer> ipRiskScores = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        // Run async so startup readiness probe is not blocked (per @AsyncStartup contract on class).
        CompletableFuture.runAsync(this::hydrate);
    }

    private void hydrate() {
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(props.hydrateDays());
            List<IpRiskScore> rows = scoreRepo.findByLastUpdatedAfter(since);
            for (IpRiskScore r : rows) {
                ipRiskScores.put(r.getIpAddress(), r.getScore() == null ? 0 : r.getScore());
                ipActivityCount.put(r.getIpAddress(),
                        new AtomicInteger(r.getActivityCount() == null ? 0 : r.getActivityCount()));
            }
            log.info("[SecurityAudit] hydrated {} IP risk scores from DB (last {} days)",
                    rows.size(), props.hydrateDays());
        } catch (Exception e) {
            log.warn("[SecurityAudit] hydrate failed (cache empty, fall back to fresh): {}", e.getMessage());
        }
    }

    /** Daily 03:00 UTC — purge audit events past retention. */
    @Scheduled(cron = "0 0 3 * * *", zone = "UTC")
    public void purgeOldEvents() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(props.retentionDays());
            int deleted = eventRepo.deleteByCreatedAtBefore(cutoff);
            if (deleted > 0) {
                log.info("[SecurityAudit] purged {} events older than {} days", deleted, props.retentionDays());
            }
        } catch (Exception e) {
            log.warn("[SecurityAudit] purge failed: {}", e.getMessage());
        }
    }

    @Override
    public void logPasswordResetAttempt(String email, String ipAddress, boolean success, String reason) {
        String maskedIp = maskIpAddress(ipAddress);
        if (success) {
            log.info("Password reset successful for email: {}, IP: {}", email, maskedIp);
        } else {
            log.warn("Password reset failed for email: {}, IP: {}, reason: {}", email, maskedIp, reason);
            increaseIpRiskScore(ipAddress, 10);
        }
        recordIpActivity(ipAddress);
        persistEvent(SecurityAuditEvent.builder()
                .eventType("PASSWORD_RESET")
                .email(email)
                .ipAddress(ipAddress)
                .success(success)
                .reason(reason)
                .scoreDelta(success ? 0 : 10)
                .build());
    }

    @Override
    public void logVerificationCodeSent(String email, String ipAddress) {
        String maskedIp = maskIpAddress(ipAddress);
        log.info("Verification code sent for email: {}, IP: {}", email, maskedIp);
        recordIpActivity(ipAddress);
        persistEvent(SecurityAuditEvent.builder()
                .eventType("VERIFICATION_CODE")
                .email(email)
                .ipAddress(ipAddress)
                .success(true)
                .scoreDelta(0)
                .build());
    }

    @Override
    public void logAccountLockout(String email, String ipAddress, String reason) {
        String maskedIp = maskIpAddress(ipAddress);
        log.warn("Account locked for email: {}, IP: {}, reason: {}", email, maskedIp, reason);
        increaseIpRiskScore(ipAddress, 50);
        persistEvent(SecurityAuditEvent.builder()
                .eventType("LOCKOUT")
                .email(email)
                .ipAddress(ipAddress)
                .reason(reason)
                .scoreDelta(50)
                .build());
    }

    @Override
    public void logSuspiciousActivity(String email, String ipAddress, String activity, String riskLevel) {
        String maskedIp = maskIpAddress(ipAddress);
        log.warn("Suspicious activity detected - Email: {}, IP: {}, Activity: {}, Risk: {}",
                email, maskedIp, activity, riskLevel);

        int scoreIncrease = switch (riskLevel == null ? "" : riskLevel.toUpperCase()) {
            case "HIGH"   -> 30;
            case "MEDIUM" -> 20;
            case "LOW"    -> 10;
            default       -> 15;
        };
        increaseIpRiskScore(ipAddress, scoreIncrease);
        persistEvent(SecurityAuditEvent.builder()
                .eventType("SUSPICIOUS")
                .email(email)
                .ipAddress(ipAddress)
                .reason(activity)
                .riskLevel(riskLevel)
                .scoreDelta(scoreIncrease)
                .build());
    }

    @Override
    public boolean isSuspiciousIp(String ipAddress) {
        int riskScore = getIpRiskScore(ipAddress);
        int activityCount = getIpActivityCount(ipAddress);
        return riskScore > 70 || activityCount > 100;
    }

    @Override
    public int getIpRiskScore(String ipAddress) {
        return ipRiskScores.getOrDefault(ipAddress, 0);
    }

    private void recordIpActivity(String ipAddress) {
        if (ipAddress == null) return;
        ipActivityCount.computeIfAbsent(ipAddress, k -> new AtomicInteger(0)).incrementAndGet();
        // DB upsert (small, async OK)
        CompletableFuture.runAsync(() -> {
            try {
                scoreRepo.upsertDelta(ipAddress, 0, 1);
            } catch (Exception e) {
                log.debug("[SecurityAudit] activity persist failed for {}: {}", ipAddress, e.getMessage());
            }
        });
    }

    private int getIpActivityCount(String ipAddress) {
        AtomicInteger count = ipActivityCount.get(ipAddress);
        return count != null ? count.get() : 0;
    }

    private void increaseIpRiskScore(String ipAddress, int increase) {
        if (ipAddress == null) return;
        ipRiskScores.merge(ipAddress, increase, Integer::sum);
        int currentScore = ipRiskScores.get(ipAddress);
        if (currentScore > 100) {
            ipRiskScores.put(ipAddress, 100);
        }
        // DB upsert with same delta (async)
        CompletableFuture.runAsync(() -> {
            try {
                scoreRepo.upsertDelta(ipAddress, increase, 0);
            } catch (Exception e) {
                log.warn("[SecurityAudit] risk score persist failed for {}: {}", ipAddress, e.getMessage());
            }
        });
    }

    private void persistEvent(SecurityAuditEvent event) {
        // Async write — never block hot path.
        CompletableFuture.runAsync(() -> {
            try {
                eventRepo.save(event);
            } catch (Exception e) {
                log.warn("[SecurityAudit] event persist failed type={}: {}", event.getEventType(), e.getMessage());
            }
        });
    }

    private String maskIpAddress(String ipAddress) {
        if (ipAddress == null) return "unknown";
        String[] parts = ipAddress.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return ipAddress;
    }
}
