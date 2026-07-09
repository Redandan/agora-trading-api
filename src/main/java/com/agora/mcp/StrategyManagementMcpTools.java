package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.util.McpParamValidator;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.dto.backtest.AiStrategyDiscoveryRequest;
import com.agora.dto.backtest.AiStrategyDiscoveryResponse;
import com.agora.dto.backtest.BacktestResultResponse;
import com.agora.dto.backtest.BacktestRunRequest;
import com.agora.dto.backtest.CreateStrategyRequest;
import com.agora.dto.backtest.SopMtfAdxConfig;
import com.agora.dto.backtest.StrategyQueryRequest;
import com.agora.dto.backtest.StrategyResponse;
import com.agora.dto.backtest.UpdateStrategyRequest;
import com.agora.model.BtBacktestResult;
import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtBacktestResultRepository;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.BacktestService;
import com.agora.service.BtStrategyService;
import com.agora.service.ai.AiStrategyDiscoveryService;
import com.agora.service.backtest.BacktestQualityValidator;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.meta.ScorecardReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * MCP 策略管理工具集。
 * 提供策略 CRUD、啟用/停用閘道(品質驗證 + robustness + walk-forward)、策略比較、清理等功能。
 *
 * 設計:setStrategyEnabled 作為核心閘道,runRobustnessCore / runWalkForwardCore 為共用 helper,
 * 兩者皆與 enableStrategy 分層 quality gate 強耦合,因此集中在此類別以降低跨類別依賴。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyManagementMcpTools {

    private final BtStrategyService strategyService;
    private final BacktestService backtestService;
    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final BtBacktestResultRepository backtestResultRepository;
    private final BtLiveSignalRepository liveSignalRepository;
    private final BtDecisionAuditRepository decisionAuditRepository;
    private final DecisionAuditWriter auditWriter;
    private final ScorecardReportService scorecardReportService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Tool(description = "一站式策略 scorecard:交易策略 vs ML 模型 vs 被動基準的統一比較表。" +
            "回答「我們目前賺錢嗎 / 哪個策略/模型值得部署?」" +
            "輸出 4 區:Trading Strategies / ML Models / Benchmarks / Current edge。" +
            "TG-friendly,取代手動 call listStrategies + listModelVersions + getMarketAnalysis + 心算比較。" +
            "param: enabledOnly=true 只顯示啟用中策略（預設 false 顯示全部）")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.REPORTING, Category.DIAGNOSTIC})
    public String getStrategyScorecard(Boolean enabledOnly) {
        try {
            return scorecardReportService.formatAsText(Boolean.TRUE.equals(enabledOnly));
        } catch (Exception e) {
            log.error("[MCP:getStrategyScorecard] failed", e);
            return "❌ scorecard 產生失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC})
    @Tool(description = "#225 查看策略完整 config_json：顯示所有設定欄位（含 null 值），" +
            "方便在不需要 SSH 進 DB 的情況下確認策略的完整配置。" +
            "param: strategyId（必填）")
    public String getStrategyConfig(Long strategyId) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        try {
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, name, strategy_type, enabled, config_json, " +
                    "alpha_source, trigger_conditions, notes, created_at, updated_at " +
                    "FROM bt_strategy WHERE id=?", strategyId);
            if (rows.isEmpty()) return "❌ 策略 " + strategyId + " 不存在";
            var row = rows.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Strategy #%d Config ===\n", strategyId));
            sb.append(String.format("name: %s\ntype: %s\nenabled: %s\n\n",
                    row.get("name"), row.get("strategy_type"), row.get("enabled")));
            if (row.get("alpha_source") != null)
                sb.append("alpha_source: ").append(row.get("alpha_source")).append("\n");
            if (row.get("trigger_conditions") != null)
                sb.append("trigger_conditions: ").append(row.get("trigger_conditions")).append("\n");
            sb.append("\n--- config_json ---\n");
            // Pretty-print JSON
            Object cfg = row.get("config_json");
            if (cfg != null) {
                String json = cfg.toString();
                // Simple indentation: replace , with ,\n  and { with {\n
                json = json.replaceAll("\\{", "{\n  ").replaceAll("}", "\n}")
                           .replaceAll(",\"", ",\n  \"");
                sb.append(json);
            } else {
                sb.append("(null)");
            }
            sb.append("\n\ncreated: ").append(row.get("created_at"));
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING})
    @Tool(description = "列出交易策略，顯示 id、名稱、類型、啟用狀態、監控幣種、最新回測品質。" +
            "param: enabledOnly=true 只顯示啟用中的策略（預設 false 顯示全部）")
    public String listStrategies(Boolean enabledOnly) {
        List<StrategyResponse> allStrategies = strategyService.queryStrategies(new StrategyQueryRequest());
        boolean filterEnabled = Boolean.TRUE.equals(enabledOnly);
        List<StrategyResponse> strategies = filterEnabled
                ? allStrategies.stream().filter(s -> Boolean.TRUE.equals(s.getEnabled())).toList()
                : allStrategies;
        StringBuilder sb = new StringBuilder();
        sb.append("=== 策略清單 (共 ").append(strategies.size());
        if (filterEnabled) sb.append("（啟用中）");
        sb.append(" / ").append(allStrategies.size()).append(" 筆) ===\n");
        sb.append("品質門檻：").append(BacktestQualityValidator.thresholdsDescription()).append("\n\n");
        for (StrategyResponse s : strategies) {
            sb.append("ID: ").append(s.getId()).append("\n");
            sb.append("名稱: ").append(s.getName()).append("\n");
            sb.append("類型: ").append(s.getStrategyType()).append("\n");
            sb.append("啟用: ").append(Boolean.TRUE.equals(s.getEnabled()) ? "✅ 是" : "❌ 否").append("\n");
            sb.append("監控幣種: ").append(s.getSymbols() != null ? s.getSymbols() : "⚠️ 未設定（全部）").append("\n");
            BtBacktestResult latest = backtestResultRepository
                    .findTopByStrategy_IdOrderByCreatedAtDesc(s.getId()).orElse(null);
            if (latest == null) {
                sb.append("回測品質: 尚無回測記錄\n");
            } else {
                int    tc  = latest.getTradeCount()  != null ? latest.getTradeCount()                : 0;
                double ret = latest.getTotalReturn() != null ? latest.getTotalReturn().doubleValue() : 0.0;
                double dd  = latest.getMaxDrawdown() != null ? latest.getMaxDrawdown().doubleValue() : 1.0;
                double wr  = latest.getWinRate()     != null ? latest.getWinRate().doubleValue()     : 0.0;
                boolean pass = BacktestQualityValidator.passes(tc, ret, dd);
                sb.append(pass ? "回測品質: ✅ 通過  " : "回測品質: ❌ 未達標  ");
                sb.append(String.format("交易=%d筆 勝率=%.0f%% 報酬=%.1f%% 回撤=%.1f%%\n",
                        tc, wr * 100, ret * 100, dd * 100));
            }
            if (s.getAlphaSource() != null && !s.getAlphaSource().isBlank()) {
                sb.append("Alpha: ").append(s.getAlphaSource()).append("\n");
            }
            if (s.getTriggerConditions() != null && !s.getTriggerConditions().isBlank()) {
                sb.append("觸發: ").append(s.getTriggerConditions(), 0,
                        Math.min(120, s.getTriggerConditions().length())).append("\n");
            }
            sb.append("備註: ").append(s.getNotes() != null && !s.getNotes().isBlank()
                    ? s.getNotes() : "(無)").append("\n");
            sb.append("建立時間: ").append(s.getCreatedAt()).append("\n");
            sb.append("---\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "查看策略 RegimeFilter / 趨勢 hard-block 設定總覽（只讀）。" +
            "快速判斷哪些啟用策略仍會被 TRENDING_DOWN→LONG hard-block、哪些已用 1.01 關閉。" +
            "param: enabledOnly=true 只看啟用策略（預設 true）")
    public String getStrategyRegimeFilterStatus(Boolean enabledOnly) {
        boolean enabledOnlyVal = enabledOnly == null || enabledOnly;
        String sql = "SELECT id, name, strategy_type, enabled, symbols, config_json " +
                "FROM bt_strategy " + (enabledOnlyVal ? "WHERE enabled=1 " : "") +
                "ORDER BY enabled DESC, id ASC";
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(sql);
        } catch (Exception e) {
            return "❌ 查詢策略 RegimeFilter 狀態失敗: " + e.getMessage();
        }
        if (rows.isEmpty()) {
            return enabledOnlyVal ? "ℹ️ 無啟用策略" : "ℹ️ 無策略";
        }

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        StringBuilder sb = new StringBuilder();
        sb.append("=== Strategy RegimeFilter Status ===\n");
        sb.append(enabledOnlyVal ? "scope: enabled strategies\n\n" : "scope: all strategies\n\n");
        sb.append(String.format("%-5s| %-7s | %-9s | %-14s | %-8s | %-8s | %-6s | %s%n",
                "ID", "enabled", "mode", "regimeBlock", "minConf", "RSIbp", "F&G", "name"));
        sb.append("-".repeat(104)).append("\n");

        int hardAll = 0, hardStrong = 0, disabled = 0, shadow = 0;
        for (Map<String, Object> row : rows) {
            com.fasterxml.jackson.databind.JsonNode cfg;
            try {
                Object raw = row.get("config_json");
                cfg = raw != null ? om.readTree(raw.toString()) : om.createObjectNode();
            } catch (Exception e) {
                cfg = om.createObjectNode();
            }

            boolean enabled = asBool(row.get("enabled"));
            boolean notifyOnly = jsonBool(cfg, "notifyOnly", false);
            if (notifyOnly) shadow++;

            double minConf = jsonDouble(cfg, "regimeFilterMinConfidence", 0.0);
            String blockMode;
            if (minConf > 1.0) {
                blockMode = "disabled";
                disabled++;
            } else if (minConf > 0.8) {
                blockMode = "strong-only";
                hardStrong++;
            } else {
                blockMode = "all";
                hardAll++;
            }

            boolean rsiBypass = jsonBool(cfg, "allowRsiBypassRegime", false);
            double rsiThr = jsonDouble(cfg, "regimeBypassRsiThreshold", 20.0);
            double fgBelow = jsonDouble(cfg, "requireFearGreedBelow", 0.0);
            double fgAbove = jsonDouble(cfg, "requireFearGreedAbove", 0.0);
            String fg = fgBelow > 0 ? "<" + trimNum(fgBelow) : fgAbove > 0 ? ">" + trimNum(fgAbove) : "-";

            String name = String.valueOf(row.get("name"));
            if (name.length() > 42) name = name.substring(0, 39) + "...";
            sb.append(String.format("%-5s| %-7s | %-9s | %-14s | %-8s | %-8s | %-6s | %s%n",
                    row.get("id"),
                    enabled ? "ON" : "OFF",
                    notifyOnly ? "shadow" : "active",
                    blockMode,
                    trimNum(minConf),
                    rsiBypass ? "<" + trimNum(rsiThr) : "-",
                    fg,
                    name));
        }

        sb.append("\n");
        sb.append(String.format("summary: all-block=%d | strong-only=%d | disabled=%d | shadow=%d%n",
                hardAll, hardStrong, disabled, shadow));
        sb.append("legend: minConf > 1.0 disables RegimeFilter; 0.85 blocks only strong deterministic downtrend; <=0.8 blocks all downtrend confidence levels.");
        return sb.toString();
    }

    @Tool(description = "查詢策略的啟用/停用歷史(從 bt_decision_audit 讀 Strategy.Enable / Strategy.Disable 事件)。" +
            "可用來 audit:某策略近期被啟停幾次、每次 notes(決策理由)。" +
            "param: strategyId, days(回溯天數, 預設 90, 最多 365)")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    public String getStrategyEnableHistory(Long strategyId, Integer days) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        int d = days != null ? Math.min(Math.max(days, 1), 365) : 90;
        LocalDateTime from = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);
        LocalDateTime to = LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);

        List<com.agora.model.BtDecisionAudit> audits = decisionAuditRepository
                .findByStrategyIdAndEventTimeBetweenOrderByEventTimeDesc(strategyId, from, to);

        List<com.agora.model.BtDecisionAudit> history = audits.stream()
                .filter(a -> "OVERRIDE_APPLIED".equals(a.getEventType()))
                .filter(a -> a.getBlocker() != null
                          && (a.getBlocker().equals("Strategy.Enable") || a.getBlocker().equals("Strategy.Disable")))
                .toList();

        StrategyResponse strategy;
        try {
            strategy = strategyService.getStrategy(strategyId);
        } catch (Exception e) {
            return "❌ 讀取策略失敗: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Strategy Enable/Disable History ===%n"));
        sb.append(String.format("策略: [%s] (ID=%d, 目前=%s)%n",
                strategy != null ? strategy.getName() : "?", strategyId,
                strategy != null && Boolean.TRUE.equals(strategy.getEnabled()) ? "✅ 啟用" : "❌ 停用"));
        sb.append(String.format("回溯 %d 天 (%s ~ now)%n%n",
                d, from.format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)));

        if (history.isEmpty()) {
            sb.append("ℹ️ 查詢區間內無 Strategy.Enable / Strategy.Disable 事件。\n");
            sb.append("   (STRATEGY_ENABLE audit 從 2026-04-16 commit b9419e8 後才開始記錄,之前的啟停不在 audit 表中)");
            return sb.toString();
        }

        sb.append(String.format("共 %d 筆事件:%n", history.size()));
        java.time.format.DateTimeFormatter ts = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm");
        for (com.agora.model.BtDecisionAudit a : history) {
            String op = a.getBlocker().equals("Strategy.Enable") ? "🟢 ENABLE" : "🔴 DISABLE";
            String reason = a.getReason() != null ? a.getReason() : "(無 notes)";
            if (reason.length() > 120) reason = reason.substring(0, 120) + "…";
            sb.append(String.format("%n• %s %s%n",
                    a.getEventTime() != null ? a.getEventTime().format(ts) : "??", op));
            sb.append(String.format("  %s%n", reason));
        }
        return sb.toString();
    }

    @Tool(description = "比對兩個策略的 config 差異,突顯不同的參數。" +
            "用於快速理解「315 比 284 好在哪」或「新舊版本差多少」。" +
            "param: strategyId1, strategyId2")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS})
    public String compareStrategies(Long strategyId1, Long strategyId2) {
        if (strategyId1 == null || strategyId2 == null) {
            return "❌ strategyId1 和 strategyId2 皆為必填";
        }
        StrategyResponse s1, s2;
        try {
            s1 = strategyService.getStrategy(strategyId1);
            s2 = strategyService.getStrategy(strategyId2);
        } catch (Exception e) {
            return "❌ 讀取策略失敗: " + e.getMessage();
        }
        if (s1 == null || s2 == null) return "❌ 策略不存在";
        if (!java.util.Objects.equals(s1.getStrategyType(), s2.getStrategyType())) {
            return String.format("⚠️ 策略類型不同:[%d] %s vs [%d] %s,不宜比對參數",
                    strategyId1, s1.getStrategyType(), strategyId2, s2.getStrategyType());
        }

        Map<String, Object> c1, c2;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            c1 = s1.getConfig() != null ? om.convertValue(s1.getConfig(), Map.class) : new LinkedHashMap<>();
            c2 = s2.getConfig() != null ? om.convertValue(s2.getConfig(), Map.class) : new LinkedHashMap<>();
        } catch (Exception e) {
            return "❌ 解析 config 失敗: " + e.getMessage();
        }

        java.util.Set<String> allKeys = new java.util.TreeSet<>();
        allKeys.addAll(c1.keySet());
        allKeys.addAll(c2.keySet());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== 策略比對 %d vs %d ===%n", strategyId1, strategyId2));
        sb.append(String.format("[%d] %s (啟用=%s)%n", strategyId1, s1.getName(),
                Boolean.TRUE.equals(s1.getEnabled()) ? "✅" : "❌"));
        sb.append(String.format("[%d] %s (啟用=%s)%n%n", strategyId2, s2.getName(),
                Boolean.TRUE.equals(s2.getEnabled()) ? "✅" : "❌"));

        List<String> differences = new ArrayList<>();
        List<String> same = new ArrayList<>();
        for (String key : allKeys) {
            Object v1 = c1.get(key);
            Object v2 = c2.get(key);
            if (java.util.Objects.equals(v1, v2)) {
                same.add(key);
            } else {
                differences.add(String.format("  %s: %s → %s", key,
                        v1 != null ? v1.toString() : "<unset>",
                        v2 != null ? v2.toString() : "<unset>"));
            }
        }

        sb.append(String.format("🔴 差異 (%d 個):%n", differences.size()));
        if (differences.isEmpty()) {
            sb.append("  無 — config 完全相同\n");
        } else {
            for (String d : differences) sb.append(d).append('\n');
        }
        sb.append(String.format("%n🟢 相同參數 (%d 個): %s%n", same.size(),
                same.size() <= 8 ? String.join(", ", same) : String.join(", ", same.subList(0, 8)) + "..."));

        return sb.toString();
    }

    @Tool(description = "啟用指定策略,使其參與即時信號評估與自動交易。" +
            "啟用前自動驗證最新回測品質(tradeCount≥5, totalReturn>0, maxDrawdown≤20%)。" +
            "tradeCount<15 時自動跑 3x robustness 驗證(SL/TP/ADX),任一 CLIFF 即拒絕(防 curve-fit)。" +
            "tradeCount≥15 時自動跑 walk-forward 驗證(5 folds × 180d),stdev/|mean|>3 或 NEGATIVE_MEAN 拒絕," +
            "1.5~3 WARN pass(notes 標註 UNSTABLE),≤1.5 直接 pass。" +
            "**notes 必填**:說明為何現在啟用此策略(類比 git commit message,供未來 review)。" +
            "緊急情況可傳 skipRobustnessCheck=true 略過 robustness/WF 閘道(會發 TG 警告)。" +
            "Shadow 模式可傳 skipQualityGate=true 略過品質門檻,**但僅限 config.notifyOnly=true 的策略**" +
            "(不實際下單→無金錢風險,用於累積 ml_inference_log 樣本)。" +
            "param: strategyId=策略ID, notes=啟用原因(必填), " +
            "skipRobustnessCheck=略過穩健性(選填), skipQualityGate=略過品質門檻(選填,需先 setStrategyFlags notifyOnly=true)")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String enableStrategy(Long strategyId, String notes, Boolean skipRobustnessCheck,
                                  Boolean skipQualityGate) {
        if (notes == null || notes.trim().isEmpty()) {
            return "❌ notes 為必填:請說明為何啟用此策略(如「RECOVERY regime + 勝率 80% 通過驗證」)";
        }
        boolean skipRobust = Boolean.TRUE.equals(skipRobustnessCheck);
        boolean skipQuality = Boolean.TRUE.equals(skipQualityGate);
        return setStrategyEnabled(strategyId, true, notes.trim(), skipRobust, skipQuality);
    }

    @Tool(description = "修改策略的 notifyOnly / allowShort 安全開關(其他 config 參數不開放)。" +
            "notifyOnly=true: 觸發時只發 TG 通知,不自動下單(shadow/預警模式)。" +
            "allowShort=false: 該策略禁止開 SHORT 方向(只走 LONG)。" +
            "**notes 必填**:說明為何改此 flag。至少要指定 notifyOnly / allowShort 其中一個。" +
            "操作會寫 bt_decision_audit。其他參數請透過建新策略 / validateCandidates 取代,避免誤改導致回測 drift。" +
            "param: strategyId, notifyOnly(Boolean 可空), allowShort(Boolean 可空)," +
            "requireAboveSma200(Boolean,通用 regime filter: 是否要求收盤 > SMA720(30d MA);SCORE_BUY/EMA_RSI 預設 true,CMI_MIH_THRESHOLD 預設 false。在 CMI 策略上設 true 可避免 BEARISH 市場逆勢做多)," +
            "allowMacdAsLowProxy(Boolean,**僅 SCORE_BUY/EMA_RSI 有效**: MACD 反轉當低點替代,適用 ETH 等無明顯 wick 形態)," +
            "buyThreshold(Double,**僅 SCORE_BUY/EMA_RSI 有效**: nnOutput 門檻,預設 0.8。⚠️ 傳 0 會被視為 placeholder 並拒絕寫入(#389 guard))," +
            "rsiOversold(Double,**僅 SCORE_BUY/EMA_RSI 有效**: RSI 超賣門檻,預設 40。⚠️ 傳 0 會被拒絕)," +
            "volumeBreakoutMultiplier(Double,**僅 SCORE_BUY/EMA_RSI 有效**: 量爆倍數,預設 1.5。⚠️ 傳 0 會被拒絕)," +
            "yearLookbackBars(Integer,**僅 SCORE_BUY/EMA_RSI 有效**: 252 for 1d/8760 for 1h。⚠️ 傳 0 會被拒絕)," +
            "notes(必填)," +
            "alphaSource(String,可選: Alpha 來源標籤,如「技術面趨勢」「崩盤底部」「市場結構(OI+Funding)」)," +
            "triggerConditions(String,可選: 結構化觸發條件說明,供快速查閱)," +
            "requireFundingImprovingBars(Integer,CMI 通用: funding_rate 需高於前 N 小時均值(空頭成本改善),適用 ShortBuild;-1=停用)," +
            "requireNoNewLowBars(Integer,CMI 通用: 前 N 小時內不能創新低(觸底確認),適用 VDI;-1=停用)," +
            "regimeFilterMinConfidence(Double,通用 #335: 只在 deterministic regime classifier confidence ≥ 此值時才 hard-block TRENDING_DOWN→LONG。" +
            "⚠️ DeterministicRegimeClassifier 產出 confidence ∈ {0.8, 1.0}：強 trend (ADX≥25)=1.0, borderline (ADX 22-25)=0.8。" +
            "0=永遠 block(預設); 0.85=放寬 borderline ADX 22-25 (推薦); 0.6=無效(< 0.8 永不觸發 bypass); 1.01=完全 disable RegimeFilter), " +
            "regimeBypassRsiThreshold(Double,通用 #335: RSI 極端 oversold bypass 門檻,搭配 allowRsiBypassRegime=true 使用,預設 20.0), " +
            "allowRsiBypassRegime(Boolean,通用 #335: 啟用 panic-bottom RSI bypass。當 RSI < regimeBypassRsiThreshold (預設 20) 時，跳過 TRENDING_DOWN→LONG hard-block。建議僅 mean-reversion / panic-bottom 策略 (#485 SCORE_BUY_V2, #508 OIF) opt-in), " +
            "requireFearGreedBelow(Double,通用 #419: F&G 必須低於此值才允許 LONG 入場 (panic-bottom 策略真實場景過濾)。預設 0=停用。推薦 25 (EXTREME_FEAR 閾值)。RSI<35 必要但不充分,F&G 也低於 25 才算真 panic-bottom), " +
            "requireFearGreedAbove(Double,通用 #425: F&G 必須高於此值才允許 SHORT 入場 (fade-rally 策略真實場景過濾,#419 對稱版)。預設 0=停用。推薦 75 (GREED 閾值)。同時擋 LONG 與 SHORT entry,因 SHORT-only 策略用 SELL signal 開倉), " +
            "entryDedupOpenExposureScope(String,通用 EntryDedup scope: ALL_OPEN_ROWS=預設保守; AUTO_TRADED_OPEN_ROWS=只把 auto-traded open rows 視為真實曝險,用於避免 zero-qty shadow rows 擋實單), " +
            "tradePlanQualityGateEnabled(Boolean,通用 TP/SL 品質閘門；false 會讓低 RR / 寬 SL 計畫不被此 gate 擋，需策略專屬審查), " +
            "tradePlanMinRiskReward(Double,通用 TP/SL 品質閘門最低 R:R；策略 508 TradingView parity 窄放寬建議 0.49), " +
            "tradePlanMaxStopLossPct(Double,通用 TP/SL 品質閘門最大 SL 百分比，小數格式；策略 508 12% disaster SL 窄放寬建議 0.121)")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String setStrategyFlags(
            @ToolParam(required = true, description = "策略 ID") Long strategyId,
            @ToolParam(required = false, description = "shadow/預警模式 (true=只發 TG 不下單)") Boolean notifyOnly,
            @ToolParam(required = false, description = "是否允許 SHORT 方向") Boolean allowShort,
            @ToolParam(required = false, description = "通用 regime filter — 收盤需在 SMA720(30d) 之上") Boolean requireAboveSma200,
            @ToolParam(required = false, description = "SCORE_BUY/EMA_RSI only — MACD 反轉當低點替代") Boolean allowMacdAsLowProxy,
            @ToolParam(required = false, description = "SCORE_BUY/EMA_RSI only — nnOutput 門檻 (預設 0.8)") Double buyThreshold,
            @ToolParam(required = false, description = "SCORE_BUY/EMA_RSI only — RSI 超賣門檻 (預設 40)") Double rsiOversold,
            @ToolParam(required = false, description = "SCORE_BUY/EMA_RSI only — 量爆倍數 (預設 1.5)") Double volumeBreakoutMultiplier,
            @ToolParam(required = false, description = "SCORE_BUY/EMA_RSI only — 252 for 1d / 8760 for 1h") Integer yearLookbackBars,
            @ToolParam(required = true, description = "說明為何改此 flag (audit 追蹤用)") String notes,
            @ToolParam(required = false, description = "Alpha 來源標籤") String alphaSource,
            @ToolParam(required = false, description = "結構化觸發條件說明") String triggerConditions,
            @ToolParam(required = false, description = "CMI 通用 — funding 需高於前 N 小時均值") Integer requireFundingImprovingBars,
            @ToolParam(required = false, description = "CMI 通用 — 前 N 小時不能創新低") Integer requireNoNewLowBars,
            @ToolParam(required = false, description = "通用 #335 — regime classifier 信心 ≥ 此值才 hard-block (classifier conf ∈ {0.8, 1.0}; 0.85=放寬 borderline)") Double regimeFilterMinConfidence,
            @ToolParam(required = false, description = "通用 #335 — RSI 極端 oversold bypass 門檻 (預設 20)") Double regimeBypassRsiThreshold,
            @ToolParam(required = false, description = "通用 #335 — 啟用 panic-bottom RSI bypass (RSI < regimeBypassRsiThreshold 時跳過 TRENDING_DOWN→LONG hard-block)") Boolean allowRsiBypassRegime,
            @ToolParam(required = false, description = "通用 #419 — F&G 必須低於此值才允許 LONG 入場 (預設 0=停用,推薦 25 for panic-bottom 策略)") Double requireFearGreedBelow,
            @ToolParam(required = false, description = "通用 #425 — F&G 必須高於此值才允許 SHORT 入場 (預設 0=停用,推薦 75 for fade-rally 策略,#419 對稱版)") Double requireFearGreedAbove,
            @ToolParam(required = false, description = "EntryDedup open exposure scope: ALL_OPEN_ROWS / AUTO_TRADED_OPEN_ROWS") String entryDedupOpenExposureScope,
            @ToolParam(required = false, description = "Trade-plan quality gate enabled flag") Boolean tradePlanQualityGateEnabled,
            @ToolParam(required = false, description = "Trade-plan quality gate minimum R:R, e.g. 0.49 for Strategy 508 +6/-12 parity") Double tradePlanMinRiskReward,
            @ToolParam(required = false, description = "Trade-plan quality gate maximum SL pct in decimal, e.g. 0.121 for Strategy 508 12% disaster SL") Double tradePlanMaxStopLossPct) {
        if (strategyId == null) return "❌ strategyId 必填";
        if (notes == null || notes.trim().isEmpty()) return "❌ notes 必填(說明為何改此 flag)";
        String normalizedEntryDedupOpenExposureScope = normalizeEntryDedupOpenExposureScope(entryDedupOpenExposureScope);
        if (entryDedupOpenExposureScope != null && normalizedEntryDedupOpenExposureScope == null) {
            return "❌ entryDedupOpenExposureScope 只能是 ALL_OPEN_ROWS 或 AUTO_TRADED_OPEN_ROWS";
        }
        String tradePlanValidationError = validateTradePlanQualityGateParams(
                tradePlanMinRiskReward, tradePlanMaxStopLossPct);
        if (tradePlanValidationError != null) {
            return tradePlanValidationError;
        }
        if (notifyOnly == null && allowShort == null && requireAboveSma200 == null
                && allowMacdAsLowProxy == null && buyThreshold == null && rsiOversold == null
                && volumeBreakoutMultiplier == null && yearLookbackBars == null
                && requireFundingImprovingBars == null && requireNoNewLowBars == null
                && regimeFilterMinConfidence == null && regimeBypassRsiThreshold == null
                && allowRsiBypassRegime == null && requireFearGreedBelow == null
                && requireFearGreedAbove == null && normalizedEntryDedupOpenExposureScope == null
                && tradePlanQualityGateEnabled == null && tradePlanMinRiskReward == null
                && tradePlanMaxStopLossPct == null) {
            return "❌ 至少要指定一個 flag / 參數";
        }
        StrategyResponse strategy;
        try {
            strategy = strategyService.getStrategy(strategyId);
        } catch (Exception e) {
            return "❌ 策略讀取失敗: " + e.getMessage();
        }
        if (strategy == null) return "❌ 策略 " + strategyId + " 不存在";

        // 用 MySQL JSON_SET 原子更新 config_json
        String strategyType = strategy.getStrategyType() != null ? strategy.getStrategyType().toUpperCase() : "";
        boolean isScoreBuyType = strategyType.startsWith("SCORE_BUY") || strategyType.equals("EMA_RSI");
        // SCORE_BUY 專屬參數清單（傳入但類型不符時發警告）
        boolean scoreBuyParamIgnored = !isScoreBuyType && (allowMacdAsLowProxy != null
                || buyThreshold != null || rsiOversold != null
                || volumeBreakoutMultiplier != null || yearLookbackBars != null);

        StringBuilder setClause = new StringBuilder("config_json = JSON_SET(config_json");
        List<Object> params = new ArrayList<>();
        Map<String, Object> changed = new LinkedHashMap<>();
        // 通用 flags（所有策略類型）
        appendFlag(setClause, params, changed, "notifyOnly", notifyOnly);
        appendFlag(setClause, params, changed, "allowShort", allowShort);
        appendFlag(setClause, params, changed, "requireAboveSma200", requireAboveSma200);
        appendFlag(setClause, params, changed, "requireFundingImprovingBars", requireFundingImprovingBars);
        appendFlag(setClause, params, changed, "requireNoNewLowBars", requireNoNewLowBars);
        appendFlag(setClause, params, changed, "regimeFilterMinConfidence", regimeFilterMinConfidence);
        appendFlag(setClause, params, changed, "regimeBypassRsiThreshold", regimeBypassRsiThreshold);
        appendFlag(setClause, params, changed, "allowRsiBypassRegime", allowRsiBypassRegime);
        appendFlag(setClause, params, changed, "requireFearGreedBelow", requireFearGreedBelow);
        appendFlag(setClause, params, changed, "requireFearGreedAbove", requireFearGreedAbove);
        appendFlag(setClause, params, changed, "entryDedupOpenExposureScope", normalizedEntryDedupOpenExposureScope);
        appendFlag(setClause, params, changed, "tradePlanQualityGateEnabled", tradePlanQualityGateEnabled);
        appendFlag(setClause, params, changed, "tradePlanMinRiskReward", tradePlanMinRiskReward);
        appendFlag(setClause, params, changed, "tradePlanMaxStopLossPct", tradePlanMaxStopLossPct);
        // SCORE_BUY / EMA_RSI 專屬 flags — 非此類型時忽略，防止覆蓋 CMI 等指標閾值
        if (isScoreBuyType) {
            appendFlag(setClause, params, changed, "allowMacdAsLowProxy", allowMacdAsLowProxy);
            appendFlag(setClause, params, changed, "buyThreshold", buyThreshold);
            appendFlag(setClause, params, changed, "rsiOversold", rsiOversold);
            appendFlag(setClause, params, changed, "volumeBreakoutMultiplier", volumeBreakoutMultiplier);
            appendFlag(setClause, params, changed, "yearLookbackBars", yearLookbackBars);
        }
        setClause.append(")");
        // Also persist notes, alpha_source, trigger_conditions as first-class columns
        setClause.append(", notes = ?");
        params.add(notes.trim());
        if (alphaSource != null && !alphaSource.isBlank()) {
            setClause.append(", alpha_source = ?");
            params.add(alphaSource.trim());
            changed.put("alpha_source", alphaSource.trim());
        }
        if (triggerConditions != null && !triggerConditions.isBlank()) {
            setClause.append(", trigger_conditions = ?");
            params.add(triggerConditions.trim());
            changed.put("trigger_conditions", triggerConditions.substring(0, Math.min(80, triggerConditions.length())) + "…");
        }
        params.add(strategyId);

        int rows;
        try {
            rows = jdbc.update("UPDATE bt_strategy SET " + setClause + " WHERE id = ?", params.toArray());
        } catch (Exception e) {
            return "❌ 更新失敗: " + e.getMessage();
        }
        if (rows != 1) return "❌ 更新失敗,rows=" + rows;
        strategyService.evictEnabledStrategiesCache();

        log.info("[MCP:setStrategyFlags] strategyId={} changed={} notes={}",
                strategyId, changed, notes.trim());
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("✅ 策略 %d [%s] 更新成功%n", strategyId, strategy.getName()));
        for (Map.Entry<String, Object> e : changed.entrySet()) {
            sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        sb.append("  - notes: ").append(notes.trim());
        if (scoreBuyParamIgnored) {
            sb.append(String.format("%n⚠️ 注意：buyThreshold / rsiOversold / allowMacdAsLowProxy / volumeBreakoutMultiplier / yearLookbackBars 僅適用於 SCORE_BUY/EMA_RSI 類型策略，此策略類型為 %s，上述參數已忽略（未寫入 config）", strategyType));
        }
        return sb.toString();
    }

    static String normalizeEntryDedupOpenExposureScope(String scope) {
        if (scope == null) return null;
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;
        if ("ALL_OPEN_ROWS".equals(normalized) || "AUTO_TRADED_OPEN_ROWS".equals(normalized)) {
            return normalized;
        }
        return null;
    }

    static String validateTradePlanQualityGateParams(Double minRiskReward, Double maxStopLossPct) {
        if (minRiskReward != null && (minRiskReward < 0.05 || minRiskReward > 5.0)) {
            return "❌ tradePlanMinRiskReward 必須介於 0.05 和 5.0 之間";
        }
        if (maxStopLossPct != null && (maxStopLossPct < 0.005 || maxStopLossPct > 0.50)) {
            return "❌ tradePlanMaxStopLossPct 必須介於 0.005 和 0.50 之間";
        }
        return null;
    }

    /**
     * #389 — params where 0/0.0 is meaningful (e.g. "feature disabled"), so the
     * suspicious-zero guard in {@link #appendFlag} must not reject them.
     * Add to this set only for params where:
     *   - 0 is a valid configured value (not a placeholder),
     *   - AND writing 0 won't silently break alpha (e.g. zapping buyThreshold to 0
     *     would silence the strategy — that's the footgun this guard prevents).
     */
    private static final Set<String> ZERO_ALLOWED_PARAMS = Set.of(
            "regimeFilterMinConfidence",  // 0 = bypass disabled (default)
            "requireFundingImprovingBars",// -1 stop value, 0 also valid for "any improvement"
            "requireNoNewLowBars"          // -1 stop value, 0 also valid for "no lookback"
    );

    private void appendFlag(StringBuilder setClause, List<Object> params,
                             Map<String, Object> changed, String key, Object value) {
        if (value == null) return;
        // #389 — guard against MCP client transmitting 0/0.0 as a placeholder for "null".
        // Without this, calling setStrategyFlags with only a single param (e.g. regimeFilterMinConfidence)
        // could silently write 0 to other numeric params (buyThreshold, rsiOversold, etc.)
        // if the client serialiser turns missing fields into 0. That zaps the strategy's alpha.
        if (value instanceof Number n
                && Math.abs(n.doubleValue()) < 1e-12
                && !ZERO_ALLOWED_PARAMS.contains(key)) {
            log.warn("[setStrategyFlags] {}={} rejected as suspicious zero placeholder; not written to config_json",
                    key, value);
            return;
        }
        setClause.append(", '$.").append(key).append("', ?");
        params.add(value);
        changed.put(key, value);
    }

    @Tool(description = "停用指定策略。**notes 必填**:說明停用原因(如「連續虧損 3 筆」、「regime 改變」)。" +
            "param: strategyId=策略ID, notes=停用原因(必填)")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.WRITE_TRADING, Category.GOVERNANCE})
    public String disableStrategy(Long strategyId, String notes) {
        if (notes == null || notes.trim().isEmpty()) {
            return "❌ notes 為必填:說明為何停用此策略(如「WF30d 連續負報酬,需重調參」)";
        }
        return setStrategyEnabled(strategyId, false, notes.trim(), true, false);
    }

    @Tool(description = "乾跑 enableStrategy 閘道邏輯,不實際啟用也不改 DB。" +
            "用於啟用前確認策略能否通過品質門檻+robustness,以及預覽拒絕訊息。" +
            "跟 enableStrategy 相比:僅跳過最後的 updateStrategy + TG 通知,其他 gate check 全跑。" +
            "Robustness 期間會建立 EXT-* 暫存策略並自動清除,與正式 enable 路徑一致。" +
            "param: strategyId")
    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    public String simulateEnableStrategy(Long strategyId) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        StrategyResponse strategy;
        try {
            strategy = strategyService.getStrategy(strategyId);
        } catch (Exception e) {
            return "❌ 讀取策略失敗: " + e.getMessage();
        }
        if (strategy == null) return "❌ 策略 " + strategyId + " 不存在";

        // 1. 基本品質門檻
        BtBacktestResult latest = backtestResultRepository
                .findTopByStrategy_IdOrderByCreatedAtDesc(strategyId)
                .orElse(null);
        if (latest == null) {
            return String.format("❌ [SIMULATION] 策略 [%s] (ID=%d) 會被拒絕%n%n原因: 尚無回測記錄",
                    strategy.getName(), strategyId);
        }
        int tradeCount = latest.getTradeCount() != null ? latest.getTradeCount() : 0;
        double winRate = latest.getWinRate() != null ? latest.getWinRate().doubleValue() : 0;
        double totalRet = latest.getTotalReturn() != null ? latest.getTotalReturn().doubleValue() : 0;
        double maxDD = latest.getMaxDrawdown() != null ? latest.getMaxDrawdown().doubleValue() : 1.0;

        List<String> failures = BacktestQualityValidator.failureReasons(tradeCount, totalRet, maxDD);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== [SIMULATION] enableStrategy dry-run ===%n"));
        sb.append(String.format("策略: [%s] (ID=%d, type=%s, enabled=%s)%n",
                strategy.getName(), strategyId, strategy.getStrategyType(),
                Boolean.TRUE.equals(strategy.getEnabled()) ? "✅" : "❌"));
        sb.append(String.format("最新回測 (ID=%d): %d筆/勝率%.1f%%/%+.2f%%/DD%.1f%%%n%n",
                latest.getId(), tradeCount, winRate * 100, totalRet * 100, maxDD * 100));

        if (!failures.isEmpty()) {
            sb.append("❌ 會被拒絕:品質門檻未通過\n");
            failures.forEach(f -> sb.append(f).append('\n'));
            return sb.toString();
        }
        sb.append("✅ 品質門檻通過\n");

        // 2. Robustness 閘道(樣本 < 15 + SOP_MTF_ADX)
        boolean needsRobustness = tradeCount < BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES
                && "SOP_MTF_ADX".equalsIgnoreCase(strategy.getStrategyType());
        // 3. Walk-forward 閘道(樣本 ≥ 15,不限策略類型)
        boolean needsWalkForward = tradeCount >= BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES;

        if (needsRobustness) {
            sb.append(String.format("⚙️ 樣本 %d < %d,執行 3x robustness 掃描 (SL/TP/ADX, CLIFF閾值 10%%)...%n",
                    tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES));

            List<RobustnessResult> results = new ArrayList<>();
            List<Long> tempIds = new ArrayList<>();
            for (String p : List.of("fixedStopLossPct", "fixedTakeProfitPct", "adxEntryThreshold")) {
                RobustnessResult rr = runRobustnessCore(strategyId, p, 20, 5, 180, "okx", 10.0);
                results.add(rr);
                tempIds.addAll(rr.createdStrategyIds());
            }
            // Cleanup 暫存策略 (同 setStrategyEnabled 的機制)
            for (Long tid : tempIds) {
                try {
                    strategyService.deleteStrategy(tid, "SYSTEM",
                            "robustness 模擬後清理暫存 EXT-* 策略（simulateEnableStrategy）");
                } catch (Exception ignored) {}
            }

            boolean anyFail = results.stream().anyMatch(r ->
                    "CLIFF".equals(r.verdict()) || "BUCKETED".equals(r.verdict())
                 || "SENSITIVE".equals(r.verdict()) || "NEGATIVE".equals(r.verdict()));
            sb.append('\n');
            for (RobustnessResult r : results) {
                String emoji = "SMOOTH".equals(r.verdict()) ? "✅" : "❌";
                sb.append(String.format("  %s %s: %s (gap=%.1f%%)%n",
                        emoji, r.paramName(), r.verdict(), r.maxGap()));
            }
            sb.append('\n');
            if (anyFail) {
                sb.append("🔴 [SIMULATION] 結論: 會被 robustness 閘道拒絕\n");
                sb.append("  建議: 重跑 runAdaptiveDiscovery 取得更高樣本,或 skipRobustnessCheck=true 強制啟用");
            } else {
                sb.append("🟢 [SIMULATION] 結論: 會成功啟用 (robustness 3/3 通過)");
            }
            return sb.toString();
        }

        if (!needsWalkForward) {
            // tradeCount < 15 但非 SOP_MTF_ADX → 兩個閘道都不適用
            sb.append(String.format("⚠️ 非 SOP_MTF_ADX(實際=%s),robustness 工具不支援,閘道跳過%n",
                    strategy.getStrategyType()));
            sb.append("\n🟢 [SIMULATION] 結論: 會成功啟用");
            return sb.toString();
        }

        // tradeCount ≥ 15 → 執行 walk-forward 閘道
        sb.append(String.format("⚙️ 樣本 %d ≥ %d,執行 walk-forward 掃描 (%d folds × %d 天)...%n",
                tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES,
                BtStrategyService.QUALITY_WF_FOLDS, BtStrategyService.QUALITY_WF_DAYS));

        WfResult wf;
        try {
            String wfSymbol = firstSymbol(strategy.getSymbols());
            wf = runWalkForwardCore(strategyId, wfSymbol, "1h",
                    BtStrategyService.QUALITY_WF_DAYS,
                    BtStrategyService.QUALITY_WF_FOLDS,
                    "okx");
        } catch (Exception e) {
            sb.append(String.format("⚠️ WF 驗證 exception: %s (fail open → 會成功啟用)%n", e.getMessage()));
            sb.append("\n🟢 [SIMULATION] 結論: 會成功啟用 (WF 驗證例外,放行)");
            return sb.toString();
        }

        String v = wf.verdict();
        sb.append(String.format("%n  WF verdict=%s  avg=%+.2f%% stdev=%.2f%% ratio=%s  positiveFolds=%d/%d%n",
                v, wf.avgReturn(), wf.stdev(),
                Double.isFinite(wf.ratio()) ? String.format("%.2f", wf.ratio()) : "N/A",
                wf.positiveFolds(), wf.validFolds()));

        boolean wfRejected = "HIGHLY_UNSTABLE".equals(v)
                || "INCONSISTENT".equals(v)
                || "NEGATIVE_MEAN".equals(v);
        if (wfRejected) {
            sb.append("\n🔴 [SIMULATION] 結論: 會被 walk-forward 閘道拒絕");
            sb.append(String.format("%n  原因: verdict=%s%n", v));
            sb.append("  建議: 重調參數提升跨時段穩定性,或 skipRobustnessCheck=true 強制啟用");
        } else if ("UNSTABLE".equals(v)) {
            sb.append("\n🟡 [SIMULATION] 結論: 會成功啟用 (WARN pass)");
            sb.append(String.format("%n  ⚠️  UNSTABLE (ratio=%.2f > %.1f),notes 會標註 [WF:UNSTABLE ratio=%.2f]",
                    wf.ratio(), BtStrategyService.QUALITY_WF_UNSTABLE_RATIO, wf.ratio()));
        } else {
            sb.append(String.format("%n🟢 [SIMULATION] 結論: 會成功啟用 (WF %s)", v));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "#215 Shadow→Active 候選清單：列出所有啟用中且為 shadow mode(notifyOnly=true)的策略，" +
            "顯示各策略的 shadow 觸發次數、ML p_win 平均值、SARS 分數建議，以及距離切 active 還差什麼條件。" +
            "快速判斷哪些策略已準備好從 shadow 轉為 active 實單模式。")
    public String listShadowActivationCandidates() {
        // Query shadow strategies directly: enabled=true AND config_json contains notifyOnly=true
        java.util.Set<Long> shadowIds;
        try {
            shadowIds = queryShadowStrategyIds();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
        List<StrategyResponse> all = strategyService.queryStrategies(new StrategyQueryRequest());
        List<StrategyResponse> shadows = all.stream()
                .filter(s -> shadowIds.contains(s.getId()))
                .toList();

        if (shadows.isEmpty()) {
            return "ℹ️ 目前無啟用中的 shadow mode 策略（notifyOnly=true）。";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Shadow→Active 候選清單 (").append(shadows.size()).append(" 筆) ===\n\n");

        LocalDateTime since30d = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(30);
        for (StrategyResponse s : shadows) {
            sb.append("ID: ").append(s.getId())
              .append("  [").append(s.getName()).append("]\n");
            if (s.getAlphaSource() != null && !s.getAlphaSource().isBlank()) {
                sb.append("  Alpha: ").append(s.getAlphaSource()).append("\n");
            }

            // Signal count in last 30d
            long fired = liveSignalRepository.countByStrategyIdAndCreatedAtAfter(s.getId(), since30d);
            sb.append("  Shadow 觸發(30d): ").append(fired).append(" 次\n");

            // ML inference stats from ml_inference_log
            try {
                List<java.util.Map<String, Object>> mlRows = jdbc.queryForList(
                        "SELECT COUNT(*) as cnt, AVG(m.score) as avg_pwin, " +
                        "SUM(CASE WHEN m.decision='PASS' THEN 1 ELSE 0 END) as passes " +
                        "FROM ml_inference_log m " +
                        "JOIN bt_live_signal ls ON m.live_signal_id = ls.id " +
                        "WHERE ls.strategy_id=? AND m.predicted_at > ?",
                        s.getId(), since30d);
                if (!mlRows.isEmpty()) {
                    Object cnt = mlRows.get(0).get("cnt");
                    Object avg = mlRows.get(0).get("avg_pwin");
                    Object pass = mlRows.get(0).get("passes");
                    long total = cnt != null ? ((Number) cnt).longValue() : 0;
                    if (total > 0) {
                        double avgPwin = avg != null ? ((Number) avg).doubleValue() : 0.0;
                        long passes = pass != null ? ((Number) pass).longValue() : 0;
                        sb.append(String.format("  ML(30d): %d 次推理, avg p_win=%.3f, PASS=%d 次\n",
                                total, avgPwin, passes));
                    } else {
                        sb.append("  ML(30d): 尚無推理記錄（等待 BUY 信號觸發）\n");
                    }
                }
            } catch (Exception e) {
                sb.append("  ML(30d): (查詢失敗)\n");
            }

            // Backtest quality
            BtBacktestResult latest = backtestResultRepository
                    .findTopByStrategy_IdOrderByCreatedAtDesc(s.getId()).orElse(null);
            if (latest != null) {
                int tc = latest.getTradeCount() != null ? latest.getTradeCount() : 0;
                double ret = latest.getTotalReturn() != null ? latest.getTotalReturn().doubleValue() : 0.0;
                double dd = latest.getMaxDrawdown() != null ? latest.getMaxDrawdown().doubleValue() : 1.0;
                boolean pass = BacktestQualityValidator.passes(tc, ret, dd);
                sb.append(String.format("  回測: %s %d筆/%.1f%%/DD%.1f%%\n",
                        pass ? "✅" : "❌", tc, ret * 100, dd * 100));
            }

            sb.append("  → 切 active: assessActivationRisk(strategyId=").append(s.getId()).append(")\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.DIAGNOSTIC, Category.REPORTING})
    @Tool(description = "Shadow strategy readiness dashboard（只讀）：彙總 shadow 策略觸發數、ML p_win/PASS、回測品質、SARS 建議。" +
            "用於判斷是否接近可切 active。params: days=回溯天數(預設30), activationThreshold=p_win門檻(預設0.33)")
    public String getShadowReadinessDashboard(Integer days, Double activationThreshold) {
        int d = days != null ? Math.min(Math.max(days, 7), 180) : 30;
        double threshold = activationThreshold != null ? activationThreshold : 0.33;
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);

        java.util.Set<Long> shadowIds;
        try {
            shadowIds = queryShadowStrategyIds();
        } catch (Exception e) {
            return "❌ 查詢 shadow 策略失敗: " + e.getMessage();
        }
        if (shadowIds.isEmpty()) return "ℹ️ 目前無啟用中的 shadow mode 策略（notifyOnly=true/1）。";

        List<StrategyResponse> shadows = strategyService.queryStrategies(new StrategyQueryRequest()).stream()
                .filter(s -> shadowIds.contains(s.getId()))
                .sorted(java.util.Comparator.comparing(StrategyResponse::getId))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Shadow Readiness Dashboard (%dd, p_win>=%.2f) ===%n%n", d, threshold));
        sb.append(String.format("%-5s| %-32s | %5s | %7s | %7s | %6s | %-10s | %-8s | %s%n",
                "ID", "status", "sig", "avgPwin", "maxPwin", "PASS", "backtest", "SARS", "name"));
        sb.append("-".repeat(142)).append("\n");

        for (StrategyResponse s : shadows) {
            long signals = liveSignalRepository.countByStrategyIdAndCreatedAtAfter(s.getId(), since);
            MlShadowSummary ml = queryMlShadowSummary(s.getId(), since, threshold);
            BtBacktestResult latest = backtestResultRepository
                    .findTopByStrategy_IdOrderByCreatedAtDesc(s.getId()).orElse(null);
            String bt = "N/A";
            boolean btPass = false;
            if (latest != null) {
                int tc = latest.getTradeCount() != null ? latest.getTradeCount() : 0;
                double ret = latest.getTotalReturn() != null ? latest.getTotalReturn().doubleValue() : 0.0;
                double dd = latest.getMaxDrawdown() != null ? latest.getMaxDrawdown().doubleValue() : 1.0;
                btPass = BacktestQualityValidator.passes(tc, ret, dd);
                bt = String.format("%s %dt/%+.1f%%", btPass ? "PASS" : "FAIL", tc, ret * 100);
            }

            int sars = estimateSarsScore(s, latest, 50.0);
            String readiness = progressiveShadowReadiness(signals, ml, threshold, btPass, sars);

            String name = s.getName() != null ? s.getName() : "";
            if (name.length() > 34) name = name.substring(0, 31) + "...";
            sb.append(String.format("%-5d| %-32s | %5d | %7s | %7s | %6d | %-10s | %-8s | %s%n",
                    s.getId(), readiness, signals,
                    ml.total > 0 ? String.format("%.3f", ml.avgPwin) : "N/A",
                    ml.total > 0 ? String.format("%.3f", ml.maxPwin) : "N/A",
                    ml.decisionPass,
                    bt,
                    sars >= 0 ? String.valueOf(sars) : "N/A",
                    name));
        }

        sb.append("\nLegend: progressive read-only rollout labels; no strategy is enabled here.");
        sb.append("\nSHADOW_READY_LOW_SAMPLE means collect more shadow samples; low signals/verified outcomes do not deadlock rollout.");
        sb.append("\nTINY_LIVE_READY_RESTRICTED_RISK means operator review plus tiny risk caps; SARS>5 reduces size instead of permanent WAIT.");
        sb.append("\nAUTONOMOUS_READY_CANONICAL still requires manual review with assessActivationRisk + recent market context before active.");
        return sb.toString();
    }

    private String progressiveShadowReadiness(long signals,
                                              MlShadowSummary ml,
                                              double threshold,
                                              boolean btPass,
                                              int sars) {
        boolean hasCandidateFlow = signals > 0 || ml.total > 0;
        boolean mlObservable = ml.total > 0;
        boolean mlConfidence = mlObservable && ml.avgPwin >= threshold && ml.decisionPass > 0;
        boolean highSars = sars > 5;
        if (!hasCandidateFlow) {
            return "NOT_READY";
        }
        if (signals < 3 || !mlObservable) {
            return "SHADOW_READY_LOW_SAMPLE";
        }
        if (highSars) {
            return "TINY_LIVE_READY_RESTRICTED_RISK";
        }
        if (!btPass || !mlConfidence) {
            return "SHADOW_READY_DEGRADED_EVIDENCE";
        }
        return "AUTONOMOUS_READY_CANONICAL";
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "#238 投資組合策略同時觸發相關性分析：計算所有啟用策略之間的信號相關係數。" +
            "若兩策略高度相關（r>0.7），同時進場會形成集中風險。" +
            "param: days=回溯天數(預設 30)")
    public String getPortfolioSignalCorrelation(Integer days) {
        int d = days != null ? Math.min(Math.max(days, 7), 90) : 30;
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);

        List<StrategyResponse> active = strategyService.queryStrategies(new StrategyQueryRequest()).stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled())).toList();
        if (active.size() < 2) return "ℹ️ 需要至少 2 個啟用中策略才能計算相關性";

        try {
            // For each strategy, build an hourly presence vector
            java.util.Map<Long, java.util.Set<String>> signalHours = new java.util.LinkedHashMap<>();
            for (StrategyResponse s : active) {
                List<java.util.Map<String, Object>> hours = jdbc.queryForList(
                        "SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') as h " +
                        "FROM bt_live_signal WHERE strategy_id=? AND created_at>=? GROUP BY h",
                        s.getId(), since);
                java.util.Set<String> hSet = hours.stream()
                        .map(r -> String.valueOf(r.get("h")))
                        .collect(java.util.stream.Collectors.toSet());
                signalHours.put(s.getId(), hSet);
            }

            // Build all hours in range
            java.util.Set<String> allHours = new java.util.HashSet<>();
            signalHours.values().forEach(allHours::addAll);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Portfolio Signal Correlation (過去 %dd) ===\n\n", d));
            sb.append(String.format("%-8s |", ""));
            for (StrategyResponse s : active) sb.append(String.format(" #%-6d|", s.getId()));
            sb.append("\n").append("-".repeat(10 + active.size() * 9)).append("\n");

            for (StrategyResponse s1 : active) {
                sb.append(String.format("#%-7d |", s1.getId()));
                java.util.Set<String> v1 = signalHours.get(s1.getId());
                for (StrategyResponse s2 : active) {
                    if (s1.getId().equals(s2.getId())) { sb.append("  1.00  |"); continue; }
                    java.util.Set<String> v2 = signalHours.get(s2.getId());
                    // Jaccard similarity as proxy for correlation
                    long intersection = v1.stream().filter(v2::contains).count();
                    long union = v1.size() + v2.size() - intersection;
                    double jaccard = union > 0 ? (double) intersection / union : 0;
                    String warn = jaccard > 0.5 ? "⚠️" : "";
                    sb.append(String.format(" %.2f%s |", jaccard, warn));
                }
                sb.append(String.format(" (n=%d)%n", v1.size()));
            }
            sb.append("\n💡 Jaccard 相似度（共同觸發小時數/聯集）；>0.5=高度重疊，集中風險需關注。\n");
            sb.append("🔍 #508 OIF 應與其他 SOP_MTF 策略低相關（設計上獨立的 alpha 來源）。");
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS})
    @Tool(description = "#230 策略信號觸發頻率統計：顯示策略每週/月觸發幾次信號、" +
            "被 filter 擋掉多少、實際執行多少。" +
            "用於評估 shadow 策略何時才會累積足夠的觸發樣本。" +
            "params: strategyId（必填）, days=回溯天數(預設 90)")
    public String getStrategySignalFrequency(Long strategyId, Integer days) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        int d = days != null ? Math.min(Math.max(days, 7), 365) : 90;
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);

        StrategyResponse strategy;
        try { strategy = strategyService.getStrategy(strategyId); }
        catch (Exception e) { return "❌ 讀取策略失敗: " + e.getMessage(); }
        if (strategy == null) return "❌ 策略 " + strategyId + " 不存在";

        try {
            // Weekly signal counts
            List<java.util.Map<String, Object>> weekly = jdbc.queryForList(
                    "SELECT YEARWEEK(created_at, 1) as yw, COUNT(*) as total, " +
                    "SUM(CASE WHEN auto_traded=1 THEN 1 ELSE 0 END) as traded, " +
                    "SUM(CASE WHEN filter_reason IS NOT NULL THEN 1 ELSE 0 END) as filtered " +
                    "FROM bt_live_signal WHERE strategy_id=? AND created_at>=? " +
                    "GROUP BY yw ORDER BY yw DESC LIMIT 13",
                    strategyId, since);

            long totalSignals = liveSignalRepository.countByStrategyIdAndCreatedAtAfter(strategyId, since);
            long totalTraded = liveSignalRepository.countByStrategyIdAndAutoTradedIsTrue(strategyId);

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("=== Strategy #%d [%s] Signal Frequency ===\n", strategyId, strategy.getName()));
            sb.append(String.format("過去 %dd：%d 筆信號，%d 筆執行\n\n", d, totalSignals, totalTraded));

            if (weekly.isEmpty()) {
                sb.append("ℹ️ 此期間無信號記錄\n");
            } else {
                sb.append(String.format("%-8s | %5s | %7s | %7s%n", "YearWeek", "total", "traded", "filtered"));
                sb.append("-".repeat(38)).append("\n");
                for (var row : weekly) {
                    sb.append(String.format("%-8s | %5d | %7d | %7d%n",
                            row.get("yw"),
                            ((Number) row.get("total")).longValue(),
                            ((Number) row.get("traded")).longValue(),
                            ((Number) row.get("filtered")).longValue()));
                }
                // Estimate weeks to accumulate N shadow signals
                if (totalSignals > 0) {
                    double avgPerWeek = (double) totalSignals / (d / 7.0);
                    sb.append(String.format("\n平均 %.1f 筆/週\n", avgPerWeek));
                    if (avgPerWeek > 0) {
                        int weeksFor3 = (int) Math.ceil(3.0 / avgPerWeek);
                        sb.append(String.format("到達 3 筆 shadow signal 預計還需 %d 週\n", Math.max(0, weeksFor3 - (int)(totalSignals / avgPerWeek))));
                    }
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "❌ 查詢失敗: " + e.getMessage();
        }
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.MODEL_OPS})
    @Tool(description = "#218: 查看指定策略的 shadow ML 推理統計。" +
            "從 ml_inference_log 讀取 shadow 模式下的 p_win 分佈，判斷是否達到啟用條件（條件B: p_win≥threshold）。" +
            "主要用於追蹤 #485 SCORE_BUY_V2 的 ML 信心度積累，決定何時可從 shadow 切 active。" +
            "param: strategyId（必填）, days（回溯天數，預設 90）, activationThreshold（p_win 門檻，預設 0.33）")
    public String getMlShadowSignalStats(Long strategyId, Integer days, Double activationThreshold) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }
        int d = days != null ? Math.min(Math.max(days, 1), 365) : 90;
        double threshold = activationThreshold != null ? activationThreshold : 0.33;
        LocalDateTime since = LocalDateTime.now(java.time.ZoneOffset.UTC).minusDays(d);

        StrategyResponse strategy;
        try { strategy = strategyService.getStrategy(strategyId); }
        catch (Exception e) { return "❌ 讀取策略失敗: " + e.getMessage(); }
        if (strategy == null) return "❌ 策略 " + strategyId + " 不存在";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== ML Shadow Signal Stats: 策略 %d [%s] ===\n", strategyId, strategy.getName()));
        sb.append(String.format("回溯: 過去 %d 天 | 啟用門檻: p_win ≥ %.2f\n\n", d, threshold));

        try {
            // ml_inference_log 無 strategy_id，透過 live_signal_id JOIN bt_live_signal 過濾
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT COUNT(*) as total, " +
                    "AVG(m.score) as avg_pwin, MAX(m.score) as max_pwin, MIN(m.score) as min_pwin, " +
                    "SUM(CASE WHEN m.score >= ? THEN 1 ELSE 0 END) as pass_count, " +
                    "SUM(CASE WHEN m.decision='PASS' THEN 1 ELSE 0 END) as decision_pass " +
                    "FROM ml_inference_log m " +
                    "JOIN bt_live_signal s ON m.live_signal_id = s.id " +
                    "WHERE s.strategy_id=? AND m.predicted_at > ?",
                    threshold, strategyId, since);

            if (rows.isEmpty() || ((Number) rows.get(0).get("total")).longValue() == 0) {
                sb.append("ℹ️ 尚無 ML 推理記錄（等待 BUY 信號觸發）\n\n");
                sb.append("可能原因：\n");
                sb.append("  1. 策略的 BUY 條件（RSI極低+布林下軌+量爆）尚未觸發\n");
                sb.append("  2. mlGateEnabled=true 但尚未走到 ML 評分步驟\n");
            } else {
                java.util.Map<String, Object> r = rows.get(0);
                long total = ((Number) r.get("total")).longValue();
                double avgPwin = r.get("avg_pwin") != null ? ((Number) r.get("avg_pwin")).doubleValue() : 0.0;
                double maxPwin = r.get("max_pwin") != null ? ((Number) r.get("max_pwin")).doubleValue() : 0.0;
                long passCount = ((Number) r.get("pass_count")).longValue();
                long decisionPass = ((Number) r.get("decision_pass")).longValue();

                sb.append(String.format("📊 推理次數: %d 次\n", total));
                sb.append(String.format("   avg p_win: %.4f  |  max: %.4f\n", avgPwin, maxPwin));
                sb.append(String.format("   p_win ≥ %.2f: %d 次 (%.1f%%)\n",
                        threshold, passCount, total > 0 ? (double) passCount / total * 100 : 0.0));
                sb.append(String.format("   決策=PASS: %d 次\n\n", decisionPass));

                boolean conditionBMet = avgPwin >= threshold && passCount > 0;
                if (conditionBMet) {
                    sb.append("✅ 條件B達標: avg p_win=").append(String.format("%.4f", avgPwin))
                      .append(" ≥ ").append(threshold).append("\n");
                    sb.append("→ 可考慮用 setStrategyFlags(notifyOnly=false) 切為實單模式\n");
                    sb.append("→ 建議先執行 assessActivationRisk(strategyId=").append(strategyId).append(") 確認 SARS 分數\n");
                } else {
                    sb.append("⏳ 條件B未達標: avg p_win=").append(String.format("%.4f", avgPwin))
                      .append(" < ").append(threshold).append("\n");
                    sb.append("→ 繼續累積 shadow signal 後再評估\n");
                }
            }
        } catch (Exception e) {
            sb.append("❌ 查詢失敗: ").append(e.getMessage()).append("\n");
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.READ_TRADING, Category.ANALYTICS, Category.GOVERNANCE})
    @Tool(description = "計算策略 SARS（Strategy Activation Risk Score）分數，判斷能否直接切 active 或需等 shadow 觀察期。" +
            "4 維評分：①單筆最壞損失 ②年觸發頻率 ③市場濾網強度 ④持倉回復力。" +
            "0-2分→直接切 active，3-5分→等 1 次 shadow 觸發，6-8分→標準 14-30 天 shadow 期。" +
            "param: strategyId, planUsdt（可選，指定計畫投入 USDT；預設 50，SCORE_BUY_V2 大倉計畫傳 20000）")
    public String assessActivationRisk(Long strategyId, Double planUsdt) {
        { String _e = McpParamValidator.requireNonNull(strategyId, "strategyId"); if (_e != null) return _e; }

        StrategyResponse strategy;
        try {
            strategy = strategyService.getStrategy(strategyId);
        } catch (Exception e) {
            return "❌ 讀取策略失敗: " + e.getMessage();
        }
        if (strategy == null) return "❌ 策略 " + strategyId + " 不存在";

        double baseUsdt = planUsdt != null && planUsdt > 0 ? planUsdt : 50.0;
        String type = strategy.getStrategyType() != null ? strategy.getStrategyType() : "";

        // ① SL% from config_json (default 5%)
        double slPct = 0.05;
        try {
            String slStr = jdbc.queryForObject(
                "SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.fixedStopLossPct')), '0.05') FROM bt_strategy WHERE id=?",
                String.class, strategyId);
            if (slStr != null && !slStr.equals("null")) slPct = Double.parseDouble(slStr);
        } catch (Exception ignored) {}

        // mlGateEnabled for ③
        boolean mlGateEnabled = false;
        try {
            String v = jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.mlGateEnabled')) FROM bt_strategy WHERE id=?",
                String.class, strategyId);
            mlGateEnabled = "true".equalsIgnoreCase(v);
        } catch (Exception ignored) {}

        // Latest backtest for ②
        BtBacktestResult latest = backtestResultRepository
                .findTopByStrategy_IdOrderByCreatedAtDesc(strategyId).orElse(null);
        int tradeCount = latest != null && latest.getTradeCount() != null ? latest.getTradeCount() : 0;
        long btDays = latest != null
                ? Math.max(1, ChronoUnit.DAYS.between(latest.getStartTime(), latest.getEndTime()))
                : 0;
        double annualRate = btDays > 0 ? (double) tradeCount / btDays * 365 : 0;

        // ① Loss
        double worstLoss = baseUsdt * slPct;
        int s1 = worstLoss < 15 ? 0 : worstLoss <= 100 ? 1 : 2;

        // ② Fire rate
        int s2 = annualRate < 20 ? 0 : annualRate <= 60 ? 1 : 2;

        // ③ Gate
        int s3; String s3Label;
        if ("SCORE_BUY_V3".equalsIgnoreCase(type)) {
            s3 = 0; s3Label = "Phase 2 多重條件（F&G + whale + funding + SMA200，≥1 必要）";
        } else if ("SCORE_BUY_V2".equalsIgnoreCase(type)) {
            s3 = 1; s3Label = "ML scorer gate (p_win ≥ buyThreshold)";
        } else if ("SOP_MTF_ADX".equalsIgnoreCase(type)) {
            s3 = mlGateEnabled ? 0 : 1;
            s3Label = mlGateEnabled ? "Gemini hint + ML gate（雙重）" : "Gemini hint gate（DISABLE 封鎖）";
        } else {
            s3 = 2; s3Label = "無外部 gate（純 K 線信號）";
        }

        // ④ Recovery
        int s4; String s4Label;
        if ("SCORE_BUY_V3".equalsIgnoreCase(type) || "SCORE_BUY_V2".equalsIgnoreCase(type)) {
            s4 = 0; s4Label = "恐慌底部買入（F&G<25，30 天內 ~80% 回復）";
        } else if ("SOP_MTF_ADX".equalsIgnoreCase(type) || "MEAN_REVERSION".equalsIgnoreCase(type)) {
            s4 = 1; s4Label = "趨勢追蹤（靠 SL/TP 管理，依賴趨勢延續）";
        } else {
            s4 = 2; s4Label = "回復期不確定（熊市中段 / 高頻）";
        }

        int total = s1 + s2 + s3 + s4;
        String decision = total <= 2 ? "✅ 直接切 active" : total <= 5 ? "⚠️ 等 1 次 shadow 觸發" : "🔶 標準 14-30 天 shadow 期";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== SARS: 策略 %d [%s] ===%n", strategyId, strategy.getName()));
        sb.append(String.format("類型: %s | 狀態: %s%n%n",
                type, Boolean.TRUE.equals(strategy.getEnabled()) ? "enabled ✅" : "shadow/disabled"));
        sb.append(String.format("① 單筆最壞損失   %d分  $%.1f  (SL=%.1f%% × $%.0f)%n",
                s1, worstLoss, slPct * 100, baseUsdt));
        sb.append(String.format("② 年觸發頻率     %d分  %.0f/年  (%d筆 / %dd × 365)%n",
                s2, annualRate, tradeCount, btDays));
        sb.append(String.format("③ 市場濾網強度   %d分  %s%n", s3, s3Label));
        sb.append(String.format("④ 持倉回復力     %d分  %s%n", s4, s4Label));
        sb.append(String.format("%n=== 總分 %d/8 → %s ===%n", total, decision));

        if (latest != null) {
            sb.append(String.format("%n📊 回測: %d筆 / %+.2f%% / DD %.1f%% / 勝率 %.0f%%",
                    tradeCount,
                    latest.getTotalReturn().doubleValue() * 100,
                    latest.getMaxDrawdown().doubleValue() * 100,
                    latest.getWinRate().doubleValue() * 100));
        } else {
            sb.append("\n⚠️ 無回測記錄，② 頻率無法計算");
        }
        if (planUsdt != null && planUsdt > 50) {
            sb.append(String.format("%n⚠️ 使用計畫投入 $%.0f 計算（非自動交易 $50 基礎倉位）", baseUsdt));
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "建立新策略（預設停用）。建立後須先執行 runBacktest 累積足夠回測資料，" +
            "通過品質門檻（tradeCount≥5, totalReturn>0, maxDrawdown≤20%）才能啟用。\n" +
            "strategyType 可用值: SCORE_BUY / EMA_RSI / SOP_MTF_ADX。\n" +
            "symbols【必填】逗號分隔幣種，例如 'BTCUSDT' 或 'ETHUSDT'（不可為空）。\n" +
            "fixedStopLossPct/fixedTakeProfitPct 為小數（例如 0.02=2%）。\n" +
            "【SOP_MTF_ADX 核心參數】adxThreshold=ADX進場門檻(建議20~30), " +
            "minSignals=最低訊號數(1~4), allowShort=是否做空, " +
            "rsiPullback=RSI回調門檻, rsiRebound=RSI反彈確認門檻, minRR=最低風報比(建議1.5)。")
    public String createStrategy(String name, String strategyType, String symbols,
                                 Double fixedStopLossPct, Double fixedTakeProfitPct,
                                 // SCORE_BUY / EMA_RSI 參數
                                 Double rsiSellThreshold, Double rsiOversold, Double buyThreshold,
                                 // SOP_MTF_ADX 核心參數
                                 Double adxThreshold, Integer minSignals, Boolean allowShort,
                                 Double rsiPullback, Double rsiRebound, Double minRR) {

        if (symbols == null || symbols.trim().isEmpty()) {
            return "❌ 建立失敗：symbols 為必填欄位，請指定監控幣種（如 'BTCUSDT' 或 'ETHUSDT'）";
        }

        SopMtfAdxConfig config = new SopMtfAdxConfig();
        if (fixedStopLossPct != null)   config.setFixedStopLossPct(fixedStopLossPct);
        if (fixedTakeProfitPct != null) config.setFixedTakeProfitPct(fixedTakeProfitPct);
        if (rsiSellThreshold != null)   config.setRsiSellThreshold(rsiSellThreshold);
        if (rsiOversold != null)        config.setRsiOversold(rsiOversold);
        if (buyThreshold != null)       config.setBuyThreshold(buyThreshold);
        if (adxThreshold != null)       config.setAdxEntryThreshold(adxThreshold);
        if (minSignals != null)         config.setMinSignals(minSignals);
        if (allowShort != null)         config.setAllowShort(allowShort);
        if (rsiPullback != null)        config.setRsiPullbackThreshold(rsiPullback);
        if (rsiRebound != null)         config.setRsiReboundConfirm(rsiRebound);
        if (minRR != null)              config.setMinRR(minRR);

        CreateStrategyRequest req = new CreateStrategyRequest();
        req.setName(name.trim());
        req.setStrategyType(strategyType.trim().toUpperCase());
        req.setSymbols(symbols.trim());
        req.setConfig(config);

        StrategyResponse result = strategyService.createStrategy(req);
        log.info("[MCP] 建立策略 id={} name={} type={} symbols={}", result.getId(), result.getName(), result.getStrategyType(), result.getSymbols());

        return String.format(
                "✅ 策略建立成功（預設停用）\n\n" +
                "ID: %d\n名稱: %s\n類型: %s\n監控幣種: %s\n\n" +
                "啟用前須通過品質門檻：tradeCount≥5 / totalReturn>0%% / maxDrawdown≤20%%\n\n" +
                "下一步：runBacktest(strategyId=%d, symbol=\"%s\", intervalCode=\"1h\", days=180)\n" +
                "回測完成後確認交易筆數 ≥ 10，再執行 enableStrategy(strategyId=%d)",
                result.getId(), result.getName(), result.getStrategyType(), result.getSymbols(),
                result.getId(), firstSymbol(result.getSymbols()),
                result.getId()
        );
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.WRITE_TRADING})
    @Tool(description = "批次刪除廢棄的 AI 探索策略（名稱以指定前綴開頭且已停用）。\n" +
            "dryRun=true 時只預覽不刪除；dryRun=false 才實際刪除。\n" +
            "namePrefix 預設為 'EXT-'，可改為 'AI-' 等其他前綴。\n" +
            "只會刪除 enabled=false 的策略，啟用中的策略一律跳過。")
    public String cleanupStrategies(String namePrefix, Boolean dryRun) {
        String prefix = (namePrefix != null && !namePrefix.isBlank()) ? namePrefix.trim() : "EXT-";
        boolean preview = !Boolean.FALSE.equals(dryRun); // 預設 dryRun=true（安全第一）

        List<StrategyResponse> all = strategyService.queryStrategies(new StrategyQueryRequest());
        List<StrategyResponse> targets = all.stream()
                .filter(s -> s.getName() != null && s.getName().startsWith(prefix))
                .filter(s -> !Boolean.TRUE.equals(s.getEnabled()))
                .toList();

        if (targets.isEmpty()) {
            return String.format("無符合條件的策略（前綴='%s'，已停用）。", prefix);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(preview ? "【預覽模式】" : "【執行刪除】")
          .append(" 前綴='").append(prefix).append("'，共 ").append(targets.size()).append(" 筆\n\n");

        int deleted = 0;
        List<String> errors = new ArrayList<>();
        for (StrategyResponse s : targets) {
            sb.append("  ID=").append(s.getId()).append(" ").append(s.getName());
            if (preview) {
                sb.append(" → 待刪除\n");
            } else {
                try {
                    strategyService.deleteStrategy(s.getId(), "MCP",
                            String.format("MCP cleanupStrategies 刪除（prefix=%s, dryRun=false）", prefix));
                    sb.append(" → ✅ 已刪除\n");
                    deleted++;
                } catch (Exception e) {
                    String err = "ID=" + s.getId() + ": " + e.getMessage();
                    sb.append(" → ❌ 失敗（").append(e.getMessage()).append("）\n");
                    errors.add(err);
                    log.warn("[cleanupStrategies] Delete failed: {}", err);
                }
            }
        }

        if (preview) {
            sb.append("\n呼叫 cleanupStrategies(namePrefix='").append(prefix)
              .append("', dryRun=false) 確認刪除。");
        } else {
            sb.append("\n已刪除 ").append(deleted).append("/").append(targets.size()).append(" 筆。");
            if (!errors.isEmpty()) {
                sb.append(" 失敗 ").append(errors.size()).append(" 筆。");
            }
            // TG 通知已由 TelegramServiceImpl queue 自動合併，每 1.1s 最多送 5 條
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Walk-forward 驗證:把指定天數切成 N 段,各段獨立跑 backtest,看各段 PnL 分布一致性。" +
            "用於偵測策略過擬合:若各段差距大(stdev > mean),說明參數只適合回測期,out-of-sample 失效。" +
            "param: strategyId(策略ID), symbol, intervalCode, totalDays(總期間,預設180), folds(切幾段,預設5), source(binance/okx,預設 okx)")
    public String validateWalkForward(Long strategyId, String symbol, String intervalCode,
                                       Integer totalDays, Integer folds, String source) {
        if (strategyId == null) return "❌ strategyId 不可為空";
        String sym = symbol != null ? symbol.toUpperCase() : "BTCUSDT";
        String itv = intervalCode != null ? intervalCode.toLowerCase() : "1h";
        int days = totalDays != null ? totalDays : 180;
        int k = folds != null ? Math.max(2, Math.min(folds, 12)) : 5;
        String src = (source != null && !source.isBlank()) ? source.toLowerCase().trim() : "okx";

        WfResult wf = runWalkForwardCore(strategyId, sym, itv, days, k, src);
        return wf.formatted();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.DIAGNOSTIC, Category.ANALYTICS})
    @Tool(description = "Robustness 驗證:對 SOP_MTF_ADX 策略的關鍵參數做 ±N% 掃描,看 PnL 曲線平滑還是懸崖。" +
            "懸崖 = 過擬合(參數微調 PnL 暴跌);平滑 = 穩健。" +
            "param: strategyId(必須是 SOP_MTF_ADX 類型), paramName(adxEntryThreshold/fixedStopLossPct/fixedTakeProfitPct), " +
            "deltaPct(掃描範圍,預設20=±20%), steps(掃描點數,預設5), days(回測天數,預設180), source(預設 okx)")
    public String validateRobustness(Long strategyId, String paramName, Integer deltaPct,
                                      Integer steps, Integer days, String source) {
        if (strategyId == null || paramName == null) return "❌ strategyId 與 paramName 不可為空";
        int delta = deltaPct != null ? Math.max(5, Math.min(deltaPct, 50)) : 20;
        int n = steps != null ? Math.max(3, Math.min(steps, 11)) : 5;
        int daysVal = days != null ? days : 180;
        String src = (source != null && !source.isBlank()) ? source.toLowerCase().trim() : "okx";

        RobustnessResult result = runRobustnessCore(strategyId, paramName, delta, n, daysVal, src, 15.0);

        // 自動 cleanup 本次建立的 EXT-* 暫存策略,避免 DB 累積
        int cleaned = 0, failed = 0;
        for (Long tempId : result.createdStrategyIds()) {
            try {
                strategyService.deleteStrategy(tempId, "SYSTEM",
                        "validateRobustness 後清理暫存 EXT-* 策略");
                cleaned++;
            } catch (Exception e) {
                failed++;
                log.warn("[MCP] validateRobustness cleanup failed strategyId={}: {}", tempId, e.getMessage());
            }
        }
        if (cleaned > 0 || failed > 0) {
            log.info("[MCP] validateRobustness cleanup: deleted {}/{} temp strategies (failed={})",
                    cleaned, result.createdStrategyIds().size(), failed);
        }
        return result.formatted();
    }

    // ─── 核心閘道實作 ───────────────────────────────────────────────────────────

    /** 直接查 config_json 的 boolean flag(避開 SopMtfAdxConfig DTO 未收 notifyOnly/allowShort 的 gap)。 */
    private boolean queryStrategyFlag(Long strategyId, String flagKey) {
        try {
            String path = "$." + flagKey;
            Object v = jdbc.queryForObject(
                    "SELECT JSON_EXTRACT(config_json, ?) FROM bt_strategy WHERE id = ?",
                    Object.class, path, strategyId);
            if (v == null) return false;
            String s = String.valueOf(v).trim().toLowerCase();
            return "true".equals(s) || "1".equals(s);
        } catch (Exception e) {
            log.debug("[MCP] queryStrategyFlag failed id={} key={}: {}", strategyId, flagKey, e.getMessage());
            return false;
        }
    }

    /**
     * #402 — query a string-valued field from config_json. Returns null when the key
     * is missing or stored as JSON null. Trims surrounding quotes (JSON_UNQUOTE) so
     * callers see the bare value, not {@code "etf_pressure_index"} with quotes.
     */
    private String queryStrategyConfigString(Long strategyId, String key) {
        try {
            String path = "$." + key;
            Object v = jdbc.queryForObject(
                    "SELECT JSON_UNQUOTE(JSON_EXTRACT(config_json, ?)) FROM bt_strategy WHERE id = ?",
                    Object.class, path, strategyId);
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            return ("null".equalsIgnoreCase(s) || s.isEmpty()) ? null : s;
        } catch (Exception e) {
            log.debug("[MCP] queryStrategyConfigString failed id={} key={}: {}", strategyId, key, e.getMessage());
            return null;
        }
    }

    private String setStrategyEnabled(Long strategyId, boolean enable, String notes,
                                        boolean skipRobustnessCheck, boolean skipQualityGate) {
        StrategyResponse strategy = strategyService.getStrategy(strategyId);

        // ── 冪等檢查 ─────────────────────────────────────────────────────────────
        if (enable && Boolean.TRUE.equals(strategy.getEnabled())) {
            return String.format("ℹ️ 策略 [%s] (ID=%d) 已在啟用中，無需重複操作。",
                    strategy.getName(), strategyId);
        }
        if (!enable && Boolean.FALSE.equals(strategy.getEnabled())) {
            return String.format("ℹ️ 策略 [%s] (ID=%d) 已在停用中，無需重複操作。",
                    strategy.getName(), strategyId);
        }

        // ── #402 type-specific config preflight (enable only) ───────────────────
        // CMI_MIH_THRESHOLD must declare mihIndicator explicitly. Default fallback
        // "sqi" was the silent root of #566/#567 monitoring the wrong indicator.
        if (enable && "CMI_MIH_THRESHOLD".equals(strategy.getStrategyType())) {
            String mih = queryStrategyConfigString(strategyId, "mihIndicator");
            if (mih == null) {
                return String.format(
                    "❌ 策略 [%s] (ID=%d) 啟用被拒絕\n\n" +
                    "原因 (#402): CMI_MIH_THRESHOLD 類型必須在 config_json 顯式設定 mihIndicator 欄位。\n" +
                    "舊預設 fallback 為 \"sqi\" 是 #566 / #567 名實不符的根因 — strategy 名稱說 ETF/MEI " +
                    "但實際監控 SQI，alpha attribution 完全錯。\n\n" +
                    "修法 (SSH SQL):\n" +
                    "  UPDATE bt_strategy SET config_json = JSON_SET(config_json, '$.mihIndicator', '<指標名>')\n" +
                    "  WHERE id=%d;\n\n" +
                    "可選指標 (依 mih_indicator_history.indicator 欄位): sqi / etf_pressure_index /\n" +
                    "market_entropy_index / vdi / short_build_index / sqi_short_crowding 等。",
                    strategy.getName(), strategyId, strategyId);
            }
        }

        // ── 停用前開倉安全檢查 ───────────────────────────────────────────────────
        if (!enable) {
            List<BtLiveSignal> openPositions = liveSignalRepository
                    .findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(strategyId);
            if (!openPositions.isEmpty()) {
                StringBuilder warn = new StringBuilder();
                warn.append(String.format(
                    "⚠️ 策略 [%s] (ID=%d) 有 %d 個進行中的倉位，停用後影響如下：\n\n",
                    strategy.getName(), strategyId, openPositions.size()));
                for (BtLiveSignal pos : openPositions) {
                    warn.append(String.format("  • #%d %s @ $%s（OCO 保護仍有效）\n",
                            pos.getId(), pos.getSymbol(),
                            pos.getActualEntryPrice() != null ? pos.getActualEntryPrice().toPlainString()
                                                              : pos.getEntryPrice().toPlainString()));
                }
                warn.append("\n停用後 TP/SL OCO 訂單仍在交易所執行，但 SELL_SIGNAL 出場路徑將停止。\n");
                warn.append("策略已停用。如需立即平倉請使用交易所介面或 market-sell 指令。");
                // 仍繼續執行停用
                UpdateStrategyRequest req = new UpdateStrategyRequest();
                req.setName(strategy.getName());
                req.setStrategyType(strategy.getStrategyType());
                req.setEnabled(false);
                req.setSymbols(strategy.getSymbols());
                req.setConfig(strategy.getConfig());
                req.setNotes(notes);
                strategyService.updateStrategy(strategyId, req);
                log.info("[MCP] 停用策略 id={} name={} 有 {} 個開倉", strategyId, strategy.getName(), openPositions.size());
                String auditReasonDisable = notes.length() > 400 ? notes.substring(0, 400) : notes;
                auditWriter.logOverrideApplied(strategyId, null, "Strategy.Disable",
                        "(open-positions=" + openPositions.size() + ") " + auditReasonDisable);
                return warn.toString();
            }
        }

        // ── 品質驗證閘道（enable=true 時執行，變數提升至外層供成功訊息重用）────────
        int tradeCount = 0;
        double winRate = 0.0, totalRet = 0.0, maxDD = 1.0;
        double tradesPerMonth = 0.0, avgDaysBetween = 0.0;

        if (enable) {
            BtBacktestResult latest = backtestResultRepository
                    .findTopByStrategy_IdOrderByCreatedAtDesc(strategyId)
                    .orElse(null);

            if (latest == null) {
                return String.format(
                    "❌ 策略 [%s] (ID=%d) 啟用被拒絕\n\n" +
                    "原因：尚無回測記錄。\n" +
                    "請先執行 runBacktest(strategyId=%d, ...) 並確認品質達標後再啟用。",
                    strategy.getName(), strategyId, strategyId);
            }

            tradeCount = latest.getTradeCount()  != null ? latest.getTradeCount()  : 0;
            winRate    = latest.getWinRate()      != null ? latest.getWinRate().doubleValue()      : 0.0;
            totalRet   = latest.getTotalReturn()  != null ? latest.getTotalReturn().doubleValue()  : 0.0;
            maxDD      = latest.getMaxDrawdown()  != null ? latest.getMaxDrawdown().doubleValue()  : 1.0;

            long lookbackDays = (latest.getStartTime() != null && latest.getEndTime() != null)
                    ? ChronoUnit.DAYS.between(latest.getStartTime(), latest.getEndTime()) : 0;
            tradesPerMonth = (lookbackDays > 0 && tradeCount > 0) ? (tradeCount * 30.0 / lookbackDays) : 0;
            avgDaysBetween = tradeCount > 0 ? (lookbackDays * 1.0 / tradeCount) : 0;

            List<String> failures = BacktestQualityValidator.failureReasons(tradeCount, totalRet, maxDD);

            if (!failures.isEmpty()) {
                // shadow-mode override:skipQualityGate 僅在 config.notifyOnly=true 才允許
                // (notifyOnly 表示只發 TG 不實際下單,質量 fail 無金錢風險;用於累積 ml_inference_log 樣本)
                boolean notifyOnly = queryStrategyFlag(strategyId, "notifyOnly");
                if (skipQualityGate && !notifyOnly) {
                    return String.format(
                        "❌ 策略 [%s] (ID=%d) 啟用被拒絕\n\n" +
                        "skipQualityGate=true 僅適用於 notifyOnly=true 的策略(shadow-mode)。\n" +
                        "此策略 config.notifyOnly 未設為 true → 拒絕。\n" +
                        "如要 shadow 模式:先 setStrategyFlags(strategyId=%d, notifyOnly=true, notes=...) 再重試。",
                        strategy.getName(), strategyId, strategyId);
                }
                if (!skipQualityGate) {
                    return String.format(
                        "❌ 策略 [%s] (ID=%d) 啟用被拒絕\n\n" +
                        "未達品質門檻：\n%s\n\n" +
                        "最新回測（ID=%d）指標：\n" +
                        "  交易=%d筆  勝率=%.1f%%  報酬=%.2f%%  回撤=%.1f%%\n" +
                        "  頻率: 每月 %.1f 筆  平均每 %.1f 天觸發一次",
                        strategy.getName(), strategyId,
                        String.join("\n", failures),
                        latest.getId(),
                        tradeCount, winRate * 100, totalRet * 100, maxDD * 100,
                        tradesPerMonth, avgDaysBetween);
                }
                // skipQualityGate=true + notifyOnly=true → 允許但留痕
                log.warn("[MCP] ⚠️ strategyId={} name={} 品質門檻未通過但 skipQualityGate+notifyOnly 允許,強制啟用為 shadow mode。失敗項: {}",
                        strategyId, strategy.getName(), failures);
                notes = "⚠️ shadow-mode (notifyOnly=true, skipQualityGate=true) " +
                        "backtest fail: " + String.join(",", failures).trim() + " | " + notes;
            }

            log.info("[MCP] 品質驗證通過：strategyId={} tradeCount={} winRate={}% totalReturn={}% maxDD={}% tradesPerMonth={}",
                    strategyId, tradeCount,
                    String.format("%.1f", winRate * 100),
                    String.format("%.2f", totalRet * 100),
                    String.format("%.1f", maxDD * 100),
                    String.format("%.1f", tradesPerMonth));

            // ── 穩健性閘道(樣本 < 15 必跑 robustness,任一 CLIFF 拒絕;樣本 ≥ 15 跑 walk-forward) ──
            boolean needsRobustness = tradeCount < BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES
                    && "SOP_MTF_ADX".equalsIgnoreCase(strategy.getStrategyType())
                    && !skipRobustnessCheck;
            boolean needsWalkForward = tradeCount >= BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES
                    && !skipRobustnessCheck;
            if (needsRobustness) {
                log.info("[MCP] strategyId={} tradeCount={} < {} → 執行 robustness 閘道",
                        strategyId, tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES);

                List<RobustnessResult> results = new ArrayList<>();
                List<Long> tempStrategyIds = new ArrayList<>();
                // enableStrategy 閘道用 10% CLIFF 閾值(比 MCP 工具的 15% 嚴格),
                // 因為低樣本策略的 bucket pattern(如 284: 5 vs 35 trades)需要更敏感的偵測
                for (String p : List.of("fixedStopLossPct", "fixedTakeProfitPct", "adxEntryThreshold")) {
                    RobustnessResult rr = runRobustnessCore(strategyId, p, 20, 5, 180, "okx", 10.0);
                    results.add(rr);
                    tempStrategyIds.addAll(rr.createdStrategyIds());
                }

                // 事後清理:用精確策略 ID 刪除 robustness 建立的暫存策略
                // (避免 batchId 碰撞時 prefix 匹配遺漏,且不受 anchor 是否啟用影響)
                int deletedCount = 0;
                for (Long tempId : tempStrategyIds) {
                    try {
                        strategyService.deleteStrategy(tempId, "SYSTEM",
                                "enableStrategy robustness 閘道後清理暫存 EXT-* 策略");
                        deletedCount++;
                    } catch (Exception e) {
                        log.warn("[MCP] robustness cleanup failed strategyId={}: {}", tempId, e.getMessage());
                    }
                }
                log.info("[MCP] robustness cleanup: deleted {}/{} temp strategies",
                        deletedCount, tempStrategyIds.size());

                boolean anyCliff = results.stream().anyMatch(r -> "CLIFF".equals(r.verdict())
                        || "NEGATIVE".equals(r.verdict()) || "SENSITIVE".equals(r.verdict())
                        || "BUCKETED".equals(r.verdict()));
                if (anyCliff) {
                    StringBuilder rejection = new StringBuilder();
                    rejection.append(String.format("❌ 策略 [%s] (ID=%d) 啟用被拒絕%n%n",
                            strategy.getName(), strategyId));
                    rejection.append(String.format("未達穩健性閘道 (樣本 %d < %d,須通過 robustness 驗證):%n",
                            tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES));
                    for (RobustnessResult r : results) {
                        String emoji = "SMOOTH".equals(r.verdict()) ? "✅" : "❌";
                        rejection.append(String.format("  • %s: %s %s (相鄰最大差 %.1f%%)%n",
                                r.paramName(), emoji, r.verdict(), r.maxGap()));
                    }
                    rejection.append(String.format("%n最新回測 (ID=%d): 交易=%d筆 勝率=%.1f%% 報酬=%+.2f%% 回撤=%.1f%%%n",
                            latest.getId(), tradeCount, winRate * 100, totalRet * 100, maxDD * 100));
                    rejection.append("建議: 重跑 runAdaptiveDiscovery 取得更高樣本配置。" +
                            "如人工審核過要啟用,可傳 skipRobustnessCheck=true (會發 TG 警告)。");
                    log.warn("[MCP] robustness gate rejected strategyId={}: verdicts={}",
                            strategyId, results.stream().map(RobustnessResult::verdict).toList());
                    return rejection.toString();
                }

                // 通過 → 把 robustness summary 附加到 notes
                String robustnessSummary = results.stream()
                        .map(RobustnessResult::summary)
                        .collect(java.util.stream.Collectors.joining(", "));
                notes = notes + " | Robustness: " + robustnessSummary;
                log.info("[MCP] robustness gate passed strategyId={}: {}", strategyId, robustnessSummary);
            } else if (skipRobustnessCheck && tradeCount < BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES) {
                log.warn("[MCP] ⚠️ skipRobustnessCheck=true strategyId={} tradeCount={} < {}",
                        strategyId, tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES);
                notes = notes + " | ⚠️ skipRobustnessCheck=true (low-sample bypass)";
            }

            // ── Walk-forward 閘道(樣本 ≥ 15):檢查各時段一致性,HIGHLY_UNSTABLE/INCONSISTENT/NEGATIVE_MEAN 拒絕 ──
            // 設計:高樣本策略也可能 regime-dependent(如 315 整體 +19% 但 5 folds 分布波動大),
            // 用 stdev/|mean| 比值量化 out-of-sample 穩定性,比單一 totalReturn 更嚴謹。
            if (needsWalkForward) {
                log.info("[MCP] strategyId={} tradeCount={} >= {} → 執行 walk-forward 閘道",
                        strategyId, tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES);

                WfResult wf;
                try {
                    String wfSymbol = firstSymbol(strategy.getSymbols());
                    wf = runWalkForwardCore(strategyId, wfSymbol, "1h",
                            BtStrategyService.QUALITY_WF_DAYS,
                            BtStrategyService.QUALITY_WF_FOLDS,
                            "okx");
                } catch (Exception e) {
                    // Fail open:WF 本身 exception 不卡 enable(驗證工具故障不應阻塞正常流程)
                    log.error("[MCP] walk-forward gate exception strategyId={} (fail open): {}",
                            strategyId, e.getMessage(), e);
                    notes = notes + " | ⚠️ WF exception, skipped";
                    wf = null;
                }

                if (wf != null) {
                    String v = wf.verdict();
                    boolean wfRejected = "HIGHLY_UNSTABLE".equals(v)
                            || "INCONSISTENT".equals(v)
                            || "NEGATIVE_MEAN".equals(v);
                    if (wfRejected) {
                        StringBuilder rejection = new StringBuilder();
                        rejection.append(String.format("❌ 策略 [%s] (ID=%d) 啟用被拒絕%n%n",
                                strategy.getName(), strategyId));
                        rejection.append(String.format("未達 Walk-Forward 閘道 (樣本 %d ≥ %d,須通過時段一致性驗證):%n",
                                tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES));
                        rejection.append(String.format("  verdict=%s%n", v));
                        rejection.append(String.format("  avgReturn=%+.2f%%  stdev=%.2f%%  ratio=%s%n",
                                wf.avgReturn(), wf.stdev(),
                                Double.isFinite(wf.ratio()) ? String.format("%.2f", wf.ratio()) : "N/A"));
                        rejection.append(String.format("  positiveFolds=%d/%d  range=[%+.2f%%, %+.2f%%]%n",
                                wf.positiveFolds(), wf.validFolds(), wf.minReturn(), wf.maxReturn()));
                        rejection.append(String.format("%n最新回測 (ID=%d): 交易=%d筆 勝率=%.1f%% 報酬=%+.2f%% 回撤=%.1f%%%n",
                                latest.getId(), tradeCount, winRate * 100, totalRet * 100, maxDD * 100));
                        rejection.append("建議: 重調參數提升跨時段穩定性,或 skipRobustnessCheck=true 強制啟用 (會發 TG 警告)。");
                        log.warn("[MCP] walk-forward gate rejected strategyId={} verdict={} ratio={}",
                                strategyId, v, wf.ratio());
                        return rejection.toString();
                    }

                    if ("UNSTABLE".equals(v)) {
                        // WARN pass:標註 notes 供 audit,仍放行
                        String wfTag = String.format(" | [WF:UNSTABLE ratio=%.2f avg=%+.2f%%]",
                                wf.ratio(), wf.avgReturn());
                        notes = notes + wfTag;
                        log.warn("[MCP] walk-forward gate WARN pass strategyId={} ratio={} avg={}%",
                                strategyId, wf.ratio(), wf.avgReturn());
                    } else if ("STABLE".equals(v)) {
                        notes = notes + String.format(" | WF: STABLE ratio=%.2f",
                                Double.isFinite(wf.ratio()) ? wf.ratio() : 0);
                        log.info("[MCP] walk-forward gate passed strategyId={} STABLE ratio={}",
                                strategyId, wf.ratio());
                    } else {
                        // DATA_INSUFFICIENT / 其他 → 放行但標註(避免 K 線缺口卡主流程)
                        notes = notes + " | WF: " + v;
                        log.warn("[MCP] walk-forward gate inconclusive strategyId={} verdict={} (allowed)",
                                strategyId, v);
                    }
                }
            } else if (skipRobustnessCheck && tradeCount >= BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES) {
                log.warn("[MCP] ⚠️ skipRobustnessCheck=true strategyId={} tradeCount={} >= {} (WF bypassed)",
                        strategyId, tradeCount, BtStrategyService.QUALITY_ROBUSTNESS_EXEMPT_TRADES);
                notes = notes + " | ⚠️ skipRobustnessCheck=true (WF bypassed)";
            }
        }

        UpdateStrategyRequest req = new UpdateStrategyRequest();
        req.setName(strategy.getName());
        req.setStrategyType(strategy.getStrategyType());
        req.setEnabled(enable);
        req.setSymbols(strategy.getSymbols());
        req.setConfig(strategy.getConfig());
        req.setNotes(notes);

        strategyService.updateStrategy(strategyId, req);
        strategyService.evictEnabledStrategiesCache();  // 使 LiveSignalEvaluator 快取立即失效
        log.info("[MCP] {} 策略 id={} name={} notes={}", enable ? "啟用" : "停用", strategyId, strategy.getName(), notes);

        // 寫入 enable/disable audit,供 getStrategyEnableHistory 查詢
        String blocker = enable ? "Strategy.Enable" : "Strategy.Disable";
        String auditReason = notes.length() > 400 ? notes.substring(0, 400) : notes;
        auditWriter.logOverrideApplied(strategyId, null, blocker, auditReason);

        // V14 — notify WsSubscriptionSyncer so it can diff + sub/unsub.
        // Publishing via Spring event decouples MCP layer from WS layer and
        // avoids the Spring AI circular-dep trap (no direct field injection).
        try {
            eventPublisher.publishEvent(
                    new com.agora.config.StrategyEnabledEvent(strategyId, enable, auditReason));
        } catch (Exception publishErr) {
            log.warn("[MCP] StrategyEnabledEvent publish failed (non-fatal): {}", publishErr.getMessage());
        }

        if (enable) {
            return String.format(
                "✅ 策略 [%s] (ID=%d) 已啟用（品質驗證通過）\n\n" +
                "最新回測指標：交易=%d筆  勝率=%.1f%%  報酬=%.2f%%  回撤=%.1f%%\n" +
                "交易頻率: 每月 %.1f 筆  平均每 %.1f 天觸發一次",
                strategy.getName(), strategyId,
                tradeCount,
                winRate * 100,
                totalRet * 100,
                maxDD * 100,
                tradesPerMonth,
                avgDaysBetween);
        }
        return String.format("✅ 策略 [%s] (ID=%d) 已停用", strategy.getName(), strategyId);
    }

    // ─── Walk-Forward / Robustness 結構與核心 ─────────────────────────────────

    /** Walk-forward 驗證結果(共用於 MCP 工具 + enableStrategy 閘道)。
     *  verdict:STABLE / UNSTABLE / HIGHLY_UNSTABLE / INCONSISTENT / NEGATIVE_MEAN / DATA_INSUFFICIENT / ERROR */
    public record WfResult(
            double avgReturn,       // 各有效段平均報酬 %
            double stdev,           // 報酬標準差 %
            double minReturn,       // 最差段報酬 %
            double maxReturn,       // 最佳段報酬 %
            double ratio,           // stdev / |avgReturn|,當 |avgReturn| < 0.01 時為 Double.POSITIVE_INFINITY
            int positiveFolds,      // 正報酬段數
            int validFolds,         // 有效(非 FAILED)段數
            int totalFolds,         // 預期切段數
            String verdict,
            String formatted        // 完整表格+分析輸出(驗證工具直接回傳)
    ) {
        public String summary() {
            return String.format("WF %s ratio=%s avg=%+.2f%%", verdict,
                    Double.isFinite(ratio) ? String.format("%.2f", ratio) : "N/A", avgReturn);
        }
    }

    /** Robustness 驗證結果(共用於 MCP 工具 + enableStrategy 閘道)。*/
    public record RobustnessResult(
            String paramName,
            String verdict,        // SMOOTH / CLIFF / SENSITIVE / NEGATIVE / UNTESTABLE / ERROR
            double baseReturn,     // 基準點報酬 %
            double maxGap,         // 相鄰最大差 %
            double range,          // 全域範圍 %
            String formatted,      // 完整表格+分析輸出
            String batchId,        // EXT-* batch prefix(資訊用,實際 cleanup 用 createdStrategyIds)
            List<Long> createdStrategyIds  // 該次掃描建立的所有 EXT-* 策略 ID,供精確 cleanup
    ) {
        public String summary() {
            return String.format("%s %s (gap=%.1f%%)", paramName, verdict, maxGap);
        }
    }

    /** Walk-forward 核心邏輯(MCP 工具與 enableStrategy 閘道共用)。
     *  回傳結構化結果:閘道看 verdict 決定 pass/warn/reject,MCP 工具直接輸出 formatted。*/
    private WfResult runWalkForwardCore(Long strategyId, String sym, String itv,
                                         int days, int k, String src) {
        int segDays = days / k;
        log.info("[MCP] runWalkForwardCore strategyId={} {} {} days={} folds={} segDays={} source={}",
                strategyId, sym, itv, days, k, segDays, src);

        List<BacktestResultResponse> segments = new ArrayList<>();
        List<String> segmentLabels = new ArrayList<>();
        LocalDateTime end = LocalDateTime.now();
        for (int i = 0; i < k; i++) {
            LocalDateTime segEnd = end.minusDays((long)(k - 1 - i) * segDays);
            LocalDateTime segStart = segEnd.minusDays(segDays);
            BacktestRunRequest req = new BacktestRunRequest();
            req.setStrategyId(strategyId);
            req.setSymbol(sym);
            req.setIntervalCode(itv);
            req.setStartTime(segStart);
            req.setEndTime(segEnd);
            req.setInitialCapital(new BigDecimal("10000"));
            req.setFeeRate(new BigDecimal("0.001"));
            req.setSource(src);
            // 關鍵:WF fold 是評估用,不汙染 bt_backtest_result「最新」狀態。
            // 漏設會造成每跑一次 validateWalkForward 產生 k 筆 low-sample(36天/fold)
            // backtest row,被 getLatestResultByStrategy / scorecard 誤抓為主回測。
            // 歷史漏修:commit 2797120 只修了 AiStrategyDiscoveryService 的 WF 呼叫,
            // 這條 MCP 直接呼叫的路徑被遺漏,直到 2026-04-18 scorecard 才暴露。
            req.setSkipPersist(true);
            try {
                segments.add(backtestService.runForExploration(req));
                segmentLabels.add(String.format("fold%d [%s ~ %s]", i + 1,
                        segStart.toLocalDate(), segEnd.toLocalDate()));
            } catch (Exception e) {
                log.warn("[runWalkForwardCore] fold {} failed: {}", i + 1, e.getMessage());
                segments.add(null);
                segmentLabels.add(String.format("fold%d [FAILED: %s]", i + 1, e.getMessage()));
            }
        }

        // 統計
        List<Double> rets = segments.stream()
                .filter(r -> r != null && r.getTotalReturn() != null)
                .map(r -> r.getTotalReturn().doubleValue() * 100)
                .toList();
        double mean = rets.isEmpty() ? 0 : rets.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double stdev = rets.size() > 1
                ? Math.sqrt(rets.stream().mapToDouble(r -> Math.pow(r - mean, 2)).sum() / (rets.size() - 1))
                : 0;
        double worst = rets.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double best  = rets.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        int positiveFolds = (int) rets.stream().filter(r -> r > 0).count();
        double ratio = Math.abs(mean) > 0.01 ? stdev / Math.abs(mean) : Double.POSITIVE_INFINITY;

        // 解讀 — verdict 碼用 code-only(emoji 留給 formatted 輸出),供閘道比對
        String verdict;
        String verdictEmoji;
        if (rets.size() < k * 0.6) {
            verdict = "DATA_INSUFFICIENT";
            verdictEmoji = String.format("❌ DATA_INSUFFICIENT(%d/%d 段成功,樣本不夠)", rets.size(), k);
        } else if (mean <= 0) {
            verdict = "NEGATIVE_MEAN";
            verdictEmoji = "❌ NEGATIVE_MEAN(平均報酬 ≤ 0,策略整體無 alpha)";
        } else if (ratio > BtStrategyService.QUALITY_WF_HIGHLY_UNSTABLE_RATIO) {
            verdict = "HIGHLY_UNSTABLE";
            verdictEmoji = String.format("❌ HIGHLY_UNSTABLE(stdev/|mean|=%.2f > %.1f,各段極度不穩)",
                    ratio, BtStrategyService.QUALITY_WF_HIGHLY_UNSTABLE_RATIO);
        } else if (ratio > BtStrategyService.QUALITY_WF_UNSTABLE_RATIO) {
            verdict = "UNSTABLE";
            verdictEmoji = String.format("⚠️  UNSTABLE(stdev/|mean|=%.2f > %.1f,各段差距大,疑似過擬合)",
                    ratio, BtStrategyService.QUALITY_WF_UNSTABLE_RATIO);
        } else if (positiveFolds < k * 0.5) {
            verdict = "INCONSISTENT";
            verdictEmoji = "❌ INCONSISTENT(正報酬段 < 50%,策略不穩)";
        } else {
            verdict = "STABLE";
            verdictEmoji = "✅ STABLE(mean > 0,stdev 可控,多數段正報酬)";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Walk-Forward 驗證 strategyId=").append(strategyId).append(" ===\n");
        sb.append(String.format("Symbol=%s  Interval=%s  TotalDays=%d  Folds=%d  SegDays=%d  Source=%s%n%n",
                sym, itv, days, k, segDays, src));
        for (int i = 0; i < k; i++) {
            BacktestResultResponse r = segments.get(i);
            if (r == null) {
                sb.append(String.format("  %s  ❌%n", segmentLabels.get(i)));
            } else {
                double ret = r.getTotalReturn() != null ? r.getTotalReturn().doubleValue() * 100 : 0;
                double wr  = r.getWinRate()     != null ? r.getWinRate().doubleValue() * 100     : 0;
                double dd  = r.getMaxDrawdown() != null ? r.getMaxDrawdown().doubleValue() * 100 : 0;
                int tc     = r.getTradeCount()  != null ? r.getTradeCount() : 0;
                sb.append(String.format("  %s%n    return=%+.2f%% winRate=%.1f%% DD=%.2f%% trades=%d%n",
                        segmentLabels.get(i), ret, wr, dd, tc));
            }
        }
        sb.append(String.format("%n📊 統計 (有效段數 %d/%d):%n", rets.size(), k));
        sb.append(String.format("  平均: %+.2f%%  標準差: %.2f%%  最差: %+.2f%%  最佳: %+.2f%%%n",
                mean, stdev, worst, best));
        sb.append(String.format("  正報酬段: %d/%d%n", positiveFolds, rets.size()));
        sb.append(String.format("  stdev/|mean| 比: %s%n",
                Double.isFinite(ratio) ? String.format("%.2f", ratio) : "N/A"));
        sb.append("\n判定: ").append(verdictEmoji);

        return new WfResult(mean, stdev, worst, best, ratio, positiveFolds,
                rets.size(), k, verdict, sb.toString());
    }

    /** Robustness 核心邏輯(MCP 工具與 enableStrategy 閘道共用)。
     *  @param cliffThreshold 相鄰最大差 % 超過此值即判定 CLIFF。MCP 工具用 15,enableStrategy 閘道用 10 (低樣本更嚴格)。*/
    private RobustnessResult runRobustnessCore(Long strategyId, String paramName,
                                                int delta, int n, int daysVal, String src,
                                                double cliffThreshold) {
        StrategyResponse orig;
        try {
            orig = strategyService.queryStrategies(new StrategyQueryRequest()).stream()
                    .filter(s -> s.getId().equals(strategyId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Strategy not found: " + strategyId));
        } catch (Exception e) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0,
                    "❌ 讀取策略失敗: " + e.getMessage(), null, Collections.emptyList());
        }
        if (!"SOP_MTF_ADX".equalsIgnoreCase(orig.getStrategyType())) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0,
                    "❌ 只支援 SOP_MTF_ADX(此策略=" + orig.getStrategyType() + ")", null, Collections.emptyList());
        }
        if (orig.getConfig() == null) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0, "❌ 策略 config 為 null", null, Collections.emptyList());
        }
        Map<String, Object> origConfig;
        try {
            origConfig = new com.fasterxml.jackson.databind.ObjectMapper()
                    .convertValue(orig.getConfig(), Map.class);
        } catch (Exception e) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0,
                    "❌ config 轉 Map 失敗: " + e.getMessage(), null, Collections.emptyList());
        }
        Object baseVal = origConfig.get(paramName);
        if (!(baseVal instanceof Number)) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0,
                    "❌ 參數 '" + paramName + "' 不存在或非數值", null, Collections.emptyList());
        }
        double base = ((Number) baseVal).doubleValue();

        List<Map<String, Object>> variants = new ArrayList<>();
        List<Double> deltas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double pct = -delta + (2.0 * delta) * i / (n - 1);
            deltas.add(pct);
            double newVal = base * (1 + pct / 100.0);
            Map<String, Object> v = new LinkedHashMap<>(origConfig);
            v.put(paramName, newVal);
            v.put("rationale", String.format("robustness %s %+.1f%% -> %.4f", paramName, pct, newVal));
            variants.add(v);
        }

        String candidatesJson;
        try {
            candidatesJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(variants);
        } catch (Exception e) {
            return new RobustnessResult(paramName, "ERROR", 0, 0, 0,
                    "❌ JSON 產生失敗: " + e.getMessage(), null, Collections.emptyList());
        }

        log.info("[MCP] validateRobustness strategyId={} param={} base={} delta=±{}% steps={}",
                strategyId, paramName, base, delta, n);

        String symbol = orig.getSymbols() != null ? orig.getSymbols().split(",")[0].trim() : "BTCUSDT";
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(daysVal).truncatedTo(ChronoUnit.DAYS);

        AiStrategyDiscoveryRequest req = new AiStrategyDiscoveryRequest();
        req.setSymbol(symbol);
        req.setIntervalCode("1h");
        req.setStartTime(startTime);
        req.setEndTime(endTime);
        req.setCandidateCount(n);
        req.setSource(src);

        // skipAnchors=true: robustness 場景不需歷史錨點,避免錨點 fingerprint 碰到 subject
        // 策略(或當前啟用策略)導致 dedup 返回既有策略 ID,後續 cleanup 誤刪。
        AiStrategyDiscoveryResponse r = aiDiscoveryService.runWithExternalCandidatesJson(
                req, candidatesJson, true);

        // 收集本次呼叫「真正新建」的策略 ID,供事後精確 cleanup。
        // 關鍵:CandidateResult.strategyName 是「意圖」建立的名稱,不代表 DB 實際儲存。
        // fingerprint dedup 時 createAiGeneratedStrategy 返回既有策略,但 caller 填的
        // CandidateResult.strategyName 仍是新名稱 → 僅比對 strategyName 會誤判。
        // 正解:查實際 DB 名稱,與 intended 對比,一致才是真正新建。
        String batchPrefix = r.getDiscoveryBatch();
        List<Long> createdIds = new ArrayList<>();
        for (AiStrategyDiscoveryResponse.CandidateResult cr : r.getCandidates()) {
            if (cr.getStrategyId() == null || cr.getStrategyName() == null) continue;
            if (batchPrefix == null || !cr.getStrategyName().startsWith(batchPrefix)) continue;
            try {
                StrategyResponse actual = strategyService.getStrategy(cr.getStrategyId());
                if (actual != null && cr.getStrategyName().equals(actual.getName())) {
                    createdIds.add(cr.getStrategyId());
                } else if (actual != null) {
                    log.debug("[Robustness] strategyId={} 為 dedup 返回既有 (actual={} intended={}),cleanup 跳過",
                            cr.getStrategyId(), actual.getName(), cr.getStrategyName());
                }
            } catch (Exception e) {
                log.warn("[Robustness] 檢查策略 {} 實際名稱失敗,保守跳過 cleanup: {}",
                        cr.getStrategyId(), e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== Robustness 驗證 strategyId=").append(strategyId).append(" ===\n");
        sb.append(String.format("參數=%s  基準值=%.4f  掃描=±%d%%  步數=%d  天數=%d  source=%s%n%n",
                paramName, base, delta, n, daysVal, src));
        sb.append("   Δ%%   |   實際值   | 交易 | 勝率 | 報酬 |  DD  | 評分\n");
        sb.append("---------|-----------|------|------|------|------|------\n");

        List<Double> returns = new ArrayList<>();
        List<Integer> tradeCounts = new ArrayList<>();
        int idx = 0;
        for (AiStrategyDiscoveryResponse.CandidateResult c : r.getCandidates()) {
            if (idx >= n) break;
            double pct = deltas.get(idx);
            double newVal = base * (1 + pct / 100.0);
            if (c.getErrorMessage() != null) {
                sb.append(String.format(" %+5.1f%% | %.4f | ERROR: %s%n", pct, newVal,
                        c.getErrorMessage().length() > 40 ? c.getErrorMessage().substring(0, 40) : c.getErrorMessage()));
                idx++;
                continue;
            }
            int tc = c.getTradeCount() != null ? c.getTradeCount() : 0;
            double wr = c.getWinRate() != null ? c.getWinRate().doubleValue() * 100 : 0;
            double ret = c.getTotalReturn() != null ? c.getTotalReturn().doubleValue() * 100 : 0;
            double dd = c.getMaxDrawdown() != null ? c.getMaxDrawdown().doubleValue() * 100 : 0;
            sb.append(String.format(" %+5.1f%% | %9.4f | %4d | %4.1f%% | %+5.2f%% | %5.2f%% | %.4f%n",
                    pct, newVal, tc, wr, ret, dd, c.getScore()));
            returns.add(ret);
            tradeCounts.add(tc);
            idx++;
        }

        String verdict;
        double maxGap = 0, retRange = 0, origRet = 0;
        if (returns.size() >= 3) {
            for (int i = 1; i < returns.size(); i++) {
                maxGap = Math.max(maxGap, Math.abs(returns.get(i) - returns.get(i - 1)));
            }
            retRange = Collections.max(returns) - Collections.min(returns);
            origRet = returns.get(returns.size() / 2);
            sb.append(String.format("%n📊 分析:%n"));
            sb.append(String.format("  基準點報酬: %+.2f%%  全域範圍: %.2f%%  相鄰最大差: %.2f%%%n",
                    origRet, retRange, maxGap));
            double sensitiveThreshold = cliffThreshold * (20.0 / 15.0);  // 維持比例: 15→20, 10→13.3
            // Bucket pattern 偵測:交易數 max/min 比率 ≥ 5x 即視為分桶過擬合(如 284 的 5 vs 35 trades),
            // 這類型過擬合即使 gap 未超閾值也應該拒絕。min 用 max(1, actual) 避免除 0。
            int tcMax = tradeCounts.isEmpty() ? 0 : Collections.max(tradeCounts);
            int tcMin = tradeCounts.isEmpty() ? 0 : Math.max(1, Collections.min(tradeCounts));
            double tcRatio = (double) tcMax / tcMin;
            sb.append(String.format("  交易數範圍: [%d, %d] ratio=%.1fx%n", tcMin, tcMax, tcRatio));

            if (tcRatio >= 5.0) {
                verdict = "BUCKETED";
                sb.append(String.format("  判定: ❌ BUCKETED(交易數 %dx 差距,分桶過擬合)", (int) tcRatio));
            } else if (maxGap > cliffThreshold) {
                verdict = "CLIFF";
                sb.append(String.format("  判定: ❌ CLIFF(相鄰點報酬差 > %.0f%%,參數懸崖 = 過擬合)",
                        cliffThreshold));
            } else if (retRange > sensitiveThreshold) {
                verdict = "SENSITIVE";
                sb.append(String.format("  判定: ⚠️  SENSITIVE(全域範圍 > %.0f%%,對參數過敏)",
                        sensitiveThreshold));
            } else if (origRet <= 0 && Collections.max(returns) <= 0) {
                verdict = "NEGATIVE";
                sb.append("  判定: ❌ NEGATIVE(所有掃描點都不賺錢)");
            } else {
                verdict = "SMOOTH";
                sb.append("  判定: ✅ SMOOTH(報酬變化平緩,參數穩健)");
            }
        } else {
            verdict = "UNTESTABLE";
            sb.append("\n⚠️ 有效結果 < 3,無法判定 robustness");
        }

        return new RobustnessResult(paramName, verdict, origRet, maxGap, retRange,
                sb.toString(), r.getDiscoveryBatch(), createdIds);
    }

    /** 從逗號分隔的 symbols 字串取出第一個幣種，供 runBacktest 提示使用 */
    private String firstSymbol(String symbols) {
        if (symbols == null) return "BTCUSDT";
        int comma = symbols.indexOf(',');
        return comma > 0 ? symbols.substring(0, comma).trim() : symbols.trim();
    }

    private java.util.Set<Long> queryShadowStrategyIds() {
        return jdbc.queryForList(
                "SELECT id FROM bt_strategy WHERE enabled=1 " +
                "AND (JSON_EXTRACT(config_json,'$.notifyOnly')=TRUE " +
                "  OR JSON_EXTRACT(config_json,'$.notifyOnly')='true' " +
                "  OR JSON_EXTRACT(config_json,'$.notifyOnly')=1 " +
                "  OR JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.notifyOnly'))='1')",
                Long.class).stream().collect(java.util.stream.Collectors.toSet());
    }

    private MlShadowSummary queryMlShadowSummary(Long strategyId, LocalDateTime since, double threshold) {
        try {
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT COUNT(*) as cnt, AVG(m.score) as avg_pwin, MAX(m.score) as max_pwin, " +
                    "SUM(CASE WHEN m.score >= ? THEN 1 ELSE 0 END) as pwin_pass, " +
                    "SUM(CASE WHEN m.decision='PASS' THEN 1 ELSE 0 END) as decision_pass " +
                    "FROM ml_inference_log m " +
                    "JOIN bt_live_signal ls ON m.live_signal_id = ls.id " +
                    "WHERE ls.strategy_id=? AND m.predicted_at > ?",
                    threshold, strategyId, since);
            if (rows.isEmpty()) return new MlShadowSummary(0, 0, 0, 0, 0);
            Map<String, Object> r = rows.get(0);
            long total = r.get("cnt") != null ? ((Number) r.get("cnt")).longValue() : 0L;
            double avg = r.get("avg_pwin") != null ? ((Number) r.get("avg_pwin")).doubleValue() : 0.0;
            double max = r.get("max_pwin") != null ? ((Number) r.get("max_pwin")).doubleValue() : 0.0;
            long pwinPass = r.get("pwin_pass") != null ? ((Number) r.get("pwin_pass")).longValue() : 0L;
            long decisionPass = r.get("decision_pass") != null ? ((Number) r.get("decision_pass")).longValue() : 0L;
            return new MlShadowSummary(total, avg, max, pwinPass, decisionPass);
        } catch (Exception e) {
            return new MlShadowSummary(0, 0, 0, 0, 0);
        }
    }

    private int estimateSarsScore(StrategyResponse strategy, BtBacktestResult latest, double baseUsdt) {
        String type = strategy.getStrategyType() != null ? strategy.getStrategyType() : "";
        double slPct = 0.05;
        try {
            String slStr = jdbc.queryForObject(
                    "SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.fixedStopLossPct')), '0.05') FROM bt_strategy WHERE id=?",
                    String.class, strategy.getId());
            if (slStr != null && !slStr.equals("null")) slPct = Double.parseDouble(slStr);
        } catch (Exception ignored) {}
        int s1 = baseUsdt * slPct < 15 ? 0 : baseUsdt * slPct <= 100 ? 1 : 2;
        int tradeCount = latest != null && latest.getTradeCount() != null ? latest.getTradeCount() : 0;
        long btDays = latest != null ? Math.max(1, ChronoUnit.DAYS.between(latest.getStartTime(), latest.getEndTime())) : 0;
        double annualRate = btDays > 0 ? (double) tradeCount / btDays * 365 : 0;
        int s2 = annualRate < 20 ? 0 : annualRate <= 60 ? 1 : 2;
        int s3 = "SCORE_BUY_V3".equalsIgnoreCase(type) ? 0
                : "SCORE_BUY_V2".equalsIgnoreCase(type) ? 1
                : "SOP_MTF_ADX".equalsIgnoreCase(type) ? 1 : 2;
        int s4 = ("SCORE_BUY_V3".equalsIgnoreCase(type) || "SCORE_BUY_V2".equalsIgnoreCase(type)) ? 0
                : ("SOP_MTF_ADX".equalsIgnoreCase(type) || "MEAN_REVERSION".equalsIgnoreCase(type)) ? 1 : 2;
        return s1 + s2 + s3 + s4;
    }

    private record MlShadowSummary(long total, double avgPwin, double maxPwin, long pwinPass, long decisionPass) {}

    private boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) return n.intValue() != 0;
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private boolean jsonBool(com.fasterxml.jackson.databind.JsonNode cfg, String field, boolean defaultValue) {
        com.fasterxml.jackson.databind.JsonNode n = cfg.path(field);
        if (n.isMissingNode() || n.isNull()) return defaultValue;
        if (n.isBoolean()) return n.asBoolean();
        if (n.isNumber()) return n.asInt() != 0;
        String text = n.asText();
        return "1".equals(text) || Boolean.parseBoolean(text);
    }

    private double jsonDouble(com.fasterxml.jackson.databind.JsonNode cfg, String field, double defaultValue) {
        com.fasterxml.jackson.databind.JsonNode n = cfg.path(field);
        if (n.isMissingNode() || n.isNull() || n.asText().isBlank()) return defaultValue;
        return n.asDouble(defaultValue);
    }

    private String trimNum(double value) {
        if (Math.rint(value) == value) return String.format("%.0f", value);
        return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ─── 跨 regime 回測驗證 (#211) ───────────────────────────────────────────

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.ANALYTICS, Category.DIAGNOSTIC})
    @Tool(description = "對策略跑三段 market regime 回測：BULL(2024-10~2025-03)、SIDEWAYS(2025-04~2025-09)、BEAR(2025-10~2026-04)。" +
            "判斷策略是否只在特定 regime 有效，避免過擬合單一市場條件。" +
            "param: strategyId=策略ID, symbol=交易對(預設BTCUSDT), intervalCode=K線週期(預設1h)")
    public String analyzeStrategyRegimes(Long strategyId, String symbol, String intervalCode) {
        if (strategyId == null) return "❌ strategyId 必填";
        String sym = (symbol == null || symbol.isBlank()) ? "BTCUSDT" : symbol.toUpperCase();
        String itv = (intervalCode == null || intervalCode.isBlank()) ? "1h" : intervalCode.toLowerCase();

        StrategyResponse strategy;
        try {
            strategy = strategyService.getStrategy(strategyId);
        } catch (Exception e) {
            return "❌ 策略 " + strategyId + " 不存在";
        }

        // Three regime windows based on BTC market history
        record RegimeWindow(String name, String emoji, LocalDateTime start, LocalDateTime end) {}
        List<RegimeWindow> regimes = List.of(
            new RegimeWindow("BULL",     "🟢", LocalDateTime.of(2024, 10,  1, 0, 0), LocalDateTime.of(2025, 3, 31, 23, 59)),
            new RegimeWindow("SIDEWAYS", "🟡", LocalDateTime.of(2025,  4,  1, 0, 0), LocalDateTime.of(2025, 9, 30, 23, 59)),
            new RegimeWindow("BEAR",     "🔴", LocalDateTime.of(2025, 10,  1, 0, 0), LocalDateTime.of(2026, 4, 28, 23, 59))
        );

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("=== Regime Analysis: 策略 %d [%s] ===\n\n", strategyId, strategy.getName()));
        sb.append(String.format("%-10s %-12s %-8s %-8s %-8s %-6s\n",
                "Regime", "Period", "Return", "DD", "WinRate", "Trades"));
        sb.append("-".repeat(58)).append("\n");

        for (RegimeWindow r : regimes) {
            BacktestRunRequest req = new BacktestRunRequest();
            req.setStrategyId(strategyId);
            req.setSymbol(sym);
            req.setIntervalCode(itv);
            req.setStartTime(r.start());
            req.setEndTime(r.end());
            req.setInitialCapital(new BigDecimal("10000"));
            req.setFeeRate(new BigDecimal("0.001"));
            req.setSource("binance");
            req.setSkipPersist(true);
            try {
                BacktestResultResponse result = backtestService.runForExploration(req);
                double ret = result.getTotalReturn() != null ? result.getTotalReturn().doubleValue() * 100 : 0;
                double dd  = result.getMaxDrawdown()  != null ? result.getMaxDrawdown().doubleValue()  * 100 : 0;
                double wr  = result.getWinRate()       != null ? result.getWinRate().doubleValue()       * 100 : 0;
                int trades = result.getTradeCount() != null ? result.getTradeCount() : 0;
                String retStr = String.format("%+.1f%%", ret);
                sb.append(String.format("%s %-8s %-12s %-8s %-8s %-8s %-6d\n",
                        r.emoji(), r.name(),
                        r.start().toLocalDate() + "~" + r.end().toLocalDate().toString().substring(5),
                        retStr, String.format("%.1f%%", dd), String.format("%.0f%%", wr), trades));
            } catch (Exception e) {
                sb.append(String.format("%s %-8s %-12s ERROR: %s\n",
                        r.emoji(), r.name(),
                        r.start().toLocalDate() + "~",
                        e.getMessage() != null ? e.getMessage().substring(0, Math.min(40, e.getMessage().length())) : "unknown"));
            }
        }

        sb.append("\n💡 解讀：\n");
        sb.append("  BULL +   BEAR 0: 趨勢多頭策略（做多，需配搭做空策略）\n");
        sb.append("  BULL 0   BEAR +: 趨勢空頭策略（做空，熊市獲利）\n");
        sb.append("  ALL   0 trades: 策略在該 regime 完全不觸發（正常）\n");
        sb.append("  ALL   +: 全 regime 穩健策略（最理想）\n");

        return sb.toString();
    }
}
