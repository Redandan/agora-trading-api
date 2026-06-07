package com.agora.scheduler.trading;

import com.agora.service.ai.GeminiMarketAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "trading.gemini-advisor.enabled", havingValue = "true", matchIfMissing = false)
public class GeminiMarketAdvisorScheduler {

    private final GeminiMarketAdvisor geminiMarketAdvisor;

    /** 00:05, 08:05, 16:05 UTC(避開 0:00 / :10 既有排程)。 */
    @Scheduled(cron = "${trading.gemini-advisor.cron:0 5 */8 * * *}", zone = "UTC")
    public void runOnSchedule() {
        geminiMarketAdvisor.runOnSchedule();
    }
}
