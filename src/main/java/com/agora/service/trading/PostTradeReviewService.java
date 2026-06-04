package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.infra.notification.NotificationPort;
import com.agora.service.ai.GroqApiClient;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.WhaleFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 交易出場後 AI 複盤服務。
 * 抽離自 OcoPositionPollerScheduler，供 OcoPoll 與 LiveSignalEvaluator 共用，
 * 確保所有出場路徑（OCO 觸發、SELL 訊號、orphan close）都能觸發 AI 分析。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostTradeReviewService {

    private final GroqApiClient groqApiClient;
    private final FearGreedService fearGreedService;
    private final WhaleFlowService whaleFlowService;
    private final NotificationPort notificationPort;

    /**
     * 非同步觸發 AI 複盤（獨立執行緒，不阻塞呼叫方）。
     * 只在有實際出場價且 Groq 啟用時執行；失敗時靜默忽略。
     */
    public void reviewAsync(BtLiveSignal pos, String exitReason, BigDecimal exitPrice, double pnlPct) {
        new Thread(() -> review(pos, exitReason, exitPrice, pnlPct),
                "post-trade-review-" + pos.getId()).start();
    }

    /**
     * 同步執行 AI 複盤（呼叫方若已在獨立執行緒中則直接呼叫此方法）。
     */
    public void review(BtLiveSignal pos, String exitReason, BigDecimal exitPrice, double pnlPct) {
        if (!groqApiClient.isEnabled()) return;

        try {
            BigDecimal refEntry = pos.getActualEntryPrice() != null
                    ? pos.getActualEntryPrice() : pos.getEntryPrice();

            long hoursHeld = pos.getCreatedAt() != null && pos.getExitTime() != null
                    ? ChronoUnit.HOURS.between(pos.getCreatedAt(), pos.getExitTime()) : 0;

            boolean isShort = "SHORT".equals(pos.getSide());
            // SHORT：TP < entry（獲利方向為下跌），取正值表示「從進場到 TP 的距離%」
            double tpPct = (pos.getSuggestedTp() != null && refEntry != null)
                    ? Math.abs(pos.getSuggestedTp().subtract(refEntry).divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue()) * 100 : 0;
            double slPct = (pos.getSuggestedSl() != null && refEntry != null)
                    ? Math.abs(pos.getSuggestedSl().subtract(refEntry).divide(refEntry, 6, RoundingMode.HALF_UP).doubleValue()) * 100 : 0;

            int fgValue = 50;
            double whaleRatio = 0.5;
            try { fgValue = fearGreedService.getFearGreedValue(); } catch (Exception ignored) {}
            try { whaleRatio = whaleFlowService.getBuyRatio(pos.getSymbol()); } catch (Exception ignored) {}

            String fgClass = fgValue <= 24 ? "極度恐慌" : fgValue <= 49 ? "恐慌" : fgValue <= 74 ? "貪婪" : "極度貪婪";
            String resultDesc = "TP".equals(exitReason) ? "止盈出場（獲利）"
                    : "SL".equals(exitReason) ? "止損出場（虧損）"
                    : exitReason + " 出場";
            String score = pos.getScore() != null ? pos.getScore().toPlainString() : "N/A";
            String nn = pos.getNnOutput() != null ? pos.getNnOutput().toPlainString() : "N/A";

            String sideLabel = isShort ? "做空（SHORT）" : "做多（LONG）";
            String prompt = String.format(
                "你是加密貨幣量化交易分析師，專門做交易覆盤。請根據以下記錄做 3 點分析。\n\n" +
                "## 交易記錄\n" +
                "- 交易對：%s（%s）\n" +
                "- 進場均價：$%s\n" +
                "- 出場均價：$%s（%s）\n" +
                "- 損益：%+.2f%%（%+.2f USDT）\n" +
                "- 持倉時間：%d 小時\n" +
                "- 止盈距離：%.1f%%，止損距離：%.1f%%\n\n" +
                "## 進場時指標\n" +
                "- 策略分數 Score：%s，NN Output：%s\n\n" +
                "## 出場時市場背景（參考）\n" +
                "- 恐懼貪婪指數：%d（%s）\n" +
                "- 大單買壓比例：%.0f%%\n\n" +
                "## 分析要求\n" +
                "用繁體中文，3 點覆盤，每點不超過 50 字：\n" +
                "1. 這筆交易的技術訊號品質如何？\n" +
                "2. %s的主因是什麼（技術面、情緒面、或風控設定）？\n" +
                "3. 下次類似情況最需要關注哪個指標或條件？\n\n" +
                "純文字，不用 markdown。",
                pos.getSymbol(), sideLabel,
                refEntry != null ? formatPrice(refEntry) : "N/A",
                formatPrice(exitPrice), resultDesc,
                pnlPct * 100,
                pos.getRealizedPnl() != null ? pos.getRealizedPnl().doubleValue() : 0,
                hoursHeld, tpPct, slPct,
                score, nn,
                fgValue, fgClass, whaleRatio * 100,
                switch (exitReason) {
                    case "TP" -> "止盈觸發";
                    case "SL" -> "止損觸發";
                    case "SELL_SIGNAL" -> "賣出訊號觸發";
                    default -> "出場";
                }
            );

            Map<String, String> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);

            String analysis = null;
            for (int attempt = 1; attempt <= 2; attempt++) {
                try {
                    analysis = groqApiClient.chat(Collections.singletonList(userMsg), 400, 0.4);
                    if (analysis != null && !analysis.trim().isEmpty()) break;
                    log.warn("[Review] Groq empty response (attempt {}/2) positionId={}", attempt, pos.getId());
                } catch (Exception retryEx) {
                    log.warn("[Review] Groq attempt {}/2 failed positionId={}: {}", attempt, pos.getId(), retryEx.getMessage());
                    if (attempt == 1) {
                        try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                    }
                }
            }

            if (analysis == null || analysis.trim().isEmpty()) {
                log.warn("[Review] Groq returned empty analysis after 2 attempts for positionId={}", pos.getId());
                return;
            }

            String emoji = switch (exitReason) {
                case "TP" -> "🎯 止盈";
                case "SL" -> "🛡 止損";
                case "SELL_SIGNAL" -> "📤 賣出訊號";
                default -> "⚙️ " + exitReason;
            };
            String tgMsg = String.format(
                "🧠 <b>AI 交易覆盤 — %s #%d</b>\n" +
                "結果：<b>%s %+.2f%%</b>\n" +
                "─────────────────────────\n" +
                "%s\n" +
                "─────────────────────────\n" +
                "<i>由 Groq AI 生成，僅供參考</i>",
                pos.getSymbol(), pos.getId(),
                emoji, pnlPct * 100,
                analysis.trim());

            notificationPort.broadcast(tgMsg, true);
            log.info("[Review] Post-trade review sent: positionId={} exitReason={}", pos.getId(), exitReason);

        } catch (Exception e) {
            log.warn("[Review] Post-trade review failed for positionId={}: {}", pos.getId(), e.getMessage());
        }
    }

    private String formatPrice(BigDecimal price) {
        if (price == null) return "N/A";
        if (price.compareTo(BigDecimal.valueOf(1000)) >= 0) {
            return String.format("%,.2f", price.doubleValue());
        }
        return price.setScale(4, RoundingMode.HALF_UP).toPlainString();
    }
}
