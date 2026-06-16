package com.agora.trading;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("local-smoke")
@AutoConfigureMockMvc
class TradingApiApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private Environment environment;

    @Autowired
    private MockMvc mockMvc;

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

    @Test
    void nonPublicHttpRoutesAreDeniedWithoutLoginFallback() throws Exception {
        mockMvc.perform(get("/admin/anything")).andExpect(status().isForbidden());
        mockMvc.perform(get("/internal/anything")).andExpect(status().isForbidden());
    }

    @Test
    void actuatorMetricsRequireLocalhostOrMcpKey() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(request -> {
            request.setRemoteAddr("203.0.113.10");
            return request;
        })).andExpect(status().isUnauthorized());

        String mcpKey = environment.getProperty("mcp.api-key", "");
        assertFalse(mcpKey.isBlank());

        mockMvc.perform(get("/actuator/metrics")
                        .header("Authorization", "Bearer " + mcpKey)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.10");
                            return request;
                        }))
                .andExpect(status().isNotFound());
    }

    @Test
    void mcpMetadataMethodsRequireDevOrOpsKey() throws Exception {
        String toolsListRequest = """
                {"jsonrpc":"2.0","id":"tools","method":"tools/list","params":{}}
                """;

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content(toolsListRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32001));

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .header("Authorization", "Bearer wrong-mcp-key")
                        .content(toolsListRequest))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value(-32001));

        String mcpKey = environment.getProperty("mcp.api-key", "");
        assertFalse(mcpKey.isBlank());

        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + mcpKey)
                        .content(toolsListRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools").isArray());
    }
}
