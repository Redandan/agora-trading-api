package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.config.properties.GeminiAdvisorProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.AttentionRule;
import com.agora.model.GeminiMarketHint;
import com.agora.service.market.CoinalyzeService;
import com.agora.model.MarketIndicatorHistory;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.scheduler.trading.KlineDivergenceMonitor;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.ai.GeminiMarketAdvisor;
import com.agora.service.market.KlineQualityValidator;
import com.agora.service.market.KlineStreamService;
import com.agora.service.market.DefiLlamaService;
import com.agora.service.market.EtherscanService;
import com.agora.service.market.EventCalendarService;
import com.agora.service.market.FearGreedService;
import com.agora.service.market.FredEconomicService;
import com.agora.service.market.MempoolSpaceService;
import com.agora.service.market.OkxKlineImportService;
import com.agora.service.market.OrderbookImbalanceService;
import com.agora.service.market.PolymarketHistoricalImportService;
import com.agora.service.market.PolymarketService;
import com.agora.repository.trading.PolymarketHistoricalOddsRepository;
import com.agora.service.market.IndicatorHistoryBackfillService;
import com.agora.service.market.UniswapDexFlowService;
import com.agora.service.market.WhaleFlowService;
import com.agora.service.trading.DailyLossGuard;
import com.agora.service.trading.OkxTradingService;
import com.agora.dto.market.KlineImportResponse;
import com.agora.dto.market.KlineSubscriptionInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.agora.dto.ExchangeRateInfo;
import com.agora.service.ExchangeRateService;
import com.agora.mcp.util.McpParamValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 市場歷史數據 MCP 工具集。
 * 提供 Fear & Greed 歷史、OKX 資金費率歷史，以及過濾規則的歷史驗證。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketDataMcpTools {
    private static final LocalDateTime REGIME_FILTER_FIX_UTC = LocalDateTime.of(2026, 5, 6, 7, 21, 41);


    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final FearGreedService fearGreedService;
    private final OkxTradingService okxTradingService;
    private final PolymarketService polymarketService;
    private final WhaleFlowService whaleFlowService;
    private final BtLiveSignalRepository liveSignalRepository;
    private final MdKlineRepository klineRepository;
    private final BtStrategyRepository strategyRepository;
    private final List<KlineStreamService> klineStreamServices;
    private final DailyLossGuard dailyLossGuard;
    private final EventCalendarService eventCalendarService;
    private final OrderbookImbalanceService orderbookImbalanceService;
    private final KlineQualityValidator klineQualityValidator;
    private final OkxKlineImportService okxKlineImportService;
    private final KlineDivergenceMonitor klineDivergenceMonitor;
    private final GeminiMarketAdvisor geminiMarketAdvisor;
    private final GeminiAdvisorProperties geminiAdvisorProperties;
    private final GeminiMarketHintRepository geminiMarketHintRepository;
    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final PolymarketHistoricalImportService polymarketHistoricalImportService;
    private final PolymarketHistoricalOddsRepository polymarketHistoricalOddsRepository;
    private final MarketIndicatorHistoryRepository indicatorHistoryRepository;
    private final com.agora.config.WsSubscriptionSyncer wsSubscriptionSyncer;
    private final ExchangeRateService exchangeRateService;
    private final FredEconomicService fredEconomicService;
    private final EtherscanService etherscanService;
    private final MempoolSpaceService mempoolSpaceService;
    private final DefiLlamaService defiLlamaService;
    private final UniswapDexFlowService uniswapDexFlowService;
    private final IndicatorHistoryBackfillService indicatorHistoryBackfillService;
    private final com.agora.repository.trading.BtDecisionAuditRepository decisionAuditRepository;
    private final com.agora.repository.trading.AttentionRuleRepository attentionRuleRepository;
    private final CoinalyzeService coinalyzeService;

    @org.springframework.beans.factory.annotation.Value("${trading.market-data.coinalyze.api-key:}")
    private String coinalyzeApiKey;

    @org.springframework.beans.factory.annotation.Value("${market.signal.source:okx}")
    private String defaultKlineQualitySource;

    @org.springframework.beans.factory.annotation.Value("${trading.market-data-mcp.live-sentiment-enabled:false}")
    private boolean liveSentimentEnabled;

    @org.springframework.beans.factory.annotation.Value("${trading.market-data-mcp.external-health-probes-enabled:false}")
    private boolean externalHealthProbesEnabled;

    @org.springframework.beans.factory.annotation.Value("${trading.market-data-mcp.external-backfills-enabled:false}")
    private boolean externalBackfillsEnabled;

    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    // ─── Fear & Greed 歷史 ────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢 Crypto Fear & Greed Index 歷史數據（每日一筆，來源：alternative.me）。" +
            "可用來了解過去市場情緒走勢，或驗證特定日期的 F&G 值。" +
            "param: days=天數（預設30，最多365）。顯示日期、指數值、市場情緒分類。")
    public String getFearGreedHistory(Integer days) {
        if (!liveSentimentEnabled) {
            return disabledLiveSentimentMessage("getFearGreedHistory",
                    "read alternative.me Fear&Greed history directly");
        }
        if (days == null || days <= 0 || days > 365) days = 30;

        List<FearGreedService.FearGreedEntry> history = fearGreedService.getHistoricalFearGreed(days);
        if (history.isEmpty()) return "❌ 無法取得 Fear & Greed 歷史數據。";

        StringBuilder sb = new StringBuilder("=== Fear & Greed 歷史（過去 ")
                .append(days).append(" 天）===\n\n");

        // 統計分佈
        long extremeFear = history.stream().filter(e -> e.value() < 25).count();
        long fear        = history.stream().filter(e -> e.value() >= 25 && e.value() < 50).count();
        long greed       = history.stream().filter(e -> e.value() >= 50 && e.value() < 75).count();
        long extremeGreed= history.stream().filter(e -> e.value() >= 75).count();

        sb.append("分佈：ExtrFear(<25)=").append(extremeFear)
          .append("  Fear=").append(fear)
          .append("  Greed=").append(greed)
          .append("  ExtrGreed(>74)=").append(extremeGreed).append("\n\n");

        // 列出每日數據（最近 30 筆）
        int show = Math.min(history.size(), 30);
        for (int i = 0; i < show; i++) {
            FearGreedService.FearGreedEntry e = history.get(i);
            String date = Instant.ofEpochSecond(e.timestamp())
                    .atZone(ZoneId.of("Asia/Taipei"))
                    .format(DateTimeFormatter.ofPattern("MM-dd"));
            String emoji = e.value() < 25 ? "🔴" : e.value() < 50 ? "🟡" :
                           e.value() < 75 ? "🟢" : "🔥";
            sb.append(String.format("  %s  %s %3d  %s\n", date, emoji, e.value(), e.classification()));
        }
        if (history.size() > 30)
            sb.append("  ...（顯示最近 30 筆，共 ").append(history.size()).append(" 筆）\n");

        return sb.toString();
    }

    // ─── OKX 資金費率歷史 ─────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢 OKX SWAP 合約資金費率歷史（每 8 小時一筆）。" +
            "資金費率 > 0：多頭付費給空頭（利空頭），< 0：空頭付費給多頭（不利空頭）。" +
            "可用來判斷市場多空偏向，驗證資金費率過濾方案的有效性。" +
            "param: symbol=交易對（如 BTCUSDT），limit=筆數（預設72=約一個月，最多100）。")
    public String getFundingRateHistory(String symbol, Integer limit) {
        if (limit == null || limit <= 0 || limit > 100) limit = 72;

        JsonNode data;
        try {
            data = okxTradingService.getFundingRateHistory(symbol, limit);
        } catch (Exception e) {
            return "❌ 查詢資金費率失敗：" + e.getMessage();
        }

        if (!data.isArray() || data.size() == 0) return "無資金費率歷史記錄。";

        StringBuilder sb = new StringBuilder("=== OKX ").append(symbol)
                .append(" 資金費率歷史（近 ").append(data.size()).append(" 筆）===\n\n");

        double sumRate = 0, maxRate = Double.MIN_VALUE, minRate = Double.MAX_VALUE;
        int negCount = 0;

        for (JsonNode item : data) {
            long tsMs = item.path("fundingTime").asLong(0);
            double rate = item.path("fundingRate").asDouble(0) * 100;
            String time = tsMs > 0 ? Instant.ofEpochMilli(tsMs)
                    .atZone(ZoneId.of("Asia/Taipei"))
                    .format(DATETIME_FMT) : "N/A";
            String emoji = rate > 0.05 ? "🔥" : rate > 0 ? "🟢" : rate > -0.02 ? "🟡" : "🔴";
            sb.append(String.format("  %s  %s %+.4f%%\n", time, emoji, rate));
            sumRate += rate;
            if (rate > maxRate) maxRate = rate;
            if (rate < minRate) minRate = rate;
            if (rate < 0) negCount++;
        }

        double avg = sumRate / data.size();
        sb.append(String.format("\n平均: %+.4f%%  最高: %+.4f%%  最低: %+.4f%%\n", avg, maxRate, minRate));
        sb.append(String.format("負費率次數: %d / %d（空頭不利比例: %.0f%%）\n",
                negCount, data.size(), negCount * 100.0 / data.size()));
        if (avg > 0.01)
            sb.append("→ 市場整體偏多頭（正費率），空頭持倉有費率收入優勢\n");
        else if (avg < -0.01)
            sb.append("⚠️ 市場整體偏空頭（負費率），開空需支付費率，需謹慎\n");

        return sb.toString();
    }

    // analyzeShortFilterHistory 已於 V024 移除，功能由 analyzeFilterStats 取代
    // simulateShortOnFgDays 已於本次重構移除，功能由 simulateAttentionRulesOnHistory 取代
    // （用 mih_indicator=fear_greed + side=SHORT + takeProfitPct/stopLossPct 達成等效效果）

    // ─── #217: FILTER_BLOCK 事後追蹤 ──────────────────────────────────────────

    // flagDataError + autoDetectDataErrors → moved to IndicatorMcpTools (#248)

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#217 FILTER_BLOCK 事後追蹤：檢視被 RegimeFilter/MlGate 攔截的 BUY 信號，" +
            "事後市場到底漲了還是跌了（回測擋對了嗎？）。" +
            "對每筆 FILTER_BLOCK 查 24h 後的收盤價，統計「正確攔截率（市場下跌）」vs「誤殺率（市場上漲）」。" +
            "param: days=回溯天數（預設 30），symbol=交易對（預設 BTCUSDT），intervalCode=1h/4h（預設 1h）")
    public String analyzeBlockedSignalOutcomes(Integer days, String symbol, String intervalCode) {
        int d = days != null ? Math.min(Math.max(days, 1), 90) : 30;
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode : "1h";
        int lookAheadHours = "4h".equals(interval) ? 96 : 24;

        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        LocalDateTime until = LocalDateTime.now(ZoneOffset.UTC);
        return analyzeBlockedSignalOutcomesBetween(d + "d", since, until, sym, interval, lookAheadHours);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#217/#442 FILTER_BLOCK window 事後追蹤：read-only。用 sinceUtc/untilUtc 將 RegimeFilter 修復前後樣本切開，" +
            "避免把舊 blocker 樣本誤判成新問題。params: sinceUtc=ISO UTC, untilUtc=ISO UTC optional, symbol=BTCUSDT, intervalCode=1h/4h/1d")
    public String analyzeBlockedSignalOutcomesWindow(String sinceUtc, String untilUtc, String symbol, String intervalCode) {
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode : "1h";
        int lookAheadHours = "4h".equals(interval) ? 96 : 24;
        LocalDateTime since = parseUtcDateTimeOrDefault(sinceUtc, LocalDateTime.now(ZoneOffset.UTC).minusDays(30));
        LocalDateTime until = parseUtcDateTimeOrDefault(untilUtc, LocalDateTime.now(ZoneOffset.UTC));
        if (until.isBefore(since)) {
            return "❌ untilUtc must be after sinceUtc";
        }
        return analyzeBlockedSignalOutcomesBetween(since + "Z.." + until + "Z", since, until, sym, interval, lookAheadHours);
    }

    private String analyzeBlockedSignalOutcomesBetween(String windowLabel,
                                                       LocalDateTime since,
                                                       LocalDateTime until,
                                                       String sym,
                                                       String interval,
                                                       int lookAheadHours) {
        LocalDateTime maxBlockTime = LocalDateTime.now(ZoneOffset.UTC).minusHours(lookAheadHours);
        LocalDateTime effectiveUntil = until.isBefore(maxBlockTime) ? until : maxBlockTime;

        List<com.agora.model.BtDecisionAudit> blocks = decisionAuditRepository
                .findFilterBlockSince(since).stream()
                .filter(b -> sym.equals(b.getSymbol()))
                .filter(b -> !b.getEventTime().isBefore(since))
                .filter(b -> b.getEventTime().isBefore(effectiveUntil))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== FILTER_BLOCK 事後追蹤 (%s %s, window=%s) ===\n", sym, interval, windowLabel));
        sb.append("mode=READ_ONLY | no signal/order/OCO/strategy/grid/fund/Earn/Telegram behavior changed.\n");
        sb.append(String.format("windowUtc: %sZ → %sZ | effectiveUntil=%sZ\n",
                since, until, effectiveUntil));
        sb.append(String.format("看前 %dh 後的收盤價判斷攔截是否正確\n\n", lookAheadHours));
        if (crossesRegimeFilterFixWindow(since, until)) {
            sb.append("⚠️ Mixed-window guard: this rolling window crosses the 2026-05-06 RegimeFilter fix. ")
                    .append("RegimeFilter/TRENDING_DOWN false-kill rows may be historical/pre-fix samples. ")
                    .append("Use analyzeBlockedSignalOutcomesWindow(sinceUtc=2026-05-06T07:21:41Z, ...) ")
                    .append("before treating this as a current recurrence or changing live filters.\n\n");
        }

        if (blocks.isEmpty()) {
            sb.append("ℹ️ 無符合條件的 FILTER_BLOCK 記錄（可能需要等 look-ahead 時間窗口過去）\n");
            return sb.toString();
        }

        // ── 優先從 signal_outcome_verification 取已 finalized 的結果（更精確）──
        java.util.Set<Long> liveSignalIds = blocks.stream()
                .map(com.agora.model.BtDecisionAudit::getLiveSignalId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        java.util.Map<Long, String> verifiedOutcomes = java.util.Collections.emptyMap();
        if (!liveSignalIds.isEmpty()) {
            verifiedOutcomes = signalVerificationRepository
                    .findFinalizedByLiveSignalIds(liveSignalIds)
                    .stream()
                    .collect(java.util.stream.Collectors.toMap(
                            com.agora.model.SignalOutcomeVerification::getLiveSignalId,
                            com.agora.model.SignalOutcomeVerification::getOutcome));
        }
        int fromTable = 0;

        int correct = 0, wrong = 0, noData = 0;
        double totalReturn = 0.0;
        int returnCount = 0;
        Map<String, BlockOutcomeStats> byBlocker = new LinkedHashMap<>();
        for (com.agora.model.BtDecisionAudit b : blocks) {
            String blockerKey = blockerReportKey(b);
            BlockOutcomeStats blockerStats = byBlocker.computeIfAbsent(blockerKey, k -> new BlockOutcomeStats());
            try {
                ForwardMetrics forward = calculateForwardMetrics(b, sym, interval, lookAheadHours);
                if (forward.hasData()) {
                    blockerStats.addForward(forward);
                    if (forward.lookAheadReturn() != null) {
                        double ret = forward.lookAheadReturn();
                        totalReturn += ret;
                        returnCount++;
                    }
                }

                // 優先讀 signal_outcome_verification（TP/SL 觸發，比 kline 時間點更準確）
                if (b.getLiveSignalId() != null && verifiedOutcomes.containsKey(b.getLiveSignalId())) {
                    String outcome = verifiedOutcomes.get(b.getLiveSignalId());
                    if ("CORRECT".equals(outcome)) { correct++; fromTable++; blockerStats.correct++; }
                    else if ("WRONG".equals(outcome)) { wrong++; fromTable++; blockerStats.wrong++; }
                    else { noData++; blockerStats.noData++; }
                    continue;
                }

                // Fallback: kline scan（信號未在 signal_outcome_verification 表中）
                LocalDateTime blockTime = b.getEventTime();
                LocalDateTime lookAheadTime = blockTime.plusHours(lookAheadHours);
                List<com.agora.model.MdKline> atBlock = klineRepository
                        .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                                sym, interval,
                                blockTime.minusHours(2), blockTime.plusHours(1));
                List<com.agora.model.MdKline> atLookAhead = klineRepository
                        .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                                sym, interval,
                                lookAheadTime.minusHours(2), lookAheadTime.plusHours(1));
                if (atBlock.isEmpty() || atLookAhead.isEmpty()) {
                    noData++;
                    blockerStats.noData++;
                    continue;
                }
                double entryClose = atBlock.get(atBlock.size() - 1).getClosePrice().doubleValue();
                double futureClose = atLookAhead.get(0).getClosePrice().doubleValue();
                double ret = directionalReturn(entryClose, futureClose, sideFromAudit(b));
                if (!forward.hasData()) {
                    totalReturn += ret;
                    returnCount++;
                    blockerStats.totalReturn += ret;
                    blockerStats.returnCount++;
                }
                if (ret < 0) {
                    correct++;
                    blockerStats.correct++;
                } else {
                    wrong++;
                    blockerStats.wrong++;
                }
            } catch (Exception ignored) {
                noData++;
                blockerStats.noData++;
            }
        }
        if (fromTable > 0) {
            sb.append(String.format("（%d 筆來自 signal_outcome_verification TP/SL 觸發，%d 筆來自 kline scan）%n",
                    fromTable, (correct + wrong) - fromTable));
        }

        int analyzed = correct + wrong;
        long totalBlocks = blocks.size();
        long finalizedBlocks = fromTable;
        long fallbackBlocks = Math.max(0, analyzed - fromTable);
        long pendingOrNoData = totalBlocks - analyzed;
        sb.append(String.format("分析 %d 筆（跳過無K線資料 %d 筆）\n\n", analyzed, noData));
        sb.append(String.format("樣本狀態: totalBlocks=%d finalized(TP/SL)=%d klineFallback=%d pending/noData=%d\n",
                totalBlocks, finalizedBlocks, fallbackBlocks, pendingOrNoData));
        sb.append(analyzed >= 30
                ? "✅ finalized+fallback 樣本數 ≥30，可作內部調參參考；公開/付費績效仍需標註方法。\n\n"
                : "⚠️ 樣本數 <30：只能做內部觀察，不應用於公開/付費信號績效宣稱。\n\n");
        if (analyzed > 0) {
            double correctRate = (double) correct / analyzed * 100;
            sb.append(String.format("✅ 正確攔截（市場往不利方向走）: %d 筆 (%.1f%%)\n", correct, correctRate));
            sb.append(String.format("❌ 誤殺（市場往有利方向走）    : %d 筆 (%.1f%%)\n", wrong, 100 - correctRate));
            if (returnCount > 0) {
                double avgRet = totalReturn / returnCount * 100;
                sb.append(String.format("📊 若全部放行，平均 %+dh 報酬   : %+.2f%% (kline樣本 %d 筆)\n\n",
                        lookAheadHours, avgRet, returnCount));
            } else {
                sb.append(String.format("📊 若全部放行，平均 %+dh 報酬   : N/A（樣本來自 TP/SL 驗證表）\n\n",
                        lookAheadHours));
            }
            if (correctRate >= 60) {
                sb.append("→ 過濾器整體有效（≥60% 正確攔截）\n");
            } else if (correctRate >= 40) {
                sb.append("→ 過濾器效果一般（40-60% 正確攔截），建議深入分析各 blocker\n");
            } else {
                sb.append("⚠️ 過濾器可能過度保守（<40% 正確攔截），建議放寬條件\n");
            }
            sb.append("\n按 blocker 分組：\n");
            byBlocker.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().analyzed(), a.getValue().analyzed()))
                    .forEach(e -> {
                        BlockOutcomeStats s = e.getValue();
                        long n = s.analyzed();
                        if (n == 0) {
                            sb.append(String.format("  %s: 無可判定樣本（noData=%d）\n", e.getKey(), s.noData));
                            return;
                        }
                        double rate = s.correct * 100.0 / n;
                        String avg = s.returnCount > 0
                                ? String.format("%+.2f%%", s.totalReturn / s.returnCount * 100)
                                : "N/A";
                        String fixed = s.forwardCount > 0
                                ? String.format(" | 1h %s 4h %s 24h %s | MFE %s MAE %s",
                                s.return1hCount > 0 ? pct(s.return1h / s.return1hCount) : "N/A",
                                s.return4hCount > 0 ? pct(s.return4h / s.return4hCount) : "N/A",
                                s.return24hCount > 0 ? pct(s.return24h / s.return24hCount) : "N/A",
                                pct(s.mfe / s.forwardCount),
                                pct(s.mae / s.forwardCount))
                                : "";
                        sb.append(String.format("  %s: 看對%d 看錯%d noData%d → 正確率 %.1f%% | falseKill %.1f%% | avgRet %s%s%n",
                                e.getKey(), s.correct, s.wrong, s.noData, rate, 100.0 - rate, avg, fixed));
                    });
        }
        return sb.toString();
    }

    private LocalDateTime parseUtcDateTimeOrDefault(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String trimmed = value.trim();
        try {
            if (trimmed.endsWith("Z")) {
                return LocalDateTime.ofInstant(java.time.Instant.parse(trimmed), ZoneOffset.UTC);
            }
            return LocalDateTime.parse(trimmed);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean crossesRegimeFilterFixWindow(LocalDateTime since, LocalDateTime until) {
        return since.isBefore(REGIME_FILTER_FIX_UTC) && until.isAfter(REGIME_FILTER_FIX_UTC);
    }

    private static class BlockOutcomeStats {
        long correct;
        long wrong;
        long noData;
        double totalReturn;
        int returnCount;
        double return1h;
        double return4h;
        double return24h;
        int return1hCount;
        int return4hCount;
        int return24hCount;
        double mfe;
        double mae;
        int forwardCount;

        long analyzed() {
            return correct + wrong;
        }

        void addForward(ForwardMetrics metrics) {
            if (!metrics.hasData()) return;
            if (metrics.return1h() != null) {
                return1h += metrics.return1h();
                return1hCount++;
            }
            if (metrics.return4h() != null) {
                return4h += metrics.return4h();
                return4hCount++;
            }
            if (metrics.return24h() != null) {
                return24h += metrics.return24h();
                return24hCount++;
            }
            mfe += metrics.mfe();
            mae += metrics.mae();
            forwardCount++;
            if (metrics.lookAheadReturn() != null) {
                totalReturn += metrics.lookAheadReturn();
                returnCount++;
            }
        }
    }

    private ForwardMetrics calculateForwardMetrics(com.agora.model.BtDecisionAudit audit,
                                                   String symbol,
                                                   String interval,
                                                   int lookAheadHours) {
        LocalDateTime blockTime = audit.getEventTime();
        int maxHorizon = Math.max(24, lookAheadHours);
        List<MdKline> window = klineRepository.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                symbol, interval, blockTime.minusHours(2), blockTime.plusHours(maxHorizon + 1L));
        if (window == null || window.isEmpty()) {
            return ForwardMetrics.empty();
        }

        MdKline entry = window.stream()
                .filter(k -> !k.getOpenTime().isAfter(blockTime.plusHours(1)))
                .reduce((first, second) -> second)
                .orElse(window.get(0));
        double entryClose = entry.getClosePrice().doubleValue();
        String side = sideFromAudit(audit);
        Double r1 = closeReturnAtOrAfter(window, entryClose, side, blockTime.plusHours(1));
        Double r4 = closeReturnAtOrAfter(window, entryClose, side, blockTime.plusHours(4));
        Double r24 = closeReturnAtOrAfter(window, entryClose, side, blockTime.plusHours(24));
        Double rLookAhead = closeReturnAtOrAfter(window, entryClose, side, blockTime.plusHours(lookAheadHours));

        double best = 0.0;
        double worst = 0.0;
        for (MdKline k : window) {
            if (k.getOpenTime().isBefore(blockTime) || k.getOpenTime().isAfter(blockTime.plusHours(24))) {
                continue;
            }
            double favorableHigh = "SHORT".equals(side)
                    ? directionalReturn(entryClose, k.getLowPrice().doubleValue(), side)
                    : directionalReturn(entryClose, k.getHighPrice().doubleValue(), side);
            double adverseLow = "SHORT".equals(side)
                    ? directionalReturn(entryClose, k.getHighPrice().doubleValue(), side)
                    : directionalReturn(entryClose, k.getLowPrice().doubleValue(), side);
            best = Math.max(best, favorableHigh);
            worst = Math.min(worst, adverseLow);
        }
        return new ForwardMetrics(r1, r4, r24, rLookAhead, best, worst, true);
    }

    private Double closeReturnAtOrAfter(List<MdKline> window, double entryClose, String side, LocalDateTime target) {
        return window.stream()
                .filter(k -> !k.getOpenTime().isBefore(target.minusMinutes(1)))
                .findFirst()
                .map(k -> directionalReturn(entryClose, k.getClosePrice().doubleValue(), side))
                .orElse(null);
    }

    private double directionalReturn(double entryClose, double futurePrice, String side) {
        if (entryClose == 0.0) return 0.0;
        double raw = (futurePrice - entryClose) / entryClose;
        return "SHORT".equals(side) ? -raw : raw;
    }

    private String sideFromAudit(com.agora.model.BtDecisionAudit audit) {
        String context = audit.getContextJson();
        if (context != null && context.toUpperCase().contains("\"SIDE\":\"SHORT\"")) {
            return "SHORT";
        }
        String reason = audit.getReason();
        if (reason != null && reason.toLowerCase().contains("short")) {
            return "SHORT";
        }
        return "LONG";
    }

    private String blockerReportKey(com.agora.model.BtDecisionAudit audit) {
        String blocker = audit.getBlocker() != null && !audit.getBlocker().isBlank()
                ? audit.getBlocker()
                : "unknown";
        if (blocker.toLowerCase().contains("regime")) {
            return blocker + "/" + regimeReasonKey(audit.getReason());
        }
        return blocker;
    }

    private String regimeReasonKey(String reason) {
        if (reason == null || reason.isBlank()) return "unspecified";
        String lower = reason.toLowerCase();
        if (lower.contains("trending_down")) return "TRENDING_DOWN";
        if (lower.contains("trending_up")) return "TRENDING_UP";
        if (lower.contains("sideways")) return "SIDEWAYS";
        if (lower.contains("rsi")) return "RSI";
        return reason.length() > 80 ? reason.substring(0, 80) : reason;
    }

    private String pct(double v) {
        return String.format("%+.2f%%", v * 100);
    }

    private record ForwardMetrics(Double return1h,
                                  Double return4h,
                                  Double return24h,
                                  Double lookAheadReturn,
                                  double mfe,
                                  double mae,
                                  boolean hasData) {
        static ForwardMetrics empty() {
            return new ForwardMetrics(null, null, null, null, 0.0, 0.0, false);
        }
    }

    // ─── Filter 實際攔截統計（使用新 filter_reason 欄位）────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "統計 LongAiFilter / ShortAiFilter 近期實際攔截記錄（讀自 bt_live_signal.filter_reason）。" +
            "展示：各方向攔截筆數、各規則觸發次數、對比同期已執行自動交易數與其勝率。" +
            "資料從 V024 migration 上線後才開始累積；早期調用會顯示樣本不足。" +
            "param: days=回溯天數（預設 30，最多 365）。")
    public String analyzeFilterStats(Integer days) {
        if (days == null || days <= 0 || days > 365) days = 30;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(days);

        // ── 資料來源 1：bt_live_signal.filterReason（有 BtLiveSignal 記錄的 block）
        List<BtLiveSignal> recent = liveSignalRepository.findByCreatedAtAfter(since);
        List<BtLiveSignal> filtered = recent.stream()
                .filter(s -> s.getFilterReason() != null)
                .filter(s -> isDecisionFilterReason(s.getFilterReason()))
                .toList();
        List<BtLiveSignal> traded = recent.stream()
                .filter(s -> Boolean.TRUE.equals(s.getAutoTraded())).toList();
        List<BtLiveSignal> closed = traded.stream()
                .filter(s -> s.getExitTime() != null && s.getRealizedPnl() != null).toList();

        // ── 資料來源 2：bt_decision_audit FILTER_BLOCK（#271 — 補捉沒有 BtLiveSignal 的 block）
        List<com.agora.model.BtDecisionAudit> auditBlocks =
                decisionAuditRepository.findFilterBlockSince(since);
        // 去重：live_signal_id 有值且已在 filtered 中的不重複計算
        java.util.Set<Long> filteredLiveIds = filtered.stream()
                .map(BtLiveSignal::getId).filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<com.agora.model.BtDecisionAudit> auditOnly = auditBlocks.stream()
                .filter(a -> a.getLiveSignalId() == null || !filteredLiveIds.contains(a.getLiveSignalId()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Filter 攔截統計（過去 ").append(days).append(" 天）===\n\n");

        // 方向分組（live_signal + audit-only）
        long longFiltered  = filtered.stream().filter(s -> !"SHORT".equals(s.getSide())).count();
        long shortFiltered = filtered.stream().filter(s ->  "SHORT".equals(s.getSide())).count();
        long auditShort = auditOnly.stream().filter(a ->
                (a.getContextJson() != null && a.getContextJson().contains("\"side\":\"SHORT\""))
                || (a.getReason() != null && a.getReason().toLowerCase().contains("short"))).count();
        long auditLong  = auditOnly.size() - auditShort;
        long longTraded  = traded.stream().filter(s -> !"SHORT".equals(s.getSide())).count();
        long shortTraded = traded.stream().filter(s ->  "SHORT".equals(s.getSide())).count();

        sb.append(String.format("📊 訊號總覽\n  LONG: 攔截 %d / 執行 %d\n  SHORT: 攔截 %d / 執行 %d\n",
                longFiltered + auditLong, longTraded, shortFiltered + auditShort, shortTraded));
        if (!auditOnly.isEmpty()) {
            sb.append(String.format("  ↑ 含 bt_decision_audit 補捉 %d 筆（無 BtLiveSignal 記錄的 block）\n", auditOnly.size()));
        }
        sb.append("\n");

        // 規則分佈（合併兩個來源）
        Map<String, Long> ruleCount = new HashMap<>();
        for (BtLiveSignal s : filtered) {
            ruleCount.merge(extractRuleKey(s.getFilterReason(), s.getSide()), 1L, Long::sum);
        }
        for (com.agora.model.BtDecisionAudit a : auditOnly) {
            boolean isShort = (a.getContextJson() != null && a.getContextJson().contains("\"side\":\"SHORT\""))
                    || (a.getReason() != null && a.getReason().toLowerCase().contains("short"));
            ruleCount.merge(extractAuditRuleKey(a, isShort ? "SHORT" : "LONG"), 1L, Long::sum);
        }

        if (ruleCount.isEmpty()) {
            sb.append("⚠️ 尚無攔截記錄。\n");
        } else {
            sb.append("🔍 攔截規則分佈\n");
            ruleCount.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> sb.append(String.format("  %s → %d 次\n", e.getKey(), e.getValue())));
            sb.append("\n");
        }

        // 已執行交易勝率
        if (!closed.isEmpty()) {
            long wins = closed.stream().filter(s -> s.getRealizedPnl().signum() > 0).count();
            BigDecimal totalPnl = closed.stream()
                    .map(BtLiveSignal::getRealizedPnl).reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append(String.format("💰 同期已平倉交易\n  筆數 %d  勝率 %d%%  PnL %+.2f USDT\n",
                    closed.size(), (int)(wins * 100 / closed.size()), totalPnl.doubleValue()));
        }

        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#272 信號正確率報告 — 各決策層（EnsembleGate/ShortAiFilter/PASS）近 N 天滾動正確率。" +
            "看對=現價觸 TP，看錯=現價觸 SL，再觀察=尚未觸發。正確率 < 40% 且 N≥5 視為需調整。" +
            "param: days=回溯天數（預設 7，最多 30）")
    public String getSignalAccuracyReport(Integer days) {
        int d = (days != null && days > 0 && days <= 30) ? days : 7;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);

        List<com.agora.model.SignalOutcomeVerification> records;
        try {
            records = signalVerificationRepository.findSince(since);
        } catch (Exception e) {
            return "⚠️ 查詢失敗：" + e.getMessage();
        }

        return buildSignalAccuracyReport(d, records);
    }

    static String buildSignalAccuracyReport(int days,
                                            List<com.agora.model.SignalOutcomeVerification> records) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== 信號正確率報告（近 %d 天）===\n\n", days));
        sb.append("看對=TP觸發 看錯=SL觸發 再觀察=未觸發；finalized=看對+看錯\n");
        sb.append("樣本數 guard：finalized < 30 時只能做內部觀察，不應作公開/付費信號績效宣稱。\n\n");

        if (records.isEmpty()) {
            return sb.append("尚無資料（signal_outcome_verification 表為空）。\n").toString();
        }

        Map<String, List<com.agora.model.SignalOutcomeVerification>> rawByGroup = records.stream()
                .collect(Collectors.groupingBy(MarketDataMcpTools::accuracyGroupKey,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<com.agora.model.SignalOutcomeVerification>> uniqueByGroup = records.stream()
                .collect(Collectors.toMap(MarketDataMcpTools::verificationDedupeKey,
                        Function.identity(),
                        (newer, older) -> newer,
                        LinkedHashMap::new))
                .values().stream()
                .collect(Collectors.groupingBy(MarketDataMcpTools::accuracyGroupKey,
                        LinkedHashMap::new, Collectors.toList()));

        int rawCount = records.size();
        int uniqueCount = uniqueByGroup.values().stream().mapToInt(List::size).sum();
        long duplicateLikeRows = rawCount - uniqueCount;
        if (duplicateLikeRows > 0) {
            sb.append(String.format("⚠️ data-quality: raw=%d uniqueBySignalShape=%d duplicateLikeRows=%d；以下正確率使用去重後樣本。\n\n",
                    rawCount, uniqueCount, duplicateLikeRows));
        }

        uniqueByGroup.entrySet().stream()
                .sorted((a, b) -> Long.compare(finalizedCount(b.getValue()), finalizedCount(a.getValue())))
                .forEach(entry -> {
            String[] parts = entry.getKey().split("\\|", 2);
            String layer = parts[0];
            String decision = parts.length > 1 ? parts[1] : "";
            List<com.agora.model.SignalOutcomeVerification> uniqueRows = entry.getValue();
            List<com.agora.model.SignalOutcomeVerification> rawRows = rawByGroup.getOrDefault(entry.getKey(), List.of());
            long correct = countOutcome(uniqueRows, "CORRECT");
            long wrong = countOutcome(uniqueRows, "WRONG");
            long watching = countOutcome(uniqueRows, "WATCHING");
            long expired = countOutcome(uniqueRows, "EXPIRED");
            long total      = correct + wrong;
            long groupDupes = rawRows.size() - uniqueRows.size();
            String dupeNote = groupDupes > 0 ? String.format(" | raw=%d unique=%d dupLike=%d",
                    rawRows.size(), uniqueRows.size(), groupDupes) : "";
            String line = renderDecisionAccuracyLine(layer, decision, correct, wrong, watching, expired,
                    30, 0.40);
            sb.append(line).append(dupeNote.isBlank() ? "" : dupeNote).append("\n");
        });

        appendDuplicateBuckets(sb, records);
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#272 列出個別信號驗證記錄，可依 outcome 過濾。用於 debug 和確認系統運作。" +
            "param: days=回溯天數（預設 7）, outcome=WATCHING/CORRECT/WRONG/EXPIRED（不填=全部）, limit=筆數（預設 20）")
    public String listSignalVerifications(Integer days, String outcome, Integer limit) {
        int d = (days != null && days > 0) ? days : 7;
        int lim = (limit != null && limit > 0 && limit <= 100) ? limit : 20;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        String outcomeFilter = (outcome != null && !outcome.isBlank()) ? outcome.toUpperCase() : null;

        var pageable = org.springframework.data.domain.PageRequest.of(0, lim);
        var records = signalVerificationRepository.findRecent(since, outcomeFilter, pageable);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== 信號驗證記錄（近 %d 天%s，共 %d 筆）===\n\n",
                d, outcomeFilter != null ? " outcome=" + outcomeFilter : "", records.size()));

        if (records.isEmpty()) {
            return sb.append("無記錄。\n").toString();
        }

        appendDuplicateBuckets(sb, records);

        for (var v : records) {
            String icon = switch (v.getOutcome()) {
                case "CORRECT"  -> "✅";
                case "WRONG"    -> "❌";
                case "EXPIRED"  -> "⏰";
                default         -> "🔄";
            };
            String price = v.getLastPrice() != null
                    ? String.format("現價 $%s", v.getLastPrice().toPlainString()) : "-";
            String resolved = v.getFinalizedAt() != null
                    ? " 確定@" + v.getFinalizedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    : "";
            sb.append(String.format("%s [%s] %s@%s %s  進場$%s SL$%s TP$%s  %s%s\n",
                    icon, v.getOutcome(),
                    v.getSymbol(), v.getIntervalCode(),
                    v.getDecision() + "/" + v.getDecisionLayer(),
                    v.getEntryPrice().toPlainString(),
                    v.getSlPrice() != null ? v.getSlPrice().toPlainString() : "N/A",
                    v.getTpPrice() != null ? v.getTpPrice().toPlainString() : "N/A",
                    price, resolved));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.READ_TRADING})
    @Tool(description = "#272 信號正確率 drill-down。Read-only；按 decisionLayer/decision 拆出樣本，並對 BLOCK 顯示攔截有效率。" +
            "param: days=回溯天數(預設7,最多30), layer=例如 EnsembleGate, decision=PASS/BLOCK, limit=明細筆數(預設20,最多100)")
    public String getSignalAccuracyDrillDown(Integer days, String layer, String decision, Integer limit) {
        int d = (days != null && days > 0 && days <= 30) ? days : 7;
        int lim = (limit != null && limit > 0 && limit <= 100) ? limit : 20;
        String layerFilter = layer == null || layer.isBlank() ? null : layer.trim();
        String decisionFilter = decision == null || decision.isBlank() ? null : decision.trim().toUpperCase();
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);

        List<com.agora.model.SignalOutcomeVerification> records;
        try {
            records = signalVerificationRepository.findSince(since);
        } catch (Exception e) {
            return "⚠️ 查詢失敗：" + e.getMessage();
        }

        List<com.agora.model.SignalOutcomeVerification> filtered = records.stream()
                .filter(v -> layerFilter == null || safe(v.getDecisionLayer()).equalsIgnoreCase(layerFilter))
                .filter(v -> decisionFilter == null || safe(v.getDecision()).equalsIgnoreCase(decisionFilter))
                .toList();
        List<com.agora.model.SignalOutcomeVerification> unique = filtered.stream()
                .collect(Collectors.toMap(MarketDataMcpTools::verificationDedupeKey,
                        Function.identity(),
                        (existing, ignored) -> existing,
                        LinkedHashMap::new))
                .values().stream()
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Signal Accuracy Drill-down ===\n")
                .append("mode=READ_ONLY | days=").append(d)
                .append(" | layer=").append(layerFilter != null ? layerFilter : "ALL")
                .append(" | decision=").append(decisionFilter != null ? decisionFilter : "ALL")
                .append(" | rawRows=").append(filtered.size())
                .append(" | uniqueBySignalShape=").append(unique.size())
                .append("\n");
        sb.append("semantic: PASS accuracy = TP-hit rate; BLOCK quality = avoided-loser rate (WRONG/total).\n\n");

        if (unique.isEmpty()) {
            return sb.append("No matching verification rows. No TG was sent and no trading behavior changed.\n").toString();
        }

        Map<String, List<com.agora.model.SignalOutcomeVerification>> uniqueByGroup = unique.stream()
                .collect(Collectors.groupingBy(MarketDataMcpTools::accuracyGroupKey,
                        LinkedHashMap::new, Collectors.toList()));
        sb.append("Summary:\n");
        uniqueByGroup.entrySet().stream()
                .sorted((a, b) -> Long.compare(finalizedCount(b.getValue()), finalizedCount(a.getValue())))
                .forEach(entry -> {
                    String[] parts = entry.getKey().split("\\|", 2);
                    String groupLayer = parts[0];
                    String groupDecision = parts.length > 1 ? parts[1] : "";
                    long correct = countOutcome(entry.getValue(), "CORRECT");
                    long wrong = countOutcome(entry.getValue(), "WRONG");
                    long watching = countOutcome(entry.getValue(), "WATCHING");
                    long expired = countOutcome(entry.getValue(), "EXPIRED");
                    sb.append("  ")
                            .append(renderDecisionAccuracyLine(groupLayer, groupDecision, correct, wrong, watching, expired,
                                    signalVerificationProperties.minSampleSize(),
                                    signalVerificationProperties.accuracyAlertThreshold()))
                            .append("\n");
                });

        sb.append("\nRows:\n");
        unique.stream()
                .sorted(java.util.Comparator.comparing(
                        com.agora.model.SignalOutcomeVerification::getCreatedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(lim)
                .forEach(v -> sb.append(renderSignalAccuracyRow(v)).append("\n"));
        if (unique.size() > lim) {
            sb.append("... ").append(unique.size() - lim).append(" more rows omitted\n");
        }
        sb.append("\nOperator action: use this for diagnosis only; do not change EnsembleGate/filter settings from N<30 samples alone.\n");
        sb.append("No TG was sent. No trading, OCO, strategy, grid order, or fund behavior was changed.");
        return sb.toString();
    }

    // ── dependencies for getSignalAccuracyReport ──
    private final com.agora.repository.trading.SignalOutcomeVerificationRepository signalVerificationRepository;
    private final com.agora.config.properties.SignalVerificationProperties signalVerificationProperties;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Preview the daily TG signal-accuracy alert using the same de-duplicated source as the scheduler. " +
            "Read-only; does not send Telegram. params: days(default 7), includeInternal(default true)")
    public String previewDailySignalAccuracyAlert(Integer days, Boolean includeInternal) {
        int d = days != null && days > 0 && days <= 30 ? days : 7;
        boolean showInternal = includeInternal == null || includeInternal;
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(d);
        List<Object[]> rows;
        try {
            rows = signalVerificationRepository.accuracyByLayerSinceDedup(since);
        } catch (Exception e) {
            return "❌ daily signal accuracy preview failed: " + e.getMessage();
        }
        return buildDailySignalAccuracyAlertPreview(
                d,
                rows,
                signalVerificationProperties.minSampleSize(),
                signalVerificationProperties.accuracyAlertThreshold(),
                showInternal);
    }

    static String buildDailySignalAccuracyAlertPreview(int days, List<Object[]> rows,
                                                       int minSampleSize,
                                                       double alertThreshold,
                                                       boolean includeInternal) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Daily Signal Accuracy TG Preview ===\n")
                .append("windowDays: ").append(days).append("\n")
                .append("source: signal_outcome_verification dedup by signal shape\n")
                .append("mode: READ_ONLY / not sent\n")
                .append("minSampleSize: ").append(minSampleSize)
                .append(" | alertThreshold: ").append(String.format("%.0f%%", alertThreshold * 100))
                .append("\n\n");
        if (rows == null || rows.isEmpty()) {
            return sb.append("NO_SEND: no accuracy rows.\n").toString();
        }

        StringBuilder body = new StringBuilder();
        boolean anyAlert = false;
        int emitted = 0;
        int suppressedInternal = 0;
        for (Object[] r : rows) {
            String layer = (String) r[0];
            String decision = (String) r[1];
            long correct = ((Number) r[2]).longValue();
            long wrong = ((Number) r[3]).longValue();
            long watching = ((Number) r[4]).longValue();
            long total = correct + wrong;
            if (total == 0) {
                continue;
            }
            boolean internalOnly = total < minSampleSize;
            if (internalOnly && !includeInternal) {
                suppressedInternal++;
                continue;
            }
            DecisionAccuracySummary summary = decisionAccuracySummary(layer, decision, correct, wrong, watching);
            boolean alert = !internalOnly && summary.decisionQuality < alertThreshold;
            String icon = alert ? "⚠️" : "✅";
            String guard = internalOnly ? "INTERNAL_ONLY_N<" + minSampleSize : "SENDABLE";
            body.append(String.format("%s %s [%s] %s %.0f%% (%d/%d) 觀察中%d | %s%s\n",
                    icon, layer, decision,
                    summary.label, summary.decisionQuality * 100, summary.qualityNumerator, total,
                    watching, guard, summary.note));
            anyAlert = anyAlert || alert;
            emitted++;
        }

        if (emitted == 0) {
            return sb.append("NO_SEND: no rows meet send/preview criteria")
                    .append(suppressedInternal > 0 ? " (internal-only rows suppressed)." : ".")
                    .append("\n").toString();
        }
        sb.append(anyAlert ? "wouldSendHeader: ⚠️ 信號正確率警告\n" : "wouldSendHeader: 📊 信號正確率報告\n");
        if (suppressedInternal > 0) {
            sb.append("suppressedInternalRows: ").append(suppressedInternal).append("\n");
        }
        sb.append("\n").append(body);
        return sb.toString();
    }

    public static String renderDecisionAccuracyLine(String layer, String decision,
                                                    long correct, long wrong, long watching, long expired,
                                                    int minSampleSize, double alertThreshold) {
        long total = correct + wrong;
        if (total == 0) {
            return String.format("⏳ %s [%s] finalized=0 觀察中%d 過期%d | INTERNAL_ONLY_N<%d",
                    layer, decision, watching, expired, minSampleSize);
        }
        DecisionAccuracySummary summary = decisionAccuracySummary(layer, decision, correct, wrong, watching);
        boolean internalOnly = total < minSampleSize;
        boolean alert = !internalOnly && summary.decisionQuality < alertThreshold;
        String icon = alert ? "⚠️" : "✅";
        String guard = internalOnly ? "INTERNAL_ONLY_N<" + minSampleSize : "SENDABLE";
        return String.format("%s %s [%s] finalized=%d signalTP=%d signalSL=%d watching=%d expired=%d → %s %.0f%% (%d/%d) | %s%s",
                icon, layer, decision, total, correct, wrong, watching, expired,
                summary.label, summary.decisionQuality * 100, summary.qualityNumerator, total,
                guard, summary.note);
    }

    private static DecisionAccuracySummary decisionAccuracySummary(String layer, String decision,
                                                                   long correct, long wrong, long watching) {
        long total = correct + wrong;
        boolean blockDecision = "BLOCK".equalsIgnoreCase(safe(decision));
        if (blockDecision) {
            double quality = total == 0 ? 0 : (double) wrong / total;
            return new DecisionAccuracySummary("攔截有效率", wrong, quality,
                    String.format(" | 原信號TP=%d/%d; BLOCK 後打到 SL 才代表攔截有效", correct, total));
        }
        double quality = total == 0 ? 0 : (double) correct / total;
        return new DecisionAccuracySummary("正確率", correct, quality, "");
    }

    private static String renderSignalAccuracyRow(com.agora.model.SignalOutcomeVerification v) {
        String judgement = decisionOutcomeJudgement(v);
        String created = v.getCreatedAt() != null
                ? v.getCreatedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                : "-";
        String finalized = v.getFinalizedAt() != null
                ? v.getFinalizedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                : "-";
        String move = renderMovePct(v);
        return String.format("- id=%s liveSignalId=%s %s %s@%s %s/%s outcome=%s judgement=%s entry=%s last=%s TP=%s SL=%s move=%s created=%s finalized=%s",
                v.getId() != null ? v.getId() : "-",
                v.getLiveSignalId() != null ? v.getLiveSignalId() : "-",
                safe(v.getSymbol()),
                safe(v.getSide()),
                safe(v.getIntervalCode()),
                safe(v.getDecisionLayer()),
                safe(v.getDecision()),
                safe(v.getOutcome()),
                judgement,
                decimalKey(v.getEntryPrice()),
                decimalKey(v.getLastPrice()),
                decimalKey(v.getTpPrice()),
                decimalKey(v.getSlPrice()),
                move,
                created,
                finalized);
    }

    private static String decisionOutcomeJudgement(com.agora.model.SignalOutcomeVerification v) {
        boolean blockDecision = "BLOCK".equalsIgnoreCase(safe(v.getDecision()));
        String outcome = safe(v.getOutcome());
        if (blockDecision) {
            return switch (outcome) {
                case "WRONG" -> "GOOD_BLOCK_avoided_SL";
                case "CORRECT" -> "BAD_BLOCK_missed_TP";
                case "WATCHING" -> "WATCHING";
                case "EXPIRED" -> "EXPIRED";
                default -> "UNKNOWN";
            };
        }
        return switch (outcome) {
            case "CORRECT" -> "GOOD_PASS_hit_TP";
            case "WRONG" -> "BAD_PASS_hit_SL";
            case "WATCHING" -> "WATCHING";
            case "EXPIRED" -> "EXPIRED";
            default -> "UNKNOWN";
        };
    }

    private static String renderMovePct(com.agora.model.SignalOutcomeVerification v) {
        if (v.getEntryPrice() == null || v.getLastPrice() == null || v.getEntryPrice().compareTo(BigDecimal.ZERO) == 0) {
            return "N/A";
        }
        BigDecimal pct = v.getLastPrice()
                .subtract(v.getEntryPrice())
                .divide(v.getEntryPrice(), 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        return pct.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private record DecisionAccuracySummary(String label, long qualityNumerator,
                                           double decisionQuality, String note) {}

    /** 從 filter_reason 萃取規則關鍵字供分組統計，例如 "ShortAiFilter: Fear&Greed=21..." → "SHORT/F&G"。 */
    private String extractRuleKey(String reason, String side) {
        if (reason == null) return "UNKNOWN";
        String sidePrefix = "SHORT".equals(side) ? "SHORT" : "LONG";
        String lower = reason.toLowerCase();
        if (lower.contains("fear&greed") || lower.contains("fear & greed")) return sidePrefix + "/F&G";
        if (lower.contains("rsi=")) return sidePrefix + "/RSI";
        if (lower.contains("4h")) return sidePrefix + "/4h 趨勢";
        if (lower.contains("鯨魚")) return sidePrefix + "/鯨魚";
        if (lower.contains("資金費率")) return sidePrefix + "/資金費率";
        if (lower.contains("多空")) return sidePrefix + "/多空比";
        if (lower.contains("polymarket")) return sidePrefix + "/Polymarket";
        if (lower.contains("dailylossguard")) return sidePrefix + "/每日熔斷";
        return sidePrefix + "/其他";
    }

    private boolean isDecisionFilterReason(String reason) {
        if (reason == null || reason.isBlank()) return false;
        String lower = reason.toLowerCase();
        // Operational skips explain why no additional order was placed; they
        // should not pollute alpha/filter statistics such as RegimeFilter,
        // LongAiFilter, MlGate, or DailyLossGuard.
        if (lower.contains("legacyentryskip")) return false;
        if (lower.contains("entrydedup")) return false;
        if (lower.contains("duplicatebar")) return false;
        if (lower.contains("entrycooldown")) return false;
        if (lower.contains("tradingdisabled")) return false;
        if (lower.contains("autotrade: existing open position")) return false;
        if (lower.contains("autotrade: maxopenpositions")) return false;
        if (lower.contains("autotrade: trading disabled")) return false;
        return true;
    }

    private String extractAuditRuleKey(com.agora.model.BtDecisionAudit audit, String side) {
        String sidePrefix = "SHORT".equals(side) ? "SHORT" : "LONG";
        String blocker = audit.getBlocker();
        if (blocker != null && !blocker.isBlank()) {
            String lower = blocker.toLowerCase();
            if (lower.contains("regime")) return sidePrefix + "/RegimeFilter";
            if (lower.contains("ensemble")) return sidePrefix + "/EnsembleGate";
            if (lower.contains("ml")) return sidePrefix + "/MlGate";
            if (lower.contains("daily")) return sidePrefix + "/每日熔斷";
            if (lower.contains("longaifilter")) return sidePrefix + "/LongAiFilter";
            if (lower.contains("shortaifilter")) return sidePrefix + "/ShortAiFilter";
            return sidePrefix + "/" + blocker;
        }
        return extractRuleKey(audit.getReason(), side);
    }

    // ─── Polymarket 宏觀風險 ───────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢 Polymarket 預測市場上川普關稅/貿易政策相關市場的概率，" +
            "評估當前宏觀正面衝擊風險（關稅暫停/貿易協議概率），用於判斷 ShortAiFilter 宏觀風險狀態。" +
            "riskScore >= 0.40 時 ShortAiFilter Layer 1 會封鎖做空。")
    public String getPolymarketRisk() {
        if (!liveSentimentEnabled) {
            return disabledLiveSentimentMessage("getPolymarketRisk", "read Polymarket directly");
        }

        try {
            PolymarketService.MacroRiskResult result = polymarketService.getMacroRisk();

            StringBuilder sb = new StringBuilder();
            sb.append("=== Polymarket 宏觀風險評估 ===\n\n");

            if (result.riskScore() < 0) {
                sb.append("❓ 無相關活躍市場 → riskScore=-1（中立，不觸發 ShortAiFilter 封鎖）\n");
                sb.append("可能原因：當前無活躍的關稅暫停/貿易協議預測市場，或 volume < $10,000\n");
            } else {
                String riskIcon = result.riskScore() >= 0.40 ? "🚫" : (result.riskScore() >= 0.25 ? "⚠️" : "✅");
                sb.append(String.format("%s 宏觀風險分數: %.0f%%\n", riskIcon, result.riskScore() * 100));
                sb.append(String.format("ShortAiFilter 封鎖門檻: 40%%\n"));
                sb.append(result.riskScore() >= 0.40
                        ? "→ 當前狀態：做空已封鎖（Polymarket 宏觀風險觸發）\n"
                        : "→ 當前狀態：宏觀風險未觸發，做空未因此封鎖\n");

                sb.append(String.format("\n找到 %d 個相關市場：\n", result.markets().size()));
                for (PolymarketService.MarketInfo m : result.markets()) {
                    sb.append(String.format("  [Yes=%.0f%% | vol=$%,d] %s\n",
                            m.yesProbability() * 100, m.volume(), m.question()));
                }
            }

            sb.append("\n注意：結果快取 1 小時，Polymarket API 失敗時自動降級為中立（不封鎖）。");
            return sb.toString();
        } catch (Exception e) {
            log.error("[MarketDataMcp] getPolymarketRisk failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── 綜合市場情緒儀表板 ──────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "一次查看 Long/ShortAiFilter Layer 1 所有指標的當前狀態，對每個指標標示 SHORT/LONG 兩側是否達到封鎖門檻。" +
            "指標：Fear&Greed、鯨魚買入比、OKX 資金費率、OKX 多空帳戶比率、Polymarket 宏觀風險、Orderbook imbalance、" +
            "BTC Basis(SWAP-現貨溢價)、BTC DVOL(隱含波動率)、US VIX。" +
            "param: symbol=交易對（如 BTCUSDT 或 ETHUSDT）。")
    public String getMarketSentiment(String symbol) {
        if (!liveSentimentEnabled) {
            return disabledLiveSentimentMessage("getMarketSentiment",
                    "read Fear&Greed, whale flow, OKX, Polymarket, and orderbook endpoints directly");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Long/Short Filter 指標儀表板 ===\n");
        sb.append("幣種: ").append(symbol).append("\n");
        sb.append("格式: 指標值 | SHORT側 | LONG側\n\n");

        // 1. Fear & Greed：SHORT 封鎖 <25，LONG 封鎖 >75
        try {
            int fg = fearGreedService.getFearGreedValue();
            String shortIcon = fg < 25 ? "🚫" : "✅";
            String longIcon  = fg > 75 ? "🚫" : "✅";
            sb.append(String.format("Fear&Greed = %d  |  SHORT %s (<25)  |  LONG %s (>75)\n",
                    fg, shortIcon, longIcon));
        } catch (Exception e) {
            sb.append("❓ Fear&Greed: 取得失敗\n");
        }

        // 2. 鯨魚買入比:SHORT 封鎖 >65%,LONG 封鎖 <25%(SPOT 寬鬆,2026-04 spot-mode 上線後降低)
        try {
            double whale = whaleFlowService.getBuyRatio(symbol);
            String shortIcon = whale > 0.65 ? "🚫" : "✅";
            String longIcon  = whale > 0 && whale < 0.25 ? "🚫" : "✅";
            sb.append(String.format("鯨魚買入比 = %.0f%%  |  SHORT %s (>65%%)  |  LONG %s (<25%%)\n",
                    whale * 100, shortIcon, longIcon));
        } catch (Exception e) {
            sb.append("❓ 鯨魚買入比: 取得失敗\n");
        }

        // 3. OKX 資金費率:SHORT 封鎖 <-0.03%;LONG 封鎖 >+0.05% **但 spot-mode 下 LONG 永遠 ✅**
        try {
            double fr = okxTradingService.getCurrentFundingRate(symbol);
            String shortIcon = fr < -0.0003 ? "🚫" : "✅";
            String longIcon  = fr > 0.0005 ? "🚫(spot-mode skip)" : "✅";
            sb.append(String.format("資金費率 = %.4f%%/8h  |  SHORT %s (<-0.03%%)  |  LONG %s (>+0.05%%, perp-only)\n",
                    fr * 100, shortIcon, longIcon));
        } catch (Exception e) {
            sb.append("❓ 資金費率: 取得失敗\n");
        }

        // 4. OKX 多空帳戶比率:SHORT 封鎖 <0.75;LONG 封鎖 >1.5 **但 spot-mode 下 LONG 永遠 ✅**
        try {
            double ls = okxTradingService.getLongShortRatio(symbol);
            if (ls < 0) {
                sb.append("❓ 多空比: 資料不可用\n");
            } else {
                String shortIcon = ls < 0.75 ? "🚫" : "✅";
                String longIcon  = ls > 1.5 ? "🚫(spot-mode skip)" : "✅";
                sb.append(String.format("多空帳戶比 = %.2f  |  SHORT %s (<0.75)  |  LONG %s (>1.5, perp-only)\n",
                        ls, shortIcon, longIcon));
            }
        } catch (Exception e) {
            sb.append("❓ 多空比: 取得失敗\n");
        }

        // 5. Polymarket 宏觀風險：SHORT 封鎖 >=40%，LONG v1 不用
        try {
            PolymarketService.MacroRiskResult macro = polymarketService.getMacroRisk();
            if (macro.riskScore() < 0) {
                sb.append("Polymarket 宏觀風險 = N/A  |  SHORT ✅ (無相關市場)  |  LONG — (v1 不使用)\n");
            } else {
                String shortIcon = macro.riskScore() >= 0.4 ? "🚫" : "✅";
                sb.append(String.format("Polymarket 宏觀風險 = %.0f%%  |  SHORT %s (≥40%%)  |  LONG — (v1 不使用)\n",
                        macro.riskScore() * 100, shortIcon));
            }
        } catch (Exception e) {
            sb.append("❓ Polymarket 宏觀風險: 取得失敗\n");
        }

        // 6. Orderbook imbalance：正向 buy wall = 封鎖 SHORT，負向 sell wall = 封鎖 LONG
        try {
            double imbalance = orderbookImbalanceService.getImbalance(symbol);
            String shortIcon = imbalance > 0.5 ? "🚫" : "✅";
            String longIcon  = imbalance < -0.5 ? "🚫" : "✅";
            sb.append(String.format("Orderbook imbalance = %+.2f  |  SHORT %s (>+0.5)  |  LONG %s (<-0.5)\n",
                    imbalance, shortIcon, longIcon));
        } catch (Exception e) {
            sb.append("❓ Orderbook imbalance: 取得失敗\n");
        }

        // 7. BTC Basis（SWAP vs 現貨溢價）：正=多頭溢價，負=貼水（觀察指標，v1 無封鎖）
        try {
            BigDecimal spotPx = okxTradingService.getLastPrice(symbol);
            BigDecimal swapPx = okxTradingService.getSwapLastPrice(symbol);
            if (spotPx != null && swapPx != null && spotPx.compareTo(BigDecimal.ZERO) > 0) {
                double basis = swapPx.subtract(spotPx)
                        .divide(spotPx, 8, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .doubleValue();
                String dir = basis > 0.02 ? "多頭溢價" : basis < -0.02 ? "貼水" : "平水";
                sb.append(String.format("Basis (SWAP-現貨) = %+.4f%%  (%s)  |  SHORT ✅  |  LONG ✅ (v1 觀察中)\n",
                        basis, dir));
            }
        } catch (Exception e) {
            sb.append("❓ Basis: 取得失敗\n");
        }

        // 8. BTC DVOL（Deribit 隱含波動率，options market fear gauge）：觀察指標
        try {
            indicatorHistoryRepository
                    .findTopCleanBySymbolAndIndicator("BTCUSDT", "btc_dvol")
                    .ifPresent(row -> {
                        double dvol = row.getValue().doubleValue();
                        String level = dvol > 80 ? "極高恐慌" : dvol > 60 ? "偏高" : dvol > 40 ? "中性" : "偏低";
                        sb.append(String.format("BTC DVOL = %.1f  (%s)  |  SHORT ✅  |  LONG ✅ (v1 觀察中)\n",
                                dvol, level));
                    });
        } catch (Exception e) {
            sb.append("❓ BTC DVOL: 取得失敗\n");
        }

        // 8b. BTC Put/Call ratio（Deribit 24h USD volume；>1=看跌保護需求高，<0.5=FOMO）：觀察指標
        try {
            indicatorHistoryRepository
                    .findTopCleanBySymbolAndIndicator("BTCUSDT", "btc_put_call_ratio")
                    .ifPresent(row -> {
                        double pcr = row.getValue().doubleValue();
                        String bias = pcr > 1.0 ? "看跌保護↑" : pcr < 0.5 ? "FOMO↑" : "中性";
                        sb.append(String.format("Put/Call ratio = %.2f  (%s)  |  SHORT ✅  |  LONG ✅ (v1 觀察中)\n",
                                pcr, bias));
                    });
        } catch (Exception e) {
            sb.append("❓ Put/Call ratio: 取得失敗\n");
        }

        // 9. US VIX（股市恐慌指數，FOMC 等宏觀事件前的風險情緒指標）：觀察指標
        try {
            indicatorHistoryRepository
                    .findTopCleanBySymbolAndIndicator("BTCUSDT", "us_vix")
                    .ifPresent(row -> {
                        double vix = row.getValue().doubleValue();
                        String level = vix > 30 ? "高恐慌" : vix > 20 ? "偏高" : "正常";
                        sb.append(String.format("US VIX = %.1f  (%s)  |  SHORT ✅  |  LONG ✅ (v1 觀察中)\n",
                                vix, level));
                    });
        } catch (Exception e) {
            sb.append("❓ US VIX: 取得失敗\n");
        }

        // 10. BTC 活躍地址數（CoinMetrics community，鏈上網路活躍度）：觀察指標
        try {
            indicatorHistoryRepository
                    .findTopCleanBySymbolAndIndicator("BTCUSDT", "btc_active_addr_cnt")
                    .ifPresent(row -> {
                        long addr = row.getValue().longValue();
                        String level = addr > 900_000 ? "高活躍" : addr > 600_000 ? "正常" : "偏低";
                        sb.append(String.format("活躍地址數 = %,d  (%s)  |  SHORT ✅  |  LONG ✅ (v1 觀察中)\n",
                                addr, level));
                    });
        } catch (Exception e) {
            sb.append("❓ 活躍地址數: 取得失敗\n");
        }

        // 11. 事件日曆（LONG+SHORT 同時封鎖）
        try {
            EventCalendarService.BlockResult evt = eventCalendarService.checkBlock();
            if (evt.blocked()) {
                long h = evt.timeToEvent().toHours();
                String when = h >= 0 ? String.format("%d 小時後", h) : String.format("%d 小時前", -h);
                sb.append(String.format("事件窗口 = %s (%s)  |  SHORT 🚫  |  LONG 🚫\n",
                        evt.event().name(), when));
            } else {
                eventCalendarService.nextUpcoming(14).ifPresentOrElse(
                        e -> {
                            long h = java.time.Duration.between(
                                    LocalDateTime.now(ZoneOffset.UTC), e.time()).toHours();
                            sb.append(String.format("事件窗口 = %s (%d 小時後)  |  SHORT ✅  |  LONG ✅\n",
                                    e.name(), h));
                        },
                        () -> sb.append("事件窗口 = 未來 14 天無事件  |  SHORT ✅  |  LONG ✅\n")
                );
            }
        } catch (Exception e) {
            sb.append("❓ 事件窗口: 取得失敗\n");
        }

        sb.append("\n🚫=封鎖該方向  ✅=該方向未封鎖  (v1 觀察中=收集中但尚未接入 Filter)");
        return sb.toString();
    }

    // ─── Short Squeeze 風險偵測 ──────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#286 短倉擠壓風險評估：綜合 L/S 比率、資金費率、成交量 spike、RSI 四項指標判斷是否為短倉擠壓行情。" +
            "短倉擠壓＝空頭過重 + 負資金費率 + 突發大量 + 急速超買 → 拉抬只是殺空頭，非趨勢起步。" +
            "用途：(1) 判斷是否為 fake breakout 應 fade；(2) 倉位已有 SL 時建議移至 break-even；" +
            "(3) 避免在 squeeze 高點追多。" +
            "param: symbol（如 BTCUSDT），intervalCode（K 線週期，預設 1h）")
    public String detectShortSqueezeRisk(String symbol, String intervalCode) {
        if (symbol == null || symbol.isBlank()) symbol = "BTCUSDT";
        if (intervalCode == null || intervalCode.isBlank()) intervalCode = "1h";

        StringBuilder sb = new StringBuilder();
        sb.append("=== Short Squeeze 風險評估: ").append(symbol).append(" ===\n\n");

        int score = 0;
        int maxScore = 4;

        // ── 條件 1：L/S 帳戶比 < 1.0（空頭主導）──────────────────────────────
        try {
            double ls = okxTradingService.getLongShortRatio(symbol);
            boolean shortHeavy = ls > 0 && ls < 1.0;
            if (shortHeavy) score++;
            sb.append(String.format("L/S 比 = %.3f  %s  (%s)\n",
                    ls,
                    shortHeavy ? "🔴 空頭主導" : "🟢 均衡/多頭主導",
                    shortHeavy ? "擠壓信號 +1" : "無信號"));
        } catch (Exception e) {
            sb.append("❓ L/S 比：取得失敗\n");
        }

        // ── 條件 2：資金費率 < -0.001%（空頭過度累積，持付費用）──────────────
        try {
            double fr = okxTradingService.getCurrentFundingRate(symbol);
            boolean negativeFunding = fr < -0.00001; // -0.001%/8h threshold
            if (negativeFunding) score++;
            sb.append(String.format("資金費率 = %.4f%%/8h  %s  (%s)\n",
                    fr * 100,
                    negativeFunding ? "🔴 空頭付息" : "🟢 中性/多頭付息",
                    negativeFunding ? "擠壓信號 +1" : "無信號"));
        } catch (Exception e) {
            sb.append("❓ 資金費率：取得失敗\n");
        }

        // ── 條件 3：近 3 根 K 線量能 spike ≥ 3× MA20 ────────────────────────
        try {
            List<MdKline> klines = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                            symbol, intervalCode,
                            org.springframework.data.domain.PageRequest.of(0, 25));
            if (klines != null && klines.size() >= 22) {
                // MA20 = mean volume of bars [3..22] (exclude latest 2 to avoid self-reference)
                double ma20 = klines.subList(2, 22).stream()
                        .filter(k -> k.getVolume() != null)
                        .mapToDouble(k -> k.getVolume().doubleValue())
                        .average().orElse(0);
                double recentMax = klines.subList(0, 3).stream()
                        .filter(k -> k.getVolume() != null)
                        .mapToDouble(k -> k.getVolume().doubleValue())
                        .max().orElse(0);
                double multiple = ma20 > 0 ? recentMax / ma20 : 0;
                boolean volSpike = multiple >= 3.0;
                if (volSpike) score++;
                sb.append(String.format("量能 spike = %.1f× MA20  %s  (%s)\n",
                        multiple,
                        volSpike ? "🔴 異常大量" : "🟢 正常量能",
                        volSpike ? "擠壓信號 +1" : "無信號"));
            } else {
                sb.append("❓ 量能：K 線資料不足\n");
            }
        } catch (Exception e) {
            sb.append("❓ 量能：取得失敗\n");
        }

        // ── 條件 4：RSI(14) > 65 且在 30 分鐘內上升 ≥ 10pp（急速超買）──────
        try {
            List<MdKline> klines = klineRepository
                    .findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                            symbol, intervalCode,
                            org.springframework.data.domain.PageRequest.of(0, 20));
            if (klines != null && klines.size() >= 15) {
                // Simple RSI(14) approximation from last 14 bars
                java.util.Collections.reverse(klines);
                double gains = 0, losses = 0;
                for (int i = klines.size() - 14; i < klines.size(); i++) {
                    if (klines.get(i).getClosePrice() == null || i == 0) continue;
                    double prev = klines.get(i-1).getClosePrice() != null
                            ? klines.get(i-1).getClosePrice().doubleValue() : 0;
                    double cur = klines.get(i).getClosePrice().doubleValue();
                    double delta = cur - prev;
                    if (delta > 0) gains += delta; else losses -= delta;
                }
                double avgGain = gains / 14.0;
                double avgLoss = losses / 14.0;
                double rsi = avgLoss == 0 ? 100 : 100 - (100 / (1 + avgGain / avgLoss));
                boolean rsiOverbought = rsi > 65;
                if (rsiOverbought) score++;
                sb.append(String.format("RSI(14) = %.1f  %s  (%s)\n",
                        rsi,
                        rsiOverbought ? "🔴 超買" : "🟢 正常",
                        rsiOverbought ? "擠壓信號 +1" : "無信號"));
            } else {
                sb.append("❓ RSI：K 線資料不足\n");
            }
        } catch (Exception e) {
            sb.append("❓ RSI：取得失敗\n");
        }

        // ── 綜合評分 ────────────────────────────────────────────────────────
        sb.append("\n───────────────────────────────────\n");
        String risk;
        String action;
        if (score >= 3) {
            risk = "🚨 高（擠壓風險 " + score + "/" + maxScore + ")";
            action = "建議：(1) 不追多 (2) 現有倉位 SL 移至 break-even (3) 觀察 fade 機會（急拉後做空）";
        } else if (score == 2) {
            risk = "⚠️ 中（擠壓信號 " + score + "/" + maxScore + ")";
            action = "建議：(1) 不追多 (2) 等待回落確認方向再操作";
        } else {
            risk = "🟢 低（擠壓信號 " + score + "/" + maxScore + ")";
            action = "建議：正常操作，注意後續量能是否持續";
        }
        sb.append("擠壓風險: ").append(risk).append("\n");
        sb.append(action).append("\n");
        sb.append("\n⚠️ 短倉擠壓 ≠ 趨勢反轉。急拉通常在 1-3 小時內回落至發動點。\n");
        return sb.toString();
    }

    // ─── Attention Rule 歷史回測 ──────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "#287 歷史回測 Attention Rules：對過去 N 天 K 線 + mih_indicator 重放，統計觸發率和觸發後價格動向。" +
            "⚡ 合併 simulateShortOnFgDays：加入 takeProfitPct+stopLossPct 時自動跑 TP/SL 模擬（完整 P&L）。" +
            "F&G 條件用 mih_indicator=fear_greed,mih_lt=25（如 simulateShortOnFgDays 等效）。" +
            "支援 mode=individual/combined_and/combined_or。" +
            "params: ruleIds=逗號分隔 rule id（如 '25,26,27'），symbol（預設 BTCUSDT），" +
            "intervalCode（預設 1h），days（回溯天數，預設 30，最多 180），" +
            "mode（combined_and），forwardWindowHours（預設 '6,24'），" +
            "side（LONG/SHORT，TP/SL 模擬時必填），" +
            "takeProfitPct（選填，設定後啟動 TP/SL 模擬，如 5.0 = 5%），" +
            "stopLossPct（選填，如 2.0 = 2%）")
    public String simulateAttentionRulesOnHistory(
            String ruleIds, String symbol, String intervalCode,
            Integer days, String mode, String forwardWindowHours,
            String side, Double takeProfitPct, Double stopLossPct) {

        // ── Input parsing ──────────────────────────────────────────────────
        if (ruleIds == null || ruleIds.isBlank()) return "❌ ruleIds 必填（例: '22,23,24'）";
        List<Long> ids;
        try { ids = java.util.Arrays.stream(ruleIds.split(","))
                .map(String::trim).map(Long::parseLong).collect(java.util.stream.Collectors.toList());
        } catch (NumberFormatException e) { return "❌ ruleIds 格式錯誤（需逗號分隔整數）"; }

        String sym   = symbol       != null && !symbol.isBlank()       ? symbol       : "BTCUSDT";
        String ivl   = intervalCode != null && !intervalCode.isBlank() ? intervalCode : "1h";
        int    d     = days         != null ? Math.min(Math.max(days, 1), 180) : 30;
        String modeStr = mode       != null && !mode.isBlank()         ? mode.toLowerCase() : "combined_and";
        List<Integer> fwdHours;
        try { fwdHours = java.util.Arrays.stream(
                (forwardWindowHours != null && !forwardWindowHours.isBlank() ? forwardWindowHours : "6,24").split(","))
                .map(String::trim).map(Integer::parseInt).collect(java.util.stream.Collectors.toList());
        } catch (NumberFormatException e) { fwdHours = java.util.List.of(6, 24); }

        // ── Load rules ────────────────────────────────────────────────────
        List<AttentionRule> rules = new ArrayList<>();
        for (Long id : ids) {
            attentionRuleRepository.findById(id).ifPresent(rules::add);
        }
        if (rules.isEmpty()) return "❌ 找不到指定的 Attention Rules (ids=" + ruleIds + ")";

        // ── Parse predicates ──────────────────────────────────────────────
        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        java.util.Map<Long, java.util.Map<String, Object>> preds = new LinkedHashMap<>();
        java.util.Set<String> mihIndicators = new java.util.HashSet<>();
        boolean needsRsi = false, needsVolRatio = false, needsFg = false;

        for (AttentionRule r : rules) {
            try {
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> p = om.readValue(r.getPredicateJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {});
                preds.put(r.getId(), p);
                if (p.containsKey("mih_indicator")) mihIndicators.add((String) p.get("mih_indicator"));
                if (p.containsKey("rsi_gt") || p.containsKey("rsi_lt")) needsRsi = true;
                if (p.containsKey("volume_ratio_gt") || p.containsKey("volume_ratio_lt")) needsVolRatio = true;
                if (p.containsKey("fg_gt") || p.containsKey("fg_lt")) needsFg = true;
            } catch (Exception e) {
                return "❌ Rule #" + r.getId() + " predicate JSON 解析失敗: " + e.getMessage();
            }
        }

        LocalDateTime now   = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        LocalDateTime since = now.minusDays(d);
        LocalDateTime warmup = since.minusDays(25); // extra warmup for MA20/RSI

        // ── Load klines (needed for RSI / volume / close lookup) ──────────
        List<MdKline> allKlines = klineRepository
                .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(sym, ivl, warmup, now);
        // Index klines by openTime for O(1) lookup
        java.util.TreeMap<LocalDateTime, MdKline> klineMap = new java.util.TreeMap<>();
        for (MdKline k : allKlines) klineMap.put(k.getOpenTime(), k);

        // Pre-compute RSI(14) and volumeMa20 for each bar
        java.util.Map<LocalDateTime, Double> rsiByTime    = new java.util.HashMap<>();
        java.util.Map<LocalDateTime, Double> volRatioByTime = new java.util.HashMap<>();
        if (needsRsi || needsVolRatio) {
            java.util.List<MdKline> sorted = new ArrayList<>(allKlines);
            double avgGain = 0, avgLoss = 0;
            java.util.Deque<Double> volWindow = new java.util.ArrayDeque<>(20);
            for (int i = 1; i < sorted.size(); i++) {
                MdKline cur  = sorted.get(i);
                MdKline prev = sorted.get(i - 1);
                if (cur.getClosePrice() == null || prev.getClosePrice() == null) continue;
                double delta = cur.getClosePrice().subtract(prev.getClosePrice()).doubleValue();
                if (i <= 14) {
                    avgGain = (avgGain * (i-1) + Math.max(delta, 0)) / i;
                    avgLoss = (avgLoss * (i-1) + Math.max(-delta, 0)) / i;
                } else {
                    avgGain = (avgGain * 13 + Math.max(delta, 0)) / 14.0;
                    avgLoss = (avgLoss * 13 + Math.max(-delta, 0)) / 14.0;
                }
                double rsi = avgLoss == 0 ? 100.0 : 100.0 - 100.0 / (1 + avgGain / avgLoss);
                rsiByTime.put(cur.getOpenTime(), rsi);
                // Volume MA20
                if (cur.getVolume() != null) {
                    volWindow.addLast(cur.getVolume().doubleValue());
                    if (volWindow.size() > 20) volWindow.pollFirst();
                    double ma20 = volWindow.stream().mapToDouble(Double::doubleValue).average().orElse(0);
                    if (ma20 > 0) volRatioByTime.put(cur.getOpenTime(),
                            cur.getVolume().doubleValue() / ma20);
                }
            }
        }

        // ── Load mih_indicator history ─────────────────────────────────────
        java.util.Map<String, java.util.TreeMap<LocalDateTime, Double>> mihHistory = new HashMap<>();
        for (String ind : mihIndicators) {
            java.util.TreeMap<LocalDateTime, Double> series = new java.util.TreeMap<>();
            for (com.agora.model.MarketIndicatorHistory h :
                    indicatorHistoryRepository
                            .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(sym, ind, warmup)) {
                if (h.getValue() != null) series.put(h.getCapturedAt(), h.getValue().doubleValue());
            }
            // Also try without symbol prefix (some stored as BTCUSDT, some as global)
            if (series.isEmpty()) {
                for (com.agora.model.MarketIndicatorHistory h :
                        indicatorHistoryRepository
                                .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc("BTCUSDT", ind, warmup)) {
                    if (h.getValue() != null) series.put(h.getCapturedAt(), h.getValue().doubleValue());
                }
            }
            mihHistory.put(ind, series);
        }

        // ── Evaluate rules bar-by-bar ──────────────────────────────────────
        // fire_by_time: {timestamp → list of rule IDs that fired}
        java.util.TreeMap<LocalDateTime, List<Long>> firesByTime = new java.util.TreeMap<>();

        for (LocalDateTime t = since; !t.isAfter(now.minusHours(java.util.Collections.max(fwdHours)));
             t = t.plusHours(1)) {
            MdKline bar = klineMap.get(t);
            if (bar == null) continue; // no kline for this hour

            java.util.List<Long> firedRules = new ArrayList<>();
            for (AttentionRule r : rules) {
                java.util.Map<String, Object> pred = preds.get(r.getId());
                if (pred == null) continue;
                if (!evalPredicate(pred, t, bar, rsiByTime, volRatioByTime, mihHistory)) continue;
                firedRules.add(r.getId());
            }
            if (!firedRules.isEmpty()) firesByTime.put(t, firedRules);
        }

        // ── Determine fire events per mode ─────────────────────────────────
        java.util.List<LocalDateTime> events = new ArrayList<>();
        for (java.util.Map.Entry<LocalDateTime, List<Long>> e : firesByTime.entrySet()) {
            List<Long> fired = e.getValue();
            boolean trigger = switch (modeStr) {
                case "combined_and"  -> fired.size() == rules.size();
                case "combined_or"   -> !fired.isEmpty();
                default              -> !fired.isEmpty(); // individual handled separately
            };
            if (trigger) events.add(e.getKey());
        }

        // ── Forward price action ──────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== simulateAttentionRulesOnHistory ===\n" +
                "Symbol: %s %s | Period: %s → %s (%d days)\n" +
                "Rules: %s\nMode: %s\n\n",
                sym, ivl,
                since.format(DateTimeFormatter.ofPattern("MM-dd")),
                now.format(DateTimeFormatter.ofPattern("MM-dd")),
                d, ruleIds, modeStr));

        // Per-rule stats
        sb.append("── Per-Rule Stats ──────────────────────────────\n");
        for (AttentionRule r : rules) {
            long hits = firesByTime.values().stream().filter(l -> l.contains(r.getId())).count();
            double rate = allKlines.size() > 0
                    ? hits * 100.0 / Math.max(1, (int) java.time.Duration.between(since, now).toHours())
                    : 0;
            sb.append(String.format("Rule #%d [%s]: hits=%d  fire_rate=%.1f%%/bar\n",
                    r.getId(), r.getName(), hits, rate));
        }
        sb.append("\n");

        // Combined events
        int totalEvents = events.size();
        sb.append(String.format("── %s Events: %d ────────────────────────────\n",
                modeStr.toUpperCase(), totalEvents));

        if (totalEvents == 0) {
            sb.append("⚠️ 指定期間內無觸發事件。可能原因：\n" +
                    "  1. mih_indicator 歷史資料不足\n" +
                    "  2. 閾值太嚴格，歷史上沒有達標\n" +
                    "  3. 指定的 indicator 名稱與 DB 不符\n");
            // Show available indicators
            sb.append("\n可用 mih_indicator 清單（前30）：\n");
            try {
                java.util.List<java.util.Map<String, Object>> inds = jdbc.queryForList(
                        "SELECT DISTINCT indicator, COUNT(*) as cnt FROM market_indicator_history " +
                        "WHERE captured_at > ? GROUP BY indicator ORDER BY cnt DESC LIMIT 30",
                        since);
                for (java.util.Map<String, Object> row : inds) {
                    sb.append(String.format("  %-40s %s rows\n", row.get("indicator"), row.get("cnt")));
                }
            } catch (Exception ignore) {}
            return sb.toString();
        }

        // Forward return stats
        java.util.Map<Integer, List<Double>> returnsByFwdHour = new LinkedHashMap<>();
        for (int fwdH : fwdHours) returnsByFwdHour.put(fwdH, new ArrayList<>());

        StringBuilder events_sb = new StringBuilder();
        int shown = 0;
        for (LocalDateTime t : events) {
            MdKline bar = klineMap.get(t);
            if (bar == null || bar.getClosePrice() == null) continue;
            double priceFire = bar.getClosePrice().doubleValue();
            StringBuilder line = new StringBuilder(String.format("  %s  price=%.0f  rules=%s",
                    t.format(DateTimeFormatter.ofPattern("MM-dd HH:mm")), priceFire,
                    firesByTime.getOrDefault(t, java.util.List.of()).toString()));
            for (int fwdH : fwdHours) {
                LocalDateTime fwdT = t.plusHours(fwdH);
                MdKline fwdBar = klineMap.floorEntry(fwdT) != null ? klineMap.floorEntry(fwdT).getValue() : null;
                if (fwdBar != null && fwdBar.getClosePrice() != null) {
                    double priceFwd = fwdBar.getClosePrice().doubleValue();
                    double ret = (priceFwd - priceFire) / priceFire * 100.0;
                    returnsByFwdHour.get(fwdH).add(ret);
                    line.append(String.format("  +%dh=%+.2f%%", fwdH, ret));
                }
            }
            if (shown < 20) { events_sb.append(line).append("\n"); shown++; }
        }

        // Aggregate
        sb.append("\n── Aggregate Stats ─────────────────────────────\n");
        for (int fwdH : fwdHours) {
            List<Double> rets = returnsByFwdHour.get(fwdH);
            if (rets.isEmpty()) { sb.append(String.format("+%dh: n/a\n", fwdH)); continue; }
            double avg = rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double wins = rets.stream().filter(r -> r < 0).count(); // fade-short = price drops = win
            double winrate = wins / rets.size() * 100;
            sb.append(String.format("+%dh: n=%d  avg_ret=%+.2f%%  fade-short winrate=%.1f%% (price-drop%%)\n",
                    fwdH, rets.size(), avg, winrate));
        }

        // ── Precision / Recall (#290) ─────────────────────────────────────────
        // Precision = 信號觸發後，價格真的走對方向的比例
        // Recall    = 所有 >3% 大行情中，被信號提前捕捉的比例（信號在行情前 fwdH 小時內觸發）
        // F1        = 2 × Precision × Recall / (Precision + Recall)
        sb.append("\n── Precision / Recall (大行情 >3%) ──────────────\n");
        boolean isShortSide = "SHORT".equalsIgnoreCase(side);
        double LARGE_MOVE_THRESHOLD = 0.03;
        for (int fwdH : fwdHours) {
            List<Double> rets = returnsByFwdHour.get(fwdH);
            if (rets.isEmpty()) continue;
            long correctSignals = isShortSide
                    ? rets.stream().filter(r -> r < 0).count()
                    : rets.stream().filter(r -> r > 0).count();
            double precision = correctSignals * 100.0 / rets.size();
            long totalLargeMoves = 0, capturedMoves = 0;
            for (java.util.Map.Entry<LocalDateTime, MdKline> entry : klineMap.entrySet()) {
                LocalDateTime barT = entry.getKey();
                MdKline bar = entry.getValue();
                if (bar.getClosePrice() == null) continue;
                MdKline fwdBar = klineMap.floorEntry(barT.plusHours(fwdH)) != null
                        ? klineMap.floorEntry(barT.plusHours(fwdH)).getValue() : null;
                if (fwdBar == null || fwdBar.getClosePrice() == null) continue;
                double ret = (fwdBar.getClosePrice().doubleValue() - bar.getClosePrice().doubleValue())
                        / bar.getClosePrice().doubleValue();
                boolean isLargeMove = isShortSide ? ret < -LARGE_MOVE_THRESHOLD : ret > LARGE_MOVE_THRESHOLD;
                if (isLargeMove) {
                    totalLargeMoves++;
                    final LocalDateTime bT = barT;
                    boolean captured = events.stream()
                            .anyMatch(t -> !t.isAfter(bT) && t.isAfter(bT.minusHours(fwdH)));
                    if (captured) capturedMoves++;
                }
            }
            double recall = totalLargeMoves > 0 ? capturedMoves * 100.0 / totalLargeMoves : 0;
            double f1 = (precision + recall > 0) ? 2 * precision * recall / (precision + recall) : 0;
            sb.append(String.format("+%dh: Precision=%.1f%%(%d/%d)  Recall=%.1f%%(%d/%d 大行情)  F1=%.1f%%\n",
                    fwdH, precision, correctSignals, rets.size(),
                    recall, capturedMoves, totalLargeMoves, f1));
        }

        sb.append("\n── Events (showing up to 20) ────────────────────\n");
        sb.append(events_sb);
        if (totalEvents > 20) sb.append(String.format("  ... + %d more events\n", totalEvents - 20));

        // ── TP/SL P&L Simulation (merged from simulateShortOnFgDays) ──────────
        // When takeProfitPct + stopLossPct provided, run actual trade simulation.
        if (takeProfitPct != null && takeProfitPct > 0 && stopLossPct != null && stopLossPct > 0) {
            boolean isShort = "SHORT".equalsIgnoreCase(side);
            int tpHit = 0, slHit = 0, timeoutCnt = 0;
            double totalPnlPct = 0;
            final int MAX_HOLD = 72;
            final double tp = takeProfitPct / 100.0;
            final double sl = stopLossPct / 100.0;

            sb.append(String.format("\n── TP/SL Trade Simulation (%s  TP=%.1f%%  SL=%.1f%%  maxHold=%dh) ──\n",
                    isShort ? "SHORT" : "LONG", takeProfitPct, stopLossPct, MAX_HOLD));

            for (LocalDateTime t : events) {
                MdKline entryBar = klineMap.get(t);
                if (entryBar == null || entryBar.getClosePrice() == null) continue;
                double entry = entryBar.getClosePrice().doubleValue();
                // LONG: TP = entry*(1+tp), SL = entry*(1-sl)
                // SHORT: TP = entry*(1-tp), SL = entry*(1+sl)
                double tpPrice = isShort ? entry * (1 - tp) : entry * (1 + tp);
                double slPrice = isShort ? entry * (1 + sl) : entry * (1 - sl);

                String outcome = "TIMEOUT";
                double exitPrice = entry;
                for (LocalDateTime fwdT = t.plusHours(1); !fwdT.isAfter(t.plusHours(MAX_HOLD)); fwdT = fwdT.plusHours(1)) {
                    MdKline bar = klineMap.get(fwdT);
                    if (bar == null || bar.getHighPrice() == null || bar.getLowPrice() == null) continue;
                    double hi = bar.getHighPrice().doubleValue();
                    double lo = bar.getLowPrice().doubleValue();
                    if (isShort) {
                        if (hi >= slPrice) { outcome = "SL"; exitPrice = slPrice; break; }
                        if (lo <= tpPrice) { outcome = "TP"; exitPrice = tpPrice; break; }
                    } else {
                        if (lo <= slPrice) { outcome = "SL"; exitPrice = slPrice; break; }
                        if (hi >= tpPrice) { outcome = "TP"; exitPrice = tpPrice; break; }
                    }
                }
                if ("TIMEOUT".equals(outcome)) {
                    java.util.Map.Entry<LocalDateTime, MdKline> last = klineMap.floorEntry(t.plusHours(MAX_HOLD));
                    if (last != null && last.getValue().getClosePrice() != null)
                        exitPrice = last.getValue().getClosePrice().doubleValue();
                }
                double pnlPct = isShort ? (entry - exitPrice) / entry * 100 : (exitPrice - entry) / entry * 100;
                if ("TP".equals(outcome)) tpHit++;
                else if ("SL".equals(outcome)) slHit++;
                else timeoutCnt++;
                totalPnlPct += pnlPct;
            }

            int simN = tpHit + slHit + timeoutCnt;
            if (simN > 0) {
                double winRate = tpHit * 100.0 / simN;
                double avgPnl  = totalPnlPct / simN;
                sb.append(String.format("n=%d  TP=%d  SL=%d  Timeout=%d\n", simN, tpHit, slHit, timeoutCnt));
                sb.append(String.format("勝率: %.1f%%  平均損益: %+.2f%%\n", winRate, avgPnl));
                if (winRate > 55)      sb.append("✅ 有 alpha（勝率 > 55%），可考慮接策略\n");
                else if (winRate > 45) sb.append("⚠️ 接近隨機，建議調整閾值或等更多樣本\n");
                else                   sb.append("❌ 勝率 < 45%，條件組合無 alpha\n");
            } else {
                sb.append("⚠️ 無足夠事件可模擬 TP/SL\n");
            }
        }

        return sb.toString();
    }

    /** Evaluate a single rule predicate against historical bar data. */
    @SuppressWarnings("unchecked")
    private boolean evalPredicate(
            java.util.Map<String, Object> pred, LocalDateTime t, MdKline bar,
            java.util.Map<LocalDateTime, Double> rsiMap,
            java.util.Map<LocalDateTime, Double> volRatioMap,
            java.util.Map<String, java.util.TreeMap<LocalDateTime, Double>> mihHistory) {
        // RSI
        if (pred.containsKey("rsi_gt") || pred.containsKey("rsi_lt")) {
            Double rsi = rsiMap.get(t);
            if (rsi == null) return false;
            if (pred.containsKey("rsi_gt") && rsi <= toDouble(pred.get("rsi_gt"))) return false;
            if (pred.containsKey("rsi_lt") && rsi >= toDouble(pred.get("rsi_lt"))) return false;
        }
        // Volume ratio
        if (pred.containsKey("volume_ratio_gt") || pred.containsKey("volume_ratio_lt")) {
            Double vr = volRatioMap.get(t);
            if (vr == null) return false;
            if (pred.containsKey("volume_ratio_gt") && vr <= toDouble(pred.get("volume_ratio_gt"))) return false;
            if (pred.containsKey("volume_ratio_lt") && vr >= toDouble(pred.get("volume_ratio_lt"))) return false;
        }
        // mih_indicator — find closest historical value (within ±2h)
        if (pred.containsKey("mih_indicator")) {
            String ind = (String) pred.get("mih_indicator");
            java.util.TreeMap<LocalDateTime, Double> series = mihHistory.get(ind);
            if (series == null || series.isEmpty()) return false;
            java.util.Map.Entry<LocalDateTime, Double> entry = series.floorEntry(t);
            if (entry == null || java.time.Duration.between(entry.getKey(), t).abs().toHours() > 2) return false;
            double val = entry.getValue();
            if (pred.containsKey("mih_gt") && val <= toDouble(pred.get("mih_gt"))) return false;
            if (pred.containsKey("mih_lt") && val >= toDouble(pred.get("mih_lt"))) return false;
        }
        return true;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); } catch (Exception e) { return 0; }
    }

    // ─── 事件日曆查詢 ──────────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "列出未來 N 天內的高影響宏觀事件（FOMC / US CPI 等）。" +
            "若當前處於事件窗口（事件前 2h 至事件後 4h），Long/ShortAiFilter 會封鎖所有新倉。" +
            "param: days=回望天數（預設 14，最多 90）。")
    public String getUpcomingEvents(Integer days) {
        if (days == null || days <= 0 || days > 90) days = 14;
        java.util.List<EventCalendarService.Event> events = eventCalendarService.listUpcoming(days);
        StringBuilder sb = new StringBuilder("=== 未來 ").append(days).append(" 天高影響事件 ===\n\n");
        if (events.isEmpty()) {
            sb.append("(無事件)");
            return sb.toString();
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        for (EventCalendarService.Event e : events) {
            long h = java.time.Duration.between(now, e.time()).toHours();
            sb.append(String.format("• %s %s  (%d 小時後)\n",
                    e.time().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                    e.name(), h));
        }
        EventCalendarService.BlockResult current = eventCalendarService.checkBlock();
        if (current.blocked()) {
            sb.append("\n🚫 當前處於 ").append(current.event().name()).append(" 事件窗口，封鎖所有新倉");
        }
        return sb.toString();
    }

    // ─── 系統健康檢查 ──────────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.META})
    @Tool(description = "一次檢查所有外部依賴與內部狀態：Binance WS 訂閱、OKX REST、Fear&Greed、" +
            "Polymarket、Whale 資料源、DB、策略數、未平倉數、最近錯誤數。" +
            "出現訊號異常或資料異常時的第一個診斷工具。")
    public String getSystemHealth() {
        StringBuilder sb = new StringBuilder("=== 系統健康檢查 ===\n");
        sb.append("時間: ").append(LocalDateTime.now(ZoneOffset.UTC)).append(" UTC\n\n");

        // 1. 所有 KlineStreamService provider 狀態（雙源：binance + okx）
        for (KlineStreamService svc : klineStreamServices) {
            try {
                List<KlineSubscriptionInfo> subs = svc.listSubscriptions();
                long running = subs.stream().filter(s -> "RUNNING".equals(s.getStatus())).count();
                long total = subs.size();
                String icon = running == total && total > 0 ? "✅" : "⚠️";
                sb.append(String.format("%s %s WS: %d/%d RUNNING\n", icon,
                        svc.providerName().toUpperCase(), running, total));
                for (KlineSubscriptionInfo s : subs) {
                    sb.append(String.format("    %s %s@%s status=%s received=%d\n",
                            "RUNNING".equals(s.getStatus()) ? "✓" : "✗",
                            s.getSymbol(), s.getIntervalCode(),
                            s.getStatus(), s.getReceivedCount()));
                }
            } catch (Exception e) {
                sb.append("❌ ").append(svc.providerName()).append(" WS: ").append(e.getMessage()).append("\n");
            }
        }

        if (externalHealthProbesEnabled) {
            // 2. OKX REST（funding rate 為快取代理 ping）
            sb.append("\n").append(checkDep("OKX REST",
                    () -> String.format("BTC funding=%.4f%%/8h",
                            okxTradingService.getCurrentFundingRate("BTCUSDT") * 100)));

            // 3. OKX Long/Short ratio
            sb.append(checkDep("OKX L/S Ratio",
                    () -> String.format("BTC ls=%.2f", okxTradingService.getLongShortRatio("BTCUSDT"))));

            // 4. Fear & Greed
            sb.append(checkDep("Fear&Greed",
                    () -> "value=" + fearGreedService.getFearGreedValue()));

            // 5. Polymarket
            sb.append(checkDep("Polymarket",
                    () -> {
                        PolymarketService.MacroRiskResult r = polymarketService.getMacroRisk();
                        return r.riskScore() < 0
                                ? "no relevant markets"
                                : String.format("risk=%.0f%% markets=%d",
                                        r.riskScore() * 100, r.markets().size());
                    }));

            // 6. Whale (OKX taker-volume)
            sb.append(checkDep("Whale (OKX)",
                    () -> {
                        double btc = whaleFlowService.getBuyRatio("BTCUSDT");
                        double eth = whaleFlowService.getBuyRatio("ETHUSDT");
                        return String.format("BTC buyRatio=%.0f%% ETH buyRatio=%.0f%%", btc * 100, eth * 100);
                    }));

            // 7. Orderbook imbalance (OKX books)
            sb.append(checkDep("Orderbook (OKX)",
                    () -> {
                        double btc = orderbookImbalanceService.getImbalance("BTCUSDT");
                        double eth = orderbookImbalanceService.getImbalance("ETHUSDT");
                        return String.format("BTC=%+.2f ETH=%+.2f", btc, eth);
                    }));
        } else {
            sb.append("\n⚠️ External dependency probes disabled by trading.market-data-mcp.external-health-probes-enabled=false\n");
            sb.append("   Set TRADING_MARKET_DATA_MCP_EXTERNAL_HEALTH_PROBES_ENABLED=true to ping OKX, Fear&Greed, Polymarket, whale flow, and orderbook endpoints.\n");
        }

        // 8. DB（kline + strategy 簡單 count）
        sb.append(checkDep("DB",
                () -> String.format("klines=%d strategies=%d",
                        klineRepository.count(), strategyRepository.count())));

        // 8. 業務狀態
        sb.append("\n--- 業務狀態 ---\n");
        try {
            long openPos = liveSignalRepository.countByAutoTradedIsTrueAndExitTimeIsNull();
            long activeStrategies = strategyRepository.findByEnabled(true).size();
            sb.append(String.format("✅ 開倉中: %d 筆  啟用策略: %d 個\n", openPos, activeStrategies));
        } catch (Exception e) {
            sb.append("❌ 業務狀態查詢失敗: ").append(e.getMessage()).append("\n");
        }

        // 9. 每日虧損熔斷狀態
        try {
            DailyLossGuard.GuardResult guard = dailyLossGuard.check();
            String icon = guard.allowed() ? "✅" : "⛔";
            sb.append(String.format("%s 每日熔斷: %s\n", icon, guard.reason()));
        } catch (Exception e) {
            sb.append("❓ 每日熔斷: 查詢失敗\n");
        }

        return sb.toString();
    }

    /** 執行依賴檢查，回傳 "✅/❌ 名稱: <耗時>ms (<info>)" 行 */
    private String checkDep(String name, java.util.function.Supplier<String> probe) {
        long t0 = System.currentTimeMillis();
        try {
            String info = probe.get();
            return String.format("✅ %s: %dms (%s)\n", name, System.currentTimeMillis() - t0, info);
        } catch (Exception e) {
            return String.format("❌ %s: %dms (%s)\n", name, System.currentTimeMillis() - t0, e.getMessage());
        }
    }

    // ─── 市況分析 / 市場快照 ───────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "完整市場分析：自動取得 1h+4h 雙時框快照，分類市場形態（TRENDING/SIDEWAYS/VOLATILE/CONSOLIDATING），\n" +
            "依 ATR 自動校準 SL/TP 範圍，並生成 5 組 ready-to-use 候選策略 JSON。\n" +
            "輸出可直接複製傳入 validateCandidates，無需手動計算參數。\n" +
            "params: symbol=交易對(BTCUSDT/ETHUSDT), intervalCode=主要K線週期(1h/4h)")
    public String getMarketAnalysis(String symbol, String intervalCode) {
        String sym      = symbol      != null ? symbol.toUpperCase().trim()      : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode.toLowerCase().trim() : "1h";
        log.info("[MCP] getMarketAnalysis symbol={} interval={}", sym, interval);
        try {
            return aiDiscoveryService.getMarketAnalysis(sym, interval);
        } catch (Exception e) {
            log.error("[MCP] getMarketAnalysis failed", e);
            return "❌ 市場分析失敗：" + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "取得指定幣種/週期的當前市場快照（RSI、ATR%、EMA 趨勢、成交量比），\n" +
            "供外部 AI 分析後生成策略參數。搭配 validateCandidates 使用。\n" +
            "params: symbol=交易對(BTCUSDT/ETHUSDT), intervalCode=K線週期(1h/4h)")
    public String getMarketSnapshot(String symbol, String intervalCode) {
        String sym      = symbol      != null ? symbol.toUpperCase().trim()      : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode.toLowerCase().trim() : "1h";
        log.info("[MCP] getMarketSnapshot symbol={} interval={}", sym, interval);
        String snapshotText = aiDiscoveryService.getMarketSnapshotText(sym, interval);
        return snapshotText +
               "\n可用策略風格建議：\n" +
               "- HIGH_FREQ:    adxEntryThreshold 12~20, fixedStopLossPct 0.008~0.02, fixedTakeProfitPct 0.015~0.04, allowShort=true\n" +
               "- TREND:        adxEntryThreshold 22~32, fixedStopLossPct 0.02~0.04,  fixedTakeProfitPct 0.05~0.12, atrTrailingStopEnabled=true\n" +
               "- CONSERVATIVE: adxEntryThreshold 28~38, fixedStopLossPct 0.01~0.02,  fixedTakeProfitPct 0.025~0.05, minRR=2.0~3.0\n" +
               "\n依市場快照生成 SopMtfAdxConfig JSON array 後，呼叫 validateCandidates 執行回測驗證。";
    }

    // ─── K 線資料品質 / 管理 ──────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.MARKET_DATA})
    @Tool(description = "比對本地 DB K 線與 OKX API 歷史 K 線，檢測資料品質。" +
            "檢查：DB 是否有缺口、OHLCV 是否與 OKX 一致、是否有幽靈 bar。" +
            "容差：價格 0.3%、成交量 300%（跨所量綱差異）。" +
            "param: symbol=交易對, intervalCode=週期(1h/4h), days=回溯天數（最多 180），" +
            "source=binance 或 okx（預設 market.signal.source，檢查哪個源的 DB 資料）")
    public String validateKlineQuality(String symbol, String intervalCode, Integer days, String source) {
        int d = (days == null || days <= 0 || days > 180) ? 60 : days;
        String src = (source == null || source.isBlank())
                ? defaultKlineQualitySource.toLowerCase()
                : source.toLowerCase();
        KlineQualityValidator.ValidationReport r =
                klineQualityValidator.validate(symbol.toUpperCase(), intervalCode.toLowerCase(), d, src);

        StringBuilder sb = new StringBuilder();
        sb.append("=== K 線資料品質驗證 ===\n");
        sb.append(String.format("%s@%s 過去 %d 天（source=%s）\n\n", symbol, intervalCode, d, src));
        sb.append(String.format("📊 覆蓋率\n  DB bars: %d\n  OKX bars: %d\n\n",
                r.dbBarCount(), r.okxBarCount()));

        String missingIcon = r.missingInDb() == 0 ? "✅" : "🚫";
        String phantomIcon = r.phantomInDb() == 0 ? "✅" : "⚠️";
        String priceIcon   = r.priceDivergences() == 0 ? "✅" : "🚫";
        String volIcon     = r.volumeDivergences() == 0 ? "✅" : "⚠️";

        sb.append("🔍 差異檢查\n");
        sb.append(String.format("  %s DB 缺口: %d 筆（OKX 有但 DB 無）\n", missingIcon, r.missingInDb()));
        sb.append(String.format("  %s 幽靈 bar: %d 筆（DB 有但 OKX 無）\n", phantomIcon, r.phantomInDb()));
        sb.append(String.format("  %s 價格差異: %d 筆（容差 0.05%%）\n", priceIcon, r.priceDivergences()));
        sb.append(String.format("  %s 成交量差異: %d 筆（容差 5%%）\n", volIcon, r.volumeDivergences()));

        if (!r.samples().isEmpty()) {
            sb.append("\n📋 差異樣本（最多 10 筆）\n");
            for (KlineQualityValidator.Divergence div : r.samples()) {
                if ("MISSING".equals(div.field()) || "PHANTOM".equals(div.field())) {
                    sb.append(String.format("  %s %s at %s\n", div.field(),
                            div.field().equals("MISSING") ? "OKX_only" : "DB_only",
                            div.openTime()));
                } else {
                    sb.append(String.format("  %s %s  db=%.4f okx=%.4f diff=%.3f%%\n",
                            div.openTime(), div.field(),
                            div.dbValue(), div.okxValue(), div.diffPct() * 100));
                }
            }
        }

        boolean allGood = r.missingInDb() == 0 && r.phantomInDb() == 0
                && r.priceDivergences() == 0;  // volume 差異可忽略
        sb.append("\n").append(allGood
                ? "✅ 資料品質通過：DB 與 OKX 在容差內一致，回測結果可信"
                : "⚠️ 發現差異：建議人工檢視或用 KlineGapDetector / reimportHistorical 修復");
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "從 OKX REST API 批次回填歷史 K 線至 md_kline（source='okx'）。" +
            "用於補齊 OKX 資料集，使 backtest(source=okx) 能跑完整歷史。" +
            "端點分頁 300 根/頁，150ms/頁保守避開 rate limit。" +
            "param: symbol=交易對, intervalCode=週期, days=回填天數（最多 365）")
    public String backfillOkxKlines(String symbol, String intervalCode, Integer days) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillOkxKlines", "read OKX REST and write md_kline");
        }
        int d = (days == null || days <= 0) ? 180 : Math.min(days, 365);
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusDays(d);
        log.info("[MCP] backfillOkxKlines {}@{} {}d", symbol, intervalCode, d);
        KlineImportResponse resp = okxKlineImportService.importHistorical(
                symbol.toUpperCase(), intervalCode.toLowerCase(), start, end);

        return String.format(
                "=== OKX 歷史 K 線回填 ===\n%s@%s %d 天\n\n" +
                "✅ 匯入: %d 根新 bar\n" +
                "⏭  略過: %d 根（已存在）\n" +
                "⏱  耗時: %.1f 秒\n\n" +
                "DB 現在可用 runBacktest(strategyId=..., source=\"okx\") 跑 OKX 源的回測。",
                symbol, intervalCode, d,
                resp.getImportedCount(), resp.getSkippedCount(), resp.getDurationMs() / 1000.0);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 Uniswap v3 WBTC/USDC on-chain DEX flow 歷史數據至 market_indicator_history。" +
            "The Graph 有 2021 年起的完整歷史，每小時一筆，idempotent（已存在的小時自動略過）。" +
            "rate limit: 150ms/請求，180 天 ≈ 10 分鐘完成。" +
            "param: days=回填天數（預設 180，最多 730）")
    public String backfillDexFlow(Integer days) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillDexFlow",
                    "read The Graph and write market_indicator_history");
        }
        int d = (days == null || days <= 0) ? 180 : Math.min(days, 730);
        LocalDateTime end   = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime start = end.minusDays(d);
        log.info("[MCP] backfillDexFlow {}d ({} → {})", d, start.toLocalDate(), end.toLocalDate());
        return uniswapDexFlowService.startBackfillAsync(start, end);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 Fear & Greed 歷史數據至 market_indicator_history（daily，最多 365 天）。idempotent。")
    public String backfillFearGreed(Integer days) {
        if (!liveSentimentEnabled) {
            return disabledLiveSentimentMessage("backfillFearGreed",
                    "read alternative.me Fear&Greed history and write market_indicator_history");
        }
        int d = (days == null || days <= 0) ? 365 : Math.min(days, 365);
        log.info("[MCP] backfillFearGreed {}d", d);
        return indicatorHistoryBackfillService.backfillFearGreed(d);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 OKX 資金費率歷史至 market_indicator_history（每 8h，最多 100 筆）。idempotent。")
    public String backfillFundingRateHistory(String symbol, Integer limit) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillFundingRateHistory",
                    "read OKX funding-rate history and write market_indicator_history");
        }
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.toUpperCase();
        int lim = (limit == null || limit <= 0) ? 100 : Math.min(limit, 100);
        log.info("[MCP] backfillFundingRateHistory sym={} limit={}", sym, lim);
        return indicatorHistoryBackfillService.backfillFundingRate(sym, lim);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "#309 回填 OKX BTC 多空帳戶比 (long_short_ratio) 至 market_indicator_history（1H，最多 1440 筆 = 60 天）。" +
            "idempotent。param: symbol=交易對（預設 BTCUSDT）, limit=筆數（預設 1440 = 60 天）")
    public String backfillLongShortRatioHistory(String symbol, Integer limit) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillLongShortRatioHistory",
                    "read OKX long/short history and write market_indicator_history");
        }
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.toUpperCase();
        int lim = (limit == null || limit <= 0) ? 1440 : Math.min(limit, 1440);
        log.info("[MCP] backfillLongShortRatioHistory sym={} limit={}", sym, lim);
        return indicatorHistoryBackfillService.backfillLongShortRatio(sym, lim);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 FRED 宏觀指標歷史至 market_indicator_history（daily，us_10y_yield/us_vix/us_sp500/us_dxy）。" +
            "param: years=回填年數（預設 2，最多 5）。需要 FRED_API_KEY。")
    public String backfillFredMacro(Integer years) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillFredMacro",
                    "read FRED macro series and write market_indicator_history");
        }
        int y = (years == null || years <= 0) ? 2 : Math.min(years, 5);
        log.info("[MCP] backfillFredMacro {}y", y);
        return indicatorHistoryBackfillService.backfillFredSeries(y);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 Hyperliquid BTC 資金費率歷史至 market_indicator_history（hourly，最多 90 天）。" +
            "Hyperliquid API 免費、無需 key。idempotent。param: days=回填天數（預設 90）")
    public String backfillHyperliquidFunding(Integer days) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillHyperliquidFunding",
                    "read Hyperliquid funding history and write market_indicator_history");
        }
        int d = (days == null || days <= 0) ? 90 : Math.min(days, 180);
        log.info("[MCP] backfillHyperliquidFunding {}d", d);
        return indicatorHistoryBackfillService.backfillHyperliquidFunding(d);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 OKX rubik BTC 未平倉合約歷史至 market_indicator_history（1H，最多 288 筆 = 12 天）。" +
            "存入 btc_open_interest_usd_m（USD millions 單位，區別於 Binance 的 btc_open_interest BTC 合約數）。idempotent。")
    public String backfillOpenInterest(String symbol, Integer limit) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillOpenInterest",
                    "read OKX open-interest history and write market_indicator_history");
        }
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.toUpperCase();
        int lim = (limit == null || limit <= 0) ? 288 : Math.min(limit, 288);
        log.info("[MCP] backfillOpenInterest sym={} limit={}", sym, lim);
        return indicatorHistoryBackfillService.backfillOpenInterest(sym, lim);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC, Category.MARKET_DATA})
    @Tool(description = "手動觸發跨源 K 線偏差掃描（binance vs okx）。" +
            "由 trading.kline-divergence.enabled 明確開啟後才會掃描並在超過門檻時發 TG；此工具可即刻確認目前狀態。" +
            "回傳每個 (symbol, interval) 最近 N 根 bar 中超過 WARN/CRITICAL 閾值的筆數與樣本。")
    public String runKlineDivergenceScan() {
        return klineDivergenceMonitor.runManual();
    }

    // ─── 策略交易事後分析(F&G context)────────────────────────────────────────

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "分析指定策略過去 N 天每筆已平倉交易的入場時 context（F&G 值與分類），" +
            "找出系統性 bias（例如:策略在 EXTREME_FEAR 區間勝率特別低 → 建議封鎖該區間進場）。" +
            "param: strategyId=策略ID, days=回溯天數（預設 30，最多 180）。" +
            "回傳:總勝率、各 F&G 區間勝率與 PnL、最近 5 筆交易明細。")
    public String analyzeStrategyTrades(Long strategyId, Integer days) {
        if (!liveSentimentEnabled) {
            return disabledLiveSentimentMessage("analyzeStrategyTrades",
                    "read alternative.me Fear&Greed history for trade context analysis");
        }
        if (strategyId == null) return "❌ strategyId 不可為空";
        int daysVal = days != null ? Math.min(days, 180) : 30;
        LocalDateTime since = LocalDateTime.now().minusDays(daysVal);

        List<BtLiveSignal> trades = liveSignalRepository
                .findByStrategyIdAndCreatedAtAfter(strategyId, since)
                .stream()
                .filter(s -> s.getExitTime() != null && s.getRealizedPnl() != null)
                .toList();

        if (trades.isEmpty()) {
            return String.format("策略 %d 過去 %d 天無已平倉交易（總訊號數可能 > 0 但都未自動下單或仍開倉中）。",
                    strategyId, daysVal);
        }

        // F&G 歷史（daily entries），用於對齊每筆交易的入場時間
        final List<FearGreedService.FearGreedEntry> fgHistory = fetchFgHistorySafe(daysVal + 7);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 策略 ").append(strategyId).append(" 過去 ").append(daysVal)
          .append(" 天交易分析（").append(trades.size()).append(" 筆已平倉） ===\n\n");

        int wins = 0;
        BigDecimal totalPnl = BigDecimal.ZERO;
        java.util.Map<String, List<BtLiveSignal>> byBin = new java.util.LinkedHashMap<>();
        for (String b : List.of("EXTREME_FEAR(<25)", "FEAR(25-44)", "NEUTRAL(45-54)", "GREED(55-74)", "EXTREME_GREED(>=75)", "UNKNOWN")) {
            byBin.put(b, new ArrayList<>());
        }

        for (BtLiveSignal t : trades) {
            int fg = findClosestFg(t.getBarOpenTime(), fgHistory);
            String bin = fgBin(fg);
            byBin.get(bin).add(t);
            if (t.getRealizedPnl().signum() > 0) wins++;
            totalPnl = totalPnl.add(t.getRealizedPnl());
        }

        sb.append(String.format("📊 總體: 勝率 %.1f%% (%d/%d) | 總 PnL %+.2f USDT%n%n",
                wins * 100.0 / trades.size(), wins, trades.size(), totalPnl.doubleValue()));

        sb.append("=== 各 F&G 區間勝率 ===\n");
        for (var entry : byBin.entrySet()) {
            List<BtLiveSignal> bucket = entry.getValue();
            if (bucket.isEmpty()) continue;
            long binWins = bucket.stream().filter(t -> t.getRealizedPnl().signum() > 0).count();
            BigDecimal binPnl = bucket.stream().map(BtLiveSignal::getRealizedPnl)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append(String.format("  %-22s: %2d 筆 / 勝率 %5.1f%% / PnL %+8.2f%n",
                    entry.getKey(), bucket.size(),
                    binWins * 100.0 / bucket.size(), binPnl.doubleValue()));
        }

        sb.append("\n=== 最近 5 筆明細 ===\n");
        trades.stream()
                .sorted(java.util.Comparator.comparing(BtLiveSignal::getExitTime).reversed())
                .limit(5)
                .forEach(t -> {
                    int fg = findClosestFg(t.getBarOpenTime(), fgHistory);
                    sb.append(String.format("  %s [%s] entry=%s F&G=%d exit=%s PnL=%+.2f%n",
                            t.getBarOpenTime(),
                            t.getSide() != null ? t.getSide() : "LONG",
                            t.getEntryPrice() != null ? t.getEntryPrice().toPlainString() : "?",
                            fg,
                            t.getExitReason() != null ? t.getExitReason() : "?",
                            t.getRealizedPnl().doubleValue()));
                });

        sb.append("\n💡 系統性 bias 提示：若某 F&G 區間勝率顯著低於總勝率（差距 >15%），考慮在該區間封鎖進場。");
        return sb.toString();
    }

    private String disabledLiveSentimentMessage(String toolName, String action) {
        return "⚠️ " + toolName + " live external reads are disabled by "
                + "trading.market-data-mcp.live-sentiment-enabled=false. "
                + "Set TRADING_MARKET_DATA_MCP_LIVE_SENTIMENT_ENABLED=true only when manual MCP market-data tools "
                + "should " + action + ".";
    }

    private String disabledExternalBackfillMessage(String toolName, String action) {
        return "⚠️ " + toolName + " external backfills are disabled by "
                + "trading.market-data-mcp.external-backfills-enabled=false. "
                + "Set TRADING_MARKET_DATA_MCP_EXTERNAL_BACKFILLS_ENABLED=true only when manual MCP backfill/import tools "
                + "should " + action + ".";
    }

    private List<FearGreedService.FearGreedEntry> fetchFgHistorySafe(int days) {
        try {
            return fearGreedService.getHistoricalFearGreed(days);
        } catch (Exception e) {
            log.warn("[analyzeStrategyTrades] F&G fetch failed: {}", e.getMessage());
            return List.of();
        }
    }

    private int findClosestFg(LocalDateTime time, List<FearGreedService.FearGreedEntry> history) {
        if (history.isEmpty() || time == null) return -1;
        long target = time.toEpochSecond(ZoneOffset.UTC);
        return history.stream()
                .min(java.util.Comparator.comparingLong(e -> Math.abs(e.timestamp() - target)))
                .map(FearGreedService.FearGreedEntry::value)
                .orElse(-1);
    }

    private String fgBin(int fg) {
        if (fg < 0)  return "UNKNOWN";
        if (fg < 25) return "EXTREME_FEAR(<25)";
        if (fg < 45) return "FEAR(25-44)";
        if (fg < 55) return "NEUTRAL(45-54)";
        if (fg < 75) return "GREED(55-74)";
        return "EXTREME_GREED(>=75)";
    }

    // ─── Gemini Market Advisor ────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.META})
    @Tool(description = "手動觸發 GeminiMarketAdvisor 對所有監控 (symbol, timeframe) 跑一輪 hint。" +
            "三 persona(trend / contrarian / risk)並行投票,結果寫進 gemini_market_hint table。" +
            "用於驗證 Gemini 一致性(同 prompt 同輸出)或週期外 ad-hoc 重新評估。" +
            "param: 無(symbols/timeframes 從 application.yml 設定)")
    public String triggerGeminiAdvisor() {
        log.info("[MCP] triggerGeminiAdvisor (all)");
        return geminiMarketAdvisor.runManual();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "對單一 (symbol, intervalCode) 觸發 Gemini Advisor,精準 debug 用。" +
            "回傳完整 hint 結果(style/regime/confidence/persona votes/reasoning)。" +
            "param: symbol(BTCUSDT/ETHUSDT), intervalCode(1h/4h) — 命名跟 pauseStrategy 等其他 MCP 工具一致")
    public String triggerGeminiAdvisorSingle(String symbol, String intervalCode) {
        if (symbol == null || intervalCode == null) return "❌ symbol 與 intervalCode 不可為空";
        log.info("[MCP] triggerGeminiAdvisorSingle {} {}", symbol, intervalCode);
        return geminiMarketAdvisor.runManualSingle(symbol, intervalCode);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "查詢最近 N 天的 Gemini Market Advisor hints。" +
            "顯示每筆 hint 的 symbol/timeframe/style/regime/confidence，明細包含小時級時間戳。" +
            "用於分析 regime 變化比市場移動早多少小時（提前量分析）。" +
            "param: days(預設 7,最多 30)，regimeFilter(可選，如 TRENDING_DOWN，只顯示特定 regime)")
    public String getRecentHints(Integer days, String regimeFilter) {
        int daysVal = days != null ? Math.min(days, 30) : 7;
        LocalDateTime since = LocalDateTime.now().minusDays(daysVal);

        List<GeminiMarketHint> all = geminiMarketHintRepository
                .findTop50ByOrderByCreatedAtDesc()
                .stream()
                .filter(h -> h.getCreatedAt().isAfter(since))
                .filter(h -> regimeFilter == null || regimeFilter.isBlank()
                        || regimeFilter.equalsIgnoreCase(h.getRegime()))
                .toList();

        if (all.isEmpty()) {
            return String.format("過去 %d 天無 hint 紀錄(advisor 可能未啟用或未跑過)。", daysVal);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Gemini Market Advisor 最近 %d 天 hints (%d 筆) ===%n%n",
                daysVal, all.size()));

        // 分類統計
        java.util.Map<String, Long> styleCount = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        GeminiMarketHint::getStyleHint, java.util.stream.Collectors.counting()));
        java.util.Map<String, Long> regimeCount = all.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        GeminiMarketHint::getRegime, java.util.stream.Collectors.counting()));

        sb.append("📊 Style 分布:\n");
        styleCount.forEach((k, v) -> sb.append(String.format("  %-15s: %d%n", k, v)));
        sb.append("\n📊 Regime 分布:\n");
        regimeCount.forEach((k, v) -> sb.append(String.format("  %-15s: %d%n", k, v)));

        // 平均 confidence
        double avgConf = all.stream()
                .mapToDouble(h -> h.getConfidence().doubleValue()).average().orElse(0);
        long highConf = all.stream()
                .filter(h -> h.getConfidence().doubleValue() >= 0.5).count();
        sb.append(String.format("%n🎯 平均 confidence: %.2f | conf ≥ 0.5: %d/%d (%.1f%%)%n",
                avgConf, highConf, all.size(), 100.0 * highConf / all.size()));

        String filterLabel = (regimeFilter != null && !regimeFilter.isBlank())
                ? " [regime=" + regimeFilter + "]" : "";
        sb.append(String.format("%n=== 最近 10 筆明細%s ===%n", filterLabel));
        all.stream().limit(10).forEach(h ->
                sb.append(String.format("  %s %s@%s style=%-12s regime=%-15s conf=%.2f adx%+.1f slx%.2f tpx%.2f short=%s%n",
                        h.getCreatedAt().format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")),
                        h.getSymbol(), h.getTimeframe(),
                        h.getStyleHint(), h.getRegime(),
                        h.getConfidence().doubleValue(),
                        h.getAdxAdjust().doubleValue(),
                        h.getSlMultiplier().doubleValue(),
                        h.getTpMultiplier().doubleValue(),
                        h.getAllowShort() ? "Y" : "N")));
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查看 GeminiMarketAdvisor 目前設定與最近 hint 狀態。" +
            "用於確認 scheduler opt-in 是否啟用、cron、symbols/timeframes、TTL 與最新 hint 時間。")
    public String getGeminiAdvisorStatus() {
        List<GeminiMarketHint> recent = geminiMarketHintRepository.findTop50ByOrderByCreatedAtDesc();

        StringBuilder sb = new StringBuilder();
        sb.append("=== Gemini Market Advisor Status ===\n\n");
        sb.append(String.format("scheduler enabled: %s%n", geminiAdvisorProperties.enabled()));
        sb.append(String.format("cron(UTC): %s%n", geminiAdvisorProperties.cron()));
        sb.append(String.format("symbols: %s%n", geminiAdvisorProperties.symbols()));
        sb.append(String.format("timeframes: %s%n", geminiAdvisorProperties.timeframes()));
        sb.append(String.format("hint TTL: %dh%n", geminiAdvisorProperties.hintTtlHours()));
        sb.append(String.format("request gap: %dms%n", geminiAdvisorProperties.requestGapMs()));
        sb.append(String.format("persona gap: %dms%n", geminiAdvisorProperties.personaGapMs()));
        sb.append(String.format("skip stuck: %s (minHints=%d conf>=%.2f)%n",
                geminiAdvisorProperties.skipStuckEnabled(),
                geminiAdvisorProperties.skipStuckMinHints(),
                geminiAdvisorProperties.skipStuckConfMin()));

        if (recent.isEmpty()) {
            sb.append("\nlatest hint: none\n");
            return sb.toString();
        }

        LocalDateTime newest = recent.get(0).getCreatedAt();
        long ageMinutes = ChronoUnit.MINUTES.between(newest, LocalDateTime.now());
        sb.append(String.format("%nlatest hint: %s UTC (%d min ago)%n", newest, ageMinutes));

        recent.stream()
                .limit(8)
                .forEach(h -> sb.append(String.format("  %s %-2s %-14s %-14s conf=%.2f created=%s expires=%s%n",
                        h.getSymbol(),
                        h.getTimeframe(),
                        h.getStyleHint(),
                        h.getRegime(),
                        h.getConfidence(),
                        h.getCreatedAt(),
                        h.getExpiresAt())));
        return sb.toString();
    }

    private static String accuracyGroupKey(com.agora.model.SignalOutcomeVerification v) {
        return safe(v.getDecisionLayer()) + "|" + safe(v.getDecision());
    }

    private static long countOutcome(List<com.agora.model.SignalOutcomeVerification> records, String outcome) {
        return records.stream().filter(v -> outcome.equals(v.getOutcome())).count();
    }

    private static long finalizedCount(List<com.agora.model.SignalOutcomeVerification> records) {
        return records.stream()
                .filter(v -> "CORRECT".equals(v.getOutcome()) || "WRONG".equals(v.getOutcome()))
                .count();
    }

    private static String verificationDedupeKey(com.agora.model.SignalOutcomeVerification v) {
        return String.join("|",
                safe(v.getSymbol()),
                safe(v.getIntervalCode()),
                safe(v.getSide()),
                safe(v.getDecision()),
                safe(v.getDecisionLayer()),
                decimalKey(v.getEntryPrice()),
                decimalKey(v.getSlPrice()),
                decimalKey(v.getTpPrice()),
                safe(v.getOutcome()),
                Objects.toString(v.getFinalizedAt(), ""));
    }

    private static void appendDuplicateBuckets(StringBuilder sb,
                                               List<com.agora.model.SignalOutcomeVerification> records) {
        Map<String, List<com.agora.model.SignalOutcomeVerification>> buckets = records.stream()
                .collect(Collectors.groupingBy(MarketDataMcpTools::verificationDedupeKey,
                        LinkedHashMap::new, Collectors.toList()));
        List<List<com.agora.model.SignalOutcomeVerification>> duplicates = buckets.values().stream()
                .filter(list -> list.size() > 1)
                .toList();
        if (duplicates.isEmpty()) {
            return;
        }

        sb.append("\n⚠️ Data Quality: duplicate-like signal verification buckets detected\n");
        duplicates.stream().limit(5).forEach(bucket -> {
            var first = bucket.get(0);
            String ids = bucket.stream()
                    .map(v -> String.valueOf(v.getLiveSignalId()))
                    .collect(Collectors.joining(","));
            sb.append(String.format("  - %s@%s %s/%s outcome=%s entry=%s SL=%s TP=%s finalized=%s count=%d liveSignalIds=[%s]\n",
                    safe(first.getSymbol()), safe(first.getIntervalCode()),
                    safe(first.getDecision()), safe(first.getDecisionLayer()),
                    safe(first.getOutcome()), decimalKey(first.getEntryPrice()),
                    decimalKey(first.getSlPrice()), decimalKey(first.getTpPrice()),
                    Objects.toString(first.getFinalizedAt(), "N/A"),
                    bucket.size(), ids));
        });
        if (duplicates.size() > 5) {
            sb.append(String.format("  ... %d more duplicate-like buckets omitted\n", duplicates.size() - 5));
        }
        sb.append("  interpretation: accuracy rows above collapse these buckets so duplicated verifier rows do not inflate sample size.\n\n");
    }

    private static String decimalKey(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    // ─── LLM Oracle K 線 dump ────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "為 LLM caller(Claude/Gemini)dump 最近 N 根 K 線 OHLCV + 當前 F&G,供 caller 自行推理輸出 BUY/SELL/HOLD 決策與機率。" +
            "用途:caller 接收 dump 後直接做 in-context 判斷,不依賴後端 ML 模型,跳脫 SOP_MTF_ADX schema 限制。" +
            "param: symbol(預設 BTCUSDT), intervalCode(預設 1h), lastNBars(預設 50,最多 200), source(預設 okx)")
    public String aiScoreBars(String symbol, String intervalCode, Integer lastNBars, String source) {
        String sym      = (symbol      != null && !symbol.isBlank())      ? symbol.toUpperCase().trim()       : "BTCUSDT";
        String interval = (intervalCode != null && !intervalCode.isBlank()) ? intervalCode.toLowerCase().trim() : "1h";
        String src      = (source      != null && !source.isBlank())      ? source.toLowerCase().trim()       : "okx";
        int n           = Math.min(lastNBars != null && lastNBars > 0 ? lastNBars : 50, 200);

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(0, n);
        List<MdKline> bars = klineRepository
                .findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(sym, interval, src, pageable);
        if (bars.isEmpty()) {
            return "❌ 無 K 線資料: " + sym + "@" + interval + " source=" + src
                    + "（先用 backfillOkxKlines 或 admin/market/import 補資料）";
        }
        java.util.Collections.reverse(bars); // 升序展示,rightmost = latest

        StringBuilder sb = new StringBuilder();
        sb.append("===== K 線 dump for LLM oracle =====\n");
        sb.append("Symbol: ").append(sym).append("  Interval: ").append(interval)
          .append("  Source: ").append(src).append("  Bars: ").append(bars.size())
          .append(" (rightmost=latest)\n\n");
        sb.append(" idx | openTime            |    open |    high |     low |   close |   volume\n");
        sb.append("-----+---------------------+---------+---------+---------+---------+---------\n");
        int idx = -bars.size() + 1;
        for (MdKline k : bars) {
            sb.append(String.format("%4d | %-19s | %7.2f | %7.2f | %7.2f | %7.2f | %8.3f%n",
                    idx++,
                    k.getOpenTime().toString(),
                    k.getOpenPrice().doubleValue(),
                    k.getHighPrice().doubleValue(),
                    k.getLowPrice().doubleValue(),
                    k.getClosePrice().doubleValue(),
                    k.getVolume().doubleValue()));
        }

        sb.append("\n===== Current market sentiment =====\n");
        try {
            int fg = fearGreedService.getFearGreedValue();
            sb.append("Fear&Greed: ").append(fg).append(" (").append(fgBin(fg)).append(")\n");
        } catch (Exception e) {
            sb.append("Fear&Greed: unavailable\n");
        }
        sb.append("(funding rate / whale ratio 可另呼叫 getFundingRateHistory / getMarketSentiment)\n");

        sb.append("\n===== Caller instructions =====\n");
        sb.append("基於以上 ").append(bars.size()).append(" 根 K 線(rightmost=最新)與當前 sentiment,");
        sb.append("以 LLM in-context 推理輸出建議,格式:\n\n");
        sb.append("  Direction: LONG / SHORT / HOLD\n");
        sb.append("  Probability: 0.00-1.00\n");
        sb.append("  Reasoning: <50-100 字解釋形態識別與關鍵 context>\n");
        sb.append("  Suggested SL%: <例如 0.02 = 2%>\n");
        sb.append("  Suggested TP%: <例如 0.05 = 5%>\n\n");
        sb.append("此 tool 不替你決策,只提供 raw data + 推理框架。最終決策權與風險在 caller。");
        return sb.toString();
    }

    // getIndicatorHistory, getIndicatorGaps, getIndicatorCoverage,
    // getCollectionFreshness, getBackfillStatus, getIndicatorAnomalies
    // → all moved to IndicatorMcpTools (#248)


    // ─── WS subscription resync (V14 dynamic subscription) ────────────────────

    @com.agora.mcp.auth.McpAuth(com.agora.mcp.auth.McpAuthLevel.OPS)
    @com.agora.mcp.auth.McpCategory({com.agora.mcp.auth.Category.DIAGNOSTIC})
    @Tool(description = "手動觸發 WS 訂閱 resync。掃 enabled bt_strategy + bt_grid,"
            + "對比當前 WS 訂閱,補齊缺的 / 移除多餘的。正常情況 StrategyEnabledEvent"
            + "會自動觸發,此工具供除錯或 yaml ↔ DB 差異對帳用。"
            + "回傳格式: 'trigger=manual desired=N current=N added=N removed=N'")
    public String reloadWsSubscriptions() {
        try {
            return "✅ " + wsSubscriptionSyncer.manualResync();
        } catch (Exception e) {
            return "❌ resync failed: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "從 Polymarket CLOB API 匯入歷史賠率時間序列（每小時），"
            + "自動對齊 BTC 1h K 線計算 1h/4h/24h 後的 BTC 漲跌幅作為訓練標籤。"
            + "首次執行約 5-15 分鐘（市場數量 × API 限速），之後增量跑只補缺口。"
            + "完成後 runPolymarketBacktest 即可分析相關性。")
    public String importPolymarketHistory() {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("importPolymarketHistory",
                    "read Polymarket CLOB history and write historical odds rows");
        }
        try {
            log.info("[MCP] importPolymarketHistory triggered");
            PolymarketHistoricalImportService.ImportResult result =
                    polymarketHistoricalImportService.importAll();
            StringBuilder sb = new StringBuilder("=== Polymarket 歷史匯入完成 ===\n\n");
            sb.append("市場數: ").append(result.marketsFound()).append("\n");
            sb.append("新增行: ").append(result.rowsSaved()).append("\n");
            sb.append("跳過行: ").append(result.rowsSkipped()).append("（已存在）\n");
            if (!result.errors().isEmpty()) {
                sb.append("錯誤 ").append(result.errors().size()).append(" 個:\n");
                result.errors().forEach(e -> sb.append("  - ").append(e).append("\n"));
            }
            sb.append("\n現有資料分佈:\n");
            polymarketHistoricalOddsRepository.countByMarket().forEach(row ->
                    sb.append("  ").append(row[0]).append(": ").append(row[1]).append(" 筆\n"));
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP] importPolymarketHistory failed: {}", e.getMessage(), e);
            return "❌ 匯入失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.MARKET_DATA})
    @Tool(description = "分析 Polymarket 歷史賠率異動與 BTC 後續漲跌的相關性（含方向性分析）。"
            + "params: minOddsDeltaPct=最小賠率變動%(預設5)、days=回測天數(預設365)、"
            + "category=類別過濾(空=全部; trade-war/geopolitical/macro/crypto)。"
            + "輸出: 賠率上升 vs 下降對 BTC 影響矩陣 + 各類別方向性 + 最強信號市場 TOP5。")
    public String runPolymarketBacktest(double minOddsDeltaPct, int days, String category) {
        try {
            if (minOddsDeltaPct <= 0) minOddsDeltaPct = 5.0;
            if (days <= 0 || days > 1500) days = 365;

            BigDecimal minDelta = BigDecimal.valueOf(minOddsDeltaPct / 100.0);
            LocalDateTime since = LocalDateTime.now().minusDays(days);

            // Category filter pushed to DB (avoids loading full dataset then filtering in Java)
            String catFilter = (category != null && !category.isBlank()) ? category.trim().toLowerCase() : null;
            List<com.agora.model.PolymarketHistoricalOdds> events =
                    polymarketHistoricalOddsRepository.findSignalEventsFiltered(minDelta, since, catFilter);

            if (events.isEmpty())
                return "⚠️ 無符合條件的歷史事件。" + (catFilter != null ? "(category=" + catFilter + ") " : "")
                        + "請先執行 importPolymarketHistory。";

            // ── 方向 × Delta 矩陣 ──
            // 6 cells: 3 delta buckets × 2 directions (Rising / Falling)
            record Bucket(int count, double sumBtc4h, double sumAbsBtc4h, int btcUp) {
                Bucket add(double btc4h) {
                    return new Bucket(count + 1, sumBtc4h + btc4h,
                            sumAbsBtc4h + Math.abs(btc4h), btc4h > 0 ? btcUp + 1 : btcUp);
                }
            }
            Map<String, Bucket> matrix = new LinkedHashMap<>();
            for (String d : List.of("≥20%", "10-20%", "5-10%")) {
                matrix.put(d + "|↑Rising", new Bucket(0, 0, 0, 0));
                matrix.put(d + "|↓Falling", new Bucket(0, 0, 0, 0));
            }

            // Category × direction stats: [count, sumBtc4h]
            Map<String, double[]> catRising  = new TreeMap<>();
            Map<String, double[]> catFalling = new TreeMap<>();

            // Market-level stats: [count, sumBtc4h, sumAbsBtc4h]
            Map<String, double[]> mkStats = new LinkedHashMap<>();

            for (com.agora.model.PolymarketHistoricalOdds e : events) {
                if (e.getBtcChange4h() == null || e.getProbDelta1h() == null) continue;
                double probDelta = e.getProbDelta1h().doubleValue();
                double absDelta  = Math.abs(probDelta) * 100;
                double btc4h     = e.getBtcChange4h().doubleValue();
                String dir       = probDelta > 0 ? "↑Rising" : "↓Falling";
                String deltaKey  = absDelta >= 20 ? "≥20%" : absDelta >= 10 ? "10-20%" : "5-10%";

                // Matrix update
                String mk = deltaKey + "|" + dir;
                matrix.put(mk, matrix.getOrDefault(mk, new Bucket(0, 0, 0, 0)).add(btc4h));

                // Category update
                String cat = e.getMarketCategory() != null ? e.getMarketCategory() : "unknown";
                Map<String, double[]> catMap = probDelta > 0 ? catRising : catFalling;
                catMap.computeIfAbsent(cat, k -> new double[]{0, 0});
                catMap.get(cat)[0]++;
                catMap.get(cat)[1] += btc4h;

                // Market-level update (only when no category filter, to avoid clutter)
                if (catFilter == null) {
                    String title = e.getMarketTitle() != null ? e.getMarketTitle() : "?";
                    mkStats.computeIfAbsent(title, k -> new double[]{0, 0, 0});
                    mkStats.get(title)[0]++;
                    mkStats.get(title)[1] += btc4h;
                    mkStats.get(title)[2] += Math.abs(btc4h);
                }
            }

            StringBuilder sb = new StringBuilder("=== Polymarket × BTC 方向性回測 ===\n");
            sb.append("條件: odds_delta_1h ≥ ").append(minOddsDeltaPct).append("% | 近 ").append(days).append(" 天");
            if (catFilter != null) sb.append(" | 類別: ").append(catFilter);
            sb.append("\n有效事件: ").append(events.size()).append(" 筆\n\n");

            // ── Section 1: Direction × Delta matrix ──
            sb.append("── 方向 × Delta 矩陣（BTC 後 4h） ──\n");
            sb.append(String.format("%-28s %5s %9s %9s %8s\n", "Delta × 方向", "n", "均漲跌%", "均絕對%", "上漲率"));
            matrix.forEach((key, b) -> {
                if (b.count() == 0) return;
                String[] parts = key.split("\\|");
                sb.append(String.format("  %-26s %5d %+9.2f %9.2f %7.0f%%\n",
                        parts[0] + " " + parts[1],
                        b.count(),
                        b.sumBtc4h() / b.count(),
                        b.sumAbsBtc4h() / b.count(),
                        100.0 * b.btcUp() / b.count()));
            });

            // ── Section 2: Category × Direction ──
            sb.append("\n── 市場類別 × 方向（BTC 4h 均值） ──\n");
            Set<String> allCats = new TreeSet<>();
            allCats.addAll(catRising.keySet());
            allCats.addAll(catFalling.keySet());
            for (String cat : allCats) {
                double[] r = catRising.getOrDefault(cat, new double[]{0, 0});
                double[] f = catFalling.getOrDefault(cat, new double[]{0, 0});
                String rStr = r[0] > 0 ? String.format("↑n=%-3d avg=%+.2f%%", (int) r[0], r[1] / r[0]) : "─";
                String fStr = f[0] > 0 ? String.format("↓n=%-3d avg=%+.2f%%", (int) f[0], f[1] / f[0]) : "─";
                sb.append(String.format("  %-15s  %-22s  %s\n", cat, rStr, fStr));
            }

            // ── Section 3: Top markets by abs BTC impact (only when no category filter) ──
            if (catFilter == null) {
                sb.append("\n── 最強信號市場 TOP 5（abs avg BTC 4h，≥5 次事件） ──\n");
                mkStats.entrySet().stream()
                        .filter(en -> en.getValue()[0] >= 5)
                        .sorted((a, b) -> Double.compare(
                                b.getValue()[2] / b.getValue()[0],
                                a.getValue()[2] / a.getValue()[0]))
                        .limit(5)
                        .forEach(en -> {
                            double[] s = en.getValue();
                            String title = en.getKey().length() > 52
                                    ? en.getKey().substring(0, 49) + "..." : en.getKey();
                            sb.append(String.format("  %-52s  n=%d  avg=%+.2f%%  abs=%.2f%%\n",
                                    title, (int) s[0], s[1] / s[0], s[2] / s[0]));
                        });
            }

            sb.append("\n💡 解讀要點: 賠率上升(↑)與下降(↓)對BTC方向相反時信號最強；abs>0.5%且上漲率偏離50%是可用信號");
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP] runPolymarketBacktest failed: {}", e.getMessage(), e);
            return "❌ 回測失敗: " + e.getMessage();
        }
    }

    // ─── Exchange Rate Status ──────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查詢 USDT 匯率狀態。回傳所有支援貨幣（USD/TWD/CNY/JPY/EUR/GBP/KRW/SGD/HKD/AUD）的當前匯率、" +
            "上次更新時間，以及 AgoraMarket internal API 或 static fallback 的結果。" +
            "部署後用來驗證 exchange-rate split contract 正常運作。")
    public String getExchangeRates() {
        try {
            List<ExchangeRateInfo> rates = exchangeRateService.getAllUsdtRates();
            boolean stale = exchangeRateService.needsUpdate();

            StringBuilder sb = new StringBuilder();
            sb.append("── USDT 匯率狀態 ──\n\n");

            if (rates.isEmpty()) {
                sb.append("⚠️ 匯率列表為空 — internal API 與 static fallback 都未回傳資料\n");
                return sb.toString();
            }

            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Taipei"));
            sb.append(stale ? "🔴 匯率資料需要更新\n" : "🟢 匯率資料可用\n");

            // 找最舊的 lastUpdated 作為「快取時間」
            rates.stream()
                    .map(ExchangeRateInfo::getLastUpdated)
                    .min(LocalDateTime::compareTo)
                    .ifPresent(t -> {
                        long secs = java.time.Duration.between(t, LocalDateTime.now()).getSeconds();
                        sb.append(String.format("更新時間: %s（%d 秒前）\n",
                                t.format(DateTimeFormatter.ofPattern("HH:mm:ss")), secs));
                    });

            sb.append(String.format("幣種數量: %d / 10\n\n", rates.size()));
            sb.append(String.format("%-6s  %-12s  %s\n", "幣種", "匯率（1 USDT）", "貨幣名稱"));
            sb.append("─".repeat(40)).append("\n");

            for (ExchangeRateInfo r : rates) {
                sb.append(String.format("%-6s  %-12s  %s\n",
                        r.getToCurrency(),
                        r.getRate().setScale(4, RoundingMode.HALF_UP).toPlainString(),
                        r.getCurrencyName() != null ? r.getCurrencyName() : ""));
            }

            if (rates.size() < 10) {
                sb.append("\n⚠️ 部分幣種缺失，請檢查 AgoraMarket internal API 或 static fallback 設定");
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP] getExchangeRates failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── FRED U.S. Macro Indicators ───────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查詢 FRED(St. Louis Fed)四個 U.S. 宏觀指標當前值:" +
            "10Y Treasury yield(DGS10)、Fed Funds Rate(DFF)、Trade-Weighted USD Index(DTWEXBGS)、" +
            "10Y Breakeven Inflation(T10YIE)。FRED 多數 series 為 daily(週末/假日無新值);" +
            "本 service 30min in-memory cache。回傳 null 代表 API key 未設定或當日值未發布。" +
            "也順帶顯示 market_indicator_history 表內最近一次每個 indicator 的寫入時間,確認 hourly collector 正常。")
    public String getMacroIndicators() {
        try {
            Map<String, Double> live = fredEconomicService.getAllMacroIndicators();

            StringBuilder sb = new StringBuilder();
            sb.append("── FRED U.S. Macro Indicators ──\n\n");
            sb.append(String.format("%-22s  %-12s  %s%n", "Indicator", "Value", "DB Last Captured (UTC)"));
            sb.append("─".repeat(70)).append('\n');

            String[][] rows = {
                    {"us_10y_yield",      "10Y Treasury yield(%)"},
                    {"us_fed_funds_rate", "Fed Funds Rate(%)"},
                    {"us_dxy",            "Trade-Weighted USD"},
                    {"us_breakeven_10y",  "10Y Breakeven Inflation(%)"},
            };
            for (String[] r : rows) {
                String key = r[0];
                String label = r[1];
                Double v = live.get(key);
                String valStr = (v == null) ? "null" : String.format("%.4f", v);

                String capturedStr = "—";
                List<MarketIndicatorHistory> recent = indicatorHistoryRepository
                        .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                                "BTCUSDT", key, LocalDateTime.now(ZoneOffset.UTC).minusDays(2));
                if (recent != null && !recent.isEmpty()) {
                    LocalDateTime last = recent.get(recent.size() - 1).getCapturedAt();
                    capturedStr = last.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                }
                sb.append(String.format("%-22s  %-12s  %s%n", key, valStr, capturedStr));
                sb.append(String.format("  ↳ %s%n", label));
            }

            long nullCount = live.values().stream().filter(java.util.Objects::isNull).count();
            if (nullCount == live.size()) {
                sb.append("\n🔴 所有值皆 null — 多半是 FRED_API_KEY 未配置或 FRED API 失敗。");
            } else if (nullCount > 0) {
                sb.append(String.format("%n⚠️ %d/%d 個指標當前無值(可能週末/假日未發布)。", nullCount, live.size()));
            } else {
                sb.append("\n🟢 所有指標皆有效。");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP] getMacroIndicators failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── Etherscan On-chain Indicators ─────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查詢 Etherscan(Ethereum mainnet)on-chain 指標當前值:" +
            "USDT supply、USDC supply、合計 stablecoin supply(billions)、ETH 提議 gas(gwei)。" +
            "穩定幣 supply 變化是 fiat-to-crypto liquidity onboarding 的領先指標 — " +
            "Tether/Circle 大規模增發通常先於資金湧入交易所。collector 寫表時自動算出 24h 變化%。" +
            "也順帶顯示 market_indicator_history 內最近一次寫入時間。")
    public String getOnchainIndicators() {
        try {
            Map<String, Double> live = etherscanService.getAllOnchainIndicators();

            StringBuilder sb = new StringBuilder();
            sb.append("── Etherscan On-chain Indicators (Ethereum mainnet) ──\n\n");
            sb.append(String.format("%-26s  %-14s  %s%n", "Indicator", "Value", "DB Last Captured (UTC)"));
            sb.append("─".repeat(75)).append('\n');

            String[][] rows = {
                    {"usdt_supply_b",                  "USDT supply(billions)"},
                    {"usdc_supply_b",                  "USDC supply(billions)"},
                    {"stablecoin_supply_b",            "Stablecoin total(USDT+USDC)"},
                    {"stablecoin_supply_change_pct_24h", "24h delta % (collector-derived)"},
                    {"eth_gas_gwei",                   "ETH propose gas(gwei)"},
            };
            for (String[] r : rows) {
                String key = r[0];
                String label = r[1];
                Double v = live.get(key);  // change_pct not in live map; falls through to DB
                String valStr = (v == null) ? "—" : String.format("%.4f", v);

                String capturedStr = "—";
                Double dbValue = null;
                List<MarketIndicatorHistory> recent = indicatorHistoryRepository
                        .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                                "BTCUSDT", key, LocalDateTime.now(ZoneOffset.UTC).minusHours(48));
                if (recent != null && !recent.isEmpty()) {
                    MarketIndicatorHistory last = recent.get(recent.size() - 1);
                    capturedStr = last.getCapturedAt().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
                    if (last.getValue() != null) dbValue = last.getValue().doubleValue();
                }
                if (v == null && dbValue != null) {
                    valStr = String.format("%.4f", dbValue) + " (db)";
                }
                sb.append(String.format("%-26s  %-14s  %s%n", key, valStr, capturedStr));
                sb.append(String.format("  ↳ %s%n", label));
            }

            // Live-only null count (excludes change_pct which is collector-derived)
            long liveNullCount = live.values().stream().filter(java.util.Objects::isNull).count();
            if (liveNullCount == live.size()) {
                sb.append("\n🔴 所有 live 值皆 null — 多半是 ETHERSCAN_API_KEY 未配置或 API 失敗。");
            } else if (liveNullCount > 0) {
                sb.append(String.format("%n⚠️ %d/%d 個 live 指標無值。", liveNullCount, live.size()));
            } else {
                sb.append("\n🟢 所有 live 指標皆有效。");
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("[MCP] getOnchainIndicators failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── Bitcoin Network (mempool.space, no key) ──────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查詢 mempool.space BTC 網路指標當前值:" +
            "未確認 tx 數、mempool vsize MB、推薦快速 fee(sat/vB)、network hashrate(EH/s)。" +
            "高 mempool/fee = 鏈上需求活躍;hashrate 是長線礦工信心指標。完全免費無 key。" +
            "也順帶顯示 DB 內最近一次寫入時間。")
    public String getBitcoinNetworkIndicators() {
        try {
            Map<String, Double> live = mempoolSpaceService.getAllNetworkIndicators();
            return formatLiveAndDb(live, "mempool.space BTC Network",
                    new String[][] {
                            {"btc_mempool_count",     "Unconfirmed tx count"},
                            {"btc_mempool_vsize_mb",  "Mempool vsize(MB)"},
                            {"btc_fast_fee_sat_vb",   "Fast fee(sat/vB)"},
                            {"btc_hashrate_eh",       "Network hashrate(EH/s)"},
                    },
                    "%-22s  %-14s  %s%n", 70);
        } catch (Exception e) {
            log.error("[MCP] getBitcoinNetworkIndicators failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── DeFi breadth (DefiLlama, no key) ─────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA, Category.DIAGNOSTIC})
    @Tool(description = "查詢 DefiLlama 跨鏈 DeFi 廣度指標:" +
            "全 DeFi 協議 TVL 總和(billions USD)、跨鏈 USD-pegged 穩定幣總市值(billions USD)。" +
            "互補 V074 Etherscan-only 指標 — DefiLlama 涵蓋 Tron/Solana/BSC 等所有鏈,反映真實全球" +
            "穩定幣『法幣浮動量』。完全免費無 key。")
    public String getDefiBreadthIndicators() {
        try {
            Map<String, Double> live = defiLlamaService.getAllDefiBreadthIndicators();
            return formatLiveAndDb(live, "DefiLlama Cross-chain DeFi Breadth",
                    new String[][] {
                            {"defi_tvl_total_b",         "Total DeFi TVL(B USD)"},
                            {"stablecoin_total_mcap_b",  "All-chain stablecoin mcap(B USD)"},
                    },
                    "%-26s  %-14s  %s%n", 70);
        } catch (Exception e) {
            log.error("[MCP] getDefiBreadthIndicators failed: {}", e.getMessage(), e);
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    // ─── shared formatter for V075 indicator dashboards ──────────────────────

    /**
     * Helper for V075+ indicator MCP tools — print live values alongside the
     * DB last-captured timestamp so callers can validate the hourly collector
     * is actually persisting rows.
     */
    private String formatLiveAndDb(Map<String, Double> live, String title,
                                   String[][] keysAndLabels,
                                   String rowFormat, int divLen) {
        StringBuilder sb = new StringBuilder();
        sb.append("── ").append(title).append(" ──\n\n");
        sb.append(String.format(rowFormat, "Indicator", "Value", "DB Last Captured (UTC)"));
        sb.append("─".repeat(divLen)).append('\n');

        for (String[] r : keysAndLabels) {
            String key = r[0];
            String label = r[1];
            Double v = live.get(key);
            String valStr = (v == null) ? "—" : String.format("%.4f", v);

            String capturedStr = "—";
            List<MarketIndicatorHistory> recent = indicatorHistoryRepository
                    .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                            "BTCUSDT", key, LocalDateTime.now(ZoneOffset.UTC).minusHours(48));
            if (recent != null && !recent.isEmpty()) {
                LocalDateTime last = recent.get(recent.size() - 1).getCapturedAt();
                capturedStr = last.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"));
            }
            sb.append(String.format(rowFormat, key, valStr, capturedStr));
            sb.append(String.format("  ↳ %s%n", label));
        }

        long nullCount = live.values().stream().filter(java.util.Objects::isNull).count();
        if (nullCount == live.size()) {
            sb.append("\n🔴 所有 live 值皆 null — API 失敗或上游故障。");
        } else if (nullCount > 0) {
            sb.append(String.format("%n⚠️ %d/%d 個 live 指標無值。", nullCount, live.size()));
        } else {
            sb.append("\n🟢 所有 live 指標皆有效。");
        }
        return sb.toString();
    }

    // ─── Coinalyze 清算回填 ────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.MARKET_DATA})
    @Tool(description = "回填 Coinalyze BTC 清算歷史至 market_indicator_history（V083）。" +
            "存入三個指標：btc_short_liq_usd_1h、btc_long_liq_usd_1h、btc_short_liq_ratio_1h。" +
            "清算值（BTC 數量）× 當前 BTC 價格轉 USD。" +
            "使用場景：系統剛部署 Coinalyze 整合時回補歷史資料，供 simulateAttentionRulesOnHistory 回測。" +
            "params: days=回溯天數（預設 30，最多 90）")
    public String backfillCoinalyzeLiquidation(Integer days) {
        if (!externalBackfillsEnabled) {
            return disabledExternalBackfillMessage("backfillCoinalyzeLiquidation",
                    "read Coinalyze liquidation history and write market_indicator_history");
        }
        int d = days != null ? Math.min(Math.max(days, 1), 90) : 30;
        String sym = "BTCUSDT";

        if (coinalyzeApiKey == null || coinalyzeApiKey.isBlank()) {
            return "❌ Coinalyze API key 未設定（application.yml trading.market-data.coinalyze.api-key）";
        }

        // Get current BTC price for BTC→USD conversion
        Double btcPx = null;
        try {
            java.math.BigDecimal px = okxTradingService.getLastPrice(sym);
            btcPx = px != null ? px.doubleValue() : null;
        } catch (Exception ignored) {}
        final double finalBtcPx = btcPx != null ? btcPx : 77000.0; // fallback

        LocalDateTime now   = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime since = now.minusDays(d);

        try {
            long fromEpoch = since.toEpochSecond(ZoneOffset.UTC);
            long toEpoch   = now.toEpochSecond(ZoneOffset.UTC);

            String configuredKey = coinalyzeApiKey;

            if (configuredKey == null || configuredKey.isBlank()) {
                return "❌ Coinalyze API key 未設定（application.yml trading.market-data.coinalyze.api-key）";
            }

            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build();

            String url = "https://api.coinalyze.net/v1/liquidation-history?symbols=BTCUSDT_PERP.A"
                    + "&interval=1hour&from=" + fromEpoch + "&to=" + toEpoch;

            okhttp3.Request req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("api-key", configuredKey)
                    .header("Accept", "application/json")
                    .build();

            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            int written = 0, skipped = 0;

            try (okhttp3.Response resp = client.newCall(req).execute()) {
                if (!resp.isSuccessful()) {
                    return String.format("❌ Coinalyze API HTTP %d: %s",
                            resp.code(), resp.body() != null ? resp.body().string() : "");
                }
                com.fasterxml.jackson.databind.JsonNode root =
                        om.readTree(resp.body() != null ? resp.body().string() : "[]");
                if (!root.isArray() || root.isEmpty()) return "⚠️ Coinalyze 回傳空資料";

                com.fasterxml.jackson.databind.JsonNode history = root.get(0).path("history");
                for (com.fasterxml.jackson.databind.JsonNode bar : history) {
                    long   epochSec   = bar.path("t").asLong();
                    double longLiqBtc = bar.path("l").asDouble(0);
                    double srtLiqBtc  = bar.path("s").asDouble(0);
                    double longLiqUsd = longLiqBtc * finalBtcPx;
                    double shortLiqUsd = srtLiqBtc * finalBtcPx;
                    double total = longLiqUsd + shortLiqUsd;
                    double ratio = total > 0 ? shortLiqUsd / total : 0.5;

                    LocalDateTime ts = java.time.Instant.ofEpochSecond(epochSec)
                            .atZone(ZoneOffset.UTC).toLocalDateTime();

                    // Skip if already exists
                    boolean exists = !indicatorHistoryRepository
                            .findBySymbolAndIndicatorAndCapturedAtAfterOrderByCapturedAtAsc(
                                    sym, "btc_short_liq_usd_1h", ts.minusMinutes(5))
                            .stream()
                            .anyMatch(h -> h.getCapturedAt().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                                    .equals(ts.truncatedTo(java.time.temporal.ChronoUnit.HOURS)));
                    if (exists) { skipped++; continue; }

                    // Write all 3 indicators
                    saveIndicator(sym, "btc_long_liq_usd_1h",  longLiqUsd,  ts);
                    saveIndicator(sym, "btc_short_liq_usd_1h", shortLiqUsd, ts);
                    saveIndicator(sym, "btc_short_liq_ratio_1h", ratio,     ts);
                    written += 3;
                }
            }
            return String.format("✅ Coinalyze 清算回填完成\n回溯 %d 天 | 寫入 %d 筆 | 跳過 %d 筆（已存在）\n" +
                    "指標：btc_short_liq_usd_1h / btc_long_liq_usd_1h / btc_short_liq_ratio_1h\n" +
                    "BTC 換算價: $%.0f", d, written, skipped, finalBtcPx);

        } catch (Exception e) {
            return "❌ 回填失敗: " + e.getMessage();
        }
    }

    /** Helper: save a single market_indicator_history row. */
    private void saveIndicator(String symbol, String indicator, double value, LocalDateTime capturedAt) {
        com.agora.model.MarketIndicatorHistory h = new com.agora.model.MarketIndicatorHistory();
        h.setSymbol(symbol);
        h.setIndicator(indicator);
        h.setValue(java.math.BigDecimal.valueOf(value));
        h.setCapturedAt(capturedAt);
        indicatorHistoryRepository.save(h);
    }
}
