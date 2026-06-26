package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.service.trading.CapitalAllocationPolicyPreviewService;
import com.agora.service.trading.PanicBottomContextPreviewService;
import com.agora.service.trading.ScoreBuyConfirmedDeployAutoExecutionService;
import com.agora.service.trading.ScoreBuyConfirmedDeployPreviewService;
import com.agora.service.trading.ScoreBuyConvictionPreviewService;
import com.agora.service.trading.ScoreBuyFormingDayObserverService;
import com.agora.service.trading.ScoreBuyPostScoutAutoAddExecutionService;
import com.agora.service.trading.ScoreBuyPostScoutManagementPolicyService;
import com.agora.service.trading.ScoreBuyPrePositionApprovalPreviewService;
import com.agora.service.trading.ScoreBuyPrePositionAutoExecutionService;
import com.agora.service.trading.ScoreBuyPrePositionExecutionPolicyPreviewService;
import com.agora.service.trading.ScoreBuyPrePositionPreviewService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScoreBuyMcpToolsTest {

    @Test
    void previewPanicBottomContextKeepsOpsReadOnlyMetadata() throws Exception {
        Method method = ScoreBuyMcpTools.class.getDeclaredMethod("previewPanicBottomContext", String.class);

        McpAuth auth = method.getAnnotation(McpAuth.class);
        McpCategory category = method.getAnnotation(McpCategory.class);

        assertThat(auth).isNotNull();
        assertThat(auth.value()).isEqualTo(McpAuthLevel.OPS);
        assertThat(category).isNotNull();
        assertThat(Arrays.asList(category.value()))
                .containsExactlyInAnyOrder(Category.READ_TRADING, Category.DIAGNOSTIC, Category.ANALYTICS, Category.MARKET_DATA);
    }

    @Test
    void previewScoreBuyConvictionDisplaysPanicBottomContextWithoutExecution() {
        ScoreBuyConvictionPreviewService conviction = mock(ScoreBuyConvictionPreviewService.class);
        PanicBottomContextPreviewService panic = mock(PanicBottomContextPreviewService.class);
        PositionMcpTools positionMcpTools = mock(PositionMcpTools.class);
        when(conviction.preview("BTCUSDT", 485L)).thenReturn("{\"tool\":\"previewScoreBuyConviction\",\"orderSent\":false}");
        when(positionMcpTools.getOcoHealth()).thenReturn("OCO Health: 3 OK | 0 abnormal");
        when(panic.preview(anyString(), anyString())).thenReturn("{\"tool\":\"previewPanicBottomContext\",\"boundary\":\"READ_ONLY\",\"orderAllowed\":false,\"gridMutationAllowed\":false}");

        ScoreBuyMcpTools tools = new ScoreBuyMcpTools(
                conviction,
                mock(ScoreBuyFormingDayObserverService.class),
                mock(ScoreBuyPrePositionPreviewService.class),
                mock(ScoreBuyPrePositionApprovalPreviewService.class),
                mock(ScoreBuyPrePositionExecutionPolicyPreviewService.class),
                mock(ScoreBuyPrePositionAutoExecutionService.class),
                mock(CapitalAllocationPolicyPreviewService.class),
                mock(ScoreBuyConfirmedDeployPreviewService.class),
                mock(ScoreBuyConfirmedDeployAutoExecutionService.class),
                mock(ScoreBuyPostScoutManagementPolicyService.class),
                mock(ScoreBuyPostScoutAutoAddExecutionService.class),
                panic,
                positionMcpTools);

        String output = tools.previewScoreBuyConviction("BTCUSDT", 485L);

        assertThat(output).contains("previewScoreBuyConviction");
        assertThat(output).contains("panicBottomContext=");
        assertThat(output).contains("previewPanicBottomContext");
        assertThat(output).contains("\"boundary\":\"READ_ONLY\"");
        assertThat(output).contains("\"orderAllowed\":false");
        assertThat(output).contains("\"gridMutationAllowed\":false");
    }
}
