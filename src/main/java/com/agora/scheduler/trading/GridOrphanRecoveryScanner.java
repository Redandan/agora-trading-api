package com.agora.scheduler.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * #340 Phase 3 — 對齊卡在 PENDING_OKX 狀態的 grid level 與 OKX trade history。
 *
 * <p>觸發場景：GridManagerService.tryBuy() 寫 PENDING_OKX 後呼叫 OKX placeMarketBuy，
 * 中途若 OKX 有回應但 Java 端 parse / save 失敗，level 會卡在 PENDING_OKX。
 *
 * <p>恢復邏輯（每 2 分鐘掃一次）：
 * <ol>
 *   <li>找所有 status=PENDING_OKX 且 intent_at &lt; NOW - 60s 的 level（避免跟 in-flight call 競賽）</li>
 *   <li>查 OKX recent SPOT trades，找符合 (symbol, BUY, qty/price/time within tolerance) 的 trade</li>
 *   <li>若匹配：UPDATE level → HOLDING with recovered filled_qty / filled_price / paired_sell_price，發 TG「recovered」</li>
 *   <li>若 intent_at &gt; recovery-give-up-min（預設 30min）仍找不到：UPDATE → BUY_FAILED（OKX 確定沒成交，安全 give up）</li>
 * </ol>
 *
 * <p>取代原本 tryBuy 的 catch 直接 BUY_FAILED 的設計（容易誤判 OKX 已成交但 Java parse 失敗）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "grid.recovery.enabled", havingValue = "true", matchIfMissing = false)
@RequiredArgsConstructor
public class GridOrphanRecoveryScanner {

    private final BtGridLevelRepository levelRepository;
    private final BtGridRepository gridRepository;
    private final OkxTradingService okxTradingService;
    private final NotificationPort notificationPort;
    private final JdbcTemplate jdbc;
    private final com.agora.config.properties.GridRecoveryProperties props;

    @Scheduled(fixedDelay = 120_000L, initialDelay = 60_000L)
    public void tick() {
        if (!props.enabled()) return;
        try {
            scan();
        } catch (Throwable t) {
            log.error("[GridOrphanRecovery] tick failed: {}", t.getMessage(), t);
        }
    }

    private void scan() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(props.delaySeconds());
        // #373 — 同時掃 PENDING_OKX (BUY 中介態) 與 SELLING_OKX (SELL 中介態)
        List<Long> levelIds = jdbc.queryForList(
                "SELECT id FROM bt_grid_level " +
                        "WHERE status IN ('PENDING_OKX','SELLING_OKX') " +
                        "  AND intent_at IS NOT NULL AND intent_at < ? " +
                        "ORDER BY intent_at ASC",
                Long.class, cutoff);
        if (levelIds.isEmpty()) return;

