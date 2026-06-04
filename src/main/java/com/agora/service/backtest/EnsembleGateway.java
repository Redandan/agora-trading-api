package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.repository.trading.MarketFlipEventRepository;
import com.agora.service.market.PolymarketService;
import com.agora.service.meta.TradeDecisionEngine;
import com.agora.service.ml.MlInferenceLogger;
import com.agora.service.trading.OkxTradingService;
import com.agora.service.market.OrderbookImbalanceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Assembles inputs for {@link TradeDecisionEngine} and delegates the score computation.
 * Extracted from LiveSignalEvaluator to reduce its injection count and isolate ensemble concerns.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class EnsembleGateway {

    private final OkxTradingService okxTradingService;
    private final PolymarketService polymarketService;
    private final MarketFlipEventRepository marketFlipEventRepository;
    private final OrderbookImbalanceService orderbookImbalanceService;
    private final MlInferenceLogger mlInferenceLogger;
    private final TradeDecisionEngine tradeDecisionEngine;

    /**
     * Gathers live market inputs and runs the ensemble scorer.
     * All external fetches are fail-open (caught individually; null passed on error).
     */
    public TradeDecisionEngine.Decision compute(
            long strategyId, String symbol, String intervalCode, String sideTag,
            List<MdKline> klines, int lastIndex,
            LiveSignalContext.Snapshot snap,
            SentimentContext.Snapshot sent,
            String geminiStyle, String geminiRegime,
            Double geminiConf, Boolean geminiShortOk,
            Map<String, double[]> indicators) {

        TradeDecisionEngine.Side engineSide =
                "LONG".equals(sideTag) ? TradeDecisionEngine.Side.LONG : TradeDecisionEngine.Side.SHORT;

        Double pWin = null;
        try {
            MlInferenceLogger.PreviewResult res =
                    mlInferenceLogger.previewSync(symbol, intervalCode, sideTag,
                            strategyId, klines, lastIndex);
            if (res != null && res.errorMessage() == null) pWin = res.pWin();
        } catch (Exception ignored) {}

        Double rsi = snap != null ? snap.rsi : null;
        Double adx = null;
        if (indicators != null) {
            double[] adxArr = indicators.get("adx");
            if (adxArr != null && lastIndex < adxArr.length
                    && !Double.isNaN(adxArr[lastIndex])) adx = adxArr[lastIndex];
        }
        Double strategyScore = snap != null ? snap.score : null;

        Integer fg = sent != null ? sent.fearGreedValue : null;
        Double whale = sent != null ? sent.whaleBuyRatio : null;
        Double fundingPct = null;
        try {
            fundingPct = okxTradingService.getCurrentFundingRate(symbol) * 100;
        } catch (Exception ignored) {}
        Double lsRatio = null;
        try {
            double r = okxTradingService.getLongShortRatio(symbol);
            if (r >= 0) lsRatio = r;
        } catch (Exception ignored) {}

        Double polyRiskPct = null;
        try {
            PolymarketService.MacroRiskResult poly = polymarketService.getMacroRisk();
            if (poly != null && poly.riskScore() >= 0) polyRiskPct = poly.riskScore() * 100.0;
        } catch (Exception ignored) {}

        Integer flipMinutes = null;
        try {
            List<com.agora.model.MarketFlipEvent> recent =
                    marketFlipEventRepository.findLatestBySymbol(symbol, PageRequest.of(0, 1));
            if (!recent.isEmpty() && recent.get(0).getDetectedAt() != null) {
                long m = java.time.Duration.between(
                        recent.get(0).getDetectedAt(), LocalDateTime.now()).toMinutes();
                if (m >= 0) flipMinutes = (int) Math.min(m, Integer.MAX_VALUE);
            }
        } catch (Exception ignored) {}

        Double obi = null;
        try {
            obi = orderbookImbalanceService.getImbalance(symbol);
        } catch (Exception ignored) {}

        Double availUsdt = null;
        try {
            String bal = okxTradingService.getUsdtBalance();
            if (bal != null && !"N/A".equals(bal)) availUsdt = Double.parseDouble(bal);
        } catch (Exception ignored) {}

        TradeDecisionEngine.Inputs inputs =
                new TradeDecisionEngine.Inputs(
                        engineSide, pWin, strategyScore,
                        geminiStyle, geminiRegime, geminiShortOk, geminiConf,
                        rsi, adx, lsRatio, whale, fundingPct, fg, polyRiskPct, flipMinutes,
                        null, null,
                        obi, availUsdt);

        return tradeDecisionEngine.score(inputs);
    }
}
