package com.agora.service.trading;

import com.agora.config.OkxTradingProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtStrategy;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.meta.DecisionAuditWriter;
import com.agora.service.tradingview.TradingViewScoreBuyAutoExitLiveService;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SpotEvidenceIsolationTest {
    @Test void failedForwardEvidenceDispatchDoesNotTurnNoSignalIntoOrderOrError() {
        AtomicBoolean attempted = new AtomicBoolean();
        var writer = new DecisionAuditWriter(null, null, null) {
            @Override public void logSpotEntryEvaluation(Long strategyId, String interval,
                    LocalDateTime bar, Map<String, Object> context) {
                attempted.set(true);
                assertEquals("NO_BUY_INTENT", context.get("disposition"));
                throw new IllegalStateException("executor rejected observation");
            }
        };
        var positions = (BtLiveSignalRepository) Proxy.newProxyInstance(BtLiveSignalRepository.class.getClassLoader(),
                new Class<?>[]{BtLiveSignalRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull"))
                        return List.of();
                    throw new AssertionError("Unexpected position operation: " + method.getName());
                });
        var properties = new TradingViewLocalSignalProperties(true, 485, "BTCUSDT", "1d", "binance", 320, 3, 72,
                BigDecimal.TEN, new BigDecimal("80"), TradingViewLocalSignalProperties.ExecutionMode.BTC_BASE_LIVE,
                new BigDecimal("250"), 15);
        var okx = new OkxTradingProperties(); okx.setEnabled(true);
        okx.setApiKey("offline-fixture"); okx.setSecretKey("offline-fixture"); okx.setPassphrase("offline-fixture");
        // No exchange adapter exists in this fixture; any accidental access fails.
        var live = new TradingViewScoreBuyAutoExitLiveService(properties, okx, null, positions, writer, null);
        var bar = SpotPerformancePolicyTest.bar(); var now = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        bar.setSource("binance"); bar.setIntervalCode("1d"); bar.setOpenTime(now.minusDays(1)); bar.setCloseTime(now);
        var strategy = new BtStrategy(); strategy.setId(485L);
        assertDoesNotThrow(() -> live.evaluate(strategy, bar, "binance", List.of(), Map.of()));
        assertTrue(attempted.get());
    }
}
