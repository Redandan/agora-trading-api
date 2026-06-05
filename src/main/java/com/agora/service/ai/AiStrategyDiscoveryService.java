package com.agora.service.ai;

import com.agora.dto.backtest.AiStrategyDiscoveryRequest;
import com.agora.dto.backtest.AiStrategyDiscoveryResponse;
import com.agora.dto.backtest.AiStrategyDiscoveryResponse.CandidateResult;
import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.dto.backtest.SopMtfAdxConfig;
import com.agora.model.BtBacktestResult;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.MdKlineRepository;
import com.agora.service.BacktestService;
import com.agora.service.BtStrategyService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * AI 策略自動探勘服務
 * <p>
 * 流程：
 * 1. 呼叫 Groq AI 取得 N 組候選 {@link SopMtfAdxConfig} 參數
 * 2. 將每組參數建立為策略並執行回測
 * 3. 依評分（totalReturn × winRate / (1 + maxDrawdown)，並加入 sharpeRatio 加權）排序
 * 4. 回傳排序後的候選清單及最佳策略
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStrategyDiscoveryService {

    private static final String STRATEGY_TYPE = "SOP_MTF_ADX";
    private static final int MIN_TRADE_COUNT = 5;
    private static final double SHARPE_WEIGHT = 0.2;
    /** 頻率乘數最大加成（每月 ≥4 筆拿滿；1 筆/月只有 0.85 倍基礎分） */
    private static final double FREQ_WEIGHT   = 0.15;
    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final GroqApiClient groqApiClient;
    private final BtStrategyService btStrategyService;
    private final BacktestService backtestService;
    private final ObjectMapper objectMapper;
    private final MdKlineRepository klineRepository;
    private final BtBacktestResultRepository btBacktestResultRepository;
    private final VectorbtCandidateLoader vectorbtCandidateLoader;

    /**
     * 市場快照讀取的 K 線資料源（應與 LiveSignalEvaluator 對齊，避免信號評估與 4h 趨勢分析使用不同源）。
     */
    @org.springframework.beans.factory.annotation.Value("${market.signal.source:okx}")
    private String signalSource;

    /**
     * When {@code false} (default), strategy discovery skips the Groq API call and uses
     * the built-in calibrated candidate sets directly ({@link #buildDefaultCandidates} /
     * {@link #buildDefaultCandidatesByStyle}).  Set to {@code true} to re-enable AI-generated
     * parameter suggestions (opt-in, requires a valid {@code GROQ_API_KEY}).
     */
    @org.springframework.beans.factory.annotation.Value("${trading.discovery.ai-suggestions.enabled:false}")
    private boolean aiSuggestionsEnabled;

    /** 專為解析 AI 回傳 JSON 的 ObjectMapper，允許未知欄位（如 rationale） */
    private final ObjectMapper aiJsonMapper =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 候選回測並行執行緒池（虛擬執行緒，無限制並發，消除回測排隊等待） */
    private final ExecutorService candidateExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @PreDestroy
    public void shutdown() {
        candidateExecutor.shutdown();
    }

    /**
     * 執行一次完整的 AI 策略探勘流程。
     *
     * @param request 探勘請求（交易對、週期、回測區間、候選數量）
     * @return 含所有候選評分及最佳策略的結果
     */
    public AiStrategyDiscoveryResponse discover(AiStrategyDiscoveryRequest request) {
        String batchId = LocalDateTime.now().format(BATCH_FMT);
        String symbol = request.getSymbol().toUpperCase().trim();
        String intervalCode = request.getIntervalCode().toLowerCase().trim();

        AiStrategyDiscoveryResponse response = new AiStrategyDiscoveryResponse();
        response.setDiscoveryBatch(batchId);
        response.setSymbol(symbol);
        response.setIntervalCode(intervalCode);
        response.setStartTime(request.getStartTime());
        response.setEndTime(request.getEndTime());

        // 1. 向 AI 取得候選配置
        List<CandidateConfig> candidateConfigs = generateCandidateConfigs(request.getCandidateCount(), symbol, intervalCode);
        if (candidateConfigs.isEmpty()) {
            log.warn("[AI探勘 {}] AI 未能產生有效候選配置，探勘結束", batchId);
            response.setCandidates(Collections.emptyList());
            response.setTotalCandidates(0);
            return response;
        }

        // 2. 對每個候選並行建立策略並執行回測
        AtomicInteger idx = new AtomicInteger(1);
        BigDecimal feeRate = request.getFeeRate();
        int minTradeCount = request.getMinTradeCount();
        List<CompletableFuture<CandidateResult>> futures = new ArrayList<CompletableFuture<CandidateResult>>();
        for (CandidateConfig cc : candidateConfigs) {
            final int i = idx.getAndIncrement();
            final String strategyName = "AI-" + batchId + "-" + i;
            final CandidateConfig config = cc;
            futures.add(CompletableFuture.supplyAsync(
                    () -> evaluateCandidate(strategyName, config, batchId,
                            symbol, intervalCode, request.getStartTime(), request.getEndTime(),
                            request.getInitialCapital(), feeRate, minTradeCount, request.getSource()),
                    candidateExecutor));
        }

        List<CandidateResult> results = futures.stream()
                .map(f -> {
                    try {
                        return f.get(60, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("[AI探勘 {}] 候選回測等待超時或異常: {}", batchId, e.getMessage());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 3. 由高到低排序
        results.sort(Comparator.comparingDouble(CandidateResult::getScore).reversed());

        // 4. 統計
        int validCount = (int) results.stream().filter(r -> r.getScore() > 0).count();
        int failedCount = (int) results.stream().filter(r -> r.getErrorMessage() != null).count();
        response.setCandidates(results);
        response.setTotalCandidates(results.size());
        response.setValidCount(validCount);
        response.setFailedCount(failedCount);
        response.setBestStrategy(results.stream().filter(r -> r.getScore() > 0).findFirst().orElse(null));

        if (response.getBestStrategy() != null) {
            log.info("[AI探勘 {}] 最佳策略: {} score={}", batchId,
                    response.getBestStrategy().getStrategyName(),
                    response.getBestStrategy().getScore());
        }
        return response;
    }

    // ─── 私有方法 ────────────────────────────────────────────────────────────

    /**
     * 呼叫 Groq AI，取得 N 組候選 SopMtfAdxConfig 及說明。
     * When {@code trading.discovery.ai-suggestions.enabled=false} (default), skips the Groq
     * call and returns the built-in calibrated default candidates immediately.
     */
    List<CandidateConfig> generateCandidateConfigs(int count, String symbol, String intervalCode) {
        // vectorbt pre-screened candidates take priority over LLM / default candidates.
        // Falls back to LLM / defaults when no file exists or file is stale.
        List<com.agora.dto.backtest.SopMtfAdxConfig> vbtCandidates =
                vectorbtCandidateLoader.load(symbol, intervalCode, count);
        if (!vbtCandidates.isEmpty()) {
            log.info("[AI探勘] using {} vectorbt candidates (symbol={} interval={})",
                    vbtCandidates.size(), symbol, intervalCode);
            return vbtCandidates.stream()
                    .map(cfg -> new CandidateConfig(cfg, "vectorbt walk-forward pre-screened"))
                    .collect(java.util.stream.Collectors.toList());
        }

        if (!aiSuggestionsEnabled) {
            log.info("[AI探勘] ai-suggestions disabled，使用預設候選配置 (symbol={} interval={})", symbol, intervalCode);
            return buildDefaultCandidates(count);
        }
        if (!groqApiClient.isEnabled()) {
            log.info("[AI探勘] Groq API 未啟用，改用預設候選配置");
            return buildDefaultCandidates(count);
        }

        String prompt = buildPrompt(count, symbol, intervalCode);
        List<Map<String, String>> messages = new ArrayList<Map<String, String>>();
        messages.add(buildMessage("system", buildSystemPrompt()));
        messages.add(buildMessage("user", prompt));

        String aiResponse = groqApiClient.chat(messages, 1500, 0.3);
        if (aiResponse == null || aiResponse.trim().isEmpty()) {
            log.warn("[AI探勘] AI 未回覆，改用預設候選配置");
            return buildDefaultCandidates(count);
        }

        List<CandidateConfig> parsed = parseAiResponse(aiResponse, count);
        if (parsed.isEmpty()) {
            log.warn("[AI探勘] AI 回覆無法解析，改用預設候選配置");
            return buildDefaultCandidates(count);
        }
        parsed.forEach(cc -> cc.config.setEnableMtf(false));  // 強制關閉 MTF（無 1d K 線）
        return parsed;
    }

    /**
     * 建立策略、執行回測、計算評分，並回傳 CandidateResult。
     * 無論建立或回測失敗，皆回傳帶有 errorMessage 的結果（score=0），不回傳 null。
     */
    private CandidateResult evaluateCandidate(String strategyName,
                                              CandidateConfig cc,
                                              String batchId,
                                              String symbol,
                                              String intervalCode,
                                              LocalDateTime startTime,
                                              LocalDateTime endTime,
                                              BigDecimal initialCapital,
                                              BigDecimal feeRate,
                                              int minTradeCount,
                                              String source) {
        BtStrategy strategy;
        try {
            strategy = btStrategyService.createAiGeneratedStrategy(strategyName, STRATEGY_TYPE, cc.config, batchId, symbol);
        } catch (Exception e) {
            log.warn("[AI探勘 {}] 建立策略 {} 失敗: {}", batchId, strategyName, e.getMessage());
            return buildFailedCandidateResult(null, strategyName, cc, "建立策略失敗: " + e.getMessage());
        }

        BacktestRunRequest runReq = new BacktestRunRequest();
        runReq.setStrategyId(strategy.getId());
        runReq.setSymbol(symbol);
        runReq.setIntervalCode(intervalCode);
        runReq.setStartTime(startTime);
        runReq.setEndTime(endTime);
        runReq.setInitialCapital(initialCapital);
        runReq.setFeeRate(feeRate);
        if (source != null && !source.isBlank()) {
            runReq.setSource(source);
        }

        BacktestResultResponse backtestResult;
        try {
            backtestResult = backtestService.runForExploration(runReq);
        } catch (Exception e) {
            log.warn("[AI探勘 {}] 策略 {} 回測失敗: {}", batchId, strategyName, e.getMessage());
            return buildFailedCandidateResult(strategy.getId(), strategyName, cc, "回測失敗: " + e.getMessage());
        }

        long lookbackDays = ChronoUnit.DAYS.between(startTime, endTime);
        double score = computeScore(backtestResult, minTradeCount, lookbackDays);
        CandidateResult result = new CandidateResult();
        result.setStrategyId(strategy.getId());
        result.setStrategyName(strategyName);
        result.setTotalReturn(backtestResult.getTotalReturn());
        result.setMaxDrawdown(backtestResult.getMaxDrawdown());
        result.setWinRate(backtestResult.getWinRate());
        result.setSharpeRatio(backtestResult.getSharpeRatio());
        result.setTradeCount(backtestResult.getTradeCount());
        result.setScore(score);
        result.setConfig(btStrategyService.parseConfig(strategy.getConfigJson()));
        result.setAiRationale(cc.rationale);

        // Walk-Forward 30 天樣本外驗證（score > 0 時執行，偵測過擬合）
        if (score > 0) {
            result.setWalkForwardNote(runWalkForward(strategy.getId(), symbol, intervalCode, initialCapital, feeRate, source));
        }

        return result;
    }

    /**
     * 綜合評分 = totalReturn × winRate / (1 + maxDrawdown) × sharpeMultiplier × freqMultiplier
     * <p>
     * sharpeMultiplier = 1 + {@link #SHARPE_WEIGHT} × max(sharpe, 0)。
     * freqMultiplier   = 0.85 + {@link #FREQ_WEIGHT} × min(tradesPerMonth / 4, 1)，
     * 確保交易頻率太低（每月 <1 筆）的策略得到懲罰（最低 0.85×），而每月 ≥4 筆則拿滿加成。
     * 若交易次數不足 minTradeCount，評分為 0（視為無效策略）。
     */
    double computeScore(BacktestResultResponse result, int minTradeCount, long lookbackDays) {
        if (result.getTradeCount() == null || result.getTradeCount() < minTradeCount) {
            return 0.0;
        }
        double totalReturn = result.getTotalReturn() == null ? 0.0 : result.getTotalReturn().doubleValue();
        double winRate     = result.getWinRate()     == null ? 0.0 : result.getWinRate().doubleValue();
        double maxDrawdown = result.getMaxDrawdown() == null ? 0.0 : result.getMaxDrawdown().doubleValue();

        double base = totalReturn * winRate / (1.0 + maxDrawdown);

        double sharpe = result.getSharpeRatio() != null ? result.getSharpeRatio().doubleValue() : 0.0;
        double sharpeMultiplier = 1.0 + SHARPE_WEIGHT * Math.max(sharpe, 0.0);

        // 頻率乘數：每月 ≥4 筆拿滿；不足 1 筆/月罰 0.85×
        double months         = lookbackDays > 0 ? lookbackDays / 30.0 : 6.0;
        double tradesPerMonth = months > 0 ? result.getTradeCount() / months : result.getTradeCount();
        double freqMultiplier = 0.85 + FREQ_WEIGHT * Math.min(tradesPerMonth / 4.0, 1.0);

        return base * sharpeMultiplier * freqMultiplier;
    }

    /** 向下相容的雙參版本（無 lookbackDays，預設 180 天）。 */
    double computeScore(BacktestResultResponse result, int minTradeCount) {
        return computeScore(result, minTradeCount, 180L);
    }

    /** 向下相容的無參版本，使用預設 MIN_TRADE_COUNT（供測試呼叫）。 */
    double computeScore(BacktestResultResponse result) {
        return computeScore(result, MIN_TRADE_COUNT, 180L);
    }

    /**
     * AI 系統提示：說明策略結構與輸出格式。
     */
    private String buildSystemPrompt() {
        return "你是一名量化交易策略顧問，專精於加密貨幣市場的技術分析策略設計。\n" +
               "你將根據用戶要求，輸出多組 SOP_MTF_ADX 策略的參數配置。\n" +
               "每組配置必須是合法的 JSON 物件，並附帶一句簡短的策略說明（rationale 欄位）。\n" +
               "輸出格式必須嚴格遵守 JSON array，不要包含任何 markdown、程式碼區塊或額外說明文字。";
    }

    /**
     * 使用者提示：請 AI 針對特定交易對和週期產生 N 組策略配置。
     */
    private String buildPrompt(int count, String symbol, String intervalCode) {
        return "請為 " + symbol + " " + intervalCode + " 市場生成 " + count + " 組 SOP_MTF_ADX 策略參數配置，" +
               "各組參數應具有差異性（保守型、均衡型、進取型等）。\n\n" +
               "每組 JSON 物件包含以下欄位（皆可選填，null 表示使用預設值）：\n" +
               "- minSignals: integer 1~5（訊號門檻）\n" +
               "- adxEntryThreshold: double > 0（ADX 進場門檻）\n" +
               "- fixedStopLossPct: double 0~0.1（固定止損比例）\n" +
               "- fixedTakeProfitPct: double 0~0.2（固定止盈比例）\n" +
               "- maxHoldingHours: integer >= 0（最大持倉時數，0 不限制）\n" +
               "- rsiPullbackThreshold: double 0~100（RSI 回調門檻）\n" +
               "- minRR: double > 0（最小風報比）\n" +
               "- allowShort: boolean（是否啟用做空）\n" +
               "- atrTrailingStopEnabled: boolean（ATR 追蹤止損）\n" +
               "- rationale: string（此組策略設計理念，一句話）\n\n" +
               "輸出範例：\n" +
               "[{\"minSignals\":3,\"adxEntryThreshold\":25.0,\"fixedStopLossPct\":0.015," +
               "\"fixedTakeProfitPct\":0.03,\"minRR\":2.0,\"allowShort\":false,\"rationale\":\"均衡型\"}]\n\n" +
               "請輸出 " + count + " 組，只輸出 JSON array，不包含任何其他文字。";
    }

    /**
     * 解析 AI 回覆中的 JSON array，轉換為 CandidateConfig 清單。
     */
    List<CandidateConfig> parseAiResponse(String aiResponse, int maxCount) {
        List<CandidateConfig> result = new ArrayList<CandidateConfig>();
        try {
            String cleaned = extractJsonArray(aiResponse);
            if (cleaned == null || cleaned.trim().isEmpty()) {
                return result;
            }
            JsonNode root = aiJsonMapper.readTree(cleaned);
            if (!root.isArray()) {
                return result;
            }

            int count = 0;
            for (JsonNode node : root) {
                if (count >= maxCount) break;
                try {
                    SopMtfAdxConfig config = aiJsonMapper.treeToValue(node, SopMtfAdxConfig.class);
                    String rationale = node.has("rationale") ? node.get("rationale").asText(null) : null;
                    result.add(new CandidateConfig(config, rationale));
                    count++;
                } catch (Exception e) {
                    log.debug("[AI探勘] 解析單一配置失敗，跳過: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[AI探勘] 解析 AI 回覆失敗: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 從 AI 回覆文字中抽取第一個 JSON array（去除 markdown 程式碼區塊等雜訊）。
     */
    private String extractJsonArray(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        // 去除可能的 markdown code block
        if (trimmed.startsWith("```")) {
            int end = trimmed.lastIndexOf("```");
            if (end > 3) {
                trimmed = trimmed.substring(trimmed.indexOf('\n') + 1, end).trim();
            }
        }
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return trimmed.substring(start, end + 1);
    }

    /**
     * 當 Groq 未啟用或解析失敗時，使用內建的合理預設配置。
     */
    List<CandidateConfig> buildDefaultCandidates(int count) {
        List<CandidateConfig> defaults = new ArrayList<CandidateConfig>();

        SopMtfAdxConfig conservative = new SopMtfAdxConfig();
        conservative.setEnableMtf(false);
        conservative.setMinSignals(4);
        conservative.setAdxEntryThreshold(30.0);
        conservative.setFixedStopLossPct(0.012);
        conservative.setFixedTakeProfitPct(0.025);
        conservative.setMinRR(2.0);
        defaults.add(new CandidateConfig(conservative, "保守型：高 ADX 門檻、多訊號確認、低止損比例"));

        if (count >= 2) {
            SopMtfAdxConfig balanced = new SopMtfAdxConfig();
            balanced.setEnableMtf(false);
            balanced.setMinSignals(3);
            balanced.setAdxEntryThreshold(25.0);
            balanced.setFixedStopLossPct(0.015);
            balanced.setFixedTakeProfitPct(0.03);
            balanced.setMinRR(1.8);
            defaults.add(new CandidateConfig(balanced, "均衡型：中等門檻，適合多空趨勢市場"));
        }

        if (count >= 3) {
            SopMtfAdxConfig aggressive = new SopMtfAdxConfig();
            aggressive.setEnableMtf(false);
            aggressive.setMinSignals(2);
            aggressive.setAdxEntryThreshold(20.0);
            aggressive.setFixedStopLossPct(0.02);
            aggressive.setFixedTakeProfitPct(0.05);
            aggressive.setMinRR(1.5);
            aggressive.setAtrTrailingStopEnabled(true);
            aggressive.setAtrMultiplier(2.0);
            aggressive.setAtrPeriod(14);
            defaults.add(new CandidateConfig(aggressive, "進取型：寬鬆門檻、ATR 追蹤止損、高止盈比例"));
        }

        if (count >= 4) {
            SopMtfAdxConfig shortBias = new SopMtfAdxConfig();
            shortBias.setEnableMtf(false);
            shortBias.setMinSignals(3);
            shortBias.setAdxEntryThreshold(28.0);
            shortBias.setFixedStopLossPct(0.012);
            shortBias.setFixedTakeProfitPct(0.024);
            shortBias.setMinRR(2.0);
            shortBias.setRsiPullbackThreshold(40.0);
            shortBias.setRsiSellThreshold(62.0);
            defaults.add(new CandidateConfig(shortBias, "嚴格 RSI 型：多空均衡，嚴格 RSI 過濾"));
        }

        if (count >= 5) {
            SopMtfAdxConfig scalping = new SopMtfAdxConfig();
            scalping.setEnableMtf(false);
            scalping.setMinSignals(3);
            scalping.setAdxEntryThreshold(22.0);
            scalping.setFixedStopLossPct(0.008);
            scalping.setFixedTakeProfitPct(0.015);
            scalping.setMaxHoldingHours(24);
            scalping.setMinRR(1.8);
            scalping.setMoveSlToBreakeven(true);
            defaults.add(new CandidateConfig(scalping, "短線型：小止損止盈、限制持倉時數、移至成本止損"));
        }

        return defaults.subList(0, Math.min(count, defaults.size()));
    }

    private CandidateResult buildFailedCandidateResult(Long strategyId, String strategyName,
                                                       CandidateConfig cc, String errorMessage) {
        CandidateResult result = new CandidateResult();
        result.setStrategyId(strategyId);
        result.setStrategyName(strategyName);
        result.setTotalReturn(BigDecimal.ZERO);
        result.setMaxDrawdown(BigDecimal.ZERO);
        result.setWinRate(BigDecimal.ZERO);
        result.setTradeCount(0);
        result.setScore(0.0);
        result.setAiRationale(cc.rationale);
        result.setErrorMessage(errorMessage);
        return result;
    }

    /**
     * Walk-Forward 驗證：對通過品質門檻的策略執行 30 天樣本外回測，
     * 偵測是否存在過擬合（回測期結束後報酬驟降）。
     */
    private String runWalkForward(Long strategyId, String symbol, String intervalCode,
                                   BigDecimal capital, BigDecimal feeRate, String source) {
        try {
            LocalDateTime wfEnd   = LocalDateTime.now();
            LocalDateTime wfStart = wfEnd.minusDays(30);

            BacktestRunRequest wfReq = new BacktestRunRequest();
            wfReq.setStrategyId(strategyId);
            wfReq.setSymbol(symbol);
            wfReq.setIntervalCode(intervalCode);
            wfReq.setStartTime(wfStart);
            wfReq.setEndTime(wfEnd);
            wfReq.setInitialCapital(capital);
            wfReq.setFeeRate(feeRate);
            if (source != null && !source.isBlank()) {
                wfReq.setSource(source);
            }
            // 關鍵:WF 不寫 bt_backtest_result,避免覆蓋主回測為「最新」
            // 否則 enableStrategy 抓到 WF 的 30d 結果(0-3 筆)誤判不達標
            wfReq.setSkipPersist(true);

            BacktestResultResponse wf = backtestService.runForExploration(wfReq);
            if (wf == null || wf.getTradeCount() == null || wf.getTradeCount() == 0) {
                return "WF30d: 無交易（0筆）⚠️";
            }
            double ret  = wf.getTotalReturn() != null ? wf.getTotalReturn().doubleValue() * 100 : 0;
            String icon = ret >= 0 ? "✅" : "⚠️";
            return String.format("WF30d: %+.1f%% (%d筆) %s", ret, wf.getTradeCount(), icon);
        } catch (Exception e) {
            log.debug("[Walk-Forward] 策略 {} 驗證失敗: {}", strategyId, e.getMessage());
            return "WF30d: 驗證失敗（" + e.getMessage() + "）";
        }
    }

    /**
     * 從歷史回測結果中載入最佳參數作為候選基準（最多 2 個）。
     * 解決冷啟動問題：新探勘時可參考歷史上表現最佳的策略參數。
     */
    private List<CandidateConfig> loadHistoricalAnchors(String symbol, String intervalCode) {
        try {
            List<BtBacktestResult> top = btBacktestResultRepository
                    .findTopPerformingBySymbolAndInterval(symbol, intervalCode, PageRequest.of(0, 2));
            List<CandidateConfig> anchors = new ArrayList<>();
            for (BtBacktestResult r : top) {
                try {
                    if (r.getConfigSnapshotJson() == null || r.getConfigSnapshotJson().isBlank()) continue;
                    SopMtfAdxConfig cfg = aiJsonMapper.readValue(r.getConfigSnapshotJson(), SopMtfAdxConfig.class);
                    cfg.setEnableMtf(false);
                    double ret = r.getTotalReturn() != null ? r.getTotalReturn().doubleValue() * 100 : 0;
                    String rationale = String.format("歷史錨點（回測 +%.1f%%, %d筆）", ret, r.getTradeCount());
                    anchors.add(new CandidateConfig(cfg, rationale));
                } catch (Exception e) {
                    log.debug("[歷史錨點] 解析配置失敗，跳過: {}", e.getMessage());
                }
            }
            return anchors;
        } catch (Exception e) {
            log.warn("[歷史錨點] 查詢失敗: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private Map<String, String> buildMessage(String role, String content) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    // ─── Adaptive Discovery（市場感知 + 風格化）────────────────────────────────

    /**
     * 根據當前 K 線指標與指定交易風格，生成並回測候選策略。
     *
     * @param request 探勘請求（與 discover 相同）
     * @param style   風格：HIGH_FREQ / TREND / CONSERVATIVE / BALANCED（預設）
     */
    public AiStrategyDiscoveryResponse discoverAdaptive(AiStrategyDiscoveryRequest request, String style) {
        String batchId   = LocalDateTime.now().format(BATCH_FMT);
        String symbol    = request.getSymbol().toUpperCase().trim();
        String interval  = request.getIntervalCode().toLowerCase().trim();
        String styleUpper = (style != null && !style.isBlank()) ? style.toUpperCase().trim() : "BALANCED";

        AiStrategyDiscoveryResponse response = new AiStrategyDiscoveryResponse();
        response.setDiscoveryBatch(batchId);
        response.setSymbol(symbol);
        response.setIntervalCode(interval);
        response.setStartTime(request.getStartTime());
        response.setEndTime(request.getEndTime());

        // 1. 計算市場快照
        MarketSnapshot snapshot = buildMarketSnapshot(symbol, interval);
        log.info("[AI自適應探勘 {}] 市場快照: {} style={}", batchId, snapshot.summary(), styleUpper);

        // 2. 向 AI 取得風格化候選配置
        List<CandidateConfig> candidateConfigs = generateAdaptiveCandidateConfigs(
                request.getCandidateCount(), symbol, interval, snapshot, styleUpper);
        if (candidateConfigs.isEmpty()) {
            log.warn("[AI自適應探勘 {}] 無有效候選配置，探勘結束", batchId);
            response.setCandidates(Collections.emptyList());
            response.setTotalCandidates(0);
            return response;
        }

        // 3. 並行評估候選（復用現有邏輯）
        AtomicInteger idx = new AtomicInteger(1);
        List<CompletableFuture<CandidateResult>> futures = new ArrayList<>();
        for (CandidateConfig cc : candidateConfigs) {
            final int i = idx.getAndIncrement();
            final String name = "AI-" + styleUpper + "-" + batchId + "-" + i;
            futures.add(CompletableFuture.supplyAsync(
                    () -> evaluateCandidate(name, cc, batchId,
                            symbol, interval, request.getStartTime(), request.getEndTime(),
                            request.getInitialCapital(), request.getFeeRate(), request.getMinTradeCount(),
                            request.getSource()),
                    candidateExecutor));
        }

        List<CandidateResult> results = futures.stream()
                .map(f -> { try { return f.get(60, TimeUnit.SECONDS); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(CandidateResult::getScore).reversed())
                .collect(Collectors.toList());

        int validCount  = (int) results.stream().filter(r -> r.getScore() > 0).count();
        int failedCount = (int) results.stream().filter(r -> r.getErrorMessage() != null).count();
        response.setCandidates(results);
        response.setTotalCandidates(results.size());
        response.setValidCount(validCount);
        response.setFailedCount(failedCount);
        response.setBestStrategy(results.stream().filter(r -> r.getScore() > 0).findFirst().orElse(null));

        if (response.getBestStrategy() != null) {
            log.info("[AI自適應探勘 {}] 最佳: {} score={} style={}",
                    batchId, response.getBestStrategy().getStrategyName(),
                    response.getBestStrategy().getScore(), styleUpper);
        }
        return response;
    }

    // ─── External-AI Validation（外部 AI 提供候選，後端只跑回測）────────────────

    /**
     * 取得市場快照的格式化文字，供外部 AI 推理使用。
     */
    public String getMarketSnapshotText(String symbol, String intervalCode) {
        MarketSnapshot snap = buildMarketSnapshot(symbol, intervalCode);
        return snap.toPromptText();
    }

    /**
     * 接受外部 AI 提供的 JSON 候選參數，執行並行回測驗證。
     * 預設注入歷史錨點(解冷啟動),適合 AI discovery 場景。
     *
     * @param request      探勘請求（symbol、interval、時間範圍、capital 等）
     * @param candidatesJson SopMtfAdxConfig JSON array 字串（含可選 rationale 欄位）
     * @return 排序後的回測結果
     */
    public AiStrategyDiscoveryResponse runWithExternalCandidatesJson(
            AiStrategyDiscoveryRequest request, String candidatesJson) {
        return runWithExternalCandidatesJson(request, candidatesJson, false);
    }

    /**
     * 接受外部 AI 提供的 JSON 候選參數,執行並行回測驗證。
     *
     * @param skipAnchors true = 不注入歷史錨點(robustness 掃描應用,避免錨點 fingerprint
     *                    碰到 subject 策略導致 dedup 返回既有策略 → 後續 cleanup 誤刪)
     */
    public AiStrategyDiscoveryResponse runWithExternalCandidatesJson(
            AiStrategyDiscoveryRequest request, String candidatesJson, boolean skipAnchors) {
        String batchId  = "EXT-" + LocalDateTime.now().format(BATCH_FMT);
        String symbol   = request.getSymbol().toUpperCase().trim();
        String interval = request.getIntervalCode().toLowerCase().trim();

        AiStrategyDiscoveryResponse response = new AiStrategyDiscoveryResponse();
        response.setDiscoveryBatch(batchId);
        response.setSymbol(symbol);
        response.setIntervalCode(interval);
        response.setStartTime(request.getStartTime());
        response.setEndTime(request.getEndTime());

        List<CandidateConfig> candidates = parseAiResponse(candidatesJson, request.getCandidateCount());
        if (candidates.isEmpty()) {
            log.warn("[外部AI驗證 {}] 無法解析 candidatesJson，驗證結束", batchId);
            response.setCandidates(Collections.emptyList());
            response.setTotalCandidates(0);
            return response;
        }

        // 強制關閉 MTF（無 1d K 線）
        candidates.forEach(cc -> cc.config.setEnableMtf(false));

        // 在候選前面插入歷史錨點（最多 2 個），解決冷啟動問題
        // robustness 場景跳過(skipAnchors=true),避免錨點誤傷 subject 策略
        if (!skipAnchors) {
            List<CandidateConfig> anchors = loadHistoricalAnchors(symbol, interval);
            if (!anchors.isEmpty()) {
                List<CandidateConfig> combined = new ArrayList<>(anchors);
                combined.addAll(candidates);
                candidates = combined;
                log.info("[外部AI驗證 {}] 加入 {} 個歷史錨點候選", batchId, anchors.size());
            }
        }

        log.info("[外部AI驗證 {}] 開始驗證 {} 個候選（symbol={} interval={}）",
                batchId, candidates.size(), symbol, interval);

        // 並行評估（複用現有邏輯）
        AtomicInteger idx = new AtomicInteger(1);
        List<CompletableFuture<CandidateResult>> futures = new ArrayList<>();
        for (CandidateConfig cc : candidates) {
            final int i = idx.getAndIncrement();
            final String name = batchId + "-" + i;
            futures.add(CompletableFuture.supplyAsync(
                    () -> evaluateCandidate(name, cc, batchId,
                            symbol, interval, request.getStartTime(), request.getEndTime(),
                            request.getInitialCapital(), request.getFeeRate(), request.getMinTradeCount(),
                            request.getSource()),
                    candidateExecutor));
        }

        List<CandidateResult> results = futures.stream()
                .map(f -> { try { return f.get(60, TimeUnit.SECONDS); } catch (Exception e) { return null; } })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(CandidateResult::getScore).reversed())
                .collect(Collectors.toList());

        int validCount  = (int) results.stream().filter(r -> r.getScore() > 0).count();
        int failedCount = (int) results.stream().filter(r -> r.getErrorMessage() != null).count();
        response.setCandidates(results);
        response.setTotalCandidates(results.size());
        response.setValidCount(validCount);
        response.setFailedCount(failedCount);
        response.setBestStrategy(results.stream().filter(r -> r.getScore() > 0).findFirst().orElse(null));

        if (response.getBestStrategy() != null) {
            log.info("[外部AI驗證 {}] 最佳: {} score={}",
                    batchId, response.getBestStrategy().getStrategyName(),
                    response.getBestStrategy().getScore());
        }
        return response;
    }

    // ─── Market Analysis（多時框 + ATR 校準 + 自動候選生成）──────────────────────

    /**
     * 完整市場分析：1h+4h 雙時框快照、形態分類、ATR 校準、自動生成 ready-to-use 候選 JSON。
     * 一次呼叫即可取得所有資訊，直接傳入 validateCandidates 執行回測。
     */
    public String getMarketAnalysis(String symbol, String intervalCode) {
        MarketSnapshot snapP = buildMarketSnapshot(symbol, intervalCode);
        String ctx = "1h".equals(intervalCode) ? "4h" : "1h";
        MarketSnapshot snapC = buildMarketSnapshot(symbol, ctx);

        String regime   = classifyRegime(snapP, snapC);
        String candJson = buildCandidatesJsonByRegime(snapP, regime);

        // ATR 校準（%）
        double atr  = snapP.atrPct();
        double tSL  = clampPct(atr * 0.75);  double tTP = tSL  * 2.0;
        double mSL  = clampPct(atr * 1.2);   double mTP = mSL  * 2.5;
        double wSL  = clampPct(atr * 1.7);   double wTP = wSL  * 2.5;

        String suggestion = switch (regime) {
            case "TRENDING_BULLISH"  -> "1h+4h 雙牛 → TREND 多方，不做空";
            case "TRENDING_BEARISH"  -> "1h+4h 雙熊 → TREND 雙向，以空為主";
            case "VOLATILE"          -> "ATR>2%，高波動 → 放寬 SL，ATR 追蹤止損";
            case "RECOVERY"          -> "1h 橫盤 + 4h 偏多 → 可能反轉，偏多策略";
            case "CONSOLIDATING"     -> "低波動橫盤 → HIGH_FREQ 雙向，等待方向確立";
            default                  -> "混合信號 → BALANCED 雙向，中等過濾";
        };

        String macdDirP = snapP.macdHistogram() > 0 ? "看多" : snapP.macdHistogram() < 0 ? "看空" : "中性";
        String macdDirC = snapC.macdHistogram() > 0 ? "看多" : snapC.macdHistogram() < 0 ? "看空" : "中性";

        return String.format(
            "=== %s 市場分析 ===\n\n" +
            "📊 %s  close=%.0f  RSI=%.1f(%s)  ATR=%.2f%%  ADX=%.1f  MACD%+.4f(%s)  trend=%s  vol=%.1fx(%s)\n" +
            "📊 %s  close=%.0f  RSI=%.1f(%s)  ATR=%.2f%%  ADX=%.1f  MACD%+.4f(%s)  trend=%s  vol=%.1fx(%s)\n\n" +
            "🏷 市場形態：%s\n→ %s\n\n" +
            "📐 ATR 校準（%s ATR=%.2f%%）：\n" +
            "   緊 SL=%.2f%% TP=%.2f%%（RR=2.0）\n" +
            "   中 SL=%.2f%% TP=%.2f%%（RR=2.5）\n" +
            "   寬 SL=%.2f%% TP=%.2f%%（RR=2.5）\n\n" +
            "📋 候選 JSON（直接用於 validateCandidates candidatesJson）：\n%s\n\n" +
            "▶ validateCandidates(\"%s\",\"%s\",180,5,\"[上方JSON]\")",
            symbol,
            intervalCode,
            snapP.lastClose(), snapP.rsi14(), rsiLabel(snapP.rsi14()),
            snapP.atrPct(), snapP.adx14(), snapP.macdHistogram(), macdDirP,
            snapP.trendDirection(), snapP.volumeRatio(), volLabel(snapP.volumeRatio()),
            ctx,
            snapC.lastClose(), snapC.rsi14(), rsiLabel(snapC.rsi14()),
            snapC.atrPct(), snapC.adx14(), snapC.macdHistogram(), macdDirC,
            snapC.trendDirection(), snapC.volumeRatio(), volLabel(snapC.volumeRatio()),
            regime, suggestion,
            intervalCode, atr, tSL, tTP, mSL, mTP, wSL, wTP,
            candJson,
            symbol, intervalCode
        );
    }

    /** 根據市場形態和 ATR 校準值生成 5 組候選參數 JSON（ADX 門檻動態校準）。 */
    private String buildCandidatesJsonByRegime(MarketSnapshot snap, String regime) {
        double a = snap.atrPct() / 100.0;
        double tSL = clampDec(a * 0.75);  double tTP = tSL * 2.0;
        double mSL = clampDec(a * 1.2);   double mTP = mSL * 2.5;
        double wSL = clampDec(a * 1.7);   double wTP = wSL * 2.5;

        // 動態 ADX 門檻（基於當前 ADX 水平，確保策略參數與市場趨勢強度一致）
        double curAdx      = snap.adx14() > 0 ? snap.adx14() : 20.0;
        int adxImmediate   = (int) Math.max(8,  curAdx * 0.75);
        int adxModerate    = (int) Math.max(12, curAdx * 1.0);
        int adxSelective   = (int) Math.max(16, curAdx * 1.4);
        int adxStrict      = (int) Math.max(20, curAdx * 1.8);

        // MACD 柱狀方向：影響橫盤/整合形態的 allowShort 偏向
        boolean macdBullish = snap.macdHistogram() > 0;

        return switch (regime) {
            case "TRENDING_BULLISH" -> "[" +
                fc(adxModerate,  mSL, mTP,        false, true,  false, null, 2, null, null, "趨勢多-ATR追蹤") + "," +
                fc(adxSelective, wSL, wTP,         false, true,  false, null, 2, null, null, "趨勢多-寬SL追蹤") + "," +
                fc(adxImmediate, mSL, mTP * 1.3,  false, false, true,  null, 2, null, null, "趨勢多-移本損") + "," +
                fc(adxStrict,    mSL, mTP,         false, false, false, null, 3, null, 2.5,  "趨勢多-強確認") + "," +
                fc(adxImmediate, tSL, tTP * 1.5,  false, false, false,   72, 1, null, null, "趨勢多-快入場") + "]";
            case "TRENDING_BEARISH" -> "[" +
                fc(adxModerate,  mSL, mTP,         true,  true,  false, null, 2, null, null, "趨勢雙向-ATR追蹤") + "," +
                fc(adxSelective, wSL, wTP,         true,  true,  false, null, 2, null, null, "趨勢雙向-寬SL") + "," +
                fc(adxImmediate, mSL, mTP,         true,  false, true,  null, 2, null, null, "趨勢雙向-移本損") + "," +
                fc(adxStrict,    mSL, mTP,         true,  false, false, null, 3, null, 2.5,  "趨勢雙向-強確認") + "," +
                fc(adxImmediate, tSL, tTP,         true,  false, false,   48, 1, null, null, "偏空快入場") + "]";
            case "VOLATILE" -> "[" +
                fc(adxImmediate, wSL, wTP,         true,  true,  false, null, 2, null, null, "高波動ATR追蹤") + "," +
                fc(adxModerate,  wSL, wTP * 1.2,  true,  true,  false, null, 2, null, null, "高波動大TP") + "," +
                fc(adxImmediate, mSL, mTP,         true,  false, true,  null, 2, null, null, "高波動移本損") + "," +
                fc(adxSelective, wSL, wTP,         true,  false, false, null, 3, null, 2.5,  "高波動嚴格確認") + "," +
                fc(adxImmediate, mSL, mTP,         true,  true,  false,   48, 1, null, null, "高波動短持") + "]";
            case "RECOVERY" -> "[" +
                fc(adxImmediate, mSL, mTP * 1.3,  false, true,  false, null, 2, null, null, "回升多-ATR追蹤") + "," +
                fc(adxModerate,  mSL, mTP,         false, false, true,  null, 2, null, null, "回升多-移本損") + "," +
                fc(adxImmediate, tSL, tTP,         false, false, false,   48, 1, null, null, "回升多-快入") + "," +
                fc(adxSelective, mSL, mTP,         true,  false, false, null, 3, 40.0, 2.5,  "回升保守+RSI確認") + "," +
                fc(adxImmediate, tSL, tTP * 1.5,  false, false, false,   72, 1, null, null, "回升多-寬TP") + "]";
            default -> // CONSOLIDATING, SIDEWAYS, MIXED — MACD 方向影響 allowShort
                "[" +
                fc(adxImmediate, tSL, tTP,         !macdBullish, false, false,  24, 1, null, null, macdBullish ? "整合偏多-緊ATR" : "高頻雙向-緊ATR") + "," +
                fc(adxImmediate, mSL, mTP,         true,  false, false,   36, 2, null, null, "高頻雙向-中ATR") + "," +
                fc(adxModerate,  wSL, wTP,         true,  false, false, null, 2, null, null, "中等雙向突破") + "," +
                fc(adxSelective, mSL, mTP,         !macdBullish, false, false, null, 3, null, 2.5, macdBullish ? "整合偏多-保守" : "保守等趨勢確立") + "," +
                fc(adxImmediate, tSL, tTP * 1.5,  true,  false, true,    48, 1, null, null, "高頻移本損") + "]";
        };
    }

    /** 市場形態分類：根據主/輔時框趨勢方向、ATR、ADX 判斷（雙重確認）。 */
    private String classifyRegime(MarketSnapshot primary, MarketSnapshot context) {
        String tp     = primary.trendDirection();
        String tc     = context.trendDirection();
        double atr    = primary.atrPct();
        double adx    = primary.adx14();
        double ctxAdx = context.adx14();

        if (atr > 2.0) return "VOLATILE";
        // 強趨勢：雙時框一致 + 至少一個時框 ADX ≥ 25
        if ("BULLISH".equals(tp) && "BULLISH".equals(tc)) {
            return (adx >= 25 || ctxAdx >= 25) ? "TRENDING_BULLISH"
                 : adx >= 20 ? "TRENDING_BULLISH" : "RECOVERY";
        }
        if ("BEARISH".equals(tp) && "BEARISH".equals(tc)) {
            return (adx >= 25 || ctxAdx >= 25) ? "TRENDING_BEARISH"
                 : adx >= 20 ? "TRENDING_BEARISH" : "SIDEWAYS";
        }
        if ("SIDEWAYS".equals(tp) && "BULLISH".equals(tc)) return "RECOVERY";
        if ("SIDEWAYS".equals(tp) && atr < 0.8)            return "CONSOLIDATING";
        return "SIDEWAYS";
    }

    /** 格式化單一候選為 JSON object 字串。 */
    private String fc(int adx, double sl, double tp,
                      boolean allowShort, boolean atrTrail, boolean moveBreakeven,
                      Integer maxHours, int minSig, Double rsiPullback, Double minRR,
                      String rationale) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("{\"adxEntryThreshold\":%d", adx));
        sb.append(String.format(",\"fixedStopLossPct\":%.4f", sl));
        sb.append(String.format(",\"fixedTakeProfitPct\":%.4f", tp));
        sb.append(String.format(",\"allowShort\":%b", allowShort));
        if (atrTrail) sb.append(",\"atrTrailingStopEnabled\":true,\"atrPeriod\":14,\"atrMultiplier\":2.0");
        if (moveBreakeven) sb.append(",\"moveSlToBreakeven\":true");
        if (maxHours != null) sb.append(String.format(",\"maxHoldingHours\":%d", maxHours));
        sb.append(String.format(",\"minSignals\":%d", minSig));
        if (rsiPullback != null) sb.append(String.format(",\"rsiPullbackThreshold\":%.1f", rsiPullback));
        if (minRR != null) sb.append(String.format(",\"minRR\":%.1f", minRR));
        sb.append(String.format(",\"rationale\":\"%s\"}", rationale));
        return sb.toString();
    }

    private static double clampPct(double v) { return Math.max(0.3, Math.min(5.0, v)); }
    private static double clampDec(double v) { return Math.max(0.003, Math.min(0.05, v)); }

    private static String rsiLabel(double rsi) {
        if (rsi < 30) return "超賣";
        if (rsi < 45) return "偏弱";
        if (rsi < 55) return "中性";
        if (rsi < 70) return "偏強";
        return "超買";
    }
    private static String volLabel(double ratio) {
        if (ratio > 1.5) return "放量";
        if (ratio < 0.7) return "縮量";
        return "正常";
    }

    /** 從 DB 取最近 K 線，計算 RSI/EMA/ATR/ADX/MACD/Volume 快照。TTL=5min（由 LocalCacheConfig 設定）。 */
    @org.springframework.cache.annotation.Cacheable(value = "marketSnapshot", key = "#symbol + '-' + #intervalCode")
    public MarketSnapshot buildMarketSnapshot(String symbol, String intervalCode) {
        List<MdKline> raw = klineRepository.findBySymbolAndIntervalCodeAndSourceOrderByOpenTimeDesc(
                symbol, intervalCode, signalSource, PageRequest.of(0, 50));
        if (raw.size() < 15) {
            return new MarketSnapshot(symbol, intervalCode, 0, 50.0, 2.0, 2.0, "UNKNOWN", 0, 1.0, raw.size(), 20.0, 0.0, "");
        }
        Collections.reverse(raw);   // 升序排列
        int n = raw.size();

        double[] closes = raw.stream().mapToDouble(k -> k.getClosePrice().doubleValue()).toArray();
        double[] highs  = raw.stream().mapToDouble(k -> k.getHighPrice().doubleValue()).toArray();
        double[] lows   = raw.stream().mapToDouble(k -> k.getLowPrice().doubleValue()).toArray();
        double[] vols   = raw.stream().mapToDouble(k -> k.getVolume().doubleValue()).toArray();
        double lastClose = closes[n - 1];

        // EMA(20) 近似為 SMA(20)
        int ema20Period = Math.min(20, n);
        double ema20 = 0;
        for (int i = n - ema20Period; i < n; i++) ema20 += closes[i];
        ema20 /= ema20Period;
        String trendDir = lastClose > ema20 * 1.01 ? "BULLISH"
                        : lastClose < ema20 * 0.99 ? "BEARISH" : "SIDEWAYS";

        // RSI(14)
        double rsi14 = 50.0;
        if (n > 14) {
            double avgGain = 0, avgLoss = 0;
            for (int i = n - 14; i < n; i++) {
                double diff = closes[i] - closes[i - 1];
                if (diff > 0) avgGain += diff; else avgLoss += -diff;
            }
            avgGain /= 14; avgLoss /= 14;
            rsi14 = avgLoss == 0 ? 100.0 : 100.0 - 100.0 / (1.0 + avgGain / avgLoss);
        }

        // ATR(14) 佔收盤價 %（current：最近 14 根）
        int atrPeriod = Math.min(14, n);
        double atr = 0;
        for (int i = n - atrPeriod; i < n; i++) atr += (highs[i] - lows[i]);
        atr /= atrPeriod;
        double atrPct = lastClose > 0 ? atr / lastClose * 100 : 2.0;

        // Baseline ATR：全部 50 根的高低幅度% 中位數。
        // 中位數對離群值免疫：即使崩跌期有 10 根 spike bar（50 根的 20%），
        // 中位數仍由第 25-26 根決定，代表「正常波動水位」。
        double[] allRangePcts = new double[n];
        for (int i = 0; i < n; i++) {
            allRangePcts[i] = closes[i] > 0 ? (highs[i] - lows[i]) / closes[i] * 100 : 0;
        }
        double[] sortedRanges = allRangePcts.clone();
        java.util.Arrays.sort(sortedRanges);
        double baselineAtrPct = sortedRanges.length % 2 == 0
                ? (sortedRanges[sortedRanges.length / 2 - 1] + sortedRanges[sortedRanges.length / 2]) / 2.0
                : sortedRanges[sortedRanges.length / 2];

        // 成交量比率：近 5 根 vs 整體均值
        int recentBars = Math.min(5, n);
        double recentVol = 0, totalVol = 0;
        for (int i = n - recentBars; i < n; i++) recentVol += vols[i];
        recentVol /= recentBars;
        for (double v : vols) totalVol += v;
        totalVol /= n;
        double volRatio = totalVol > 0 ? recentVol / totalVol : 1.0;

        // ADX(14) — Wilder 平滑 + 近期觸發率統計
        double adx14 = 20.0;
        String adxTriggerStats = "";
        int p = 14;
        if (n >= p * 2 + 1) {
            double[] tr  = new double[n];
            double[] pdm = new double[n];
            double[] ndm = new double[n];
            for (int i = 1; i < n; i++) {
                double prevC = closes[i - 1];
                tr[i]  = Math.max(highs[i] - lows[i],
                         Math.max(Math.abs(highs[i] - prevC), Math.abs(lows[i] - prevC)));
                double up   = highs[i] - highs[i - 1];
                double down = lows[i - 1] - lows[i];
                pdm[i] = (up > 0 && up > down) ? up   : 0;
                ndm[i] = (down > 0 && down > up) ? down : 0;
            }
            // 初始平滑值：前 p 根的總和
            double aTR = 0, aPDM = 0, aNDM = 0;
            for (int i = 1; i <= p; i++) { aTR += tr[i]; aPDM += pdm[i]; aNDM += ndm[i]; }
            // 後續 Wilder 平滑並計算 DX
            double[] dx = new double[n];
            for (int i = p + 1; i < n; i++) {
                aTR  = aTR  - aTR  / p + tr[i];
                aPDM = aPDM - aPDM / p + pdm[i];
                aNDM = aNDM - aNDM / p + ndm[i];
                double pdi = aTR > 0 ? 100.0 * aPDM / aTR : 0;
                double ndi = aTR > 0 ? 100.0 * aNDM / aTR : 0;
                double dxSum = pdi + ndi;
                dx[i] = dxSum > 0 ? 100.0 * Math.abs(pdi - ndi) / dxSum : 0;
            }
            // ADX = DX 的 Wilder 平滑（從 2p 開始），同步收集歷史值
            double adxVal = 0;
            for (int i = p + 1; i <= 2 * p; i++) adxVal += dx[i];
            adxVal /= p;
            List<Double> adxHistory = new ArrayList<>();
            for (int i = 2 * p + 1; i < n; i++) {
                adxVal = (adxVal * (p - 1) + dx[i]) / p;
                adxHistory.add(adxVal);
            }
            adx14 = adxVal;
            // 計算各門檻觸發率（供 Claude 篩選候選 adxEntryThreshold）
            if (!adxHistory.isEmpty()) {
                int total = adxHistory.size();
                int[] thresholds = {15, 20, 25, 30, 35};
                StringBuilder sb = new StringBuilder();
                for (int t : thresholds) {
                    long cnt = adxHistory.stream().filter(v -> v > t).count();
                    sb.append(String.format(">%d=%.0f%%", t, 100.0 * cnt / total));
                    if (t < 35) sb.append("  ");
                }
                adxTriggerStats = sb.toString();
            }
        }

        // MACD(12,26,9) 柱狀（以收盤價比例表示）
        double macdHistogram = 0.0;
        if (n >= 35) {
            double k12 = 2.0 / 13;
            double k26 = 2.0 / 27;
            double k9  = 2.0 / 10;
            // Seed EMA12 with SMA(12)
            double e12 = 0;
            for (int i = 0; i < 12; i++) e12 += closes[i];
            e12 /= 12;
            // Seed EMA26 with SMA(26)
            double e26 = 0;
            for (int i = 0; i < 26; i++) e26 += closes[i];
            e26 /= 26;
            // 計算從 index 25 起的 MACD 線
            double e12v = e12, e26v = e26;
            for (int i = 12; i < 26; i++) e12v = closes[i] * k12 + e12v * (1 - k12);
            int mvLen = n - 25;
            double[] macdVals = new double[mvLen];
            macdVals[0] = e12v - e26v;
            for (int i = 26; i < n; i++) {
                e12v = closes[i] * k12 + e12v * (1 - k12);
                e26v = closes[i] * k26 + e26v * (1 - k26);
                macdVals[i - 25] = e12v - e26v;
            }
            if (mvLen >= 9) {
                double signal = 0;
                for (int i = 0; i < 9; i++) signal += macdVals[i];
                signal /= 9;
                for (int i = 9; i < mvLen; i++) signal = macdVals[i] * k9 + signal * (1 - k9);
                double rawHist = macdVals[mvLen - 1] - signal;
                macdHistogram = lastClose > 0 ? rawHist / lastClose : rawHist;
            }
        }

        return new MarketSnapshot(symbol, intervalCode, lastClose, rsi14, atrPct, baselineAtrPct, trendDir, ema20, volRatio, n, adx14, macdHistogram, adxTriggerStats);
    }

    /**
     * 呼叫 Groq AI（含市場快照 + 風格指引），取得 N 組候選配置。
     * When {@code trading.discovery.ai-suggestions.enabled=false} (default), skips the Groq
     * call and returns style-calibrated default candidates directly.
     */
    private List<CandidateConfig> generateAdaptiveCandidateConfigs(
            int count, String symbol, String intervalCode, MarketSnapshot snapshot, String style) {
        if (!aiSuggestionsEnabled) {
            log.info("[AI自適應探勘] ai-suggestions disabled，使用風格化預設候選 (style={} symbol={} interval={})", style, symbol, intervalCode);
            return buildDefaultCandidatesByStyle(count, style);
        }
        if (!groqApiClient.isEnabled()) {
            log.info("[AI自適應探勘] Groq 未啟用，使用風格化預設候選");
            return buildDefaultCandidatesByStyle(count, style);
        }
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(buildMessage("system", buildAdaptiveSystemPrompt()));
        messages.add(buildMessage("user", buildAdaptivePrompt(count, snapshot, style)));

        String aiResponse = groqApiClient.chat(messages, 2000, 0.4);
        if (aiResponse == null || aiResponse.isBlank()) {
            return buildDefaultCandidatesByStyle(count, style);
        }
        List<CandidateConfig> parsed = parseAiResponse(aiResponse, count);
        if (parsed.isEmpty()) {
            return buildDefaultCandidatesByStyle(count, style);
        }
        parsed.forEach(cc -> cc.config.setEnableMtf(false));  // 強制關閉 MTF（無 1d K 線）
        return parsed;
    }

    private String buildAdaptiveSystemPrompt() {
        return "你是一名量化交易策略顧問，專精於加密貨幣市場的技術分析策略設計。\n" +
               "你會根據當前市場指標（RSI、ATR、EMA 趨勢、成交量）和指定的交易風格，\n" +
               "輸出多組針對性的 SOP_MTF_ADX 策略參數配置。\n" +
               "每組配置必須是合法的 JSON 物件，並附帶一句簡短的策略說明（rationale 欄位）。\n" +
               "輸出格式必須嚴格遵守 JSON array，不要包含任何 markdown、程式碼區塊或額外說明文字。";
    }

    private String buildAdaptivePrompt(int count, MarketSnapshot snapshot, String style) {
        // 短時框（≤ 1h）噪音較高，需要降低 ADX 門檻避免 0 信號
        boolean isShortTimeframe = "15m".equals(snapshot.intervalCode())
                || "30m".equals(snapshot.intervalCode())
                || "1h".equals(snapshot.intervalCode());
        // 高波動標的（BTC）需要相對緊的 SL 防過早停損 + 防止 HIGH_FREQ 過交易
        boolean isHighVolSymbol = "BTCUSDT".equals(snapshot.symbol()) && snapshot.atrPct() >= 2.5;

        String envHint = "【市場環境提示】\n" +
                "- 時框：" + snapshot.intervalCode()
                + (isShortTimeframe ? "（噪音較高，adxEntryThreshold 比 4h 偏低 25%，maxHoldingHours 較短）\n"
                                    : "（噪音較低，標準參數適用）\n")
                + "- 標的：" + snapshot.symbol()
                + (isHighVolSymbol
                    ? String.format("（高波動，ATR=%.2f%%；SL 收緊 15-20%% 防過早停損；HIGH_FREQ 風格須避免過交易）%n",
                                    snapshot.atrPct())
                    : "（標準波動）\n");

        String styleGuidance = buildStyleGuidance(style, isShortTimeframe, isHighVolSymbol);

        return snapshot.toPromptText() + "\n" +
               envHint + "\n" +
               styleGuidance + "\n\n" +
               "請針對以上市場狀態與風格目標，生成 " + count + " 組 SOP_MTF_ADX 策略配置。\n" +
               "各組應有差異（不同的 adxThreshold、SL/TP 比例組合等）。\n\n" +
               "每組 JSON 包含以下欄位（皆可選填，null 表示使用預設值）：\n" +
               "minSignals, adxEntryThreshold, fixedStopLossPct, fixedTakeProfitPct,\n" +
               "maxHoldingHours, rsiPullbackThreshold, minRR, allowShort,\n" +
               "atrTrailingStopEnabled, moveSlToBreakeven, rationale\n\n" +
               "只輸出 JSON array，不包含任何其他文字。";
    }

    /**
     * Style 範圍依 timeframe 與 symbol 動態調整：
     * <ul>
     *   <li>短時框（≤1h）：adxEntryThreshold 範圍 × 0.75（避免高門檻 0 信號）</li>
     *   <li>高波動標的（BTC ATR≥2.5%）：fixedStopLossPct 範圍 × 0.85（防過早停損）</li>
     * </ul>
     */
    private String buildStyleGuidance(String style, boolean shortTf, boolean highVol) {
        return switch (style) {
            case "HIGH_FREQ" -> String.format(
                "【目標：高頻交易，每月 10 筆以上】%n" +
                "- adxEntryThreshold: %s（低門檻，頻繁進場）%n" +
                "- fixedStopLossPct: %s（緊止損，快進快出）%n" +
                "- fixedTakeProfitPct: 0.015~0.04（對應 minRR ≥ 1.5）%n" +
                "- minSignals: 1~2（快速確認）%n" +
                "- maxHoldingHours: 12~48（限制持倉時間）%n" +
                "- allowShort: true（多空雙向增加機會）",
                shortTf ? "9~15"   : "12~20",
                highVol ? "0.007~0.017" : "0.008~0.02");
            case "TREND" -> String.format(
                "【目標：趨勢跟蹤，每月 3~8 筆，高確信度】%n" +
                "- adxEntryThreshold: %s（確保趨勢明確）%n" +
                "- fixedStopLossPct: %s（允許震盪）%n" +
                "- fixedTakeProfitPct: 0.05~0.12（追大波段）%n" +
                "- minSignals: 2~4（多訊號確認）%n" +
                "- atrTrailingStopEnabled: true%n" +
                "- moveSlToBreakeven: true",
                shortTf ? "16~24"  : "22~32",
                highVol ? "0.017~0.034" : "0.02~0.04");
            case "CONSERVATIVE" -> String.format(
                "【目標：保守低頻，每月 1~5 筆，低回撤優先】%n" +
                "- adxEntryThreshold: %s（只在強趨勢進場）%n" +
                "- fixedStopLossPct: %s（嚴格風控）%n" +
                "- fixedTakeProfitPct: 0.025~0.05%n" +
                "- minSignals: 3~5（嚴格過濾）%n" +
                "- rsiPullbackThreshold: 35~45（等待回調確認）%n" +
                "- minRR: 2.0~3.0",
                shortTf ? "21~28"  : "28~38",
                highVol ? "0.0085~0.017" : "0.01~0.02");
            default -> "【目標：均衡型，每月 5~10 筆】\n" +
                "各項參數取中間值，多空均衡，適應不同市況。";
        };
    }

    /** 當 Groq 未啟用或解析失敗時，依風格回傳內建預設候選。 */
    private List<CandidateConfig> buildDefaultCandidatesByStyle(int count, String style) {
        List<CandidateConfig> list = new ArrayList<>();
        switch (style) {
            case "HIGH_FREQ" -> {
                addCandidate(list, 1, 16.0, 0.012, 0.024, 24, null, 2.0, true, false, false, "高頻-低ADX雙向快進");
                addCandidate(list, 1, 18.0, 0.010, 0.020, 36, null, 2.0, true, false, true,  "高頻-超緊SL移本損");
                addCandidate(list, 2, 15.0, 0.015, 0.035, 48, null, null, true, false, false, "高頻-寬TP適中確認");
                addCandidate(list, 1, 14.0, 0.008, 0.016, 12, null, 2.0, true, false, false,  "高頻-超快速進出");
                addCandidate(list, 2, 18.0, 0.012, 0.030, 24, null, 2.5, true, true,  false,  "高頻-ATR追蹤止損");
            }
            case "TREND" -> {
                addCandidate(list, 3, 25.0, 0.025, 0.070, null, null, null, false, true,  false, "趨勢-ATR追蹤中等確認");
                addCandidate(list, 2, 22.0, 0.020, 0.060, null, null, null, true,  false, true,  "趨勢-快入場移本損雙向");
                addCandidate(list, 4, 28.0, 0.030, 0.090, null, null, 2.5,  false, true,  false, "趨勢-強確認高風報比");
                addCandidate(list, 3, 24.0, 0.022, 0.055, null, 40.0, null, true,  false, false, "趨勢-雙向RSI回調確認");
                addCandidate(list, 2, 26.0, 0.035, 0.100, null, null, null, false, true,  false, "趨勢-超寬TP捕大波段");
            }
            case "CONSERVATIVE" -> {
                addCandidate(list, 4, 30.0, 0.012, 0.030, null, 38.0, 2.5, false, false, false, "保守-高ADX嚴格RSI過濾");
                addCandidate(list, 5, 32.0, 0.010, 0.025, null, null, 2.5,  false, false, false, "保守-極嚴格進場條件");
                addCandidate(list, 3, 28.0, 0.015, 0.040, null, null, 2.5,  false, false, true,  "保守-移本損保護利潤");
                addCandidate(list, 4, 33.0, 0.012, 0.036, null, 40.0, 3.0,  false, false, false, "保守-高風報比RSI確認");
                addCandidate(list, 3, 30.0, 0.013, 0.032, null, null, 2.5,  false, true,  false, "保守-ATR追蹤鎖利");
            }
            default -> { return buildDefaultCandidates(count); }
        }
        return list.subList(0, Math.min(count, list.size()));
    }

    private void addCandidate(List<CandidateConfig> list,
                              int minSig, double adx, double sl, double tp,
                              Integer maxHours, Double rsiPullback, Double minRR,
                              boolean allowShort, boolean atrTrailing, boolean moveBreakeven,
                              String rationale) {
        SopMtfAdxConfig c = new SopMtfAdxConfig();
        c.setEnableMtf(false);  // AI 探勘策略不使用 MTF 過濾（DB 無 1d K 線）
        c.setMinSignals(minSig);
        c.setAdxEntryThreshold(adx);
        c.setFixedStopLossPct(sl);
        c.setFixedTakeProfitPct(tp);
        if (maxHours  != null) c.setMaxHoldingHours(maxHours);
        if (rsiPullback != null) c.setRsiPullbackThreshold(rsiPullback);
        if (minRR     != null) c.setMinRR(minRR);
        c.setAllowShort(allowShort);
        if (atrTrailing) { c.setAtrTrailingStopEnabled(true); c.setAtrMultiplier(2.0); c.setAtrPeriod(14); }
        c.setMoveSlToBreakeven(moveBreakeven);
        list.add(new CandidateConfig(c, rationale));
    }

    // ─── Market Snapshot record ───────────────────────────────────────────────

    public record MarketSnapshot(
        String symbol, String intervalCode,
        double lastClose, double rsi14, double atrPct,
        double baselineAtrPct,   // 50根bar的高低幅度%中位數（中位數對離群值免疫，spike時仍代表「正常波動」）
        String trendDirection, double ema20, double volumeRatio, int barsAnalyzed,
        double adx14, double macdHistogram, String adxTriggerStats
    ) {
        /**
         * 判斷當前 ATR 是否為「異常 spike」。
         * 使用 median-of-all-bars 作 baseline：50根bar中即使 5-10根是崩跌bar，
         * 中位數仍代表大多數時間的正常波動，對短期崩跌免疫。
         *
         * @param spikeMultiple  spike 判定倍數，預設 2.0（current > baseline × 2 才算 spike）
         */
        public boolean isAtrSpike(double spikeMultiple) {
            return baselineAtrPct > 0 && atrPct > baselineAtrPct * spikeMultiple;
        }

        String summary() {
            String spikeFlag = isAtrSpike(2.0) ? " ⚠️SPIKE" : "";
            return String.format("close=%.2f rsi=%.1f atr=%.2f%%(baseline=%.2f%%)%s adx=%.1f macd=%+.4f trend=%s ema20=%.2f vol=%.1fx bars=%d",
                    lastClose, rsi14, atrPct, baselineAtrPct, spikeFlag, adx14, macdHistogram, trendDirection, ema20, volumeRatio, barsAnalyzed);
        }

        public String toPromptText() {
            String rsiLabel  = rsi14 < 30 ? "超賣" : rsi14 < 45 ? "偏弱" : rsi14 < 55 ? "中性" : rsi14 < 70 ? "偏強" : "超買";
            String volLabel  = volumeRatio > 1.5 ? "放量" : volumeRatio < 0.7 ? "縮量" : "正常";
            String adxLabel  = adx14 < 15 ? "無趨勢" : adx14 < 25 ? "弱趨勢" : adx14 < 35 ? "趨勢中" : "強趨勢";
            String macdLabel = macdHistogram > 0 ? "看多" : macdHistogram < 0 ? "看空" : "中性";
            String triggerLine = adxTriggerStats.isEmpty() ? "資料不足"
                    : adxTriggerStats + "\n  → 建議 adxEntryThreshold ≤ 觸發率10%以上對應的門檻";
            return String.format(
                "當前市場快照（%s %s，基於最近 %d 根K線）：\n" +
                "- 最新收盤價: %.2f\n" +
                "- RSI(14): %.1f（%s）\n" +
                "- ATR 波動率: %.2f%%（每根K線平均波動）\n" +
                "- ADX(14): %.1f（%s）\n" +
                "- ADX 觸發率（近期K線）: %s\n" +
                "- MACD 柱狀: %+.4f（%s）\n" +
                "- EMA(20) 趨勢: %s（EMA=%.2f）\n" +
                "- 近期成交量: %s（近5根均量是整體均量的 %.1f 倍）\n",
                symbol, intervalCode, barsAnalyzed,
                lastClose, rsi14, rsiLabel, atrPct,
                adx14, adxLabel, triggerLine,
                macdHistogram, macdLabel,
                trendDirection, ema20, volLabel, volumeRatio);
        }
    }

    /**
     * 內部資料類：持有解析後的策略配置及 AI 說明。
     */
    static class CandidateConfig {
        final SopMtfAdxConfig config;
        final String rationale;

        CandidateConfig(SopMtfAdxConfig config, String rationale) {
            this.config = config;
            this.rationale = rationale;
        }
    }
}
