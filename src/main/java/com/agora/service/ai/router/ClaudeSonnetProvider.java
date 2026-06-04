package com.agora.service.ai.router;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Claude Sonnet 4.5(Anthropic API)provider for AiTaskRouter。
 *
 * <p>Spring AI Anthropic starter 自動 configure {@link AnthropicChatModel};
 * 此 bean 只在 model 存在(有 API key)時註冊。
 *
 * <p><b>定價</b>(2026-04 catalog):input $3/M、output $15/M。實際 token 用量從 ChatResponse.metadata 拿。
 */
@Slf4j
@Component
// 只有當 spring.ai.model.chat=anthropic 時才註冊 Claude provider。
// 預設不啟用(Anthropic API key 政策不穩;用戶決定要才開)。
@ConditionalOnProperty(name = "spring.ai.model.chat", havingValue = "anthropic")
public class ClaudeSonnetProvider implements AiProvider {

    /** 2026-04 Anthropic 定價:Sonnet input $3/M, output $15/M。 */
    private static final BigDecimal INPUT_PRICE_PER_M  = new BigDecimal("3.0");
    private static final BigDecimal OUTPUT_PRICE_PER_M = new BigDecimal("15.0");
    private static final BigDecimal ONE_MILLION        = new BigDecimal("1000000");

    private final AnthropicChatModel chatModel;
    private final String model;

    @Autowired
    public ClaudeSonnetProvider(@Lazy AnthropicChatModel chatModel,
                                 @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5}")
                                 String model) {
        // @Lazy 解開循環依賴:Spring AI 的 chatModel → toolCallingManager → 所有
        // ToolCallback bean → AiRouterMcpTools → AiTaskRouter → ClaudeSonnetProvider → chatModel
        // 用 lazy proxy 注入,實際 call 時才解析
        this.chatModel = chatModel;
        this.model = model;
    }

    @Override
    public String name() { return "claude-sonnet"; }

    @Override
    public String model() { return model; }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(
                AiCapability.TOOL_USE,
                AiCapability.LARGE_CONTEXT,
                AiCapability.STREAMING,
                AiCapability.JSON_MODE
        );
    }

    @Override
    public boolean healthy() {
        // Spring AI bean 存在等同於 API key 配置成功
        return chatModel != null;
    }

    @Override
    public AiResponse execute(AiTask task) {
        Instant t0 = Instant.now();
        try {
            List<Message> messages = new ArrayList<>();
            if (task.systemPrompt() != null && !task.systemPrompt().isBlank()) {
                messages.add(new SystemMessage(task.systemPrompt()));
            }
            messages.add(new UserMessage(task.userPrompt()));

            AnthropicChatOptions opts = AnthropicChatOptions.builder()
                    .model(model)
                    .maxTokens(task.maxTokens())
                    .build();

            ChatResponse resp = chatModel.call(new Prompt(messages, opts));
            String text = resp.getResult().getOutput().getText();

            // Token 用量(Spring AI 從 metadata 拿)
            int inputTokens = 0, outputTokens = 0;
            if (resp.getMetadata() != null && resp.getMetadata().getUsage() != null) {
                inputTokens  = resp.getMetadata().getUsage().getPromptTokens();
                outputTokens = resp.getMetadata().getUsage().getCompletionTokens();
            }
            BigDecimal cost = computeCost(inputTokens, outputTokens);
            Duration latency = Duration.between(t0, Instant.now());

            log.info("[ClaudeProvider] task={} model={} in={} out={} cost=${} latency={}ms",
                    task.type(), model, inputTokens, outputTokens, cost, latency.toMillis());

            return new AiResponse(text, name(), model, inputTokens, outputTokens, cost, latency);

        } catch (Throwable t) {
            throw new AiProviderException(
                    "Claude call failed: " + t.getMessage(),
                    AiRetryClassifier.isRetryable(t), t);
        }
    }

    @Override
    public BigDecimal estimateCostUsd(AiTask task) {
        // 粗估:user prompt 1 字 ≈ 1 token(CJK)、output ~ maxTokens 半量
        int estIn  = task.userPrompt() != null ? task.userPrompt().length() : 100;
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
