package com.agora.mcp;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 將所有 MCP @Tool 類別註冊為 Spring AI MCP Server 工具。
 *
 * <p>2026-04-16 重構:原 TradingMcpTools.java(2365 行)拆為 4 個專責類別:
 * {@link StrategyManagementMcpTools} / {@link BacktestValidationMcpTools} /
 * {@link GridMcpTools} / {@link MarketDataMcpTools}。
 */
@Configuration
@Slf4j
public class McpToolsConfig {

    @Autowired private ApplicationContext ctx;

    /**
     * #324 啟動時 self-check：scan 所有 *McpTools bean，檢查每個是否在 McpToolsConfig 註冊。
     * 漏註冊（如 #248 IndicatorMcpTools 拆分後沒加 @Bean）會 log.error 警告，
     * 但不阻擋啟動 — 純 detection。
     *
     * 用 ContextRefreshedEvent 而非 @PostConstruct 避免循環依賴（McpToolsConfig 自己生成
     * 所有 ToolCallbackProvider，不能再 @Autowired 它們）。
     */
    @org.springframework.context.event.EventListener
    void verifyAllMcpToolsRegistered(org.springframework.context.event.ContextRefreshedEvent event) {
        // 找所有 ApplicationContext 內 class name 結尾為 McpTools 的 bean
        Set<String> mcpToolBeans = ctx.getBeansOfType(Object.class).values().stream()
                .map(b -> AopProxyUtils.ultimateTargetClass(b).getSimpleName())
                .filter(n -> n.endsWith("McpTools"))
                .collect(Collectors.toSet());
        // 找所有 @Bean method 的參數類別（ToolCallbackProvider 的 *McpTools 參數）
        Set<String> wiredClasses = new HashSet<>();
        for (Method m : McpToolsConfig.class.getDeclaredMethods()) {
            if (m.isAnnotationPresent(Bean.class) && ToolCallbackProvider.class.isAssignableFrom(m.getReturnType())) {
                for (Class<?> p : m.getParameterTypes()) {
                    if (p.getSimpleName().endsWith("McpTools")) wiredClasses.add(p.getSimpleName());
                }
            }
        }
        Set<String> missing = new HashSet<>(mcpToolBeans);
        missing.removeAll(wiredClasses);
        if (!missing.isEmpty()) {
            log.error("[McpTools] ⚠️ {} *McpTools bean(s) NOT wired in McpToolsConfig: {}", missing.size(), missing);
            log.error("[McpTools] Add @Bean ToolCallbackProvider for each missing class to expose @Tool methods.");
        } else {
            log.info("[McpTools] ✅ all {} *McpTools beans wired in McpToolsConfig", mcpToolBeans.size());
        }
    }


    @Bean
    public ToolCallbackProvider strategyManagementMcpToolCallbacks(StrategyManagementMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider backtestValidationMcpToolCallbacks(BacktestValidationMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider gridMcpToolCallbacks(GridMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider positionMcpToolCallbacks(PositionMcpTools positionMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(positionMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider executionEventMcpToolCallbacks(ExecutionEventMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider reportMcpToolCallbacks(ReportMcpTools reportMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(reportMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider tradingManagerMcpToolCallbacks(TradingManagerMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider runtimeEvidenceMcpToolCallbacks(RuntimeEvidenceMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider strategy508TimeExitMcpToolCallbacks(Strategy508TimeExitMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider signalCorrectnessMcpToolCallbacks(SignalCorrectnessMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider scoreBuyMcpToolCallbacks(ScoreBuyMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider stagedAddMcpToolCallbacks(StagedAddMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider guardianMcpToolCallbacks(GuardianMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider diagnosticMcpToolCallbacks(DiagnosticMcpTools diagnosticMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(diagnosticMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider marketDataMcpToolCallbacks(MarketDataMcpTools marketDataMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(marketDataMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider indicatorMcpToolCallbacks(IndicatorMcpTools indicatorMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(indicatorMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider metaControlMcpToolCallbacks(MetaControlMcpTools metaControlMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(metaControlMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider fundingArbMcpToolCallbacks(FundingArbMcpTools fundingArbMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(fundingArbMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider aiRouterMcpToolCallbacks(AiRouterMcpTools aiRouterMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(aiRouterMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider aiTaskOrchestrationMcpToolCallbacks(AiTaskOrchestrationMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider tradingMlMcpToolCallbacks(TradingMlMcpTools tradingMlMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tradingMlMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider ensembleMcpToolCallbacks(EnsembleMcpTools ensembleMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(ensembleMcpTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider earnMcpToolCallbacks(EarnMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
