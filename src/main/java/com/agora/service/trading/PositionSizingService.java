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
        double freeBuffer = Math.max(0.0, props.getPositionSizingFreeUsdtBuffer());

        double entryPx = positive(entry);
        double slPx = positive(sl);
        double tpPx = positive(tp);
        if (entryPx <= 0 || slPx <= 0 || slPx == entryPx) {
            return new PositionSizingDecision(symbol, strategyId, legacyAmountUsdt, legacyAmountUsdt,
                    legacyAmountUsdt, 0.0, 0.0, 0.0, 0.0, availableUsdt,
                    false, "INVALID_SL_OR_ENTRY", "missing valid entry/sl; keep legacy amount");
        }

        double slDistancePct = Math.abs(entryPx - slPx) / entryPx;
        double tpDistancePct = tpPx > 0 ? Math.abs(tpPx - entryPx) / entryPx : 0.0;
        double rr = slDistancePct > 0 ? tpDistancePct / slDistancePct : 0.0;
        double quality = qualityMultiplier(nnOutput, rr);
        double riskBudget = hardRisk * quality;
        double raw = riskBudget / slDistancePct;
        double recommended = raw;

        if (recommended < min) {
            recommended = min;
            reasons.add("min_notional_floor");
        }
        if (recommended > max) {
            recommended = max;
            reasons.add("max_notional_cap");
        }
        if (availableUsdt != null) {
            double spendable = Math.max(0.0, availableUsdt - freeBuffer);
            if (recommended > spendable) {
                recommended = spendable;
                reasons.add("free_usdt_buffer_cap");
            }
        }
        recommended = Math.max(0.0, round2(recommended));

        boolean live = props.isPositionSizingLiveEnabled();
        double finalAmount = live ? recommended : legacyAmountUsdt;
        if (!live) {
            reasons.add("shadow_only_live_amount_unchanged");
        }
        if (reasons.isEmpty()) {
            reasons.add("risk_sized");
        }

        return new PositionSizingDecision(symbol, strategyId, legacyAmountUsdt, recommended, finalAmount,
                slDistancePct, tpDistancePct, rr, riskBudget, availableUsdt, live,
                String.join(",", reasons), buildExplain(legacyAmountUsdt, recommended, slDistancePct, riskBudget, rr, live));
    }

    private static String buildExplain(double legacy, double recommended, double slDistancePct,
                                       double riskBudget, double rr, boolean live) {
        return String.format("legacy=%.2f recommended=%.2f slDistance=%.2f%% riskBudget=%.2fUSDT rr=%.2f mode=%s",
                legacy, recommended, slDistancePct * 100.0, riskBudget, rr, live ? "LIVE" : "SHADOW");
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
            boolean liveEnabled,
            String reason,
            String explain
    ) {
        public String tgLine() {
            return String.format(
                    "📐 Sizing: %s %.2f USDT (legacy %.2f, shadow %.2f) | SL %.2f%% | risk %.2f USDT | %s",
                    liveEnabled ? "LIVE" : "SHADOW",
                    finalAmountUsdt,
                    legacyAmountUsdt,
                    recommendedAmountUsdt,
                    slDistancePct * 100.0,
                    riskBudgetUsdt,
                    reason);
        }
    }
}
