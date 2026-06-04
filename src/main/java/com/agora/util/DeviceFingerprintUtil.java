package com.agora.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
public class DeviceFingerprintUtil {

    public String generateDeviceFingerprint(HttpServletRequest request) {
        // 1. 抓取 Cloudflare 提供的關鍵標頭 (嘗試大寫與小寫)
        String cfIp = getHeaderIgnoreCase(request, "CF-Connecting-IP");
        String cfCountry = getHeaderIgnoreCase(request, "CF-IPCountry");
        String cfCity = getHeaderIgnoreCase(request, "CF-IPCity");
        String cfJa3 = getHeaderIgnoreCase(request, "CF-Client-Hello-JA3-Fingerprint");

        // 2. 處理地理位置
        String country = Optional.ofNullable(cfCountry).orElse("XX");
        // 移除城市名中的空格，避免指紋斷開
        String city = Optional.ofNullable(cfCity)
                .map(c -> c.toUpperCase().replace(" ", ""))
                .orElse("UNKNOWN");

        // 3. 處理 TLS DNA (JA3)
        String ja3Short = Optional.ofNullable(cfJa3)
                .filter(s -> s.length() >= 6)
                .map(s -> s.substring(0, 6).toUpperCase())
                .orElse("NOTLS");

        // 4. 解析 User-Agent
        String ua = request.getHeader("User-Agent");
        String os = simplifyOS(ua);
        String browser = simplifyBrowser(ua);

        // 5. 生成指紋
        String fingerprint = String.format("%s-%s-%s-%s-%s", 
                country, city, os, browser, ja3Short);

        // 6. 整合日誌紀錄
        log.info("Device Profile [Fingerprint: {} | IP: {} | City: {} | JA3: {}]", 
                fingerprint, 
                cfIp != null ? cfIp : getClientIpAddress(request), 
                city,
                ja3Short);
        
        return fingerprint;
    }

    /**
     * 安全地獲取 Header，忽略大小寫
     */
    private String getHeaderIgnoreCase(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null) {
            value = request.getHeader(name.toLowerCase());
        }
        return value;
    }

    private String simplifyOS(String ua) {
        if (ua == null) return "OS";
        String upperUA = ua.toUpperCase();
        if (upperUA.contains("WINDOWS")) return "WIN";
        if (upperUA.contains("IPHONE")) return "IOS";
        if (upperUA.contains("IPAD")) return "IPAD";
        if (upperUA.contains("ANDROID")) return "AND";
        if (upperUA.contains("MACINTOSH")) return "MAC";
        if (upperUA.contains("LINUX")) return "LINUX";
        return "DEV";
    }

    private String simplifyBrowser(String ua) {
        if (ua == null) return "BRW";
        String upperUA = ua.toUpperCase();
        if (upperUA.contains("EDG/")) return "EDGE";
        if (upperUA.contains("CHROME")) return "CHROME";
        if (upperUA.contains("SAFARI") && !upperUA.contains("CHROME")) return "SAFARI";
        if (upperUA.contains("FIREFOX")) return "FIREFOX";
        return "OTHER";
    }

    public String getClientIpAddress(HttpServletRequest request) {
        String ip = getHeaderIgnoreCase(request, "CF-Connecting-IP");
        if (ip == null || ip.isEmpty()) {
            ip = request.getHeader("X-Forwarded-For");
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip != null ? ip : request.getRemoteAddr();
    }
}