package com.agora.util;

import org.apache.commons.lang3.RandomStringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 業務ID生成器
 * 生成格式：前綴 + 時間戳(到毫秒) + 序列號 + 隨機字符串
 */
public class BusinessIdGenerator {
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("yyMMddHHmmss");
    private static final AtomicInteger SEQUENCE = new AtomicInteger(0);
    private static final int MAX_SEQUENCE = 9999;

    /**
     * 生成業務ID
     *
     * @param prefix ID前綴，如：ORD(訂單)、PRO(商品)
     * @return 生成的業務ID
     */
    public static String generateId(String prefix) {
        // 獲取當前時間戳（到毫秒）
        String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMATTER);
        // 獲取序列號
        int sequence = SEQUENCE.getAndIncrement() % MAX_SEQUENCE;
        // 生成隨機字符串
        String random = RandomStringUtils.randomAlphanumeric(4);
        // 組合：前綴 + 時間戳 + 序列號 + 隨機字符串
        return String.format("%s%s%04d%s", prefix, timestamp, sequence, random).toUpperCase(Locale.ROOT);
    }

    /**
     * 生成訂單ID
     */
    public static String orderId() {
        return generateId("O");
    }

    /**
     * 生成充值ID
     */
    public static String rechargeId() {
        return generateId("R");
    }

    /**
     * 生成商品ID
     */
    public static String productId() {
        return generateId("P");
    }

    /**
     * 生成提現ID
     */
    public static String withdrawId() {
        return generateId("W");
    }

    /**
     * 生成客戶問題ID
     */
    public static String issueId() {
        return generateId("I");
    }

    /**
     * 生成質押ID
     */
    public static String stakingId() {
        return generateId("S");
    }
} 