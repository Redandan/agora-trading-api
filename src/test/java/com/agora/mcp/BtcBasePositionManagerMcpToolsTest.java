package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.BtcBasePositionManagerService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BtcBasePositionManagerMcpToolsTest {

    @Test
    void toolsDelegateToReadOnlyManagerAndKeepOpsAnalyticsAuth() throws Exception {
        BtcBasePositionManagerService manager = mock(BtcBasePositionManagerService.class);
        BtcBasePositionManagerMcpTools tools = new BtcBasePositionManagerMcpTools(manager);
        when(manager.status("BTCUSDT")).thenReturn("status");
        when(manager.previewAdoption("260,261,262", 168)).thenReturn("adoption");
        when(manager.previewDisposition("260,261,262", 168)).thenReturn("disposition");

        assertThat(tools.getBtcBasePositionManagerStatus("BTCUSDT")).isEqualTo("status");
        assertThat(tools.previewBtcBasePositionAdoption("260,261,262", 168)).isEqualTo("adoption");
        assertThat(tools.previewBtcBasePositionDisposition("260,261,262", 168)).isEqualTo("disposition");
        verify(manager).status("BTCUSDT");
        verify(manager).previewAdoption("260,261,262", 168);
        verify(manager).previewDisposition("260,261,262", 168);

        Method method = BtcBasePositionManagerMcpTools.class.getDeclaredMethod(
                "previewBtcBasePositionDisposition", String.class, Integer.class);
        McpAuth auth = method.getAnnotation(McpAuth.class);
        McpCategory category = method.getAnnotation(McpCategory.class);
        assertThat(auth.value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(Arrays.asList(category.value()))
                .containsExactlyInAnyOrder(Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS);
    }

    @Test
    void mcpToolsConfigRegistersManagerProvider() throws Exception {
        Method method = McpToolsConfig.class.getDeclaredMethod(
                "btcBasePositionManagerMcpToolCallbacks", BtcBasePositionManagerMcpTools.class);

        assertThat(method.getReturnType()).isEqualTo(ToolCallbackProvider.class);
        assertThat(method.getAnnotation(org.springframework.context.annotation.Bean.class)).isNotNull();
    }
}
