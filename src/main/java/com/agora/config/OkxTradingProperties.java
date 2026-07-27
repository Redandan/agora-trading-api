package com.agora.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * OKX 現貨自動交易配置。
 * 對應 application.yml 的 trading.okx.* 區塊。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "trading.okx")
public class OkxTradingProperties {

    /** 主開關，預設關閉。設定好 API Key 並測試無誤後再開啟。 */
    private boolean enabled = false;

    /** OKX API Key */
    private String apiKey = "";

    /** OKX Secret Key（用於 HMAC-SHA256 簽名） */
    private String secretKey = "";

    /** OKX API Passphrase（建立 API Key 時自行設定的密碼） */
    private String passphrase = "";

    /** OKX REST Base URL（不含路徑） */
    private String baseUrl = "https://www.okx.com";

    /** SWAP 合約槓桿倍數（預設 3x，需在 OKX 帳戶預先設定相同值） */
    private int swapLeverage = 3;

    /** SWAP 合約使用的保證金模式：cross 或 isolated */
    private String swapTdMode = "cross";

    public boolean hasPrivateCredentials() {
        return hasText(apiKey) && hasText(secretKey) && hasText(passphrase);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
