package com.agora.mcp;

import com.agora.config.properties.TradingGridProperties;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.trading.CapitalAllocationPolicyPreviewService;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.TradeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP Grid 交易工具集。
 * 提供網格交易策略的建立、查詢、暫停/恢復/關閉、詳細統計,以及結合倉位/餘額的綜合風險儀表板。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridMcpTools {

    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final MdKlineRepository klineRepository;
    private final OkxTradingService okxTradingService;
    private final TradingGridProperties gridProperties;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "DEPRECATED custom Grid create path. New Grid creation is disabled by default while migration " +
            "moves execution to OKX native Spot Grid. Existing custom grids remain queryable/closable for retirement. " +
            "When explicitly re-enabled, N 條價格線等距鋪在 [priceLower, priceUpper] 區間,建立 N-1 個 buy level," +
            "上界只作 paired sell boundary。每格 perLevelUsdt 金額。價格觸某 level → market buy,之後漲到 pairedSellPrice(filled + step)→ market sell。" +
            "stopOutPct 預設 0.03(3%,區間外 3% 觸發全平);hintGated=true(預設)受 Gemini advisor regime 白名單控管。" +
            "param: symbol, priceLower, priceUpper, gridCount(2-50), perLevelUsdt(≥5)," +
            "stopOutPct(選填,預設 0.03), regimeWhitelist(預設 'SIDEWAYS,VOLATILE,RECOVERY')")
    @Deprecated(forRemoval = true)
    public String createGrid(String symbol, BigDecimal priceLower, BigDecimal priceUpper,
                              Integer gridCount, BigDecimal perLevelUsdt,
                              BigDecimal stopOutPct, String regimeWhitelist) {
        if (!gridProperties.customCreateResumeEnabled()) {
            return "BLOCKED_DEPRECATED_CUSTOM_GRID_CREATE_USE_OKX_NATIVE; "
                    + "closeGrid remains available for exact legacy holding retirement";
        }
        if (symbol == null || priceLower == null || priceUpper == null || gridCount == null || perLevelUsdt == null) {
            return "❌ symbol/priceLower/priceUpper/gridCount/perLevelUsdt 皆必填";
        }
        if (gridCount < 2 || gridCount > 50) return "❌ gridCount 必須在 2-50";
        if (priceUpper.compareTo(priceLower) <= 0) return "❌ priceUpper 必須 > priceLower";
        BigDecimal minNotional = gridProperties.minSellNotionalUsdt();
        if (perLevelUsdt.compareTo(minNotional) < 0) {
            return "❌ perLevelUsdt 最少 " + minNotional.toPlainString()
                    + " USDT,避免 Grid 產生低於 OKX minimum order amount 的 dust sell";
        }

        BtGrid grid = new BtGrid();
        grid.setSymbol(symbol.toUpperCase().trim());
        grid.setPriceLower(priceLower);
        grid.setPriceUpper(priceUpper);
        grid.setGridCount(gridCount);
        grid.setPerLevelUsdt(perLevelUsdt);
        grid.setStopOutPct(stopOutPct != null ? stopOutPct : new BigDecimal("0.03"));
        grid.setEnabled(true);
        grid.setHintGated(true);
        grid.setRegimeWhitelist(regimeWhitelist != null && !regimeWhitelist.isBlank()
                ? regimeWhitelist : "SIDEWAYS,VOLATILE,RECOVERY");
        grid.setTotalRealizedPnl(BigDecimal.ZERO);
        grid.setClosedPairCount(0);
        grid.setAutoRebalance(false);   // default off; use enableGridAutoRebalance to turn on
        grid.setRebalanceTriggerPct(0.015);
        grid.setRebalanceCount(0);
        LocalDateTime now = LocalDateTime.now();
        grid.setCreatedAt(now);
        grid.setUpdatedAt(now);
        BtGrid saved = gridRepository.save(grid);

        // 建 N-1 個 buy level；priceUpper 是最後一格的 paired-sell boundary，不是買入觸發點。
        BigDecimal step = calcGridStep(priceLower, priceUpper, gridCount);
        List<BigDecimal> buyPrices = buildBuyLevelPrices(priceLower, priceUpper, gridCount);
        List<BtGridLevel> levels = new ArrayList<>();
        for (int i = 0; i < buyPrices.size(); i++) {
            BtGridLevel level = new BtGridLevel();
            level.setGridId(saved.getId());
            level.setLevelIndex(i);
            level.setPrice(buyPrices.get(i));
            level.setStatus("PENDING");
            level.setCreatedAt(now);
            levels.add(level);
        }
        gridLevelRepository.saveAll(levels);

        log.info("[MCP] createGrid id={} {} range=[{}, {}] priceLines={} buyLevels={} perLevel={} step={}",
                saved.getId(), symbol, priceLower, priceUpper, gridCount, buyPrices.size(), perLevelUsdt, step);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("✅ Grid #%d 建立成功%n", saved.getId()));
        sb.append(String.format("  %s 區間: %s ~ %s  價格線: %d  buyLevels: %d  每格: %s USDT  step: %s%n",
                symbol, priceLower.toPlainString(), priceUpper.toPlainString(),
                gridCount, buyPrices.size(), perLevelUsdt.toPlainString(), step.toPlainString()));
        sb.append(String.format("  Stop-out: %s%%  Hint-gated: ON (%s)%n",
                grid.getStopOutPct().multiply(BigDecimal.valueOf(100)).toPlainString(),
                grid.getRegimeWhitelist()));
        sb.append(String.format("  總資金需求: ~%s USDT(所有 buy level 若全填 FILLED)%n",
                estimateCreateGridCapital(perLevelUsdt, gridCount).toPlainString()));
        sb.append("\n下一步:scheduler 每 5 分鐘自動檢查。用 listGrids 看狀態,closeGrid 手動停。");
        return sb.toString();
    }

    static BigDecimal calcGridStep(BigDecimal priceLower, BigDecimal priceUpper, int gridCount) {
        return priceUpper.subtract(priceLower)
                .divide(BigDecimal.valueOf(gridCount - 1L), 8, RoundingMode.HALF_UP);
    }

    static List<BigDecimal> buildBuyLevelPrices(BigDecimal priceLower, BigDecimal priceUpper, int gridCount) {
        BigDecimal step = calcGridStep(priceLower, priceUpper, gridCount);
        List<BigDecimal> buyPrices = new ArrayList<>();
        for (int i = 0; i < gridCount - 1; i++) {
            buyPrices.add(priceLower.add(step.multiply(BigDecimal.valueOf(i)))
                    .setScale(8, RoundingMode.HALF_UP));
        }
        return buyPrices;
    }

    static BigDecimal estimateCreateGridCapital(BigDecimal perLevelUsdt, int priceLineCount) {
        return perLevelUsdt.multiply(BigDecimal.valueOf(Math.max(0, priceLineCount - 1L)));
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "綜合風險儀表板:合併 balance / 開倉 / grid 曝險 / stopOut 緩衝,一眼看當前總風險。" +
            "比 getBalance + getOpenPositions + listGrids 三個 tool 的拼裝結果更結構化。")
    public String getCurrentExposure() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== 綜合風險儀表板 ===\n\n");

        // 1. 帳戶餘額
        List<OkxTradingService.SpotHolding> holdings;
        BigDecimal totalUsd = BigDecimal.ZERO;
        BigDecimal usdtAvail = BigDecimal.ZERO;
        try {
            holdings = okxTradingService.getSpotHoldings();
            for (var h : holdings) {
                if (h.eqUsd != null) totalUsd = totalUsd.add(h.eqUsd);
                if ("USDT".equalsIgnoreCase(h.ccy)) usdtAvail = h.availBal;
            }
        } catch (Exception e) {
            sb.append("⚠️ 無法查 OKX 餘額: ").append(e.getMessage()).append("\n\n");
            holdings = Collections.emptyList();
        }
        CapitalAllocationPolicyPreviewService.CapitalAllocationSnapshot capitalSnapshot = null;
        try {
            capitalSnapshot = capitalAllocationPolicyPreviewService.snapshot("BTCUSDT");
        } catch (Exception e) {
            sb.append("⚠️ 無法讀取 reserve-aware capital snapshot: ").append(e.getMessage()).append("\n");
        }

        if (capitalSnapshot != null) {
            sb.append(String.format("💰 總觀測資金: $%.2f USD (Spot $%.2f + Earn flexible $%.2f)%n",
                    capitalSnapshot.totalObservedCapitalUsdt().doubleValue(),
                    capitalSnapshot.tradingAccountObservedUsd().doubleValue(),
                    capitalSnapshot.earnFlexibleUsdt().doubleValue()));
            sb.append(String.format("   USDT 可用: %.2f | SCORE_BUY reserve target: $%.2f | deployable after planned reserve top-up: $%.2f%n",
                    usdtAvail.doubleValue(),
                    capitalSnapshot.scoreBuyReserveTargetUsdt().doubleValue(),
                    capitalSnapshot.deployableAfterPlannedRedeemUsdt().doubleValue()));
            if (capitalSnapshot.missedOpportunityDueToCapitalSegmentation()) {
                sb.append("   ⚠️ capital segmentation: Earn capital is visible here but is not auto-redeemed by this tool.\n");
            }
        } else {
            sb.append(String.format("💰 Spot 帳戶觀測資金: $%.2f USD%n", totalUsd.doubleValue()));
            sb.append(String.format("   USDT 可用: %.2f%n", usdtAvail.doubleValue()));
        }
        for (var h : holdings) {
            if (!"USDT".equalsIgnoreCase(h.ccy) && h.eqUsd != null
                    && h.eqUsd.compareTo(BigDecimal.valueOf(0.5)) > 0) {
                sb.append(String.format("   %s: %s (~$%.2f)%n",
                        h.ccy, h.availBal.toPlainString(), h.eqUsd.doubleValue()));
            }
        }

        // 2. 自動交易開倉
        List<BtLiveSignal> openPositions = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNull();
        BigDecimal posExposure = BigDecimal.ZERO;
        BigDecimal posMarketValue = BigDecimal.ZERO;
        BigDecimal posUnrealizedPnl = BigDecimal.ZERO;
        Map<String, BigDecimal> lastPriceBySymbol = new HashMap<>();
        for (BtLiveSignal p : openPositions) {
            BigDecimal entry = entryPrice(p);
            BigDecimal qty = p.getTradedQty();
            if (entry != null && qty != null) {
                BigDecimal cost = entry.multiply(qty);
                posExposure = posExposure.add(cost);
                BigDecimal mark = lastPrice(p.getSymbol(), lastPriceBySymbol);
                if (mark != null) {
                    BigDecimal marketValue = mark.multiply(qty);
                    posMarketValue = posMarketValue.add(marketValue);
                    posUnrealizedPnl = posUnrealizedPnl.add(marketValue.subtract(cost));
                }
            }
        }
        sb.append(String.format("%n📊 自動交易開倉: %d 筆", openPositions.size()));
        if (!openPositions.isEmpty()) {
            sb.append(String.format(" (成本曝險 ~$%.2f", posExposure.doubleValue()));
            if (posMarketValue.signum() > 0) {
                sb.append(String.format(" / 現值 ~$%.2f / 浮動PnL %s)",
                        posMarketValue.doubleValue(),
                        fmtSignedUsd(posUnrealizedPnl)));
            } else {
                sb.append(" / 浮動PnL N/A)");
            }
            sb.append('\n');
            for (BtLiveSignal p : openPositions) {
                boolean protected_ = p.getOcoOrderListId() != null;
                BigDecimal entry = entryPrice(p);
                BigDecimal qty = p.getTradedQty();
                BigDecimal mark = lastPrice(p.getSymbol(), lastPriceBySymbol);
                BigDecimal cost = entry != null && qty != null ? entry.multiply(qty) : null;
                BigDecimal marketValue = mark != null && qty != null ? mark.multiply(qty) : null;
                BigDecimal pnl = cost != null && marketValue != null ? marketValue.subtract(cost) : null;
                BigDecimal pnlPct = cost != null && cost.signum() > 0 && pnl != null
                        ? pnl.divide(cost, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                        : null;
                sb.append(String.format("   %s #%s %s @ %s qty=%s mark=%s cost=%s value=%s uPnL=%s (%s) OCO=%s%n",
                        protected_ ? "🟢" : "🔴",
                        p.getId(),
                        p.getSymbol(), fmtBd(entry),
                        fmtBd(p.getTradedQty()),
                        fmtBd(mark),
                        fmtUsd(cost),
                        fmtUsd(marketValue),
                        fmtSignedUsd(pnl),
                        fmtPct(pnlPct),
                        protected_ ? "✅" : "❌"));
            }
        } else {
            sb.append('\n');
        }

        // 3. Grid 曝險
        List<BtGrid> activeGrids = gridRepository.findByEnabledTrueAndClosedAtIsNull();
        BigDecimal gridMaxExposure = BigDecimal.ZERO;   // 全 level 都填滿的理論曝險
        BigDecimal gridActualExposure = BigDecimal.ZERO; // 已 HOLDING/SELL_FAILED/SELL_PARTIAL 的實際曝險
        sb.append(String.format("%n🔲 活躍 Grid: %d 個%n", activeGrids.size()));
        for (BtGrid g : activeGrids) {
            List<BtGridLevel> levels = gridLevelRepository.findByGridId(g.getId());
            BigDecimal gridCapacity = estimateConfiguredGridCapacity(g, levels);
            gridMaxExposure = gridMaxExposure.add(gridCapacity);
            List<BtGridLevel> holdings_ = levels.stream()
                    .filter(l -> "HOLDING".equals(l.getStatus())
                            || "SELL_FAILED".equals(l.getStatus())
                            || "SELL_PARTIAL".equals(l.getStatus()))
                    .toList();
            BigDecimal holdingValue = BigDecimal.ZERO;
            for (BtGridLevel lv : holdings_) {
                if (lv.getFilledPrice() != null && lv.getFilledQty() != null) {
                    holdingValue = holdingValue.add(
                            lv.getFilledPrice().multiply(lv.getFilledQty()));
                }
            }
            gridActualExposure = gridActualExposure.add(holdingValue);
            sb.append(String.format("   Grid #%d %s [%s~%s]%n",
                    g.getId(), g.getSymbol(),
                    fmtBd(g.getPriceLower()), fmtBd(g.getPriceUpper())));
            sb.append(String.format("     容量: $%.2f (%d buy levels×$%.2f; %d price lines)  持倉: $%.2f (%d level)%n",
                    gridCapacity.doubleValue(), configuredBuyLevelCount(g, levels), g.getPerLevelUsdt().doubleValue(),
                    g.getGridCount(),
                    holdingValue.doubleValue(), holdings_.size()));
            // stopOut 提示
            if (g.getStopOutPct() != null) {
                BigDecimal stopOutPrice = g.getPriceLower().multiply(
                        BigDecimal.ONE.subtract(g.getStopOutPct()));
                sb.append(String.format("     stopOut 觸發點: ~%s (下界 -%.1f%%)%n",
                        fmtBd(stopOutPrice), g.getStopOutPct().doubleValue() * 100));
            }
        }

        // 4. 總結
        BigDecimal totalExposure = posExposure.add(gridActualExposure);
        BigDecimal exposureDenominator = capitalSnapshot != null
                ? capitalSnapshot.totalObservedCapitalUsdt()
                : totalUsd;
        double expPct = exposureDenominator.doubleValue() > 0
                ? totalExposure.doubleValue() / exposureDenominator.doubleValue() * 100 : 0;
        sb.append(String.format("%n📈 總實際曝險: $%.2f (%.1f%% of 總觀測資金)%n",
                totalExposure.doubleValue(), expPct));
        if (capitalSnapshot != null && capitalSnapshot.tradingAccountObservedUsd().signum() > 0) {
            double tradingPct = totalExposure.doubleValue()
                    / capitalSnapshot.tradingAccountObservedUsd().doubleValue() * 100;
            sb.append(String.format("   Spot 帳戶內曝險率: %.1f%% | Earn flexible 顯示但不自動移動%n",
                    tradingPct));
        }
        sb.append(String.format("   Grid 最大曝險(全 level 填滿): $%.2f%n",
                gridMaxExposure.doubleValue()));

        return sb.toString();
    }

    private BigDecimal entryPrice(BtLiveSignal position) {
        if (position == null) return null;
        return position.getActualEntryPrice() != null
                ? position.getActualEntryPrice()
                : position.getEntryPrice();
    }

    private BigDecimal lastPrice(String symbol, Map<String, BigDecimal> cache) {
        if (symbol == null || symbol.isBlank()) return null;
        return cache.computeIfAbsent(symbol, sym -> {
            try {
                return okxTradingService.getLastPrice(sym);
            } catch (Exception e) {
                log.warn("[getCurrentExposure] failed to load last price for {}: {}", sym, e.getMessage());
                return null;
            }
        });
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "列出所有 grid(活躍的在前,已關閉的在後),顯示區間/格數/PnL/當前觸發 level 數。")
    public String listGrids() {
        List<BtGrid> all = gridRepository.findAll();
        if (all.isEmpty()) return "目前無 grid。用 createGrid 建立。";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Grid 清單 (共 ").append(all.size()).append(" 筆) ===\n\n");
        all.stream()
                .sorted((a, b) -> {
                    // 活躍在前,然後按 id desc
                    int sa = a.getClosedAt() == null ? 0 : 1;
                    int sb0 = b.getClosedAt() == null ? 0 : 1;
                    if (sa != sb0) return Integer.compare(sa, sb0);
                    return Long.compare(b.getId(), a.getId());
                })
                .forEach(g -> {
                    long pending = gridLevelRepository.countByGridIdAndStatus(g.getId(), "PENDING");
                    long holding = gridLevelRepository.countByGridIdAndStatus(g.getId(), "HOLDING");
                    long closedPerm = gridLevelRepository.countByGridIdAndStatus(g.getId(), "CLOSED");
                    long sellFailed = gridLevelRepository.countByGridIdAndStatus(g.getId(), "SELL_FAILED");
                    long sellPartial = gridLevelRepository.countByGridIdAndStatus(g.getId(), "SELL_PARTIAL");
                    long buyFailed = gridLevelRepository.countByGridIdAndStatus(g.getId(), "BUY_FAILED");
                    String state = g.getClosedAt() != null ? "🔴 CLOSED"
                            : g.getPausedAt() != null ? "⏸ PAUSED"
                            : Boolean.TRUE.equals(g.getEnabled()) ? "✅ ACTIVE"
                            : "⚪ DISABLED";
                    sb.append(String.format("#%d %s %s%n", g.getId(), g.getSymbol(), state));
                    sb.append(String.format("  區間: %s ~ %s (%d 格, %s USDT/格)%n",
                            g.getPriceLower().toPlainString(),
                            g.getPriceUpper().toPlainString(),
                            g.getGridCount(),
                            g.getPerLevelUsdt().toPlainString()));
                    sb.append(String.format("  Level: PENDING=%d HOLDING=%d CLOSED=%d SELL_FAILED=%d SELL_PARTIAL=%d BUY_FAILED=%d%n",
                            pending, holding, closedPerm, sellFailed, sellPartial, buyFailed));
                    sb.append(String.format("  PnL: %+.4f USDT  完成對數: %d%n",
                            g.getTotalRealizedPnl().doubleValue(), g.getClosedPairCount()));
                    if (sellFailed > 0) {
                        sb.append(gridSellFailureSummary(g));
                    }
                    if (g.getPausedReason() != null) {
                        sb.append(String.format("  ⚠️ %s%n", g.getPausedReason()));
                    }
                    sb.append("\n");
                });
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "手動暫停 grid(levels 不動但不再觸發新 buy/sell,可用 resumeGrid 恢復)。" +
            "param: gridId, reason(選填)")
    public String pauseGrid(Long gridId, String reason) {
        if (gridId == null) return "❌ gridId 不可為空";
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";
        if (g.getClosedAt() != null) return "❌ Grid #" + gridId + " 已關閉";
        g.setPausedAt(LocalDateTime.now());
        g.setPausedReason(reason != null ? reason : "manual pause");
        g.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(g);
        log.info("[MCP] pauseGrid id={}: {}", gridId, g.getPausedReason());
        return String.format("⏸ Grid #%d %s 已暫停(reason: %s)", gridId, g.getSymbol(), g.getPausedReason());
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "開啟或關閉 Grid 自動換範圍功能。防呆機制：(1)只在 SIDEWAYS regime 換 " +
            "(2)價格需在範圍外 minHoursOutside 小時才觸發 (3)每日最多換 1 次 " +
            "(4)累計超過 maxRebalanceCount 次後停止並 TG 通知需人工確認。" +
            "params: gridId, enable=true/false, triggerPct=觸發比例（預設 0.015=1.5%）, " +
            "minHoursOutside=最少在外幾小時才換（預設 4）, maxRebalanceCount=最大換範圍次數（預設 5）")
    public String enableGridAutoRebalance(Long gridId, Boolean enable, Double triggerPct,
                                          Integer minHoursOutside, Integer maxRebalanceCount) {
        if (gridId == null || enable == null) return "❌ gridId 和 enable 為必填";
        if (Boolean.TRUE.equals(enable) && !gridProperties.customCreateResumeEnabled()) {
            return "BLOCKED_DEPRECATED_CUSTOM_GRID_AUTO_REBALANCE_USE_OKX_NATIVE";
        }
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";
        if (g.getClosedAt() != null) return "❌ Grid #" + gridId + " 已關閉";
        g.setAutoRebalance(enable);
        if (triggerPct != null && triggerPct > 0) g.setRebalanceTriggerPct(triggerPct);
        if (minHoursOutside != null && minHoursOutside >= 1) g.setMinHoursOutside(minHoursOutside);
        if (maxRebalanceCount != null && maxRebalanceCount >= 1) g.setMaxRebalanceCount(maxRebalanceCount);
        if (!enable) g.setOutsideRangeSince(null); // reset when disabling
        g.setUpdatedAt(java.time.LocalDateTime.now());
        gridRepository.save(g);
        String status = enable
                ? String.format("✅ Grid #%d 自動換範圍已啟用\n  觸發閾值: %.1f%%  最少在外: %d小時  最大次數: %d次",
                        gridId, g.getRebalanceTriggerPct() * 100, g.getMinHoursOutside(), g.getMaxRebalanceCount())
                : String.format("⏹ Grid #%d 自動換範圍已停用", gridId);
        log.info("[MCP] enableGridAutoRebalance id={} enable={} pct={} minHours={} maxCnt={}",
                gridId, enable, g.getRebalanceTriggerPct(), g.getMinHoursOutside(), g.getMaxRebalanceCount());
        return status;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "DEPRECATED unsafe custom Grid close entrypoint. Always blocked during migration. " +
            "Use retireLegacyGrid dry-run and its exact dynamic confirmation for separately authorized retirement.")
    @Deprecated(forRemoval = true)
    public String closeGrid(Long gridId) {
        return "BLOCKED_DEPRECATED_CUSTOM_GRID_CLOSE_USE_RETIRE_LEGACY_GRID_DRY_RUN";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "Protected retirement for one legacy custom Grid. Dry-run by default. disposition must be " +
            "MARKET_SELL_AND_CLOSE when exactly attributable HOLDING inventory exists, or CLOSE_NO_HOLDING when none exists. " +
            "execute=true requires both disabled-by-default legacy-retirement gates and exact dynamic confirmText. " +
            "Market sell additionally requires the OKX master trading switch. Preview performs provider/order/fee/lot-size reads " +
            "but never sends an order or mutates DB. params: gridId, disposition, execute, confirmText")
    public String retireLegacyGrid(
            Long gridId,
            String disposition,
            @ToolParam(required = false, description = "False/null for dry-run; true requests guarded retirement") Boolean execute,
            @ToolParam(required = false, description = "Exact dynamic confirmation text returned by dry-run") String confirmText) {
        boolean executeRequested = Boolean.TRUE.equals(execute);
        String normalizedDisposition = disposition == null ? "" : disposition.trim().toUpperCase(java.util.Locale.ROOT);
        List<String> blockers = new ArrayList<>();
        BtGrid grid = gridId == null ? null : gridRepository.findById(gridId).orElse(null);
        if (gridId == null || gridId <= 0) blockers.add("GRID_ID_REQUIRED");
        if (gridId != null && grid == null) blockers.add("GRID_NOT_FOUND");
        if (grid != null && grid.getClosedAt() != null) blockers.add("GRID_ALREADY_CLOSED");

        List<BtGridLevel> levels = grid == null ? List.of() : gridLevelRepository.findByGridId(gridId).stream()
                .sorted(java.util.Comparator.comparing(BtGridLevel::getId))
                .toList();
        List<BtGridLevel> holdings = levels.stream().filter(level -> "HOLDING".equals(level.getStatus())).toList();
        long inFlight = levels.stream().filter(level -> Set.of("PENDING_OKX", "SELLING_OKX").contains(level.getStatus())).count();
        long residual = levels.stream().filter(level -> Set.of("SELL_FAILED", "SELL_PARTIAL").contains(level.getStatus())).count();
        if (inFlight > 0) blockers.add("LEGACY_IN_FLIGHT_LEVELS_MUST_BE_ZERO");
        if (residual > 0) blockers.add("LEGACY_RESIDUAL_REQUIRES_DEDICATED_RECOVERY");

        String requiredDisposition = holdings.isEmpty() ? "CLOSE_NO_HOLDING" : "MARKET_SELL_AND_CLOSE";
        if (!requiredDisposition.equals(normalizedDisposition)) {
            blockers.add("DISPOSITION_MUST_EQUAL_" + requiredDisposition);
        }

        List<String> providerPlans = new ArrayList<>();
        BigDecimal totalSellQty = BigDecimal.ZERO;
        for (BtGridLevel holding : holdings) {
            try {
                OkxTradingService.GridRetirementQuantity quantity = okxTradingService.getGridRetirementQuantity(
                        grid.getSymbol(), holding.getBuyOrderId(), holding.getFilledQty());
                totalSellQty = totalSellQty.add(quantity.sellQuantity());
                providerPlans.add("level=" + holding.getId()
                        + ",buyOrderId=" + holding.getBuyOrderId()
                        + ",providerGross=" + quantity.providerGrossQty().toPlainString()
                        + ",signedBuyFee=" + quantity.signedBuyFee().toPlainString()
                        + ",feeCurrency=" + quantity.feeCurrency()
                        + ",netAttributable=" + quantity.netAttributableQty().toPlainString()
                        + ",sellQty=" + quantity.sellQuantity().toPlainString()
                        + ",dust=" + quantity.attributionDust().toPlainString());
            } catch (RuntimeException error) {
                blockers.add("LEVEL_" + holding.getId() + "_PROVIDER_ATTRIBUTION_FAILED");
            }
        }

        String stateCanonical = levels.stream()
                .map(level -> level.getId() + "|" + level.getStatus() + "|"
                        + nullSafe(level.getFilledQty()) + "|" + nullSafe(level.getBuyOrderId()) + "|"
                        + nullSafe(level.getSellOrderId()) + "|" + nullSafe(level.getIntentAt()))
                .collect(java.util.stream.Collectors.joining(";"));
        String stateHash = sha256GridRetirementState(stateCanonical + "|" + String.join(";", providerPlans));
        String requiredConfirmText = "AUTHORIZE_LEGACY_GRID_RETIREMENT|gridId=" + gridId
                + "|symbol=" + (grid == null ? "UNKNOWN" : grid.getSymbol())
                + "|disposition=" + normalizedDisposition
                + "|holdingCount=" + holdings.size()
                + "|totalSellQty=" + totalSellQty.stripTrailingZeros().toPlainString()
                + "|stateSha256=" + stateHash;

        if (executeRequested && !gridProperties.legacyRetirementEnabled()) blockers.add("LEGACY_RETIREMENT_FEATURE_DISABLED");
        if (executeRequested && !gridProperties.legacyRetirementLiveActionEnabled()) blockers.add("LEGACY_RETIREMENT_LIVE_ACTION_DISABLED");
        if (executeRequested && !holdings.isEmpty() && !okxTradingService.isAutoTradeEnabled()) {
            blockers.add("OKX_AUTO_TRADE_MASTER_DISABLED");
        }
        if (executeRequested && !requiredConfirmText.equals(confirmText)) blockers.add("CONFIRM_TEXT_MISMATCH");

        StringBuilder packet = new StringBuilder();
        packet.append("packetType=LEGACY_GRID_RETIREMENT\n");
        packet.append("boundary=PROTECTED_WRITE_DRY_RUN_BY_DEFAULT\n");
        packet.append("gridId=").append(gridId).append('\n');
        packet.append("symbol=").append(grid == null ? "UNKNOWN" : grid.getSymbol()).append('\n');
        packet.append("disposition=").append(normalizedDisposition).append('\n');
        packet.append("requiredDisposition=").append(requiredDisposition).append('\n');
        packet.append("holdingCount=").append(holdings.size()).append('\n');
        packet.append("inFlightCount=").append(inFlight).append('\n');
        packet.append("residualCount=").append(residual).append('\n');
        packet.append("totalSellQty=").append(totalSellQty.stripTrailingZeros().toPlainString()).append('\n');
        packet.append("providerPlans=").append(providerPlans).append('\n');
        packet.append("stateSha256=").append(stateHash).append('\n');
        packet.append("featureEnabled=").append(gridProperties.legacyRetirementEnabled()).append('\n');
        packet.append("liveActionEnabled=").append(gridProperties.legacyRetirementLiveActionEnabled()).append('\n');
        packet.append("executeRequested=").append(executeRequested).append('\n');
        packet.append("requiredConfirmText=").append(requiredConfirmText).append('\n');
        packet.append("blockers=").append(new LinkedHashSet<>(blockers)).append('\n');

        if (!executeRequested || !blockers.isEmpty()) {
            packet.append("status=").append(blockers.isEmpty()
                    ? "READY_FOR_SEPARATE_EXACT_RETIREMENT_AUTHORIZATION"
                    : "RETIREMENT_BLOCKED").append('\n');
            packet.append("providerOrderAttempted=false\n");
            packet.append("databaseMutationAttempted=false\n");
            return packet.toString();
        }

        packet.append("status=EXECUTION_REQUEST_ACCEPTED\n");
        packet.append(executeAuthorizedLegacyClose(gridId));
        return packet.toString();
    }

    String executeAuthorizedLegacyClose(Long gridId) {
        if (gridId == null) return "❌ gridId 不可為空";
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";
        if (g.getClosedAt() != null) return "❌ Grid #" + gridId + " 已於 " + g.getClosedAt() + " 關閉";

        List<BtGridLevel> filled = gridLevelRepository.findByGridIdAndStatusIn(
                gridId, List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
        int closedCount = 0;
        int partialCount = 0;
        int failCount = 0;
        BigDecimal totalClose = BigDecimal.ZERO;
        BigDecimal mark = null;
        if (!filled.isEmpty()) {
            try {
                mark = okxTradingService.getLastPrice(g.getSymbol());
            } catch (Exception ignored) {
                mark = null;
            }
        }
        LocalDateTime attemptAt = LocalDateTime.now();
        for (BtGridLevel level : filled) {
            BigDecimal requestedQty;
            OkxTradingService.GridRetirementQuantity retirementQuantity;
            try {
                retirementQuantity =
                        okxTradingService.getGridRetirementQuantity(
                                g.getSymbol(), level.getBuyOrderId(), level.getFilledQty());
                requestedQty = retirementQuantity.sellQuantity();
                level.setErrorMessage(String.format(
                        "retirement preflight: gross=%s signedBuyFee=%s %s net=%s sell=%s dust=%s",
                        retirementQuantity.providerGrossQty(), retirementQuantity.signedBuyFee(),
                        retirementQuantity.feeCurrency(), retirementQuantity.netAttributableQty(),
                        retirementQuantity.sellQuantity(), retirementQuantity.attributionDust()));
                gridLevelRepository.save(level);
            } catch (Exception preflightError) {
                level.setErrorMessage(closeGridError("closeGrid fee-aware quantity preflight blocked: ", preflightError));
                gridLevelRepository.save(level);
                failCount++;
                continue;
            }

            try {
                level.setStatus("SELLING_OKX");
                level.setIntentAt(attemptAt);
                level.setErrorMessage(null);
                gridLevelRepository.save(level);

                TradeResult sellResult = okxTradingService.placeMarketSellWithFill(g.getSymbol(), requestedQty);
                if (sellResult == null) {
                    throw new IllegalStateException("OKX sell returned null TradeResult");
                }
                BigDecimal soldQty = sellResult.getQty() == null ? requestedQty : sellResult.getQty();
                if (soldQty.signum() < 0) soldQty = BigDecimal.ZERO;
                BigDecimal leftover = requestedQty.subtract(soldQty).max(BigDecimal.ZERO);
                BigDecimal sellPx = closeGridSellPrice(sellResult, mark, level);
                BigDecimal buyPx = level.getFilledPrice() != null ? level.getFilledPrice() : sellPx;
                BigDecimal grossBuyCost = buyPx.multiply(retirementQuantity.providerGrossQty());
                BigDecimal allocatedBuyCost = grossBuyCost.multiply(soldQty)
                        .divide(retirementQuantity.netAttributableQty(), 16, RoundingMode.HALF_UP);
                BigDecimal sellFeeUsdt = sellResult.getFeeUsdt() != null
                        ? sellResult.getFeeUsdt() : BigDecimal.ZERO;
                BigDecimal pnl = sellPx.multiply(soldQty)
                        .subtract(allocatedBuyCost)
                        .subtract(sellFeeUsdt)
                        .setScale(8, RoundingMode.HALF_UP);
                BigDecimal prevRealized = level.getRealizedPnl() != null
                        ? level.getRealizedPnl() : BigDecimal.ZERO;

                if (leftover.compareTo(requestedQty.multiply(new BigDecimal("0.01"))) > 0) {
                    level.setStatus("SELL_PARTIAL");
                    level.setFilledQty(leftover);
                    level.setRealizedPnl(prevRealized.add(pnl));
                    level.setSellOrderId(sellResult.getOrderId());
                    level.setErrorMessage(String.format(
                            "closeGrid partial fill: requested=%s sold=%s leftover=%s",
                            requestedQty.toPlainString(), soldQty.toPlainString(), leftover.toPlainString()));
                    level.setIntentAt(null);
                    gridLevelRepository.save(level);
                    g.setTotalRealizedPnl(nullToZero(g.getTotalRealizedPnl()).add(pnl));
                    g.setUpdatedAt(LocalDateTime.now());
                    gridRepository.save(g);
                    totalClose = totalClose.add(pnl);
                    partialCount++;
                    continue;
                }

                level.setStatus("CLOSED");
                level.setRetryCount(0);
                level.setRealizedPnl(prevRealized.add(pnl));
                level.setSellOrderId(sellResult.getOrderId());
                level.setClosedAt(LocalDateTime.now());
                level.setIntentAt(null);
                level.setErrorMessage(null);
                gridLevelRepository.save(level);
                g.setTotalRealizedPnl(nullToZero(g.getTotalRealizedPnl()).add(pnl));
                g.setClosedPairCount((g.getClosedPairCount() == null ? 0 : g.getClosedPairCount()) + 1);
                g.setUpdatedAt(LocalDateTime.now());
                gridRepository.save(g);
                totalClose = totalClose.add(pnl);
                closedCount++;
            } catch (Exception e) {
                log.error("[MCP closeGrid] level={} sell failed: {}", level.getLevelIndex(), e.getMessage());
                level.setStatus("SELLING_OKX");
                level.setIntentAt(attemptAt);
                level.setErrorMessage(closeGridError("closeGrid SELLING_OKX exception: ", e));
                gridLevelRepository.save(level);
                failCount++;
            }
        }
        if (partialCount > 0 || failCount > 0) {
            g.setPausedAt(LocalDateTime.now());
            g.setPausedReason(String.format("closeGrid blocked: partial=%d failed=%d; resolve residual before final close",
                    partialCount, failCount));
            g.setUpdatedAt(LocalDateTime.now());
            gridRepository.save(g);
            log.warn("[MCP] closeGrid id={} blocked: closed {} levels, partial {}, failed {}, pnl {}",
                    gridId, closedCount, partialCount, failCount, totalClose);
            return String.format(
                    "⚠️ Grid #%d %s close blocked%n  close_grid_status=BLOCKED_RESIDUAL_NOT_CLOSED%n  grid_closed=false%n  平倉 level: %d%n  partial: %d%n  failed: %d%n  此次已實現 PnL: %+.4f USDT%n  處置: Grid 已手動暫停但未 closed_at；先處理 SELLING_OKX/SELL_PARTIAL/SELL_FAILED residual，再重試 closeGrid 或 cleanup。",
                    gridId, g.getSymbol(), closedCount, partialCount, failCount, totalClose.doubleValue());
        }
        g.setEnabled(false);
        g.setPausedAt(null);
        g.setPausedReason(null);
        g.setClosedAt(LocalDateTime.now());
        g.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(g);
        log.info("[MCP] closeGrid id={} closed {} levels, partial {}, failed {}, pnl {}",
                gridId, closedCount, partialCount, failCount, totalClose);

        return String.format(
                "🔴 Grid #%d %s 已關閉%n  close_grid_status=CLOSED_NO_RESIDUAL%n  grid_closed=true%n  平倉 level: %d(partial %d / failed %d)%n  此次 PnL: %+.4f%n  總累計 PnL: %+.4f USDT%n  完成對數: %d",
                gridId, g.getSymbol(), closedCount, partialCount, failCount,
                totalClose.doubleValue(), nullToZero(g.getTotalRealizedPnl()).doubleValue(),
                g.getClosedPairCount());
    }

    private static BigDecimal closeGridSellPrice(TradeResult sellResult, BigDecimal mark, BtGridLevel level) {
        if (sellResult.getAvgPrice() != null) return sellResult.getAvgPrice();
        if (mark != null) return mark;
        if (level.getPairedSellPrice() != null) return level.getPairedSellPrice();
        return level.getFilledPrice() != null ? level.getFilledPrice() : BigDecimal.ZERO;
    }

    private static BigDecimal nullToZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String closeGridError(String prefix, Exception e) {
        String message = prefix + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return message.length() <= 500 ? message : message.substring(0, 500);
    }

    private static String nullSafe(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String sha256GridRetirementState(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "查單一 grid 詳細統計:每個 level 的 status / price / 成交價 / PnL。" +
            "用於除錯或確認網格運行狀況。param: gridId")
    public String gridStats(Long gridId) {
        if (gridId == null) return "❌ gridId 不可為空";
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";

        List<BtGridLevel> levels = gridLevelRepository.findByGridId(gridId);
        levels.sort((a, b) -> Integer.compare(a.getLevelIndex(), b.getLevelIndex()));

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Grid #%d %s ===%n", g.getId(), g.getSymbol()));
        sb.append(String.format("區間 %s ~ %s  %d 格  %s USDT/格%n",
                g.getPriceLower().toPlainString(), g.getPriceUpper().toPlainString(),
                g.getGridCount(), g.getPerLevelUsdt().toPlainString()));
        sb.append(String.format("狀態: %s  PnL: %+.4f  對數: %d%n",
                g.getClosedAt() != null ? "CLOSED" : g.getPausedAt() != null ? "PAUSED" : "ACTIVE",
                g.getTotalRealizedPnl().doubleValue(), g.getClosedPairCount()));
        sb.append(String.format("Hint-gated: %s (whitelist: %s)%n%n",
                g.getHintGated(), g.getRegimeWhitelist()));

        BigDecimal currentPrice = null;
        try {
            currentPrice = okxTradingService.getLastPrice(g.getSymbol());
        } catch (Exception e) {
            log.debug("[MCP gridStats] last price unavailable for {}: {}", g.getSymbol(), e.getMessage());
        }
        BigDecimal unrealizedPnl = BigDecimal.ZERO;
        if (currentPrice != null) {
            sb.append(String.format("Current price: %s%n", fmtBd(currentPrice)));
        }

        sb.append(" Lv | 狀態     | 設定價      | 成交價      | 目標賣價    | PnL\n");
        sb.append("----+----------+-------------+-------------+-------------+-------\n");
        for (BtGridLevel l : levels) {
            String pnlDisplay = "-";
            if (l.getRealizedPnl() != null) {
                pnlDisplay = String.format("%+.4f", l.getRealizedPnl().doubleValue());
            } else if (currentPrice != null && l.getFilledPrice() != null && l.getFilledQty() != null
                    && List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL").contains(l.getStatus())) {
                BigDecimal pnl = currentPrice.subtract(l.getFilledPrice()).multiply(l.getFilledQty())
                        .setScale(8, RoundingMode.HALF_UP);
                unrealizedPnl = unrealizedPnl.add(pnl);
                pnlDisplay = String.format("%+.4f*", pnl.doubleValue());
            }
            sb.append(String.format(" %2d | %-8s | %11s | %11s | %11s | %s%n",
                    l.getLevelIndex(), l.getStatus(),
                    l.getPrice().toPlainString(),
                    l.getFilledPrice() != null ? l.getFilledPrice().toPlainString() : "-",
                    l.getPairedSellPrice() != null ? l.getPairedSellPrice().toPlainString() : "-",
                    pnlDisplay));
        }
        if (currentPrice != null) {
            sb.append(String.format("%n* 未實現 PnL 按 current price 粗估: %+.4f USDT%n",
                    unrealizedPnl.doubleValue()));
        }
        return sb.toString();
    }

    private String gridSellFailureSummary(BtGrid grid) {
        BigDecimal markPrice = null;
        try {
            markPrice = okxTradingService.getLastPrice(grid.getSymbol());
        } catch (Exception e) {
            log.debug("[MCP listGrids] last price unavailable for {}: {}",
                    grid.getSymbol(), e.getMessage());
        }

        BigDecimal threshold = gridProperties.minSellNotionalUsdt();
        StringBuilder sb = new StringBuilder();
        List<BtGridLevel> failedLevels = gridLevelRepository.findByGridIdAndStatus(grid.getId(), "SELL_FAILED");
        for (BtGridLevel level : failedLevels) {
            BigDecimal referencePrice = level.getPairedSellPrice() != null ? level.getPairedSellPrice() : markPrice;
            BigDecimal notional = level.getFilledQty() != null && referencePrice != null
                    ? level.getFilledQty().multiply(referencePrice).setScale(8, RoundingMode.HALF_UP)
                    : null;
            String materiality = classifyGridSellFailureMateriality(notional, threshold);
            String lifecycle = classifyGridSellFailureLifecycle(level);
            sb.append(String.format("  SELL_FAILED L%d: class=%s lifecycle=%s qty=%s estNotional=%s threshold=%s retry=%d/3%n",
                    level.getLevelIndex(),
                    materiality,
                    lifecycle,
                    level.getFilledQty() != null ? level.getFilledQty().toPlainString() : "N/A",
                    notional != null ? notional.toPlainString() : "N/A",
                    threshold.toPlainString(),
                    level.getRetryCount() != null ? level.getRetryCount() : 0));
        }
        return sb.toString();
    }

    private String classifyGridSellFailureMateriality(BigDecimal notional, BigDecimal threshold) {
        if (notional == null) return "unknown_failure";
        if (notional.compareTo(threshold) < 0) return "dust_failure";
        return "material_failure";
    }

    private String classifyGridSellFailureLifecycle(BtGridLevel level) {
        int retries = level.getRetryCount() != null ? level.getRetryCount() : 0;
        return retries >= 3 ? "stale" : "recoverable";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#466 只讀診斷：列出 active grid 中 HOLDING / SELL_FAILED / SELL_PARTIAL 且估算賣出名目金額低於 OKX minimum order amount 的 dust sell 風險。" +
            "param: minNotionalUsdt 可選,預設 trading.grid.min-sell-notional-usdt。")
    public String listGridDustSellRisks(BigDecimal minNotionalUsdt) {
        BigDecimal threshold = minNotionalUsdt != null && minNotionalUsdt.signum() > 0
                ? minNotionalUsdt
                : gridProperties.minSellNotionalUsdt();
        List<BtGrid> grids = gridRepository.findByEnabledTrueAndClosedAtIsNull();
        if (grids.isEmpty()) return "ℹ️ 無 active grid。";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Grid Dust Sell Risks ===\n");
        sb.append("threshold: ").append(threshold.toPlainString()).append(" USDT\n\n");

        int riskCount = 0;
        int checkedCount = 0;
        for (BtGrid grid : grids) {
            BigDecimal markPrice = null;
            try {
                markPrice = okxTradingService.getLastPrice(grid.getSymbol());
            } catch (Exception e) {
                log.debug("[MCP listGridDustSellRisks] last price unavailable for {}: {}",
                        grid.getSymbol(), e.getMessage());
            }
            List<BtGridLevel> levels = gridLevelRepository.findByGridIdAndStatusIn(
                    grid.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
            for (BtGridLevel level : levels) {
                if (level.getFilledQty() == null) continue;
                BigDecimal referencePrice = level.getPairedSellPrice() != null
                        ? level.getPairedSellPrice()
                        : markPrice;
                if (referencePrice == null || referencePrice.signum() <= 0) continue;
                checkedCount++;
                BigDecimal notional = level.getFilledQty().multiply(referencePrice)
                        .setScale(8, RoundingMode.HALF_UP);
                if (notional.compareTo(threshold) < 0) {
                    riskCount++;
                    sb.append(String.format(
                            "⚠️ Grid #%d L%d %s %s qty=%s refPx=%s estNotional=%s retry=%d/3%n",
                            grid.getId(),
                            level.getLevelIndex(),
                            grid.getSymbol(),
                            level.getStatus(),
                            level.getFilledQty().toPlainString(),
                            referencePrice.toPlainString(),
                            notional.toPlainString(),
                            level.getRetryCount() != null ? level.getRetryCount() : 0));
                    if (level.getErrorMessage() != null && !level.getErrorMessage().isBlank()) {
                        sb.append("   lastError: ")
                                .append(level.getErrorMessage(), 0, Math.min(160, level.getErrorMessage().length()))
                                .append('\n');
                    }
                }
            }
        }

        if (riskCount == 0) {
            return String.format("✅ No grid dust sell risks. checked=%d threshold=%s USDT",
                    checkedCount, threshold.toPlainString());
        }
        sb.append(String.format("%nSummary: risks=%d checked=%d%n", riskCount, checkedCount));
        sb.append("Note: read-only report. No DB write, no order action.");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "Protected write: sell and close exact residual BTC inventory from an already-closed Grid. " +
            "Dry-run by default. Requires symbol, closed gridId, comma-separated levelIds, exact expectedQty, " +
            "optional maxNotionalUsdt, execute=true, and exact confirmText. The tool blocks if active positions exist, " +
            "if post-sell BTC would not cover active-grid inventory, or if current notional is below min sell amount.")
    public String cleanupClosedGridResidual(String symbol, Long gridId, String levelIdsCsv,
                                            BigDecimal expectedQty, BigDecimal maxNotionalUsdt,
                                            Boolean execute, String confirmText) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        boolean executeRequested = Boolean.TRUE.equals(execute);
        List<String> blockers = new ArrayList<>();

        List<Long> levelIds = parseLevelIds(levelIdsCsv);
        if (gridId == null || gridId <= 0) blockers.add("GRID_ID_REQUIRED");
        if (levelIds.isEmpty()) blockers.add("LEVEL_IDS_REQUIRED");
        if (expectedQty == null || expectedQty.signum() <= 0) blockers.add("EXPECTED_QTY_REQUIRED");

        BtGrid grid = null;
        List<BtGridLevel> levels = new ArrayList<>();
        if (blockers.isEmpty()) {
            grid = gridRepository.findById(gridId).orElse(null);
            if (grid == null) {
                blockers.add("GRID_NOT_FOUND");
            } else {
                if (!sym.equalsIgnoreCase(grid.getSymbol())) blockers.add("SYMBOL_GRID_MISMATCH");
                if (grid.getClosedAt() == null) blockers.add("GRID_NOT_CLOSED");
                for (Long id : levelIds) {
                    BtGridLevel level = gridLevelRepository.findById(id).orElse(null);
                    if (level == null) {
                        blockers.add("LEVEL_NOT_FOUND_" + id);
                        continue;
                    }
                    if (!gridId.equals(level.getGridId())) blockers.add("LEVEL_GRID_MISMATCH_" + id);
                    if (!List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL").contains(level.getStatus())) {
                        blockers.add("LEVEL_STATUS_NOT_RESIDUAL_" + id + "_" + level.getStatus());
                    }
                    if (level.getFilledQty() == null || level.getFilledQty().signum() <= 0) {
                        blockers.add("LEVEL_FILLED_QTY_MISSING_" + id);
                    }
                    if (level.getFilledPrice() == null || level.getFilledPrice().signum() <= 0) {
                        blockers.add("LEVEL_FILLED_PRICE_MISSING_" + id);
                    }
                    levels.add(level);
                }
            }
        }

        BigDecimal residualQty = levels.stream()
                .map(l -> l.getFilledQty() == null ? BigDecimal.ZERO : l.getFilledQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (expectedQty != null && residualQty.compareTo(expectedQty) != 0) {
            blockers.add("EXPECTED_QTY_MISMATCH actual=" + residualQty.toPlainString());
        }

        BigDecimal currentPrice = null;
        BigDecimal currentNotional = null;
        try {
            currentPrice = okxTradingService.getLastPrice(sym);
            if (currentPrice != null) {
                currentNotional = residualQty.multiply(currentPrice).setScale(8, RoundingMode.HALF_UP);
            }
        } catch (Exception e) {
            currentPrice = null;
        }
        if (currentPrice == null || currentPrice.signum() <= 0) {
            blockers.add("LAST_PRICE_UNAVAILABLE");
        }
        if (currentNotional != null
                && currentNotional.compareTo(gridProperties.minSellNotionalUsdt()) < 0) {
            blockers.add("CURRENT_NOTIONAL_BELOW_MIN_SELL " + currentNotional.toPlainString()
                    + "<" + gridProperties.minSellNotionalUsdt().toPlainString());
        }
        if (maxNotionalUsdt != null && currentNotional != null
                && currentNotional.compareTo(maxNotionalUsdt) > 0) {
            blockers.add("CURRENT_NOTIONAL_ABOVE_OPERATOR_CAP " + currentNotional.toPlainString()
                    + ">" + maxNotionalUsdt.toPlainString());
        }

        BigDecimal availableBase = BigDecimal.ZERO;
        BigDecimal cashBase = BigDecimal.ZERO;
        try {
            String base = sym.replace("USDT", "");
            for (OkxTradingService.SpotHolding holding : okxTradingService.getSpotHoldings()) {
                if (base.equalsIgnoreCase(holding.ccy)) {
                    availableBase = holding.availBal == null ? BigDecimal.ZERO : holding.availBal;
                    cashBase = holding.cashBal == null ? BigDecimal.ZERO : holding.cashBal;
                    break;
                }
            }
        } catch (Exception e) {
            blockers.add("SPOT_BALANCE_UNAVAILABLE");
        }
        if (availableBase.compareTo(residualQty) < 0) {
            blockers.add("AVAILABLE_BASE_BELOW_RESIDUAL_QTY available=" + availableBase.toPlainString());
        }

        BigDecimal activeGridQty = BigDecimal.ZERO;
        try {
            activeGridQty = sumActiveGridQtyForSymbol(sym);
        } catch (Exception e) {
            blockers.add("ACTIVE_GRID_QTY_UNAVAILABLE");
        }
        BigDecimal postSellAvailable = availableBase.subtract(residualQty);
        if (postSellAvailable.compareTo(activeGridQty) < 0) {
            blockers.add("POST_SELL_AVAILABLE_BELOW_ACTIVE_GRID_QTY postSell="
                    + postSellAvailable.toPlainString() + " activeGrid=" + activeGridQty.toPlainString());
        }

        long openPositions = 0;
        try {
            openPositions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull().stream()
                    .filter(p -> sym.equalsIgnoreCase(p.getSymbol()))
                    .count();
        } catch (Exception e) {
            blockers.add("OPEN_POSITION_LOOKUP_UNAVAILABLE");
        }
        if (openPositions > 0) {
            blockers.add("OPEN_AUTO_POSITION_EXISTS count=" + openPositions);
        }

        String requiredConfirmText = cleanupClosedGridResidualConfirmText(sym, gridId, levelIds, expectedQty);
        if (executeRequested && !requiredConfirmText.equals(confirmText)) {
            blockers.add("CONFIRM_TEXT_MISMATCH");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[closed-grid-residual-cleanup] protected execution path\n");
        sb.append("packetType=CLOSED_GRID_RESIDUAL_CLEANUP_EXECUTION\n");
        sb.append("boundary=PROTECTED_WRITE_REQUIRES_EXACT_CONFIRMATION\n");
        sb.append("symbol=").append(sym).append('\n');
        sb.append("gridId=").append(gridId).append('\n');
        sb.append("levelIds=").append(levelIds).append('\n');
        sb.append("expectedQty=").append(expectedQty != null ? expectedQty.toPlainString() : "null").append('\n');
        sb.append("residualQty=").append(residualQty.toPlainString()).append('\n');
        sb.append("currentPrice=").append(currentPrice != null ? currentPrice.toPlainString() : "UNKNOWN").append('\n');
        sb.append("currentNotionalUsdt=").append(currentNotional != null ? currentNotional.toPlainString() : "UNKNOWN").append('\n');
        sb.append("minSellNotionalUsdt=").append(gridProperties.minSellNotionalUsdt().toPlainString()).append('\n');
        sb.append("maxNotionalUsdt=").append(maxNotionalUsdt != null ? maxNotionalUsdt.toPlainString() : "null").append('\n');
        sb.append("cashBase=").append(cashBase.toPlainString()).append('\n');
        sb.append("availableBase=").append(availableBase.toPlainString()).append('\n');
        sb.append("activeGridQty=").append(activeGridQty.toPlainString()).append('\n');
        sb.append("postSellAvailableBase=").append(postSellAvailable.toPlainString()).append('\n');
        sb.append("openAutoPositions=").append(openPositions).append('\n');
        sb.append("executeRequested=").append(executeRequested).append('\n');
        sb.append("requiredConfirmText=").append(requiredConfirmText).append('\n');
        sb.append("cleanupBlockers=").append(blockers).append('\n');

        if (!blockers.isEmpty()) {
            sb.append("order_attempted=false\n");
            sb.append("db_write_attempted=false\n");
            sb.append("telegram_send_allowed=false\n");
            sb.append("deploy_or_env_change_allowed=false\n");
            sb.append("closed_grid_residual_cleanup_status=BLOCKED_PRECHECK\n");
            return sb.toString();
        }
        if (!executeRequested) {
            sb.append("order_attempted=false\n");
            sb.append("db_write_attempted=false\n");
            sb.append("telegram_send_allowed=false\n");
            sb.append("deploy_or_env_change_allowed=false\n");
            sb.append("closed_grid_residual_cleanup_status=READY_NOT_EXECUTED\n");
            return sb.toString();
        }

        TradeResult result = okxTradingService.placeMarketSellWithFill(sym, residualQty);
        BigDecimal soldQty = result.getQty() == null ? residualQty : result.getQty();
        BigDecimal avgPrice = result.getAvgPrice() == null ? currentPrice : result.getAvgPrice();
        BigDecimal totalPnl = applyClosedGridResidualSellResult(grid, levels, soldQty, avgPrice, result.getOrderId());
        BigDecimal leftoverQty = residualQty.subtract(soldQty).max(BigDecimal.ZERO);
        String status = leftoverQty.compareTo(residualQty.multiply(new BigDecimal("0.01"))) > 0
                ? "EXECUTED_PARTIAL_FILL_REVIEW_REQUIRED"
                : "EXECUTED";

        sb.append("order_attempted=true\n");
        sb.append("db_write_attempted=true\n");
        sb.append("orderId=").append(result.getOrderId()).append('\n');
        sb.append("soldQty=").append(soldQty.toPlainString()).append('\n');
        sb.append("avgPrice=").append(avgPrice != null ? avgPrice.toPlainString() : "UNKNOWN").append('\n');
        sb.append("leftoverQty=").append(leftoverQty.toPlainString()).append('\n');
        sb.append("realizedPnlApplied=").append(totalPnl.toPlainString()).append('\n');
        sb.append("telegram_send_allowed=false\n");
        sb.append("deploy_or_env_change_allowed=false\n");
        sb.append("closed_grid_residual_cleanup_status=").append(status).append('\n');
        return sb.toString();
    }

    static List<Long> parseLevelIds(String levelIdsCsv) {
        if (levelIdsCsv == null || levelIdsCsv.isBlank()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (String raw : levelIdsCsv.split(",")) {
            String token = raw.trim();
            if (token.isEmpty() || !token.matches("\\d+")) {
                return List.of();
            }
            try {
                long id = Long.parseLong(token);
                if (id <= 0) {
                    return List.of();
                }
                ids.add(id);
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return new ArrayList<>(ids);
    }

    static String cleanupClosedGridResidualConfirmText(String symbol, Long gridId,
                                                       List<Long> levelIds, BigDecimal expectedQty) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        List<Long> sorted = new ArrayList<>(levelIds == null ? List.of() : levelIds);
        Collections.sort(sorted);
        String levels = sorted.stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "_" + b)
                .orElse("NONE");
        String qty = expectedQty == null ? "NULL" : expectedQty.stripTrailingZeros().toPlainString();
        return "EXECUTE_CLOSED_GRID_RESIDUAL_CLEANUP_"
                + sym + "_GRID" + gridId + "_LEVELS" + levels + "_QTY" + qty;
    }

    private BigDecimal sumActiveGridQtyForSymbol(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        for (Object[] row : gridLevelRepository.sumFilledQtyBySymbolForActiveGrids()) {
            if (row == null || row.length < 2 || row[0] == null || row[1] == null) {
                continue;
            }
            if (sym.equalsIgnoreCase(String.valueOf(row[0]))) {
                Object qty = row[1];
                if (qty instanceof BigDecimal bd) {
                    return bd;
                }
                if (qty instanceof Number n) {
                    return BigDecimal.valueOf(n.doubleValue());
                }
                return new BigDecimal(String.valueOf(qty));
            }
        }
        return BigDecimal.ZERO;
    }

    private BigDecimal applyClosedGridResidualSellResult(BtGrid grid, List<BtGridLevel> levels,
                                                         BigDecimal soldQty, BigDecimal avgPrice, String orderId) {
        BigDecimal remainingSold = soldQty == null ? BigDecimal.ZERO : soldQty.max(BigDecimal.ZERO);
        BigDecimal totalAppliedPnl = BigDecimal.ZERO;
        int closedCount = 0;

        List<BtGridLevel> sorted = new ArrayList<>(levels);
        sorted.sort(java.util.Comparator.comparing(BtGridLevel::getId));
        for (BtGridLevel level : sorted) {
            BigDecimal levelQty = safe(level.getFilledQty());
            if (levelQty.signum() <= 0) {
                continue;
            }
            BigDecimal soldFromLevel = remainingSold.min(levelQty);
            BigDecimal pnl = BigDecimal.ZERO;
            if (avgPrice != null && level.getFilledPrice() != null && soldFromLevel.signum() > 0) {
                pnl = avgPrice.subtract(level.getFilledPrice())
                        .multiply(soldFromLevel)
                        .setScale(8, RoundingMode.HALF_UP);
            }
            totalAppliedPnl = totalAppliedPnl.add(pnl);
            level.setRealizedPnl(safe(level.getRealizedPnl()).add(pnl).setScale(8, RoundingMode.HALF_UP));
            level.setSellOrderId(orderId);
            level.setRetryCount(0);

            BigDecimal leftover = levelQty.subtract(soldFromLevel).max(BigDecimal.ZERO);
            BigDecimal dustTolerance = levelQty.multiply(new BigDecimal("0.01"));
            if (leftover.compareTo(dustTolerance) <= 0) {
                level.setStatus("CLOSED");
                level.setFilledQty(BigDecimal.ZERO);
                level.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
                level.setErrorMessage("CLOSED_GRID_RESIDUAL_CLEANUP orderId=" + orderId);
                closedCount++;
            } else {
                level.setStatus("SELL_PARTIAL");
                level.setFilledQty(leftover.setScale(8, RoundingMode.HALF_UP));
                level.setErrorMessage("CLOSED_GRID_RESIDUAL_CLEANUP_PARTIAL orderId=" + orderId);
            }
            gridLevelRepository.save(level);
            remainingSold = remainingSold.subtract(soldFromLevel).max(BigDecimal.ZERO);
        }

        grid.setTotalRealizedPnl(safe(grid.getTotalRealizedPnl()).add(totalAppliedPnl).setScale(8, RoundingMode.HALF_UP));
        grid.setClosedPairCount(nullToZero(grid.getClosedPairCount()) + closedCount);
        grid.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        gridRepository.save(grid);
        return totalAppliedPnl.setScale(8, RoundingMode.HALF_UP);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "Read-only Grid efficiency score. Quantifies capital efficiency, pair frequency, dust/stale penalty, " +
            "range alignment, and recommendation for active grids. No DB write and no order action. param: gridId optional.")
    public String getGridEfficiencyScore(Long gridId) {
        List<BtGrid> grids = gridId == null
                ? gridRepository.findByEnabledTrueAndClosedAtIsNull()
                : gridRepository.findById(gridId).stream().toList();
        if (grids.isEmpty()) {
            return gridId == null ? "ℹ️ 無 active grid。" : "❌ Grid #" + gridId + " 不存在";
        }

        StringBuilder sb = new StringBuilder("=== Grid Efficiency Score ===\n");
        sb.append("boundary: READ_ONLY report; no grid/order/fund behavior changed.\n\n");
        for (BtGrid grid : grids) {
            sb.append(renderGridEfficiency(grid)).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "Read-only Grid redesign plan. Explains whether to hold, resume, rebuild, or close a grid, " +
            "with capital and trigger rules. No DB write and no order action. param: gridId required")
    public String getGridRedesignPlan(Long gridId) {
        if (gridId == null) {
            return "❌ gridId required. Use listGrids first.";
        }
        BtGrid grid = gridRepository.findById(gridId).orElse(null);
        if (grid == null) {
            return "❌ Grid #" + gridId + " 不存在";
        }
        return renderGridRedesignPlan(grid);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "Read-only Grid trend adjustment review. Uses recent 1h/4h md_kline trend/ATR, current price alignment, " +
            "and grid efficiency evidence to recommend KEEP/PAUSE/WATCH/REBUILD_REVIEW/RESIZE_REVIEW. " +
            "No DB write, no order action, no scheduler/grid state change. params: gridId optional, symbol optional default BTCUSDT, lookbackHours optional default 72.")
    public String getGridTrendAdjustmentReview(Long gridId, String symbol, Integer lookbackHours) {
        String sym = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase();
        int hours = lookbackHours == null ? 72 : Math.max(24, Math.min(336, lookbackHours));
        int fourHourBars = Math.max(12, (int) Math.ceil(hours / 4.0));
        List<BtGrid> grids = gridId == null
                ? gridRepository.findByEnabledTrueAndClosedAtIsNull().stream()
                        .filter(g -> sym.equalsIgnoreCase(g.getSymbol()))
                        .toList()
                : gridRepository.findById(gridId).stream().toList();

        MarketTrendEvidence trend1h = loadGridTrendEvidence(sym, "1h", hours);
        MarketTrendEvidence trend4h = loadGridTrendEvidence(sym, "4h", fourHourBars);
        String alignment = trendAlignment(trend1h.direction(), trend4h.direction(), trend4h.bars());
        BigDecimal current = trend1h.currentPrice() != null ? trend1h.currentPrice() : trend4h.currentPrice();
        StringBuilder sb = new StringBuilder("=== Grid Trend Adjustment Review ===\n");
        sb.append("boundary=READ_ONLY; mutationAllowed=false; orderAllowed=false; ")
                .append("gridMutationAllowed=false; schedulerChangeAllowed=false; telegramSendAllowed=false\n");
        sb.append("purpose=operator review only; actions ending in _REVIEW are not execution authorization.\n\n");
        sb.append(String.format("market symbol=%s lookbackHours=%d trend=%s trendPct=%s atrPct=%s current=%s trendAlignment=%s%n",
                sym, hours, trend1h.direction(), fmtPctValue(trend1h.trendPct()),
                fmtPctValue(trend1h.atrPct()), fmtUsd(current), alignment));
        sb.append(String.format("trend1h=%s bars=%d source=%s trendPct=%s atrPct=%s latestBar=%s%n",
                trend1h.direction(), trend1h.bars(), trend1h.source(),
                fmtPctValue(trend1h.trendPct()), fmtPctValue(trend1h.atrPct()), trend1h.latestOpenTimeText()));
        sb.append(String.format("trend4h=%s bars=%d source=%s trendPct=%s atrPct=%s latestBar=%s%n%n",
                trend4h.direction(), trend4h.bars(), trend4h.source(),
                fmtPctValue(trend4h.trendPct()), fmtPctValue(trend4h.atrPct()), trend4h.latestOpenTimeText()));
        sb.append("decisionSet=KEEP,PAUSE,WATCH,REBUILD_REVIEW,RESIZE_REVIEW\n");
        sb.append("automationAllowed=false; closeGridAllowed=false; createGridAllowed=false; autoRebalanceAllowed=false\n");
        sb.append("activeGridCount=").append(grids.size()).append("\n\n");

        if (grids.isEmpty()) {
            String action = classifyGridTrendAdjustment("NO_GRID",
                    trend1h.direction(), trend1h.trendPct(), trend1h.atrPct(),
                    trend4h.direction(), trend4h.trendPct(), trend4h.atrPct(),
                    null, null, 0, trend1h.bars(), trend4h.bars(), 0, 0, 0);
            sb.append("recommendation=").append(action).append('\n');
            sb.append("reason=no active grid for symbol; trend review can only inform a future operator-created grid plan.\n");
            sb.append("decisionBlockers=[NO_ACTIVE_GRID]\n");
            sb.append("nextEvidence=operator must choose grid range/capital and run redesign/price-alignment review before any createGrid call.\n");
            return sb.toString();
        }

        for (BtGrid grid : grids) {
            List<BtGridLevel> levels = gridLevelRepository.findByGridId(grid.getId());
            GridEfficiencySnapshot s = gridEfficiencySnapshot(grid, levels);
            BigDecimal price = current != null ? current : s.currentPrice();
            RangePlacement placement = rangePlacement(grid, price);
            String action = classifyGridTrendAdjustment(
                    s.range(), trend1h.direction(), trend1h.trendPct(), trend1h.atrPct(),
                    trend4h.direction(), trend4h.trendPct(), trend4h.atrPct(),
                    placement.rangeWidthPct(), placement.pricePositionPct(),
                    s.materialFailed(), trend1h.bars(), trend4h.bars(), s.closedPairs(), s.ageDays(), s.score());
            sb.append(String.format("Grid #%d %s enabled=%s paused=%s closed=%s%n",
                    grid.getId(), grid.getSymbol(), grid.getEnabled(), grid.getPausedAt() != null, grid.getClosedAt() != null));
            sb.append(String.format("  range=%s lower=%s upper=%s pricePosition=%s rangeWidthPct=%s%n",
                    s.range(), fmtBd(grid.getPriceLower()), fmtBd(grid.getPriceUpper()),
                    fmtPctValue(placement.pricePositionPct()), fmtPctValue(placement.rangeWidthPct())));
            sb.append(String.format("  efficiencyScore=%d/100 pairs=%d pairsPerDay=%.3f pnlPerDay=%+.4f bpPerDay=%.2f%n",
                    s.score(), s.closedPairs(), s.pairsPerDay(), s.pnlPerDay(), s.capitalEfficiencyBpPerDay()));
            sb.append(String.format("  levels pending=%d holding=%d sellFailed=%d dustStale=%d materialFailed=%d%n",
                    s.pending(), s.holding(), s.sellFailed(), s.dustStale(), s.materialFailed()));
            sb.append("  recommendation=").append(action).append('\n');
            sb.append("  trendAlignment=").append(alignment).append('\n');
            sb.append("  decisionBlockers=").append(gridTrendDecisionBlockers(action, s, trend1h, trend4h, placement)).append('\n');
            sb.append("  candidatePlan=").append(trendAwareCandidateGridPlan(grid, price, trend1h, trend4h, action)).append('\n');
            sb.append("  rationale=").append(gridTrendRationale(action, s, trend1h, trend4h, placement)).append('\n');
            sb.append("  safeNextStep=").append(gridTrendSafeNextStep(action)).append("\n\n");
        }
        return sb.toString().stripTrailing();
    }

    private String renderGridEfficiency(BtGrid grid) {
        List<BtGridLevel> levels = gridLevelRepository.findByGridId(grid.getId());
        long ageDays = grid.getCreatedAt() == null
                ? 1
                : Math.max(1, Duration.between(grid.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).toDays());
        int closedPairs = grid.getClosedPairCount() == null ? 0 : grid.getClosedPairCount();
        BigDecimal realizedPnl = grid.getTotalRealizedPnl() == null ? BigDecimal.ZERO : grid.getTotalRealizedPnl();
        BigDecimal capacity = estimateConfiguredGridCapacity(grid, levels);
        BigDecimal activeCapital = estimateActiveGridCapital(grid, levels);
        double pnlPerDay = realizedPnl.doubleValue() / ageDays;
        double pairsPerDay = (double) closedPairs / ageDays;
        double capitalEfficiencyBpPerDay = activeCapital.signum() > 0
                ? pnlPerDay / activeCapital.doubleValue() * 10000.0
                : 0.0;

        List<BtGridLevel> failed = levels.stream()
                .filter(l -> "SELL_FAILED".equals(l.getStatus()))
                .toList();
        long dustStale = failed.stream().filter(this::isDustStaleSellFailure).count();
        long materialFailed = failed.size() - dustStale;
        long holding = levels.stream().filter(l -> "HOLDING".equals(l.getStatus())).count();
        long pending = levels.stream().filter(l -> "PENDING".equals(l.getStatus())).count();
        String range = gridRangeStatus(grid);

        int score = 50;
        score += Math.min(25, closedPairs * 3);
        score += Math.max(-20, Math.min(20, (int) Math.round(capitalEfficiencyBpPerDay * 2)));
        if (closedPairs == 0 && ageDays >= 7) score -= 15;
        score -= (int) dustStale * 5;
        score -= (int) materialFailed * 20;
        if (range.startsWith("OUT")) score -= 20;
        if (Boolean.TRUE.equals(grid.getAutoRebalance())
                && grid.getRebalanceCount() != null
                && grid.getMaxRebalanceCount() != null
                && grid.getRebalanceCount() >= grid.getMaxRebalanceCount() - 1) {
            score -= 10;
        }
        score = Math.max(0, Math.min(100, score));

        String recommendation = gridEfficiencyRecommendation(score, closedPairs, ageDays, materialFailed, dustStale, range);
        return String.format(
                "Grid #%d %s score=%d/100 recommendation=%s%n" +
                        "  age=%dd range=%s capitalActive=%s capacity=%s%n" +
                        "  realizedPnl=%+.4f USDT pnlPerDay=%+.4f pairs=%d pairsPerDay=%.3f%n" +
                        "  levels: pending=%d holding=%d sellFailed=%d dustStale=%d materialFailed=%d%n" +
                        "  efficiency=%.2f bp/day%n" +
                        "  operatorAction=%s%n",
                grid.getId(),
                grid.getSymbol(),
                score,
                recommendation,
                ageDays,
                range,
                activeCapital.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                capacity.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                realizedPnl.doubleValue(),
                pnlPerDay,
                closedPairs,
                pairsPerDay,
                pending,
                holding,
                failed.size(),
                dustStale,
                materialFailed,
                capitalEfficiencyBpPerDay,
                "READ_ONLY; do not increase grid capital until score and range/pair activity justify it");
    }

    private String renderGridRedesignPlan(BtGrid grid) {
        List<BtGridLevel> levels = gridLevelRepository.findByGridId(grid.getId());
        GridEfficiencySnapshot s = gridEfficiencySnapshot(grid, levels);
        String recommendation = gridEfficiencyRecommendation(
                s.score(), s.closedPairs(), s.ageDays(), s.materialFailed(), s.dustStale(), s.range());

        StringBuilder sb = new StringBuilder("=== Grid Redesign Plan ===\n");
        sb.append("boundary: READ_ONLY report; no grid/order/fund behavior changed.\n\n");
        sb.append(String.format("Grid #%d %s current=%s recommendation=%s%n",
                grid.getId(), grid.getSymbol(), s.currentPriceText(), recommendation));
        sb.append(String.format("score=%d/100 age=%dd range=%s pairs=%d pairsPerDay=%.3f pnl=%+.4f pnlPerDay=%+.4f efficiency=%.2f bp/day%n",
                s.score(), s.ageDays(), s.range(), s.closedPairs(), s.pairsPerDay(),
                s.realizedPnl().doubleValue(), s.pnlPerDay(), s.capitalEfficiencyBpPerDay()));
        sb.append(String.format("levels: pending=%d holding=%d sellFailed=%d dustStale=%d materialFailed=%d capacity=%s activeCapital=%s%n%n",
                s.pending(), s.holding(), s.sellFailed(), s.dustStale(), s.materialFailed(),
                s.capacity().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                s.activeCapital().setScale(2, RoundingMode.HALF_UP).toPlainString()));

        sb.append("Decision:\n");
        if (s.materialFailed() > 0) {
            sb.append("- REVIEW_FAILURE_FIRST: material sell failures exist; do not resume or add capital until failure reason is fixed.\n");
        } else if (s.closedPairs() == 0 && s.ageDays() >= 7) {
            sb.append("- REDESIGN_BEFORE_CAPITAL: old grid has no completed pairs; capital is not cycling enough.\n");
        } else if (s.range().startsWith("OUT")) {
            sb.append("- WAIT_OR_REBUILD_RANGE: price is outside range; current grid geometry is stale.\n");
        } else if (s.score() >= 75 && s.dustStale() == 0) {
            sb.append("- HOLD_AND_MONITOR: grid is efficient enough to consider future capital increase, but not from this report alone.\n");
        } else {
            sb.append("- HOLD_MONITOR: keep current reserved capital only; revisit after more completed pairs.\n");
        }

        sb.append("\nDo now:\n");
        sb.append("- Keep per-level capital unchanged; do not increase Grid funds while score < 55 or pairsPerDay < 0.20.\n");
        sb.append("- Keep free USDT reserve for existing grid only; do not move it to Earn if auto-resume is expected.\n");
        if (s.dustStale() > 0) {
            sb.append("- Treat dust SELL_FAILED as accounting/cleanup noise, not a reason to chase a sell; estimated notional is below min order.\n");
        }
        if (grid.getPausedAt() != null) {
            sb.append("- Grid is paused by regime gate; resume should be automatic only when regime enters whitelist ")
                    .append(grid.getRegimeWhitelist()).append(".\n");
        }

        sb.append("\nResume conditions:\n");
        sb.append("- Regime is in whitelist and price remains inside range.\n");
        sb.append("- No materialFailed sell levels.\n");
        sb.append("- OCO/open position risk remains protected; no manual exposure increase from TG.\n");

        sb.append("\nRebuild template if current grid remains low-efficiency:\n");
        sb.append("- Trigger: score < 55 for 24h OR no closed pairs for 10d OR range exits while grid is paused.\n");
        sb.append("- Use smaller fresh range around current realized volatility; keep perLevelUsdt >= ")
                .append(gridProperties.minSellNotionalUsdt().toPlainString())
                .append(" and avoid dust-producing levels.\n");
        sb.append("- Candidate range: ").append(candidateGridRange(grid, s.currentPrice()))
                .append(" (diagnostic only; createGrid still requires explicit operator approval).\n");
        sb.append("- Close/rebuild only through MCP/operator path, never TG button.\n");

        sb.append("\nStop conditions:\n");
        sb.append("- materialFailed > 0, repeated stop-out, or efficiency stays under 2 bp/day after another completed pair window.\n");
        sb.append("- If current position/OCO risk changes, review portfolio first before grid changes.\n");
        return sb.toString();
    }

    private GridEfficiencySnapshot gridEfficiencySnapshot(BtGrid grid, List<BtGridLevel> levels) {
        long ageDays = grid.getCreatedAt() == null
                ? 1
                : Math.max(1, Duration.between(grid.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).toDays());
        int closedPairs = grid.getClosedPairCount() == null ? 0 : grid.getClosedPairCount();
        BigDecimal realizedPnl = grid.getTotalRealizedPnl() == null ? BigDecimal.ZERO : grid.getTotalRealizedPnl();
        BigDecimal capacity = estimateConfiguredGridCapacity(grid, levels);
        BigDecimal activeCapital = estimateActiveGridCapital(grid, levels);
        double pnlPerDay = realizedPnl.doubleValue() / ageDays;
        double pairsPerDay = (double) closedPairs / ageDays;
        double capitalEfficiencyBpPerDay = activeCapital.signum() > 0
                ? pnlPerDay / activeCapital.doubleValue() * 10000.0
                : 0.0;
        List<BtGridLevel> failed = levels.stream()
                .filter(l -> "SELL_FAILED".equals(l.getStatus()))
                .toList();
        long dustStale = failed.stream().filter(this::isDustStaleSellFailure).count();
        long materialFailed = failed.size() - dustStale;
        long holding = levels.stream().filter(l -> "HOLDING".equals(l.getStatus())).count();
        long pending = levels.stream().filter(l -> "PENDING".equals(l.getStatus())).count();
        BigDecimal currentPrice = lastPriceOrNull(grid.getSymbol());
        String range = gridRangeStatus(grid, currentPrice);

        int score = 50;
        score += Math.min(25, closedPairs * 3);
        score += Math.max(-20, Math.min(20, (int) Math.round(capitalEfficiencyBpPerDay * 2)));
        if (closedPairs == 0 && ageDays >= 7) score -= 15;
        score -= (int) dustStale * 5;
        score -= (int) materialFailed * 20;
        if (range.startsWith("OUT")) score -= 20;
        if (Boolean.TRUE.equals(grid.getAutoRebalance())
                && grid.getRebalanceCount() != null
                && grid.getMaxRebalanceCount() != null
                && grid.getRebalanceCount() >= grid.getMaxRebalanceCount() - 1) {
            score -= 10;
        }
        score = Math.max(0, Math.min(100, score));
        return new GridEfficiencySnapshot(ageDays, closedPairs, realizedPnl, capacity, activeCapital,
                pnlPerDay, pairsPerDay, capitalEfficiencyBpPerDay, failed.size(), dustStale, materialFailed,
                holding, pending, range, currentPrice, score);
    }

    private BigDecimal estimateActiveGridCapital(BtGrid grid, List<BtGridLevel> levels) {
        BigDecimal perLevel = safe(grid.getPerLevelUsdt());
        BigDecimal capital = BigDecimal.ZERO;
        for (BtGridLevel level : levels) {
            String status = level.getStatus();
            if ("PENDING".equals(status) || "HOLDING".equals(status)
                    || "SELL_FAILED".equals(status) || "SELL_PARTIAL".equals(status)) {
                capital = capital.add(perLevel);
            }
        }
        return capital;
    }

    private BigDecimal estimateConfiguredGridCapacity(BtGrid grid, List<BtGridLevel> levels) {
        return safe(grid.getPerLevelUsdt()).multiply(BigDecimal.valueOf(configuredBuyLevelCount(grid, levels)));
    }

    private int configuredBuyLevelCount(BtGrid grid, List<BtGridLevel> levels) {
        if (levels != null && !levels.isEmpty()) {
            return levels.size();
        }
        return Math.max(0, nullToZero(grid.getGridCount()) - 1);
    }

    private boolean isDustStaleSellFailure(BtGridLevel level) {
        int retry = level.getRetryCount() == null ? 0 : level.getRetryCount();
        if (retry < 3) return false;
        BigDecimal notional = level.getFilledQty() != null && level.getPairedSellPrice() != null
                ? level.getFilledQty().multiply(level.getPairedSellPrice()).setScale(8, RoundingMode.HALF_UP)
                : null;
        return notional != null && notional.compareTo(gridProperties.minSellNotionalUsdt()) < 0;
    }

    private String gridRangeStatus(BtGrid grid) {
        return gridRangeStatus(grid, lastPriceOrNull(grid.getSymbol()));
    }

    private String gridRangeStatus(BtGrid grid, BigDecimal price) {
        if (price == null) return "UNKNOWN";
        if (price.compareTo(grid.getPriceLower()) < 0) return "OUT_BELOW";
        if (price.compareTo(grid.getPriceUpper()) > 0) return "OUT_ABOVE";
        return "IN_RANGE";
    }

    private BigDecimal lastPriceOrNull(String symbol) {
        try {
            return okxTradingService.getLastPrice(symbol);
        } catch (Exception e) {
            return null;
        }
    }

    private String candidateGridRange(BtGrid grid, BigDecimal currentPrice) {
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return "UNKNOWN_CURRENT_PRICE";
        }
        BigDecimal halfWidth = new BigDecimal("0.035");
        BigDecimal lower = currentPrice.multiply(BigDecimal.ONE.subtract(halfWidth))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = currentPrice.multiply(BigDecimal.ONE.add(halfWidth))
                .setScale(2, RoundingMode.HALF_UP);
        int gridCount = grid.getGridCount() == null ? 8 : Math.max(4, Math.min(12, grid.getGridCount()));
        BigDecimal perLevel = safe(grid.getPerLevelUsdt()).max(gridProperties.minSellNotionalUsdt());
        return String.format("%s %s~%s, gridCount=%d, perLevelUsdt=%s",
                grid.getSymbol(), lower.toPlainString(), upper.toPlainString(),
                gridCount, perLevel.toPlainString());
    }

    private String trendAwareCandidateGridPlan(BtGrid grid, BigDecimal currentPrice,
                                               MarketTrendEvidence trend1h,
                                               MarketTrendEvidence trend4h,
                                               String action) {
        if (!"REBUILD_REVIEW".equals(action) && !"RESIZE_REVIEW".equals(action)) {
            return "NO_RANGE_CHANGE_PREVIEW; decision=" + action;
        }
        if (currentPrice == null || currentPrice.signum() <= 0) {
            return "UNKNOWN_CURRENT_PRICE";
        }
        BigDecimal baseAtrPct = trend1h.atrPct() != null ? trend1h.atrPct() : new BigDecimal("0.75");
        BigDecimal widthPct = baseAtrPct.multiply(new BigDecimal("8"))
                .max(new BigDecimal("4.00"))
                .min(new BigDecimal("14.00"));
        String alignment = trendAlignment(trend1h.direction(), trend4h.direction(), trend4h.bars());
        BigDecimal lowerShare = new BigDecimal("0.50");
        BigDecimal upperShare = new BigDecimal("0.50");
        if ("UP_CONFIRMED".equals(alignment) || "UP_FORMING".equals(alignment)) {
            lowerShare = new BigDecimal("0.35");
            upperShare = new BigDecimal("0.65");
        } else if ("DOWN_CONFIRMED".equals(alignment) || "DOWN_FORMING".equals(alignment)) {
            lowerShare = new BigDecimal("0.65");
            upperShare = new BigDecimal("0.35");
        }

        BigDecimal lowerOffset = widthPct.multiply(lowerShare)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal upperOffset = widthPct.multiply(upperShare)
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
        BigDecimal lower = currentPrice.multiply(BigDecimal.ONE.subtract(lowerOffset))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal upper = currentPrice.multiply(BigDecimal.ONE.add(upperOffset))
                .setScale(2, RoundingMode.HALF_UP);
        int priceLines = grid.getGridCount() == null ? 8 : Math.max(4, Math.min(12, grid.getGridCount()));
        BigDecimal perLevel = safe(grid.getPerLevelUsdt()).max(gridProperties.minSellNotionalUsdt());
        return String.format("PREVIEW_ONLY symbol=%s lower=%s upper=%s priceLines=%d buyLevels=%d perLevelUsdt=%s capital=%s widthPct=%s alignment=%s basedOn=1hAtrPct",
                grid.getSymbol(), lower.toPlainString(), upper.toPlainString(), priceLines,
                Math.max(0, priceLines - 1), perLevel.toPlainString(),
                estimateCreateGridCapital(perLevel, priceLines).toPlainString(),
                widthPct.setScale(2, RoundingMode.HALF_UP).toPlainString(), alignment);
    }

    private MarketTrendEvidence loadGridTrendEvidence(String symbol, String intervalCode, int requestedBars) {
        List<MdKline> bars = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, intervalCode, "okx", PageRequest.of(0, requestedBars));
        String source = "md_kline:okx";
        if (bars == null || bars.isEmpty()) {
            bars = klineRepository.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    symbol, intervalCode, PageRequest.of(0, requestedBars));
            source = "md_kline:any";
        }
        if (bars == null || bars.isEmpty()) {
            BigDecimal current = lastPriceOrNull(symbol);
            return new MarketTrendEvidence(intervalCode, 0, source, "INSUFFICIENT_DATA", null, null, current, null);
        }
        Collections.reverse(bars);
        MdKline first = bars.get(0);
        MdKline last = bars.get(bars.size() - 1);
        BigDecimal firstClose = first.getClosePrice();
        BigDecimal lastClose = last.getClosePrice();
        BigDecimal trendPct = pctChange(firstClose, lastClose);
        BigDecimal atrPct = averageRangePct(bars, Math.min(24, bars.size()));
        BigDecimal current = lastPriceOrNull(symbol);
        if (current == null) current = lastClose;
        String direction = trendDirection(trendPct);
        return new MarketTrendEvidence(intervalCode, bars.size(), source, direction, trendPct, atrPct, current, last.getOpenTime());
    }

    private static BigDecimal pctChange(BigDecimal from, BigDecimal to) {
        if (from == null || to == null || from.signum() == 0) return null;
        return to.subtract(from).divide(from, 8, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private static BigDecimal averageRangePct(List<MdKline> bars, int count) {
        if (bars == null || bars.isEmpty() || count <= 0) return null;
        int start = Math.max(0, bars.size() - count);
        BigDecimal sum = BigDecimal.ZERO;
        int n = 0;
        for (int i = start; i < bars.size(); i++) {
            MdKline k = bars.get(i);
            if (k.getHighPrice() == null || k.getLowPrice() == null
                    || k.getClosePrice() == null || k.getClosePrice().signum() == 0) {
                continue;
            }
            BigDecimal rangePct = k.getHighPrice().subtract(k.getLowPrice())
                    .divide(k.getClosePrice(), 8, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            sum = sum.add(rangePct);
            n++;
        }
        return n == 0 ? null : sum.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);
    }

    static String trendDirection(BigDecimal trendPct) {
        if (trendPct == null) return "INSUFFICIENT_DATA";
        if (trendPct.compareTo(new BigDecimal("3.0")) >= 0) return "UP_STRONG";
        if (trendPct.compareTo(new BigDecimal("1.0")) >= 0) return "UP";
        if (trendPct.compareTo(new BigDecimal("-3.0")) <= 0) return "DOWN_STRONG";
        if (trendPct.compareTo(new BigDecimal("-1.0")) <= 0) return "DOWN";
        return "SIDEWAYS";
    }

    static String trendBias(String trendDirection) {
        if (trendDirection == null || trendDirection.isBlank() || "INSUFFICIENT_DATA".equals(trendDirection)) {
            return "UNKNOWN";
        }
        if (trendDirection.startsWith("UP")) return "UP";
        if (trendDirection.startsWith("DOWN")) return "DOWN";
        return "SIDEWAYS";
    }

    static String trendAlignment(String trend1hDirection, String trend4hDirection, int trend4hBars) {
        String oneHour = trendBias(trend1hDirection);
        String fourHour = trend4hBars < 12 ? "UNKNOWN" : trendBias(trend4hDirection);
        if ("UNKNOWN".equals(oneHour) && "UNKNOWN".equals(fourHour)) return "INSUFFICIENT_DATA";
        if ("UNKNOWN".equals(fourHour)) return oneHour + "_UNCONFIRMED_4H";
        if ("UP".equals(oneHour) && "UP".equals(fourHour)) return "UP_CONFIRMED";
        if ("DOWN".equals(oneHour) && "DOWN".equals(fourHour)) return "DOWN_CONFIRMED";
        if ("UP".equals(oneHour) && "SIDEWAYS".equals(fourHour)) return "UP_FORMING";
        if ("DOWN".equals(oneHour) && "SIDEWAYS".equals(fourHour)) return "DOWN_FORMING";
        if ("SIDEWAYS".equals(oneHour) && "UP".equals(fourHour)) return "UP_COOLING";
        if ("SIDEWAYS".equals(oneHour) && "DOWN".equals(fourHour)) return "DOWN_COOLING";
        if ("SIDEWAYS".equals(oneHour) && "SIDEWAYS".equals(fourHour)) return "SIDEWAYS";
        return "MIXED";
    }

    private RangePlacement rangePlacement(BtGrid grid, BigDecimal price) {
        BigDecimal lower = grid.getPriceLower();
        BigDecimal upper = grid.getPriceUpper();
        if (price == null || lower == null || upper == null || price.signum() == 0
                || upper.compareTo(lower) <= 0) {
            return new RangePlacement(null, null);
        }
        BigDecimal width = upper.subtract(lower);
        BigDecimal rangeWidthPct = width.divide(price, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        BigDecimal pricePositionPct = price.subtract(lower).divide(width, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        return new RangePlacement(rangeWidthPct, pricePositionPct);
    }

    static String classifyGridTrendAdjustment(String range, String trendDirection, BigDecimal trendPct,
                                              BigDecimal atrPct, BigDecimal rangeWidthPct,
                                              BigDecimal pricePositionPct, long materialFailed,
                                              int bars, int closedPairs, long ageDays) {
        return classifyGridTrendAdjustment(range, trendDirection, trendPct, atrPct,
                trendDirection, trendPct, atrPct, rangeWidthPct, pricePositionPct,
                materialFailed, bars, bars, closedPairs, ageDays, 50);
    }

    static String classifyGridTrendAdjustment(String range,
                                              String trend1hDirection, BigDecimal trend1hPct, BigDecimal atr1hPct,
                                              String trend4hDirection, BigDecimal trend4hPct, BigDecimal atr4hPct,
                                              BigDecimal rangeWidthPct, BigDecimal pricePositionPct,
                                              long materialFailed, int bars1h, int bars4h,
                                              int closedPairs, long ageDays, int efficiencyScore) {
        if (materialFailed > 0) return "PAUSE";
        if ("NO_GRID".equals(range)) return "WATCH";
        if (bars1h < 24 || trend1hPct == null || atr1hPct == null) return "WATCH";

        String alignment = trendAlignment(trend1hDirection, trend4hDirection, bars4h);
        boolean strongUp = "UP_STRONG".equals(trend1hDirection);
        boolean strongDown = "DOWN_STRONG".equals(trend1hDirection);
        boolean upConfirmed = "UP_CONFIRMED".equals(alignment);
        boolean downConfirmed = "DOWN_CONFIRMED".equals(alignment);
        boolean upForming = "UP_FORMING".equals(alignment);
        boolean downForming = "DOWN_FORMING".equals(alignment);
        boolean mixed = "MIXED".equals(alignment);
        boolean highVol = atr1hPct.compareTo(new BigDecimal("1.20")) >= 0
                || (atr4hPct != null && atr4hPct.compareTo(new BigDecimal("2.40")) >= 0);
        boolean lowVol = atr1hPct.compareTo(new BigDecimal("0.35")) <= 0;
        boolean narrowRange = rangeWidthPct != null
                && rangeWidthPct.compareTo(atr1hPct.multiply(BigDecimal.valueOf(4))) < 0;
        boolean wideRange = rangeWidthPct != null
                && rangeWidthPct.compareTo(atr1hPct.multiply(BigDecimal.valueOf(18))) > 0;
        boolean nearUpper = pricePositionPct != null && pricePositionPct.compareTo(new BigDecimal("85")) >= 0;
        boolean nearLower = pricePositionPct != null && pricePositionPct.compareTo(new BigDecimal("15")) <= 0;
        boolean lowTurnover = closedPairs == 0 && ageDays >= 3;

        if ("OUT_ABOVE".equals(range)) return (upConfirmed || (strongUp && upForming)) ? "REBUILD_REVIEW" : "WATCH";
        if ("OUT_BELOW".equals(range)) return (downConfirmed || (strongDown && downForming)) ? "REBUILD_REVIEW" : "WATCH";
        if ((upConfirmed && nearUpper) || (downConfirmed && nearLower)) return "PAUSE";
        if (mixed && (strongUp || strongDown)) return "WATCH";
        if (highVol || narrowRange) return "RESIZE_REVIEW";
        if (wideRange && (lowVol || lowTurnover || efficiencyScore < 55)) return "RESIZE_REVIEW";
        if ((strongUp && nearUpper) || (strongDown && nearLower)) return "WATCH";
        return "KEEP";
    }

    private String gridTrendDecisionBlockers(String action, GridEfficiencySnapshot s,
                                             MarketTrendEvidence trend1h,
                                             MarketTrendEvidence trend4h,
                                             RangePlacement placement) {
        List<String> blockers = new ArrayList<>();
        if (s.materialFailed() > 0) blockers.add("MATERIAL_GRID_FAILURE");
        if (trend1h.bars() < 24 || trend1h.trendPct() == null || trend1h.atrPct() == null) blockers.add("INSUFFICIENT_1H_EVIDENCE");
        if (trend4h.bars() < 12 || trend4h.trendPct() == null) blockers.add("INSUFFICIENT_4H_CONFIRMATION");
        if ("MIXED".equals(trendAlignment(trend1h.direction(), trend4h.direction(), trend4h.bars()))) blockers.add("MIXED_1H_4H_TREND");
        if ("PAUSE".equals(action)) blockers.add("GRID_SHOULD_NOT_ADD_RISK");
        if ("WATCH".equals(action)) blockers.add("REVIEW_ONLY_NO_EXECUTION_SIGNAL");
        if ("REBUILD_REVIEW".equals(action) || "RESIZE_REVIEW".equals(action)) blockers.add("SEPARATE_OPERATOR_APPROVAL_REQUIRED");
        if (placement.pricePositionPct() == null) blockers.add("UNKNOWN_RANGE_POSITION");
        return blockers.isEmpty() ? "[]" : blockers.toString();
    }

    private String gridTrendRationale(String action, GridEfficiencySnapshot s,
                                      MarketTrendEvidence trend1h,
                                      MarketTrendEvidence trend4h,
                                      RangePlacement placement) {
        return switch (action) {
            case "PAUSE" -> "material failure or confirmed directional boundary pressure means the grid should not add risk without operator review.";
            case "WATCH" -> "trend evidence is missing, mixed, outside-range but unconfirmed, or near-boundary without enough 4h confirmation.";
            case "REBUILD_REVIEW" -> "price is outside range and 1h/4h trend alignment supports a fresh range review; this is not execution authorization.";
            case "RESIZE_REVIEW" -> "volatility/range/efficiency evidence suggests the grid width is mis-sized for current conditions.";
            default -> String.format("trend1h=%s trend4h=%s alignment=%s atr1h=%s pricePosition=%s score=%d; no adjustment trigger exceeded.",
                    trend1h.direction(), trend4h.direction(),
                    trendAlignment(trend1h.direction(), trend4h.direction(), trend4h.bars()),
                    fmtPctValue(trend1h.atrPct()), fmtPctValue(placement.pricePositionPct()), s.score());
        };
    }

    private String gridTrendSafeNextStep(String action) {
        return switch (action) {
            case "REBUILD_REVIEW", "RESIZE_REVIEW" ->
                    "prepare operator packet with candidate range/capital; require separate approval before closeGrid/createGrid.";
            case "PAUSE" ->
                    "continue read-only monitoring; require separate approval before pause/resume/close.";
            case "WATCH" ->
                    "collect more 1h/4h evidence or wait for range/efficiency change; no grid mutation from this review.";
            default -> "KEEP current grid policy unchanged and rerun after next completed pair window.";
        };
    }

    private String gridEfficiencyRecommendation(int score, int closedPairs, long ageDays,
                                                long materialFailed, long dustStale, String range) {
        if (materialFailed > 0) return "REVIEW_FAILURE_BEFORE_CAPITAL";
        if (range.startsWith("OUT")) return "REVIEW_RANGE_BEFORE_CAPITAL";
        if (closedPairs == 0 && ageDays >= 7) return "HOLD_OR_REDESIGN_NO_CAPITAL_INCREASE";
        if (score >= 75 && dustStale == 0) return "CANDIDATE_FOR_CAPITAL_INCREASE";
        if (score >= 55) return "HOLD_MONITOR";
        return "LOW_EFFICIENCY_REVIEW";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    @Tool(description = "DEPRECATED custom Grid resume path. Disabled by default during OKX native Spot Grid migration. " +
            "Legacy grids remain queryable and may be closed through a separately authorized retirement action. param: gridId")
    @Deprecated(forRemoval = true)
    public String resumeGrid(Long gridId) {
        if (!gridProperties.customCreateResumeEnabled()) {
            return "BLOCKED_DEPRECATED_CUSTOM_GRID_RESUME_USE_OKX_NATIVE";
        }
        if (gridId == null) return "❌ gridId 不可為空";
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";
        if (g.getClosedAt() != null) return "❌ Grid #" + gridId + " 已關閉無法恢復";
        if (g.getPausedAt() == null) return "ℹ️ Grid #" + gridId + " 本來就未暫停";
        g.setPausedAt(null);
        g.setPausedReason(null);
        g.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(g);
        log.info("[MCP] resumeGrid id={}", gridId);
        return String.format("▶ Grid #%d %s 已恢復執行", gridId, g.getSymbol());
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#246 Grid 與當前價格對齊分析：顯示每個 ACTIVE Grid 的當前價格位置。" +
            "若價格在 Grid 範圍之外，顯示距離最近掛單的差距，並建議調整或暫停。")
    public String getGridPriceAlignment() {
        List<BtGrid> grids = gridRepository.findAll().stream()
                .filter(g -> Boolean.TRUE.equals(g.getEnabled()) && g.getPausedAt() == null && g.getClosedAt() == null).toList();
        if (grids.isEmpty()) return "ℹ️ 無 ACTIVE Grid";

        StringBuilder sb = new StringBuilder("=== Grid Price Alignment ===\n\n");
        for (BtGrid g : grids) {
            BigDecimal currentPrice = okxTradingService.getLastPrice(g.getSymbol());
            if (currentPrice == null) { sb.append(String.format("Grid #%d: 無法取得 %s 當前價格\n", g.getId(), g.getSymbol())); continue; }
            BigDecimal lower = g.getPriceLower(), upper = g.getPriceUpper();
            boolean inRange = currentPrice.compareTo(lower) >= 0 && currentPrice.compareTo(upper) <= 0;
            String rangeStatus;
            String suggestion;
            if (inRange) {
                rangeStatus = "✅ IN RANGE";
                double distToLower = currentPrice.subtract(lower).divide(currentPrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
                double distToUpper = upper.subtract(currentPrice).divide(currentPrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
                suggestion = String.format("距下界 %.1f%%，距上界 %.1f%%", distToLower, distToUpper);
            } else if (currentPrice.compareTo(lower) < 0) {
                rangeStatus = "❌ BELOW 下界";
                double distPct = lower.subtract(currentPrice).divide(currentPrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
                suggestion = String.format("低於下界 %.1f%% → 建議下移 Grid 範圍或等待價格回升", distPct);
            } else {
                rangeStatus = "❌ ABOVE 上界";
                double distPct = currentPrice.subtract(upper).divide(currentPrice, 4, java.math.RoundingMode.HALF_UP).doubleValue() * 100;
                suggestion = String.format("高於上界 %.1f%% → Grid 已 stopOut 風險，確認 stopOut 設定", distPct);
            }
            sb.append(String.format("Grid #%d %s [%s~%s]\n  當前: $%.2f  %s\n  → %s\n\n",
                    g.getId(), g.getSymbol(), fmtBd(lower), fmtBd(upper),
                    currentPrice.doubleValue(), rangeStatus, suggestion));
        }
        return sb.toString();
    }

    private static String fmtBd(BigDecimal bd) {
        return bd != null ? bd.stripTrailingZeros().toPlainString() : "-";
    }

    private static String fmtUsd(BigDecimal bd) {
        return bd != null ? "$" + bd.setScale(2, RoundingMode.HALF_UP).toPlainString() : "N/A";
    }

    private static String fmtSignedUsd(BigDecimal bd) {
        if (bd == null) return "N/A";
        return (bd.signum() >= 0 ? "+$" : "-$") + bd.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmtPct(BigDecimal pct) {
        if (pct == null) return "N/A";
        return (pct.signum() >= 0 ? "+" : "") + pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String fmtPctValue(BigDecimal pct) {
        if (pct == null) return "N/A";
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private record GridEfficiencySnapshot(
            long ageDays,
            int closedPairs,
            BigDecimal realizedPnl,
            BigDecimal capacity,
            BigDecimal activeCapital,
            double pnlPerDay,
            double pairsPerDay,
            double capitalEfficiencyBpPerDay,
            long sellFailed,
            long dustStale,
            long materialFailed,
            long holding,
            long pending,
            String range,
            BigDecimal currentPrice,
            int score
    ) {
        private String currentPriceText() {
            return currentPrice == null ? "UNKNOWN" : currentPrice.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
    }

    private record MarketTrendEvidence(
            String intervalCode,
            int bars,
            String source,
            String direction,
            BigDecimal trendPct,
            BigDecimal atrPct,
            BigDecimal currentPrice,
            LocalDateTime latestOpenTime
    ) {
        private String latestOpenTimeText() {
            return latestOpenTime == null ? "N/A" : latestOpenTime.toString();
        }
    }

    private record RangePlacement(
            BigDecimal rangeWidthPct,
            BigDecimal pricePositionPct
    ) {
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
