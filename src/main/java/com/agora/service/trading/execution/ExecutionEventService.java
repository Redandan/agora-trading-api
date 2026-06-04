package com.agora.service.trading.execution;

import com.agora.enums.trading.ExecutionActionBoundary;
import com.agora.enums.trading.ExecutionEventSeverity;
import com.agora.enums.trading.ExecutionEventSource;
import com.agora.enums.trading.ExecutionEventStatus;
import com.agora.enums.trading.ExecutionEventType;
import com.agora.enums.trading.ExecutionRecommendation;
import com.agora.model.ExecutionEvent;
import com.agora.repository.trading.ExecutionEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ExecutionEventService {

    private final ExecutionEventRepository repository;

    public record Draft(
            ExecutionEventSource source,
            ExecutionEventType type,
            ExecutionEventSeverity severity,
            ExecutionRecommendation recommendation,
            ExecutionActionBoundary actionBoundary,
            String symbol,
            Long positionId,
            Long strategyId,
            String intervalCode,
            String title,
            String summary,
            String evidenceJson,
            String fingerprint,
            LocalDateTime detectedAt,
            LocalDateTime expiresAt
    ) {
    }

    @Transactional
    public synchronized ExecutionEvent upsert(Draft draft) {
        validate(draft);
        LocalDateTime now = LocalDateTime.now();
        String normalizedSymbol = normalizeSymbol(draft.symbol());
        String fingerprint = normalizeFingerprint(draft);

        ExecutionEvent event = repository.findByFingerprint(fingerprint).orElseGet(ExecutionEvent::new);
        boolean preserveHumanTerminalStatus = isHumanTerminalStatus(event.getStatus());
        event.setSource(draft.source());
        event.setType(draft.type());
        event.setSeverity(draft.severity());
        event.setRecommendation(draft.recommendation());
        event.setActionBoundary(draft.actionBoundary());
        event.setStatus(preserveHumanTerminalStatus ? event.getStatus() : ExecutionEventStatus.ACTIVE);
        event.setSymbol(normalizedSymbol);
        event.setPositionId(draft.positionId());
        event.setStrategyId(draft.strategyId());
        event.setIntervalCode(normalizeNullable(draft.intervalCode()));
        event.setTitle(draft.title().trim());
        event.setSummary(draft.summary().trim());
        event.setEvidenceJson(normalizeNullable(draft.evidenceJson()));
        event.setFingerprint(fingerprint);
        if (!preserveHumanTerminalStatus) {
            event.setDetectedAt(draft.detectedAt() == null ? now : draft.detectedAt());
        }
        event.setUpdatedAt(now);
        event.setExpiresAt(draft.expiresAt());
        if (!preserveHumanTerminalStatus) {
            event.setAcknowledgedAt(null);
            event.setResolvedAt(null);
        }
        return repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<ExecutionEvent> listActive(String symbol, Long positionId, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return repository.findActive(
                ExecutionEventStatus.ACTIVE,
                normalizeNullableSymbol(symbol),
                positionId,
                LocalDateTime.now(),
                PageRequest.of(0, size));
    }

    @Transactional
    public int expireStale(LocalDateTime now) {
        return repository.expireStale(
                now == null ? LocalDateTime.now() : now,
                ExecutionEventStatus.ACTIVE,
                ExecutionEventStatus.EXPIRED);
    }

    @Transactional
    public ExecutionEvent markStatus(Long eventId, ExecutionEventStatus status) {
        if (eventId == null) throw new IllegalArgumentException("eventId is required");
        if (status == null || status == ExecutionEventStatus.ACTIVE) {
            throw new IllegalArgumentException("terminal status is required");
        }
        ExecutionEvent event = repository.findById(eventId)
                .orElseThrow(() -> new IllegalArgumentException("execution event not found: " + eventId));
        LocalDateTime now = LocalDateTime.now();
        event.setStatus(status);
        event.setUpdatedAt(now);
        if (status == ExecutionEventStatus.ACKED || status == ExecutionEventStatus.IGNORED) {
            event.setAcknowledgedAt(now);
        }
        if (status == ExecutionEventStatus.RESOLVED || status == ExecutionEventStatus.EXPIRED) {
            event.setResolvedAt(now);
        }
        return repository.save(event);
    }

    public static String fingerprint(ExecutionEventSource source, ExecutionEventType type,
                                     String symbol, Long positionId, String discriminator) {
        String raw = "%s|%s|%s|%s|%s".formatted(
                source,
                type,
                normalizeNullableSymbol(symbol),
                positionId == null ? "ALL" : positionId,
                discriminator == null ? "" : discriminator.trim());
        return sha256(raw);
    }

    private static void validate(Draft draft) {
        if (draft == null) throw new IllegalArgumentException("draft is required");
        if (draft.source() == null) throw new IllegalArgumentException("source is required");
        if (draft.type() == null) throw new IllegalArgumentException("type is required");
        if (draft.severity() == null) throw new IllegalArgumentException("severity is required");
        if (draft.recommendation() == null) throw new IllegalArgumentException("recommendation is required");
        if (draft.actionBoundary() == null) throw new IllegalArgumentException("actionBoundary is required");
        if (draft.symbol() == null || draft.symbol().isBlank()) throw new IllegalArgumentException("symbol is required");
        if (draft.title() == null || draft.title().isBlank()) throw new IllegalArgumentException("title is required");
        if (draft.summary() == null || draft.summary().isBlank()) throw new IllegalArgumentException("summary is required");
    }

    private static String normalizeFingerprint(Draft draft) {
        if (draft.fingerprint() != null && !draft.fingerprint().isBlank()) {
            return draft.fingerprint().trim().toLowerCase(Locale.ROOT);
        }
        return fingerprint(draft.source(), draft.type(), draft.symbol(), draft.positionId(), draft.title());
    }

    private static boolean isHumanTerminalStatus(ExecutionEventStatus status) {
        return status == ExecutionEventStatus.ACKED
                || status == ExecutionEventStatus.IGNORED
                || status == ExecutionEventStatus.RESOLVED;
    }

    private static String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeNullableSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        return normalizeSymbol(symbol);
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
