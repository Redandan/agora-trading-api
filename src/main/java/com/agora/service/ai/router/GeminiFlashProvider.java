package com.agora.service.ai.router;

import com.agora.service.ai.GeminiApiClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gemini Flash provider for AiTaskRouter — 複用既有 {@link GeminiApiClient}
 * (OpenAI 相容端點 https://generativelanguage.googleapis.com)。
 *
 * <p>Phase 1 用 Gemini 2.5 Flash:免費 quota 慷慨、延遲低、品質夠用於 trading review 場景。
 *
 * <p><b>定價</b>(2026-04 catalog,參考):input ~$0.075/M, output ~$0.30/M
 * — 比 Claude Sonnet 便宜 40-50x。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "gemini.api.key", matchIfMissing = false)
public class GeminiFlashProvider implements AiProvider {

    private static final BigDecimal INPUT_PRICE_PER_M  = new BigDecimal("0.075");
    private static final BigDecimal OUTPUT_PRICE_PER_M = new BigDecimal("0.30");
    private static final BigDecimal ONE_MILLION        = new BigDecimal("1000000");

    private final GeminiApiClient geminiClient;
    private final String model;

    /**
     * Router 用獨立 model config(預設 gemini-2.0-flash 無 thinking 機制,maxTokens 直接=visible output)。
     * 不共享全域 {@code gemini.model}(那個給 GeminiMarketAdvisor 用 2.5-flash thinking 做深度 hint)。
     */
    public GeminiFlashProvider(GeminiApiClient geminiClient,
                                @Value("${ai.providers.gemini-flash.model:models/gemini-2.5-flash-lite}") String model) {
        this.geminiClient = geminiClient;
        this.model = model;
    }

    @Override
    public String name() { return "gemini-flash"; }

    @Override
    public String model() { return model; }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TOOL_USE,        // GeminiApiClient.chatWithTools 已支援
                AiCapability.LARGE_CONTEXT,   // 1M token context
                AiCapability.STREAMING,
                AiCapability.JSON_MODE
        );
    }

    @Override
    public boolean healthy() {
        return geminiClient != null && geminiClient.isEnabled();
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

            // 把自己的 model 傳給 client(override 全域 gemini.model)
            GeminiApiClient.ChatResult result = geminiClient.chatWithUsage(
                    messages, model, task.maxTokens(), 0.3);

            BigDecimal cost = computeCost(result.promptTokens(), result.completionTokens());
            Duration latency = Duration.between(t0, Instant.now());

            log.info("[GeminiProvider] task={} model={} in={} out={} cost=${} latency={}ms",
                    task.type(), model, result.promptTokens(), result.completionTokens(),
                    cost, latency.toMillis());

            return new AiResponse(
                    result.text(), name(), model,
                    result.promptTokens(), result.completionTokens(),
                    cost, latency);

        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : "unknown";
            throw new AiProviderException("Gemini call failed: " + msg,
                    AiRetryClassifier.isRetryable(t), t);
        }
    }

    @Override
    public BigDecimal estimateCostUsd(AiTask task) {
        int estIn = task.userPrompt() != null ? task.userPrompt().length() : 100;
        if (task.systemPrompt() != null) estIn += task.systemPrompt().length();
        int estOut = task.maxTokens() / 2;
        return computeCost(estIn, estOut);
    }

    private BigDecimal computeCost(int inputTokens, int outputTokens) {
        BigDecimal in  = new BigDecimal(inputTokens).multiply(INPUT_PRICE_PER_M)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        BigDecimal out = new BigDecimal(outputTokens).multiply(OUTPUT_PRICE_PER_M)
                .divide(ONE_MILLION, 8, RoundingMode.HALF_UP);
        return in.add(out).setScale(6, RoundingMode.HALF_UP);
    }
}
