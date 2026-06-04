package com.agora.util;

import lombok.extern.slf4j.Slf4j;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Pattern;

@Slf4j
public class UrlValidationUtil {

    // URL格式正则表达式
    private static final Pattern URL_PATTERN = Pattern.compile(
        "^https?://" + // http:// 或 https://
        "([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}" + // 域名
        "(:[0-9]{1,5})?" + // 可选的端口号
        "(/[a-zA-Z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=%]*)?$" // 路径、查询参数等
    );

    /**
     * 验证URL格式是否正确
     * @param urlString 要验证的URL字符串
     * @return 是否为有效的URL格式
     */
    public static boolean isValidUrlFormat(String urlString) {
        if (urlString == null || urlString.trim().isEmpty()) {
            return false;
        }
        
        try {
            // 使用正则表达式进行初步验证
            if (!URL_PATTERN.matcher(urlString).matches()) {
                return false;
            }
            
            // 尝试创建URL对象进行进一步验证
            new URL(urlString);
            return true;
        } catch (MalformedURLException e) {
            log.debug("URL格式验证失败: {}, 错误: {}", urlString, e.getMessage());
            return false;
        }
    }

    /**
     * 验证URL是否为图片URL
     * @param urlString 要验证的URL字符串
     * @return 是否为图片URL
     */
    public static boolean isImageUrl(String urlString) {
        if (!isValidUrlFormat(urlString)) {
            return false;
        }
        
        // 检查文件扩展名
        String lowerUrl = urlString.toLowerCase();
        return lowerUrl.endsWith(".jpg") || 
               lowerUrl.endsWith(".jpeg") || 
               lowerUrl.endsWith(".png") || 
               lowerUrl.endsWith(".gif") || 
               lowerUrl.endsWith(".webp") ||
               lowerUrl.endsWith(".bmp") ||
               lowerUrl.endsWith(".svg");
    }

    /**
     * 验证多个URL格式
     * @param urls URL字符串集合
     * @return 是否所有URL都有效
     */
    public static boolean areAllUrlsValid(java.util.Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return true; // 空集合视为有效
        }
        
        return urls.stream().allMatch(UrlValidationUtil::isValidUrlFormat);
    }

    /**
     * 验证多个URL是否为图片URL
     * @param urls URL字符串集合
     * @return 是否所有URL都是图片URL
     */
    public static boolean areAllImageUrls(java.util.Collection<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return true; // 空集合视为有效
        }
        
        return urls.stream().allMatch(UrlValidationUtil::isImageUrl);
    }
}
