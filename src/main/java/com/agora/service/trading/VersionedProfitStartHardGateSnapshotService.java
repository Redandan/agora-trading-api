package com.agora.service.trading;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure, read-only hard-gate calculation for VERSIONED_PROFIT_START_ACCEPTANCE_V1.
 *
 * <p>This service deliberately has no repository, provider, scheduler, order, OCO,
 * Grid, fund, Earn, or notification dependency. Callers must collect read-only
 * evidence before invoking it. The returned snapshot is immutable and binds all
 * normalized inputs and gate results to a deterministic SHA-256.</p>
 */
@Service
public class VersionedProfitStartHardGateSnapshotService {

    public static final String CONTRACT_VERSION = "VERSIONED_PROFIT_START_ACCEPTANCE_V1";
    public static final String IMPLEMENTATION_ID = "TINY_LIVE_HARD_GATE_SNAPSHOT_V1";
    public static final Duration DEFAULT_SNAPSHOT_TTL = Duration.ofSeconds(30);

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern COMMIT = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Z0-9._:-]{1,160}$");
    private static final int MAX_DECIMAL_PRECISION = 38;
    private static final int MAX_DECIMAL_INTEGER_DIGITS = 38;

    public Snapshot snapshot(Inputs inputs) {
        return evaluate(inputs);
    }

    public Snapshot evaluate(Inputs inputs) {
        if (inputs == null) {
            return terminalSnapshot(null, Decision.STOP, List.of("INPUTS_MISSING"));
        }

        List<GateResult> gates = new ArrayList<>();
        List<String> stopReasons = new ArrayList<>();
        List<String> waitReasons = new ArrayList<>();

        evaluateObservedAt(inputs.observedAt(), gates, stopReasons);
        evaluateSnapshotTtl(inputs.snapshotTtl(), gates, stopReasons);
        evaluateIdentity(inputs.expectedIdentity(), inputs.observedIdentity(), inputs.observedAt(), gates, stopReasons);
        evaluateCandidate(inputs.candidate(), inputs.observedAt(), gates, stopReasons, waitReasons);
        evaluateTimedGate("PRIMARY_TREND_1H", inputs.primaryTrend1h(), inputs.observedAt(), gates, stopReasons);
        evaluateTimedGate("PRIMARY_TREND_4H", inputs.primaryTrend4h(), inputs.observedAt(), gates, stopReasons);
        evaluateTimedGate("EVENT_RISK", inputs.eventRisk(), inputs.observedAt(), gates, stopReasons);
        evaluateLimits(inputs.limits(), gates, stopReasons);
        evaluateExposure(inputs.exposure(), gates, stopReasons);
        OcoPolicy ocoPolicy = evaluateOco(inputs.oco(), inputs.observedIdentity(), inputs.observedAt(),
                gates, stopReasons, waitReasons);
        evaluateTimedGate("RUNTIME_WRITER", inputs.runtimeWriter(), inputs.observedAt(), gates, stopReasons);

        Decision decision = !stopReasons.isEmpty()
                ? Decision.STOP
                : (!waitReasons.isEmpty() || ocoPolicy == OcoPolicy.NO_ORDER
                ? Decision.WAIT_MARKET : Decision.READY);
        List<String> reasons = decision == Decision.STOP ? stopReasons : waitReasons;
        return buildSnapshot(inputs, decision, gates, reasons);
    }

    /**
     * Re-evaluates inputs at the final order boundary and rejects expiry or any
     * hash drift from the previously reviewed snapshot.
     */
    public BoundaryDecision verifyAtOrderBoundary(Snapshot expected, Inputs currentInputs, Instant boundaryAt) {
        List<String> reasons = new ArrayList<>();
        if (expected == null) reasons.add("SNAPSHOT_MISSING");
        if (boundaryAt == null) reasons.add("ORDER_BOUNDARY_TIME_MISSING");
        if (!reasons.isEmpty()) return new BoundaryDecision(false, null, List.copyOf(reasons));

        if (expected.decision() != Decision.READY) reasons.add("SNAPSHOT_NOT_READY");
        if (expected.expiresAt() == null || boundaryAt.isAfter(expected.expiresAt())) {
            reasons.add("SNAPSHOT_EXPIRED");
        }
        Snapshot current = evaluate(currentInputs);
        if (current.decision() != Decision.READY) reasons.add("CURRENT_SNAPSHOT_NOT_READY");
        if (!Objects.equals(expected.sha256(), current.sha256())) reasons.add("SNAPSHOT_HASH_DRIFT");
        return new BoundaryDecision(reasons.isEmpty(), current, List.copyOf(reasons));
    }

    private void evaluateObservedAt(Instant observedAt, List<GateResult> gates, List<String> stops) {
        if (observedAt == null) block("SNAPSHOT_TIME", "OBSERVED_AT_MISSING", gates, stops);
        else clear("SNAPSHOT_TIME", observedAt.toString(), gates);
    }

    private void evaluateSnapshotTtl(Duration ttl, List<GateResult> gates, List<String> stops) {
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            block("SNAPSHOT_TTL", "SNAPSHOT_TTL_MISSING_OR_INVALID", gates, stops);
        } else {
            clear("SNAPSHOT_TTL", ttl.toString(), gates);
        }
    }

    private void evaluateIdentity(CohortIdentity expected, CohortIdentity observed, Instant observedAt,
                                  List<GateResult> gates, List<String> stops) {
        if (expected == null || observed == null) {
            block("COHORT_IDENTITY", "COHORT_IDENTITY_MISSING", gates, stops);
            return;
        }
        String invalid = invalidIdentity(expected);
        if (invalid == null) invalid = invalidIdentity(observed);
        if (invalid != null) {
            block("COHORT_IDENTITY", invalid, gates, stops);
        } else if (!expected.equals(observed)) {
            block("COHORT_IDENTITY", "COHORT_IDENTITY_MISMATCH", gates, stops);
        } else if (!isTinyLiveMode(observed.executionMode())) {
            block("COHORT_IDENTITY", "EXECUTION_MODE_NOT_TINY_LIVE", gates, stops);
        } else if (observedAt != null && observed.effectiveFrom().isAfter(observedAt)) {
            block("COHORT_IDENTITY", "EFFECTIVE_FROM_IN_FUTURE", gates, stops);
        } else {
            clear("COHORT_IDENTITY", observed.cohortId(), gates);
        }
    }

    private String invalidIdentity(CohortIdentity identity) {
        if (!safeId(identity.cohortId())) return "COHORT_ID_INVALID";
        if (!matches(COMMIT, identity.codeCommit())) return "CODE_COMMIT_INVALID";
        if (!matches(SHA256, identity.configSha256())) return "CONFIG_SHA256_INVALID";
        if (!safeId(identity.modelVersion())) return "MODEL_VERSION_INVALID";
        if (!safeId(identity.signalSource())) return "SIGNAL_SOURCE_INVALID";
        if (!safeId(identity.executionMode())) return "EXECUTION_MODE_INVALID";
        if (identity.strategyId() <= 0) return "STRATEGY_ID_INVALID";
        if (!safeId(identity.strategyFamily())) return "STRATEGY_FAMILY_INVALID";
        if (!safeId(identity.symbol())) return "SYMBOL_INVALID";
        if (identity.effectiveFrom() == null) return "EFFECTIVE_FROM_MISSING";
        return null;
    }

    private void evaluateCandidate(Candidate candidate, Instant observedAt,
                                   List<GateResult> gates, List<String> stops, List<String> waits) {
        if (candidate == null) {
            block("CANDIDATE", "CANDIDATE_INPUT_MISSING", gates, stops);
            return;
        }
        if (!candidate.present()) {
            waitGate("CANDIDATE", "NO_VALID_CANDIDATE", gates, waits);
            return;
        }
        if (!safeId(candidate.stableOpportunityId())) {
            block("CANDIDATE", "STABLE_OPPORTUNITY_ID_MISSING_OR_INVALID", gates, stops);
            return;
        }
        if (!candidate.closedBar()) {
            block("CANDIDATE", "CANDIDATE_BAR_NOT_CLOSED", gates, stops);
            return;
        }
        String freshnessFailure = freshnessFailure(candidate.signalAt(), candidate.maxAge(), observedAt, "SIGNAL");
        if (freshnessFailure == null) {
            clear("CANDIDATE", candidate.stableOpportunityId(), gates);
        } else {
            block("CANDIDATE", freshnessFailure, gates, stops);
        }
    }

    private void evaluateTimedGate(String name, TimedGate gate, Instant observedAt,
                                   List<GateResult> gates, List<String> stops) {
        if (gate == null) {
            block(name, name + "_INPUT_MISSING", gates, stops);
            return;
        }
        if (gate.state() == null || gate.state() == GateState.UNKNOWN) {
            block(name, name + "_UNKNOWN", gates, stops);
            return;
        }
        String freshnessFailure = freshnessFailure(gate.sourceAt(), gate.maxAge(), observedAt, name);
        if (freshnessFailure != null) {
            block(name, freshnessFailure, gates, stops);
        } else if (gate.state() == GateState.BLOCK) {
            block(name, name + "_BLOCKED", gates, stops);
        } else {
            clear(name, gate.detail(), gates);
        }
    }

    private void evaluateLimits(Limits limits, List<GateResult> gates, List<String> stops) {
        if (limits == null) {
            block("LIMITS", "LIMITS_INPUT_MISSING", gates, stops);
            return;
        }
        if (invalidDecimal(limits.orderNotional()) || invalidDecimal(limits.minimumOrderNotional())) {
            block("MINIMUM_ORDER", "ORDER_SIZE_MISSING_MALFORMED_OR_OVERFLOW", gates, stops);
        } else if (limits.orderNotional().compareTo(limits.minimumOrderNotional()) < 0 || !limits.lotValid()) {
            block("MINIMUM_ORDER", "MINIMUM_ORDER_OR_LOT_BLOCKED", gates, stops);
        } else clear("MINIMUM_ORDER", limits.orderNotional().toPlainString(), gates);

        counterGate("DAILY_ORDER_CAP", limits.ordersToday(), limits.maxOrdersPerDay(), gates, stops);
        counterGate("OPEN_POSITION_CAP", limits.openPositions(), limits.maxOpenPositions(), gates, stops);
        if (limits.duplicateOpportunity()) block("DUPLICATE_OPPORTUNITY", "STABLE_OPPORTUNITY_DUPLICATE", gates, stops);
        else clear("DUPLICATE_OPPORTUNITY", "CLEAR", gates);

        if (limits.currentCohortClosedEpisodes() < 0 || limits.sequentialLosses() < 0
                || limits.maxSequentialLosses() <= 0
                || limits.sequentialLosses() > limits.currentCohortClosedEpisodes()) {
            block("SEQUENTIAL_LOSS", "CURRENT_COHORT_LOSS_INPUT_MALFORMED", gates, stops);
        } else if (limits.sequentialLosses() >= limits.maxSequentialLosses()) {
            block("SEQUENTIAL_LOSS", "CURRENT_COHORT_SEQUENTIAL_LOSS_CAP_REACHED", gates, stops);
        } else {
            // Zero closed episodes is intentionally clear when every safety gate is clear.
            clear("SEQUENTIAL_LOSS", limits.sequentialLosses() + "/" + limits.maxSequentialLosses(), gates);
        }
    }

    private void counterGate(String name, long used, long cap,
                             List<GateResult> gates, List<String> stops) {
        if (used < 0 || cap <= 0) block(name, name + "_MALFORMED_OR_OVERFLOW", gates, stops);
        else if (used >= cap) block(name, name + "_REACHED", gates, stops);
        else clear(name, used + "/" + cap, gates);
    }

    private void evaluateExposure(Exposure exposure, List<GateResult> gates, List<String> stops) {
        if (exposure == null) {
            block("EXPOSURE", "EXPOSURE_INPUT_MISSING", gates, stops);
            return;
        }
        BigDecimal[] all = {exposure.grid(), exposure.livePosition(), exposure.fundingArbitrage(),
                exposure.btcBase(), exposure.proposed(), exposure.cap()};
        for (BigDecimal value : all) {
            if (invalidDecimal(value)) {
                block("EXPOSURE", "EXPOSURE_MISSING_MALFORMED_OR_OVERFLOW", gates, stops);
                return;
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < all.length - 1; i++) total = total.add(all[i]);
        if (invalidDecimal(total)) {
            block("EXPOSURE", "EXPOSURE_ARITHMETIC_OVERFLOW", gates, stops);
        } else if (total.compareTo(exposure.cap()) > 0) {
            block("EXPOSURE", "CROSS_STRATEGY_EXPOSURE_CAP_BREACHED", gates, stops);
        } else {
            clear("EXPOSURE", total.toPlainString() + "/" + exposure.cap().toPlainString(), gates);
        }
    }

    private OcoPolicy evaluateOco(Oco oco, CohortIdentity identity, Instant observedAt, List<GateResult> gates,
                                  List<String> stops, List<String> waits) {
        if (oco == null || oco.policy() == null) {
            block("OCO_POLICY", "OCO_POLICY_MISSING_OR_UNKNOWN", gates, stops);
            return null;
        }
        if (oco.policy() == OcoPolicy.NO_ORDER) {
            if (identity == null || !"BTC_BASE_DRY_RUN".equals(normalizeDetail(identity.executionMode()))) {
                block("OCO_POLICY", "NO_ORDER_POLICY_EXECUTION_MODE_MISMATCH", gates, stops);
            } else {
                waitGate("OCO_POLICY", "NO_ORDER_EXECUTION_POLICY", gates, waits);
            }
            return oco.policy();
        }
        if (identity != null && "BTC_BASE_DRY_RUN".equals(normalizeDetail(identity.executionMode()))) {
            block("OCO_POLICY", "BTC_BASE_DRY_RUN_REQUIRES_NO_ORDER_POLICY", gates, stops);
            return oco.policy();
        }
        if (oco.policy() == OcoPolicy.NOT_REQUIRED) {
            if (!oco.explicitNoOcoPolicy()) block("OCO_POLICY", "NO_OCO_POLICY_NOT_EXPLICIT", gates, stops);
            else clear("OCO_POLICY", "EXPLICIT_NO_OCO", gates);
            return oco.policy();
        }
        if (!oco.identityMatches()) {
            block("OCO_POLICY", "OCO_IDENTITY_MISMATCH", gates, stops);
            return oco.policy();
        }
        evaluateTimedGate("OCO_FEASIBILITY", oco.feasibility(), observedAt, gates, stops);
        evaluateTimedGate("OCO_HEALTH", oco.health(), observedAt, gates, stops);
        return oco.policy();
    }

    private String freshnessFailure(Instant sourceAt, Duration maxAge, Instant observedAt, String prefix) {
        if (sourceAt == null) return prefix + "_SOURCE_TIME_MISSING";
        if (observedAt == null) return prefix + "_OBSERVED_AT_MISSING";
        if (maxAge == null || maxAge.isZero() || maxAge.isNegative()) return prefix + "_MAX_AGE_INVALID";
        if (sourceAt.isAfter(observedAt)) return prefix + "_SOURCE_TIME_IN_FUTURE";
        if (Duration.between(sourceAt, observedAt).compareTo(maxAge) > 0) return prefix + "_STALE";
        return null;
    }

    private Snapshot terminalSnapshot(Inputs inputs, Decision decision, List<String> reasons) {
        return buildSnapshot(inputs, decision,
                List.of(new GateResult("INPUTS", GateState.BLOCK, reasons.get(0))), reasons);
    }

    private Snapshot buildSnapshot(Inputs inputs, Decision decision, List<GateResult> gates, List<String> reasons) {
        Instant observedAt = inputs == null ? null : inputs.observedAt();
        Duration ttl = inputs == null ? null : inputs.snapshotTtl();
        Instant expiresAt = observedAt != null && ttl != null && !ttl.isNegative() && !ttl.isZero()
                ? observedAt.plus(ttl) : null;
        List<GateResult> immutableGates = gates.stream()
                .sorted(Comparator.comparing(GateResult::gate))
                .toList();
        List<String> immutableReasons = reasons.stream().distinct().sorted().toList();
        String canonical = canonicalInput(inputs, decision, immutableGates, immutableReasons, expiresAt);
        String hash = sha256(canonical);
        String snapshotId = "VPSHG1-" + hash.substring(0, 20).toUpperCase(Locale.ROOT);
        return new Snapshot(CONTRACT_VERSION, IMPLEMENTATION_ID, snapshotId, decision, observedAt, expiresAt,
                inputs, immutableGates, immutableReasons, canonical, hash, true);
    }

    private String canonicalInput(Inputs inputs, Decision decision, List<GateResult> gates,
                                  List<String> reasons, Instant expiresAt) {
        return "contract=" + CONTRACT_VERSION
                + "|implementation=" + IMPLEMENTATION_ID
                + "|decision=" + decision
                + "|expiresAt=" + text(expiresAt)
                + "|inputs=" + text(inputs)
                + "|gates=" + text(gates)
                + "|reasons=" + text(reasons);
    }

    private void clear(String gate, String detail, List<GateResult> gates) {
        gates.add(new GateResult(gate, GateState.CLEAR, normalizeDetail(detail)));
    }

    private void block(String gate, String reason, List<GateResult> gates, List<String> stops) {
        gates.add(new GateResult(gate, GateState.BLOCK, reason));
        stops.add(reason);
    }

    private void waitGate(String gate, String reason, List<GateResult> gates, List<String> waits) {
        gates.add(new GateResult(gate, GateState.CLEAR, reason));
        waits.add(reason);
    }

    private boolean invalidDecimal(BigDecimal value) {
        if (value == null || value.signum() < 0 || value.precision() > MAX_DECIMAL_PRECISION) {
            return true;
        }
        // BigDecimal precision alone does not bound negative-scale values: 1E+1000 has
        // precision 1 but 1001 integer digits. Use long arithmetic so an extreme int scale
        // cannot overflow the domain check itself.
        long integerDigits = Math.max(0L, (long) value.precision() - value.scale());
        return integerDigits > MAX_DECIMAL_INTEGER_DIGITS;
    }

    private boolean isTinyLiveMode(String value) {
        String mode = normalizeDetail(value);
        return "LIVE_MICRO".equals(mode) || "BTC_BASE_LIVE_MICRO".equals(mode)
                || "BTC_BASE_DRY_RUN".equals(mode);
    }

    private boolean safeId(String value) {
        return value != null && SAFE_ID.matcher(value.trim().toUpperCase(Locale.ROOT)).matches();
    }

    private boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value.trim().toLowerCase(Locale.ROOT)).matches();
    }

    private String normalizeDetail(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private String text(Object value) {
        if (value == null) return "<null>";
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        return String.valueOf(value);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public enum Decision { READY, WAIT_MARKET, STOP }
    public enum GateState { CLEAR, BLOCK, UNKNOWN }
    public enum OcoPolicy { REQUIRED, NOT_REQUIRED, NO_ORDER }

    public record CohortIdentity(String cohortId, String codeCommit, String configSha256,
                                 String modelVersion, String signalSource, String executionMode,
                                 long strategyId, String strategyFamily, String symbol,
                                 Instant effectiveFrom) { }

    public record Candidate(boolean present, String stableOpportunityId, boolean closedBar,
                            Instant signalAt, Duration maxAge) { }

    public record TimedGate(GateState state, Instant sourceAt, Duration maxAge, String detail) { }

    public record Limits(BigDecimal orderNotional, BigDecimal minimumOrderNotional, boolean lotValid,
                         long ordersToday, long maxOrdersPerDay,
                         long openPositions, long maxOpenPositions,
                         boolean duplicateOpportunity,
                         long currentCohortClosedEpisodes, long sequentialLosses,
                         long maxSequentialLosses) { }

    public record Exposure(BigDecimal grid, BigDecimal livePosition, BigDecimal fundingArbitrage,
                           BigDecimal btcBase, BigDecimal proposed, BigDecimal cap) { }

    public record Oco(OcoPolicy policy, boolean explicitNoOcoPolicy, boolean identityMatches,
                      TimedGate feasibility, TimedGate health) { }

    public record Inputs(Instant observedAt, Duration snapshotTtl,
                         CohortIdentity expectedIdentity, CohortIdentity observedIdentity,
                         Candidate candidate, TimedGate primaryTrend1h, TimedGate primaryTrend4h,
                         TimedGate eventRisk, Limits limits, Exposure exposure, Oco oco,
                         TimedGate runtimeWriter) { }

    public record GateResult(String gate, GateState state, String detail) { }

    public record Snapshot(String contractVersion, String implementationId, String snapshotId, Decision decision,
                           Instant observedAt, Instant expiresAt, Inputs inputs,
                           List<GateResult> gates, List<String> reasons,
                           String deterministicHashInput, String sha256,
                           boolean readOnly) { }

    public record BoundaryDecision(boolean allowed, Snapshot currentSnapshot, List<String> reasons) { }
}
