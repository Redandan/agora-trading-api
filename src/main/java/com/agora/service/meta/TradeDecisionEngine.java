package com.agora.service.meta;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 1 deterministic ensemble scorer for trade decisions.
 *
 * <p>Combines strategy signal + ML P(win) + Gemini hint + filter state + sentiment
 * + market-flip + polymarket into a single 0-100 conviction score with full
 * component breakdown for explainability. Pure function: no service dependencies,
 * no side effects — caller gathers inputs and hands them in.
 *
 * <p>Design:
 * <ul>
 *   <li>Base score = ML P(win) × 100. If ML absent, falls back to strategy score × 100.</li>
 *   <li>Other layers contribute +/- adjustments with hard-coded weights (tunable via config).</li>
 *   <li>One "veto" rule: Gemini style=DISABLE drops score to 0 regardless of others.</li>
 *   <li>Score ≥ threshold → PASS (trade); otherwise BLOCK with full reasoning.</li>
 * </ul>
 *
 * <p>Phase 2 path: replace hand-tuned weights with logistic regression fitted on
 * {@code bt_decision_audit.context_json} + {@code ml_inference_log.actual_outcome}.
 * That requires ≥100 labeled trades — see project_ml_pipeline memory.
 */
@Slf4j
@Component
public class TradeDecisionEngine {

    @Value("${trade-decision-engine.threshold:60.0}")
    private double threshold;

    /** Side of trade being evaluated. */
    public enum Side { LONG, SHORT }

    /**
     * Input bundle. Every field is nullable — missing data simply skips that layer's
     * contribution, it does not fail the evaluation.
     */
    public record Inputs(
            Side side,
            Double mlPWin,              // from v13 predictOne, 0.0-1.0
            Double strategyScore,       // strategy's own score, 0.0-1.0
            String geminiStyle,         // CONSERVATIVE / AGGRESSIVE / DISABLE / HIGH_FREQ / TREND
            String geminiRegime,        // TRENDING_UP / SIDEWAYS / VOLATILE / TRENDING_DOWN / RECOVERY
            Boolean geminiShortOk,      // whether hint permits SHORT
            Double geminiConfidence,    // 0.0-1.0
            Double rsi,                 // 0-100
            Double adx,                 // 0-100
            Double lsRatio,             // OKX long/short ratio
            Double whaleBuyRatio,       // 0.0-1.0
            Double fundingRatePct,      // per-8h %, positive = shorts pay longs
            Integer fearGreed,          // 0-100, high = greed, low = fear
            Double polymarketRiskPct,   // 0-100 from getPolymarketRisk
            Integer marketFlipRecentMinutes,  // minutes since last flip, null if none
            Boolean allFiltersPass,     // null if filters not run
            List<String> filterBlockReasons,  // blocking-rule identifiers, empty if all pass
            Double orderBookImbalance,  // spot OB (bid-ask)/(bid+ask) ∈ [-1,+1]; + = bid-heavy
            Double availableUsdtAmt     // free USDT in OKX account; null if unavailable
    ) {}

    /** One contribution line in the score breakdown. */
    public record Component(String layer, double points, String reason) {}

    /** Final decision output. */
    public record Decision(
            double score,
            double threshold,
            String outcome,              // PASS / BLOCK / VETO
            String vetoReason,           // non-null only if outcome=VETO
            List<Component> components,
            Map<String, Object> inputsEcho  // for audit + MCP display
    ) {}

