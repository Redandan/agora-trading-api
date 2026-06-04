package com.agora.config;

import com.agora.annotation.CurrentUser;
import com.agora.model.User;
import com.agora.util.JwtUtil;
import com.agora.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.core.MethodParameter;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 當前用戶參數解析器
 * 用於在控制器方法中注入當前登入的用戶
 * 同時會從JWT token中提取設備指紋和IP地址並設置到User對象中
 */
@Slf4j
@RequiredArgsConstructor
public class CurrentUserMethodArgumentResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    private final JwtUtil jwtUtil;

    /**
     * 檢查參數是否支持當前用戶注入
     *
     * @param parameter 方法參數
     * @return 是否支持
     */
    @Override
    public boolean supportsParameter(@NonNull MethodParameter parameter) {
        return parameter.getParameterType().isAssignableFrom(User.class) &&
                parameter.hasParameterAnnotation(CurrentUser.class);
    }

    /**
     * 解析並返回當前用戶對象
     * 同時從JWT token中提取設備指紋和IP地址並設置到User對象中
     *
     * @param parameter     方法參數
     * @param mavContainer  模型和視圖容器
     * @param webRequest    網絡請求
     * @param binderFactory 數據綁定工廠
     * @return 當前用戶對象（包含設備指紋和IP地址）
     */
    @Override
    @Nullable
    public Object resolveArgument(@NonNull MethodParameter parameter,
                                  @Nullable ModelAndViewContainer mavContainer,
                                  @NonNull NativeWebRequest webRequest,
                                  @Nullable WebDataBinderFactory binderFactory) {
        try {
            log.debug("Resolving CurrentUser argument for parameter: {}", parameter.getParameterName());
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null
                    || !authentication.isAuthenticated()
                    || authentication.getPrincipal() instanceof String) {
                log.debug("No authenticated application user found for CurrentUser argument");
                return null;
            }
            User user = SecurityUtils.getCurrentUser();
            
            if (user == null) {
                log.warn("User is null, cannot resolve CurrentUser");
                return null;
            }
            
            // 從請求中獲取設備指紋和IP地址（優先使用緩存的 Claims，避免重複解析）
            HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
            if (request != null) {
                // 優先從 request attribute 獲取已解析的 Claims（性能優化）
                io.jsonwebtoken.Claims claims = (io.jsonwebtoken.Claims) request.getAttribute("JWT_CLAIMS");
                
                if (claims != null) {
                    // 使用緩存的 Claims，避免重複解析
                    try {
                        String deviceFingerprint = jwtUtil.extractDeviceFingerprintFromClaims(claims);
                        String ipAddress = jwtUtil.extractIpAddressFromClaims(claims);
                        
                        user.setCurrentDeviceFingerprint(deviceFingerprint);
                        user.setCurrentIpAddress(ipAddress);
                        
                        log.debug("Successfully resolved user: {} with device fingerprint: {} and IP: {} (using cached claims)", 
                                user.getUsername(), deviceFingerprint, ipAddress);
                    } catch (Exception e) {
                        log.debug("Failed to extract device fingerprint or IP from cached claims: {}", e.getMessage());
                    }
                } else {
                    // 如果緩存不存在，再解析（向後兼容）
                    String jwt = getJwtFromRequest(request);
                    if (StringUtils.hasText(jwt)) {
                        try {
                            String deviceFingerprint = jwtUtil.extractDeviceFingerprint(jwt);
                            String ipAddress = jwtUtil.extractIpAddress(jwt);
                            
                            user.setCurrentDeviceFingerprint(deviceFingerprint);
                            user.setCurrentIpAddress(ipAddress);
                            
                            log.debug("Successfully resolved user: {} with device fingerprint: {} and IP: {} (parsed from token)", 
                                    user.getUsername(), deviceFingerprint, ipAddress);
                        } catch (Exception e) {
                            log.debug("Failed to extract device fingerprint or IP from token: {}", e.getMessage());
                        }
                    } else {
                        log.debug("No JWT token found in request");
                    }
                }
            } else {
                log.debug("Cannot get HttpServletRequest from NativeWebRequest");
            }
            
            return user;
        } catch (Exception e) {
            log.error("Error resolving CurrentUser argument: {}", e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 從請求中提取 JWT 令牌
     */
    private String getJwtFromRequest(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTH_HEADER);
        if (StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX)) {
            return authHeader.substring(BEARER_PREFIX_LENGTH);
        }
        return null;
    }
}
