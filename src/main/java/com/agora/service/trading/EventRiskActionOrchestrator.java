package com.agora.service.trading;

import com.agora.config.properties.EventRiskControlProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.model.BtStrategy;
import com.agora.service.trading.EventRiskLevelEngine.RiskLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventRiskActionOrchestrator {

    private final EventRiskLevelEngine riskLevelEngine;
    private final EventRiskControlProperties properties;
    private final NotificationPort notificationPort;

    private final Map<String, LastNotification> lastNotificationBySymbol = new ConcurrentHashMap<>();

    public EntryRiskDecision assessNewEntry(BtStrategy strategy,
                                            Map<String, Object> strategyConfig,
                                            String symbol,
                                            String intervalCode,
                                            String side,
                                            Long liveSignalId) {
        EventRiskLevelEngine.Snapshot snapshot = riskLevelEngine.evaluate(symbol);
        maybeNotifyStateChange(snapshot);

        if (!properties.enabled() || !properties.blockNewEntries()) {
            return EntryRiskDecision.allowed(snapshot, "event-risk-control disabled or observe-only");
        }
        if (!snapshot.level().atLeast(RiskLevel.R2)) {
            return EntryRiskDecision.allowed(snapshot, "risk below R2");
        }

        Long strategyId = strategy != null ? strategy.getId() : null;
        boolean r3 = snapshot.level().atLeast(RiskLevel.R3);
        boolean allowed = r3
                ? isAllowlisted(strategyId, properties.r3AllowlistStrategyIds())
                    || getBoolean(strategyConfig, "eventRiskAllowAtR3", false)
                : isAllowlisted(strategyId, properties.r2AllowlistStrategyIds())
                    || getBoolean(strategyConfig, "eventRiskAllowAtR2", false);
        if (allowed) {
            return EntryRiskDecision.allowed(snapshot, "strategy allowlisted for " + snapshot.level());
        }

        String reason = String.format(Locale.ROOT,
                "EventRiskControl: %s score=%d blocks new %s entries",
                snapshot.level(), snapshot.score(), side == null ? "UNKNOWN" : side);
        return EntryRiskDecision.blocked(snapshot, reason, auditContext(snapshot, strategyId, intervalCode, side, liveSignalId));
    }

    private Map<String, Object> auditContext(EventRiskLevelEngine.Snapshot snapshot,
                                             Long strategyId,
                                             String intervalCode,
                                             String side,
                                             Long liveSignalId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("riskLevel", snapshot.level().name());
        ctx.put("riskScore", snapshot.score());
        ctx.put("riskReasons", snapshot.reasons());
        ctx.put("side", side);
        ctx.put("strategy_id", strategyId);
        ctx.put("interval", intervalCode);
        ctx.put("live_signal_id", liveSignalId);
        ctx.put("riskGeneratedAtUtc", snapshot.generatedAtUtc().toString());
        ctx.put("policy", "block_new_entries_at_R2_R3");
        return ctx;
    }

    private void maybeNotifyStateChange(EventRiskLevelEngine.Snapshot snapshot) {
        if (!properties.enabled()) return;
        String symbol = snapshot.symbol();
        String fingerprint = snapshot.level().name();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LastNotification prev = lastNotificationBySymbol.get(symbol);
        boolean levelChanged = prev == null || !Objects.equals(prev.fingerprint(), fingerprint);
        boolean cooldownElapsed = prev == null
                || prev.sentAt().plusMinutes(properties.statusNotifyCooldownMinutes()).isBefore(now);
        if (!levelChanged || !cooldownElapsed) return;

        lastNotificationBySymbol.put(symbol, new LastNotification(fingerprint, now));
        try {
            notificationPort.broadcast(String.format(
                    "<b>事件風險等級 %s</b> %s\n分數=%d\n原因=%s\n策略=%s",
                    snapshot.level(), symbol, snapshot.score(),
                    snapshot.reasons().isEmpty() ? "無" : String.join(" | ", snapshot.reasons()),
                    snapshot.level().atLeast(RiskLevel.R2) && properties.blockNewEntries()
                            ? "暫停新的自動交易進場，除非該策略在 allowlist"
                            : "允許新的進場評估"), true);
        } catch (Exception e) {
            log.debug("[EventRisk] state-change TG failed: {}", e.getMessage());
        }
    }

    private boolean isAllowlisted(Long strategyId, String csv) {
        if (strategyId == null || csv == null || csv.isBlank()) return false;
        String id = String.valueOf(strategyId);
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .anyMatch(id::equals);
    }

    private boolean getBoolean(Map<String, Object> config, String key, boolean defaultValue) {
        if (config == null || !config.containsKey(key)) return defaultValue;
        Object value = config.get(key);
        if (value instanceof Boolean b) return b;
        if (value instanceof String s) return Boolean.parseBoolean(s.trim());
        return defaultValue;
    }

    private record LastNotification(String fingerprint, LocalDateTime sentAt) {
    }

    public record EntryRiskDecision(
            boolean allowed,
            EventRiskLevelEngine.Snapshot snapshot,
            String reason,
            Map<String, Object> auditContext
    ) {
        public static EntryRiskDecision allowed(EventRiskLevelEngine.Snapshot snapshot, String reason) {
            return new EntryRiskDecision(true, snapshot, reason, Map.of());
        }

        public static EntryRiskDecision blocked(EventRiskLevelEngine.Snapshot snapshot,
                                                String reason,
                                                Map<String, Object> auditContext) {
            return new EntryRiskDecision(false, snapshot, reason, auditContext);
        }
    }
}
