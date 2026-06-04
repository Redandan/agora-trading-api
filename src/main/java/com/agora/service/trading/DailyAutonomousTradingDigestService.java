package com.agora.service.trading;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class DailyAutonomousTradingDigestService {

    private static final String DEFAULT_SYMBOL = "BTCUSDT";
    private static final long DEFAULT_STRATEGY_ID = 574L;
    private static final String DEFAULT_SIDE = "LONG";
    private static final String BOUNDARY = "boundary=READ_ONLY; no order/OCO/strategy/grid/fund/Earn/RuntimeEvidence behavior changed.";
    private static final String WATCH_SIGNAL_NEAR_BUY_THRESHOLD = "WATCH_SIGNAL_NEAR_BUY_THRESHOLD";
    private static final String WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD = "WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD";
    private static final String WATCH_HIGH_FORWARD_RETURN_NO_BUY = "WATCH_HIGH_FORWARD_RETURN_NO_BUY";
    private static final String SCORE_BUY_DAILY_CAP_BREACH_REVIEW = "SCORE_BUY_DAILY_CAP_BREACH_REVIEW";
    private static final String SCORE_BUY_POST_SCOUT_SCHEDULER_FAILED = "SCORE_BUY_POST_SCOUT_SCHEDULER_FAILED";
    private static final String SCORE_BUY_POST_SCOUT_SCHEDULER_STALE = "SCORE_BUY_POST_SCOUT_SCHEDULER_STALE";

    private final Map<String, Digest> latestSnapshots = new ConcurrentHashMap<>();

    private final AutonomousTradingSnapshotService snapshotService;
    private final MissedOpportunityRegressionValidationService missedOpportunityRegressionValidationService;

    @Transactional(readOnly = true)
    public Digest generate(String symbol, Long strategyId, String side) {
        return generate(symbol, strategyId, side, "REFRESH");
    }

    @Transactional(readOnly = true)
    public Digest generate(String symbol, Long strategyId, String side, String snapshotMode) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);

        AutonomousTradingSnapshotService.Snapshot snapshot = snapshotService.capture(sym, sid, normalizedSide);
        String missedOpportunityRegression = missedOpportunityRegressionValidationService
                .getMissedOpportunityRegressionReport(sym, 24);
        List<String> anomalies = anomalies(snapshot);
        addMissedOpportunityRegressionAnomalies(missedOpportunityRegression, anomalies);
        String verdict = verdict(snapshot.rolloutRaw(), snapshot.loopRaw(), snapshot.monitorRaw(), snapshot.governanceRaw(), anomalies);
        boolean humanActionNeeded = "ACTION_REQUIRED".equals(verdict) || "HALTED".equals(verdict) || "REVIEW_PROMOTION".equals(verdict);
        String nextAction = nextAction(verdict, snapshot.rolloutRaw(), snapshot.loopRaw(), snapshot.monitorRaw(),
                snapshot.scoreBuyPrePositionAutoExecutionRaw(), snapshot.scoreBuyPostScoutAutoAddRaw(),
                snapshot.scoreBuyConfirmedDeployAutoExecutionRaw(), missedOpportunityRegression);

        Digest digest = new Digest(
                Instant.now(),
                snapshotMode == null || snapshotMode.isBlank() ? "REFRESH" : snapshotMode,
                snapshot.symbol(),
                snapshot.strategyId(),
                snapshot.side(),
                verdict,
                humanActionNeeded,
                nextAction,
                snapshot.rolloutRaw(),
                snapshot.loopRaw(),
                snapshot.monitorRaw(),
                snapshot.executionsRaw(),
                snapshot.ocoRaw(),
                snapshot.exposureRaw(),
                snapshot.tradesRaw(),
                snapshot.tradeReconciliationRaw(),
                snapshot.tradeAttribution(),
                snapshot.eventRiskRaw(),
                snapshot.outcomeRaw(),
                snapshot.governanceRaw(),
                snapshot.relaxationRaw(),
                snapshot.tighteningRaw(),
                snapshot.systemRaw(),
                snapshot.startupRaw(),
                snapshot.freshnessRaw(),
                snapshot.kline1hRaw(),
                snapshot.kline4hRaw(),
                snapshot.divergenceRaw(),
                snapshot.capitalAllocationRaw(),
                snapshot.scoreBuyRaw(),
                snapshot.scoreBuyFormingDayRaw(),
                snapshot.scoreBuyPrePositionAutoExecutionRaw(),
                snapshot.scoreBuyPostScoutRaw(),
                snapshot.scoreBuyPostScoutAutoAddRaw(),
                snapshot.scoreBuyConfirmedDeployAutoExecutionRaw(),
                missedOpportunityRegression,
                anomalies,
                promotionDecision(snapshot.rolloutRaw()),
                false);
        latestSnapshots.put(snapshotKey(sym, sid, normalizedSide), digest);
        return digest;
    }

    @Transactional(readOnly = true)
    public String getDailyAutonomousTradingDigest(String symbol, Long strategyId, String side) {
        return getDailyAutonomousTradingDigest(symbol, strategyId, side, false);
    }

    @Transactional(readOnly = true)
    public String getDailyAutonomousTradingDigest(String symbol, Long strategyId, String side, Boolean refresh) {
        String sym = normalizeSymbol(symbol);
        long sid = strategyId == null ? DEFAULT_STRATEGY_ID : strategyId;
        String normalizedSide = normalizeSide(side);
        if (Boolean.TRUE.equals(refresh)) {
            return generate(sym, sid, normalizedSide, "REFRESH").render();
        }
        Digest cached = latestSnapshots.get(snapshotKey(sym, sid, normalizedSide));
        if (cached != null) {
            return cached.withSnapshotMode("CACHED").render();
        }
        return generate(sym, sid, normalizedSide, "GENERATED_FALLBACK_NO_SNAPSHOT").render();
    }

    public String compactTelegramSummary(Digest digest) {
        return """
                <b>每日自動交易摘要</b>
                標的=%s 策略=%d 方向=%s
                結論=%s 需人工處理=%s 已下單=否
                Rollout=%s 建議階段=%s Loop=%s 生產模式=%s
                OCO=%s 治理=%s 標籤覆蓋率=%s
                額度檢查=%s
                SCORE_BUY預備倉=%s
                post-scout排程=%s
                post-scout下一觸發=%s
                post-scout加倉=%s
                日線確認部署=%s
                tiny-live槽位=%s
                下一步=%s
                異常=%s
                邊界=只讀摘要；不會下單、不會修改 OCO/策略/Grid/資金/Earn。
                """.formatted(
                digest.symbol(),
                digest.strategyId(),
                digest.side(),
                digest.verdict(),
                yesNo(digest.humanActionNeeded()),
                value(digest.rolloutRaw(), "currentStage"),
                value(digest.rolloutRaw(), "recommendedStage"),
                value(digest.loopRaw(), "currentState"),
                yesNo("true".equalsIgnoreCase(value(digest.rolloutRaw(), "productionEnabled"))),
                compactOco(digest.ocoRaw()),
                value(digest.governanceRaw(), "governanceMode"),
                value(digest.outcomeRaw(), "labelCoveragePct"),
                zhCompact(capBudgetConsistencySummary(digest.scoreBuyPostScoutAutoAddRaw())),
                zhCompact(scoreBuyPrePositionExecutionSummary(digest.scoreBuyPrePositionAutoExecutionRaw())),
                zhCompact(scoreBuyPostScoutSchedulerSummary(digest.scoreBuyPostScoutAutoAddRaw())),
                zhCompact(scoreBuyPostScoutNextTriggerSummary(digest.scoreBuyPostScoutAutoAddRaw())),
                zhCompact(scoreBuyPostScoutResetSummary(digest.scoreBuyPostScoutAutoAddRaw())),
                zhCompact(scoreBuyConfirmedDeploySummary(digest.scoreBuyConfirmedDeployAutoExecutionRaw())),
                zhCompact(tinyLiveSlotOutcomeSummary(digest.loopRaw(), digest.monitorRaw())),
                zhNextAction(digest.nextRecommendedAction()),
                digest.anomalies().isEmpty() ? "無" : digest.anomalies());
    }

    private static String yesNo(boolean value) {
        return value ? "是" : "否";
    }

    private static String zhNextAction(String raw) {
        if (raw == null || raw.isBlank()) {
            return "等待下一次摘要更新。";
        }
        String text = raw;
        text = text.replace("Keep high-frequency observation active for #574", "保持 #574 高頻觀察");
        text = text.replace("SCORE_BUY post-scout is waiting for", "SCORE_BUY post-scout 正在等待");
        text = text.replace("and will recheck all gates before any add", "，任何加倉前都會重新檢查所有門檻");
        text = text.replace("Wait for", "等待");
        text = text.replace("scheduler must recheck OCO/evidence/market gates before any add", "排程必須在任何加倉前重新檢查 OCO、證據與市場門檻");
        text = text.replace("Wait for expected gate changes.", "等待預期門檻變化。");
        text = text.replace("SCORE_BUY post-scout waits for pullback or daily confirmation before any add.", "SCORE_BUY post-scout 會等待回落或日線確認後才評估加倉。");
        text = text.replace("SCORE_BUY confirmed deploy is not ready for write-path execution.", "SCORE_BUY 日線確認部署尚未達到寫入路徑條件。");
        text = text.replace("SCORE_BUY pre-position is not ready for write-path execution.", "SCORE_BUY 預備倉尚未達到寫入路徑條件。");
        text = text.replace("Loop is waiting for the current tiny-live position to close.", "Loop 正在等待目前 tiny-live 倉位關閉。");
        text = text.replace("Continue dry-run observation.", "繼續 dry-run 觀察。");
        return text;
    }

    private static String zhCompact(String raw) {
        if (raw == null || raw.isBlank()) {
            return "N/A";
        }
        return raw
                .replace("scoreBuyPostScoutDailyCapAudit", "post-scout每日額度稽核")
                .replace("scoreBuyPostScoutScheduler", "post-scout排程")
                .replace("scoreBuyPostScoutNextTrigger", "post-scout下一觸發")
                .replace("scoreBuyPostScout", "post-scout加倉")
                .replace("scoreBuyPrePosition", "SCORE_BUY預備倉")
                .replace("scoreBuyConfirmedDeploy", "日線確認部署")
                .replace("tinyLiveSlot", "tiny-live槽位")
                .replace("state=", "狀態=")
                .replace("ordersToday=", "今日訂單=")
                .replace("base=", "基準=")
                .replace("currentNextCap=", "下一單目前上限=")
                .replace("adaptiveLimit=", "自適應上限=")
                .replace("residualLimit=", "剩餘上限=")
                .replace("maxConfigured=", "設定最大=")
                .replace("currentCapBlocksNextOrder=", "目前額度阻擋下一單=")
                .replace("priorExtraSlots=", "先前額外槽位=")
                .replace("extraSlotsUsed=", "已用額外槽位=")
                .replace("breachSuspected=", "疑似超額=")
                .replace("policy=", "策略=")
                .replace("formingState=", "成形狀態=")
                .replace("notional=", "名義金額=")
                .replace("primary=", "主要原因=")
                .replace("primaryBlockers=", "主要阻擋=")
                .replace("secondaryBlockers=", "次要阻擋=")
                .replace("capacityBlockers=", "容量阻擋=")
                .replace("openSameThesis=", "同thesis持倉=")
                .replace("maxOpen=", "最大持倉=")
                .replace("orderSent=", "已下單=")
                .replace("dailyConfirmed=", "日線已確認=")
                .replace("firstTranche=", "第一批=")
                .replace("type=", "類型=")
                .replace("suggested=", "建議=")
                .replace("remainingBudget=", "剩餘預算=")
                .replace("dailyCapOnly=", "僅每日額度阻擋=")
                .replace("resetUtc=", "重置UTC=")
                .replace("resetLocal=", "台北重置=")
                .replace("resetMinutes=", "距重置分鐘=")
                .replace("eligibleAfterReset=", "重置後可評估=")
                .replace("wouldExecuteAfterReset=", "重置後可能執行=")
                .replace("resetAction=", "重置動作=")
                .replace("watchdog=", "看門狗=")
                .replace("postResetRecheck=", "重置後重檢=")
                .replace("postResetExecutionPossible=", "重置後可能執行=")
                .replace("schedulerFresh=", "排程新鮮=")
                .replace("schedulerLagSeconds=", "排程延遲秒=")
                .replace("watchdogReason=", "看門狗原因=")
                .replace("blockers=", "阻擋=")
                .replace("enabled=", "已啟用=")
                .replace("dryRun=", "dry-run=")
                .replace("delayMs=", "延遲ms=")
                .replace("ticks=", "tick數=")
                .replace("skippedOverlap=", "重疊跳過=")
                .replace("lastStatus=", "上次狀態=")
                .replace("lastMode=", "上次模式=")
                .replace("lastTick=", "上次tick=")
                .replace("lastDone=", "上次完成=")
                .replace("next=", "下一次=")
                .replace("lastOrderSent=", "上次已下單=")
                .replace("lastOcoAttached=", "上次OCO已掛=")
                .replace("lastError=", "上次錯誤=")
                .replace("primaryGap=", "主要缺口=")
                .replace("action=", "動作=")
                .replace("reversal=", "反轉=")
                .replace("signals=", "訊號=")
                .replace("partialWatch=", "部分觀察=")
                .replace("persistence=", "持續性=")
                .replace("reversalNeed=", "反轉要求=")
                .replace("pullbackGap=", "回落差距=")
                .replace("requiredPullbackMax=", "回落上限=")
                .replace("rsi1h=", "1h RSI=")
                .replace("rsi15m=", "15m RSI=")
                .replace("triggerBlockers=", "觸發阻擋=")
                .replace("monitor=", "監控=")
                .replace("label=", "標籤=")
                .replace("outcome=", "結果=")
                .replace("reason=", "原因=");
    }

    public boolean severe(Digest digest) {
        if (digest == null) return false;
        if ("HALTED".equals(digest.verdict()) || "ACTION_REQUIRED".equals(digest.verdict())) return true;
        return containsAny(digest.anomalies(), "OCO_ABNORMAL", "UNEXPECTED_ORDER", "DAILY_LOSS_BUDGET",
                "PRODUCTION_ENABLED", SCORE_BUY_DAILY_CAP_BREACH_REVIEW,
                SCORE_BUY_POST_SCOUT_SCHEDULER_FAILED, SCORE_BUY_POST_SCOUT_SCHEDULER_STALE);
    }

    public String severeFingerprint(Digest digest) {
        if (digest == null) return "null";
        return digest.verdict() + "|" + value(digest.rolloutRaw(), "currentStage")
                + "|" + value(digest.loopRaw(), "currentState")
                + "|" + digest.anomalies();
    }

    private String verdict(String rollout, String loop, String monitor, String governance, List<String> anomalies) {
        String currentStage = value(rollout, "currentStage");
        String recommendedStage = value(rollout, "recommendedStage");
        boolean productionEnabled = "true".equalsIgnoreCase(value(rollout, "productionEnabled"));
        boolean canAutoPromote = "true".equalsIgnoreCase(value(rollout, "canAutoPromote"));
        String loopState = value(loop, "currentState");
        String monitorStatus = value(monitor, "monitorStatus");
        if ("HALTED".equals(currentStage) || "HALT_AND_NOTIFY".equals(loopState) || "TOO_LOOSE".equals(value(governance, "governanceMode"))) {
            return "HALTED";
        }
        if (containsAny(anomalies, "OCO_ABNORMAL", "SYSTEM_HEALTH_NOT_OK", "STARTUP_ERROR", "UNEXPECTED_ORDER",
                "DATA_HEALTH_CRITICAL", SCORE_BUY_DAILY_CAP_BREACH_REVIEW,
                SCORE_BUY_POST_SCOUT_SCHEDULER_FAILED, SCORE_BUY_POST_SCOUT_SCHEDULER_STALE,
                "TINY_LIVE_SLOT_CONSISTENCY_REVIEW")) {
            return "ACTION_REQUIRED";
        }
        if (containsAny(anomalies, WATCH_HIGH_FORWARD_RETURN_NO_BUY)) {
            return "REVIEW_PROMOTION";
        }
        if ((canAutoPromote || startsWith(recommendedStage, "PRODUCTION_")) && !productionEnabled) {
            return "REVIEW_PROMOTION";
        }
        if ("READY_TO_EXPLORE".equals(loopState)) {
            return productionEnabled ? "REVIEW_PROMOTION" : "OK_READY_DRY_RUN";
        }
        if ("WAIT_OPEN_POSITION".equals(loopState) || "WAIT_SIGNAL_BUY".equals(loopState)
                || "WAIT_EV_PASS".equals(loopState)
                || "WAIT_DAILY_CAP_RESET".equals(loopState) || "WAIT_OUTCOME_LABEL".equals(loopState)
                || "WAIT_OUTCOME_MATURITY".equals(monitorStatus)
                || WATCH_SIGNAL_NEAR_BUY_THRESHOLD.equals(monitorStatus)) {
            return "OK_WAIT";
        }
        if (containsText(value(rollout, "promotionBlockers"), "EV_FAIL")
                || containsText(value(loop, "blockers"), "OPEN_TINY_LIVE_POSITION")) {
            return "BLOCKED_EXPECTED";
        }
        return "OK_WAIT";
    }

    private List<String> anomalies(AutonomousTradingSnapshotService.Snapshot snapshot) {
        List<String> out = new ArrayList<>();
        String rollout = snapshot.rolloutRaw();
        String loop = snapshot.loopRaw();
        String monitor = snapshot.monitorRaw();
        String oco = snapshot.ocoRaw();
        String outcome = snapshot.outcomeRaw();
        String governance = snapshot.governanceRaw();
        String system = snapshot.systemRaw();
        String startup = snapshot.startupRaw();
        String freshness = snapshot.freshnessRaw();
        String kline1h = snapshot.kline1hRaw();
        String kline4h = snapshot.kline4hRaw();
        String divergence = snapshot.divergenceRaw();
        String scoreBuyPostScoutAutoAdd = snapshot.scoreBuyPostScoutAutoAddRaw();
        AutonomousTradingSnapshotService.TradeAttribution tradeAttribution = snapshot.tradeAttribution();
        if (containsText(oco, "SYNC_ERROR") && !containsText(oco, "0 SYNC_ERROR")) out.add("OCO_ABNORMAL");
        if (containsText(oco, "CRITICAL_UNPROTECTED") || containsText(oco, "[UNPROTECTED]")) out.add("OCO_ABNORMAL");
        if ("true".equalsIgnoreCase(value(rollout, "productionEnabled"))) out.add("PRODUCTION_ENABLED");
        if (tradeAttribution != null && tradeAttribution.unexpectedOrderDetected()) out.add("UNEXPECTED_ORDER");
        if (containsText(monitor, "UNEXPECTED_ORDER")) out.add("UNEXPECTED_ORDER");
        if (containsText(rollout, "dailyLossBudgetBreached=true") || containsText(loop, "DAILY_LOSS_BUDGET")) out.add("DAILY_LOSS_BUDGET");
        if ("TOO_LOOSE".equals(value(governance, "governanceMode"))) out.add("GOVERNANCE_TOO_LOOSE");
        if (containsText(system, "❌") || containsText(system, "⛔")) out.add("SYSTEM_HEALTH_NOT_OK");
        if (!value(startup, "ERROR").startsWith("0")) out.add("STARTUP_ERROR");
        if (containsText(freshness, "CRITICAL") || containsText(kline1h, "FAIL") || containsText(kline4h, "FAIL")
                || containsText(divergence, "critical=") && !containsText(divergence, "critical=0")) {
            out.add("DATA_HEALTH_CRITICAL");
        }
        if (containsText(outcome, "UNAVAILABLE") || containsText(governance, "UNAVAILABLE")) out.add("OUTCOME_OR_GOVERNANCE_UNAVAILABLE");
        if ("true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAdd, "dailyCapBreachSuspected"))) {
            out.add(SCORE_BUY_DAILY_CAP_BREACH_REVIEW);
        }
        if (scoreBuyPostScoutSchedulerFailed(scoreBuyPostScoutAutoAdd)) {
            out.add(SCORE_BUY_POST_SCOUT_SCHEDULER_FAILED);
        }
        if (scoreBuyPostScoutSchedulerStale(scoreBuyPostScoutAutoAdd)) {
            out.add(SCORE_BUY_POST_SCOUT_SCHEDULER_STALE);
        }
        if (tinyLiveSlotNeedsConsistencyReview(loop, monitor)) {
            out.add("TINY_LIVE_SLOT_CONSISTENCY_REVIEW");
        }
        return new ArrayList<>(out.stream().distinct().toList());
    }

    private void addMissedOpportunityRegressionAnomalies(String report, List<String> anomalies) {
        if ("FAIL".equals(value(report, "overallStatus"))) {
            anomalies.add("MISSED_OPPORTUNITY_REGRESSION_FAIL");
        } else if ("WARN".equals(value(report, "overallStatus"))) {
            anomalies.add("MISSED_OPPORTUNITY_RISK");
        }
        if (hasClassification(report, WATCH_SIGNAL_NEAR_BUY_THRESHOLD)) {
            anomalies.add(WATCH_SIGNAL_NEAR_BUY_THRESHOLD);
        }
        if (positiveLongValue(report, "capacityLimitedOpportunityCount")
                || hasClassification(report, WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD)) {
            anomalies.add(WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD);
        }
        if (positiveLongValue(report, "highForwardReturnNoBuyCount")) {
            anomalies.add(WATCH_HIGH_FORWARD_RETURN_NO_BUY);
        }
    }

    private String nextAction(String verdict, String rollout, String loop, String monitor,
                              String scoreBuyPrePositionAutoExecutionRaw,
                              String scoreBuyPostScoutAutoAddRaw,
                              String scoreBuyConfirmedDeployAutoExecutionRaw,
                              String missedOpportunityRegression) {
        boolean highForwardNoBuy = positiveLongValue(missedOpportunityRegression, "highForwardReturnNoBuyCount");
        return switch (verdict) {
            case "HALTED" -> "Operator review required before autonomous exploration can continue.";
            case "ACTION_REQUIRED" -> "Inspect anomalies first; do not promote production until cleared.";
            case "REVIEW_PROMOTION" -> highForwardNoBuy
                    ? "Review high-forward-return no-buy examples before changing thresholds; consider bounded pre-threshold exploration only after hard gates remain intact."
                    : "Review production promotion/cap recommendation; digest does not change config.";
            case "OK_READY_DRY_RUN" -> "Continue dry-run observation; existing loop may promote only through configured rollout gates.";
            case "BLOCKED_EXPECTED", "OK_WAIT" -> {
                boolean nearBuy = WATCH_SIGNAL_NEAR_BUY_THRESHOLD.equals(value(monitor, "monitorStatus"))
                        || hasClassification(missedOpportunityRegression, WATCH_SIGNAL_NEAR_BUY_THRESHOLD);
                boolean capacityLimited = positiveLongValue(missedOpportunityRegression, "capacityLimitedOpportunityCount")
                        || hasClassification(missedOpportunityRegression, WATCH_SCORE_BUY_CAPACITY_LIMITED_ADD);
                boolean dailyCapWait = hasClassification(missedOpportunityRegression, "VALID_DAILY_CAP_WAIT");
                String scoreBuyPrePositionAction = scoreBuyPrePositionNextAction(scoreBuyPrePositionAutoExecutionRaw);
                String scoreBuyPostScoutAction = scoreBuyPostScoutNextAction(scoreBuyPostScoutAutoAddRaw);
                String scoreBuyConfirmedDeployAction = scoreBuyConfirmedDeployNextAction(scoreBuyConfirmedDeployAutoExecutionRaw);
                String scoreBuyAction = combineActions(scoreBuyPrePositionAction,
                        combineActions(scoreBuyPostScoutAction, scoreBuyConfirmedDeployAction));
                String scoreBuyActionWithoutPostScout = combineActions(scoreBuyPrePositionAction,
                        scoreBuyConfirmedDeployAction);
                if (nearBuy && dailyCapWait) {
                    yield combineActions("Keep high-frequency observation active for #574; SCORE_BUY post-scout is waiting for " + dailyCapResetTiming(scoreBuyPostScoutAutoAddRaw) + " and will recheck all gates before any add.",
                            scoreBuyActionWithoutPostScout);
                }
                if (nearBuy && capacityLimited) {
                    yield combineActions("Keep high-frequency observation active for #574; also review SCORE_BUY staged add capacity before increasing exposure.",
                            scoreBuyAction);
                }
                if (dailyCapWait) {
                    yield combineActions("Wait for " + dailyCapResetTiming(scoreBuyPostScoutAutoAddRaw) + "; SCORE_BUY post-scout scheduler must recheck OCO/evidence/market gates before any add.",
                            scoreBuyActionWithoutPostScout);
                }
                if (nearBuy) {
                    yield combineActions("Keep high-frequency observation active; wait for BUY threshold cross before any tiny-live execution.",
                            scoreBuyAction);
                }
                if (capacityLimited) {
                    yield combineActions("Review SCORE_BUY staged add capacity; do not increase exposure until caps/OCO/budget are explicitly accepted.",
                            scoreBuyAction);
                }
                yield combineActions(firstNonBlank(value(loop, "nextRecommendedAction"), value(monitor, "nextRecommendedAction"), "Wait for expected gate changes."),
                        scoreBuyAction);
            }
            default -> "Monitor only.";
        };
    }

    private static String scoreBuyPrePositionNextAction(String scoreBuyPrePositionAutoExecutionRaw) {
        String primary = value(scoreBuyPrePositionAutoExecutionRaw, "primaryNoBuyReason");
        if ("N/A".equals(primary)) {
            return "";
        }
        if ("ELIGIBLE".equals(primary)) {
            return "SCORE_BUY pre-position is eligible; write path must still recheck OCO/evidence/caps before any order.";
        }
        if (primary.startsWith("PRE_POSITION_NOT_READY")) {
            return "SCORE_BUY pre-position waits for forming-day/pre-position readiness; capacity blockers are secondary until readiness clears.";
        }
        if (primary.startsWith("DAILY_CAP")) {
            return "SCORE_BUY pre-position is waiting for its scoped daily cap reset.";
        }
        if (primary.startsWith("OPEN_POSITION")) {
            return "SCORE_BUY pre-position is waiting on same-thesis open-position limits.";
        }
        return "SCORE_BUY pre-position is blocked by " + primary + ".";
    }

    private static String scoreBuyPostScoutNextAction(String scoreBuyPostScoutAutoAddRaw) {
        String state = value(scoreBuyPostScoutAutoAddRaw, "postScoutManagementState");
        if ("N/A".equals(state)) {
            return "";
        }
        boolean dailyCapResetPending = "true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "dailyCapOnlyBlocker"))
                || "true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "eligibleAfterDailyCapResetPreview"))
                || "true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "wouldExecuteAfterDailyCapReset"));
        if (dailyCapResetPending) {
            return "SCORE_BUY post-scout is waiting for " + dailyCapResetTiming(scoreBuyPostScoutAutoAddRaw) + " and will recheck all gates before any add.";
        }
        String normalizedState = state.toUpperCase(Locale.ROOT);
        if ("WAIT_PULLBACK_AFTER_SCOUT".equals(normalizedState)) {
            return "SCORE_BUY post-scout waits for pullback or daily confirmation; current add notional is 0 because short-term rebound is overheated.";
        }
        if (normalizedState.startsWith("HOLD_")) {
            return "SCORE_BUY post-scout is held by hard/readiness gates; no add until gates clear.";
        }
        if (normalizedState.startsWith("ADD_ON_")) {
            return "SCORE_BUY post-scout add is ready, but execution still depends on scoped caps, OCO, evidence, and market gates.";
        }
        return "";
    }

    private static String dailyCapResetTiming(String scoreBuyPostScoutAutoAddRaw) {
        String utc = value(scoreBuyPostScoutAutoAddRaw, "nextDailyCapResetAtUtc");
        String local = value(scoreBuyPostScoutAutoAddRaw, "nextDailyCapResetAtAsiaTaipei");
        String minutes = value(scoreBuyPostScoutAutoAddRaw, "dailyCapResetMinutesRemaining");
        if (!"N/A".equals(local)) {
            return "the configured UTC daily cap reset at " + utc
                    + " (Asia/Taipei " + local + ", minutesRemaining=" + minutes + ")";
        }
        if (!"N/A".equals(utc)) {
            return "the configured UTC daily cap reset at " + utc;
        }
        return "the configured UTC daily cap reset";
    }

    private static String scoreBuyConfirmedDeployNextAction(String scoreBuyConfirmedDeployAutoExecutionRaw) {
        String primary = value(scoreBuyConfirmedDeployAutoExecutionRaw, "primaryNoBuyReason");
        if ("N/A".equals(primary)) {
            return "";
        }
        if ("ELIGIBLE".equals(primary)) {
            return "SCORE_BUY confirmed deploy is eligible for the first capped tranche after write-path rechecks.";
        }
        if ("CONFIRMED_DEPLOY_NOT_READY".equals(primary)) {
            return "SCORE_BUY confirmed deploy waits for official daily #485 confirmation; min-notional/cap blockers are secondary until then.";
        }
        return "SCORE_BUY confirmed deploy is blocked by " + primary + ".";
    }

    private static String combineActions(String primary, String secondary) {
        if (secondary == null || secondary.isBlank()) {
            return primary;
        }
        if (primary == null || primary.isBlank()) {
            return secondary;
        }
        if (primary.contains(secondary)) {
            return primary;
        }
        return primary + " " + secondary;
    }

    private String promotionDecision(String rollout) {
        return "currentStage=" + value(rollout, "currentStage")
                + "; recommendedStage=" + value(rollout, "recommendedStage")
                + "; canAutoPromote=" + value(rollout, "canAutoPromote")
                + "; productionEnabled=" + value(rollout, "productionEnabled");
    }

    private static String section(String name, String raw, int lines) {
        return name + ":\n" + indent(excerpt(raw, lines), 2);
    }

    private static String excerpt(String text, int maxLines) {
        if (text == null || text.isBlank()) return "N/A";
        String[] lines = text.strip().split("\\R");
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(lines.length, Math.max(1, maxLines));
        for (int i = 0; i < limit; i++) {
            sb.append(lines[i]).append('\n');
        }
        if (lines.length > limit) sb.append("... truncated ").append(lines.length - limit).append(" lines\n");
        return sb.toString().stripTrailing();
    }

    private static String indent(String text, int spaces) {
        String pad = " ".repeat(spaces);
        return pad + (text == null ? "N/A" : text.replace("\n", "\n" + pad));
    }

    private static String value(String text, String key) {
        if (text == null) return "N/A";
        Pattern pattern = Pattern.compile("(?m)^\\s*\"?" + Pattern.quote(key) + "\"?\\s*[:=]\\s*([^\\n]+)");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return "N/A";
        String value = matcher.group(1).trim();
        if (value.endsWith(",")) value = value.substring(0, value.length() - 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String capBudgetConsistencySummary(String scoreBuyPostScoutAutoAddRaw) {
        String state = value(scoreBuyPostScoutAutoAddRaw, "state");
        if ("N/A".equals(state)) {
            return "scoreBuyPostScoutDailyCapAudit=N/A";
        }
        return "scoreBuyPostScoutDailyCapAudit state=%s ordersToday=%s base=%s currentNextCap=%s adaptiveLimit=%s residualLimit=%s maxConfigured=%s currentCapBlocksNextOrder=%s priorExtraSlots=%s extraSlotsUsed=%s breachSuspected=%s"
                .formatted(
                        state,
                        value(scoreBuyPostScoutAutoAddRaw, "ordersToday"),
                        value(scoreBuyPostScoutAutoAddRaw, "baseMaxOrdersPerDay"),
                        value(scoreBuyPostScoutAutoAddRaw, "currentMaxOrdersPerDayForNextOrder"),
                        value(scoreBuyPostScoutAutoAddRaw, "configuredAdaptiveLimit"),
                        value(scoreBuyPostScoutAutoAddRaw, "configuredResidualLimit"),
                        value(scoreBuyPostScoutAutoAddRaw, "maxConfiguredOrdersPerDayIncludingAdaptiveAndResidual"),
                        value(scoreBuyPostScoutAutoAddRaw, "currentCapBlocksNextOrder"),
                        value(scoreBuyPostScoutAutoAddRaw, "priorAdaptiveOrResidualSlotsUsed"),
                        value(scoreBuyPostScoutAutoAddRaw, "extraSlotsUsedToday"),
                        value(scoreBuyPostScoutAutoAddRaw, "dailyCapBreachSuspected"));
    }

    private static String scoreBuyPostScoutResetSummary(String scoreBuyPostScoutAutoAddRaw) {
        String state = value(scoreBuyPostScoutAutoAddRaw, "postScoutManagementState");
        if ("N/A".equals(state)) {
            return "scoreBuyPostScout=N/A";
        }
        return "state=%s holdingState=%s holdBtcMode=%s addOnType=%s autoAddAllowed=%s suggestedAddNotionalUsdt=%s remainingBudgetUsdt=%s primaryNoBuyReason=%s primaryBlockers=%s secondaryBlockers=%s dailyCapOnlyBlocker=%s nextDailyCapResetAtUtc=%s nextDailyCapResetAtAsiaTaipei=%s dailyCapResetMinutesRemaining=%s eligibleAfterReset=%s wouldExecuteAfterReset=%s dailyCapResetAction=%s capResetWatchdogState=%s postResetRecheckExpected=%s postResetExecutionPossible=%s capResetSchedulerFresh=%s capResetSchedulerLagSeconds=%s capResetWatchdogReason=%s blockers=%s orderSent=%s"
                .formatted(
                        state,
                        value(scoreBuyPostScoutAutoAddRaw, "scoreBuyHoldingState"),
                        value(scoreBuyPostScoutAutoAddRaw, "holdBtcMode"),
                        value(scoreBuyPostScoutAutoAddRaw, "addOnType"),
                        value(scoreBuyPostScoutAutoAddRaw, "autoAddAllowed"),
                        value(scoreBuyPostScoutAutoAddRaw, "suggestedAddNotionalUsdt"),
                        value(scoreBuyPostScoutAutoAddRaw, "remainingPostScoutAddBudgetUsdt"),
                        value(scoreBuyPostScoutAutoAddRaw, "primaryNoBuyReason"),
                        value(scoreBuyPostScoutAutoAddRaw, "primaryBlockers"),
                        value(scoreBuyPostScoutAutoAddRaw, "secondaryBlockers"),
                        value(scoreBuyPostScoutAutoAddRaw, "dailyCapOnlyBlocker"),
                        value(scoreBuyPostScoutAutoAddRaw, "nextDailyCapResetAtUtc"),
                        value(scoreBuyPostScoutAutoAddRaw, "nextDailyCapResetAtAsiaTaipei"),
                        value(scoreBuyPostScoutAutoAddRaw, "dailyCapResetMinutesRemaining"),
                        value(scoreBuyPostScoutAutoAddRaw, "eligibleAfterDailyCapResetPreview"),
                        value(scoreBuyPostScoutAutoAddRaw, "wouldExecuteAfterDailyCapReset"),
                        value(scoreBuyPostScoutAutoAddRaw, "dailyCapResetAction"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetWatchdogState"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetPostResetRecheckExpected"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetPostResetExecutionPossibleIfStillEligible"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetSchedulerFresh"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetSchedulerLagSeconds"),
                        value(scoreBuyPostScoutAutoAddRaw, "capResetWatchdogReason"),
                        value(scoreBuyPostScoutAutoAddRaw, "blockers"),
                        value(scoreBuyPostScoutAutoAddRaw, "orderSent"));
    }

    private static String scoreBuyPostScoutNextTriggerSummary(String scoreBuyPostScoutAutoAddRaw) {
        String primaryGap = value(scoreBuyPostScoutAutoAddRaw, "primaryGap");
        if ("N/A".equals(primaryGap)) {
            return "scoreBuyPostScoutNextTrigger=N/A";
        }
        return "primaryGap=%s nextRequiredAction=%s intradayReversalStatus=%s intradayReversalSignalCount=%s partialReversalWatch=%s partialReversalPersistenceReady=%s nextIntradayReversalRequirement=%s currentAbovePullbackMaxPct=%s requiredPullbackMaxPrice=%s oneHourRsi=%s fifteenMinuteRsi=%s triggerBlockingSignals=%s"
                .formatted(
                        primaryGap,
                        value(scoreBuyPostScoutAutoAddRaw, "nextRequiredAction"),
                        value(scoreBuyPostScoutAutoAddRaw, "intradayReversalStatus"),
                        value(scoreBuyPostScoutAutoAddRaw, "intradayReversalSignalCount"),
                        value(scoreBuyPostScoutAutoAddRaw, "partialReversalWatch"),
                        value(scoreBuyPostScoutAutoAddRaw, "partialReversalPersistenceReady"),
                        value(scoreBuyPostScoutAutoAddRaw, "nextIntradayReversalRequirement"),
                        value(scoreBuyPostScoutAutoAddRaw, "currentAbovePullbackMaxPct"),
                        value(scoreBuyPostScoutAutoAddRaw, "requiredPullbackMaxPrice"),
                        value(scoreBuyPostScoutAutoAddRaw, "oneHourRsi"),
                        value(scoreBuyPostScoutAutoAddRaw, "fifteenMinuteRsi"),
                        value(scoreBuyPostScoutAutoAddRaw, "triggerBlockingSignals"));
    }

    private static String scoreBuyPostScoutSchedulerSummary(String scoreBuyPostScoutAutoAddRaw) {
        String installed = value(scoreBuyPostScoutAutoAddRaw, "schedulerInstalled");
        if ("N/A".equals(installed)) {
            return "scoreBuyPostScoutScheduler=N/A";
        }
        return "installed=%s enabled=%s dryRun=%s fixedDelayMs=%s tickCount=%s skippedOverlapCount=%s lastStatus=%s lastMode=%s lastTickAtUtc=%s lastCompletedAtUtc=%s nextCheckAtUtc=%s lastOrderSent=%s lastOcoAttached=%s lastError=%s"
                .formatted(
                        installed,
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerEnabled"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerDryRun"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerFixedDelayMs"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerTickCount"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerSkippedOverlapCount"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastStatus"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastMode"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastTickAtUtc"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastCompletedAtUtc"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerNextCheckAtUtc"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastOrderSent"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastOcoAttached"),
                        value(scoreBuyPostScoutAutoAddRaw, "schedulerLastError"));
    }

    private static boolean scoreBuyPostScoutSchedulerFailed(String scoreBuyPostScoutAutoAddRaw) {
        return "true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "schedulerEnabled"))
                && "FAILED".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "schedulerLastStatus"));
    }

    private static boolean scoreBuyPostScoutSchedulerStale(String scoreBuyPostScoutAutoAddRaw) {
        if (!"true".equalsIgnoreCase(value(scoreBuyPostScoutAutoAddRaw, "schedulerEnabled"))) {
            return false;
        }
        String completedRaw = value(scoreBuyPostScoutAutoAddRaw, "schedulerLastCompletedAtUtc");
        if ("N/A".equals(completedRaw) || completedRaw.isBlank()) {
            return false;
        }
        long fixedDelayMs = positiveLongOrDefault(value(scoreBuyPostScoutAutoAddRaw, "schedulerFixedDelayMs"), 60000L);
        try {
            Instant completedAt = Instant.parse(completedRaw);
            long staleAfterMs = Math.max(180000L, fixedDelayMs * 3L);
            return completedAt.plusMillis(staleAfterMs).isBefore(Instant.now());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long positiveLongOrDefault(String raw, long fallback) {
        if (raw == null || raw.isBlank() || "N/A".equals(raw)) return fallback;
        try {
            long parsed = Long.parseLong(raw.replace("\"", "").trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String scoreBuyPrePositionExecutionSummary(String scoreBuyPrePositionAutoExecutionRaw) {
        String policy = value(scoreBuyPrePositionAutoExecutionRaw, "executionPolicy");
        if ("N/A".equals(policy)) {
            return "scoreBuyPrePosition=N/A";
        }
        return "policy=%s state=%s holdingState=%s holdBtcMode=%s proposedNotionalUsdt=%s primaryNoBuyReason=%s primaryBlockers=%s secondaryBlockers=%s capacityBlockers=%s ordersToday=%s maxOrdersPerDay=%s openSameThesisPositions=%s maxOpenPositions=%s orderSent=%s"
                .formatted(
                        policy,
                        value(scoreBuyPrePositionAutoExecutionRaw, "scoreBuyFormingState"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "scoreBuyHoldingState"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "holdBtcMode"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "proposedNotionalUsdt"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "primaryNoBuyReason"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "primaryBlockers"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "secondaryBlockers"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "capacityBlockers"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "scoreBuyPrePositionOrdersToday"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "maxOrdersPerDay"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "openSameThesisPositions"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "maxOpenPositions"),
                        value(scoreBuyPrePositionAutoExecutionRaw, "orderSent"));
    }

    private static String scoreBuyConfirmedDeploySummary(String scoreBuyConfirmedDeployAutoExecutionRaw) {
        String policy = value(scoreBuyConfirmedDeployAutoExecutionRaw, "confirmedDeployPolicy");
        if ("N/A".equals(policy)) {
            return "scoreBuyConfirmedDeploy=N/A";
        }
        return "policy=%s dailyScoreBuyConfirmed=%s holdingState=%s holdBtcMode=%s firstTrancheNotionalUsdt=%s primaryNoBuyReason=%s primaryBlockers=%s secondaryBlockers=%s capacityBlockers=%s ordersToday=%s maxOrdersPerDay=%s openSameThesisPositions=%s maxOpenPositions=%s orderSent=%s"
                .formatted(
                        policy,
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "dailyScoreBuyConfirmed"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "scoreBuyHoldingState"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "holdBtcMode"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "firstTrancheNotionalUsdt"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "primaryNoBuyReason"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "primaryBlockers"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "secondaryBlockers"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "capacityBlockers"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "scoreBuyConfirmedDeployOrdersToday"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "maxOrdersPerDay"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "openSameThesisPositions"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "maxOpenPositions"),
                        value(scoreBuyConfirmedDeployAutoExecutionRaw, "orderSent"));
    }

    private static String tinyLiveSlotOutcomeSummary(String loopRaw, String monitorRaw) {
        String currentState = value(loopRaw, "currentState");
        String monitorStatus = value(monitorRaw, "monitorStatus");
        String openTinyLiveWait = firstNonBlankStatic(
                value(loopRaw, "openTinyLiveWaitSummary"),
                embeddedValue(value(monitorRaw, "explorationReadinessSummary"), "openTinyLiveWait"));
        String slotStatus = embeddedValue(openTinyLiveWait, "status");
        String openCount = firstNonBlankStatic(
                embeddedValue(openTinyLiveWait, "openPositionCount"),
                embeddedValue(value(monitorRaw, "explorationReadinessSummary"), "openTinyLivePositions"));
        String staleEligible = firstNonBlankStatic(
                embeddedValue(openTinyLiveWait, "staleSlotReleaseEligible"),
                embeddedValue(loopRaw, "staleTinyLiveSlotReleaseEligible"));
        String staleReason = firstNonBlankStatic(
                embeddedValue(openTinyLiveWait, "staleSlotReleaseReason"),
                embeddedValue(loopRaw, "staleTinyLiveSlotReleaseReason"));
        boolean staleReleaseEligible = "true".equalsIgnoreCase(staleEligible);
        boolean loopWaitingOpenPosition = "WAIT_OPEN_POSITION".equalsIgnoreCase(currentState)
                || containsTextStatic(value(loopRaw, "blockers"), "OPEN_TINY_LIVE_POSITION");
        boolean slotBlocksLoop = loopWaitingOpenPosition && !staleReleaseEligible;

        String outcomeLine = firstNonBlankStatic(value(monitorRaw, "outcomeLabelSummary"), value(loopRaw, "lastOutcomeLabel"));
        String unresolved = firstNonBlankStatic(
                embeddedValue(outcomeLine, "unresolvedCandidates"),
                embeddedValue(outcomeLine, "UNRESOLVED"),
                embeddedValue(loopRaw, "OUTCOME_LABELS_UNRESOLVED"));
        String pendingForward = firstNonBlankStatic(
                embeddedValue(outcomeLine, "pendingForwardWindowCount"),
                embeddedValue(loopRaw, "OUTCOME_LABELS_UNRESOLVED"));
        boolean maturityBlocking = "WAIT_OUTCOME_LABEL".equalsIgnoreCase(currentState)
                || "WAIT_OUTCOME_MATURITY".equalsIgnoreCase(currentState)
                || "WAIT_OUTCOME_MATURITY".equalsIgnoreCase(monitorStatus);
        if (containsTextStatic(loopRaw, "OUTCOME_LABELS_PENDING_FORWARD_WINDOW_NOT_LOOP_BLOCKING")) {
            maturityBlocking = false;
        }

        return "currentState=%s monitorStatus=%s openPositionCount=%s slotStatus=%s staleSlotReleaseEligible=%s staleSlotReleaseReason=%s slotBlocksLoop=%s unresolvedOutcomeLabels=%s pendingForwardWindowCount=%s outcomeMaturityBlocksLoop=%s"
                .formatted(
                        currentState,
                        monitorStatus,
                        openCount,
                        slotStatus,
                        staleEligible,
                        staleReason,
                        slotBlocksLoop,
                        unresolved,
                        pendingForward,
                        maturityBlocking);
    }

    private static boolean tinyLiveSlotNeedsConsistencyReview(String loopRaw, String monitorRaw) {
        String summary = tinyLiveSlotOutcomeSummary(loopRaw, monitorRaw);
        boolean staleReleaseEligible = containsTextStatic(summary, "staleSlotReleaseEligible=true");
        boolean loopWaitingOpenPosition = containsTextStatic(summary, "currentState=WAIT_OPEN_POSITION")
                || containsTextStatic(value(loopRaw, "blockers"), "OPEN_TINY_LIVE_POSITION");
        boolean loopWaitingSignal = containsTextStatic(summary, "currentState=WAIT_SIGNAL_BUY");
        boolean openPositionPresent = !containsTextStatic(summary, "openPositionCount=0")
                && !containsTextStatic(summary, "openPositionCount=N/A");
        if (staleReleaseEligible && loopWaitingOpenPosition) {
            return true;
        }
        return openPositionPresent && !staleReleaseEligible && loopWaitingSignal;
    }

    private static String embeddedValue(String text, String key) {
        if (text == null || text.isBlank() || "N/A".equals(text)) return "N/A";
        Pattern pattern = Pattern.compile("(?i)(?:^|[\\s,\\[{])" + Pattern.quote(key) + "\\s*=\\s*([^\\s,\\]}]+)");
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) return "N/A";
        return matcher.group(1).trim();
    }

    private static String firstNonBlankStatic(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"N/A".equals(value)) return value;
        }
        return "N/A";
    }

    private static boolean containsTextStatic(String value, String needle) {
        return value != null && needle != null && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private boolean startsWith(String value, String prefix) {
        return value != null && value.toUpperCase(Locale.ROOT).startsWith(prefix.toUpperCase(Locale.ROOT));
    }

    private boolean containsText(String value, String needle) {
        return value != null && needle != null && value.toUpperCase(Locale.ROOT).contains(needle.toUpperCase(Locale.ROOT));
    }

    private boolean hasClassification(String text, String classification) {
        if (text == null || classification == null) return false;
        Pattern jsonPattern = Pattern.compile("\"classification\"\\s*:\\s*\"" + Pattern.quote(classification) + "\"",
                Pattern.CASE_INSENSITIVE);
        if (jsonPattern.matcher(text).find()) return true;
        Pattern linePattern = Pattern.compile("(?m)^\\s*classification\\s*=\\s*" + Pattern.quote(classification) + "\\s*$",
                Pattern.CASE_INSENSITIVE);
        return linePattern.matcher(text).find();
    }

    private boolean positiveLongValue(String text, String key) {
        String raw = value(text, key);
        if (raw == null || raw.isBlank() || "N/A".equals(raw)) return false;
        try {
            return Long.parseLong(raw.replace("\"", "").trim()) > 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private boolean containsAny(List<String> values, String... needles) {
        if (values == null) return false;
        for (String value : values) {
            for (String needle : needles) {
                if (containsText(value, needle)) return true;
            }
        }
        return false;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank() && !"N/A".equals(value)) return value;
        }
        return "";
    }

    private String compactOco(String raw) {
        if (raw == null) return "N/A";
        for (String line : raw.split("\\R")) {
            if (line.contains("OK |") || line.contains("SYNC_ERROR") && line.contains("異常")) {
                return line.trim();
            }
        }
        return firstMeaningfulLine(raw);
    }

    private String firstMeaningfulLine(String raw) {
        if (raw == null) return "N/A";
        for (String line : raw.split("\\R")) {
            if (!line.isBlank() && !line.startsWith("===") && !line.startsWith("boundary")) return line.trim();
        }
        return "N/A";
    }

    private String normalizeSymbol(String symbol) {
        return symbol == null || symbol.isBlank() ? DEFAULT_SYMBOL : symbol.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeSide(String side) {
        String s = side == null || side.isBlank() ? DEFAULT_SIDE : side.trim().toUpperCase(Locale.ROOT);
        return "BUY".equals(s) ? "LONG" : s;
    }

    private String snapshotKey(String symbol, long strategyId, String side) {
        return normalizeSymbol(symbol) + "|" + strategyId + "|" + normalizeSide(side);
    }

    public record Digest(Instant generatedAt,
                         String snapshotMode,
                         String symbol,
                         long strategyId,
                         String side,
                         String verdict,
                         boolean humanActionNeeded,
                         String nextRecommendedAction,
                         String rolloutRaw,
                         String loopRaw,
                         String monitorRaw,
                         String executionsRaw,
                         String ocoRaw,
                         String exposureRaw,
                         String tradesRaw,
                         String tradeReconciliationRaw,
                         AutonomousTradingSnapshotService.TradeAttribution tradeAttribution,
                         String eventRiskRaw,
                         String outcomeRaw,
                         String governanceRaw,
                         String relaxationRaw,
                         String tighteningRaw,
                         String systemRaw,
                         String startupRaw,
                         String freshnessRaw,
                         String kline1hRaw,
                         String kline4hRaw,
                         String divergenceRaw,
                         String capitalAllocationRaw,
                         String scoreBuyRaw,
                         String scoreBuyFormingDayRaw,
                         String scoreBuyPrePositionAutoExecutionRaw,
                         String scoreBuyPostScoutRaw,
                         String scoreBuyPostScoutAutoAddRaw,
                         String scoreBuyConfirmedDeployAutoExecutionRaw,
                         String missedOpportunityRegressionRaw,
                         List<String> anomalies,
                         String promotionDecision,
                         boolean orderSent) {

        public Digest withSnapshotMode(String snapshotMode) {
            return new Digest(generatedAt, snapshotMode, symbol, strategyId, side, verdict, humanActionNeeded,
                    nextRecommendedAction, rolloutRaw, loopRaw, monitorRaw, executionsRaw, ocoRaw, exposureRaw,
                    tradesRaw, tradeReconciliationRaw, tradeAttribution, eventRiskRaw, outcomeRaw, governanceRaw,
                    relaxationRaw, tighteningRaw, systemRaw, startupRaw, freshnessRaw, kline1hRaw, kline4hRaw,
                    divergenceRaw, capitalAllocationRaw, scoreBuyRaw, scoreBuyFormingDayRaw,
                    scoreBuyPrePositionAutoExecutionRaw, scoreBuyPostScoutRaw, scoreBuyPostScoutAutoAddRaw,
                    scoreBuyConfirmedDeployAutoExecutionRaw, missedOpportunityRegressionRaw, anomalies, promotionDecision,
                    orderSent);
        }

        public String render() {
            AutonomousTradingSnapshotService.TradeAttribution attribution = tradeAttribution == null
                    ? AutonomousTradingSnapshotService.TradeAttribution.empty()
                    : tradeAttribution;
            StringBuilder sb = new StringBuilder();
            sb.append("=== Daily Autonomous Trading Digest v0 ===\n")
                    .append(BOUNDARY).append('\n')
                    .append("generatedAt=").append(generatedAt).append('\n')
                    .append("snapshotMode=").append(snapshotMode).append('\n')
                    .append("snapshotAgeSeconds=").append(Math.max(0, Duration.between(generatedAt, Instant.now()).toSeconds())).append('\n')
                    .append("symbol=").append(symbol).append(" strategyId=").append(strategyId).append(" side=").append(side).append('\n')
                    .append("verdict=").append(verdict).append('\n')
                    .append("humanActionNeeded=").append(humanActionNeeded).append('\n')
                    .append("nextRecommendedAction=").append(nextRecommendedAction).append('\n')
                    .append("promotionDecision=").append(promotionDecision).append('\n')
                    .append("capBudgetConsistencySummary=").append(capBudgetConsistencySummary(scoreBuyPostScoutAutoAddRaw)).append('\n')
                    .append("scoreBuyPrePositionExecutionSummary=").append(scoreBuyPrePositionExecutionSummary(scoreBuyPrePositionAutoExecutionRaw)).append('\n')
                    .append("scoreBuyPostScoutSchedulerSummary=").append(scoreBuyPostScoutSchedulerSummary(scoreBuyPostScoutAutoAddRaw)).append('\n')
                    .append("scoreBuyPostScoutNextTriggerSummary=").append(scoreBuyPostScoutNextTriggerSummary(scoreBuyPostScoutAutoAddRaw)).append('\n')
                    .append("scoreBuyPostScoutResetSummary=").append(scoreBuyPostScoutResetSummary(scoreBuyPostScoutAutoAddRaw)).append('\n')
                    .append("scoreBuyConfirmedDeploySummary=").append(scoreBuyConfirmedDeploySummary(scoreBuyConfirmedDeployAutoExecutionRaw)).append('\n')
                    .append("tinyLiveSlotOutcomeSummary=").append(tinyLiveSlotOutcomeSummary(loopRaw, monitorRaw)).append('\n')
                    .append("unexpectedOrderDetected=").append(attribution.unexpectedOrderDetected()).append('\n')
                    .append("orphanOkxTradeCount=").append(attribution.orphanOkxTradeCount()).append('\n')
                    .append("orphanDbRecordCount=").append(attribution.orphanDbRecordCount()).append('\n')
                    .append("matchedOkxTrades=").append(attribution.matchedOkxTrades()).append('\n')
                    .append("matchedGridTrades=").append(attribution.matchedGridTrades()).append('\n')
                    .append("matchedLiveSignalTrades=").append(attribution.matchedLiveSignalTrades()).append('\n')
                    .append("matchedTinyLiveAuditTrades=").append(attribution.matchedTinyLiveAuditTrades()).append('\n')
                    .append("orderSent=").append(orderSent).append("\n\n")
                    .append(section("rolloutSummary", rolloutRaw, 18)).append("\n\n")
                    .append(section("loopSummary", loopRaw, 18)).append("\n\n")
                    .append(section("monitorSummary", monitorRaw, 14)).append("\n\n")
                    .append(section("executionSummary", executionsRaw, 12)).append("\n\n")
                    .append(section("ocoSummary", ocoRaw, 8)).append("\n\n")
                    .append(section("exposureSummary", exposureRaw, 12)).append("\n\n")
                    .append(section("okxTradeHistorySummary", tradesRaw, 12)).append("\n\n")
                    .append(section("okxTradeReconciliationSummary", tradeReconciliationRaw, 12)).append("\n\n")
                    .append(section("eventRiskSummary", eventRiskRaw, 10)).append("\n\n")
                    .append(section("capitalAllocationSummary", capitalAllocationRaw, 22)).append("\n\n")
                    .append(section("scoreBuyConvictionSummary", scoreBuyRaw, 26)).append("\n\n")
                    .append(section("scoreBuyFormingDaySummary", scoreBuyFormingDayRaw, 28)).append("\n\n")
                    .append(section("scoreBuyPrePositionAutoExecutionSummary", scoreBuyPrePositionAutoExecutionRaw, 26)).append("\n\n")
                    .append(section("scoreBuyPostScoutManagementSummary", scoreBuyPostScoutRaw, 26)).append("\n\n")
                    .append(section("scoreBuyPostScoutAutoAddSummary", scoreBuyPostScoutAutoAddRaw, 26)).append("\n\n")
                    .append(section("scoreBuyConfirmedDeployAutoExecutionSummary", scoreBuyConfirmedDeployAutoExecutionRaw, 26)).append("\n\n")
                    .append(section("missedOpportunityRegressionSummary", missedOpportunityRegressionRaw, 30)).append("\n\n")
                    .append(section("outcomeSummary", outcomeRaw, 14)).append("\n\n")
                    .append(section("governanceSummary", governanceRaw, 14)).append("\n")
                    .append(section("governanceRelaxationSummary", relaxationRaw, 8)).append("\n")
                    .append(section("governanceTighteningSummary", tighteningRaw, 8)).append("\n\n")
                    .append(section("systemHealthSummary", systemRaw, 14)).append("\n\n")
                    .append(section("startupLogSummary", startupRaw, 14)).append("\n\n")
                    .append(section("dataHealthSummary", freshnessRaw + "\n\n" + kline1hRaw + "\n\n" + kline4hRaw + "\n\n" + divergenceRaw, 24)).append("\n\n")
                    .append("anomalies=").append(anomalies == null || anomalies.isEmpty() ? "[]" : anomalies).append('\n');
            return sb.toString();
        }
    }

}

