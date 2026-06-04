package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.TelegramService;
import com.agora.service.telegram.PositionDrillDownTelegramButtons;
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

@Component
@Slf4j
@RequiredArgsConstructor
public class PositionAgingMonitor {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final NotificationPort notificationPort;
    private final TelegramService telegramService;
    private final EventRiskLevelEngine eventRiskLevelEngine;

    private static final int  AGING_THRESHOLD_DAYS   = 5;
    private static final long AGING_ALERT_INTERVAL_H = 24;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    public void checkAgingPositions() {
        List<BtLiveSignal> openPositions = liveSignalRepository.findByAutoTradedIsTrueAndExitTimeIsNull();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        for (BtLiveSignal pos : openPositions) {
            if (pos.getCreatedAt() == null) continue;

            long daysOpen = ChronoUnit.DAYS.between(pos.getCreatedAt(), now);
            if (daysOpen < AGING_THRESHOLD_DAYS) continue;

            // Use persisted lastAgingAlertAt — survives restarts (V089 fix)
            LocalDateTime lastAlert = pos.getLastAgingAlertAt();
            if (lastAlert != null &&
                    ChronoUnit.HOURS.between(lastAlert, now) < AGING_ALERT_INTERVAL_H) continue;

            // Persist the alert time before sending TG
            pos.setLastAgingAlertAt(now);
            liveSignalRepository.save(pos);

            log.warn("[OcoPoll] Aging position: id={} symbol={} daysOpen={}", pos.getId(), pos.getSymbol(), daysOpen);

            try {
                BigDecimal currentPrice = okxTradingService.getLastPrice(pos.getSymbol());
                EventRiskLevelEngine.Snapshot risk = eventRiskLevelEngine.evaluate(pos.getSymbol());
                BigDecimal refEntry = pos.getActualEntryPrice() != null
                        ? pos.getActualEntryPrice() : pos.getEntryPrice();
                String floatPnlStr = "N/A";
                BigDecimal paperLossPct = BigDecimal.ZERO;
                if (currentPrice != null && refEntry != null && pos.getTradedQty() != null) {
                    boolean posIsShort = "SHORT".equals(pos.getSide());
                    BigDecimal priceDiff = posIsShort
                            ? refEntry.subtract(currentPrice)
                            : currentPrice.subtract(refEntry);
                    BigDecimal effectiveQty = posIsShort
                            ? pos.getTradedQty().multiply(BigDecimal.valueOf(okxTradingService.getContractSizeInBase(pos.getSymbol())))
                            : pos.getTradedQty();
                    BigDecimal floatPnl = priceDiff.multiply(effectiveQty)
                            .setScale(2, RoundingMode.HALF_UP);
                    double floatPct = priceDiff.divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue();
                    if (floatPct < 0) {
                        paperLossPct = BigDecimal.valueOf(Math.abs(floatPct) * 100)
                                .setScale(4, RoundingMode.HALF_UP);
                    }
                    floatPnlStr = String.format("%+.2f USDT (%+.2f%%)",
                            floatPnl.doubleValue(), floatPct * 100);
                }
                boolean criticalDefense = risk.level().atLeast(EventRiskLevelEngine.RiskLevel.R3)
                        && paperLossPct.compareTo(BigDecimal.valueOf(5)) >= 0;
                String message = renderAgingMessage(pos, daysOpen, refEntry, floatPnlStr, risk, criticalDefense);
                String source = criticalDefense ? "PositionDefenseAlert" : "PositionAgingReminder";
                String level = criticalDefense ? "CRITICAL" : "INFO";
                try {
                    telegramService.sendChannelMessageWithKeyboard(
                            message,
                            true,
                            PositionDrillDownTelegramButtons.buildKeyboard(pos.getSymbol(), pos.getId()),
                            source,
                            level);
                } catch (Exception keyboardError) {
                    log.warn("[OcoPoll] aging keyboard alert failed, falling back to plain TG: id={} error={}",
                            pos.getId(), keyboardError.getMessage());
                    if (criticalDefense) {
                        notificationPort.alert(message, true, source, level);
                    } else {
                        notificationPort.broadcast(message, true);
                    }
                }
            } catch (Exception e) {
                log.error("[OcoPoll] Aging alert TG failed: id={} error={}", pos.getId(), e.getMessage());
            }
        }
    }

    private String formatPrice(BigDecimal price) {
        if (price.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%,.2f", price.doubleValue());
        }
        return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }

    String renderAgingMessage(BtLiveSignal pos, long daysOpen, BigDecimal refEntry, String floatPnlStr) {
        return renderAgingMessage(pos, daysOpen, refEntry, floatPnlStr, null, false);
    }

    String renderAgingMessage(BtLiveSignal pos,
                              long daysOpen,
                              BigDecimal refEntry,
                              String floatPnlStr,
                              EventRiskLevelEngine.Snapshot risk,
                              boolean criticalDefense) {
        String level = criticalDefense ? "CRITICAL_DEFENSE_REVIEW" : "REVIEW_ONLY";
        String status = criticalDefense
                ? "R3 風險 + 持倉浮虧達防守門檻，應評估減倉或收緊 OCO"
                : "OCO 保護中，暫無強制操作";
        String riskLine = risk == null
                ? "事件風險：N/A"
                : String.format("事件風險：%s score=%d", risk.level(), risk.score());
        String recommendation = criticalDefense
                ? "建議：先做風險降低處理預案（部分減倉 25-50% 或收緊 SL），再評估 short shadow；此通知不代表已下單或已改 OCO。"
                : "建議：只有在 OCO 異常、價格接近 TP/SL、或風險條件改變時才處理；先用下方按鈕做 read-only drill-down。";
        return String.format(
                "🛡️ <b>持倉健康檢查｜%s #%d</b>\n" +
                "等級：%s（不是買入/賣出指令）\n" +
                "狀態：%s\n" +
                "持倉：%d 天\n" +
                "浮動：%s\n" +
                "進場：$%s（%s）\n" +
                "TP / SL：$%s / $%s\n" +
                "%s\n\n" +
                "%s",
                pos.getSymbol(), pos.getId(),
                level,
                status,
                daysOpen,
                floatPnlStr,
                formatPrice(refEntry), pos.getCreatedAt().format(DATE_FMT),
                pos.getSuggestedTp() != null ? formatPrice(pos.getSuggestedTp()) : "N/A",
                pos.getSuggestedSl() != null ? formatPrice(pos.getSuggestedSl()) : "N/A",
                riskLine,
                recommendation);
    }
}
