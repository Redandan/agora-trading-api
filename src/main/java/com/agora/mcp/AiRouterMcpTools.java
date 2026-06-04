package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.ai.router.AiCapability;
import com.agora.service.ai.router.AiProvider;
import com.agora.service.ai.router.AiResponse;
import com.agora.service.ai.router.AiTask;
import com.agora.service.ai.router.AiTaskRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

import com.agora.mcp.util.McpParamValidator;
import java.util.Map;

/**
 * Multi-AI Router MCP 工具集 — Phase 1。
 *
 * <p>Phase 2 會加:setTaskRoute / setAiBudget / getAiCostReport(需要 ai_provider_usage_daily 表)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRouterMcpTools {

    private final AiTaskRouter router;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META})
    @Tool(description = "列出所有 AI Provider 與當前 routing 配置。" +
            "顯示:provider 名稱、模型、是否健康、能力標籤、各 task type 的 primary/fallback 對映。")
    public String listAiProviders() {
        StringBuilder sb = new StringBuilder();
        Map<String, AiProvider> providers = router.getProviders();

        sb.append("=== AI Providers (").append(providers.size()).append(") ===\n");
        if (providers.isEmpty()) {
            sb.append("⚠️ 無 provider 註冊。檢查 ANTHROPIC_API_KEY 是否設定。\n");
        } else {
            for (AiProvider p : providers.values()) {
                String health = p.healthy() ? "✅" : "❌";
                String caps = p.capabilities().stream()
                        .map(AiCapability::name)
                        .reduce((a, b) -> a + "," + b).orElse("-");
                sb.append(String.format("  %s [%s] model=%s caps=%s%n",
                        health, p.name(), p.model(), caps));
            }
        }

        sb.append("\n=== Task Routing Config ===\n");
        Map<String, AiTaskRouter.TaskRoute> routes = router.getRoutingConfig().getRouting();
        if (routes.isEmpty()) {
            sb.append("⚠️ 無 routing 配置。檢查 application.yml 的 ai.routing.*\n");
        } else {
            for (Map.Entry<String, AiTaskRouter.TaskRoute> e : routes.entrySet()) {
                sb.append(String.format("  %s → primary=%s, fallback=%s%n",
                        e.getKey(),
                        e.getValue().getPrimary(),
                        e.getValue().getFallback() != null ? e.getValue().getFallback() : "[]"));
            }
        }
        return sb.toString();
    }

    @McpAuth(McpAuthLevel.DEV)
    @McpCategory({Category.META, Category.DIAGNOSTIC})
    @Tool(description = "測試 AI Router 端到端:用 GenericPrompt 跑一次完整流程(routing → provider → AI → 回應)。" +
            "回傳實際 provider 名稱、用量、成本、延遲。" +
            "params: taskType(對應 routing key, 如 'annotate-trade'), prompt(測試訊息), maxTokens(可選,預設 200)")
    public String runAiTaskTest(String taskType, String prompt, Integer maxTokens) {
        { String _e = McpParamValidator.requireNonBlank(taskType, "taskType"); if (_e != null) return _e; }
        { String _e = McpParamValidator.requireNonBlank(prompt, "prompt"); if (_e != null) return _e; }

        int tokens = maxTokens != null && maxTokens > 0 ? Math.min(maxTokens, 2000) : 200;
        AiTask task = new AiTask.GenericPrompt(taskType, prompt, tokens);

        try {
            AiResponse resp = router.execute(task);
            return String.format(
                    "✅ AI Router 測試成功\n" +
                    "  provider: %s (%s)\n" +
                    "  tokens: in=%d out=%d\n" +
                    "  cost: $%s\n" +
                    "  latency: %d ms\n\n" +
                    "── Response ──\n%s",
                    resp.provider(), resp.model(),
                    resp.inputTokens(), resp.outputTokens(),
                    resp.costUsd(),
                    resp.latency().toMillis(),
                    resp.text());
        } catch (AiTaskRouter.AllProvidersFailedException e) {
            return "❌ 所有 provider 失敗:" + e.getMessage();
        } catch (Throwable t) {
            return "❌ 未預期錯誤:" + t.getMessage();
        }
    }
}
