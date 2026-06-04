package com.agora.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Central helper for trading-related Micrometer meters. Single injection point so
 * counter / timer names + tag conventions live in one file and can't drift.
 *
 * <p>Counters are created on demand and cached by MeterRegistry; calling
 * {@code registry.counter(name, tags)} is cheap after the first hit.</p>
 */
@Component
@RequiredArgsConstructor
public class TradingMetrics {

    public static final String OCO_POLL_DURATION  = "trading.oco.poll.duration";
    public static final String SIGNAL_EMIT        = "trading.signal.emit";
    public static final String SIGNAL_FILTERED    = "trading.signal.filtered";
    public static final String ORDER_PLACED       = "trading.order.placed";
    public static final String MCP_TOOL_CALL      = "mcp.tool.call";
    public static final String KLINE_STREAM_LAG   = "market.kline.stream.lag.seconds";

    private final MeterRegistry registry;

    /** Timer around OcoPositionPollerScheduler.pollOcoPositions. */
    public Timer ocoPollTimer() {
        return Timer.builder(OCO_POLL_DURATION)
                .description("OcoPositionPollerScheduler.pollOcoPositions wall time")
                .publishPercentileHistogram()
                .register(registry);
    }

    /** BUY/SELL signal emitted by LiveSignalEvaluator (pre-filter). */
    public void signalEmit(String symbol, String intervalCode, String side) {
        Counter.builder(SIGNAL_EMIT)
                .description("Strategy signal emitted (BUY/SELL) before filters")
                .tag("symbol", nullSafe(symbol))
                .tag("interval", nullSafe(intervalCode))
                .tag("side", nullSafe(side))
                .register(registry)
                .increment();
    }

    /** Signal blocked by a filter (LongAiFilter / ShortAiFilter / DailyLossGuard / ...). */
    public void signalFiltered(String filterName, String reason) {
        Counter.builder(SIGNAL_FILTERED)
                .description("Signal blocked before order placement")
                .tag("filter_name", nullSafe(filterName))
                .tag("reason", truncateReason(reason))
                .register(registry)
                .increment();
    }

    /**
     * Order actually sent to exchange (BUY/SELL/SHORT_OPEN/SHORT_CLOSE).
     * outcome: ok / fail / post_buy_fail
     */
    public void orderPlaced(String symbol, String side, String outcome) {
        Counter.builder(ORDER_PLACED)
                .description("Orders placed on exchange")
                .tag("symbol", nullSafe(symbol))
                .tag("side", nullSafe(side))
                .tag("outcome", nullSafe(outcome))
                .register(registry)
                .increment();
    }

    /** MCP @Tool invocation (recorded by McpToolMetricsAspect). */
    public void mcpToolCall(String toolName, String authLevel, String outcome) {
        Counter.builder(MCP_TOOL_CALL)
                .description("MCP @Tool method invocations")
                .tag("tool_name", nullSafe(toolName))
                .tag("auth_level", nullSafe(authLevel))
                .tag("outcome", nullSafe(outcome))
                .register(registry)
                .increment();
    }

    public MeterRegistry registry() {
        return registry;
    }

    private static String nullSafe(String v) {
        return (v == null || v.isBlank()) ? "unknown" : v;
    }

    /** High-cardinality reasons explode tag space; cap to 60 chars and strip variable bits. */
    private static String truncateReason(String reason) {
        if (reason == null || reason.isBlank()) return "unknown";
        String trimmed = reason.strip();
        if (trimmed.length() > 60) trimmed = trimmed.substring(0, 60);
        return trimmed;
    }
}
