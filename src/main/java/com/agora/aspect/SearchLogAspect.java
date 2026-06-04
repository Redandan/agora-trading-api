package com.agora.aspect;

import com.agora.annotation.CurrentUser;
import com.agora.annotation.SearchLog;
import com.agora.model.User;
import com.agora.model.UserSearchLog;
import com.agora.service.UserSearchLogService;
import com.agora.util.DeviceFingerprintUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 搜尋紀錄AOP切面
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class SearchLogAspect {
    private static final Map<String, String> NORMALIZED_KEYWORD_ALIASES = Map.of(
            "cafe", "coffee",
            "coffee bean", "coffee",
            "咖啡", "coffee",
            "珈琲", "coffee"
    );
    
    private final UserSearchLogService userSearchLogService;
    private final ObjectMapper objectMapper;
    private final DeviceFingerprintUtil deviceFingerprintUtil;
    
    @Around("@annotation(searchLog)")
    public Object around(ProceedingJoinPoint joinPoint, SearchLog searchLog) throws Throwable {
        // 獲取請求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return joinPoint.proceed();
        }
        
        HttpServletRequest request = attributes.getRequest();
        LocalDateTime requestTime = LocalDateTime.now();
        
        // 獲取用戶信息
        User user = getCurrentUser(joinPoint);
        
        // 獲取請求參數
        Map<String, Object> requestParams = getRequestParams(joinPoint, searchLog);
        String requestBody = getRequestBody(joinPoint, searchLog);
        
        // 獲取關鍵字
        String keyword = extractKeyword(requestParams, searchLog.keywordParam());
        
        // 執行目標方法
        Object result = joinPoint.proceed();
        
        // 記錄響應信息
        LocalDateTime responseTime = LocalDateTime.now();
        long responseTimeMs = java.time.Duration.between(requestTime, responseTime).toMillis();
        
        // 創建搜尋紀錄
        UserSearchLog searchLogEntity = createSearchLog(
            user, request, requestParams, requestBody, 
            result, searchLog, keyword, requestTime, responseTime, responseTimeMs
        );
        
        // 異步記錄
        userSearchLogService.logSearchRequest(searchLogEntity);
        
        return result;
    }
    
    /**
     * 獲取當前用戶
     */
    private User getCurrentUser(ProceedingJoinPoint joinPoint) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Parameter[] parameters = method.getParameters();
            Object[] args = joinPoint.getArgs();
            
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                if (parameter.isAnnotationPresent(CurrentUser.class)) {
                    return (User) args[i];
                }
            }
        } catch (Exception e) {
            log.debug("獲取當前用戶失敗: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 獲取請求參數
     */
    private Map<String, Object> getRequestParams(ProceedingJoinPoint joinPoint, SearchLog searchLog) {
        if (!searchLog.logRequestParams()) {
            return new HashMap<>();
        }
        
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Parameter[] parameters = method.getParameters();
            Object[] args = joinPoint.getArgs();
            
            Map<String, Object> requestParams = new HashMap<>();
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                Object arg = args[i];
                if (shouldSkipRequestParam(parameter, arg)) {
                    continue;
                }
                requestParams.put(parameter.getName(), arg);
            }
            return requestParams;
        } catch (Exception e) {
            log.debug("獲取請求參數失敗: {}", e.getMessage());
            return new HashMap<>();
        }
    }
    
    /**
     * 獲取請求體
     */
    private String getRequestBody(ProceedingJoinPoint joinPoint, SearchLog searchLog) {
        if (!searchLog.logRequestBody()) {
            return null;
        }
        
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            Parameter[] parameters = method.getParameters();
            Object[] args = joinPoint.getArgs();
            
            for (int i = 0; i < parameters.length; i++) {
                Parameter parameter = parameters[i];
                // 尋找@RequestBody參數
                if (parameter.isAnnotationPresent(org.springframework.web.bind.annotation.RequestBody.class)) {
                    return objectMapper.writeValueAsString(args[i]);
                }
            }
        } catch (Exception e) {
            log.debug("獲取請求體失敗: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * 提取關鍵字
     */
    private String extractKeyword(Map<String, Object> requestParams, String keywordParam) {
        try {
            Object keywordObj = requestParams.get(keywordParam);
            String direct = stringifyKeyword(keywordObj);
            if (direct != null) {
                return direct;
            }

            for (Object value : requestParams.values()) {
                String nested = extractNestedKeyword(value, keywordParam);
                if (nested != null) {
                    return nested;
                }
            }
        } catch (Exception e) {
            log.debug("提取關鍵字失敗: {}", e.getMessage());
        }
        return null;
    }

    private boolean shouldSkipRequestParam(Parameter parameter, Object arg) {
        if (parameter.isAnnotationPresent(CurrentUser.class)) {
            return true;
        }
        return arg instanceof ServletRequest || arg instanceof ServletResponse;
    }

    private String extractNestedKeyword(Object value, String keywordParam) {
        if (value == null || keywordParam == null || keywordParam.isBlank()) {
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            return stringifyKeyword(map.get(keywordParam));
        }

        String getterName = "get" + Character.toUpperCase(keywordParam.charAt(0)) + keywordParam.substring(1);
        try {
            Method getter = value.getClass().getMethod(getterName);
            return stringifyKeyword(getter.invoke(value));
        } catch (Exception ignored) {
            // Fall through to field access. DTOs usually have Lombok getters, but
            // field access keeps telemetry robust for simple request records too.
        }

        try {
            Field field = value.getClass().getDeclaredField(keywordParam);
            field.setAccessible(true);
            return stringifyKeyword(field.get(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stringifyKeyword(Object keywordObj) {
        if (keywordObj == null) {
            return null;
        }
        String keyword = keywordObj.toString().trim();
        return keyword.isEmpty() ? null : keyword;
    }
    
    /**
     * 創建搜尋紀錄
     */
    private UserSearchLog createSearchLog(User user, HttpServletRequest request, 
                                        Map<String, Object> requestParams, String requestBody,
                                        Object result, SearchLog searchLog, String keyword,
                                        LocalDateTime requestTime, LocalDateTime responseTime, 
                                        long responseTimeMs) {
        UserSearchLog searchLogEntity = new UserSearchLog();
        
        // 設置用戶信息
        searchLogEntity.setUserId(user != null ? user.getId() : null);
        
        // 設置請求信息
        searchLogEntity.setRequestMethod(request.getMethod());
        searchLogEntity.setRequestUri(request.getRequestURI());
        searchLogEntity.setUserAgent(request.getHeader("User-Agent"));
        searchLogEntity.setIpAddress(deviceFingerprintUtil.getClientIpAddress(request));
        
        // 設置請求參數和請求體
        try {
            if (searchLog.logRequestParams()) {
                searchLogEntity.setRequestParams(objectMapper.writeValueAsString(requestParams));
            }
            if (searchLog.logRequestBody()) {
                searchLogEntity.setRequestBody(requestBody);
            }
        } catch (Exception e) {
            log.debug("序列化請求參數失敗: {}", e.getMessage());
        }
        
        // 設置響應信息
        searchLogEntity.setResponseTimeMs(responseTimeMs);
        searchLogEntity.setRequestTime(requestTime);
        searchLogEntity.setResponseTime(responseTime);
        
        // 設置搜尋特定信息
        Long resultCount = extractResultCount(result);
        searchLogEntity.setSearchType(searchLog.searchType());
        searchLogEntity.setKeyword(keyword);
        searchLogEntity.setRawQuery(keyword);
        searchLogEntity.setNormalizedKeyword(normalizeSearchQuery(keyword));
        
        searchLogEntity.setResponseStatus(extractResponseStatus(result));
        searchLogEntity.setResultCount(resultCount);
        searchLogEntity.setZeroResult(resultCount != null && resultCount == 0);
        
        return searchLogEntity;
    }

    private String normalizeSearchQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }
        String normalized = Normalizer.normalize(rawQuery, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
        if (normalized.isBlank()) {
            return null;
        }
        return NORMALIZED_KEYWORD_ALIASES.getOrDefault(normalized, normalized);
    }

    private Integer extractResponseStatus(Object result) {
        if (result instanceof ResponseEntity<?> response) {
            return response.getStatusCode().value();
        }
        return null;
    }

    private Long extractResultCount(Object result) {
        Object body = result;
        if (result instanceof ResponseEntity<?> response) {
            body = response.getBody();
        }
        if (body instanceof Page<?> page) {
            return page.getTotalElements();
        }
        if (body != null) {
            try {
                Method getter = body.getClass().getMethod("getTotalElements");
                Object total = getter.invoke(body);
                if (total instanceof Number number) {
                    return number.longValue();
                }
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
    
}
