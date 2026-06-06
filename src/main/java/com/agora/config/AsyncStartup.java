package com.agora.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * #361 — Marker annotation for {@code ApplicationRunner} / {@code CommandLineRunner}
 * implementations that explicitly dispatch their work asynchronously, NOT blocking
 * Spring Boot's {@code ReadinessState} during startup.
 *
 * <p>Spring Boot's blue-green deploy health check (via {@code /actuator/health})
 * waits for {@code ReadinessState.ACCEPTING_TRAFFIC}. If an {@code ApplicationRunner}
 * runs synchronously and takes &gt; 30s, the readiness probe fails and deploy.sh
 * times out at 240s. Past incidents:
 * <ul>
 *   <li>{@code CompositeIndicatorBackfillRunner} — synchronous backfill froze deploy</li>
 *   <li>Legacy SQI startup backfill — JPA Metaspace exhaustion via too many distinct queries</li>
 * </ul>
 *
 * <p><b>Compliance options for ApplicationRunner classes:</b>
 * <ol>
 *   <li>{@code @AsyncStartup} marker — first line of {@code run()} dispatches via
 *       {@code CompletableFuture.runAsync()} / {@code new Thread()} / {@code @Async}</li>
 *   <li>{@code @ConditionalOnProperty(..., havingValue = "true", matchIfMissing = false)} —
 *       runner is opt-in (manual trigger only, default disabled)</li>
 * </ol>
 *
 * <p>Enforced by {@code ApplicationRunnerArchTest} at compile time. Runtime status
 * tracked by {@code StartupBudgetWatcher}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface AsyncStartup {
    /** Brief reason / description (optional, shown in startup log). */
    String value() default "";
}
