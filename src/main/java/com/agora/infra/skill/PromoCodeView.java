package com.agora.infra.skill;

/**
 * Skill-side projection of {@link com.agora.model.PromoCode}. Carries only the
 * fields the promo-code skill renders.
 */
public record PromoCodeView(
        String code,
        String name,
        String description
) {}
