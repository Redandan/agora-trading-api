package com.agora.service.trading;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ProbePositionExecutorDryRunService {

    private static final BigDecimal PROBE_NOTIONAL = new BigDecimal("5.00");
    private static final BigDecimal SMALL_NOTIONAL = new BigDecimal("10.00");

    private final ObjectMapper objectMapper;

    public Plan preview(PreviewInput input) {
        PreviewInput normalized = input.normalized();
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("version", "v0");
        preview.put("mode", "DRY_RUN_ONLY");
        preview.put("symbol", normalized.symbol());
        preview.put("strategyId", normalized.strategyId());
        preview.put("side", normalized.side());
        preview.put("policyMode", normalized.policyMode());
        preview.put("qualityScore", normalized.qualityScore());
        preview.put("tqsBand", normalized.tqsBand());
        preview.put("expectedR", normalized.expectedR());
        preview.put("autonomousExecutionEnabled", false);
        preview.put("probePositionExecutorEnabled", false);
        preview.put("orderSent", false);
        preview.put("writesRuntimeEvidence", false);
        preview.put("telegramSent", false);

        String suppression = suppressionReason(normalized);
        if (suppression != null) {
            preview.put("status", "SUPPRESSED");
            preview.put("probeNotionalUsdt", BigDecimal.ZERO);
            preview.put("executionPreview", "NO_EXECUTION_PREVIEW");
            preview.put("entryPlan", Map.of("status", "NOT_CREATED"));
            preview.put("ocoPlan", Map.of("status", "NOT_CREATED"));
            preview.put("maxLossUsdt", BigDecimal.ZERO);
            preview.put("estimatedRiskReward", BigDecimal.ZERO);
            preview.put("capitalCapCheck", capitalCapCheck(normalized, BigDecimal.ZERO));
            preview.put("executionSuppressionReason", suppression);
            return plan("DRY_RUN_ONLY", BigDecimal.ZERO, preview, BigDecimal.ZERO, BigDecimal.ZERO,
                    String.valueOf(preview.get("capitalCapCheck")), suppression);
        }

        if ("ALLOW_RISK_REDUCING_ONLY".equals(normalized.policyMode())) {
            preview.put("status", "PROTECTION_PREVIEW_ONLY");
            preview.put("probeNotionalUsdt", BigDecimal.ZERO);
            preview.put("executionPreview", "PROTECTION_ADJUSTMENT_PREVIEW_ONLY");
            preview.put("entryPlan", Map.of("status", "NOT_CREATED"));
            preview.put("ocoPlan", ocoPlan(normalized));
            preview.put("maxLossUsdt", BigDecimal.ZERO);
            preview.put("estimatedRiskReward", BigDecimal.ZERO);
            preview.put("capitalCapCheck", "RISK_REDUCING_ONLY");
            preview.put("executionSuppressionReason", "risk reducing only; additive entry disabled");
            return plan("DRY_RUN_ONLY", BigDecimal.ZERO, preview, BigDecimal.ZERO, BigDecimal.ZERO,
                    "RISK_REDUCING_ONLY", "risk reducing only; additive entry disabled");
        }

        BigDecimal notional = notionalFor(normalized.policyMode());
        BigDecimal maxLoss = maxLoss(normalized, notional);
        BigDecimal riskReward = riskReward(normalized);
        String capitalCapCheck = capitalCapCheck(normalized, notional);
        if (!"PASS".equals(capitalCapCheck)) {
            preview.put("status", "SUPPRESSED");
            preview.put("probeNotionalUsdt", BigDecimal.ZERO);
            preview.put("executionPreview", "NO_EXECUTION_PREVIEW");
            preview.put("entryPlan", Map.of("status", "NOT_CREATED"));
            preview.put("ocoPlan", Map.of("status", "NOT_CREATED"));
            preview.put("maxLossUsdt", BigDecimal.ZERO);
            preview.put("estimatedRiskReward", BigDecimal.ZERO);
            preview.put("capitalCapCheck", capitalCapCheck);
            preview.put("executionSuppressionReason", "capital cap check failed");
            return plan("DRY_RUN_ONLY", BigDecimal.ZERO, preview, BigDecimal.ZERO, BigDecimal.ZERO,
                    capitalCapCheck, "capital cap check failed");
        }

        preview.put("status", "PREVIEW_CREATED");
        preview.put("probeNotionalUsdt", notional);
        preview.put("executionPreview", "DRY_RUN_ENTRY_PREVIEW");
        preview.put("entryPlan", entryPlan(normalized, notional));
        preview.put("ocoPlan", ocoPlan(normalized));
        preview.put("maxLossUsdt", maxLoss);
        preview.put("estimatedRiskReward", riskReward);
        preview.put("capitalCapCheck", capitalCapCheck);
        preview.put("executionSuppressionReason", "autonomous execution disabled");
        return plan("DRY_RUN_ONLY", notional, preview, maxLoss, riskReward, capitalCapCheck,
                "autonomous execution disabled");
    }

    public String previewJson(PreviewInput input) {
        return preview(input).executionPreviewJson();
    }

    private String suppressionReason(PreviewInput input) {
        if (input.duplicateBar()) return "duplicate bar terminal blocker";
        if (input.dataFreshnessHardFail()) return "data freshness hard fail";
        if (input.dailyBreaker()) return "daily breaker active";
        if (input.exposureCapHit()) return "exposure cap hit";
        String mode = input.policyMode();
        if ("BLOCK".equals(mode)) return "policy BLOCK";
        if (!input.ocoHealthy()) return "OCO health is not clean";
        if (!input.ocoCapable()) return "OCO-capable path is not proven";
        if ("READ_ONLY".equals(mode)) return "policy READ_ONLY";
        if (!"ALLOW_PROBE_ENTRY_DRY_RUN".equals(mode)
                && !"ALLOW_SMALL_ENTRY_DRY_RUN".equals(mode)
                && !"ALLOW_RISK_REDUCING_ONLY".equals(mode)) {
            return "policy does not permit probe executor preview";
        }
        if (requiresEntryPrices(mode) && (input.entryPrice() == null || input.tpPrice() == null || input.slPrice() == null)) {
            return "missing entry/tp/sl price";
        }
        return null;
    }

    private boolean requiresEntryPrices(String policyMode) {
        return "ALLOW_PROBE_ENTRY_DRY_RUN".equals(policyMode) || "ALLOW_SMALL_ENTRY_DRY_RUN".equals(policyMode);
    }

    private BigDecimal notionalFor(String policyMode) {
        if ("ALLOW_SMALL_ENTRY_DRY_RUN".equals(policyMode)) {
            return SMALL_NOTIONAL;
        }
        if ("ALLOW_PROBE_ENTRY_DRY_RUN".equals(policyMode)) {
            return PROBE_NOTIONAL;
        }
        return BigDecimal.ZERO;
    }

    private String capitalCapCheck(PreviewInput input, BigDecimal notional) {
        if (input.exposureCapHit()) return "BLOCKED_EXPOSURE_CAP";
        if (input.availableUsdt() == null) return "UNKNOWN_AVAILABLE_USDT";
        return input.availableUsdt().compareTo(notional) >= 0 ? "PASS" : "BLOCKED_INSUFFICIENT_USDT";
    }

    private Map<String, Object> entryPlan(PreviewInput input, BigDecimal notional) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "DRY_RUN_ONLY");
        out.put("symbol", input.symbol());
        out.put("side", input.side());
        out.put("notionalUsdt", notional);
        out.put("entryPrice", input.entryPrice());
        out.put("estimatedQty", input.entryPrice().compareTo(BigDecimal.ZERO) > 0
                ? notional.divide(input.entryPrice(), 10, RoundingMode.DOWN)
                : BigDecimal.ZERO);
        return out;
    }

    private Map<String, Object> ocoPlan(PreviewInput input) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", input.ocoCapable() && input.ocoHealthy() ? "DRY_RUN_ONLY" : "NOT_CREATED");
        out.put("ocoCapable", input.ocoCapable());
        out.put("ocoHealthy", input.ocoHealthy());
        out.put("tpPrice", input.tpPrice());
        out.put("slPrice", input.slPrice());
        return out;
    }

    private BigDecimal maxLoss(PreviewInput input, BigDecimal notional) {
        if (notional.compareTo(BigDecimal.ZERO) <= 0 || input.entryPrice() == null || input.slPrice() == null
                || input.entryPrice().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal lossRatio = input.entryPrice().subtract(input.slPrice()).abs()
                .divide(input.entryPrice(), 10, RoundingMode.HALF_UP);
        return notional.multiply(lossRatio).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal riskReward(PreviewInput input) {
        if (input.entryPrice() == null || input.tpPrice() == null || input.slPrice() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal risk = input.entryPrice().subtract(input.slPrice()).abs();
        if (risk.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        return input.tpPrice().subtract(input.entryPrice()).abs()
                .divide(risk, 4, RoundingMode.HALF_UP);
    }

    private Plan plan(String executionMode,
                      BigDecimal probeNotionalUsdt,
                      Map<String, Object> preview,
                      BigDecimal maxLossUsdt,
                      BigDecimal riskReward,
                      String capitalCapCheck,
                      String executionSuppressionReason) {
        return new Plan(
                executionMode,
                probeNotionalUsdt,
                toJson(preview),
                maxLossUsdt,
                riskReward,
                capitalCapCheck,
                executionSuppressionReason);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"version\":\"v0\",\"mode\":\"DRY_RUN_ONLY\",\"status\":\"SERIALIZATION_ERROR\",\"orderSent\":false}";
        }
    }

    public record Plan(String executionMode,
                       BigDecimal probeNotionalUsdt,
                       String executionPreviewJson,
                       BigDecimal maxLossUsdt,
                       BigDecimal riskReward,
                       String capitalCapCheck,
                       String executionSuppressionReason) {
    }

    public record PreviewInput(String symbol,
                               Long strategyId,
                               String side,
                               String policyMode,
                               Integer qualityScore,
                               String tqsBand,
                               Double expectedR,
                               Boolean exposureCapHit,
                               Boolean ocoCapable,
                               Boolean ocoHealthy,
                               Boolean dailyBreaker,
                               Boolean duplicateBar,
                               Boolean dataFreshnessHardFail,
                               BigDecimal entryPrice,
                               BigDecimal tpPrice,
                               BigDecimal slPrice,
                               BigDecimal availableUsdt) {
        PreviewInput normalized() {
            return new PreviewInput(
                    symbol == null || symbol.isBlank() ? "BTCUSDT" : symbol.trim().toUpperCase(Locale.ROOT),
                    strategyId == null ? 574L : strategyId,
                    side == null || side.isBlank() ? "LONG" : side.trim().toUpperCase(Locale.ROOT),
                    policyMode == null || policyMode.isBlank() ? "READ_ONLY" : policyMode.trim().toUpperCase(Locale.ROOT),
                    qualityScore == null ? 0 : qualityScore,
                    tqsBand == null ? "" : tqsBand.trim().toUpperCase(Locale.ROOT),
                    expectedR == null ? 0.0 : expectedR,
                    Boolean.TRUE.equals(exposureCapHit),
                    Boolean.TRUE.equals(ocoCapable),
                    Boolean.TRUE.equals(ocoHealthy),
                    Boolean.TRUE.equals(dailyBreaker),
                    Boolean.TRUE.equals(duplicateBar),
                    Boolean.TRUE.equals(dataFreshnessHardFail),
                    entryPrice,
                    tpPrice,
                    slPrice,
                    availableUsdt);
        }
    }
}
