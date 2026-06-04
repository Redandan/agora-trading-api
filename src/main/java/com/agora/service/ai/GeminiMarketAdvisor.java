package com.agora.service.ai;

import com.agora.config.properties.GeminiAdvisorProperties;
import com.agora.model.GeminiMarketHint;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.agora.service.market.DeterministicRegimeClassifier;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Gemini Market Advisor — 用 Single-Model Multi-Persona 為每個 (symbol, timeframe)
 * 產生市場形態 hint,strategy 動態調參用。
 *
 * <p><b>設計</b>:同一個 Gemini 模型,3 個 persona 用不同 system prompt(趨勢者 / 反向者 /
 * 風控官),majority vote 決定最終 style_hint,若三方分歧則 confidence=0.3 + DISABLE。
 *
 * <p><b>Phase 1 (current)</b>:mode=shadow,寫 hint 進 DB 但 strategy 尚未讀。
 * 用於累積資料 + 驗證 Gemini 一致性(同 prompt 同輸出)。
 *
 * <p><b>Phase 3 (future)</b>:Strategy.evaluate() 開頭讀 hint,動態調 adxThreshold/SL/TP。
 *
 * <p>Quota 估算:3 persona × 4 對 (BTC/ETH × 1h/4h) × 6 次/天 = 72 calls/day,
 * 遠低於 Gemini Flash free tier 1500/day。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeminiMarketAdvisor {

    private final GeminiApiClient geminiApiClient;
    private final AiStrategyDiscoveryService discoveryService;
    private final GeminiMarketHintRepository hintRepository;
    private final NotificationPort notificationPort;
    private final AiTaskRouter aiTaskRouter;
    private final GeminiAdvisorProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** style+regime 快取，用於偵測「無變化時靜默」。key = symbol+timeframe */
    private final Map<String, String> lastSentHintKey = new java.util.concurrent.ConcurrentHashMap<>();

    /** 00:05, 08:05, 16:05 UTC(避開 0:00 / :10 既有排程)。 */
    @Scheduled(cron = "${trading.gemini-advisor.cron:0 5 */8 * * *}", zone = "UTC")
    public void runOnSchedule() {
        log.info("[GeminiAdvisor scheduled] tick enabled={} cron={} symbols={} timeframes={}",
                props.enabled(), props.cron(), props.symbols(), props.timeframes());
        if (!props.enabled()) {
            log.info("[GeminiAdvisor scheduled] disabled, skipping scheduled run");
            return;
        }
        runForAll("scheduled");
    }

    /** 給 MCP / admin 手動觸發。 */
    public String runManual() {
        return runForAll("manual");
    }

    /** 對單一 (symbol, timeframe) 執行,用於精準 debug。 */
    public String runManualSingle(String symbol, String timeframe) {
        try {
            HintResult r = generateHint(symbol.toUpperCase(), timeframe.toLowerCase());
            return formatHintForLog(r);
        } catch (Exception e) {
            log.error("[GeminiAdvisor] manual single failed: {}", e.getMessage(), e);
            return "❌ 失敗: " + e.getMessage();
        }
    }

    private String runForAll(String trigger) {
        List<String> symbols = Arrays.stream(props.symbols().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        List<String> timeframes = Arrays.stream(props.timeframes().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        List<HintResult> results = new ArrayList<>();
        int failures = 0;
        int total = symbols.size() * timeframes.size();
        int index = 0;
        for (String s : symbols) {
            for (String tf : timeframes) {
                try {
                    results.add(generateHint(s.toUpperCase(), tf.toLowerCase()));
                } catch (Exception e) {
                    log.warn("[GeminiAdvisor] Failed {} {}: {}", s, tf, e.getMessage());
                    failures++;
                }
                index++;
                if (index < total) {
                    pauseBetweenAdvisorGroups();
                }
            }
        }

        String summary = String.format(
                "[GeminiAdvisor %s] generated %d hints, %d failures (symbols=%s tfs=%s)",
                trigger, results.size(), failures, symbols, timeframes);
        log.info(summary);

        if (props.tgSummary() && !results.isEmpty()) {
            try {
                String tgMsg = buildTgSummary(trigger, results, failures);
                if (tgMsg != null) {
                    notificationPort.broadcast(tgMsg, true);
                }
            } catch (Exception e) {
                log.warn("[GeminiAdvisor] TG summary failed: {}", e.getMessage());
            }
        }
        return summary;
    }

    private void pauseBetweenAdvisorGroups() {
        long gapMs = props.requestGapMs();
        if (gapMs <= 0) return;
        try {
            Thread.sleep(gapMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GeminiAdvisor interrupted during provider rate-limit pacing", e);
        }
    }

    /** 產生單一 hint(三 persona 並行 + 投票 + 寫入)。 */
    private HintResult generateHint(String symbol, String timeframe) {
        // #336 Phase 1: stuck-skip — 若最近 N 筆 hint 全同 style+regime 且 conf≥門檻，
        //               跳過 Gemini call 重用最新 hint（延長 expiresAt），降 token 消耗。
        if (props.skipStuckEnabled()) {
            HintResult skipped = trySkipStuck(symbol, timeframe);
            if (skipped != null) return skipped;
        }
        AiStrategyDiscoveryService.MarketSnapshot snap =
                discoveryService.buildMarketSnapshot(symbol, timeframe);
        String baseCtx = snap.toPromptText();

        // #336 Phase 3 — 加 prior hint differential context，避免「stuck conservative」回音
        String priorDiff = props.priorHintContextEnabled() ? buildPriorHintContext(symbol, timeframe) : null;
        final String marketCtx = priorDiff != null ? (baseCtx + "\n\n" + priorDiff) : baseCtx;

        // Provider free tiers are short-window rate limited; pace the three persona calls
        // so a scheduled 4-market sweep does not fail halfway through with 429s.
        PersonaVote trend = askPersona("trend", marketCtx, PERSONA_TREND);
        pauseBetweenPersonaCalls();
        PersonaVote contra = askPersona("contrarian", marketCtx, PERSONA_CONTRARIAN);
        pauseBetweenPersonaCalls();
        PersonaVote risk = askPersona("risk", marketCtx, PERSONA_RISK);

        List<PersonaVote> votes = List.of(trend, contra, risk);
        long valid = votes.stream().filter(v -> v.parsed).count();
        if (valid < 2) {
            throw new IllegalStateException("有效 persona vote < 2 (got " + valid + ")");
        }

        // 投票決定 final hint
        Map<String, Long> styleCount = votes.stream()
                .filter(v -> v.parsed)
                .collect(Collectors.groupingBy(v -> v.styleHint, Collectors.counting()));
        String finalStyle = styleCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse("DISABLE");
        long maxVotes = styleCount.getOrDefault(finalStyle, 1L);
        double confidence = (double) maxVotes / votes.size();
        // 三方分歧(每人不同 style)→ 取保守 DISABLE,confidence 低
        if (maxVotes == 1L) {
            finalStyle = "DISABLE";
            confidence = 0.30;
        }

        // 數值欄位取 median(去極端值)
        double adxAdjust = median(votes, v -> v.adxAdjust, 0.0);
        double slMult   = median(votes, v -> v.slMultiplier, 1.0);
        double tpMult   = median(votes, v -> v.tpMultiplier, 1.0);
        boolean allowShort = votes.stream().filter(v -> v.parsed)
                .filter(v -> v.allowShort).count() >= 2;  // 至少 2 個同意才允許做空
        String regime = pickMostCommon(votes, v -> v.regime, "UNKNOWN");

        // clamp 防 Gemini 給離譜值
        adxAdjust = clamp(adxAdjust, -5.0, 5.0);
        slMult   = clamp(slMult,    0.5, 2.0);
        tpMult   = clamp(tpMult,    0.5, 2.0);

        // 組 reasoning
        String reasoning = votes.stream().filter(v -> v.parsed)
                .map(v -> "[" + v.persona + "] " + v.reasoning)
                .collect(Collectors.joining(" | "));

        // ── Deterministic baseline — run alongside Gemini for A/B tracking ──
        DeterministicRegimeClassifier.Result det = null;
        try {
            String ctxTf = "1h".equals(timeframe) ? "4h" : "1h";
            AiStrategyDiscoveryService.MarketSnapshot ctxSnap =
                    discoveryService.buildMarketSnapshot(symbol, ctxTf);
            det = DeterministicRegimeClassifier.classify(snap, ctxSnap);
            boolean regimeAgree = det.regime().equals(regime);
            boolean styleAgree  = det.styleHint().equals(finalStyle);
            log.info("[GeminiAdvisor] deterministic {} {} regime={} style={} agree_regime={} agree_style={}",
                    symbol, timeframe, det.regime(), det.styleHint(), regimeAgree, styleAgree);
        } catch (Exception e) {
            log.debug("[GeminiAdvisor] deterministic classifier failed: {}", e.getMessage());
        }

        // 組 personaVotes JSON (含 deterministic 結果供 A/B 分析)
        String votesJson;
        try {
            Map<String, Object> vm = new LinkedHashMap<>();
            for (PersonaVote v : votes) {
                if (v.parsed) {
                    vm.put(v.persona, Map.of(
                            "style", v.styleHint, "regime", v.regime,
                            "adxAdjust", v.adxAdjust, "allowShort", v.allowShort));
                } else {
                    vm.put(v.persona, Map.of("error", v.error != null ? v.error : "parse failed"));
                }
            }
            if (det != null) {
                vm.put("deterministic", Map.of(
                        "style", det.styleHint(), "regime", det.regime(),
                        "adxAdjust", det.adxAdjust(), "allowShort", det.allowShort(),
                        "confidence", det.confidence()));
            }
            votesJson = objectMapper.writeValueAsString(vm);
        } catch (Exception e) {
            votesJson = "{\"error\":\"" + e.getMessage() + "\"}";
        }

        // 寫入 DB
        GeminiMarketHint hint = new GeminiMarketHint();
        hint.setSymbol(symbol);
        hint.setTimeframe(timeframe);
        hint.setRegime(regime);
        hint.setStyleHint(finalStyle);
        hint.setAdxAdjust(BigDecimal.valueOf(adxAdjust).setScale(2, RoundingMode.HALF_UP));
        hint.setSlMultiplier(BigDecimal.valueOf(slMult).setScale(3, RoundingMode.HALF_UP));
        hint.setTpMultiplier(BigDecimal.valueOf(tpMult).setScale(3, RoundingMode.HALF_UP));
        hint.setAllowShort(allowShort);
        hint.setConfidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP));
        hint.setPersonaVotes(votesJson);
        hint.setReasoning(reasoning.length() > 800 ? reasoning.substring(0, 800) : reasoning);
        LocalDateTime now = LocalDateTime.now();
        hint.setCreatedAt(now);
        hint.setExpiresAt(now.plusHours(props.hintTtlHours()));

        hintRepository.save(hint);

        log.info("[GeminiAdvisor] {} {} → style={} regime={} conf={} adx={} slx={} tpx={} short={}",
                symbol, timeframe, finalStyle, regime, hint.getConfidence(),
                hint.getAdxAdjust(), hint.getSlMultiplier(), hint.getTpMultiplier(), allowShort);

        return new HintResult(symbol, timeframe, hint, votes);
    }

    /** 對單一 persona 發 prompt 並解析 JSON 回應。透過 AiTaskRouter 路由(含 fallback + token 計費)。 */
    private PersonaVote askPersona(String persona, String marketCtx, String personaSystem) {
        try {
            // 8192 tokens: Gemini 2.5 Flash thinking ~5k + visible output ~3k
            AiTask.MarketAdvisorPersona task = new AiTask.MarketAdvisorPersona(
                    persona, personaSystem, marketCtx, RESPONSE_FORMAT, 8192);
            String reply = aiTaskRouter.execute(task).text();
            if (reply == null || reply.isBlank()) {
                log.warn("[GeminiAdvisor] persona={} empty/null reply", persona);
                return PersonaVote.fail(persona, "empty reply");
            }
            String escapedReply = reply.replace("\n", "\\n").replace("\r", "");
            log.info("[GeminiAdvisor] persona={} reply_len={} reply={}",
                    persona, reply.length(),
                    escapedReply.length() > 800 ? escapedReply.substring(0, 800) + "..." : escapedReply);
            PersonaVote v = parseVote(persona, reply);
            if (!v.parsed) {
                log.warn("[GeminiAdvisor] persona={} parse failed: {}", persona, v.error);
            }
            return v;
        } catch (Exception e) {
            log.warn("[GeminiAdvisor] persona={} chat exception: {}", persona, e.getMessage(), e);
            return PersonaVote.fail(persona, e.getMessage());
        }
    }

    private void pauseBetweenPersonaCalls() {
        long gapMs = props.personaGapMs();
        if (gapMs <= 0) return;
        try {
            Thread.sleep(gapMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GeminiAdvisor interrupted during persona pacing", e);
        }
    }

    /** 解析 Gemini JSON 回應。容忍 markdown code fence 與額外文字。 */
    private PersonaVote parseVote(String persona, String reply) {
        String json = extractJson(reply);
        if (json == null) {
            return PersonaVote.fail(persona, "no JSON found in: " + truncate(reply, 100));
        }
        try {
            JsonNode n = objectMapper.readTree(json);
            PersonaVote v = new PersonaVote();
            v.persona     = persona;
            v.parsed      = true;
            v.regime      = upper(n.path("regime").asText("UNKNOWN"));
            v.styleHint   = upper(n.path("style_hint").asText("DISABLE"));
            v.adxAdjust   = n.path("adx_adjust").asDouble(0.0);
            v.slMultiplier = n.path("sl_multiplier").asDouble(1.0);
            v.tpMultiplier = n.path("tp_multiplier").asDouble(1.0);
            v.allowShort  = n.path("allow_short").asBoolean(false);
            v.reasoning   = truncate(n.path("reasoning").asText(""), 200);
            // sanity:style 必須在白名單
            if (!STYLE_WHITELIST.contains(v.styleHint)) {
                v.styleHint = "DISABLE";
            }
            return v;
        } catch (Exception e) {
            return PersonaVote.fail(persona, "JSON parse: " + e.getMessage());
        }
    }

    /** 從可能含 markdown fence 的回應抽出 JSON 物件。 */
    private static String extractJson(String s) {
        int start = s.indexOf('{');
        int end   = s.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return s.substring(start, end + 1);
    }

    private static String upper(String s) { return s == null ? "" : s.trim().toUpperCase(); }
    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static double median(List<PersonaVote> votes, java.util.function.ToDoubleFunction<PersonaVote> f, double dflt) {
        List<Double> vals = votes.stream().filter(v -> v.parsed).map(f::applyAsDouble).sorted().toList();
        if (vals.isEmpty()) return dflt;
        int mid = vals.size() / 2;
        return vals.size() % 2 == 0 ? (vals.get(mid - 1) + vals.get(mid)) / 2.0 : vals.get(mid);
    }

    private static <T> String pickMostCommon(List<PersonaVote> votes, java.util.function.Function<PersonaVote, String> f, String dflt) {
        return votes.stream().filter(v -> v.parsed).map(f)
                .collect(Collectors.groupingBy(x -> x, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(dflt);
    }

    private String formatHintForLog(HintResult r) {
        return String.format("✅ %s %s style=%s regime=%s conf=%.2f adx=%+.1f slx=%.2f tpx=%.2f short=%s%n  %s",
                r.symbol, r.timeframe, r.hint.getStyleHint(), r.hint.getRegime(),
                r.hint.getConfidence().doubleValue(),
                r.hint.getAdxAdjust().doubleValue(),
                r.hint.getSlMultiplier().doubleValue(),
                r.hint.getTpMultiplier().doubleValue(),
                r.hint.getAllowShort(),
                r.hint.getReasoning());
    }

    /**
     * 建立 TG 摘要。scheduled trigger 時若所有 hint 均未改變（style+regime 相同），回傳 null（靜默）。
     * manual trigger 永遠發送。
     */
    private String buildTgSummary(String trigger, List<HintResult> results, int failures) {
        boolean isScheduled = "scheduled".equals(trigger);

        // 偵測是否有任何 hint 改變
        boolean anyChanged = results.stream().anyMatch(r -> {
            String key = r.symbol() + "|" + r.timeframe();
            String newVal = r.hint().getStyleHint() + "|" + r.hint().getRegime();
            return !newVal.equals(lastSentHintKey.get(key));
        });

        if (isScheduled && !anyChanged && failures == 0) {
            log.info("[GeminiAdvisor] No regime/style change detected, suppressing scheduled TG");
            return null;
        }

        // 更新快取（只在有內容要發時）
        results.forEach(r -> {
            String key = r.symbol() + "|" + r.timeframe();
            String val = r.hint().getStyleHint() + "|" + r.hint().getRegime();
            lastSentHintKey.put(key, val);
        });

        StringBuilder sb = new StringBuilder();
        sb.append("🤖 <b>Gemini Market Advisor</b> (").append(trigger).append(")\n\n");
        sb.append("等級：SHADOW_INFO（僅觀察，不影響實際策略）\n\n");
        for (HintResult r : results) {
            // 標記有變化的幣種
            String key = r.symbol() + "|" + r.timeframe();
            String prev = lastSentHintKey.get(key); // 已更新，比原來多一個 "changed" marker 機制
            sb.append(String.format("<b>%s %s</b> → %s (conf %.2f)%n",
                    r.symbol(), r.timeframe(), r.hint().getStyleHint(), r.hint().getConfidence().doubleValue()));
            sb.append(String.format("  %s adx%+.1f slx%.2f tpx%.2f short=%s%n",
                    regimeCn(r.hint().getRegime()),
                    r.hint().getAdxAdjust().doubleValue(),
                    r.hint().getSlMultiplier().doubleValue(),
                    r.hint().getTpMultiplier().doubleValue(),
                    r.hint().getAllowShort() ? "ON" : "OFF"));
        }
        if (failures > 0) sb.append("\n⚠️ ").append(failures).append(" 個 hint 失敗\n");
        sb.append("\n<i>mode=SHADOW</i>");
        return sb.toString();
    }

    private static String regimeCn(String regime) {
        if (regime == null) return "未知";
        return switch (regime.toUpperCase()) {
            case "TRENDING_UP"   -> "上升趨勢↑";
            case "TRENDING_DOWN" -> "下降趨勢↓";
            case "SIDEWAYS"      -> "橫盤整理";
            case "VOLATILE"      -> "高波動⚡";
            case "RECOVERY"      -> "復甦📈";
            default              -> regime;
        };
    }

    // ─── Persona system prompts ────────────────────────────────────────────

    private static final String PERSONA_TREND =
            "你是趨勢跟蹤交易者(persona: trend)。信奉「順勢而為」,只在強趨勢進場。\n" +
            "重點看:ADX > 25(強趨勢)/ EMA20 與價格關係 / MACD 直方圖一致性。\n" +
            "震盪市(ADX < 20)應建議 DISABLE 或 CONSERVATIVE。\n" +
            "對下方市場狀態,輸出你的策略建議(JSON 格式)。";

    private static final String PERSONA_CONTRARIAN =
            "你是均值回歸交易者(persona: contrarian)。信奉「物極必反」,在超買超賣時反向進場。\n" +
            "重點看:RSI < 30(LONG)或 > 70(SHORT)/ 布林帶觸碰 / 距離 SMA200 偏離。\n" +
            "強趨勢期(ADX > 30)風險高,應建議 CONSERVATIVE 或 DISABLE。\n" +
            "對下方市場狀態,輸出你的策略建議(JSON 格式)。";

    private static final String PERSONA_RISK =
            "你是風控經理(persona: risk)。信奉「保本第一」,不確定時建議減倉/觀望。\n" +
            "重點看:ATR%(高 = 危險)/ 市場形態混亂 / 極端值。\n" +
            "傾向 CONSERVATIVE / DISABLE 多於 TREND / HIGH_FREQ。\n" +
            "對下方市場狀態,輸出你的策略建議(JSON 格式)。";

    private static final String RESPONSE_FORMAT =
            "請輸出單一 JSON 物件(不要 markdown code fence),含以下欄位:\n" +
            "{\n" +
            "  \"regime\": \"TRENDING_UP\" | \"TRENDING_DOWN\" | \"SIDEWAYS\" | \"VOLATILE\" | \"RECOVERY\",\n" +
            "  \"style_hint\": \"TREND\" | \"HIGH_FREQ\" | \"CONSERVATIVE\" | \"DISABLE\",\n" +
            "  \"adx_adjust\": -5.0 ~ +5.0(現有 ADX 門檻調整,負=放寬,正=收緊),\n" +
            "  \"sl_multiplier\": 0.5 ~ 2.0(SL 乘數,< 1 收緊,> 1 放寬),\n" +
            "  \"tp_multiplier\": 0.5 ~ 2.0(TP 乘數),\n" +
            "  \"allow_short\": true | false,\n" +
            "  \"reasoning\": \"≤ 80 字解釋\"\n" +
            "}\n" +
            "嚴格遵守 JSON 格式,只輸出物件本身。";

    private static final Set<String> STYLE_WHITELIST =
            Set.of("TREND", "HIGH_FREQ", "CONSERVATIVE", "DISABLE");

    // ─── 內部資料類 ─────────────────────────────────────────────────────────

    private static class PersonaVote {
        String persona;
        boolean parsed;
        String regime;
        String styleHint;
        double adxAdjust;
        double slMultiplier;
        double tpMultiplier;
        boolean allowShort;
        String reasoning;
        String error;
        static PersonaVote fail(String persona, String error) {
            PersonaVote v = new PersonaVote();
            v.persona = persona; v.parsed = false; v.error = error;
            v.styleHint = "DISABLE"; v.regime = "UNKNOWN";
            v.slMultiplier = 1.0; v.tpMultiplier = 1.0; v.adxAdjust = 0.0;
            return v;
        }
    }

    private record HintResult(String symbol, String timeframe, GeminiMarketHint hint, List<PersonaVote> votes) {}

    /**
     * #336 Phase 1 — 若最近 N 筆 hint 全部相同高信心，跳過此次 Gemini call。
     * 只延長 expiresAt 不寫新 row（避免 hint 表暴量；getRecentHints 仍見 stuck pattern → staleness detector 接手）。
     */
    private HintResult trySkipStuck(String symbol, String timeframe) {
        try {
            int needed = Math.max(2, props.skipStuckMinHints());
            List<GeminiMarketHint> recent = hintRepository
                    .findActiveHints(symbol, timeframe, LocalDateTime.now(),
                            org.springframework.data.domain.PageRequest.of(0, needed));
            if (recent.size() < needed) return null;
            GeminiMarketHint head = recent.get(0);
            String style = head.getStyleHint();
            String regime = head.getRegime();
            for (GeminiMarketHint h : recent) {
                if (!eq(style, h.getStyleHint()) || !eq(regime, h.getRegime())) return null;
                if (h.getConfidence() == null
                        || h.getConfidence().doubleValue() < props.skipStuckConfMin()) return null;
            }
            // 全 stuck — 延長最新 hint TTL，不再呼叫 Gemini
            head.setExpiresAt(LocalDateTime.now().plusHours(props.hintTtlHours()));
            hintRepository.save(head);
            log.info("[GeminiAdvisor] SKIP-STUCK {} {} style={} regime={} (last {} hints all same conf≥{}) — token saved",
                    symbol, timeframe, style, regime, recent.size(), props.skipStuckConfMin());
            return new HintResult(symbol, timeframe, head, List.of());
        } catch (Exception e) {
            log.debug("[GeminiAdvisor] stuck-skip check failed, fall through: {}", e.getMessage());
            return null;
        }
    }

    private static boolean eq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }

    /**
     * #336 Phase 3 — 取最近一筆 hint 組成 differential prompt 段。
     * 推 Gemini 顯式判斷「市場是否從上次 hint 起變化」，避免持續回 stuck conservative 卻沒理由。
     */
    private String buildPriorHintContext(String symbol, String timeframe) {
        try {
            List<GeminiMarketHint> recent = hintRepository
                    .findActiveHints(symbol, timeframe, LocalDateTime.now(),
                            org.springframework.data.domain.PageRequest.of(0, 1));
            if (recent.isEmpty()) return null;
            GeminiMarketHint h = recent.get(0);
            long hoursAgo = h.getCreatedAt() != null
                    ? java.time.Duration.between(h.getCreatedAt(), LocalDateTime.now()).toHours()
                    : -1;
            return String.format(
                    "[Prior hint context — %d hour(s) ago]\nstyle=%s regime=%s conf=%.2f\n" +
                    "→ Has the market state meaningfully shifted since? " +
                    "If unchanged, briefly justify; if changed, identify the specific shift " +
                    "(price, volume, funding, etc.). Avoid echoing prior hint without evidence.",
                    hoursAgo,
                    h.getStyleHint(),
                    h.getRegime(),
                    h.getConfidence() != null ? h.getConfidence().doubleValue() : 0.0);
        } catch (Exception e) {
            log.debug("[GeminiAdvisor] prior hint context build failed: {}", e.getMessage());
            return null;
        }
    }
}
