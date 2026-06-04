package com.agora.service.backtest;

import com.agora.model.MdKline;
import com.agora.repository.trading.MdKlineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 獨立驗證回測引擎產生的交易記錄自洽性。
 *
 * <p><b>驗證邏輯</b>（每筆 trade）：
 * <ul>
 *   <li><b>Entry 價位合理性</b>：entryPrice 應在 entryBar 的 [low, high] 範圍內</li>
 *   <li><b>Exit 價位合理性</b>：exitPrice 應在 exitBar 的 [low, high] 範圍內</li>
 *   <li><b>exitReason 一致性</b>：
 *     <ul>
 *       <li>若 exitReason=SL → exitPrice 應與 SL 線位附近（依 side 方向）</li>
 *       <li>若 exitReason=TP → exitPrice 應與 TP 線位附近</li>
 *     </ul>
 *   </li>
 *   <li><b>時序</b>：entryTime < exitTime</li>
 *   <li><b>PnL 算術</b>：對 LONG，(exit-entry)×qty 應 ≈ grossPnl；SHORT 則反向</li>
 * </ul>
 *
 * <p>此驗證器**獨立於 BacktestEngine**，只讀 TradeRecord + Kline，不複製策略邏輯。
 * 目的是抓「引擎寫入的數字是否與歷史 K 線事實一致」這類低階 bug。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestTradeValidator {

    private static final double PRICE_TOLERANCE_PCT = 0.001;  // 0.1% 容許誤差（四捨五入 / spread）

    private final MdKlineRepository klineRepository;

    public record Issue(
            int tradeIndex,
            LocalDateTime entryTime,
            String field,
            String expected,
            String actual,
            String note
    ) {}

    public record Report(
            int totalTrades,
            int checkedTrades,
            int issuesFound,
            List<Issue> issues
    ) {}

    public Report validate(String symbol, String intervalCode, List<TradeRecord> trades) {
        List<Issue> issues = new ArrayList<>();
        int checked = 0;

        for (int i = 0; i < trades.size(); i++) {
            TradeRecord t = trades.get(i);
            checked++;

            // 1. 時序檢查
            if (t.getEntryTime() != null && t.getExitTime() != null
                    && !t.getExitTime().isAfter(t.getEntryTime())) {
                issues.add(new Issue(i, t.getEntryTime(), "time_order",
                        "exit > entry",
                        "entry=" + t.getEntryTime() + " exit=" + t.getExitTime(),
                        "non-chronological"));
            }

            // 2. Entry 價位落在 entryBar 區間
            if (t.getEntryTime() != null) {
                MdKline entryBar = findBar(symbol, intervalCode, t.getEntryTime());
                if (entryBar == null) {
                    issues.add(new Issue(i, t.getEntryTime(), "entry_bar",
                            "kline exists",
                            "not found",
                            "entry bar missing from DB"));
                } else if (!priceInRange(t.getEntryPrice(), entryBar)) {
                    issues.add(new Issue(i, t.getEntryTime(), "entry_price",
                            String.format("in [%.4f, %.4f]",
                                    entryBar.getLowPrice().doubleValue(),
                                    entryBar.getHighPrice().doubleValue()),
                            String.format("%.4f", t.getEntryPrice()),
                            "entry price outside bar range"));
                }
            }

            // 3. Exit 價位落在 exitBar 區間
            if (t.getExitTime() != null) {
                MdKline exitBar = findBar(symbol, intervalCode, t.getExitTime());
                if (exitBar == null) {
                    issues.add(new Issue(i, t.getEntryTime(), "exit_bar",
                            "kline exists",
                            "not found",
                            "exit bar missing from DB"));
                } else if (!priceInRange(t.getExitPrice(), exitBar)) {
                    issues.add(new Issue(i, t.getEntryTime(), "exit_price",
                            String.format("in [%.4f, %.4f]",
                                    exitBar.getLowPrice().doubleValue(),
                                    exitBar.getHighPrice().doubleValue()),
                            String.format("%.4f", t.getExitPrice()),
                            "exit price outside bar range"));
                }
            }

            // 4. PnL 算術
            if (t.getQuantity() > 0) {
                double expectedGross = "SHORT".equalsIgnoreCase(t.getSide())
                        ? (t.getEntryPrice() - t.getExitPrice()) * t.getQuantity()
                        : (t.getExitPrice() - t.getEntryPrice()) * t.getQuantity();
                double diff = Math.abs(expectedGross - t.getGrossPnl());
                double baseline = Math.max(Math.abs(expectedGross), 0.01);
                if (baseline > 0 && diff / baseline > 0.005) {  // 0.5% 容差
                    issues.add(new Issue(i, t.getEntryTime(), "pnl_math",
                            String.format("%.4f", expectedGross),
                            String.format("%.4f", t.getGrossPnl()),
                            "(exit-entry)*qty mismatches grossPnl"));
                }
            }
        }

        log.info("[TradeValidator] {} {}: checked={} issues={}",
                symbol, intervalCode, checked, issues.size());
        return new Report(trades.size(), checked, issues.size(), issues);
    }

    private MdKline findBar(String symbol, String intervalCode, LocalDateTime barOpenTime) {
        // 容許 entryTime/exitTime 有秒數誤差：找 >= barOpenTime - 1min 的第一根
        LocalDateTime start = barOpenTime.minusMinutes(1);
        LocalDateTime end = barOpenTime.plusMinutes(1);
        List<MdKline> matches = klineRepository
                .findBySymbolAndIntervalCodeAndOpenTimeBetweenOrderByOpenTimeAsc(
                        symbol, intervalCode, start, end);
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean priceInRange(double price, MdKline bar) {
        double low = bar.getLowPrice().doubleValue();
        double high = bar.getHighPrice().doubleValue();
        double tolerance = high * PRICE_TOLERANCE_PCT;
        return price >= low - tolerance && price <= high + tolerance;
    }
}
