package com.agora.dto.telegram;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@Schema(description = "AI 群組對話轉化效率統計")
public class GroupConversionStatsDTO {

    @Schema(description = "群組 ID")
    private Long groupId;

    @Schema(description = "群組名稱")
    private String groupName;

    @Schema(description = "查詢起始日期")
    private LocalDate from;

    @Schema(description = "查詢結束日期")
    private LocalDate to;

    @Schema(description = "期間彙總")
    private Summary summary;

    @Schema(description = "每日明細")
    private List<DailyRow> daily;

    @Data
    @Builder
    public static class Summary {
        private int totalProactiveChat;
        private int totalMentionChat;
        private int totalChat;           // proactive + mention
        private int totalBetTrigger;
        private int totalBuyTrigger;
        private int totalRechargeTrigger;
        private int totalGameTrigger;
        private int totalStoreTrigger;
        private int totalPromoTrigger;
        private int totalSkillHit;
        private int totalGeneralFallback;
        private int totalButtonClicked;
        private int totalKnowledgeHit;
        @Schema(description = "Skill 命中率，例如 76.5")
        private double skillHitRate;     // skillHit / (skillHit + generalFallback) * 100
    }

    @Data
    @Builder
    public static class DailyRow {
        private LocalDate date;
        private int proactiveChat;
        private int mentionChat;
        private int betTrigger;
        private int buyTrigger;
        private int rechargeTrigger;
        private int gameTrigger;
        private int storeTrigger;
        private int promoTrigger;
        private int skillHit;
        private int generalFallback;
        private int buttonClicked;
        private int knowledgeHit;
    }
}
