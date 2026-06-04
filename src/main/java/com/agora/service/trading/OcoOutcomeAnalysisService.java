package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import com.agora.service.ai.AiStrategyDiscoveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * #408 — Per-position OCO outcome analysis.
 *
 * <p>Given an active OCO-protected position, compute:
 * <ul>
 *   <li>baseline first-touch probability (random-walk between SL and TP)</li>
 *   <li>regime-adjusted probability (trendDirection from MarketSnapshot)</li>
 *   <li>indicator-adjusted probability (funding, long/short, short-liq ratios)</li>
 *   <li>expected value in USDT</li>
 *   <li>HOLD / MODIFY / CLOSE / WARN suggestion</li>
 * </ul>
 *
 * <p><b>Heuristic disclaimer</b>: regime + indicator adjustments are simple
 * additive nudges (±5pp / ±10pp), not statistically calibrated. Use the EV
 * number as a directional signal ("am I positive or negative?"), not as a
 * precise dollar forecast. Real outcome distributions depend on volatility
 * regime, news, and order-book microstructure — none of which this captures.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OcoOutcomeAnalysisService {

    private final BtLiveSignalRepository liveSignalRepository;
    private final OkxTradingService okxTradingService;
    private final AiStrategyDiscoveryService aiDiscoveryService;
    private final MarketIndicatorHistoryRepository historyRepo;

    /** Public outcome for unit testing — wraps the formatted string with raw inputs. */
    public record Outcome(
            long positionId, String symbol,
            double entry, double current, double tp, double sl, double qty,
            double pTpFirstBaseline, double pTpFirstAdjusted,
            double atrPct, String regime, int regimeBiasPp, int indicatorBiasPp,
            double evUsdt, String suggestion, String report
    ) {}

    /**
     * Analyze a position. {@code horizonHours} is informational only (we don't
     * model time-to-resolution explicitly in this MVP).
     */
    public Outcome analyze(long positionId, int horizonHours) {
        BtLiveSignal pos = liveSignalRepository.findById(positionId)
                .orElseThrow(() -> new IllegalArgumentException("Position not found: id=" + positionId));
        if (pos.getExitTime() != null)
            throw new IllegalArgumentException("Position already closed at " + pos.getExitTime());
        if (pos.getOcoOrderListId() == null)
            throw new IllegalArgumentException("Position has no active OCO; nothing to analyze");

        String symbol = pos.getSymbol();
        BigDecimal entryBd = pos.getActualEntryPrice() != null
                ? pos.getActualEntryPrice() : pos.getEntryPrice();
        if (entryBd == null) throw new IllegalArgumentException("Position has no entry price recorded");

        double entry = entryBd.doubleValue();
        double tp    = pos.getSuggestedTp().doubleValue();
        double sl    = pos.getSuggestedSl().doubleValue();
        double qty   = (pos.getOcoQty() != null ? pos.getOcoQty() : pos.getTradedQty()).doubleValue();
        double current = okxTradingService.getLastPrice(symbol).doubleValue();

        // Step 1 — baseline first-touch (random-walk assumption between SL and TP).
        double range = tp - sl;
        if (range <= 0)
            throw new IllegalStateException("Invalid OCO bracket: TP must be above SL for LONG");
        double pTpFirstBaseline = clamp((current - sl) / range, 0.01, 0.99);

        // Step 2 — Regime via MarketSnapshot. trendDirection is already
        // derived from price-vs-EMA20 with ±1% deadband (BULLISH / BEARISH /
        // SIDEWAYS). atrPct is volatility, used in the disclosure only.
        AiStrategyDiscoveryService.MarketSnapshot snap = null;
        double atrPct = 0;
        String regime = "UNKNOWN";
        try {
            snap = aiDiscoveryService.buildMarketSnapshot(symbol, "1h");
            atrPct = snap.atrPct();
            regime = snap.trendDirection();
        } catch (Exception e) {
            log.warn("[OcoOutcome] MarketSnapshot failed for {}: {}", symbol, e.getMessage());
        }

        int regimeBiasPp = switch (regime) {
            case "BULLISH" -> +5;   // upward drift favours TP-first
            case "BEARISH" -> -5;   // downward drift favours SL-first
            default        -> 0;    // SIDEWAYS / UNKNOWN
        };

        // Step 3 — Indicator modifiers (3 most directional, additive).
        // funding_rate     deeply negative = squeeze setup, +bp toward TP
        // long_short_ratio < 0.85 = retail short-heavy, +bp toward TP (squeeze fuel)
        // short_liq_ratio  > 0.6  = active short capitulation, +bp toward TP
        double fundingRate = latestIndicator(symbol, "funding_rate");
        double lsRatio     = latestIndicator(symbol, "long_short_ratio");
        double shortLiqRatio = latestIndicator(symbol, "btc_short_liq_ratio_1h");

        int indicatorBiasPp = 0;
        if (fundingRate < -0.0003)            indicatorBiasPp += 3;
        else if (fundingRate < -0.00005)      indicatorBiasPp += 1;
        else if (fundingRate >  0.00050)      indicatorBiasPp -= 2;

        if (lsRatio > 0 && lsRatio < 0.85)    indicatorBiasPp += 3;
        else if (lsRatio > 1.5)               indicatorBiasPp -= 2;

        if (shortLiqRatio > 0.6)              indicatorBiasPp += 4;
        else if (shortLiqRatio < 0.3)         indicatorBiasPp -= 2;

        // Step 4 — adjusted probabilities (clamped to avoid <1% / >99% silliness).
        double adjustedPTpFirst = clamp(
                pTpFirstBaseline + (regimeBiasPp + indicatorBiasPp) / 100.0,
                0.05, 0.95);
        double adjustedPSlFirst = 1 - adjustedPTpFirst;

        // Step 5 — EV in USDT. We assume the OCO bracket fully resolves within
        // the horizon (P(stuck) folded into P(SL first) for safety — pessimistic).
        double profitUsdt = (tp - entry) * qty;
        double lossUsdt   = (entry - sl) * qty;  // positive number
        double evUsdt = adjustedPTpFirst * profitUsdt - adjustedPSlFirst * lossUsdt;

        // Step 6 — suggestion. Thresholds are deliberately wide to avoid noise.
        String suggestion;
        String suggestionReason;
        if (current <= sl * 1.005) {
            suggestion = "WARN";
            suggestionReason = "Within 0.5% of SL — high SL-first risk";
        } else if (current >= tp * 0.995) {
            suggestion = "WARN";
            suggestionReason = "Within 0.5% of TP — likely triggers soon";
        } else if (evUsdt > 1.0) {
            suggestion = "HOLD";
            suggestionReason = String.format("Positive EV %s USDT", fmt(evUsdt));
        } else if (evUsdt > 0) {
            suggestion = "HOLD (low margin)";
            suggestionReason = String.format("Marginal EV %s USDT — monitor", fmt(evUsdt));
        } else if (evUsdt > -lossUsdt * 0.3) {
            suggestion = "MODIFY";
            suggestionReason = "Slightly negative EV — consider tightening TP / loosening SL";
        } else {
            suggestion = "CLOSE";
            suggestionReason = String.format("Strongly negative EV %s USDT", fmt(evUsdt));
        }

        String report = formatReport(
                positionId, symbol, entry, current, tp, sl, qty,
                pTpFirstBaseline, adjustedPTpFirst, adjustedPSlFirst,
                atrPct, regime, regimeBiasPp,
                fundingRate, lsRatio, shortLiqRatio, indicatorBiasPp,
                profitUsdt, lossUsdt, evUsdt,
                suggestion, suggestionReason, horizonHours);

        return new Outcome(
                positionId, symbol, entry, current, tp, sl, qty,
                pTpFirstBaseline, adjustedPTpFirst,
                atrPct, regime, regimeBiasPp, indicatorBiasPp,
                evUsdt, suggestion, report);
    }

    private double latestIndicator(String symbol, String indicator) {
        return historyRepo.findTopCleanBySymbolAndIndicator(symbol, indicator)
                .map(h -> h.getValue().doubleValue())
                .orElse(0.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmtPct(double v) {
        return BigDecimal.valueOf(v * 100).setScale(1, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String fmtPrice(double v) {
        return "$" + BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String formatReport(
            long id, String symbol, double entry, double current, double tp, double sl, double qty,
            double pTpFirstBaseline, double pTpFirstAdj, double pSlFirstAdj,
            double atrPct, String regime, int regimeBiasPp,
            double fundingRate, double lsRatio, double shortLiqRatio, int indicatorBiasPp,
            double profitUsdt, double lossUsdt, double evUsdt,
            String suggestion, String suggestionReason, int horizonHours) {

        double pctFromEntry = (current - entry) / entry;
        double pctToTp      = (tp - current) / current;
        double pctToSl      = (current - sl) / current;

        return String.format(
                "=== OCO Outcome Analysis: Position #%d ===%n" +
                "Symbol: %s  Entry: %s%n" +
                "Current: %s (%+.2f%%)%n" +
                "TP: %s (+%.2f%%)  |  SL: %s (-%.2f%%)%n" +
                "Qty: %.8f  |  Horizon: %dh%n" +
                "%n" +
                "📊 Volatility:%n" +
                "  1h ATR: %.2f%%%n" +
                "%n" +
                "🎯 First-touch probability:%n" +
                "  Baseline (random walk):  P(TP) = %s%n" +
                "%n" +
                "🌐 Regime: %s  → %+dpp%n" +
                "%n" +
                "📡 Indicators:%n" +
                "  funding_rate:           %.6f%n" +
                "  long_short_ratio:       %.3f%n" +
                "  short_liq_ratio_1h:     %.3f%n" +
                "  Indicator adjustment:   %+dpp%n" +
                "%n" +
                "📈 Adjusted probabilities:%n" +
                "  P(TP first):  %s%n" +
                "  P(SL first):  %s%n" +
                "%n" +
                "💰 Expected Value:%n" +
                "  TP profit:  +%s USDT  ×  P(TP) = +%s%n" +
                "  SL loss:    -%s USDT  ×  P(SL) = -%s%n" +
                "  EV:         %s USDT%n" +
                "%n" +
                "🎯 Suggestion: %s%n" +
                "  %s%n",
                id, symbol, fmtPrice(entry),
                fmtPrice(current), pctFromEntry * 100,
                fmtPrice(tp), pctToTp * 100, fmtPrice(sl), pctToSl * 100,
                qty, horizonHours,
                atrPct,
                fmtPct(pTpFirstBaseline),
                regime, regimeBiasPp,
                fundingRate, lsRatio, shortLiqRatio, indicatorBiasPp,
                fmtPct(pTpFirstAdj),
                fmtPct(pSlFirstAdj),
                fmt(profitUsdt), fmt(pTpFirstAdj * profitUsdt),
                fmt(lossUsdt),   fmt(pSlFirstAdj * lossUsdt),
                fmt(evUsdt),
                suggestion, suggestionReason);
    }
}
