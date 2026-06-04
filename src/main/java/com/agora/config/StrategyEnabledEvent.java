package com.agora.config;

/**
 * Fired from {@code StrategyManagementMcpTools.setStrategyEnabled} whenever a
 * strategy is turned on or off. Consumers (primarily
 * {@code WsSubscriptionSyncer}) react by diffing WS subscriptions.
 *
 * <p>Deliberately minimal payload; listeners can re-query the repository
 * for authoritative state. Using Spring's {@code ApplicationEventPublisher}
 * decouples the MCP layer from the WS subscription layer (avoids the
 * Spring AI circular-dep pattern noted in CLAUDE.md).
 *
 * @param strategyId which strategy changed
 * @param enabled    new enabled state (true = just enabled, false = disabled)
 * @param reason     short human-readable note ({@code enableStrategy notes})
 */
public record StrategyEnabledEvent(Long strategyId, boolean enabled, String reason) { }
