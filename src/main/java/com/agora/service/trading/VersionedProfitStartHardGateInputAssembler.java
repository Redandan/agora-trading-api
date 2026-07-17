package com.agora.service.trading;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Builds hard-gate inputs from an explicit observed runtime packet. */
@Service
public class VersionedProfitStartHardGateInputAssembler {

    public VersionedProfitStartHardGateSnapshotService.Inputs assemble(
            VersionedProfitStartCohortService.Snapshot cohort,
            RuntimeInputs runtime) {
        if (cohort == null || !cohort.identityReady() || runtime == null) {
            return null;
        }
        return new VersionedProfitStartHardGateSnapshotService.Inputs(
                runtime.observedAt(), runtime.snapshotTtl(), identity(cohort), runtime.observedIdentity(),
                runtime.candidate(), runtime.primaryTrend1h(), runtime.primaryTrend4h(), runtime.eventRisk(),
                runtime.limits(), runtime.exposure(), runtime.oco(), runtime.runtimeWriter());
    }

    public VersionedProfitStartHardGateSnapshotService.Inputs assemble(
            VersionedProfitStartCohortService.Snapshot cohort,
            Map<String, Object> context) {
        Object packet = context == null ? null : context.get("versionedProfitStartHardGateRuntimeInputs");
        if (packet instanceof RuntimeInputProvider provider) {
            packet = provider.current();
        }
        return packet instanceof RuntimeInputs runtime ? assemble(cohort, runtime) : null;
    }

    public VersionedProfitStartHardGateSnapshotService.CohortIdentity identity(
            VersionedProfitStartCohortService.Snapshot cohort) {
        if (cohort == null) return null;
        return new VersionedProfitStartHardGateSnapshotService.CohortIdentity(
                cohort.cohortId(), cohort.codeCommit(), cohort.configSha256(), cohort.modelVersion(),
                cohort.signalSource(), cohort.executionMode(), cohort.strategyId(), cohort.strategyFamily(),
                cohort.symbol(), cohort.effectiveFrom());
    }

    public record RuntimeInputs(
            Instant observedAt,
            Duration snapshotTtl,
            VersionedProfitStartHardGateSnapshotService.CohortIdentity observedIdentity,
            VersionedProfitStartHardGateSnapshotService.Candidate candidate,
            VersionedProfitStartHardGateSnapshotService.TimedGate primaryTrend1h,
            VersionedProfitStartHardGateSnapshotService.TimedGate primaryTrend4h,
            VersionedProfitStartHardGateSnapshotService.TimedGate eventRisk,
            VersionedProfitStartHardGateSnapshotService.Limits limits,
            VersionedProfitStartHardGateSnapshotService.Exposure exposure,
            VersionedProfitStartHardGateSnapshotService.Oco oco,
            VersionedProfitStartHardGateSnapshotService.TimedGate runtimeWriter) { }

    /** Allows the final order boundary to refresh volatile source inputs. */
    @FunctionalInterface
    public interface RuntimeInputProvider {
        RuntimeInputs current();
    }
}
