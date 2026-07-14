package com.agora.service.trading;

import com.agora.model.BtGrid;
import com.agora.model.BtGridLevel;
import com.agora.model.BtLiveSignal;
import com.agora.model.BtStrategy;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtGridLevelRepository;
import com.agora.repository.trading.BtGridRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.BtOcoAdjustmentAuditRepository;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * #413 — Opportunity Scanner.
 *
 * <p>Read-only meta-tool that integrates current OCO / Grid / Earn / Strategy /
 * FundingArb state into a single ranked "what should I do" list. Replaces the
 * 5-10 minute manual juggling of {@code getOpenPositions} + {@code listGrids}
 * + {@code getBalance} + {@code listStrategies} + mental EV math.
 *
 * <p><b>Phase 1 scope</b>: 5 action kinds — HOLD/MODIFY/CLOSE OCO (per
 * position), HOLD Grid (per active grid), SUBSCRIBE Earn USDT, WAIT for
 * strategy trigger (aggregate), AVOID Funding Arb (single warning). No
 * capital constraint solver, no correlation penalty, no AI ranking. Pure
 * additive integration over already-shipped services.
 *
 * <p><b>EV horizon</b>: caller passes {@code horizonDays} (default 7). Each
 * EV is annualized-or-event-based as appropriate, then scaled to the horizon.
 * <ul>
 *   <li>OCO EV: from {@link OcoOutcomeAnalysisService} as-is — that service
 *       assumes the bracket fully resolves within the horizon, so no scaling.</li>
 *   <li>Grid EV: historical PnL-per-pair × projected pairs in horizon.</li>
 *   <li>Earn EV: principal × APY × (horizonDays / 365).</li>
 *   <li>Strategy WAIT EV: signals in last 7d × historical avg PnL × (horizon/7),
 *       Phase 1 simplification: $0 placeholder if zero historical fires.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OpportunityScannerService {

    /** Reserve a small USDT buffer so we never drain the trading account dry. */
    private static final double USDT_BUFFER = 20.0;

    /** Minimum funding rate magnitude (per 8h) below which arb is dead money. */
    private static final double FUNDING_ARB_MIN_ABS = 0.0001;  // 0.01% per 8h

    /** Earn min subscribe — anything smaller than this isn't worth a row. */
    private static final double EARN_MIN_SUBSCRIBE = 5.0;

    /** Phase 3 — concentration penalty triggers above this BTC % of total assets. */
    private static final double BTC_CONCENTRATION_THRESHOLD = 60.0;
    /** Phase 3 — multiplicative penalty applied to BTC LONG rows when over threshold. */
    private static final double CONCENTRATION_PENALTY = 0.7;
    /** Phase 3 — joint EV correlation haircut per extra BTC LONG row (cap at 30%). */
    private static final double JOINT_EV_HAIRCUT_PER_EXTRA_ROW = 0.05;
    private static final double JOINT_EV_HAIRCUT_CAP = 0.30;
    /** Phase 3 — ADD_GRID template params. */
    private static final int ADD_GRID_LEVELS = 3;
    private static final double ADD_GRID_MIN_FREE_USDT = 50.0;
    private static final double ADD_GRID_MAX_CONCENTRATION = 70.0;

    /** Phase 4 — MODIFY_OCO_TIGHTEN template emits when WARN P(TP) ≥ this. */
    private static final double MODIFY_OCO_TIGHTEN_PTP_MIN = 90.0;
    /** Phase 4 — tighten reduces upside (less room to TP) but locks profit; rough EV factor. */
    private static final double MODIFY_OCO_TIGHTEN_EV_FACTOR = 0.85;
    /** Phase 4 — Monte Carlo joint EV simulation params. */
    private static final int MC_SIMULATIONS = 500;
    private static final long MC_SEED = 42L;  // deterministic for reproducibility
    private static final int MC_VOL_LOOKBACK_DAYS = 30;
    private static final double MC_DEFAULT_DAILY_VOL = 0.025;  // 2.5% fallback when no klines

    private final BtLiveSignalRepository liveSignalRepo;
    private final BtGridRepository gridRepo;
    private final BtGridLevelRepository gridLevelRepo;
    private final BtStrategyRepository strategyRepo;
    private final OkxTradingService okxTradingService;
    private final OkxEarnService okxEarnService;
    private final OcoOutcomeAnalysisService ocoAnalysisService;
    private final MdKlineRepository klineRepo;

    @Autowired(required = false)
    private BtOcoAdjustmentAuditRepository ocoAdjustmentAuditRepository;

    public enum ActionKind {
        HOLD_OCO, MODIFY_OCO, MODIFY_OCO_TIGHTEN, CLOSE_OCO, WARN_OCO, RECONCILE_OCO,
        HOLD_GRID, ADD_GRID, RESERVE_GRID,
        SUBSCRIBE_EARN,
        WAIT_STRATEGY,
        AVOID_FUNDING_ARB
    }

    /**
     * Phase 2 — affects EV scaling on speculative rows (OCO / Grid / WAIT) and
     * the visibility of MODIFY/CLOSE rows. Earn rows are unaffected (deterministic).
     * <ul>
     *   <li>{@code CONSERVATIVE}: 0.7× discount on speculative EV; demote any
     *       row when BTC concentration > 60%.</li>
     *   <li>{@code MODERATE}: 1.0× (Phase 1 default behaviour).</li>
     *   <li>{@code AGGRESSIVE}: 1.2× boost on speculative EV.</li>
     * </ul>
     */
    public enum RiskTolerance { CONSERVATIVE, MODERATE, AGGRESSIVE }

    public record Opportunity(
            ActionKind kind,
            String symbol,        // "BTCUSDT" / "USDT" / "" — Phase 3 correlation key
            String label,         // e.g. "HOLD #27 OCO"
            String detail,        // longer explanation
            double evUsdt,        // expected dollar P&L for the horizon (risk + concentration scaled)
            double rawEvUsdt,     // EV before any scaling — for transparency
            double evStdUsdt,     // Phase 4: estimated std deviation of EV (NaN if unknown)
            double winRatePct,    // 0..100, or NaN if not applicable
            double capitalUsdt,   // capital deployed or required
            String capitalNote,   // "already locked" / "free needed" / etc.
            String baselineNote,  // comparison to "do nothing" baseline
            boolean newCapital    // true if action requires NEW capital (subject to solver)
    ) {}

    public record ScanResult(
            int horizonDays,
            RiskTolerance risk,
            double freeUsdt,
            double btcQty,
            double btcValueUsd,
            double earnUsdtPrincipal,
            double totalUsd,
            double btcConcentrationPct,    // % of total assets exposed to BTC LONG
            Map<String, Double> concentrationBySymbol, // Phase 4: per-symbol % of total
            List<Opportunity> ranked,      // EV >= 0, sorted desc (final scaled)
            List<Opportunity> avoid,       // EV < 0 or warnings
            double summedEv,
            double allInEarnEv,            // baseline: subscribe all free USDT to Earn
            double btcDriftPct7d,          // Phase 3: last 7d BTC close drift %
            double hodlBaselineEv,         // Phase 3: drift × total BTC value × (horizon/7)
            double edgeOverBestBaseline,   // summedEv - max(0, allInEarn, hodl)
            double newCapitalRequested,    // sum of capital from newCapital=true rows
            double newCapitalAvailable,    // freeUsdt - USDT_BUFFER
            boolean capitalSolverOk,       // true if requested <= available
            int btcLongRowCount,           // Phase 3: # of BTC LONG rows in ranked (for joint EV)
            double correlationHaircutPct,  // Phase 3: 0..0.30 applied to summedEv
            double jointEvEstimate,        // Phase 3: summedEv × (1 - haircut) — fallback
            double mcJointEvMean,          // Phase 4: Monte Carlo joint EV mean (0 if unavailable)
            double mcJointEvStd,           // Phase 4: Monte Carlo joint EV std
            int mcSimulations,             // Phase 4: # of sims executed (0 = MC skipped)
            double mcDailyVolPct,          // Phase 4: estimated daily vol used (% × 100)
            String report
    ) {}

    /** Phase 1 entrypoint — defaults to MODERATE risk. */
    public ScanResult scan(int horizonDays) {
        return scan(horizonDays, RiskTolerance.MODERATE);
    }

    public ScanResult scan(int horizonDays, RiskTolerance risk) {
        if (horizonDays <= 0) horizonDays = 7;
        if (risk == null) risk = RiskTolerance.MODERATE;

        // ── 1. Snapshot account state ────────────────────────────────────
        double freeUsdt = 0;
        double btcQty = 0;
        try {
            List<OkxTradingService.SpotHolding> holdings = okxTradingService.getSpotHoldings();
            for (OkxTradingService.SpotHolding h : holdings) {
                if ("USDT".equalsIgnoreCase(h.ccy) && h.cashBal != null) {
                    freeUsdt = h.cashBal.doubleValue();
                } else if ("BTC".equalsIgnoreCase(h.ccy) && h.cashBal != null) {
                    btcQty = h.cashBal.doubleValue();
                }
            }
        } catch (Exception e) {
            log.warn("[OpportunityScanner] balance fetch failed: {}", e.getMessage());
        }

        double btcPrice = 0;
        try {
            BigDecimal p = okxTradingService.getLastPrice("BTCUSDT");
            if (p != null) btcPrice = p.doubleValue();
        } catch (Exception e) {
            log.warn("[OpportunityScanner] BTC price fetch failed: {}", e.getMessage());
        }
        double btcValue = btcQty * btcPrice;

        double earnPrincipal = 0;
        try {
            for (OkxEarnService.EarnBalance b : okxEarnService.getBalance(null)) {
                if ("USDT".equalsIgnoreCase(b.ccy()) && b.amt() != null) {
                    earnPrincipal += b.amt().doubleValue();
                }
            }
        } catch (Exception e) {
            log.warn("[OpportunityScanner] earn balance fetch failed: {}", e.getMessage());
        }

        double totalUsd = freeUsdt + btcValue + earnPrincipal;

        // Phase 3 — pre-compute last 7d BTC drift for HODL baseline
        double btcDriftPct = btc7dDriftPct();

        List<Opportunity> ranked = new ArrayList<>();
        List<Opportunity> avoid = new ArrayList<>();

        // ── 2. OCO opportunities (one per open position) ─────────────────
        List<BtLiveSignal> openOcoPositions = liveSignalRepo
                .findByAutoTradedIsTrueAndExitTimeIsNullAndOcoOrderListIdIsNotNull().stream()
                .filter(position -> !BtcBasePositionStatePolicy.isBtcBase(position))
                .toList();

        // Phase 4 — per-symbol LONG exposure (BTCUSDT, ETHUSDT, …)
        Map<String, Double> longExposureBySymbol = new HashMap<>();
        // Spot BTC always counts as BTCUSDT exposure
        if (btcValue > 0) longExposureBySymbol.merge("BTCUSDT", btcValue, Double::sum);

        for (BtLiveSignal pos : openOcoPositions) {
            try {
                TargetTouchAnomaly anomaly = detectTargetTouchAnomaly(pos);
                if (anomaly != null) {
                    double capital = notional(pos);
                    if (pos.getSymbol() != null && "LONG".equalsIgnoreCase(pos.getSide())) {
                        longExposureBySymbol.merge(pos.getSymbol(), capital, Double::sum);
                    }
                    avoid.add(new Opportunity(
                            ActionKind.RECONCILE_OCO,
                            pos.getSymbol(),
                            "RECONCILE #" + pos.getId() + " OCO",
                            String.format(
                                    "TARGET_TOUCHED_BUT_RECORD_OPEN: Position #%d %s %s target=%s observedExtreme=%s current=%s source=%s bars=%d; suppress normal HOLD until external parent/child/history reconciliation.",
                                    pos.getId(), pos.getSymbol(), side(pos), plain(pos.getSuggestedTp()),
                                    plain(anomaly.extreme()), plain(lastPrice(pos.getSymbol())),
                                    anomaly.source(), anomaly.bars()),
                            0.0,
                            0.0,
                            Double.NaN,
                            Double.NaN,
                            capital,
                            "already locked in OCO",
                            "requires read-only reconciliation before normal OCO recommendation",
                            false));
                    continue;
                }

                OcoOutcomeAnalysisService.Outcome o = ocoAnalysisService
                        .analyze(pos.getId(), horizonDays * 24);
                ActionKind kind = mapOcoSuggestion(o.suggestion());
                double capital = o.qty() * o.entry();
                if (pos.getSymbol() != null && "LONG".equalsIgnoreCase(pos.getSide())) {
                    longExposureBySymbol.merge(pos.getSymbol(), capital, Double::sum);
                }
                String detail = String.format(
                        "Position #%d %s entry=$%.2f tp=$%.2f sl=$%.2f → %s (P(TP)=%.0f%%)",
                        pos.getId(), pos.getSymbol(), o.entry(), o.tp(), o.sl(),
                        o.suggestion(), o.pTpFirstAdjusted() * 100);
                String label = kind == ActionKind.HOLD_OCO ? "HOLD #" + pos.getId() + " OCO"
                        : kind == ActionKind.MODIFY_OCO ? "MODIFY #" + pos.getId() + " OCO"
                        : kind == ActionKind.CLOSE_OCO ? "CLOSE #" + pos.getId() + " OCO"
                        : "WARN #" + pos.getId() + " OCO";
                double rawEv = o.evUsdt();
                double scaledEv = scaleSpeculativeEv(rawEv, kind, risk);
                double evStd = estimateOcoStd(o);
                String baseline = String.format("vs HODL: %+.2f USDT delta",
                        scaledEv - hodlReturnUsdt(capital, horizonDays, btcDriftPct));
                Opportunity opp = new Opportunity(kind, pos.getSymbol(), label, detail,
                        scaledEv, rawEv, evStd, o.pTpFirstAdjusted() * 100,
                        capital, "already locked in OCO", baseline,
                        false /* HOLD/MODIFY uses already-locked capital */);
                if (rawEv < 0 || kind == ActionKind.WARN_OCO || kind == ActionKind.CLOSE_OCO) {
                    avoid.add(opp);
                } else {
                    ranked.add(opp);
                }
                // Phase 4 — emit MODIFY_OCO_TIGHTEN alongside WARN with high P(TP)
                if (kind == ActionKind.WARN_OCO
                        && o.pTpFirstAdjusted() * 100 >= MODIFY_OCO_TIGHTEN_PTP_MIN) {
                    double tightenRaw = rawEv * MODIFY_OCO_TIGHTEN_EV_FACTOR;
                    double tightenScaled = scaleSpeculativeEv(tightenRaw, ActionKind.MODIFY_OCO_TIGHTEN, risk);
                    double tightenStd = evStd * 0.5;  // tighter bracket → narrower outcome
                    String tightenLabel = "TIGHTEN #" + pos.getId() + " OCO SL→entry+0.5%";
                    String tightenDetail = String.format(
                            "Lock partial profit: tighten SL from $%.2f to $%.2f (entry+0.5%%) — P(TP)=%.0f%% high",
                            o.sl(), o.entry() * 1.005, o.pTpFirstAdjusted() * 100);
                    String tightenBaseline = "vs current OCO: locks profit if BTC reverses; reduces upside";
                    ranked.add(new Opportunity(ActionKind.MODIFY_OCO_TIGHTEN,
                            pos.getSymbol(), tightenLabel, tightenDetail,
                            tightenScaled, tightenRaw, tightenStd,
                            o.pTpFirstAdjusted() * 100,
                            capital, "already locked in OCO", tightenBaseline,
                            false));
                }
            } catch (Exception e) {
                log.warn("[OpportunityScanner] OCO #{} analyze failed: {}",
                        pos.getId(), e.getMessage());
            }
        }

        // ── 3. Grid opportunities (one per active grid) ──────────────────
        List<BtGrid> activeGrids = gridRepo.findByEnabledTrueAndClosedAtIsNull();
        // Phase 5 (#416) — track capital reserved for PAUSED grids (don't divert to Earn)
        double pausedGridReserve = 0;
        for (BtGrid g : activeGrids) {
            try {
                if (g.getPausedAt() != null) {
                    // Phase 5 — emit RESERVE_GRID row for paused grids so user sees
                    // the idle capital allocation (vs silently dropping the grid)
                    Opportunity reserveOpp = buildReserveGridOpportunity(g);
                    if (reserveOpp != null) {
                        ranked.add(reserveOpp);
                        pausedGridReserve += reserveOpp.capitalUsdt();
                        // PAUSED grid still represents BTC LONG potential; flag for concentration
                        if (g.getSymbol() != null) {
                            longExposureBySymbol.merge(g.getSymbol(), reserveOpp.capitalUsdt(), Double::sum);
                        }
                    }
                    continue;
                }
                Opportunity opp = analyzeGrid(g, horizonDays, risk);
                if (opp != null) {
                    ranked.add(opp);
                    if (g.getSymbol() != null) {
                        longExposureBySymbol.merge(g.getSymbol(), opp.capitalUsdt(), Double::sum);
                    }
                }
            } catch (Exception e) {
                log.warn("[OpportunityScanner] Grid #{} analyze failed: {}",
                        g.getId(), e.getMessage());
            }
        }

        // ── 3b. Phase 4 — pre-compute per-symbol concentration (no ADD_GRID yet) ──
        Map<String, Double> currentConcentrationBySymbol = computeConcentration(longExposureBySymbol, totalUsd);
        double currentConcentrationPct = currentConcentrationBySymbol
                .getOrDefault("BTCUSDT", 0.0);

        // ── 3c. Phase 3 — ADD_GRID template (consumes free USDT, gated on concentration) ──
        double addGridCapital = 0;
        Opportunity addGridOpp = maybeAddGridOpportunity(activeGrids,
                freeUsdt, currentConcentrationPct, horizonDays, risk);
        if (addGridOpp != null) {
            ranked.add(addGridOpp);
            addGridCapital = addGridOpp.capitalUsdt();
        }

        // ── 4. Earn opportunity (USDT only, single recommendation) ───────
        // Phase 3 — Earn ask shrinks by ADD_GRID capital so solver doesn't overspend.
        // Phase 5 (#416) — also shrinks by pausedGridReserve so user keeps capital ready
        // for grid resume (regime flip) instead of fully draining to Earn.
        double subscribeUsdt = Math.max(0, freeUsdt - USDT_BUFFER - addGridCapital - pausedGridReserve);
        double allInEarnEv = 0;
        // Phase 3 — full-pool Earn baseline (independent of ADD_GRID/reserve claim)
        double fullPoolSubscribe = Math.max(0, freeUsdt - USDT_BUFFER);
        if (subscribeUsdt >= EARN_MIN_SUBSCRIBE) {
            try {
                OkxEarnService.EarnRateSummary rate = okxEarnService.getRateSummary("USDT");
                double apyPct = rate.estApyPct().doubleValue();
                double evEarn = subscribeUsdt * (apyPct / 100.0) * (horizonDays / 365.0);
                // Baseline anchor uses full pool (alternative: skip ADD_GRID, all-in Earn)
                allInEarnEv = fullPoolSubscribe * (apyPct / 100.0) * (horizonDays / 365.0);
                String detail = String.format(
                        "subscribeEarn(USDT, %.2f) — current est APY %.2f%%, %d-day yield",
                        subscribeUsdt, apyPct, horizonDays);
                String baseline = String.format("vs idle 0 USDT: +%.2f USDT", evEarn);
                // Earn EV is deterministic — risk tolerance does NOT scale it.
                // #481: free USDT is reserved for near-term investment deployment.
                // Keep the Earn EV visible for comparison, but do not rank it as
                // an executable recommendation.
                avoid.add(new Opportunity(ActionKind.SUBSCRIBE_EARN, "USDT",
                        String.format("SUBSCRIBE Earn USDT $%.0f", subscribeUsdt),
                        detail, evEarn, evEarn, 0.0 /* deterministic */, 99.0,
                        subscribeUsdt, "policy-blocked: deployment reserve", baseline,
                        true /* NEW capital — solver tracks this */));
            } catch (Exception e) {
                log.warn("[OpportunityScanner] Earn rate fetch failed: {}", e.getMessage());
            }
        }

        // ── 5. WAIT for strategy trigger (aggregate) ─────────────────────
        try {
            List<BtStrategy> enabled = strategyRepo.findAll().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                    .toList();
            if (!enabled.isEmpty()) {
                LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(7);
                long totalFires7d = 0;
                long totalTraded7d = 0;
                for (BtStrategy s : enabled) {
                    totalFires7d += liveSignalRepo
                            .countByStrategyIdAndCreatedAtAfter(s.getId(), since);
                }
                // Approximation: traded count from last 7d ≈ closed positions in same window
                for (BtStrategy s : enabled) {
                    totalTraded7d += liveSignalRepo.countByStrategyIdAndAutoTradedIsTrue(s.getId());
                }
                double fireRatePerDay = totalFires7d / 7.0;
                String detail = String.format(
                        "%d enabled strategies — fired %d signals in last 7d (≈%.1f/day, %d auto-traded all-time)",
                        enabled.size(), totalFires7d, fireRatePerDay, totalTraded7d);
                // Phase 1: don't try to forecast EV; mark as 0 placeholder.
                String baseline = "vs idle 0 USDT: passive (no capital deployed)";
                ranked.add(new Opportunity(ActionKind.WAIT_STRATEGY, "",
                        "WAIT for strategy trigger",
                        detail, 0.0, 0.0, Double.NaN, Double.NaN,
                        0.0, "no capital required", baseline,
                        false /* passive */));
            }
        } catch (Exception e) {
            log.warn("[OpportunityScanner] strategy wait analysis failed: {}", e.getMessage());
        }

        // ── 6. AVOID Funding Arb if current rate is flat ─────────────────
        try {
            double fundingRate = okxTradingService.getCurrentFundingRate("BTCUSDT");
            if (Math.abs(fundingRate) < FUNDING_ARB_MIN_ABS) {
                String detail = String.format(
                        "BTC swap funding rate = %+.4f%% per 8h → ~%+.2f%% APY before fees. " +
                        "OKX taker fee 0.1%% × 2 legs = 0.2%% per round trip. Net EV negative.",
                        fundingRate * 100, fundingRate * 365 * 3 * 100);
                avoid.add(new Opportunity(ActionKind.AVOID_FUNDING_ARB, "BTCUSDT",
                        "AVOID Funding Arb",
                        detail, -0.05, -0.05, Double.NaN, Double.NaN,
                        0.0, "no capital — skip", "vs idle 0: -0.05 (after fees)",
                        false));
            }
        } catch (Exception e) {
            log.warn("[OpportunityScanner] funding rate fetch failed: {}", e.getMessage());
        }

        // ── 7. Phase 4 — multi-symbol concentration penalty + MC joint EV ──
        // ADD_GRID's hypothetical capital is NOT added to longExposureBySymbol —
        // its row is gated/demoted based on pre-ADD_GRID concentration (no feedback loop).
        Map<String, Double> concentrationBySymbol = currentConcentrationBySymbol;
        double btcConcentrationPct = concentrationBySymbol.getOrDefault("BTCUSDT", 0.0);

        // Apply per-symbol concentration penalty: flag all symbols' LONG rows
        // when over threshold; demote *incremental* (newCapital=true) rows × 0.7.
        for (var entry : concentrationBySymbol.entrySet()) {
            if (entry.getValue() > BTC_CONCENTRATION_THRESHOLD) {
                applyConcentrationPenaltyForSymbol(ranked, entry.getKey());
                applyConcentrationPenaltyForSymbol(avoid, entry.getKey());
            }
        }

        // Sort
        ranked.sort(Comparator.comparingDouble(Opportunity::evUsdt).reversed());
        avoid.sort(Comparator.comparingDouble(Opportunity::evUsdt));

        double summedEv = ranked.stream().mapToDouble(Opportunity::evUsdt).sum();

        // Capital solver — only NEW capital actions consume the free pool
        double newCapitalRequested = ranked.stream()
                .filter(Opportunity::newCapital)
                .mapToDouble(Opportunity::capitalUsdt)
                .sum();
        double newCapitalAvailable = Math.max(0, freeUsdt - USDT_BUFFER);
        boolean capitalSolverOk = newCapitalRequested <= newCapitalAvailable + 0.01;

        // Phase 3 — BTC drift baseline (replaces flat $0 HODL)
        double hodlBaselineEv = btcValue * btcDriftPct * (horizonDays / 7.0);

        // Phase 3 — Edge over the best of {do-nothing, all-in Earn, HODL drift}
        double bestBaseline = Math.max(0, Math.max(allInEarnEv, hodlBaselineEv));
        double edge = summedEv - bestBaseline;

        // Phase 3 — Joint EV correlation haircut (now used as fallback only)
        int btcLongRowCount = (int) ranked.stream()
                .filter(o -> "BTCUSDT".equalsIgnoreCase(o.symbol())
                        && isLongKind(o.kind()))
                .count();
        double extraCorrelated = Math.max(0, btcLongRowCount - 1);
        double correlationHaircutPct = Math.min(
                JOINT_EV_HAIRCUT_PER_EXTRA_ROW * extraCorrelated,
                JOINT_EV_HAIRCUT_CAP);
        double jointEvEstimate = summedEv * (1.0 - correlationHaircutPct);

        // Phase 4 — full Monte Carlo GBM joint EV (replaces haircut when available)
        McJointResult mc = runMonteCarlo(btcPrice, horizonDays,
                openOcoPositions, activeGrids, risk);

        String report = formatReport(horizonDays, risk, freeUsdt, btcQty, btcValue,
                earnPrincipal, totalUsd, btcConcentrationPct, concentrationBySymbol,
                ranked, avoid, summedEv, allInEarnEv,
                btcDriftPct, hodlBaselineEv, edge,
                newCapitalRequested, newCapitalAvailable, capitalSolverOk,
                btcLongRowCount, correlationHaircutPct, jointEvEstimate,
                mc);

        return new ScanResult(horizonDays, risk, freeUsdt, btcQty, btcValue,
                earnPrincipal, totalUsd, btcConcentrationPct, concentrationBySymbol,
                ranked, avoid, summedEv, allInEarnEv,
                btcDriftPct, hodlBaselineEv, edge,
                newCapitalRequested, newCapitalAvailable, capitalSolverOk,
                btcLongRowCount, correlationHaircutPct, jointEvEstimate,
                mc.mean(), mc.std(), mc.sims(), mc.dailyVolPct(),
                report);
    }

    /** Phase 4 — compute per-symbol concentration % from longExposureBySymbol map. */
    private static Map<String, Double> computeConcentration(
            Map<String, Double> longExposureBySymbol, double totalUsd) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (totalUsd <= 0) return result;
        // Sort entries by exposure desc for stable display order
        longExposureBySymbol.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(e -> result.put(e.getKey(), 100.0 * e.getValue() / totalUsd));
        return result;
    }

    /** Phase 4 — true if this kind represents LONG exposure (subject to concentration). */
    private static boolean isLongKind(ActionKind k) {
        return k == ActionKind.HOLD_OCO || k == ActionKind.MODIFY_OCO
                || k == ActionKind.MODIFY_OCO_TIGHTEN
                || k == ActionKind.WARN_OCO || k == ActionKind.CLOSE_OCO
                || k == ActionKind.RECONCILE_OCO
                || k == ActionKind.HOLD_GRID || k == ActionKind.ADD_GRID;
    }

    /**
     * Phase 4 — apply concentration penalty + flag note to LONG rows of a specific
     * symbol in-place.  Penalty (0.7×) applied only to *incremental* (newCapital=true)
     * rows; existing locked rows get a flag note but unchanged EV.
     */
    private static void applyConcentrationPenaltyForSymbol(List<Opportunity> rows, String symbol) {
        for (int i = 0; i < rows.size(); i++) {
            Opportunity opp = rows.get(i);
            if (!symbol.equalsIgnoreCase(opp.symbol())) continue;
            if (!isLongKind(opp.kind())) continue;
            String detail = opp.detail();
            double newEv = opp.evUsdt();
            String suffix;
            if (opp.newCapital()) {
                newEv = opp.evUsdt() * CONCENTRATION_PENALTY;
                suffix = "  ⚠️ " + symbol + " concentration high — incremental 0.7× penalty";
            } else {
                suffix = "  ⚠️ " + symbol + " concentration high (existing position)";
            }
            if (!detail.contains("concentration high")) {
                detail = detail + suffix;
            }
            rows.set(i, new Opportunity(opp.kind(), opp.symbol(), opp.label(),
                    detail, newEv, opp.rawEvUsdt(), opp.evStdUsdt(),
                    opp.winRatePct(), opp.capitalUsdt(),
                    opp.capitalNote(), opp.baselineNote(), opp.newCapital()));
        }
    }

    /** Phase 4 — Monte Carlo GBM result (joint EV mean ± std over N sims). */
    public record McJointResult(double mean, double std, int sims, double dailyVolPct) {}

    /**
     * Phase 4 — estimate OCO P&L std from P(TP) Bernoulli (TP/SL outcomes).
     * Two-outcome simplification: var = pTp × (tpPnl − mean)² + pSl × (slPnl − mean)².
     */
    private static double estimateOcoStd(OcoOutcomeAnalysisService.Outcome o) {
        double pTp = Math.max(0, Math.min(1, o.pTpFirstAdjusted()));
        double pSl = 1.0 - pTp;
        double tpPnl = (o.tp() - o.entry()) * o.qty();
        double slPnl = (o.sl() - o.entry()) * o.qty();
        double mean = pTp * tpPnl + pSl * slPnl;
        double var = pTp * Math.pow(tpPnl - mean, 2) + pSl * Math.pow(slPnl - mean, 2);
        return Math.sqrt(Math.max(0, var));
    }

    /** Phase 4 — Grid pair PnL std heuristic: 50% CoV (rough). */
    private static double estimateGridStd(double evGrid) {
        return Math.abs(evGrid) * 0.5;
    }

    /** Phase 4 — estimate BTC daily log-return std from last N daily klines. */
    private double estimateBtcDailyVol() {
        try {
            List<MdKline> klines = klineRepo.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    "BTCUSDT", "1d", PageRequest.of(0, MC_VOL_LOOKBACK_DAYS + 1));
            if (klines.size() < 5) return MC_DEFAULT_DAILY_VOL;
            int n = klines.size() - 1;
            double[] logReturns = new double[n];
            for (int i = 0; i < n; i++) {
                double prev = klines.get(i + 1).getClosePrice().doubleValue();
                double curr = klines.get(i).getClosePrice().doubleValue();
                if (prev > 0 && curr > 0) {
                    logReturns[i] = Math.log(curr / prev);
                }
            }
            double mean = 0;
            for (double r : logReturns) mean += r;
            mean /= n;
            double var = 0;
            for (double r : logReturns) var += Math.pow(r - mean, 2);
            var /= n;
            double vol = Math.sqrt(var);
            return vol > 0 ? vol : MC_DEFAULT_DAILY_VOL;
        } catch (Exception e) {
            log.warn("[OpportunityScanner] daily vol estimate failed: {}", e.getMessage());
            return MC_DEFAULT_DAILY_VOL;
        }
    }

    /**
     * Phase 4 — Monte Carlo joint EV simulation. Generates {@link #MC_SIMULATIONS}
     * GBM price paths over {@code horizonDays} (daily steps), evaluates per-OCO
     * first-touch barrier outcomes and per-Grid range overlap, and returns mean ± std.
     * Risk scaling is applied to the final mean/std (consistent with single-row scaling).
     */
    private McJointResult runMonteCarlo(double btcPriceNow, int horizonDays,
                                         List<BtLiveSignal> ocoPositions,
                                         List<BtGrid> activeGrids,
                                         RiskTolerance risk) {
        if (btcPriceNow <= 0) return new McJointResult(0, 0, 0, 0);
        if (ocoPositions.isEmpty() && activeGrids.isEmpty()) {
            return new McJointResult(0, 0, 0, 0);
        }
        double dailyVol = estimateBtcDailyVol();
        Random r = new Random(MC_SEED);
        double[] simPnls = new double[MC_SIMULATIONS];

        for (int sim = 0; sim < MC_SIMULATIONS; sim++) {
            double price = btcPriceNow;
            double pathHigh = price, pathLow = price;
            for (int d = 0; d < horizonDays; d++) {
                double dailyReturn = dailyVol * r.nextGaussian();
                price *= Math.exp(dailyReturn);
                pathHigh = Math.max(pathHigh, price);
                pathLow = Math.min(pathLow, price);
            }
            double terminal = price;
            double pathPnl = 0;

            // Per-OCO: first-touch heuristic on path extremes
            for (BtLiveSignal pos : ocoPositions) {
                if (!"BTCUSDT".equalsIgnoreCase(pos.getSymbol())) continue;
                if (pos.getActualEntryPrice() == null
                        || pos.getSuggestedTp() == null
                        || pos.getSuggestedSl() == null
                        || pos.getOcoQty() == null) continue;
                double entry = pos.getActualEntryPrice().doubleValue();
                double tp = pos.getSuggestedTp().doubleValue();
                double sl = pos.getSuggestedSl().doubleValue();
                double qty = pos.getOcoQty().doubleValue();
                boolean tpCrossed = pathHigh >= tp;
                boolean slCrossed = pathLow <= sl;
                if (tpCrossed && !slCrossed) {
                    pathPnl += (tp - entry) * qty;
                } else if (!tpCrossed && slCrossed) {
                    pathPnl += (sl - entry) * qty;
                } else if (tpCrossed && slCrossed) {
                    // Ambiguous: weight by ratio of high/low excursions from entry
                    double upMove = Math.max(0, pathHigh - entry);
                    double downMove = Math.max(0, entry - pathLow);
                    double pTp = (upMove + downMove > 0) ? upMove / (upMove + downMove) : 0.5;
                    pathPnl += pTp * (tp - entry) * qty + (1 - pTp) * (sl - entry) * qty;
                } else {
                    pathPnl += (terminal - entry) * qty;
                }
            }

            // Per-Grid: range-overlap pair count × historical avg pair PnL
            for (BtGrid g : activeGrids) {
                if (g.getPausedAt() != null) continue;
                if (!"BTCUSDT".equalsIgnoreCase(g.getSymbol())) continue;
                if (g.getPriceLower() == null || g.getPriceUpper() == null
                        || g.getClosedPairCount() == null
                        || g.getTotalRealizedPnl() == null) continue;
                int closedPairs = g.getClosedPairCount();
                if (closedPairs == 0) continue;
                double lower = g.getPriceLower().doubleValue();
                double upper = g.getPriceUpper().doubleValue();
                int levels = g.getGridCount() != null ? g.getGridCount() : 1;
                double gridSpread = upper - lower;
                if (gridSpread <= 0) continue;
                double overlap = Math.max(0, Math.min(pathHigh, upper) - Math.max(pathLow, lower));
                if (overlap <= 0) continue;
                double avgPnl = g.getTotalRealizedPnl().doubleValue() / closedPairs;
                // Rough: overlap fraction × levels × 0.5 (each level contributes one half-pair)
                double pathPairs = (overlap / gridSpread) * levels * 0.5;
                pathPnl += pathPairs * avgPnl;
            }

            simPnls[sim] = pathPnl;
        }

        double mean = 0;
        for (double v : simPnls) mean += v;
        mean /= MC_SIMULATIONS;
        double var = 0;
        for (double v : simPnls) var += Math.pow(v - mean, 2);
        var /= MC_SIMULATIONS;
        double std = Math.sqrt(var);

        // Apply risk scaling to MC outputs (consistent with per-row scaling)
        double riskScale = switch (risk) {
            case CONSERVATIVE -> 0.7;
            case AGGRESSIVE -> 1.2;
            case MODERATE -> 1.0;
        };
        return new McJointResult(mean * riskScale, std * riskScale,
                                  MC_SIMULATIONS, dailyVol * 100);
    }

    /**
     * Phase 3 — ADD_GRID template. Generates an "extend the best-performing grid"
     * suggestion when conditions are met. Returns null when no candidate.
     * Conditions: at least one active grid with closed pairs > 0, free USDT
     * above {@link #ADD_GRID_MIN_FREE_USDT} + buffer, current BTC concentration
     * below {@link #ADD_GRID_MAX_CONCENTRATION}.
     */
    private Opportunity maybeAddGridOpportunity(List<BtGrid> activeGrids,
                                                 double freeUsdt,
                                                 double currentConcentrationPct,
                                                 int horizonDays,
                                                 RiskTolerance risk) {
        if (currentConcentrationPct >= ADD_GRID_MAX_CONCENTRATION) return null;
        if (freeUsdt < ADD_GRID_MIN_FREE_USDT + USDT_BUFFER) return null;
        BtGrid best = null;
        double bestPpd = 0;  // pairs-per-day × avgPnl (productivity score)
        long bestAgeDays = 1;
        for (BtGrid g : activeGrids) {
            if (g.getPausedAt() != null) continue;
            int closedPairs = g.getClosedPairCount() != null ? g.getClosedPairCount() : 0;
            if (closedPairs == 0) continue;
            double totalPnl = g.getTotalRealizedPnl() != null
                    ? g.getTotalRealizedPnl().doubleValue() : 0;
            long ageDays = g.getCreatedAt() != null
                    ? Math.max(1, Duration.between(g.getCreatedAt(),
                            LocalDateTime.now(ZoneOffset.UTC)).toDays())
                    : 1;
            double pairsPerDay = (double) closedPairs / ageDays;
            double avgPnlPerPair = totalPnl / closedPairs;
            double score = pairsPerDay * avgPnlPerPair;
            if (score > bestPpd) {
                best = g;
                bestPpd = score;
                bestAgeDays = ageDays;
            }
        }
        if (best == null || bestPpd <= 0) return null;
        double perLevelUsdt = best.getPerLevelUsdt() != null
                ? best.getPerLevelUsdt().doubleValue() : 10.0;
        double addCapital = perLevelUsdt * ADD_GRID_LEVELS;
        if (addCapital > Math.max(0, freeUsdt - USDT_BUFFER)) return null;

        // Project EV: same per-pair productivity scaled to additional levels.
        // Conservative: assume new levels match current pairs-per-day per level.
        int currentLevels = best.getGridCount() != null ? best.getGridCount() : 1;
        double rawEv = bestPpd * horizonDays * ((double) ADD_GRID_LEVELS / currentLevels);
        double scaledEv = scaleSpeculativeEv(rawEv, ActionKind.ADD_GRID, risk);
        int closedPairsBest = best.getClosedPairCount() != null ? best.getClosedPairCount() : 0;
        double ppd = (double) closedPairsBest / Math.max(1, bestAgeDays);
        double avgPnl = ppd > 0 ? bestPpd / ppd : 0;
        String detail = String.format(
                "Extend Grid #%d %s by %d levels @ $%.2f each — projected from %.1f pairs/day × $%.4f/pair",
                best.getId(), best.getSymbol(), ADD_GRID_LEVELS, perLevelUsdt,
                ppd, avgPnl);
        String baseline = String.format(
                "vs idle 0 USDT: +%.2f USDT (capital newly deployed)", scaledEv);
        double evStd = estimateGridStd(rawEv);
        return new Opportunity(ActionKind.ADD_GRID, best.getSymbol(),
                "ADD Grid #" + best.getId() + " +" + ADD_GRID_LEVELS + "L",
                detail, scaledEv, rawEv, evStd, Double.NaN,
                addCapital, "free USDT used", baseline,
                true /* NEW capital */);
    }

    /** Phase 3 — last 7d BTC close-to-close drift, returns 0 if data unavailable. */
    private double btc7dDriftPct() {
        try {
            List<MdKline> recent = klineRepo.findBySymbolAndIntervalCodeOrderByOpenTimeDesc(
                    "BTCUSDT", "1d", PageRequest.of(0, 8));
            if (recent.size() < 2) return 0;
            double now = recent.get(0).getClosePrice().doubleValue();
            double then = recent.get(recent.size() - 1).getClosePrice().doubleValue();
            if (then == 0) return 0;
            return (now - then) / then;
        } catch (Exception e) {
            log.warn("[OpportunityScanner] BTC drift fetch failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Phase 2 — risk-tolerance EV scalar for speculative rows.
     * Earn / WAIT placeholder / AVOID rows are never scaled.
     * Phase 3 — ADD_GRID is speculative (projection from grid history).
     */
    static double scaleSpeculativeEv(double rawEv, ActionKind kind, RiskTolerance risk) {
        boolean speculative = kind == ActionKind.HOLD_OCO
                || kind == ActionKind.MODIFY_OCO
                || kind == ActionKind.MODIFY_OCO_TIGHTEN
                || kind == ActionKind.CLOSE_OCO
                || kind == ActionKind.WARN_OCO
                || kind == ActionKind.HOLD_GRID
                || kind == ActionKind.ADD_GRID;
        if (!speculative) return rawEv;
        return switch (risk) {
            case CONSERVATIVE -> rawEv * 0.7;
            case AGGRESSIVE   -> rawEv * 1.2;
            case MODERATE     -> rawEv;
        };
    }

    /** Map OcoOutcomeAnalysisService suggestion strings to our action kinds. */
    private static ActionKind mapOcoSuggestion(String s) {
        if (s == null) return ActionKind.HOLD_OCO;
        String upper = s.toUpperCase();
        if (upper.startsWith("WARN")) return ActionKind.WARN_OCO;
        if (upper.startsWith("CLOSE")) return ActionKind.CLOSE_OCO;
        if (upper.startsWith("MODIFY")) return ActionKind.MODIFY_OCO;
        return ActionKind.HOLD_OCO;
    }

    /**
     * Project Grid EV over the horizon based on historical pair frequency.
     * <p>Returns null if grid has no history (closedPairCount=0) since Phase 1
     * doesn't model breakeven/level-distance forecast for fresh grids.
     */
    private Opportunity analyzeGrid(BtGrid g, int horizonDays, RiskTolerance risk) {
        int closedPairs = g.getClosedPairCount() != null ? g.getClosedPairCount() : 0;
        double totalPnl = g.getTotalRealizedPnl() != null
                ? g.getTotalRealizedPnl().doubleValue() : 0;

        long ageDays = g.getCreatedAt() != null
                ? Math.max(1, Duration.between(g.getCreatedAt(),
                        LocalDateTime.now(ZoneOffset.UTC)).toDays())
                : 1;

        // Historical pair-rate × per-pair PnL × horizon — null if no history.
        double evGrid;
        String detail;
        if (closedPairs == 0) {
            // No closed pairs yet — fall back to "passive monitoring" with $0 EV
            // rather than fabricate a forecast. Caller still sees the row.
            evGrid = 0;
            detail = String.format(
                    "Grid #%d %s [%s ~ %s] %d levels — no closed pairs yet (age %dd)",
                    g.getId(), g.getSymbol(),
                    g.getPriceLower().toPlainString(), g.getPriceUpper().toPlainString(),
                    g.getGridCount(), ageDays);
        } else {
            double pairsPerDay = (double) closedPairs / ageDays;
            double avgPnlPerPair = totalPnl / closedPairs;
            evGrid = pairsPerDay * horizonDays * avgPnlPerPair;
            detail = String.format(
                    "Grid #%d %s — %d pairs in %dd (≈%.1f/day, avg %+.4f USDT/pair)",
                    g.getId(), g.getSymbol(), closedPairs, ageDays,
                    pairsPerDay, avgPnlPerPair);
        }

        // Capital tied up = sum of per-level USDT × levels still HOLDING + PENDING
        // Approximation: assume all levels deploy at full perLevelUsdt
        double capital = 0;
        try {
            List<BtGridLevel> levels = gridLevelRepo.findByGridId(g.getId());
            for (BtGridLevel lv : levels) {
                String st = lv.getStatus();
                if ("HOLDING".equals(st) || "PENDING".equals(st)) {
                    capital += g.getPerLevelUsdt().doubleValue();
                }
            }
        } catch (Exception ignored) {
            capital = g.getPerLevelUsdt().doubleValue() * g.getGridCount();
        }

        String baseline = "vs HODL: grid captures sideways oscillation";
        double scaledEv = scaleSpeculativeEv(evGrid, ActionKind.HOLD_GRID, risk);
        double evStd = estimateGridStd(evGrid);
        return new Opportunity(ActionKind.HOLD_GRID, g.getSymbol(),
                "HOLD Grid #" + g.getId(),
                detail, scaledEv, evGrid, evStd, Double.NaN,
                capital, "already locked in Grid", baseline,
                false /* already-locked, not new capital */);
    }

    /**
     * Phase 5 (#416) — emit RESERVE_GRID row for paused grids so the user sees
     * the idle USDT capital reserved for grid resume (instead of silently dropping
     * the grid from output and over-allocating to Earn).
     * EV = 0 (opportunity cost only); capital = perLevelUsdt × pendingLevelCount.
     */
    private Opportunity buildReserveGridOpportunity(BtGrid g) {
        if (g.getPerLevelUsdt() == null) return null;
        double perLevel = g.getPerLevelUsdt().doubleValue();
        long pending;
        try {
            pending = gridLevelRepo.countByGridIdAndStatus(g.getId(), "PENDING");
        } catch (Exception e) {
            // fallback to total grid count if repo call fails
            pending = g.getGridCount() != null ? g.getGridCount() : 0;
        }
        if (pending <= 0) return null;
        double reserveCapital = perLevel * pending;
        String label = String.format("RESERVE Grid #%d (%d levels @ $%.2f)",
                g.getId(), pending, perLevel);
        String pausedAt = g.getPausedAt() != null
                ? g.getPausedAt().toString().substring(0, 10)
                : "(unknown)";
        String detail = String.format(
                "Grid #%d %s PAUSED since %s — keep $%.2f USDT idle for resume " +
                "(don't subscribe to Earn). Re-evaluate when regime flips.",
                g.getId(), g.getSymbol(), pausedAt, reserveCapital);
        String baseline = "vs full subscribe Earn: lose ~+$0.0X yield to keep grid alpha optionality";
        return new Opportunity(ActionKind.RESERVE_GRID, g.getSymbol(),
                label, detail,
                0.0 /* EV — pure reservation, no expected income */,
                0.0 /* rawEv */, 0.0 /* std — deterministic 0 */,
                Double.NaN /* winRate N/A */,
                reserveCapital, "reserved for grid resume", baseline,
                false /* not NEW capital — already in account, just earmarked */);
    }

    /**
     * Phase 3 — HODL baseline using last-7d BTC drift instead of flat 0.
     * Returns capital × drift × (horizon/7).  Drift = 0 falls back to flat baseline.
     */
    private static double hodlReturnUsdt(double capital, int horizonDays, double btcDriftPct) {
        if (btcDriftPct == 0) return 0;
        return capital * btcDriftPct * (horizonDays / 7.0);
    }

    private String formatReport(int horizonDays, RiskTolerance risk,
                                double freeUsdt, double btcQty,
                                double btcValue, double earnPrincipal, double totalUsd,
                                double btcConcentrationPct,
                                Map<String, Double> concentrationBySymbol,
                                List<Opportunity> ranked, List<Opportunity> avoid,
                                double summedEv, double allInEarnEv,
                                double btcDriftPct, double hodlBaselineEv, double edge,
                                double newCapitalRequested, double newCapitalAvailable,
                                boolean capitalSolverOk,
                                int btcLongRowCount, double correlationHaircutPct,
                                double jointEvEstimate,
                                McJointResult mc) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== Opportunity Scan ===\n");
        // Phase 5 (#416) — IDLE WARNING banner at top when recommendations
        // underperform best baseline (e.g. all-cash + Grid paused + no fires)
        if (edge < 0) {
            long btcLongCount = ranked.stream()
                    .filter(o -> "BTCUSDT".equalsIgnoreCase(o.symbol())
                            && (o.kind() == ActionKind.HOLD_OCO
                            || o.kind() == ActionKind.HOLD_GRID))
                    .count();
            long pausedCount = ranked.stream()
                    .filter(o -> o.kind() == ActionKind.RESERVE_GRID).count();
            sb.append("\n🚨 PORTFOLIO IDLE WARNING:\n");
            sb.append(String.format("  Recommended (%+.2f) underperforms HODL (%+.2f) by %.2f USDT.%n",
                    summedEv, hodlBaselineEv, Math.abs(edge)));
            sb.append(String.format(
                    "  Reasons: %d active BTC LONG positions / %d Grid PAUSED / no strategy fired in 7d.%n",
                    btcLongCount, pausedCount));
            sb.append("  Consider: (a) wait for regime flip / (b) light spot BTC buy / (c) accept Earn floor.\n\n");
        }
        sb.append(String.format("Horizon: %d days  |  Risk: %s  |  Total: $%.2f USD%n",
                horizonDays, risk, totalUsd));
        sb.append(String.format("  Free USDT:    $%.2f%n", freeUsdt));
        sb.append(String.format("  BTC:          %.8f ($%.2f)%n", btcQty, btcValue));
        sb.append(String.format("  Earn USDT:    $%.2f%n", earnPrincipal));
        // Phase 4 — multi-symbol concentration line
        if (concentrationBySymbol.isEmpty()) {
            sb.append("  Concentration: (no LONG exposure)\n");
        } else {
            StringBuilder cs = new StringBuilder();
            for (var e : concentrationBySymbol.entrySet()) {
                if (cs.length() > 0) cs.append(", ");
                cs.append(String.format("%s %.0f%%%s", e.getKey(), e.getValue(),
                        e.getValue() > BTC_CONCENTRATION_THRESHOLD ? " ⚠️" : ""));
            }
            sb.append(String.format("  Concentration: %s of total assets%n", cs));
        }

        if (!ranked.isEmpty()) {
            sb.append("\n📋 Ranked opportunities (EV desc):\n");
            int rank = 1;
            for (Opportunity o : ranked) {
                String medal = rank == 1 ? "🏆" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "  ";
                sb.append(String.format("%n%s RANK %d [%s]: %s%n", medal, rank++, o.kind(), o.label()));
                appendEvLine(sb, horizonDays, risk, o);
                if (!Double.isNaN(o.winRatePct())) {
                    sb.append(String.format("  Win rate: %.0f%%%n", o.winRatePct()));
                }
                sb.append(String.format("  Capital:  $%.2f (%s%s)%n",
                        o.capitalUsdt(), o.capitalNote(),
                        o.newCapital() ? ", NEW" : ""));
                sb.append(String.format("  %s%n", o.baselineNote()));
                sb.append(String.format("  Detail:   %s%n", o.detail()));
            }
        }

        if (!avoid.isEmpty()) {
            sb.append("\n⚠️ Avoid / policy-blocked / negative EV:\n");
            for (Opportunity o : avoid) {
                sb.append(String.format("%n❌ [%s]: %s%n", o.kind(), o.label()));
                // Phase 3 — avoid block now uses same EV formatting (with raw 後綴)
                appendEvLine(sb, horizonDays, risk, o);
                sb.append(String.format("  Reason:   %s%n", o.detail()));
            }
        }

        // Phase 3 — Baseline comparison (HODL uses real BTC drift)
        sb.append("\n📊 Baseline comparison (").append(horizonDays).append("d):\n");
        sb.append(String.format("  Do nothing (HODL):     %+.2f USDT  (BTC drift %+.2f%% over 7d)%n",
                hodlBaselineEv, btcDriftPct * 100));
        sb.append(String.format("  All-in OKX Earn:       %+.2f USDT%n", allInEarnEv));
        sb.append(String.format("  Recommended (this scan): %+.2f USDT%n", summedEv));
        sb.append(String.format("  Edge over best baseline: %+.2f USDT %s%n",
                edge, edge >= 0 ? "✅" : "⚠️ recommended underperforms baseline"));

        // Phase 4 — Joint EV (Monte Carlo when available; haircut as fallback)
        sb.append("\n📈 Joint EV:\n");
        sb.append(String.format("  Independent sum:       %+.2f USDT%n", summedEv));
        sb.append(String.format("  BTC LONG row count:    %d%s%n",
                btcLongRowCount,
                btcLongRowCount > 1 ? " (correlated exposure)" : ""));
        if (mc.sims() > 0) {
            sb.append(String.format("  Monte Carlo (%d sims): %+.2f ± %.2f USDT  (BTC vol %.2f%%/day)%n",
                    mc.sims(), mc.mean(), mc.std(), mc.dailyVolPct()));
            sb.append(String.format("  ↳ 95%% CI: [%+.2f, %+.2f] USDT%n",
                    mc.mean() - 1.96 * mc.std(), mc.mean() + 1.96 * mc.std()));
        } else {
            sb.append(String.format("  Correlation haircut:   %.0f%% (MC unavailable — fallback)%n",
                    correlationHaircutPct * 100));
            sb.append(String.format("  Joint EV estimate:     %+.2f USDT%n", jointEvEstimate));
        }

        // Phase 2 — Capital solver
        sb.append("\n💰 Capital solver:\n");
        sb.append(String.format("  NEW capital requested: $%.2f%n", newCapitalRequested));
        sb.append(String.format("  Available (free - $%.0f buffer): $%.2f%n",
                USDT_BUFFER, newCapitalAvailable));
        sb.append(String.format("  Status: %s%n",
                capitalSolverOk ? "✅ within budget" : "⚠️ OVER BUDGET — trim recommendations"));

        sb.append("\n💼 Summary:\n");
        sb.append(String.format("  Sum of recommended EV: %+.2f USDT (%dd)%n", summedEv, horizonDays));
        sb.append(String.format("  Annualized: %+.2f USDT%n", summedEv * 365.0 / horizonDays));

        sb.append("\n⚠️ Disclaimer: Phase 4 — read-only ranked recommendations.\n");
        sb.append("    EV is risk-scaled (CONSERVATIVE 0.7× / MODERATE 1.0× / AGGRESSIVE 1.2×)\n");
        sb.append("    on speculative rows; Earn EV is deterministic. Per-symbol concentration\n");
        sb.append("    > 60% applies 0.7× penalty on *incremental* (newCapital=true) rows of\n");
        sb.append("    that symbol; existing locked rows are flagged but EV unchanged.\n");
        sb.append("    HODL baseline uses last-7d BTC drift. Per-row ±std comes from Bernoulli\n");
        sb.append("    (OCO TP/SL) or 50%-CoV heuristic (Grid). Joint EV uses Monte Carlo GBM\n");
        sb.append("    (").append(MC_SIMULATIONS).append(" sims, BTC vol from last ").append(MC_VOL_LOOKBACK_DAYS)
                .append("d daily klines, deterministic seed) when\n");
        sb.append("    klines are available; falls back to 5%/extra-row haircut otherwise.\n");
        sb.append("    MODIFY_OCO_TIGHTEN emits when WARN_OCO P(TP)≥90% (lock partial profit).\n");
        sb.append("    ADD_GRID is template only; actual grid extension needs modifyGrid MCP.\n");
        return sb.toString();
    }

    /** Phase 4 — common EV line formatter: includes ± std (per-row variance) and raw 後綴. */
    private static void appendEvLine(StringBuilder sb, int horizonDays,
                                      RiskTolerance risk, Opportunity o) {
        boolean differs = Math.abs(o.evUsdt() - o.rawEvUsdt()) > 1e-9;
        boolean hasStd = !Double.isNaN(o.evStdUsdt()) && o.evStdUsdt() > 0;
        StringBuilder line = new StringBuilder();
        line.append(String.format("  EV (%dd): %+.2f", horizonDays, o.evUsdt()));
        if (hasStd) {
            line.append(String.format(" ± %.2f", o.evStdUsdt()));
        }
        line.append(" USDT");
        if (differs) {
            line.append(String.format("  (raw %+.2f × %s)", o.rawEvUsdt(), risk));
        }
        line.append(System.lineSeparator());
        sb.append(line);
    }

    private record TargetTouchAnomaly(BigDecimal extreme, String source, int bars) {}

    private TargetTouchAnomaly detectTargetTouchAnomaly(BtLiveSignal pos) {
        BigDecimal target = pos.getSuggestedTp();
        if (target == null || target.signum() <= 0) return null;
        TargetTouchAnomaly anomaly = inPositionExtreme(pos);
        if (anomaly == null || anomaly.extreme() == null || anomaly.extreme().signum() <= 0) return null;
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        boolean touched = isLong
                ? anomaly.extreme().compareTo(target) >= 0
                : anomaly.extreme().compareTo(target) <= 0;
        return touched ? anomaly : null;
    }

    private TargetTouchAnomaly inPositionExtreme(BtLiveSignal pos) {
        LocalDateTime start = pos.getCreatedAt() != null ? pos.getCreatedAt() : pos.getBarOpenTime();
        if (start == null) start = LocalDateTime.now(ZoneOffset.UTC).minusHours(72);
        LocalDateTime currentOcoEffectiveAt = currentOcoEffectiveAt(pos);
        if (currentOcoEffectiveAt != null && currentOcoEffectiveAt.isAfter(start)) {
            start = currentOcoEffectiveAt;
        }
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        String source = "okx";
        List<MdKline> bars = klineRepo.findBySymbolAndIntervalCodeAndSourceAndOpenTimeBetweenOrderByOpenTimeAsc(
                pos.getSymbol(), "1m", source, start, end);
        if (bars.isEmpty()) {
            bars = klineRepo.findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                    pos.getSymbol(), "1m", start, end);
            source = "local_kline";
        }
        boolean isLong = !"SHORT".equalsIgnoreCase(pos.getSide());
        BigDecimal best = null;
        for (MdKline bar : bars) {
            BigDecimal candidate = isLong ? bar.getHighPrice() : bar.getLowPrice();
            if (candidate == null) continue;
            if (best == null || (isLong ? candidate.compareTo(best) > 0 : candidate.compareTo(best) < 0)) {
                best = candidate;
            }
        }
        return best == null ? null : new TargetTouchAnomaly(best, source, bars.size());
    }

    private LocalDateTime currentOcoEffectiveAt(BtLiveSignal pos) {
        if (pos == null || pos.getOcoOrderListId() == null || pos.getSymbol() == null) {
            return null;
        }
        LocalDateTime localEffectiveAt = currentOcoEffectiveAtFromAudit(pos);
        if (localEffectiveAt != null) {
            return localEffectiveAt;
        }
        try {
            boolean isShort = "SHORT".equalsIgnoreCase(pos.getSide());
            com.fasterxml.jackson.databind.JsonNode algo = isShort
                    ? okxTradingService.getSwapAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId())
                    : okxTradingService.getAlgoOrder(pos.getSymbol(), pos.getOcoOrderListId());
            if (algo == null || algo.isNull() || algo.isMissingNode()) {
                return null;
            }
            String cTime = algo.path("cTime").asText("");
            if (cTime == null || cTime.isBlank()) {
                return null;
            }
            return java.time.Instant.ofEpochMilli(Long.parseLong(cTime))
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception e) {
            log.debug("[OpportunityScanner] current OCO effective time lookup failed posId={} algoId={}: {}",
                    pos.getId(), pos.getOcoOrderListId(), e.getMessage());
            return null;
        }
    }

    private LocalDateTime currentOcoEffectiveAtFromAudit(BtLiveSignal pos) {
        if (ocoAdjustmentAuditRepository == null || pos == null
                || pos.getId() == null || pos.getOcoOrderListId() == null) {
            return null;
        }
        try {
            return ocoAdjustmentAuditRepository
                    .findFirstByLiveSignalIdAndNewOcoOrderListIdOrderByEffectiveAtDesc(
                            pos.getId(), pos.getOcoOrderListId())
                    .map(com.agora.model.BtOcoAdjustmentAudit::getEffectiveAt)
                    .orElse(null);
        } catch (Exception e) {
            log.debug("[OpportunityScanner] local OCO audit lookup failed posId={} algoId={}: {}",
                    pos.getId(), pos.getOcoOrderListId(), e.getMessage());
            return null;
        }
    }

    private double notional(BtLiveSignal pos) {
        BigDecimal entry = pos.getActualEntryPrice() != null ? pos.getActualEntryPrice() : pos.getEntryPrice();
        BigDecimal qty = pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty();
        if (entry == null || qty == null) return 0.0;
        return entry.multiply(qty).doubleValue();
    }

    private BigDecimal lastPrice(String symbol) {
        try {
            return okxTradingService.getLastPrice(symbol);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String side(BtLiveSignal pos) {
        return "SHORT".equalsIgnoreCase(pos.getSide()) ? "SHORT" : "LONG";
    }

    private String plain(BigDecimal value) {
        return value == null ? "N/A" : value.stripTrailingZeros().toPlainString();
    }

    static double round(double v, int scale) {
        return BigDecimal.valueOf(v).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }
}
