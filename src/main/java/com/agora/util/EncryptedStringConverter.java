package com.agora.util;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * JPA AttributeConverter — 對 String 欄位透明 AES 加密 / 解密。
 *
 * <h3>使用方式</h3>
 * <pre>
 * &#064;Convert(converter = EncryptedStringConverter.class)
 * &#064;Column(name = "secret_field")
 * private String secretField;
 * </pre>
 *
 * <h3>金鑰來源</h3>
 * 從 {@code digital-order.code-encryption-key} (env: DIGITAL_ORDER_CODE_KEY) 讀取。
 * 未設定時降級為 passthrough(明文存),log warning,讓 dev 環境不會炸。
 *
 * <h3>限制</h3>
 * 加密後值會比明文長且不固定,不可作為 indexed/searchable column。
 * 目前只用於 {@code order_delivery_proof.code_payload}(兌換碼/序號)。
 */
@Slf4j
@Converter
@Component
@RequiredArgsConstructor
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    @Value("${digital-order.code-encryption-key:}")
    private String encryptionKey;

    @Value("${digital-order.code-encryption-salt:5c0744940b5c369b}")
    private String encryptionSalt;

    private static volatile TextEncryptor encryptor;

    @PostConstruct
    void init() {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("digital-order.code-encryption-key 未設定,code_payload 將以明文儲存(僅 dev 環境可接受)");
            encryptor = null;
        } else {
            encryptor = Encryptors.text(encryptionKey, encryptionSalt);
            log.info("EncryptedStringConverter 已啟用 AES 加密");
        }
    }

    @Override
    public String convertToDatabaseColumn(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) return plaintext;
        TextEncryptor enc = encryptor;
        if (enc == null) return plaintext;
        try {
            return enc.encrypt(plaintext);
        } catch (Exception e) {
            log.warn("加密失敗,降級為明文儲存: {}", e.getMessage());
            return plaintext;
        }
    }

    @Override
    public String convertToEntityAttribute(String dbValue) {
        if (dbValue == null || dbValue.isEmpty()) return dbValue;
        TextEncryptor enc = encryptor;
        if (enc == null) return dbValue;
        try {
            return enc.decrypt(dbValue);
        } catch (Exception e) {
            // DB 中可能是舊明文資料,加密後升級時的相容路徑
            log.debug("解密失敗,視為明文回傳: {}", e.getMessage());
            return dbValue;
        }
    }
}
