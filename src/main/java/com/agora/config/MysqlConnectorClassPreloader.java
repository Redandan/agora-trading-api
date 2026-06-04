package com.agora.config;

import com.agora.infra.notification.NotificationPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * #430 — Force-load MySQL Connector lazy classes at application startup so they
 * are resident before any blue/green drain begins.
 *
 * <p>Background — recurring deploy-drain race (2026-04-25 / 04-26 / 04-27 / 05-04):
 * MySQL Connector lazily loads internal classes (anonymous inner classes,
 * SSL helpers, value encoders) on first use of a given code path. When the
 * Spring Boot {@code LaunchedClassLoader} starts closing during graceful
 * shutdown (deploy drain), any thread that hits a never-before-loaded
 * MySQL class fails with {@code NoClassDefFoundError}. Two distinct
 * symptoms have been observed in production:
 *
 * <ul>
 *   <li>{@code com.mysql.cj.protocol.a.LocalDateValueEncoder$1} —
 *       triggered by {@link java.time.LocalDate} JDBC bind on the dying JVM.
 *       Surfaced today (2026-05-04 09:11:51 UTC) when DailyMlPipelineDigest
 *       cron collided with the #428 deploy drain.</li>
 *   <li>{@code com.mysql.cj.protocol.ExportControlled} — referenced by
 *       {@code NetworkResources.forceClose}. Recurs every time HikariPool's
 *       connection-closer thread runs during shutdown if the SSL/TLS cleanup
 *       class has not been needed yet on this JVM. Seen 4/25, 4/26, 4/27.</li>
 * </ul>
 *
 * <p>Both symptoms share a single root cause and a single fix:
 * trigger the lazy load ourselves, while the application is healthy and
 * the classloader is fully open. Once loaded, the JVM keeps the class
 * resident until the loader itself is GC'd — by which point the JVM is
 * about to die anyway, and OS-level TCP cleanup handles any straggler
 * connections.
 *
 * <p>The preload is best-effort: every step is wrapped in try/catch and
 * logged at WARN. A startup failure of this preloader must NEVER prevent
 * the application from coming up — it only weakens shutdown hygiene on
 * the next deploy, not steady-state correctness.
 *
 * @see com.agora.service.ml.MlPipelineDigestService persistDailyProgress
 *      where the {@code java.sql.Date} substitution removes the need for
 *      LocalDate lazy load on the most-affected scheduled job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MysqlConnectorClassPreloader {

    /**
     * Classes whose name we know upfront — load by reflection at startup.
     * Public so {@link com.agora.scheduler.system.MysqlConnectorHealthCheckScheduler}
     * can re-verify on a daily cadence.
     */
    public static final List<String> KNOWN_LAZY_CLASSES = List.of(
            "com.mysql.cj.protocol.ExportControlled",
            "com.mysql.cj.protocol.a.LocalDateValueEncoder",
            "com.mysql.cj.protocol.a.LocalDateValueEncoder$1",
            "com.mysql.cj.protocol.a.LocalDateTimeValueEncoder",
            "com.mysql.cj.protocol.a.LocalTimeValueEncoder"
    );

    private final JdbcTemplate jdbc;
    private final NotificationPort notificationPort;

    @EventListener(ApplicationReadyEvent.class)
    public void preload() {
        ClassLoader loader = getClass().getClassLoader();
        List<String> loaded = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        for (String name : KNOWN_LAZY_CLASSES) {
            try {
                Class.forName(name, true, loader);
                loaded.add(name);
            } catch (Throwable t) {
                missing.add(name + " (" + t.getClass().getSimpleName() + ")");
            }
        }

        // Exercise a real LocalDate bind so any encoder-internal lazy hooks
        // (lambda metafactories, MethodHandle resolution, etc.) also resolve now.
        String bindError = null;
        try {
            jdbc.queryForObject("SELECT 1 WHERE ? = ?", Integer.class,
                    LocalDate.now(), LocalDate.now());
        } catch (Throwable t) {
            bindError = t.getClass().getSimpleName() + ": " + t.getMessage();
            log.warn("[MysqlConnectorClassPreloader] LocalDate bind warm-up failed " +
                    "(non-fatal — Class.forName above usually suffices): {}", t.getMessage());
        }

        log.info("[MysqlConnectorClassPreloader] preloaded {} class(es); missing {}: {}",
                loaded.size(),
                missing.size(),
                missing.isEmpty() ? "none" : missing);

        // #430 — fire CRITICAL TG if anything failed at startup. Otherwise the
        // failure stays buried in error.log and only surfaces months later when
        // the next blue/green drain triggers a NoClassDefFoundError. We want to
        // know within seconds of boot, not weeks of running broken.
        if (!missing.isEmpty() || bindError != null) {
            try {
                StringBuilder msg = new StringBuilder(
                        "🚨 <b>MySQL Connector preload failed at startup</b>\n");
                if (!missing.isEmpty()) {
                    msg.append("Missing classes:\n");
                    for (String m : missing) msg.append("  • ").append(m).append('\n');
                }
                if (bindError != null) {
                    msg.append("LocalDate JDBC bind smoke test: ").append(bindError);
                }
                msg.append("\nNext blue/green drain may surface NoClassDefFoundError. " +
                        "Investigate jar packaging or KNOWN_LAZY_CLASSES drift.");
                notificationPort.broadcast(msg.toString(), true);
            } catch (Throwable t) {
                log.error("[MysqlConnectorClassPreloader] failed to send alert TG: {}",
                        t.getMessage());
            }
        }
    }
}
