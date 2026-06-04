package com.agora.service.backtest;

import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtStrategyRepository;
import com.agora.repository.trading.MdKlineRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * V041 啟動防線：檢查每個 enabled 策略的 {@code klineSource} 在 md_kline 是否
 * 已備妥足夠樣本供指標計算。
 *
 * <p>觸發情境：
 * <ul>
 *   <li>策略被切到一個還未累積歷史的 source（例：binance→okx 但 okx 只有 3 天資料）</li>
 *   <li>資料匯入流程中斷，某 (symbol, interval, source) 組合沒寫入</li>
 *   <li>人工 UPDATE bt_strategy.kline_source 填入錯字（例："okex"）</li>
 * </ul>
 *
 * <p>任一組合不足 {@link #MIN_BARS_REQUIRED} 根 bar 即 throw，使 Spring 啟動失敗
 * （deploy.sh 會偵測到 READY 逾時並 rollback）。比起讓 live 信號默默讀到空資料好。
 *
 * <p>僅檢查 {@code 1h} / {@code 4h}：這兩個是 WS 主動訂閱的週期，也是所有信號策略
 * 實際使用的週期。{@code 1d} 由 MTF 依需要載入，樣本需求低（≤100 bar），不在此 gate。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EnabledStrategyDataValidator {

    private static final int MIN_BARS_REQUIRED = 200;
    private static final List<String> CHECKED_INTERVALS = Arrays.asList("1h", "4h");

    private final BtStrategyRepository strategyRepository;
    private final MdKlineRepository klineRepository;
    @Value("${backtest.enabled-strategy-validator.enabled:true}")
    private boolean startupValidationEnabled;

    /**
     * 以 {@link PostConstruct} 而非 {@code @EventListener(ApplicationReadyEvent.class)}：
     * 後者的 exception 不會一定終止 Spring 啟動（只會被 log）。@PostConstruct 失敗則直接讓 bean
     * 初始化 fail → context refresh fail → SpringApplication.run() 丟 → JVM exit，這樣 deploy.sh
     * 偵測 READY 逾時後會 rollback 舊 instance。
     */
    @PostConstruct
    public void validateOnStartup() {
        if (!startupValidationEnabled) {
            log.warn("[KlineDataValidator] Startup validation disabled by config backtest.enabled-strategy-validator.enabled=false");
            return;
        }
        List<BtStrategy> enabled = strategyRepository.findByEnabled(Boolean.TRUE);
        if (enabled.isEmpty()) {
            log.info("[KlineDataValidator] No enabled strategies; skipping bar-count check.");
            return;
        }

        List<String> failures = new ArrayList<>();
        Map<String, Long> barCountCache = new HashMap<>();
        for (BtStrategy s : enabled) {
            String source = s.getKlineSource();
            if (source == null || source.isBlank()) {
                failures.add(String.format(
                        "strategy id=%d (%s) has blank klineSource — column is NOT NULL with DEFAULT 'okx'; " +
                                "DB state is inconsistent",
                        s.getId(), s.getName()));
                continue;
            }
            String normalizedSource = source.toLowerCase();

            String symbols = s.getSymbols();
            if (symbols == null || symbols.isBlank()) {
                failures.add(String.format(
                        "strategy id=%d (%s) has no symbols configured",
                        s.getId(), s.getName()));
                continue;
            }

            for (String rawSymbol : symbols.split(",")) {
                String symbol = rawSymbol.trim().toUpperCase();
                if (symbol.isEmpty()) continue;
                for (String interval : CHECKED_INTERVALS) {
                    String cacheKey = normalizedSource + "|" + symbol + "|" + interval;
                    long bars = barCountCache.computeIfAbsent(cacheKey,
                            ignored -> klineRepository.countBySymbolAndIntervalCodeAndSource(
                                    symbol, interval, normalizedSource));
                    if (bars < MIN_BARS_REQUIRED) {
                        failures.add(String.format(
                                "strategy id=%d (%s) requires source=%s symbol=%s interval=%s " +
                                        "with >=%d bars but md_kline has only %d",
                                s.getId(), s.getName(), normalizedSource, symbol, interval,
                                MIN_BARS_REQUIRED, bars));
                    } else {
                        log.info("[KlineDataValidator] OK strategy={} symbol={} interval={} source={} bars={}",
                                s.getId(), symbol, interval, normalizedSource, bars);
                    }
                }
            }
        }

        if (!failures.isEmpty()) {
            String joined = String.join("\n  - ", failures);
            String msg = "Enabled strategies are missing kline data. Either backfill md_kline " +
                    "(see backfillOkxKlines / kline-admin.md) or UPDATE bt_strategy.kline_source " +
                    "to a source with coverage. Problems:\n  - " + joined;
            log.error("[KlineDataValidator] STARTUP ABORT:\n{}", msg);
            throw new IllegalStateException(msg);
        }
        log.info("[KlineDataValidator] All {} enabled strategies have sufficient kline coverage.", enabled.size());
    }
}
