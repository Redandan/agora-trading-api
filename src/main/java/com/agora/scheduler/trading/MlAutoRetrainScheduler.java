package com.agora.scheduler.trading;

import com.agora.config.properties.MlAutoRetrainProperties;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ml.MlTrainingOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weekly ML signal_scorer retrain + walk-forward evaluation, Telegram-reported.
 *
 * <p>Rationale: Phase 2 walk-forward revealed that current v4 model (trained
 * on all 2126 trades) has 0pp edge vs baseline when tested on unseen regime
 * (2026-03 crash). Root cause: training data is 70% bull regime. As
 * {@code bt_backtest_trade} accumulates diverse regimes over time, the
 * retrained model should slowly improve — IF features are informative at all.
 *
 * <p>This scheduler silently (no trading impact) retrains weekly and reports
 * to Telegram:
 * <ul>
 *   <li>Current sample count + class balance</li>
 *   <li>Train on all-but-last-30d, eval on last 30d (true walk-forward)</li>
 *   <li>Edge over baseline (🟢 ≥ 5pp / 🟡 ≥ 2pp / 🔴 &lt; 2pp)</li>
 *   <li>Calibration inversion check (P(win) monotonicity vs actual)</li>
 * </ul>
 *
 * <p>The new model row is ARCHIVED immediately if edge is 🔴 (don't clutter
 * the registry); retained if 🟡 or 🟢 for Claude / human inspection.
 *
 * <p>Disabled by default; enable via {@code meta-control.ml-autoretrain.enabled=true}.
 * Config:
 * <ul>
 *   <li>cron — default {@code 0 0 5 * * SUN} (Sunday 05:00 UTC)</li>
 *   <li>holdout-days — default 30 (last N days = holdout)</li>
 *   <li>min-holdout-trades — default 80 (skip if too few)</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "meta-control.ml-autoretrain.enabled", havingValue = "true", matchIfMissing = false)
public class MlAutoRetrainScheduler {

    private final MlTrainingOrchestrator orchestrator;
    private final NotificationPort notificationPort;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final MlAutoRetrainProperties props;

