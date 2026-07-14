package com.agora.service.backtest;

import com.agora.model.BtGrid;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.ai.AiStrategyDiscoveryService.MarketSnapshot;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /analysis — 市場現況簡報（獨立於 enabled 策略）。
 *
 * <p>輸出結構化指標表 + AI 綜合判讀。資料來源：
 * <ul>
 *   <li>{@link AiStrategyDiscoveryService#buildMarketSnapshot} — ADX/RSI/ATR/volume/trend</li>
 *   <li>{@link FearGreedService} / {@link WhaleFlowService} — 情緒與主力動向</li>
 *   <li>{@link AiTaskRouter} — 走 answer-user-query 路由（Gemini flash → Groq fallback）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingAnalysisService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final String[] SYMBOLS = {"BTCUSDT", "ETHUSDT"};
    private static final String[] INTERVALS = {"1h", "4h"};
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L;  // 15 分鐘快取

    private volatile String cachedReport = null;
    private volatile long cacheTimestamp = 0;

    private final AiStrategyDiscoveryService discoveryService;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final BtStrategyRepository strategyRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtGridRepository gridRepository;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepository;
    private final GeminiMarketHintRepository geminiHintRepository;
    private final AiTaskRouter aiTaskRouter;

    public String analyze() {
        // 15 分鐘快取：避免短時間重複呼叫浪費 AI token
        long now = System.currentTimeMillis();
        if (cachedReport != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            log.debug("[TradingAnalysis] returning cached report (age={}s)", (now - cacheTimestamp) / 1000);
            return cachedReport;
        }

        Map<String, Map<String, MarketSnapshot>> snapshots = collectSnapshots();
        int fg = safeFearGreed();
        Map<String, Double> whale = collectWhale();
        int enabledCount = countEnabledStrategies();
        Map<String, String> regimes = collectRegimes();
        String basisStr = fetchIndicator("BTCUSDT", "btc_basis_pct");
        String pcRatioStr = fetchIndicator("BTCUSDT", "btc_put_call_ratio");

        String aiContext = buildAiContext(snapshots, fg, whale, enabledCount, regimes, basisStr, pcRatioStr);
        String commentary = fetchCommentary(aiContext);

        String result = formatReport(snapshots, fg, whale, enabledCount, commentary, regimes, basisStr, pcRatioStr);
        cachedReport = result;
        cacheTimestamp = System.currentTimeMillis();
        return result;
    }

    // ─── 資料收集 ──────────────────────────────────────────

    private Map<String, Map<String, MarketSnapshot>> collectSnapshots() {
        Map<String, Map<String, MarketSnapshot>> all = new LinkedHashMap<>();
        for (String symbol : SYMBOLS) {
            Map<String, MarketSnapshot> perSym = new LinkedHashMap<>();
            for (String interval : INTERVALS) {
                try {
                    perSym.put(interval, discoveryService.buildMarketSnapshot(symbol, interval));
                } catch (Exception e) {
                    log.warn("[TradingAnalysis] snapshot failed {} {}: {}", symbol, interval, e.getMessage());
                }
            }
            all.put(symbol, perSym);
        }
        return all;
    }

    private int safeFearGreed() {
        try { return fearGreedService.getFearGreedValue(); }
        catch (Exception e) { log.warn("[TradingAnalysis] F&G failed: {}", e.getMessage()); return 50; }
    }

    private Map<String, Double> collectWhale() {
        Map<String, Double> m = new LinkedHashMap<>();
        for (String symbol : SYMBOLS) {
            try { m.put(symbol, whaleFlowService.getBuyRatio(symbol)); }
            catch (Exception e) {
                log.warn("[TradingAnalysis] whale failed {}: {}", symbol, e.getMessage());
                m.put(symbol, 0.5);
            }
        }
        return m;
    }

    /** 每個幣種最新 Gemini 4h regime。 */
    private Map<String, String> collectRegimes() {
        Map<String, String> m = new LinkedHashMap<>();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(6);
        for (String symbol : SYMBOLS) {
            try {
                var hints = geminiHintRepository
                        .findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(symbol, "4h", since);
                m.put(symbol, hints.isEmpty() ? null : hints.get(0).getRegime());
            } catch (Exception e) { m.put(symbol, null); }
        }
        return m;
    }

    /** 最新 market_indicator_history 值，格式化成顯示字串。 */
    private String fetchIndicator(String symbol, String indicator) {
        try {
            // #384 — filter error_flag=1 outliers in AI market analysis context
            return indicatorHistoryRepository
                    .findTopCleanBySymbolAndIndicator(symbol, indicator)
                    .map(h -> {
                        double v = h.getValue().doubleValue();
                        if (indicator.contains("pct")) return String.format("%+.3f%%", v);
                        if (indicator.contains("ratio")) return String.format("%.2f", v);
                        return String.format("%.4f", v);
                    }).orElse(null);
        } catch (Exception e) { return null; }
    }

    /** 當前持倉 + 網格狀態摘要，注入 AI 提示詞。 */
    private String buildPortfolioContext() {
        StringBuilder sb = new StringBuilder();
        try {
            List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
            if (!open.isEmpty()) {
                sb.append("當前開倉（SL/TP 已設定，OCO 已掛，不要重複建議調整至相同數值）：\n");
                for (BtLiveSignal p : open) {
                    boolean hasOco = p.getOcoOrderListId() != null;
                    sb.append(String.format("  • %s [%s] 入場$%s 現行SL=$%s 現行TP=$%s %s\n",
                            p.getSymbol(), p.getIntervalCode(),
                            p.getEntryPrice() != null ? p.getEntryPrice().toPlainString() : "?",
                            p.getSuggestedSl() != null ? p.getSuggestedSl().toPlainString() : "?",
                            p.getSuggestedTp() != null ? p.getSuggestedTp().toPlainString() : "?",
                            hasOco ? "OCO已掛勿重複建議"
                                    : BtcBasePositionStatePolicy.isIntentionalNoOco(p)
                                    ? "BTC_BASE管理模式故意無OCO，不得補掛或自動賣出"
                                    : "⚠️無OCO需補掛"));
                }
            }
            List<BtGrid> grids = gridRepository.findByEnabledTrueAndClosedAtIsNull();
            if (!grids.isEmpty()) {
                sb.append("活躍網格（注意：⏸暫停=regime不符，5分鐘內自動恢復，不需人工介入；autoRebalance 網格不要建議縮小）：\n");
                for (BtGrid g : grids) {
                    String rebalanceNote = Boolean.TRUE.equals(g.getAutoRebalance())
                            ? String.format(" 🔄已啟用autoRebalance(%.1f%%觸發)", g.getRebalanceTriggerPct()*100)
                            : "";
                    sb.append(String.format("  • Grid#%d %s %.0f~%.0f PnL%+.2f%s\n",
                            g.getId(), g.getSymbol(),
                            g.getPriceLower().doubleValue(), g.getPriceUpper().doubleValue(),
                            g.getTotalRealizedPnl() != null ? g.getTotalRealizedPnl().doubleValue() : 0.0,
                            rebalanceNote));
                }
            }
        } catch (Exception e) { log.debug("[TradingAnalysis] portfolio context failed: {}", e.getMessage()); }
        return sb.toString();
    }

    private int countEnabledStrategies() {
        try {
            List<BtStrategy> enabled = strategyRepository.findByEnabled(true);
            return enabled == null ? 0 : enabled.size();
        } catch (Exception e) {
            log.warn("[TradingAnalysis] count enabled failed: {}", e.getMessage());
            return 0;
        }
    }

    /** 近期停用策略簡短 context（幫助 AI 解釋為何沒啟用）。 */
    private String recentDisabledSummary() {
        try {
            List<BtStrategy> all = strategyRepository.findByEnabled(false);
            if (all == null || all.isEmpty()) return "無歷史停用策略";
            return String.format("共 %d 個停用策略（最近回測全數未達 quality gate：勝率 <40%% 或報酬為負）", all.size());
        } catch (Exception e) {
            return "停用策略查詢失敗";
        }
    }

    // ─── AI commentary ────────────────────────────────────

    private String buildAiContext(Map<String, Map<String, MarketSnapshot>> snapshots,
                                   int fg, Map<String, Double> whale, int enabledCount,
                                   Map<String, String> regimes, String basis, String pcRatio) {
        StringBuilder sb = new StringBuilder("市場快照（只用此資訊回答）：\n\n");
        sb.append(String.format("F&G=%d(%s)  Basis=%s  P/C=%s  策略=%d個%n",
                fg, fearGreedLabel(fg),
                basis != null ? basis : "N/A",
                pcRatio != null ? pcRatio : "N/A",
                enabledCount));
        sb.append("\n");
        for (String symbol : SYMBOLS) {
            sb.append("### ").append(symbol).append("\n");
            Double w = whale.get(symbol);
            String regime = regimes.get(symbol);
            sb.append(String.format("鯨魚=%.0f%%  Gemini4h=%s%n",
                    w == null ? 50.0 : w * 100,
                    regime != null ? regime : "N/A"));
            for (String interval : INTERVALS) {
                MarketSnapshot s = snapshots.get(symbol).get(interval);
                if (s != null) {
                    // 精簡格式：只保留 AI 真正需要的關鍵指標，省 ~60% input token
                    String rsiLabel = s.rsi14() < 30 ? "超賣" : s.rsi14() > 70 ? "超買" : String.format("%.0f", s.rsi14());
                    String adxLabel = s.adx14() < 20 ? "弱" : s.adx14() > 35 ? "強" : "中";
                    String macdDir = s.macdHistogram() > 0 ? "多" : "空";
                    sb.append(String.format("[%s] RSI=%s ADX=%s(%s) MACD=%s trend=%s vol=%.1fx%n",
                            interval.toUpperCase(),
                            rsiLabel, String.format("%.0f", s.adx14()), adxLabel,
                            macdDir, s.trendDirection(),
                            s.volumeRatio()));
                }
            }
        }
        // 持倉/網格 context（讓 AI 給針對性建議）
        String portfolio = buildPortfolioContext();
        if (!portfolio.isBlank()) {
            sb.append("\n持倉現況：\n").append(portfolio);
        }
        return sb.toString();
    }

    private String fetchCommentary(String context) {
        try {
            String prompt =
                "繁體中文，約 150 字，三段：\n" +
                "【形態】1h vs 4h 對比，一句結論\n" +
                "【風險】最關鍵矛盾或風險，一句\n" +
                "【建議】針對持倉各寫一條（「-」開頭）\n" +
                "嚴格規則：\n" +
                "1. 若倉位已有 OCO，不建議『調整至相同數值』的止損止盈\n" +
                "2. 若 Grid 已啟用 autoRebalance，不建議暫停或縮小\n" +
                "3. 只說需要改變的事，現狀正常就說『維持現狀』\n" +
                "4. 不重複數字，不用 Markdown，語氣簡潔";
            AiTask task = new AiTask.AnswerUserQuery(prompt, List.of(context), 700);
            return aiTaskRouter.execute(task).text();
        } catch (Exception e) {
            log.warn("[TradingAnalysis] AI commentary failed: {}", e.getMessage());
            return null;
        }
    }

    // ─── 格式化輸出 ──────────────────────────────────────

    private String formatReport(Map<String, Map<String, MarketSnapshot>> snapshots,
                                 int fg, Map<String, Double> whale, int enabledCount,
                                 String commentary, Map<String, String> regimes,
                                 String basis, String pcRatio) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>市場現況簡報</b>  ");
        sb.append(now.format(FMT)).append(" UTC\n\n");

        // 頂部一行：F&G + Basis + P/C + 策略數
        sb.append(String.format("<b>💭 F&amp;G:</b> %d %s %s", fg, fearGreedLabel(fg), fearGreedEmoji(fg)));
        if (basis != null) sb.append(String.format("  <b>Basis:</b> %s", escapeHtml(basis)));
        if (pcRatio != null) sb.append(String.format("  <b>P/C:</b> %s", pcRatio));
        sb.append("\n");
        sb.append(String.format("<b>⚙️ 策略:</b> %d 個%s\n\n",
                enabledCount, enabledCount == 0 ? "（待機）" : "（觀察中）"));

        // 各 symbol 指標表 + regime
        for (String symbol : SYMBOLS) {
            Map<String, MarketSnapshot> perSym = snapshots.get(symbol);
            if (perSym == null || perSym.isEmpty()) continue;

            Double w = whale.get(symbol);
            String regime = regimes != null ? regimes.get(symbol) : null;
            sb.append("<b>📊 ").append(symbol).append("</b>");
            if (w != null) sb.append(String.format("  🐋 %.0f%%", w * 100));
            if (regime != null) sb.append("  ").append(regimeCn(regime));
            sb.append("\n");
            sb.append("<code>").append(buildIndicatorTable(perSym)).append("</code>\n\n");
        }

        // AI 判讀
        if (commentary != null && !commentary.isBlank()) {
            sb.append("<b>💡 AI 分析</b>\n");
            sb.append(escapeHtml(commentary.trim()));
        } else {
            sb.append("<i>⚠️ AI 分析暫時無法取得</i>");
        }
        return sb.toString();
    }

    private static String regimeCn(String regime) {
        if (regime == null) return "";
        return switch (regime.toUpperCase()) {
            case "TRENDING_UP"   -> "上升趨勢↑";
            case "TRENDING_DOWN" -> "下降趨勢↓";
            case "SIDEWAYS"      -> "橫盤整理";
            case "VOLATILE"      -> "高波動⚡";
            case "RECOVERY"      -> "復甦📈";
            default              -> regime;
        };
    }

    private String buildIndicatorTable(Map<String, MarketSnapshot> perSym) {
        StringBuilder sb = new StringBuilder();
        sb.append("週期  ADX     RSI   ATR%   量    趨勢\n");
        List<String> orderedIntervals = new ArrayList<>(perSym.keySet());
        for (String iv : orderedIntervals) {
            MarketSnapshot s = perSym.get(iv);
            if (s == null) continue;
            sb.append(String.format("%-4s  %5.1f%s  %4.1f%s %4.1f%%  %3.1fx  %s%n",
                    iv.toUpperCase(),
                    s.adx14(), adxSuffix(s.adx14()),
                    s.rsi14(), rsiSuffix(s.rsi14()),
                    s.atrPct(),
                    s.volumeRatio(),
                    trendLabel(s.trendDirection())));
        }
        return sb.toString();
    }

    // ─── 格式化輔助 ─────────────────────────────────────

    private String adxSuffix(double adx) {
        if (adx >= 35) return "🔥";
        if (adx >= 25) return "📈";
        if (adx >= 15) return "·";
        return "💤";
    }

    private String rsiSuffix(double rsi) {
        if (rsi < 30) return "🟢";
        if (rsi < 45) return "·";
        if (rsi < 55) return " ";
        if (rsi < 70) return "·";
        return "🔴";
    }

    private String trendLabel(String dir) {
        return switch (dir == null ? "UNKNOWN" : dir) {
            case "BULLISH" -> "📈偏多";
            case "BEARISH" -> "📉偏空";
            case "SIDEWAYS" -> "➡️盤整";
            default -> "❓未知";
        };
    }

    private String fearGreedLabel(int value) {
        if (value <= 24) return "極度恐慌";
        if (value <= 49) return "恐慌";
        if (value <= 74) return "貪婪";
        return "極度貪婪";
    }

    private String fearGreedEmoji(int value) {
        if (value <= 24) return "📉";
        if (value <= 49) return "😨";
        if (value <= 74) return "😊";
        return "🚀";
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
