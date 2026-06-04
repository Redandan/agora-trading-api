package com.agora.config.properties;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Server-Sent Events config (5 keys, 1 class: SSEService).
 *
 * <p>Nested records map sub-prefixes {@code connection.*}, {@code heartbeat.*},
 * {@code cleanup.*}, {@code max.*}.
 */
@Validated
@ConfigurationProperties(prefix = "sse")
public record SseProperties(
        @DefaultValue Connection connection,
        @DefaultValue Heartbeat heartbeat,
        @DefaultValue Cleanup cleanup,
        @DefaultValue Max max
) {
    public record Connection(@DefaultValue("1800000") @Positive long timeout) {}
    public record Heartbeat(@DefaultValue("30000") @Positive long interval) {}
    public record Cleanup(@DefaultValue("60000") @Positive long interval) {}
    public record Max(@DefaultValue("1000") @Positive int connections, @DefaultValue("5") @Positive int perUser) {}
}
