package com.agora.service;

import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.model.BtBacktestResult;
import com.agora.model.BtBacktestTrade;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtBacktestTradeRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.backtest.BacktestDiagnosticCollector;
import com.agora.service.backtest.DiagnosticCode;
import com.agora.service.backtest.BacktestEngine;
import com.agora.service.backtest.BacktestRunSummary;
import com.agora.service.backtest.Strategy;
import com.agora.service.backtest.StrategyRegistry;
import com.agora.service.backtest.TradeRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.PageRequest;

@Service
@RequiredArgsConstructor
public class BacktestService {

    private final MdKlineRepository klineRepository;
    private final BtBacktestResultRepository resultRepository;
    private final BtBacktestTradeRepository tradeRepository;
    private final BtStrategyService strategyService;
    private final StrategyRegistry strategyRegistry;
    private final BacktestEngine backtestEngine;
    private final ObjectMapper objectMapper;

    /**
     * V041 fallback 順序底層預設。strategy.klineSource 為 NOT NULL DEFAULT 'okx'，
     * 此常數只在極端退化情境（strategy 物件 klineSource 被手動設為 null）使用。
     */
    private static final String DEFAULT_KLINE_SOURCE = "okx";

    @Transactional
    public BacktestResultResponse run(BacktestRunRequest request) {
        BtStrategy strategy = strategyService.getRequired(request.getStrategyId());
        if (!Boolean.TRUE.equals(strategy.getEnabled())) {
            throw new IllegalArgumentException("策略已停用");
        }
        return runWithStrategy(strategy, request);
    }

    /** MCP / AI 探索用：跳過啟用狀態限制，允許對停用策略執行回測 */
    @Transactional
    public BacktestResultResponse runForExploration(BacktestRunRequest request) {
        BtStrategy strategy = strategyService.getRequired(request.getStrategyId());
        return runWithStrategy(strategy, request);
    }

    private BacktestResultResponse runWithStrategy(BtStrategy strategy, BacktestRunRequest request) {
        if (!request.getEndTime().isAfter(request.getStartTime())) {
            throw new IllegalArgumentException("endTime 必須大於 startTime");
        }

        // V041 source resolution: request 明確覆寫 > strategy.klineSource > 全域預設
        // strategy.klineSource 是 source of truth；request.source 僅 MCP / 研究工具用來暫時切源。
        String source = resolveKlineSource(request.getSource(), strategy.getKlineSource());
        List<MdKline> klines = klineRepository
                .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                        request.getSymbol().toUpperCase(),
                        request.getIntervalCode().toLowerCase(),
                        source,
                        request.getStartTime(),
                        request.getEndTime());

        validateKlineIntegrity(klines, request.getIntervalCode());

        Map<String, Object> config = strategyService.parseConfig(strategy.getConfigJson());
        Map<String, Object> executionConfig = new HashMap<String, Object>(config);
        // 臨時 config 覆蓋（不寫 DB），優先級高於 DB config
        if (request.getConfigOverride() != null && !request.getConfigOverride().isEmpty()) {
            executionConfig.putAll(request.getConfigOverride());
        }
        executionConfig.put("runIntervalCode", request.getIntervalCode().toLowerCase());
        if (Boolean.TRUE.equals(request.getApplyFilters())) {
            executionConfig.put("applyFilters", true);
        }

        Strategy strategyBean = strategyRegistry.getRequiredStrategy(strategy.getStrategyType());
        strategyBean.defaultExecutionConfig().forEach(executionConfig::putIfAbsent);

        executionConfig.put(BacktestDiagnosticCollector.CONFIG_KEY, new BacktestDiagnosticCollector(parseDiagnosticsConfig(executionConfig)));

