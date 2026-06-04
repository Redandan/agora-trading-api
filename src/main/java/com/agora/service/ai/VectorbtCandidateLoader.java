package com.agora.service.ai;

import com.agora.config.properties.VectorbtProperties;
import com.agora.dto.backtest.SopMtfAdxConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads pre-screened strategy candidates from vectorbt scanner output (issue #196).
 *
 * <p>The Python scanner (vectorbt/scanner.py) runs a parameter grid search with
 * walk-forward validation and writes top candidates to a JSON file. This loader
 * reads that file and converts each entry to {@link SopMtfAdxConfig} for Java
 * backtest validation.
 *
 * <p>Candidate JSON fields → SopMtfAdxConfig mapping:
 * <ul>
 *   <li>{@code rsi_low}  → rsiPullbackThreshold (RSI pullback entry zone lower bound)</li>
 *   <li>{@code rsi_high} → rsiReboundConfirm (rsi_low + 3, first rebound confirmation)</li>
 *   <li>{@code sl_pct}   → fixedStopLossPct</li>
 *   <li>{@code tp_pct}   → fixedTakeProfitPct</li>
 * </ul>
 *
 * <p>Config: {@code trading.vectorbt.candidates-dir} (default: {@code vectorbt/output})
 * <p>Staleness: candidates older than {@code trading.vectorbt.max-age-days} (default: 30)
 * are ignored — forces periodic re-scan.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VectorbtCandidateLoader {

    private final ObjectMapper objectMapper;
    private final VectorbtProperties props;

    /** Minimum walk-forward pass rate to include a candidate. */
    private static final double MIN_WF_PASS_RATE = 0.60;

    /**
     * Load vectorbt candidates for the given symbol and interval.
     * Returns empty list if no file exists, file is stale, or the file has no
     * candidates meeting the minimum walk-forward threshold.
     *
     * @param symbol       e.g. "BTCUSDT"
     * @param intervalCode e.g. "1h"
     * @param maxCount     maximum number of candidates to return
     */
    public List<SopMtfAdxConfig> load(String symbol, String intervalCode, int maxCount) {
        String filename = String.format("top_candidates_%s_%s.json",
                symbol.toUpperCase(), intervalCode.toLowerCase());
        File file = new File(props.candidatesDir(), filename);

        if (!file.exists()) {
            log.debug("[VectorbtLoader] no candidate file: {}", file.getPath());
            return List.of();
        }

        // Staleness check
        long ageHours = ChronoUnit.HOURS.between(
                Instant.ofEpochMilli(file.lastModified()), Instant.now());
        if (ageHours > props.maxAgeDays() * 24L) {
            log.warn("[VectorbtLoader] candidate file is stale ({} hours old > {} days), skipping. " +
                    "Re-run: python3 vectorbt/scanner.py --symbol {} --interval {}",
                    ageHours, props.maxAgeDays(), symbol, intervalCode);
            return List.of();
        }

        try {
            JsonNode root = objectMapper.readTree(file);
            JsonNode candidates = root.path("candidates");
            if (!candidates.isArray() || candidates.isEmpty()) {
                log.warn("[VectorbtLoader] candidate file has no entries: {}", file.getPath());
                return List.of();
            }

            List<SopMtfAdxConfig> result = new ArrayList<>();
            for (JsonNode c : candidates) {
                if (result.size() >= maxCount) break;

                double wfPassRate = c.path("wf_pass_rate").asDouble(0);
                if (wfPassRate < MIN_WF_PASS_RATE) continue;

                SopMtfAdxConfig cfg = toConfig(c);
                if (cfg != null) result.add(cfg);
            }

            log.info("[VectorbtLoader] loaded {} candidates from {} (age={}h, wf≥{})",
                    result.size(), filename, ageHours, MIN_WF_PASS_RATE);
            return result;

        } catch (Exception e) {
            log.warn("[VectorbtLoader] failed to parse {}: {}", file.getPath(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Maps vectorbt output to SopMtfAdxConfig.
     *
     * <p>vectorbt tests a simple "RSI in [low, high]" entry condition which is
     * fundamentally different from SOP_MTF_ADX's pullback-rebound state machine.
     * Direct RSI parameter mapping causes 0 trades because:
     * 1. detectReboundReady needs RSI to first DROP below pullbackThreshold THEN
     *    rise above reboundConfirm — not just "be in range"
     * 2. trendLong (no-MTF mode) = close > EMA20 AND MACD > 0, which is rare
     *    in bearish periods when pullbacks occur
     *
     * Solution: use known-working RSI defaults (pullback=40, confirm=50) and let
     * vectorbt optimize ONLY the risk management parameters (SL/TP).
     * vectorbt's strength is fast SL/TP grid search; RSI timing stays fixed.
     */
    private SopMtfAdxConfig toConfig(JsonNode c) {
        try {
            double slPct = c.path("sl_pct").asDouble(0.02);
            double tpPct = c.path("tp_pct").asDouble(0.04);
            // Filter out degenerate candidates where tp <= sl (R/R ≤ 1)
            if (tpPct <= slPct) return null;

            SopMtfAdxConfig cfg = new SopMtfAdxConfig();
            cfg.setEnableMtf(false);
            cfg.setMinSignals(2);            // standard gate
            cfg.setAdxEntryThreshold(25.0);  // standard gate
            // Fixed RSI parameters — proven to generate trades in SOP_MTF_ADX
            cfg.setRsiPullbackThreshold(40.0);
            cfg.setRsiReboundConfirm(50.0);
            // vectorbt-optimized risk management
            cfg.setFixedStopLossPct(slPct);
            cfg.setFixedTakeProfitPct(tpPct);
            cfg.setAllowShort(false);
            cfg.setMoveSlToBreakeven(false);
            cfg.setMinRR(1.5);  // match Strategy 27's proven 1.8, use 1.5 for more permissive discovery
            return cfg;
        } catch (Exception e) {
            log.debug("[VectorbtLoader] skipping malformed candidate: {}", e.getMessage());
            return null;
        }
    }
}
