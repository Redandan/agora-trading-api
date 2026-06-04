package com.agora.scheduler.trading;

import com.agora.config.properties.ShortSqueezeAlertProperties;
import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import com.agora.service.market.BinanceSpotTakerBuyService;
import com.agora.service.market.OkxLiquidationWsService;
import com.agora.service.market.SqueezeIndicatorService;
import com.agora.service.market.SqueezeIndicatorService.Level;
import com.agora.service.market.SqueezeIndicatorService.SqiResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 軋空預警 v2.1（SQI 整合版）
 *
 * <p>每分鐘執行：
 * <ol>
 *   <li>計算 SQI（三子維度）— 用於 alert 判斷，<b>不寫 DB</b>（#405）</li>
 *   <li>路徑 A（熊市積累型，marketPhase != BULL）</li>
 *   <li>路徑 B（牛市急速型，WS 爆倉異常 + 價格上行）</li>
 *   <li>SQI &gt;= 75 直接 Critical 告警（無需路徑條件）</li>
 * </ol>
 *
 * <p><b>#405 — SQI 持久化責任</b>：5 個 SQI 系列 indicator
 * （{@code sqi} / {@code sqi_short_crowding} / {@code sqi_liquidation_anomaly} /
 * {@code sqi_price_confirmation} / {@code short_build_index}）
 * 由 {@link CompositeIndicatorScheduler} 透過 {@code SqiIndicator}
 * 獨佔寫入。本 scheduler 只負責 alert 邏輯與週邊 indicator
 * （market_phase / squeeze_fuel_score / squeeze_trigger_score /
 * squeeze_readiness_b / short_squeeze_readiness）— 那些是本 scheduler
 * 獨有，不會與 CMI 框架重疊。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortSqueezeAlertScheduler {

    private final MarketIndicatorHistoryRepository historyRepo;
    private final NotificationPort                 notificationPort;
    private final TgNotificationDeduper            deduper;
    private final SqueezeIndicatorService          sqiService;
    private final OkxLiquidationWsService          liquidationWs;
    private final ShortSqueezeAlertProperties      props;

    private static final String SYM = "BTCUSDT";

    /** #362 — per-path dedup keys. Pre-migration the scheduler used a single
     *  TG_SOURCE for all 3 paths + initCooldownsFromDb to seed all 3 cooldown
     *  caches from the same shared timestamp. The deduper's per-key DB warm-up
     *  replaces both. Each key is its own freshness signal — A firing no longer
     *  freezes Sqi/B (which the old shared-source design caused as a side
     *  effect of the cross-path cooldown sharing). */
    static final String KEY_SQI    = "ShortSqueeze:sqi";
    static final String KEY_PATH_A = "ShortSqueeze:pathA";
    static final String KEY_PATH_B = "ShortSqueeze:pathB";

    private final AtomicBoolean evaluateRunning = new AtomicBoolean(false);

    private enum MarketPhase { BULL, NEUTRAL, BEAR }

    // ─────────────────────────────────────────────────────────────────────────

    @Scheduled(fixedRate = 60_000, initialDelay = 90_000)
    public void evaluate() {
        if (!props.enabled()) return;
        if (!evaluateRunning.compareAndSet(false, true)) {
            log.warn("[ShortSqueezeAlert] previous evaluation still running; skipping this tick");
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

            // ── 1. 計算 SQI（in-memory only — #405 持久化交給 CompositeIndicatorScheduler）──
            SqiResult sqi = sqiService.calcSqi();

            // ── 2. 市場階段 ──
            MarketPhase phase = detectMarketPhase(now);
            saveIndicator("market_phase", phase == MarketPhase.BULL ? 1 : phase == MarketPhase.BEAR ? -1 : 0, now);

            // ── 3. SQI 連續性驗證（防單點誤報）──
            // Critical(>=75)：無需連續，立即告警
            // Warning/Alert(<75)：需前一小時 SQI >= 20，確認是持續信號而非單點餘震
            boolean sustained = sqi.getSqi() >= 75 || isPreviousHourElevated(now, 20);

            // ── 4. 頂部方向性過濾（closes #293）──
            // 若過去 1h 價格下跌 > 0.3%，代表擠倉已見頂，非 Critical 不重複告警
            // 防止：擠倉結束後 liqAnomaly 仍餘熱觸發的頂部訊號（04-17 14:00-15:00 模式）
            double priceChange1h = getPriceChange1h(now);
            boolean priceRising  = priceChange1h > -0.003;

            // ── 5. 告警分級（V3 閾值調整：Warning 從 50 → 40）──
            //
            //  SQI >= 75 CRITICAL：直接告警，豁免過濾
            //  SQI 40-74 WARNING ：sustained + priceRising → 發 TG 警告
            //  SQI 30-39 ALERT   ：LOG only，不發 TG（僅指標持久化）
            //  SQI < 30  NORMAL  ：靜默

            Duration cooldown = Duration.ofMinutes(props.cooldownMinutes());

            if (sqi.getSqi() >= 75 && deduper.shouldSend(KEY_SQI, cooldown, Severity.WARN)) {
                sendSqiAlert(sqi);
                return; // Critical 告警後本輪結束
            }

            // Warning（40-74）：需同時通過連續性 + 方向性兩道過濾
            if (sqi.getSqi() >= 40 && sustained && priceRising
                    && deduper.shouldSend(KEY_SQI, cooldown, Severity.WARN)) {
                sendSqiWarning(sqi, priceChange1h);
                return;
            }

            // 低於 Warning 閾值：LOG only（ALERT/NORMAL），不觸發 TG
            // 路徑 A/B 作為補充觸發（當 SQI 因數據不足未達 40，但條件組合已滿足時）
            if (!sustained || !priceRising || sqi.getSqi() < 30) return;

            // ── 6. 路徑 A（熊市積累型，SQI 驅動不足時的補充）──
            boolean pathAFired = false;
            if (phase != MarketPhase.BULL) {
                pathAFired = evaluatePathA(now, sqi);
            }

            // ── 7. 路徑 B（牛市急速型）──
            evaluatePathB(now, sqi, pathAFired);
        } finally {
            evaluateRunning.set(false);
        }
    }

    // ── 路徑 A ────────────────────────────────────────────────────────────────

    private boolean evaluatePathA(LocalDateTime now, SqiResult sqi) {
        List<MarketIndicatorHistory> fundingHistory = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "funding_rate", now.minusHours(4));
        List<MarketIndicatorHistory> takerHistory = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, BinanceSpotTakerBuyService.INDICATOR, now.minusMinutes(16));

        if (fundingHistory.size() < 2 || takerHistory.isEmpty()) return false;

        double avgFunding = fundingHistory.stream()
                .mapToDouble(h -> h.getValue().doubleValue()).average().orElse(0);
        double latestTakerBuy = takerHistory.get(takerHistory.size() - 1).getValue().doubleValue();

        double fuelScore    = clamp(-avgFunding / Math.abs(props.fundingRateThreshold()));
        double triggerScore = clamp(latestTakerBuy / props.spotTakerBuyThreshold());
        saveIndicator("squeeze_fuel_score",    fuelScore,    now);
        saveIndicator("squeeze_trigger_score", triggerScore, now);

        if (avgFunding < props.fundingRateThreshold() && latestTakerBuy > props.spotTakerBuyThreshold()
                && deduper.shouldSend(KEY_PATH_A,
                        Duration.ofMinutes(props.cooldownMinutes()), Severity.WARN)) {
            notificationPort.alert(String.format(
                    "🔔 <b>軋空預警（積累型）</b>\n\n" +
                    "📊 SQI = %d %s %s\n   %s\n\n" +
                    "🔴 4h 資金費率 <code>%.5f%%</code>（閾值 %.5f%%）\n" +
                    "⚡ 15min 主動買單 <code>$%.1fM</code>（閾值 $%.1fM）",
                    sqi.getSqi(), sqi.getLevel().emoji(), sqi.getLevel().label,
                    sqi.formatDecomposed(),
                    avgFunding * 100, props.fundingRateThreshold() * 100,
                    latestTakerBuy / 1e6, props.spotTakerBuyThreshold() / 1e6), true, KEY_PATH_A, "WARN");
            return true;
        }
        return false;
    }

    // ── 路徑 B ────────────────────────────────────────────────────────────────

    private void evaluatePathB(LocalDateTime now, SqiResult sqi, boolean pathAFired) {
        // 爆倉數據：優先 WebSocket，降級用 mih 小時數據
        double liq5m;
        boolean wsActive = liquidationWs.isConnected() && !liquidationWs.isDegraded();
        if (wsActive) {
            liq5m = liquidationWs.getShortLiqUsd(5);
        } else {
            List<MarketIndicatorHistory> liqRecent = historyRepo
                    .findTop2BySymbolAndIndicatorOrderByCapturedAtDesc(SYM, "btc_short_liq_usd_1h");
            liq5m = liqRecent.isEmpty() ? 0 : liqRecent.get(0).getValue().doubleValue();
        }

        Double p95 = historyRepo.findPercentile95(
                SYM, "btc_short_liq_usd_1h", now.minusDays(30));
        double threshold = (p95 != null && p95 > 0) ? p95 : props.pathBFallbackThreshold();

        double ratio        = threshold > 0 ? liq5m / threshold : 0;
        double priceChange  = getPriceChange(now);
        double readinessB   = clamp((ratio / props.pathBMinRatio()) * 0.7 + (priceChange > 0.003 ? 0.3 : 0));

        saveIndicator("squeeze_readiness_b", readinessB, now);

        boolean liqSurge = liq5m > threshold && ratio > props.pathBMinRatio();
        boolean isUpward = priceChange > 0.003;

        if (liqSurge && isUpward
                && deduper.shouldSend(KEY_PATH_B,
                        Duration.ofMinutes(props.cooldownMinutes()), Severity.WARN)) {
            String header = pathAFired
                    ? "🚨 <b>CRITICAL 軋空共振（雙路徑同時觸發）</b>"
                    : "⚡ <b>軋空進行中（即時型）</b>";
            String wsTag = wsActive ? "WebSocket 即時" : "小時近似（WS 降級）";
            notificationPort.alert(String.format(
                    "%s\n\n" +
                    "📊 SQI = %d %s %s\n   %s\n\n" +
                    "💥 空頭爆倉（%s）<code>$%.1fM</code>（95分位 $%.1fM，環比 %.1f 倍）\n" +
                    "📈 近期價格 <code>+%.2f%%</code>（方向確認：上行）\n\n" +
                    "→ 參考 Rule #37（$30M+ 小時爆倉確認）",
                    header,
                    sqi.getSqi(), sqi.getLevel().emoji(), sqi.getLevel().label,
                    sqi.formatDecomposed(),
                    wsTag, liq5m / 1e6, threshold / 1e6, ratio,
                    priceChange * 100), true, KEY_PATH_B, "WARN");
        }

        // 綜合 readiness
        double fuelScore    = getIndicatorValue("squeeze_fuel_score",    now);
        double triggerScore = getIndicatorValue("squeeze_trigger_score", now);
        double readiness    = (fuelScore + triggerScore + readinessB) / 3.0;
        saveIndicator("short_squeeze_readiness", readiness, now);
    }

    // ── SQI Warning 告警（40-74）─────────────────────────────────────────────

    private void sendSqiWarning(SqiResult sqi, double priceChange1h) {
        notificationPort.alert(String.format(
                "⚠️ <b>軋空警告 SQI = %d</b> %s %s\n\n" +
                "📊 %s\n\n" +
                "空頭擁擠度：%.0f/40\n" +
                "爆倉異常度：%.0f/40\n" +
                "價格確認度：%.0f/20\n\n" +
                "📈 1h 價格變化：<code>%+.2f%%</code>",
                sqi.getSqi(), sqi.getLevel().emoji(), sqi.getLevel().label,
                sqi.formatDecomposed(),
                sqi.getCrowding() * 0.40,
                sqi.getLiqAnomaly() * 0.40,
                sqi.getPriceConfirmation() * 0.20,
                priceChange1h * 100), true, KEY_SQI, "WARN");
        log.info("[ShortSqueezeAlert] SQI Warning FIRED: {}", sqi.getSqi());
    }

    // ── SQI Critical 告警（>=75）──────────────────────────────────────────────

    private void sendSqiAlert(SqiResult sqi) {
        notificationPort.alert(String.format(
                "🚨 <b>軋空 Critical（SQI = %d）</b>\n\n" +
                "📊 %s %s\n   %s\n\n" +
                "空頭擁擠度：%.0f/40\n" +
                "爆倉異常度：%.0f/40\n" +
                "價格確認度：%.0f/20\n\n" +
                "→ 參考 Rule #37（$30M+ 小時爆倉確認）",
                sqi.getSqi(),
                sqi.getLevel().emoji(), sqi.getLevel().label,
                sqi.formatDecomposed(),
                sqi.getCrowding() * 0.40,
                sqi.getLiqAnomaly() * 0.40,
                sqi.getPriceConfirmation() * 0.20), true, KEY_SQI, "CRITICAL");
        log.info("[ShortSqueezeAlert] SQI Critical FIRED: {}", sqi.getSqi());
    }

    // ── 工具方法 ──────────────────────────────────────────────────────────────

    private MarketPhase detectMarketPhase(LocalDateTime now) {
        List<MarketIndicatorHistory> h = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "funding_rate", now.minusDays(30));
        if (h.size() < 24) return MarketPhase.NEUTRAL;
        double avg = h.stream().mapToDouble(r -> r.getValue().doubleValue()).average().orElse(0);
        if (avg > 0.0001)   return MarketPhase.BULL;
        if (avg < -0.00005) return MarketPhase.BEAR;
        return MarketPhase.NEUTRAL;
    }

    private double getPriceChange(LocalDateTime now) {
        List<MarketIndicatorHistory> prices = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "kraken_btc_usd_price", now.minusMinutes(10));
        if (prices.size() < 2) return 0;
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 ? (latest - older) / older : 0;
    }

    /** 1 小時價格變化（頂部過濾用） */
    private double getPriceChange1h(LocalDateTime now) {
        List<MarketIndicatorHistory> prices = historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "kraken_btc_usd_price", now.minusHours(2));
        if (prices.size() < 2) return 0; // 無數據時不過濾
        double latest = prices.get(prices.size() - 1).getValue().doubleValue();
        double older  = prices.get(0).getValue().doubleValue();
        return older > 0 ? (latest - older) / older : 0;
    }

    private double getIndicatorValue(String indicator, LocalDateTime now) {
        return historyRepo.findTopCleanBySymbolAndIndicator(SYM, indicator)
                .map(h -> h.getValue().doubleValue()).orElse(0.0);
    }

    /**
     * 連續信號驗證：前一小時是否也有 SQI >= minScore。
     * 防止單點爆倉餘震誤報（04-20/04-22 模式：僅 1 小時孤立觸發）。
     * 真實擠倉（04-17）持續 5+ 小時，必然通過此檢查。
     */
    /**
     * 查 now 前 2 小時內是否曾有 SQI >= minScore（排除本輪剛寫入的當前值）。
     * 邏輯：saveSqiIndicators 用 capturedAt=now 寫入；這裡用 isBefore(now) 排除它，
     * 確保檢查的是「上一輪的歷史數據」而不是當前計算結果本身。
     */
    private boolean isPreviousHourElevated(LocalDateTime now, int minScore) {
        return historyRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        SYM, "sqi", now.minusHours(2))
                .stream()
                .filter(h -> h.getCapturedAt().isBefore(now)) // 排除本輪剛寫入的當前值
                .anyMatch(h -> h.getValue().doubleValue() >= minScore);
    }

    /**
     * #405 — SQI 系列 5 個 indicator 不在這寫；由 {@link CompositeIndicatorScheduler}
     * 透過 {@code SqiIndicator} 獨佔寫入。本 method 只接受 alert 週邊 indicator
     * （market_phase / squeeze_*）— 加 guard 防未來誤用重新引入雙寫 bug。
     */
    private static final java.util.Set<String> CMI_OWNED_INDICATORS = java.util.Set.of(
            "sqi",
            "sqi_short_crowding",
            "sqi_liquidation_anomaly",
            "sqi_price_confirmation",
            "short_build_index");

    private void saveIndicator(String indicator, double value, LocalDateTime capturedAt) {
        if (CMI_OWNED_INDICATORS.contains(indicator)) {
            // #405 regression guard — never write CMI-owned indicators here.
            log.warn("[ShortSqueezeAlert] refusing to write CMI-owned indicator '{}' (see #405)",
                    indicator);
            return;
        }
        BigDecimal scaled = BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
        historyRepo.insertIgnore(SYM, indicator, capturedAt, scaled);
    }

    private static double clamp(double v) { return Math.min(1.0, Math.max(0.0, v)); }
}