        Map<String, List<MdKline>> timeframeKlines = new HashMap<String, List<MdKline>>();
        timeframeKlines.put(request.getIntervalCode().toLowerCase(), klines);
        if (Boolean.TRUE.equals(executionConfig.get("enableMtf"))) {
            String symbol = request.getSymbol().toUpperCase();
            LocalDateTime mtfStart = request.getStartTime().minusDays(120);
            // MTF 同步使用解析後的 source，避免 1h 走 okx、4h 走 binance 的跨源混用
            for (String mtfInterval : new String[]{"1h", "4h", "1d"}) {
                List<MdKline> mtfKlines = klineRepository
                        .findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                                symbol, mtfInterval, source, mtfStart, request.getEndTime());
                if (!mtfKlines.isEmpty()) {
                    validateMtfKlineContinuity(mtfKlines, mtfInterval);
                }
                timeframeKlines.put(mtfInterval, mtfKlines);
            }
        }

        BacktestRunSummary summary = backtestEngine.run(
                klines,
                timeframeKlines,
                strategyBean,
            executionConfig,
                request.getInitialCapital(),
                request.getFeeRate()
        );

        MarketContext marketContext = buildMarketContext(klines);

        BtBacktestResult entity = new BtBacktestResult();
        entity.setStrategy(strategy);
        entity.setSymbol(request.getSymbol().toUpperCase());
        entity.setIntervalCode(request.getIntervalCode().toLowerCase());
        entity.setKlineSource(source);
        entity.setStartTime(request.getStartTime());
        entity.setEndTime(request.getEndTime());
        entity.setInitialCapital(scale(summary.getInitialCapital(), 8));
        entity.setFinalCapital(scale(summary.getFinalCapital(), 8));
        entity.setTotalReturn(scale(summary.getTotalReturn(), 6));
        entity.setMaxDrawdown(scale(summary.getMaxDrawdown(), 6));
        entity.setWinRate(scale(summary.getWinRate(), 6));
        entity.setTradeCount(summary.getTradeCount());
        double rawSharpe = summary.getSharpeRatio();
        entity.setSharpeRatio((Double.isNaN(rawSharpe) || Double.isInfinite(rawSharpe)) ? null
                : scale(Math.max(-999.0, Math.min(999.0, rawSharpe)), 6));
        entity.setFeeRate(request.getFeeRate().setScale(6, RoundingMode.HALF_UP));
        entity.setTradesJson(writeTradesJson(summary.getTrades()));
        entity.setConfigSnapshotJson(writeConfigSnapshotJson(sanitizeConfigSnapshot(executionConfig)));
        entity.setDiagnosticLogsJson(writeDiagnosticLogsJson(summary.getDiagnosticLogs()));
        entity.setMarketOpenPrice(scale(marketContext.openPrice, 8));
        entity.setMarketClosePrice(scale(marketContext.closePrice, 8));
        entity.setMarketHighPrice(scale(marketContext.highPrice, 8));
        entity.setMarketLowPrice(scale(marketContext.lowPrice, 8));
        entity.setMarketVolatilityPct(scale(marketContext.volatilityPct, 6));
        entity.setMarketPriceChangePct(scale(marketContext.priceChangePct, 6));
        entity.setMarketTrend(marketContext.trend);
        entity.setBenchmarkReturn(scale(marketContext.priceChangePct, 6));

        // skipPersist=true 用於 WF 等 ad-hoc 評估,不汙染 bt_backtest_result「最新」狀態
        // 解決 enableStrategy race condition:WF 跑完不會覆蓋主回測為「最新」
        BacktestResultResponse response;
        if (Boolean.TRUE.equals(request.getSkipPersist())) {
            response = toResponse(entity);
        } else {
            BtBacktestResult saved = resultRepository.save(entity);
            // V040 起並存寫入正規化 bt_backtest_trade。同一 @Transactional,trades_json
            // 與 bt_backtest_trade 同生同死;parent 未 persist 的 skipPersist 分支不寫。
            persistNormalizedTrades(saved, summary.getTrades());
            response = toResponse(saved);
        }
        // filteredEntryCount 未落 DB（runtime-only 觀察值）→ 在回傳前直接從 summary 補上
        response.setFilteredEntryCount(summary.getFilteredEntryCount());
        return response;
    }

    @Transactional(readOnly = true)
    public BacktestResultResponse getLatestResultByStrategy(Long strategyId) {
        BtBacktestResult result = resultRepository.findTopByStrategy_IdOrderByCreatedAtDesc(strategyId)
                .orElseThrow(() -> new IllegalArgumentException("查無策略最新回測結果: " + strategyId));
        return toResponse(result);
    }

    @Transactional(readOnly = true)
    public List<BacktestResultResponse> getResultsByStrategy(Long strategyId) {
        List<BtBacktestResult> results = resultRepository.findByStrategy_IdOrderByCreatedAtDesc(strategyId);
        List<BacktestResultResponse> responses = new ArrayList<BacktestResultResponse>();
        for (BtBacktestResult result : results) {
            responses.add(toResponse(result));
        }
        return responses;
    }

    @Transactional(readOnly = true)
    public List<BacktestResultResponse> queryResultsByStrategy(Long strategyId, Long resultId, Boolean latest, Integer limit) {
        strategyService.getRequired(strategyId);

        if (resultId != null) {
            BtBacktestResult result = resultRepository.findById(resultId)
                    .orElseThrow(() -> new IllegalArgumentException("回測結果不存在: " + resultId));
            if (result.getStrategy() == null || !strategyId.equals(result.getStrategy().getId())) {
                throw new IllegalArgumentException("回測結果不屬於策略: strategyId=" + strategyId + ", resultId=" + resultId);
            }
            List<BacktestResultResponse> single = new ArrayList<BacktestResultResponse>();
            single.add(toResponse(result));
            return single;
        }

        boolean latestOnly = latest == null || latest;
        if (latestOnly) {
            List<BacktestResultResponse> single = new ArrayList<BacktestResultResponse>();
            resultRepository.findTopByStrategy_IdOrderByCreatedAtDesc(strategyId)
                    .ifPresent(result -> single.add(toResponse(result)));
            return single;
        }

        if (limit != null) {
            List<BtBacktestResult> entities = resultRepository.findByStrategy_IdOrderByCreatedAtDesc(
                    strategyId, PageRequest.of(0, limit));
            List<BacktestResultResponse> results = new ArrayList<>();
            for (BtBacktestResult entity : entities) {
                results.add(toResponse(entity));
            }
            return results;
        }
        return getResultsByStrategy(strategyId);
    }

    private void validateMtfKlineContinuity(List<MdKline> klines, String intervalCode) {
        int stepMinutes = parseIntervalMinutes(intervalCode);
        for (int i = 1; i < klines.size(); i++) {
            MdKline prev = klines.get(i - 1);
            MdKline current = klines.get(i);
            if (!current.getOpenTime().isAfter(prev.getOpenTime())) {
                throw new IllegalArgumentException("MTF K 線（" + intervalCode + "）時間未依序遞增");
            }
            long diff = Duration.between(prev.getOpenTime(), current.getOpenTime()).toMinutes();
            if (diff != stepMinutes) {
                throw new IllegalArgumentException("MTF K 線（" + intervalCode + "）資料不連續，預期間隔 "
                        + stepMinutes + " 分鐘，實際 " + diff + " 分鐘");
            }
        }
    }

    private void validateKlineIntegrity(List<MdKline> klines, String intervalCode) {
        if (klines == null || klines.isEmpty()) {
            throw new IllegalArgumentException("查無 K 線資料");
        }

        int stepMinutes = parseIntervalMinutes(intervalCode);
        for (int i = 1; i < klines.size(); i++) {
            MdKline prev = klines.get(i - 1);
            MdKline current = klines.get(i);

            if (!current.getOpenTime().isAfter(prev.getOpenTime())) {
                throw new IllegalArgumentException("K 線時間未依序遞增");
            }

            long diff = Duration.between(prev.getOpenTime(), current.getOpenTime()).toMinutes();
            if (diff != stepMinutes) {
                throw new IllegalArgumentException("K 線資料不連續，預期間隔 " + stepMinutes + " 分鐘，實際 " + diff + " 分鐘");
            }
        }
    }

    /**
     * V041 fallback 鏈：request 覆寫 > 策略設定 > 硬編碼預設（okx）。
     * 呼叫方可明確傳 source（MCP 研究工具）；一般情況留白就以 strategy.klineSource 為準。
     */
    private String resolveKlineSource(String requestSource, String strategySource) {
        if (requestSource != null && !requestSource.isBlank()) {
            return requestSource.toLowerCase();
        }
        if (strategySource != null && !strategySource.isBlank()) {
            return strategySource.toLowerCase();
        }
        return DEFAULT_KLINE_SOURCE;
    }

    private int parseIntervalMinutes(String intervalCode) {
        String code = intervalCode == null ? "" : intervalCode.trim().toLowerCase();
        if (code.endsWith("m")) {
            return Integer.parseInt(code.substring(0, code.length() - 1));
        }
        if (code.endsWith("h")) {
            return Integer.parseInt(code.substring(0, code.length() - 1)) * 60;
        }
        if (code.endsWith("d")) {
            return Integer.parseInt(code.substring(0, code.length() - 1)) * 1440;
        }
        throw new IllegalArgumentException("不支援的 intervalCode: " + intervalCode);
    }

    private BigDecimal scale(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    private String writeTradesJson(List<TradeRecord> trades) {
        try {
            return objectMapper.writeValueAsString(trades);
        } catch (Exception ex) {
            throw new IllegalArgumentException("交易記錄序列化失敗", ex);
        }
    }

    /**
     * V040 dual-write:將 runtime {@link TradeRecord} 映射到正規化 {@link BtBacktestTrade}
     * 並存。ON DELETE CASCADE + deleteByStrategy_Id 會自動清理子資料表。
     *
     * <p>precision 對齊:entryPrice/exitPrice 8 位、quantity 10 位(BTC satoshi=8 位即足),
     * double → BigDecimal 透過 {@link BigDecimal#valueOf(double)} 避免 binary float 偽位數。
     */
    private void persistNormalizedTrades(BtBacktestResult parent, List<TradeRecord> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        List<BtBacktestTrade> rows = new ArrayList<BtBacktestTrade>(trades.size());
        int idx = 0;
        for (TradeRecord src : trades) {
            BtBacktestTrade row = new BtBacktestTrade();
            row.setBacktest(parent);
            row.setTradeIdx(idx++);
            row.setEntryTime(src.getEntryTime());
            row.setExitTime(src.getExitTime());
            row.setEntryPrice(scale(src.getEntryPrice(), 8));
            row.setExitPrice(src.getExitTime() == null ? null : scale(src.getExitPrice(), 8));
            row.setQuantity(BigDecimal.valueOf(src.getQuantity()).setScale(10, RoundingMode.HALF_UP));
            row.setGrossPnl(scale(src.getGrossPnl(), 8));
            row.setNetPnl(scale(src.getNetPnl(), 8));
            row.setReturnPct(scale(src.getReturnPct(), 6));
            row.setExitReason(src.getExitReason());
            row.setSide(BtBacktestTrade.Side.valueOf(src.getSide()));
            row.setBorrowingCost(scale(src.getBorrowingCost(), 8));
            row.setReleasedNotional(scale(src.getReleasedNotional(), 8));
            // V047 indicator snapshot (nullable — Double → BigDecimal null-safe)
            row.setAdx14(doubleToBd(src.getAdx14(), 4));
            row.setRsi14(doubleToBd(src.getRsi14(), 4));
            row.setAtrPct(doubleToBd(src.getAtrPct(), 8));
            row.setVolumeRatioMa20(doubleToBd(src.getVolumeRatioMa20(), 6));
            row.setCloseVsEma50Pct(doubleToBd(src.getCloseVsEma50Pct(), 8));
            row.setEma20SlopePct(doubleToBd(src.getEma20SlopePct(), 8));
            row.setBbWidthPct(doubleToBd(src.getBbWidthPct(), 8));
            // V049 regime features (rolling, derived from same-TF kline)
            row.setDd20barPct(doubleToBd(src.getDd20barPct(), 6));
            row.setDd50barPct(doubleToBd(src.getDd50barPct(), 6));
            row.setMomentum50barPct(doubleToBd(src.getMomentum50barPct(), 8));
            row.setRealizedVol20bar(doubleToBd(src.getRealizedVol20bar(), 8));
            row.setDistFromEma200Pct(doubleToBd(src.getDistFromEma200Pct(), 8));
            row.setRangePct50bar(doubleToBd(src.getRangePct50bar(), 6));
            // V050 HTF (live engine pass-through; backfill populates separately)
            row.setHtfMomentum50barPct(doubleToBd(src.getHtfMomentum50barPct(), 8));
            row.setHtfTrendUp(src.getHtfTrendUp());
            row.setHtfDistEma50Pct(doubleToBd(src.getHtfDistEma50Pct(), 8));
            rows.add(row);
        }
        tradeRepository.saveAll(rows);
    }

    /** V047 helper — Double? → BigDecimal with given scale; null-safe. */
    private static BigDecimal doubleToBd(Double v, int scale) {
        if (v == null || Double.isNaN(v) || Double.isInfinite(v)) return null;
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP);
    }

    private String writeConfigSnapshotJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (Exception ex) {
            throw new IllegalArgumentException("策略參數快照序列化失敗", ex);
        }
    }

    private String writeDiagnosticLogsJson(List<BacktestResultResponse.DiagnosticLogDto> diagnosticLogs) {
        if (diagnosticLogs == null || diagnosticLogs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(diagnosticLogs);
        } catch (Exception ex) {
            throw new IllegalArgumentException("診斷日誌序列化失敗", ex);
        }
    }

    private Map<DiagnosticCode, Boolean> parseDiagnosticsConfig(Map<String, Object> config) {
        Object diagnosticsObj = config.get(BacktestDiagnosticCollector.DIAGNOSTICS_CONFIG_KEY);
        if (!(diagnosticsObj instanceof Map)) {
            return new LinkedHashMap<DiagnosticCode, Boolean>();
        }
        Map<?, ?> raw = (Map<?, ?>) diagnosticsObj;
        Map<DiagnosticCode, Boolean> result = new LinkedHashMap<DiagnosticCode, Boolean>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            String key = String.valueOf(entry.getKey());
            try {
                DiagnosticCode code = DiagnosticCode.valueOf(key);
                result.put(code, Boolean.TRUE.equals(entry.getValue()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("不支援的診斷碼: " + key);
            }
        }
        return result;
    }

    private static class MarketContext {
        double openPrice;
        double closePrice;
        double highPrice;
        double lowPrice;
        double volatilityPct;
        double priceChangePct;
        String trend;
    }

    private MarketContext buildMarketContext(List<MdKline> klines) {
        MarketContext ctx = new MarketContext();
        ctx.openPrice = klines.get(0).getOpenPrice().doubleValue();
        ctx.closePrice = klines.get(klines.size() - 1).getClosePrice().doubleValue();
        ctx.highPrice = klines.stream().mapToDouble(k -> k.getHighPrice().doubleValue()).max().orElse(ctx.closePrice);
        ctx.lowPrice = klines.stream().mapToDouble(k -> k.getLowPrice().doubleValue()).min().orElse(ctx.closePrice);
        ctx.volatilityPct = ctx.lowPrice > 0.0 ? (ctx.highPrice - ctx.lowPrice) / ctx.lowPrice : 0.0;
        ctx.priceChangePct = ctx.openPrice > 0.0 ? (ctx.closePrice - ctx.openPrice) / ctx.openPrice : 0.0;
        if (ctx.priceChangePct > 0.03) {
            ctx.trend = "BULLISH";
        } else if (ctx.priceChangePct < -0.03) {
            ctx.trend = "BEARISH";
        } else {
            ctx.trend = "SIDEWAYS";
        }
        return ctx;
    }

    private Map<String, Object> sanitizeConfigSnapshot(Map<String, Object> config) {
        Map<String, Object> snapshot = new LinkedHashMap<String, Object>(config);
        snapshot.remove(BacktestDiagnosticCollector.CONFIG_KEY);
        return snapshot;
    }

    private List<BacktestResultResponse.TradeRecordDto> parseTrades(String tradesJson) {
        if (tradesJson == null || tradesJson.trim().isEmpty()) {
            return new ArrayList<BacktestResultResponse.TradeRecordDto>();
        }

        try {
            List<TradeRecord> records = objectMapper.readValue(tradesJson, new TypeReference<List<TradeRecord>>() {
            });
            List<BacktestResultResponse.TradeRecordDto> dtos = new ArrayList<BacktestResultResponse.TradeRecordDto>();
            for (TradeRecord record : records) {
                BacktestResultResponse.TradeRecordDto dto = new BacktestResultResponse.TradeRecordDto();
                dto.setEntryTime(record.getEntryTime());
                dto.setExitTime(record.getExitTime());
                dto.setEntryPrice(scale(record.getEntryPrice(), 8));
                dto.setExitPrice(scale(record.getExitPrice(), 8));
                dto.setQuantity(scale(record.getQuantity(), 8));
                dto.setGrossPnl(scale(record.getGrossPnl(), 8));
                dto.setNetPnl(scale(record.getNetPnl(), 8));
                dto.setReturnPct(scale(record.getReturnPct(), 6));
                dto.setExitReason(record.getExitReason());
                dto.setSide(record.getSide());
                dto.setBorrowingCost(record.getBorrowingCost() > 0.0 ? scale(record.getBorrowingCost(), 8) : null);
                dto.setEntryReason(record.getEntryReason());
                dto.setEntryLabel(record.getEntryLabel());
                dto.setEntryRequestedQuantity(record.getEntryRequestedQuantity() == null
                        ? null : scale(record.getEntryRequestedQuantity(), 8));
                dto.setEntryOrderCount(record.getEntryOrderCount());
                dto.setEntryOrderReasons(record.getEntryOrderReasons());
                dtos.add(dto);
            }
            return dtos;
        } catch (Exception ex) {
            throw new IllegalArgumentException("交易記錄反序列化失敗", ex);
        }
    }

    private List<BacktestResultResponse.DiagnosticLogDto> parseDiagnosticLogs(String diagnosticLogsJson) {
        if (diagnosticLogsJson == null || diagnosticLogsJson.trim().isEmpty()) {
            return new ArrayList<BacktestResultResponse.DiagnosticLogDto>();
        }

        try {
            JsonNode root = objectMapper.readTree(diagnosticLogsJson);
            List<BacktestResultResponse.DiagnosticLogDto> diagnosticLogs = new ArrayList<BacktestResultResponse.DiagnosticLogDto>();

            if (root.isObject()) {
                diagnosticLogs.add(objectMapper.treeToValue(root, BacktestResultResponse.DiagnosticLogDto.class));
                return diagnosticLogs;
            }

            if (root.isTextual()) {
                diagnosticLogs.add(parseLegacyDiagnosticLog(root.asText()));
                return diagnosticLogs;
            }

            if (!root.isArray()) {
                diagnosticLogs.add(buildParseFallbackDiagnostic(diagnosticLogsJson));
                return diagnosticLogs;
            }

            for (JsonNode node : root) {
                if (node.isObject()) {
                    diagnosticLogs.add(objectMapper.treeToValue(node, BacktestResultResponse.DiagnosticLogDto.class));
                } else if (node.isTextual()) {
                    diagnosticLogs.add(parseLegacyDiagnosticLog(node.asText()));
                } else {
                    diagnosticLogs.add(buildParseFallbackDiagnostic(node.toString()));
                }
            }
            return diagnosticLogs;
        } catch (Exception ex) {
            List<BacktestResultResponse.DiagnosticLogDto> fallback = new ArrayList<BacktestResultResponse.DiagnosticLogDto>();
            fallback.add(buildParseFallbackDiagnostic(diagnosticLogsJson));
            return fallback;
        }
    }

    private BacktestResultResponse.DiagnosticLogDto buildParseFallbackDiagnostic(String rawJson) {
        BacktestResultResponse.DiagnosticLogDto dto = new BacktestResultResponse.DiagnosticLogDto();
        dto.setCode(DiagnosticCode.DIAGNOSTIC_PARSE_FALLBACK.getCode());
        dto.setCount(1);
        dto.setSampleDetail(limitLength(rawJson, 500));
        return dto;
    }

    private String limitLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    private BacktestResultResponse.DiagnosticLogDto parseLegacyDiagnosticLog(String raw) {
        BacktestResultResponse.DiagnosticLogDto dto = new BacktestResultResponse.DiagnosticLogDto();
        if (raw == null || raw.trim().isEmpty()) {
            dto.setCode("LEGACY_DIAGNOSTIC_LOG");
            dto.setCount(1);
            return dto;
        }

        String[] parts = raw.split("\\s*\\|\\s*");
        dto.setCode(parts.length > 0 && !parts[0].trim().isEmpty() ? parts[0].trim() : "LEGACY_DIAGNOSTIC_LOG");
        dto.setCount(1);
        dto.setSampleDetail(raw);

        for (int i = 1; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("次數=")) {
                dto.setCount(parseInteger(part.substring(3), 1));
            } else if (part.startsWith("首次=")) {
                dto.setFirstOccurredAt(parseDateTime(part.substring(3)));
            } else if (part.startsWith("最後=")) {
                dto.setLastOccurredAt(parseDateTime(part.substring(3)));
            } else if (part.startsWith("範例=")) {
                dto.setSampleDetail(part.substring(3));
            }
        }
        return dto;
    }

    private Integer parseInteger(String value, int defaultValue) {
        try {
            return Integer.valueOf(value.trim());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value.trim());
        } catch (Exception ex) {
            return null;
        }
    }

    private BacktestResultResponse toResponse(BtBacktestResult entity) {
        BacktestResultResponse response = new BacktestResultResponse();
        response.setId(entity.getId());
        response.setStrategyId(entity.getStrategy().getId());
        response.setStrategyName(entity.getStrategy().getName());
        response.setSymbol(entity.getSymbol());
        response.setIntervalCode(entity.getIntervalCode());
        response.setStartTime(entity.getStartTime());
        response.setEndTime(entity.getEndTime());
        response.setInitialCapital(entity.getInitialCapital());
        response.setFinalCapital(entity.getFinalCapital());
        response.setTotalReturn(entity.getTotalReturn());
        response.setMaxDrawdown(entity.getMaxDrawdown());
        response.setWinRate(entity.getWinRate());
        response.setTradeCount(entity.getTradeCount());
        response.setSharpeRatio(entity.getSharpeRatio());
        response.setFeeRate(entity.getFeeRate());
        response.setCreatedAt(entity.getCreatedAt());
        response.setConfigSnapshotJson(entity.getConfigSnapshotJson());
        List<BacktestResultResponse.TradeRecordDto> trades = parseTrades(entity.getTradesJson());
        response.setTrades(trades);
        response.setDiagnosticLogs(parseDiagnosticLogs(entity.getDiagnosticLogsJson()));

        int longCount = 0, shortCount = 0, longWin = 0, shortWin = 0;
        for (BacktestResultResponse.TradeRecordDto t : trades) {
            if ("SHORT".equals(t.getSide())) {
                shortCount++;
                if (t.getNetPnl() != null && t.getNetPnl().compareTo(java.math.BigDecimal.ZERO) > 0) shortWin++;
            } else if (t.getSide() != null) {
                longCount++;
                if (t.getNetPnl() != null && t.getNetPnl().compareTo(java.math.BigDecimal.ZERO) > 0) longWin++;
            }
        }
        response.setLongTradeCount(longCount);
        response.setShortTradeCount(shortCount);
        response.setLongWinRate(longCount == 0 ? null : scale((double) longWin / longCount, 6));
        response.setShortWinRate(shortCount == 0 ? null : scale((double) shortWin / shortCount, 6));
        response.setMarketOpenPrice(entity.getMarketOpenPrice());
        response.setMarketClosePrice(entity.getMarketClosePrice());
        response.setMarketHighPrice(entity.getMarketHighPrice());
        response.setMarketLowPrice(entity.getMarketLowPrice());
        response.setMarketVolatilityPct(entity.getMarketVolatilityPct());
        response.setMarketPriceChangePct(entity.getMarketPriceChangePct());
        response.setMarketTrend(entity.getMarketTrend());
        response.setBenchmarkReturn(entity.getBenchmarkReturn());

        return response;
    }
}
