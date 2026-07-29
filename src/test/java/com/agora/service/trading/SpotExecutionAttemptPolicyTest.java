package com.agora.service.trading;

import com.agora.model.SpotExecutionAttempt.State;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotExecutionAttemptPolicyTest {

    private static final LocalDateTime DRA_SIGNAL_BAR =
            LocalDateTime.of(2026, 7, 26, 23, 0);

    @Test
    void firstDraSellKeepsAcceptedClientOrderId() {
        assertEquals(
                "DRA1S20260726230000",
                SpotExecutionAttemptPolicy.draSellClientOrderId(
                        DRA_SIGNAL_BAR,
                        1));
    }

    @Test
    void provenPartialGetsDistinctDeterministicSequenceId() {
        String first =
                SpotExecutionAttemptPolicy.draSellClientOrderId(
                        DRA_SIGNAL_BAR,
                        1);
        String second =
                SpotExecutionAttemptPolicy.draSellClientOrderId(
                        DRA_SIGNAL_BAR,
                        2);

        assertNotEquals(first, second);
        assertEquals("DRA1S20260726230000A002", second);
        assertTrue(second.length() <= 32);
        assertTrue(second.matches("[A-Za-z0-9]+"));
    }

    @Test
    void fillDeltaIsIdempotentAndCannotConsumeAnotherLot() {
        assertEquals(
                new BigDecimal("0.00020000"),
                SpotExecutionAttemptPolicy.unappliedFillDelta(
                        new BigDecimal("0.00030000"),
                        new BigDecimal("0.00010000"),
                        new BigDecimal("0.00035767")));
        assertEquals(
                BigDecimal.ZERO,
                SpotExecutionAttemptPolicy.unappliedFillDelta(
                        new BigDecimal("0.00030000"),
                        new BigDecimal("0.00030000"),
                        new BigDecimal("0.00015767")));
        assertEquals(
                new BigDecimal("0.00015767"),
                SpotExecutionAttemptPolicy.unappliedFillDelta(
                        new BigDecimal("0.00050000"),
                        new BigDecimal("0.00030000"),
                        new BigDecimal("0.00015767")));
    }

    @Test
    void ambiguousSubmissionCannotReturnToSubmitting() {
        assertTrue(SpotExecutionAttemptPolicy.canTransition(
                State.SUBMITTING,
                State.SUBMISSION_UNKNOWN));
        assertTrue(SpotExecutionAttemptPolicy.canTransition(
                State.SUBMISSION_UNKNOWN,
                State.PROVIDER_ACCEPTED));
        assertFalse(SpotExecutionAttemptPolicy.canTransition(
                State.SUBMISSION_UNKNOWN,
                State.SUBMITTING));
        assertFalse(SpotExecutionAttemptPolicy.canTransition(
                State.RECONCILED_PARTIAL,
                State.SUBMITTING));
    }

    @Test
    void invalidSequenceAndNegativeAccountingAreRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SpotExecutionAttemptPolicy.draSellClientOrderId(
                        DRA_SIGNAL_BAR,
                        0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpotExecutionAttemptPolicy.unappliedFillDelta(
                        new BigDecimal("-0.1"),
                        BigDecimal.ZERO,
                        BigDecimal.ONE));
    }
}
