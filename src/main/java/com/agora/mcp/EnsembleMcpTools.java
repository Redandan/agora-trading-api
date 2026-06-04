package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.GeminiMarketHint;
import com.agora.model.MarketFlipEvent;
import com.agora.model.MdKline;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.market.DeterministicRegimeClassifier;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.PolymarketService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.meta.TradeDecisionEngine;
import com.agora.service.ml.MlInferenceLogger;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ensemble-scoring MCP tools — Phase 1 conviction score preview.
 *
 * <p>Single tool {@code previewEnsembleScore} that gathers current readings
 * from ML + Gemini + sentiment + flip + polymarket, hands them to
 * {@link TradeDecisionEngine}, and returns a full breakdown. Pure read-only,
 * no audit write, no side effect on live trading.
 *
 * <p>Used to validate the ensemble logic before wiring into
 * {@code LiveSignalEvaluator}. Once weights are deemed reasonable, the same
 * input-gathering logic will migrate to LiveSignalEvaluator for shadow-mode
 * scoring of real BUY/SELL signals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnsembleMcpTools {

    private final TradeDecisionEngine engine;
    private final MlInferenceLogger inferenceLogger;
    private final MdKlineRepository klineRepository;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final OkxTradingService okxTradingService;
    private final PolymarketService polymarketService;
    private final GeminiMarketHintRepository hintRepository;
    private final MarketFlipEventRepository flipEventRepository;
    private final AiStrategyDiscoveryService discoveryService;
    private final JdbcTemplate jdbc;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "Phase 1 ensemble 決策預覽 — 一次收集 ML P(win) + Gemini hint + sentiment(F&G/whale/funding/LS)" +
            " + Polymarket 宏觀風險 + 最近 Market Flip,丟進 TradeDecisionEngine 算 0-100 conviction score + 逐層 breakdown。" +
            "不下單、不寫 audit、不影響 live trading,純驗證 ensemble 邏輯。" +
            "params: symbol(如 BTCUSDT), intervalCode(1h/4h), side(LONG/SHORT), strategyId(影響 ML features,可 0)")
    public String previewEnsembleScore(String symbol, String intervalCode, String side, Long strategyId) {
        { String _e = McpParamValidator.requireNonBlank(symbol, "symbol"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(intervalCode, "intervalCode"); if (_e != null) return _e; }
        String sd = (side == null || side.isBlank()) ? "LONG" : side.toUpperCase();
        if (!"LONG".equals(sd) && !"SHORT".equals(sd)) return "❌ side 必須是 LONG 或 SHORT";
        TradeDecisionEngine.Side engineSide = "LONG".equals(sd)
                ? TradeDecisionEngine.Side.LONG : TradeDecisionEngine.Side.SHORT;
        long sid = strategyId == null ? 0L : strategyId;

        try {
            // ── ML preview (gives p_win + features with rsi/adx already computed) ──
            Double pWin = null;
            Double rsi = null;
            Double adx = null;
            Long mlModelVersion = null;
            String mlDecision = null;
            try {
                List<MdKline> klinesDesc = klineRepository
                        .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(symbol, intervalCode,
                                PageRequest.of(0, 60));
                if (!klinesDesc.isEmpty()) {
                    List<MdKline> klinesAsc = new ArrayList<>(klinesDesc);
                    Collections.reverse(klinesAsc);
                    int lastIdx = klinesAsc.size() - 1;
                    MlInferenceLogger.PreviewResult res = inferenceLogger.previewSync(
                            symbol, intervalCode, sd, sid, klinesAsc, lastIdx);
                    if (res.errorMessage() == null) {
                        pWin = res.pWin();
                        mlModelVersion = res.modelVersionId();
                        mlDecision = res.decision();
                        Object r = res.features().get("rsi14");
                        Object a = res.features().get("adx14");
                        if (r instanceof Number rn) rsi = rn.doubleValue();
                        if (a instanceof Number an) adx = an.doubleValue();
                    }
                }
            } catch (Exception e) {
                log.debug("[previewEnsemble] ML preview failed: {}", e.getMessage());
            }

            // ── Sentiment snapshot ──
            Integer fg = safeInt(() -> fearGreedService.getFearGreedValue());
            Double whale = safeDouble(() -> whaleFlowService.getBuyRatio(symbol));
            // funding rate from OKX returns decimal (e.g. 0.0001 = 0.01%), normalize to percent-per-8h
            Double fundingPct = safeDouble(() -> okxTradingService.getCurrentFundingRate(symbol) * 100);
            Double lsRatio = safeDouble(() -> {
                double r = okxTradingService.getLongShortRatio(symbol);
                return r < 0 ? null : r;  // -1 sentinel = fetch failure
            });

            // ── Gemini hint (latest active for this symbol/timeframe) ──
            String geminiStyle = null;
            String geminiRegime = null;
            Double geminiConf = null;
            Boolean geminiShortOk = null;
            try {
                List<GeminiMarketHint> hints = hintRepository.findActiveHints(
                        symbol, intervalCode, LocalDateTime.now(),
                        PageRequest.of(0, 1));
                if (!hints.isEmpty()) {
                    GeminiMarketHint h = hints.get(0);
                    geminiStyle = h.getStyleHint();
                    geminiRegime = h.getRegime();
                    if (h.getConfidence() != null) geminiConf = h.getConfidence().doubleValue();
                    geminiShortOk = h.getAllowShort();
                }
            } catch (Exception e) {
                log.debug("[previewEnsemble] hint fetch failed: {}", e.getMessage());
            }

            // ── Polymarket macro risk ──
            Double polyRiskPct = null;
            try {
                PolymarketService.MacroRiskResult poly = polymarketService.getMacroRisk();
                if (poly != null && poly.riskScore() >= 0) {
                    polyRiskPct = poly.riskScore() * 100.0;
                }
            } catch (Exception e) {
                log.debug("[previewEnsemble] polymarket failed: {}", e.getMessage());
            }

            // ── Recent market flip (last 4h window for engine) ──
            Integer flipMinutes = null;
            try {
                List<MarketFlipEvent> recent = flipEventRepository
                        .findLatestBySymbol(symbol, PageRequest.of(0, 1));
                if (!recent.isEmpty() && recent.get(0).getDetectedAt() != null) {
                    long mins = Duration.between(recent.get(0).getDetectedAt(), LocalDateTime.now()).toMinutes();
                    if (mins >= 0) flipMinutes = (int) Math.min(mins, Integer.MAX_VALUE);
                }
            } catch (Exception e) {
                log.debug("[previewEnsemble] flip fetch failed: {}", e.getMessage());
            }

            // ── Build inputs + score ──
            TradeDecisionEngine.Inputs inputs = new TradeDecisionEngine.Inputs(
                    engineSide,
                    pWin,
                    null,                // strategyScore — not trivial to compute standalone; rely on ML
                    geminiStyle, geminiRegime, geminiShortOk, geminiConf,
                    rsi, adx,
                    lsRatio, whale, fundingPct, fg, polyRiskPct, flipMinutes,
                    null, null,          // filters — not run standalone (no BUY/SELL signal happening)
                    null, null);         // orderBookImbalance, availableUsdtAmt — fetched live by LSE

            TradeDecisionEngine.Decision decision = engine.score(inputs);

            // ── Format output ──
            StringBuilder sb = new StringBuilder();
            sb.append("=== Ensemble Preview — ").append(symbol).append(" ")
              .append(intervalCode).append(" ").append(sd).append(" ===\n\n");

            String resultEmoji = switch (decision.outcome()) {
                case "PASS"  -> "✅";
                case "BLOCK" -> "🚫";
                case "VETO"  -> "🛑";
                default -> "·";
            };
            sb.append(String.format("%s SCORE = %.1f / threshold %.1f  →  %s%n",
                    resultEmoji, decision.score(), decision.threshold(), decision.outcome()));
            if (decision.vetoReason() != null) {
                sb.append("   VETO reason: ").append(decision.vetoReason()).append("\n");
            }

            sb.append("\n-- Inputs used --\n");
            if (pWin != null) {
                sb.append(String.format("  ML v%s        p_win=%.4f (%s)%n",
                        mlModelVersion != null ? mlModelVersion : "?", pWin,
                        mlDecision != null ? mlDecision : "?"));
            } else {
                sb.append("  ML             (unavailable)\n");
            }
            if (geminiStyle != null || geminiRegime != null) {
                sb.append(String.format("  Gemini         style=%s regime=%s conf=%s short_ok=%s%n",
                        geminiStyle, geminiRegime,
                        geminiConf == null ? "?" : String.format("%.2f", geminiConf),
                        geminiShortOk));
            }
            sb.append(String.format("  Indicators     rsi=%s adx=%s%n",
                    rsi == null ? "?" : String.format("%.1f", rsi),
                    adx == null ? "?" : String.format("%.1f", adx)));
            sb.append(String.format("  Sentiment      fg=%s whale=%s funding=%s L/S=%s%n",
                    fg == null ? "?" : fg,
                    whale == null ? "?" : String.format("%.2f", whale),
                    fundingPct == null ? "?" : String.format("%+.4f%%/8h", fundingPct),
                    lsRatio == null ? "?" : String.format("%.2f", lsRatio)));
            sb.append(String.format("  Polymarket     risk=%s%n",
                    polyRiskPct == null ? "?" : String.format("%.0f%%", polyRiskPct)));
            sb.append(String.format("  MarketFlip     recent=%s%n",
                    flipMinutes == null ? "none" : flipMinutes + " min ago"));

            sb.append("\n-- Components --\n");
            for (TradeDecisionEngine.Component c : decision.components()) {
                String sign = c.points() >= 0 ? "+" : "";
                sb.append(String.format("  %-22s %s%.1f   %s%n",
                        c.layer(), sign, c.points(), c.reason()));
            }

            return sb.toString();
        } catch (Exception e) {
            log.warn("[MCP previewEnsembleScore] failed", e);
            return "❌ preview 失敗: " + e.getMessage();
        }
    }

    // ─── compareRegimeClassifiers ─────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.META})
    @Tool(description = "比較 Gemini 3-persona 投票 vs 確定性規則分類器(DeterministicRegimeClassifier)的一致性。" +
            "兩部分輸出：" +
            "①即時快照：對當前市場跑兩個分類器並排顯示(regime/style/allowShort)。" +
            "②歷史一致率：分析近 N 天 gemini_market_hint 中已嵌入 deterministic 結果的紀錄，" +
            "統計 regime/style 一致率與分布差異。" +
            "結果用於決定何時可以停用 GeminiMarketAdvisor，改用確定性分類器。" +
            "params: symbol(如 BTCUSDT,null=全部), days(歷史天數,預設 30)")
    public String compareRegimeClassifiers(String symbol, Integer days) {
        int d = (days == null || days < 1) ? 30 : Math.min(days, 90);
        StringBuilder sb = new StringBuilder();
        sb.append("=== Regime 分類器 A/B 比較 ===\n\n");

        // ── Part 1: 即時快照 ──────────────────────────────────────────────
        sb.append("── 即時快照（DeterministicRegimeClassifier vs 最新 Gemini hint）──\n\n");
        List<String> symbols = (symbol != null && !symbol.isBlank())
                ? List.of(symbol.toUpperCase())
                : List.of("BTCUSDT", "ETHUSDT");
        List<String> timeframes = List.of("1h", "4h");

        for (String sym : symbols) {
            for (String tf : timeframes) {
                sb.append(String.format("  %s @ %s\n", sym, tf));
                // deterministic
                try {
                    String ctxTf = "1h".equals(tf) ? "4h" : "1h";
                    AiStrategyDiscoveryService.MarketSnapshot primary =
                            discoveryService.buildMarketSnapshot(sym, tf);
                    AiStrategyDiscoveryService.MarketSnapshot context =
                            discoveryService.buildMarketSnapshot(sym, ctxTf);
                    DeterministicRegimeClassifier.Result det =
                            DeterministicRegimeClassifier.classify(primary, context);
                    sb.append(String.format("    Deterministic  regime=%-14s style=%-14s short=%s conf=%.1f\n",
                            det.regime(), det.styleHint(),
                            det.allowShort() ? "Y" : "N", det.confidence()));
                    sb.append(String.format("    Indicators     rsi=%.1f adx=%.1f atr=%.2f%% trend=%s\n",
                            primary.rsi14(), primary.adx14(), primary.atrPct(), primary.trendDirection()));
                } catch (Exception e) {
                    sb.append("    Deterministic  ERROR: ").append(e.getMessage()).append("\n");
                }
                // latest gemini hint
                try {
                    List<GeminiMarketHint> hints = hintRepository.findActiveHints(
                            sym, tf, LocalDateTime.now(), PageRequest.of(0, 1));
                    if (hints.isEmpty()) {
                        sb.append("    Gemini         (no active hint)\n");
                    } else {
                        GeminiMarketHint h = hints.get(0);
                        sb.append(String.format("    Gemini         regime=%-14s style=%-14s short=%s conf=%.2f\n",
                                h.getRegime(), h.getStyleHint(),
                                Boolean.TRUE.equals(h.getAllowShort()) ? "Y" : "N",
                                h.getConfidence().doubleValue()));
                    }
                } catch (Exception e) {
                    sb.append("    Gemini         ERROR: ").append(e.getMessage()).append("\n");
                }
                sb.append("\n");
            }
        }

        // ── Part 2: 歷史一致率 ────────────────────────────────────────────
        sb.append("── 歷史一致率（近 ").append(d).append(" 天，需 personaVotes 含 'deterministic' key）──\n\n");
        try {
            LocalDateTime since = LocalDateTime.now().minusDays(d);
            List<GeminiMarketHint> allHints;
            if (symbol != null && !symbol.isBlank()) {
                // filter by symbol using available repo methods
                allHints = new ArrayList<>();
                for (String tf : List.of("1h", "4h")) {
                    allHints.addAll(hintRepository
                            .findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(
                                    symbol.toUpperCase(), tf, since));
                }
            } else {
                // fetch all recent, filter by date
                allHints = hintRepository.findTop50ByOrderByCreatedAtDesc().stream()
                        .filter(h -> h.getCreatedAt().isAfter(since))
                        .toList();
            }

            int total = 0, withDet = 0, regimeAgree = 0, styleAgree = 0;
            // distribution counters
            java.util.Map<String,Integer> gemRegime = new java.util.TreeMap<>();
            java.util.Map<String,Integer> detRegime  = new java.util.TreeMap<>();
            java.util.Map<String,Integer> gemStyle  = new java.util.TreeMap<>();
            java.util.Map<String,Integer> detStyle   = new java.util.TreeMap<>();
            // disagreement log (last 5)
            List<String> disagreeLog = new ArrayList<>();

            for (GeminiMarketHint h : allHints) {
                total++;
                gemRegime.merge(h.getRegime(), 1, Integer::sum);
                gemStyle.merge(h.getStyleHint(), 1, Integer::sum);
                if (h.getPersonaVotes() == null) continue;
                try {
                    JsonNode pv = MAPPER.readTree(h.getPersonaVotes());
                    if (!pv.has("deterministic")) continue;
                    withDet++;
                    JsonNode det = pv.get("deterministic");
                    String dReg = det.path("regime").asText("");
                    String dSty = det.path("style").asText("");
                    if (!dReg.isEmpty()) detRegime.merge(dReg, 1, Integer::sum);
                    if (!dSty.isEmpty()) detStyle.merge(dSty, 1, Integer::sum);

                    boolean rAgree = h.getRegime().equals(dReg);
                    boolean sAgree = h.getStyleHint().equals(dSty);
                    if (rAgree) regimeAgree++;
                    if (sAgree) styleAgree++;
                    if ((!rAgree || !sAgree) && disagreeLog.size() < 5) {
                        disagreeLog.add(String.format("    %s %s@%s: Gemini=%s/%s Deterministic=%s/%s",
                                h.getCreatedAt().toLocalDate(), h.getSymbol(), h.getTimeframe(),
                                h.getRegime(), h.getStyleHint(), dReg, dSty));
                    }
                } catch (Exception ignored) {}
            }

            sb.append(String.format("  總共 %d 筆 hint，其中 %d 筆含 deterministic 結果\n", total, withDet));
            if (withDet == 0) {
                sb.append("  ⚠️  尚無 deterministic 數據 — 部署後下一次 GeminiAdvisor 排程執行後才會有\n");
                sb.append("     可手動觸發：triggerGeminiAdvisor() MCP 工具\n");
            } else {
                double rPct = 100.0 * regimeAgree / withDet;
                double sPct = 100.0 * styleAgree / withDet;
                sb.append(String.format("  Regime 一致率: %.0f%%  (%d/%d)\n", rPct, regimeAgree, withDet));
                sb.append(String.format("  Style  一致率: %.0f%%  (%d/%d)\n", sPct, styleAgree, withDet));
                sb.append("\n  Gemini regime 分布:      ").append(gemRegime).append("\n");
                sb.append(  "  Deterministic 分布:      ").append(detRegime).append("\n");
                sb.append("\n  Gemini style 分布:        ").append(gemStyle).append("\n");
                sb.append(  "  Deterministic style 分布: ").append(detStyle).append("\n");
                if (!disagreeLog.isEmpty()) {
                    sb.append("\n  最近不一致案例 (最多 5 筆):\n");
                    disagreeLog.forEach(l -> sb.append(l).append("\n"));
                }
                // recommendation
                sb.append("\n  ── 建議 ──\n");
                if (rPct >= 85 && sPct >= 80) {
                    sb.append("  ✅ 一致率 ≥ 85% / 80% → 可考慮停用 GeminiMarketAdvisor，改用確定性分類器\n");
                    sb.append("     步驟：設 trading.gemini-advisor.enabled=false，LiveSignalEvaluator 改讀 DeterministicRegimeClassifier\n");
                } else if (withDet < 20) {
                    sb.append("  ⏳ 樣本不足（< 20 筆）— 等待更多排程執行後再判斷\n");
                } else {
                    sb.append(String.format("  ⚠️ 一致率 %.0f%% / %.0f%% 未達門檻 (85%%/80%%) — 檢查不一致案例，調整規則\n", rPct, sPct));
                }
            }
        } catch (Exception e) {
            sb.append("  ❌ 歷史分析失敗: ").append(e.getMessage()).append("\n");
            log.warn("[compareRegimeClassifiers] historical analysis failed", e);
        }

        return sb.toString();
    }

    /**
     * #437 sub-task 2 — Ensemble shadow accuracy audit.
     *
     * <p>對比 ensemble 算 BLOCK 但仍 trade(Phase 1 shadow)的 case 後續實際 P&amp;L,
     * 跟 ensemble 算 PASS case 比。若 BLOCK case 平均 P&amp;L 顯著差於 PASS(BLOCK_avg &lt; PASS_avg × 0.5)
     * → ensemble 有預測力,可 promote sub-task 3 Phase 2 enforce(BLOCK 真擋)。
     *
     * <p>樣本要求:BLOCK ≥ 50,window ≥ 30 days(統計可信度)。
     *
     * <p>資料路徑: bt_decision_audit(SIGNAL_EVAL with ensemble in context_json)
     *  JOIN bt_live_signal(realized_pnl,只算已平倉)。
     */
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.READ_TRADING, Category.META})
    @Tool(description = "Audit ensemble shadow 預測準確度。比對 BLOCK case 後續 P&L 平均 vs PASS case,給 promote 建議。" +
            "params: days(回溯天數,預設 30), minSampleSize(每 bucket 最少樣本,預設 30)")
    public String auditEnsembleShadowAccuracy(Integer days, Integer minSampleSize) {
        int d = (days == null || days <= 0) ? 30 : Math.min(days, 180);
        int minN = (minSampleSize == null || minSampleSize <= 0) ? 30 : minSampleSize;
        LocalDateTime since = LocalDateTime.now().minusDays(d);

        // Pull SIGNAL_EVAL audits with ensemble extras + matching live_signal exit info.
        // JSON path: contextJson.ensemble.outcome / contextJson.ensemble.score
        String sql =
                "SELECT " +
                "  JSON_UNQUOTE(JSON_EXTRACT(a.context_json, '$.ensemble.outcome')) AS outcome, " +
                "  JSON_EXTRACT(a.context_json, '$.ensemble.score') AS score, " +
                "  s.realized_pnl, s.entry_price, s.exit_time " +
                "FROM bt_decision_audit a " +
                "JOIN bt_live_signal s " +
                "  ON s.strategy_id = a.strategy_id " +
                "  AND s.symbol = a.symbol " +
                "  AND s.interval_code = a.interval_code " +
                "  AND s.bar_open_time = a.bar_open_time " +
                "WHERE a.event_type = 'SIGNAL_EVAL' " +
                "  AND a.event_time >= ? " +
                "  AND JSON_EXTRACT(a.context_json, '$.ensemble.outcome') IS NOT NULL " +
                "  AND s.realized_pnl IS NOT NULL " +
                "  AND s.exit_time IS NOT NULL";

        java.util.Map<String, Bucket> buckets = new java.util.LinkedHashMap<>();
        buckets.put("PASS",  new Bucket());
        buckets.put("BLOCK", new Bucket());
        buckets.put("VETO",  new Bucket());

        try {
            jdbc.query(sql, ps -> ps.setObject(1, since), rs -> {
                String outcome = rs.getString("outcome");
                java.math.BigDecimal pnl = rs.getBigDecimal("realized_pnl");
                java.math.BigDecimal entry = rs.getBigDecimal("entry_price");
                if (outcome == null || pnl == null || entry == null || entry.signum() <= 0) return;
                double pnlPct = pnl.doubleValue() / entry.doubleValue();
                Bucket b = buckets.get(outcome.toUpperCase());
                if (b == null) return;  // unknown outcome label, skip
                b.n++;
                b.sumPnl += pnl.doubleValue();
                b.sumPnlPct += pnlPct;
                if (pnl.signum() > 0) b.wins++;
            });
        } catch (Exception e) {
            return "❌ audit query failed: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Ensemble Shadow Accuracy Audit ===\n");
        sb.append(String.format("window: %d days (since %s)\n", d, since.toLocalDate()));
        sb.append(String.format("min sample size per bucket: %d\n\n", minN));

        sb.append("bucket  n   wins  winrate  avg_pnl    avg_pnl_pct\n");
        for (var e : buckets.entrySet()) {
            Bucket b = e.getValue();
            if (b.n == 0) {
                sb.append(String.format("%-7s %-3d %-5d %-8s %-10s %s\n",
                        e.getKey(), 0, 0, "n/a", "n/a", "n/a"));
                continue;
            }
            double winrate = (double) b.wins / b.n;
            double avgPnl = b.sumPnl / b.n;
            double avgPnlPct = b.sumPnlPct / b.n;
            sb.append(String.format("%-7s %-3d %-5d %-8.1f%% %+9.4f %+8.2f%%\n",
                    e.getKey(), b.n, b.wins, winrate * 100, avgPnl, avgPnlPct * 100));
        }
        sb.append("\n");

        Bucket pass = buckets.get("PASS");
        Bucket block = buckets.get("BLOCK");

        sb.append("── Promotion gate (sub-task 3 Phase 2 enforce) ──\n");
        if (pass.n < minN || block.n < minN) {
            sb.append(String.format("⏳ Insufficient samples: PASS=%d BLOCK=%d (need ≥%d each)\n",
                    pass.n, block.n, minN));
            sb.append("→ keep collecting; do NOT promote yet.\n");
        } else {
            double passAvg = pass.sumPnl / pass.n;
            double blockAvg = block.sumPnl / block.n;
            double ratio = passAvg != 0 ? blockAvg / passAvg : Double.NaN;
            sb.append(String.format("PASS  avg_pnl = %+.4f  (n=%d)\n", passAvg, pass.n));
            sb.append(String.format("BLOCK avg_pnl = %+.4f  (n=%d)\n", blockAvg, block.n));
            sb.append(String.format("ratio BLOCK/PASS = %.2f\n", ratio));
            if (passAvg > 0 && blockAvg < passAvg * 0.5) {
                sb.append("✅ BLOCK avg < PASS × 0.5 — ensemble has predictive power → recommend promote Phase 2.\n");
            } else if (passAvg > 0 && blockAvg < 0) {
                sb.append("✅ BLOCK avg negative while PASS positive — ensemble has predictive power → recommend promote Phase 2.\n");
            } else {
                sb.append("⚠️  BLOCK not significantly worse than PASS — ensemble lacks signal at current threshold.\n");
                sb.append("   → tune weights / threshold OR keep shadow longer.\n");
            }
        }
        return sb.toString();
    }

    private static class Bucket {
        int n = 0;
        int wins = 0;
        double sumPnl = 0.0;
        double sumPnlPct = 0.0;
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private interface IntFetcher { Integer get() throws Exception; }
    private interface DoubleFetcher { Double get() throws Exception; }

    private static Integer safeInt(IntFetcher f) {
        try { return f.get(); } catch (Exception e) { return null; }
    }
    private static Double safeDouble(DoubleFetcher f) {
        try {
            Double v = f.get();
            return (v == null || v.isNaN() || v.isInfinite()) ? null : v;
        } catch (Exception e) { return null; }
    }
}
