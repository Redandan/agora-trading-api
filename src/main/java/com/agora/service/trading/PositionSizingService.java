package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

@Service
public class PositionSizingService {

    private final OkxTradingProperties props;

    public PositionSizingService(OkxTradingProperties props) {
        this.props = props;
    }

    public PositionSizingDecision calculate(String symbol,
                                            Long strategyId,
                                            BigDecimal entry,
                                            BigDecimal tp,
                                            BigDecimal sl,
                                            double nnOutput,
                                            double legacyAmountUsdt,
                                            Double availableUsdt) {
        List<String> reasons = new ArrayList<>();
        double min = sanePositive(props.getPositionSizingMinNotionalUsdt(), 50.0);
        double max = Math.max(min, sanePositive(props.getPositionSizingMaxNotionalUsdt(), 150.0));
        double hardRisk = sanePositive(props.getPositionSizingHardMaxRiskUsdt(), 5.0);
        double floorMaxRisk = sanePositive(props.getPositionSizingMinNotionalFloorMaxRiskUsdt(), hardRisk);
        double freeBuffer = Math.max(0.0, props.getPositionSizingFreeUsdtBuffer());

        double entryPx = positive(entry);
        double slPx = positive(sl);
        double tpPx = positive(tp);
        if (entryPx <= 0 || slPx <= 0 || slPx == entryPx) {
            return new PositionSizingDecision(symbol, strategyId, legacyAmountUsdt, legacyAmountUsdt,
                    legacyAmountUsdt, 0.0, 0.0, 0.0, 0.0, availableUsdt,
                    min, false, true, false, "INVALID_SL_OR_ENTRY",
                    "missing valid entry/sl; keep legacy amount");
        }

        double slDistancePct = Math.abs(entryPx - slPx) / entryPx;
        double tpDistancePct = tpPx > 0 ? Math.abs(tpPx - entryPx) / entryPx : 0.0;
        double rr = slDistancePct > 0 ? tpDistancePct / slDistancePct : 0.0;
        double quality = qualityMultiplier(nnOutput, rr);
        double riskBudget = hardRisk * quality;
        double raw = riskBudget / slDistancePct;
        double recommended = raw;
        boolean belowMinNotional = recommended < min;
        boolean floorApplied = false;

        if (recommended > max) {
            recommended = max;
            reasons.add("max_notional_cap");
        }
        Double spendableUsdt = null;
        if (availableUsdt != null) {
            spendableUsdt = Math.max(0.0, availableUsdt - freeBuffer);
            if (recommended > spendableUsdt) {
                recommended = spendableUsdt;
                reasons.add("free_usdt_buffer_cap");
            }
        }
        recommended = Math.max(0.0, round2(recommended));
        if (recommended < min) {
            double floorRisk = min * slDistancePct;
            boolean hasSpendableFloor = spendableUsdt == null || spendableUsdt >= min;
            if (props.isPositionSizingMinNotionalFloorEnabled() && hasSpendableFloor && floorRisk <= floorMaxRisk) {
                recommended = min;
                belowMinNotional = false;
                floorApplied = true;
                reasons.add("min_notional_floor_applied");
            } else {
                belowMinNotional = true;
                if (props.isPositionSizingMinNotionalFloorEnabled() && !hasSpendableFloor) {
                    reasons.add("min_notional_floor_insufficient_spendable_usdt");
                } else if (props.isPositionSizingMinNotionalFloorEnabled() && floorRisk > floorMaxRisk) {
                    reasons.add("min_notional_floor_risk_too_high");
                }
                reasons.add("below_min_notional_skip");
            }
        }

        boolean live = props.isPositionSizingLiveEnabled();
        boolean liveEntryAllowed = !belowMinNotional;
        double finalAmount = live ? (liveEntryAllowed ? recommended : 0.0) : legacyAmountUsdt;
        if (!live) {
            reasons.add("shadow_only_live_amount_unchanged");
        }
        if (reasons.isEmpty()) {
            reasons.add("risk_sized");
        }

        return new PositionSizingDecision(symbol, strategyId, legacyAmountUsdt, recommended, finalAmount,
                slDistancePct, tpDistancePct, rr, riskBudget, availableUsdt, min,
                belowMinNotional, liveEntryAllowed, live,
                String.join(",", reasons),
                buildExplain(legacyAmountUsdt, raw, recommended, slDistancePct, riskBudget, rr, live, floorApplied));
    }

    private static String buildExplain(double legacy, double raw, double recommended, double slDistancePct,
                                       double riskBudget, double rr, boolean live, boolean floorApplied) {
        return String.format("legacy=%.2f rawRiskSized=%.2f recommended=%.2f slDistance=%.2f%% riskBudget=%.2fUSDT rr=%.2f mode=%s floorApplied=%s",
                legacy, raw, recommended, slDistancePct * 100.0, riskBudget, rr, live ? "LIVE" : "SHADOW", floorApplied);
    }

    private static double qualityMultiplier(double nnOutput, double rr) {
        double nnPart = nnOutput >= 0.90 ? 1.0 : nnOutput >= 0.85 ? 0.75 : 0.50;
        double rrPart = rr >= 2.0 ? 1.0 : rr >= 1.3 ? 0.85 : 0.65;
        return Math.min(nnPart, rrPart);
    }

    private static double sanePositive(double value, double fallback) {
        return value > 0 && Double.isFinite(value) ? value : fallback;
    }

    private static double positive(BigDecimal value) {
        return value == null ? 0.0 : Math.max(0.0, value.doubleValue());
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    public record PositionSizingDecision(
            String symbol,
            Long strategyId,
            double legacyAmountUsdt,
            double recommendedAmountUsdt,
            double finalAmountUsdt,
            double slDistancePct,
            double tpDistancePct,
            double riskReward,
            double riskBudgetUsdt,
            Double availableUsdt,
            double minNotionalUsdt,
            boolean belowMinNotional,
            boolean liveEntryAllowed,
            boolean liveEnabled,
            String reason,
            String explain
    ) {
        public String tgLine() {
            return String.format(
                    "📐 Sizing: %s %.2f USDT (legacy %.2f, shadow %.2f) | SL %.2f%% | risk %.2f USDT | %s",
                    liveEnabled ? (liveEntryAllowed ? "LIVE" : "SKIP") : "SHADOW",
                    finalAmountUsdt,
                    legacyAmountUsdt,
                    recommendedAmountUsdt,
                    slDistancePct * 100.0,
                    riskBudgetUsdt,
                    reason);
        }
    }
}
