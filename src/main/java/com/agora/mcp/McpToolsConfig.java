package com.agora.mcp;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Minimal MCP surface for the strategy-driven runtime.
 *
 * <p>Only catalog inspection, owner strategy evidence, execution-safety reads,
 * and read-only OKX Native Grid monitoring are exposed. Legacy AI,
 * ML, ensemble, autonomous execution, Earn, funding-arbitrage, and broad
 * strategy-management tools remain outside the runtime API.</p>
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider registryVersionMcpToolCallbacks(McpRegistryVersionService tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider strategyCatalogMcpToolCallbacks(StrategyCatalogMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider btcDonchianShadowMcpToolCallbacks(BtcDonchianShadowMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider executionSafetyMcpToolCallbacks(ExecutionSafetyMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    @Bean
    public ToolCallbackProvider okxNativeGridMcpToolCallbacks(OkxNativeGridMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
