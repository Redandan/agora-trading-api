package com.agora.service.trading;

import com.agora.config.FundingArbProperties;
import com.agora.model.BtFundingArb;
import com.agora.repository.trading.BtFundingArbRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Funding Rate Arbitrage(Layer 2)— delta-neutral spot long + perp short 配對。
 *
 * <h3>核心邏輯</h3>
 * <ul>
 *   <li>{@link #tryOpen}: 檢查 funding + 餘額 + 既有 active → 原子性開兩條腿(失敗回滾 spot)</li>
 *   <li>{@link #close}: 平兩條腿 + 計算 realized PnL + 寫 audit</li>
 *   <li>{@link #reconcileAll}: 每 30 min 被 scheduler 呼叫一次,處理所有 OPEN positions</li>
 * </ul>
 *
 * <h3>Phase 1 限制</h3>
 * single position / BTC only / 無 rebalance / perp 使用 {@link OkxTradingProperties#getSwapLeverage() swap leverage}(3x 預設),
 * notional 意義 = spot 買入金額 = perp 空單敞口金額(兩者 delta 相等)。
 *
 * <h3>安全</h3>
 * Dry-run 必開。任何 leg 失敗都寫 audit FAILURE + TG CRITICAL 通知,不自動 retry。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundingArbService {

    private static final List<String> ACTIVE_STATUSES =
            List.of("OPENING", "OPEN", "CLOSING");

    private final FundingArbProperties props;
    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final BtFundingArbRepository repo;
    private final DecisionAuditWriter auditWriter;
    private final NotificationPort notificationPort;

    // ========================================================================
    // Open
    // ========================================================================

    /**
     * 嘗試開一筆 funding arb position。
     *
     * @param symbol       目前僅支援 BTCUSDT
     * @param notional     spot + perp 的名目價值(USDT)
     * @param triggerReason "AUTO_SCHEDULER" / "MCP_CREATE" / etc.
     * @return Result(成功則 positionId 存在)
     */
    @Transactional
    public OpenResult tryOpen(String symbol, BigDecimal notional, String triggerReason) {
        try {
            // 1) 前置檢查
            if (!props.isEnabled()) {
                return OpenResult.skipped("funding-arb.enabled=false");
            }
            if (!props.getSymbol().equalsIgnoreCase(symbol)) {
                return OpenResult.skipped("Phase 1 僅支援 " + props.getSymbol());
            }
            List<BtFundingArb> actives = repo.findBySymbolAndStatusIn(
                    symbol.toUpperCase(), ACTIVE_STATUSES);
            if (!actives.isEmpty()) {
                return OpenResult.skipped("已有 active position id=" + actives.get(0).getId());
            }

            double currentFunding = okxTradingService.getCurrentFundingRate(symbol);
            BigDecimal currentFundingBd = BigDecimal.valueOf(currentFunding);
            if (currentFundingBd.compareTo(props.getMinFundingRate()) < 0) {
                return OpenResult.skipped(String.format(
                        "funding %.6f < min %.6f",
                        currentFunding, props.getMinFundingRate().doubleValue()));
            }

            // 2) 建 pending row 先落地 → 有事故才追得到
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            BtFundingArb pos = new BtFundingArb();
            pos.setSymbol(symbol.toUpperCase());
            pos.setNotionalUsdt(notional);
            pos.setStatus("PENDING");
            pos.setMinFundingRate(props.getMinFundingRate());
            pos.setExitThreshold(props.getExitThreshold());
            pos.setTargetProfitUsdt(notional.multiply(props.getTargetProfitPct())
                    .setScale(2, RoundingMode.HALF_UP));
            pos.setHintGated(props.isHintGated());
            pos.setRegimeWhitelist(props.getRegimeWhitelist());
            pos.setCreatedAt(now);
            pos.setUpdatedAt(now);
            pos = repo.save(pos);

            // 3) Dry-run:只 log 不下單
            if (props.isDryRun()) {
                log.info("[FundingArb][DRY-RUN] would open symbol={} notional={} funding={} posId={}",
                        symbol, notional, currentFunding, pos.getId());
                pos.setStatus("CLOSED");
                pos.setCloseReason("DRY_RUN");
                pos.setClosedAt(now);
                pos.setUpdatedAt(now);
                repo.save(pos);
                auditFaEvent("FA_OPEN_DRYRUN", "INFO", pos.getId(), symbol,
                        "dry-run, funding=" + currentFunding, triggerReason);
                return OpenResult.dryRun(pos.getId());
            }

            // 4) Spot leg: market buy
            pos.setStatus("OPENING");
            pos.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            repo.save(pos);

            // Earn 補款：下單前確認 USDT 夠用（non-blocking）
            try {
                String balStr = okxTradingService.getUsdtBalance();
                if (!"N/A".equals(balStr)) {
                    BigDecimal availUsdt = new BigDecimal(balStr);
                    if (availUsdt.compareTo(notional) < 0) {
                        log.info("[FundingArb] USDT 不足 ({} < {})，嘗試從 Simple Earn 補款",
                                availUsdt, notional);
                        okxEarnService.topUpTradingBuffer(availUsdt);
                    }
                }
            } catch (Exception e) {
                log.warn("[FundingArb] Simple Earn 補款檢查失敗（non-blocking，繼續嘗試下單）: {}",
                        e.getMessage());
            }

            TradeResult spotRes;
            try {
                spotRes = okxTradingService.placeMarketBuy(symbol, notional.doubleValue());
            } catch (Exception e) {
                pos.setStatus("FAILED");
                pos.setCloseReason("SPOT_BUY_FAIL: " + e.getMessage());
                pos.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                repo.save(pos);
                auditFaEvent("FA_API_ERROR", "ERROR", pos.getId(), symbol,
                        "spot buy failed: " + e.getMessage(), triggerReason);
                notifyCritical("FundingArb: SPOT 買入失敗", symbol, e.getMessage());
                return OpenResult.failed("spot buy failed: " + e.getMessage());
            }
            pos.setSpotQty(spotRes.getQty());
            pos.setSpotEntryPrice(spotRes.getAvgPrice());
            pos.setSpotBuyOrderId(spotRes.getOrderId());
            repo.save(pos);

            // 5) Perp leg: SWAP short entry
            TradeResult perpRes;
            try {
                perpRes = okxTradingService.placeSwapShortEntry(symbol, notional.doubleValue());
            } catch (Exception e) {
                // 回滾 spot
                log.error("[FundingArb] perp short FAILED, rolling back spot. posId={} err={}",
                        pos.getId(), e.getMessage());
                try {
                    okxTradingService.placeMarketSell(symbol, spotRes.getQty());
                    pos.setStatus("FAILED");
                    pos.setCloseReason("PERP_FAIL_SPOT_ROLLED_BACK: " + e.getMessage());
                    notifyCritical("FundingArb: PERP 失敗,SPOT 已回滾", symbol, e.getMessage());
                } catch (Exception rollbackEx) {
                    pos.setStatus("FAILED");
                    pos.setCloseReason("PERP_FAIL_ROLLBACK_FAIL: perp=" + e.getMessage()
                            + " rollback=" + rollbackEx.getMessage());
                    notifyCritical("🚨 FundingArb: PERP + SPOT 回滾皆失敗,需手動清理!",
                            symbol, e.getMessage() + " / " + rollbackEx.getMessage());
                }
                pos.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
                repo.save(pos);
                auditFaEvent("FA_API_ERROR", "ERROR", pos.getId(), symbol,
                        "perp short failed: " + e.getMessage(), triggerReason);
                return OpenResult.failed("perp short failed: " + e.getMessage());
            }

            // 6) 成功:標記 OPEN
            pos.setPerpContractQty(perpRes.getQty());
            pos.setPerpEntryPrice(perpRes.getAvgPrice());
            pos.setPerpOpenOrderId(perpRes.getOrderId());
            pos.setStatus("OPEN");
            pos.setOpenedAt(LocalDateTime.now(ZoneOffset.UTC));
            pos.setUpdatedAt(pos.getOpenedAt());
            repo.save(pos);

            auditFaEvent("FA_OPEN", "PASS", pos.getId(), symbol,
                    String.format("notional=%s funding=%.6f spotQty=%s perpQty=%s",
                            notional, currentFunding, pos.getSpotQty(), pos.getPerpContractQty()),
                    triggerReason);
            try {
                notificationPort.broadcast(String.format(
                        "🎯 <b>FundingArb OPEN</b>\n%s notional=$%s\nfunding=%.4f%%/8h (年化 ~%.1f%%)\n" +
                        "spot=%s @ $%s | perp=%s contracts @ $%s\nposId=%d",
                        symbol, notional,
                        currentFunding * 100, currentFunding * 3 * 365 * 100,
                        pos.getSpotQty(), pos.getSpotEntryPrice(),
                        pos.getPerpContractQty(), pos.getPerpEntryPrice(),
                        pos.getId()), true);
            } catch (Exception ignored) {}
            return OpenResult.ok(pos.getId());

        } catch (Throwable t) {
            log.error("[FundingArb] tryOpen fatal: {}", t.getMessage(), t);
            return OpenResult.failed("fatal: " + t.getMessage());
        }
    }

    // ========================================================================
    // Close
    // ========================================================================

    /**
     * 平倉指定 position。
     *
     * @param positionId bt_funding_arb.id
     * @param reason     "FUNDING_LOW" / "TARGET_HIT" / "REGIME_EXIT" / "DELTA_DRIFT" / "MANUAL" 等
     */
    @Transactional
    public CloseResult close(Long positionId, String reason) {
        BtFundingArb pos = repo.findById(positionId).orElse(null);
        if (pos == null) return CloseResult.failed("position id=" + positionId + " 不存在");
        if (!pos.isOpen()) {
            return CloseResult.failed("position id=" + positionId + " status=" + pos.getStatus() + " 非 OPEN");
        }

        try {
            if (props.isDryRun()) {
                pos.setStatus("CLOSED");
                pos.setCloseReason("DRY_RUN: " + reason);
                pos.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
                pos.setUpdatedAt(pos.getClosedAt());
                repo.save(pos);
                auditFaEvent("FA_CLOSE_DRYRUN", "INFO", pos.getId(), pos.getSymbol(),
                        "dry-run close reason=" + reason, reason);
                return CloseResult.ok(BigDecimal.ZERO, "dry-run");
            }

            pos.setStatus("CLOSING");
            pos.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            repo.save(pos);

            // 1) Close spot(market sell)
            BigDecimal spotExit = null;
            try {
                spotExit = okxTradingService.placeMarketSell(pos.getSymbol(), pos.getSpotQty());
                pos.setSpotExitPrice(spotExit);
            } catch (Exception e) {
                auditFaEvent("FA_API_ERROR", "ERROR", pos.getId(), pos.getSymbol(),
                        "spot sell failed: " + e.getMessage(), reason);
                notifyCritical("FundingArb: CLOSE spot 失敗", pos.getSymbol(), e.getMessage());
                pos.setCloseReason("CLOSE_SPOT_FAIL: " + e.getMessage());
                pos.setStatus("FAILED");
                repo.save(pos);
                return CloseResult.failed("spot sell failed: " + e.getMessage());
            }

            // 2) Close perp(SWAP short exit = market buy back)
            TradeResult perpExitRes;
            try {
                perpExitRes = okxTradingService.placeSwapShortExit(pos.getSymbol(), pos.getPerpContractQty());
            } catch (Exception e) {
                auditFaEvent("FA_API_ERROR", "ERROR", pos.getId(), pos.getSymbol(),
                        "perp close failed after spot sold: " + e.getMessage(), reason);
                notifyCritical("🚨 FundingArb: CLOSE perp 失敗(spot 已賣)需手動平倉",
                        pos.getSymbol(), e.getMessage());
                pos.setCloseReason("CLOSE_PERP_FAIL: " + e.getMessage());
                pos.setStatus("FAILED");
                repo.save(pos);
                return CloseResult.failed("perp close failed: " + e.getMessage());
            }
            pos.setPerpExitPrice(perpExitRes.getAvgPrice());
            pos.setPerpCloseOrderId(perpExitRes.getOrderId());

            // 3) 計算 realized PnL(delta-neutral 下 spot + perp 差幾乎為 0,收益主要來自 funding)
            BigDecimal spotPnl = spotExit.subtract(pos.getSpotEntryPrice()).multiply(pos.getSpotQty());
            BigDecimal perpPnl = pos.getPerpEntryPrice().subtract(perpExitRes.getAvgPrice())
                    .multiply(pos.getPerpContractQty());
            BigDecimal realized = spotPnl.add(perpPnl).add(pos.getAccumulatedFunding());
            pos.setRealizedPnl(realized.setScale(8, RoundingMode.HALF_UP));
            pos.setStatus("CLOSED");
            pos.setCloseReason(reason);
            pos.setClosedAt(LocalDateTime.now(ZoneOffset.UTC));
            pos.setUpdatedAt(pos.getClosedAt());
            repo.save(pos);

            auditFaEvent("FA_CLOSE", "PASS", pos.getId(), pos.getSymbol(),
                    String.format("realized=%.4f (spot=%.4f perp=%.4f funding=%.4f)",
                            realized, spotPnl, perpPnl, pos.getAccumulatedFunding()),
                    reason);
            try {
                notificationPort.broadcast(String.format(
                        "✅ <b>FundingArb CLOSE</b>\n%s  reason=%s\n" +
                        "spot PnL=$%.4f  perp PnL=$%.4f  funding=$%.4f\n" +
                        "realized = <b>$%.4f</b>\nposId=%d",
                        pos.getSymbol(), reason, spotPnl, perpPnl,
                        pos.getAccumulatedFunding(), realized, pos.getId()), true);
            } catch (Exception ignored) {}
            return CloseResult.ok(realized, reason);

        } catch (Throwable t) {
            log.error("[FundingArb] close fatal: posId={} {}", positionId, t.getMessage(), t);
            return CloseResult.failed("fatal: " + t.getMessage());
        }
    }

    // ========================================================================
    // Reconcile(給 scheduler 呼叫)
    // ========================================================================

    /** 對所有 active position 跑 reconcile。Phase 1 single-position,實際最多 1 筆。 */
    public void reconcileAll() {
        if (!props.isEnabled()) return;
        List<BtFundingArb> open = repo.findByStatusIn(List.of("OPEN"));
        for (BtFundingArb pos : open) {
            try {
                reconcileOne(pos);
            } catch (Throwable t) {
                log.error("[FundingArb] reconcile posId={} failed: {}", pos.getId(), t.getMessage(), t);
            }
        }
    }

    private void reconcileOne(BtFundingArb pos) {
        double currentFunding = okxTradingService.getCurrentFundingRate(pos.getSymbol());
        BigDecimal currentFundingBd = BigDecimal.valueOf(currentFunding);

        // 1) Funding 過低
        if (currentFundingBd.compareTo(pos.getExitThreshold()) < 0) {
            // Phase 1 簡化:一次低於就出(不做「連續 2 期」狀態追蹤,避免新增狀態欄位)
            close(pos.getId(), "FUNDING_LOW funding=" + currentFunding);
            return;
        }

        // 2) 目標 profit 達成
        if (pos.getTargetProfitUsdt() != null
                && pos.getAccumulatedFunding().compareTo(pos.getTargetProfitUsdt()) >= 0) {
            close(pos.getId(), "TARGET_HIT acc=" + pos.getAccumulatedFunding());
            return;
        }

        // 3) Delta drift 檢查(spot qty × 當前價 vs perp 合約敞口)
        try {
            BigDecimal spotNow = okxTradingService.getLastPrice(pos.getSymbol());
            if (spotNow != null) {
                BigDecimal spotValue = pos.getSpotQty().multiply(spotNow);
                BigDecimal drift = spotValue.subtract(pos.getNotionalUsdt()).abs()
                        .divide(pos.getNotionalUsdt(), 4, RoundingMode.HALF_UP);
                if (drift.compareTo(props.getMaxDeltaDriftPct()) > 0) {
                    close(pos.getId(), "DELTA_DRIFT drift=" + drift);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("[FundingArb] delta drift check failed posId={}: {}", pos.getId(), e.getMessage());
        }

        // 4) Funding 結算追蹤(每 8h OKX 自動打進 margin,我們 poll 累積)
        try {
            accumulateFundingIfSettled(pos, currentFunding);
        } catch (Exception e) {
            log.warn("[FundingArb] accumulate funding failed posId={}: {}", pos.getId(), e.getMessage());
        }
    }

    /**
     * Funding 結算追蹤:用「距上次更新 > 8h」粗略判斷是否已結算一次。
     * Phase 1 簡化,Phase 2 可讀 OKX funding history API 精確對帳。
     */
    private void accumulateFundingIfSettled(BtFundingArb pos, double currentFunding) {
        LocalDateTime lastUpdate = pos.getUpdatedAt();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        long minutesSinceLast = java.time.Duration.between(lastUpdate, now).toMinutes();
        if (minutesSinceLast < 480) return; // < 8h 沒結算

        BigDecimal thisPeriod = pos.getNotionalUsdt().multiply(BigDecimal.valueOf(currentFunding));
        pos.setAccumulatedFunding(pos.getAccumulatedFunding().add(thisPeriod));
        pos.setFundingPeriods(pos.getFundingPeriods() + 1);
        pos.setUpdatedAt(now);
        repo.save(pos);
        auditFaEvent("FA_FUNDING_SETTLED", "INFO", pos.getId(), pos.getSymbol(),
                String.format("period=%d rate=%.6f amt=%.4f acc=%.4f",
                        pos.getFundingPeriods(), currentFunding, thisPeriod, pos.getAccumulatedFunding()),
                null);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private void auditFaEvent(String eventType, String outcome, Long posId, String symbol,
                               String reason, String triggerReason) {
        java.util.Map<String, Object> ctx = new java.util.LinkedHashMap<>();
        ctx.put("positionId", posId);
        if (triggerReason != null) ctx.put("trigger", triggerReason);
        // 走 logOverrideApplied 路由(最接近的現有 event_type,不改 schema)
        auditWriter.logOverrideApplied(null, symbol, eventType, reason);
    }

    private void notifyCritical(String title, String symbol, String err) {
        try {
            notificationPort.broadcast(String.format(
                    "🚨 <b>%s</b>\n%s\nerror: %s\n請立即檢查 OKX 帳戶!",
                    title, symbol, err), true);
        } catch (Exception ignored) {}
    }

    // ========================================================================
    // Result records
    // ========================================================================

    public record OpenResult(boolean ok, Long positionId, String message, boolean isDryRun) {
        public static OpenResult ok(Long id)            { return new OpenResult(true,  id, "OK",         false); }
        public static OpenResult dryRun(Long id)        { return new OpenResult(true,  id, "DRY_RUN",    true);  }
        public static OpenResult skipped(String reason) { return new OpenResult(false, null, reason,     false); }
        public static OpenResult failed(String reason)  { return new OpenResult(false, null, reason,     false); }
    }

    public record CloseResult(boolean ok, BigDecimal realizedPnl, String message) {
        public static CloseResult ok(BigDecimal pnl, String reason) { return new CloseResult(true,  pnl, reason); }
        public static CloseResult failed(String reason)             { return new CloseResult(false, null, reason); }
    }
}
