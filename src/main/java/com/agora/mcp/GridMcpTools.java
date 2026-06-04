package com.agora.mcp;

import com.agora.config.properties.TradingGridProperties;
import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.trading.CapitalAllocationPolicyPreviewService;
import com.agora.service.trading.OkxTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final OkxTradingService okxTradingService;
    private final TradingGridProperties gridProperties;
    private final CapitalAllocationPolicyPreviewService capitalAllocationPolicyPreviewService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "建立網格交易(grid)。N 個 level 等距鋪在 [priceLower, priceUpper] 區間,每格" +
            "perLevelUsdt 金額。價格觸某 level → market buy,之後漲到 pairedSellPrice(filled + step)→ market sell。" +
            "stopOutPct 預設 0.03(3%,區間外 3% 觸發全平);hintGated=true(預設)受 Gemini advisor regime 白名單控管。" +
            "param: symbol, priceLower, priceUpper, gridCount(2-50), perLevelUsdt(≥5)," +
            "stopOutPct(選填,預設 0.03), regimeWhitelist(預設 'SIDEWAYS,VOLATILE,RECOVERY')")
    public String createGrid(String symbol, BigDecimal priceLower, BigDecimal priceUpper,
                              Integer gridCount, BigDecimal perLevelUsdt,
                              BigDecimal stopOutPct, String regimeWhitelist) {
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

        // 建 N 個 level(等距)
        BigDecimal step = priceUpper.subtract(priceLower)
                .divide(BigDecimal.valueOf(gridCount - 1L), 8, RoundingMode.HALF_UP);
        List<BtGridLevel> levels = new ArrayList<>();
        for (int i = 0; i < gridCount; i++) {
            BtGridLevel level = new BtGridLevel();
            level.setGridId(saved.getId());
            level.setLevelIndex(i);
            level.setPrice(priceLower.add(step.multiply(BigDecimal.valueOf(i)))
                    .setScale(8, RoundingMode.HALF_UP));
            level.setStatus("PENDING");
            level.setCreatedAt(now);
            levels.add(level);
        }
        gridLevelRepository.saveAll(levels);

        log.info("[MCP] createGrid id={} {} range=[{}, {}] count={} perLevel={} step={}",
                saved.getId(), symbol, priceLower, priceUpper, gridCount, perLevelUsdt, step);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("✅ Grid #%d 建立成功%n", saved.getId()));
        sb.append(String.format("  %s 區間: %s ~ %s  格數: %d  每格: %s USDT  step: %s%n",
                symbol, priceLower.toPlainString(), priceUpper.toPlainString(),
                gridCount, perLevelUsdt.toPlainString(), step.toPlainString()));
        sb.append(String.format("  Stop-out: %s%%  Hint-gated: ON (%s)%n",
                grid.getStopOutPct().multiply(BigDecimal.valueOf(100)).toPlainString(),
                grid.getRegimeWhitelist()));
        sb.append(String.format("  總資金需求: ~%s USDT(所有 level 若全填 FILLED)%n",
                perLevelUsdt.multiply(BigDecimal.valueOf(gridCount)).toPlainString()));
        sb.append("\n下一步:scheduler 每 5 分鐘自動檢查。用 listGrids 看狀態,closeGrid 手動停。");
        return sb.toString();
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
            BigDecimal gridCapacity = g.getPerLevelUsdt().multiply(BigDecimal.valueOf(g.getGridCount()));
            gridMaxExposure = gridMaxExposure.add(gridCapacity);
            List<BtGridLevel> holdings_ = gridLevelRepository
                    .findByGridIdAndStatusIn(g.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
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
            sb.append(String.format("     容量: $%.2f (%d 格×$%.2f)  持倉: $%.2f (%d level)%n",
                    gridCapacity.doubleValue(), g.getGridCount(), g.getPerLevelUsdt().doubleValue(),
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
    @Tool(description = "關閉 grid:平掉所有 HOLDING + SELL_FAILED + SELL_PARTIAL 倉位 + 設 enabled=false + 記 closed_at。" +
            "不可逆;若只是暫時停請用 pauseGrid。回傳總 PnL 摘要。")
    public String closeGrid(Long gridId) {
        if (gridId == null) return "❌ gridId 不可為空";
        BtGrid g = gridRepository.findById(gridId).orElse(null);
        if (g == null) return "❌ Grid #" + gridId + " 不存在";
        if (g.getClosedAt() != null) return "❌ Grid #" + gridId + " 已於 " + g.getClosedAt() + " 關閉";

        List<BtGridLevel> filled = gridLevelRepository.findByGridIdAndStatusIn(
                gridId, List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
        int closedCount = 0;
        int failCount = 0;
        BigDecimal totalClose = BigDecimal.ZERO;
        BigDecimal mark;
        try {
            mark = okxTradingService.getLastPrice(g.getSymbol());
        } catch (Exception e) {
            mark = null;
        }
        for (BtGridLevel level : filled) {
            try {
                okxTradingService.placeMarketSell(g.getSymbol(), level.getFilledQty());
                BigDecimal pnl = (mark != null)
                        ? mark.subtract(level.getFilledPrice()).multiply(level.getFilledQty())
                              .setScale(8, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                level.setStatus("CLOSED");
                level.setRealizedPnl(pnl);
                level.setClosedAt(LocalDateTime.now());
                gridLevelRepository.save(level);
                g.setTotalRealizedPnl(g.getTotalRealizedPnl().add(pnl));
                totalClose = totalClose.add(pnl);
                closedCount++;
            } catch (Exception e) {
                log.error("[MCP closeGrid] level={} sell failed: {}", level.getLevelIndex(), e.getMessage());
                failCount++;
            }
        }
        g.setEnabled(false);
        g.setClosedAt(LocalDateTime.now());
        g.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(g);
        log.info("[MCP] closeGrid id={} closed {} levels, failed {}, pnl {}",
                gridId, closedCount, failCount, totalClose);

        return String.format(
                "🔴 Grid #%d %s 已關閉%n  平倉 level: %d(失敗 %d)%n  此次 PnL: %+.4f%n  總累計 PnL: %+.4f USDT%n  完成對數: %d",
                gridId, g.getSymbol(), closedCount, failCount,
                totalClose.doubleValue(), g.getTotalRealizedPnl().doubleValue(),
                g.getClosedPairCount());
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

    private String renderGridEfficiency(BtGrid grid) {
        List<BtGridLevel> levels = gridLevelRepository.findByGridId(grid.getId());
        long ageDays = grid.getCreatedAt() == null
                ? 1
                : Math.max(1, Duration.between(grid.getCreatedAt(), LocalDateTime.now(ZoneOffset.UTC)).toDays());
        int closedPairs = grid.getClosedPairCount() == null ? 0 : grid.getClosedPairCount();
        BigDecimal realizedPnl = grid.getTotalRealizedPnl() == null ? BigDecimal.ZERO : grid.getTotalRealizedPnl();
        BigDecimal capacity = safe(grid.getPerLevelUsdt()).multiply(BigDecimal.valueOf(nullToZero(grid.getGridCount())));
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
        BigDecimal capacity = safe(grid.getPerLevelUsdt()).multiply(BigDecimal.valueOf(nullToZero(grid.getGridCount())));
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
    @Tool(description = "恢復暫停中的 grid。param: gridId")
    public String resumeGrid(Long gridId) {
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

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
