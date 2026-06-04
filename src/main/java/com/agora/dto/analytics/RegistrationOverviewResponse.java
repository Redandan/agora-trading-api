package com.agora.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Schema(description = "註冊流量概覽統計")
public class RegistrationOverviewResponse {

    @Schema(description = "查詢起始時間")
    private LocalDateTime startTime;

    @Schema(description = "查詢結束時間")
    private LocalDateTime endTime;

    @Schema(description = "區間內總註冊數", example = "428")
    private long totalRegistrations;

    @Schema(description = "今日註冊數", example = "17")
    private long todayRegistrations;

    @Schema(description = "昨日註冊數（對比用）", example = "22")
    private long yesterdayRegistrations;

    @Schema(description = "上週同日註冊數（對比用）", example = "15")
    private long lastWeekSameDayRegistrations;

    @Schema(description = "按日分組的每日註冊趨勢")
    private List<DailyStatDto> dailyTrend;

    @Schema(description = "按小時分組（查詢範圍 ≤ 48 小時時填充，否則為 null）")
    private List<HourlyStatDto> hourlyDistribution;

    @Schema(description = "按註冊渠道（方式）分組")
    private List<ChannelStatDto> channelBreakdown;

    @Schema(description = "Top N 推廣碼使用量")
    private List<PromoCodeStatDto> topPromoCodes;

    @Data
    @Builder
    @Schema(description = "每日統計點")
    public static class DailyStatDto {
        @Schema(description = "日期，格式 yyyy-MM-dd", example = "2026-03-15")
        private String date;
        @Schema(description = "當日註冊數", example = "17")
        private long count;
    }

    @Data
    @Builder
    @Schema(description = "每小時統計點")
    public static class HourlyStatDto {
        @Schema(description = "小時 (0-23)", example = "14")
        private int hour;
        @Schema(description = "當小時註冊數", example = "5")
        private long count;
    }

    @Data
    @Builder
    @Schema(description = "渠道（註冊方式）統計")
    public static class ChannelStatDto {
        @Schema(description = "渠道名稱，如 FORM / GOOGLE / TELEGRAM_BOT / UNKNOWN", example = "FORM")
        private String method;
        @Schema(description = "該渠道註冊數", example = "350")
        private long count;
        @Schema(description = "佔比百分比（保留 2 位小數）", example = "81.78")
        private double percentage;
    }

    @Data
    @Builder
    @Schema(description = "推廣碼統計")
    public static class PromoCodeStatDto {
        @Schema(description = "推廣碼，未使用推廣碼顯示為 (direct)", example = "SUMMER2026")
        private String promoCode;
        @Schema(description = "使用此推廣碼的註冊數", example = "42")
        private long count;
    }
}
