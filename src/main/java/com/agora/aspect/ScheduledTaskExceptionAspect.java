package com.agora.aspect;

import com.agora.service.DatabaseConnectionAlertService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * Scheduled 任務異常攔截器
 * 用於攔截所有 @Scheduled 註解的方法中的異常，特別是數據庫連接錯誤
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class ScheduledTaskExceptionAspect {

    private final DatabaseConnectionAlertService databaseConnectionAlertService;

    /**
     * 攔截所有使用 @Scheduled 註解的方法
     */
    @Around("@annotation(org.springframework.scheduling.annotation.Scheduled)")
    public Object handleScheduledTaskException(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().toShortString();
        
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            // 檢測是否為數據庫連接錯誤
            if (databaseConnectionAlertService.isDatabaseConnectionError(throwable)) {
                // 發送 Telegram 告警
                String context = String.format("Scheduled Task: %s", methodName);
                databaseConnectionAlertService.sendDatabaseConnectionAlert(throwable, context);
                
                log.error("Scheduled 任務發生數據庫連接錯誤: {}", methodName, throwable);
            } else {
                log.error("Scheduled 任務發生異常: {}", methodName, throwable);
            }
            
            // 重新拋出異常，讓原有的異常處理邏輯繼續處理
            throw throwable;
        }
    }
}

