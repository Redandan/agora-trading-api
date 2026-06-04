package com.agora.util;

import com.agora.config.JwtConfig;
import com.agora.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtUtil {
    private final JwtConfig jwtConfig;

    public long getAccessTokenExpirationConfig() {
        long configValue = jwtConfig.getAccessTokenExpiration();
        log.info("=== JWT Config Debug ===");
        log.info("Access Token Expiration from config: {} ms", configValue);
        log.info("Access Token Expiration in hours: {} hours", configValue / (1000 * 60 * 60));
        log.info("Config object hash: {}", System.identityHashCode(jwtConfig));
        log.info("=========================");
        return configValue;
    }

    private SecretKey getSigningKey() {
        String secret = jwtConfig.getSecret();
        if (secret == null || secret.trim().isEmpty()) {
            throw new IllegalStateException("JWT secret is not configured. Please check your application.yml configuration.");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public Date extractIssuedAt(String token) {
        return extractClaim(token, Claims::getIssuedAt);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .clockSkewSeconds(30)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 公開方法：提取所有 Claims（用於性能優化，避免重複解析）
     * 注意：此方法會驗證簽名和過期時間
     */
    public Claims extractAllClaimsPublic(String token) {
        return extractAllClaims(token);
    }

    private Boolean isTokenExpired(String token) {
        Date expiration = extractExpiration(token);
        Date now = new Date();
        boolean expired = expiration.before(now);
        
        log.info("=== Token Expiration Check Debug ===");
        log.info("Token expiration: {} ({} ms)", expiration, expiration.getTime());
        log.info("Current time: {} ({} ms)", now, now.getTime());
        log.info("Time difference: {} ms", expiration.getTime() - now.getTime());
        log.info("Is expired: {}", expired);
        log.info("================================");
            
        return expired;
    }

    public String generateToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        return createToken(claims, userDetails.getUsername());
    }

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        return createAccessToken(claims, user.getUsername());
    }

    /**
     * 生成帶有設備信息的 access token
     * 
     * @param user 用戶對象
     * @param deviceFingerprint 設備指紋
     * @param ipAddress IP地址
     * @return JWT access token
     */
    public String generateToken(User user, String deviceFingerprint, String ipAddress) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("deviceFingerprint", deviceFingerprint);
        claims.put("ipAddress", ipAddress);
        return createAccessToken(claims, user.getUsername());
    }

    public String generateRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("type", "refresh");
        return createRefreshToken(claims, user.getUsername());
    }

    /**
     * 生成帶有 trusted 標記的 refresh token
     * 
     * @param user 用戶對象
     * @return JWT refresh token（帶 trusted 標記）
     */
    public String generateTrustedRefreshToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("type", "refresh");
        claims.put("trusted", true);
        return createRefreshToken(claims, user.getUsername());
    }

    /**
     * 從舊的 refresh token 生成新的 refresh token，保留 trusted 標記
     * 
     * @param user 用戶對象
     * @param oldRefreshToken 舊的 refresh token
     * @return 新的 JWT refresh token
     */
    public String generateRefreshTokenFromOld(User user, String oldRefreshToken) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("type", "refresh");
        
        // 從舊的 refresh token 中提取 trusted 標記並保留
        try {
            Claims oldClaims = extractAllClaims(oldRefreshToken);
            Object trusted = oldClaims.get("trusted");
            if (trusted != null && Boolean.TRUE.equals(trusted)) {
                claims.put("trusted", true);
            }
        } catch (Exception e) {
            log.debug("Failed to extract trusted flag from old refresh token: {}", e.getMessage());
        }
        
        return createRefreshToken(claims, user.getUsername());
    }

    private String createToken(Map<String, Object> claims, String subject) {
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + jwtConfig.getAccessTokenExpiration()))
                .signWith(getSigningKey())
                .compact();
    }

    private String createAccessToken(Map<String, Object> claims, String subject) {
        long currentTime = System.currentTimeMillis();
        long expirationTime = currentTime + jwtConfig.getAccessTokenExpiration();
        
        log.info("=== Creating Access Token Debug ===");
        log.info("Current time: {} ({} ms)", new Date(currentTime), currentTime);
        log.info("Config value: {} ms", jwtConfig.getAccessTokenExpiration());
        log.info("Calculated duration: {} ms", expirationTime - currentTime);
        log.info("Expiration time: {} ({} ms)", new Date(expirationTime), expirationTime);
        log.info("Token will expire in: {} minutes", (expirationTime - currentTime) / (1000 * 60));
        log.info("Config object hash: {}", System.identityHashCode(jwtConfig));
        log.info("================================");
            
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(currentTime))
                .expiration(new Date(expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    private String createRefreshToken(Map<String, Object> claims, String subject) {
        long currentTime = System.currentTimeMillis();
        long expirationTime = currentTime + jwtConfig.getRefreshTokenExpiration();
        
        log.info("=== Creating Refresh Token Debug ===");
        log.info("Current time: {} ({} ms)", new Date(currentTime), currentTime);
        log.info("Refresh config value: {} ms", jwtConfig.getRefreshTokenExpiration());
        log.info("Calculated duration: {} ms", expirationTime - currentTime);
        log.info("Expiration time: {} ({} ms)", new Date(expirationTime), expirationTime);
        log.info("Token will expire in: {} days", (expirationTime - currentTime) / (1000 * 60 * 60 * 24));
        boolean isTrusted = Boolean.TRUE.equals(claims.get("trusted"));
        log.info("Is trusted device: {}", isTrusted);
        log.info("================================");
        
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(currentTime))
                .expiration(new Date(expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    /**
     * 創建受信任設備的 access token（30天過期）
     */
    private String createTrustedDeviceAccessToken(Map<String, Object> claims, String subject) {
        long currentTime = System.currentTimeMillis();
        long expirationTime = currentTime + jwtConfig.getTrustedDeviceAccessTokenExpiration();
        
        log.info("=== Creating Trusted Device Access Token Debug ===");
        log.info("Current time: {} ({} ms)", new Date(currentTime), currentTime);
        log.info("Trusted device access token config value: {} ms", jwtConfig.getTrustedDeviceAccessTokenExpiration());
        log.info("Calculated duration: {} ms", expirationTime - currentTime);
        log.info("Expiration time: {} ({} ms)", new Date(expirationTime), expirationTime);
        log.info("Token will expire in: {} days", (expirationTime - currentTime) / (1000 * 60 * 60 * 24));
        log.info("================================");
        
        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(new Date(currentTime))
                .expiration(new Date(expirationTime))
                .signWith(getSigningKey())
                .compact();
    }

    public boolean isRefreshToken(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return "refresh".equals(claims.get("type"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 檢查 refresh token 是否為受信任設備
     * 
     * @param refreshToken refresh token
     * @return 是否為受信任設備
     */
    public boolean isTrustedDevice(String refreshToken) {
        try {
            Claims claims = extractAllClaims(refreshToken);
            Object trusted = claims.get("trusted");
            return trusted != null && Boolean.TRUE.equals(trusted);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 為受信任設備生成 access token（30天過期）
     * 
     * @param user 用戶對象
     * @return JWT access token（30天過期）
     */
    public String generateTrustedDeviceAccessToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("trusted", true); // 標記為受信任設備
        return createTrustedDeviceAccessToken(claims, user.getUsername());
    }

    /**
     * 為受信任設備生成帶有設備信息的 access token（30天過期）
     * 
     * @param user 用戶對象
     * @param deviceFingerprint 設備指紋
     * @param ipAddress IP地址
     * @return JWT access token（30天過期）
     */
    public String generateTrustedDeviceAccessToken(User user, String deviceFingerprint, String ipAddress) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole());
        claims.put("trusted", true); // 標記為受信任設備
        claims.put("deviceFingerprint", deviceFingerprint);
        claims.put("ipAddress", ipAddress);
        return createTrustedDeviceAccessToken(claims, user.getUsername());
    }

    /**
     * 安全地提取 JWT claims，不拋出異常
     * 用於錯誤日誌記錄，即使 token 過期也能提取 claims
     */
    public Claims extractClaimsSafely(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSigningKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            // 對於過期的 token，仍然返回 claims
            log.debug("Token expired but extracting claims for logging: {}", e.getMessage());
            return e.getClaims();
        } catch (Exception e) {
            log.debug("Failed to extract claims from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 獲取 token 的詳細信息用於日誌記錄
     */
    public String getTokenInfoForLogging(String token) {
        try {
            // 使用安全的方法提取 claims，避免拋出異常
            Claims claims = extractClaimsSafely(token);
            if (claims == null) {
                return "Token Info - Failed to extract claims safely";
            }
            
            Date issuedAt = claims.getIssuedAt();
            Date expiration = claims.getExpiration();
            String subject = claims.getSubject();
            Date now = new Date();
            
            return String.format(
                "Token Info - Subject: %s, Issued At: %s (%d ms), Expiration: %s (%d ms), Current Time: %s (%d ms), Time Until Expiry: %d ms",
                subject,
                issuedAt, issuedAt.getTime(),
                expiration, expiration.getTime(),
                now, now.getTime(),
                expiration.getTime() - now.getTime()
            );
        } catch (Exception e) {
            return String.format("Token Info - Failed to parse: %s", e.getMessage());
        }
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            boolean isValid = (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
            
            log.info("Token validation - Username match: {}, Token expired: {}, Overall valid: {}", 
                username.equals(userDetails.getUsername()),
                isTokenExpired(token),
                isValid);
                
            return isValid;
        } catch (Exception e) {
            log.error("Token validation failed", e);
            return false;
        }
    }

    /**
     * 驗證 token 的設備指紋和 IP 地址是否匹配
     * 
     * @param token JWT token
     * @param currentDeviceFingerprint 當前請求的設備指紋
     * @param currentIpAddress 當前請求的 IP 地址
     * @return 是否匹配
     */
    public boolean validateDeviceAndIp(String token, String currentDeviceFingerprint, String currentIpAddress) {
        try {
            Claims claims = extractAllClaims(token);
            String tokenDeviceFingerprint = (String) claims.get("deviceFingerprint");
            String tokenIpAddress = (String) claims.get("ipAddress");
            
            // 如果 token 中沒有設備信息，直接拒絕（不向後兼容）
            if (tokenDeviceFingerprint == null || tokenIpAddress == null) {
                log.warn("Token does not contain device information, rejecting token. Device: {}, IP: {}", 
                    tokenDeviceFingerprint, tokenIpAddress);
                return false;
            }
            
            // 檢查設備指紋和 IP 是否匹配
            boolean deviceMatch = tokenDeviceFingerprint.equals(currentDeviceFingerprint);
            boolean ipMatch = tokenIpAddress.equals(currentIpAddress);
            
            boolean isValid = deviceMatch && ipMatch;
            
            if (!isValid) {
                log.warn("Device/IP validation failed - Token device: {}, Current device: {}, Token IP: {}, Current IP: {}", 
                    tokenDeviceFingerprint, currentDeviceFingerprint, tokenIpAddress, currentIpAddress);
            }
            
            return isValid;
        } catch (Exception e) {
            log.error("Device/IP validation failed", e);
            return false;
        }
    }

    /**
     * 從 token 中提取設備指紋
     */
    public String extractDeviceFingerprint(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return (String) claims.get("deviceFingerprint");
        } catch (Exception e) {
            log.debug("Failed to extract device fingerprint from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 從 token 中提取 IP 地址
     */
    public String extractIpAddress(String token) {
        try {
            Claims claims = extractAllClaims(token);
            return (String) claims.get("ipAddress");
        } catch (Exception e) {
            log.debug("Failed to extract IP address from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 從 Claims 中直接提取設備指紋（避免重複解析）
     */
    public String extractDeviceFingerprintFromClaims(Claims claims) {
        if (claims == null) {
            return null;
        }
        try {
            return (String) claims.get("deviceFingerprint");
        } catch (Exception e) {
            log.debug("Failed to extract device fingerprint from claims: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 從 Claims 中直接提取 IP 地址（避免重複解析）
     */
    public String extractIpAddressFromClaims(Claims claims) {
        if (claims == null) {
            return null;
        }
        try {
            return (String) claims.get("ipAddress");
        } catch (Exception e) {
            log.debug("Failed to extract IP address from claims: {}", e.getMessage());
            return null;
        }
    }
} 