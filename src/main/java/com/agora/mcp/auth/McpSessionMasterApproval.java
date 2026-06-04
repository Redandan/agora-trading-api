package com.agora.mcp.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpSessionMasterApproval {

    private final Environment env;

    private final Map<String, PendingApproval> pendingById = new ConcurrentHashMap<>();
    private final Map<String, ApprovedScope> approvedScopesBySession = new ConcurrentHashMap<>();

    public McpSessionMasterApproval(Environment env) {
        this.env = env;
    }

    public boolean isEnabled() {
        return Boolean.parseBoolean(env.getProperty("mcp.master-approval.enabled", "true"));
    }

    public boolean isApproved(HttpServletRequest request) {
        return isApproved(request, null);
    }

    public boolean isApproved(HttpServletRequest request, String toolName) {
        if (!isEnabled()) {
            return false;
        }

        cleanup();

        String sessionHash = fingerprint(request, toolName);
        ApprovedScope scope = approvedScopesBySession.get(sessionHash);
        if (scope == null || Instant.now().isAfter(scope.until)) {
            return false;
        }
        if (toolName == null || toolName.isBlank()) {
            return true;
        }
        return scope.allowedTools.contains(toolName.trim());
    }

    public boolean isApprovedForRequestedTools(HttpServletRequest request, Set<String> requestedTools) {
        if (requestedTools == null || requestedTools.isEmpty()) {
            return false;
        }
        for (String tool : requestedTools) {
            if (tool == null || tool.isBlank()) {
                continue;
            }
            if (!isApproved(request, tool.trim())) {
                return false;
            }
        }
        return true;
    }

    public Instant getApprovalExpiresAt(HttpServletRequest request, String toolName) {
        if (!isEnabled()) {
            return null;
        }
        cleanup();
        String sessionHash = fingerprint(request, toolName);
        ApprovedScope scope = approvedScopesBySession.get(sessionHash);
        if (scope == null || Instant.now().isAfter(scope.until)) {
            return null;
        }
        return scope.until;
    }

    public PendingApproval createOrReusePending(HttpServletRequest request, String toolName) {
        return createOrReusePending(request, toolName, Set.of(toolName));
    }

    public PendingApproval createOrReusePending(HttpServletRequest request, String toolName, Set<String> requestedTools) {
        cleanup();

        String sessionHash = fingerprint(request, toolName);
        Set<String> normalizedTools = normalizeRequestedTools(requestedTools, toolName);
        if (normalizedTools.isEmpty()) {
            throw new IllegalArgumentException("requestedTools must not be empty");
        }

        for (PendingApproval p : pendingById.values()) {
            if (p.sessionHash.equals(sessionHash) && p.isPending() && p.requestedTools.equals(normalizedTools)) {
                return p;
            }
        }

        String id = "mcp_" + UUID.randomUUID().toString().replace("-", "");

        PendingApproval p = new PendingApproval(
                id,
                sessionHash,
                shortHash(sessionHash),
                toolName,
                normalizedTools,
                Instant.now(),
                Instant.now().plusSeconds(pendingTtlSeconds())
        );

        pendingById.put(id, p);
        return p;
    }

    public boolean approve(String grantRequestId) {
        return approveWithBatch(grantRequestId).approvedCount > 0;
    }

    public ApprovalResult approveWithBatch(String grantRequestId) {
        PendingApproval target = pendingById.get(grantRequestId);
        if (target == null) {
            return ApprovalResult.notFound();
        }
        if (!target.isPending()) {
            return ApprovalResult.failed(status(grantRequestId));
        }

        Instant now = Instant.now();
        Instant until = now.plusSeconds(approvalTtlSeconds());
        approvedScopesBySession.put(target.sessionHash, new ApprovedScope(until, target.requestedTools));

        int approvedCount = 0;
        for (PendingApproval p : pendingById.values()) {
            if (!target.sessionHash.equals(p.sessionHash)
                    || !p.isPending()
                    || !target.requestedTools.equals(p.requestedTools)) {
                continue;
            }
            p.status = "APPROVED";
            p.resolvedAt = now;
            approvedCount++;
        }
        return ApprovalResult.approved(target.sessionShortHash, until, approvedCount);
    }

    public boolean reject(String grantRequestId) {
        PendingApproval p = pendingById.get(grantRequestId);

        if (p == null || !p.isPending()) {
            return false;
        }

        p.status = "REJECTED";
        p.resolvedAt = Instant.now();
        return true;
    }

    public String status(String grantRequestId) {
        PendingApproval p = pendingById.get(grantRequestId);
        if (p == null) {
            return "NOT_FOUND";
        }
        if ("PENDING".equals(p.status) && Instant.now().isAfter(p.expiresAt)) {
            return "EXPIRED";
        }
        return p.status;
    }

    public boolean markTelegramPrompted(String grantRequestId) {
        PendingApproval pending = pendingById.get(grantRequestId);
        if (pending == null || !pending.isPending()) {
            return false;
        }
        return pending.markTelegramPrompted();
    }

    public String sessionShortHash(HttpServletRequest request) {
        return sessionShortHash(request, null);
    }

    public String sessionShortHash(HttpServletRequest request, String toolName) {
        return shortHash(fingerprint(request, toolName));
    }

    private void cleanup() {
        Instant now = Instant.now();

        pendingById.values().removeIf(p ->
                ("PENDING".equals(p.status) && now.isAfter(p.expiresAt))
                        || (!"PENDING".equals(p.status)
                        && p.resolvedAt != null
                        && now.isAfter(p.resolvedAt.plusSeconds(resolvedRetentionSeconds())))
        );

        approvedScopesBySession.entrySet().removeIf(e -> now.isAfter(e.getValue().until));
    }

    private long pendingTtlSeconds() {
        return Long.parseLong(env.getProperty("mcp.master-approval.pending-ttl-seconds", "300"));
    }

    private long approvalTtlSeconds() {
        return Long.parseLong(env.getProperty("mcp.master-approval.approval-ttl-seconds", "3600"));
    }

    public long getApprovalTtlSeconds() {
        return approvalTtlSeconds();
    }

    private long resolvedRetentionSeconds() {
        return Long.parseLong(env.getProperty("mcp.master-approval.resolved-retention-seconds", "86400"));
    }

    private String fingerprint(HttpServletRequest request) {
        return fingerprint(request, null);
    }

    private String fingerprint(HttpServletRequest request, String toolName) {
        String pepper = env.getProperty("mcp.session-fingerprint-pepper", "change-me");

        if (!hasStableSessionHeaders(request)) {
            String raw = String.join("|",
                    "fallback-public-mcp",
                    "SESSION_BATCH"
            );
            return sha256(pepper + "|" + raw);
        }

        String raw = String.join("|",
                safe(request.getHeader("X-MCP-Session-Id")),
                safe(request.getHeader("X-OpenAI-Conversation-Id")),
                safe(request.getHeader("X-OpenAI-Connector-Id")),
                safe(clientIp(request)),
                safe(request.getHeader("User-Agent")),
                safe(request.getRemoteAddr())
        );

        return sha256(pepper + "|" + raw);
    }

    private boolean hasStableSessionHeaders(HttpServletRequest request) {
        return hasText(request.getHeader("X-MCP-Session-Id"))
                || hasText(request.getHeader("X-OpenAI-Conversation-Id"))
                || hasText(request.getHeader("X-OpenAI-Connector-Id"));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Set<String> normalizeRequestedTools(Set<String> requestedTools, String fallbackTool) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (requestedTools != null) {
            for (String tool : requestedTools) {
                if (hasText(tool)) {
                    normalized.add(tool.trim());
                }
            }
        }
        if (normalized.isEmpty() && hasText(fallbackTool)) {
            normalized.add(fallbackTool.trim());
        }
        return Set.copyOf(normalized);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            return comma >= 0 ? forwardedFor.substring(0, comma).trim() : forwardedFor.trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String cfConnectingIp = request.getHeader("CF-Connecting-IP");
        if (cfConnectingIp != null && !cfConnectingIp.isBlank()) {
            return cfConnectingIp.trim();
        }
        return request.getRemoteAddr();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    private String shortHash(String hash) {
        return hash == null || hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public static class PendingApproval {
        public final String grantRequestId;
        public final String sessionHash;
        public final String sessionShortHash;
        public final String firstToolName;
        public final Set<String> requestedTools;
        public final Instant createdAt;
        public final Instant expiresAt;
        public String status = "PENDING";
        public Instant resolvedAt;
        private volatile boolean telegramPrompted;

        public PendingApproval(
                String grantRequestId,
                String sessionHash,
                String sessionShortHash,
                String firstToolName,
                Set<String> requestedTools,
                Instant createdAt,
                Instant expiresAt
        ) {
            this.grantRequestId = grantRequestId;
            this.sessionHash = sessionHash;
            this.sessionShortHash = sessionShortHash;
            this.firstToolName = firstToolName;
            this.requestedTools = requestedTools;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        public boolean isPending() {
            return "PENDING".equals(status) && Instant.now().isBefore(expiresAt);
        }

        public boolean markTelegramPrompted() {
            if (telegramPrompted) {
                return false;
            }
            synchronized (this) {
                if (telegramPrompted) {
                    return false;
                }
                telegramPrompted = true;
                return true;
            }
        }
    }

    public static class ApprovalResult {
        public final boolean approved;
        public final String sessionShortHash;
        public final Instant approvedUntil;
        public final int approvedCount;
        public final String failureReason;

        private ApprovalResult(boolean approved, String sessionShortHash, Instant approvedUntil, int approvedCount,
                               String failureReason) {
            this.approved = approved;
            this.sessionShortHash = sessionShortHash;
            this.approvedUntil = approvedUntil;
            this.approvedCount = approvedCount;
            this.failureReason = failureReason;
        }

        public static ApprovalResult notFound() {
            return failed("NOT_FOUND");
        }

        public static ApprovalResult failed(String reason) {
            return new ApprovalResult(false, null, null, 0, reason);
        }

        public static ApprovalResult approved(String sessionShortHash, Instant approvedUntil, int approvedCount) {
            return new ApprovalResult(true, sessionShortHash, approvedUntil, approvedCount, null);
        }
    }

    private static class ApprovedScope {
        private final Instant until;
        private final Set<String> allowedTools;

        private ApprovedScope(Instant until, Set<String> allowedTools) {
            this.until = until;
            this.allowedTools = allowedTools;
        }
    }
}
