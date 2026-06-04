package com.agora.service.trading;

import com.agora.dto.backtest.SopMtfAdxConfig;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.infra.notification.NotificationPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
@RequiredArgsConstructor
public class SwapRiskMonitorService {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final NotificationPort notificationPort;
    private final PostTradeReviewService postTradeReviewService;
    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository klineRepository;
    private final ObjectMapper objectMapper;

    public void detectSwapOrphans() {
        List<OkxTradingService.SwapPosition> swapPositions;
        try {
            swapPositions = okxTradingService.getOpenSwapPositions();
        } catch (Exception e) {
            log.warn("[SwapOrphan] Failed to query OKX SWAP positions: {}", e.getMessage());
            return;
        }
        if (swapPositions.isEmpty()) return;

        List<BtLiveSignal> dbShorts = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()
                .stream().filter(p -> "SHORT".equals(p.getSide())).toList();
        Set<String> trackedSymbols = new HashSet<>();
        for (BtLiveSignal p : dbShorts) trackedSymbols.add(p.getSymbol());

        for (OkxTradingService.SwapPosition sp : swapPositions) {
            String symbol = sp.toSymbol();
            if (trackedSymbols.contains(symbol)) continue;

            log.error("[SwapOrphan] 發現孤兒 SWAP 倉位：{} pos={} avgPx={} upl={}",
                    sp.instId(), sp.pos(), sp.avgPx(), sp.upl());
            try {
                notificationPort.broadcast(String.format(
                        "🚨 <b>發現孤兒 SWAP 空頭倉位</b>\n" +
                        "幣種: <b>%s</b>  合約張數: %s\n" +
                        "平均開倉價: $%s  浮動損益: %s USDT\n" +
                        "⚠️ DB 無此倉位記錄，無止損保護！\n" +
                        "請立即至 OKX App 手動設定止損，或執行 retryOco。",
                        sp.instId(), sp.pos(), sp.avgPx(), sp.upl()), true);
            } catch (Exception e) {
                log.error("[SwapOrphan] TG notify failed: {}", e.getMessage());
            }
        }
    }

    public void checkSwapRiskState() {
        try {
            JsonNode data = okxTradingService.getAccountRiskState();
            if (!data.isArray() || data.size() == 0) return;

            for (JsonNode item : data) {
                String riskState = item.path("atRisk").asText("false");
                boolean atRisk = "true".equalsIgnoreCase(riskState);
                if (!atRisk) continue;

                log.error("[RiskState] OKX 帳戶爆倉風險警告！atRisk=true data={}", item);
                try {
                    notificationPort.broadcast(
                            "🚨 <b>OKX 帳戶爆倉風險警告！</b>\n" +
                            "帳戶保證金不足，SWAP 倉位面臨強制平倉風險！\n" +
                            "請立即至 OKX 補充保證金或手動平倉。", true);
                } catch (Exception e) {
                    log.error("[RiskState] TG notify failed: {}", e.getMessage());
                }
                return;
            }
        } catch (Exception e) {
            log.warn("[RiskState] Failed to query risk state: {}", e.getMessage());
        }
    }

    public void detectPhantomShorts() {
        List<BtLiveSignal> dbShorts = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()
                .stream().filter(p -> "SHORT".equals(p.getSide())).toList();
        if (dbShorts.isEmpty()) return;

        List<OkxTradingService.SwapPosition> swapPositions;
        try {
            swapPositions = okxTradingService.getOpenSwapPositions();
        } catch (Exception e) {
            log.warn("[PhantomShort] Failed to query OKX SWAP positions: {}", e.getMessage());
            return;
        }

        Set<String> okxSymbols = new HashSet<>();
        for (OkxTradingService.SwapPosition sp : swapPositions) okxSymbols.add(sp.toSymbol());

        for (BtLiveSignal pos : dbShorts) {
            if (okxSymbols.contains(pos.getSymbol())) continue;

            log.warn("[PhantomShort] DB SHORT id={} symbol={} 在 OKX 無對應 SWAP 持倉，嘗試自動關閉",
                    pos.getId(), pos.getSymbol());
            try {
                autoCloseOrphanPosition(pos);
            } catch (Exception e) {
                log.error("[PhantomShort] Auto-close failed: id={} error={}", pos.getId(), e.getMessage());
                try {
                    notificationPort.broadcast(String.format(
                            "⚠️ <b>反向孤兒 SHORT</b>\n%s #%d 在 OKX 無持倉但 DB 仍顯示開倉。\n" +
                            "自動關閉失敗，請手動處理。",
                            pos.getSymbol(), pos.getId()), true);
                } catch (Exception ignored) {}
            }
        }
    }

