package com.agora.scheduler.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.SignalOutcomeVerificationRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.trading.OkxTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每日 TG 交易摘要（UTC 00:00 = TPE 08:00，早上醒來即可看）。
 *
 * <p>摘要內容：
 * <ul>
 *   <li>過去 24h 多/空信號數、已執行筆數</li>
 *   <li>當日已平倉筆數、勝率、PnL</li>
 *   <li>目前未平倉倉位數</li>
 *   <li>啟用策略數</li>
 * </ul>
 *
 * <p>不依賴 DB schema 變更；純讀取現有 bt_live_signal / bt_strategy 聚合。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DailyReportScheduler {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final BigDecimal GRID_DUST_NOTIONAL_THRESHOLD = new BigDecimal("5.0");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final BtGridRepository gridRepository;
    private final BtGridLevelRepository gridLevelRepository;
    private final OkxTradingService okxTradingService;
    private final ObjectMapper objectMapper;
    private final NotificationPort notificationPort;
    private final SignalOutcomeVerificationRepository signalVerificationRepository;

    /** 每日 00:00 UTC（TPE 08:00）發送前 24 小時摘要。@Scheduled 已移至 DailyTgReportOrchestrator（step 1）。 */
    public void sendDailySummary() {
        try {
            LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime start = end.minusHours(24);
            String msg = buildSummary(start, end);
            notificationPort.broadcast(msg, true);
            log.info("[DailyReport] Sent daily summary for {} → {}", start, end);
        } catch (Exception e) {
            log.error("[DailyReport] Failed to send daily summary: {}", e.getMessage(), e);
        }
    }

    private void appendStrategyModeSection(StringBuilder sb) {
        try {
            List<BtStrategy> strategies = strategyRepository.findByEnabled(true);
            List<String> real = new ArrayList<>();
            List<String> shadow = new ArrayList<>();
            for (BtStrategy s : strategies) {
                boolean notifyOnly = true;
                try {
                    if (s.getConfigJson() != null) {
                        JsonNode cfg = objectMapper.readTree(s.getConfigJson());
                        notifyOnly = cfg.path("notifyOnly").asBoolean(true);
                    }
                } catch (Exception ignored) {}
                (notifyOnly ? shadow : real).add("#" + s.getId());
            }
            sb.append("\n<b>🎯 策略模式</b>\n");
            if (!real.isEmpty())
                sb.append("  💰 實單: ").append(String.join(" ", real)).append("\n");
            if (!shadow.isEmpty())
                sb.append("  👁 Shadow: ").append(String.join(" ", shadow)).append("\n");
        } catch (Exception e) {
            log.warn("[DailyReport] appendStrategyModeSection failed: {}", e.getMessage());
        }
    }

    private void appendGridHealthSection(StringBuilder sb) {
        try {
            List<BtGrid> grids = gridRepository.findByEnabledTrueAndClosedAtIsNull();
            if (grids.isEmpty()) return;

            // 嘗試取 BTC 現價（用於邊界距離計算）
            BigDecimal btcPrice = null;
            try {
                btcPrice = okxTradingService.getSpotHoldings().stream()
                        .filter(h -> "BTC".equals(h.ccy) && h.eqUsd != null && h.cashBal != null
                                && h.cashBal.compareTo(BigDecimal.ZERO) > 0)
                        .map(h -> h.eqUsd.divide(h.cashBal, 2, RoundingMode.HALF_UP))
                        .findFirst().orElse(null);
            } catch (Exception ignored) {}

            sb.append("\n<b>📊 活躍網格 (").append(grids.size()).append(")</b>\n");
            for (BtGrid grid : grids) {
                long holding    = gridLevelRepository.countByGridIdAndStatus(grid.getId(), "HOLDING");
                long sellFailed = gridLevelRepository.countByGridIdAndStatus(grid.getId(), "SELL_FAILED");
                long sellPartial = gridLevelRepository.countByGridIdAndStatus(grid.getId(), "SELL_PARTIAL");
                double pnl = grid.getTotalRealizedPnl() != null ? grid.getTotalRealizedPnl().doubleValue() : 0.0;

                sb.append(String.format("  Grid #%d %s %.0f~%.0f | 持倉 %d 格 | PnL %+.2f USDT\n",
                        grid.getId(), grid.getSymbol(),
                        grid.getPriceLower().doubleValue(), grid.getPriceUpper().doubleValue(),
                        holding, pnl));

                // 邊界距離警告
                if (btcPrice != null) {
                    double lower = grid.getPriceLower().doubleValue();
                    double upper = grid.getPriceUpper().doubleValue();
                    double price = btcPrice.doubleValue();
                    double distUp  = (upper - price) / price * 100;
                    double distDn  = (price - lower) / price * 100;
                    if (distUp < 5.0)
                        sb.append(String.format("  ⚠️ 距上限 %.1f%%，考慮擴展範圍\n", distUp));
                    if (distDn < 5.0)
                        sb.append(String.format("  ⚠️ 距下限 %.1f%%，考慮擴展範圍\n", distDn));
                }
                if (sellFailed > 0) {
                    List<BtGridLevel> failedLevels = gridLevelRepository.findByGridIdAndStatus(grid.getId(), "SELL_FAILED");
                    long dustFailed = failedLevels.stream().filter(this::isDustSellFailure).count();
                    long actionableFailed = Math.max(0, sellFailed - dustFailed);
                    if (actionableFailed > 0) {
                        sb.append(String.format("  ⚠️ SELL_FAILED %d 格，請手動檢查\n", actionableFailed));
                    }
                    if (dustFailed > 0) {
                        sb.append(String.format("  ℹ️ Grid dust locked %d 格，小於 OKX 最小下單額或已停止 retry\n", dustFailed));
                    }
                }
                if (sellPartial > 0)
                    sb.append(String.format("  ⚠️ SELL_PARTIAL %d 格 (#399 leftover dust)，主迴圈會 retry，3 次後留人工\n", sellPartial));

                // 自動換範圍狀態
                if (Boolean.TRUE.equals(grid.getAutoRebalance())) {
                    int cnt = grid.getRebalanceCount() != null ? grid.getRebalanceCount() : 0;
                    int max = grid.getMaxRebalanceCount() != null ? grid.getMaxRebalanceCount() : 5;
                    sb.append(String.format("  🔄 自動換範圍：%d/%d 次 (閾值 %.1f%%, 最少在外 %dh)\n",
                            cnt, max,
                            (grid.getRebalanceTriggerPct() != null ? grid.getRebalanceTriggerPct() : 0.015) * 100,
                            grid.getMinHoursOutside() != null ? grid.getMinHoursOutside() : 4));
                }
            }
        } catch (Exception e) {
            log.warn("[DailyReport] appendGridHealthSection failed: {}", e.getMessage());
        }
    }

    private boolean isDustSellFailure(BtGridLevel level) {
        if (level == null) return false;
        String error = level.getErrorMessage() != null ? level.getErrorMessage().toLowerCase() : "";
        boolean explicitDust = error.contains("dust")
                || error.contains("minsz")
                || error.contains("minimum order")
                || error.contains("minimum")
                || error.contains("51020");
        if (explicitDust && level.getRetryCount() != null && level.getRetryCount() >= 3) {
            return true;
        }
        BigDecimal notional = estimateSellNotional(level);
        return notional != null
                && notional.compareTo(GRID_DUST_NOTIONAL_THRESHOLD) < 0
                && level.getRetryCount() != null
                && level.getRetryCount() >= 3;
    }

    private BigDecimal estimateSellNotional(BtGridLevel level) {
        if (level.getFilledQty() == null || level.getPairedSellPrice() == null) {
            return null;
        }
        if (level.getFilledQty().signum() <= 0 || level.getPairedSellPrice().signum() <= 0) {
            return null;
        }
        return level.getFilledQty().multiply(level.getPairedSellPrice()).setScale(8, RoundingMode.HALF_UP);
    }

    private void appendSignalAccuracySection(StringBuilder sb) {
        try {
            java.time.LocalDateTime since = java.time.LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
            java.util.List<Object[]> rows = signalVerificationRepository.accuracyByLayerSinceDedup(since);
            if (rows == null || rows.isEmpty()) return;

            boolean hasData = rows.stream().anyMatch(r ->
                    ((Number) r[2]).longValue() + ((Number) r[3]).longValue() > 0);
            if (!hasData) return;

            sb.append("\n<b>🎯 信號正確率（近7天）</b>\n");
            for (Object[] r : rows) {
                String layer    = (String) r[0];
                String decision = (String) r[1];
                long correct    = ((Number) r[2]).longValue();
                long wrong      = ((Number) r[3]).longValue();
                long watching   = ((Number) r[4]).longValue();
                long total = correct + wrong;
                if (total == 0) continue;
                double pct = (double) correct / total * 100;
                String icon = total >= 5 && pct < 40 ? "⚠️" : "✅";
                sb.append(String.format("  %s %s[%s] 看對%d 看錯%d (%.0f%%) 觀察中%d\n",
                        icon, layer, decision, correct, wrong, pct, watching));
            }
        } catch (Exception e) {
            log.warn("[DailyReport] appendSignalAccuracySection failed: {}", e.getMessage());
        }
    }

    private void appendOcoProtectionSection(StringBuilder sb) {
        try {
            List<BtLiveSignal> openPos = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
            if (openPos.isEmpty()) {
                sb.append("\n<b>🛡 OCO 保護</b> ✅ 目前無開倉\n");
                return;
            }
            long unprotected = openPos.stream().filter(p -> p.getOcoOrderListId() == null).count();
            sb.append("\n<b>🛡 OCO 保護</b>\n");
            if (unprotected > 0)
                sb.append(String.format("  ⚠️ %d/%d 筆倉位無保護！請立即補掛\n",
                        unprotected, openPos.size()));
            else
                sb.append(String.format("  ✅ %d/%d 均有保護\n", openPos.size(), openPos.size()));
        } catch (Exception e) {
            log.warn("[DailyReport] appendOcoProtectionSection failed: {}", e.getMessage());
        }
    }

    /** 供測試或手動觸發（例如 MCP 工具）使用。 */
    public String buildSummary(LocalDateTime start, LocalDateTime end) {
        // 1. 信號：取 start 之後，過濾出 end 之前
        List<BtLiveSignal> allSignals = liveSignalRepository.findByCreatedAtAfter(start).stream()
                .filter(s -> s.getCreatedAt() != null && s.getCreatedAt().isBefore(end))
                .toList();
        long longSignals  = allSignals.stream().filter(s -> !"SHORT".equals(s.getSide())).count();
        long shortSignals = allSignals.stream().filter(s ->  "SHORT".equals(s.getSide())).count();
        long autoTraded   = allSignals.stream().filter(s -> Boolean.TRUE.equals(s.getAutoTraded())).count();

        // 2. 已平倉（autoTraded=true 且 exitTime 在區間內）
        List<BtLiveSignal> closed = liveSignalRepository
                .findByAutoTradedIsTrueAndExitTimeIsNotNullAndExitTimeAfter(start).stream()
                .filter(s -> s.getExitTime() != null && s.getExitTime().isBefore(end))
                .toList();

        BigDecimal totalPnl = closed.stream()
                .map(s -> s.getRealizedPnl() != null ? s.getRealizedPnl() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long wins = closed.stream()
                .filter(s -> s.getRealizedPnl() != null && s.getRealizedPnl().signum() > 0).count();
        long losses = closed.size() - wins;

        // 3. 目前狀態
        long openCount = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
        long activeStrategies = strategyRepository.findByEnabled(true).size();

        // 4. Build
        StringBuilder sb = new StringBuilder();
        sb.append("📊 <b>每日交易摘要</b>\n");
        sb.append(end.format(DATE_FMT)).append(" UTC\n\n");

        sb.append("<b>📨 信號</b>\n");
        sb.append(String.format("  多 %d 筆 / 空 %d 筆\n", longSignals, shortSignals));
        sb.append(String.format("  已執行 %d 筆（含自動交易策略）\n\n", autoTraded));

        sb.append("<b>💰 當日平倉</b>\n");
        if (closed.isEmpty()) {
            sb.append("  無\n\n");
        } else {
            int winRate = closed.isEmpty() ? 0 : (int) (wins * 100 / closed.size());
            sb.append(String.format("  筆數 %d  勝率 %d%% (%d勝 %d負)\n", closed.size(), winRate, wins, losses));
            sb.append(String.format("  PnL %+.2f USDT\n\n", totalPnl.doubleValue()));
        }

        sb.append(String.format("<b>🔄 未平倉</b> %d 筆\n", openCount));
        sb.append(String.format("<b>⚙️ 啟用策略</b> %d 個\n", activeStrategies));

        // ── 策略模式明細 ────────────────────────────────────────────────────
        appendStrategyModeSection(sb);

        // ── 活躍網格 ─────────────────────────────────────────────────────────
        appendGridHealthSection(sb);

        // ── OCO 保護狀態 ──────────────────────────────────────────────────────
        appendOcoProtectionSection(sb);

        // ── 信號正確率 ───────────────────────────────────────────────────────
        appendSignalAccuracySection(sb);

        return sb.toString();
    }
}
