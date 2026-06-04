package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * Funding Rate Arbitrage(Layer 2 被動收益)配置。
 * 對應 application.yml 的 trading.funding-arb.* 區塊。
 *
 * <p>Phase 1:single position at a time、BTCUSDT only、無 rebalance、無槓桿選項。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.funding-arb")
public class FundingArbProperties {

    /** 主開關。預設關閉。 */
    private boolean enabled = false;

    /**
     * Dry-run mode:true 時只 log 預期動作,**不呼叫** OKX API。
     * Phase 1 上線必開啟,驗證 scheduler 邏輯後再切 false。
     */
    private boolean dryRun = true;

    /** 目前只支援單一 symbol。 */
    private String symbol = "BTCUSDT";

    /** 單筆 position 的 notional 價值(spot 用 USDT 買入,perp 等值做空)。 */
    private BigDecimal notionalUsdt = new BigDecimal("800");

    /** 進場門檻:當前 8h funding rate ≥ 此值才考慮開倉(0.0002 = 0.02% = 年化 21.9%)。 */
    private BigDecimal minFundingRate = new BigDecimal("0.0002");

    /** 持續性門檻:歷史最近 3 期 funding 全部 ≥ 此值(防刺激進場)。 */
    private BigDecimal sustainedThreshold = new BigDecimal("0.00015");

    /** 出場門檻:當前 funding < 此值連續 2 期即平倉(0.00005 = 0.005% = 年化 5.5%)。 */
    private BigDecimal exitThreshold = new BigDecimal("0.00005");

    /** 累積 funding 收入達 notional × 此比例即出場(0.01 = 1%)。 */
    private BigDecimal targetProfitPct = new BigDecimal("0.01");

    /** Delta drift 超過此比例即強制平倉(0.05 = 5%)。 */
    private BigDecimal maxDeltaDriftPct = new BigDecimal("0.05");

    /** 是否受 Gemini hint regime 影響。 */
    private boolean hintGated = true;

    /** 允許持倉的 regime CSV。 */
    private String regimeWhitelist = "TRENDING_UP,SIDEWAYS,RECOVERY";

    /** Scheduler 設定(ms)。 */
    private Scheduler scheduler = new Scheduler();

    @Data
    public static class Scheduler {
        /** 檢查週期:預設 30 min。 */
        private long fixedDelayMs = 1_800_000L;
        /** 啟動延遲。 */
        private long initialDelayMs = 60_000L;
    }
}
