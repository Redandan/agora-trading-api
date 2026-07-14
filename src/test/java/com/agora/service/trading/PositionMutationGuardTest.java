package com.agora.service.trading;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class PositionMutationGuardTest {

    @Test
    void samePositionIsMutuallyExclusiveAcrossThreadsAndLeaseReleaseIsIdempotent() throws Exception {
        PositionMutationGuard.Lease adoption = PositionMutationGuard.tryAcquire(260L, "BTC_BASE_ADOPTION");
        try {
            assertThat(adoption.acquired()).isTrue();
            assertThat(PositionMutationGuard.isBusy(260L)).isTrue();
            assertThat(PositionMutationGuard.activeOperation(260L)).isEqualTo("BTC_BASE_ADOPTION");

            Boolean trailingAcquired = CompletableFuture.supplyAsync(() -> {
                try (PositionMutationGuard.Lease trailing =
                             PositionMutationGuard.tryAcquire(260L, "TRAILING_STOP")) {
                    assertThat(trailing.activeOperation()).isEqualTo("BTC_BASE_ADOPTION");
                    return trailing.acquired();
                }
            }).get(5, TimeUnit.SECONDS);
            assertThat(trailingAcquired).isFalse();
        } finally {
            adoption.close();
            adoption.close();
        }
        assertThat(PositionMutationGuard.isBusy(260L)).isFalse();
    }

    @Test
    void differentPositionsCanProceedIndependently() {
        try (PositionMutationGuard.Lease first = PositionMutationGuard.tryAcquire(260L, "A");
             PositionMutationGuard.Lease second = PositionMutationGuard.tryAcquire(261L, "B")) {
            assertThat(first.acquired()).isTrue();
            assertThat(second.acquired()).isTrue();
        }
    }

    @Test
    void sameThreadCanNestADelegatedPositionOperation() {
        try (PositionMutationGuard.Lease scheduler = PositionMutationGuard.tryAcquire(260L, "TRAILING_STOP")) {
            assertThat(scheduler.acquired()).isTrue();
            try (PositionMutationGuard.Lease oco = PositionMutationGuard.tryAcquire(260L, "OCO_MODIFY")) {
                assertThat(oco.acquired()).isTrue();
                assertThat(oco.activeOperation()).isEqualTo("TRAILING_STOP");
            }
            assertThat(PositionMutationGuard.isBusy(260L)).isTrue();
        }
        assertThat(PositionMutationGuard.isBusy(260L)).isFalse();
    }
}
