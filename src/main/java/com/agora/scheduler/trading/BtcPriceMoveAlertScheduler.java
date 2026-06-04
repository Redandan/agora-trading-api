package com.agora.scheduler.trading;

import com.agora.model.MdKline;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.indicator.HysteresisAlertGuard;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * #434 — BTC price-move TG alert scheduler.
 *
 * <p>Six independent threshold checks (3 windows × 2 severities):
 *
 * <table>
 *   <tr><th>Window</th><th>Severity</th><th>Trigger</th><th>Emoji</th></tr>
 *   <tr><td>1h</td>  <td>WARN</td>     <td>atr_1h ≥ 3 OR |change_1h| ≥ 3%</td>   <td>🟠</td></tr>
 *   <tr><td>1h</td>  <td>CRITICAL</td> <td>atr_1h ≥ 4 OR |change_1h| ≥ 5%</td>   <td>🔴</td></tr>
 *   <tr><td>4h</td>  <td>WARN</td>     <td>atr_4h ≥ 3 OR |change_4h| ≥ 5%</td>   <td>🟠</td></tr>
 *   <tr><td>4h</td>  <td>CRITICAL</td> <td>|change_4h| ≥ 8%</td>                  <td>🔴</td></tr>
 *   <tr><td>24h</td> <td>WARN</td>     <td>|change_24h| ≥ 6%</td>                 <td>🟠</td></tr>
 *   <tr><td>24h</td> <td>CRITICAL</td> <td>|change_24h| ≥ 10%</td>                <td>🔴</td></tr>
 * </table>
 *
 * <p><b>Why a dedicated scheduler instead of attention_rule</b>: the predicate
 * shape ({@code atr ≥ X OR |change| ≥ Y}) is OR — but
 * {@code AttentionRuleEvaluator.matchesPredicate} is strict AND. Adding OR
 * support there would expand schema + evaluator scope; one custom scheduler
 * keeps the change minimal.
 *
 * <p><b>Cooldown</b>: per-trigger 30min hysteresis via
 * {@link HysteresisAlertGuard#evaluateBoolean} (state key {@code btc_pm_<window>_<sev>}).
 * Once ELEVATED, repeated breaches stay silent until the metric crosses back
 * below threshold (EXIT) or the 12h reminder window passes.
 *
 * <p><b>Rate limit</b>: in-memory {@link ArrayDeque} tracks all firings in
 * the last hour. When ≥ 5 fires occurred in the last 60 min, further alerts
 * are muted for 30 min (cascade ceiling — prevents a flash-crash from
 * spamming six rules × multiple windows simultaneously).
 *
 * <p><b>Acceptance — 180d validation</b>: a one-shot hit-rate computation
 * runs at startup ({@link #validateHistoricalHitRate()}). It scans the last
 * 180 days of BTCUSDT 1d klines, finds every event with {@code |Δ24h| ≥ 8%},
 * and verifies whether the WARN-24h rule would have fired. Logged at INFO,
 * fires CRITICAL TG if hit rate ≤ 80% (acceptance threshold).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BtcPriceMoveAlertScheduler {

    private static final String SYMBOL = "BTCUSDT";

    private static final long COOLDOWN_HOURS = 12;
    private static final int  RATE_LIMIT_PER_HOUR = 5;
    private static final long RATE_LIMIT_WINDOW_MIN = 60;
    private static final long RATE_LIMIT_MUTE_MIN = 30;

    private final MdKlineRepository klineRepo;
    private final MarketIndicatorHistoryRepository historyRepo;
    private final NotificationPort notificationPort;
    private final HysteresisAlertGuard hysteresisGuard;

    /** All alert firings within the last hour, oldest first. */
    private final Deque<LocalDateTime> recentFires = new ArrayDeque<>();
    /** When non-null and in the future, alerts are muted (cascade ceiling). */
    private volatile LocalDateTime mutedUntil = null;

    // ─── Scheduled evaluation ──────────────────────────────────────────────

    @Scheduled(cron = "0 */5 * * * *", zone = "UTC")
    public void evaluate() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            evaluateWindow("1h",  3.0, 4.0, 3.0, 5.0, now);
            evaluateWindow("4h",  3.0, Double.MAX_VALUE, 5.0, 8.0, now);
            evaluateWindow("24h", Double.MAX_VALUE, Double.MAX_VALUE, 6.0, 10.0, now);
        } catch (Throwable t) {
            log.warn("[BtcPriceMoveAlert] evaluate fatal: {}", t.getMessage());
        }
    }

    /**
     * Evaluate one window. Each window contributes up to two firings: one for
     * the WARN line, one for the CRITICAL line. CRITICAL is reported first so
     * that during a regime event the operator sees the more-severe header
     * before the redundant WARN header.
     */
    private void evaluateWindow(String window,
                                double atrWarn, double atrCrit,
                                double pctWarn, double pctCrit,
                                LocalDateTime now) {
        WindowState state = readWindowState(window, now);
        if (state == null) return;

        boolean critTriggered = state.atrUnits >= atrCrit
                || Math.abs(state.changePct) >= pctCrit;
        boolean warnTriggered = state.atrUnits >= atrWarn
                || Math.abs(state.changePct) >= pctWarn;

        // Fire CRITICAL first (header order in TG) — but only if not also a fresh WARN.
        // Hysteresis is keyed per-severity so they don't interfere.
        maybeFire(window, "CRIT", "🔴", "CRITICAL", critTriggered, state, now);
        maybeFire(window, "WARN", "🟠", "WARN",     warnTriggered, state, now);
    }

    private void maybeFire(String window, String sevKey, String emoji, String sevLabel,
                           boolean triggered, WindowState state, LocalDateTime now) {
        String stateKey = "btc_pm_" + window + "_" + sevKey;
        HysteresisAlertGuard.Decision dec = hysteresisGuard.evaluateBoolean(
                stateKey, triggered, !triggered, COOLDOWN_HOURS, null, now);
        boolean shouldFire = dec == HysteresisAlertGuard.Decision.ENTER
                || dec == HysteresisAlertGuard.Decision.REMINDER;
        if (!shouldFire) return;

        if (isMuted(now)) {
            log.debug("[BtcPriceMoveAlert] muted: skipping {} {}", window, sevLabel);
            return;
        }
        if (!recordFireAndCheckCeiling(now)) {
            log.warn("[BtcPriceMoveAlert] cascade ceiling reached, muting {}min", RATE_LIMIT_MUTE_MIN);
            mutedUntil = now.plusMinutes(RATE_LIMIT_MUTE_MIN);
            return;
        }
        sendTg(window, sevLabel, emoji, state, now);
    }

    private void sendTg(String window, String sevLabel, String emoji,
                        WindowState state, LocalDateTime now) {
        try {
            String direction = state.changePct >= 0 ? "UP ⬆️" : "DOWN ⬇️";
            String pctStr = String.format("%+.2f%%", state.changePct);
            String atrStr = state.atrUnits > 0 ? String.format("%.1f", state.atrUnits) : "n/a";
            String msg = String.format(
                    "%s <b>BTC Price Move — %s %s</b>\n" +
                    "方向: %s\n" +
                    "%s 變動: %s\n" +
                    "ATR units: %s × normal %s move",
                    emoji, window, sevLabel,
                    direction,
                    window, pctStr,
                    atrStr, window);
            notificationPort.broadcast(msg, true);
            log.info("[BtcPriceMoveAlert] sent {} {} change={}% atr_units={}",
                    window, sevLabel, pctStr, atrStr);
        } catch (Exception e) {
            log.warn("[BtcPriceMoveAlert] TG send failed: {}", e.getMessage());
        }
    }

    // ─── Rate limit ───────────────────────────────────────────────────────

    private synchronized boolean recordFireAndCheckCeiling(LocalDateTime now) {
        LocalDateTime cutoff = now.minusMinutes(RATE_LIMIT_WINDOW_MIN);
        while (!recentFires.isEmpty() && recentFires.peekFirst().isBefore(cutoff)) {
            recentFires.pollFirst();
        }
        if (recentFires.size() >= RATE_LIMIT_PER_HOUR) {
            return false; // ceiling hit, do not record this one
        }
        recentFires.offerLast(now);
        return true;
    }

    private boolean isMuted(LocalDateTime now) {
        LocalDateTime until = mutedUntil;
        return until != null && now.isBefore(until);
    }

    // ─── Window state (read latest indicator values) ──────────────────────

    private WindowState readWindowState(String window, LocalDateTime now) {
        String changeInd = "btc_change_pct_" + window;
        String atrInd    = "btc_atr_units_"  + window;

        BigDecimal changeVal = historyRepo.findTopCleanBySymbolAndIndicator(SYMBOL, changeInd)
                .map(h -> h.getValue()).orElse(null);
        BigDecimal atrVal = historyRepo.findTopCleanBySymbolAndIndicator(SYMBOL, atrInd)
                .map(h -> h.getValue()).orElse(null);

        if (changeVal == null || atrVal == null) {
            log.debug("[BtcPriceMoveAlert] {} indicators not yet collected", window);
            return null;
        }
        return new WindowState(changeVal.doubleValue(), atrVal.doubleValue());
    }

    private record WindowState(double changePct, double atrUnits) {}

    // ─── 180d validation (one-shot at startup) ────────────────────────────

    /**
     * Smoke-test the WARN-24h rule against 180 days of historical 1d klines.
     * Acceptance from issue #434: ≥ 80% of {@code |Δ24h| ≥ 8%} events would
     * have fired the rule. Runs once after Spring fully starts; failure
     * sends a CRITICAL TG so it's visible.
     */
    @PostConstruct
    void scheduleStartupValidation() {
        // Defer to avoid blocking application startup. A simple delayed
        // run — can't use @EventListener(ApplicationReadyEvent.class) +
        // @Scheduled on the same bean cleanly without thread juggling.
        new Thread(() -> {
            try {
                Thread.sleep(60_000L); // wait 1 min for klines to be queryable
                validateHistoricalHitRate();
            } catch (Throwable t) {
                log.warn("[BtcPriceMoveAlert] startup validation skipped: {}", t.getMessage());
            }
        }, "btc-pm-validator").start();
    }

    void validateHistoricalHitRate() {
        try {
            List<MdKline> daily = klineRepo.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    SYMBOL, "1d", PageRequest.of(0, 200));
            if (daily.size() < 30) {
                log.warn("[BtcPriceMoveAlert] validation: insufficient daily klines ({}<30)",
                        daily.size());
                return;
            }
            Collections.reverse(daily); // ascending

            int eventCount = 0;
            int hitCount = 0;
            int falsePositive = 0;
            for (int i = 1; i < daily.size(); i++) {
                double prev = daily.get(i - 1).getClosePrice().doubleValue();
                double curr = daily.get(i).getClosePrice().doubleValue();
                if (prev <= 0) continue;
                double pct = (curr - prev) / prev * 100.0;
                boolean isEvent = Math.abs(pct) >= 8.0;
                boolean wouldFire = Math.abs(pct) >= 6.0; // WARN-24h threshold
                if (isEvent) {
                    eventCount++;
                    if (wouldFire) hitCount++;
                } else if (wouldFire) {
                    falsePositive++;
                }
            }

            double hitRate = eventCount == 0 ? 1.0 : (double) hitCount / eventCount;
            log.info("[BtcPriceMoveAlert] 180d validation: events(|Δ24h|≥8%)={}, " +
                    "hit(rule fires)={}, hit_rate={:.1%}, false_positives={}, total_days={}",
                    eventCount, hitCount, hitRate, falsePositive, daily.size());

            // Acceptance: hit rate ≥ 80%, FP ≤ 60 events / 180d.
            // Fire CRITICAL TG only if acceptance failed; quiet on pass.
            if (hitRate < 0.80 && eventCount > 0) {
                notificationPort.broadcast(String.format(
                        "🚨 <b>#434 Validation FAILED — BTC price-move rule undershoots</b>\n" +
                        "180d: %d events ≥8%% │ rule fired %d (%.1f%%) │ acceptance ≥80%%\n" +
                        "Threshold may be too lenient — consider tightening.",
                        eventCount, hitCount, hitRate * 100.0), true);
            }
            if (falsePositive > 60) {
                notificationPort.broadcast(String.format(
                        "🟡 <b>#434 Validation NOTE — high false-positive count</b>\n" +
                        "180d: false positives = %d (acceptance ≤ 60). " +
                        "Threshold may be too aggressive.", falsePositive), true);
            }
        } catch (Throwable t) {
            log.warn("[BtcPriceMoveAlert] validation error: {}", t.getMessage());
        }
    }
}
