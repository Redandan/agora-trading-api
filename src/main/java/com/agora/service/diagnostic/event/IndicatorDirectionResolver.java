package com.agora.service.diagnostic.event;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * #337 — 把 source name + 觸發語意映射到「預期事後價格方向」。
 *
 * <p>原本 TgIndicatorEventSource 的 keyword heuristic（含 "Squeeze" → SHORT）
 * 直接寫反了：ShortSqueeze 是看多訊號（空頭被擠壓→反彈→LONG），ShortBuild 也是
 * 看多（空頭累積→將被擠壓→LONG）。
 *
 * <p>這個 resolver 用顯式 lookup table 確保語意正確，避免後續分析誤算 hit_rate。
 */
@Component
public class IndicatorDirectionResolver {

    /**
     * TG source 欄位（如 "SqiIndicator" / "ShortBuildIndicator"）→ 預期方向。
     * 缺項 → 預設 LONG（看 issue body：多數預警都是「市場反彈」訊號）。
     */
    private static final Map<String, String> TG_SOURCE_TO_DIRECTION = Map.ofEntries(
            // —— LONG（看多） ——
            Map.entry("SqiIndicator", Event.LONG),               // Short Squeeze Index：空頭擁擠→反彈
            Map.entry("ShortBuildIndicator", Event.LONG),        // 空頭累積→squeeze fuel→反彈
            Map.entry("StablecoinDemandIndicator", Event.LONG),  // 穩定幣需求高→sidelined buying→反彈
            Map.entry("EtfPressureIndicator", Event.LONG),       // ETF 買壓→LONG
            // —— SHORT（看空） ——
            Map.entry("VolumeDecayIndicator", Event.SHORT),      // 量能衰退→趨勢延續向下
            // —— MEI 從 NEUTRAL 改 LONG（#338 V1 首跑發現 30d/7d avg +1.49%/+0.21% 偏漲）——
            // 原假設「市場熵高=無共識方向不明」與 30d 觀察不符
            Map.entry("MarketEntropyIndicator", Event.LONG),
            // —— 其他 ——
            Map.entry("WhaleBuyMonitor", Event.LONG),
            Map.entry("FundingRateMonitor", Event.LONG),
            Map.entry("LongShortRatioMonitor", Event.LONG),
            Map.entry("OpenInterestMonitor", Event.LONG),
            Map.entry("LiquidationMonitor", Event.LONG)
    );

    /**
     * mih_threshold 的方向，依 (indicator, operator) 對應。
     * key 格式：{@code "indicator:opCategory"}，opCategory ∈ "low" (lt/lte) / "high" (gt/gte)。
     */
    private static final Map<String, String> MIH_THRESHOLD_DIRECTION = Map.ofEntries(
            // funding_rate：低=空頭擁擠→LONG；高=多頭擁擠→SHORT
            Map.entry("funding_rate:low", Event.LONG),
            Map.entry("funding_rate:high", Event.SHORT),
            // long_short_ratio：低=空頭多→LONG；高=多頭多→SHORT
            Map.entry("long_short_ratio:low", Event.LONG),
            Map.entry("long_short_ratio:high", Event.SHORT),
            // whale_buy_ratio:high → #338 V1 30d/7d 兩窗都 contra（25%/15.4% hit）
            // 假設修正：「鯨魚大買出現在散戶恐慌賣的對手盤，短期繼續跌」→ SHORT
            // 注意：> 0.75 sample n=3 lowN，這個 reclassify 主要基於 0.65 sample
            Map.entry("whale_buy_ratio:high", Event.SHORT),
            Map.entry("whale_buy_ratio:low", Event.LONG),  // 對稱反轉
            // sqi 高=擁擠→LONG（squeeze fuel）
            Map.entry("sqi:high", Event.LONG),
            Map.entry("sqi:low", Event.SHORT),
            // short_build_index 高=空頭累積→LONG
            Map.entry("short_build_index:high", Event.LONG),
            Map.entry("short_build_index:low", Event.SHORT),
            // vdi 低=量能衰退→SHORT；高=量能放大
            Map.entry("vdi:low", Event.SHORT),
            Map.entry("vdi:high", Event.LONG),
            // mei 高=高混亂→NEUTRAL（無方向）
            Map.entry("mei:high", Event.NEUTRAL),
            Map.entry("mei:low", Event.NEUTRAL),
            // btc_basis_pct 低=現貨溢價（深 contango 反轉）→LONG
            Map.entry("btc_basis_pct:low", Event.LONG),
            Map.entry("btc_basis_pct:high", Event.SHORT),
            // btc_short_liq_ratio_1h:high → #351 Phase 1 改 SHORT 後揭露真相是 noise（不是 contra）
            // 兩方向 hit% 36% / 40% 接近對稱 + avg_return +0.02% 接近 0 → 純 noise
            // revert 回 LONG（noise 標 LONG 不誤導，避免假 SHORT alpha 印象）
            Map.entry("btc_short_liq_ratio_1h:high", Event.LONG),
            // oi_change_pct_1h 高=OI 暴增→延續方向；低=OI 縮減
            Map.entry("oi_change_pct_1h:high", Event.LONG),  // V1 預設 LONG
            Map.entry("oi_change_pct_1h:low", Event.SHORT),
            // fear_greed 低=極度恐懼→反彈→LONG；高=極度貪婪→修正
            Map.entry("fear_greed:low", Event.LONG),
            Map.entry("fear_greed:high", Event.SHORT)
    );

    public String forTgSource(String tgSource) {
        if (tgSource == null) return Event.LONG;
        // 移除 #ID 後綴（"AttentionRule#37" → "AttentionRule"）
        String key = tgSource.split("#", 2)[0].trim();
        return TG_SOURCE_TO_DIRECTION.getOrDefault(key, Event.LONG);
    }

    public String forMihThreshold(String indicator, String operator) {
        if (indicator == null || operator == null) return Event.LONG;
        String opCat = operator.toLowerCase().startsWith("l") ? "low" : "high";
        return MIH_THRESHOLD_DIRECTION.getOrDefault(indicator + ":" + opCat, Event.LONG);
    }

    /** 給 ml_inference 用：BLOCK 通常代表「不看多」→ SHORT 反向驗證；PASS 代表「看多」→ LONG */
    public String forMlInferenceDecision(String decision) {
        if (decision == null) return Event.LONG;
        return switch (decision.toUpperCase()) {
            case "BLOCK" -> Event.SHORT;  // 反向驗證：v19 BLOCK 後是否真的下跌
            case "PASS" -> Event.LONG;
            default -> Event.NEUTRAL;
        };
    }
}
