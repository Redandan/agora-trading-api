package com.agora.service.backtest;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class ExpectedValueThresholdDecimalBoundaryTest {

    @Test
    void binaryFloatingBoundaryNormalizesToContractThreshold() {
        double rawExpectedR = 0.19999990000002504;

        assertThat(rawExpectedR).isLessThan(0.2);

        LiveSignalEvaluator.ExpectedValueThresholdDecision decision =
                LiveSignalEvaluator.evaluateExpectedValueThreshold(rawExpectedR, 0.2);

        assertThat(decision.valid()).isTrue();
        assertThat(decision.passed()).isTrue();
        assertThat(decision.normalizedExpectedR()).isEqualByComparingTo("0.2000");
        assertThat(decision.normalizedMinExpectedR()).isEqualByComparingTo("0.2000");
        assertThat(decision.reason()).isEqualTo("pass");
        assertThat(LiveSignalEvaluator.EXPECTED_VALUE_DECIMAL_SCALE).isEqualTo(4);
        assertThat(LiveSignalEvaluator.EXPECTED_VALUE_ROUNDING_MODE).isEqualTo(RoundingMode.HALF_EVEN);
    }

    @Test
    void clearlyBelowThresholdRemainsBlockedAtContractScale() {
        LiveSignalEvaluator.ExpectedValueThresholdDecision decision =
                LiveSignalEvaluator.evaluateExpectedValueThreshold(0.1999, 0.2);

        assertThat(decision.valid()).isTrue();
        assertThat(decision.passed()).isFalse();
        assertThat(decision.normalizedExpectedR()).isEqualByComparingTo("0.1999");
        assertThat(decision.reason()).isEqualTo("expectedR<minExpectedR");
    }

    @Test
    void exactAndAboveThresholdPassWithoutLoweringMinimum() {
        LiveSignalEvaluator.ExpectedValueThresholdDecision exact =
                LiveSignalEvaluator.evaluateExpectedValueThreshold(0.2, 0.2);
        LiveSignalEvaluator.ExpectedValueThresholdDecision above =
                LiveSignalEvaluator.evaluateExpectedValueThreshold(0.2001, 0.2);

        assertThat(exact.passed()).isTrue();
        assertThat(above.passed()).isTrue();
        assertThat(exact.normalizedMinExpectedR()).isEqualTo(new BigDecimal("0.2000"));
        assertThat(above.normalizedMinExpectedR()).isEqualTo(new BigDecimal("0.2000"));
    }

    @Test
    void nullNonFiniteAndNegativeInputsFailClosed() {
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(null, 0.2));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(0.2, null));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(Double.NaN, 0.2));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(Double.POSITIVE_INFINITY, 0.2));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(Double.NEGATIVE_INFINITY, 0.2));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(-0.0001, 0.2));
        assertInvalid(LiveSignalEvaluator.evaluateExpectedValueThreshold(0.2, -0.0001));
    }

    private static void assertInvalid(LiveSignalEvaluator.ExpectedValueThresholdDecision decision) {
        assertThat(decision.valid()).isFalse();
        assertThat(decision.passed()).isFalse();
        assertThat(decision.normalizedExpectedR()).isNull();
        assertThat(decision.normalizedMinExpectedR()).isNull();
        assertThat(decision.reason()).isEqualTo("EXPECTED_VALUE_INPUT_INVALID");
    }
}
