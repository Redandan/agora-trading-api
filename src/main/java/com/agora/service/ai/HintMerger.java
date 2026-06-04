package com.agora.service.ai;

import com.agora.model.GeminiMarketHint;
import com.agora.model.HintOverride;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 合併 Claude 手動 {@link HintOverride} 與 Gemini 自動 {@link GeminiMarketHint} 的 per-field merger。
 *
 * <h3>合併語義</h3>
 * <ol>
 *   <li>override 的**非 null 欄位勝出**(Claude 意圖明確)</li>
 *   <li>override 的 null 欄位,由 Gemini hint 的對應欄位填補</li>
 *   <li>若 override 或 Gemini 都無 → null(caller 用 strategy config 預設)</li>
 *   <li>{@code styleHint == "DISABLE"} 為 kill switch,caller 應短路 return,**不參與** merge</li>
 * </ol>
 *
 * <p><b>為何是 Component 而非 static util</b>:未來想擴展(log merge 細節 / 埋點 audit)有 bean 較乾淨。
 * 目前邏輯純函數,無狀態。
 */
@Component
public class HintMerger {

    /**
     * 合併 override 與 gemini hint。兩者皆 null → return null。
     * 結果型別仍為 GeminiMarketHint(合成實例,未持久化),供 applyHintOverrides 沿用。
     *
     * <p>特別處理:當 override 存在但 gemini 不存在時,需 synthesize 一個符合 applyHintOverrides
     * 前置條件(confidence ≥ threshold)的 hint — 人為 override 視為 confidence=1.0。
     */
    public GeminiMarketHint merge(HintOverride override, GeminiMarketHint gemini) {
        if (override == null && gemini == null) return null;

        // 僅 gemini:原樣回傳(caller 處理 confidence 門檻)
        if (override == null) return gemini;

        // 合成出發點:要嘛 copy gemini,要嘛 new 一個符合 defaults
        GeminiMarketHint merged = gemini != null ? copyOf(gemini) : synthDefault(override);

        // per-field override:override 非 null 欄位勝出
        if (override.getStyleHint()    != null) merged.setStyleHint(override.getStyleHint());
        if (override.getRegime()       != null) merged.setRegime(override.getRegime());
        if (override.getAdxAdjust()    != null) merged.setAdxAdjust(override.getAdxAdjust());
        if (override.getSlMultiplier() != null) merged.setSlMultiplier(override.getSlMultiplier());
        if (override.getTpMultiplier() != null) merged.setTpMultiplier(override.getTpMultiplier());
        if (override.getAllowShort()   != null) merged.setAllowShort(override.getAllowShort());

        // Claude 人為 override 視為 conf=1.0(確保通過 confidence threshold)
        merged.setConfidence(BigDecimal.ONE);

        return merged;
    }

    private GeminiMarketHint copyOf(GeminiMarketHint src) {
        GeminiMarketHint c = new GeminiMarketHint();
        c.setId(src.getId());
        c.setSymbol(src.getSymbol());
        c.setTimeframe(src.getTimeframe());
        c.setRegime(src.getRegime());
        c.setStyleHint(src.getStyleHint());
        c.setAdxAdjust(src.getAdxAdjust());
        c.setSlMultiplier(src.getSlMultiplier());
        c.setTpMultiplier(src.getTpMultiplier());
        c.setAllowShort(src.getAllowShort());
        c.setConfidence(src.getConfidence());
        c.setPersonaVotes(src.getPersonaVotes());
        c.setReasoning(src.getReasoning());
        c.setCreatedAt(src.getCreatedAt());
        c.setExpiresAt(src.getExpiresAt());
        return c;
    }

    /** 無 Gemini hint 時,用 override + config defaults 合成一個 hint 殼。 */
    private GeminiMarketHint synthDefault(HintOverride override) {
        GeminiMarketHint h = new GeminiMarketHint();
        h.setSymbol(override.getSymbol());
        h.setTimeframe(override.getTimeframe());
        h.setRegime(override.getRegime() != null ? override.getRegime() : "UNKNOWN");
        h.setStyleHint(override.getStyleHint() != null ? override.getStyleHint() : "TREND");
        h.setAdxAdjust(override.getAdxAdjust() != null ? override.getAdxAdjust() : BigDecimal.ZERO);
        h.setSlMultiplier(override.getSlMultiplier() != null ? override.getSlMultiplier() : BigDecimal.ONE);
        h.setTpMultiplier(override.getTpMultiplier() != null ? override.getTpMultiplier() : BigDecimal.ONE);
        h.setAllowShort(override.getAllowShort() != null ? override.getAllowShort() : false);
        h.setConfidence(BigDecimal.ONE);
        h.setPersonaVotes("{\"source\":\"manual_override\"}");
        h.setReasoning("Claude manual hint override (no Gemini hint active)");
        h.setCreatedAt(override.getCreatedAt());
        h.setExpiresAt(override.getExpiresAt());
        return h;
    }

    /**
     * 計算合成 hint 的「有效截止時間」— 取 override 與 gemini 中較早者。
     * 用於 cache 時可做 TTL。Phase 1 未用,預留。
     */
    public LocalDateTime earliestExpiry(HintOverride o, GeminiMarketHint g) {
        if (o == null) return g != null ? g.getExpiresAt() : null;
        if (g == null) return o.getExpiresAt();
        return o.getExpiresAt().isBefore(g.getExpiresAt()) ? o.getExpiresAt() : g.getExpiresAt();
    }
}
