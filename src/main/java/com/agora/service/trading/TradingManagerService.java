package com.agora.service.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.SignalOutcomeVerificationRepository;
import com.agora.service.ai.GeminiApiClient;
import com.agora.service.ai.GroqApiClient;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import com.agora.service.backtest.MarketSignalCache;
import com.agora.service.backtest.StrategySignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 交易經理服務。
 * 彙整當前持倉與週報資料，透過 Gemini 生成繁體中文分析報告。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingManagerService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtGridRepository btGridRepository;
    private final BtGridLevelRepository btGridLevelRepository;
    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final GeminiApiClient geminiApiClient;
    private final GroqApiClient groqApiClient;
    private final MarketSignalCache marketSignalCache;
    private final AiTaskRouter aiTaskRouter;
    private final SignalOutcomeVerificationRepository signalVerificationRepository;

    // ── 當前倉位報告 ──────────────────────────────────────

    /**
     * 生成當前倉位狀況報告（Telegram HTML 格式）。
     * 會即時查詢 OKX 現貨持倉（含 USDT 及其他幣種）、現價（浮動損益）與 OCO 狀態。
     */
    public String reportCurrentSituation() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<BtLiveSignal> openPositions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();

        List<OkxTradingService.SpotHolding> holdings;
        try {
            holdings = okxTradingService.getSpotHoldings();
        } catch (Exception e) {
            log.warn("[TradingManager] getSpotHoldings failed: {}", e.getMessage());
            holdings = Collections.emptyList();
        }

        // 拆出 USDT 可用餘額（格式化至 2 位小數）
        String usdtBalance = "N/A";
        List<OkxTradingService.SpotHolding> nonUsdt = new ArrayList<>();
        for (OkxTradingService.SpotHolding h : holdings) {
            if ("USDT".equals(h.ccy)) {
                usdtBalance = String.format("%.2f", h.availBal.doubleValue());
            } else {
                nonUsdt.add(h);
            }
        }

        // 活存餘額（失敗不影響主流程）
        List<OkxEarnService.EarnBalance> earnBalances;
        try {
            earnBalances = okxEarnService.getBalance(null);
        } catch (Exception e) {
            log.warn("[TradingManager] getEarnBalance failed: {}", e.getMessage());
            earnBalances = Collections.emptyList();
        }

        // 資金帳戶餘額（Funding Account, /api/v5/asset/balances）— Issue #155 修補
        // 過去只查交易帳戶 + 賺幣帳戶，漏算資金帳戶導致總資產低估
        List<OkxTradingService.SpotHolding> fundingHoldings;
        try {
            fundingHoldings = okxTradingService.getFundingHoldings();
        } catch (Exception e) {
            log.warn("[TradingManager] getFundingHoldings failed: {}", e.getMessage());
            fundingHoldings = Collections.emptyList();
        }

        // 活躍 Grid（DB 查詢，不需 API）
        List<BtGrid> activeGrids;
        try {
            activeGrids = btGridRepository.findByEnabledTrueAndClosedAtIsNull();
        } catch (Exception e) {
            log.warn("[TradingManager] fetchActiveGrids failed: {}", e.getMessage());
            activeGrids = Collections.emptyList();
        }

        List<PositionView> views = buildPositionViews(openPositions, now);
        String positionTable = buildPositionTable(views);
        String holdingsSection = buildHoldingsSection(nonUsdt, activeGrids);
        String earnSection = buildEarnSection(earnBalances);
        String fundingSection = buildFundingSection(fundingHoldings);
        // AI comment 移除：/report 已在第二條訊息提供完整市場分析，此處重複呼叫 Gemini 浪費 token
        return formatCurrentReport(openPositions.size(), usdtBalance, earnSection, fundingSection,
                holdingsSection, positionTable, null, now, activeGrids);
    }

    /** 建立 OKX Funding Account 段落（Issue #155）；若無餘額回傳 null。 */
    private String buildFundingSection(List<OkxTradingService.SpotHolding> fundingHoldings) {
        if (fundingHoldings == null || fundingHoldings.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (OkxTradingService.SpotHolding h : fundingHoldings) {
            if (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.valueOf(0.01)) >= 0) {
                // 格式化到 2 位小數，避免超長小數
                double eqUsd = h.eqUsd.doubleValue();
                String balStr = eqUsd < 1 ? String.format("%.4f", h.cashBal.doubleValue())
                        : String.format("%.2f", h.cashBal.doubleValue());
                sb.append(String.format("%s: %s (≈$%.2f)\n", h.ccy, balStr, eqUsd));
            }
        }
        return sb.toString().trim();
    }

    /** 為每個 DB 持倉即時向 OKX 查詢現價與 OCO 狀態，組成 PositionView 列表。 */
    private List<PositionView> buildPositionViews(List<BtLiveSignal> positions, LocalDateTime now) {
        List<PositionView> views = new ArrayList<>();
        for (BtLiveSignal pos : positions) {
            PositionView v = new PositionView();
            v.pos = pos;
            v.entryRef = pos.getActualEntryPrice() != null
                    ? pos.getActualEntryPrice() : pos.getEntryPrice();
            v.hoursHeld = ChronoUnit.HOURS.between(pos.getCreatedAt(), now);

            // 即時查現價
            v.lastPrice = okxTradingService.getLastPrice(pos.getSymbol());

            // 即時確認 OCO 狀態（有 OCO 才查）
            if (pos.getOcoOrderListId() != null) {
                try {
                    JsonNode algo = okxTradingService.getAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId());
                    String state = algo.path("state").asText("live");
                    if ("filled".equals(state)) {
                        v.ocoAlreadyFilled = true;
                    } else if ("canceled".equals(state)) {
                        // canceled 但 avgPx != "0" 表示有實際成交（某腿觸發後另一腿被取消）
                        String avgPx = algo.path("avgPx").asText("0");
                        v.ocoAlreadyFilled = !"0".equals(avgPx) && !avgPx.isEmpty();
                    }
                } catch (Exception e) {
                    log.warn("[TradingManager] getAlgoOrder failed for id={}: {}", pos.getId(), e.getMessage());
                }
            }

            // 即時市場信號（cache 無資料時為 null，不影響流程）
            v.signal = marketSignalCache.get(pos.getSymbol(), pos.getIntervalCode());

            views.add(v);
        }
        return views;
    }

    private String buildPositionTable(List<PositionView> views) {
        if (views.isEmpty()) return "（目前無持倉）";

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < views.size(); i++) {
            PositionView v = views.get(i);
            String heldStr = v.hoursHeld >= 24
                    ? String.format("%d天%d小時", v.hoursHeld / 24, v.hoursHeld % 24)
                    : v.hoursHeld + "小時";

            // 浮動損益
            String pnlStr = "";
            double currentPnl = 0;
            if (v.lastPrice != null && v.entryRef != null
                    && v.entryRef.compareTo(BigDecimal.ZERO) != 0) {
                currentPnl = v.lastPrice.subtract(v.entryRef)
                        .divide(v.entryRef, 6, RoundingMode.HALF_UP)
                        .doubleValue() * 100;
                pnlStr = String.format(" | 浮動 %+.2f%%", currentPnl);
            }

            // OCO 已觸發但 DB 尚未同步
            String syncWarning = v.ocoAlreadyFilled ? "\n   🚨 OCO 已成交但 DB 未更新！用 forceClosePosition 修復" : "";

            sb.append(String.format("%d. %s [%s] 入場 $%s | 持倉 %s%s%s",
                    i + 1,
                    v.pos.getSymbol(),
                    v.pos.getIntervalCode(),
                    formatPrice(v.entryRef),
                    heldStr,
                    pnlStr,
                    syncWarning));

            // TP / SL 行（有掛單才顯示）
            BigDecimal tp = v.pos.getSuggestedTp();
            BigDecimal sl = v.pos.getSuggestedSl();
            if (tp != null || sl != null) {
                sb.append(String.format("\n   🎯 TP: %s  🛡 SL: %s",
                        tp != null ? "$" + formatPrice(tp) : "N/A",
                        sl != null ? "$" + formatPrice(sl) : "N/A"));
            }

            // 市場信號行（cache 有資料才顯示）
            if (v.signal != null) {
                String sigEmoji = v.signal.signal == StrategySignal.BUY ? "🟢"
                        : v.signal.signal == StrategySignal.SELL ? "🔴" : "🟡";
                String riskFlag = (v.signal.signal == StrategySignal.SELL && currentPnl < 0) ? "⚠️ " : "";
                // nnOutput/rsi 為 0.0 表示評估時未計算（HOLD 快速路徑），顯示 N/A 避免誤導
                String nnStr  = v.signal.nnOutput > 0.0 ? String.format("%.2f", v.signal.nnOutput) : "N/A";
                String rsiStr = v.signal.rsi > 0.0     ? String.format("%.1f", v.signal.rsi)       : "N/A";
                // OCO 保護狀態
                String ocoStr = v.pos.getOcoOrderListId() != null ? "🛡有OCO" : "❌無OCO";
                sb.append(String.format("\n   %s%s 信號: %s  NN: %s  RSI: %s  %s",
                        riskFlag, sigEmoji,
                        v.signal.signal.name(), nnStr, rsiStr, ocoStr));
            } else {
                // cache 無資料時仍顯示 OCO 狀態
                String ocoStr = v.pos.getOcoOrderListId() != null ? "🛡有OCO" : "❌無OCO";
                sb.append(String.format("\n   🟡 信號: 待更新  %s", ocoStr));
            }

            if (i < views.size() - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String fetchAiComment(int count, String usdtBalance, String holdingsSection,
                                   String positionTable, List<PositionView> views) {
        StringBuilder promptSb = new StringBuilder("以下是目前的帳戶狀況：\n\n");
        promptSb.append("可用 USDT: ").append(usdtBalance).append("\n");
        if (holdingsSection != null && !holdingsSection.isEmpty()) {
            promptSb.append("現貨持倉:\n").append(holdingsSection).append("\n");
        }
        promptSb.append("\n開倉中 (").append(count).append("筆，含市場信號):\n").append(positionTable).append("\n\n");

        // 市場情緒補充（從 signal cache 取最新值，AI 評估需要市況 context）
        if (!views.isEmpty() && views.get(0).signal != null) {
            MarketSignalCache.EvalSnapshot snap = views.get(0).signal;
            promptSb.append("市場情緒：F&G=").append(snap.fearGreedValue)
                    .append("，鯨魚買入比=").append(String.format("%.0f%%", snap.whaleBuyRatio * 100))
                    .append("\n");
        }

        // 倉位補充：OCO 保護狀態 + TP 距離
        for (PositionView v : views) {
            boolean hasOco = v.pos.getOcoOrderListId() != null;
            promptSb.append("倉位 ").append(v.pos.getSymbol()).append("：");
            promptSb.append(hasOco ? "已有OCO保護" : "⚠️無OCO保護（需手動掛單）");
            if (v.lastPrice != null && v.pos.getSuggestedTp() != null && v.entryRef != null
                    && v.entryRef.compareTo(BigDecimal.ZERO) > 0) {
                double tpDistPct = v.pos.getSuggestedTp().subtract(v.lastPrice)
                        .divide(v.lastPrice, 4, RoundingMode.HALF_UP).doubleValue() * 100;
                promptSb.append(String.format("，距TP %.1f%%", tpDistPct));
            }
            promptSb.append("\n");
        }

        promptSb.append("\n請以交易經理的角色，根據信號狀態、OCO保護情況和市場情緒評估是否需要手動介入（80字以內）。" +
                "若有⚠️無OCO必須指出；若倉位正常且有保護，說明整體狀況正常即可。");

        // maxTokens 800:預留 thinking ~200 + visible ~600
        return callAiWithFallback(
                "你是一位專業加密貨幣交易經理，負責管理倉位並提供簡潔分析。" +
                "使用繁體中文回答，語氣專業簡潔，適合 Telegram 閱讀，勿使用 Markdown。",
                promptSb.toString(), 800);
    }

    /** 建立非 USDT 現貨持倉段落，並附上對應的 Grid 持倉明細。 */
    private String buildHoldingsSection(List<OkxTradingService.SpotHolding> nonUsdt,
                                        List<BtGrid> activeGrids) {
        // 過濾塵埃持倉：用 cashBal 判斷（OCO 鎖倉後 eqUsd 可能為 0，但 cashBal 仍有值）
        // eqUsd > 0 優先，否則用 cashBal > 0.001（約 $0.08 for BTC at $76k）兜底
        List<OkxTradingService.SpotHolding> meaningful = nonUsdt.stream()
                .filter(h -> (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.valueOf(0.1)) >= 0)
                          || (h.cashBal != null && h.cashBal.compareTo(BigDecimal.valueOf(0.0001)) >= 0))
                .toList();
        if (meaningful.isEmpty()) return null;
        nonUsdt = meaningful;
        // 依 symbol 分組 grid
        Map<String, List<BtGrid>> gridsBySymbol = activeGrids.stream()
                .collect(Collectors.groupingBy(BtGrid::getSymbol));

        StringBuilder sb = new StringBuilder();
        for (OkxTradingService.SpotHolding h : nonUsdt) {
            double usdVal = (h.eqUsd != null && h.eqUsd.compareTo(BigDecimal.ZERO) > 0)
                    ? h.eqUsd.doubleValue()
                    : (h.cashBal != null ? h.cashBal.doubleValue() * 76000 : 0); // fallback 估算
            sb.append(String.format("%s: %s (≈$%.2f)",
                    h.ccy, h.cashBal.toPlainString(), usdVal));

            // 同幣種的 Grid 持倉明細（只找 HOLDING / SELL_FAILED / SELL_PARTIAL 的格）
            String symbol = h.ccy + "USDT";
            List<BtGrid> grids = gridsBySymbol.getOrDefault(symbol, Collections.emptyList());
            for (BtGrid g : grids) {
                List<BtGridLevel> holdingLevels = btGridLevelRepository
                        .findByGridIdAndStatusIn(g.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
                if (holdingLevels.isEmpty()) continue;
                String gridStatus = g.getPausedAt() != null ? " ⏸" : "";
                sb.append(String.format("\n Grid#%d [%s~%s]%s 持倉%d格:",
                        g.getId(),
                        formatPrice(g.getPriceLower()),
                        formatPrice(g.getPriceUpper()),
                        gridStatus,
                        holdingLevels.size()));
                holdingLevels.sort(Comparator.comparing(BtGridLevel::getLevelIndex));
                for (BtGridLevel lv : holdingLevels) {
                    String sellFlag = "SELL_FAILED".equals(lv.getStatus()) ? "⚠️" : "";
                    // 計算每格預期利潤（扣掉手續費後約 = (sell-buy) × qty × (1 - 0.001×2)）
                    String profitStr = "";
                    if (lv.getFilledQty() != null && lv.getFilledPrice() != null
                            && lv.getPairedSellPrice() != null) {
                        BigDecimal grossProfit = lv.getPairedSellPrice()
                                .subtract(lv.getFilledPrice())
                                .multiply(lv.getFilledQty());
                        BigDecimal netProfit = grossProfit.multiply(BigDecimal.valueOf(0.998));
                        profitStr = String.format(" (+$%.3f)", netProfit.doubleValue());
                    }
                    sb.append(String.format("\n  L%d%s: 買@%s→賣@%s | %s %s%s",
                            lv.getLevelIndex(),
                            sellFlag,
                            formatPrice(lv.getFilledPrice()),
                            formatPrice(lv.getPairedSellPrice()),
                            lv.getFilledQty() != null ? lv.getFilledQty().toPlainString() : "?",
                            h.ccy,
                            profitStr));
                }
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /** 建立 OKX Simple Earn 活存段落；若無餘額回傳 null。 */
    private String buildEarnSection(List<OkxEarnService.EarnBalance> earnBalances) {
        List<OkxEarnService.EarnBalance> nonEmpty = earnBalances.stream()
                .filter(b -> b.amt().compareTo(BigDecimal.ZERO) > 0)
                .collect(Collectors.toList());
        if (nonEmpty.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (OkxEarnService.EarnBalance b : nonEmpty) {
            // OKX balance.rate 是日利率（百分比形式，如 0.01 = 0.01%/day）
            // 年化 APY = rate × 365（注意：不需再 ×100，rate 已是 % 形式）
            BigDecimal apyPct = b.rate().multiply(BigDecimal.valueOf(365))
                    .setScale(2, RoundingMode.HALF_UP);
            String earningsStr = b.earnings().compareTo(BigDecimal.ZERO) > 0
                    ? String.format(" (+%.4f 利息)", b.earnings().doubleValue())
                    : "";
            sb.append(String.format("%s: %s%s  APY≈%s%%",
                    b.ccy(),
                    b.amt().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                    earningsStr,
                    apyPct.toPlainString()));
        }
        return sb.toString();
    }

    private String formatCurrentReport(int count, String usdtBalance, String earnSection,
                                       String fundingSection, String holdingsSection,
                                       String table, String aiComment, LocalDateTime now,
                                       List<BtGrid> activeGrids) {
        StringBuilder sb = new StringBuilder();
        sb.append("🤖 <b>交易經理｜當前倉位報告</b>\n");
        sb.append("🕐 ").append(now.format(FMT)).append(" (UTC)\n\n");

        sb.append("<b>💰 可用 USDT：</b>").append(usdtBalance).append("\n");

        if (earnSection != null && !earnSection.isEmpty()) {
            sb.append("<b>💵 活存：</b>\n");
            sb.append("<code>").append(escapeHtml(earnSection)).append("</code>\n");
        }

        if (fundingSection != null && !fundingSection.isEmpty()) {
            sb.append("<b>🏦 資金帳戶：</b>\n");
            sb.append("<code>").append(escapeHtml(fundingSection)).append("</code>\n");
        }

        if (holdingsSection != null && !holdingsSection.isEmpty()) {
            sb.append("<b>📊 現貨持倉：</b>\n");
            sb.append("<code>").append(escapeHtml(holdingsSection)).append("</code>\n");
        }

        // 活躍網格獨立區塊（含 PENDING，不依賴 HOLDING 格）
        String gridSection = buildGridStatusSection(activeGrids);
        if (!gridSection.isEmpty()) {
            sb.append("<b>📈 活躍網格 (").append(activeGrids.size()).append(")</b>\n");
            sb.append("<code>").append(escapeHtml(gridSection)).append("</code>\n");
        }

        sb.append("<b>📦 開倉數量：</b>").append(count).append(" 筆\n\n");

        if (count > 0) {
            sb.append("<b>倉位明細：</b>\n");
            sb.append("<code>").append(escapeHtml(table)).append("</code>\n");
        } else {
            sb.append("目前無開倉，系統待機中。\n");
        }

        if (aiComment != null && !aiComment.trim().isEmpty()) {
            sb.append("\n💬 <b>經理評估：</b>\n").append(escapeHtml(aiComment.trim()));
        }

        return sb.toString();
    }

    /** 所有活躍網格的狀態摘要（含 PENDING，不依賴有無 HOLDING 格）。 */
    private String buildGridStatusSection(List<BtGrid> grids) {
        if (grids.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (BtGrid g : grids) {
            long holding = btGridLevelRepository.countByGridIdAndStatus(g.getId(), "HOLDING");
            long sellFailed = btGridLevelRepository.countByGridIdAndStatus(g.getId(), "SELL_FAILED");
            double pnl = g.getTotalRealizedPnl() != null ? g.getTotalRealizedPnl().doubleValue() : 0.0;
            String status = g.getPausedAt() != null ? "⏸暫停(regime不符，5分鐘內自動恢復)" : "▶️運行";
            String rebalanceTag = Boolean.TRUE.equals(g.getAutoRebalance()) ? " 🔄自動換範圍" : "";
            sb.append(String.format("#%d %s %.0f~%.0f | %s | PnL %+.2f USDT%s\n",
                    g.getId(), g.getSymbol(),
                    g.getPriceLower().doubleValue(), g.getPriceUpper().doubleValue(),
                    status, pnl, rebalanceTag));
            if (holding > 0 || sellFailed > 0) {
                sb.append(String.format("  持倉%d格", holding));
                if (sellFailed > 0) sb.append(String.format("  ⚠️賣出失敗%d格", sellFailed));
                sb.append("\n");
            }
            if (Boolean.TRUE.equals(g.getAutoRebalance()) && g.getRebalanceCount() != null && g.getRebalanceCount() > 0) {
                sb.append(String.format("  已自動換範圍%d次（上限%d）\n", g.getRebalanceCount(), g.getMaxRebalanceCount()));
            }
        }
        return sb.toString().trim();
    }

    /** 近 7 天信號正確率摘要（空=無資料）。 */
    private String buildSignalAccuracySection() {
        try {
            java.time.LocalDateTime since = java.time.LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
            java.util.List<Object[]> rows = signalVerificationRepository.accuracyByLayerSinceDedup(since);
            if (rows == null || rows.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (Object[] r : rows) {
                String layer = (String) r[0];
                String decision = (String) r[1];
                long correct = ((Number) r[2]).longValue();
                long wrong   = ((Number) r[3]).longValue();
                long watching = ((Number) r[4]).longValue();
                long total = correct + wrong;
                if (total == 0) continue;
                double pct = (double) correct / total * 100;
                String icon = total >= 5 && pct < 40 ? "⚠️" : "✅";
                sb.append(String.format("%s %s[%s] 看對%d/看錯%d(%.0f%%) 觀察中%d\n",
                        icon, layer, decision, correct, wrong, pct, watching));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.debug("[TradingManager] signalAccuracy fetch failed: {}", e.getMessage());
            return "";
        }
    }

    // ── 週報 ──────────────────────────────────────────────

    /**
     * 生成過去 7 天交易週報（Telegram HTML 格式）。
     */
    public String reportWeekly() {
        return reportWeekly(true);
    }

    /**
     * 生成過去 7 天交易週報（Telegram HTML 格式）。
     *
     * @param includeAiComment true 時附加 AI 週評；MCP/巡檢路徑可傳 false，避免 AI provider
     *                         503/429 或長尾延遲阻塞核心收益數字。
     */
    public String reportWeekly(boolean includeAiComment) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime weekStart = now.minusDays(7);

        List<BtLiveSignal> closed =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(weekStart);

        if (closed.isEmpty()) {
            return "📊 <b>交易經理｜本週報告</b>\n\n" +
                   "過去 7 天尚無已完成的自動交易記錄。";
        }

        WeeklyStats stats = calcWeeklyStats(closed);
        String tradeDetail = buildTradeDetail(closed);
        String aiComment = includeAiComment ? fetchWeeklyAiComment(stats, tradeDetail) : null;

        return formatWeeklyReport(stats, tradeDetail, aiComment, weekStart, now);
    }

    private WeeklyStats calcWeeklyStats(List<BtLiveSignal> trades) {
        int total = trades.size();
        int wins = 0;
        int slCount = 0;
        double totalPnl = 0;
        BtLiveSignal best = null, worst = null;
        double bestPnl = Double.NEGATIVE_INFINITY, worstPnl = Double.POSITIVE_INFINITY;

        for (BtLiveSignal t : trades) {
            double pnl = calcPnlPct(t);
            totalPnl += pnl;
            if ("TP".equals(t.getExitReason())) wins++;
            if ("SL".equals(t.getExitReason())) slCount++;
            if (pnl > bestPnl)  { bestPnl  = pnl;  best  = t; }
            if (pnl < worstPnl) { worstPnl = pnl;  worst = t; }
        }

        WeeklyStats s = new WeeklyStats();
        s.total = total;
        s.wins = wins;
        s.losses = slCount;
        s.avgPnl = total > 0 ? totalPnl / total : 0;
        s.best = best;
        s.bestPnl = bestPnl;
        s.worst = worst;
        s.worstPnl = worstPnl;
        return s;
    }

    private String buildTradeDetail(List<BtLiveSignal> trades) {
        List<BtLiveSignal> sorted = new ArrayList<>(trades);
        sorted.sort(Comparator.comparing(BtLiveSignal::getExitTime).reversed());

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(sorted.size(), 10);
        for (int i = 0; i < limit; i++) {
            BtLiveSignal t = sorted.get(i);
            double pnl = calcPnlPct(t);
            BigDecimal entryRef = t.getActualEntryPrice() != null
                    ? t.getActualEntryPrice() : t.getEntryPrice();
            sb.append(String.format("%d. %s [%s] $%s→$%s | %+.2f%% %s",
                    i + 1,
                    t.getSymbol(), t.getIntervalCode(),
                    formatPrice(entryRef), formatPrice(t.getExitPrice()),
                    pnl,
                    exitReasonEmoji(t.getExitReason())));
            if (i < limit - 1) sb.append("\n");
        }
        return sb.toString();
    }

    private String fetchWeeklyAiComment(WeeklyStats stats, String tradeDetail) {
        if (!geminiApiClient.isEnabled()) return null;

        double winRate = stats.total > 0 ? (double) stats.wins / stats.total * 100 : 0;
        StringBuilder promptSb = new StringBuilder();
        promptSb.append("以下是本週（過去7天）自動交易成績：\n\n");
        promptSb.append(String.format("總交易 %d筆 | 止盈(TP): %d筆 | 止損(SL): %d筆 | 勝率 %.0f%%\n",
                stats.total, stats.wins, stats.losses, winRate));
        promptSb.append(String.format("平均損益: %+.2f%%\n", stats.avgPnl));
        if (stats.best != null) {
            promptSb.append(String.format("最佳交易: %s %+.2f%%\n", stats.best.getSymbol(), stats.bestPnl));
        }
        if (stats.worst != null) {
            promptSb.append(String.format("最差交易: %s %+.2f%%\n", stats.worst.getSymbol(), stats.worstPnl));
        }
        promptSb.append("\n交易明細（最近10筆）：\n").append(tradeDetail).append("\n\n");
        promptSb.append("請以交易經理的角色，提供本週績效摘要評估（80字以內），包括策略表現及建議。");

        // maxTokens 1500:週報內容多,thinking ~200 + visible ~1300 充裕
        return callAiWithFallback(
                "你是一位專業加密貨幣交易經理，負責分析本週交易績效。" +
                "使用繁體中文，語氣專業簡潔，適合 Telegram 閱讀，勿使用 Markdown。",
                promptSb.toString(), 1500);
    }

    /** AI 呼叫：透過 AiTaskRouter（含 budget guard + fallback）取代手動 Gemini→Groq 切換。 */
    private String callAiWithFallback(String systemContent, String userContent, int maxTokens) {
        try {
            return aiTaskRouter.execute(
                    new AiTask.WithSystem("trading-manager-query", systemContent, userContent, maxTokens)
            ).text();
        } catch (Exception e) {
            log.warn("[TradingManager] AI call failed: {}", e.getMessage());
            return null;
        }
    }

    private String formatWeeklyReport(WeeklyStats stats, String tradeDetail,
                                      String aiComment, LocalDateTime from, LocalDateTime to) {
        double winRate = stats.total > 0 ? (double) stats.wins / stats.total * 100 : 0;
        String winEmoji = winRate >= 60 ? "🟢" : winRate >= 40 ? "🟡" : "🔴";

        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>交易經理｜週報告</b>\n");
        sb.append(String.format("📅 %s ~ %s (UTC)\n\n", from.format(FMT), to.format(FMT)));

        sb.append(String.format("<b>總交易：</b>%d 筆\n", stats.total));
        sb.append(String.format("<b>止盈(TP)：</b>%d 筆 | <b>止損(SL)：</b>%d 筆\n", stats.wins, stats.losses));
        sb.append(String.format("<b>勝率：</b>%s %.0f%%\n", winEmoji, winRate));
        sb.append(String.format("<b>平均損益：</b>%+.2f%%\n", stats.avgPnl));

        if (stats.best != null) {
            sb.append(String.format("<b>最佳：</b>%s %+.2f%%\n", stats.best.getSymbol(), stats.bestPnl));
        }
        if (stats.worst != null) {
            sb.append(String.format("<b>最差：</b>%s %+.2f%%\n", stats.worst.getSymbol(), stats.worstPnl));
        }

        sb.append("\n<b>交易明細：</b>\n");
        sb.append("<code>").append(escapeHtml(tradeDetail)).append("</code>\n");

        if (aiComment != null && !aiComment.trim().isEmpty()) {
            sb.append("\n💬 <b>經理評估：</b>\n").append(escapeHtml(aiComment.trim()));
        }

        return sb.toString();
    }

    // ── 工具方法 ──────────────────────────────────────────

    private List<Map<String, String>> buildMessages(String systemContent, String userContent) {
        Map<String, String> systemMsg = new LinkedHashMap<>();
        systemMsg.put("role", "system");
        systemMsg.put("content", systemContent);

        Map<String, String> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", userContent);

        return Arrays.asList(systemMsg, userMsg);
    }

    private double calcPnlPct(BtLiveSignal t) {
        BigDecimal entry = t.getActualEntryPrice() != null
                ? t.getActualEntryPrice() : t.getEntryPrice();
        if (entry == null || t.getExitPrice() == null || entry.compareTo(BigDecimal.ZERO) == 0)
            return 0.0;
        double pct = t.getExitPrice().subtract(entry)
                .divide(entry, 6, RoundingMode.HALF_UP)
                .doubleValue() * 100;
        // SHORT 做空：價格下跌才是獲利，需反向
        return "SHORT".equals(t.getSide()) ? -pct : pct;
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        if (price.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%,.2f", price.doubleValue());
        }
        return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    private String exitReasonEmoji(String reason) {
        if ("TP".equals(reason)) return "🎯";
        if ("SL".equals(reason)) return "🛡";
        return "📤";
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ── 內部資料類 ────────────────────────────────────────

    private static class PositionView {
        BtLiveSignal pos;
        BigDecimal entryRef;
        BigDecimal lastPrice;
        long hoursHeld;
        boolean ocoAlreadyFilled;
        MarketSignalCache.EvalSnapshot signal;
    }

    private static class WeeklyStats {
        int total, wins, losses;
        double avgPnl, bestPnl, worstPnl;
        BtLiveSignal best, worst;
    }
}
