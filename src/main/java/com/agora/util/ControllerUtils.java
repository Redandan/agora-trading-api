package com.agora.util;

import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Supplier;

/**
 * 控制器工具类
 */
@Slf4j
public class ControllerUtils {

    /**
     * 设置MDC日志上下文
     */
    public static void setMdcContext(String operation, String... keyValuePairs) {
        if (keyValuePairs == null || keyValuePairs.length % 2 != 0) {
            return;
        }
        
        MDC.put("operation", operation);
        
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            if (i + 1 < keyValuePairs.length) {
                MDC.put(keyValuePairs[i], keyValuePairs[i + 1]);
            }
        }
    }

    /**
     * 清除MDC日志上下文
     */
    public static void clearMdcContext() {
        MDC.clear();
    }

    /**
     * 设置用户上下文
     */
    public static void setUserContext(String userId, String username) {
        MDC.put("userId", userId);
        MDC.put("username", username);
    }

    /**
     * 设置请求上下文
     */
    public static void setRequestContext(String requestId, String method, String path) {
        MDC.put("requestId", requestId);
        MDC.put("method", method);
        MDC.put("path", path);
    }

    /**
     * 执行带时间统计的操作
     */
    public static <T> T executeTimedOperation(String operationName, Supplier<T> operation) {
        long startTime = System.currentTimeMillis();
        try {
            log.debug("开始执行操作: {}", operationName);
            T result = operation.get();
            long duration = System.currentTimeMillis() - startTime;
            log.debug("操作执行完成: {}, 耗时: {}ms", operationName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("操作执行失败: {}, 耗时: {}ms, 错误: {}", operationName, duration, e.getMessage(), e);
            throw e;
        }
    }
}
