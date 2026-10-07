package com.agora.service.advice;

import com.agora.config.BtcOrderAdviceProperties;
import com.agora.config.TradingInternalApiProperties;
import com.agora.infra.bot.InternalTradingReportController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BtcOrderAdviceControllerTest {
    @TempDir Path temp;
    @Test void protectedEndpointRejectsMissingInvalidAndUnconfiguredKeysBeforeReadingData() {
        var controller = new InternalTradingReportController(null, new TradingInternalApiProperties("local-test-key"), null);
        assertEquals(401, controller.btcOrderAdvice(null).getStatusCode().value());
        assertEquals(401, controller.btcOrderAdvice("wrong").getStatusCode().value());
        var absent = new InternalTradingReportController(null, new TradingInternalApiProperties(""), null);
        assertEquals(401, absent.btcOrderAdvice("local-test-key").getStatusCode().value());
    }
    @Test void authorizedEndpointReturnsStructuredPlanWithoutNotificationService() {
        var props = new BtcOrderAdviceProperties(); props.setStateFile(temp.resolve("state.json").toString());
        var service = new BtcOrderAdviceService(new BtcOrderAdviceSnapshotReaderTest().reader(), props,
                new ObjectMapper().findAndRegisterModules(), null);
        var controller = new InternalTradingReportController(null, new TradingInternalApiProperties("local-test-key"), service);
        var response = controller.btcOrderAdvice("local-test-key");
        assertEquals(200, response.getStatusCode().value());
        assertEquals("PLAN", ((BtcOrderAdviceService.View) response.getBody()).state().advice().status());
    }
}
