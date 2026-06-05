package com.agora.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("local-smoke")
class TradingApiApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Test
    void contextLoads() {
    }

    @Test
    void localSmokeDoesNotRegisterScheduledTasks() {
        assertTrue(applicationContext.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
    }

    @Test
    void localSmokeMcpAuthKeysAreConfigured() {
        assertEquals("local-smoke-mcp", environment.getProperty("trading.mcp.api-key"));
        assertFalse(environment.getProperty("mcp.api-key", "").isBlank());
        assertFalse(environment.getProperty("mcp.ops-key", "").isBlank());
    }
}
