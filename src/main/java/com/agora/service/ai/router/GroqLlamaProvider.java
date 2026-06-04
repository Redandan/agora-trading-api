package com.agora.service.ai.router;

import com.agora.service.ai.GroqApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groq Llama 3.3 70B provider for AiTaskRouter — 免費 tier 14,400 req/day,亞秒級回應。
 *
 * <p>複用既有 {@link GroqApiClient},router 端用 {@code chatWithUsage} 取得 token 用量。
 *
 * <p><b>cost</b>:Groq 免費 tier 實質 0 USD,但仍紀錄 token 用量供 quota 監控。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "groq.api.key", matchIfMissing = false)
public class GroqLlamaProvider implements AiProvider {

    private final GroqApiClient groqClient;

    public GroqLlamaProvider(GroqApiClient groqClient) {
        this.groqClient = groqClient;
    }

    @Override
    public String name() { return "groq-llama-3.3-70b"; }

    @Override
    public String model() { return groqClient.getModel(); }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TOOL_USE,
                AiCapability.JSON_MODE,
                AiCapability.LARGE_CONTEXT
        );
    }

    @Override
    public boolean healthy() {
        return groqClient != null && groqClient.isEnabled();
    }

    @Override
    public AiResponse execute(AiTask task) {
        Instant t0 = Instant.now();
        try {
            List<Map<String, String>> messages = new ArrayList<>();
            if (task.systemPrompt() != null && !task.systemPrompt().isBlank()) {
                Map<String, String> sys = new LinkedHashMap<>();
                sys.put("role", "system");
                sys.put("content", task.systemPrompt());
                messages.add(sys);
            }
            Map<String, String> user = new LinkedHashMap<>();
            user.put("role", "user");
            user.put("content", task.userPrompt());
            messages.add(user);

            GroqApiClient.ChatResult result = groqClient.chatWithUsage(messages, task.maxTokens(), 0.3);
            if (result == null) {
                throw new AiProviderException("Groq returned null (api key missing or error)", true, null);
            }
            Duration latency = Duration.between(t0, Instant.now());
            log.info("[GroqProvider] task={} in={} out={} latency={}ms",
                    task.type(), result.promptTokens(), result.completionTokens(), latency.toMillis());

            return new AiResponse(
                    result.text(), name(), model(),
                    result.promptTokens(), result.completionTokens(),
                    BigDecimal.ZERO, // Groq 免費 tier
                    latency);
        } catch (AiProviderException e) {
            throw e;
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : "unknown";
            throw new AiProviderException("Groq call failed: " + msg,
                    AiRetryClassifier.isRetryable(t), t);
        }
    }

    @Override
    public BigDecimal estimateCostUsd(AiTask task) {
        return BigDecimal.ZERO;  // Free tier
    }
}
