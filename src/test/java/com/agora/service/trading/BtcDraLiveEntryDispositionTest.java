package com.agora.service.trading;

import com.agora.config.properties.BtcDraRuntimeProperties;
import com.agora.model.BtLiveSignal;
import com.agora.model.MdKline;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.service.meta.DecisionAuditWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BtcDraLiveEntryDispositionTest {
    @Test void anOccupiedLotReportsExactBlockerBeforeAnyProviderOrReservationAccess() throws Exception {
        var row = new BtLiveSignal(); row.setAutoTraded(true);
        row.setFilterReason(BtcBasePositionStatePolicy.DRA_V1_POSITION_PREFIX + "OPEN");
        var repository = (BtLiveSignalRepository) Proxy.newProxyInstance(BtLiveSignalRepository.class.getClassLoader(),
                new Class<?>[]{BtLiveSignalRepository.class}, (p, method, args) -> {
                    if (method.getName().equals("findByStrategyIdAndSymbolAndIntervalCodeAndBarOpenTime")) return Optional.empty();
                    if (method.getName().equals("findByStrategyIdAndSymbolAndIntervalCodeAndExitTimeIsNullAndNotifiedAtIsNotNull")) return List.of(row);
                    throw new AssertionError("Unexpected DB write/read: " + method.getName());
                });
        List<String> reasons = new ArrayList<>();
        var writer = new DecisionAuditWriter(null, null, null) {
            @Override public void logEntrySkip(Long id, String symbol, String interval, LocalDateTime bar,
                    String blocker, String reason, Map<String, Object> context, Long liveSignalId) {
                reasons.add(blocker); assertEquals(true, context.get("entryCandidate"));
            }
        };
        var props = new BtcDraRuntimeProperties(BtcDraRuntimeProperties.Mode.LIVE, new BigDecimal("30"), new BigDecimal("30"), 15);
        // Provider and execution-attempt dependencies deliberately absent: accidental access fails.
        var service = new BtcDraLiveExecutionService(props, null, null, null, repository, null, writer, null, new ObjectMapper());
        var bar = new MdKline(); bar.setOpenTime(LocalDateTime.of(2024, 1, 10, 23, 0)); bar.setCloseTime(bar.getOpenTime().plusHours(1));
        var step = new BtcDraShadowEngine.StepResult(null, null, List.of(BtcDraExecutionContractTest.event("VIRTUAL_ENTRY_QUEUED")));
        var observation = new BtcDraRuntimeLaneService.RuntimeObservation(bar, step, false, false, 1, 0, 1L);
        var method = BtcDraLiveExecutionService.class.getDeclaredMethod("executeBuy", BtcDraRuntimeLaneService.RuntimeObservation.class);
        method.setAccessible(true);
        assertEquals("BLOCKED:DRA_SINGLE_LOT_ALREADY_OPEN", method.invoke(service, observation));
        row.setAutoTraded(false);
        assertEquals("BLOCKED:DRA_UNRESOLVED_ORDER_RESERVATION", method.invoke(service, observation));
        assertEquals(List.of("DRA_SINGLE_LOT_ALREADY_OPEN", "DRA_UNRESOLVED_ORDER_RESERVATION"), reasons);
    }
}
