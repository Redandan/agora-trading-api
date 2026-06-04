package com.agora.service;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies trading Telegram messages into operator-facing routing buckets.
 *
 * <p>This deliberately stays side-effect free so TG routing, MCP history
 * summaries, and tests can share one vocabulary instead of re-implementing
 * fragile substring checks.
 */
@Component
public class TgTradingNotificationClassifier {

    private static final Pattern GRID_ID_PATTERN =
            Pattern.compile("grid\\s*#?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public enum Bucket {
        ACTIONABLE_TRADE,
        MARKET_SIGNAL,
        SYSTEM_NOISE,
        OPS_AUDIT,
        GRID_INCIDENT,
        OTHER
    }

    public Bucket classify(String message, String source, String level) {
        String text = normalized(message, source, level);
        if (isEventScanNotification(text)) {
            return Bucket.OTHER;
        }
        if (isMarketSignalRiskSummary(text)) {
            return Bucket.MARKET_SIGNAL;
        }
        if (isReadOnlyTradingContext(text)) {
            return isMarketSignal(text) ? Bucket.MARKET_SIGNAL : Bucket.OTHER;
        }
        if (isGridDustDiagnostic(text)) {
            return Bucket.SYSTEM_NOISE;
        }
        if (isGridIncident(text)) {
            return Bucket.GRID_INCIDENT;
        }
        if (isActionableTrade(text)) {
            return Bucket.ACTIONABLE_TRADE;
        }
        if (isOpsAudit(text)) {
            return Bucket.OPS_AUDIT;
        }
        if (isSystemNoise(text)) {
            return Bucket.SYSTEM_NOISE;
        }
        if (isMarketSignal(text)) {
            return Bucket.MARKET_SIGNAL;
        }
        return Bucket.OTHER;
    }

    public boolean isSuppressible(Bucket bucket) {
        return bucket != Bucket.ACTIONABLE_TRADE && bucket != Bucket.OTHER;
    }

    public String routingKey(String message, String source, String level) {
        String text = normalized(message, source, level);
        if (isEventScanNotification(text)) {
            return sourceKey(source);
        }
        if (isMarketSignalRiskSummary(text)) {
            return "market-signal:risk-summary";
        }
        Bucket bucket = classify(message, source, level);
        if (bucket == Bucket.GRID_INCIDENT) {
            return "grid-incident:" + gridIncidentKey(text);
        }
        if (bucket == Bucket.OPS_AUDIT) {
            return opsAuditKey(text);
        }
        if (bucket == Bucket.MARKET_SIGNAL) {
            return marketSignalKey(text);
        }
        if (bucket == Bucket.SYSTEM_NOISE) {
            return systemNoiseKey(text, source);
        }
        if (bucket == Bucket.ACTIONABLE_TRADE) {
            return actionableTradeKey(text, source);
        }
        return sourceKey(source);
    }

    private boolean isGridIncident(String text) {
        return text.contains("grid")
                && (text.contains("selling_okx")
                || text.contains("sell_failed")
                || text.contains("sell failed")
                || text.contains("gave up")
                || text.contains("give up")
                || text.contains("賣出失敗")
                || text.contains("放棄")
                || text.contains("重試")
                || text.contains("retry"));
    }

    private boolean isEventScanNotification(String text) {
        return text.contains("eventscannotification")
                || text.contains("[event scan]")
                || text.contains("[事件掃描]");
    }

    private boolean isMarketSignalRiskSummary(String text) {
        return text.contains("marketsignalrisksummary")
                || text.contains("[市場風險摘要]");
    }

    private boolean isReadOnlyTradingContext(String text) {
        return text.contains("review_only")
                || text.contains("read_only")
                || text.contains("shadow_info")
                || text.contains("read-only")
                || text.contains("非交易指令")
                || text.contains("不是 buy/sell")
                || text.contains("不是買入/賣出指令")
                || text.contains("僅觀察，不影響實際下單")
                || text.contains("僅觀察，不影響實際策略")
                || text.contains("不是買賣指令");
    }

    private boolean isGridDustDiagnostic(String text) {
        return text.contains("grid")
                && (text.contains("dust_failure") || text.contains("dust failure")
                || text.contains("grid dust locked") || text.contains("小於 okx 最小下單額"))
                && (text.contains("lifecycle=stale") || text.contains("stale")
                || text.contains("diagnostic") || text.contains("diagnostics")
                || text.contains("已停止 retry") || text.contains("retry=3/3"));
    }

