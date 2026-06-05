package com.agora.mcp;

import com.agora.config.properties.MlSqlProperties;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.ml.MlTrainingOrchestrator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tools for the HeatWave ML-backed signal scorer pipeline.
 *
 * <p>V1 scope (2026-04-17):
 * <ul>
 *   <li>{@code trainSignalScorer} — train a new version from
 *       {@code vw_signal_training_v1} (2126 trades from bt_backtest_trade).</li>
 *   <li>{@code listModelVersions} — history view for a model name.</li>
 *   <li>{@code getModelMetrics} — algorithm, sample count, feature importance,
 *       training hyperparameters for one version.</li>
 *   <li>{@code predictOne} — single-row prediction + ML_EXPLAIN_ROW attribution.</li>
 *   <li>{@code promoteModel} — mark version PROMOTED (demotes previous).</li>
 * </ul>
 *
 * <p>Not yet in V1 (future follow-ups tracked in CLAUDE.md ML section):
 * <ul>
 *   <li>shadow-mode inference hook in LiveSignalEvaluator</li>
 *   <li>counterfactual whatIfPromote</li>
 *   <li>drift monitor (compare prod feature distribution vs training)</li>
 *   <li>richer training view with indicator snapshots at entry time</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingMlMcpTools {

    /** The first model's canonical name + training view. */
    private static final String SIGNAL_SCORER = "signal_scorer";
    /**
     * Default training view — V071 v7_dedup removes temporal features (hour_of_day,
     * day_of_week) that caused temporal overfitting in v9 (day_of_week=21.8%) and
     * v16 (hour_of_day=2nd ranked at 0.0342). v6 already excluded strategy 496
     * (strategy_id proxy bias). Both classification and regression use this view.
     *
     * Cardinality: 464 unique setups (same as v6; only schema change, no row filter).
     */
    /**
     * #444 — point at the materialized table {@code bt_signal_training_v8_mat}
     * instead of the (slow) view {@code vw_signal_training_v8_dedup}. The
     * view's GROUP BY + 8 correlated subqueries took 2m36s for a 30d scan in
     * prod (5/6), exceeding Cloudflare's 30s timeout for every MCP eval call.
     * The materialized table is refreshed nightly by
     * {@link com.agora.service.ml.SignalTrainingMaterializedRefreshService}
     * and reads in milliseconds.
     */
    /** Same materialized table, used for regression — distinguishes via target_column = target_return. */
    private static final String SIGNAL_SCORER_TARGET = "profitable";

    private final MlTrainingOrchestrator orchestrator;
    private final MlSqlProperties mlSqlProperties;
    private final com.agora.service.ml.SignalTrainingMaterializedRefreshService matRefresh;
    private final com.agora.service.ml.MlFeatureBackfillService backfillService;
    private final com.agora.service.ml.SignalScorerEnsemble ensemble;
    private final com.agora.service.ml.MlPipelineDigestService digestService;
    private final com.agora.service.ml.MlInferenceLogger inferenceLogger;
    private final com.agora.repository.trading.MdKlineRepository klineRepository;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    /** Retro-scoring for evalEnsembleOnLiveSignalHistory. */
    private final com.agora.service.meta.TradeDecisionEngine tradeDecisionEngine;

    private String signalScorerTrainingTable() {
        return mlSqlProperties.signalScorerTrainingTableName();
    }

    /**
     * Default holdout window in days for {@link #trainSignalScorer(String)}.
     *
     * <p>Training excludes trades with {@code entry_time >= NOW - HOLDOUT_DAYS}
     * so that {@code evalOnHoldout(versionId, cutoff, today)} tests on rows
     * the model has NEVER seen.
     *
     * <p>Mirrors {@code meta-control.ml-autoretrain.holdout-days} (default 30)
     * used by {@link com.agora.scheduler.trading.MlAutoRetrainScheduler#weeklyRetrain()}.
     *
     * <p>2026-04-18 temporal-leakage fix: prior to this constant, the view had
     * no time filter and ML_TRAIN consumed all 330 rows; then evalOnHoldout
     * queried a subset of those same rows → test was a proper subset of train,
     * yielding 100% accuracy (v11/v12 holdout metrics). Root cause write-up
     * in the fix/ml-temporal-leakage-2026-04-18 branch commit messages.
     */
    private static final int DEFAULT_HOLDOUT_DAYS = 30;

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.WRITE_TRADING})
    @Tool(description = "訓練 signal_scorer 新版本(**預設保留最近 30 天當 holdout**,防 training-on-test)。" +
            "用 vw_signal_training_v6_dedup(V068:排除 strategy 496)且 WHERE entry_time < NOW - 30d;evalOnHoldout 用同 cutoff 後半段做真 holdout。" +
            "自動呼叫 sys.ML_TRAIN,AutoML 選 LightGBM + 調超參。" +
            "訓練 30-300 秒,建議 MCP timeout >= 360s。" +
            "notes 必填(類比 enableStrategy)。holdoutDays optional(預設 30,範圍 7-90)。" +
            "回傳:registryId + algorithm + n_rows + holdout cutoff + 建議的 evalOnHoldout 呼叫。" +
            "下一步:evalOnHoldout(id, cutoff, today) 驗證;或 predictOne 試跑。")
    public String trainSignalScorer(String notes, Integer holdoutDays) {
        if (notes == null || notes.trim().isEmpty()) {
            return "❌ notes 為必填:說明為何訓練此版本(如「加了 ADX 特徵」/「樣本量達 3k 重訓」)";
        }
        int holdout = (holdoutDays == null || holdoutDays <= 0) ? DEFAULT_HOLDOUT_DAYS : holdoutDays;
        if (holdout < 7 || holdout > 90) {
            return "❌ holdoutDays 需在 [7, 90] 範圍(預設 30)";
        }
        try {
            Map<String, Object> options = new HashMap<>();
            // Exclude columns that must NOT be fed to HeatWave ML_TRAIN as features:
            //   row_id        — synthetic id, not predictive.
            //   entry_time    — in the view for WHERE-filtering (V048) but would
            //                   either crash on DATETIME or become a spurious
            //                   "time of bull market" proxy feature.
            //   target_return — the OTHER target (regression) lives alongside
            //                   `profitable` in vw_signal_training_v5_dedup. If not
            //                   excluded, AutoML picks it up as a feature; since
            //                   target_return is perfectly correlated with the
            //                   classification label (positive return → profitable=1),
            //                   the model trivially "learns" to output the label,
            //                   producing 100% accuracy and feature_importance
            //                   dominated by target_return (~0.44 on v11). See
            //                   2026-04-18 Level-1 leakage write-up (commit b4af0556).
            //   replica_count — diagnostic count of dedup source rows; leaks
            //                   "popular setup" signal and correlates with outcome
            //                   via selection bias, also excluded by trainOnWindow
            //                   (the walk-forward classification counterpart).
            options.put("exclude_column_list", List.of(
                    "row_id", "entry_time", "target_return", "replica_count"));

            // Level-2 (temporal) leakage fix: reserve last N days as holdout by
            // applying a WHERE clause to the training snapshot. Before this
            // guard, ML_TRAIN saw ALL 330 rows in the view; evalOnHoldout then
            // queried a subset of those same rows → 100% accuracy (v11/v12
            // were trained-on-test). Mirrors what MlAutoRetrainScheduler has
            // done since its inception; trainSignalScorer was the outlier that
            // skipped the walk-forward split for convenience.
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoff = today.minusDays(holdout);
            String whereClause = "entry_time < '" + cutoff + "'";

            long id = orchestrator.trainAndRegister(
                    SIGNAL_SCORER,
                    signalScorerTrainingTable(),
                    whereClause,
                    SIGNAL_SCORER_TARGET,
                    "classification",
                    "mcp:trainSignalScorer",
                    notes.trim() + " [holdout=" + holdout + "d, train<" + cutoff + "]",
                    options);

            Map<String, Object> row = orchestrator.getVersion(id);
            StringBuilder sb = new StringBuilder();
            sb.append("✅ 訓練完成\n");
            sb.append("ID         : ").append(row.get("id")).append("\n");
            sb.append("Model      : ").append(row.get("model_name")).append(" v").append(row.get("version")).append("\n");
            sb.append("Status     : ").append(row.get("status")).append("\n");
            sb.append("Algorithm  : ").append(row.get("algorithm")).append("\n");
            sb.append("Samples    : ").append(row.get("sample_count")).append(" (train only, pre-cutoff)\n");
            sb.append("Train window : entry_time < ").append(cutoff).append(" (holdout=").append(holdout).append("d)\n");
            sb.append("Holdout window: ").append(cutoff).append(" → ").append(today).append(" (reserved for eval)\n");
            sb.append("HW handle  : ").append(row.get("heatwave_handle")).append("\n");
            sb.append("\n--- Metrics ---\n");
            sb.append(truncate(String.valueOf(row.get("metrics_json")), 400)).append("\n");
            sb.append("\n--- Feature importance (top) ---\n");
            sb.append(truncate(String.valueOf(row.get("feature_importance_json")), 300)).append("\n");
            sb.append("\n下一步(honest walk-forward):\n");
            sb.append("- evalOnHoldout(").append(row.get("id")).append(", '").append(cutoff).append("', '").append(today).append("') 真 holdout 驗證\n");
            sb.append("- getModelMetrics(").append(row.get("id")).append(") 看完整資料\n");
            sb.append("- predictOne(").append(row.get("id")).append(", {...features...}) 試算一筆\n");
            sb.append("- promoteModel(").append(row.get("id")).append(", notes) 升為 PROMOTED(會 demote 舊版)\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP:trainSignalScorer] failed", e);
            return "❌ 訓練失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS})
    @Tool(description = "列出指定 model 的所有版本(預設 signal_scorer)。顯示 version / status / algorithm / 樣本數 / 訓練時間 / notes preview。" +
            "param: modelName(預設 signal_scorer), limit(預設 10)")
    public String listModelVersions(String modelName, Integer limit) {
        String name = (modelName == null || modelName.isBlank()) ? SIGNAL_SCORER : modelName.trim();
        int n = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        List<Map<String, Object>> rows = orchestrator.listVersions(name, n);
        if (rows.isEmpty()) {
            return "ℹ️ 尚無版本 for model=" + name + "。先跑 trainSignalScorer。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== Model versions: ").append(name).append(" (").append(rows.size()).append(" rows) ===\n");
        for (Map<String, Object> r : rows) {
            sb.append(String.format("id=%s  v%s  [%s]  algo=%s  samples=%s  trained=%s  promoted=%s%n",
                    r.get("id"), r.get("version"), r.get("status"),
                    r.get("algorithm") != null ? r.get("algorithm") : "-",
                    r.get("sample_count") != null ? r.get("sample_count") : "-",
                    r.get("trained_at"),
                    r.get("promoted_at") != null ? r.get("promoted_at") : "-"));
            Object notes = r.get("notes_preview");
            if (notes != null && !notes.toString().isBlank()) {
                sb.append("    notes: ").append(notes).append("\n");
            }
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "查看單一 model 版本的完整資料:algorithm、樣本數、hyperparams、feature importance、training view、notes。" +
            "param: versionId(ml_model_registry.id)")
    public String getModelMetrics(Long versionId) {
        { String _e = McpParamValidator.requireNonNull(versionId, "versionId"); if (_e != null) return _e; }
        try {
            Map<String, Object> row = orchestrator.getVersion(versionId);
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(row.get("model_name")).append(" v").append(row.get("version"))
              .append(" [").append(row.get("status")).append("] ===\n");
            sb.append("id          : ").append(row.get("id")).append("\n");
            sb.append("algorithm   : ").append(row.get("algorithm")).append("\n");
            sb.append("samples     : ").append(row.get("sample_count")).append("\n");
            sb.append("trained_at  : ").append(row.get("trained_at")).append("\n");
            sb.append("promoted_at : ").append(row.get("promoted_at") != null ? row.get("promoted_at") : "-").append("\n");
            sb.append("training    : ").append(row.get("training_view")).append(" (target=").append(row.get("target_column")).append(")\n");
            sb.append("hw_handle   : ").append(row.get("heatwave_handle")).append("\n");
            sb.append("\n--- metrics_json ---\n").append(prettyJson(row.get("metrics_json")));
            sb.append("\n--- feature_importance_json ---\n").append(prettyJson(row.get("feature_importance_json")));
            sb.append("\n--- notes ---\n").append(row.get("notes") != null ? row.get("notes") : "-");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "對指定 version 跑一筆 ML_PREDICT_ROW + ML_EXPLAIN_ROW。" +
            "features 為 JSON string(必含訓練 view 的所有欄位,缺值 HW 會當 NaN)。" +
            "範例:{\"strategy_id\":315,\"is_short\":0,\"is_btc\":1,\"is_1h\":1,\"entry_price\":74000,\"hour_of_day\":14,\"day_of_week\":5}" +
            "param: versionId, featuresJson")
    public String predictOne(Long versionId, String featuresJson) {
        { String _e = McpParamValidator.requireNonNull(versionId, "versionId"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(featuresJson, "featuresJson"); if (_e != null) return _e; }
        try {
            Map<String, Object> row = orchestrator.getVersion(versionId);
            String handle = (String) row.get("heatwave_handle");
            if (handle == null || handle.isBlank()) {
                return "❌ model v" + versionId + " 尚無 heatwave_handle (status=" + row.get("status") + ")";
            }
            orchestrator.loadModel(handle);

            @SuppressWarnings("unchecked")
            Map<String, Object> features = objectMapper.readValue(featuresJson, Map.class);

            String prediction = orchestrator.predictOne(handle, features);
            String explanation = orchestrator.explainOne(handle, features);

            StringBuilder sb = new StringBuilder();
            sb.append("=== Prediction ===\n").append(prettyJson(prediction));
            sb.append("\n\n=== Explanation (SHAP-like) ===\n").append(prettyJson(explanation));
            return sb.toString();
        } catch (Exception e) {
            return "❌ predict 失敗: " + e.getMessage();
        }
    }

    // ─── Walk-Forward evaluation (Phase 2 — honest holdout) ─────────────────

    /** Strict ISO date validation — YYYY-MM-DD only, no hours/minutes/seconds. */
    private static final java.util.regex.Pattern ISO_DATE = java.util.regex.Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.WRITE_TRADING})
    @Tool(description = "以時間視窗訓練 signal_scorer(walk-forward 誠實評估用)。" +
            "只用 entry_time ∈ [startDate, endDate] 的 trades 訓,可和 evalOnHoldout 搭配做真 holdout 測試。" +
            "例:trainOnWindow('2024-01-01','2026-02-28', notes) → 留 2026-03 以後當 holdout。" +
            "notes 必填。startDate/endDate 必須 YYYY-MM-DD 格式。" +
            "和 trainSignalScorer 共用 daily cap + concurrent lock(見 getMlLimits)。")
    public String trainOnWindow(String startDate, String endDate, String notes) {
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD 格式";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD 格式";
        if (startDate.compareTo(endDate) >= 0) return "❌ startDate 必須早於 endDate";
        if (notes == null || notes.trim().isEmpty()) return "❌ notes 必填";
        try {
            Map<String, Object> options = new HashMap<>();
            // Exclude row_id, entry_time, and the OTHER target so HW doesn't try to
            // use them as features. replica_count is informative for debugging but
            // would leak the "this setup is popular" signal — exclude it too.
            options.put("exclude_column_list", List.of(
                    "row_id", "entry_time", "target_return", "replica_count"));
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            long id = orchestrator.trainAndRegister(
                    SIGNAL_SCORER, signalScorerTrainingTable(), whereClause,
                    SIGNAL_SCORER_TARGET, "classification",
                    "mcp:trainOnWindow", notes.trim() + " [walk-forward: " + startDate + "→" + endDate + "]",
                    options);
            Map<String, Object> row = orchestrator.getVersion(id);
            return String.format("✅ Walk-forward 訓練完成%n" +
                    "ID=%s  version=v%s  algorithm=%s  samples=%s%n" +
                    "訓練視窗: %s → %s%n%n" +
                    "下一步:evalOnHoldout(%s, '後半段 startDate', '後半段 endDate') 驗證 holdout 表現",
                    row.get("id"), row.get("version"), row.get("algorithm"), row.get("sample_count"),
                    startDate, endDate, row.get("id"));
        } catch (Exception e) {
            return "❌ 訓練失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.WRITE_TRADING})
    @Tool(description = "Regression 版本訓練 — target = return_pct (連續變數,-8% ~ +39.5%, avg +2.1%)。" +
            "使用 vw_signal_regression_v1(V050 features + return_pct as target_return)。" +
            "Hypothesis:classification 把 +0.1% 和 +5% 都標 1,丟掉量級資訊。Regression 保留量級," +
            "讓 model 排序「誰會賺最多」而非「誰會賺」。" +
            "回傳 ID + algorithm(HW AutoML 會選 Gradient Boosting Regressor 之類)。" +
            "param: startDate, endDate(YYYY-MM-DD), notes(必填)")
    public String trainRegressionOnWindow(String startDate, String endDate, String notes) {
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD";
        if (startDate.compareTo(endDate) >= 0) return "❌ startDate 必須早於 endDate";
        if (notes == null || notes.trim().isEmpty()) return "❌ notes 必填";
        try {
            Map<String, Object> options = new HashMap<>();
            // Exclude row_id, entry_time, the binary `profitable` target, and
            // replica_count — see trainOnWindow for rationale.
            options.put("exclude_column_list", List.of(
                    "row_id", "entry_time", "profitable", "replica_count"));
            options.put("task", "regression");
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            long id = orchestrator.trainAndRegister(
                    SIGNAL_SCORER, signalScorerTrainingTable(), whereClause,
                    "target_return", "regression",
                    "mcp:trainRegressionOnWindow",
                    notes.trim() + " [walk-forward regression: " + startDate + "→" + endDate + "]",
                    options);
            Map<String, Object> row = orchestrator.getVersion(id);
            return String.format("✅ Regression 訓練完成%n" +
                    "ID=%s  version=v%s  algorithm=%s  samples=%s%n" +
                    "Target: return_pct (continuous)  Window: %s → %s%n%n" +
                    "下一步:evalRegressionOnHoldout(%s, '後半段 startDate', '後半段 endDate')",
                    row.get("id"), row.get("version"), row.get("algorithm"), row.get("sample_count"),
                    startDate, endDate, row.get("id"));
        } catch (Exception e) {
            return "❌ regression 訓練失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "統一的 model 評估入口 — 依 modelType + dataSource dispatch 到對應評估邏輯,取代舊 5 個 eval* 工具。" +
            "modelType: 'classifier'(單一 ML 分類器,需 versionId) | 'regression'(回歸 model,需 versionId,可選 topN) | " +
            "'ensemble'(多層 ensemble,需 mlVersionId,可選 maxN) | 'ensemble_gate'(ensemble + gate,用 holdoutDays + minSamples)。" +
            "dataSource: 'holdout'(預設,需 startDate/endDate) | 'live_signal_history'(僅 ensemble 支援,用 holdoutPct + threshold 範圍掃描)。" +
            "param 速查:" +
            " classifier+holdout → versionId, startDate, endDate;" +
            " regression+holdout → versionId, startDate, endDate, topN(預設 20);" +
            " ensemble+holdout → mlVersionId, startDate, endDate, maxN(預設 30);" +
            " ensemble+live_signal_history → holdoutPct(預設 30), thresholdMin(預設 40), thresholdMax(預設 80);" +
            " ensemble_gate(僅 holdout 語義) → holdoutDays(預設 30), minSamples(預設 10)。" +
            "底層直接呼叫既有 eval* method,不改業務邏輯。")
    public String evalModel(String modelType,
                            String dataSource,
                            Long versionId,
                            Long mlVersionId,
                            String startDate,
                            String endDate,
                            Integer topN,
                            Integer maxN,
                            Integer holdoutDays,
                            Integer minSamples,
                            Integer holdoutPct,
                            Integer thresholdMin,
                            Integer thresholdMax) {
        if (modelType == null || modelType.isBlank()) {
            return "❌ modelType 必填: classifier | regression | ensemble | ensemble_gate";
        }
        String mt = modelType.trim().toLowerCase();
        String ds = (dataSource == null || dataSource.isBlank()) ? "holdout" : dataSource.trim().toLowerCase();

        // classifier: holdout only
        if ("classifier".equals(mt)) {
            if (!"holdout".equals(ds)) {
                return "❌ classifier 只支援 dataSource=holdout";
            }
            // versionId fallback to mlVersionId for caller convenience
            Long vid = versionId != null ? versionId : mlVersionId;
            return evalOnHoldout(vid, startDate, endDate);
        }

        // regression: holdout only
        if ("regression".equals(mt)) {
            if (!"holdout".equals(ds)) {
                return "❌ regression 只支援 dataSource=holdout";
            }
            Long vid = versionId != null ? versionId : mlVersionId;
            return evalRegressionOnHoldout(vid, startDate, endDate, topN);
        }

        // ensemble: holdout OR live_signal_history
        if ("ensemble".equals(mt)) {
            if ("holdout".equals(ds)) {
                Long vid = mlVersionId != null ? mlVersionId : versionId;
                return evalEnsembleOnHoldout(startDate, endDate, vid, maxN);
            }
            if ("live_signal_history".equals(ds)) {
                return evalEnsembleOnLiveSignalHistory(holdoutPct, thresholdMin, thresholdMax);
            }
            return "❌ ensemble 的 dataSource 需為 holdout 或 live_signal_history";
        }

        // ensemble_gate: holdout (param semantics differ — uses holdoutDays not date window)
        if ("ensemble_gate".equals(mt)) {
            if (!"holdout".equals(ds)) {
                return "❌ ensemble_gate 只支援 dataSource=holdout";
            }
            return evalEnsembleGateOnHoldout(holdoutDays, minSamples);
        }

        return "❌ 未知 modelType=" + modelType + " (支援: classifier | regression | ensemble | ensemble_gate)";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use evalModel(modelType='regression', dataSource='holdout', ...). " +
            "Regression model 的 holdout 評估 — 計算 RMSE / MAE / 方向準確率(predicted_return > 0 視為 BUY)。" +
            "輸出:預測 vs 實際 return 統計 + top-N 高預測 return picks 的實際勝率(等同 topConfidencePicks 但用連續分數)。" +
            "param: versionId(regression model), startDate, endDate, topN(預設 20)")
    public String evalRegressionOnHoldout(Long versionId, String startDate, String endDate, Integer topN) {
        if (versionId == null) return "❌ versionId 必填";
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD";
        int n = (topN == null || topN <= 0) ? 20 : Math.min(topN, 100);
        try {
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            // Materialize eval set + run ML_PREDICT_TABLE; emit a per-row table we can aggregate
            Map<String, Object> model = jdbc.queryForMap(
                    "SELECT heatwave_handle FROM ml_model_registry WHERE id = ?", versionId);
            String handle = (String) model.get("heatwave_handle");
            { String _e = McpParamValidator.requireNonBlank(handle, "handle"); if (_e != null) return _e; }
            orchestrator.loadModel(handle);

            String evalTable = mlSqlProperties.tempTable("_ml_reg_eval_", versionId, System.currentTimeMillis());
            String predTable = evalTable + "_pred";
            try {
                jdbc.update("DROP TABLE IF EXISTS " + evalTable);
                jdbc.update("CREATE TABLE " + evalTable + " AS SELECT * FROM "
                        + signalScorerTrainingTable() + " WHERE " + whereClause);
                Integer evalN = jdbc.queryForObject("SELECT COUNT(*) FROM " + evalTable, Integer.class);
                if (evalN == null || evalN == 0) return "⚠️ holdout 視窗無 trades";
                jdbc.update("DROP TABLE IF EXISTS " + predTable);
                jdbc.update("CALL sys.ML_PREDICT_TABLE(?, ?, ?, NULL)", evalTable, handle, predTable);

                // Aggregate: RMSE, MAE, directional accuracy
                Map<String, Object> stats = jdbc.queryForMap(
                        "SELECT "
                                + "  COUNT(*) AS total, "
                                + "  ROUND(SQRT(AVG(POWER(CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE) - target_return, 2))),5) AS rmse, "
                                + "  ROUND(AVG(ABS(CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE) - target_return)),5) AS mae, "
                                + "  SUM(CASE WHEN (CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE) > 0 AND target_return > 0) "
                                + "         OR (CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE) <= 0 AND target_return <= 0) THEN 1 ELSE 0 END) AS dir_correct, "
                                + "  ROUND(AVG(CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE)),5) AS avg_pred, "
                                + "  ROUND(AVG(target_return),5) AS avg_actual, "
                                + "  SUM(CASE WHEN target_return > 0 THEN 1 ELSE 0 END) AS actual_wins "
                                + "FROM " + predTable);

                long total = ((Number) stats.get("total")).longValue();
                long dirCorrect = ((Number) stats.get("dir_correct")).longValue();
                long actualWins = ((Number) stats.get("actual_wins")).longValue();
                double dirAcc = total > 0 ? 100.0 * dirCorrect / total : 0;
                double dirBaseline = total > 0 ? 100.0 * Math.max(actualWins, total - actualWins) / total : 0;

                // Top-N predicted return — see if "biggest predicted gainers" actually win
                List<Map<String, Object>> top = jdbc.queryForList(
                        "SELECT row_id, "
                                + "       ROUND(CAST(JSON_EXTRACT(ml_results,'$.predictions.target_return') AS DOUBLE),5) AS predicted_return, "
                                + "       target_return AS actual_return, "
                                + "       strategy_id, entry_time "
                                + "FROM " + predTable + " ORDER BY predicted_return DESC LIMIT ?", n);
                int topWins = 0;
                double topAvgActual = 0;
                for (Map<String, Object> r : top) {
                    double a = ((Number) r.get("actual_return")).doubleValue();
                    if (a > 0) topWins++;
                    topAvgActual += a;
                }
                if (!top.isEmpty()) topAvgActual /= top.size();
                double topWinrate = top.isEmpty() ? 0 : 100.0 * topWins / top.size();
                double popWinrate = total > 0 ? 100.0 * actualWins / total : 0;

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("=== evalRegressionOnHoldout v%d window=[%s, %s] ===%n",
                        versionId, startDate, endDate));
                sb.append(String.format("Total trades       : %d (%d wins)%n", total, actualWins));
                sb.append(String.format("RMSE / MAE         : %s / %s (return units, e.g., 0.05 = ±5%%)%n",
                        stats.get("rmse"), stats.get("mae")));
                sb.append(String.format("Avg pred / actual  : %s / %s%n",
                        stats.get("avg_pred"), stats.get("avg_actual")));
                sb.append(String.format("Directional acc    : %.1f%% (baseline %.1f%%)  edge=%+.1fpp  %s%n",
                        dirAcc, dirBaseline, dirAcc - dirBaseline,
                        dirAcc - dirBaseline >= 5 ? "🟢" :
                        dirAcc - dirBaseline >= 2 ? "🟡" : "🔴"));
                sb.append(String.format("%n--- Top-%d by predicted return ---%n", n));
                sb.append(String.format("Top winrate        : %.1f%% (vs population %.1f%%)  lift=%+.1fpp%n",
                        topWinrate, popWinrate, topWinrate - popWinrate));
                sb.append(String.format("Top avg actual ret : %.4f (vs all %s)%n",
                        topAvgActual, stats.get("avg_actual")));
                sb.append("\nSample top picks:\n");
                for (int i = 0; i < Math.min(top.size(), 10); i++) {
                    Map<String, Object> r = top.get(i);
                    double a = ((Number) r.get("actual_return")).doubleValue();
                    sb.append(String.format("  pred=%s  actual=%+.4f  %s  strat=%s  entry=%s%n",
                            r.get("predicted_return"), a, a > 0 ? "✅" : "❌",
                            r.get("strategy_id"), r.get("entry_time")));
                }
                return sb.toString();
            } finally {
                try { jdbc.update("DROP TABLE IF EXISTS " + predTable); } catch (Exception ignored) {}
                try { jdbc.update("DROP TABLE IF EXISTS " + evalTable); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            log.error("[MCP:evalRegressionOnHoldout] failed", e);
            return "❌ regression eval 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use evalModel(modelType='classifier', dataSource='holdout', ...). " +
            "評估指定 model 在時間視窗內 holdout trades 的表現(accuracy/confusion matrix/calibration)。" +
            "傳 versionId 和 holdout 的 [startDate, endDate] YYYY-MM-DD。" +
            "回傳:總數/TP/FP/TN/FN/accuracy/winner_recall/specificity + P(win) 分佈 + 按 decile 的 calibration(實際勝率 vs 預測機率)。" +
            "搭配 trainOnWindow 做誠實 walk-forward 驗證;單獨用也可以(對已訓模型在某段時間的表現打分)。")
    public String evalOnHoldout(Long versionId, String startDate, String endDate) {
        if (versionId == null) return "❌ versionId 必填";
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD 格式";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD 格式";
        try {
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            Map<String, Object> stats = orchestrator.evaluateOnWindow(versionId, whereClause);
            long total   = ((Number) stats.get("total")).longValue();
            long correct = ((Number) stats.get("correct")).longValue();
            long tp = ((Number) stats.get("tp")).longValue();
            long tn = ((Number) stats.get("tn")).longValue();
            long fp = ((Number) stats.get("fp")).longValue();
            long fn = ((Number) stats.get("fn")).longValue();
            long actualWins = ((Number) stats.get("actual_wins")).longValue();
            long actualLosses = ((Number) stats.get("actual_losses")).longValue();
            double accuracy = total > 0 ? 100.0 * correct / total : 0;
            double baseline = total > 0 ? 100.0 * Math.max(actualWins, actualLosses) / total : 0;
            double recall = actualWins > 0 ? 100.0 * tp / actualWins : 0;
            double spec   = actualLosses > 0 ? 100.0 * tn / actualLosses : 0;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== evalOnHoldout v%s window=[%s, %s] ===%n",
                    versionId, startDate, endDate));
            sb.append(String.format("Total trades        : %d%n", total));
            sb.append(String.format("Actual wins/losses  : %d / %d  (winrate=%.1f%%)%n",
                    actualWins, actualLosses, total > 0 ? 100.0 * actualWins / total : 0));
            sb.append(String.format("Accuracy            : %.1f%%  (baseline=%.1f%% if always predict majority)%n",
                    accuracy, baseline));
            sb.append(String.format("Edge vs baseline    : %+.1fpp  %s%n",
                    accuracy - baseline,
                    accuracy - baseline >= 5.0 ? "🟢 有料" :
                    accuracy - baseline >= 2.0 ? "🟡 薄edge" : "🔴 無 alpha"));
            sb.append(String.format("Winner recall (TPR) : %d/%d = %.1f%%%n", tp, actualWins, recall));
            sb.append(String.format("Loser specificity   : %d/%d = %.1f%%%n", tn, actualLosses, spec));
            sb.append(String.format("P(win) range        : %s–%s  avg=%s%n",
                    stats.get("min_p_win"), stats.get("max_p_win"), stats.get("avg_p_win")));
            sb.append(String.format("%nConfusion matrix:%n"));
            sb.append(String.format("                actual=win  actual=loss%n"));
            sb.append(String.format("  pred=win         %5d       %5d%n", tp, fp));
            sb.append(String.format("  pred=loss        %5d       %5d%n", fn, tn));
            sb.append(String.format("%nCalibration by P(win) decile (n / actual winrate%%):%n"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> calib = (List<Map<String, Object>>) stats.get("calibration_by_decile");
            for (Map<String, Object> row : calib) {
                sb.append(String.format("  P ∈ [%.1f, %.1f) : n=%d, actual=%s%%  (ideal=%d-%d%%)%n",
                        ((Number) row.get("p_bucket")).doubleValue(),
                        ((Number) row.get("p_bucket")).doubleValue() + 0.1,
                        ((Number) row.get("n")).intValue(),
                        row.get("actual_winrate_pct"),
                        (int)(((Number) row.get("p_bucket")).doubleValue() * 100),
                        (int)((((Number) row.get("p_bucket")).doubleValue() + 0.1) * 100)));
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 評估失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "回填 bt_backtest_trade V047 indicator snapshot(adx14/rsi14/atr_pct/...) 給歷史 trades。" +
            "每 batch 限 1-1000 筆(預設 200)。會 JOIN md_kline 重算同樣指標,新 trade(backtest 啟動後)已自動帶 snapshot 不需回填。" +
            "回傳:processed / updated / skipped_no_history(kline 不夠)/ errors / remaining_estimate。" +
            "建議多跑幾次直到 remaining=0(2126 筆估計 10-15 batches × 200 筆,每 batch < 30 秒)。" +
            "param: limit(預設 200)")
    public String backfillTradeIndicators(Integer limit) {
        int n = (limit == null || limit <= 0) ? 200 : Math.min(limit, 1000);
        try {
            Map<String, Integer> r = backfillService.backfill(n);
            return String.format("✅ backfill batch: processed=%d updated=%d skipped=%d errors=%d  remaining=%d",
                    r.get("processed"), r.get("updated"),
                    r.get("skipped_no_history"), r.get("errors"),
                    r.get("remaining_estimate"));
        } catch (Exception e) {
            return "❌ backfill 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "#444 — 手動 refresh signal_training 物化表 bt_signal_training_v8_mat。" +
            "目前沒有 nightly orchestrator 自動 refresh；這個 MCP 用於:" +
            "(a) 即時補回 stale snapshot 之後再跑 evalOnHoldout,(b) 啟動後第一次驗證,(c) 改完 view 定義後手動重 build。" +
            "會 DELETE 全表 + INSERT FROM vw_signal_training_v8_dedup,單 transaction(看不到 partial state)。" +
            "view 慢(2-3 min) → MCP timeout 可能觸發,但實際 SQL 在 background 仍會跑完。回傳:before/after row count + elapsedMs。")
    public String refreshSignalTrainingMv() {
        try {
            var stats = matRefresh.refresh();
            return String.format("✅ refreshed bt_signal_training_v8_mat: before=%d → after=%d (deleted=%d inserted=%d)  elapsed=%.1fs",
                    stats.beforeCount(), stats.inserted(), stats.deleted(), stats.inserted(),
                    stats.elapsedMs() / 1000.0);
        } catch (Exception e) {
            return "❌ refresh 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "回傳目前 ML pipeline 的資源限制 + 實際使用狀況。" +
            "AI 跑 trainSignalScorer 前可先 call 這個避免打到 rate limit / concurrent 鎖。" +
            "顯示:每模型並發訓練上限 / 每日訓練次數上限 / 每模型保留版本 / 全站 PROMOTED 上限,以及各模型當前數字。")
    public String getMlLimits() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ML Pipeline Resource Limits ===\n");
        sb.append(String.format("concurrent train per model : 1 (semaphore,跨 JVM 不適用)%n"));
        sb.append(String.format("max trains / model / 24h   : %d%n",
                com.agora.service.ml.MlTrainingOrchestrator.MAX_TRAINS_PER_DAY_PER_MODEL));
        sb.append(String.format("keep last versions / model : %d (older 自動 ARCHIVED)%n",
                com.agora.service.ml.MlTrainingOrchestrator.MAX_VERSIONS_KEPT_PER_MODEL));
        sb.append(String.format("max global PROMOTED        : %d (跨所有 model_name)%n",
                com.agora.service.ml.MlTrainingOrchestrator.MAX_GLOBAL_PROMOTED));
        sb.append("\n=== Current Usage ===\n");
        try {
            List<Map<String, Object>> usage = orchestrator.getUsageStats();
            if (usage.isEmpty()) {
                sb.append("  (no models registered)\n");
            } else {
                for (Map<String, Object> m : usage) {
                    sb.append(String.format("  %-24s versions=%s  last_24h=%s  promoted=%s%n",
                            m.get("model_name"),
                            m.get("total_versions"),
                            m.get("trains_last_24h"),
                            m.get("promoted_count")));
                }
            }
            Integer globalPromoted = (Integer) orchestrator.globalPromotedCount();
            sb.append(String.format("%nGlobal PROMOTED: %d / %d%n",
                    globalPromoted,
                    com.agora.service.ml.MlTrainingOrchestrator.MAX_GLOBAL_PROMOTED));
        } catch (Exception e) {
            sb.append("  ⚠️ usage query failed: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "Deprecated compatibility alias for getMlLimits(). Read-only ML resource status.")
    public String getMlResourceStatus() {
        return getMlLimits();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "將指定 version 升為 PROMOTED。**會 demote 同 model_name 目前的 PROMOTED 版為 ARCHIVED。**" +
            "**notes 必填**:說明為何該版本勝過舊版(像 git commit message)。" +
            "之後 inference 路徑(LiveSignalEvaluator shadow/active mode)會使用此版本。" +
            "若全站 PROMOTED 已達上限(見 getMlLimits),會回 PROMOTED_CAP_EXCEEDED 錯誤。" +
            "param: versionId, notes")
    public String promoteModel(Long versionId, String notes) {
        { String _e = McpParamValidator.requireNonNull(versionId, "versionId"); if (_e != null) return _e; }
        if (notes == null || notes.trim().isEmpty()) {
            return "❌ notes 為必填(說明為何 promote 此版本)";
        }
        try {
            orchestrator.promote(versionId, "mcp:promoteModel", notes.trim());
            return "✅ Promoted id=" + versionId + "。舊 PROMOTED 已 ARCHIVED。";
        } catch (IllegalStateException e) {
            return "❌ " + e.getMessage();
        } catch (Exception e) {
            return "❌ promote 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "DEPRECATED, use getReport(scope='day', focus='ml'). " +
            "取得每日 ML pipeline digest(資料新鮮度 + PROMOTED drift + 候選 READY 比對 + 訓練動作)。" +
            "這是 DailyMlPipelineDigest scheduler(每日 UTC 09:00 發 TG)的 on-demand 版本 — 可隨時查看而不用等 cron。" +
            "**預設 triggerTraining=false**:只報告「會不會訓練」,實際訓練留給 scheduler(避免 MCP 意外觸發 10/day 配額)。" +
            "若真想 MCP 手動觸發訓練,傳 triggerTraining=true 明確意圖（getReport 不支援該參數，需訓練請繼續用此舊工具）。" +
            "回傳 TG HTML 格式字串(含 <b>、<code> tag),可直接貼 TG 或從 stdout 閱讀。")
    public String getDailyMlPipelineDigest(Boolean triggerTraining) {
        boolean trigger = Boolean.TRUE.equals(triggerTraining);
        try {
            return digestService.buildDigest(trigger);
        } catch (Exception e) {
            log.warn("[MCP getDailyMlPipelineDigest] failed: {}", e.getMessage(), e);
            return "❌ digest 產出失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "讀取 ml_pipeline_progress_log 的歷史(由 DailyMlPipelineDigest 每日寫入)。" +
            "回答『上週 vs 這週樣本增加多少、PROMOTED edge 走勢如何、候選 promote 建議出現幾次』。" +
            "param: days 回溯天數(1-180,預設 30)。" +
            "回傳表格:snapshot_date / samples / new24h / PROMOTED v+edge / candidate v+edge / recommend。")
    public String getMlProgressHistory(Integer days) {
        int n = (days == null || days <= 0) ? 30 : Math.min(days, 180);
        java.time.LocalDate cutoff = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(n);
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT snapshot_date, total_samples, new_trades_24h, " +
                            "promoted_version, promoted_edge_short_pp, promoted_edge_long_pp, " +
                            "promoted_n_long, candidate_version, candidate_edge_long_pp, " +
                            "promote_recommended " +
                            "FROM ml_pipeline_progress_log " +
                            "WHERE model_name = ? AND snapshot_date >= ? " +
                            "ORDER BY snapshot_date DESC",
                    SIGNAL_SCORER, cutoff);
            if (rows.isEmpty()) {
                return "⚠️ ml_pipeline_progress_log 近 " + n + " 天無資料。" +
                        "若剛部署 V056 migration,需等次日 09:00 UTC scheduler 首次寫入。";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== ML Pipeline Progress History (signal_scorer, last %dd, %d rows) ===%n", n, rows.size()));
            sb.append("Date         | samples | +24h | PROM v  edge_short  edge_long  n_long | CAND v  edge_long | recommend\n");
            sb.append("-".repeat(110)).append("\n");
            for (Map<String, Object> r : rows) {
                sb.append(String.format("%-12s | %7s | %4s | %6s  %9s  %9s  %6s | %6s  %9s | %s%n",
                        String.valueOf(r.get("snapshot_date")),
                        String.valueOf(r.get("total_samples")),
                        String.valueOf(r.get("new_trades_24h")),
                        fmtInt(r.get("promoted_version"), "-"),
                        fmtPp(r.get("promoted_edge_short_pp")),
                        fmtPp(r.get("promoted_edge_long_pp")),
                        fmtInt(r.get("promoted_n_long"), "-"),
                        fmtInt(r.get("candidate_version"), "-"),
                        fmtPp(r.get("candidate_edge_long_pp")),
                        longVal(r.get("promote_recommended")) == 1L ? "💡 promote" : "-"));
            }
            // Summary footer: sample growth + edge trend
            Object firstSamples = rows.get(rows.size() - 1).get("total_samples");
            Object lastSamples = rows.get(0).get("total_samples");
            long deltaSamples = longVal(lastSamples) - longVal(firstSamples);
            sb.append(String.format("%n📊 樣本增長 %dd: +%d (%s → %s)%n",
                    n, deltaSamples, firstSamples, lastSamples));
            long recommendCount = rows.stream()
                    .filter(r -> longVal(r.get("promote_recommended")) == 1L).count();
            sb.append(String.format("💡 期內 promote 建議次數: %d / %d%n", recommendCount, rows.size()));
            return sb.toString();
        } catch (Exception e) {
            log.warn("[MCP getMlProgressHistory] failed", e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    private String fmtPp(Object v) {
        if (v == null) return "-";
        if (v instanceof Number n) return String.format("%+.1fpp", n.doubleValue());
        return v.toString();
    }

    private String fmtInt(Object v, String fallback) {
        if (v == null) return fallback;
        if (v instanceof Number n) return String.valueOf(n.longValue());
        return v.toString();
    }

    private long longVal(Object v) {
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "#240 顯示當前 bar 實際送進 ML 的每個 feature 值（debug 工具）。" +
            "用於診斷 ML003011 feature mismatch 或驗證 mih_* features 是否正確填入。" +
            "標示 null/0 的 feature（可能資料缺失）。" +
            "params: symbol=BTCUSDT(預設), intervalCode=1h(預設), strategyId=485(可選)")
    public String validateMlFeaturesForBar(String symbol, String intervalCode, Long strategyId) {
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode : "1h";
        long sid = strategyId != null ? strategyId : 0L;
        try {
            List<com.agora.model.MdKline> klinesDesc = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(sym, interval,
                            org.springframework.data.domain.PageRequest.of(0, 60));
            if (klinesDesc.isEmpty()) return "⚠️ 查無 " + sym + " " + interval + " K 線";
            List<com.agora.model.MdKline> klinesAsc = new java.util.ArrayList<>(klinesDesc);
            java.util.Collections.reverse(klinesAsc);
            int lastIdx = klinesAsc.size() - 1;

            com.agora.service.ml.MlInferenceLogger.PreviewResult res =
                    inferenceLogger.previewSync(sym, interval, "LONG", sid, klinesAsc, lastIdx);
            if (res.errorMessage() != null) return "❌ preview 失敗: " + res.errorMessage();

            java.util.Set<String> keyFeatures = java.util.Set.of(
                    "rsi14", "adx14", "strategy_id", "mih_us_vix", "mih_dex_wbtc_net_flow",
                    "mih_fear_greed", "mih_funding_rate", "mih_oi_change_pct_1h",
                    "mih_whale_buy_ratio", "mih_us_10y_yield", "mih_btc_dvol");

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== ML Features for %s %s (sid=%d) ===\n\n", sym, interval, sid));
            sb.append(String.format("p_win=%.4f  decision=%s\n\n", res.pWin(), res.decision()));
            sb.append(String.format("%-35s| %-15s | status%n", "feature", "value"));
            sb.append("-".repeat(58)).append("\n");

            // Show key features first, then others
            java.util.Map<String, Object> features = res.features();
            features.entrySet().stream()
                .sorted((a, b) -> {
                    boolean aKey = keyFeatures.contains(a.getKey()), bKey = keyFeatures.contains(b.getKey());
                    if (aKey != bKey) return aKey ? -1 : 1;
                    return a.getKey().compareTo(b.getKey());
                })
                .forEach(e -> {
                    String k = e.getKey();
                    String v = e.getValue() == null ? "null" : String.valueOf(e.getValue());
                    boolean isNull = e.getValue() == null;
                    boolean isKey = keyFeatures.contains(k);
                    String status = isNull ? "⚠️ null" : (isKey ? "✅" : "");
                    sb.append(String.format("%-35s| %-15s | %s%n", k, v.length() > 15 ? v.substring(0, 12) + "..." : v, status));
                });
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.MARKET_DATA})
    @Tool(description = "模擬 ML PROMOTED 模型對『當前市場最新 K 線』的預測(不實際下單,純預覽)。" +
            "回答:『若我現在啟用策略 X 且觸發 BUY,ML 會建議 PASS 還是 BLOCK?』" +
            "param: symbol(BTCUSDT/ETHUSDT), intervalCode(1h/4h), side(LONG/SHORT,預設 LONG), strategyId(可選,影響 feature)")
    public String previewMlFilter(String symbol, String intervalCode, String side, Long strategyId) {
        { String _e = McpParamValidator.requireNonBlank(symbol, "symbol"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(intervalCode, "intervalCode"); if (_e != null) return _e; }
        String sd = (side == null || side.isBlank()) ? "LONG" : side.toUpperCase();
        long sid = strategyId == null ? 0L : strategyId;
        try {
            List<com.agora.model.MdKline> klinesDesc = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(symbol, intervalCode,
                            org.springframework.data.domain.PageRequest.of(0, 60));
            if (klinesDesc.isEmpty()) {
                return "⚠️ 查無 " + symbol + " " + intervalCode + " 最近 K 線,無法預測";
            }
            // reverse to chronological asc
            List<com.agora.model.MdKline> klinesAsc = new java.util.ArrayList<>(klinesDesc);
            java.util.Collections.reverse(klinesAsc);
            int lastIdx = klinesAsc.size() - 1;
            com.agora.service.ml.MlInferenceLogger.PreviewResult res =
                    inferenceLogger.previewSync(symbol, intervalCode, sd, sid, klinesAsc, lastIdx);
            if (res.errorMessage() != null) {
                return "❌ preview 失敗: " + res.errorMessage();
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Preview ML v%d on %s %s %s ===%n",
                    res.modelVersionId(), symbol, intervalCode, sd));
            sb.append(String.format("bar_close=%s  entry_price feature=%.2f%n",
                    klinesAsc.get(lastIdx).getOpenTime(),
                    klinesAsc.get(lastIdx).getClosePrice().doubleValue()));
            sb.append(String.format("p_win    = %.4f%n", res.pWin()));
            sb.append(String.format("threshold= %.2f%n", res.threshold()));
            sb.append(String.format("decision = %s%n",
                    "PASS".equals(res.decision()) ? "✅ PASS (ML 看好)" : "🚫 BLOCK (ML 看壞)"));
            sb.append("\n-- key features sent to model --\n");
            String[] interesting = {"adx14", "rsi14", "atr_pct", "volume_ratio_ma20",
                    "close_vs_ema50_pct", "ema20_slope_pct", "bb_width_pct",
                    "hour_of_day", "day_of_week"};
            for (String k : interesting) {
                Object v = res.features().get(k);
                if (v != null) sb.append(String.format("  %s = %s%n", k, v));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[MCP previewMlFilter] failed", e);
            return "❌ preview 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.ANALYTICS})
    @Tool(description = "#241 按市場 regime 分層的模型表現分析。" +
            "回答「v18 是在哪個 regime 下失效的？」—— 將 ml_inference_log 按 regime 欄位分組，" +
            "計算每個 regime 的推理次數、PASS 率、avg p_win。" +
            "有助診斷 v18 -27.9pp near-window degradation 是否為 regime 特定問題。" +
            "params: versionId(預設 PROMOTED), days=回溯天數(預設 30)")
    public String getModelRegimePerformance(Long versionId, Integer days) {
        int d = days != null ? Math.min(Math.max(days, 1), 90) : 30;
        java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);
        try {
            // Resolve version id
            Long vid = versionId;
            if (vid == null) {
                List<Map<String, Object>> promoted = jdbc.queryForList(
                        "SELECT id FROM ml_model_registry WHERE model_name='signal_scorer' AND status='PROMOTED' LIMIT 1");
                if (promoted.isEmpty()) return "⚠️ 無 PROMOTED 模型";
                vid = ((Number) promoted.get(0).get("id")).longValue();
            }

            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT regime, COUNT(*) as total, " +
                    "SUM(CASE WHEN decision='PASS' THEN 1 ELSE 0 END) as passes, " +
                    "AVG(score) as avg_pwin " +
                    "FROM ml_inference_log " +
                    "WHERE model_version_id=? AND predicted_at>=? AND regime IS NOT NULL " +
                    "GROUP BY regime ORDER BY total DESC",
                    vid, since);

            if (rows.isEmpty()) return String.format("ℹ️ v%d 在過去 %d 天無 ML 推理記錄", vid, d);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Model v%d Regime Performance (過去 %dd) ===\n\n", vid, d));
            sb.append(String.format("%-16s| %5s | %5s | %6s | PASS率%n", "regime", "total", "PASS", "avgPwin"));
            sb.append("-".repeat(50)).append("\n");

            long grandTotal = 0;
            for (var row : rows) {
                long total = ((Number) row.get("total")).longValue();
                long passes = ((Number) row.get("passes")).longValue();
                double avgPwin = row.get("avg_pwin") != null ? ((Number) row.get("avg_pwin")).doubleValue() : 0;
                double passRate = total > 0 ? (double) passes / total * 100 : 0;
                grandTotal += total;
                String regime = String.valueOf(row.get("regime"));
                String emoji = regime.contains("UP") ? "🟢" : regime.contains("DOWN") ? "🔴" : "🟡";
                sb.append(String.format("%-16s| %5d | %5d | %6.4f | %.0f%% %s%n",
                        regime, total, passes, avgPwin, passRate, emoji));
            }
            sb.append(String.format("\n총 %d 筆推理\n", grandTotal));
            sb.append("💡 TRENDING_DOWN 的 PASS 率低是正常的（策略偏空頭方向）。\n");
            sb.append("   若 SIDEWAYS 的 avg_pwin 也很低，說明模型在橫盤市場失效，需 regime-conditional threshold。");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "#226 查看 ml_inference_log 最近 N 筆推理記錄（tail）。" +
            "顯示：predicted_at、model version、p_win、decision、regime、key features 摘要。" +
            "用於確認 v18 是否真的被使用、調試 ML gate 行為、驗證 mih_* features 是否正確傳入。" +
            "params: strategyId（可選，不填則顯示全部）, limit=筆數(預設 20, 最多 100)")
    public String getRecentMlInferences(Long strategyId, Integer limit) {
        int lim = limit != null ? Math.min(Math.max(limit, 1), 100) : 20;
        try {
            // ml_inference_log 無 strategy_id 欄位，透過 live_signal_id JOIN bt_live_signal 過濾
            String sql = strategyId != null
                    ? "SELECT m.predicted_at, m.model_version_id, m.score, m.decision, m.regime, m.features_json " +
                      "FROM ml_inference_log m " +
                      "JOIN bt_live_signal s ON m.live_signal_id = s.id " +
                      "WHERE s.strategy_id=? ORDER BY m.id DESC LIMIT ?"
                    : "SELECT m.predicted_at, m.model_version_id, m.score, m.decision, m.regime, m.features_json, s.strategy_id " +
                      "FROM ml_inference_log m " +
                      "LEFT JOIN bt_live_signal s ON m.live_signal_id = s.id " +
                      "ORDER BY m.id DESC LIMIT ?";
            List<Map<String, Object>> rows = strategyId != null
                    ? jdbc.queryForList(sql, strategyId, lim)
                    : jdbc.queryForList(sql, lim);

            if (rows.isEmpty()) return "ℹ️ 無 ML 推理記錄" + (strategyId != null ? "（strategy " + strategyId + "）" : "");

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== 最近 %d 筆 ML 推理記錄%s ===\n\n",
                    rows.size(), strategyId != null ? "（strategy #" + strategyId + "）" : ""));
            for (var row : rows) {
                long version = row.get("model_version_id") != null ? ((Number) row.get("model_version_id")).longValue() : 0;
                double pwin = row.get("score") != null ? ((Number) row.get("score")).doubleValue() : 0;
                String decision = String.valueOf(row.get("decision"));
                String regime = String.valueOf(row.get("regime"));
                String time = String.valueOf(row.get("predicted_at")).substring(0, 16);
                sb.append(String.format("[v%d] %s  p_win=%.4f  %s  regime=%s",
                        version, time, pwin, "PASS".equals(decision) ? "✅ PASS" : "🚫 BLOCK", regime));
                if (strategyId == null && row.get("strategy_id") != null)
                    sb.append("  sid=").append(row.get("strategy_id"));
                // Show top 3 features from features_json
                String featJson = row.get("features_json") != null ? row.get("features_json").toString() : "";
                if (!featJson.isEmpty() && featJson.length() > 10) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(featJson);
                        java.util.List<String> keyFeatures = java.util.List.of("rsi14", "adx14", "strategy_id", "mih_us_vix", "mih_dex_wbtc_net_flow");
                        StringBuilder feat = new StringBuilder();
                        for (String k : keyFeatures) {
                            if (node.has(k) && !node.get(k).isNull())
                                feat.append(k).append("=").append(String.format("%.2f", node.get(k).asDouble())).append(" ");
                        }
                        if (feat.length() > 0) sb.append("\n  features: ").append(feat.toString().trim());
                    } catch (Exception ignored) {}
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.ANALYTICS})
    @Tool(description = "#239 跨版本 feature importance 對比：並排顯示兩個模型版本的 permutation importance，" +
            "標示新增/移除/顯著變化的 feature。用於驗證 mih_* 特徵在 v18 中是否真的被學習到。" +
            "param: v1(必填), v2(必填) — 兩個 model version ID")
    public String compareFeatureImportanceAcrossVersions(Long v1, Long v2) {
        if (v1 == null || v2 == null) return "❌ v1, v2 皆必填";
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, version, feature_importance_json FROM ml_model_registry " +
                    "WHERE id IN (?, ?) ORDER BY id", v1, v2);
            if (rows.size() < 2) return "❌ 找不到指定版本";

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            java.util.Map<String, Double>[] importances = new java.util.Map[2];
            long[] ids = new long[2];
            for (int i = 0; i < 2; i++) {
                ids[i] = ((Number) rows.get(i).get("id")).longValue();
                String json = String.valueOf(rows.get(i).get("feature_importance_json"));
                com.fasterxml.jackson.databind.JsonNode node = om.readTree(json);
                com.fasterxml.jackson.databind.JsonNode perm = node.path("permutation_importance");
                java.util.Map<String, Double> imp = new java.util.LinkedHashMap<>();
                perm.fields().forEachRemaining(e -> imp.put(e.getKey(), e.getValue().asDouble()));
                importances[i] = imp;
            }

            java.util.Set<String> allKeys = new java.util.LinkedHashSet<>();
            allKeys.addAll(importances[0].keySet()); allKeys.addAll(importances[1].keySet());

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Feature Importance: v%d vs v%d ===\n\n", ids[0], ids[1]));
            sb.append(String.format("%-30s| v%-6d | v%-6d | Δ%n", "feature", ids[0], ids[1]));
            sb.append("-".repeat(55)).append("\n");

            allKeys.stream()
                .sorted((a, b) -> {
                    double d1 = importances[0].getOrDefault(b, 0.0) - importances[0].getOrDefault(a, 0.0);
                    return (int)(d1 * 10000);
                })
                .forEach(k -> {
                    double imp1 = importances[0].getOrDefault(k, 0.0);
                    double imp2 = importances[1].getOrDefault(k, 0.0);
                    double delta = imp2 - imp1;
                    String flag = "";
                    if (!importances[0].containsKey(k)) flag = " [NEW]";
                    else if (!importances[1].containsKey(k)) flag = " [REMOVED]";
                    else if (Math.abs(delta) > 0.005) flag = delta > 0 ? " ↑" : " ↓";
                    sb.append(String.format("%-30s| %7.4f | %7.4f | %+7.4f%s%n", k, imp1, imp2, delta, flag));
                });
            sb.append("\n💡 strategy_id 重要度高可能表示模型在記憶策略 ID 而非學習市場特徵（偽 alpha）。");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.ANALYTICS})
    @Tool(description = "#233 ML 模型 p_win 校準度驗證。" +
            "當模型預測 p_win=0.3 時，真的有 30% 的交易獲利嗎？" +
            "按 p_win bucket 統計實際勝率，計算 Expected Calibration Error (ECE)。" +
            "若校準差（例如 p_win=0.6 但實際勝率只有 20%），說明模型過度自信，threshold 設定需要調整。" +
            "params: versionId(預設 PROMOTED), days=回溯天數(預設 90)")
    public String getModelCalibration(Long versionId, Integer days) {
        int d = days != null ? Math.min(Math.max(days, 7), 365) : 90;
        java.time.LocalDateTime since = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);
        try {
            Long vid = versionId;
            if (vid == null) {
                List<Map<String, Object>> promoted = jdbc.queryForList(
                        "SELECT id FROM ml_model_registry WHERE model_name='signal_scorer' AND status='PROMOTED' LIMIT 1");
                if (promoted.isEmpty()) return "⚠️ 無 PROMOTED 模型";
                vid = ((Number) promoted.get(0).get("id")).longValue();
            }
            // Join ml_inference_log with bt_live_signal to get actual outcomes
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT FLOOR(mil.score * 10) / 10.0 AS pwin_bucket, " +
                    "COUNT(*) AS cnt, " +
                    "SUM(CASE WHEN s.realized_pnl > 0 THEN 1 ELSE 0 END) AS wins, " +
                    "SUM(CASE WHEN s.realized_pnl IS NOT NULL THEN 1 ELSE 0 END) AS settled " +
                    "FROM ml_inference_log mil " +
                    "LEFT JOIN bt_live_signal s ON s.id = mil.live_signal_id AND s.exit_time IS NOT NULL " +
                    "WHERE mil.model_version_id=? AND mil.predicted_at>=? AND mil.decision='PASS' " +
                    "GROUP BY pwin_bucket ORDER BY pwin_bucket",
                    vid, since);

            if (rows.isEmpty()) return String.format("ℹ️ v%d 在過去 %dd 無 PASS 推理記錄", vid, d);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Model v%d Calibration (過去 %dd, PASS 決策) ===\n\n", vid, d));
            sb.append(String.format("%-10s| %5s | %7s | %7s | calibration%n",
                    "p_win_bucket", "total", "settled", "win_rate"));
            sb.append("-".repeat(52)).append("\n");

            double totalEce = 0; int buckets = 0;
            for (var row : rows) {
                double bucket = ((Number) row.get("pwin_bucket")).doubleValue();
                long cnt = ((Number) row.get("cnt")).longValue();
                long settled = ((Number) row.get("settled")).longValue();
                long wins = row.get("wins") != null ? ((Number) row.get("wins")).longValue() : 0;
                double winRate = settled > 0 ? (double) wins / settled : -1;
                String calStr = settled < 3 ? "(n 太少)" : String.format("%.1f%%", winRate * 100);
                String status = settled < 3 ? "⚪" : (Math.abs(winRate - bucket) < 0.15 ? "✅" : (winRate > bucket ? "🟢 over" : "🔴 under"));
                sb.append(String.format("%-10.1f | %5d | %7d | %-7s | %s%n",
                        bucket, cnt, settled, calStr, status));
                if (settled >= 3) { totalEce += Math.abs(winRate - bucket) * cnt; buckets++; }
            }
            if (buckets > 0) {
                long totalCnt = rows.stream().mapToLong(r -> ((Number) r.get("cnt")).longValue()).sum();
                sb.append(String.format("\nECE ≈ %.3f（0=完美，>0.1=需重新校準）", totalEce / Math.max(totalCnt, 1)));
            }
            sb.append("\n💡 strategy_id 重要度最高可能導致模型記憶 ID 而非市場特徵，校準差時考慮排除 strategy_id 特徵重新訓練。");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @Tool(description = "統計 ml_inference_log 最近 N 天的 shadow-mode 預測分布。" +
            "回答『shadow ML 有沒有在跑 / 預測傾向 PASS 還是 BLOCK / 樣本夠不夠驗證 gate』。" +
            "當 actual_outcome 有 backfill 後可加總勝率對比,目前 V1 尚未接 backfill,只顯示決策分布。" +
            "param: days(1-90,預設 14)")
    public String getMlShadowStats(Integer days) {
        int n = (days == null || days <= 0) ? 14 : Math.min(days, 90);
        try {
            List<Map<String, Object>> byDay = jdbc.queryForList(
                    "SELECT DATE(predicted_at) AS d, model_version_id, " +
                            "SUM(CASE WHEN decision='SHADOW_PASS' THEN 1 ELSE 0 END) AS passes, " +
                            "SUM(CASE WHEN decision='SHADOW_BLOCK' THEN 1 ELSE 0 END) AS blocks, " +
                            "AVG(score) AS avg_p_win, " +
                            "SUM(CASE WHEN actual_outcome=1 THEN 1 ELSE 0 END) AS confirmed_wins, " +
                            "SUM(CASE WHEN actual_outcome=0 THEN 1 ELSE 0 END) AS confirmed_losses " +
                            "FROM ml_inference_log " +
                            "WHERE predicted_at >= ? " +
                            "GROUP BY DATE(predicted_at), model_version_id " +
                            "ORDER BY d DESC, model_version_id",
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(n));
            if (byDay.isEmpty()) {
                return "⚠️ 近 " + n + " 天 ml_inference_log 無資料。若 0 啟用策略則 shadow mode 不會觸發。" +
                        "啟用至少 1 個策略並等 K 線收盤,就會開始寫入。";
            }
            Map<String, Object> totals = jdbc.queryForMap(
                    "SELECT COUNT(*) AS total, " +
                            "SUM(CASE WHEN decision='SHADOW_PASS' THEN 1 ELSE 0 END) AS passes, " +
                            "SUM(CASE WHEN decision='SHADOW_BLOCK' THEN 1 ELSE 0 END) AS blocks, " +
                            "SUM(CASE WHEN actual_outcome IS NOT NULL THEN 1 ELSE 0 END) AS with_outcome " +
                            "FROM ml_inference_log WHERE predicted_at >= ?",
                    java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(n));
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== ML Shadow Stats (last %dd, %d daily rows) ===%n", n, byDay.size()));
            sb.append("Date         | v  | PASS | BLOCK | avg p_win | wins | losses\n");
            sb.append("-".repeat(70)).append("\n");
            for (Map<String, Object> r : byDay) {
                Double avgP = r.get("avg_p_win") instanceof Number ap ? ap.doubleValue() : null;
                sb.append(String.format("%-12s | %2s | %4s | %5s | %9s | %4s | %s%n",
                        String.valueOf(r.get("d")),
                        fmtInt(r.get("model_version_id"), "-"),
                        fmtInt(r.get("passes"), "0"),
                        fmtInt(r.get("blocks"), "0"),
                        avgP == null ? "-" : String.format("%.4f", avgP),
                        fmtInt(r.get("confirmed_wins"), "0"),
                        fmtInt(r.get("confirmed_losses"), "0")));
            }
            sb.append(String.format("%n📊 總計 %sd: %s 筆 (PASS %s, BLOCK %s)%n",
                    n, fmtInt(totals.get("total"), "0"),
                    fmtInt(totals.get("passes"), "0"),
                    fmtInt(totals.get("blocks"), "0")));
            sb.append(String.format("🎯 已有實際結果驗證: %s 筆(其餘倉位未平或需 backfill)%n",
                    fmtInt(totals.get("with_outcome"), "0")));

            // Regime-stratified breakdown (V069 — regime column may be NULL for pre-V069 rows)
            try {
                List<Map<String, Object>> byRegime = jdbc.queryForList(
                        "SELECT COALESCE(regime, 'UNKNOWN') AS regime, COUNT(*) AS total, " +
                                "SUM(CASE WHEN decision='SHADOW_PASS' THEN 1 ELSE 0 END) AS passes, " +
                                "AVG(score) AS avg_p_win, " +
                                "SUM(CASE WHEN actual_outcome=1 THEN 1 ELSE 0 END) AS wins, " +
                                "SUM(CASE WHEN actual_outcome=0 THEN 1 ELSE 0 END) AS losses " +
                                "FROM ml_inference_log WHERE predicted_at >= ? " +
                                "GROUP BY regime ORDER BY FIELD(regime,'BULL','SIDEWAYS','BEAR','UNKNOWN')",
                        java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(n));
                if (!byRegime.isEmpty()) {
                    sb.append("\n🌐 Regime 分層統計:\n");
                    sb.append(String.format("  %-9s | %5s | %4s | %8s | %4s | %6s%n",
                            "Regime", "total", "PASS", "avg_pWin", "wins", "losses"));
                    sb.append("  " + "-".repeat(50) + "\n");
                    for (Map<String, Object> r : byRegime) {
                        Double avgP = r.get("avg_p_win") instanceof Number ap ? ap.doubleValue() : null;
                        String icon = switch (String.valueOf(r.get("regime"))) {
                            case "BULL"     -> "🟢";
                            case "SIDEWAYS" -> "🟡";
                            case "BEAR"     -> "🔴";
                            default         -> "⚪";
                        };
                        sb.append(String.format("  %s %-8s | %5s | %4s | %8s | %4s | %6s%n",
                                icon, r.get("regime"),
                                fmtInt(r.get("total"), "0"),
                                fmtInt(r.get("passes"), "0"),
                                avgP == null ? "-" : String.format("%.4f", avgP),
                                fmtInt(r.get("wins"), "0"),
                                fmtInt(r.get("losses"), "0")));
                    }
                }
            } catch (Exception ignored) {
                // regime column absent on older schema — skip breakdown gracefully
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[MCP getMlShadowStats] failed", e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "拉取指定 model 在 holdout 視窗上「P(win) 最高的 top-N 筆 trade」實際結果。" +
            "用來驗證:即使 binary 0.5 閾值上 edge 為 0,model 在高信心區是否仍有真實 +Npp edge。" +
            "v6 上 calibration 顯示 P>=0.8 bucket 真實勝率 50%(vs 30% baseline)— 這個 tool 抓全 holdout 的 top-20 確認。" +
            "回傳:top-N 列表(p_win/實際/strategy_id/entry_time)+ top-N 整體勝率對比 baseline 的 lift。" +
            "param: versionId, startDate, endDate(YYYY-MM-DD), topN(預設 20)")
    public String topConfidencePicks(Long versionId, String startDate, String endDate, Integer topN) {
        if (versionId == null) return "❌ versionId 必填";
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD";
        int n = (topN == null || topN <= 0) ? 20 : Math.min(topN, 100);
        try {
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            // Population baseline = win rate across UNIQUE setups in the holdout
            // window (V052 dedup view). Pre-V052 we used raw vw_signal_training_v3
            // which inflated baseline by 8x replication (29.78% raw vs 22.2% dedup
            // for the 2026-03 holdout).
            Map<String, Object> popMap = jdbc.queryForMap(
                    "SELECT COUNT(*) AS pop_n, "
                            + "       ROUND(100*AVG(profitable),2) AS pop_winrate_pct "
                            + "FROM " + signalScorerTrainingTable() + " WHERE " + whereClause);
            long popN = ((Number) popMap.get("pop_n")).longValue();
            double popWinrate = popMap.get("pop_winrate_pct") instanceof Number
                    ? ((Number) popMap.get("pop_winrate_pct")).doubleValue() : 0;

            List<Map<String, Object>> picks = orchestrator.topConfidencePicks(versionId, whereClause, n);
            if (picks.isEmpty()) return "⚠️ 無資料 — 視窗無 trades 或 model 未 ready";

            int wins = 0;
            for (Map<String, Object> r : picks) {
                if (((Number) r.get("actual")).intValue() == 1) wins++;
            }
            int total = picks.size();
            double topWinrate = 100.0 * wins / total;
            double lift = topWinrate - popWinrate;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== topConfidencePicks v%d window=[%s, %s] top-%d ===%n",
                    versionId, startDate, endDate, n));
            sb.append(String.format("Population (full %d trades): winrate=%.2f%%%n", popN, popWinrate));
            sb.append(String.format("Top-%d high-conf picks    : winrate=%.2f%% (wins=%d losses=%d)%n",
                    total, topWinrate, wins, total - wins));
            sb.append(String.format("Lift over baseline       : %+.1fpp  %s%n%n",
                    lift,
                    lift >= 15 ? "🟢 強烈 alpha (top-decile selectivity works)" :
                    lift >= 5  ? "🟡 有 edge,可考慮 selectivity gate" :
                    lift >= 0  ? "⚪ 弱 edge / 噪音邊緣" : "🔴 高信心反指標 — bug 或 anti-pattern"));
            sb.append("--- Top picks (p_win desc) ---\n");
            for (Map<String, Object> r : picks) {
                int actual = ((Number) r.get("actual")).intValue();
                sb.append(String.format("  p=%s  %s  strat=%s  entry=%s%n",
                        r.get("p_win"),
                        actual == 1 ? "✅ WIN " : "❌ LOSS",
                        r.get("strategy_id"),
                        r.get("entry_time")));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP:topConfidencePicks] failed", e);
            return "❌ topConfidencePicks 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Bootstrap 95% CI 驗證 top-N 高信心 picks 的 lift 是否統計顯著(防 small-N 假象)。" +
            "在 holdout 視窗上對 (predicted p_win, actual profitable) pairs 抽樣 nIter 次(預設 1000)," +
            "每次計算 top-N lift,回 mean / stddev / 95% CI / 機率為正比例。" +
            "解讀:CI 全在正(下界 > 0)→ 真 alpha;CI 包含 0 → noise 邊緣;CI 全負 → anti-pattern。" +
            "v9 dedup +27.8pp 在 N=45 holdout 是否顯著就靠這個。" +
            "param: versionId, startDate, endDate(YYYY-MM-DD), topN(預設 10), nIter(預設 1000, 最多 5000)")
    public String bootstrapTopNLift(Long versionId, String startDate, String endDate,
                                     Integer topN, Integer nIter) {
        if (versionId == null) return "❌ versionId 必填";
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD";
        int n = (topN == null || topN <= 0) ? 10 : Math.min(topN, 100);
        int iters = (nIter == null || nIter <= 0) ? 1000 : Math.min(nIter, 5000);
        try {
            String whereClause = "entry_time >= '" + startDate + "' AND entry_time <= '" + endDate + " 23:59:59'";
            Map<String, Object> r = orchestrator.bootstrapTopNLift(versionId, whereClause, n, iters);
            double meanLift = ((Number) r.get("lift_mean_pp")).doubleValue();
            double sd       = ((Number) r.get("lift_stddev_pp")).doubleValue();
            double lo       = ((Number) r.get("ci95_lo_pp")).doubleValue();
            double hi       = ((Number) r.get("ci95_hi_pp")).doubleValue();
            double med      = ((Number) r.get("median_lift_pp")).doubleValue();
            double pPos     = ((Number) r.get("pct_iters_positive")).doubleValue();
            double winrate  = ((Number) r.get("holdout_winrate_pct")).doubleValue();

            String verdict;
            if (lo > 5)         verdict = "🟢 強統計顯著 — CI 全 > 5pp,真 alpha";
            else if (lo > 0)    verdict = "🟢 顯著 — CI 全 > 0,但下界較弱";
            else if (med > 5)   verdict = "🟡 邊緣 — 中位數正但 CI 跨 0,可能 small-N noise";
            else if (med > 0)   verdict = "⚪ 弱訊號 — 中位數略正,信心低";
            else                verdict = "🔴 無顯著 lift — 不要用 top-N selectivity 操作";

            return String.format(
                    "=== Bootstrap top-%d lift v%d window=[%s, %s] ===%n" +
                            "Holdout n=%d  population winrate=%.2f%%%n" +
                            "Bootstrap iters=%d (seed=42, reproducible)%n%n" +
                            "Lift distribution:%n" +
                            "  mean   : %+.2fpp%n" +
                            "  stddev : %.2fpp%n" +
                            "  median : %+.2fpp%n" +
                            "  95%% CI : [%+.2fpp, %+.2fpp]%n" +
                            "  P(lift > 0) : %.1f%%%n%n" +
                            "Verdict: %s",
                    n, versionId, startDate, endDate,
                    ((Number) r.get("holdout_n")).intValue(), winrate,
                    iters, meanLift, sd, med, lo, hi, pPos, verdict);
        } catch (Exception e) {
            log.error("[MCP:bootstrapTopNLift] failed", e);
            return "❌ bootstrap 失敗: " + e.getMessage();
        }
    }

    // ─── Ensemble scoring + multi-model architecture validation ────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "對單一 trade entry 跑「多層多模型」評分:HeatWave ML + Gemini Flash + Groq Llama 並行,合成 ensemble P(win)。" +
            "用於 ML 0pp edge 後驗證:LLM regime 推理是否能補強純數值模型。" +
            "param: featuresJson (vw_signal_training_v2 欄位,例 {\"strategy_id\":315,\"is_short\":0,\"is_btc\":1,\"is_1h\":1,\"entry_price\":74000,\"hour_of_day\":14,\"day_of_week\":5,\"adx14\":28.5,\"rsi14\":52,\"atr_pct\":0.012,\"volume_ratio_ma20\":1.3,\"close_vs_ema50_pct\":0.018,\"ema20_slope_pct\":0.005,\"bb_width_pct\":0.025})。" +
            "mlVersionId 可選(null = 跳過 ML 層,只跑 LLM)。" +
            "回傳:每層 p_win + reasoning + ensemble p_win + 決策(BUY/SKIP) + 共識類型。")
    public String scoreSignalMultiModel(String featuresJson, Long mlVersionId,
                                         String symbol, String side, java.math.BigDecimal currentPrice) {
        { String _e = McpParamValidator.requireNonBlank(featuresJson, "featuresJson"); if (_e != null) return _e; }
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (side == null || side.isBlank()) side = "LONG";
        if (currentPrice == null) currentPrice = java.math.BigDecimal.ZERO;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> features = objectMapper.readValue(featuresJson, Map.class);
            com.agora.service.ml.SignalScorerEnsemble.EnsembleResult r = ensemble.score(
                    features, mlVersionId, symbol, side, currentPrice);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Ensemble Score: %s %s @ $%s ===%n", symbol, side, currentPrice));
            for (Map.Entry<String, com.agora.service.ml.SignalScorerEnsemble.LayerOutput> e : r.layers().entrySet()) {
                com.agora.service.ml.SignalScorerEnsemble.LayerOutput lo = e.getValue();
                if (lo.success()) {
                    sb.append(String.format("  %-26s p_win=%.3f  latency=%dms%n",
                            e.getKey() + " (" + lo.providerName() + ")",
                            lo.pWin(), lo.latencyMs()));
                    if (lo.reasoning() != null) sb.append("    └─ ").append(lo.reasoning()).append("\n");
                } else {
                    sb.append(String.format("  %-26s ❌ %s%n",
                            e.getKey() + " (" + lo.providerName() + ")", lo.error()));
                }
            }
            sb.append("\n--- Ensemble ---\n");
            if (r.ensemblePWin() != null) {
                sb.append(String.format("Ensemble p_win    : %s (mean of %d layers)%n",
                        r.ensemblePWin(), r.successCount()));
                sb.append(String.format("Decision          : %s%n", r.decision()));
                sb.append(String.format("Consensus type    : %s%n", r.consensusType()));
            } else {
                sb.append("⚠️ ALL LAYERS FAILED: ").append(r.error()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ ensemble 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC})
    @Tool(description = "只跑單一 LLM provider 的 score-signal(用於 ablation 測試)。" +
            "providerName 可選 'gemini-flash' 或 'groq-llama-3.3-70b'(預設 gemini-flash)。" +
            "回傳:p_win + regime + reasoning + latency。比 scoreSignalMultiModel 快 50%(只 1 個 API call)。")
    public String scoreSignalLlmOnly(String featuresJson, String providerName,
                                       String symbol, String side, java.math.BigDecimal currentPrice) {
        { String _e = McpParamValidator.requireNonBlank(featuresJson, "featuresJson"); if (_e != null) return _e; }
        if (providerName == null || providerName.isBlank()) providerName = "gemini-flash";
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (side == null || side.isBlank()) side = "LONG";
        if (currentPrice == null) currentPrice = java.math.BigDecimal.ZERO;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> features = objectMapper.readValue(featuresJson, Map.class);
            com.agora.service.ml.SignalScorerEnsemble.LayerOutput lo = ensemble.scoreLlmOnly(
                    features, providerName, symbol, side, currentPrice);
            if (!lo.success()) return "❌ " + providerName + " failed: " + lo.error();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== LLM-only score: %s ===%n", providerName));
            sb.append(String.format("p_win    : %.3f%n", lo.pWin()));
            sb.append(String.format("latency  : %dms%n", lo.latencyMs()));
            sb.append(String.format("reasoning: %s%n", lo.reasoning()));
            return sb.toString();
        } catch (Exception e) {
            return "❌ scoreSignalLlmOnly 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use evalModel(modelType='ensemble', dataSource='holdout', ...). " +
            "在 holdout 視窗上批次評估「多層多模型」架構 — 比較 ML / Gemini / Groq / Ensemble 的 walk-forward 邊際。" +
            "對視窗內 trades 各跑一次 ensemble,記錄每層 p_win 與實際 profitable,計算每層 + ensemble 的 accuracy / edge。" +
            "成本:maxN trades × 2 LLM calls(parallel) ≈ maxN × 3.5 秒。建議 maxN=30(~2 min);加大需確認 timeout。" +
            "param: startDate/endDate(YYYY-MM-DD), mlVersionId, maxN(預設 30, 上限 100)。" +
            "回傳:per-layer 準確率 / Edge vs baseline / consensus 與 ML 一致率。")
    public String evalEnsembleOnHoldout(String startDate, String endDate, Long mlVersionId, Integer maxN) {
        if (startDate == null || !ISO_DATE.matcher(startDate).matches()) return "❌ startDate 需 YYYY-MM-DD";
        if (endDate == null || !ISO_DATE.matcher(endDate).matches())   return "❌ endDate 需 YYYY-MM-DD";
        if (mlVersionId == null) return "❌ mlVersionId 必填(可指定 v4 或最新 walk-forward 版本)";
        int n = (maxN == null || maxN <= 0) ? 30 : Math.min(maxN, 100);
        try {
            // Read from v5_dedup view (V052) so eval is on UNIQUE setups, not
            // 8x-replicated trades. profitable is majority-vote target.
            // Older v2-v7 models trained on raw views will see this column set
            // at inference — HeatWave silently drops unknown columns.
            String sql = "SELECT row_id, strategy_id, is_short, is_btc, is_1h, entry_price, " +
                    "hour_of_day, day_of_week, adx14, rsi14, atr_pct, volume_ratio_ma20, " +
                    "close_vs_ema50_pct, ema20_slope_pct, bb_width_pct, " +
                    "dd_20bar_pct, dd_50bar_pct, momentum_50bar_pct, " +
                    "realized_vol_20bar, dist_from_ema200_pct, range_pct_50bar, " +
                    "htf_momentum_50bar_pct, htf_trend_up, htf_dist_ema50_pct, " +
                    "profitable " +
                    "FROM " + signalScorerTrainingTable() + " " +
                    "WHERE entry_time >= ? AND entry_time <= ? " +
                    "ORDER BY RAND() LIMIT ?";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    sql, startDate, endDate + " 23:59:59", n);
            if (rows.isEmpty()) return "⚠️ holdout 視窗 [" + startDate + ", " + endDate + "] 無 trades";

            // Per-layer counters
            Counter ml = new Counter("HeatWave ML v" + mlVersionId);
            Counter gemini = new Counter("Gemini Flash-lite");
            Counter groq = new Counter("Groq Llama 3.3 70B");
            Counter ensembleC = new Counter("Ensemble (mean)");
            int wins = 0, losses = 0, mlVsEnsembleAgree = 0, mlVsEnsembleN = 0;
            long startMs = System.currentTimeMillis();

            for (Map<String, Object> r : rows) {
                int actual = ((Number) r.get("profitable")).intValue();
                if (actual == 1) wins++; else losses++;

                Map<String, Object> feats = new HashMap<>(r);
                feats.remove("row_id");
                feats.remove("profitable");

                String symbol = ((Number) r.get("is_btc")).intValue() == 1 ? "BTCUSDT" : "ETHUSDT";
                String side = ((Number) r.get("is_short")).intValue() == 1 ? "SHORT" : "LONG";
                java.math.BigDecimal price = r.get("entry_price") != null
                        ? new java.math.BigDecimal(r.get("entry_price").toString())
                        : java.math.BigDecimal.ZERO;

                com.agora.service.ml.SignalScorerEnsemble.EnsembleResult er =
                        ensemble.score(feats, mlVersionId, symbol, side, price);

                recordLayer(ml, er.layers().get("layer2_ml"), actual);
                recordLayer(gemini, er.layers().get("layer3_gemini-flash"), actual);
                recordLayer(groq, er.layers().get("layer3_groq-llama-3.3-70b"), actual);

                if (er.ensemblePWin() != null) {
                    int predEnsemble = er.ensemblePWin().doubleValue() >= 0.5 ? 1 : 0;
                    ensembleC.total++;
                    if (predEnsemble == 1) ensembleC.predWin++; else ensembleC.predLoss++;
                    if (predEnsemble == actual) ensembleC.correct++;
                    if (actual == 1 && predEnsemble == 1) ensembleC.tp++;
                    if (actual == 0 && predEnsemble == 0) ensembleC.tn++;

                    com.agora.service.ml.SignalScorerEnsemble.LayerOutput mlOut = er.layers().get("layer2_ml");
                    if (mlOut != null && mlOut.success()) {
                        int predMl = mlOut.pWin() >= 0.5 ? 1 : 0;
                        mlVsEnsembleN++;
                        if (predMl == predEnsemble) mlVsEnsembleAgree++;
                    }
                }
            }
            long elapsed = (System.currentTimeMillis() - startMs) / 1000;

            int totalActual = wins + losses;
            double baseline = totalActual > 0 ? 100.0 * Math.max(wins, losses) / totalActual : 0;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Ensemble Walk-Forward Eval ===%n"));
            sb.append(String.format("Window           : %s → %s%n", startDate, endDate));
            sb.append(String.format("Holdout sample   : %d trades (wins=%d losses=%d, baseline=%.1f%%)%n",
                    totalActual, wins, losses, baseline));
            sb.append(String.format("ML version       : v%d%n", mlVersionId));
            sb.append(String.format("Total elapsed    : %ds (%.1fs/trade)%n", elapsed, totalActual > 0 ? (double) elapsed / totalActual : 0));
            sb.append("\n--- Per-layer accuracy (predict win iff p_win ≥ 0.5) ---\n");
            sb.append(formatCounter(ml, baseline));
            sb.append(formatCounter(gemini, baseline));
            sb.append(formatCounter(groq, baseline));
            sb.append(formatCounter(ensembleC, baseline));
            sb.append(String.format("%nML vs Ensemble agreement: %d/%d (%.1f%%) — high agreement = ensemble adds little; low = LLMs flip ML calls%n",
                    mlVsEnsembleAgree, mlVsEnsembleN,
                    mlVsEnsembleN > 0 ? 100.0 * mlVsEnsembleAgree / mlVsEnsembleN : 0));
            sb.append("\n💡 解讀:\n");
            sb.append("  - 任一層 edge >= 5pp = 該層有料\n");
            sb.append("  - ensemble edge > 任一單層 = 多模型互補\n");
            sb.append("  - 全部 < 2pp = features 本身缺 regime 資訊,需 V049+ 加 sentiment/rolling features\n");
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP:evalEnsembleOnHoldout] failed", e);
            return "❌ eval 失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use evalModel(modelType='ensemble_gate', dataSource='holdout', ...). " +
            "用時間分割 holdout 驗證 Ensemble Gate 的 BLOCK 精準度。" +
            "保留最近 holdoutDays 天作為 holdout 測試集（從未參與校準），更早的資料為 calibration 集，" +
            "分別計算 BLOCK precision(正確攔截的 losers 佔 BLOCK 總數比例)、BLOCK recall 和 PASS win-rate，" +
            "最後給出是否建議開啟 ENSEMBLE_GATE_ENABLED=true 的明確建議。" +
            "前提：ml_inference_log.actual_outcome 需已回填(倉位平倉後自動寫入)，且 ensemble shadow 有在 BUY/SELL 訊號後運行。" +
            "param: holdoutDays(保留幾天作 holdout，預設 30)、minSamples(holdout 至少需幾筆才判定，預設 10)。")
    public String evalEnsembleGateOnHoldout(Integer holdoutDays, Integer minSamples) {
        int hDays = (holdoutDays == null || holdoutDays <= 0) ? 30 : holdoutDays;
        int minN  = (minSamples  == null || minSamples  <= 0) ? 10 : minSamples;
        try {
            // Labeled decisions: audit has ensemble data AND ml_inference_log has actual outcome.
            // Join path: bt_decision_audit (SIGNAL_EVAL) → bt_live_signal via composite key
            //            → ml_inference_log via live_signal_id FK.
            String sql = """
                    SELECT
                        bda.event_time,
                        bda.strategy_id,
                        bda.symbol,
                        JSON_UNQUOTE(JSON_EXTRACT(bda.context_json, '$.extras.ensemble.outcome')) AS ensemble_outcome,
                        CAST(JSON_EXTRACT(bda.context_json, '$.extras.ensemble.score') AS DECIMAL(8,2)) AS ensemble_score,
                        mil.actual_outcome,
                        mil.actual_pnl
                    FROM bt_decision_audit bda
                    INNER JOIN bt_live_signal bls
                        ON  bda.strategy_id   = bls.strategy_id
                        AND bda.symbol        = bls.symbol
                        AND bda.interval_code = bls.interval_code
                        AND bda.bar_open_time = bls.bar_open_time
                    INNER JOIN ml_inference_log mil
                        ON bls.id = mil.live_signal_id
                    WHERE bda.event_type = 'SIGNAL_EVAL'
                        AND JSON_EXTRACT(bda.context_json, '$.extras.ensemble') IS NOT NULL
                        AND mil.actual_outcome IS NOT NULL
                    ORDER BY bda.event_time ASC
                    """;

            // Total audit rows with ensemble data (including still-open / unlabeled)
            String coverageSql = """
                    SELECT COUNT(*) AS cnt
                    FROM bt_decision_audit
                    WHERE event_type = 'SIGNAL_EVAL'
                        AND JSON_EXTRACT(context_json, '$.extras.ensemble') IS NOT NULL
                    """;

            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            long totalEnsemble = ((Number) jdbc.queryForMap(coverageSql).get("cnt")).longValue();
            int labeled   = rows.size();
            long unlabeled = totalEnsemble - labeled;

            if (labeled == 0) {
                return String.format(
                        "⚠️ 無帶 ensemble 且已標記結果的 SIGNAL_EVAL rows。%n" +
                        "總 ensemble decisions: %d (actual_outcome 全為 NULL)%n" +
                        "檢查:%n" +
                        "  1. ml_inference_log.actual_outcome 是否已回填 (倉位需先平倉)%n" +
                        "  2. Ensemble shadow 是否在 BUY/SELL 訊號後運行",
                        totalEnsemble);
            }

            // Time-based split: holdout = last hDays days, calibration = everything before
            LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(hDays);
            List<Map<String, Object>> calibRows   = new ArrayList<>();
            List<Map<String, Object>> holdoutRows = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Object t = r.get("event_time");
                LocalDateTime ts = (t instanceof LocalDateTime ldt) ? ldt
                        : (t instanceof java.sql.Timestamp sqlt) ? sqlt.toLocalDateTime()
                        : cutoff; // safe fallback: treat as holdout boundary
                (ts.isBefore(cutoff) ? calibRows : holdoutRows).add(r);
            }

            EnsembleWindowStats calibStats   = computeEnsembleWindowStats(calibRows);
            EnsembleWindowStats holdoutStats = computeEnsembleWindowStats(holdoutRows);

            StringBuilder sb = new StringBuilder();
            sb.append("=== Ensemble Gate Holdout Validation ===\n");
            sb.append(String.format("Holdout window     : 最近 %d 天 (>= %s UTC)%n", hDays, cutoff.toLocalDate()));
            sb.append(String.format("Calibration window : 更早期 (< %s UTC)%n", cutoff.toLocalDate()));
            sb.append(String.format("帶 ensemble 決策   : 總計 %d 筆 (已標記=%d 未標記=%d)%n%n",
                    totalEnsemble, labeled, unlabeled));
            sb.append("--- Calibration ---\n");
            appendEnsembleWindowStats(sb, calibStats);
            sb.append("\n--- Holdout ---\n");
            appendEnsembleWindowStats(sb, holdoutStats);
            sb.append("\n💡 建議:\n");
            appendEnsembleRecommendation(sb, calibStats, holdoutStats, minN);
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP:evalEnsembleGateOnHoldout] failed", e);
            return "❌ 評估失敗: " + e.getMessage();
        }
    }

    // ─── Ensemble Gate Holdout helpers ─────────────────────────────────────

    /**
     * Metrics for one time window of labeled ensemble decisions.
     *
     * <p>Precision (BLOCK): of all trades we blocked, what % were actual losers?
     * Higher = fewer missed winners blocked = less opportunity cost.
     *
     * <p>Recall (BLOCK): of all actual losing trades, what % did we catch?
     * Higher = fewer losses slipped through as PASS.
     */
    private record EnsembleWindowStats(
            int n, int wins, int losses,
            int passCount, int passWins, int passLosses,
            int blockCount, int blockWins, int blockLosses,
            int vetoCount, int vetoWins, int vetoLosses) {

        double baselineWinPct() { return n > 0 ? 100.0 * wins / n : 0; }
        double blockPrecision() { return blockCount > 0 ? 100.0 * blockLosses / blockCount : Double.NaN; }
        double blockRecall()    { return losses > 0    ? 100.0 * blockLosses / losses      : Double.NaN; }
        double passWinPct()     { return passCount > 0 ? 100.0 * passWins / passCount      : Double.NaN; }
        double passEdge()       { return Double.isNaN(passWinPct()) ? Double.NaN : passWinPct() - baselineWinPct(); }
    }

    private EnsembleWindowStats computeEnsembleWindowStats(List<Map<String, Object>> rows) {
        int n = 0, wins = 0, losses = 0;
        int passC = 0, passW = 0, passL = 0;
        int blockC = 0, blockW = 0, blockL = 0;
        int vetoC  = 0, vetoW  = 0, vetoL  = 0;
        for (Map<String, Object> r : rows) {
            String outcome = (String) r.get("ensemble_outcome");
            if (outcome == null) continue;
            int actual = ((Number) r.get("actual_outcome")).intValue();
            n++;
            if (actual == 1) wins++; else losses++;
            if ("PASS".equalsIgnoreCase(outcome)) {
                passC++;  if (actual == 1) passW++;  else passL++;
            } else if ("BLOCK".equalsIgnoreCase(outcome)) {
                blockC++; if (actual == 1) blockW++; else blockL++;
            } else if ("VETO".equalsIgnoreCase(outcome)) {
                vetoC++;  if (actual == 1) vetoW++;  else vetoL++;
            } else {
                // Unknown outcome: undo n/wins/losses increment
                n--; if (actual == 1) wins--; else losses--;
            }
        }
        return new EnsembleWindowStats(n, wins, losses,
                passC, passW, passL, blockC, blockW, blockL, vetoC, vetoW, vetoL);
    }

    private void appendEnsembleWindowStats(StringBuilder sb, EnsembleWindowStats s) {
        if (s.n() == 0) { sb.append("  (無資料)\n"); return; }
        sb.append(String.format("  n=%-3d  win_rate=%.1f%%  (wins=%d losses=%d)%n",
                s.n(), s.baselineWinPct(), s.wins(), s.losses()));
        if (s.passCount() > 0) {
            double wr   = s.passWinPct();
            double edge = s.passEdge();
            String em   = !Double.isNaN(edge) && edge >= 5 ? "🟢"
                        : !Double.isNaN(edge) && edge >= 0 ? "🟡" : "🔴";
            sb.append(String.format("  PASS  %-3d  win=%.1f%%  edge=%+.1fpp %s  (wins=%d losses=%d)%n",
                    s.passCount(), Double.isNaN(wr) ? 0 : wr,
                    Double.isNaN(edge) ? 0 : edge, em, s.passWins(), s.passLosses()));
        }
        if (s.blockCount() > 0) {
            double prec = s.blockPrecision();
            double rec  = s.blockRecall();
            String em   = !Double.isNaN(prec) && prec >= 70 ? "🟢"
                        : !Double.isNaN(prec) && prec >= 50 ? "🟡" : "🔴";
            sb.append(String.format("  BLOCK %-3d  precision=%.1f%% %s  recall=%.1f%%  (TP=%d FP=%d)%n",
                    s.blockCount(), Double.isNaN(prec) ? 0 : prec, em,
                    Double.isNaN(rec) ? 0 : rec, s.blockLosses(), s.blockWins()));
        }
        if (s.vetoCount() > 0) {
            sb.append(String.format("  VETO  %-3d  (always enforced | wins=%d losses=%d)%n",
                    s.vetoCount(), s.vetoWins(), s.vetoLosses()));
        }
    }

    private void appendEnsembleRecommendation(StringBuilder sb,
                                               EnsembleWindowStats calib,
                                               EnsembleWindowStats holdout,
                                               int minN) {
        final int minBlock = 5; // minimum BLOCK count for meaningful precision
        boolean calibDataOk   = calib.n()   >= minN && calib.blockCount()   >= minBlock;
        boolean holdoutDataOk = holdout.n() >= minN && holdout.blockCount() >= minBlock;
        boolean calibPrecOk   = calibDataOk   && !Double.isNaN(calib.blockPrecision())
                                && calib.blockPrecision()   >= 60.0;
        boolean holdoutPrecOk = holdoutDataOk && !Double.isNaN(holdout.blockPrecision())
                                && holdout.blockPrecision() >= 60.0;

        if (!calibDataOk) {
            int need = Math.max(minN - calib.n(), minBlock - calib.blockCount());
            sb.append(String.format("  ⏳ Calibration 樣本不足 (n=%d BLOCK=%d) — 需再等約 %d 筆 labeled decisions。%n",
                    calib.n(), calib.blockCount(), need));
            sb.append("  暫不建議開啟 ENSEMBLE_GATE_ENABLED。\n");
            return;
        }

        sb.append(String.format("  Calibration: BLOCK precision=%.1f%% (%s n=%d)%n",
                calib.blockPrecision(),
                calibPrecOk ? "✅ >= 60%" : "❌ < 60%",
                calib.blockCount()));

        if (!holdoutDataOk) {
            int need = Math.max(minN - holdout.n(), minBlock - holdout.blockCount());
            sb.append(String.format("  Holdout:     樣本不足 (n=%d BLOCK=%d) — 需再等約 %d 筆。%n",
                    holdout.n(), holdout.blockCount(), need));
            if (calibPrecOk) {
                sb.append("  ✅ Calibration 精準度足夠，但 holdout 尚無法確認泛化能力。\n");
                sb.append("  建議: 可先對最低頻策略 (Strategy 27/485) 試開 ENSEMBLE_GATE_ENABLED=true，\n");
                sb.append(String.format("        待 holdout n >= %d 後再做最終雙視窗確認。%n", minN));
            } else {
                sb.append("  ⛔ Calibration 精準度不足，暫不建議開啟 BLOCK gate。\n");
            }
            return;
        }

        sb.append(String.format("  Holdout:     BLOCK precision=%.1f%% (%s n=%d)%n",
                holdout.blockPrecision(),
                holdoutPrecOk ? "✅ >= 60%" : "❌ < 60%",
                holdout.blockCount()));

        if (calibPrecOk && holdoutPrecOk) {
            sb.append("  ✅ ✅ 雙視窗精準度皆 >= 60% — 建議開啟 ENSEMBLE_GATE_ENABLED=true\n");
            sb.append("  最安全路徑: 先對最低頻策略試跑 2 週，確認無誤再全域開啟。\n");
        } else if (calibPrecOk) {
            sb.append("  ⚠️  Calibration OK 但 Holdout 未達標 — 可能有 temporal drift，繼續觀察。\n");
            sb.append("  VETO 路徑 (Gemini DISABLE) 已永遠生效，不受此限。\n");
        } else {
            sb.append("  ⛔ BLOCK precision 不足 — 暫不建議開啟 ENSEMBLE_GATE_ENABLED。\n");
            sb.append("  考慮調整 threshold (目前 60.0) 或增加 scoring layers 後重評。\n");
        }
    }

    /** Per-layer accumulator for the ensemble eval. */
    private static class Counter {
        final String name;
        int total, correct, tp, tn, predWin, predLoss, failed;
        Counter(String name) { this.name = name; }
    }
    private void recordLayer(Counter c, com.agora.service.ml.SignalScorerEnsemble.LayerOutput lo, int actual) {
        if (lo == null || !lo.success()) {
            if (lo != null) c.failed++;
            return;
        }
        c.total++;
        int pred = lo.pWin() >= 0.5 ? 1 : 0;
        if (pred == 1) c.predWin++; else c.predLoss++;
        if (pred == actual) c.correct++;
        if (actual == 1 && pred == 1) c.tp++;
        if (actual == 0 && pred == 0) c.tn++;
    }
    private String formatCounter(Counter c, double baseline) {
        if (c.total == 0) {
            return String.format("  %-26s (no successful runs, failed=%d)%n", c.name, c.failed);
        }
        double acc = 100.0 * c.correct / c.total;
        double edge = acc - baseline;
        String verdict = edge >= 5 ? "🟢 alpha" : edge >= 2 ? "🟡 thin" : "🔴 no edge";
        return String.format("  %-26s n=%d acc=%.1f%% edge=%+.1fpp %s  predW/L=%d/%d  TP=%d TN=%d  failed=%d%n",
                c.name, c.total, acc, edge, verdict, c.predWin, c.predLoss, c.tp, c.tn, c.failed);
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MODEL_OPS, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "DEPRECATED, use evalModel(modelType='ensemble', dataSource='live_signal_history', ...). " +
            "在所有歷史 closed bt_live_signal 上 retro 計算 ensemble score，做嚴格的 train/holdout 時間切割驗證。" +
            "不依賴 ensemble shadow（PR#93 後才有），對所有已平倉倉位用當時的 nn_output/ML p_win/RSI/regime 回推 ensemble 分數。" +
            "流程：(1) 查 closed bt_live_signal + LEFT JOIN bt_decision_audit(補 RSI/regime/sentiment) + ml_inference_log；" +
            "(2) 按時間排序，前 (100-holdoutPct)% 為 calibration，末尾 holdoutPct% 為 holdout（從不參與校準）；" +
            "(3) calibration 上掃 threshold(thresholdMin~thresholdMax，步長 5)找最佳 F1；" +
            "(4) holdout 上用最佳 threshold 驗證 BLOCK precision/recall，回傳是否建議調整 ENSEMBLE_GATE_THRESHOLD + 開啟 GATE。" +
            "param: holdoutPct(末尾幾% 作 holdout，預設 30)、thresholdMin/thresholdMax(掃描範圍，預設 40/80)。")
    public String evalEnsembleOnLiveSignalHistory(Integer holdoutPct, Integer thresholdMin, Integer thresholdMax) {
        int hPct = (holdoutPct == null || holdoutPct <= 0 || holdoutPct >= 100) ? 30 : holdoutPct;
        int tMin = thresholdMin == null ? 40 : Math.max(0, thresholdMin);
        int tMax = thresholdMax == null ? 80 : Math.min(150, thresholdMax);
        if (tMin >= tMax) return "❌ thresholdMin 需 < thresholdMax";

        try {
            // Closed live signals + LEFT JOIN audit (v1/v2 context_json) + ML inference log.
            // COALESCE handles v1 sparse path ($.rsi, $.fg, $.whale) vs v2 structured path
            // ($.indicators.rsi, $.sentiment.fg, etc.).
            // Correlated subquery on bt_decision_audit ensures at most 1 SIGNAL_EVAL row per signal.
            String sql = """
                    SELECT
                        bls.id              AS signal_id,
                        bls.bar_open_time,
                        bls.side,
                        bls.symbol,
                        bls.strategy_id,
                        bls.nn_output,
                        bls.score           AS strategy_score,
                        CASE WHEN bls.realized_pnl > 0 THEN 1 ELSE 0 END AS actual_outcome,
                        mil.score           AS ml_p_win,
                        COALESCE(
                            CAST(JSON_EXTRACT(bda.context_json, '$.indicators.rsi') AS DECIMAL(8,2)),
                            CAST(JSON_EXTRACT(bda.context_json, '$.rsi')            AS DECIMAL(8,2))
                        ) AS rsi,
                        COALESCE(
                            CAST(JSON_EXTRACT(bda.context_json, '$.indicators.adx') AS DECIMAL(8,2)),
                            CAST(JSON_EXTRACT(bda.context_json, '$.adx')            AS DECIMAL(8,2))
                        ) AS adx,
                        COALESCE(
                            CAST(JSON_EXTRACT(bda.context_json, '$.sentiment.fg')   AS SIGNED),
                            CAST(JSON_EXTRACT(bda.context_json, '$.fg')             AS SIGNED)
                        ) AS fear_greed,
                        COALESCE(
                            CAST(JSON_EXTRACT(bda.context_json, '$.sentiment.whale_buy_ratio') AS DECIMAL(8,4)),
                            CAST(JSON_EXTRACT(bda.context_json, '$.whale')                     AS DECIMAL(8,4))
                        ) AS whale_buy_ratio,
                        JSON_UNQUOTE(JSON_EXTRACT(bda.context_json, '$.regime.gemini_style'))        AS gemini_style,
                        JSON_UNQUOTE(JSON_EXTRACT(bda.context_json, '$.regime.gemini_regime'))       AS gemini_regime,
                        CAST(JSON_EXTRACT(bda.context_json,  '$.regime.gemini_confidence') AS DECIMAL(4,2)) AS gemini_confidence,
                        CAST(COALESCE(JSON_EXTRACT(bda.context_json, '$.version'), 1) AS UNSIGNED)  AS ctx_version
                    FROM bt_live_signal bls
                    LEFT JOIN ml_inference_log mil
                        ON bls.id = mil.live_signal_id
                    LEFT JOIN bt_decision_audit bda
                        ON  bda.strategy_id   = bls.strategy_id
                        AND bda.symbol        = bls.symbol
                        AND bda.interval_code = bls.interval_code
                        AND bda.bar_open_time = bls.bar_open_time
                        AND bda.event_type    = 'SIGNAL_EVAL'
                        AND bda.id = (
                            SELECT MAX(id) FROM bt_decision_audit
                            WHERE strategy_id   = bls.strategy_id
                              AND symbol        = bls.symbol
                              AND interval_code = bls.interval_code
                              AND bar_open_time = bls.bar_open_time
                              AND event_type    = 'SIGNAL_EVAL'
                        )
                    WHERE bls.exit_price IS NOT NULL
                        AND bls.realized_pnl IS NOT NULL
                    ORDER BY bls.bar_open_time ASC
                    """;

            List<Map<String, Object>> rows = jdbc.queryForList(sql);
            if (rows.isEmpty()) {
                return "⚠️ 無已平倉的 bt_live_signal (需 exit_price + realized_pnl 皆存在)。\n" +
                       "確認策略有實際交易(auto_traded=true)並已平倉。";
            }

            // ── Retro-score each signal ──────────────────────────────────────
            record ScoredRow(LocalDateTime time, double score, int actual,
                             int ctxVersion, boolean hasMl, boolean hasRegime) {}

            var scored = new ArrayList<ScoredRow>(rows.size());
            int missingMl = 0, missingRegime = 0, v2Count = 0;

            for (Map<String, Object> r : rows) {
                String side = (String) r.get("side");
                com.agora.service.meta.TradeDecisionEngine.Side tradeSide =
                        "SHORT".equalsIgnoreCase(side)
                        ? com.agora.service.meta.TradeDecisionEngine.Side.SHORT
                        : com.agora.service.meta.TradeDecisionEngine.Side.LONG;

                Double mlPWin       = toDouble(r.get("ml_p_win"));
                Double nnOutput     = toDouble(r.get("nn_output"));
                Double stratScore   = toDouble(r.get("strategy_score"));
                String geminiStyle  = (String) r.get("gemini_style");
                String geminiRegime = (String) r.get("gemini_regime");

                if (mlPWin    == null) missingMl++;
                if (geminiRegime == null) missingRegime++;
                int ctxVer = r.get("ctx_version") != null
                        ? ((Number) r.get("ctx_version")).intValue() : 1;
                if (ctxVer >= 2) v2Count++;

                // Build Inputs — prefer ML p_win as base; fall back to nn_output then strategy score.
                // Fields not persisted in context_json (lsRatio, fundingRate, polymarket,
                // marketFlip, filters) are left null → 0 contribution in TradeDecisionEngine.
                com.agora.service.meta.TradeDecisionEngine.Inputs inputs =
                        new com.agora.service.meta.TradeDecisionEngine.Inputs(
                                tradeSide,
                                mlPWin != null ? mlPWin   : nnOutput,   // ML p_win (or nn_output proxy)
                                mlPWin != null ? null      : stratScore, // strategy_score only when no ML
                                geminiStyle,
                                geminiRegime,
                                null,                              // geminiShortOk — not persisted
                                toDouble(r.get("gemini_confidence")),
                                toDouble(r.get("rsi")),
                                toDouble(r.get("adx")),
                                null,                              // lsRatio — not in context
                                toDouble(r.get("whale_buy_ratio")),
                                null,                              // fundingRatePct — not in context
                                toInt(r.get("fear_greed")),
                                null,                              // polymarketRiskPct — not in context
                                null,                              // marketFlipRecentMinutes
                                null,                              // allFiltersPass
                                null,                              // filterBlockReasons
                                null,                              // orderBookImbalance — not in context_json
                                null                               // availableUsdtAmt  — not in context_json
                        );

                double retroScore = tradeDecisionEngine.score(inputs).score();
                int actual = ((Number) r.get("actual_outcome")).intValue();

                Object t = r.get("bar_open_time");
                LocalDateTime ts = (t instanceof LocalDateTime ldt) ? ldt
                        : (t instanceof java.sql.Timestamp sqlt) ? sqlt.toLocalDateTime()
                        : LocalDateTime.now(ZoneOffset.UTC);

                scored.add(new ScoredRow(ts, retroScore, actual, ctxVer,
                        mlPWin != null, geminiRegime != null));
            }

            // ── Time split ────────────────────────────────────────────────────
            // Already sorted ASC from SQL; split index = ceil(n × calibPct / 100)
            int splitIdx = (int) Math.ceil(scored.size() * (100.0 - hPct) / 100.0);
            splitIdx = Math.max(1, Math.min(splitIdx, scored.size() - 1));
            var calibList   = scored.subList(0, splitIdx);
            var holdoutList = scored.subList(splitIdx, scored.size());

            // ── Calibration: sweep threshold ─────────────────────────────────
            record ThreshResult(int thresh, int blockN, int blockTP, int blockFP,
                                double prec, double recall, double f1) {}

            int cWins = 0, cLosses = 0;
            for (var s : calibList) { if (s.actual() == 1) cWins++; else cLosses++; }

            var sweep = new ArrayList<ThreshResult>();
            for (int t = tMin; t <= tMax; t += 5) {
                int bn = 0, bTP = 0, bFP = 0;
                for (var s : calibList) {
                    if (s.score() < t) {
                        bn++;
                        if (s.actual() == 0) bTP++; else bFP++;
                    }
                }
                double prec   = bn > 0       ? 100.0 * bTP / bn      : 0;
                double recall = cLosses > 0  ? 100.0 * bTP / cLosses : 0;
                double f1     = (prec + recall) > 0 ? 2 * prec * recall / (prec + recall) : 0;
                sweep.add(new ThreshResult(t, bn, bTP, bFP, prec, recall, f1));
            }

            // Best = highest F1 among candidates where recall >= 15% (must actually block something)
            ThreshResult best = sweep.stream()
                    .filter(r -> r.recall() >= 15.0 && r.blockN() > 0)
                    .max(java.util.Comparator.comparingDouble(ThreshResult::f1))
                    .orElse(sweep.stream()
                            .max(java.util.Comparator.comparingDouble(ThreshResult::prec))
                            .orElse(null));

            // ── Holdout: evaluate with calibration-optimal threshold ──────────
            int hWins = 0, hLosses = 0, hBN = 0, hBTP = 0, hBFP = 0;
            for (var s : holdoutList) {
                if (s.actual() == 1) hWins++; else hLosses++;
                if (best != null && s.score() < best.thresh()) {
                    hBN++;
                    if (s.actual() == 0) hBTP++; else hBFP++;
                }
            }
            double hPrec   = hBN > 0     ? 100.0 * hBTP / hBN     : 0;
            double hRecall = hLosses > 0 ? 100.0 * hBTP / hLosses : 0;

            // ── Report ────────────────────────────────────────────────────────
            StringBuilder sb = new StringBuilder();
            sb.append("=== Ensemble Retro Train/Holdout Validation ===\n");
            LocalDateTime firstTs = scored.get(0).time();
            LocalDateTime splitTs = scored.get(splitIdx).time();
            LocalDateTime lastTs  = scored.get(scored.size() - 1).time();
            sb.append(String.format("資料範圍  : %s → %s (%d 筆)%n",
                    firstTs.toLocalDate(), lastTs.toLocalDate(), scored.size()));
            sb.append(String.format("時間切分  : calibration %d 筆 (→ %s) / holdout %d 筆 (%s →)%n",
                    calibList.size(), splitTs.toLocalDate().minusDays(1),
                    holdoutList.size(), splitTs.toLocalDate()));
            sb.append(String.format("缺 ML p_win : %d 筆 (用 nn_output 代替)  |  缺 Gemini regime : %d 筆%n%n",
                    missingMl, missingRegime));

            // Calibration block
            double cBaseline = calibList.isEmpty() ? 0 : 100.0 * cWins / calibList.size();
            sb.append(String.format("--- Calibration (n=%d  wins=%d  losses=%d  win_rate=%.1f%%) ---%n",
                    calibList.size(), cWins, cLosses, cBaseline));
            sb.append("  Threshold sweep (BLOCK = score < threshold):\n");
            for (var tr : sweep) {
                boolean isBest = best != null && tr.thresh() == best.thresh();
                String em = tr.prec() >= 70 ? "🟢" : tr.prec() >= 50 ? "🟡" : "🔴";
                sb.append(String.format("    t=%-3d  BLOCK=%-3d  prec=%.1f%% %s  recall=%.1f%%  F1=%.1f%s%n",
                        tr.thresh(), tr.blockN(), tr.prec(), em, tr.recall(), tr.f1(),
                        isBest ? "  ← best" : ""));
            }

            // Holdout block
            sb.append(String.format("%n--- Holdout (n=%d  wins=%d  losses=%d  win_rate=%.1f%%) ---%n",
                    holdoutList.size(), hWins, hLosses,
                    holdoutList.isEmpty() ? 0 : 100.0 * hWins / holdoutList.size()));
            if (holdoutList.isEmpty()) {
                sb.append("  (無資料 — holdoutPct 太小)\n");
            } else if (best == null) {
                sb.append("  ⚠️ Calibration 找不到 recall >= 15% 的有效 threshold，無法驗證。\n");
            } else {
                String hEm = hPrec >= 70 ? "🟢" : hPrec >= 50 ? "🟡" : "🔴";
                sb.append(String.format("  Optimal threshold (from calib) : %d%n", best.thresh()));
                sb.append(String.format("  BLOCK=%-3d  prec=%.1f%% %s  recall=%.1f%%  (TP=%d FP=%d)%n",
                        hBN, hPrec, hEm, hRecall, hBTP, hBFP));

                sb.append("\n💡 建議:\n");
                if (hPrec >= 60 && hRecall >= 10) {
                    sb.append(String.format("  ✅ Holdout precision=%.1f%% >= 60%% — BLOCK gate 泛化有效%n", hPrec));
                    if (best.thresh() != 60) {
                        sb.append(String.format("  threshold 建議調為 %d (目前 60.0):%n", best.thresh()));
                        sb.append(String.format("  ENSEMBLE_GATE_THRESHOLD=%d  ENSEMBLE_GATE_ENABLED=true%n", best.thresh()));
                    } else {
                        sb.append("  threshold 維持 60.0，直接開啟 ENSEMBLE_GATE_ENABLED=true 即可\n");
                    }
                } else if (hPrec >= 50) {
                    sb.append(String.format("  🟡 Holdout precision=%.1f%% (50-60%%) — 邊際效果%n", hPrec));
                    sb.append("  建議增加 scoring layers (ls_ratio / funding / polymarket) 後重評。\n");
                } else {
                    sb.append(String.format("  ❌ Holdout precision=%.1f%% < 50%% — 泛化效果不足%n", hPrec));
                    sb.append("  可能原因:\n");
                    sb.append(String.format("    1. v2 context rows 比例僅 %.0f%%，Gemini regime 等關鍵層缺失%n",
                            100.0 * v2Count / scored.size()));
                    sb.append("    2. calibration 與 holdout 期間 market regime 差異太大\n");
                    sb.append("    3. hardcoded weights 需要重新校準\n");
                }
            }

            // Feature coverage summary
            sb.append(String.format("%n📊 Feature coverage:%n"));
            sb.append(String.format("  v2 context_json : %d/%d (%.0f%%) — rsi/adx/regime 完整%n",
                    v2Count, scored.size(), 100.0 * v2Count / scored.size()));
            sb.append(String.format("  ML p_win 存在   : %d/%d (%.0f%%)%n",
                    scored.size() - missingMl, scored.size(),
                    100.0 * (scored.size() - missingMl) / scored.size()));
            sb.append(String.format("  Gemini regime   : %d/%d (%.0f%%)%n",
                    scored.size() - missingRegime, scored.size(),
                    100.0 * (scored.size() - missingRegime) / scored.size()));
            if (v2Count < scored.size() / 2) {
                sb.append("  ⚠️ 多數 rows 為 v1 context_json — 分數僅反映 ML + sentiment 層，Gemini regime 層缺失。\n");
                sb.append("  等 v2 rows 佔多數後（約 2026-04 後的倉位），重跑結果更可靠。\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("[MCP:evalEnsembleOnLiveSignalHistory] failed", e);
            return "❌ 評估失敗: " + e.getMessage();
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private String truncate(String s, int max) {
        if (s == null) return "-";
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private Double toDouble(Object v) {
        if (v == null) return null;
        try { return ((Number) v).doubleValue(); } catch (Exception e) { return null; }
    }

    private Integer toInt(Object v) {
        if (v == null) return null;
        try { return ((Number) v).intValue(); } catch (Exception e) { return null; }
    }

    private String prettyJson(Object rawJson) {
        if (rawJson == null) return "-";
        try {
            JsonNode node = objectMapper.readTree(rawJson.toString());
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return rawJson.toString();
        }
    }
}
