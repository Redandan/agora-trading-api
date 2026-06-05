package com.agora.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.regex.Pattern;

@ConfigurationProperties(prefix = "meta-control.ml.sql")
public record MlSqlProperties(
        @DefaultValue("agora_trading") String schema,
        @DefaultValue("bt_signal_training_v8_mat") String signalScorerTrainingTable,
        @DefaultValue("vw_signal_training_v2") String weeklyRetrainTrainingView
) {
    private static final Pattern SQL_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public MlSqlProperties {
        schema = validateIdentifier("schema", schema);
        signalScorerTrainingTable = validateIdentifier("signalScorerTrainingTable", signalScorerTrainingTable);
        weeklyRetrainTrainingView = validateIdentifier("weeklyRetrainTrainingView", weeklyRetrainTrainingView);
    }

    public String signalScorerTrainingTableName() {
        return qualified(signalScorerTrainingTable);
    }

    public String weeklyRetrainTrainingViewName() {
        return qualified(weeklyRetrainTrainingView);
    }

    public String tempTable(String prefix, Object id, long suffix) {
        return qualified(prefix + id + "_" + suffix);
    }

    public String snapshotTable(String modelName, int version) {
        return qualified("_ml_snapshot_" + sanitizeIdentifier(modelName) + "_v" + version);
    }

    private String qualified(String objectName) {
        return schema + "." + validateIdentifier("objectName", objectName);
    }

    private static String sanitizeIdentifier(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String validateIdentifier(String label, String value) {
        String normalized = value == null ? "" : value.trim();
        if (!SQL_IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Invalid ML SQL " + label + ": " + value);
        }
        return normalized;
    }
}
