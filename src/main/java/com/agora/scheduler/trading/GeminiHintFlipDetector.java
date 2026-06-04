package com.agora.scheduler.trading;

import com.agora.config.properties.GeminiAdvisorProperties;
import com.agora.model.GeminiMarketHint;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.infra.notification.NotificationPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 偵測 GeminiMarketAdvisor 的 style hint 翻轉 (如 DISABLE → CONSERVATIVE),
 * 即時 TG 通知人類 — 這類翻轉通常代表 advisor 判斷市況轉為可進場,是實用信號。
 *
 * <p><b>Why this exists:</b> 2026-04-16 BTC 1h 從 DISABLE (conf=0.30) 翻成 CONSERVATIVE (conf=1.00),
 * 是手動觸發 triggerGeminiAdvisor 才發現的。自動偵測省去人工觀察。
 *
 * <p><b>頻率:</b> 每 10 分鐘掃一次 (advisor cron 預設每 8h 跑,10min 足以及時發現新 hint)。
 *
 * <p><b>翻轉定義:</b> 新 hint 的 styleHint 或 regime 與最近一次不同,即 flip。
 * 限 (symbol, timeframe) 範圍內比較。
 */
@Slf4j
@Component
public class GeminiHintFlipDetector {

    private final GeminiMarketHintRepository hintRepo;
    private final NotificationPort notificationPort;
    private final GeminiAdvisorProperties props;

    /** key = "{symbol}|{timeframe}", value = 上次看到的 hint id */
    private final Map<String, Long> lastSeenHintId = new HashMap<>();

    public GeminiHintFlipDetector(GeminiMarketHintRepository hintRepo,
                                   NotificationPort notificationPort,
                                   GeminiAdvisorProperties props) {
        this.hintRepo = hintRepo;
        this.notificationPort = notificationPort;
        this.props = props;
    }

    // 10 min（Gemini Advisor 預設每 8h 更新，10 min 對偵測延遲幾乎無感知差異）
    @Scheduled(fixedDelay = 600_000, initialDelay = 60_000)
    public void tick() {
        if (!props.flipDetectorEnabled()) return;
        try {
            detectFlips();
        } catch (Throwable t) {
            log.error("[HintFlipDetector] tick failed: {}", t.getMessage(), t);
        }
    }

    private void detectFlips() {
        String[] symbols = props.symbols().split(",");
        String[] timeframes = props.timeframes().split(",");

        for (String symbol : symbols) {
            for (String tf : timeframes) {
                String key = symbol.trim() + "|" + tf.trim();
                List<GeminiMarketHint> recent = hintRepo
                        .findActiveHints(symbol.trim(), tf.trim(), LocalDateTime.now(),
                                org.springframework.data.domain.PageRequest.of(0, 2));

                if (recent.isEmpty()) continue;
                GeminiMarketHint latest = recent.get(0);
                Long seenId = lastSeenHintId.get(key);

                // 第一次啟動:記住當前,不發通知(避免 startup storm)
                if (seenId == null) {
                    lastSeenHintId.put(key, latest.getId());
                    continue;
                }

                // 同一筆 hint — 略過
                if (latest.getId().equals(seenId)) continue;

                // 新 hint 出現,比對是否 flip
                GeminiMarketHint prev = recent.size() > 1 ? recent.get(1) : null;
                lastSeenHintId.put(key, latest.getId());

                if (prev == null) continue;  // 沒前值可比

                boolean styleFlipped = !equalsIgnoreCase(prev.getStyleHint(), latest.getStyleHint());
                boolean regimeFlipped = !equalsIgnoreCase(prev.getRegime(), latest.getRegime());

                if (styleFlipped || regimeFlipped) {
                    sendFlipAlert(symbol.trim(), tf.trim(), prev, latest, styleFlipped, regimeFlipped);
                }
            }
        }
    }

    private void sendFlipAlert(String symbol, String tf,
                                GeminiMarketHint prev, GeminiMarketHint latest,
                                boolean styleFlipped, boolean regimeFlipped) {
        String emoji = styleFlipped && "DISABLE".equalsIgnoreCase(prev.getStyleHint()) ? "🟢"
                     : styleFlipped && "DISABLE".equalsIgnoreCase(latest.getStyleHint()) ? "🔴"
                     : "🔄";
        StringBuilder msg = new StringBuilder();
        msg.append(String.format("%s <b>Gemini Hint 翻轉</b>%n", emoji));
        msg.append(String.format("<code>%s@%s</code>%n", symbol, tf));
        if (styleFlipped) {
            msg.append(String.format("Style: %s → <b>%s</b>%n", prev.getStyleHint(), latest.getStyleHint()));
        }
        if (regimeFlipped) {
            msg.append(String.format("Regime: %s → <b>%s</b>%n",
                    regimeCn(prev.getRegime()), regimeCn(latest.getRegime())));
        }
        msg.append(String.format("Conf: %.2f → %.2f", prev.getConfidence().doubleValue(),
                latest.getConfidence().doubleValue()));

        log.info("[HintFlipDetector] {} {}@{} styleFlip={} regimeFlip={} {}→{}",
                emoji, symbol, tf, styleFlipped, regimeFlipped,
                prev.getStyleHint(), latest.getStyleHint());
        try {
            notificationPort.broadcast(msg.toString(), true);
        } catch (Exception e) {
            log.warn("[HintFlipDetector] TG send failed: {}", e.getMessage());
        }
    }

    private static String regimeCn(String regime) {
        if (regime == null) return "未知";
        return switch (regime.toUpperCase()) {
            case "TRENDING_UP"   -> "上升趨勢↑";
            case "TRENDING_DOWN" -> "下降趨勢↓";
            case "SIDEWAYS"      -> "橫盤整理";
            case "VOLATILE"      -> "高波動⚡";
            case "RECOVERY"      -> "復甦📈";
            default              -> regime;
        };
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equalsIgnoreCase(b);
    }
}
