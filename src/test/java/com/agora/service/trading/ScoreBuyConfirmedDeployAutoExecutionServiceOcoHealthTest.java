package com.agora.service.trading;

import com.agora.model.BtLiveSignal;
import com.agora.repository.trading.BtDecisionAuditRepository;
import com.agora.repository.trading.BtLiveSignalRepository;
import com.agora.repository.trading.RuntimeDecisionEvidenceRepository;
import com.agora.service.TelegramService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreBuyConfirmedDeployAutoExecutionServiceOcoHealthTest {

    @Test
    void confirmedDeployHealthBlocksFilledSecondChild() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        BtLiveSignalRepository repository = mock(BtLiveSignalRepository.class);
        OkxTradingService okx = mock(OkxTradingService.class);
        BtLiveSignal position = new BtLiveSignal();
        position.setId(260L);
        position.setSymbol("BTCUSDT");
        position.setOcoOrderListId(1260L);
        when(repository.findByStrategyIdAndAutoTradedIsTrueAndExitTimeIsNull(485L))
                .thenReturn(List.of(position));
        when(okx.getAlgoOrder("BTCUSDT", 1260L)).thenReturn(mapper.readTree(
                "{\"state\":\"effective\",\"ordIdList\":[\"tp-260\",\"sl-260\"]}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "tp-260"))
                .thenReturn(mapper.readTree("{\"state\":\"live\"}"));
        when(okx.querySpotOrderDetail("BTCUSDT", "sl-260"))
                .thenReturn(mapper.readTree("{\"state\":\"filled\",\"avgPx\":\"88\"}"));
        ScoreBuyConfirmedDeployAutoExecutionService service =
                new ScoreBuyConfirmedDeployAutoExecutionService(
                        mock(ScoreBuyConfirmedDeployPreviewService.class),
                        mock(RuntimeDecisionEvidenceService.class),
                        okx,
                        new OcoOrderStateInspector(okx),
                        repository,
                        mock(BtDecisionAuditRepository.class),
                        mock(RuntimeDecisionEvidenceRepository.class),
                        mock(TelegramService.class),
                        mapper,
                        new MockEnvironment());

        Object health = ReflectionTestUtils.invokeMethod(
                service, "checkExistingOcoHealth", 485L, "BTCUSDT");
        Boolean okResult = ReflectionTestUtils.invokeMethod(health, "ok");

        assertThat(okResult).isFalse();
        assertThat((String) ReflectionTestUtils.invokeMethod(health, "reason"))
                .contains("OCO_FILLED_DB_OPEN:position=260");
    }
}
