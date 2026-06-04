package com.agora.service.ai.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI Task Router — 依 task type 路由到對應 {@link AiProvider},失敗時自動 fallback。
 *
 * <h3>Routing 邏輯</h3>
 * <ol>
 *   <li>從 application.yml `ai.routing.<task>.primary` 找 primary provider</li>
 *   <li>若 primary 不健康 / 不存在 → 立即跳 fallback</li>
 *   <li>若 primary execute throw 且 retryable → 跳 fallback chain 第 1 個</li>
 *   <li>所有 fallback 都失敗 → throw 給 caller(由 caller 決定如何處理,通常降級為 no-op)</li>
 * </ol>
 *
 * <h3>Phase 1 限制</h3>
 * 沒做 budget guard / cost-aware routing(Phase 2);沒做 multi-agent council(Phase 3)。
 * 純粹是 primary + linear fallback chain。
 */
@Slf4j
@Component
public class AiTaskRouter {

    private final Map<String, AiProvider> providersByName;
    private final RoutingConfig routingConfig;
    private final AiUsageTracker usageTracker;
    private final Map<String, Instant> providerCooldownUntil = new ConcurrentHashMap<>();
    private final Map<String, Instant> nonCoreDegradedWarnUntil = new ConcurrentHashMap<>();
    private final Duration rateLimitCooldown = Duration.ofMinutes(5);

    public AiTaskRouter(List<AiProvider> providers, RoutingConfig routingConfig, AiUsageTracker usageTracker) {
        this.providersByName = new HashMap<>();
        for (AiProvider p : providers) {
            providersByName.put(p.name(), p);
        }
        this.routingConfig = routingConfig;
        this.usageTracker = usageTracker;
        log.info("[AiTaskRouter] initialized with {} providers: {}",
                providersByName.size(), providersByName.keySet());
    }

    /**
     * Execute task with primary + fallback chain.
     *
     * @throws AllProvidersFailedException 若所有 provider 都失敗
     */
    public AiResponse execute(AiTask task) {
        TaskRoute route = routingConfig.routeFor(task.type());
        List<AiProvider> chain = buildProviderChain(route);
        if (chain.isEmpty()) {
            throw new AllProvidersFailedException(
                    "no provider available for task type: " + task.type());
        }

        Throwable lastError = null;
        for (AiProvider p : chain) {
            Instant now = Instant.now();
            Instant cooldownUntil = providerCooldownUntil.get(p.name());
            if (cooldownUntil != null && cooldownUntil.isAfter(now)) {
                String key = "provider:" + p.name() + ":" + task.type();
                if (!isNonCoreDegradable(task) || shouldLogNonCoreDegradedWarn(key, now, cooldownUntil)) {
                    log.warn("[AiTaskRouter] AI_PROVIDER_DEGRADED provider {} cooldown active until {} for task={} "
                                    + "(nonCore={}, suppressedUntil={})",
                            p.name(), cooldownUntil, task.type(), isNonCoreDegradable(task),
                            nonCoreDegradedWarnUntil.get(key));
                } else {
                    log.debug("[AiTaskRouter] AI_PROVIDER_DEGRADED provider {} cooldown active until {} for non-core task={} "
                                    + "(suppressed duplicate WARN)",
                            p.name(), cooldownUntil, task.type());
                }
                continue;
            }
            if (!p.healthy()) {
                log.warn("[AiTaskRouter] skip unhealthy provider: {}", p.name());
                continue;
            }
            if (usageTracker.isOverBudget(p.name(), p.model())) {
                log.warn("[AiTaskRouter] {} daily budget reached, skipping to fallback", p.name());
                continue;
            }
            try {
                AiResponse resp = p.execute(task);
                if (chain.indexOf(p) > 0) {
                    log.warn("[AiTaskRouter] task={} fell back to {} (primary failed)",
                            task.type(), p.name());
                }
                try { usageTracker.record(resp); } catch (Exception ignored) {}
                return resp;
            } catch (AiProvider.AiProviderException e) {
                lastError = e;
                boolean rateLimited = AiRetryClassifier.isRateLimit(e);
                if (rateLimited) {
                    now = Instant.now();
                    Instant until = now.plus(rateLimitCooldown);
                    providerCooldownUntil.put(p.name(), until);
                    String key = "provider:" + p.name() + ":" + task.type();
                    if (!isNonCoreDegradable(task) || shouldLogNonCoreDegradedWarn(key, now, until)) {
                        log.warn("[AiTaskRouter] AI_PROVIDER_DEGRADED provider {} rate-limited for task {}: {} "
                                        + "(cooldownUntil={}, retryable={}, nonCore={})",
                                p.name(), task.type(), e.getMessage(), until, e.isRetryable(),
                                isNonCoreDegradable(task));
                    } else {
                        log.debug("[AiTaskRouter] AI_PROVIDER_DEGRADED provider {} rate-limited for non-core task {}: {} "
                                        + "(cooldownUntil={}, suppressed duplicate WARN)",
                                p.name(), task.type(), e.getMessage(), until);
                    }
                } else {
                    log.warn("[AiTaskRouter] provider {} failed for task {}: {} (retryable={})",
                            p.name(), task.type(), e.getMessage(), e.isRetryable());
                }
                if (!e.isRetryable()) {
                    // 非 retryable 錯誤(4xx)直接終止 — 不要遮蓋 client 邏輯錯
                    throw new AllProvidersFailedException(
                            "non-retryable error from " + p.name() + ": " + e.getMessage(), e);
                }
                // retryable → 試下一個 provider
            } catch (Throwable t) {
                lastError = t;
                log.error("[AiTaskRouter] provider {} unexpected error: {}", p.name(), t.getMessage(), t);
            }
        }

        if (isNonCoreDegradable(task)) {
            Instant now = Instant.now();
            String key = "exhausted:" + task.type();
            if (shouldLogNonCoreDegradedWarn(key, now, now.plus(rateLimitCooldown))) {
                log.warn("[AiTaskRouter] AI_PROVIDER_DEGRADED non-core task={} all providers exhausted; "
                                + "returning graceful empty response. Core checkout/trading health unaffected. cause={}",
                        task.type(), lastError == null ? "N/A" : lastError.getMessage());
            } else {
                log.debug("[AiTaskRouter] AI_PROVIDER_DEGRADED non-core task={} all providers exhausted; "
                                + "returning graceful empty response with duplicate WARN suppressed. cause={}",
                        task.type(), lastError == null ? "N/A" : lastError.getMessage());
            }
            return new AiResponse("", "degraded", task.type() + ":degraded",
                    0, 0, BigDecimal.ZERO, Duration.ZERO);
        }

        throw new AllProvidersFailedException(
                "all providers exhausted for task: " + task.type(), lastError);
    }