    public void autoCloseOrphanPosition(BtLiveSignal pos) {
        String symbol = pos.getSymbol();
        boolean isShort = "SHORT".equals(pos.getSide());
        BigDecimal exitPrice = null;
        String exitReason = "ORPHAN_CLOSED";

        if (pos.getOcoOrderListId() != null) {
            try {
                JsonNode algo = isShort
                        ? okxTradingService.getSwapAlgoOrder(symbol, pos.getOcoOrderListId())
                        : okxTradingService.getAlgoOrder(symbol, pos.getOcoOrderListId());
                String state = algo.path("state").asText("");
                String avgPxStr = algo.path("avgPx").asText("");
                if ("filled".equals(state) && !avgPxStr.isEmpty() && !"0".equals(avgPxStr)) {
                    exitPrice = new BigDecimal(avgPxStr);
                    BigDecimal refEntry = pos.getActualEntryPrice() != null
                            ? pos.getActualEntryPrice() : pos.getEntryPrice();
                    if (pos.getSuggestedTp() != null && pos.getSuggestedSl() != null) {
                        BigDecimal mid = pos.getSuggestedTp().add(pos.getSuggestedSl())
                                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
                        exitReason = isShort
                                ? (exitPrice.compareTo(mid) <= 0 ? "TP" : "SL")
                                : (exitPrice.compareTo(mid) >= 0 ? "TP" : "SL");
                    } else {
                        exitReason = exitPrice.compareTo(refEntry) >= 0 ? "TP" : "SL";
                    }
                }
            } catch (Exception e) {
                log.warn("[Reconcile] Cannot query algo order for id={}: {}", pos.getId(), e.getMessage());
            }
        }

        boolean usedFallback = exitPrice == null;
        if (usedFallback) {
            exitPrice = pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
        }

        pos.setExitPrice(exitPrice);
        pos.setExitTime(LocalDateTime.now(ZoneOffset.UTC));
        pos.setExitReason(exitReason);
        // ocoRetryCount is managed by OcoPositionPollerScheduler; orphan closure removes
        // the position from future polls (exitTime set), so the retry entry expires naturally.
        if (!usedFallback && exitPrice != null && pos.getActualEntryPrice() != null && pos.getTradedQty() != null) {
            BigDecimal effectiveQty = isShort
                    ? pos.getTradedQty().multiply(BigDecimal.valueOf(okxTradingService.getContractSizeInBase(pos.getSymbol())))
                    : pos.getTradedQty();
            BigDecimal pnl = isShort
                    ? pos.getActualEntryPrice().subtract(exitPrice).multiply(effectiveQty)
                    : exitPrice.subtract(pos.getActualEntryPrice()).multiply(effectiveQty);
            pos.setRealizedPnl(pnl.setScale(8, RoundingMode.HALF_UP));
        }
        liveSignalRepository.save(pos);

        log.info("[Reconcile] Auto-closed orphan: id={} symbol={} reason={} exitPrice={} usedFallback={}",
                pos.getId(), symbol, exitReason, exitPrice, usedFallback);

        try {
            String note = usedFallback ? "\n⚠️ 出場價為入場價 fallback，請手動至 OKX 確認實際成交價" : "";
            notificationPort.broadcast(String.format(
                    "⚠️ <b>對帳自動關倉</b>\n倉位 #%d %s\nOKX 無餘額，已自動關閉 DB 記錄\n原因: %s\n出場價: %s%s",
                    pos.getId(), symbol, exitReason,
                    exitPrice != null ? exitPrice.toPlainString() : "N/A", note), true);
        } catch (Exception e) {
            log.error("[Reconcile] TG notify failed for auto-close: {}", e.getMessage());
        }

        if (!usedFallback && exitPrice != null && pos.getActualEntryPrice() != null) {
            BigDecimal refEntry = pos.getActualEntryPrice();
            double pnlPct = isShort
                    ? refEntry.subtract(exitPrice).divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue()
                    : exitPrice.subtract(refEntry).divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
            postTradeReviewService.reviewAsync(pos, exitReason, exitPrice, pnlPct);
        }
    }

