package com.agora.service.ml;

import com.agora.service.ai.router.AiProvider;
import com.agora.service.ai.router.AiResponse;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Multi-layer / multi-model signal scorer.
 *
 * <h3>Why an ensemble at all?</h3>
 * Phase 2 walk-forward (2026-04-17) showed v4 HeatWave LightGBM has 0pp edge on
 * 2026-03 holdout — the model learned bull-regime patterns that don't transfer
 * to crash regime. Hypothesis: adding LLM scorers (which can reason about
 * regime in plain English from the same features) gives orthogonal signal
 * to the gradient-boosted trees, and combined accuracy > best single model.
 *
 * <h3>Architecture</h3>
 * <pre>
 *   features (entry snapshot)
 *      │
 *      ├─ Layer 2: HeatWave ML (LightGBM, sub-second inference)
 *      ├─ Layer 3a: Gemini Flash-lite (LLM, ~500ms-2s)
 *      ├─ Layer 3b: Groq Llama 3.3 70B (LLM, ~500ms)
 *      │   (3a + 3b fired in parallel via CompletableFuture)
 *      │
 *      └─ Layer 4: Consensus = simple mean of layer outputs
 *                  + majority-vote-on-threshold (p ≥ 0.5)
 * </pre>
 *
 * <h3>Output</h3>
 * Returns p_win per layer + ensemble p_win + a "consensus_decision" (BUY/SKIP)
 * derived from majority vote at the 0.5 threshold. The MCP layer formats this
 * for human / LLM-AI consumption; the eval tool batches across holdout window.
 *
 * <h3>Failure tolerance</h3>
 * If ML layer fails → ensemble continues with LLM-only. If both LLMs fail →
 * fallback to ML-only. If everything fails → IllegalStateException (caller
 * decides what to do; for batch eval we just record a NULL row and continue).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SignalScorerEnsemble {

    private final MlTrainingOrchestrator orchestrator;
    private final AiTaskRouter aiTaskRouter;
    private final ObjectMapper objectMapper;
    private final com.agora.config.properties.AiRoutingProperties aiRoutingProps;

    /**
     * Score one signal across ML + LLM layers.
     *
     * @param features      same shape used by HeatWave ML predict (vw_signal_training_v2 columns minus target)
     * @param mlVersionId   ml_model_registry.id (or null to skip ML layer)
     * @param symbol        for prompt context (e.g. "BTCUSDT")
     * @param side          for prompt context ("LONG" / "SHORT")
     * @param currentPrice  for prompt context (entry price)
     */
    public EnsembleResult score(Map<String, Object> features, Long mlVersionId,
                                 String symbol, String side, BigDecimal currentPrice) {
        Map<String, LayerOutput> layers = new LinkedHashMap<>();

        // ────── Layer 2: HeatWave ML ──────
        if (mlVersionId != null) {
            try {
                Map<String, Object> mv = orchestrator.getVersion(mlVersionId);
                String handle = (String) mv.get("heatwave_handle");
                if (handle == null || handle.isBlank()) {
                    layers.put("layer2_ml", LayerOutput.failed("ml_v" + mlVersionId,
                            "no heatwave_handle (status=" + mv.get("status") + ")"));
                } else {
                    long t0 = System.currentTimeMillis();
                    orchestrator.loadModel(handle);
                    // HW ML003011: input columns must strictly match trained columns.
                    // Backfill any missing V047/V049/V050 columns with null so a caller
                    // who only supplies V047 base features doesn't crash the layer.
                    Map<String, Object> mlFeatures = new HashMap<>(features);
                    for (String k : com.agora.service.backtest.EntryFeatureSnapshot.ALL_FEATURE_KEYS) {
                        mlFeatures.putIfAbsent(k, null);
                    }
                    for (String k : com.agora.service.backtest.EntryFeatureSnapshot.STATIC_FEATURE_KEYS) {
                        mlFeatures.putIfAbsent(k, null);
                    }
                    String predJson = orchestrator.predictOne(handle, mlFeatures);
                    Double pWin = extractMlPWin(predJson);
                    layers.put("layer2_ml", LayerOutput.success(
                            "ml_v" + mlVersionId, pWin, "HeatWave LightGBM",
                            (int) (System.currentTimeMillis() - t0)));
                }
            } catch (Exception e) {
                layers.put("layer2_ml", LayerOutput.failed("ml_v" + mlVersionId, e.getMessage()));
            }
        }

        // ────── Layer 3: LLM scorers (parallel) ──────
        AiTask.ScoreSignal task = new AiTask.ScoreSignal(symbol, side, currentPrice, features);
        Map<String, AiProvider> all = aiTaskRouter.getProviders();
        List<String> providerNames = Arrays.stream(aiRoutingProps.scoreSignal().parallel().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        Map<String, CompletableFuture<LayerOutput>> futures = new LinkedHashMap<>();
        for (String name : providerNames) {
            AiProvider p = all.get(name);
            if (p == null) {
                layers.put("layer3_" + name, LayerOutput.failed(name, "provider not registered"));
                continue;
            }
            if (!p.healthy()) {
                layers.put("layer3_" + name, LayerOutput.failed(name, "provider unhealthy"));
                continue;
            }
            futures.put(name, CompletableFuture.supplyAsync(() -> callLlm(p, task)));
        }
        for (Map.Entry<String, CompletableFuture<LayerOutput>> e : futures.entrySet()) {
            try {
                layers.put("layer3_" + e.getKey(),
                        e.getValue().get(aiRoutingProps.scoreSignal().timeoutSec(), TimeUnit.SECONDS));
            } catch (Exception ex) {
                layers.put("layer3_" + e.getKey(),
                        LayerOutput.failed(e.getKey(), "timeout/error: " + ex.getMessage()));
            }
        }

        // ────── Layer 4: Consensus ──────
        List<LayerOutput> successful = layers.values().stream()
                .filter(LayerOutput::success).toList();
        if (successful.isEmpty()) {
            return new EnsembleResult(layers, null, null, null, 0,
                    "ALL_LAYERS_FAILED");
        }

        // Simple mean
        double mean = successful.stream().mapToDouble(o -> o.pWin).average().orElse(0.5);
        // Majority vote on threshold
        long buyVotes = successful.stream().filter(o -> o.pWin >= aiRoutingProps.scoreSignal().decisionThreshold()).count();
        long skipVotes = successful.size() - buyVotes;
        String decision = buyVotes > skipVotes ? "BUY"
                : skipVotes > buyVotes ? "SKIP"
                : (mean >= aiRoutingProps.scoreSignal().decisionThreshold() ? "BUY_TIE" : "SKIP_TIE");
        String consensusType = (long) successful.size() == buyVotes ? "UNANIMOUS_BUY"
                : (long) successful.size() == skipVotes ? "UNANIMOUS_SKIP"
                : Math.abs(buyVotes - skipVotes) >= 1 ? "MAJORITY"
                : "SPLIT";

        return new EnsembleResult(layers,
                BigDecimal.valueOf(mean).setScale(4, RoundingMode.HALF_UP),
                decision, consensusType, successful.size(), null);
    }

    /** Score using only one LLM provider (no ML, no ensemble). For ablation tests. */
    public LayerOutput scoreLlmOnly(Map<String, Object> features, String providerName,
                                     String symbol, String side, BigDecimal currentPrice) {
        AiProvider p = aiTaskRouter.getProviders().get(providerName);
        if (p == null) return LayerOutput.failed(providerName, "provider not registered");
        if (!p.healthy()) return LayerOutput.failed(providerName, "provider unhealthy");
        AiTask.ScoreSignal task = new AiTask.ScoreSignal(symbol, side, currentPrice, features);
        return callLlm(p, task);
    }

    private LayerOutput callLlm(AiProvider provider, AiTask.ScoreSignal task) {
        long t0 = System.currentTimeMillis();
        try {
            AiResponse resp = provider.execute(task);
            ParsedScore parsed = parseScoreJson(resp.text());
            int latency = (int) (System.currentTimeMillis() - t0);
            if (parsed == null) {
                String preview = resp.text() == null ? "(null)" :
                        resp.text().substring(0, Math.min(120, resp.text().length()));
                return LayerOutput.failed(provider.name(), "unparseable: " + preview);
            }
            String reasoning = parsed.reasoning();
            if (parsed.regime() != null) reasoning = "[" + parsed.regime() + "] " + reasoning;
            return LayerOutput.success(provider.name(), parsed.pWin(), reasoning, latency);
        } catch (Throwable t) {
            return LayerOutput.failed(provider.name(), t.getMessage());
        }
    }

    /** Strip markdown fence then parse JSON. */
    private ParsedScore parseScoreJson(String text) {
        if (text == null || text.isBlank()) return null;
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }
        // Some LLMs wrap with leading/trailing prose; try to slice the first {...}
        int lb = cleaned.indexOf('{');
        int rb = cleaned.lastIndexOf('}');
        if (lb >= 0 && rb > lb) cleaned = cleaned.substring(lb, rb + 1);
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            double p = node.path("p_win").asDouble(-1);
            if (p < 0 || p > 1) return null;
            String regime = node.path("regime").asText(null);
            String reasoning = node.path("reasoning").asText("(no reasoning)");
            return new ParsedScore(p, regime, reasoning);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * HeatWave ML_PREDICT_ROW response shape (verified 2026-04-17):
     * <pre>
     * {
     *   ...echoed feature columns...,
     *   "Prediction": 0|1,
     *   "ml_results": {
     *     "predictions":   {"profitable": 0|1},
     *     "probabilities": {"0": 0.89, "1": 0.11}
     *   }
     * }
     * </pre>
     * We want {@code ml_results.probabilities."1"} = P(profitable=1) = P(win).
     * Falls back to root-level {@code probabilities."1"} if the {@code ml_results}
     * envelope is missing (defensive — shouldn't happen with current HW versions).
     */
    private Double extractMlPWin(String predJson) {
        try {
            JsonNode root = objectMapper.readTree(predJson);
            // Preferred path: ml_results.probabilities.1
            JsonNode probs = root.path("ml_results").path("probabilities");
            if (probs.isMissingNode() || probs.isNull()) {
                // Fallback: root.probabilities.1 (older HW versions)
                probs = root.path("probabilities");
            }
            if (probs.isMissingNode() || probs.isNull()) {
                log.warn("[Ensemble] HW response missing probabilities: {}",
                        predJson.length() > 300 ? predJson.substring(0, 300) + "..." : predJson);
                return null;
            }
            JsonNode p1 = probs.get("1");
            if (p1 == null) return null;
            return p1.asDouble();
        } catch (Exception e) {
            log.warn("[Ensemble] extractMlPWin parse failed: {}", e.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Output records
    // ════════════════════════════════════════════════════════════════════════

    /** One layer's contribution to the ensemble. */
    public record LayerOutput(
            String providerName,
            boolean success,
            double pWin,           // 0.0 if !success
            String reasoning,      // null if !success
            int latencyMs,
            String error           // null if success
    ) {
        public static LayerOutput success(String name, Double pWin, String reasoning, int latencyMs) {
            if (pWin == null) return failed(name, "null pWin returned");
            return new LayerOutput(name, true, pWin, reasoning, latencyMs, null);
        }
        public static LayerOutput failed(String name, String error) {
            return new LayerOutput(name, false, 0.0, null, 0, error);
        }
    }

    /** Final ensemble verdict. */
    public record EnsembleResult(
            Map<String, LayerOutput> layers,
            BigDecimal ensemblePWin,
            String decision,           // BUY / SKIP / BUY_TIE / SKIP_TIE
            String consensusType,      // UNANIMOUS_BUY / UNANIMOUS_SKIP / MAJORITY / SPLIT
            int successCount,
            String error               // non-null if all layers failed
    ) {}

    private record ParsedScore(double pWin, String regime, String reasoning) {}
}
