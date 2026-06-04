package com.agora.service;

import com.agora.repository.system.TgNotificationLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * #362 — Centralised TG notification deduper.
 *
 * <p>Catches the recurring "same alert N times in a row" bug pattern (issues
 * #312/#313/#318/#319/#330/#331 — 5 dedup bugs in a single week).
 *
 * <p>Each call site provides a stable {@code key} unique per logical event,
 * a {@code ttl} after which the same key may fire again, and a severity
 * level that controls overrides:
 *
 * <pre>{@code
 * String key = "AttentionRule:" + ruleId + ":" + bucketHour;
 * if (deduper.shouldSend(key, Duration.ofHours(1), Severity.WARN)) {
 *     telegramService.sendAlert(msg, true, key, "WARN"); // pass key as source
 * }
 * }</pre>
 *
 * <p>The {@code key} MUST also be passed to
 * {@link TelegramService#sendAlert(String, boolean, String, String) sendAlert}
 * as the {@code source} argument. {@code TelegramServiceImpl} writes a
 * {@code tg_notification_log} row with that source, which is how the deduper
 * survives restarts: on cache miss, it queries the log table for a recent
 * row matching {@code source = key}.
 *
 * <p>Severity behaviour:
 * <ul>
 *   <li>{@code CRIT} — always sends (bypass dedup), but still records timestamp</li>
 *   <li>{@code WARN} — standard TTL dedup</li>
 *   <li>{@code FYI}  — same as WARN but wider default cap; caller picks ttl</li>
 * </ul>
 *
 * <p><b>Race-safe</b>: uses {@link ConcurrentHashMap#compute} so the
 * cache-check + DB-fallback + put-or-return decision happens atomically per
 * key, even under high concurrency.
 *
 * <p><b>Restart-safe</b>: first call after a restart for a given key triggers
 * a single {@code findLatestSentAtBySourceAfter} query. If a recent matching
 * log row exists, the cache is warmed with that timestamp and the send is
 * suppressed — closing the regression window that previously caused
 * #330 ("weekly cron but actually 4h/次 after deploy churn").
 *
 * <p><b>Drift mode</b>: if {@code TelegramServiceImpl.logAsync} silently
 * fails to write a row (it catches all exceptions), in-memory cache and DB
 * can drift. Subsequent restart loses cache and DB has no record → that
 * specific event MAY re-fire. This is rare and acceptable.
 *
 * <p>Periodic cleanup (every 6h) drops in-memory keys older than 7 days.
 */
@Slf4j
@Component
public class TgNotificationDeduper {

    public enum Severity {
        /** Always send (e.g. account-level catastrophic loss); bypass dedup. */
        CRIT,
        /** Standard dedup with caller-specified TTL. */
        WARN,
        /** Informational; long TTL recommended. */
        FYI
    }

    private static final Duration STALE_AFTER = Duration.ofDays(7);
    /** #401 — pause between DB warm-up retry attempts (one retry total). */
    private static final long WARMUP_RETRY_BACKOFF_MS = 50L;
    /** #401 — Micrometer counter name for warm-up failures. */
    static final String METRIC_WARMUP_FAIL = "tg_dedup.warmup.fail";

    /** Map<dedupKey, lastSentAt>. Lazy-populated; warmed from DB on cache miss. */
    private final Map<String, LocalDateTime> lastSentAt = new ConcurrentHashMap<>();

    /** Optional — null in unit tests with no Spring context. */
    private final TgNotificationLogRepository logRepo;

    /** #401 — optional MeterRegistry; null when not running under Spring. */
    private final MeterRegistry meterRegistry;

    @Autowired
    public TgNotificationDeduper(TgNotificationLogRepository logRepo,
                                 @Autowired(required = false) MeterRegistry meterRegistry) {
        this.logRepo = logRepo;
        this.meterRegistry = meterRegistry;
    }

    /** Backwards-compat constructor for callers that don't have a MeterRegistry. */
    public TgNotificationDeduper(TgNotificationLogRepository logRepo) {
        this(logRepo, null);
    }

    /** Test-only constructor (no DB fallback). */
    TgNotificationDeduper() {
        this(null, null);
    }

    /**
     * Decide whether the caller should fire the TG send.
     *
     * <p><b>Atomic semantics</b>: implementation uses
     * {@link ConcurrentHashMap#compute} so concurrent callers with the same
     * key serialise on the segment lock — exactly one returns {@code true}
     * within the TTL window, even across threads.
     *
     * @param key      stable dedup key (include hour/bucket to survive restart);
     *                 caller MUST pass the same key as {@code source} to
     *                 {@code TelegramService.sendAlert} for restart-safety
     * @param ttl      after this duration since last send, key is allowed again
     * @param severity CRIT bypasses, WARN/FYI both honour ttl
     * @return true if caller should fire; false if duplicate suppressed
     */
    public boolean shouldSend(String key, Duration ttl, Severity severity) {
        if (key == null) {
            log.warn("[TgDedup] null key — refusing to dedup, allowing send");
            return true;
        }
        LocalDateTime now = LocalDateTime.now();

        if (severity == Severity.CRIT) {
            lastSentAt.put(key, now);
            return true;
        }

        // compute() acquires the segment lock for this key for the duration
        // of the BiFunction. Other threads with same key wait. Other keys
        // proceed independently. The DB query inside is OK: tg-alert
        // throughput is low (sub-Hz per key); occasional brief lock-hold
        // is acceptable.
        //
        // Use a 1-element boolean[] holder rather than value-equality on the
        // returned LocalDateTime: low-resolution clocks (Windows ~10ms) can
        // produce identical timestamps across racing threads, breaking the
        // naive `decision.equals(now)` check. The holder is written exactly
        // once by the winning thread inside the atomic compute() block.
        boolean[] allowed = { false };
        lastSentAt.compute(key, (k, prev) -> {
            // Cache hit — check TTL
            if (prev != null) {
                if (Duration.between(prev, now).compareTo(ttl) < 0) {
                    allowed[0] = false;
                    return prev; // suppress, keep prev
                }
                allowed[0] = true;
                return now; // expired → allow + overwrite
            }
            // Cache miss — DB warm-up (covers post-restart and concurrent first-call).
            // #401 — retry once on transient failure (e.g. DB connection still warming
            // up immediately after JVM startup / blue-green deploy churn). Still
            // fail-open after 2 attempts so a sustained DB outage doesn't deadlock the
            // notification path; that case is counted in {@link #METRIC_WARMUP_FAIL}.
            if (logRepo != null) {
                LocalDateTime cutoff = now.minus(ttl);
                LocalDateTime dbSent = null;
                Exception lastException = null;
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        dbSent = logRepo.findLatestSentAtBySourceAfter(k, cutoff);
                        lastException = null;
                        break;
                    } catch (Exception e) {
                        lastException = e;
                        if (attempt == 0) {
                            log.debug("[TgDedup] DB warm-up retry for key={}: {}",
                                    k, e.getMessage());
                            try {
                                Thread.sleep(WARMUP_RETRY_BACKOFF_MS);
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                }
                if (lastException != null) {
                    log.warn("[TgDedup] DB warm-up failed twice for key={}: {} — fail-open allow",
                            k, lastException.getMessage());
                    incrementWarmupFailCounter();
                    // fall through — allow this send
                } else if (dbSent != null) {
                    log.debug("[TgDedup] DB warm-up hit key={} dbSent={} (suppress)",
                            k, dbSent);
                    allowed[0] = false;
                    return dbSent; // suppress, cache the DB timestamp
                }
            }
            allowed[0] = true;
            return now; // allow + record
        });

        if (!allowed[0]) {
            log.debug("[TgDedup] suppressed key={} ttl={}", key, ttl);
        }
        return allowed[0];
    }

    /** Test/diagnostic: peek the last sent time without affecting state. */
    public LocalDateTime lastSent(String key) {
        return lastSentAt.get(key);
    }

    /** Test/diagnostic: clear a specific key (e.g. force re-fire). */
    public void clear(String key) {
        lastSentAt.remove(key);
    }

    /**
     * #401 — increments the {@link #METRIC_WARMUP_FAIL} counter when present.
     * Wrapped in try/catch so a metric-system fault never escalates into a
     * caller-facing exception. No-op when running outside Spring (no registry).
     */
    private void incrementWarmupFailCounter() {
        if (meterRegistry == null) return;
        try {
            Counter.builder(METRIC_WARMUP_FAIL)
                    .description("TgNotificationDeduper DB warm-up query failed twice and fell open")
                    .register(meterRegistry)
                    .increment();
        } catch (Throwable t) {
            log.debug("[TgDedup] metric increment failed: {}", t.getMessage());
        }
    }

    /** Periodic memory hygiene — drop keys older than {@link #STALE_AFTER}. */
    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 60 * 60_000L)
    public void purgeStale() {
        LocalDateTime cutoff = LocalDateTime.now().minus(STALE_AFTER);
        int removed = 0;
        for (Map.Entry<String, LocalDateTime> e : lastSentAt.entrySet()) {
            if (e.getValue().isBefore(cutoff)) {
                lastSentAt.remove(e.getKey());
                removed++;
            }
        }
        if (removed > 0) {
            log.info("[TgDedup] purged {} stale entries (cutoff={})", removed, cutoff);
        }
    }
}
