package com.agora.service.backtest;

import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
class DataFreshnessShadowReplayCollector {

    static final String DISABLED = "DISABLED";
    static final String SNAPSHOT_ONLY_NOT_REPLAYABLE = "SNAPSHOT_ONLY_NOT_REPLAYABLE";
    static final String CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE = "CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE";
    static final String MISSING_REPLAY_FIELDS_WITHOUT_PLAN = String.join(",",
            "candidateEntry",
            "candidateTp",
            "candidateSl",
            "expected_r",
            "ev_result",
            "tqs_result",
            "oco_preflight",
            "duplicate_gate",
            "daily_cap",
            "exposure_gate",
            "event_risk",
            "open_position",
            "loss_budget");
    static final String MISSING_REPLAY_FIELDS_WITH_PLAN = String.join(",",
            "expected_r",
            "evaluated_ev_result",
            "evaluated_oco_preflight",
            "evaluated_duplicate_gate",
            "evaluated_daily_cap",
            "evaluated_exposure_gate",
            "evaluated_event_risk",
            "evaluated_open_position",
            "evaluated_loss_budget");

    private final DataFreshnessShadowReplayCandidatePlanBuilder candidatePlanBuilder;
    private final DataFreshnessShadowReplayHardGatePreviewBuilder hardGatePreviewBuilder;

    @Value("${trading.data-freshness.shadow-replay.collector.enabled:false}")
    private boolean enabled;

    void enrichAfterHardBlock(Map<String, Object> context,
                              BtStrategy strategy,
                              String symbol,
                              String intervalCode,
                              String klineSource,
                              MdKline newest,
                              LocalDateTime nowUtc,
                              LocalDateTime latestCloseEstimate,
                              long minSinceOpen,
                              long staleThreshold,
                              int intervalMinutes,
                              int klinesLoaded) {
        if (context == null) {
            return;
        }
        context.put("shadowReplayCollectorEnabled", enabled);
        context.put("shadowReplayCollectorStatus", enabled ? SNAPSHOT_ONLY_NOT_REPLAYABLE : DISABLED);
        context.put("shadowReplayEvidenceOnly", true);
        context.put("shadowReplayTerminalDecision", "DataFreshnessGuard");
        context.put("shadowReplayKeepsHardBlock", true);
        context.put("shadowReplayCreatesLiveSignal", false);
        context.put("shadowReplaySendsTelegram", false);
        context.put("shadowReplayPlacesOrder", false);
        context.put("shadowReplayCreatesOco", false);
        context.put("shadowReplayMutatesPolicy", false);

        if (!enabled) {
            return;
        }

        context.put("shadowReplayCandidateStatus", SNAPSHOT_ONLY_NOT_REPLAYABLE);
        context.put("shadowReplayMissingCounterfactualFields", MISSING_REPLAY_FIELDS_WITHOUT_PLAN);
        context.put("shadowReplayRequiredNextAction", "collect_ev_tqs_oco_and_hard_gate_snapshots_before_policy_review");
        context.put("strategyType", strategy != null ? strategy.getStrategyType() : null);
        context.put("strategyKlineSource", strategy != null ? strategy.getKlineSource() : null);
        context.put("snapshotSymbol", symbol);
        context.put("snapshotIntervalCode", intervalCode);
        context.put("snapshotKlineSource", klineSource);
        context.put("snapshotNowUtc", nowUtc != null ? nowUtc.toString() : null);
        context.put("snapshotLatestCloseEstimate", latestCloseEstimate != null ? latestCloseEstimate.toString() : null);
        context.put("snapshotStaleMinutes", minSinceOpen);
        context.put("snapshotStaleThresholdMinutes", staleThreshold);
        context.put("snapshotIntervalMinutes", intervalMinutes);
        context.put("snapshotKlinesLoaded", klinesLoaded);
        if (newest != null) {
            context.put("snapshotLatestBarOpen", newest.getOpenTime() != null ? newest.getOpenTime().toString() : null);
            context.put("snapshotLatestBarClose", newest.getCloseTime() != null ? newest.getCloseTime().toString() : null);
            context.put("snapshotOpenPrice", newest.getOpenPrice());
            context.put("snapshotHighPrice", newest.getHighPrice());
            context.put("snapshotLowPrice", newest.getLowPrice());
            context.put("snapshotClosePrice", newest.getClosePrice());
            context.put("snapshotVolume", newest.getVolume());
        }

        candidatePlanBuilder.build(strategy, newest).ifPresent(plan -> {
            context.put("shadowReplayCandidatePlanSource", plan.source());
            if (!plan.available()) {
                context.put("shadowReplayCandidatePlanStatus", plan.source());
                return;
            }
            context.put("shadowReplayCollectorStatus", CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE);
            context.put("shadowReplayCandidateStatus", CANDIDATE_PLAN_SNAPSHOT_NOT_REPLAYABLE);
            context.put("shadowReplayCandidatePlanStatus", "AVAILABLE_NOT_REPLAYABLE");
            context.put("shadowReplayMissingCounterfactualFields", MISSING_REPLAY_FIELDS_WITH_PLAN);
            context.put("shadowReplayRequiredNextAction", "collect_ev_tqs_oco_and_hard_gate_snapshots_before_policy_review");
            context.put("currentPrice", plan.entry());
            context.put("entry", plan.entry());
            context.put("tp", plan.tp());
            context.put("sl", plan.sl());
            context.put("candidateEntry", plan.entry());
            context.put("candidateTp", plan.tp());
            context.put("candidateSl", plan.sl());
            context.put("candidateQty", "NOT_SIZED");
            context.put("riskUsdt", "NOT_SIZED");
            context.put("stop_loss_pct", plan.stopLossPct());
            context.put("take_profit_pct", plan.takeProfitPct());
            context.put("maxHoldingHours", plan.maxHoldingHours());
            hardGatePreviewBuilder.enrich(context);
        });
    }
}
