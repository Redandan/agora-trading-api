package com.agora.dto.analytics;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@Schema(description = "Slot 遊戲流量概覽統計")
public class SlotOverviewResponse {

    @Schema(description = "查詢起始時間")
    private LocalDateTime startTime;

    @Schema(description = "查詢結束時間")
    private LocalDateTime endTime;

    @Schema(description = "篩選的遊戲 ID，null 代表全部遊戲")
    private String gameId;

    // ── 整體指標 ─────────────────────────────────────────────────────────────

    @Schema(description = "區間總局數", example = "12480")
    private long totalRounds;

    @Schema(description = "今日總局數", example = "342")
    private long todayRounds;

    @Schema(description = "昨日總局數（對比）", example = "410")
    private long yesterdayRounds;

    @Schema(description = "中獎局數", example = "5832")
    private long winRounds;

    @Schema(description = "中獎率 %（保留 2 位小數）", example = "46.73")
    private double winRate;

    @Schema(description = "區間活躍玩家數（唯一用戶）", example = "87")
    private long activePlayers;

    @Schema(description = "今日活躍玩家數", example = "23")
    private long todayActivePlayers;

    @Schema(description = "總下注金額")
    private BigDecimal totalBet;

    @Schema(description = "今日總下注金額")
    private BigDecimal todayBet;

    @Schema(description = "總派彩金額")
    private BigDecimal totalPayout;

    @Schema(description = "毛利（totalBet - totalPayout）")
    private BigDecimal grossRevenue;

    @Schema(description = "今日毛利")
    private BigDecimal todayGrossRevenue;

    @Schema(description = "實際 RTP %（totalPayout / totalBet × 100）", example = "88.34")
    private double actualRtp;

    @Schema(description = "平均每局下注", example = "10.50")
    private BigDecimal avgBetPerRound;

    @Schema(description = "平均每玩家下注（總下注 / 活躍玩家）", example = "143.68")
    private BigDecimal avgBetPerPlayer;

    @Schema(description = "區間最高中獎倍率", example = "500")
    private int maxMultiplier;

    // ── 趨勢 ─────────────────────────────────────────────────────────────────

    @Schema(description = "每日趨勢（局數、活躍玩家、下注、毛利）")
    private List<SlotDailyStatDto> dailyTrend;

    @Schema(description = "每小時分佈（查詢範圍 ≤ 48 小時時填充，否則為 null）")
    private List<SlotHourlyStatDto> hourlyDistribution;

    // ── 嵌套 DTO ─────────────────────────────────────────────────────────────

    @Data
    @Builder
    @Schema(description = "Slot 每日統計點")
    public static class SlotDailyStatDto {
        @Schema(description = "日期，格式 yyyy-MM-dd", example = "2026-03-15")
        private String date;
        @Schema(description = "當日局數", example = "342")
        private long rounds;
        @Schema(description = "當日活躍玩家", example = "23")
        private long players;
        @Schema(description = "當日總下注")
        private BigDecimal totalBet;
        @Schema(description = "當日毛利（下注 - 派彩）")
        private BigDecimal grossRevenue;
    }

    @Data
    @Builder
    @Schema(description = "Slot 每小時統計點")
    public static class SlotHourlyStatDto {
        @Schema(description = "小時（0-23）", example = "14")
        private int hour;
        @Schema(description = "當小時局數", example = "58")
        private long rounds;
        @Schema(description = "當小時活躍玩家", example = "12")
        private long players;
        @Schema(description = "當小時總下注")
        private BigDecimal totalBet;
    }
}
