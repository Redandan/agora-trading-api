package com.agora.scheduler.trading;

import com.agora.dto.market.KlineSubscriptionInfo;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.market.KlineStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 每日 UTC 00:05 發送即時訊號系統健康摘要至 TG。
 *
 * <p>內容包含：
 * <ul>
 *   <li>WS 訂閱狀態（RUNNING / 異常）</li>
 *   <li>過去 24h 內產生的買入訊號數</li>
 *   <li>目前未出場的開倉訊號數</li>
 *   <li>待重試的 TG 通知數</li>
 * </ul>
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveSignalHealthScheduler {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BtLiveSignalRepository liveSignalRepository;
    private final BtStrategyRepository strategyRepository;
    private final java.util.List<KlineStreamService> wsKlineServices;
    private final NotificationPort notificationPort;
    private final AiStrategyDiscoveryService aiDiscoveryService;

    /** 每日 UTC 00:00。@Scheduled 已移至 DailyTgReportOrchestrator（step 3，串行自然錯開 TG）。 */
    public void sendDailySummary() {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            LocalDateTime since24h = now.minusHours(24);

            // WS 狀態
            List<KlineSubscriptionInfo> subs = wsKlineServices.stream()
                    .flatMap(svc -> svc.listSubscriptions().stream())
                    .collect(Collectors.toList());
            long runningCount = subs.stream().filter(s -> "RUNNING".equals(s.getStatus())).count();
            long abnormalCount = subs.size() - runningCount;

            // 全部正常時只顯示一行摘要，有異常才展開明細
            String wsLines;
            if (abnormalCount == 0) {
                wsLines = "  ✅ 全部正常 (" + subs.size() + "/" + subs.size() + ")";
            } else {
                wsLines = subs.stream()
                        .map(s -> String.format("  %s %s@%s [%s] (received=%d)",
                                "RUNNING".equals(s.getStatus()) ? "✅" : "⚠️",
                                s.getSymbol(), s.getIntervalCode(),
                                s.getSource() != null ? s.getSource() : "?",
                                s.getReceivedCount()))
                        .collect(Collectors.joining("\n"));
            }

            // 訊號統計
            long signalsToday   = liveSignalRepository.countByCreatedAtAfter(since24h);
            long openPositions  = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
            long pendingRetry   = liveSignalRepository.findByNotifiedAtIsNullAndCreatedAtBefore(
                    now.minusMinutes(5)).size();

            String statusIcon = abnormalCount > 0 ? "⚠️" : "✅";
            String msg = String.format(
                "📋 <b>LiveSignal 每日健康摘要</b>\n" +
                "🕐 %s (UTC)\n\n" +
                "<b>WS 訂閱</b> %s\n%s\n\n" +
                "<b>過去 24h 買入訊號</b>: <b>%d</b> 筆\n" +
                "<b>目前持倉中</b>: <b>%d</b> 筆\n" +
                "<b>待重試通知</b>: <b>%d</b> 筆%s",
                now.format(FMT),
                statusIcon,
                wsLines,
                signalsToday,
                openPositions,
                pendingRetry,
                pendingRetry > 0 ? " ⚠️" : ""
            );

            notificationPort.broadcast(msg, true);
            log.info("[HealthScheduler] Daily summary sent: ws={}/{} signals={} open={} retry={}",
                    runningCount, subs.size(), signalsToday, openPositions, pendingRetry);

            // ── Layer 2：寬 TP 警示（只通知，不自動操作）──
            // 每日掃描：持倉 > 48h 且 TP > 15%（可能是崩跌時設的月球射程）
            // 若當前 ATR 已恢復正常，提示可考慮手動收斂 TP
            checkWideTpPositions(now);
        } catch (Exception e) {
            log.error("[HealthScheduler] Failed to send daily summary: {}", e.getMessage(), e);
        }
    }

    /** 供 DailyTgReportOrchestrator 合併用：只回傳 WS 訂閱健康 + 待重試，不含信號/持倉統計。 */
    public String buildWsContent() {
        try {
            List<KlineSubscriptionInfo> subs = wsKlineServices.stream()
                    .flatMap(svc -> svc.listSubscriptions().stream())
                    .collect(Collectors.toList());
            long running = subs.stream().filter(s -> "RUNNING".equals(s.getStatus())).count();
            long abnormal = subs.size() - running;
            long pendingRetry = liveSignalRepository.findByNotifiedAtIsNullAndCreatedAtBefore(
                    LocalDateTime.now(ZoneOffset.UTC).minusMinutes(5)).size();

            StringBuilder sb = new StringBuilder();
            if (abnormal == 0 && pendingRetry == 0) {
                sb.append(String.format("🌐 WS: ✅ 全部正常 (%d/%d)", running, subs.size()));
            } else {
                sb.append(String.format("🌐 WS: %d/%d 正常", running, subs.size()));
                if (abnormal > 0) sb.append(String.format(" ⚠️ %d 異常", abnormal));
                if (pendingRetry > 0) sb.append(String.format("  待重試 %d 筆⚠️", pendingRetry));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[HealthScheduler] buildWsContent failed: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 每小時 wide-TP 掃描入口（供 HourlyOrchestrator 呼叫）。
     * 相同邏輯也在 sendDailySummary() 中觸發，二者共用同一私有方法。
     */
    public void runWideTpScan() {
        checkWideTpPositions(LocalDateTime.now(ZoneOffset.UTC));
    }

    /**
     * 每日掃描開倉中的「寬 TP 警示」（Layer 2 — 只通知，不自動 cancel/replace）。
     *
     * <p>觸發條件（三個同時滿足）：
     * <ol>
     *   <li>持倉 ≥ 48h（短期進場不干擾）</li>
     *   <li>TP 距入場 ≥ 15%（高機率是崩跌期 ATR spike 產生的月球射程）</li>
     *   <li>當前 ATR 已恢復正常（current ≤ baseline × 1.3，spike 消退）</li>
     * </ol>
     *
     * <p>發現時傳送 TG 提醒含建議 TP，由人工確認後手動調整。
     */
    private void checkWideTpPositions(LocalDateTime now) {
        try {
            List<BtLiveSignal> openPos = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
            for (BtLiveSignal pos : openPos) {
                if (pos.getSuggestedTp() == null) continue;

                BigDecimal entryRef = pos.getActualEntryPrice() != null
                        ? pos.getActualEntryPrice() : pos.getEntryPrice();
                if (entryRef == null || entryRef.compareTo(BigDecimal.ZERO) == 0) continue;

                // 條件 1：持倉 ≥ 48h
                long hoursOpen = ChronoUnit.HOURS.between(pos.getCreatedAt(), now);
                if (hoursOpen < 48) continue;

                // 條件 2：TP ≥ entry × 1.15（15% 以上才警示）
                double tpPct = pos.getSuggestedTp()
                        .subtract(entryRef)
                        .divide(entryRef, 4, RoundingMode.HALF_UP)
                        .doubleValue();
                if (tpPct < 0.15) continue;

                // 條件 3：當前 ATR 是否已恢復正常
                try {
                    AiStrategyDiscoveryService.MarketSnapshot ms =
                            aiDiscoveryService.buildMarketSnapshot(pos.getSymbol(), pos.getIntervalCode());
                    double currentAtr  = ms.atrPct();
                    double baselineAtr = ms.baselineAtrPct();
                    if (baselineAtr <= 0) continue;

                    // current > baseline × 1.3 → ATR 仍偏高，不警示（避免誤報）
                    if (currentAtr > baselineAtr * 1.3) continue;

                    // 計算「ATR 正常化後應有的 TP」（baseline × convergence=1.5 × tpMul=5.0）
                    double normalizedTpPct = Math.min(0.15, (baselineAtr * 1.5 / 100.0) * 5.0);

                    // 策略感知最低 TP 底限：崩盤底部策略（SCORE_BUY*）設計目標是大幅回升，
                    // 公式產生的 3-5% 建議 TP 沒有實際意義，需要抬高底限避免誤導性的「收斂」通知。
                    double minTpFloor = resolveMinTpFloor(pos.getStrategyId());
                    normalizedTpPct = Math.max(minTpFloor, normalizedTpPct);

                    BigDecimal suggestedTp = entryRef
                            .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(normalizedTpPct)))
                            .setScale(2, RoundingMode.HALF_UP);

                    // 只在建議 TP 比現有 TP 至少低 10% 時才發通知（避免差距太小意義不大）
                    if (suggestedTp.compareTo(pos.getSuggestedTp().multiply(BigDecimal.valueOf(0.90))) >= 0) continue;

                    String warnMsg = String.format(
                            "⚠️ <b>TP 收斂建議｜倉位 #%d</b>\n" +
                            "📌 %s [%s]  持倉 %dh\n" +
                            "📍 現行 TP: <b>$%s</b>（入場 $%s，+%.1f%%）\n" +
                            "📊 ATR 已恢復：%.2f%% → 中位 %.2f%%\n" +
                            "💡 建議 TP: <b>$%s</b>（+%.1f%%，ATR 正常化）\n" +
                            "⚙️ 手動調整：取消 OCO 後重掛",
                            pos.getId(),
                            pos.getSymbol(), pos.getIntervalCode(), hoursOpen,
                            formatPrice(pos.getSuggestedTp()),
                            formatPrice(entryRef),
                            tpPct * 100,
                            currentAtr, baselineAtr,
                            formatPrice(suggestedTp),
                            normalizedTpPct * 100);
                    notificationPort.broadcast(warnMsg, true);
                    log.info("[HealthScheduler] Wide TP warning sent for pos#{}: tp={}% atr={}% baseline={}%",
                            pos.getId(), String.format("%.1f", tpPct * 100),
                            String.format("%.2f", currentAtr), String.format("%.2f", baselineAtr));
                } catch (Exception e) {
                    log.debug("[HealthScheduler] WideTp check skipped for pos#{}: {}", pos.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[HealthScheduler] checkWideTpPositions failed: {}", e.getMessage());
        }
    }

    /**
     * 依策略類型決定 wide-TP 通知的最低 TP 底限。
     * <ul>
     *   <li>SCORE_BUY* (崩盤底部策略)：最低 10%，避免 ATR 公式給出毫無意義的 3-5% 建議</li>
     *   <li>其他（SOP_MTF_ADX 等趨勢/均值回歸）：最低 4%，比公式上限略寬裕</li>
     * </ul>
     */
    private double resolveMinTpFloor(Long strategyId) {
        if (strategyId == null) return 0.04;
        try {
            BtStrategy strategy = strategyRepository.findById(strategyId).orElse(null);
            if (strategy != null && strategy.getStrategyType() != null
                    && strategy.getStrategyType().startsWith("SCORE_BUY")) {
                return 0.10; // 10% 底限：崩盤底部策略預期大幅反彈
            }
        } catch (Exception e) {
            log.debug("[HealthScheduler] Strategy lookup failed for id={}: {}", strategyId, e.getMessage());
        }
        return 0.04; // 4% 底限：一般趨勢策略
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        return price.compareTo(BigDecimal.valueOf(1000)) >= 0
                ? String.format("%,.2f", price.doubleValue())
                : price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
