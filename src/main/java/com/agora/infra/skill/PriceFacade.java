package com.agora.infra.skill;

import com.agora.model.MdKline;

/**
 * Skill-facing price/kline surface. AI skills depend on this interface,
 * not on BinanceKlineImportService directly — keeps {@code service.ai.skill}
 * free of {@code service.market} imports (plan §4).
 *
 * <p>Returns {@code MdKline} rather than a DTO because the model class is not
 * blocked by the skill ArchTest rule (which only excludes Product/Store/
 * PromoCode entities). Wrapping again would be unnecessary translation.
 */
public interface PriceFacade {

    /** Latest kline for {@code symbol} on the given interval. {@code null} on API failure. */
    MdKline fetchLatestKline(String symbol, String intervalCode);
}
