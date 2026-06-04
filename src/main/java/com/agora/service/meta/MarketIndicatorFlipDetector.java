package com.agora.service.meta;

import com.agora.config.properties.MarketFlipDetectorProperties;
import com.agora.model.MarketFlipConfig;
import com.agora.model.MarketFlipEvent;
import com.agora.repository.trading.MarketFlipConfigRepository;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.infra.notification.NotificationPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 偵測市場指標跨越關鍵門檻或大幅變化 (F&amp;G / 鯨魚買入比),即時 TG 通知
 * 並寫入 {@code market_flip_event} 表供後續 AI 分析。
 *
 * <p><b>Event-driven:</b> 由 {@link com.agora.service.backtest.LiveSignalEvaluator}
 * 在每根 bar 收盤載入 SentimentContext 後呼叫 {@link #checkAndNotify}。
 *
 * <p><b>Phase 2A 運作模式 (shadow):</b>
 * <ul>
 *   <li>Mode SHADOW (預設): 即時 TG(維持 Phase 1 行為)+ 寫 event 到 DB 供觀察</li>
 *   <li>Mode COMPARE: 即時 TG + 寫 event,AI 會對 event 做分析產生第二條 TG(Phase 2B)</li>
 *   <li>Mode ACTIVE: 不即時 TG,完全由 AI 分析後決定(Phase 2C)</li>
 * </ul>
 *
 * <p><b>門檻設定</b>從 {@code market_flip_config} 表讀(首次 V038 初始化為 hardcoded 預設值)。
 * 變更門檻請透過 {@code tuneFlipThreshold} MCP 工具,會寫 audit。
 *
 * <p><b>去重:</b> in-memory 快取上次觀察值;同值不重複檢測。
 */
@Slf4j
@Service
public class MarketIndicatorFlipDetector {

    private static final String INDICATOR_FG    = "fear_greed";
    private static final String INDICATOR_WHALE = "whale_buy_ratio";

    private final MarketFlipEventRepository eventRepo;
    private final MarketFlipConfigRepository configRepo;
    private final NotificationPort notificationPort;
    private final ObjectMapper objectMapper;
    private final DataQualityMonitor dataQualityMonitor;
    private final MarketFlipDetectorProperties props;

    /** key = symbol, value = 上次觀察值 */
    private final Map<String, Snapshot> lastObserved = new HashMap<>();
    /** key = symbol:indicator, value = last emitted risk state; suppresses threshold-edge chatter. */
    private final Map<String, String> lastEmittedState = new HashMap<>();

    public MarketIndicatorFlipDetector(MarketFlipEventRepository eventRepo,
                                       MarketFlipConfigRepository configRepo,
                                       NotificationPort notificationPort,
                                       ObjectMapper objectMapper,
                                       DataQualityMonitor dataQualityMonitor,
                                       MarketFlipDetectorProperties props) {
        this.eventRepo = eventRepo;
        this.configRepo = configRepo;
        this.notificationPort = notificationPort;
        this.objectMapper = objectMapper;
        this.dataQualityMonitor = dataQualityMonitor;
        this.props = props;
    }

    /**
     * LiveSignalEvaluator 在載入 SentimentContext 後呼叫。
     * 比對前值,跨門檻或大變:
     *  - SHADOW/COMPARE: TG + 寫 event
     *  - ACTIVE: 只寫 event (交由 AI 決定是否 TG)
     */
    public void checkAndNotify(String symbol, int fgValue, double whaleRatio) {
        if (!props.enabled()) return;
        try {
            Snapshot prev = lastObserved.get(symbol);
            Snapshot current = new Snapshot(fgValue, whaleRatio, LocalDateTime.now(ZoneOffset.UTC));
            lastObserved.put(symbol, current);

            // 首次觀察 = seeding,不發 TG 也不寫 event(避免啟動爆噴)
            if (prev == null) {
                log.debug("[MarketFlip] {} seeded: fg={} whale={}", symbol, fgValue,
                        String.format("%.2f", whaleRatio));
                return;
            }

            if (prev.fg == fgValue && Math.abs(prev.whale - whaleRatio) < 0.005) return;

            // 讀 config 決定門檻(fallback 到 hardcoded 預設)
            MarketFlipConfig fgCfg    = configRepo.findBySymbolAndIndicator(symbol, INDICATOR_FG).orElse(null);
            MarketFlipConfig whaleCfg = configRepo.findBySymbolAndIndicator(symbol, INDICATOR_WHALE).orElse(null);

            FlipResult fgFlip    = detectFgFlip(prev.fg, fgValue, fgCfg);
            FlipResult whaleFlip = detectWhaleFlip(prev.whale, whaleRatio, whaleCfg);
            if (fgFlip == null) {
                fgFlip = detectHysteresisClear(symbol, INDICATOR_FG, fgValue, fgCfg);
            }
            if (whaleFlip == null) {
                whaleFlip = detectHysteresisClear(symbol, INDICATOR_WHALE, whaleRatio, whaleCfg);
            }

            StringBuilder tgMsg = new StringBuilder();
            if (fgFlip != null && shouldEmitStateTransition(symbol, INDICATOR_FG, fgValue, fgCfg)) {
                persistEvent(symbol, INDICATOR_FG, prev.fg, fgValue, fgFlip, current);
                tgMsg.append(fgFlip.description).append('\n');
            }
            if (whaleFlip != null && shouldEmitStateTransition(symbol, INDICATOR_WHALE, whaleRatio, whaleCfg)) {
                persistEvent(symbol, INDICATOR_WHALE, prev.whale, whaleRatio, whaleFlip, current);
                tgMsg.append(whaleFlip.description).append('\n');
            }

            // SHADOW/COMPARE 模式才即時 TG; ACTIVE 模式完全交由 AI 決定
            if (tgMsg.length() > 0 && !"ACTIVE".equalsIgnoreCase(props.mode())) {
                sendAlert(symbol, prev, current, tgMsg.toString());
            }
        } catch (Throwable t) {
            log.warn("[MarketFlip] {} check failed (non-fatal): {}", symbol, t.getMessage());
        }
    }

    private FlipResult detectFgFlip(int prev, int current, MarketFlipConfig cfg) {
        int loT   = cfg != null && cfg.getThresholdLo() != null ? cfg.getThresholdLo().intValue() : 25;
        int hiT   = cfg != null && cfg.getThresholdHi() != null ? cfg.getThresholdHi().intValue() : 75;
        int deltaT = cfg != null && cfg.getDeltaThreshold() != null ? cfg.getDeltaThreshold().intValue() : 20;

        if (crossed(prev, current, loT)) {
            return new FlipResult("fg_" + loT,
                    String.format("  F&G 跨 %d: %d → %d (%s 恐慌區)", loT, prev, current,
                            current < loT ? "進入" : "離開"),
                    Math.abs(current - prev));
        }
        if (crossed(prev, current, hiT)) {
            return new FlipResult("fg_" + hiT,
                    String.format("  F&G 跨 %d: %d → %d (%s 貪婪區)", hiT, prev, current,
                            current > hiT ? "進入" : "離開"),
                    Math.abs(current - prev));
        }
        if (Math.abs(current - prev) >= deltaT) {
            return new FlipResult("fg_delta",
                    String.format("  F&G 大變: %d → %d (Δ %+d)", prev, current, current - prev),
                    Math.abs(current - prev));
        }
        return null;
    }

    private FlipResult detectWhaleFlip(double prev, double current, MarketFlipConfig cfg) {
        double loT    = cfg != null && cfg.getThresholdLo() != null ? cfg.getThresholdLo().doubleValue() : 0.25;
        double hiT    = cfg != null && cfg.getThresholdHi() != null ? cfg.getThresholdHi().doubleValue() : 0.65;
        double deltaT = cfg != null && cfg.getDeltaThreshold() != null ? cfg.getDeltaThreshold().doubleValue() : 0.20;

        if (crossed(prev, current, loT)) {
            String direction = current < loT
                    ? "⚠️ 進入 LongAiFilter 擋 LONG 區 (鯨魚賣太凶)"
                    : "✅ 離開 LongAiFilter 擋 LONG 區";
            return new FlipResult(String.format("whale_%d", (int)(loT * 100)),
                    String.format("  鯨魚買入比跨 %.0f%%: %.1f%% → %.1f%%  %s",
                            loT * 100, prev * 100, current * 100, direction),
                    Math.abs(current - prev));
        }
        if (crossed(prev, current, hiT)) {
            String direction = current > hiT
                    ? "⚠️ 進入 ShortAiFilter 擋 SHORT 區 (鯨魚買太凶)"
                    : "✅ 離開 ShortAiFilter 擋 SHORT 區";
            return new FlipResult(String.format("whale_%d", (int)(hiT * 100)),
                    String.format("  鯨魚買入比跨 %.0f%%: %.1f%% → %.1f%%  %s",
                            hiT * 100, prev * 100, current * 100, direction),
                    Math.abs(current - prev));
        }
        if (Math.abs(current - prev) >= deltaT) {
            return new FlipResult("whale_delta",
                    String.format("  鯨魚大變: %.1f%% → %.1f%% (Δ %+.1f pp)",
                            prev * 100, current * 100, (current - prev) * 100),
                    Math.abs(current - prev));
        }
        return null;
    }

    private FlipResult detectHysteresisClear(String symbol, String indicator, double current, MarketFlipConfig cfg) {
        String previousState = lastEmittedState.get(symbol + ":" + indicator);
        if (previousState == null || "NEUTRAL".equals(previousState)) return null;
        String currentState = stateWithHysteresis(indicator, current, cfg, previousState);
        if (!"NEUTRAL".equals(currentState)) return null;
        String label = INDICATOR_WHALE.equals(indicator) ? "whale_clear" : "fg_clear";
        String description = INDICATOR_WHALE.equals(indicator)
                ? String.format("  鯨魚買入比離開風險區: %.1f%%  ✅ 回到中性區", current * 100)
                : String.format("  F&G 離開風險區: %.0f  ✅ 回到中性區", current);
        return new FlipResult(label, description, 0);
    }

    private boolean shouldEmitStateTransition(String symbol, String indicator, double current, MarketFlipConfig cfg) {
        String key = symbol + ":" + indicator;
        String previousState = lastEmittedState.get(key);
        String currentState = stateWithHysteresis(indicator, current, cfg, previousState);
        if (currentState.equals(previousState)) {
            log.debug("[MarketFlip] {} {} suppressed same-state chatter state={} current={}",
                    symbol, indicator, currentState, current);
            return false;
        }
        lastEmittedState.put(key, currentState);
        return true;
    }

    private String stateWithHysteresis(String indicator, double current, MarketFlipConfig cfg, String previousState) {
        if (INDICATOR_WHALE.equals(indicator)) {
            double loT = cfg != null && cfg.getThresholdLo() != null ? cfg.getThresholdLo().doubleValue() : 0.25;
            double hiT = cfg != null && cfg.getThresholdHi() != null ? cfg.getThresholdHi().doubleValue() : 0.65;
            double hys = 0.03;
            if ("WHALE_BUY_RISK".equals(previousState) && current >= hiT - hys) return "WHALE_BUY_RISK";
            if ("WHALE_SELL_RISK".equals(previousState) && current <= loT + hys) return "WHALE_SELL_RISK";
            if (current >= hiT) return "WHALE_BUY_RISK";
            if (current <= loT) return "WHALE_SELL_RISK";
            return "NEUTRAL";
        }

        int loT = cfg != null && cfg.getThresholdLo() != null ? cfg.getThresholdLo().intValue() : 25;
        int hiT = cfg != null && cfg.getThresholdHi() != null ? cfg.getThresholdHi().intValue() : 75;
        int hys = 3;
        if ("FEAR_RISK".equals(previousState) && current <= loT + hys) return "FEAR_RISK";
        if ("GREED_RISK".equals(previousState) && current >= hiT - hys) return "GREED_RISK";
        if (current <= loT) return "FEAR_RISK";
        if (current >= hiT) return "GREED_RISK";
        return "NEUTRAL";
    }

    private void persistEvent(String symbol, String indicator,
                              double prev, double current,
                              FlipResult flip, Snapshot ctx) {
        try {
            MarketFlipEvent event = new MarketFlipEvent();
            event.setSymbol(symbol);
            event.setIndicator(indicator);
            event.setPrevValue(BigDecimal.valueOf(prev));
            event.setCurrentValue(BigDecimal.valueOf(current));
            event.setThresholdCrossed(flip.label);
            event.setDeltaValue(BigDecimal.valueOf(flip.delta));
            event.setDetectedAt(LocalDateTime.now(ZoneOffset.UTC));
            event.setStatus("PENDING");

            // 寫 context snapshot (F&G + whale 當前值) + 資料品質 flag
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("fg", ctx.fg);
            context.put("whale", ctx.whale);
            context.put("mode", props.mode());

            // DataQualityMonitor 在 save 前跑,flag anomalous 讓 AI 納入考量 (不阻擋寫入)
            try {
                DataQualityMonitor.AnomalyResult anomaly = dataQualityMonitor.check(
                        symbol, indicator, prev, current);
                if (anomaly.anomalous()) {
                    context.put("anomalous", true);
                    context.put("anomaly_reasons", anomaly.reasons());
                    log.warn("[MarketFlip] {} {} anomalous flip: {} (prev={}, current={})",
                            symbol, indicator, anomaly.reasons(), prev, current);
                }
            } catch (Exception e) {
                // fail-open,不影響 event 寫入
                log.debug("[MarketFlip] DataQualityMonitor check failed: {}", e.getMessage());
            }

            try {
                event.setContextJson(objectMapper.writeValueAsString(context));
            } catch (Exception e) {
                event.setContextJson("{}");
            }

            eventRepo.save(event);
            log.debug("[MarketFlip] persisted event id={} {} {} {}→{}",
                    event.getId(), symbol, indicator, prev, current);
        } catch (Exception e) {
            log.warn("[MarketFlip] persistEvent failed (non-fatal): {}", e.getMessage());
        }
    }

    private static boolean crossed(double prev, double current, double threshold) {
        return (prev < threshold && current >= threshold)
            || (prev >= threshold && current < threshold);
    }

    private static boolean crossed(int prev, int current, int threshold) {
        return (prev < threshold && current >= threshold)
            || (prev >= threshold && current < threshold);
    }

    private void sendAlert(String symbol, Snapshot prev, Snapshot current, String flips) {
        String msg = renderAlertMessage(symbol, prev, current, flips, props.mode());
        log.info("[MarketFlip] {} alert: {}", symbol, flips.trim().replace('\n', ' '));
        try {
            notificationPort.broadcast(msg, true);
        } catch (Exception e) {
            log.warn("[MarketFlip] TG send failed: {}", e.getMessage());
        }
    }

    String renderAlertMessage(String symbol, Snapshot prev, Snapshot current, String flips, String mode) {
        boolean shadow = mode == null || "SHADOW".equalsIgnoreCase(mode);
        String title = shadow
                ? "🧪 <b>Shadow 市場狀態變化</b>"
                : "🌊 <b>Market 指標翻轉</b>";
        String level = shadow
                ? "SHADOW_INFO（僅觀察，不影響實際下單）"
                : "REVIEW_ONLY（不是買入/賣出指令）";
        String suggestion = shadow
                ? "不操作；等 active 策略或多指標共振再看。"
                : "只當風險背景；需搭配 active 策略、倉位與風控再判斷。";
        return String.format(
                "%s — <code>%s</code>%n" +
                "等級：%s%n%n" +
                "%s%n" +
                "前次觀察：%s UTC%n" +
                "現在：fg=%d whale=%.1f%%%n" +
                "建議：%s%n%n" +
                "<i>mode=%s</i>",
                title, symbol,
                level,
                flips.trim(),
                prev.observedAt != null ? prev.observedAt.toLocalTime().toString().substring(0, 5) : "?",
                current.fg, current.whale * 100,
                suggestion,
                mode);
    }

    private record Snapshot(int fg, double whale, LocalDateTime observedAt) {}

    /** 內部偵測結果 */
    private record FlipResult(String label, String description, double delta) {}
}
