package com.agora.metrics;

import com.agora.mcp.auth.McpAuth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Around-aspect on every {@code @Tool} method — records {@code mcp.tool.call}
 * with tool_name / auth_level / outcome labels.
 *
 * <p>Works despite Spring AI running tool methods on virtual threads: AOP
 * interceptor chains don't rely on {@code ThreadLocal}. Only HTTP-request-scoped
 * state (e.g. {@code RequestContextHolder}) is broken on virtual threads — which
 * is why {@link com.agora.mcp.auth.McpApiKeyFilter} handles auth at the HTTP layer
 * instead of here.</p>
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class McpToolMetricsAspect {

    private final TradingMetrics metrics;

    @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
    public Object measure(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Tool tool = AnnotationUtils.findAnnotation(method, Tool.class);
        McpAuth mcpAuth = AnnotationUtils.findAnnotation(method, McpAuth.class);

        String toolName = resolveToolName(tool, method);
        String authLevel = mcpAuth != null ? mcpAuth.value().name() : "PUBLIC";
        String outcome = "success";

        try {
            return pjp.proceed();
        } catch (Throwable t) {
            outcome = "error";
            throw t;
        } finally {
            try {
                metrics.mcpToolCall(toolName, authLevel, outcome);
            } catch (Exception meterEx) {
                log.warn("[McpToolMetrics] meter record failed: tool={} err={}",
                        toolName, meterEx.getMessage());
            }
        }
    }

    private static String resolveToolName(Tool tool, Method method) {
        if (tool != null) {
            String n = tool.name();
            if (n != null && !n.isBlank()) return n;
        }
        return method.getName();
    }
}