    /** 取得當前所有 provider 狀態(供 MCP listAiProviders 用)。 */
    public Map<String, AiProvider> getProviders() {
        return new LinkedHashMap<>(providersByName);
    }

    public RoutingConfig getRoutingConfig() {
        return routingConfig;
    }

    private List<AiProvider> buildProviderChain(TaskRoute route) {
        List<AiProvider> chain = new java.util.ArrayList<>();
        if (route != null) {
            AiProvider primary = providersByName.get(route.primary);
            if (primary != null) chain.add(primary);
            if (route.fallback != null) {
                for (String name : route.fallback) {
                    AiProvider fb = providersByName.get(name);
                    if (fb != null && !chain.contains(fb)) chain.add(fb);
                }
            }
        }
        // 若 config 完全沒設定,fallback 到任何健康 provider(避免完全 dead)
        if (chain.isEmpty()) {
            for (AiProvider p : providersByName.values()) {
                if (p.healthy()) chain.add(p);
            }
        }
        return chain;
    }

    private boolean isNonCoreDegradable(AiTask task) {
        return task != null && "market-advisor-persona".equals(task.type());
    }

    private boolean shouldLogNonCoreDegradedWarn(String key, Instant now, Instant suppressUntil) {
        Instant previousUntil = nonCoreDegradedWarnUntil.get(key);
        if (previousUntil != null && previousUntil.isAfter(now)) {
            return false;
        }
        Instant until = suppressUntil != null && suppressUntil.isAfter(now)
                ? suppressUntil
                : now.plus(rateLimitCooldown);
        nonCoreDegradedWarnUntil.put(key, until);
        return true;
    }

    // ========================================================================
    // Configuration
    // ========================================================================

    /**
     * `ai.routing.<task-type>.primary / fallback` 對映。
     * Bind via {@link ConfigurationProperties} so config reload 也可動態切換。
     */
    @Data
    @Configuration
    @ConfigurationProperties(prefix = "ai")
    public static class RoutingConfig {
        /** key = task type(annotate-trade / reason-on-signal / ...) */
        private Map<String, TaskRoute> routing = new HashMap<>();

        public TaskRoute routeFor(String taskType) {
            return routing.get(taskType);
        }
    }

    @Data
    public static class TaskRoute {
        private String primary;
        private List<String> fallback;
    }

    // ========================================================================
    // Exception
    // ========================================================================

    public static class AllProvidersFailedException extends RuntimeException {
        public AllProvidersFailedException(String message) { super(message); }
        public AllProvidersFailedException(String message, Throwable cause) { super(message, cause); }
    }
}
