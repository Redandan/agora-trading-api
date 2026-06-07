package com.agora.scheduler.trading;

import com.agora.config.properties.GeminiAdvisorProperties;
import com.agora.model.GeminiMarketHint;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.TgNotificationDeduper;
import com.agora.service.TgNotificationDeduper.Severity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * #336 — 偵測 GeminiMarketAdvisor 連續 N 筆 hint 同 style + 同 regime + 高 confidence
 * 的 staleness。Stuck 不一定是錯（市場可能真的無方向），但人類值得被告知，且可作為
 * 「下游 RegimeFilter 過度保守」的根因線索。
 *
 * <p><b>Why this exists:</b> 2026-05-01 audit 發現連 7 天 50 筆 hint 全部
 * CONSERVATIVE / SIDEWAYS conf=0.87～1.00，等於 advisor 在浪費 token 重複輸出。
 *
 * <p><b>頻率:</b> 每 6 小時掃一次（advisor 預設 8h 更新，6h 可提前發現 stuck）。
 *
 * <p><b>Stuck 定義:</b> 最近 N（預設 24，約 4 天 1h advisor）筆 hint 同 styleHint
 * 且同 regime 且 confidence 全部 ≥ 0.9 → 視為 stuck。
 *
 * <p><b>反 spam:</b> 每 (symbol, timeframe) 24h 內只發一次 TG。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "trading.gemini-advisor.staleness-detector-enabled", havingValue = "true", matchIfMissing = false)
public class GeminiHintStalenessDetector {

    private final GeminiMarketHintRepository hintRepo;
    private final NotificationPort notificationPort;
    private final TgNotificationDeduper deduper;
    private final GeminiAdvisorProperties props;

    /** 取 hint 的時間視窗（hint 約 4h 一筆，14d 涵蓋 ~84 筆，留足樣本）*/
    private static final int LOOKBACK_DAYS = 14;

    /** #362 — dedup key prefix; full key = prefix + symbol + ":" + timeframe. */
    private static final String DEDUP_KEY_PREFIX = "GeminiHintStaleness:";

    public GeminiHintStalenessDetector(GeminiMarketHintRepository hintRepo,
                                        NotificationPort notificationPort,
                                        TgNotificationDeduper deduper,
                                        GeminiAdvisorProperties props) {
        this.hintRepo = hintRepo;
        this.notificationPort = notificationPort;
        this.deduper = deduper;
        this.props = props;
    }

    @Scheduled(fixedDelay = 6 * 3600_000L, initialDelay = 5 * 60_000L)
    public void tick() {
        if (!props.stalenessDetectorEnabled()) return;
        try {
            detectStaleness();
        } catch (Throwable t) {
            log.error("[HintStalenessDetector] tick failed: {}", t.getMessage(), t);
        }
    }

    private void detectStaleness() {
        String[] symbols = props.symbols().split(",");
        String[] timeframes = props.timeframes().split(",");
        int minHints = props.stalenessMinHints();
        double confMin = props.stalenessConfMin();
        LocalDateTime since = LocalDateTime.now().minusDays(LOOKBACK_DAYS);

        for (String symbol : symbols) {
            for (String tf : timeframes) {
                String s = symbol.trim();
                String t = tf.trim();
                List<GeminiMarketHint> hints = hintRepo
                        .findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(s, t, since);
                if (hints.size() < minHints) continue;

                // 取最近 minHints 筆檢查
                List<GeminiMarketHint> sample = hints.subList(0, minHints);
                String style0 = sample.get(0).getStyleHint();
                String regime0 = sample.get(0).getRegime();

                boolean stuck = sample.stream().allMatch(h ->
                        equalsIgnoreCase(style0, h.getStyleHint())
                        && equalsIgnoreCase(regime0, h.getRegime())
                        && h.getConfidence() != null
                        && h.getConfidence().doubleValue() >= confMin);
                if (!stuck) continue;

                // #362 — TgNotificationDeduper replaces lastAlertAt HashMap.
                // DB warm-up via tg_notification_log row with source=DEDUP_KEY
                // means cooldown survives deploy restarts.
                String dedupKey = DEDUP_KEY_PREFIX + s + ":" + t;
                Duration cooldown = Duration.ofHours(props.stalenessCooldownHours());
                if (!deduper.shouldSend(dedupKey, cooldown, Severity.WARN)) {
                    continue;
                }
                sendStalenessAlert(s, t, style0, regime0, sample, dedupKey);
            }
        }
    }

    private void sendStalenessAlert(String symbol, String tf,
                                     String style, String regime,
                                     List<GeminiMarketHint> sample,
                                     String dedupKey) {
        double avgConf = sample.stream()
                .mapToDouble(h -> h.getConfidence().doubleValue())
                .average().orElse(0);
        LocalDateTime oldest = sample.get(sample.size() - 1).getCreatedAt();
        LocalDateTime newest = sample.get(0).getCreatedAt();
        long spanHours = Duration.between(oldest, newest).toHours();

        String msg = String.format(
                "🟡 <b>Gemini Advisor Stuck</b>%n"
                + "<code>%s@%s</code>%n"
                + "連續 <b>%d</b> 筆 hint 都是 <b>%s / %s</b>%n"
                + "Avg conf: <code>%.2f</code>  時間跨度: <code>%dh</code>%n%n"
                + "→ 下游 filter（如 RegimeFilter）可能因此過度保守。"
                + "若市場已轉向，考慮手動 triggerGeminiAdvisor 強制重算。",
                symbol, tf, sample.size(), style, regimeCn(regime), avgConf, spanHours);

        log.info("[HintStalenessDetector] STUCK {}@{} style={} regime={} n={} avgConf={}",
                symbol, tf, style, regime, sample.size(), avgConf);
        try {
            // sendAlert (not sendMessage) writes log row with source=dedupKey
            // for deduper's DB warm-up.
            notificationPort.alert(msg, true, dedupKey, "WARN");
        } catch (Exception e) {
            log.warn("[HintStalenessDetector] TG send failed: {}", e.getMessage());
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
