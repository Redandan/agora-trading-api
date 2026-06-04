package com.agora.service.backtest;

import com.agora.model.MarketIndicatorHistory;
import com.agora.repository.trading.MarketIndicatorHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CMI 指標驅動策略 — 通用型，每個 CMI 指標各部署一個策略實例。
 *
 * <p>從 {@code market_indicator_history} 讀取 CMI 複合指標分數，
 * 當分數越過閾值時生成 BUY/SELL 信號。
 *
 * <h3>Config 參數</h3>
 * <ul>
 *   <li>{@code mihIndicator}  — 指標 key，如 "sqi" / "vdi" / "short_build_index" 等</li>
 *   <li>{@code buyThreshold}  — 分數 ≥ 此值時 BUY（高分看多型，適用 SQI/SDI/ETF/ShortBuild）</li>
 *   <li>{@code buyBelow}      — 分數 ≤ 此值時 BUY（低分看多型，適用 VDI 低估做多）</li>
 *   <li>{@code sellAbove}     — 分數 ≥ 此值時 SELL（高分看空型，適用 VDI 高估做空）</li>
 *   <li>{@code requireAbove}  — 只在 CMI score >= 此值時才允許策略評估（品質門檻，可選）</li>
 *   <li>{@code stopLossPct}   — 止損比例（預設 0.03 = 3%）</li>
 *   <li>{@code takeProfitPct} — 止盈比例（預設 0.06 = 6%）</li>
 *   <li>{@code allowShort}    — 是否允許做空（預設 false）</li>
 * </ul>
 *
 * <h3>部署範例（6 個指標各一個策略）</h3>
 * <pre>
 *   SQI 做多策略      : mihIndicator=sqi,               buyThreshold=40
 *   ShortBuild 做多  : mihIndicator=short_build_index,  buyThreshold=35
 *   SDI 做多策略      : mihIndicator=stablecoin_demand_index, buyThreshold=50
 *   ETF 做多策略      : mihIndicator=etf_pressure_index, buyThreshold=60
 *   MEI 突破策略      : mihIndicator=market_entropy_index, buyThreshold=65
 *   VDI 低估做多      : mihIndicator=vdi, buyBelow=30
 *   VDI 高估做空      : mihIndicator=vdi, sellAbove=80, allowShort=true
 * </pre>
 *
 * <p>注意：CMI 數據從指標上線日（約 2026-04）開始，歷史回測樣本有限。
 * 建議先以 Shadow 模式運行 30+ 天收集 live 樣本再評估啟用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CmiMihStrategy implements Strategy {

    public static final String TYPE = "CMI_MIH_THRESHOLD";

    private final MarketIndicatorHistoryRepository indicatorRepo;

    // Cache: indicator_key -> [NavigableMap<capturedAt_epoch_min, score>, loadedAt_ms]
    // TTL = 5 分鐘，確保回測前 backfill 完成的新數據能被正確載入（#316）
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;
    private final ConcurrentHashMap<String, NavigableMap<Long, Double>> cache =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cacheLoadedAt = new ConcurrentHashMap<>();

    /**
     * 動態 TP 進場狀態追蹤（每個回測線程獨立）。
     * 記錄最近一次 BUY 信號的 [進場價, 進場清算量, 進場 bar index]。
     * 用 ThreadLocal 確保並發回測互不干擾；bar index 回退時自動重置（新回測開始）。
     */
    private final ThreadLocal<double[]> entryState = ThreadLocal.withInitial(() -> new double[]{0, 0, -1});

    @Override
    public String getType() { return TYPE; }

    @Override
    public Map<String, Object> defaultExecutionConfig() {
        return Map.ofEntries(
            Map.entry("mihIndicator",            "sqi"),
            Map.entry("buyThreshold",            40.0),
            Map.entry("buyBelow",                -1.0),   // disabled when < 0
            Map.entry("sellAbove",               101.0),  // disabled when > 100
            Map.entry("sellBelow",               -1.0),   // #425: disabled when < 0 (mirror of buyBelow for SHORT)
            Map.entry("requireAbove",            -1.0),   // disabled when < 0
            Map.entry("stopLossPct",             0.03),
            Map.entry("takeProfitPct",           0.06),
            Map.entry("allowShort",              false),
            Map.entry("requireFundingNonNegative", false), // #425: block SHORT when funding < 0
            Map.entry("useMaxIndicatorInBar",    false)  // #445 POC: use MAX score within [openTime, openTime+interval) instead of floor at openTime — captures intra-bar peaks for indicators that fluctuate faster than bar interval (e.g. SQI minute-resolution within 1h bar)
        );
    }

    @Override
    public StrategySignal evaluate(StrategyContext context, Map<String, Object> config) {
        int i = context.getIndex();
        var current = context.getCurrent();
        if (current == null || i < 5) return StrategySignal.HOLD;

        String indicator    = getString(config, "mihIndicator", "sqi");
        double buyThreshold = getDouble(config, "buyThreshold",  40.0);
        double buyBelow     = getDouble(config, "buyBelow",      -1.0);
        double sellAbove    = getDouble(config, "sellAbove",    101.0);
        double sellBelow    = getDouble(config, "sellBelow",     -1.0);  // #425: SHORT 觸發 (mirror of buyBelow)
        double requireAbove = getDouble(config, "requireAbove",  -1.0);
        boolean allowShort  = getBoolean(config, "allowShort", false);
        // requireAboveSma200: 收盤價需在 SMA200 之上才允許 BUY（過濾 BEARISH 市場逆勢做多）
        boolean requireAboveSma200      = getBoolean(config, "requireAboveSma200", false);
        // requireFundingImprovingBars: BUY 前 funding_rate 需高於前 N 小時均值（空頭成本上升中）
        int requireFundingImprovingBars  = (int) getDouble(config, "requireFundingImprovingBars", -1.0);
        // requireNoNewLowBars: BUY 前 N 小時內不能有比當前 low 更低的 bar（觸底確認）
        int requireNoNewLowBars          = (int) getDouble(config, "requireNoNewLowBars", -1.0);
        // #425: requireFundingNonNegative: 入場時 funding_rate 必須 ≥ 0（避免 SHORT 在負 funding 期間開倉，付給 longs 的 funding cost）
        boolean requireFundingNonNegative = getBoolean(config, "requireFundingNonNegative", false);

        boolean useMaxIndicatorInBar = getBoolean(config, "useMaxIndicatorInBar", false);
        Double score = useMaxIndicatorInBar
                ? getMaxScoreInBar(indicator, current.getOpenTime(),
                        intervalToMinutes(current.getIntervalCode()))
                : getScore(indicator, current.getOpenTime());
        // #398 — publish trigger-condition snapshot so SIGNAL_EVAL audit shows why HOLD.
        LiveSignalContext.putDetail("mih_indicator", indicator);
        LiveSignalContext.putDetail("mih_value", score);
        LiveSignalContext.putDetail("mih_aggregation", useMaxIndicatorInBar ? "bar_max" : "floor_at_open");
        LiveSignalContext.putDetail("buy_threshold", buyThreshold);
        LiveSignalContext.putDetail("buy_below", buyBelow);
        LiveSignalContext.putDetail("sell_above", sellAbove);
        LiveSignalContext.putDetail("sell_below", sellBelow);
        if (score == null) {
            LiveSignalContext.putDetail("hold_reason", "indicator_missing");
            return StrategySignal.HOLD;   // 指標數據不存在（上線前歷史）
        }

        // 品質門檻：若設定 requireAbove，只在指標有意義時才評估
        if (requireAbove >= 0 && score < requireAbove) {
            LiveSignalContext.putDetail("hold_reason", "below_require_above");
            LiveSignalContext.putDetail("require_above", requireAbove);
            return StrategySignal.HOLD;
        }

        // Regime 過濾：收盤價需在 SMA720（30天）之上才允許 BUY（防止 BEARISH 市場逆勢做多）
        if (requireAboveSma200 && current.getClosePrice() != null) {
            double[] sma720arr = context.getIndicators().get("sma720");
            if (sma720arr != null && i < sma720arr.length && sma720arr[i] > 0) {
                if (current.getClosePrice().doubleValue() < sma720arr[i]) {
                    LiveSignalContext.putDetail("hold_reason", "below_sma720");
                    LiveSignalContext.putDetail("sma720", sma720arr[i]);
                    return StrategySignal.HOLD;
                }
            }
        }

        // Funding rate 改善確認：當前 funding_rate 需高於前 N 小時均值（空頭成本上升趨勢）
        // 用於 ShortBuild：空頭積累 + funding 開始轉好才代表真正的軋空燃料
        if (requireFundingImprovingBars > 0 && current.getOpenTime() != null) {
            Double currentFunding = getScore("funding_rate", current.getOpenTime());
            if (currentFunding != null) {
                double sumFunding = 0;
                int countFunding = 0;
                for (int h = 1; h <= requireFundingImprovingBars; h++) {
                    Double pastFunding = getScore("funding_rate", current.getOpenTime().minusHours(h));
                    if (pastFunding != null) { sumFunding += pastFunding; countFunding++; }
                }
                if (countFunding > 0 && currentFunding <= sumFunding / countFunding) {
                    LiveSignalContext.putDetail("hold_reason", "funding_not_improving");
                    LiveSignalContext.putDetail("funding_rate", currentFunding);
                    LiveSignalContext.putDetail("funding_avg_prev", sumFunding / countFunding);
                    return StrategySignal.HOLD; // funding 仍在惡化（空頭持續獲利），非軋空入場點
                }
            }
        }

        // 觸底確認：前 N 小時內沒有比當前 low 更低的 bar（價格已穩定，不再創新低）
        // 用於 VDI：低估值 + 不再創新低，才是均值回歸入場點
        if (requireNoNewLowBars > 0 && i >= requireNoNewLowBars && current.getLowPrice() != null) {
            double currentLow = current.getLowPrice().doubleValue();
            List<com.agora.model.MdKline> klines = context.getKlines();
            double minRecentLow = Double.MAX_VALUE;
            for (int j = Math.max(0, i - requireNoNewLowBars); j < i; j++) {
                com.agora.model.MdKline k = klines.get(j);
                if (k.getLowPrice() != null) minRecentLow = Math.min(minRecentLow, k.getLowPrice().doubleValue());
            }
            if (minRecentLow < Double.MAX_VALUE && currentLow <= minRecentLow) {
                LiveSignalContext.putDetail("hold_reason", "creating_new_low");
                return StrategySignal.HOLD; // 仍在創新低，尚未觸底
            }
        }

        // 24h 漲幅過濾：BTC 已急漲 > maxGain24hPct，擠倉接近尾聲，不追高
        // 分析顯示：24h > 4% 的信號勝率 0%，過濾後 EV 從 0.78% → 1.59% / signal
        double maxGain24hPct = getDouble(config, "maxGain24hPct", -1.0); // -1 = 不過濾
        if (maxGain24hPct > 0 && i >= 24 && current.getClosePrice() != null) {
            var bar24h = context.getKlines().get(Math.max(0, i - 24));
            if (bar24h != null && bar24h.getClosePrice() != null) {
                double gain24h = (current.getClosePrice().doubleValue() - bar24h.getClosePrice().doubleValue())
                        / bar24h.getClosePrice().doubleValue() * 100.0;
                if (gain24h > maxGain24hPct) {
                    log.debug("[CMI_MIH] SKIP bar={} 24h_gain={:.2f}% > {:.1f}% threshold",
                            current.getOpenTime(), gain24h, maxGain24hPct);
                    LiveSignalContext.putDetail("hold_reason", "gain_24h_excessive");
                    LiveSignalContext.putDetail("gain_24h_pct", gain24h);
                    return StrategySignal.HOLD;
                }
            }
        }

        log.debug("[CMI_MIH] bar={} indicator={} score={}", current.getOpenTime(), indicator, score);

        // ── 動態 TP：使用真實進場狀態（ThreadLocal 追蹤）───────────────────────
        // liqExitThreshold: 當前清算量 < 「進場時清算量」× threshold（預設 0.5）
        // minGainPct: 相對「真實進場價」的浮盈需 > minGainPct（預設 1.5%）
        double liqExitThreshold = getDouble(config, "liqExitThreshold", -1.0);
        double minGainPct       = getDouble(config, "minGainPct",       0.015);

        // 重置偵測：如果 bar index 回退，表示新的回測開始
        double[] state = entryState.get();
        if (i <= (int) state[2] - 10 || i == 0) {
            state[0] = 0; state[1] = 0; state[2] = -1; // 重置
        }
        state[2] = i; // 更新最新 bar index

        if (liqExitThreshold > 0 && state[0] > 0 && current.getClosePrice() != null) {
            double entryClose = state[0];  // 進場時收盤價
            double entryLiq   = state[1];  // 進場時清算量

            Double currentLiq   = getScore("btc_short_liq_usd_1h", current.getOpenTime());
            double currentClose = current.getClosePrice().doubleValue();
            double realGain     = entryClose > 0 ? (currentClose - entryClose) / entryClose : 0;

            if (entryLiq > 0 && currentLiq != null
                    && currentLiq < entryLiq * liqExitThreshold
                    && realGain >= minGainPct) {
                log.debug("[CMI_MIH] LIQ_TP bar={} liq={} < entry_liq({})×{} gain={:.2f}%",
                        current.getOpenTime(), currentLiq, entryLiq, liqExitThreshold, realGain * 100);
                state[0] = 0; state[1] = 0; // 平倉後清除進場狀態
                return StrategySignal.SELL;
            }
        }

        // 高分看多：記錄進場狀態（進場價 + 進場清算量）
        if (buyThreshold >= 0 && score >= buyThreshold) {
            if (liqExitThreshold > 0 && current.getClosePrice() != null) {
                Double entryLiq = getScore("btc_short_liq_usd_1h", current.getOpenTime());
                state[0] = current.getClosePrice().doubleValue();
                state[1] = entryLiq != null ? entryLiq : 0;
            }
            LiveSignalContext.putDetail("trigger_reason", "buy_threshold_hit");
            return StrategySignal.BUY;
        }

        // 低分看多（VDI 低估）
        if (buyBelow >= 0 && score <= buyBelow) {
            LiveSignalContext.putDetail("trigger_reason", "buy_below_hit");
            return StrategySignal.BUY;
        }

        // 高分看空（VDI 高估，allowShort = true）
        if (allowShort && sellAbove <= 100 && score >= sellAbove) {
            // #425: requireFundingNonNegative — block SHORT 當 funding < 0
            // (negative funding = shorts pay longs，SHORT 部位反而要付 funding cost)
            if (requireFundingNonNegative && current.getOpenTime() != null) {
                Double currentFunding = getScore("funding_rate", current.getOpenTime());
                if (currentFunding != null && currentFunding < 0) {
                    LiveSignalContext.putDetail("hold_reason", "funding_negative_blocks_short");
                    LiveSignalContext.putDetail("funding_rate", currentFunding);
                    return StrategySignal.HOLD;
                }
            }
            LiveSignalContext.putDetail("trigger_reason", "sell_above_hit");
            return StrategySignal.SELL;
        }

        // #425: 低分看空（fade-SHORT 或 trend-follow SHORT，allowShort = true）
        // 例：long_short_ratio < 0.85 → SHORT (bears confirmed, ride down)
        if (allowShort && sellBelow >= 0 && score <= sellBelow) {
            // 同樣套 funding gate
            if (requireFundingNonNegative && current.getOpenTime() != null) {
                Double currentFunding = getScore("funding_rate", current.getOpenTime());
                if (currentFunding != null && currentFunding < 0) {
                    LiveSignalContext.putDetail("hold_reason", "funding_negative_blocks_short");
                    LiveSignalContext.putDetail("funding_rate", currentFunding);
                    return StrategySignal.HOLD;
                }
            }
            LiveSignalContext.putDetail("trigger_reason", "sell_below_hit");
            return StrategySignal.SELL;
        }

        LiveSignalContext.putDetail("hold_reason", "no_threshold_hit");
        return StrategySignal.HOLD;
    }

    // ── #450 Phase 3 — adjustExit: dynamic OCO management ────────────────────

    /**
     * #450 Phase 3 — CMI strategy 的 exit 邏輯。
     *
     * <p>5 條規則(ordered by priority):
     * <ol>
     *   <li><b>ALPHA_GONE_FORCE</b>:indicator score 跌破 gone threshold + 浮虧 → 立即 market exit</li>
     *   <li><b>TIME_DECAY_7D_FORCE</b>:持倉 ≥ 7 天 → 立即 market exit(thesis 已 exhausted)</li>
     *   <li><b>ALPHA_WEAKENED_LOCK</b>:indicator weakened + 浮盈 → tightenTp 鎖小利</li>
     *   <li><b>TIME_DECAY_5D</b>:持倉 ≥ 5 天 + 浮盈 &lt; target/2 → tighten 雙端</li>
     *   <li><b>TRAIL_SL_BE</b>:TP progress ≥ 50% → trail SL 到 BE+(只往上 ratchet)</li>
     * </ol>
     *
     * <p>Threshold per indicator(absolute,not relative to entry,因 Phase 2 entry snapshot 是 empty):
     * <table>
     *   <tr><th>Indicator</th><th>Trigger</th><th>Weak</th><th>Gone</th></tr>
     *   <tr><td>etf_pressure_index</td><td>≥60</td><td>&lt;50</td><td>&lt;40</td></tr>
     *   <tr><td>sqi</td><td>≥40</td><td>&lt;25</td><td>&lt;15</td></tr>
     *   <tr><td>market_entropy_index</td><td>≥70</td><td>&lt;60</td><td>&lt;50</td></tr>
     *   <tr><td>stablecoin_demand_index</td><td>≥40</td><td>&lt;30</td><td>&lt;20</td></tr>
     *   <tr><td>short_build_index</td><td>≥70</td><td>&lt;55</td><td>&lt;40</td></tr>
     * </table>
     */
    @Override
    public java.util.Optional<ExitAdjustment> adjustExit(
            StrategyContext ctx, OpenPositionView pos, Map<String, Object> config) {
        if (!"LONG".equals(pos.side())) return java.util.Optional.empty();
        if (pos.entryPrice() == null || pos.currentPrice() == null) return java.util.Optional.empty();

        String indicator = getString(config, "mihIndicator", "sqi");

        // Determine "now" — backtest=current bar's open time, live=latest kline open time
        java.time.LocalDateTime now = ctx.getCurrent() != null
                ? ctx.getCurrent().getOpenTime()
                : java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);

        Double currentScore = getScore(indicator, now);

        double targetTpPct = getDouble(config, "fixedTakeProfitPct", 0.05);
        java.math.BigDecimal currentPrice = pos.currentPrice();
        double[] thresholds = thresholdsFor(indicator, getDouble(config, "buyThreshold", 40.0));
        double weakThreshold = thresholds[0];
        double goneThreshold = thresholds[1];

        // Rule 1: ALPHA_GONE_FORCE — score < gone AND loss → force close
        if (currentScore != null && currentScore < goneThreshold && pos.inLoss()) {
            return java.util.Optional.of(ExitAdjustment.forceClose(
                String.format("%s alpha gone (%.1f < %.1f gone), in loss %.2f%%",
                        indicator, currentScore, goneThreshold,
                        pos.unrealizedPnlPct().doubleValue() * 100),
                "ALPHA_GONE_FORCE"
            ));
        }

        // Time decay configuration (per-strategy tunable):
        //   timeDecayHardHours      — 強制 forceClose 的時長(default 7d=168h)
        //   timeDecaySoftHours      — 開始 tighten 的時長(default 5d=120h)
        //   timeDecayProgressThreshold — 觸發 soft 的 TP progress 下限(default 0.30)
        // 邏輯:soft 只在 progress < threshold 才觸發(避免殺正在朝 TP 走的單)
        // ETF flow strategy 可 override 為 (soft=240h=10d, hard=480h=20d) 讓它 ride 更久
        long timeDecayHardHours = (long) getDouble(config, "timeDecayHardHours", 24L * 7);
        long timeDecaySoftHours = (long) getDouble(config, "timeDecaySoftHours", 24L * 5);
        double timeDecayProgressThreshold = getDouble(config, "timeDecayProgressThreshold", 0.30);

        // Rule 2: TIME_DECAY_HARD_FORCE — 強制平倉的時間上限
        if (pos.ageHours() >= timeDecayHardHours) {
            return java.util.Optional.of(ExitAdjustment.forceClose(
                String.format("Hard time decay (held %dh >= %dh)", pos.ageHours(), timeDecayHardHours),
                "TIME_DECAY_HARD_FORCE"
            ));
        }

        // Rule 3: ALPHA_WEAKENED_TRAIL — score < weak AND profit → trail SL up, leave TP alone
        // 改自 ALPHA_WEAKENED_LOCK(原 tightenTp +0.5% 太兇,把 trend rides 全砍成小利)。
        // 新邏輯:trail SL 到 max(BE+, current×0.985),保留 downside protection 但不殺 upside。
        //
        // Per-strategy gating: trend-ride strategies(ETF flow / multi-day hold)應 disable
        // 此規則,讓 trend 自然走完。SQI scalper 等短線策略保持 enabled。
        boolean alphaWeakenedEnabled = getBoolean(config, "alphaWeakenedEnabled", true);
        if (alphaWeakenedEnabled && currentScore != null && currentScore < weakThreshold && pos.inProfit()) {
            java.math.BigDecimal bePlusFloor = pos.entryPrice().multiply(java.math.BigDecimal.valueOf(1.005));
            java.math.BigDecimal currentFloor = currentPrice.multiply(java.math.BigDecimal.valueOf(0.985));
            java.math.BigDecimal newSl = (bePlusFloor.compareTo(currentFloor) > 0 ? bePlusFloor : currentFloor)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            // 只往上 ratchet,不放大 risk
            if (pos.currentSl() == null || newSl.compareTo(pos.currentSl()) > 0) {
                return java.util.Optional.of(ExitAdjustment.trailingSl(
                    newSl,
                    String.format("%s alpha weakened (%.1f < %.1f weak), trail SL to %s",
                            indicator, currentScore, weakThreshold, newSl.toPlainString()),
                    "ALPHA_WEAKENED_TRAIL"
                ));
            }
        }

        // Rule 4: TIME_DECAY_SOFT — held >= soft AND TP progress < threshold
        // 關鍵改動 (#450 Option C):progress >= threshold 不觸發,讓朝 TP 走的單 ride
        if (pos.ageHours() >= timeDecaySoftHours) {
            double progress = pos.tpProgressPct(targetTpPct);
            if (progress < timeDecayProgressThreshold) {
                java.math.BigDecimal newTp = currentPrice.multiply(java.math.BigDecimal.valueOf(1.01))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                java.math.BigDecimal newSl = pos.entryPrice().multiply(java.math.BigDecimal.valueOf(0.99))
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                return java.util.Optional.of(ExitAdjustment.tighten(
                    newTp, newSl,
                    String.format("Soft time decay (held %dh, progress=%.0f%% < %.0f%%)",
                            pos.ageHours(), progress * 100, timeDecayProgressThreshold * 100),
                    "TIME_DECAY_SOFT"
                ));
            }
        }

        // Rule 5: TRAIL_SL_BE — TP progress ≥ 50% → SL ratchets up to BE+
        double tpProgress = pos.tpProgressPct(targetTpPct);
        if (tpProgress >= 0.5) {
            java.math.BigDecimal newSl = pos.entryPrice().multiply(java.math.BigDecimal.valueOf(1.005))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (pos.currentSl() == null || newSl.compareTo(pos.currentSl()) > 0) {
                return java.util.Optional.of(ExitAdjustment.trailingSl(
                    newSl,
                    String.format("TP progress %.0f%%, trail SL to BE+", tpProgress * 100),
                    "TRAIL_SL_BE"
                ));
            }
        }

        return java.util.Optional.empty();
    }

    /** Return {weakThreshold, goneThreshold} for given indicator. */
    private static double[] thresholdsFor(String indicator, double buyThreshold) {
        switch (indicator) {
            case "etf_pressure_index":     return new double[]{50.0, 40.0};
            case "sqi":                    return new double[]{25.0, 15.0};
            case "market_entropy_index":   return new double[]{60.0, 50.0};
            case "stablecoin_demand_index":return new double[]{30.0, 20.0};
            case "short_build_index":      return new double[]{55.0, 40.0};
            default:
                // Unknown indicator → derive from buyThreshold
                return new double[]{buyThreshold * 0.6, buyThreshold * 0.4};
        }
    }

    // ── 數據讀取 ──────────────────────────────────────────────────────────────

    /**
     * 取得指定時間點之前最近一筆 CMI 分數（不使用未來數據）。
     * 懶加載並快取到記憶體；CMI 分數每分鐘更新，用 NavigableMap floorKey 做毫秒對齊。
     */
    private Double getScore(String indicator, LocalDateTime barTime) {
        // TTL 檢查：5 分鐘後重新載入，確保 backfill 完成後的新數據能生效
        long now = System.currentTimeMillis();
        Long loadedAt = cacheLoadedAt.get(indicator);
        if (loadedAt == null || (now - loadedAt) > CACHE_TTL_MS) {
            cache.remove(indicator);
            cacheLoadedAt.put(indicator, now);
        }
        NavigableMap<Long, Double> map = cache.computeIfAbsent(indicator, this::loadIndicator);
        if (map.isEmpty()) return null;

        long barEpoch = barTime.toEpochSecond(ZoneOffset.UTC) / 60; // 分鐘精度
        Map.Entry<Long, Double> entry = map.floorEntry(barEpoch);
        return entry != null ? entry.getValue() : null;
    }

    /**
     * #445 POC — 取 [barOpenTime, barOpenTime + interval) 區間內 indicator 的 MAX 值。
     *
     * <p>解決 1h K-line + minute-level SQI 採樣不對齊問題:1h bar 視角下,
     * floorEntry(openTime) 只看 bar 開始那一刻的單一 sample,會 miss bar 期間
     * peak 過 threshold 但 close 時已 decay 的 event。改 max 抓 peak。
     *
     * <p>無 future leak:評估發生在 bar close 時,所有 [openTime, closeTime) 區間
     * 的 SQI sample 都已是過去資料。
     */
    private Double getMaxScoreInBar(String indicator, LocalDateTime barOpenTime, int barIntervalMinutes) {
        long now = System.currentTimeMillis();
        Long loadedAt = cacheLoadedAt.get(indicator);
        if (loadedAt == null || (now - loadedAt) > CACHE_TTL_MS) {
            cache.remove(indicator);
            cacheLoadedAt.put(indicator, now);
        }
        NavigableMap<Long, Double> map = cache.computeIfAbsent(indicator, this::loadIndicator);
        if (map.isEmpty()) return null;

        long startEpoch = barOpenTime.toEpochSecond(ZoneOffset.UTC) / 60;
        long endEpoch = startEpoch + barIntervalMinutes;

        Double maxValue = null;
        for (Double v : map.subMap(startEpoch, true, endEpoch, false).values()) {
            if (maxValue == null || v > maxValue) maxValue = v;
        }

        if (maxValue == null) {
            // Fallback: bar 內無 sample 時退回 floor (最近一筆 ≤ openTime)
            Map.Entry<Long, Double> floor = map.floorEntry(startEpoch);
            return floor != null ? floor.getValue() : null;
        }
        return maxValue;
    }

    private static int intervalToMinutes(String code) {
        if (code == null) return 60;
        return switch (code) {
            case "1m"  -> 1;
            case "3m"  -> 3;
            case "5m"  -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h"  -> 60;
            case "2h"  -> 120;
            case "4h"  -> 240;
            case "6h"  -> 360;
            case "12h" -> 720;
            case "1d"  -> 1440;
            default    -> 60;
        };
    }

    private NavigableMap<Long, Double> loadIndicator(String indicator) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusDays(180);
        // #384 — filter error_flag=1 outliers from indicator history
        List<MarketIndicatorHistory> rows = indicatorRepo
                .findCleanBySymbolAndIndicatorAndCapturedAtAfter(
                        "BTCUSDT", indicator, since);
        NavigableMap<Long, Double> map = new TreeMap<>();
        for (MarketIndicatorHistory r : rows) {
            long key = r.getCapturedAt().toEpochSecond(ZoneOffset.UTC) / 60;
            map.put(key, r.getValue().doubleValue());
        }
        log.info("[CMI_MIH] loaded {} rows for indicator={}", map.size(), indicator);
        return map;
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static double getDouble(Map<String, Object> m, String key, double def) {
        Object v = m.get(key);
        return v instanceof Number ? ((Number) v).doubleValue() : def;
    }

    private static boolean getBoolean(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number)  return ((Number) v).intValue() != 0;
        return def;
    }

    private static String getString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String ? (String) v : def;
    }
}
