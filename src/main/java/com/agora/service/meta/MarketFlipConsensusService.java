package com.agora.service.meta;

import com.agora.model.MarketFlipAiAnalysis;
import com.agora.model.MarketFlipDecision;
import com.agora.model.MarketFlipEvent;
import com.agora.repository.trading.MarketFlipAiAnalysisRepository;
import com.agora.repository.trading.MarketFlipDecisionRepository;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.infra.notification.NotificationPort;
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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Market flip 共識合成器 — 支援統計規則模式（預設）和 AI 共識模式（可選）。
 *
 * <h3>預設模式：統計規則（aiConsensusEnabled=false）</h3>
 * <ol>
 *   <li>{@link StatisticalFlipAnalyzer} 根據指標值與門檻直接判斷 ALERT/DISMISS</li>
 *   <li>寫 {@code market_flip_ai_analysis}（provider="statistical-rules"）</li>
 *   <li>寫 {@code market_flip_decision}（decider="statistical-rules"）</li>
 *   <li>若 ALERT → 發 TG，含規則推導說明</li>
 * </ol>
 *
 * <h3>AI 共識模式（aiConsensusEnabled=true）</h3>
 * <ol>
 *   <li>並行呼叫 Groq + Gemini（各 30s timeout）</li>
 *   <li>UNANIMOUS / MAJORITY / SPLIT 投票合成</li>
 *   <li>全失敗 → fallback 到統計規則，不留 PENDING</li>
 * </ol>
 *
 * <p><b>設計理由</b>：市場翻轉偵測器在設定門檻時已確定重要性，不需再用 LLM 判斷「這重要嗎」。
 * AI 在此的附加值僅為 reasoning 文字（可讀性），決策本身可由規則覆蓋。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketFlipConsensusService {

    private final AiTaskRouter aiTaskRouter;
    private final MarketFlipEventRepository eventRepo;
    private final MarketFlipAiAnalysisRepository analysisRepo;
    private final MarketFlipDecisionRepository decisionRepo;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;

    private final com.agora.config.properties.MarketFlipProperties marketFlipProps;
    private final com.agora.config.properties.AiRoutingProperties aiRoutingProps;

    /**
     * 對 PENDING event 執行分析，完成後 event status 轉 REVIEWED 並（視決策）發 TG。
     *
     * <p>依 {@code aiConsensusEnabled} 選擇路徑：
     * <ul>
     *   <li>false（預設）：{@link StatisticalFlipAnalyzer} 即時決策，無 API call</li>
     *   <li>true：並行 AI 投票；全失敗時自動 fallback 到統計規則</li>
     * </ul>
     *
     * @return 是否成功產生 decision（一律 true，除非 DB 錯誤）
     */
    public boolean processEvent(MarketFlipEvent event) {
        try {
            event.setStatus("IN_REVIEW");
            eventRepo.save(event);

            List<AnalysisAttempt> successful;

            if (marketFlipProps.aiConsensus().enabled()) {
                // ── AI 共識路徑 ─────────────────────────────────────────────
                successful = runAiConsensus(event);
                if (successful.isEmpty()) {
                    // 全失敗 → fallback 到統計規則（不留 PENDING）
                    log.warn("[FlipConsensus] AI all failed, falling back to statistical rules for event={}",
                            event.getId());
                    successful = List.of(StatisticalFlipAnalyzer.analyze(event));
                }
            } else {
                // ── 統計規則路徑（預設）─────────────────────────────────────
                successful = List.of(StatisticalFlipAnalyzer.analyze(event));
                log.debug("[FlipConsensus] statistical: event={} {} → {}",
                        event.getId(), event.getIndicator(), successful.get(0).decision());
            }

            // 寫 analysis 表（統計規則也寫，保持 pipeline 一致）
            for (AnalysisAttempt a : successful) {
                MarketFlipAiAnalysis row = new MarketFlipAiAnalysis();
                row.setEventId(event.getId());
                row.setProvider(a.providerName());
                row.setDecision(a.decision());
                row.setConfidence(BigDecimal.valueOf(a.confidence()));
                row.setReasoning(a.reasoning());
                row.setTokensUsed(a.tokensUsed());
                row.setLatencyMs(a.latencyMs());
                row.setAnalyzedAt(LocalDateTime.now(ZoneOffset.UTC));
                analysisRepo.save(row);
            }

            // 合成共識
            Consensus consensus = buildConsensus(successful);
            MarketFlipDecision decision = new MarketFlipDecision();
            decision.setEventId(event.getId());
            decision.setFinalDecision(consensus.finalDecision());
            decision.setConsensusType(consensus.consensusType());
            decision.setDecider(marketFlipProps.aiConsensus().enabled() ? "ai-consensus" : "statistical-rules");
            decision.setSummary(consensus.summary());
            decision.setActionTakenJson("{}");
            decision.setDecidedAt(LocalDateTime.now(ZoneOffset.UTC));
            decisionRepo.save(decision);

            // 標記 event 已審
            event.setStatus("REVIEWED");
            event.setReviewedAt(LocalDateTime.now(ZoneOffset.UTC));
            eventRepo.save(event);

            // ALERT → 發 TG
            if ("ALERT".equals(consensus.finalDecision())) {
                sendConsensusAlert(event, successful, consensus);
            } else {
                log.info("[FlipConsensus] event={} decision={} consensus={} (no TG)",
                        event.getId(), consensus.finalDecision(), consensus.consensusType());
            }
            return true;
        } catch (Throwable t) {
            log.error("[FlipConsensus] processEvent {} failed: {}", event.getId(), t.getMessage(), t);
            try {
                event.setStatus("PENDING");
                eventRepo.save(event);
            } catch (Exception ignored) {}
            return false;
        }
    }

    /** 並行呼叫所有 AI provider，回傳成功的 attempts（可為空）。 */
    private List<AnalysisAttempt> runAiConsensus(MarketFlipEvent event) {
        List<String> providerNames = Arrays.stream(aiRoutingProps.analyzeMarketFlip().parallel().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        AiTask.AnalyzeMarketFlip task = new AiTask.AnalyzeMarketFlip(
                event.getId(), event.getSymbol(), event.getIndicator(),
                event.getPrevValue(), event.getCurrentValue(),
                event.getThresholdCrossed(), event.getContextJson());
        Map<String, AiProvider> allProviders = aiTaskRouter.getProviders();
        Map<String, CompletableFuture<AnalysisAttempt>> futures = new ConcurrentHashMap<>();
        for (String name : providerNames) {
            AiProvider provider = allProviders.get(name);
            if (provider == null || !provider.healthy()) continue;
            futures.put(name, CompletableFuture.supplyAsync(() -> callProvider(provider, task)));
        }
        if (futures.isEmpty()) return List.of();
        List<AnalysisAttempt> attempts = new ArrayList<>();
        for (Map.Entry<String, CompletableFuture<AnalysisAttempt>> e : futures.entrySet()) {
            try {
                AnalysisAttempt a = e.getValue().get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (a != null) attempts.add(a);
            } catch (Exception ex) {
                log.warn("[FlipConsensus] provider {} timeout/failed: {}", e.getKey(), ex.getMessage());
            }
        }
        return attempts.stream().filter(AnalysisAttempt::success).toList();
    }

    private AnalysisAttempt callProvider(AiProvider provider, AiTask task) {
        long t0 = System.currentTimeMillis();
        try {
            AiResponse resp = provider.execute(task);
            long latency = System.currentTimeMillis() - t0;
            ParsedAnalysis parsed = parseAiJson(resp.text());
            if (parsed == null) {
                log.warn("[FlipConsensus] provider {} returned unparseable: {}", provider.name(),
                        resp.text() != null ? resp.text().substring(0, Math.min(200, resp.text().length())) : "null");
                return AnalysisAttempt.failed(provider.name());
            }
            int totalTok = resp.inputTokens() + resp.outputTokens();
            return new AnalysisAttempt(provider.name(), true,
                    parsed.decision(), parsed.confidence(), parsed.reasoning(),
                    totalTok, (int) latency);
        } catch (Throwable t) {
            log.warn("[FlipConsensus] provider {} call failed: {}", provider.name(), t.getMessage());
            return AnalysisAttempt.failed(provider.name());
        }
    }

    private ParsedAnalysis parseAiJson(String aiText) {
        if (aiText == null || aiText.isBlank()) return null;
        String cleaned = aiText.trim();
        // Strip markdown code block if present
        if (cleaned.startsWith("```")) {
            int firstNl = cleaned.indexOf('\n');
            if (firstNl > 0) cleaned = cleaned.substring(firstNl + 1);
            if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);
            cleaned = cleaned.trim();
        }
        try {
            JsonNode node = objectMapper.readTree(cleaned);
            String decision = node.path("decision").asText("").toUpperCase();
            double confidence = node.path("confidence").asDouble(0.5);
            String reasoning = node.path("reasoning").asText("(no reasoning)");
            if (!Set.of("DISMISS", "ALERT", "TUNE").contains(decision)) return null;
            return new ParsedAnalysis(decision, confidence, reasoning);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Consensus 邏輯:
     * - 只有 1 個成功 → SINGLE_AI
     * - 2+ 成功,全同決策 → UNANIMOUS
     * - 2+ 成功,多數決 → MAJORITY
     * - 平手 → SPLIT (取 confidence 較高者,但標 SPLIT 提醒觀察)
     */
    private Consensus buildConsensus(List<AnalysisAttempt> attempts) {
        if (attempts.size() == 1) {
            AnalysisAttempt a = attempts.get(0);
            return new Consensus(a.decision(), "SINGLE_AI",
                    String.format("唯一回應 %s conf=%.2f: %s", a.providerName(), a.confidence(), a.reasoning()));
        }

        Map<String, List<AnalysisAttempt>> byDecision = new HashMap<>();
        for (AnalysisAttempt a : attempts) {
            byDecision.computeIfAbsent(a.decision(), k -> new ArrayList<>()).add(a);
        }

        // UNANIMOUS
        if (byDecision.size() == 1) {
            String dec = byDecision.keySet().iterator().next();
            double avgConf = attempts.stream().mapToDouble(AnalysisAttempt::confidence).average().orElse(0);
            return new Consensus(dec, "UNANIMOUS",
                    String.format("%d 個 AI 一致 %s (avg conf=%.2f)", attempts.size(), dec, avgConf));
        }

        // MAJORITY or SPLIT
        String topDecision = byDecision.entrySet().stream()
                .max(Comparator.comparingInt(e -> e.getValue().size()))
                .map(Map.Entry::getKey).orElse("DISMISS");
        int topCount = byDecision.get(topDecision).size();
        int totalCount = attempts.size();

        if (topCount > totalCount / 2) {
            double avgConf = byDecision.get(topDecision).stream()
                    .mapToDouble(AnalysisAttempt::confidence).average().orElse(0);
            return new Consensus(topDecision, "MAJORITY",
                    String.format("%d/%d 多數決 %s (avg conf=%.2f)", topCount, totalCount, topDecision, avgConf));
        }

        // 平手 SPLIT — 取信心度最高者
        AnalysisAttempt highest = attempts.stream()
                .max(Comparator.comparingDouble(AnalysisAttempt::confidence))
                .orElse(attempts.get(0));
        return new Consensus(highest.decision(), "SPLIT",
                String.format("AI 意見分歧 — %s 信心度最高 (%.2f) 暫採: %s",
                        highest.providerName(), highest.confidence(), highest.reasoning()));
    }

    private void sendConsensusAlert(MarketFlipEvent event, List<AnalysisAttempt> attempts, Consensus consensus) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 <b>Market Flip 共識分析</b> — <code>%s</code>%n", event.getSymbol()));
        sb.append(String.format("指標: %s  %s → %s%n",
                event.getIndicator(),
                event.getPrevValue().toPlainString(),
                event.getCurrentValue().toPlainString()));
        sb.append(String.format("決策: <b>%s</b> (%s)%n%n", consensus.finalDecision(), consensus.consensusType()));
        for (AnalysisAttempt a : attempts) {
            sb.append(String.format("<b>%s</b> → %s (conf=%.2f)%n%s%n%n",
                    a.providerName(), a.decision(), a.confidence(), a.reasoning()));
        }
        log.info("[FlipConsensus] event={} ALERT: {}", event.getId(), consensus.summary());
        try {
            notificationPort.broadcast(sb.toString(), true);
        } catch (Exception e) {
            log.warn("[FlipConsensus] TG send failed: {}", e.getMessage());
        }
    }

    // ─── StatisticalFlipAnalyzer ──────────────────────────────────────────────

    /**
     * Rule-based flip significance analyzer — replaces Groq + Gemini consensus.
     *
     * <p>Design principle: the flip detector already filters by threshold; any event that
     * reaches this analyzer has crossed a meaningful level. Rules classify severity:
     * <ul>
     *   <li>Extreme values (F&amp;G ≤ 20 or ≥ 80, whale ≤ 25% or ≥ 70%) → ALERT</li>
     *   <li>Rapid change (delta > 15 for F&amp;G, > 20pp for whale) → ALERT</li>
     *   <li>Entering fear/sell zone (F&amp;G &lt; 35 declining, whale &lt; 40% declining) → ALERT</li>
     *   <li>Normal fluctuation within mid-range → DISMISS</li>
     * </ul>
     */
    private static final class StatisticalFlipAnalyzer {

        static AnalysisAttempt analyze(MarketFlipEvent event) {
            long t0 = System.currentTimeMillis();
            String indicator = event.getIndicator() != null ? event.getIndicator() : "";
            double current = event.getCurrentValue() != null ? event.getCurrentValue().doubleValue() : 0;
            double prev    = event.getPrevValue()    != null ? event.getPrevValue().doubleValue()    : 0;
            double delta   = current - prev;

            String decision;
            String reasoning;

            if (indicator.contains("fear_greed") || indicator.contains("fg")) {
                if (current <= 20) {
                    decision  = "ALERT";
                    reasoning = String.format("F&G 達極端恐懼 %.0f（前 %.0f，Δ%+.0f）— 市場恐慌，潛在底部或進一步下殺", current, prev, delta);
                } else if (current >= 80) {
                    decision  = "ALERT";
                    reasoning = String.format("F&G 達極端貪婪 %.0f（前 %.0f，Δ%+.0f）— 過熱風險，注意反轉", current, prev, delta);
                } else if (delta <= -15) {
                    decision  = "ALERT";
                    reasoning = String.format("F&G 急降 %.0f→%.0f（Δ%+.0f）— 情緒快速惡化", prev, current, delta);
                } else if (delta >= 15) {
                    decision  = "ALERT";
                    reasoning = String.format("F&G 急升 %.0f→%.0f（Δ%+.0f）— 情緒快速好轉，關注過熱", prev, current, delta);
                } else if (delta < 0 && current < 35) {
                    decision  = "ALERT";
                    reasoning = String.format("F&G 降入恐懼區 %.0f（前 %.0f）— 持續弱勢", current, prev);
                } else {
                    decision  = "DISMISS";
                    reasoning = String.format("F&G 變化 %.0f→%.0f 屬正常波動，門檻已跨但幅度溫和", prev, current);
                }
            } else if (indicator.contains("whale")) {
                double curPct = current * 100, prevPct = prev * 100, deltaPct = delta * 100;
                if (curPct <= 25) {
                    decision  = "ALERT";
                    reasoning = String.format("鯨魚買入比 %.0f%%（前 %.0f%%，Δ%+.0f%%）— 大戶大量拋售", curPct, prevPct, deltaPct);
                } else if (curPct >= 70) {
                    decision  = "ALERT";
                    reasoning = String.format("鯨魚買入比 %.0f%%（前 %.0f%%，Δ%+.0f%%）— 大戶強力進場", curPct, prevPct, deltaPct);
                } else if (Math.abs(deltaPct) >= 20) {
                    decision  = "ALERT";
                    reasoning = String.format("鯨魚買入比急變 %.0f%%→%.0f%%（Δ%+.0f%%）", prevPct, curPct, deltaPct);
                } else if (deltaPct < 0 && curPct < 40) {
                    decision  = "ALERT";
                    reasoning = String.format("鯨魚買入比降入偏空區 %.0f%%（前 %.0f%%）", curPct, prevPct);
                } else {
                    decision  = "DISMISS";
                    reasoning = String.format("鯨魚買入比 %.0f%%→%.0f%% 屬正常波動", prevPct, curPct);
                }
            } else {
                // Unknown indicator — default to ALERT (fail-safe: better to over-notify)
                decision  = "ALERT";
                reasoning = String.format("未知指標 %s: %.4f → %.4f（門檻 %s）", indicator, prev, current,
                        event.getThresholdCrossed() != null ? event.getThresholdCrossed() : "?");
            }

            int latency = (int)(System.currentTimeMillis() - t0);
            return new AnalysisAttempt("statistical-rules", true, decision, 1.0, reasoning, 0, latency);
        }
    }

    // ─── Internal records ────────────────────────────────────────────────────
    private record AnalysisAttempt(String providerName, boolean success,
                                    String decision, double confidence, String reasoning,
                                    int tokensUsed, int latencyMs) {
        static AnalysisAttempt failed(String name) {
            return new AnalysisAttempt(name, false, null, 0, null, 0, 0);
        }
    }
    private record ParsedAnalysis(String decision, double confidence, String reasoning) {}
    private record Consensus(String finalDecision, String consensusType, String summary) {}
}
