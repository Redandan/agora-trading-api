package com.agora.util;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期工具类
 * 提供统一的日期格式化方法
 */
public class DateUtil {
    
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Taipei");
    
    /**
     * 格式化日期为带时区的ISO-8601字符串
     * 
     * @param date 日期对象
     * @return 格式化后的字符串，如果date为null返回null
     */
    public static String formatDateWithZone(Date date) {
        if (date == null) {
            return null;
        }
        return ZonedDateTime.ofInstant(date.toInstant(), ZONE_ID)
                .format(DATE_TIME_FORMATTER);
    }
}

