package com.agora.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import javax.net.ssl.*;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;

public class FacilityUtil {
    private static final String API_URL = "https://tycsc.cyc.org.tw/api";
    private static final RestTemplate restTemplate;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    static {
        try {
            // 創建信任所有證書的 TrustManager
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            // 設置 SSL 上下文
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            
            // 設置全局 SSL 配置
            HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

            // 創建 RestTemplate
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5000);
            factory.setReadTimeout(5000);
            restTemplate = new RestTemplate(factory);
            
        } catch (Exception e) {
            throw new RuntimeException("初始化 RestTemplate 失敗", e);
        }
    }

    public static String getFacilityInfo() {
        try {
            // 設置請求頭
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // 發送請求
            String response = restTemplate.exchange(
                API_URL,
                HttpMethod.GET,
                entity,
                String.class
            ).getBody();

            Map<String, List<String>> data = objectMapper.readValue(response, 
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, List<String>>>() {});

            List<String> gymData = data.get("gym");
            List<String> swimData = data.get("swim");

            return String.format("健身房: 當前人數: %d, 最大容量: %d\n游泳池: 當前人數: %d, 最大容量: %d",
                Integer.parseInt(gymData.get(0)),
                Integer.parseInt(gymData.get(1)),
                Integer.parseInt(swimData.get(0)),
                Integer.parseInt(swimData.get(1)));
        } catch (Exception e) {
            throw new RuntimeException("獲取設施資訊失敗: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        try {
            System.out.println("設施使用狀況：");
            System.out.println(getFacilityInfo());
        } catch (Exception e) {
            System.err.println("錯誤：" + e.getMessage());
            e.printStackTrace();
        }
    }
} 