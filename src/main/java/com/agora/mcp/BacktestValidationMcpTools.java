package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.dto.backtest.AiStrategyDiscoveryRequest;
import com.agora.dto.backtest.AiStrategyDiscoveryResponse;
import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.GeminiMarketHintRepository;
import com.agora.service.BacktestService;
import com.agora.service.BtStrategyService;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.BacktestQualityValidator;
import com.agora.service.backtest.BacktestTradeValidator;
import com.agora.service.backtest.LiveSignalContext;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyContext;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.StrategySignal;
import com.agora.service.backtest.TradeRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 回測驗證工具集。
 * 提供單策略回測、AI 探勘(自動/自適應)、外部候選參數驗證、交易記錄自洽性驗證、
 * 啟用後策略實戰巡檢等功能。
 *
 * 與 StrategyManagementMcpTools 區隔:此類別專注「回測生成/驗證資料」,
 * 不含啟停閘道(閘道實作在 StrategyManagementMcpTools.setStrategyEnabled)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestValidationMcpTools {

    private final BacktestService backtestService;
    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final BacktestTradeValidator backtestTradeValidator;
    private final GeminiMarketHintRepository geminiMarketHintRepository;
    private final com.agora.service.BtStrategyService btStrategyService;
    private final ObjectMapper objectMapper;
    private final com.agora.repository.trading.BtBacktestResultRepository btBacktestResultRepo;
    private final com.agora.repository.trading.BtBacktestTradeRepository btBacktestTradeRepo;
    private final com.agora.repository.trading.BtDecisionAuditRepository decisionAuditRepo;
    private final com.agora.repository.trading.MdKlineRepository klineRepo;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "對指定策略執行回測（即使策略停用也可執行）。" +
            "params: strategyId=策略ID, symbol=交易對(BTCUSDT/ETHUSDT), " +
            "intervalCode=K線週期(15m/1h/4h/1d), days=回測天數(例如180), " +
            "applyFilters=true 時套用歷史 F&G / 事件日曆 / 資金費率過濾層（預設 false）, " +
            "source=K 線資料源 binance 或 okx（留白時使用策略 klineSource；僅研究對照時覆寫）," +
            "configOverrideJson=臨時覆蓋 config 參數（不改 DB！），JSON 字串，例如 " +
            "{\"buyThreshold\":25,\"requireFundingImprovingBars\":48}。可帶 {\"skipPersist\":true} 避免寫入回測結果表。")
    public String runBacktest(Long strategyId, String symbol, String intervalCode, Integer days,
                              Boolean applyFilters, String source, String configOverrideJson) {
        int daysVal = (days != null) ? days : 365;
        boolean applyF = Boolean.TRUE.equals(applyFilters);
        String src = (source == null || source.isBlank()) ? null : source.toLowerCase();
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        BacktestRunRequest req = new BacktestRunRequest();
        req.setStrategyId(strategyId);
        req.setSymbol(symbol.toUpperCase());
        req.setIntervalCode(intervalCode.toLowerCase());
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setInitialCapital(new BigDecimal("10000"));
        req.setFeeRate(new BigDecimal("0.001"));
        req.setApplyFilters(applyF);
        req.setSource(src);
        if (configOverrideJson != null && !configOverrideJson.isBlank()) {
            try {
                Map<String, Object> override = objectMapper.readValue(configOverrideJson,
                        new TypeReference<Map<String, Object>>() {});
                // Special-case engine-level overrides: extract from map before passing as strategy config.
                Object capOverride = override.remove("initialCapital");
                if (capOverride != null) {
                    req.setInitialCapital(new BigDecimal(String.valueOf(capOverride)));
                }
                Object feeOverride = override.remove("feeRate");
                if (feeOverride != null) {
                    req.setFeeRate(new BigDecimal(String.valueOf(feeOverride)));
                }
                Object skipPersistOverride = override.remove("skipPersist");
                if (skipPersistOverride != null) {
                    req.setSkipPersist(Boolean.parseBoolean(String.valueOf(skipPersistOverride)));
                }
                req.setConfigOverride(override);
            } catch (Exception e) {
                return "❌ configOverrideJson 格式錯誤: " + e.getMessage();
            }
        }

        log.info("[MCP] run_backtest strategyId={} symbol={} interval={} days={} applyFilters={} source={}",
                strategyId, symbol, intervalCode, daysVal, applyF, src == null ? "<strategy>" : src);

        BacktestResultResponse r = backtestService.runForExploration(req);

        int    tc  = r.getTradeCount()  != null ? r.getTradeCount()                : 0;
        double ret = r.getTotalReturn() != null ? r.getTotalReturn().doubleValue() : 0.0;
        double dd  = r.getMaxDrawdown() != null ? r.getMaxDrawdown().doubleValue() : 1.0;

        // #306 按策略類型分級門檻
        String strategyType = null;
        try { strategyType = btStrategyService.getStrategy(strategyId).getStrategyType(); } catch (Exception ignored) {}
        int minTc = BacktestQualityValidator.minTradeCount(strategyType);
        String qTc  = tc  >= minTc                                       ? "✅" : "❌";
        String qRet = ret >  0                                           ? "✅" : "❌";
        String qDd  = dd  <= BtStrategyService.QUALITY_MAX_DRAWDOWN     ? "✅" : "❌";
        boolean passAll = BacktestQualityValidator.passes(tc, ret, dd, strategyType);
        String verdict = passAll
                ? "✅ 品質門檻全部通過，可執行 enableStrategy(strategyId=" + strategyId + ")"
                : "❌ 尚未通過品質門檻，請調整參數或延長回測期間後重新執行";

        String filterLine = "";
        if (applyF) {
            int fc = r.getFilteredEntryCount() != null ? r.getFilteredEntryCount() : 0;
            filterLine = String.format("\n🛡 歷史過濾層 (applyFilters=true):\n  被過濾進場次數: %d\n", fc);
        }

        String tradeSamples = formatTradeSamples(r.getTrades());

        return String.format(
                "=== 回測結果 ===\n" +
                "策略: %s (ID=%d)\n" +
                "幣種: %s  週期: %s  天數: %d 天\n" +
                "期間: %s ~ %s\n\n" +
                "📊 績效指標:\n" +
                "  勝率:     %.1f%%\n" +
                "  總報酬率: %.2f%%\n" +
                "  最大回撤: %.2f%%\n" +
                "  夏普比率: %s\n" +
                "  交易筆數: %d 筆\n\n" +
                "💰 資金:\n" +
                "  初始資金: %s USDT\n" +
                "  最終資金: %s USDT\n\n" +
                "📈 市場背景:\n" +
                "  市場趨勢: %s\n" +
                "  市場漲跌: %.2f%%\n" +
                "%s" +
                "%s\n" +
                "🔒 啟用品質門檻:\n" +
                "  %s 交易筆數: %d 筆（需 ≥ %d）\n" +
                "  %s 總報酬:   %.2f%%（需 > 0%%）\n" +
                "  %s 最大回撤: %.1f%%（需 ≤ %.0f%%）\n\n" +
                "%s",
                r.getStrategyName(), r.getStrategyId(),
                r.getSymbol(), r.getIntervalCode(), daysVal,
                r.getStartTime(), r.getEndTime(),
                pct(r.getWinRate()), pct(r.getTotalReturn()),
                pct(r.getMaxDrawdown()),
                r.getSharpeRatio() != null ? String.format("%.3f", r.getSharpeRatio()) : "N/A",
                r.getTradeCount(),
                r.getInitialCapital(), r.getFinalCapital(),
                r.getMarketTrend(),
                pct(r.getMarketPriceChangePct()),
                tradeSamples,
                filterLine,
                qTc, tc, BtStrategyService.QUALITY_MIN_TRADE_COUNT,
                qRet, ret * 100,
                qDd, dd * 100, BtStrategyService.QUALITY_MAX_DRAWDOWN * 100,
                verdict
        );
    }

    private String formatTradeSamples(List<BacktestResultResponse.TradeRecordDto> trades) {
        if (trades == null || trades.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n🧾 交易樣本:\n");
        int limit = Math.min(trades.size(), 5);
        for (int i = 0; i < limit; i++) {
            BacktestResultResponse.TradeRecordDto t = trades.get(i);
            sb.append(String.format(
                    "  %d. entry=%s exit=%s side=%s ret=%.2f%% reason=%s label=%s tvQty=%s orderCount=%s\n",
                    i + 1,
                    t.getEntryTime(),
                    t.getExitTime(),
                    t.getSide(),
                    pct(t.getReturnPct()),
                    emptyToDash(t.getEntryReason()),
                    emptyToDash(t.getEntryLabel()),
                    t.getEntryRequestedQuantity() == null ? "-" : t.getEntryRequestedQuantity().toPlainString(),
                    t.getEntryOrderCount() == null ? "-" : t.getEntryOrderCount().toString()));
        }
        if (trades.size() > limit) {
            sb.append("  ... +").append(trades.size() - limit).append(" 筆\n");
        }
        return sb.toString();
    }

    private String emptyToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "只讀預覽 SCORE_BUY 的 TradingView 買點/訂單意圖。" +
            "逐根 K 線執行策略並列出 Pine 等價 order intent，不寫 bt_backtest_result、不下單。" +
            "params: strategyId, symbol, intervalCode(建議 1d), days, source(binance/okx), " +
            "configOverrideJson(不改 DB), limit(回傳最後 N 筆 order intent，預設 50)。")
    public String previewScoreBuyTradingViewOrders(Long strategyId, String symbol, String intervalCode,
                                                   Integer days, String source, String configOverrideJson,
                                                   Integer limit) {
        int daysVal = days != null ? days : 365;
        int limitVal = Math.max(1, Math.min(limit != null ? limit : 50, 500));
        String symbolVal = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.toUpperCase();
        String intervalVal = intervalCode == null || intervalCode.isBlank() ? "1d" : intervalCode.toLowerCase();

        BtStrategy strategyEntity = btStrategyService.getRequired(strategyId);
        if (!isScoreBuy(strategyEntity.getStrategyType())) {
            return "❌ strategyId=" + strategyId + " 不是 SCORE_BUY 類型，實際類型=" + strategyEntity.getStrategyType();
        }

        String src = resolvePreviewSource(source, strategyEntity.getKlineSource());
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime visibleStart = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);
        LocalDateTime queryStart = visibleStart.minusDays(warmupDays(intervalVal));

        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                symbolVal, intervalVal, src, queryStart, endTime);
        if (klines.isEmpty()) {
            return String.format("❌ 查無 K 線: symbol=%s interval=%s source=%s range=%s~%s",
                    symbolVal, intervalVal, src, queryStart, endTime);
        }

        Map<String, Object> config = new HashMap<String, Object>(btStrategyService.parseConfig(strategyEntity.getConfigJson()));
        if (configOverrideJson != null && !configOverrideJson.isBlank()) {
            try {
                Map<String, Object> override = objectMapper.readValue(configOverrideJson,
                        new TypeReference<Map<String, Object>>() {});
                override.remove("initialCapital");
                override.remove("feeRate");
                override.remove("skipPersist");
                config.putAll(override);
            } catch (Exception e) {
                return "❌ configOverrideJson 格式錯誤: " + e.getMessage();
            }
        }
        config.put("runIntervalCode", intervalVal);

        Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
        strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
        Map<String, double[]> indicators = backtestEngine.buildIndicators(klines, config);

        List<String> rows = new ArrayList<String>();
        int orderCount = 0;
        int orderBarCount = 0;
        LocalDateTime firstOrderAt = null;
        LocalDateTime lastOrderAt = null;

        for (int i = 0; i < klines.size(); i++) {
            MdKline current = klines.get(i);
            MdKline previous = i > 0 ? klines.get(i - 1) : null;
            StrategyContext context = new StrategyContext(i, current, previous, klines, indicators);

            LiveSignalContext.clear();
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> intents = LiveSignalContext.getOrderIntents();
            if (current.getOpenTime().isBefore(visibleStart) || intents.isEmpty()) {
                continue;
            }

            orderBarCount++;
            if (firstOrderAt == null) {
                firstOrderAt = current.getOpenTime();
            }
            lastOrderAt = current.getOpenTime();
            Map<String, Object> details = LiveSignalContext.getDetails();
            for (LiveSignalContext.OrderIntent intent : intents) {
                orderCount++;
                rows.add(String.format(
                        "%s close=%s signal=%s reason=%s qty=%.0f label=%s nn=%s rsi=%s buySignal=%s",
                        current.getOpenTime(),
                        current.getClosePrice(),
                        signal,
                        intent.reason(),
                        intent.quantity(),
                        intent.label(),
                        detail(details, "tradingview_nn_output"),
                        detail(details, "tradingview_rsi"),
                        detail(details, "tradingview_buy_signal")));
            }
        }

        int fromIndex = Math.max(0, rows.size() - limitVal);
        List<String> tailRows = rows.subList(fromIndex, rows.size());
        String coverageLine = buildTradingViewDataCoverageLine(klines, visibleStart, endTime);
        return String.format(
                "=== SCORE_BUY TradingView order-intent preview ===\n" +
                "strategyId=%d symbol=%s interval=%s source=%s days=%d queryBars=%d visibleStart=%s\n" +
                "%s\n" +
                "orderBars=%d orderIntents=%d firstOrderAt=%s lastOrderAt=%s\n" +
                "note=此工具只比對 TradingView 買點/訂單意圖；不落庫、不下單、不套用資金/倉位/SLTP 模型。\n\n%s",
                strategyId, symbolVal, intervalVal, src, daysVal, klines.size(), visibleStart,
                coverageLine,
                orderBarCount, orderCount, firstOrderAt, lastOrderAt,
                String.join("\n", tailRows));
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "只讀執行 SCORE_BUY 的 TradingView parity mark-to-market 回測。" +
            "按 Pine order intent 逐筆建倉、允許 pyramiding、持有到回測期末，不套用本地單倉/SL/TP/資金模型。" +
            "qty 以 USDT notional 解讀，用於修正 TradingView 報表 INVALID DATA 下的可比績效。")
    public String runScoreBuyTradingViewParityBacktest(Long strategyId, String symbol, String intervalCode,
                                                       Integer days, String source, String configOverrideJson,
                                                       Double feeRate, Integer limit) {
        int daysVal = days != null ? days : 365;
        int limitVal = Math.max(1, Math.min(limit != null ? limit : 50, 500));
        double fee = feeRate != null ? Math.max(0.0, feeRate) : 0.001;
        String symbolVal = symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.toUpperCase();
        String intervalVal = intervalCode == null || intervalCode.isBlank() ? "1d" : intervalCode.toLowerCase();

        BtStrategy strategyEntity = btStrategyService.getRequired(strategyId);
        if (!isScoreBuy(strategyEntity.getStrategyType())) {
            return "❌ strategyId=" + strategyId + " 不是 SCORE_BUY 類型，實際類型=" + strategyEntity.getStrategyType();
        }

        String src = resolvePreviewSource(source, strategyEntity.getKlineSource());
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime visibleStart = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);
        LocalDateTime queryStart = visibleStart.minusDays(warmupDays(intervalVal));

        List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                symbolVal, intervalVal, src, queryStart, endTime);
        if (klines.isEmpty()) {
            return String.format("❌ 查無 K 線: symbol=%s interval=%s source=%s range=%s~%s",
                    symbolVal, intervalVal, src, queryStart, endTime);
        }

        Map<String, Object> config = new HashMap<String, Object>(btStrategyService.parseConfig(strategyEntity.getConfigJson()));
        if (configOverrideJson != null && !configOverrideJson.isBlank()) {
            try {
                Map<String, Object> override = objectMapper.readValue(configOverrideJson,
                        new TypeReference<Map<String, Object>>() {});
                override.remove("initialCapital");
                override.remove("feeRate");
                override.remove("skipPersist");
                config.putAll(override);
            } catch (Exception e) {
                return "❌ configOverrideJson 格式錯誤: " + e.getMessage();
            }
        }
        config.put("runIntervalCode", intervalVal);

        Strategy strategy = strategyRegistry.getRequiredStrategy(strategyEntity.getStrategyType());
        strategy.defaultExecutionConfig().forEach(config::putIfAbsent);
        Map<String, double[]> indicators = backtestEngine.buildIndicators(klines, config);

        List<TvLot> lots = new ArrayList<TvLot>();
        List<String> orderRows = new ArrayList<String>();
        int orderBarCount = 0;
        LocalDateTime firstOrderAt = null;
        LocalDateTime lastOrderAt = null;

        for (int i = 0; i < klines.size(); i++) {
            MdKline current = klines.get(i);
            MdKline previous = i > 0 ? klines.get(i - 1) : null;
            StrategyContext context = new StrategyContext(i, current, previous, klines, indicators);

            LiveSignalContext.clear();
            StrategySignal signal = strategy.evaluate(context, config);
            List<LiveSignalContext.OrderIntent> intents = LiveSignalContext.getOrderIntents();
            if (current.getOpenTime().isBefore(visibleStart) || intents.isEmpty()) {
                continue;
            }

            orderBarCount++;
            if (firstOrderAt == null) {
                firstOrderAt = current.getOpenTime();
            }
            lastOrderAt = current.getOpenTime();
            double entryPrice = current.getClosePrice().doubleValue();
            Map<String, Object> details = LiveSignalContext.getDetails();
            for (LiveSignalContext.OrderIntent intent : intents) {
                double notional = Math.max(0.0, intent.quantity());
                if (notional <= 0.0 || entryPrice <= 0.0) {
                    continue;
                }
                TvLot lot = new TvLot(current.getOpenTime(), entryPrice, notional,
                        intent.reason(), intent.label(), signal.name(),
                        detail(details, "tradingview_nn_output"),
                        detail(details, "tradingview_rsi"));
                lots.add(lot);
                orderRows.add(String.format(Locale.ROOT,
                        "%s entry=%s notional=%.2f reason=%s label=%s signal=%s nn=%s rsi=%s",
                        lot.entryTime(), fmt(entryPrice), notional,
                        lot.reason(), lot.label(), lot.signal(), lot.nn(), lot.rsi()));
            }
        }

        if (lots.isEmpty()) {
            String coverageLine = buildTradingViewDataCoverageLine(klines, visibleStart, endTime);
            return String.format(
                    "=== SCORE_BUY TradingView parity backtest ===\n" +
                    "strategyId=%d symbol=%s interval=%s source=%s days=%d queryBars=%d visibleStart=%s\n" +
                    "%s\n" +
                    "orderBars=0 orderIntents=0\n" +
                    "note=未產生 TradingView order intent；不寫庫、不下單。",
                    strategyId, symbolVal, intervalVal, src, daysVal, klines.size(), visibleStart,
                    coverageLine);
        }

        List<MdKline> visibleKlines = klines.stream()
                .filter(k -> !k.getOpenTime().isBefore(visibleStart))
                .toList();
        MdKline finalBar = visibleKlines.isEmpty() ? klines.get(klines.size() - 1) : visibleKlines.get(visibleKlines.size() - 1);
        double finalClose = finalBar.getClosePrice().doubleValue();
        double capitalUsed = lots.stream().mapToDouble(TvLot::notional).sum();
        double finalValue = 0.0;
        double netPnl = 0.0;
        int winningLots = 0;
        for (TvLot lot : lots) {
            double quantity = lot.notional() / lot.entryPrice();
            double exitValue = quantity * finalClose;
            double entryFee = lot.notional() * fee;
            double exitFee = exitValue * fee;
            double lotNet = exitValue - lot.notional() - entryFee - exitFee;
            finalValue += exitValue - exitFee;
            netPnl += lotNet;
            if (lotNet > 0) {
                winningLots++;
            }
        }

        double maxDrawdown = computeTvMaxDrawdown(lots, visibleKlines, fee);
        double totalReturn = capitalUsed > 0.0 ? netPnl / capitalUsed : 0.0;
        double winRate = lots.isEmpty() ? 0.0 : (double) winningLots / (double) lots.size();
        List<String> tailRows = orderRows.subList(Math.max(0, orderRows.size() - limitVal), orderRows.size());
        String coverageLine = buildTradingViewDataCoverageLine(klines, visibleStart, endTime);

        return String.format(Locale.ROOT,
                "=== SCORE_BUY TradingView parity backtest ===\n" +
                "strategyId=%d symbol=%s interval=%s source=%s days=%d queryBars=%d visibleStart=%s\n" +
                "%s\n" +
                "orderBars=%d orderIntents=%d firstOrderAt=%s lastOrderAt=%s\n" +
                "execution=TradingView parity: pyramiding=true, exit=mark_to_market_at_end, qtyAsNotionalUsdt=true, localSLTP=false, singlePosition=false\n" +
                "finalMark=%s finalClose=%s feeRate=%.4f\n\n" +
                "績效:\n" +
                "  deployedNotional: %.2f USDT\n" +
                "  finalValueAfterExitFee: %.2f USDT\n" +
                "  netPnl: %.2f USDT\n" +
                "  totalReturn: %.2f%%\n" +
                "  maxDrawdown: %.2f%%\n" +
                "  winningLots: %d/%d (%.1f%%)\n\n" +
                "note=此工具只比對 TradingView 交易語義；不寫 bt_backtest_result、不下單、不套用本地風控/品質門檻。\n\n%s",
                strategyId, symbolVal, intervalVal, src, daysVal, klines.size(), visibleStart,
                coverageLine,
                orderBarCount, lots.size(), firstOrderAt, lastOrderAt,
                finalBar.getOpenTime(), fmt(finalClose), fee,
                capitalUsed, finalValue, netPnl, totalReturn * 100.0,
                maxDrawdown * 100.0, winningLots, lots.size(), winRate * 100.0,
                String.join("\n", tailRows));
    }

    static String buildTradingViewDataCoverageLine(List<MdKline> klines, LocalDateTime visibleStart,
                                                   LocalDateTime requestedEnd) {
        if (klines == null || klines.isEmpty()) {
            return "dataStart=null dataEnd=null visibleBars=0 coverage=NO_DATA coverageWarning=NO_KLINES";
        }
        LocalDateTime dataStart = klines.get(0).getOpenTime();
        LocalDateTime dataEnd = klines.get(klines.size() - 1).getOpenTime();
        long visibleBars = klines.stream()
                .filter(k -> !k.getOpenTime().isBefore(visibleStart))
                .count();
        boolean partial = dataStart.isAfter(visibleStart);
        long missingLeadDays = partial ? ChronoUnit.DAYS.between(visibleStart, dataStart) : 0L;
        long trailingGapHours = requestedEnd != null ? Math.max(0L, ChronoUnit.HOURS.between(dataEnd, requestedEnd)) : 0L;
        String warning = partial
                ? String.format(Locale.ROOT, "REQUESTED_WINDOW_PARTIAL missingLeadDays=%d", missingLeadDays)
                : "NONE";
        return String.format(Locale.ROOT,
                "dataStart=%s dataEnd=%s visibleBars=%d coverage=%s trailingGapHours=%d coverageWarning=%s",
                dataStart, dataEnd, visibleBars, partial ? "PARTIAL" : "OK", trailingGapHours, warning);
    }

    private double computeTvMaxDrawdown(List<TvLot> lots, List<MdKline> visibleKlines, double fee) {
        if (lots.isEmpty() || visibleKlines == null || visibleKlines.isEmpty()) {
            return 0.0;
        }
        double maxEquity = 0.0;
        double maxDrawdown = 0.0;
        for (MdKline bar : visibleKlines) {
            double close = bar.getClosePrice().doubleValue();
            double deployed = 0.0;
            double equity = 0.0;
            for (TvLot lot : lots) {
                if (lot.entryTime().isAfter(bar.getOpenTime())) {
                    continue;
                }
                double quantity = lot.notional() / lot.entryPrice();
                double exitValue = quantity * close;
                deployed += lot.notional();
                equity += exitValue - exitValue * fee - lot.notional() * fee;
            }
            if (deployed <= 0.0) {
                continue;
            }
            maxEquity = Math.max(maxEquity, equity);
            if (maxEquity > 0.0) {
                maxDrawdown = Math.max(maxDrawdown, (maxEquity - equity) / maxEquity);
            }
        }
        return maxDrawdown;
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record TvLot(LocalDateTime entryTime, double entryPrice, double notional,
                         String reason, String label, String signal, String nn, String rsi) {
    }

    private boolean isScoreBuy(String strategyType) {
        return strategyType != null && strategyType.toUpperCase(Locale.ROOT).startsWith("SCORE_BUY");
    }

    private String resolvePreviewSource(String requestedSource, String strategySource) {
        if (requestedSource != null && !requestedSource.isBlank()) {
            return requestedSource.toLowerCase();
        }
        if (strategySource != null && !strategySource.isBlank()) {
            return strategySource.toLowerCase();
        }
        return "okx";
    }

    private long warmupDays(String intervalCode) {
        return switch (intervalCode.toLowerCase()) {
            case "1m", "5m", "15m" -> 30L;
            case "1h" -> 90L;
            case "4h" -> 180L;
            default -> 365L;
        };
    }

    private String detail(Map<String, Object> details, String key) {
        if (details == null) {
            return "N/A";
        }
        Object value = details.get(key);
        if (value == null) {
            return "N/A";
        }
        if (value instanceof Number number) {
            return String.format("%.4f", number.doubleValue());
        }
        return String.valueOf(value);
    }

    // ─── runBacktestSweep ────────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "批量掃描策略參數空間，自動跑所有組合的回測並返回績效矩陣。" +
            "不修改 DB，所有 configOverride 都是臨時的。最多 50 組合。" +
            "params: strategyId, symbol, intervalCode, days, source（同 runBacktest）," +
            "sweepJson=參數掃描範圍 JSON，格式 {\"param\": [val1, val2, ...], ...}。" +
            "例如 {\"buyThreshold\":[20,25,30],\"requireFundingImprovingBars\":[-1,24,48]}。" +
            "輸出：所有組合的績效表 + 最佳報酬組合標示。")
    public String runBacktestSweep(Long strategyId, String symbol, String intervalCode,
                                   Integer days, String source, String sweepJson) {
        if (sweepJson == null || sweepJson.isBlank()) return "❌ sweepJson 必填";
        Map<String, List<Object>> sweep;
        try {
            sweep = objectMapper.readValue(sweepJson, new TypeReference<Map<String, List<Object>>>() {});
        } catch (Exception e) {
            return "❌ sweepJson 格式錯誤（需 {\"param\":[v1,v2,...]}）: " + e.getMessage();
        }
        if (sweep.isEmpty()) return "❌ sweepJson 至少需要一個參數";

        // 生成 Cartesian product
        List<Map<String, Object>> combos = cartesianProduct(sweep);
        if (combos.size() > 50) return "❌ 組合數 " + combos.size() + " 超過上限 50，請縮小掃描範圍";

        int daysVal = (days != null) ? days : 90;
        String src = (source == null || source.isBlank()) ? "okx" : source.toLowerCase();
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        List<String> paramKeys = new ArrayList<>(sweep.keySet());
        // #392 Option B — when sweeping any regime-related param, auto-enable the
        // backtest's RegimeFilter helper so each combo actually gets evaluated by
        // the filter. Without this, the sweep would still be a no-op (Option C
        // warning era). Caller can override by including applyRegimeFilter in the
        // sweep itself.
        boolean autoEnableRegimeFilter = sweepContainsRegimeParam(paramKeys)
                && !paramKeys.contains("applyRegimeFilter");
        record SweepRow(Map<String, Object> params, BacktestResultResponse result) {}
        List<SweepRow> rows = new ArrayList<>();

        for (Map<String, Object> combo : combos) {
            if (autoEnableRegimeFilter) {
                combo.put("applyRegimeFilter", true);
            }
            BacktestRunRequest req = new BacktestRunRequest();
            req.setStrategyId(strategyId);
            req.setSymbol(symbol.toUpperCase());
            req.setIntervalCode(intervalCode.toLowerCase());
            req.setStartTime(startTime);
            req.setEndTime(endTime);
            req.setInitialCapital(new BigDecimal("10000"));
            req.setFeeRate(new BigDecimal("0.001"));
            req.setSource(src);
            req.setSkipPersist(true);
            req.setConfigOverride(combo);
            try {
                BacktestResultResponse r = backtestService.runForExploration(req);
                rows.add(new SweepRow(combo, r));
            } catch (Exception e) {
                log.warn("[runBacktestSweep] combo={} failed: {}", combo, e.getMessage());
            }
        }
        if (rows.isEmpty()) return "❌ 所有組合均失敗";

        // 排序：報酬率降序
        rows.sort((a, b) -> {
            double ra = a.result().getTotalReturn() != null ? a.result().getTotalReturn().doubleValue() : -999;
            double rb = b.result().getTotalReturn() != null ? b.result().getTotalReturn().doubleValue() : -999;
            return Double.compare(rb, ra);
        });

        // 欄寬計算
        List<String> shortKeys = paramKeys.stream()
                .map(k -> k.length() > 12 ? k.substring(0, 12) : k)
                .collect(Collectors.toList());
        String header = shortKeys.stream().map(k -> String.format("%-12s", k)).collect(Collectors.joining(" | "))
                + " | 筆數 | 勝率    | 報酬     | 回撤    | Sharpe";
        String sep = "-".repeat(header.length());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== 回測掃描結果（%d 組合）===\n", rows.size()));
        sb.append(String.format("策略 ID=%d  %s  %sd  %s\n", strategyId, symbol, daysVal, intervalCode));
        // #392 Option B — backtest pipeline 現已模擬 LiveSignalEvaluator 的 RegimeFilter（lightweight
        // primary-TF 版，see BacktestRegimeFilter）。當 sweep 含 regime-related params 時自動 enable，
        // 結果應該不再相同。下方提示列出 caveat（單 TF approximation，比 live dual-TF 略 aggressive）。
        if (sweepContainsRegimeParam(paramKeys)) {
            sb.append("ℹ️  applyRegimeFilter=true 已自動 inject — sweep 結果反映 BacktestRegimeFilter " +
                    "（primary-TF lightweight 版）對 LONG entry 的 TRENDING_DOWN 抑制。\n" +
                    "   注意：此 helper 用單一 timeframe，比 LiveSignalEvaluator 的 dual-TF 略 aggressive，\n" +
                    "   real-world live 行為仍以 analyzeBlockedSignalOutcomes 為準。詳見 #392 Option B。\n");
        }
        sb.append("\n").append(header).append("\n").append(sep).append("\n");

        for (int i = 0; i < rows.size(); i++) {
            SweepRow row = rows.get(i);
            String mark = (i == 0) ? " ← 最佳" : "";
            String paramPart = paramKeys.stream()
                    .map(k -> String.format("%-12s", fmtVal(row.params().get(k))))
                    .collect(Collectors.joining(" | "));
            BacktestResultResponse r = row.result();
            double ret = r.getTotalReturn() != null ? r.getTotalReturn().doubleValue() * 100 : 0;
            double dd  = r.getMaxDrawdown() != null ? r.getMaxDrawdown().doubleValue()  * 100 : 0;
            double wr  = r.getWinRate()     != null ? r.getWinRate().doubleValue()      * 100 : 0;
            String sh  = r.getSharpeRatio() != null ? String.format("%.2f", r.getSharpeRatio()) : "N/A";
            sb.append(String.format("%s | %4d | %6.1f%% | %+7.2f%% | %6.1f%% | %6s%s\n",
                    paramPart, r.getTradeCount() != null ? r.getTradeCount() : 0,
                    wr, ret, dd, sh, mark));
        }
        return sb.toString();
    }

    private List<Map<String, Object>> cartesianProduct(Map<String, List<Object>> sweep) {
        List<String> keys = new ArrayList<>(sweep.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (String key : keys) {
            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> existing : result) {
                for (Object val : sweep.get(key)) {
                    Map<String, Object> combo = new LinkedHashMap<>(existing);
                    combo.put(key, val);
                    next.add(combo);
                }
            }
            result = next;
        }
        return result;
    }

    private String fmtVal(Object v) {
        if (v == null) return "null";
        if (v instanceof Double d && d == Math.floor(d) && !Double.isInfinite(d)) return String.valueOf(d.intValue());
        return String.valueOf(v);
    }

    /**
     * #392 — backtest pipeline 不執行 RegimeFilter（只 LiveSignalEvaluator 走），
     * 所以 sweep regime-related params 會得到 noop 結果。Surface 警告避免誤判。
     */
    private static final Set<String> REGIME_PARAMS = Set.of(
            "regimeFilterMinConfidence",
            "regimeBypassRsiThreshold",
            "allowRsiBypassRegime"
    );

    private static boolean sweepContainsRegimeParam(List<String> paramKeys) {
        for (String key : paramKeys) {
            if (REGIME_PARAMS.contains(key)) return true;
        }
        return false;
    }

    // ─── analyzeAlphaByRegime ────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "分析策略在不同市場 Regime 下的 alpha 有效性。從最新回測交易中，" +
            "依 ADX + EMA 動量分類每筆交易的入場 Regime（BULLISH/BEARISH/SIDEWAYS），" +
            "輸出各 Regime 下的勝率/報酬/交易數。用途：快速判斷策略 alpha 是否 regime-dependent，" +
            "防止在錯誤 regime 中浪費時間調參。param: strategyId")
    public String analyzeAlphaByRegime(Long strategyId) {
        var latestResult = btBacktestResultRepo.findTopByStrategy_IdOrderByCreatedAtDesc(strategyId)
                .orElse(null);
        if (latestResult == null) return "❌ 策略 " + strategyId + " 尚無回測記錄，請先執行 runBacktest";

        List<com.agora.model.BtBacktestTrade> trades =
                btBacktestTradeRepo.findByBacktest_IdOrderByTradeIdxAsc(latestResult.getId());
        if (trades.isEmpty()) return "❌ 最新回測無交易記錄（tradeCount=0）";

        // 依 regime 聚合：key=regime, value=[wins, total, sumReturn]
        Map<String, double[]> stats = new LinkedHashMap<>();
        for (String r : new String[]{"BULLISH", "SIDEWAYS", "BEARISH"})
            stats.put(r, new double[3]);

        for (com.agora.model.BtBacktestTrade t : trades) {
            String regime = classifyTradeRegime(t);
            double ret = t.getReturnPct() != null ? t.getReturnPct().doubleValue() * 100 : 0;
            double[] s = stats.get(regime);
            if (s == null) continue;
            s[0] += (ret > 0) ? 1 : 0;
            s[1]++;
            s[2] += ret;
        }

        String name = btStrategyService.getStrategy(strategyId).getName();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Alpha Regime 分析 — %s (ID=%d) ===\n", name, strategyId));
        sb.append(String.format("基於最新回測：%d 筆交易 (%s ~ %s)\n\n",
                trades.size(), latestResult.getStartTime(), latestResult.getEndTime()));
        sb.append(String.format("%-10s | %6s | %8s | %8s | %s\n", "Regime", "筆數", "勝率", "平均報酬", "結論"));
        sb.append("-".repeat(55)).append("\n");

        for (Map.Entry<String, double[]> e : stats.entrySet()) {
            double[] s = e.getValue();
            if (s[1] == 0) {
                sb.append(String.format("%-10s | %6s | %8s | %8s | 無數據\n", e.getKey(), "-", "-", "-"));
                continue;
            }
            double wr = s[0] / s[1] * 100;
            double avgRet = s[2] / s[1];
            String verdict = (wr >= 50 && avgRet > 0) ? "✅ Alpha 有效" :
                             (s[1] < 3)               ? "⚠️ 樣本太少" : "❌ Alpha 無效";
            sb.append(String.format("%-10s | %6.0f | %7.1f%% | %+7.2f%% | %s\n",
                    e.getKey(), s[1], wr, avgRet, verdict));
        }
        sb.append("\n分類規則: ADX<20→SIDEWAYS; ADX≥20+動量>0→BULLISH; ADX≥20+動量≤0→BEARISH");
        return sb.toString();
    }

    private String classifyTradeRegime(com.agora.model.BtBacktestTrade t) {
        double adx = t.getAdx14() != null ? t.getAdx14().doubleValue() : 0;
        double momentum = t.getMomentum50barPct() != null ? t.getMomentum50barPct().doubleValue() : 0;
        if (adx < 20) return "SIDEWAYS";
        return momentum > 0 ? "BULLISH" : "BEARISH";
    }

    // ─── getShadowSignalStats ────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.READ_TRADING})
    @Tool(description = "分析 notifyOnly 策略的 live shadow 信號質量。" +
            "查詢最近 N 天的 shadow BUY 信號，計算信號後 4h/12h/24h 的 BTC 價格表現，" +
            "用於評估策略是否達到 Shadow→Live 轉換條件。" +
            "params: strategyId=策略ID, days=查詢天數（預設 90）")
    public String getShadowSignalStats(Long strategyId, Integer days) {
        int daysVal = (days != null) ? days : 90;
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(daysVal);
        LocalDateTime until = LocalDateTime.now(java.time.ZoneOffset.UTC);

        List<com.agora.model.BtDecisionAudit> allAudits =
                decisionAuditRepo.findByStrategyIdAndEventTimeBetweenOrderByEventTimeDesc(
                        strategyId, since, until);

        // 篩選 shadow BUY 信號（eventType=SIGNAL_EVAL, reason includes BUY）
        List<com.agora.model.BtDecisionAudit> signals = allAudits.stream()
                .filter(a -> "SIGNAL_EVAL".equals(a.getEventType())
                        && a.getReason() != null && a.getReason().contains("BUY"))
                .collect(Collectors.toList());

        if (signals.isEmpty()) return String.format(
                "策略 %d 在近 %d 天內無 shadow BUY 信號記錄。\n確認策略已啟用（enabled=true, notifyOnly=true）且有 K 線數據。",
                strategyId, daysVal);

        // 取 symbol 和 intervalCode 用於 kline 查詢
        String sym = signals.get(0).getSymbol();
        String intv = signals.get(0).getIntervalCode();
        if (sym == null) sym = "BTCUSDT";
        if (intv == null) intv = "1h";

        String name = btStrategyService.getStrategy(strategyId).getName();
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Shadow 信號質量 — %s (ID=%d) ===\n", name, strategyId));
        sb.append(String.format("近 %dd，共 %d 筆 BUY 信號\n\n", daysVal, signals.size()));

        // 前向收益統計
        // Fix #339-bug: track which signals actually have forward price data per horizon
        // so we don't treat "future/missing = 0.0 return" as valid data points.
        int[] horizons = {4, 12, 24};
        double[][] fwdReturns = new double[horizons.length][signals.size()];
        boolean[][] hasFwdData = new boolean[horizons.length][signals.size()]; // true = fwd kline found
        int sigIdx = 0;

        for (com.agora.model.BtDecisionAudit sig : signals) {
            LocalDateTime barTime = sig.getBarOpenTime() != null ? sig.getBarOpenTime() : sig.getEventTime();
            // 找入場 kline 收盤價
            var entryKlines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                    sym, intv, barTime.minusMinutes(1), barTime.plusMinutes(5));
            if (entryKlines.isEmpty()) { sigIdx++; continue; }
            double entryClose = entryKlines.get(0).getClosePrice() != null
                    ? entryKlines.get(0).getClosePrice().doubleValue() : 0;
            if (entryClose <= 0) { sigIdx++; continue; }

            for (int h = 0; h < horizons.length; h++) {
                LocalDateTime fwdTime = barTime.plusHours(horizons[h]);
                var fwdKlines = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                        sym, intv, fwdTime.minusMinutes(5), fwdTime.plusMinutes(65));
                if (!fwdKlines.isEmpty() && fwdKlines.get(0).getClosePrice() != null) {
                    double fwdClose = fwdKlines.get(0).getClosePrice().doubleValue();
                    fwdReturns[h][sigIdx] = (fwdClose - entryClose) / entryClose * 100;
                    hasFwdData[h][sigIdx] = true;
                }
                // else: future signal — no kline yet, hasFwdData stays false
            }
            sigIdx++;
        }

        int totalSignals = sigIdx;
        sb.append(String.format("%-6s | %8s | %10s | %6s | %s\n", "持有期", "平均報酬", "正報酬率", "樣本n", "結論"));
        sb.append("-".repeat(55)).append("\n");
        for (int h = 0; h < horizons.length; h++) {
            double sum = 0, pos = 0;
            int validN = 0;  // only count signals with actual fwd price data
            for (int i = 0; i < totalSignals; i++) {
                if (!hasFwdData[h][i]) continue;  // skip missing/future
                validN++;
                sum += fwdReturns[h][i];
                if (fwdReturns[h][i] > 0) pos++;
            }
            int pending = totalSignals - validN;  // signals too recent for this horizon
            double avgRet  = validN > 0 ? sum / validN : 0;
            double posRate = validN > 0 ? pos / validN * 100 : 0;
            String verdict;
            if (validN == 0) {
                verdict = "⏳ 等待中（信號太新）";
            } else if (posRate >= 60 && avgRet > 0) {
                verdict = "✅ 建議考慮 Live";
            } else if (posRate >= 50) {
                verdict = "⚠️ 繼續觀察";
            } else {
                verdict = "❌ 信號質量差";
            }
            String pendingNote = pending > 0 ? String.format(" [%d筆待結算]", pending) : "";
            sb.append(String.format("T+%-4d | %+7.2f%% | %9.1f%% | %6d | %s%s\n",
                    horizons[h], avgRet, posRate, validN, verdict, pendingNote));
        }
        sb.append("\nShadow→Live 建議條件：信號數 ≥ 3 且 T+24h 正報酬率 ≥ 60%");
        if (totalSignals < 3)
            sb.append(String.format("\n⚠️ 現在只有 %d 筆信號，需要更多樣本", totalSignals));
        return sb.toString();
    }

    // ─── getBacktestHistory ──────────────────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.READ_TRADING})
    @Tool(description = "查詢策略過去的回測結果清單（最多 5 筆，依時間降序）。param: strategyId=策略ID")
    public String getBacktestHistory(Long strategyId) {
        List<BacktestResultResponse> results = backtestService.queryResultsByStrategy(strategyId, null, false, 5);

        if (results.isEmpty()) {
            return "策略 " + strategyId + " 尚無回測記錄";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 策略 ").append(strategyId).append(" 歷史回測 (最近 ").append(results.size()).append(" 筆) ===\n");
        sb.append("啟用門檻：").append(BacktestQualityValidator.thresholdsDescription()).append("\n\n");
        for (BacktestResultResponse r : results) {
            int    tc  = r.getTradeCount()  != null ? r.getTradeCount()                : 0;
            double ret = r.getTotalReturn() != null ? r.getTotalReturn().doubleValue() : 0.0;
            double dd  = r.getMaxDrawdown() != null ? r.getMaxDrawdown().doubleValue() : 1.0;
            boolean pass = BacktestQualityValidator.passes(tc, ret, dd);
            long lookbackDays = (r.getStartTime() != null && r.getEndTime() != null)
                    ? ChronoUnit.DAYS.between(r.getStartTime(), r.getEndTime()) : 0;
            double tradesPerMonth = (lookbackDays > 0 && tc > 0) ? (tc * 30.0 / lookbackDays) : 0;
            double avgDaysBetween = tc > 0 ? (lookbackDays * 1.0 / tc) : 0;
            sb.append(pass ? "✅ " : "❌ ");
            sb.append("ID: ").append(r.getId()).append("  ");
            sb.append(r.getSymbol()).append(" ").append(r.getIntervalCode()).append("  ");
            sb.append(r.getStartTime().toLocalDate()).append("~").append(r.getEndTime().toLocalDate()).append("\n");
            sb.append(String.format("  勝率: %.1f%%  報酬: %.2f%%  回撤: %.2f%%  夏普: %s  交易: %d筆\n",
                    pct(r.getWinRate()), pct(r.getTotalReturn()), pct(r.getMaxDrawdown()),
                    r.getSharpeRatio() != null ? String.format("%.3f", r.getSharpeRatio()) : "N/A",
                    tc));
            sb.append(String.format("  頻率: 每月 %.1f 筆  平均每 %.1f 天觸發一次（%d 天回測）\n",
                    tradesPerMonth, avgDaysBetween, lookbackDays));
            sb.append("  建立: ").append(r.getCreatedAt()).append("\n---\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "使用 Groq AI 自動探索最佳 SOP_MTF_ADX 策略參數。" +
            "AI 生成多組候選配置並行回測，依評分排序回傳最佳策略 ID。" +
            "AI 探勘與啟用門檻相同（tradeCount≥5, totalReturn>0, maxDrawdown≤20%）。" +
            "若最佳策略未達啟用門檻，建議延長 days 或重新探勘。" +
            "params: symbol=交易對(BTCUSDT/ETHUSDT), intervalCode=K線週期(1h/4h), " +
            "days=回測天數(預設365), candidateCount=候選策略數量(1~10, 預設5), " +
            "source=K 線資料源 binance 或 okx（預設 binance；雙寫上線後若 binance 有缺口可改 okx）")
    public String runAiDiscovery(String symbol, String intervalCode, Integer days, Integer candidateCount,
                                  String source) {
        String sym = (symbol != null) ? symbol : "BTCUSDT";
        String interval = (intervalCode != null) ? intervalCode : "4h";
        int daysVal = (days != null) ? days : 365;
        int candidates = Math.min((candidateCount != null) ? candidateCount : 5, 10);

        // #219: bear market warning — SOP_MTF_ADX requires trendLong=close>EMA20+MACD>0
        // which is rarely met in TRENDING_DOWN, producing 0-trade backtests.
        StringBuilder bearWarning = new StringBuilder();
        try {
            geminiMarketHintRepository
                    .findBySymbolAndTimeframeAndCreatedAtAfterOrderByCreatedAtDesc(
                            sym, interval, LocalDateTime.now().minusHours(6))
                    .stream().findFirst().ifPresent(h -> {
                if ("TRENDING_DOWN".equalsIgnoreCase(h.getRegime())) {
                    bearWarning.append("⚠️ 熊市警告：當前 ").append(sym).append(" regime=TRENDING_DOWN\n");
                    bearWarning.append("SOP_MTF_ADX 策略要求 trendLong=close>EMA20+MACD>0，在熊市中幾乎不觸發，");
                    bearWarning.append("backtest 很可能回傳 0 筆交易。\n");
                    bearWarning.append("建議：\n");
                    bearWarning.append("  1. 改用 runAiDiscovery + allowShort=true 探索做空策略\n");
                    bearWarning.append("  2. 或等待 regime 回到 SIDEWAYS/TRENDING_UP 再執行\n\n");
                }
            });
        } catch (Exception ignored) {}

        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        AiStrategyDiscoveryRequest req = new AiStrategyDiscoveryRequest();
        req.setSymbol(sym);
        req.setIntervalCode(interval);
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setCandidateCount(candidates);
        if (source != null && !source.isBlank()) {
            req.setSource(source.toLowerCase().trim());
        }

        log.info("[MCP] run_ai_discovery symbol={} interval={} days={} candidates={} source={}",
                sym, interval, daysVal, candidates, req.getSource());

        AiStrategyDiscoveryResponse r = aiDiscoveryService.discover(req);

        StringBuilder sb = new StringBuilder();
        if (bearWarning.length() > 0) sb.append(bearWarning);
        sb.append("=== AI 策略探勘結果 ===\n");
        sb.append("批次 ID: ").append(r.getDiscoveryBatch()).append("\n");
        sb.append("幣種: ").append(r.getSymbol()).append("  週期: ").append(r.getIntervalCode()).append("\n");
        sb.append("候選總數: ").append(r.getTotalCandidates())
          .append("  有效: ").append(r.getValidCount())
          .append("  失敗: ").append(r.getFailedCount()).append("\n\n");

        if (r.getBestStrategy() != null) {
            AiStrategyDiscoveryResponse.CandidateResult best = r.getBestStrategy();
            int    btc = best.getTradeCount() != null ? best.getTradeCount() : 0;
            double bwr = best.getWinRate()    != null ? best.getWinRate().doubleValue()    : 0.0;
            double brt = best.getTotalReturn()!= null ? best.getTotalReturn().doubleValue(): 0.0;
            double bdd = best.getMaxDrawdown()!= null ? best.getMaxDrawdown().doubleValue(): 1.0;
            boolean bestPass = BacktestQualityValidator.passes(btc, brt, bdd);
            sb.append("🏆 最佳策略:\n");
            sb.append("  策略 ID: ").append(best.getStrategyId()).append("\n");
            sb.append("  名稱: ").append(best.getStrategyName()).append("\n");
            sb.append(String.format("  評分: %.6f\n", best.getScore()));
            sb.append(String.format("  勝率: %.1f%%  報酬: %.2f%%  回撤: %.2f%%  交易: %d筆\n",
                    pct(best.getWinRate()), pct(best.getTotalReturn()),
                    pct(best.getMaxDrawdown()), best.getTradeCount()));
            if (best.getAiRationale() != null) {
                sb.append("  AI說明: ").append(best.getAiRationale()).append("\n");
            }
            if (bestPass) {
                sb.append("\n✅ 通過啟用品質門檻\n");
                sb.append("下一步：enableStrategy(strategyId=").append(best.getStrategyId()).append(")\n");
            } else {
                sb.append(BacktestQualityValidator.failedThresholdLine());
                sb.append("建議：增加 days 參數（如 days=365）後重新執行 runAiDiscovery，或手動執行 runBacktest 更長期間\n");
            }
        } else {
            sb.append("⚠️ 所有候選策略均無效（交易次數不足或回測失敗）\n");
        }

        sb.append("\n--- 所有候選 ---\n");
        for (AiStrategyDiscoveryResponse.CandidateResult c : r.getCandidates()) {
            if (c.getErrorMessage() != null) {
                sb.append(String.format("❌ %s: %s\n", c.getStrategyName(), c.getErrorMessage()));
            } else {
                String wf = c.getWalkForwardNote() != null ? "  " + c.getWalkForwardNote() : "";
                sb.append(String.format("  [ID=%d] %s  評分=%.4f  勝率=%.1f%%  報酬=%.2f%%  交易=%d筆%s\n",
                        c.getStrategyId(), c.getStrategyName(), c.getScore(),
                        pct(c.getWinRate()), pct(c.getTotalReturn()), c.getTradeCount(), wf));
            }
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS})
    @Tool(description = "根據當前 K 線市場狀態，自適應生成並回測 SOP_MTF_ADX 策略。\n" +
            "先分析最近 K 線的 RSI/ATR/EMA/成交量，再由 AI 依指定風格生成針對性參數，最後並行回測篩選最佳策略。\n" +
            "style 可選：HIGH_FREQ（高頻，每月 10 筆以上）/ TREND（趨勢跟蹤，每月 3~8 筆）/ CONSERVATIVE（保守低頻，每月 1~5 筆）。\n" +
            "params: symbol=交易對(BTCUSDT/ETHUSDT), intervalCode=K線週期(1h/4h), style=策略風格, " +
            "days=回測天數（預設180）, candidateCount=候選數量（預設5，最多10）, " +
            "source=K 線資料源 binance 或 okx（預設 binance；雙寫上線後若 binance 有缺口可改 okx）")
    public String runAdaptiveDiscovery(String symbol, String intervalCode, String style,
                                       Integer days, Integer candidateCount, String source) {
        String sym      = symbol      != null ? symbol.toUpperCase().trim()      : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode.toLowerCase().trim() : "1h";
        String st       = style        != null ? style.toUpperCase().trim()        : "BALANCED";
        int daysVal      = days           != null ? days           : 180;
        int candidates   = Math.min(candidateCount != null ? candidateCount : 5, 10);

        LocalDateTime endTime   = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        AiStrategyDiscoveryRequest req = new AiStrategyDiscoveryRequest();
        req.setSymbol(sym);
        req.setIntervalCode(interval);
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setCandidateCount(candidates);
        if (source != null && !source.isBlank()) {
            req.setSource(source.toLowerCase().trim());
        }

        log.info("[MCP] runAdaptiveDiscovery symbol={} interval={} style={} days={} candidates={} source={}",
                sym, interval, st, daysVal, candidates, req.getSource());

        AiStrategyDiscoveryResponse r = aiDiscoveryService.discoverAdaptive(req, st);

        StringBuilder sb = new StringBuilder();
        sb.append("=== AI 自適應策略探勘結果 ===\n");
        sb.append("批次 ID: ").append(r.getDiscoveryBatch()).append("\n");
        sb.append("幣種: ").append(r.getSymbol()).append("  週期: ").append(r.getIntervalCode())
          .append("  風格: ").append(st).append("\n");
        sb.append("候選總數: ").append(r.getTotalCandidates())
          .append("  有效: ").append(r.getValidCount())
          .append("  失敗: ").append(r.getFailedCount()).append("\n\n");

        if (r.getBestStrategy() != null) {
            AiStrategyDiscoveryResponse.CandidateResult best = r.getBestStrategy();
            int    tc  = best.getTradeCount()  != null ? best.getTradeCount()  : 0;
            double ret = best.getTotalReturn()  != null ? best.getTotalReturn().doubleValue()  : 0.0;
            double dd  = best.getMaxDrawdown()  != null ? best.getMaxDrawdown().doubleValue()  : 1.0;
            boolean pass = BacktestQualityValidator.passes(tc, ret, dd);

            sb.append("🏆 最佳策略:\n");
            sb.append("  策略 ID: ").append(best.getStrategyId()).append("\n");
            sb.append("  名稱: ").append(best.getStrategyName()).append("\n");
            sb.append(String.format("  評分: %.6f\n", best.getScore()));
            sb.append(String.format("  勝率: %.1f%%  報酬: %.2f%%  回撤: %.2f%%  交易: %d筆\n",
                    pct(best.getWinRate()), pct(best.getTotalReturn()),
                    pct(best.getMaxDrawdown()), tc));
            // 頻率指標
            long lookbackDays = ChronoUnit.DAYS.between(r.getStartTime(), r.getEndTime());
            if (lookbackDays > 0 && tc > 0) {
                sb.append(String.format("  頻率: 每月 %.1f 筆  平均每 %.1f 天觸發\n",
                        tc * 30.0 / lookbackDays, lookbackDays * 1.0 / tc));
            }
            if (best.getAiRationale() != null) {
                sb.append("  AI說明: ").append(best.getAiRationale()).append("\n");
            }
            if (pass) {
                sb.append("\n✅ 通過啟用品質門檻\n");
                sb.append("下一步：enableStrategy(strategyId=").append(best.getStrategyId()).append(")\n");
            } else {
                sb.append(BacktestQualityValidator.failedThresholdLine());
                sb.append("建議：增加 days 參數後重新探勘，或手動執行 runBacktest 更長期間\n");
            }
        } else {
            sb.append("⚠️ 所有候選策略均無效（交易次數不足或回測失敗）\n");
            sb.append("建議：增加 days 參數或改用其他風格（如 TREND / CONSERVATIVE）\n");
        }

        sb.append("\n--- 所有候選 ---\n");
        for (AiStrategyDiscoveryResponse.CandidateResult c : r.getCandidates()) {
            if (c.getErrorMessage() != null) {
                sb.append(String.format("❌ %s: %s\n", c.getStrategyName(), c.getErrorMessage()));
            } else {
                String wf = c.getWalkForwardNote() != null ? "  " + c.getWalkForwardNote() : "";
                sb.append(String.format("  [ID=%d] %s  評分=%.4f  勝率=%.1f%%  報酬=%.2f%%  交易=%d筆%s\n",
                        c.getStrategyId(), c.getStrategyName(), c.getScore(),
                        pct(c.getWinRate()), pct(c.getTotalReturn()), c.getTradeCount(), wf));
            }
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "對外部 AI 提供的候選策略參數執行並行回測驗證。\n" +
            "candidatesJson 為 SopMtfAdxConfig JSON array（含可選 rationale 欄位）。\n" +
            "可用欄位（皆可選）：minSignals, adxEntryThreshold, fixedStopLossPct, fixedTakeProfitPct,\n" +
            "maxHoldingHours, rsiPullbackThreshold, minRR, allowShort,\n" +
            "atrTrailingStopEnabled, moveSlToBreakeven, rationale\n" +
            "params: symbol=交易對, intervalCode=K線週期, days=回測天數(預設180), " +
            "candidateCount=候選數量上限(預設5,最多10), candidatesJson=策略參數 JSON array, " +
            "source=K 線資料源 binance 或 okx（預設 binance；雙寫上線後若 binance 有缺口可改 okx）")
    public String validateCandidates(String symbol, String intervalCode,
                                     Integer days, Integer candidateCount, String candidatesJson,
                                     String source) {
        if (candidatesJson == null || candidatesJson.isBlank()) {
            return "❌ candidatesJson 不可為空，請提供 SopMtfAdxConfig JSON array。\n" +
                   "範例：[{\"adxEntryThreshold\":25,\"fixedStopLossPct\":0.02,\"fixedTakeProfitPct\":0.06,\"allowShort\":false}]";
        }

        String sym      = symbol      != null ? symbol.toUpperCase().trim()      : "BTCUSDT";
        String interval = intervalCode != null ? intervalCode.toLowerCase().trim() : "1h";
        int daysVal     = days          != null ? days          : 180;
        int candidates  = Math.min(candidateCount != null ? candidateCount : 5, 10);

        LocalDateTime endTime   = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        AiStrategyDiscoveryRequest req = new AiStrategyDiscoveryRequest();
        req.setSymbol(sym);
        req.setIntervalCode(interval);
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setCandidateCount(candidates);
        if (source != null && !source.isBlank()) {
            req.setSource(source.toLowerCase().trim());
        }

        log.info("[MCP] validateCandidates symbol={} interval={} days={} candidates={} source={}",
                sym, interval, daysVal, candidates, req.getSource());

        AiStrategyDiscoveryResponse r = aiDiscoveryService.runWithExternalCandidatesJson(req, candidatesJson);

        if (r.getTotalCandidates() == 0) {
            return "❌ 無法解析 candidatesJson，請確認格式為有效的 JSON array。\n" +
                   "範例：[{\"adxEntryThreshold\":25,\"fixedStopLossPct\":0.02,\"fixedTakeProfitPct\":0.06}]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== 外部 AI 策略驗證結果 ===\n");
        sb.append("批次 ID: ").append(r.getDiscoveryBatch()).append("\n");
        sb.append("幣種: ").append(r.getSymbol()).append("  週期: ").append(r.getIntervalCode()).append("\n");
        sb.append("候選總數: ").append(r.getTotalCandidates())
          .append("  有效: ").append(r.getValidCount())
          .append("  失敗: ").append(r.getFailedCount()).append("\n\n");

        if (r.getBestStrategy() != null) {
            AiStrategyDiscoveryResponse.CandidateResult best = r.getBestStrategy();
            int    tc  = best.getTradeCount()  != null ? best.getTradeCount()  : 0;
            double ret = best.getTotalReturn()  != null ? best.getTotalReturn().doubleValue()  : 0.0;
            double dd  = best.getMaxDrawdown()  != null ? best.getMaxDrawdown().doubleValue()  : 1.0;
            boolean pass = BacktestQualityValidator.passes(tc, ret, dd);

            sb.append("🏆 最佳策略:\n");
            sb.append("  策略 ID: ").append(best.getStrategyId()).append("\n");
            sb.append("  名稱: ").append(best.getStrategyName()).append("\n");
            sb.append(String.format("  評分: %.6f\n", best.getScore()));
            sb.append(String.format("  勝率: %.1f%%  報酬: %.2f%%  回撤: %.2f%%  交易: %d筆\n",
                    pct(best.getWinRate()), pct(best.getTotalReturn()),
                    pct(best.getMaxDrawdown()), tc));
            long lookbackDays = ChronoUnit.DAYS.between(r.getStartTime(), r.getEndTime());
            if (lookbackDays > 0 && tc > 0) {
                sb.append(String.format("  頻率: 每月 %.1f 筆  平均每 %.1f 天觸發\n",
                        tc * 30.0 / lookbackDays, lookbackDays * 1.0 / tc));
            }
            if (best.getAiRationale() != null) {
                sb.append("  說明: ").append(best.getAiRationale()).append("\n");
            }
            if (best.getWalkForwardNote() != null) {
                sb.append("  ").append(best.getWalkForwardNote()).append("\n");
            }
            if (pass) {
                sb.append("\n✅ 通過啟用品質門檻\n");
                sb.append("下一步：enableStrategy(strategyId=").append(best.getStrategyId()).append(")\n");
            } else {
                sb.append(BacktestQualityValidator.failedThresholdLine());
                sb.append("建議：調整參數後重新呼叫 validateCandidates\n");
            }
        } else {
            sb.append("⚠️ 所有候選策略均無效（交易次數不足或回測失敗）\n");
            sb.append("建議：調整 adxEntryThreshold、SL/TP 比例，或增加 days 參數\n");
        }

        sb.append("\n--- 所有候選 ---\n");
        for (AiStrategyDiscoveryResponse.CandidateResult c : r.getCandidates()) {
            if (c.getErrorMessage() != null) {
                sb.append(String.format("❌ %s: %s\n", c.getStrategyName(), c.getErrorMessage()));
            } else {
                String wf = c.getWalkForwardNote() != null ? "  " + c.getWalkForwardNote() : "";
                sb.append(String.format("  [ID=%d] %s  評分=%.4f  勝率=%.1f%%  報酬=%.2f%%  交易=%d筆%s\n",
                        c.getStrategyId(), c.getStrategyName(), c.getScore(),
                        pct(c.getWinRate()), pct(c.getTotalReturn()), c.getTradeCount(), wf));
            }
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.DIAGNOSTIC})
    @Tool(description = "驗證回測引擎產生的交易記錄自洽性。對指定策略跑一次回測，" +
            "再獨立讀取每筆 trade 的 entry/exit 時間對應 K 線，確認：" +
            "（1）entry/exit 價格在當時 bar 的 [low, high] 範圍內；" +
            "（2）時序正確（entry < exit）；" +
            "（3）grossPnl 與 (exitPrice-entryPrice)*quantity 一致。" +
            "抓引擎寫入的數字 vs 歷史 K 線事實的低階 bug。" +
            "param: strategyId, symbol, intervalCode, days")
    public String validateBacktestTrades(Long strategyId, String symbol, String intervalCode, Integer days) {
        int d = (days == null || days <= 0) ? 180 : days;

        // 1. 跑回測取得 trades
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(d).truncatedTo(ChronoUnit.DAYS);
        BacktestRunRequest req = new BacktestRunRequest();
        req.setStrategyId(strategyId);
        req.setSymbol(symbol.toUpperCase());
        req.setIntervalCode(intervalCode.toLowerCase());
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setInitialCapital(new BigDecimal("10000"));
        req.setFeeRate(new BigDecimal("0.001"));

        BacktestResultResponse resp = backtestService.runForExploration(req);

        // 2. DTO → TradeRecord
        List<TradeRecord> records = new ArrayList<>();
        if (resp.getTrades() != null) {
            for (BacktestResultResponse.TradeRecordDto dto : resp.getTrades()) {
                TradeRecord tr = new TradeRecord();
                tr.setEntryTime(dto.getEntryTime());
                tr.setExitTime(dto.getExitTime());
                tr.setEntryPrice(dto.getEntryPrice() != null ? dto.getEntryPrice().doubleValue() : 0);
                tr.setExitPrice(dto.getExitPrice() != null ? dto.getExitPrice().doubleValue() : 0);
                tr.setQuantity(dto.getQuantity() != null ? dto.getQuantity().doubleValue() : 0);
                tr.setGrossPnl(dto.getGrossPnl() != null ? dto.getGrossPnl().doubleValue() : 0);
                tr.setNetPnl(dto.getNetPnl() != null ? dto.getNetPnl().doubleValue() : 0);
                tr.setExitReason(dto.getExitReason());
                tr.setSide(dto.getSide());
                records.add(tr);
            }
        }

        // 3. 驗證
        BacktestTradeValidator.Report report =
                backtestTradeValidator.validate(symbol.toUpperCase(), intervalCode.toLowerCase(), records);

        StringBuilder sb = new StringBuilder();
        sb.append("=== 回測交易自洽性驗證 ===\n");
        sb.append(String.format("策略 %d (%s) %s@%s %d 天\n", strategyId,
                resp.getStrategyName(), symbol, intervalCode, d));
        sb.append(String.format("回測結果: %d 筆交易、報酬 %.2f%%、回撤 %.2f%%\n\n",
                report.totalTrades(),
                resp.getTotalReturn() != null ? resp.getTotalReturn().doubleValue() * 100 : 0,
                resp.getMaxDrawdown() != null ? resp.getMaxDrawdown().doubleValue() * 100 : 0));

        String icon = report.issuesFound() == 0 ? "✅" : "🚫";
        sb.append(String.format("%s 檢查 %d 筆 / 發現 %d 個不一致\n",
                icon, report.checkedTrades(), report.issuesFound()));

        if (!report.issues().isEmpty()) {
            sb.append("\n📋 不一致樣本\n");
            int show = Math.min(10, report.issues().size());
            for (int i = 0; i < show; i++) {
                BacktestTradeValidator.Issue iss = report.issues().get(i);
                sb.append(String.format("  #%d %s [%s] expected=%s actual=%s\n     %s\n",
                        iss.tradeIndex(), iss.entryTime(), iss.field(),
                        iss.expected(), iss.actual(), iss.note()));
            }
        }

        sb.append("\n").append(report.issuesFound() == 0
                ? "✅ 引擎自洽：所有交易記錄與底層 K 線一致"
                : "🚫 發現不一致：回測引擎可能有 bug，請檢視上列樣本");
        return sb.toString();
    }

    private double pct(BigDecimal bd) {
        return bd == null ? 0.0 : bd.doubleValue() * 100.0;
    }
}
