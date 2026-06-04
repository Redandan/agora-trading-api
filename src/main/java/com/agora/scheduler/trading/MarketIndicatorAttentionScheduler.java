package com.agora.scheduler.trading;

import com.agora.service.meta.AttentionRuleEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every 30 minutes to evaluate attention rules that use mih_indicator predicates
 * (market_indicator_history values like btc_put_call_ratio, btc_basis_pct, us_vix).
 * Independent of strategy signal firing — triggers purely on indicator thresholds.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketIndicatorAttentionScheduler {

    private final AttentionRuleEvaluator attentionRuleEvaluator;

    @Scheduled(cron = "0 */30 * * * *", zone = "UTC")
    public void run() {
        log.debug("[MihAttention] evaluating market indicator attention rules");
        attentionRuleEvaluator.evaluateMarketIndicators();
    }
}
