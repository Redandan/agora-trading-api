package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.BtcBasePositionAdoptionService;
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
        BtcBasePositionAdoptionService adoption = mock(BtcBasePositionAdoptionService.class);
        BtcBasePositionManagerMcpTools tools = new BtcBasePositionManagerMcpTools(manager, adoption);
        when(manager.status("BTCUSDT")).thenReturn("status");
        when(manager.previewAdoption("260,261,262", 168)).thenReturn("adoption");
        when(manager.previewDisposition("260,261,262", 168)).thenReturn("disposition");
        when(adoption.previewOrExecute("260,261,262", null, false, null)).thenReturn("adoption-write-preview");

        assertThat(tools.getBtcBasePositionManagerStatus("BTCUSDT")).isEqualTo("status");
        assertThat(tools.previewBtcBasePositionAdoption("260,261,262", 168)).isEqualTo("adoption");
        assertThat(tools.previewBtcBasePositionDisposition("260,261,262", 168)).isEqualTo("disposition");
        assertThat(tools.adoptBtcBasePositionsKeepBtc("260,261,262", null, false, null))
                .isEqualTo("adoption-write-preview");
        verify(manager).status("BTCUSDT");
        verify(manager).previewAdoption("260,261,262", 168);
        verify(manager).previewDisposition("260,261,262", 168);
        verify(adoption).previewOrExecute("260,261,262", null, false, null);

        Method method = BtcBasePositionManagerMcpTools.class.getDeclaredMethod(
                "previewBtcBasePositionDisposition", String.class, Integer.class);
        McpAuth auth = method.getAnnotation(McpAuth.class);
        McpCategory category = method.getAnnotation(McpCategory.class);
        assertThat(auth.value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(Arrays.asList(category.value()))
                .containsExactlyInAnyOrder(Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS);

        Method writeMethod = BtcBasePositionManagerMcpTools.class.getDeclaredMethod(
                "adoptBtcBasePositionsKeepBtc", String.class, java.math.BigDecimal.class,
                Boolean.class, String.class);
        assertThat(writeMethod.getAnnotation(McpAuth.class).value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(Arrays.asList(writeMethod.getAnnotation(McpCategory.class).value()))
                .containsExactlyInAnyOrder(Category.WRITE_TRADING, Category.GOVERNANCE);
    }

    @Test
    void mcpToolsConfigRegistersManagerProvider() throws Exception {
        Method method = McpToolsConfig.class.getDeclaredMethod(
                "btcBasePositionManagerMcpToolCallbacks", BtcBasePositionManagerMcpTools.class);

        assertThat(method.getReturnType()).isEqualTo(ToolCallbackProvider.class);
        assertThat(method.getAnnotation(org.springframework.context.annotation.Bean.class)).isNotNull();
    }
}