        log.info("[GridOrphanRecovery] checking {} stuck levels (PENDING_OKX/SELLING_OKX)", levelIds.size());
        for (Long id : levelIds) {
            try {
                processOne(id);
            } catch (Exception e) {
                log.warn("[GridOrphanRecovery] level {} processing failed: {}", id, e.getMessage());
            }
        }
    }

    private void processOne(long levelId) {
        BtGridLevel level = levelRepository.findById(levelId).orElse(null);
        if (level == null) return;
        boolean isBuy = "PENDING_OKX".equals(level.getStatus());
        boolean isSell = "SELLING_OKX".equals(level.getStatus());
        if (!isBuy && !isSell) return;
        BtGrid grid = gridRepository.findById(level.getGridId()).orElse(null);
        if (grid == null) return;

        String wantSide = isBuy ? "buy" : "sell";

        // 1. 查 OKX recent fills 找匹配 trade
        // #436 Bug B — limit 100 對 active multi-grid 不夠,bump 200 避免 fill 被擠出 window
        JsonNode fills = okxTradingService.getRecentFills("SPOT", 200);
        if (!fills.isArray()) return;

        Instant intentInstant = level.getIntentAt().atZone(ZoneOffset.UTC).toInstant();
        BigDecimal targetPrice = isBuy ? level.getPrice() : level.getPairedSellPrice();
        if (targetPrice == null) {
            log.warn("[GridOrphanRecovery] level {} {} has null target price — skip", levelId, level.getStatus());
            return;
        }
        BigDecimal expectedQty = isBuy
                ? grid.getPerLevelUsdt().divide(targetPrice, 8, RoundingMode.HALF_UP)
                : level.getFilledQty();
        if (expectedQty == null || expectedQty.signum() <= 0) {
            log.warn("[GridOrphanRecovery] level {} expectedQty invalid — skip", levelId);
            return;
        }

        // #436 Bug B — forward-only window: 實際成交不可能在 intent_at 之前,
        // 但 trySell retry 會 reset intent_at,所以舊有 ±5min abs() 邏輯會錯過第一輪成交。
        // 改成「intent_at - clockSkew(60s) ≤ fillTime ≤ intent_at + maxLookForward」,
        // maxLookForward = timeToleranceMinutes(預設 5min,可由 application config 提高)。
        long maxForwardSec = props.timeToleranceMinutes() * 60L;
        long maxBackwardSec = 60L;
        JsonNode bestMatch = null;
        long bestDeltaSec = Long.MAX_VALUE;
        for (JsonNode f : fills) {
            String instId = f.path("instId").asText("");
            if (!instId.equalsIgnoreCase(grid.getSymbol().replace("USDT", "-USDT"))) continue;
            String side = f.path("side").asText("");
            if (!wantSide.equalsIgnoreCase(side)) continue;
            long ts = f.path("ts").asLong(0);
            if (ts <= 0) continue;
            Instant when = Instant.ofEpochMilli(ts);
            long deltaSec = Duration.between(intentInstant, when).getSeconds();  // signed
            if (deltaSec < -maxBackwardSec) continue;  // fill 太早於 intent → 不是這次的
            if (deltaSec > maxForwardSec) continue;    // fill 太晚 → 視為 stale
            BigDecimal px;
            BigDecimal sz;
            try {
                px = new BigDecimal(f.path("fillPx").asText("0"));
                sz = new BigDecimal(f.path("fillSz").asText("0"));
            } catch (NumberFormatException nfe) { continue; }
            if (sz.signum() <= 0 || px.signum() <= 0) continue;
            if (px.subtract(targetPrice).abs().doubleValue() > props.priceTolerance()) continue;
            if (sz.subtract(expectedQty).abs()
                    .divide(expectedQty, 6, RoundingMode.HALF_UP).doubleValue() > 0.05) continue;
            long absDelta = Math.abs(deltaSec);
            if (absDelta < bestDeltaSec) {
                bestDeltaSec = absDelta;
                bestMatch = f;
            }
        }

        if (bestMatch != null) {
            if (isBuy) recoverToHolding(grid, level, bestMatch);
            else       recoverToClosed(grid, level, bestMatch);
            return;
        }

        // 2. 沒匹配 — 看 intent_at 卡多久。超過 give-up 才標 *_FAILED。
        long mins = Duration.between(level.getIntentAt(), LocalDateTime.now()).toMinutes();
        if (mins >= props.giveUpMinutes()) {
            String failedStatus = isBuy ? "BUY_FAILED" : "SELL_FAILED";
            level.setStatus(failedStatus);
            level.setErrorMessage(level.getStatus() + " timed out after " + mins + "min (no matching OKX trade)");
            levelRepository.save(level);
            log.warn("[GridOrphanRecovery] level {} gave up after {}min — set {}", levelId, mins, failedStatus);
            try {
                notificationPort.broadcast(String.format(
                        "❌ <b>Grid #%d L%d gave up</b>\n%s\n卡 %s %d 分鐘無匹配 OKX trade，標 %s",
                        grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                        isBuy ? "PENDING_OKX" : "SELLING_OKX", mins, failedStatus), true);
            } catch (Exception ignore) { /* TG fallback */ }
        } else {
            log.debug("[GridOrphanRecovery] level {} still {} ({}min) — keep waiting",
                    levelId, level.getStatus(), mins);
        }
    }

    /** #373 — SELLING_OKX → CLOSED with recovered fill px / pnl from OKX trade. */
    private void recoverToClosed(BtGrid grid, BtGridLevel level, JsonNode trade) {
        BigDecimal px = new BigDecimal(trade.path("fillPx").asText("0"));
        String ordId = trade.path("ordId").asText("");

        BigDecimal pnl = px.subtract(level.getFilledPrice())
                .multiply(level.getFilledQty())
                .setScale(2, RoundingMode.HALF_UP);

        level.setStatus("CLOSED");
        level.setRetryCount(0);
        level.setRealizedPnl(pnl);
        level.setSellOrderId(ordId);
        level.setClosedAt(LocalDateTime.now());
        level.setErrorMessage(null);
        levelRepository.save(level);

        // 同步 grid 累計 PnL（trySell 主路徑做法）
        grid.setTotalRealizedPnl(grid.getTotalRealizedPnl().add(pnl));
        grid.setClosedPairCount(grid.getClosedPairCount() + 1);
        grid.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(grid);

        log.warn("[GridOrphanRecovery] RECOVERED-SELL level={} grid={} px={} pnl={} ordId={}",
                level.getId(), grid.getId(), px, pnl, ordId);
        try {
            notificationPort.broadcast(String.format(
                    "🔧 <b>Grid #%d L%d 自動恢復(SELL)</b>\n%s sold @ %s\nPnL: %+.4f USDT (累計 %+.4f)\n從 SELLING_OKX → CLOSED (matched OKX ordId=%s)",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    px.toPlainString(), pnl.doubleValue(),
                    grid.getTotalRealizedPnl().doubleValue(), ordId), true);
        } catch (Exception ignore) { /* TG fallback */ }
    }

    private void recoverToHolding(BtGrid grid, BtGridLevel level, JsonNode trade) {
        BigDecimal px = new BigDecimal(trade.path("fillPx").asText("0"));
        BigDecimal sz = new BigDecimal(trade.path("fillSz").asText("0"));
        String ordId = trade.path("ordId").asText("");

        BigDecimal gridStep = grid.getPriceUpper().subtract(grid.getPriceLower())
                .divide(BigDecimal.valueOf(grid.getGridCount() - 1L), 2, RoundingMode.HALF_UP);

        level.setStatus("HOLDING");
        level.setRetryCount(0);
        level.setFilledQty(sz);
        level.setFilledPrice(px);
        level.setBuyOrderId(ordId);
        level.setFilledAt(LocalDateTime.now());
        level.setPairedSellPrice(px.add(gridStep).setScale(2, RoundingMode.HALF_UP));
        level.setErrorMessage(null);
        levelRepository.save(level);

        log.warn("[GridOrphanRecovery] RECOVERED level={} grid={} qty={} px={} ordId={}",
                level.getId(), grid.getId(), sz, px, ordId);
        try {
            notificationPort.broadcast(String.format(
                    "🔧 <b>Grid #%d L%d 自動恢復</b>\n%s qty=%s @ %s\n從 PENDING_OKX → HOLDING (matched OKX ordId=%s)\nPaired Sell: %s",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    sz.toPlainString(), px.toPlainString(), ordId,
                    level.getPairedSellPrice().toPlainString()), true);
        } catch (Exception ignore) { /* TG fallback */ }
    }
}
