package com.agora.service.ml;

import com.agora.config.properties.MlSqlProperties;
import com.agora.config.properties.DailyMlDigestProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 彙整 ML pipeline 每日進度成 TG-friendly 文字報告。
 *
 * <p>由 {@link com.agora.scheduler.trading.DailyMlPipelineDigest} 每日排程呼叫（含 training），
 * 也由 MCP 工具 {@code getDailyMlPipelineDigest} 供 on-demand 查詢（預設不觸發 training）。
 *
 * <p>單一責任：讀 DB + 呼叫 {@link MlTrainingOrchestrator} → 產出字串。
 * 不做 TG 發送、不做 HTML escape（HTML tags 直接寫進去，由 caller 決定是否 escape）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlPipelineDigestService {

    private final MlTrainingOrchestrator orchestrator;
    private final JdbcTemplate jdbc;
    private final DailyMlDigestProperties props;
    private final MlSqlProperties mlSqlProperties;

    /**
     * 產生每日 ML pipeline digest 報告。
     *
     * @param triggerTraining true → 若新資料達門檻就真的訓新版本（scheduler 用）；
     *                        false → 只報告「會/不會訓練」但不實際訓（MCP on-demand 查詢用）
     * @return HTML-tagged 文字報告（<b>/<code> 等 TG HTML mode tags 已 inlined）
     */
    public String buildDigest(boolean triggerTraining) {
        StringBuilder sb = new StringBuilder();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate shortStart = today.minusDays(props.driftWindowDaysShort());
        LocalDate longStart = today.minusDays(props.driftWindowDaysLong());

        sb.append("🤖 <b>ML Pipeline 每日 Digest</b>\n");
        sb.append("📅 ").append(today).append(" (UTC)\n");
        if (!triggerTraining) sb.append("<i>(on-demand 查詢；不會觸發實際訓練)</i>\n");
        sb.append("\n");

        // ── 1. 資料新鮮度 ──
        int newTrades24h = countTradesSince(today.minusDays(1));
        int totalTrainingRows = countTrainingRows();
        ProgressSnapshot cached = loadLatestProgressSnapshot();
        sb.append("<b>📊 資料</b>\n");
        sb.append(String.format("• 過去 24h 新 trades: %d%n", newTrades24h));
        sb.append(String.format("• 累積訓練樣本: %d%n%n", totalTrainingRows));

        // ── 2. PROMOTED drift check (short + long window) ──
        Long promotedId = findPromotedVersionId();
        sb.append("<b>🏅 PROMOTED 模型</b>\n");
        Double promotedShortEdge = null;
        Double promotedLongEdge = null;
        if (promotedId == null) {
            sb.append("• 無 PROMOTED 版本 — 請先 promote 一個\n\n");
        } else {
            Map<String, Object> meta = loadMeta(promotedId);
            int version = intVal(meta.get("version"));
            sb.append(String.format("• v%d (id=%d)%n", version, promotedId));
            if (cached != null && cached.promotedVersionId() != null && cached.promotedVersionId().equals(promotedId)) {
                promotedShortEdge = cached.promotedEdgeShort();
                promotedLongEdge = cached.promotedEdgeLong();
                appendCachedWindowLine(sb, "近窗", props.driftWindowDaysShort(), cached.snapshotDate(),
                        cached.promotedEdgeShort(), null);
                appendCachedWindowLine(sb, "長窗", props.driftWindowDaysLong(), cached.snapshotDate(),
                        cached.promotedEdgeLong(), cached.promotedNLong());
            } else {
                appendEvaluationSkipped(sb);
            }

            // Alert only when 長窗 confirms — 避免小樣本近窗噪音誤報
            if (promotedLongEdge != null && promotedLongEdge < props.driftAlertPp()) {
                sb.append(String.format("• 🚨 長窗 edge 已掉到 +%.1fpp 以下 — 建議檢查最新 READY 版本%n",
                        props.driftAlertPp()));
            } else if (promotedShortEdge != null && promotedShortEdge < props.driftAlertPp()
                    && promotedLongEdge != null && promotedLongEdge >= props.driftAlertPp()) {
                sb.append("• ℹ️ 近窗轉差但長窗仍穩 — 持續觀察,可能是 regime 暫時不利\n");
            }
            // #243: near-window negative edge alert — model actively hurting signal selection
            if (promotedShortEdge != null && promotedShortEdge < -10.0) {
                sb.append(String.format(
                        "• 🔴 <b>近窗 edge = %+.1fpp — 模型比隨機猜測還差</b>%n" +
                        "  ML gate 可能在 block 應該 PASS 的信號。建議：%n" +
                        "  (1) getModelRegimePerformance 確認是否 regime 特定問題%n" +
                        "  (2) 考慮調高 buyThreshold 或暫時停用 ML gate%n",
                        promotedShortEdge));
            } else if (promotedShortEdge != null && promotedShortEdge < 0.0) {
                sb.append(String.format(
                        "• ⚠️ 近窗 edge = %+.1fpp — 低於 baseline，持續觀察%n",
                        promotedShortEdge));
            }
            sb.append("\n");
        }

        // ── 3. 候選升級 (short + long window agreement required) ──
        Long candidateId = findLatestReadyVersionId(promotedId);
        sb.append("<b>🔬 候選 READY 版本</b>\n");
        if (candidateId == null) {
            sb.append("• 無候選版本\n\n");
        } else {
            Map<String, Object> meta = loadMeta(candidateId);
            int version = intVal(meta.get("version"));
            sb.append(String.format("• v%d (id=%d)%n", version, candidateId));
            Double candidateLongEdge = null;
            if (cached != null && cached.candidateVersion() != null && cached.candidateVersion() == version) {
                sb.append(String.format("• 近窗 (%dd): ℹ️ 未快取；跳過即時 HeatWave 重評估%n",
                        props.driftWindowDaysShort()));
                candidateLongEdge = cached.candidateEdgeLong();
                appendCachedWindowLine(sb, "長窗", props.driftWindowDaysLong(), cached.snapshotDate(),
                        candidateLongEdge, null);
            } else {
                appendEvaluationSkipped(sb);
            }

            // Promote recommendation gates:
            //   (a) 長窗有足夠樣本;(b) 長窗 lift ≥ threshold;(c) 近窗不反向(≥ -2pp 容忍)
            if (candidateLongEdge != null && promotedLongEdge != null) {
                double longLift = candidateLongEdge - promotedLongEdge;
                if (cached != null && cached.promoteRecommended()) {
                    sb.append(String.format(
                            "• 💡 <b>最近成功評估曾建議 promote v%d</b>(長窗 +%.1fpp)%n",
                            version, longLift));
                } else if (longLift >= props.promoteLiftPp()) {
                    sb.append(String.format(
                            "• ⚠️ 長窗差距 %+.1fpp 已達門檻，但最近成功評估未通過 promote gate，保守觀察%n",
                            longLift));
                } else {
                    sb.append(String.format("• 長窗差距 %+.1fpp — 未達 promote 門檻(%.1fpp)%n",
                            longLift, props.promoteLiftPp()));
                }
            } else {
                sb.append("• ℹ️ 無最近成功評估快照，暫不下 promote 判斷\n");
            }
            sb.append("\n");
        }

        // ── 4. 訓練動作 ──
        sb.append("<b>⚙️ 訓練動作</b>\n");
        int newSinceLastTrain = newTradesSinceLastTraining();
        if (newSinceLastTrain < props.minNewTradesToTrain()) {
            sb.append(String.format("• 跳過(新資料只 %d 筆 &lt; %d 門檻)%n",
                    newSinceLastTrain, props.minNewTradesToTrain()));
        } else if (!triggerTraining) {
            sb.append(String.format("• 新資料 %d 筆 ≥ 門檻 → 次日 09:17 UTC scheduler 會自動訓練%n",
                    newSinceLastTrain));
        } else {
            sb.append(String.format("• 新資料 %d 筆 ≥ 門檻 → 嘗試訓練新版本...%n", newSinceLastTrain));
            try {
                long newId = trainNewVersion();
                sb.append(String.format("• ✅ 訓練成功 id=%d(明日 digest 會 eval)%n", newId));
            } catch (Exception e) {
                sb.append("• ❌ 訓練失敗: ").append(safeErr(e)).append("\n");
            }
        }

        return sb.toString();
    }

    // ─── helpers (package-private for scheduler unit tests if needed) ───

    int countTradesSince(LocalDate from) {
        Integer c = jdbc.queryForObject(
                "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) " +
                        "FROM bt_backtest_trade WHERE entry_time >= ?",
                Integer.class, from.toString());
        return c == null ? 0 : c;
    }

    int countTrainingRows() {
        try {
            Integer c = jdbc.queryForObject(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) " +
                            "FROM bt_backtest_trade", Integer.class);
            return c == null ? 0 : c;
        } catch (Exception e) { return -1; }
    }

    /**
     * #334 watcher 用：取目前 PROMOTED 模型的近窗 edge（pp）。
     * 樣本不足或評估失敗回傳 null（caller 視為「無資料」，不計入連續低 edge 計數）。
     */
    public Double getPromotedShortWindowEdgePp() {
        ProgressSnapshot cached = loadLatestProgressSnapshot();
        return cached != null ? cached.promotedEdgeShort() : null;
    }

    public double getDriftAlertPp() { return props.driftAlertPp(); }

    Long findPromotedVersionId() {
        try {
            return jdbc.queryForObject(
                    "SELECT id FROM ml_model_registry "
                            + "WHERE model_name = ? AND status = 'PROMOTED' "
                            + "ORDER BY promoted_at DESC LIMIT 1",
                    Long.class, props.modelName());
        } catch (Exception e) { return null; }
    }

    Long findLatestReadyVersionId(Long excludeId) {
        try {
            if (excludeId == null) {
                return jdbc.queryForObject(
                        "SELECT id FROM ml_model_registry "
                                + "WHERE model_name = ? AND status = 'READY' "
                                + "ORDER BY trained_at DESC LIMIT 1",
                        Long.class, props.modelName());
            }
            return jdbc.queryForObject(
                    "SELECT id FROM ml_model_registry "
                            + "WHERE model_name = ? AND status = 'READY' AND id != ? "
                            + "ORDER BY trained_at DESC LIMIT 1",
                    Long.class, props.modelName(), excludeId);
        } catch (Exception e) { return null; }
    }

    Map<String, Object> loadMeta(long id) {
        try {
            return jdbc.queryForMap(
                    "SELECT version, trained_at FROM ml_model_registry WHERE id = ?", id);
        } catch (Exception e) { return Map.of(); }
    }

    ProgressSnapshot loadLatestProgressSnapshot() {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT snapshot_date, promoted_version, promoted_version_id, " +
                            "promoted_edge_short_pp, promoted_edge_long_pp, promoted_n_long, " +
                            "candidate_version, candidate_edge_long_pp, promote_recommended " +
                            "FROM ml_pipeline_progress_log " +
                            "WHERE model_name = ? " +
                            "  AND (promoted_edge_long_pp IS NOT NULL OR candidate_edge_long_pp IS NOT NULL) " +
                            "ORDER BY snapshot_date DESC LIMIT 1",
                    props.modelName());
            return new ProgressSnapshot(
                    toLocalDate(row.get("snapshot_date")),
                    intObj(row.get("promoted_version")),
                    longObj(row.get("promoted_version_id")),
                    doubleObj(row.get("promoted_edge_short_pp")),
                    doubleObj(row.get("promoted_edge_long_pp")),
                    intObj(row.get("promoted_n_long")),
                    intObj(row.get("candidate_version")),
                    doubleObj(row.get("candidate_edge_long_pp")),
                    longVal(row.get("promote_recommended")) == 1L);
        } catch (Exception e) {
            return null;
        }
    }

    Map<String, Object> evalWindow(long id, LocalDate from, LocalDate to) {
        String where = "entry_time >= '" + from + "' AND entry_time <= '" + to + " 23:59:59'";
        return orchestrator.evaluateOnWindow(id, where);
    }

    /** Eval that never throws — returns null on failure. */
    EvalResult evalSafely(long id, LocalDate from, LocalDate to) {
        try {
            return parseEval(evalWindow(id, from, to));
        } catch (Exception e) {
            log.debug("[MlDigest] eval failed id={} window={}..{}: {}", id, from, to, e.getMessage());
            return null;
        }
    }

    /** Append one window line to digest. null → "評估失敗"; sample<min → mark "樣本不足". */
    void appendWindowLine(StringBuilder sb, String label, int windowDays, EvalResult er) {
        if (er == null) {
            sb.append(String.format("• %s (%dd): ⚠️ 評估失敗%n", label, windowDays));
            return;
        }
        String tag = isTrustworthy(er) ? verdictEmoji(er.edge()) : "⚪ 樣本不足";
        sb.append(String.format("• %s (%dd, n=%d): acc %.1f%% / baseline %.1f%% → edge %+.1fpp %s%n",
                label, windowDays, er.total(), er.accuracy(), er.baseline(), er.edge(), tag));
    }

    boolean isTrustworthy(EvalResult er) {
        return er != null && er.total() >= props.minSamplesForVerdict();
    }

    void appendCachedWindowLine(StringBuilder sb, String label, int windowDays, LocalDate snapshotDate,
                                Double edge, Integer n) {
        if (edge == null) {
            sb.append(String.format("• %s (%dd): ℹ️ 無最近成功評估快照%n", label, windowDays));
            return;
        }
        String nText = n != null ? ", n=" + n : "";
        sb.append(String.format("• %s (%dd%s): edge %+.1fpp %s <i>(最近成功評估 %s)</i>%n",
                label, windowDays, nText, edge, verdictEmoji(edge), snapshotDate));
    }

    void appendEvaluationSkipped(StringBuilder sb) {
        sb.append("• ℹ️ 跳過即時 HeatWave 重評估，避免每日 Digest 觸發 SECONDARY_LOAD\n");
    }

    int newTradesSinceLastTraining() {
        try {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT trained_at FROM ml_model_registry "
                            + "WHERE model_name = ? ORDER BY trained_at DESC LIMIT 1",
                    props.modelName());
            Object trainedAt = row.get("trained_at");
            Integer c = jdbc.queryForObject(
                    "SELECT /*+ SET_VAR(use_secondary_engine=OFF) */ COUNT(*) " +
                            "FROM bt_backtest_trade WHERE entry_time > ?",
                    Integer.class, trainedAt);
            return c == null ? 0 : c;
        } catch (Exception e) {
            return 0;
        }
    }

    long trainNewVersion() {
        Map<String, Object> options = new HashMap<>();
        options.put("exclude_column_list", List.of("row_id", "entry_time", "replica_count", "target_return"));
        options.put("task", "classification");
        // Withhold last props.holdoutDays() from training so next digest can honestly eval edge on
        // unseen data. Without this, daily retrain is in-sample and reports inflated 100% accuracy.
        LocalDate cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(props.holdoutDays());
        String whereClause = "entry_time < '" + cutoff + "'";
        return orchestrator.trainAndRegister(
                props.modelName(),
                // #444 — materialized table; operator-configured schema for the split trading service.
                mlSqlProperties.signalScorerTrainingTableName(),
                whereClause,
                "profitable",
                "classification",
                "scheduler:daily",
                "Daily auto-retrain (DailyMlPipelineDigest) holdout=" + props.holdoutDays() + "d cutoff=" + cutoff,
                options);
    }

    EvalResult parseEval(Map<String, Object> stats) {
        long total = longVal(stats.get("total"));
        long correct = longVal(stats.get("correct"));
        long actualWins = longVal(stats.get("actual_wins"));
        long actualLosses = longVal(stats.get("actual_losses"));
        long tp = longVal(stats.get("tp"));
        double accuracy = total > 0 ? 100.0 * correct / total : 0;
        double baseline = total > 0 ? 100.0 * Math.max(actualWins, actualLosses) / total : 0;
        double edge = accuracy - baseline;
        double recall = actualWins > 0 ? 100.0 * tp / actualWins : 0;
        return new EvalResult(total, accuracy, baseline, edge, recall);
    }

    String verdictEmoji(double edge) {
        if (edge >= 10) return "🟢 有 alpha";
        if (edge >= 5) return "🟡 薄 edge";
        return "🔴 無 alpha";
    }

    int intVal(Object o) { return o instanceof Number n ? n.intValue() : 0; }
    long longVal(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
    Integer intObj(Object o) { return o instanceof Number n ? n.intValue() : null; }
    Long longObj(Object o) { return o instanceof Number n ? n.longValue() : null; }
    Double doubleObj(Object o) { return o instanceof Number n ? n.doubleValue() : null; }

    LocalDate toLocalDate(Object o) {
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof LocalDate d) return d;
        return LocalDate.parse(String.valueOf(o));
    }

    String safeErr(Throwable t) {
        String m = t.getMessage();
        if (m == null) return t.getClass().getSimpleName();
        return m.length() > 200 ? m.substring(0, 200) + "..." : m;
    }

    public record EvalResult(long total, double accuracy, double baseline, double edge, double winnerRecall) {}
    public record ProgressSnapshot(
            LocalDate snapshotDate,
            Integer promotedVersion,
            Long promotedVersionId,
            Double promotedEdgeShort,
            Double promotedEdgeLong,
            Integer promotedNLong,
            Integer candidateVersion,
            Double candidateEdgeLong,
            boolean promoteRecommended
    ) {}

    // ═══════════════════════════════════════════════════════════════════════
    // Progress log — daily KPI snapshot into ml_pipeline_progress_log
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 寫一筆當日 KPI snapshot 到 {@code ml_pipeline_progress_log}（V056 table）。
     *
     * <p>Idempotent via UNIQUE KEY (model_name, snapshot_date) + ON DUPLICATE KEY
     * UPDATE；同日多次呼叫以最新值覆寫。由 {@code DailyMlPipelineDigest} 在
     * 每日 TG 發送後呼叫。
     *
     * <p>失敗絕不拋出（log only），避免 progress 記錄問題影響主 digest 流程。
     */
    public void persistDailyProgress() {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate shortStart = today.minusDays(props.driftWindowDaysShort());
            LocalDate longStart = today.minusDays(props.driftWindowDaysLong());

            int newTrades24h = countTradesSince(today.minusDays(1));
            int totalSamples = countTrainingRows();

            ProgressSnapshot cached = loadLatestProgressSnapshot();
            Long promotedId = findPromotedVersionId();
            Integer promotedVersion = null;
            Double promotedEdgeShort = null, promotedEdgeLong = null;
            Integer promotedNLong = null;
            boolean evalFailed = false;
            if (promotedId != null) {
                promotedVersion = intVal(loadMeta(promotedId).get("version"));
                if (cached != null && cached.promotedVersionId() != null && cached.promotedVersionId().equals(promotedId)) {
                    promotedEdgeShort = cached.promotedEdgeShort();
                    promotedEdgeLong = cached.promotedEdgeLong();
                    promotedNLong = cached.promotedNLong();
                } else {
                    evalFailed = true;
                }
            }

            Long candidateId = findLatestReadyVersionId(promotedId);
            Integer candidateVersion = null;
            Double candidateEdgeLong = null;
            boolean promoteRecommended = false;
            if (candidateId != null) {
                candidateVersion = intVal(loadMeta(candidateId).get("version"));
                if (cached != null && cached.candidateVersion() != null && cached.candidateVersion().equals(candidateVersion)) {
                    candidateEdgeLong = cached.candidateEdgeLong();
                    promoteRecommended = cached.promoteRecommended();
                } else {
                    evalFailed = true;
                }
            }

            jdbc.update(
                    "INSERT INTO ml_pipeline_progress_log(" +
                            "snapshot_date, snapshot_at, model_name, total_samples, new_trades_24h, " +
                            "promoted_version, promoted_version_id, promoted_edge_short_pp, " +
                            "promoted_edge_long_pp, promoted_n_long, candidate_version, " +
                            "candidate_edge_long_pp, promote_recommended) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) " +
                            "ON DUPLICATE KEY UPDATE " +
                            "snapshot_at=VALUES(snapshot_at), total_samples=VALUES(total_samples), " +
                            "new_trades_24h=VALUES(new_trades_24h), " +
                            "promoted_version=VALUES(promoted_version), " +
                            "promoted_version_id=VALUES(promoted_version_id), " +
                            "promoted_edge_short_pp=COALESCE(VALUES(promoted_edge_short_pp), promoted_edge_short_pp), " +
                            "promoted_edge_long_pp=COALESCE(VALUES(promoted_edge_long_pp), promoted_edge_long_pp), " +
                            "promoted_n_long=COALESCE(VALUES(promoted_n_long), promoted_n_long), " +
                            "candidate_version=VALUES(candidate_version), " +
                            "candidate_edge_long_pp=COALESCE(VALUES(candidate_edge_long_pp), candidate_edge_long_pp), " +
                            "promote_recommended=IF(VALUES(promoted_edge_long_pp) IS NULL " +
                            "OR VALUES(candidate_edge_long_pp) IS NULL, promote_recommended, VALUES(promote_recommended))",
                    // #430 — bind java.sql.Date instead of LocalDate to bypass MySQL Connector 9.6
                    // LocalDateValueEncoder anonymous-inner-class lazy load. The inner class is
                    // physically present in the deployed jar but a Spring Boot LaunchedClassLoader
                    // close (e.g. blue/green drain killing the old JVM mid-task at 09:11:51 UTC on
                    // 2026-05-04) raced against the first-ever LocalDate JDBC bind in that JVM and
                    // failed with NoClassDefFoundError. java.sql.Date.valueOf is eagerly resolved
                    // and uses a different encoder path with no lazy inner classes.
                    java.sql.Date.valueOf(today), LocalDateTime.now(ZoneOffset.UTC), props.modelName(),
                    totalSamples, newTrades24h,
                    promotedVersion, promotedId, promotedEdgeShort, promotedEdgeLong, promotedNLong,
                    candidateVersion, candidateEdgeLong, promoteRecommended ? 1 : 0);
            if (evalFailed) {
                log.warn("[MlDigest] progress snapshot persisted with eval failure; preserving existing edge columns where possible for {}",
                        today);
            }
            log.info("[MlDigest] progress snapshot persisted for {}", today);
        } catch (Throwable t) {
            // #430 — catch Throwable, not Exception. NoClassDefFoundError (an Error,
            // not an Exception) escaped this block during the 2026-05-04 09:11:51 UTC
            // drain race and surfaced to the scheduler as a fatal 💥 TG, even though
            // the digest TG had already been sent successfully. Honoring the
            // method's javadoc contract: "失敗絕不拋出 — 避免 progress 記錄問題影響主 digest 流程".
            log.warn("[MlDigest] persistDailyProgress failed: {}", t.getMessage(), t);
        }
    }
}