    public Decision score(Inputs in) {
        List<Component> comps = new ArrayList<>();
        Map<String, Object> echo = new LinkedHashMap<>();
        echo.put("side", in.side());

        // ── Veto checks first ──────────────────────────────────────────────
        if ("DISABLE".equalsIgnoreCase(in.geminiStyle())) {
            return new Decision(0.0, threshold, "VETO",
                    "Gemini style=DISABLE (kill switch)",
                    comps, echo);
        }
        if (in.side() == Side.SHORT && Boolean.FALSE.equals(in.geminiShortOk())) {
            return new Decision(0.0, threshold, "VETO",
                    "Gemini short_ok=false on this timeframe",
                    comps, echo);
        }

        // ── Base score: ML P(win) × 100, fallback to strategy score ────────
        double base;
        if (in.mlPWin() != null) {
            base = clamp(in.mlPWin() * 100.0, 0, 100);
            comps.add(new Component("ml_p_win",
                    base, String.format("v13 勝率=%.3f", in.mlPWin())));
            echo.put("ml_p_win", in.mlPWin());
        } else if (in.strategyScore() != null) {
            base = clamp(in.strategyScore() * 100.0, 0, 100);
            comps.add(new Component("strategy_score_fallback",
                    base, String.format("無ML模型，使用策略分數=%.3f", in.strategyScore())));
            echo.put("strategy_score", in.strategyScore());
        } else {
            base = 50.0;  // neutral fallback
            comps.add(new Component("no_base", 50.0, "無ML或策略分數，中性50"));
        }

        double total = base;

        // ── Gemini style / regime (soft adjustments after veto) ────────────
        if ("CONSERVATIVE".equalsIgnoreCase(in.geminiStyle())) {
            total += add(comps, "gemini_style", -10,
                    "Gemini 保守派壓低信心");
        } else if ("AGGRESSIVE".equalsIgnoreCase(in.geminiStyle())
                || "TREND".equalsIgnoreCase(in.geminiStyle())) {
            total += add(comps, "gemini_style", +5,
                    "Gemini " + in.geminiStyle() + " 提升信心");
        }
        // Full directional regime scoring — aligns conviction with market structure.
        // TRENDING_DOWN strongly penalises LONG (counter-trend); rewards SHORT.
        // SIDEWAYS penalises both directions moderately (chop risk).
        // TRENDING_UP rewards LONG, penalises SHORT.
        // RECOVERY is mildly bullish (penalises SHORT lightly).
        if (in.geminiRegime() != null) {
            String regime = in.geminiRegime().toUpperCase();
            if (in.side() == Side.LONG) {
                total += switch (regime) {
                    case "TRENDING_DOWN" -> add(comps, "gemini_regime", -15,
                            "下降趨勢逆勢做多（風險高）");
                    case "SIDEWAYS"      -> add(comps, "gemini_regime", -8,
                            "橫盤做多（假突破風險）");
                    case "VOLATILE"      -> add(comps, "gemini_regime", -5,
                            "高波動做多（方向難測）");
                    case "TRENDING_UP"   -> add(comps, "gemini_regime", +8,
                            "上升趨勢順勢做多");
                    case "RECOVERY"      -> add(comps, "gemini_regime", +5,
                            "復甦市場做多（風險偏好回升）");
                    default              -> 0;
                };
            } else {  // SHORT
                total += switch (regime) {
                    case "TRENDING_DOWN" -> add(comps, "gemini_regime", +8,
                            "下降趨勢順勢做空");
                    case "SIDEWAYS"      -> add(comps, "gemini_regime", -5,
                            "橫盤做空（反彈風險）");
                    case "VOLATILE"      -> add(comps, "gemini_regime", -5,
                            "高波動做空（方向難測）");
                    case "TRENDING_UP"   -> add(comps, "gemini_regime", -10,
                            "上升趨勢逆勢做空（逼空風險高）");
                    case "RECOVERY"      -> add(comps, "gemini_regime", -5,
                            "復甦市場做空（風險偏好環境）");
                    default              -> 0;
                };
            }
        }
        if (in.geminiConfidence() != null && in.geminiConfidence() >= 0.80) {
            total += add(comps, "gemini_confidence", +3,
                    String.format("高信心度(%.2f)加分", in.geminiConfidence()));
        } else if (in.geminiConfidence() != null && in.geminiConfidence() < 0.50) {
            total += add(comps, "gemini_confidence", -3,
                    String.format("低信心度(%.2f)扣分", in.geminiConfidence()));
        }
        echo.put("gemini_style", in.geminiStyle());
        echo.put("gemini_regime", in.geminiRegime());

        // ── RSI extremes ───────────────────────────────────────────────────
        if (in.rsi() != null) {
            echo.put("rsi", in.rsi());
            if (in.side() == Side.LONG && in.rsi() >= 75) {
                total += add(comps, "rsi", -15, String.format("RSI %.1f 超買，不利做多", in.rsi()));
            } else if (in.side() == Side.SHORT && in.rsi() <= 25) {
                total += add(comps, "rsi", -15, String.format("RSI %.1f 超賣，不利做空", in.rsi()));
            } else if (in.side() == Side.LONG && in.rsi() <= 30) {
                total += add(comps, "rsi", +8, String.format("RSI %.1f 超賣，支持做多", in.rsi()));
            } else if (in.side() == Side.SHORT && in.rsi() >= 70) {
                total += add(comps, "rsi", +8, String.format("RSI %.1f 超買，支持做空", in.rsi()));
            }
        }

        // ── L/S ratio ──────────────────────────────────────────────────────
        if (in.lsRatio() != null) {
            echo.put("ls_ratio", in.lsRatio());
            if (in.side() == Side.LONG && in.lsRatio() < 0.80) {
                total += add(comps, "ls_ratio", -5,
                        String.format("多空比%.2f 偏空，不利做多", in.lsRatio()));
            } else if (in.side() == Side.SHORT && in.lsRatio() > 1.20) {
                total += add(comps, "ls_ratio", -5,
                        String.format("多空比%.2f 偏多，不利做空", in.lsRatio()));
            } else if (in.side() == Side.LONG && in.lsRatio() > 1.20) {
                total += add(comps, "ls_ratio", +3,
                        String.format("多空比%.2f 偏多，支持做多", in.lsRatio()));
            } else if (in.side() == Side.SHORT && in.lsRatio() < 0.80) {
                total += add(comps, "ls_ratio", +3,
                        String.format("多空比%.2f 偏空，支持做空", in.lsRatio()));
            }
        }

        // ── Whale buy ratio ────────────────────────────────────────────────
        if (in.whaleBuyRatio() != null) {
            echo.put("whale_buy_ratio", in.whaleBuyRatio());
            if (in.side() == Side.LONG && in.whaleBuyRatio() >= 0.65) {
                total += add(comps, "whale", +10,
                        String.format("鯨魚買入%.0f%% 強烈看漲", in.whaleBuyRatio() * 100));
            } else if (in.side() == Side.SHORT && in.whaleBuyRatio() <= 0.35) {
                total += add(comps, "whale", +10,
                        String.format("鯨魚買入%.0f%% 強烈看跌", in.whaleBuyRatio() * 100));
            } else if (in.side() == Side.LONG && in.whaleBuyRatio() <= 0.35) {
                total += add(comps, "whale", -8,
                        String.format("鯨魚買入%.0f%% 看跌，不利做多", in.whaleBuyRatio() * 100));
            } else if (in.side() == Side.SHORT && in.whaleBuyRatio() >= 0.65) {
                total += add(comps, "whale", -8,
                        String.format("鯨魚買入%.0f%% 看漲，不利做空", in.whaleBuyRatio() * 100));
            }
        }

        // ── Funding rate (positive = shorts pay longs = bullish pressure) ──
        if (in.fundingRatePct() != null) {
            echo.put("funding_rate_pct_8h", in.fundingRatePct());
            if (in.side() == Side.LONG && in.fundingRatePct() > 0) {
                total += add(comps, "funding", +3, "資金費率為正，支持做多");
            } else if (in.side() == Side.SHORT && in.fundingRatePct() < 0) {
                total += add(comps, "funding", +3, "資金費率為負，支持做空");
            }
        }

        // ── Fear & Greed ───────────────────────────────────────────────────
        if (in.fearGreed() != null) {
            echo.put("fear_greed", in.fearGreed());
            if (in.side() == Side.LONG && in.fearGreed() <= 25) {
                total += add(comps, "fng", +10, "極端恐懼，支持做多抄底");
            } else if (in.side() == Side.SHORT && in.fearGreed() >= 75) {
                total += add(comps, "fng", +10, "極端貪婪，支持做空");
            }
        }

        // ── Polymarket macro risk (currently only meaningful for LONG) ─────
        if (in.polymarketRiskPct() != null) {
            echo.put("polymarket_risk_pct", in.polymarketRiskPct());
            if (in.side() == Side.LONG && in.polymarketRiskPct() >= 40) {
                total += add(comps, "polymarket", -10,
                        String.format("Polymarket風險%.0f%%，宏觀逆風不利做多",
                                in.polymarketRiskPct()));
            }
        }

        // ── Market Flip recency (last 4h is considered fresh) ──────────────
        if (in.marketFlipRecentMinutes() != null && in.marketFlipRecentMinutes() <= 240) {
            echo.put("market_flip_recent_minutes", in.marketFlipRecentMinutes());
            total += add(comps, "market_flip", -15,
                    String.format("近期市況翻轉（%d 分鐘前），市場不穩",
                            in.marketFlipRecentMinutes()));
        }

        // ── Filter state (Long/ShortAiFilter if run) ───────────────────────
        if (in.allFiltersPass() != null) {
            echo.put("all_filters_pass", in.allFiltersPass());
            if (in.allFiltersPass()) {
                total += add(comps, "filters", +10, "所有過濾規則通過");
            } else {
                int n = in.filterBlockReasons() != null ? in.filterBlockReasons().size() : 1;
                total += add(comps, "filters", -20 * n,
                        n + " 條規則攔截："
                                + (in.filterBlockReasons() != null
                                ? String.join("，", in.filterBlockReasons()) : "未知"));
            }
        }

        // ── Spot order-book imbalance (bid/ask depth pressure) ────────────
        // (bidVol - askVol) / (bidVol + askVol): +1 = all bids, -1 = all asks.
        // ≥ +0.20 = meaningful bid-side excess; ≤ -0.20 = meaningful ask-side excess.
        // Strong conviction at ±0.40.
        if (in.orderBookImbalance() != null) {
            echo.put("order_book_imbalance", in.orderBookImbalance());
            double obi = in.orderBookImbalance();
            if (in.side() == Side.LONG) {
                if (obi >= 0.40) {
                    total += add(comps, "order_book", +8,
                            String.format("委買牆OB=%.2f 強力支持做多", obi));
                } else if (obi >= 0.20) {
                    total += add(comps, "order_book", +5,
                            String.format("買盤過剩OB=%.2f 支持做多", obi));
                } else if (obi <= -0.40) {
                    total += add(comps, "order_book", -8,
                            String.format("委賣牆OB=%.2f 強力壓制做多", obi));
                } else if (obi <= -0.20) {
                    total += add(comps, "order_book", -5,
                            String.format("賣盤過剩OB=%.2f 不利做多", obi));
                }
            } else { // SHORT
                if (obi <= -0.40) {
                    total += add(comps, "order_book", +8,
                            String.format("委賣牆OB=%.2f 強力支持做空", obi));
                } else if (obi <= -0.20) {
                    total += add(comps, "order_book", +5,
                            String.format("賣盤過剩OB=%.2f 支持做空", obi));
                } else if (obi >= 0.40) {
                    total += add(comps, "order_book", -8,
                            String.format("委買牆OB=%.2f 強力壓制做空", obi));
                } else if (obi >= 0.20) {
                    total += add(comps, "order_book", -5,
                            String.format("買盤過剩OB=%.2f 不利做空", obi));
                }
            }
        }

        // ── Available capital level (free USDT in account) ────────────────
        // Minimum position size = ~$50 USDT. Capital below this literally prevents execution.
        // Adjust conviction to reflect capital availability / over-deployment risk.
        if (in.availableUsdtAmt() != null) {
            echo.put("available_usdt", in.availableUsdtAmt());
            double usdt = in.availableUsdtAmt();
            if (usdt < 50) {
                total += add(comps, "capital", -10,
                        String.format("資金耗盡：%.0f USDT < 最小倉位50", usdt));
            } else if (usdt < 100) {
                total += add(comps, "capital", -3,
                        String.format("資金緊張：%.0f USDT（最多2筆倉位）", usdt));
            } else if (usdt >= 300) {
                total += add(comps, "capital", +3,
                        String.format("資金充裕：%.0f USDT（可開6+筆倉位）", usdt));
            }
            // 100-300 USDT: neutral, no adjustment
        }

        double finalScore = clamp(total, 0, 150);  // allow slight overshoot for display; cap at 150
        String outcome = finalScore >= threshold ? "PASS" : "BLOCK";

        if (log.isDebugEnabled()) {
            log.debug("[TradeDecisionEngine] side={} score={} outcome={} components={}",
                    in.side(), String.format("%.1f", finalScore), outcome, comps.size());
        }

        return new Decision(finalScore, threshold, outcome, null, comps, echo);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    private static double add(List<Component> comps, String layer, double points, String reason) {
        comps.add(new Component(layer, points, reason));
        return points;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
