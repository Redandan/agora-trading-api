package com.agora.service.trading;

import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.config.properties.TradingViewLocalSignalProperties;
import com.agora.model.BtDecisionAudit;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SpotPerformanceObservationServiceTest {
    @Test void duplicateAndHistoricalBarsDoNotCreateNewSnapshotsAndDbFailureDoesNotEscape() throws Exception {
        List<BtDecisionAudit> saved = new ArrayList<>();
        BtDecisionAuditRepository audits = proxy(BtDecisionAuditRepository.class, (name, args) -> {
            if (name.startsWith("existsBy")) return saved.stream().anyMatch(a -> a.getStrategyId().equals(args[0])
                    && a.getBarOpenTime().equals(args[3]));
            if (name.equals("saveAndFlush")) { saved.add((BtDecisionAudit) args[0]); return args[0]; }
            throw new AssertionError(name);
        });
        BtLiveSignalRepository positions = proxy(BtLiveSignalRepository.class, (name, args) -> {
            if (name.equals("findByStrategyIdAndSymbol")) return List.of();
            throw new AssertionError(name);
        });
        var service = new SpotPerformanceObservationService(positions, audits,
                defaults(BtcDraRuntimeProperties.class), defaults(TradingViewLocalSignalProperties.class), new ObjectMapper());
        var bar = SpotPerformancePolicyTest.bar();
        var now = LocalDateTime.of(2026, 10, 2, 12, 0);
        bar.setCloseTime(now); bar.setOpenTime(now.minusHours(1));
        service.afterClosedBarAt(bar, now); service.afterClosedBarAt(bar, now);
        assertEquals(2, saved.size());
        bar.setCloseTime(now.minusHours(1)); bar.setOpenTime(now.minusHours(2));
        service.afterClosedBarAt(bar, now); assertEquals(2, saved.size());
        var failing = new SpotPerformanceObservationService(positions,
                proxy(BtDecisionAuditRepository.class, (name, args) -> { throw new IllegalStateException("DB down"); }),
                defaults(BtcDraRuntimeProperties.class), defaults(TradingViewLocalSignalProperties.class), new ObjectMapper());
        bar.setCloseTime(now); bar.setOpenTime(now.minusHours(1));
        assertDoesNotThrow(() -> failing.afterClosedBarAt(bar, now));
    }

    private static <T> T defaults(Class<T> type) throws Exception {
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] types = java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new);
        Object[] values = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class) values[i] = false;
            else if (types[i] == int.class) values[i] = 0;
            else if (types[i] == long.class) values[i] = 0L;
        }
        return type.getDeclaredConstructor(types).newInstance(values);
    }
    @SuppressWarnings("unchecked") private static <T> T proxy(Class<T> type, Invocation call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.invoke(method.getName(), args));
    }
    private interface Invocation { Object invoke(String name, Object[] args); }
}
