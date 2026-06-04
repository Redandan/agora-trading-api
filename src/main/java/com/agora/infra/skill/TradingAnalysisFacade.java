package com.agora.infra.skill;

/**
 * Skill-facing trading analysis surface. AI skills depend on this interface,
 * not on TradingAnalysisService directly — keeps {@code service.ai.skill}
 * free of trading-domain imports (plan §4).
 */
public interface TradingAnalysisFacade {

    /** Composite Fear&Greed + whale-flow + technical analysis as HTML. */
    String analyze();
}