    private boolean isActionableTrade(String text) {
        return text.contains("買入訊號") || text.contains("買入信號")
                || text.contains("sell signal") || text.contains("buy signal")
                || text.contains("autotrade") || text.contains("auto trade")
                || text.contains("自動下單") || text.contains("下單")
                || text.contains("oco") || text.contains("daily loss")
                || text.contains("熔斷") || text.contains("止盈") || text.contains("止損")
                || text.contains("grid #") || text.contains("grid#");
    }

    private boolean isOpsAudit(String text) {
        return text.contains("kb audit")
                || text.contains("post-deploy audit")
                || text.contains("stale audit")
                || text.contains("infra-kb-snapshot-pipeline");
    }

    private boolean isSystemNoise(String text) {
        return text.contains("app startup slow")
                || text.contains("nightly cleanup")
                || text.contains("daily review ping")
                || text.contains("ml pipeline 每日 digest")
                || text.contains("系統提醒");
    }

    private boolean isMarketSignal(String text) {
        return text.contains("polymarket") || text.contains("market 指標翻轉")
                || text.contains("market flip") || text.contains("鯨魚")
                || text.contains("whale") || text.contains("gemini market advisor")
                || text.contains("put/call") || text.contains("持續警戒提醒")
                || text.contains("空頭燃料") || text.contains("市場熵值")
                || text.contains("etf 壓力") || text.contains("vdi")
                || text.contains("回歸正常") || text.contains("軋空");
    }

    private String gridIncidentKey(String text) {
        String gridId = "unknown";
        Matcher matcher = GRID_ID_PATTERN.matcher(text);
        if (matcher.find()) {
            gridId = matcher.group(1);
        }
        String status;
        if (text.contains("selling_okx")) {
            status = "selling-okx";
        } else if (text.contains("sell_failed") || text.contains("sell failed") || text.contains("賣出失敗")) {
            status = "sell-failed";
        } else if (text.contains("gave up") || text.contains("give up") || text.contains("放棄")) {
            status = "gave-up";
        } else {
            status = "retry";
        }
        return "grid-" + gridId + ":" + status;
    }

    private String opsAuditKey(String text) {
        if (text.contains("kb audit")) return "ops-audit:kb";
        if (text.contains("post-deploy audit")) return "ops-audit:post-deploy";
        if (text.contains("stale audit")) return "ops-audit:stale";
        return "ops-audit:project";
    }

    private String marketSignalKey(String text) {
        if (text.contains("polymarket")) return "market-signal:polymarket";
        if (text.contains("market 指標翻轉") || text.contains("market flip")) return "market-signal:market-flip";
        if (text.contains("鯨魚") || text.contains("whale")) return "market-signal:whale";
        if (text.contains("gemini market advisor")) return "market-signal:gemini-advisor";
        if (text.contains("put/call")) return "market-signal:put-call";
        return "market-signal:macro";
    }

    private String systemNoiseKey(String text, String source) {
        if (isGridDustDiagnostic(text)) return "system-noise:grid-dust-diagnostic";
        if (text.contains("app startup slow")) return "system-noise:startup-slow";
        if (text.contains("ml pipeline 每日 digest")) return "system-noise:ml-digest";
        if (text.contains("daily review ping") || text.contains("系統提醒")) return "system-noise:reminder";
        return "system-noise:" + sourceKey(source);
    }

    private String actionableTradeKey(String text, String source) {
        if ((text.contains("買入訊號") || text.contains("買入信號") || text.contains("buy signal"))
                && (text.contains("補送") || text.contains("重送") || text.contains("resend")
                || text.contains("duplicate"))) {
            return "trade-signal:buy-resend";
        }
        if (text.contains("買入訊號") || text.contains("買入信號") || text.contains("buy signal")) {
            return "trade-signal:buy";
        }
        if (text.contains("sell signal")) {
            return "trade-signal:sell";
        }
        if (text.contains("oco")) {
            return "trade-safety:oco";
        }
        return "trade-action:" + sourceKey(source);
    }

    private String normalized(String message, String source, String level) {
        return ((message != null ? message : "") + " "
                + (source != null ? source : "") + " "
                + (level != null ? level : "")).toLowerCase(Locale.ROOT);
    }

    private String sourceKey(String source) {
        String src = source != null && !source.isBlank() ? source : "system";
        return src.replaceAll("[^A-Za-z0-9_.#-]", "_");
    }
}
