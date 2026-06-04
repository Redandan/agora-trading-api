package com.agora.security;

import com.agora.config.SecurityPaths;
import com.agora.util.DeviceFingerprintUtil;
import com.agora.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import org.springframework.lang.NonNull;
import org.springframework.util.StringUtils;

/**
 * JWT 認證過濾器
 * 處理每個請求的 JWT 令牌驗證
 */
@Slf4j
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";
    private static final int BEARER_PREFIX_LENGTH = BEARER_PREFIX.length();

    private final JwtUtil jwtUtil;
    private final ObjectProvider<UserDetailsService> userDetailsServiceProvider;
    private final DeviceFingerprintUtil deviceFingerprintUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil,
                                   ObjectProvider<UserDetailsService> userDetailsServiceProvider,
                                   DeviceFingerprintUtil deviceFingerprintUtil) {
        this.jwtUtil = jwtUtil;
        this.userDetailsServiceProvider = userDetailsServiceProvider;
        this.deviceFingerprintUtil = deviceFingerprintUtil;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String requestURI = request.getRequestURI();
        log.debug("JWT Filter Processing - URI: {}, Method: {}", requestURI, request.getMethod());

        String jwt = getJwtFromRequest(request);

        // 公開路徑 + 無 JWT → 完全跳過(最快路徑)
        // 公開路徑 + 不像 JWT 格式的 Bearer(例:MCP API key,0 個 dot)→ 也跳過,
        //   不要嘗試解析否則 io.jsonwebtoken.MalformedJwtException 噴 ERROR log。
        //   /api/mcp 由 McpApiKeyFilter 已正確 auth,JwtAuthenticationFilter 不該插手。
        // 公開路徑 + 有 JWT 格式 token → 仍處理,讓 /auth/me 等「公開但需身份」端點拿 context
        // 非公開路徑 → 處理(若 token 缺/壞,Spring Security 後續會擋)
        if (isPublicPath(request) && (!StringUtils.hasText(jwt) || !looksLikeJwt(jwt))) {
            log.debug("Public path {} — skipping JWT (token={})",
                    requestURI, StringUtils.hasText(jwt) ? "non-JWT format" : "absent");
            filterChain.doFilter(request, response);
            return;
        }

        try {
            if (StringUtils.hasText(jwt)) {
                log.debug("Processing JWT token for URI: {}", requestURI);

                // 提取用户名
                String username = jwtUtil.extractUsername(jwt);

                if (StringUtils.hasText(username) && SecurityContextHolder.getContext().getAuthentication() == null) {
                    UserDetailsService userDetailsService = userDetailsServiceProvider.getObject();
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                    // 驗證 token 的基本信息（用戶名、過期時間）
                    if (jwtUtil.validateToken(jwt, userDetails)) {
                        // 驗證設備指紋和 IP 地址
                        String currentDeviceFingerprint = deviceFingerprintUtil.generateDeviceFingerprint(request);
                        String currentIpAddress = deviceFingerprintUtil.getClientIpAddress(request);

                        if (!jwtUtil.validateDeviceAndIp(jwt, currentDeviceFingerprint, currentIpAddress)) {
                            log.warn("Device/IP validation failed for user: {} on URI: {}. Device or IP changed, token invalidated.",
                                    username, requestURI);
                            // 設備或 IP 不匹配，拒絕該 token
                            filterChain.doFilter(request, response);
                            return;
                        }

                        // 將 Claims 存入 request attribute，供後續使用（避免重複解析）
                        try {
                            io.jsonwebtoken.Claims claims = jwtUtil.extractAllClaimsPublic(jwt);
                            request.setAttribute("JWT_CLAIMS", claims);
                        } catch (Exception e) {
                            log.debug("Failed to cache JWT claims for performance optimization: {}", e.getMessage());
                        }

                        log.debug("JWT token validation successful for user: {}", username);

                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        SecurityContextHolder.getContext().setAuthentication(authentication);
                        log.debug("Authentication set in SecurityContext for user: {}", username);
                    } else {
                        log.warn("JWT token validation failed for user: {} on URI: {}", username, requestURI);
                    }
                } else {
                    if (!StringUtils.hasText(username)) {
                        log.warn("No username extracted from JWT token for URI: {}", requestURI);
                    }
                }
            } else {
                log.debug("No JWT token found in request for URI: {}", requestURI);
            }
        } catch (Exception e) {
            String tokenInfo = "";

            if (StringUtils.hasText(jwt)) {
                try {
                    tokenInfo = jwtUtil.getTokenInfoForLogging(jwt);
                } catch (Exception tokenParseException) {
                    tokenInfo = "Token Info - Failed to extract token details: " + tokenParseException.getMessage();
                }
            } else {
                tokenInfo = "Token Info - No token found in request";
            }

            log.error("=== JWT Authentication Error ===");
            log.error("URI: {}", requestURI);
            log.error("Error: {}", e.getMessage());
            log.error("{}", tokenInfo);
            log.error("Stack trace:", e);
            log.error("================================");
        }

        filterChain.doFilter(request, response);
    }

    /**
     * 檢查是否為公共路徑（不需要認證）。
     *
     * <p>使用 {@code getServletPath()}（已去除 context path）進行比對，
     * 並正確處理 {@code /**} 萬用字元，避免把 API key 當 JWT 解析而噴 ERROR log。
     */
    private boolean isPublicPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        return Arrays.stream(SecurityPaths.ALLOWED_PATHS).anyMatch(pattern -> {
            if (pattern.endsWith("/**")) {
                String prefix = pattern.substring(0, pattern.length() - 3);
                return servletPath.startsWith(prefix + "/") || servletPath.equals(prefix);
            }
            return servletPath.equals(pattern) || servletPath.startsWith(pattern + "/");
        });
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

    /**
     * Cheap shape check before delegating to {@code io.jsonwebtoken} parser.
     *
     * <p>JWT compact serialization (RFC 7515 / 7519) requires exactly 2 dots
     * (JWS: header.payload.signature). JWE has 4 dots. API keys / opaque
     * tokens have 0. Returning {@code false} here lets a public-path request
     * carrying an opaque Bearer (e.g. MCP API key) skip the JWT parser —
     * which would otherwise throw {@code MalformedJwtException} and noise up
     * ERROR logs (every MCP call from Claude Code triggered one before this
     * fix landed 2026-04-18).
     *
     * <p>Conservative: requires at least 2 dots, ignores upper bound. JWE's
     * 4-dot tokens still pass and reach the parser, which is correct.
     */
    private static boolean looksLikeJwt(String token) {
        int first = token.indexOf('.');
        if (first <= 0) return false;
        int second = token.indexOf('.', first + 1);
        return second > first;
    }
}
