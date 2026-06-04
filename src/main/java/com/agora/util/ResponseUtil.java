package com.agora.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;

/**
 * 響應處理工具類
 * 提供統一的成功和錯誤響應構建方法
 */
@Slf4j
public class ResponseUtil {

    /**
     * 構建成功響應
     */
    public static <T> ResponseEntity<T> success(T response) {
        return ResponseEntity.ok(response);
    }

    /**
     * 構建錯誤響應
     */
    public static <T> ResponseEntity<T> error(T errorResponse) {
        return ResponseEntity.internalServerError().body(errorResponse);
    }

    /**
     * 執行操作並返回響應
     * @param operation 要執行的操作
     * @param successResponse 成功響應
     * @param errorResponseBuilder 錯誤響應構建器
     * @param logMessage 日誌消息
     * @param <T> 響應類型
     * @return ResponseEntity
     */
    public static <T> ResponseEntity<T> execute(
            Runnable operation,
            T successResponse,
            java.util.function.Supplier<T> errorResponseBuilder,
            String logMessage) {
        
        try {
            operation.run();
            return success(successResponse);
        } catch (Exception e) {
            log.error(logMessage, e);
            return error(errorResponseBuilder.get());
        }
    }

    /**
     * 執行操作並返回響應（帶返回值）
     * @param operation 要執行的操作
     * @param successResponseBuilder 成功響應構建器
     * @param errorResponseBuilder 錯誤響應構建器
     * @param logMessage 日誌消息
     * @param <T> 響應類型
     * @param <R> 操作返回類型
     * @return ResponseEntity
     */
    public static <T, R> ResponseEntity<T> executeWithResult(
            java.util.function.Supplier<R> operation,
            java.util.function.Function<R, T> successResponseBuilder,
            java.util.function.Supplier<T> errorResponseBuilder,
            String logMessage) {
        
        try {
            R result = operation.get();
            return success(successResponseBuilder.apply(result));
        } catch (Exception e) {
            log.error(logMessage, e);
            return error(errorResponseBuilder.get());
        }
    }
}
