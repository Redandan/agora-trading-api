package com.agora.scheduler.trading;

import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.indicator.CompositeIndicator;
import com.agora.service.indicator.CompositeResult;
import com.agora.service.indicator.HysteresisAlertGuard;
import com.agora.service.indicator.IndicatorLevel;
import com.agora.service.indicator.SubDimension;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * CMI Framework 統一排程器。
 *
 * <p>每分鐘自動驅動所有 {@link CompositeIndicator} 實作：
 * <ol>
 *   <li>呼叫 {@code calculate()} 取得分數</li>
 *   <li>持久化主分數 + 所有子維度到 {@code market_indicator_history}</li>
 *   <li>透過 {@link HysteresisAlertGuard} 決定 TG 告警動作</li>
 * </ol>
 *
 * <p>告警邏輯（#404 hysteresis state machine）：
 * <ul>
 *   <li><b>ENTER</b> — score 跨上 {@code warningThreshold}：發「進入警戒」訊息
 *       （WARNING 層仍套 sustained + directional filter；CRITICAL 豁免）</li>
 *   <li><b>REMINDER</b> — 持續 elevated ≥ {@code reminderHours}（預設 12h）：發提醒</li>
 *   <li><b>EXIT</b> — score 跌出 {@code elevatedExitScore}（預設 = alertThreshold）：發回歸正常</li>
 *   <li><b>SUPPRESS</b> — dead-zone hover（exit ≤ score < warning）或 NORMAL：靜默</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompositeIndicatorScheduler {

    private final List<CompositeIndicator> indicators; // Spring 自動注入所有實作
    private final MarketIndicatorHistoryRepository historyRepo;
    private final NotificationPort notificationPort;
    private final HysteresisAlertGuard hysteresisGuard;
    private final AtomicBoolean evaluateRunning = new AtomicBoolean(false);

    @Scheduled(fixedRate = 60_000, initialDelay = 90_000)
    public void evaluate() {
        if (!evaluateRunning.compareAndSet(false, true)) {
            log.warn("[CMI] previous evaluation still running; skipping this tick");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);

            for (CompositeIndicator ind : indicators) {
                try {
                    CompositeResult result = ind.calculate(now);
                    persist(ind, result, now);
                    checkAndAlert(ind, result, now);
                } catch (Exception e) {
                    log.warn("[CMI] {} evaluation failed: {}", ind.getName(), e.getMessage());
                }
            }
        } finally {
            evaluateRunning.set(false);
        }
    }

    // ── 持久化 ────────────────────────────────────────────────────────────────

    private void persist(CompositeIndicator ind, CompositeResult result, LocalDateTime now) {
        // 主分數
        save(ind.getSymbol(), ind.getName(), result.score(), now);
        // 子維度
        for (SubDimension dim : ind.getDimensions()) {
            double val = result.dimValues().getOrDefault(dim.mhiKey(), 0.0);
            save(ind.getSymbol(), dim.mhiKey(), val, now);
        }
        // 非聲明子維度的額外指標（如 short_build_index 附帶在 SQI result 中）
        for (Map.Entry<String, Double> entry : result.dimValues().entrySet()) {
            boolean isDeclared = ind.getDimensions().stream()
                    .anyMatch(d -> d.mhiKey().equals(entry.getKey()));
            boolean isMain = ind.getName().equals(entry.getKey());
            if (!isDeclared && !isMain) {
                save(ind.getSymbol(), entry.getKey(), entry.getValue(), now);
            }
        }
    }

    // ── 告警判斷 ──────────────────────────────────────────────────────────────

    /**
     * #404 — Hysteresis-driven alert decision.
     *
     * <p>Always invokes {@link HysteresisAlertGuard#evaluate(CompositeIndicator, int, LocalDateTime)}
     * so the persisted state stays in sync with the current score, even when
     * we end up suppressing the TG send. Only ENTER / REMINDER / EXIT cause a
     * TG fire; SUPPRESS is the silent path during dead-zone hover.
     *
     * <p>Sustained / directional filters apply only to ENTER (the moment a
     * WARNING-level indicator first crosses up) and only when the level is
     * not CRITICAL — those filters were originally added to fight noise on
     * threshold-grazing WARNINGs, which is exactly when ENTER fires; CRITICAL
     * cross-ups bypass them as before.
     */
    private void checkAndAlert(CompositeIndicator ind, CompositeResult result, LocalDateTime now) {
        HysteresisAlertGuard.Decision decision =
                hysteresisGuard.evaluate(ind, result.score(), now);

        switch (decision) {
            case SUPPRESS -> {
                // dead-zone hover or below warning band — silent
            }
            case ENTER -> {
                if (result.level() == IndicatorLevel.WARNING) {
                    if (ind.isSustainedRequired() && !isPreviousHourElevated(ind, now)) return;
                    if (ind.isDirectionalFilterEnabled() && isPriceFalling(ind.getSymbol(), now)) return;
                }
                sendAlert(ind, result, "ENTER");
            }
            case REMINDER -> sendAlert(ind, result, "REMINDER");
            case EXIT    -> sendExit(ind, result);
        }
    }

    private void sendAlert(CompositeIndicator ind, CompositeResult result, String transition) {
        String msg = ind.formatAlertMessage(result);
        msg = String.format(
                "📎 <b>MARKET_RISK_REVIEW｜%s</b>\n" +
                "非交易指令：這張卡不是 BUY/SELL；只作為倉位與風控背景。\n\n%s",
                ind.getDisplayName(), msg);
        if ("REMINDER".equals(transition)) {
            msg = "⏰ <b>持續警戒提醒</b>\n\n" + msg;
        }
        notificationPort.alert(msg, true,
                ind.getClass().getSimpleName(),
                result.level().name());
        log.info("[CMI] {} {}: score={} level={}",
                ind.getName(), transition, result.score(), result.level());
    }

    private void sendExit(CompositeIndicator ind, CompositeResult result) {
        String msg = String.format(
                "✅ <b>%s 回歸正常</b>\n\n當前分數：%d（已跌出警戒區）",
                ind.getDisplayName(), result.score());
        notificationPort.alert(msg, true,
                ind.getClass().getSimpleName(),
                "INFO");
        log.info("[CMI] {} EXIT: score={}", ind.getName(), result.score());
    }

    /** 前 2h 內是否有主分數 >= alertThreshold（sustained 檢查）。 */
    private boolean isPreviousHourElevated(CompositeIndicator ind, LocalDateTime now) {
        return historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        ind.getSymbol(), ind.getName(), now.minusHours(2))
                .stream()
                .filter(h -> h.getCapturedAt().isBefore(now))
                .anyMatch(h -> h.getValue().doubleValue() >= ind.getAlertThreshold());
    }

    /** 過去 1h 價格是否下跌 > 0.3%（方向過濾）。 */
    private boolean isPriceFalling(String symbol, LocalDateTime now) {
        var prices = historyRepo.findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                symbol, "kraken_btc_usd_price", now.minusHours(2));
        if (prices.size() < 2) return false;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 && (latest - older) / older < -0.003;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private void save(String symbol, String indicator, double value, LocalDateTime capturedAt) {
        try {
            int inserted = historyRepo.insertIgnore(
                    symbol,
                    indicator,
                    capturedAt,
                    BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP));
            if (inserted == 0) {
                log.debug("[CMI] duplicate snapshot ignored {}/{}", indicator, capturedAt);
            }
        } catch (Exception e) {
            log.warn("[CMI] save failed {}/{}: {}", indicator, capturedAt, e.getMessage());
        }
    }
}
