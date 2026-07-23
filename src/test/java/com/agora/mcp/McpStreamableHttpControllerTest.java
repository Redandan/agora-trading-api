package com.agora.mcp;

import com.agora.exception.BusinessException;
import com.agora.mcp.auth.McpApiKeyFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class McpStreamableHttpControllerTest {

    @Test
    void wrappedIllegalArgumentIsInvalidParamsWithoutRuntimeError(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = controllerThrowing(
                new IllegalArgumentException("algoId must contain digits only"))
                .handleMcp(toolCall());

        assertInvalidParams(response, "algoId must contain digits only");
        assertThat(output.getOut())
                .contains("Rejected invalid params method=tools/call")
                .doesNotContain("Error handling method=tools/call");
    }

    @Test
    void wrappedBusinessValidationIsInvalidParamsWithoutRuntimeError(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = controllerThrowing(
                new BusinessException("Invalid taskType=AGORA_MARKET_ISSUE_SCOUT"))
                .handleMcp(toolCall());

        assertInvalidParams(response, "Invalid taskType=AGORA_MARKET_ISSUE_SCOUT");
        assertThat(output.getOut())
                .contains("Rejected invalid params method=tools/call")
                .doesNotContain("Error handling method=tools/call");
    }

    @Test
    void unexpectedToolFailureRemainsInternalError(CapturedOutput output) {
        ResponseEntity<Map<String, Object>> response = controllerThrowing(
                new IllegalStateException("provider unavailable"))
                .handleMcp(toolCall());

        assertThat(error(response).get("code")).isEqualTo(-32603);
        assertThat(output.getOut()).contains("Error handling method=tools/call");
    }

    @SuppressWarnings("unchecked")
    private McpStreamableHttpController controllerThrowing(Throwable cause) {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("testTool");

        ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition()).thenReturn(definition);
        when(callback.call(anyString())).thenThrow(new ToolExecutionException(definition, cause));

        ToolCallbackProvider callbackProvider = mock(ToolCallbackProvider.class);
        when(callbackProvider.getToolCallbacks()).thenReturn(new ToolCallback[]{callback});

        ObjectProvider<BuildProperties> buildProperties = mock(ObjectProvider.class);
        ObjectProvider<McpApiKeyFilter> apiKeyFilter = mock(ObjectProvider.class);
        return new McpStreamableHttpController(
                List.of(callbackProvider),
                new ObjectMapper(),
                buildProperties,
                mock(Environment.class),
                apiKeyFilter);
    }

    private Map<String, Object> toolCall() {
        return Map.of(
                "jsonrpc", "2.0",
                "id", "test",
                "method", "tools/call",
                "params", Map.of("name", "testTool", "arguments", Map.of()));
    }

    @SuppressWarnings("unchecked")
    private void assertInvalidParams(ResponseEntity<Map<String, Object>> response, String message) {
        Map<String, Object> error = error(response);
        assertThat(error.get("code")).isEqualTo(-32602);
        assertThat(error.get("message")).isEqualTo(message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> error(ResponseEntity<Map<String, Object>> response) {
        assertThat(response.getBody()).isNotNull();
        return (Map<String, Object>) response.getBody().get("error");
    }
}
