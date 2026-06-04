package com.agora.service.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.GeminiMarketHint;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.util.RegimeI18n;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Grid Trading Manager — 每 5 分鐘掃所有 active grid,檢查價格觸發 buy/sell。
 *
 * <p><b>狀態機(每 level)</b>:
 * <pre>
 * PENDING ──(price cross-down)──→ HOLDING ──(price cross-up)──→ CLOSED ──(recycle)──→ PENDING
 * PENDING ──(buy 失敗)──→ BUY_FAILED ──(scanner 自動重置)──→ PENDING
 * HOLDING ──(sell 失敗)──→ SELL_FAILED ──(scanner retry ×3)──→ HOLDING 或留 SELL_FAILED
 * </pre>
 *
 * <p><b>保護機制</b>:
 * <ul>
 *   <li>Stop-out:現價 outside [lower×(1-stopOutPct), upper×(1+stopOutPct)] → 全平 + 關閉 grid</li>
 *   <li>Hint gate:Gemini hint regime 不在 grid.regimeWhitelist → 暫停(可恢復)</li>
 *   <li>DailyLossGuard / max-open-positions 由系統其他層共用</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GridManagerService {

    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository levelRepository;
    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final GeminiMarketHintRepository hintRepository;
    private final NotificationPort notificationPort;
    private final com.agora.config.properties.TradingGridProperties props;
    /** #443 Gap 2 — Grid trade events 寫 bt_decision_audit,即使 TG 失敗也有 source-of-truth。 */
    private final DecisionAuditWriter auditWriter;

    /** 記錄已發過老化警報的 level id → 日期,避免重複發送 */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.time.LocalDate> agingAlertSent =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * #436 — 連續同一 level 51020 (qty &lt; minSz) 計數。Bug A+B 修完後正常 0,
     * 但若有未預見的 race (e.g. minSz 上調 / OKX 改規) 會立即累積 → 連續 3 次強制終止 + Critical alert。
     * 重啟後清零(JVM restart 通常意味著 new fix deploy,不需 persist)。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Integer> consecutive51020Count =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final int SPAM_51020_THRESHOLD = 3;

    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final int PRICE_SCALE = 8;

    /**
     * #436 — OKX SPOT minSz per base ccy. availBal < minSz 時 OKX 一定回 51020(qty < minSz),
     * 直接 OKX 呼叫等於白燒 quota + 主帳戶 dust 永遠卡 SELL_FAILED retry loop。
     * 預設 0.00001(BTC),其他 base 用同 conservative default。
     */
    private static final BigDecimal DEFAULT_MIN_SZ = new BigDecimal("0.00001");
    private static final java.util.Map<String, BigDecimal> MIN_SZ_BY_BASE = java.util.Map.of(
            "BTC", new BigDecimal("0.00001"),
            "ETH", new BigDecimal("0.0001")
    );

    /**
     * 記憶體快取每個 grid 上次 check 時的 price,用於 cross-detection。
     * 第一次 check(prev=null)只記 price 不觸發,避免初始化時所有
     * 「level.price ≥ currentPrice」的 PENDING level 同時 tryBuy 造成一次性大倉。
     * 重啟後 map 清空,第一次掃再次只記不動,第二次才開始正常偵測 cross。
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, BigDecimal> lastPriceByGrid =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 預設每 5 分鐘檢查一次。fixedDelay 從上一次 finish 算,避免 overlap。
     */
    @Scheduled(fixedDelayString = "${trading.grid.check-interval-ms:300000}")
    public void checkAllGrids() {
        if (!props.enabled()) return;
        List<BtGrid> active = gridRepository.findByEnabledTrueAndClosedAtIsNull();
        if (active.isEmpty()) return;
        log.debug("[Grid] Checking {} active grids", active.size());
        for (BtGrid grid : active) {
            try {
                checkGrid(grid);
            } catch (Exception e) {
                log.error("[Grid] id={} check failed: {}", grid.getId(), e.getMessage(), e);
            }
        }
    }

    private void checkGrid(BtGrid grid) {
        BigDecimal price;
        try {
            price = okxTradingService.getLastPrice(grid.getSymbol());
        } catch (Exception e) {
            log.warn("[Grid] id={} cannot fetch price: {}", grid.getId(), e.getMessage());
            return;
        }
        if (price == null || price.signum() <= 0) return;

        // 1. Stop-out check(優先)
        if (grid.getStopOutPct() != null && grid.getStopOutPct().signum() > 0) {
            BigDecimal stopLow = grid.getPriceLower().multiply(ONE.subtract(grid.getStopOutPct()));
            BigDecimal stopHigh = grid.getPriceUpper().multiply(ONE.add(grid.getStopOutPct()));
            if (price.compareTo(stopLow) < 0 || price.compareTo(stopHigh) > 0) {
                stopOutGrid(grid, price, stopLow, stopHigh);
                return;
            }
        }

        // 2. Hint gate
        if (Boolean.TRUE.equals(grid.getHintGated())) {
            boolean allowed = isAllowedByHint(grid);
            if (!allowed) {
                if (grid.getPausedAt() == null) {
                    grid.setPausedAt(LocalDateTime.now());
                    grid.setPausedReason("hint regime not in whitelist " + grid.getRegimeWhitelist());
                    grid.setUpdatedAt(LocalDateTime.now());
                    gridRepository.save(grid);
                    log.info("[Grid] id={} {} paused — hint regime mismatch", grid.getId(), grid.getSymbol());
                    notifyTg(String.format("⏸ <b>網格 #%d %s 暫停</b>\n原因: 當前市場形態不適合網格交易\n適用形態: %s\n<i>→ 市場切換後 5 分鐘內自動恢復</i>",
                            grid.getId(), grid.getSymbol(),
                            RegimeI18n.regimeList(grid.getRegimeWhitelist())));
                }
                return;
            } else if (grid.getPausedAt() != null) {
                grid.setPausedAt(null);
                grid.setPausedReason(null);
                grid.setUpdatedAt(LocalDateTime.now());
                gridRepository.save(grid);
                log.info("[Grid] id={} {} resumed — hint permits", grid.getId(), grid.getSymbol());
                notifyTg(String.format("▶ <b>網格 #%d %s 恢復執行</b>\n當前市場形態符合", grid.getId(), grid.getSymbol()));
            }
        }

        // 3. 取上次 price 做 cross-detection。第一次(或重啟後第一次)prev=null,
        //    只記 price 不觸發任何 buy/sell,避免初始化全 level 立刻 trigger。
        BigDecimal prevPrice = lastPriceByGrid.put(grid.getId(), price);
        if (prevPrice == null) {
            log.info("[Grid] id={} {} first check, recorded price={} (no action until next scan)",
                    grid.getId(), grid.getSymbol(), price);
            return;
        }

        // 4. 計算 grid step(每兩格之間的距離)
        BigDecimal gridStep = calcGridStep(grid);

        // 5. 檢查 levels — 只在 cross-event 時觸發
        //    BUY  cross-down: prevPrice > level.price AND currentPrice ≤ level.price
        //    SELL cross-up:   prevPrice < pairedSellPrice AND currentPrice ≥ pairedSellPrice
        List<BtGridLevel> levels = levelRepository.findByGridIdAndStatusIn(
                grid.getId(), Arrays.asList("PENDING", "HOLDING", "SELL_FAILED", "SELL_PARTIAL", "BUY_FAILED"));

        for (BtGridLevel level : levels) {
            try {
                String st = level.getStatus();
                if ("PENDING".equals(st)) {
                    boolean crossedDown = prevPrice.compareTo(level.getPrice()) > 0
                                       && price.compareTo(level.getPrice()) <= 0;
                    if (crossedDown) {
                        tryBuy(grid, level, gridStep, price);
                    }
                } else if ("HOLDING".equals(st)
                        && level.getPairedSellPrice() != null) {
                    boolean crossedUp = prevPrice.compareTo(level.getPairedSellPrice()) < 0
                                     && price.compareTo(level.getPairedSellPrice()) >= 0;
                    if (crossedUp) {
                        trySell(grid, level, price);
                    }
                } else if ("SELL_FAILED".equals(st)
                        && level.getRetryCount() < 3
                        && level.getPairedSellPrice() != null
                        && price.compareTo(level.getPairedSellPrice()) >= 0) {
                    // SELL_FAILED retry: price still above target → retry sell
                    level.setRetryCount(level.getRetryCount() + 1);
                    log.info("[Grid] id={} level={} SELL_FAILED retry #{} (price={} >= pairedSell={})",
                            grid.getId(), level.getLevelIndex(), level.getRetryCount(),
                            price, level.getPairedSellPrice());
                    trySell(grid, level, price);
                } else if ("SELL_PARTIAL".equals(st)
                        && level.getRetryCount() < 3
                        && level.getPairedSellPrice() != null
                        && price.compareTo(level.getPairedSellPrice()) >= 0) {
                    // #399 — SELL_PARTIAL retry: leftover qty in filled_qty; sell again.
                    // Mirrors SELL_FAILED retry policy (max 3) so dust 不會無限輪迴。
                    level.setRetryCount(level.getRetryCount() + 1);
                    log.info("[Grid] id={} level={} SELL_PARTIAL retry #{} leftover={} (price={} >= pairedSell={})",
                            grid.getId(), level.getLevelIndex(), level.getRetryCount(),
                            level.getFilledQty(), price, level.getPairedSellPrice());
                    trySell(grid, level, price);
                } else if ("BUY_FAILED".equals(st)) {
                    // BUY_FAILED auto-reset: no BTC held, safe to return to PENDING
                    level.setStatus("PENDING");
                    level.setErrorMessage(null);
                    level.setRetryCount(0);
                    levelRepository.save(level);
                    log.info("[Grid] id={} level={} BUY_FAILED auto-reset to PENDING",
                            grid.getId(), level.getLevelIndex());
                }
            } catch (Exception e) {
                log.error("[Grid] id={} level={} action failed: {}",
                        grid.getId(), level.getLevelIndex(), e.getMessage(), e);
            }

            // SELL_FAILED / SELL_PARTIAL aging alert: stuck > N hours + today not yet alerted
            String s = level.getStatus();
            if (("SELL_FAILED".equals(s) || "SELL_PARTIAL".equals(s))
                    && level.getFilledAt() != null) {
                long hoursStuck = java.time.Duration.between(level.getFilledAt(), LocalDateTime.now()).toHours();
                if (hoursStuck >= props.sellFailedAgingHours()) {
                    if (shouldSuppressDustAgingAlert(level)) {
                        log.debug("[Grid] level={} {} aging suppressed: retry={}/3 estNotional={} < minSellNotional={}",
                                level.getId(), s, level.getRetryCount(),
                                estimateSellNotional(level.getFilledQty(), level.getPairedSellPrice()),
                                props.minSellNotionalUsdt());
                        continue;
                    }
                    java.time.LocalDate today = java.time.LocalDate.now();
                    java.time.LocalDate lastAlert = agingAlertSent.get(level.getId());
                    if (lastAlert == null || !lastAlert.equals(today)) {
                        agingAlertSent.put(level.getId(), today);
                        log.warn("[Grid] id={} level={} {} aging {}h (retry={}/3)",
                                grid.getId(), level.getLevelIndex(), s, hoursStuck, level.getRetryCount());
                        notifyTg(String.format(
                                "⏰ <b>Grid #%d L%d %s 超過 %dh</b>\n%s qty=%s pairedSell=%s\nretry=%d/3\n請確認是否需手動處理",
                                grid.getId(), level.getLevelIndex(), s, hoursStuck,
                                grid.getSymbol(), level.getFilledQty().toPlainString(),
                                level.getPairedSellPrice().toPlainString(), level.getRetryCount()));
                    }
                }
            }
        }
    }

    private BigDecimal calcGridStep(BtGrid grid) {
        return grid.getPriceUpper().subtract(grid.getPriceLower())
                .divide(BigDecimal.valueOf(grid.getGridCount() - 1L), PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private boolean isAllowedByHint(BtGrid grid) {
        // 用 1h timeframe hint 作為 grid regime 控管(較高頻響應)
        List<GeminiMarketHint> hints = hintRepository.findActiveHints(
                grid.getSymbol(), "1h", LocalDateTime.now(),
                PageRequest.of(0, 1));
        if (hints.isEmpty()) return true;  // 無 hint = 放行(advisor 未跑或已過期)
        GeminiMarketHint hint = hints.get(0);
        if (hint.getConfidence().doubleValue() < 0.5) return true;  // 低 conf = 放行

        String regime = hint.getRegime().toUpperCase();
        Set<String> whitelist = Arrays.stream(grid.getRegimeWhitelist().split(","))
                .map(String::trim).map(String::toUpperCase)
                .collect(Collectors.toSet());

        // RSI-based 智能覆蓋：TRENDING_UP 但動能衰退時提前允許 Grid 運行
        // 若 hint=TRENDING_UP 且 RSI ≤ 60（動能降溫，即將回歸橫盤）→ 放行
        // 若 hint=TRENDING_UP 且 RSI > 70（超買，Grid 不適合）→ 維持暫停
        if ("TRENDING_UP".equals(regime) && !whitelist.contains(regime)) {
            try {
                BigDecimal currentPrice = okxTradingService.getLastPrice(grid.getSymbol());
                if (currentPrice != null) {
                    // 取最新 1h RSI 從 market snapshot 估算（使用資金費率 3h MA 指標替代判斷）
                    // 簡化：若 hint.adxAdjust < 0（ADX 趨弱信號）視為動能衰退，放行
                    double adxAdj = hint.getAdxAdjust() != null ? hint.getAdxAdjust().doubleValue() : 0;
                    if (adxAdj <= 0) {
                        log.debug("[Grid] id={} TRENDING_UP 但 ADX adj={} 衰退，放行 grid", grid.getId(), adxAdj);
                        return true;
                    }
                }
            } catch (Exception e) {
                log.debug("[Grid] RSI override check failed: {}", e.getMessage());
            }
        }

        return whitelist.contains(regime);
    }

    private void tryBuy(BtGrid grid, BtGridLevel level, BigDecimal gridStep, BigDecimal currentPrice) {
        log.info("[Grid] id={} level={} BUY trigger price={} <= levelPrice={}",
                grid.getId(), level.getLevelIndex(), currentPrice, level.getPrice());

        // 下單前確認 USDT 夠用；不足時先從 Simple Earn 補款（non-blocking）
        try {
            String balStr = okxTradingService.getUsdtBalance();
            if (!"N/A".equals(balStr)) {
                BigDecimal availUsdt = new BigDecimal(balStr);
                if (availUsdt.compareTo(grid.getPerLevelUsdt()) < 0) {
                    log.info("[Grid] id={} level={} USDT 不足 ({} < {})，嘗試從 Simple Earn 補款",
                            grid.getId(), level.getLevelIndex(), availUsdt, grid.getPerLevelUsdt());
                    boolean topped = okxEarnService.topUpTradingBuffer(availUsdt);
                    if (topped) {
                        log.info("[Grid] id={} level={} Simple Earn 補款成功，繼續下單",
                                grid.getId(), level.getLevelIndex());
                    } else {
                        log.warn("[Grid] id={} level={} Simple Earn 補款未執行（餘額已足夠或 Earn 無存款）",
                                grid.getId(), level.getLevelIndex());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[Grid] id={} level={} Simple Earn 補款檢查失敗（non-blocking，繼續嘗試下單）: {}",
                    grid.getId(), level.getLevelIndex(), e.getMessage());
        }

        // #340 Phase 3 — distributed-tx 防護：先寫 PENDING_OKX 中介態，OKX 成功才轉 HOLDING。
        // 若 JVM crash 或 OKX response parse fail 卡在 PENDING_OKX，由 GridOrphanRecoveryScanner 接手。
        level.setStatus("PENDING_OKX");
        level.setIntentAt(LocalDateTime.now());
        level.setErrorMessage(null);
        levelRepository.save(level);

        try {
            TradeResult result = okxTradingService.placeMarketBuy(
                    grid.getSymbol(), grid.getPerLevelUsdt().doubleValue());
            level.setStatus("HOLDING");
            level.setRetryCount(0);
            level.setFilledQty(result.getQty());
            level.setFilledPrice(result.getAvgPrice());
            level.setBuyOrderId(result.getOrderId());
            level.setFilledAt(LocalDateTime.now());
            // 賣出價 = 成交價 + 一個 grid step(下一格)
            level.setPairedSellPrice(result.getAvgPrice().add(gridStep)
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP));
            levelRepository.save(level);
            log.info("[Grid] id={} level={} BUY filled qty={} avgPx={} pairedSell={}",
                    grid.getId(), level.getLevelIndex(),
                    result.getQty(), result.getAvgPrice(), level.getPairedSellPrice());
            notifyTg(String.format("🟢 <b>Grid #%d L%d BUY</b>\n%s qty=%s @ %s\nPaired Sell: %s",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    result.getQty().toPlainString(),
                    result.getAvgPrice().toPlainString(),
                    level.getPairedSellPrice().toPlainString()));
            // #443 Gap 2 — audit row 即使 TG 失敗也保留 grid trade 事件
            auditWriter.logAutoTradeOk(null, grid.getSymbol(), null,
                    java.util.Map.of(
                            "source", "GRID_BUY",
                            "grid_id", grid.getId(),
                            "level_index", level.getLevelIndex(),
                            "qty", result.getQty().toPlainString(),
                            "price", result.getAvgPrice().toPlainString(),
                            "paired_sell", level.getPairedSellPrice().toPlainString()));
        } catch (Exception e) {
            // #340 Phase 3 — 不再直接 BUY_FAILED，因 OKX 可能其實已成交但 response parse 失敗。
            // 留 PENDING_OKX 狀態給 GridOrphanRecoveryScanner 用 OKX trade history 對齊判定。
            level.setErrorMessage(truncate("PENDING_OKX exception: " + e.getMessage(), 500));
            levelRepository.save(level);
            log.error("[Grid] id={} level={} OKX BUY threw — leaving PENDING_OKX for recovery scanner: {}",
                    grid.getId(), level.getLevelIndex(), e.getMessage());
            notifyTg(String.format("⚠️ <b>Grid #%d L%d PENDING_OKX</b>\n%s\nOKX call exception — recovery scanner 將比對 OKX trade history 還原狀態\n%s",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    truncate(e.getMessage(), 200)));
        }
    }

    private void trySell(BtGrid grid, BtGridLevel level, BigDecimal currentPrice) {
        log.info("[Grid] id={} level={} SELL trigger price={} >= pairedSell={}",
                grid.getId(), level.getLevelIndex(), currentPrice, level.getPairedSellPrice());
        // #373 — distributed-tx 防護（對稱 BUY 流程的 PENDING_OKX）：
        // 寫 SELLING_OKX + intent_at 後才呼叫 OKX，OKX 成功才轉 CLOSED；OKX 端成交但 Java
        // parse fail / DB save fail 會卡在 SELLING_OKX 由 GridOrphanRecoveryScanner 接手。
        level.setStatus("SELLING_OKX");
        level.setIntentAt(LocalDateTime.now());
        level.setErrorMessage(null);
        levelRepository.save(level);

        try {
            BigDecimal sellQty = resolveAvailableQty(grid.getSymbol(), level.getFilledQty());
            // #436 — availBal < OKX minSz: abandon 而非 retry,阻斷 51020 spam loop。
            if (sellQty.signum() <= 0) {
                handleSellAbandonedDust(grid, level);
                return;
            }
            BigDecimal estimatedNotional = estimateSellNotional(sellQty, currentPrice);
            if (isBelowMinimumSellNotional(estimatedNotional)) {
                handleSellAbandonedMinimumOrder(grid, level,
                        "preflight estimatedNotional=" + estimatedNotional
                                + " < minSellNotionalUsdt=" + props.minSellNotionalUsdt(),
                        estimatedNotional);
                return;
            }
            // #399 — placeMarketSellWithFill 回 TradeResult 含實際 sold qty + avgPrice，
            // 才能偵測 OKX market sell 的 partial fill（之前只取 avgPrice，partial 完全 silent）。
            com.agora.service.trading.TradeResult sellResult =
                    okxTradingService.placeMarketSellWithFill(grid.getSymbol(), sellQty);
            BigDecimal soldQty = sellResult.getQty();
            // partial-fill tolerance: 1% of requested qty（issue #399 spec）。
            BigDecimal tolerance = sellQty.multiply(BigDecimal.valueOf(0.01));
            BigDecimal leftover = sellQty.subtract(soldQty);
            if (leftover.compareTo(tolerance) > 0) {
                handleSellPartialFill(grid, level, sellQty, soldQty, leftover, sellResult);
                return;
            }

            // 簡化:用 pairedSellPrice 作為成交均價估算 PnL(實際略有 slippage)
            BigDecimal sellPx = level.getPairedSellPrice();
            BigDecimal pnl = sellPx.subtract(level.getFilledPrice())
                    .multiply(level.getFilledQty())
                    .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
            level.setStatus("CLOSED");
            level.setRetryCount(0);
            level.setRealizedPnl(pnl);
            level.setClosedAt(LocalDateTime.now());
            levelRepository.save(level);

            grid.setTotalRealizedPnl(grid.getTotalRealizedPnl().add(pnl));
            grid.setClosedPairCount(grid.getClosedPairCount() + 1);
            grid.setUpdatedAt(LocalDateTime.now());
            gridRepository.save(grid);

            consecutive51020Count.remove(level.getId());  // #436 — sell 成功重置 spam counter
            log.info("[Grid] id={} level={} SELL closed sold={} pnl={} totalPnl={}",
                    grid.getId(), level.getLevelIndex(), soldQty, pnl, grid.getTotalRealizedPnl());
            notifyTg(String.format("💰 <b>Grid #%d L%d SELL</b>\n%s sold=%s @ %s\nPnL: %+.4f USDT (累計 %+.4f)",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    soldQty.toPlainString(), sellPx.toPlainString(),
                    pnl.doubleValue(), grid.getTotalRealizedPnl().doubleValue()));
            // #443 Gap 2 — audit row 紀錄 grid sell 出場
            auditWriter.logAutoTradeOk(null, grid.getSymbol(), null,
                    java.util.Map.of(
                            "source", "GRID_SELL",
                            "grid_id", grid.getId(),
                            "level_index", level.getLevelIndex(),
                            "qty", soldQty.toPlainString(),
                            "sell_price", sellPx.toPlainString(),
                            "pnl_usdt", pnl.doubleValue(),
                            "grid_total_pnl", grid.getTotalRealizedPnl().doubleValue()));

            // 5. 循環:CLOSED → 重置為 PENDING(讓網格反覆運行)
            if (props.recycleClosedLevels()) {
                level.setStatus("PENDING");
                level.setRetryCount(0);
                level.setFilledQty(null);
                level.setFilledPrice(null);
                level.setBuyOrderId(null);
                level.setSellOrderId(null);
                level.setPairedSellPrice(null);
                level.setRealizedPnl(null);
                level.setFilledAt(null);
                level.setClosedAt(null);
                level.setErrorMessage(null);
                levelRepository.save(level);
            }
        } catch (Exception e) {
            // #436 — 51020 連續 spam monitor: 同 level 連續 3 次 (qty < minSz) → 強制終止 + Critical alert。
            // Bug A+B 後正常永遠 0,這是 defense-in-depth (e.g. minSz 上調 / OKX 改規 / 未預見 race)。
            String emsg = e.getMessage() == null ? "" : e.getMessage();
            if (isOkxMinimumOrderAmountReject(emsg)) {
                // #465 — OKX rejected before accepting an order, so there is no
                // possible fill for recovery scanner to reconcile. Stop the retry loop now.
                log.warn("[Grid] id={} level={} SELL_ABANDONED minimum order amount: {}",
                        grid.getId(), level.getLevelIndex(), emsg);
                handleSellAbandonedMinimumOrder(grid, level, emsg, null);
                consecutive51020Count.remove(level.getId());
                return;
            }
            if (emsg.contains("51020")) {
                int n = consecutive51020Count.merge(level.getId(), 1, Integer::sum);
                if (n >= SPAM_51020_THRESHOLD) {
                    log.error("[Grid] id={} level={} 51020 SPAM ×{} — force terminate, manual reconcile required",
                            grid.getId(), level.getLevelIndex(), n);
                    handleSellAbandonedDust(grid, level);
                    notifyTg(String.format(
                            "🚨 <b>Grid #%d L%d 51020 SPAM ×%d</b>\n%s\n連續 qty &lt; minSz 失敗,強制 SELL_FAILED retry=3/3,請手動 reconcile",
                            grid.getId(), level.getLevelIndex(), n, grid.getSymbol()));
                    consecutive51020Count.remove(level.getId());
                    return;
                }
            } else {
                consecutive51020Count.remove(level.getId());  // 非 51020 錯誤重置 counter
            }
            // #373 — 不再直接 SELL_FAILED；OKX 可能其實已成交。留 SELLING_OKX 給 recovery scanner
            // 用 OKX trade history 對齊判定（成交→CLOSED with recovered details / 真失敗→SELL_FAILED）。
            level.setErrorMessage(truncate("SELLING_OKX exception: " + e.getMessage(), 500));
            levelRepository.save(level);
            log.error("[Grid] id={} level={} OKX SELL threw — leaving SELLING_OKX for recovery scanner: {}",
                    grid.getId(), level.getLevelIndex(), e.getMessage());
            notifyTg(String.format("⚠️ <b>Grid #%d L%d SELLING_OKX</b>\n%s qty=%s\nOKX call exception — recovery scanner 將比對 OKX trade history 還原狀態\n%s",
                    grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                    level.getFilledQty().toPlainString(),
                    truncate(e.getMessage(), 200)));
        }
    }

    /**
     * #399 — Mark a level as SELL_PARTIAL: OKX market sell completed but with smaller
     * filled qty than requested. Records leftover qty back into {@code filled_qty}
     * so the next sell retry / stop-out / orphan scanner sells exactly the leftover.
     * Realized PnL of the sold portion is added to the grid total but the level does
     * NOT close — main loop's SELL_PARTIAL retry handler picks up the leftover when
     * price next crosses pairedSell.
     */
    private void handleSellPartialFill(BtGrid grid, BtGridLevel level,
                                       BigDecimal requestedQty, BigDecimal soldQty,
                                       BigDecimal leftover,
                                       com.agora.service.trading.TradeResult sellResult) {
        BigDecimal sellPx = sellResult.getAvgPrice() != null
                ? sellResult.getAvgPrice() : level.getPairedSellPrice();
        BigDecimal soldPnl = sellPx.subtract(level.getFilledPrice())
                .multiply(soldQty)
                .setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        level.setStatus("SELL_PARTIAL");
        level.setFilledQty(leftover);                  // remaining BTC to sell on retry
        // accumulate realized PnL for sold portion (later retries top this up)
        BigDecimal prevRealized = level.getRealizedPnl() != null
                ? level.getRealizedPnl() : BigDecimal.ZERO;
        level.setRealizedPnl(prevRealized.add(soldPnl));
        level.setSellOrderId(sellResult.getOrderId());
        level.setErrorMessage(String.format(
                "partial fill: requested=%s sold=%s leftover=%s",
                requestedQty.toPlainString(), soldQty.toPlainString(), leftover.toPlainString()));
        levelRepository.save(level);

        grid.setTotalRealizedPnl(grid.getTotalRealizedPnl().add(soldPnl));
        grid.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(grid);

        log.warn("[Grid] id={} level={} SELL_PARTIAL requested={} sold={} leftover={} soldPnl={}",
                grid.getId(), level.getLevelIndex(),
                requestedQty, soldQty, leftover, soldPnl);
        notifyTg(String.format(
                "⚠️ <b>Grid #%d L%d PARTIAL FILL</b>\n%s requested=%s sold=%s leftover=%s\nsold PnL: %+.4f USDT (累計 %+.4f)\n→ status=SELL_PARTIAL，下次 cross pairedSell 時 retry 賣 leftover",
                grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                requestedQty.toPlainString(), soldQty.toPlainString(), leftover.toPlainString(),
                soldPnl.doubleValue(), grid.getTotalRealizedPnl().doubleValue()));
    }

    private void stopOutGrid(BtGrid grid, BigDecimal price, BigDecimal stopLow, BigDecimal stopHigh) {
        log.warn("[Grid] id={} {} STOP-OUT triggered price={} (range {}~{})",
                grid.getId(), grid.getSymbol(), price, stopLow, stopHigh);

        // 平掉所有持 BTC 的 levels(HOLDING + SELL_FAILED + SELL_PARTIAL leftover)
        List<BtGridLevel> filled = levelRepository.findByGridIdAndStatusIn(
                grid.getId(), List.of("HOLDING", "SELL_FAILED", "SELL_PARTIAL"));
        int closedCount = 0;
        int partialCount = 0;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int abandonedCount = 0;
        for (BtGridLevel level : filled) {
            try {
                BigDecimal sellQty = resolveAvailableQty(grid.getSymbol(), level.getFilledQty());
                // #436 — dust < minSz: 跳過此 level(無法清倉,但其他 level 仍可繼續)。
                if (sellQty.signum() <= 0) {
                    handleSellAbandonedDust(grid, level);
                    abandonedCount++;
                    continue;
                }
                BigDecimal estimatedNotional = estimateSellNotional(sellQty, price);
                if (isBelowMinimumSellNotional(estimatedNotional)) {
                    handleSellAbandonedMinimumOrder(grid, level,
                            "stop-out preflight estimatedNotional=" + estimatedNotional
                                    + " < minSellNotionalUsdt=" + props.minSellNotionalUsdt(),
                            estimatedNotional);
                    abandonedCount++;
                    continue;
                }
                // #399 — partial-fill aware path
                com.agora.service.trading.TradeResult sellResult =
                        okxTradingService.placeMarketSellWithFill(grid.getSymbol(), sellQty);
                BigDecimal soldQty = sellResult.getQty();
                BigDecimal tolerance = sellQty.multiply(BigDecimal.valueOf(0.01));
                BigDecimal leftover = sellQty.subtract(soldQty);
                if (leftover.compareTo(tolerance) > 0) {
                    // Partial fill mid-stop-out: keep leftover for orphan recovery / future
                    // manual reconcile. Accumulate PnL for sold portion only.
                    handleSellPartialFill(grid, level, sellQty, soldQty, leftover, sellResult);
                    partialCount++;
                    totalLoss = totalLoss.add(level.getRealizedPnl());
                    continue;
                }
                BigDecimal pnl = price.subtract(level.getFilledPrice())
                        .multiply(level.getFilledQty())
                        .setScale(PRICE_SCALE, RoundingMode.HALF_UP);
                level.setStatus("CLOSED");
                level.setRealizedPnl(pnl);
                level.setClosedAt(LocalDateTime.now());
                levelRepository.save(level);
                grid.setTotalRealizedPnl(grid.getTotalRealizedPnl().add(pnl));
                totalLoss = totalLoss.add(pnl);
                closedCount++;
            } catch (Exception e) {
                log.error("[Grid] id={} level={} STOP-OUT sell failed: {}",
                        grid.getId(), level.getLevelIndex(), e.getMessage());
            }
        }

        grid.setEnabled(false);
        grid.setClosedAt(LocalDateTime.now());
        grid.setPausedReason(String.format("STOP-OUT @ %s (range %s~%s)", price, stopLow, stopHigh));
        grid.setUpdatedAt(LocalDateTime.now());
        gridRepository.save(grid);

        String partialNote = partialCount > 0
                ? String.format("\n⚠️ %d 格 partial fill — leftover 留 SELL_PARTIAL 等手動 reconcile", partialCount)
                : "";
        String abandonNote = abandonedCount > 0
                ? String.format("\n⚠️ %d 格 dust < minSz — 標 SELL_FAILED 不送 OKX(避免 51020 spam)", abandonedCount)
                : "";
        notifyTg(String.format("🚫 <b>Grid #%d %s STOP-OUT</b>\n價格 %s 超出停損區間 [%s, %s]\n平 %d 倉 累計 PnL %+.4f USDT\nGrid 已關閉%s%s",
                grid.getId(), grid.getSymbol(),
                price.toPlainString(), stopLow.toPlainString(), stopHigh.toPlainString(),
                closedCount, totalLoss.doubleValue(), partialNote, abandonNote));
        // #443 Gap 2 — audit row 紀錄 stop-out 事件
        auditWriter.logAutoTradeOk(null, grid.getSymbol(), null,
                java.util.Map.of(
                        "source", "GRID_STOPOUT",
                        "grid_id", grid.getId(),
                        "trigger_price", price.toPlainString(),
                        "stop_low", stopLow.toPlainString(),
                        "stop_high", stopHigh.toPlainString(),
                        "closed_count", closedCount,
                        "total_loss_usdt", totalLoss.doubleValue(),
                        "partial_count", partialCount,
                        "abandoned_count", abandonedCount));
    }

    /**
     * #436 — availBal < OKX minSz dust: 標 SELL_FAILED retryCount=3(max-out)
     * 阻斷 main-loop retry + recovery scanner retry,寫明確 errorMessage,發單次 TG。
     * 不呼叫 OKX(必死 51020,白燒 quota)。
     */
    private void handleSellAbandonedDust(BtGrid grid, BtGridLevel level) {
        level.setStatus("SELL_FAILED");
        level.setRetryCount(3);
        level.setErrorMessage(truncate(
                "ABANDONED: availBal < OKX minSz dust — refused to call OKX (would 51020). "
                        + "filledQty=" + level.getFilledQty() + " — manual reconcile required.", 500));
        levelRepository.save(level);
        log.warn("[Grid] id={} level={} SELL_ABANDONED dust filledQty={} — main-loop retry blocked",
                grid.getId(), level.getLevelIndex(), level.getFilledQty());
        notifyTg(String.format(
                "🛑 <b>Grid #%d L%d SELL ABANDONED</b>\n%s filledQty=%s pairedSell=%s\n"
                        + "OKX availBal &lt; minSz dust — 不送 OKX(避免 51020 spam),標 SELL_FAILED retry=3/3\n"
                        + "請手動 reconcile",
                grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                level.getFilledQty().toPlainString(),
                level.getPairedSellPrice() != null ? level.getPairedSellPrice().toPlainString() : "n/a"));
    }

    /**
     * #465 — OKX 51020 can also mean the order notional is below the exchange
     * minimum order amount, even when qty itself is above minSz. This is a
     * deterministic pre-order reject, so recovery scanner should not wait 30min.
     */
    private void handleSellAbandonedMinimumOrder(BtGrid grid, BtGridLevel level,
                                                 String okxMessage, BigDecimal estimatedNotional) {
        BigDecimal estimate = estimatedNotional != null
                ? estimatedNotional
                : level.getFilledQty() != null && level.getPairedSellPrice() != null
                    ? estimateSellNotional(level.getFilledQty(), level.getPairedSellPrice())
                    : null;
        level.setStatus("SELL_FAILED");
        level.setRetryCount(3);
        level.setErrorMessage(truncate(
                "ABANDONED: OKX minimum order amount reject — no order accepted, recovery skipped. "
                        + "filledQty=" + level.getFilledQty()
                        + ", pairedSell=" + level.getPairedSellPrice()
                        + ", estimatedNotional=" + estimate
                        + ", okx=" + okxMessage,
                500));
        levelRepository.save(level);
        log.warn("[Grid] id={} level={} SELL_ABANDONED minimum-order filledQty={} pairedSell={} estNotional={}",
                grid.getId(), level.getLevelIndex(), level.getFilledQty(),
                level.getPairedSellPrice(), estimate);
        notifyTg(String.format(
                "🛑 <b>Grid #%d L%d SELL ABANDONED</b>\n%s filledQty=%s pairedSell=%s estNotional=%s\n"
                        + "OKX minimum order amount — 不進入 recovery loop,標 SELL_FAILED retry=3/3\n"
                        + "請手動 reconcile",
                grid.getId(), level.getLevelIndex(), grid.getSymbol(),
                level.getFilledQty() != null ? level.getFilledQty().toPlainString() : "n/a",
                level.getPairedSellPrice() != null ? level.getPairedSellPrice().toPlainString() : "n/a",
                estimate != null ? estimate.toPlainString() : "n/a"));
    }

    private BigDecimal estimateSellNotional(BigDecimal sellQty, BigDecimal referencePrice) {
        if (sellQty == null || referencePrice == null) return null;
        if (sellQty.signum() <= 0 || referencePrice.signum() <= 0) return null;
        return sellQty.multiply(referencePrice).setScale(8, RoundingMode.HALF_UP);
    }

    private boolean isBelowMinimumSellNotional(BigDecimal estimatedNotional) {
        BigDecimal min = props.minSellNotionalUsdt();
        return min != null
                && estimatedNotional != null
                && estimatedNotional.compareTo(min) < 0;
    }

    private boolean shouldSuppressDustAgingAlert(BtGridLevel level) {
        if (level == null) return false;
        Integer retry = level.getRetryCount();
        if (retry == null || retry < 3) return false;
        BigDecimal estimatedNotional = estimateSellNotional(
                level.getFilledQty(), level.getPairedSellPrice());
        return isBelowMinimumSellNotional(estimatedNotional);
    }

    private boolean isOkxMinimumOrderAmountReject(String message) {
        if (message == null) return false;
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("51020")
                && (m.contains("minimum order amount")
                || m.contains("meet or exceed the minimum order"));
    }

    /**
     * OKX 扣手續費後實際可賣 qty 可能 < DB 記錄的 filledQty,直接用 filledQty 下單
     * 會被 OKX 拒(51008 insufficient balance)。查 OKX 實際 availBal 做 cap。
     * 對稱 LiveSignalEvaluator.resolveOcoQty 的邏輯。
     *
     * <p>#436 — 當 availBal &lt; OKX minSz(BTC=0.00001)時 return ZERO 表示 abandon,
     * 由 caller 標 level 為 SELL_FAILED retryCount=3 阻斷後續 retry,避免 51020 spam loop。
     */
    private BigDecimal resolveAvailableQty(String symbol, BigDecimal expectedQty) {
        try {
            String base = symbol.replace("USDT", "");
            BigDecimal availBal = okxTradingService.getSpotHoldings().stream()
                    .filter(h -> base.equals(h.ccy))
                    .map(h -> h.availBal)
                    .findFirst()
                    .orElse(BigDecimal.ZERO);
            BigDecimal minSz = MIN_SZ_BY_BASE.getOrDefault(base, DEFAULT_MIN_SZ);
            if (availBal.compareTo(BigDecimal.ZERO) > 0
                    && availBal.compareTo(expectedQty) < 0) {
                if (availBal.compareTo(minSz) < 0) {
                    log.warn("[Grid] sell ABANDON: availBal={} < minSz={} ({} dust),"
                                    + " expected={} — refusing OKX call to avoid 51020 spam",
                            availBal, minSz, base, expectedQty);
                    return BigDecimal.ZERO;
                }
                log.info("[Grid] sell qty adjusted: expected={} -> avail={} (fee ate {})",
                        expectedQty, availBal, expectedQty.subtract(availBal));
                return availBal;
            }
        } catch (Exception e) {
            log.warn("[Grid] resolveAvailableQty failed, using expected={}: {}",
                    expectedQty, e.getMessage());
        }
        return expectedQty;
    }

    private void notifyTg(String msg) {
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.warn("[Grid] TG notify failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int n) {
        if (s == null) return null;
        return s.length() <= n ? s : s.substring(0, n);
    }
}
