package com.agora.scheduler.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.metrics.TradingMetrics;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.agora.service.trading.BtcBasePositionStatePolicy;
import com.agora.service.trading.OcoManagementService;
import com.agora.service.trading.OcoOrderStateInspector;
import com.agora.service.trading.PositionMutationGuard;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.trading.SpotPositionCloseService;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每 5 分鐘輪詢所有未出場且有 OCO 掛單的自動交易倉位。
 *
 * <p>解決的問題：當 OKX OCO 觸發（止盈或止損成交）時，DB 不知道。
 * 若沒有這個 poller，DB 記錄會一直顯示「持倉中」，直到下一個 SELL 訊號才更新，
 * 導致 maxOpenPositions 計數錯誤、P&L 計算錯誤。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OcoPositionPollerScheduler {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final OcoManagementService ocoManagementService;
    private final NotificationPort notificationPort;
    private final OkxTradingProperties tradingProperties;
    private final TradingMetrics tradingMetrics;
    /** #340 — 2-cycle confirmation 避免 grid BUY 成交瞬間 race condition 誤報。*/
    private final UntrackedHoldingTracker untrackedHoldingTracker;
    /** #340 Phase 2 — alert 觸發時自動 call reconcileOrphanTrades 帶 OKX trade context */
    private final com.agora.service.diagnostic.OrphanTradeReconcilerService orphanReconciler;
    /** #380 — dedup 舊 orphan alert（24h TTL，每天最多 1 條）via #362 framework */
    private final TgNotificationDeduper tgDeduper;
    /** Prevent OCO auto-retry from racing a scoped cancel-and-market-close flow. */
    private final SpotPositionCloseService spotPositionCloseService;
    /** Single read-only source of truth for OCO parent and all child order states. */
    private final OcoOrderStateInspector ocoOrderStateInspector;

    @Value("${trading.oco-poller.enabled:false}")
    private boolean ocoPollerEnabled;

    @Value("${trading.oco-poller.untracked-min-notional-usdt:10.0}")
    private BigDecimal untrackedMinNotionalUsdt;

    /** OCO 補掛重試計數（in-memory；服務重啟後歸零，重新評估是正確行為）。
     *  value ≥ 5 = 已放棄重試；value = 6 = 放棄 TG 已發送，保持靜默。 */
    private final ConcurrentHashMap<Long, Integer> ocoRetryCount = new ConcurrentHashMap<>();

    private static final int OCO_MAX_RETRIES = 5;

    /** 啟動後 15 秒開始，之後每 10 分鐘執行一次（WS 推送為主要路徑，此 polling 為 fallback）。 */
    @Scheduled(initialDelay = 15_000, fixedDelay = 600_000)
    public void pollOcoPositions() {
        if (!ocoPollerEnabled) return;
        if (!tradingProperties.isEnabled()) return;
        Timer.Sample sample = Timer.start(tradingMetrics.registry());
        try {
            doPollOcoPositions();
        } finally {
            sample.stop(tradingMetrics.ocoPollTimer());
        }
    }

    private void doPollOcoPositions() {
        // ── 1. 檢查已有 OCO 的倉位是否已成交 ──────────────────────────────────
        List<BtLiveSignal> openPositions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull()
                        .stream()
                        .filter(position -> !"SHORT".equals(position.getSide()))
                        .toList();

        log.debug("[OcoPoll] Checking {} open position(s) with OCO orders", openPositions.size());

        for (BtLiveSignal pos : openPositions) {
            try {
                checkAndClose(pos);
            } catch (Exception e) {
                log.error("[OcoPoll] Error checking position id={} symbol={}: {}",
                        pos.getId(), pos.getSymbol(), e.getMessage());
            }
        }

        // ── 2. 自動補掛：ocoOrderListId=null 的倉位自動重試 OCO ────────────────
        try {
            retryUnprotectedPositions();
        } catch (Exception e) {
            log.error("[OcoPoll] retryUnprotectedPositions failed: {}", e.getMessage());
        }

        // ── 3. 對帳 ────────────────────────────────────────────────────────────
        try {
            reconcileHoldings();
        } catch (Exception e) {
            log.error("[Reconcile] reconcileHoldings failed: {}", e.getMessage());
        }

    }

    /**
     * 找出所有無 OCO 保護的開倉（ocoOrderListId=null），自動補掛。
     * 最多重試 {@value #OCO_MAX_RETRIES} 次，超過後發 TG 放棄通知並靜默，等人工處理。
     */
    private void retryUnprotectedPositions() {
        List<BtLiveSignal> unprotected =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNull()
                        .stream()
                        .filter(position -> !"SHORT".equals(position.getSide()))
                        .toList();
        if (unprotected.isEmpty()) return;

        log.info("[OcoPoll] Found {} unprotected position(s), attempting auto OCO retry", unprotected.size());

        for (BtLiveSignal pos : unprotected) {
            if (spotPositionCloseService.isClosing(pos.getId())) {
                log.debug("[OcoPoll] skip OCO retry while scoped market close is in progress: id={}", pos.getId());
                continue;
            }
            if (BtcBasePositionStatePolicy.isBtcBase(pos)) {
                log.info("[OcoPoll] BTC_BASE position id={} state={} intentionally suppresses auto OCO retry",
                        pos.getId(), BtcBasePositionStatePolicy.managementState(pos));
                continue;
            }
            if (isSoftExitNoHardSl(pos)) {
                log.info("[OcoPoll] Soft-exit position id={} intentionally has no hard OCO; auto retry suppressed",
                        pos.getId());
                continue;
            }
            int attempts = ocoRetryCount.getOrDefault(pos.getId(), 0);

            // 已超過上限：僅在剛達到上限時發一次放棄通知，之後靜默
            if (attempts >= OCO_MAX_RETRIES) {
                if (attempts == OCO_MAX_RETRIES) {
                    log.warn("[OcoPoll] OCO retry give up: id={} symbol={} after {} attempts",
                            pos.getId(), pos.getSymbol(), OCO_MAX_RETRIES);
                    try {
                        notificationPort.broadcast(String.format(
                                "🚨 <b>OCO 補掛放棄</b>\n倉位 #%d %s 已連續失敗 %d 次，需人工處理",
                                pos.getId(), pos.getSymbol(), OCO_MAX_RETRIES), true);
                    } catch (Exception ignored) {}
                    ocoRetryCount.put(pos.getId(), OCO_MAX_RETRIES + 1); // 標記已發通知
                }
                continue;
            }

            try {
                autoRetryOco(pos);
                ocoRetryCount.remove(pos.getId()); // 成功時清除計數
            } catch (Exception e) {
                int next = attempts + 1;
                ocoRetryCount.put(pos.getId(), next);
                log.error("[OcoPoll] Auto OCO retry error ({}/{}): id={} symbol={} error={}",
                        next, OCO_MAX_RETRIES, pos.getId(), pos.getSymbol(), e.getMessage());
            }
        }
    }

    private boolean isSoftExitNoHardSl(BtLiveSignal pos) {
        return pos != null
                && pos.getFilterReason() != null
                && pos.getFilterReason().startsWith(OcoManagementService.SOFT_EXIT_NO_HARD_SL_MARKER);
    }

    private void autoRetryOco(BtLiveSignal pos) {
        if (pos.getTradedQty() == null || pos.getSuggestedTp() == null || pos.getSuggestedSl() == null) {
            log.warn("[OcoPoll] Skipping unprotected pos id={}: missing qty/tp/sl", pos.getId());
            return;
        }

        // SHORT 倉位直接轉交 OcoManagementService（已支援 SWAP 路徑）
        if ("SHORT".equals(pos.getSide())) {
            log.info("[OcoPoll] Auto SWAP OCO retry: id={} symbol={}", pos.getId(), pos.getSymbol());
            try {
                ocoManagementService.retryOco(pos.getId());
            } catch (Exception e) {
                log.error("[OcoPoll] Auto SWAP OCO retry failed: id={} error={}", pos.getId(), e.getMessage());
            }
            return;
        }

        // LONG（現貨）路徑
        String symbol = pos.getSymbol();
        String base   = symbol.replace("USDT", "");

        BigDecimal availBal;
        try {
            availBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> base.equals(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
        } catch (Exception e) {
            log.warn("[OcoPoll] Cannot query OKX availBal for id={}: {}", pos.getId(), e.getMessage());
            return;
        }

        // #407 follow-up — effective-avail logic identical to OcoManagementService.
        // autoRetryOco only fires when ocoOrderListId == null, so cancel-then-place
        // is not in play here; availBal is final. But the previous code paired
        // availBal.min(dbQty) with `if (ocoQty != dbQty) setTradedQty(ocoQty)`
        // which permanently corrupted the position's traded_qty whenever
        // availBal was below dbQty (e.g. Grid HOLDING locked some BTC).
        // reconcileHoldings then over-reported the gap as untracked BTC.
        BigDecimal dbQty = pos.getTradedQty();
        BigDecimal effectiveAvail = pos.getOcoOrderListId() != null
                ? availBal.add(dbQty)  // dbQty would release on cancel — but autoRetryOco's filter
                                        // requires ocoOrderListId==null, so this branch is defensive
                : availBal;            // no active OCO — availBal is final

        if (effectiveAvail.compareTo(RECONCILE_THRESHOLD) < 0) {
            log.warn("[OcoPoll] effectiveAvail={} for {} is near 0, pos id={} may already be sold (raw availBal={})",
                    effectiveAvail, base, pos.getId(), availBal);
            return;
        }

        BigDecimal ocoQty = dbQty.min(effectiveAvail);
        if (ocoQty.compareTo(dbQty) < 0) {
            log.warn("[OcoPoll] partial OCO coverage: ocoQty={} < dbQty={} (raw availBal={}, BTC locked elsewhere — Grid HOLDING / other algo?)",
                    ocoQty, dbQty, availBal);
        }
        log.info("[OcoPoll] Auto OCO retry: id={} symbol={} qty={}", pos.getId(), symbol, ocoQty);

        Long algoId;
        try {
            algoId = okxTradingService.placeOco(symbol, ocoQty, pos.getSuggestedTp(), pos.getSuggestedSl());
        } catch (Exception e) {
            // 拋出讓 retryUnprotectedPositions 的計數器處理，避免重複 TG 通知
            throw new RuntimeException("placeOco failed: " + e.getMessage(), e);
        }

        pos.setOcoOrderListId(algoId);
        // #407 follow-up — DO NOT mutate tradedQty here. tradedQty is the
        // historical record of what was actually purchased; corrupting it to
        // ocoQty (which may be < tradedQty under Grid HOLDING contention)
        // breaks reconcileHoldings and PnL reporting downstream.
        pos.setOcoQty(ocoQty);  // 記錄實際 OCO 委託量，供 checkAndClose PnL 計算使用
        liveSignalRepository.save(pos);

        log.info("[OcoPoll] Auto OCO retry OK: id={} symbol={} algoId={}", pos.getId(), symbol, algoId);
        try {
            notificationPort.broadcast(String.format(
                    "✅ <b>自動補掛 OCO 成功</b>\n倉位 #%d %s\n數量: %s | TP: %s | SL: %s\n新 AlgoId: %d",
                    pos.getId(), symbol, ocoQty.toPlainString(),
                    pos.getSuggestedTp().toPlainString(), pos.getSuggestedSl().toPlainString(),
                    algoId), true);
        } catch (Exception ignored) {}
    }

    /**
     * OKX Private WebSocket 推送入口：algo-orders 狀態變為 filled/canceled 時立即觸發。
     * 從 DB 查出對應倉位後呼叫 checkAndClose()，exitTimeIsNull 作為天然冪等保護。
     */
    public void handleAlgoFillPush(Long algoId) {
        if (!ocoPollerEnabled) {
            log.debug("[WsPush] OCO poller disabled, ignoring algo fill push: algoId={}", algoId);
            return;
        }
        liveSignalRepository
                .findFirstByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListId(algoId)
                .ifPresentOrElse(pos -> {
                    log.info("[WsPush] Handling algo fill: algoId={} posId={} symbol={}",
                            algoId, pos.getId(), pos.getSymbol());
                    try {
                        checkAndClose(pos);
                    } catch (Exception e) {
                        log.error("[WsPush] checkAndClose error: algoId={} posId={} err={}",
                                algoId, pos.getId(), e.getMessage());
                    }
                }, () -> log.debug("[WsPush] No open position for algoId={}", algoId));
    }

    void checkAndClose(BtLiveSignal candidate) {
        if (candidate == null || candidate.getId() == null) return;
        try (PositionMutationGuard.Lease lease = PositionMutationGuard.tryAcquire(
                candidate.getId(), "OCO_POLLER_RECONCILIATION")) {
            if (!lease.acquired()) {
                log.debug("[OcoPoll] skip position={} while mutation={} is active",
                        candidate.getId(), lease.activeOperation());
                return;
            }
            BtLiveSignal fresh = liveSignalRepository.findById(candidate.getId()).orElse(null);
            if (fresh == null || fresh.getExitTime() != null || fresh.getOcoOrderListId() == null) return;
            checkAndCloseLocked(fresh);
        }
    }

    private void checkAndCloseLocked(BtLiveSignal pos) {
        boolean isShort = "SHORT".equals(pos.getSide());
        OcoOrderStateInspector.Inspection inspection = isShort
                ? ocoOrderStateInspector.inspectSwap(pos.getSymbol(), pos.getOcoOrderListId())
                : ocoOrderStateInspector.inspectSpot(pos.getSymbol(), pos.getOcoOrderListId());
        if (!inspection.queryComplete() && !inspection.filled()) {
            log.warn("[OcoPoll] OCO inspection incomplete: id={} algoId={} side={} errors={}",
                    pos.getId(), pos.getOcoOrderListId(), isShort ? "SHORT" : "LONG", inspection.errors());
            return;
        }

        String state = inspection.effectiveState();
        if (inspection.active()) {
            log.debug("[OcoPoll] Still live: id={} algoId={} state={}",
                    pos.getId(), pos.getOcoOrderListId(), inspection.parentState());
            return;
        }
        if (inspection.filledChildOrderId() != null) {
            log.info("[OcoPoll] {} OCO child filled: id={} parentState={} childOrdId={} avgPx={}",
                    isShort ? "SWAP" : "SPOT", pos.getId(), inspection.parentState(),
                    inspection.filledChildOrderId(), inspection.fillPrice());
        }
        if (!inspection.filled() && !inspection.canceled()) {
            log.warn("[OcoPoll] Unexpected algo state: id={} algoId={} state={}", pos.getId(), pos.getOcoOrderListId(), state);
            return;
        }

        String avgPxStr = inspection.fillPrice() == null ? "" : inspection.fillPrice().toPlainString();
        if (avgPxStr.isEmpty() || "0".equals(avgPxStr)) {
            if ("filled".equals(state)) {
                log.warn("[OcoPoll] state=filled but avgPx empty, will retry next cycle: id={} algoId={}",
                        pos.getId(), pos.getOcoOrderListId());
                return;
            }
            Long cancelledAlgoId = pos.getOcoOrderListId();
            log.info("[OcoPoll] Algo canceled with no fill: id={} algoId={}", pos.getId(), cancelledAlgoId);
            // Re-fetch to guard against race condition: retryOco may have already updated
            // ocoOrderListId to a new algoId between the time we fetched pos and now.
            BtLiveSignal freshPos = liveSignalRepository.findById(pos.getId()).orElse(null);
            if (freshPos == null || !cancelledAlgoId.equals(freshPos.getOcoOrderListId())) {
                log.info("[OcoPoll] Skipping null-clear: cancelled algoId={} already replaced by {} in pos#{}",
                        cancelledAlgoId,
                        freshPos != null ? freshPos.getOcoOrderListId() : "N/A",
                        pos.getId());
                return;
            }
            freshPos.setOcoOrderListId(null);
            liveSignalRepository.save(freshPos);
            if (BtcBasePositionStatePolicy.isAdoptionPending(freshPos)) {
                log.info("[OcoPoll] BTC_BASE adoption cancellation observed: id={} algoId={}; " +
                                "leaving position open and suppressing manual-cancel alert",
                        pos.getId(), cancelledAlgoId);
                return;
            }
            try {
                notificationPort.broadcast(String.format(
                        "⚠️ <b>OCO 已取消（無成交）</b>\n%s 倉位 #%d [%s] 的 OCO 訂單已被取消且無成交。\n倉位仍開倉中，請手動處理。",
                        pos.getSymbol(), pos.getId(), isShort ? "SHORT" : "LONG"), true);
            } catch (Exception e) {
                log.error("[OcoPoll] TG notify failed for manual cancel: {}", e.getMessage());
            }
            return;
        }

        // Re-fetch before writing exit data:
        // (1) Guard against double-close: WS push + scheduled OcoPoll may both reach here concurrently.
        // (2) Ensure latest tradedQty is used in case retryOco updated it after this pos was fetched.
        BtLiveSignal freshPos = liveSignalRepository.findById(pos.getId()).orElse(null);
        if (freshPos == null || freshPos.getExitTime() != null) {
            log.info("[OcoPoll] Position already closed, skipping duplicate close: id={}", pos.getId());
            return;
        }
        pos = freshPos;

        BigDecimal exitPrice = new BigDecimal(avgPxStr);
        LocalDateTime exitTime = LocalDateTime.now(ZoneOffset.UTC);
        BigDecimal refEntry = pos.getActualEntryPrice() != null
                ? pos.getActualEntryPrice() : pos.getEntryPrice();

        // TP/SL 判斷：LONG: exitPrice >= midpoint = TP；SHORT 方向相反，exitPrice <= midpoint = TP
        String exitReason;
        if (pos.getSuggestedTp() != null && pos.getSuggestedSl() != null) {
            BigDecimal midpoint = pos.getSuggestedTp().add(pos.getSuggestedSl())
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
            if (isShort) {
                exitReason = exitPrice.compareTo(midpoint) <= 0 ? "TP" : "SL";
            } else {
                exitReason = exitPrice.compareTo(midpoint) >= 0 ? "TP" : "SL";
            }
        } else {
            exitReason = exitPrice.compareTo(refEntry) >= 0 ? "TP" : "SL";
        }

        pos.setExitPrice(exitPrice);
        pos.setExitTime(exitTime);
        pos.setExitReason(exitReason);
        ocoRetryCount.remove(pos.getId()); // 倉位已平，清除重試計數

        // PnL：LONG = exitPrice - entry；SHORT = entry - exitPrice（空頭獲利來自下跌）
        double pnlPct;
        BigDecimal realizedPnl;
        // ocoQty 優先：記錄了 OCO 實際委託量，可能因 Grid HOLDING level 鎖定部分 BTC 而小於 tradedQty。
        // 回落至 tradedQty 以向下相容 V066 migration 前的舊資料。
        BigDecimal contracts = pos.getOcoQty() != null ? pos.getOcoQty()
                : pos.getTradedQty() != null ? pos.getTradedQty() : BigDecimal.ZERO;
        // SHORT qty is contract count; multiply by ctVal to get base-currency exposure
        BigDecimal effectiveQty = isShort
                ? contracts.multiply(BigDecimal.valueOf(okxTradingService.getContractSizeInBase(pos.getSymbol())))
                : contracts;
        if (isShort && refEntry != null) {
            pnlPct = refEntry.subtract(exitPrice)
                    .divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
            realizedPnl = refEntry.subtract(exitPrice)
                    .multiply(effectiveQty).setScale(8, RoundingMode.HALF_UP);
        } else if (refEntry != null) {
            pnlPct = exitPrice.subtract(refEntry)
                    .divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
            realizedPnl = exitPrice.subtract(refEntry)
                    .multiply(effectiveQty).setScale(8, RoundingMode.HALF_UP);
        } else {
            pnlPct = 0;
            realizedPnl = BigDecimal.ZERO;
        }
        pos.setRealizedPnl(realizedPnl);
        liveSignalRepository.save(pos);

        log.info("[OcoPoll] Position closed by OCO: id={} symbol={} side={} reason={} exitPrice={} pnl={}%",
                pos.getId(), pos.getSymbol(), pos.getSide(), exitReason, exitPrice,
                String.format("%.2f", pnlPct * 100));

        String sideLabel = isShort ? "📉 SHORT" : "📈 LONG";
        String emoji = "TP".equals(exitReason) ? "🎯" : "🛡";
        String msg = String.format(
                "%s <b>OCO 已出場 %s</b> [%s]\n" +
                "📋 原因: <b>%s</b>\n" +
                "💰 出場均價: <b>$%s</b>\n" +
                "📥 入場均價: $%s\n" +
                "損益: <b>%+.2f%%</b> (%+.2f USDT)",
                emoji, pos.getSymbol(), sideLabel,
                exitReason, formatPrice(exitPrice),
                refEntry != null ? formatPrice(refEntry) : "N/A",
                pnlPct * 100, realizedPnl.doubleValue());
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.error("[OcoPoll] TG notify failed: {}", e.getMessage());
        }

    }

    private String formatPrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%,.2f", price.doubleValue());
        }
        return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    // ──────────────────────────────────────────────
    //  對帳：OKX 現貨餘額 vs DB 開倉記錄
    // ──────────────────────────────────────────────

    private static final BigDecimal RECONCILE_THRESHOLD = new BigDecimal("0.0001");

    /**
     * 比對 OKX 現貨持倉與 DB 開倉記錄，偵測未追蹤的手動倉位或已成交但 DB 未更新的情況。
     * 僅發警告，不自動修正。
     */
    private void reconcileHoldings() {
        // 1. DB：所有未出場的 autoTrade 倉位，按 base currency 加總 tradedQty
        //    SHORT 倉位是 SWAP 合約，不在現貨帳戶，需排除以避免誤報
        List<BtLiveSignal> openPositions =
                liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        Map<String, BigDecimal> dbQtyByBase = new HashMap<>();
        for (BtLiveSignal pos : openPositions) {
            if (pos.getTradedQty() == null) continue;
            if ("SHORT".equals(pos.getSide())) continue; // SWAP 合約不計入現貨對帳
            String base = pos.getSymbol().replace("USDT", ""); // "ETHUSDT" → "ETH"
            dbQtyByBase.merge(base, pos.getTradedQty(), BigDecimal::add);
        }

        // 1c. OKX 上 pending 的 OCO algo 訂單(side=sell)鎖住的 base qty 也算 managed,
        //     涵蓋兩種場景:
        //     (a) /admin/oco/market-buy 手動買入 + OCO 但沒寫 bt_live_signal
        //     (b) DB 紀錄遺失但 OKX 端 OCO 仍活著的 edge case
        //     防雙計:bt_live_signal 已記錄 ocoOrderListId 的算過了,跳過同 algoId。
        //     失敗時 fall back 不影響主流程(原本行為就是會 TG warn)。
        try {
            java.util.Set<Long> dbTrackedAlgoIds = new java.util.HashSet<>();
            for (BtLiveSignal pos : openPositions) {
                if (pos.getOcoOrderListId() != null) {
                    dbTrackedAlgoIds.add(pos.getOcoOrderListId());
                }
            }
            JsonNode pendingAlgos = okxTradingService.getPendingOcoAlgos();
            for (JsonNode algo : pendingAlgos) {
                long algoId = algo.path("algoId").asLong(0);
                if (algoId == 0 || dbTrackedAlgoIds.contains(algoId)) continue;
                if (!"sell".equalsIgnoreCase(algo.path("side").asText(""))) continue;
                String instId = algo.path("instId").asText("");
                if (!instId.endsWith("-USDT")) continue;
                String currency = instId.substring(0, instId.indexOf('-'));
                String szStr = algo.path("sz").asText("0");
                if (szStr.isEmpty() || "0".equals(szStr)) continue;
                try {
                    BigDecimal sz = new BigDecimal(szStr);
                    if (sz.signum() > 0) {
                        dbQtyByBase.merge(currency, sz, BigDecimal::add);
                        log.debug("[Reconcile] +OCO-locked {} qty={} (algoId={})", currency, sz, algoId);
                    }
                } catch (NumberFormatException nfe) {
                    log.warn("[Reconcile] bad sz '{}' on algoId={}", szStr, algoId);
                }
            }
        } catch (Exception e) {
            log.warn("[Reconcile] OCO algo enumeration failed (will fall back to old behavior): {}",
                    e.getMessage());
        }

        // 2. OKX：查詢現貨持倉（cashBal > 0，排除 USDT）
        List<OkxTradingService.SpotHolding> holdings = okxTradingService.getSpotHoldings();

        // 3a. OKX 有持倉但 DB 沒有（或少了）→ 可能是手動倉位
        for (OkxTradingService.SpotHolding h : holdings) {
            if ("USDT".equals(h.ccy)) continue;
            if (h.cashBal.compareTo(RECONCILE_THRESHOLD) < 0) continue;
            BigDecimal dbQty = dbQtyByBase.getOrDefault(h.ccy, BigDecimal.ZERO);
            BigDecimal diff = h.cashBal.subtract(dbQty);
            if (diff.compareTo(RECONCILE_THRESHOLD) > 0) {
                BigDecimal diffNotionalUsdt = estimateDiffNotionalUsdt(h, diff);
                if (isBelowUntrackedNotionalThreshold(diffNotionalUsdt)) {
                    untrackedHoldingTracker.clear(h.ccy);
                    log.info("[Reconcile] ignore small untracked holding {}: diff={} estimatedNotionalUsdt={} thresholdUsdt={}",
                            h.ccy, diff, diffNotionalUsdt, untrackedMinNotionalUsdt);
                    continue;
                }
                LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
                boolean confirmed = untrackedHoldingTracker.confirmOrSeed(h.ccy, diff, now);
                if (!confirmed) {
                    log.info("[Reconcile] untracked candidate seeded {} diff={} — wait for confirmation",
                            h.ccy, diff);
                    continue;
                }
                log.warn("[Reconcile] 發現未追蹤持倉 {}: OKX cashBal={} DB openQty={} 差={}",
                        h.ccy, h.cashBal, dbQty, diff);
                // #380 — dedup 同 currency 24h 只發 1 次（舊 orphan 卡死時不洗版）
                // 不含 diff 數字 — fee dust 微變化（如 0.000127265 → 0.000127300）
                // round 到 6 位後仍可能跨界產生新 key，dedup 即失效。同 currency 24h 1 條 alert 即足夠。
                String dedupKey = "Reconcile:Untracked:" + h.ccy;
                if (!tgDeduper.shouldSend(dedupKey, Duration.ofHours(24), Severity.WARN)) {
                    log.info("[Reconcile] untracked alert suppressed (deduper) {} diff={}", h.ccy, diff);
                    continue;
                }
                // #340 Phase 2: 帶 OKX trade context 提供精確診斷
                String orphanContext = "";
                try {
                    String report = orphanReconciler.reconcile(h.ccy, 24, 10.0, 0.5, 5, false);
                    orphanContext = "\n\n<code>" + escapeHtml(truncate(report, 1500)) + "</code>";
                } catch (Exception e) {
                    log.warn("[Reconcile] orphan reconciler context fetch failed: {}", e.getMessage());
                }
                try {
                    notificationPort.alert(String.format(
                            "⚠️ <b>發現未追蹤持倉</b>\n幣種: %s\nOKX 實際: %s\nDB 記錄: %s\n差額: %s\n請確認是否為手動倉位或 distributed-tx gap (#340)。%s",
                            h.ccy, h.cashBal.toPlainString(),
                            dbQty.toPlainString(), diff.toPlainString(), orphanContext),
                            true, dedupKey, "WARN");
                } catch (Exception e) {
                    log.error("[Reconcile] TG notify failed: {}", e.getMessage());
                }
            } else {
                untrackedHoldingTracker.clear(h.ccy);
            }
        }
        untrackedHoldingTracker.cleanup(LocalDateTime.now(ZoneOffset.UTC));

        // DB 有倉位但 OKX 無餘額只記錄告警。實際關閉由 OCO provider
        // fill evidence 驅動，避免以模糊餘額自動改寫持倉。
        for (Map.Entry<String, BigDecimal> entry : dbQtyByBase.entrySet()) {
            String base = entry.getKey();
            BigDecimal dbQty = entry.getValue();
            if (dbQty.compareTo(RECONCILE_THRESHOLD) < 0) continue;
            boolean foundInOkx = holdings.stream().anyMatch(h ->
                    h.ccy.equals(base) && h.cashBal.compareTo(RECONCILE_THRESHOLD) >= 0);
            if (!foundInOkx) {
                log.warn("[Reconcile] DB has {} open qty={} but OKX has no visible balance; no mutation without OCO fill evidence",
                        base, dbQty);
            }
        }

    }


    private boolean isBelowUntrackedNotionalThreshold(BigDecimal diffNotionalUsdt) {
        if (untrackedMinNotionalUsdt == null || untrackedMinNotionalUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (diffNotionalUsdt == null || diffNotionalUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return diffNotionalUsdt.compareTo(untrackedMinNotionalUsdt) < 0;
    }

    private BigDecimal estimateDiffNotionalUsdt(OkxTradingService.SpotHolding holding, BigDecimal diff) {
        if (holding == null || diff == null || diff.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        if (holding.eqUsd != null && holding.eqUsd.compareTo(BigDecimal.ZERO) > 0
                && holding.cashBal != null && holding.cashBal.compareTo(BigDecimal.ZERO) > 0) {
            return holding.eqUsd.multiply(diff).divide(holding.cashBal, 8, RoundingMode.HALF_UP);
        }
        try {
            BigDecimal price = okxTradingService.getLastPrice(holding.ccy + "USDT");
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                return null;
            }
            return diff.multiply(price).setScale(8, RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }


    /**
     * 查詢 OCO algo 訂單是否仍處於活躍狀態（live/effective/pause）。
     * 用於 reconcile 前的保護：cashBal=0 可能只是 ETH 被鎖在活躍掛單中，並非已售出。
     * 查詢失敗時保守回傳 true，避免在 OCO 狀態未知時自動關閉 DB 記錄。
     */
    private boolean isOcoStillActive(BtLiveSignal pos) {
        if (pos.getOcoOrderListId() == null) return false;
        boolean isShort = "SHORT".equals(pos.getSide());
        OcoOrderStateInspector.Inspection inspection = isShort
                ? ocoOrderStateInspector.inspectSwap(pos.getSymbol(), pos.getOcoOrderListId())
                : ocoOrderStateInspector.inspectSpot(pos.getSymbol(), pos.getOcoOrderListId());
        if (!inspection.queryComplete() && !inspection.filled()) {
            log.warn("[Reconcile] Cannot confirm OCO state for id={}: {}", pos.getId(), inspection.errors());
            return true;
        }
        return inspection.active();
    }


    // ──────────────────────────────────────────────
    //  每日 P&L 彙報（UTC 00:00）
    // ──────────────────────────────────────────────

    /**
     * 供 DailyTgReportOrchestrator 合併日報用，回傳昨日成交明細 + 當前持倉浮動損益，不發送 TG。
     * 不含聚合統計（勝率/總PnL 由 DailyReportScheduler.buildSummary 處理）。
     */
    public String buildPnlContent(LocalDateTime dayStart, LocalDateTime dayEnd) {
        StringBuilder sb = new StringBuilder();
        try {
            // 昨日已出場明細
            List<BtLiveSignal> closed = liveSignalRepository
                    .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(dayStart)
                    .stream().filter(p -> p.getExitTime() != null && p.getExitTime().isBefore(dayEnd))
                    .collect(java.util.stream.Collectors.toList());
            if (!closed.isEmpty()) {
                sb.append("<b>昨日成交明細</b>\n");
                for (BtLiveSignal pos : closed) {
                    boolean win = "TP".equals(pos.getExitReason());
                    BigDecimal pnl = pos.getRealizedPnl();
                    sb.append(String.format("%s %s #%d → %s USDT (%s)\n",
                            win ? "✅" : "❌", pos.getSymbol(), pos.getId(),
                            pnl != null ? String.format("%+.2f", pnl.doubleValue()) : "?",
                            pos.getExitReason()));
                }
            }
            // 當前持倉浮動損益
            List<BtLiveSignal> open = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
            if (!open.isEmpty()) {
                BigDecimal totalFloat = BigDecimal.ZERO;
                StringBuilder openLines = new StringBuilder();
                for (BtLiveSignal pos : open) {
                    try {
                        BigDecimal cur = okxTradingService.getLastPrice(pos.getSymbol());
                        BigDecimal ref = pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
                        if (cur != null && ref != null && pos.getTradedQty() != null) {
                            boolean isShort = "SHORT".equals(pos.getSide());
                            BigDecimal diff = isShort ? ref.subtract(cur) : cur.subtract(ref);
                            BigDecimal qty = isShort
                                    ? pos.getTradedQty().multiply(BigDecimal.valueOf(okxTradingService.getContractSizeInBase(pos.getSymbol())))
                                    : pos.getTradedQty();
                            BigDecimal fl = diff.multiply(qty).setScale(2, RoundingMode.HALF_UP);
                            double pct = diff.divide(ref, 6, RoundingMode.HALF_UP).doubleValue() * 100;
                            totalFloat = totalFloat.add(fl);
                            openLines.append(String.format("  %s #%d %+.2f (%+.1f%%) @ $%s\n",
                                    pos.getSymbol(), pos.getId(), fl.doubleValue(), pct, formatPrice(cur)));
                        }
                    } catch (Exception ignored) {}
                }
                sb.append(String.format("<b>持倉浮動</b> %d 筆 合計 %+.2f USDT\n",
                        open.size(), totalFloat.doubleValue()));
                sb.append(openLines);
            }
        } catch (Exception e) { log.warn("[DailyReport] buildPnlContent: {}", e.getMessage()); }
        return sb.toString().trim();
    }

    /** 每天 UTC 00:00。@Scheduled 已移至 DailyTgReportOrchestrator（step 2，串行自然錯開 TG）。 */
    public void sendDailyPnlReport() {
        if (!tradingProperties.isEnabled()) return;

        LocalDateTime now    = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime dayStart = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS).minusDays(1); // 昨天 00:00
        LocalDateTime dayEnd   = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS);              // 今天 00:00

        // 昨日已出場的自動交易倉位
        List<BtLiveSignal> closed = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(dayStart)
                .stream()
                .filter(p -> p.getExitTime() != null && p.getExitTime().isBefore(dayEnd))
                .collect(java.util.stream.Collectors.toList());

        int total = closed.size();
        int wins  = 0;
        int losses = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;

        StringBuilder tradeLines = new StringBuilder();
        for (BtLiveSignal pos : closed) {
            boolean isWin = "TP".equals(pos.getExitReason());
            if (isWin) wins++; else losses++;

            BigDecimal pnl = pos.getRealizedPnl();
            if (pnl != null) totalPnl = totalPnl.add(pnl);

            String pnlStr = pnl != null ? String.format("%+.2f", pnl.doubleValue()) : "?";
            String emoji  = isWin ? "✅" : "❌";
            tradeLines.append(String.format("%s %s #%d → %s USDT (%s)\n",
                    emoji, pos.getSymbol(), pos.getId(), pnlStr, pos.getExitReason()));
        }

        // 當前持倉浮動損益
        List<BtLiveSignal> openPositions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        StringBuilder openLines = new StringBuilder();
        BigDecimal totalFloat = BigDecimal.ZERO;
        for (BtLiveSignal pos : openPositions) {
            try {
                BigDecimal currentPrice = okxTradingService.getLastPrice(pos.getSymbol());
                BigDecimal refEntry = pos.getActualEntryPrice() != null
                        ? pos.getActualEntryPrice() : pos.getEntryPrice();
                if (currentPrice != null && refEntry != null && pos.getTradedQty() != null) {
                    boolean posIsShort = "SHORT".equals(pos.getSide());
                    // SHORT：price 下跌 = 獲利；qty = contract count → multiply by ctVal
                    BigDecimal priceDiff = posIsShort
                            ? refEntry.subtract(currentPrice)
                            : currentPrice.subtract(refEntry);
                    BigDecimal effectiveQty = posIsShort
                            ? pos.getTradedQty().multiply(BigDecimal.valueOf(okxTradingService.getContractSizeInBase(pos.getSymbol())))
                            : pos.getTradedQty();
                    BigDecimal floatPnl = priceDiff.multiply(effectiveQty).setScale(2, RoundingMode.HALF_UP);
                    double floatPct = priceDiff.divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
                    totalFloat = totalFloat.add(floatPnl);
                    openLines.append(String.format("📦 %s #%d %+.2f USDT (%+.2f%%) @ $%s\n",
                            pos.getSymbol(), pos.getId(),
                            floatPnl.doubleValue(), floatPct * 100,
                            formatPrice(currentPrice)));
                }
            } catch (Exception e) {
                openLines.append(String.format("📦 %s #%d 無法取得現價\n", pos.getSymbol(), pos.getId()));
            }
        }

        String winRate = total > 0 ? String.format("%.0f%%", (double) wins / total * 100) : "N/A";
        String msg = String.format(
                "📊 <b>每日交易彙報</b> %s\n\n" +
                "<b>昨日成交</b>：%d 筆（✅ %d 盈 / ❌ %d 虧，勝率 %s）\n" +
                "昨日已實現損益：<b>%+.2f USDT</b>\n" +
                "%s\n" +
                "<b>當前持倉</b>：%d 筆，浮動 %+.2f USDT\n%s",
                dayStart.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                total, wins, losses, winRate,
                totalPnl.doubleValue(),
                tradeLines.length() > 0 ? tradeLines.toString() : "（昨日無成交）\n",
                openPositions.size(), totalFloat.doubleValue(),
                openLines.length() > 0 ? openLines.toString() : "（無持倉）");

        try {
            notificationPort.broadcast(msg, true);
            log.info("[DailyReport] Sent: trades={} pnl={} openPositions={}",
                    total, totalPnl, openPositions.size());
        } catch (Exception e) {
            log.error("[DailyReport] TG send failed: {}", e.getMessage());
        }
    }

    /** TG HTML mode escape for context block (avoid breaking formatting on user content). */
    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "\n…(truncated)";
    }
}
