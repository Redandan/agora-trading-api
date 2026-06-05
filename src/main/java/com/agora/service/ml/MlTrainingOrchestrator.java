package com.agora.service.ml;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates HeatWave ML training/inference calls and records them in
 * {@code ml_model_registry} + {@code ml_action_log}.
 *
 * <h3>Why JDBC, not JPA</h3>
 * The HeatWave ML procedures ({@code sys.ML_TRAIN}, {@code sys.ML_PREDICT_ROW})
 * are stored-procedure calls that return values via OUT parameters or
 * session-scoped variables. JdbcTemplate + {@code @Qualifier} data-source
 * is the cleanest adapter; adding a JPA entity per table would triple the
 * code with zero runtime benefit for something already mostly written in SQL.
 *
 * <h3>POC verified (2026-04-17)</h3>
 * <ul>
 *   <li>ML_TRAIN on 2126 trades / 9 features → LGBMClassifier, 4 min</li>
 *   <li>ML_PREDICT_TABLE batch: 1.4 ms/row</li>
 *   <li>ML_PREDICT_ROW single: ~1.6 s (ok for low-frequency signal eval)</li>
 *   <li>ML_EXPLAIN_ROW returns SHAP-like attribution + human-readable Notes</li>
 * </ul>
 * <p>ML pipeline schema should be covered by the pending trading Flyway
 * baseline before production schema hardening.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlTrainingOrchestrator {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    // ══════════════════════════════════════════════════════════════════════
    // Resource guards — protect HeatWave + DB + MODEL_CATALOG from abuse
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Max concurrent training jobs per model_name. HeatWave ML_TRAIN holds
     * the analytics cluster for minutes at a time; two concurrent trainings
     * of the same model also race on the snapshot-table naming (v{N}).
     * Value: 1. Enforced via in-JVM semaphore below.
     *
     * Note: single-JVM enforcement only. Deploying additional app instances
     * would require promoting this to a DB advisory lock (GET_LOCK).
     */
    public static final int MAX_CONCURRENT_TRAIN_PER_MODEL = 1;

    /**
     * Max training requests per model_name per rolling 24 h. Protects against
     * a runaway AI session looping trainSignalScorer. Bumped 5 → 10 on 2026-04-17
     * to allow active multi-version (V047/V049/V050 + classification/regression)
     * iteration sprints. Long-term steady-state should set this back to 5.
     */
    public static final int MAX_TRAINS_PER_DAY_PER_MODEL = 10;

    /**
     * Keep the last N versions per model_name (PROMOTED always kept, older
     * READY / FAILED trimmed to ARCHIVED). Prevents {@code ml_model_registry}
     * and HeatWave {@code MODEL_CATALOG} from growing unbounded.
     */
    public static final int MAX_VERSIONS_KEPT_PER_MODEL = 10;

    /**
     * Global PROMOTED cap across ALL model_names. Accidentally promoting
     * many models = many parallel inference paths firing in LiveSignalEvaluator;
     * cap keeps operational surface small.
     */
    public static final int MAX_GLOBAL_PROMOTED = 10;

    /** Per-model training lock. Map populated lazily on first train of each name. */
    private final Map<String, Semaphore> trainingLocks = new ConcurrentHashMap<>();
    /** Per-handle load lock — avoids stampeding ML_MODEL_LOAD on hot paths. */
    private final Map<String, Semaphore> modelLoadLocks = new ConcurrentHashMap<>();
    /** Last successful ML_MODEL_LOAD time per handle. */
    private final Map<String, Long> modelLastLoadedAt = new ConcurrentHashMap<>();
    /** Global ML circuit-open deadline (epoch ms). */
    private final AtomicLong mlCircuitOpenUntilMs = new AtomicLong(0L);

    /** Re-load the same model at most once every 5 minutes. */
    private static final long MODEL_RELOAD_MIN_INTERVAL_MS = 5 * 60_000L;
    /** Circuit-open duration after lock/connection meltdown signals. */
    private static final long ML_CIRCUIT_OPEN_MS = 10 * 60_000L;

    private Semaphore lockFor(String modelName) {
        return trainingLocks.computeIfAbsent(modelName,
                k -> new Semaphore(MAX_CONCURRENT_TRAIN_PER_MODEL, /*fair=*/true));
    }
    private Semaphore loadLockFor(String handle) {
        return modelLoadLocks.computeIfAbsent(handle,
                k -> new Semaphore(1, /*fair=*/true));
    }

    /**
     * Train a new model version and register it. Returns the registry row id.
     *
     * <p>Blocks until sys.ML_TRAIN completes (typically 30-300s depending on
     * task + AutoML HPO budget). Caller should invoke via MCP tool with an
     * appropriate timeout budget.
     *
     * @param modelName    logical name, e.g. "signal_scorer"
     * @param trainingView fully-qualified VIEW, e.g. "agora_market.vw_signal_training_v1"
     * @param targetColumn column in the view that carries the label
     * @param taskType     classification / regression
     * @param actor        who kicked off the training
     * @param notes        reasoning (like enableStrategy notes)
     * @param options      extra JSON options passed to ML_TRAIN
     *                     (e.g., {@code {"exclude_column_list":["row_id"]}})
     * @return registry row id
     */
    @Transactional
    public long trainAndRegister(String modelName,
                                  String trainingView,
                                  String targetColumn,
                                  String taskType,
                                  String actor,
                                  String notes,
                                  Map<String, Object> options) {
        return trainAndRegister(modelName, trainingView, null, targetColumn, taskType, actor, notes, options);
    }

    /**
     * Variant accepting an optional SQL WHERE clause applied to the training
     * view during snapshot materialization. Enables walk-forward training
     * (e.g. {@code "entry_time <= '2026-02-28'"}).
     *
     * @param whereClause raw SQL predicate (no leading WHERE); null = no filter.
     *                    Caller is responsible for passing trusted input
     *                    (only called from validated MCP tools).
     */
    @Transactional
    public long trainAndRegister(String modelName,
                                  String trainingView,
                                  String whereClause,
                                  String targetColumn,
                                  String taskType,
                                  String actor,
                                  String notes,
                                  Map<String, Object> options) {
        // Guard 1: rate limit (24 h rolling window)
        // #323: NOW(6) is MySQL UTC; trained_at column is JDBC-written Taipei wall-clock.
        // Use Java LocalDateTime parameter so the 8h JDBC offset cancels for both sides.
        java.time.LocalDateTime cutoff24h =
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1);
        Integer dailyCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ml_model_registry "
                        + "WHERE model_name = ? AND trained_at > ?",
                Integer.class, modelName, cutoff24h);
        if (dailyCount != null && dailyCount >= MAX_TRAINS_PER_DAY_PER_MODEL) {
            throw new IllegalStateException(String.format(
                    "RATE_LIMITED: %s already trained %d times in the last 24h (cap=%d). "
                            + "Wait or raise MAX_TRAINS_PER_DAY_PER_MODEL.",
                    modelName, dailyCount, MAX_TRAINS_PER_DAY_PER_MODEL));
        }

        // Guard 2: concurrent training lock (fail fast, don't block the MCP worker).
        Semaphore lock = lockFor(modelName);
        if (!lock.tryAcquire()) {
            throw new IllegalStateException(String.format(
                    "TRAINING_IN_PROGRESS: another training for %s is already running. "
                            + "Wait ~4 min and retry.", modelName));
        }

        int version = nextVersion(modelName);
        long registryId = insertTrainingRow(modelName, version, trainingView, targetColumn, taskType, actor, notes);
        logAction(modelName, null, "TRAIN", actor,
                "training kicked off: view=" + trainingView + " target=" + targetColumn,
                options);

        // HeatWave ML_TRAIN requires a BASE TABLE (error 1347 'is not BASE TABLE'
        // when handed a VIEW). Materialize the view into a per-version snapshot
        // table so (a) ML_TRAIN accepts it and (b) we retain reproducibility
        // (the exact rows used by version N stay around until manually dropped).
        // Name pattern: _ml_snapshot_{model}_v{version}
        String snapshotTable = "agora_market._ml_snapshot_" + sanitizeIdent(modelName) + "_v" + version;
        String handle;
        long startMs = System.currentTimeMillis();
        try {
            // try/finally ensures lock always released even on unexpected Throwable.
            jdbc.update("DROP TABLE IF EXISTS " + snapshotTable);
            String ctas = "CREATE TABLE " + snapshotTable + " AS SELECT * FROM " + trainingView;
            if (whereClause != null && !whereClause.isBlank()) {
                ctas += " WHERE " + whereClause;
            }
            jdbc.update(ctas);
            log.info("[MlOrchestrator] materialized snapshot {} from {} where=[{}]",
                    snapshotTable, trainingView, whereClause == null ? "(full)" : whereClause);

            Map<String, Object> effectiveOptions = new HashMap<>();
            effectiveOptions.put("task", taskType);
            if (options != null) effectiveOptions.putAll(options);
            String optionsJson = objectMapper.writeValueAsString(effectiveOptions);

            // sys.ML_TRAIN is a CALL, not a SELECT. Use update + query pattern
            // to capture the OUT variable.
            jdbc.update("SET @model_handle = NULL");
            jdbc.update("CALL sys.ML_TRAIN(?, ?, CAST(? AS JSON), @model_handle)",
                    snapshotTable, targetColumn, optionsJson);
            handle = jdbc.queryForObject("SELECT @model_handle", String.class);
            if (handle == null || handle.isBlank()) {
                throw new IllegalStateException("ML_TRAIN returned null handle");
            }
        } catch (Exception e) {
            jdbc.update("UPDATE ml_model_registry SET status='FAILED', notes=CONCAT(COALESCE(notes,''),'\nFAILED: "
                    + e.getMessage().replace("'", "''") + "') WHERE id=?", registryId);
            logAction(modelName, registryId, "REJECTED", actor, "ML_TRAIN failed: " + e.getMessage(), null);
            lock.release();
            throw new RuntimeException("ML_TRAIN failed for " + modelName + ": " + e.getMessage(), e);
        } finally {
            // Belt and braces — normal path releases below after metadata update
            // but this covers any throwable slipping past. Semaphore release
            // is idempotent-ish: a second release would over-credit, so guard
            // via tryAcquire/release pairing at call sites only.
        }
        long durationMs = System.currentTimeMillis() - startMs;

        // Pull metrics + algorithm from HeatWave MODEL_CATALOG.
        Map<String, Object> catalog = jdbc.queryForMap(
                "SELECT model_type, model_metadata, column_names, model_explanation "
                        + "FROM ML_SCHEMA_redan.MODEL_CATALOG WHERE model_handle = ?",
                handle);

        Object algorithm = catalog.get("model_type");
        Object metricsBlob = catalog.get("model_metadata");
        Object featureImportance = catalog.get("model_explanation");

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("training_duration_ms", durationMs);
        try {
            if (metricsBlob != null) {
                Map<?, ?> parsed = objectMapper.readValue(metricsBlob.toString(), Map.class);
                if (parsed.get("training_score") != null) metrics.put("training_score", parsed.get("training_score"));
                if (parsed.get("optimization_metric") != null) metrics.put("optimization_metric", parsed.get("optimization_metric"));
                if (parsed.get("n_rows") != null) metrics.put("n_rows", parsed.get("n_rows"));
                if (parsed.get("n_selected_rows") != null) metrics.put("n_selected_rows", parsed.get("n_selected_rows"));
                if (parsed.get("n_selected_columns") != null) metrics.put("n_selected_columns", parsed.get("n_selected_columns"));
                if (parsed.get("model_quality") != null) metrics.put("model_quality", parsed.get("model_quality"));
                if (parsed.get("hyperparameters") != null) metrics.put("hyperparameters", parsed.get("hyperparameters"));
            }
        } catch (Exception parseErr) {
            log.warn("[MlOrchestrator] Failed to parse HW metadata: {}", parseErr.getMessage());
        }

        int sampleCount = metrics.get("n_rows") instanceof Number ? ((Number) metrics.get("n_rows")).intValue() : 0;

        try {
            jdbc.update(
                    "UPDATE ml_model_registry SET "
                            + "  heatwave_handle=?, status='READY', algorithm=?, "
                            + "  sample_count=?, metrics_json=CAST(? AS JSON), "
                            + "  feature_importance_json=CAST(? AS JSON) "
                            + "WHERE id=?",
                    handle,
                    algorithm != null ? algorithm.toString() : null,
                    sampleCount,
                    objectMapper.writeValueAsString(metrics),
                    featureImportance != null ? featureImportance.toString() : "{}",
                    registryId);
        } catch (Exception updateErr) {
            log.warn("[MlOrchestrator] metadata update failed: {}", updateErr.getMessage());
        }

        logAction(modelName, registryId, "TRAIN", actor,
                "completed: handle=" + handle + " duration=" + durationMs + "ms rows=" + sampleCount,
                metrics);
        log.info("[MlOrchestrator] trained {} v{} handle={} rows={} duration={}ms",
                modelName, version, handle, sampleCount, durationMs);

        // Guard 3: ring-buffer prune. Archive old READY/FAILED beyond keepLast,
        // keeping PROMOTED always. Runs in same transaction; non-fatal on error.
        try {
            int archived = pruneOldVersions(modelName, MAX_VERSIONS_KEPT_PER_MODEL);
            if (archived > 0) {
                log.info("[MlOrchestrator] ring-buffer archived {} old versions of {} (keepLast={})",
                        archived, modelName, MAX_VERSIONS_KEPT_PER_MODEL);
            }
        } catch (Exception pruneErr) {
            log.warn("[MlOrchestrator] prune failed (non-fatal): {}", pruneErr.getMessage());
        }

        lock.release();
        return registryId;
    }

    /**
     * Mark READY/FAILED versions beyond {@code keepLast} as ARCHIVED. PROMOTED
     * always kept. Visible for MCP use (manual prune MCP tool in V2).
     *
     * @return number of rows archived
     */
    @Transactional
    public int pruneOldVersions(String modelName, int keepLast) {
        return jdbc.update(
                "UPDATE ml_model_registry SET status='ARCHIVED', archived_at=NOW(6) "
                        + "WHERE model_name = ? "
                        + "  AND status IN ('READY','FAILED','SHADOW') "
                        + "  AND id NOT IN ("
                        + "      SELECT id FROM ("
                        + "          SELECT id FROM ml_model_registry "
                        + "          WHERE model_name = ? "
                        + "            AND status IN ('READY','FAILED','SHADOW','PROMOTED') "
                        + "          ORDER BY version DESC LIMIT ?"
                        + "      ) subq"
                        + "  )",
                modelName, modelName, keepLast);
    }

    /** True when ML calls are temporarily fail-fast to protect DB pool. */
    public boolean isMlCircuitOpen() {
        return System.currentTimeMillis() < mlCircuitOpenUntilMs.get();
    }

    /** Milliseconds until circuit closes; 0 when already closed. */
    public long mlCircuitRemainingMs() {
        long remain = mlCircuitOpenUntilMs.get() - System.currentTimeMillis();
        return Math.max(remain, 0L);
    }

    /** External trip hook (e.g. lock guard scheduler). */
    public void tripMlCircuit(String reason) {
        long until = System.currentTimeMillis() + ML_CIRCUIT_OPEN_MS;
        long prev = mlCircuitOpenUntilMs.getAndUpdate(old -> Math.max(old, until));
        if (until > prev) {
            log.warn("[MlOrchestrator] ML circuit OPEN for {}s: {}",
                    ML_CIRCUIT_OPEN_MS / 1000, reason);
        }
    }

    private void guardMlCircuit(String op, String handle) {
        if (!isMlCircuitOpen()) return;
        throw new IllegalStateException(
                "ML_CIRCUIT_OPEN: skip " + op + " for handle=" + handle
                        + " remainingMs=" + mlCircuitRemainingMs());
    }

    private void maybeTripCircuit(Throwable t, String context) {
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        String lower = msg.toLowerCase();
        if (lower.contains("hikaripool")
                || lower.contains("connection is not available")
                || lower.contains("system lock")
                || lower.contains("secondary_load")
                || lower.contains("rapid_ml_operation")) {
            tripMlCircuit(context + " | " + msg);
        }
    }

    /**
     * Load a model into HeatWave memory (required before ML_PREDICT_ROW).
     * Safe to call repeatedly; no-op if already loaded.
     */
    public void loadModel(String heatwaveHandle) {
        if (heatwaveHandle == null || heatwaveHandle.isBlank()) return;
        guardMlCircuit("ML_MODEL_LOAD", heatwaveHandle);
        long now = System.currentTimeMillis();
        Long last = modelLastLoadedAt.get(heatwaveHandle);
        if (last != null && (now - last) < MODEL_RELOAD_MIN_INTERVAL_MS) {
            return; // Already loaded recently; avoid repeated SECONDARY_LOAD pressure.
        }
        Semaphore lock = loadLockFor(heatwaveHandle);
        if (!lock.tryAcquire()) {
            return; // Another thread is loading this handle now.
        }
        try {
            jdbc.update("CALL sys.ML_MODEL_LOAD(?, NULL)", heatwaveHandle);
            modelLastLoadedAt.put(heatwaveHandle, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("[MlOrchestrator] model load warning for {}: {}", heatwaveHandle, e.getMessage());
            maybeTripCircuit(e, "ML_MODEL_LOAD failed handle=" + heatwaveHandle);
            // Not fatal — model may already be loaded.
        } finally {
            lock.release();
        }
    }

    /**
     * Single-row prediction. Warm-call latency ~1.6s; don't call in tight loops
     * (use {@code predictBatch} via ML_PREDICT_TABLE for throughput).
     *
     * @return JSON string as returned by ML_PREDICT_ROW
     */
    public String predictOne(String heatwaveHandle, Map<String, Object> features) {
        guardMlCircuit("ML_PREDICT_ROW", heatwaveHandle);
        try {
            String json = objectMapper.writeValueAsString(features);
            return jdbc.queryForObject(
                    "SELECT sys.ML_PREDICT_ROW(CAST(? AS JSON), ?, NULL)",
                    String.class, json, heatwaveHandle);
        } catch (Exception e) {
            maybeTripCircuit(e, "ML_PREDICT_ROW failed handle=" + heatwaveHandle);
            throw new RuntimeException("ML_PREDICT_ROW failed: " + e.getMessage(), e);
        }
    }

    /**
     * Explain a single prediction via HeatWave's permutation_importance
     * explainer (SHAP-like per-feature attribution).
     */
    public String explainOne(String heatwaveHandle, Map<String, Object> features) {
        guardMlCircuit("ML_EXPLAIN_ROW", heatwaveHandle);
        try {
            String json = objectMapper.writeValueAsString(features);
            return jdbc.queryForObject(
                    "SELECT sys.ML_EXPLAIN_ROW(CAST(? AS JSON), ?, "
                            + "JSON_OBJECT('prediction_explainer','permutation_importance'))",
                    String.class, json, heatwaveHandle);
        } catch (Exception e) {
            maybeTripCircuit(e, "ML_EXPLAIN_ROW failed handle=" + heatwaveHandle);
            throw new RuntimeException("ML_EXPLAIN_ROW failed: " + e.getMessage(), e);
        }
    }

    /**
     * Promote a model version to PROMOTED + demote any previous PROMOTED of same name.
     * Also invalidates {@link MlInferenceLogger}'s PROMOTED cache so the new
     * version takes effect on the next live signal (instead of waiting up to 60s
     * for cache TTL).
     */
    @Transactional
    public void promote(long registryId, String actor, String reasoning) {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT model_name, status FROM ml_model_registry WHERE id = ?", registryId);
        String modelName = (String) row.get("model_name");
        String status = (String) row.get("status");
        if (!"READY".equals(status) && !"SHADOW".equals(status)) {
            throw new IllegalStateException("Cannot promote model in status=" + status);
        }

        // Guard 4: global PROMOTED cap (across all model_names).
        // Count DOES include the one we're about to demote for same modelName,
        // so subtract that to get the post-promote count.
        Integer globalPromoted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ml_model_registry WHERE status='PROMOTED'", Integer.class);
        Integer sameNamePromoted = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ml_model_registry WHERE status='PROMOTED' AND model_name=?",
                Integer.class, modelName);
        int postPromoteGlobal = (globalPromoted == null ? 0 : globalPromoted)
                - (sameNamePromoted == null ? 0 : sameNamePromoted) + 1;
        if (postPromoteGlobal > MAX_GLOBAL_PROMOTED) {
            throw new IllegalStateException(String.format(
                    "PROMOTED_CAP_EXCEEDED: promoting would leave %d models PROMOTED globally (cap=%d). "
                            + "ARCHIVE or disable some other model_name first.",
                    postPromoteGlobal, MAX_GLOBAL_PROMOTED));
        }

        // Demote existing PROMOTED of same name
        jdbc.update("UPDATE ml_model_registry SET status='ARCHIVED', archived_at=NOW(6) "
                + "WHERE model_name=? AND status='PROMOTED'", modelName);
        // Promote this row
        jdbc.update("UPDATE ml_model_registry SET status='PROMOTED', promoted_at=NOW(6), "
                + "notes=CONCAT(COALESCE(notes,''),'\nPROMOTE: ',?) WHERE id=?",
                reasoning, registryId);
        logAction(modelName, registryId, "PROMOTE", actor, reasoning, null);
        // Best-effort cache invalidation; null check guards against startup-order races
        if (inferenceLoggerRef != null) {
            try { inferenceLoggerRef.invalidateCache(); } catch (Exception ignored) {}
        }
    }

    /**
     * Optional setter wired by Spring after both beans are constructed (avoids
     * cyclic constructor injection: MlInferenceLogger needs Orchestrator,
     * Orchestrator wants to invalidate logger's cache on promote).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setInferenceLogger(@org.springframework.context.annotation.Lazy MlInferenceLogger logger) {
        this.inferenceLoggerRef = logger;
    }
    private MlInferenceLogger inferenceLoggerRef;

    // ─── Internal helpers ─────────────────────────────────────────────────

    private int nextVersion(String modelName) {
        Integer maxVer = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM ml_model_registry WHERE model_name = ?",
                Integer.class, modelName);
        return (maxVer == null ? 0 : maxVer) + 1;
    }

    /** Accept only [a-zA-Z0-9_] in model names for SQL identifier interpolation. */
    private String sanitizeIdent(String name) {
        if (name == null) return "unknown";
        return name.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private long insertTrainingRow(String modelName, int version, String view, String target,
                                    String taskType, String actor, String notes) {
        jdbc.update(
                "INSERT INTO ml_model_registry (model_name, version, heatwave_handle, status, "
                        + "task_type, target_column, training_view, notes, trained_by) "
                        + "VALUES (?, ?, '', 'TRAINING', ?, ?, ?, ?, ?)",
                modelName, version, taskType, target, view, notes, actor);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (id == null) throw new IllegalStateException("LAST_INSERT_ID returned null");
        return id;
    }

    private void logAction(String modelName, Long registryId, String action, String actor,
                           String reasoning, Map<String, Object> outcome) {
        try {
            String outcomeJson = outcome == null ? null : objectMapper.writeValueAsString(outcome);
            jdbc.update(
                    "INSERT INTO ml_action_log (model_name, model_version_id, action, actor, reasoning, outcome_json) "
                            + "VALUES (?, ?, ?, ?, ?, CAST(? AS JSON))",
                    modelName, registryId, action, actor, reasoning, outcomeJson);
        } catch (Exception e) {
            log.warn("[MlOrchestrator] action log write failed: {}", e.getMessage());
        }
    }

    /** List versions of a given model (newest first). Returns simple maps for MCP display. */
    public List<Map<String, Object>> listVersions(String modelName, int limit) {
        return jdbc.queryForList(
                "SELECT id, version, status, algorithm, sample_count, trained_at, promoted_at, "
                        + "LEFT(notes, 120) notes_preview "
                        + "FROM ml_model_registry WHERE model_name = ? "
                        + "ORDER BY version DESC LIMIT ?",
                modelName, limit);
    }

    /**
     * Evaluate a model against a time window of held-out trades.
     * Produces accuracy + confusion matrix + calibration + P(win) distribution,
     * the structured data AI needs to decide whether a model is usable.
     *
     * <p>Creates a per-call predict staging table, runs ML_PREDICT_TABLE, reads
     * the result, aggregates, drops the staging table. Handles null features
     * gracefully (pre-V047 rows have NULL indicator columns).
     *
     * @param registryId   ml_model_registry.id
     * @param whereClause  SQL predicate filtering bt_backtest_trade (e.g.,
     *                     {@code "entry_time BETWEEN '2026-03-01' AND '2026-04-16'"})
     */
    public Map<String, Object> evaluateOnWindow(long registryId, String whereClause) {
        Map<String, Object> model = jdbc.queryForMap(
                "SELECT heatwave_handle, training_view FROM ml_model_registry WHERE id = ?", registryId);
        String handle = (String) model.get("heatwave_handle");
        String view = (String) model.get("training_view");
        if (handle == null || handle.isBlank()) {
            throw new IllegalStateException("model " + registryId + " has no handle");
        }
        loadModel(handle);

        String evalTable = "agora_market._ml_eval_" + registryId + "_" + System.currentTimeMillis();
        String predTable = evalTable + "_pred";
        try {
            // Materialize eval set from the training view with WHERE filter
            String ctas = "CREATE TABLE " + evalTable + " AS SELECT * FROM " + view;
            if (whereClause != null && !whereClause.isBlank()) {
                ctas += " WHERE " + whereClause;
            }
            jdbc.update("DROP TABLE IF EXISTS " + evalTable);
            jdbc.update(ctas);

            Integer evalN = jdbc.queryForObject("SELECT COUNT(*) FROM " + evalTable, Integer.class);
            if (evalN == null || evalN == 0) {
                throw new IllegalStateException("eval window produced zero rows from view=" + view
                        + " where=" + whereClause);
            }

            jdbc.update("DROP TABLE IF EXISTS " + predTable);
            jdbc.update("CALL sys.ML_PREDICT_TABLE(?, ?, ?, NULL)", evalTable, handle, predTable);

            // Compute stats in one query
            Map<String, Object> stats = jdbc.queryForMap(
                    "SELECT "
                            + "  COUNT(*)                                                AS total, "
                            + "  SUM(CASE WHEN profitable=1 THEN 1 ELSE 0 END)            AS actual_wins, "
                            + "  SUM(CASE WHEN profitable=0 THEN 1 ELSE 0 END)            AS actual_losses, "
                            + "  SUM(CASE WHEN CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=1 THEN 1 ELSE 0 END) AS pred_wins, "
                            + "  SUM(CASE WHEN CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=0 THEN 1 ELSE 0 END) AS pred_losses, "
                            + "  SUM(CASE WHEN CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=profitable THEN 1 ELSE 0 END) AS correct, "
                            + "  SUM(CASE WHEN profitable=1 AND CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=1 THEN 1 ELSE 0 END) AS tp, "
                            + "  SUM(CASE WHEN profitable=0 AND CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=0 THEN 1 ELSE 0 END) AS tn, "
                            + "  SUM(CASE WHEN profitable=0 AND CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=1 THEN 1 ELSE 0 END) AS fp, "
                            + "  SUM(CASE WHEN profitable=1 AND CAST(JSON_EXTRACT(ml_results,'$.predictions.profitable') AS UNSIGNED)=0 THEN 1 ELSE 0 END) AS fn, "
                            + "  ROUND(AVG(CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE)),4) AS avg_p_win, "
                            + "  ROUND(MIN(CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE)),4) AS min_p_win, "
                            + "  ROUND(MAX(CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE)),4) AS max_p_win "
                            + "FROM " + predTable);

            // Calibration buckets: for each P(win) decile, measure actual win rate
            List<Map<String, Object>> calibration = jdbc.queryForList(
                    "SELECT "
                            + "  FLOOR(CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE)*10)/10 AS p_bucket, "
                            + "  COUNT(*) AS n, "
                            + "  ROUND(100*AVG(profitable),1) AS actual_winrate_pct "
                            + "FROM " + predTable + " "
                            + "GROUP BY p_bucket ORDER BY p_bucket");

            Map<String, Object> result = new HashMap<>(stats);
            result.put("window", whereClause == null ? "(full)" : whereClause);
            result.put("calibration_by_decile", calibration);
            return result;
        } finally {
            try { jdbc.update("DROP TABLE IF EXISTS " + predTable); } catch (Exception ignored) {}
            try { jdbc.update("DROP TABLE IF EXISTS " + evalTable); } catch (Exception ignored) {}
        }
    }

    /**
     * Return the top-N rows from a holdout window ranked by predicted P(win).
     * Used to verify that high-confidence ML signals are actually winning at
     * higher rates than the population baseline, which the binary 0.5-threshold
     * accuracy metric in evaluateOnWindow can hide.
     *
     * <p>Returns row maps with: row_id, p_win, profitable (actual), entry_time,
     * strategy_id, plus a summary count. Sorted by p_win DESC.
     */
    public List<Map<String, Object>> topConfidencePicks(long registryId, String whereClause, int topN) {
        Map<String, Object> model = jdbc.queryForMap(
                "SELECT heatwave_handle, training_view FROM ml_model_registry WHERE id = ?", registryId);
        String handle = (String) model.get("heatwave_handle");
        String view = (String) model.get("training_view");
        if (handle == null || handle.isBlank()) {
            throw new IllegalStateException("model " + registryId + " has no handle");
        }
        loadModel(handle);

        String evalTable = "agora_market._ml_topn_" + registryId + "_" + System.currentTimeMillis();
        String predTable = evalTable + "_pred";
        try {
            String ctas = "CREATE TABLE " + evalTable + " AS SELECT * FROM " + view;
            if (whereClause != null && !whereClause.isBlank()) {
                ctas += " WHERE " + whereClause;
            }
            jdbc.update("DROP TABLE IF EXISTS " + evalTable);
            jdbc.update(ctas);
            jdbc.update("DROP TABLE IF EXISTS " + predTable);
            jdbc.update("CALL sys.ML_PREDICT_TABLE(?, ?, ?, NULL)", evalTable, handle, predTable);

            // Top-N by p_win
            return jdbc.queryForList(
                    "SELECT row_id, "
                            + "       ROUND(CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE),4) AS p_win, "
                            + "       profitable AS actual, "
                            + "       strategy_id, entry_time "
                            + "FROM " + predTable + " "
                            + "ORDER BY p_win DESC "
                            + "LIMIT ?", topN);
        } finally {
            try { jdbc.update("DROP TABLE IF EXISTS " + predTable); } catch (Exception ignored) {}
            try { jdbc.update("DROP TABLE IF EXISTS " + evalTable); } catch (Exception ignored) {}
        }
    }

    /**
     * Bootstrap confidence interval on top-N selectivity lift. Resamples the
     * holdout window with replacement {@code nIter} times; for each resample,
     * computes top-N winrate − population winrate; reports the empirical
     * distribution (mean / stddev / p2.5 / p97.5).
     *
     * <p>Used to answer "is the +27.8pp top-10 lift statistically real, or
     * artifact of a 45-setup small holdout?" — if the 95% CI is e.g.
     * [+5pp, +50pp] → real edge; [-10pp, +50pp] → noise-bound, do not trust.
     *
     * <p>Uses ML_PREDICT_TABLE once (not per iteration) — the bootstrap is on
     * the predicted-vs-actual rows, not on the model itself. This is the
     * "predictive distribution" bootstrap, suitable for assessing the variance
     * of a deterministic model's selectivity metric on a finite holdout.
     */
    public Map<String, Object> bootstrapTopNLift(long registryId, String whereClause,
                                                  int topN, int nIter) {
        Map<String, Object> model = jdbc.queryForMap(
                "SELECT heatwave_handle, training_view FROM ml_model_registry WHERE id = ?", registryId);
        String handle = (String) model.get("heatwave_handle");
        String view = (String) model.get("training_view");
        if (handle == null || handle.isBlank()) {
            throw new IllegalStateException("model " + registryId + " has no handle");
        }
        loadModel(handle);

        String evalTable = "agora_market._ml_boot_" + registryId + "_" + System.currentTimeMillis();
        String predTable = evalTable + "_pred";
        try {
            String ctas = "CREATE TABLE " + evalTable + " AS SELECT * FROM " + view;
            if (whereClause != null && !whereClause.isBlank()) ctas += " WHERE " + whereClause;
            jdbc.update("DROP TABLE IF EXISTS " + evalTable);
            jdbc.update(ctas);
            jdbc.update("DROP TABLE IF EXISTS " + predTable);
            jdbc.update("CALL sys.ML_PREDICT_TABLE(?, ?, ?, NULL)", evalTable, handle, predTable);

            // Pull all (p_win, profitable) pairs into JVM
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT CAST(JSON_EXTRACT(ml_results,'$.probabilities.\"1\"') AS DOUBLE) AS p_win, "
                            + "       profitable AS actual "
                            + "FROM " + predTable);
            if (rows.size() < topN) {
                throw new IllegalStateException("not enough holdout rows (" + rows.size()
                        + ") for top-" + topN + " selectivity");
            }

            int n = rows.size();
            double[] pWins = new double[n];
            int[] actuals = new int[n];
            int totalWins = 0;
            for (int i = 0; i < n; i++) {
                Object pObj = rows.get(i).get("p_win");
                pWins[i] = pObj instanceof Number ? ((Number) pObj).doubleValue() : 0;
                actuals[i] = ((Number) rows.get(i).get("actual")).intValue();
                if (actuals[i] == 1) totalWins++;
            }
            double popWinrate = 100.0 * totalWins / n;

            // Bootstrap nIter resamples-with-replacement of size n; compute top-N lift each time
            java.util.Random rng = new java.util.Random(42);  // reproducible
            double[] lifts = new double[nIter];
            for (int it = 0; it < nIter; it++) {
                double[] sP = new double[n];
                int[] sA = new int[n];
                int sWins = 0;
                for (int i = 0; i < n; i++) {
                    int idx = rng.nextInt(n);
                    sP[i] = pWins[idx];
                    sA[i] = actuals[idx];
                    if (sA[i] == 1) sWins++;
                }
                double samplePopWinrate = 100.0 * sWins / n;
                // Top-N by p_win — partial sort would be faster but n=45 makes full sort cheap
                Integer[] indices = new Integer[n];
                for (int i = 0; i < n; i++) indices[i] = i;
                final double[] sPRef = sP;
                java.util.Arrays.sort(indices, (a, b) -> Double.compare(sPRef[b], sPRef[a]));
                int topWins = 0;
                for (int i = 0; i < topN; i++) {
                    if (sA[indices[i]] == 1) topWins++;
                }
                double topWinrate = 100.0 * topWins / topN;
                lifts[it] = topWinrate - samplePopWinrate;
            }
            java.util.Arrays.sort(lifts);
            double meanLift = 0;
            for (double v : lifts) meanLift += v;
            meanLift /= nIter;
            double sd = 0;
            for (double v : lifts) sd += (v - meanLift) * (v - meanLift);
            sd = Math.sqrt(sd / nIter);
            double p025 = lifts[(int) (nIter * 0.025)];
            double p500 = lifts[(int) (nIter * 0.5)];
            double p975 = lifts[(int) (nIter * 0.975)];
            int posSamples = 0;
            for (double v : lifts) if (v > 0) posSamples++;
            double pPositive = 100.0 * posSamples / nIter;

            Map<String, Object> out = new HashMap<>();
            out.put("holdout_n", n);
            out.put("holdout_winrate_pct", popWinrate);
            out.put("top_n", topN);
            out.put("bootstrap_iter", nIter);
            out.put("lift_mean_pp", meanLift);
            out.put("lift_stddev_pp", sd);
            out.put("ci95_lo_pp", p025);
            out.put("ci95_hi_pp", p975);
            out.put("median_lift_pp", p500);
            out.put("pct_iters_positive", pPositive);
            return out;
        } finally {
            try { jdbc.update("DROP TABLE IF EXISTS " + predTable); } catch (Exception ignored) {}
            try { jdbc.update("DROP TABLE IF EXISTS " + evalTable); } catch (Exception ignored) {}
        }
    }

    /** Per-model summary for {@code getMlLimits} MCP tool. */
    public List<Map<String, Object>> getUsageStats() {
        // #323: Java LocalDateTime parameter avoids MySQL NOW(6) (UTC) vs JDBC-written
        // trained_at (Taipei wall-clock) 8h offset.
        java.time.LocalDateTime cutoff24h =
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(1);
        return jdbc.queryForList(
                "SELECT model_name, "
                        + "       COUNT(*) AS total_versions, "
                        + "       SUM(CASE WHEN trained_at > ? THEN 1 ELSE 0 END) AS trains_last_24h, "
                        + "       SUM(CASE WHEN status='PROMOTED' THEN 1 ELSE 0 END) AS promoted_count "
                        + "FROM ml_model_registry GROUP BY model_name ORDER BY model_name",
                cutoff24h);
    }

    /** Total PROMOTED rows across all model_names. */
    public Integer globalPromotedCount() {
        Integer v = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ml_model_registry WHERE status='PROMOTED'", Integer.class);
        return v == null ? 0 : v;
    }

    /** Fetch single model row (with metrics JSON inlined). */
    public Map<String, Object> getVersion(long id) {
        return jdbc.queryForMap(
                "SELECT id, model_name, version, status, algorithm, sample_count, trained_at, "
                        + "promoted_at, heatwave_handle, metrics_json, feature_importance_json, "
                        + "training_view, target_column, notes "
                        + "FROM ml_model_registry WHERE id = ?", id);
    }
}
