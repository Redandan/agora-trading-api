package com.agora.config.properties;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * #378 — Daily ML pipeline digest config.
 *
 * <p>Replaces 8 @Value injections in {@code MlPipelineDigestService}.
 */
@Validated
@ConfigurationProperties(prefix = "meta-control.daily-ml-digest")
public record DailyMlDigestProperties(

        /** Drift detection short-term window (days). */
        @DefaultValue("30") @Positive int driftWindowDaysShort,

        /** Drift detection long-term window (days). */
        @DefaultValue("90") @Positive int driftWindowDaysLong,

        /** Min samples per window for verdict to be issued. */
        @DefaultValue("40") @Positive int minSamplesForVerdict,

        /** Drift alert threshold (percentage points). */
        @DefaultValue("5.0") @Positive double driftAlertPp,

        /** Promotion lift threshold (percentage points, candidate vs prod). */
        @DefaultValue("5.0") @Positive double promoteLiftPp,

        /** Min new trades since last train to trigger retrain. */
        @DefaultValue("10") @Positive int minNewTradesToTrain,

        /** Model name to digest. */
        @DefaultValue("signal_scorer") @NotBlank String modelName,

        /** Holdout window (days) for out-of-sample evaluation. */
        @DefaultValue("30") @Positive int holdoutDays
) {
}
