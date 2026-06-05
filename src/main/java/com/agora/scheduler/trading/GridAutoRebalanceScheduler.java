package com.agora.scheduler.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.trading.OkxTradingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;

/**
 * #273 Grid 自動換範圍排程。
 *
 * <p>每小時執行，對所有 autoRebalance=true 的 ACTIVE Grid 評估是否需要重建：
 *
 * <h3>防呆機制（全部滿足才觸發）</h3>
 * <ol>
 *   <li>Gemini 4h regime ∈ grid.regimeWhitelist（通常 SIDEWAYS/VOLATILE/RECOVERY）</li>
 *   <li>價格持續在範圍外 ≥ minHoursOutside 小時</li>
 *   <li>今日尚未重建（lastRebalanceAt < today 00:00 UTC）</li>
 *   <li>rebalanceCount < maxRebalanceCount（超過上限暫停並通知人工��認）</li>
 * </ol>
 *
 * <h3>重建邏輯</h3>
 * 保留原網格設定（格數、每格金額、stopOutPct、regimeWhitelist），
 * 以當前價格為中心，原寬度等比例分配（40% 在下，60% 在上）重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.grid.auto-rebalance-scheduler.enabled", havingValue = "true")
public class GridAutoRebalanceScheduler {

    /** ATR 倍數 — 以當前 4h ATR 計算重建後的網格寬度（預設 8×，BTC 4h ATR ≈ $1200-2000 → 寬度 $9600-16000）。 */
    private static final double ATR_MULTIPLIER = 8.0;
    /** ATR 計算期數。 */
    private static final int ATR_PERIOD = 14;

    private final BtGridRepository gridRepository;
    private final GeminiMarketHintRepository hintRepository;
    private final MdKlineRepository klineRepository;
    private final OkxTradingService okxTradingService;
    private final NotificationPort notificationPort;
    private final com.agora.mcp.GridMcpTools gridMcpTools;

    @Scheduled(cron = "0 15 * * * *", zone = "UTC")   // 每小時 :15 分執行
    public void checkAndRebalance() {
        List<BtGrid> candidates = gridRepository.findByEnabledTrueAndClosedAtIsNull()
                .stream()
                .filter(g -> Boolean.TRUE.equals(g.getAutoRebalance()))
                .toList();
        if (candidates.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (BtGrid g : candidates) {
            try {
                evaluate(g, now);
            } catch (Exception e) {
                log.warn("[GridRebalance] Grid #{} eval failed: {}", g.getId(), e.getMessage());
            }
        }
    }

    private void evaluate(BtGrid g, LocalDateTime now) {
        BigDecimal currentPrice = okxTradingService.getLastPrice(g.getSymbol());
        if (currentPrice == null) return;

        double price = currentPrice.doubleValue();
        double lower = g.getPriceLower().doubleValue();
        double upper = g.getPriceUpper().doubleValue();
        double triggerPct = g.getRebalanceTriggerPct() != null ? g.getRebalanceTriggerPct() : 0.015;

        boolean belowRange = price < lower * (1 - triggerPct);
        boolean aboveRange = price > upper * (1 + triggerPct);
        boolean outsideRange = belowRange || aboveRange;

        if (!outsideRange) {
            // 回到範圍內 → 重置 outsideRangeSince
            if (g.getOutsideRangeSince() != null) {
                g.setOutsideRangeSince(null);
                g.setUpdatedAt(now);
                gridRepository.save(g);
                log.debug("[GridRebalance] Grid #{} 回到範圍內，重置 outsideRangeSince", g.getId());
            }
            return;
        }

        // 記錄首次超出時間
        if (g.getOutsideRangeSince() == null) {
            g.setOutsideRangeSince(now);
            g.setUpdatedAt(now);
            gridRepository.save(g);
            log.info("[GridRebalance] Grid #{} {} 首次超出範圍（price={} range={}-{}），開始計時",
                    g.getId(), g.getSymbol(), price, lower, upper);
            return;
        }

        // ── 防呆 1：持續在外 minHoursOutside 小時 ────────────────────────
        int minHours = g.getMinHoursOutside() != null ? g.getMinHoursOutside() : 4;
        long hoursOutside = java.time.Duration.between(g.getOutsideRangeSince(), now).toHours();
        if (hoursOutside < minHours) {
            log.debug("[GridRebalance] Grid #{} 在外 {}h，未達 {}h 門檻", g.getId(), hoursOutside, minHours);
            return;
        }

        // ── 防呆 2：Gemini 4h regime 必須在白名單內 ──────────────────────
        String regime = getLatestRegime(g.getSymbol());
        String whitelist = g.getRegimeWhitelist() != null ? g.getRegimeWhitelist() : "SIDEWAYS,VOLATILE,RECOVERY";
        if (regime != null && !whitelist.toUpperCase().contains(regime.toUpperCase())) {
            log.info("[GridRebalance] Grid #{} regime={} 不在白名單 {}，跳過重建", g.getId(), regime, whitelist);
            return;
        }

        // ── 防呆 3：今日已重建過 ───────���─────────────────────────────────
        if (g.getLastRebalanceAt() != null) {
            LocalDateTime todayStart = now.toLocalDate().atStartOfDay();
            if (g.getLastRebalanceAt().isAfter(todayStart)) {
                log.info("[GridRebalance] Grid #{} 今日已重建（at {}），跳過", g.getId(), g.getLastRebalanceAt());
                return;
            }
        }

        // ── 防呆 4：重建次數上限 ──────────���───────────────────���───────────
        int maxCnt = g.getMaxRebalanceCount() != null ? g.getMaxRebalanceCount() : 5;
        int curCnt = g.getRebalanceCount() != null ? g.getRebalanceCount() : 0;
        if (curCnt >= maxCnt) {
            // 停用自動重建並 TG ��知
            g.setAutoRebalance(false);
            g.setUpdatedAt(now);
            gridRepository.save(g);
            try {
                notificationPort.broadcast(String.format(
                        "⚠️ <b>Grid #%d %s 自動換範圍已暫停</b>\n" +
                        "已累計重建 %d 次（上限 %d）\n現價 $%.0f 仍在範圍外\n" +
                        "請人工確認方向後用 enableGridAutoRebalance 重啟",
                        g.getId(), g.getSymbol(), curCnt, maxCnt, price), true);
            } catch (Exception ignored) {}
            log.warn("[GridRebalance] Grid #{} 達重建上限 {}，停用自動重建", g.getId(), maxCnt);
            return;
        }

        // ── 所有條件滿足，執行重建 ────────────────────────────────────────
        doRebalance(g, price, lower, upper, now, regime);
    }

    private void doRebalance(BtGrid g, double currentPrice, double oldLower, double oldUpper,
                              LocalDateTime now, String regime) {
        // ── ATR 自適應寬度（#279）────────────────────────────────────────────
        // 用 ATR(14) × ATR_MULTIPLIER 作為新寬度；若無 kline 資料則 fallback 舊寬度。
        double atr = calculateAtr4h(g.getSymbol());
        double width;
        String widthSource;
        if (atr > 0) {
            width = atr * ATR_MULTIPLIER;
            widthSource = String.format("ATR4h=%.0f × %.0f = %.0f", atr, ATR_MULTIPLIER, width);
        } else {
            width = oldUpper - oldLower;
            widthSource = String.format("固定寬度(ATR不可用) = %.0f", width);
        }
        // 以當前價為中心，40% 在下、60% 在上（slightly bullish bias）
        double newLower = currentPrice - width * 0.4;
        double newUpper = currentPrice + width * 0.6;
        log.info("[GridRebalance] #{} 寬度計算: {}", g.getId(), widthSource);

        // 取整（百位對齊）
        newLower = Math.floor(newLower / 100) * 100;
        newUpper = Math.ceil(newUpper  / 100) * 100;

        log.info("[GridRebalance] Grid #{} {} 開始重建：price={} old={}-{} new={}-{} regime={}",
                g.getId(), g.getSymbol(), currentPrice, oldLower, oldUpper, newLower, newUpper, regime);

        // 1. 關閉舊網格
        String closeResult = gridMcpTools.closeGrid(g.getId());
        log.info("[GridRebalance] closeGrid #{}: {}", g.getId(), closeResult);

        // 2. 建新網格（繼承舊設定 + 開啟 autoRebalance）
        String createResult = gridMcpTools.createGrid(
                g.getSymbol(),
                BigDecimal.valueOf(newLower).setScale(0, RoundingMode.FLOOR),
                BigDecimal.valueOf(newUpper).setScale(0, RoundingMode.CEILING),
                g.getGridCount(),
                g.getPerLevelUsdt(),
                g.getStopOutPct(),
                g.getRegimeWhitelist());
        log.info("[GridRebalance] createGrid result: {}", createResult);

        // 3. 找新建的 Grid 並複製 autoRebalance 設定
        List<BtGrid> newest = gridRepository.findByEnabledTrueAndClosedAtIsNull()
                .stream()
                .filter(x -> g.getSymbol().equals(x.getSymbol()) && x.getId() > g.getId())
                .sorted(java.util.Comparator.comparingLong(BtGrid::getId).reversed())
                .limit(1)
                .toList();
        if (!newest.isEmpty()) {
            BtGrid newGrid = newest.get(0);
            newGrid.setAutoRebalance(true);
            newGrid.setRebalanceTriggerPct(g.getRebalanceTriggerPct());
            newGrid.setMinHoursOutside(g.getMinHoursOutside());
            newGrid.setMaxRebalanceCount(g.getMaxRebalanceCount());
            newGrid.setRebalanceCount((g.getRebalanceCount() != null ? g.getRebalanceCount() : 0) + 1);
            newGrid.setLastRebalanceAt(now);
            newGrid.setUpdatedAt(now);
            gridRepository.save(newGrid);
        }

        // 4. TG 通知
        try {
            notificationPort.broadcast(String.format(
                    "🔄 <b>Grid 自動換��圍</b>\n" +
                    "%s 舊: $%.0f~$%.0f\n→ 新: $%.0f~$%.0f\n" +
                    "現價: $%.0f  Regime: %s\n寬度: %s\n第 %d 次重建（上限 %d）",
                    g.getSymbol(), oldLower, oldUpper, newLower, newUpper,
                    currentPrice, regime != null ? regime : "N/A",
                    widthSource,
                    (g.getRebalanceCount() != null ? g.getRebalanceCount() : 0) + 1,
                    g.getMaxRebalanceCount() != null ? g.getMaxRebalanceCount() : 5), true);
        } catch (Exception ignored) {}
    }

    /**
     * 計算指定交易對的 4h ATR(14)（簡化版：mean(high-low) 最近 14 根 4h K 線）。
     * True Range（考慮前收）在網格場景下差異不大，此簡化足夠。
     *
     * @return ATR 值（USD）；若資料不足回傳 0.0
     */
    private double calculateAtr4h(String symbol) {
        try {
            List<MdKline> klines = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                            symbol, "4h", PageRequest.of(0, ATR_PERIOD + 2));
            if (klines == null || klines.size() < ATR_PERIOD) {
                log.warn("[GridRebalance] {} 4h klines 不足 {} 根（got {}），ATR 無法計算",
                        symbol, ATR_PERIOD, klines == null ? 0 : klines.size());
                return 0.0;
            }
            // desc order → reverse to ascending, take last ATR_PERIOD bars
            Collections.reverse(klines);
            int startIdx = klines.size() - ATR_PERIOD;
            double sumTr = 0;
            int cnt = 0;
            for (int i = startIdx; i < klines.size(); i++) {
                MdKline k = klines.get(i);
                if (k.getHighPrice() == null || k.getLowPrice() == null) continue;
                sumTr += k.getHighPrice().subtract(k.getLowPrice()).doubleValue();
                cnt++;
            }
            if (cnt == 0) return 0.0;
            double atr = sumTr / cnt;
            log.debug("[GridRebalance] {} ATR({})4h = {}", symbol, ATR_PERIOD, String.format("%.2f", atr));
            return atr;
        } catch (Exception e) {
            log.warn("[GridRebalance] ATR 計算失敗 {}: {}", symbol, e.getMessage());
            return 0.0;
        }
    }

    private String getLatestRegime(String symbol) {
        try {
            LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(6);
            var hints = hintRepository
                    .findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(symbol, "4h", since);
            return hints.isEmpty() ? null : hints.get(0).getRegime();
        } catch (Exception e) {
            log.warn("[GridRebalance] Cannot get regime for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