    /** Weekly cron — Sunday 05:00 UTC. Change via application.yml if needed. */
    @Scheduled(cron = "${meta-control.ml-autoretrain.cron:0 0 5 * * SUN}",
            zone = "UTC")
    public void weeklyRetrain() {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusDays(props.holdoutDays());
            LocalDate trainStart = LocalDate.of(2024, 1, 1);  // lower bound

            int holdoutCount = countTradesIn(cutoff.toString(), today.toString());
            if (holdoutCount < props.minHoldoutTrades()) {
                sendTg(String.format(
                        "🤖 <b>ML Auto-Retrain skipped</b>%n" +
                                "Holdout window %s → %s only has %d trades (min %d required).%n" +
                                "Skipping. Try again next week.",
                        cutoff, today, holdoutCount, props.minHoldoutTrades()));
                return;
            }

            // 1. Train on everything BEFORE cutoff
            String whereClause = "entry_time >= '" + trainStart + "' "
                    + "AND entry_time < '" + cutoff + "'";
            long modelId;
            int trainCount = countTradesIn(trainStart.toString(), cutoff.toString());
            try {
                Map<String, Object> options = new HashMap<>();
                // NOTE: pinned to vw_signal_training_v2, which has neither
                // target_return nor replica_count. If this scheduler is ever
                // re-pointed to vw_signal_training_v5_dedup or a successor that
                // carries both classification and regression targets, extend the
                // exclude list (see TradingMlMcpTools#trainSignalScorer for
                // leakage rationale). Scheduler is disabled-by-default
                // (meta-control.ml-autoretrain.enabled=false) so no prod impact.
                options.put("exclude_column_list", List.of("row_id", "entry_time"));
                options.put("task", "classification");
                modelId = orchestrator.trainAndRegister(
                        "signal_scorer",
                        "agora_market.vw_signal_training_v2",
                        whereClause,
                        "profitable",
                        "classification",
                        "scheduler:weekly",
                        String.format("Weekly auto-retrain %s → %s (%d samples)",
                                trainStart, cutoff, trainCount),
                        options);
            } catch (Exception trainErr) {
                sendTg(String.format(
                        "🤖❌ <b>ML Auto-Retrain FAILED</b>%n" +
                                "Training error: %s%n" +
                                "(Possibly rate-limited; will retry next week.)",
                        trainErr.getMessage()));
                log.warn("[MlAutoRetrain] train failed", trainErr);
                return;
            }

            // 2. Eval on holdout window
            String holdoutWhere = "entry_time >= '" + cutoff + "' "
                    + "AND entry_time <= '" + today + " 23:59:59'";
            Map<String, Object> stats;
            try {
                stats = orchestrator.evaluateOnWindow(modelId, holdoutWhere);
            } catch (Exception evalErr) {
                sendTg(String.format(
                        "🤖⚠️ <b>ML Weekly Retrain</b> v? trained but eval failed%n" +
                                "model_id=%d  error=%s",
                        modelId, evalErr.getMessage()));
                return;
            }

            // 3. Compute edge, classify, compose report
            long total = ((Number) stats.get("total")).longValue();
            long correct = ((Number) stats.get("correct")).longValue();
            long actualWins = ((Number) stats.get("actual_wins")).longValue();
            long actualLosses = ((Number) stats.get("actual_losses")).longValue();
            long tp = ((Number) stats.get("tp")).longValue();
            long tn = ((Number) stats.get("tn")).longValue();
            double accuracy = total > 0 ? 100.0 * correct / total : 0;
            double baseline = total > 0 ? 100.0 * Math.max(actualWins, actualLosses) / total : 0;
            double edge = accuracy - baseline;
            double recall = actualWins > 0 ? 100.0 * tp / actualWins : 0;
            double spec = actualLosses > 0 ? 100.0 * tn / actualLosses : 0;

            String verdict = edge >= 5.0 ? "🟢 有 alpha" :
                    edge >= 2.0 ? "🟡 薄 edge" : "🔴 無 alpha";

            // Check for calibration inversion (bad sign — model's confidence is backwards)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> calib =
                    (List<Map<String, Object>>) stats.get("calibration_by_decile");
            boolean inverted = isCalibrationInverted(calib);

            String reportBody = String.format(
                    "🤖 <b>ML Weekly Retrain v? (id=%d)</b>%n%n" +
                            "<b>Train:</b> %s → %s (%d samples)%n" +
                            "<b>Holdout:</b> %s → %s (%d trades, winrate %.1f%%)%n%n" +
                            "<b>Accuracy:</b> %.1f%% (baseline %.1f%%)%n" +
                            "<b>Edge:</b> %+.1fpp %s%n" +
                            "<b>Winner recall:</b> %.1f%% | <b>Loser spec:</b> %.1f%%%n" +
                            "%s",
                    modelId,
                    trainStart, cutoff, trainCount,
                    cutoff, today, total, total > 0 ? 100.0 * actualWins / total : 0,
                    accuracy, baseline, edge, verdict,
                    recall, spec,
                    inverted ? "%n⚠️ <b>Calibration INVERTED</b> — 模型信心與實際勝率相反,regime shift 跡象".formatted() : "");

            sendTg(reportBody);

            // Auto-archive if no edge (don't clutter)
            if (edge < 2.0) {
                try {
                    jdbc.update("UPDATE ml_model_registry SET status='ARCHIVED', "
                            + "archived_at=NOW(6), notes=CONCAT(COALESCE(notes,''),"
                            + "'\\nauto-ARCHIVED by MlAutoRetrainScheduler (no edge)') "
                            + "WHERE id=?", modelId);
                    log.info("[MlAutoRetrain] auto-archived no-edge model id={} edge={}pp",
                            modelId, edge);
                } catch (Exception archErr) {
                    log.warn("[MlAutoRetrain] archive failed", archErr);
                }
            }
        } catch (Throwable t) {
            // Scheduler must never die — any fatal gets logged + TG'd, next week retries.
            log.error("[MlAutoRetrain] fatal", t);
            try {
                sendTg("🤖💥 <b>ML Auto-Retrain fatal</b>\n" + t.getMessage());
            } catch (Exception ignored) {}
        }
    }

    private int countTradesIn(String startDate, String endDate) {
        Integer c = jdbc.queryForObject(
                "SELECT COUNT(*) FROM bt_backtest_trade "
                        + "WHERE entry_time >= ? AND entry_time < ?",
                Integer.class, startDate, endDate);
        return c == null ? 0 : c;
    }

    /**
     * Simple inversion check: if P(win) buckets ≥ 0.4 have actual winrate < 30%,
     * or buckets < 0.2 have actual winrate > 40%, the model is ranking wrong.
     */
    private boolean isCalibrationInverted(List<Map<String, Object>> calib) {
        if (calib == null) return false;
        int suspiciousCells = 0;
        for (Map<String, Object> row : calib) {
            double bucket = ((Number) row.get("p_bucket")).doubleValue();
            Number n = (Number) row.get("n");
            Number actual = (Number) row.get("actual_winrate_pct");
            if (n == null || actual == null || n.intValue() < 10) continue;
            if (bucket >= 0.4 && actual.doubleValue() < 30) suspiciousCells++;
            if (bucket < 0.2 && actual.doubleValue() > 40) suspiciousCells++;
        }
        return suspiciousCells >= 2;
    }

    private void sendTg(String message) {
        try {
            notificationPort.broadcast(message, /*html=*/true);
        } catch (Exception e) {
            log.warn("[MlAutoRetrain] TG send failed: {}", e.getMessage());
        }
    }
}
