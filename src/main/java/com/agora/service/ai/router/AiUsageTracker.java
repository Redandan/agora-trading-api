package com.agora.service.ai.router;

import com.agora.config.properties.AiBudgetProperties;
import com.agora.model.AiTokenUsageDaily;
import com.agora.repository.system.AiTokenUsageDailyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Transactional wrapper around ai_token_usage_daily upsert + daily budget guard.
 * Injected into AiTaskRouter so every routed call is tracked and capped centrally.
 *
 * Budget caps (configurable via application.yml ai.budget.*):
 *   gemini-flash       default 1200 req/day (free tier = 1500, 20% buffer)
 *   groq-llama-3.3-70b default 13000 req/day (free tier = 14400, 10% buffer)
 *   0 = unlimited
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageTracker {

    private final AiTokenUsageDailyRepository repo;
    private final AiBudgetProperties props;

    @Transactional
    public void record(AiResponse resp) {
        if (resp == null || resp.model() == null) return;
        try {
            repo.upsert(LocalDate.now(), resp.model(), 1, resp.inputTokens(), resp.outputTokens(), 0);
        } catch (Exception e) {
            log.debug("[AiUsageTracker] upsert failed (non-critical): {}", e.getMessage());
        }
    }

    /** Returns true if today's request count for the provider's model has reached its daily cap. */
    @Transactional(readOnly = true)
    public boolean isOverBudget(String providerName, String model) {
        int cap = resolveCap(providerName);
        if (cap <= 0) return false;
        return repo.findByStatDateAndModel(LocalDate.now(), model)
                   .map(u -> u.getReqCount() >= cap)
                   .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<AiTokenUsageDaily> today() {
        return repo.findByStatDate(LocalDate.now());
    }

    private int resolveCap(String providerName) {
        return switch (providerName) {
            case "gemini-flash"        -> props.geminiFlashDailyReq();
            case "groq-llama-3.3-70b"  -> props.groqLlamaDailyReq();
            default -> 0;
        };
    }
}