    /**
     * #267: ATR Trailing Stop for live SWAP positions.
     * For each open SHORT with atrTrailingStopEnabled=true, computes a new SL
     * anchored on the latest low (HIGH for LONG) and updates the OCO if improved.
     * Called by OcoPositionPollerScheduler to ratchet stop-loss as price moves.
     */
    public void applyAtrTrailingStop() {
        List<BtLiveSignal> opens = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull()
                .stream()
                .filter(p -> p.getOcoOrderListId() != null && p.getSuggestedSl() != null
                        && p.getSuggestedTp() != null && p.getStrategyId() != null)
                .toList();
        if (opens.isEmpty()) return;

        for (BtLiveSignal pos : opens) {
            try {
                BtStrategy strategy = strategyRepository.findById(pos.getStrategyId()).orElse(null);
                if (strategy == null) continue;

                SopMtfAdxConfig cfg = objectMapper.readValue(strategy.getConfigJson(), SopMtfAdxConfig.class);
                if (cfg.getAtrTrailingStopEnabled() == null || !cfg.getAtrTrailingStopEnabled()) continue;

                int atrPeriod = cfg.getAtrPeriod() != null ? cfg.getAtrPeriod() : 14;
                double atrMult = cfg.getAtrMultiplier() != null ? cfg.getAtrMultiplier() : 2.0;
                String interval = pos.getIntervalCode() != null ? pos.getIntervalCode() : "1h";

                List<MdKline> raw = klineRepository
                        .findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                                pos.getSymbol(), interval, "okx",
                                PageRequest.of(0, atrPeriod + 5));
                if (raw.size() < atrPeriod + 1) continue;

                List<MdKline> klines = new ArrayList<>(raw);
                Collections.reverse(klines);
                int last = klines.size() - 1;

                double atr = computeAtrLocal(klines, last, atrPeriod);
                if (Double.isNaN(atr) || atr <= 0) continue;
                double atrStop = atr * atrMult;

                boolean isShort = "SHORT".equals(pos.getSide());
                BigDecimal currentSl = pos.getSuggestedSl();
                BigDecimal newSl;
                if (isShort) {
                    // SHORT: SL above price; lower it as price drops to lock profit
                    double latestLow = klines.get(last).getLowPrice().doubleValue();
                    newSl = BigDecimal.valueOf(latestLow + atrStop).setScale(2, RoundingMode.HALF_UP);
                    if (newSl.compareTo(currentSl) >= 0) continue; // not improved
                } else {
                    // LONG: SL below price; raise it as price rises to lock profit
                    double latestHigh = klines.get(last).getHighPrice().doubleValue();
                    newSl = BigDecimal.valueOf(latestHigh - atrStop).setScale(2, RoundingMode.HALF_UP);
                    if (newSl.compareTo(currentSl) <= 0) continue; // not improved
                }

                BigDecimal qty = pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty();
                if (qty == null) continue;

                // Cancel old OCO, place new one with tighter SL
                try {
                    if (isShort) okxTradingService.cancelSwapOco(pos.getSymbol(), pos.getOcoOrderListId());
                    else         okxTradingService.cancelOco(pos.getSymbol(), pos.getOcoOrderListId());
                } catch (Exception cancelErr) {
                    log.warn("[TrailSL] cancel OCO failed id={}: {}", pos.getId(), cancelErr.getMessage());
                    continue;
                }

                Long newAlgoId = isShort
                        ? okxTradingService.placeSwapOco(pos.getSymbol(), qty, pos.getSuggestedTp(), newSl)
                        : okxTradingService.placeOco(pos.getSymbol(), qty, pos.getSuggestedTp(), newSl);

                pos.setSuggestedSl(newSl);
                pos.setOcoOrderListId(newAlgoId);
                liveSignalRepository.save(pos);

                log.info("[TrailSL] {} id={} sl {} → {} (atr={} mult={})",
                        pos.getSymbol(), pos.getId(), currentSl, newSl,
                        String.format("%.4f", atr), atrMult);
            } catch (Exception e) {
                log.warn("[TrailSL] id={} failed: {}", pos.getId(), e.getMessage());
            }
        }
    }

    private double computeAtrLocal(List<MdKline> klines, int index, int period) {
        if (index < period) return Double.NaN;
        double sumTr = 0;
        for (int i = index - period + 1; i <= index; i++) {
            double h = klines.get(i).getHighPrice().doubleValue();
            double l = klines.get(i).getLowPrice().doubleValue();
            double prevC = i > 0 ? klines.get(i - 1).getClosePrice().doubleValue() : l;
            sumTr += Math.max(h - l, Math.max(Math.abs(h - prevC), Math.abs(l - prevC)));
        }
        return sumTr / period;
    }
}
