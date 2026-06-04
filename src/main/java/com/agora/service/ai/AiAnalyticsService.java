package com.agora.service.ai;

import com.agora.dto.telegram.GroupConversionStatsDTO;
import com.agora.model.AiGroupConversionDaily;
import com.agora.model.TgMonitoredGroup;
import com.agora.repository.system.AiGroupConversionDailyRepository;
import com.agora.repository.system.TgMonitoredGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * #370 — Read-only analytics service for AI-group conversion stats.
 *
 * <p>Extracted from {@code AdminAiController} which previously injected
 * {@link AiGroupConversionDailyRepository} and
 * {@link TgMonitoredGroupRepository} directly (arch boundary violation —
 * controllers should not import {@code com.agora.repository.*}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiAnalyticsService {

    private final AiGroupConversionDailyRepository conversionDailyRepository;
    private final TgMonitoredGroupRepository monitoredGroupRepository;

    public List<GroupConversionStatsDTO> getConversionStats(Long groupId, LocalDate from, LocalDate to) {
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusDays(29);

        List<AiGroupConversionDaily> rows = (groupId != null)
                ? conversionDailyRepository.findByGroupIdAndDateRange(groupId, effectiveFrom, effectiveTo)
                : conversionDailyRepository.findByDateRange(effectiveFrom, effectiveTo);

        Map<Long, List<AiGroupConversionDaily>> byGroup = rows.stream()
                .collect(Collectors.groupingBy(AiGroupConversionDaily::getGroupId, LinkedHashMap::new, Collectors.toList()));

        Map<Long, String> groupNames = monitoredGroupRepository.findAll().stream()
                .collect(Collectors.toMap(TgMonitoredGroup::getTgGroupId, TgMonitoredGroup::getGroupName));

        List<GroupConversionStatsDTO> result = new ArrayList<>();
        for (Map.Entry<Long, List<AiGroupConversionDaily>> entry : byGroup.entrySet()) {
            Long gId = entry.getKey();
            List<AiGroupConversionDaily> dailyRows = entry.getValue();

            int totalProactiveChat    = dailyRows.stream().mapToInt(AiGroupConversionDaily::getProactiveChat).sum();
            int totalMentionChat      = dailyRows.stream().mapToInt(AiGroupConversionDaily::getMentionChat).sum();
            int totalBetTrigger       = dailyRows.stream().mapToInt(AiGroupConversionDaily::getBetTrigger).sum();
            int totalBuyTrigger       = dailyRows.stream().mapToInt(AiGroupConversionDaily::getBuyTrigger).sum();
            int totalRechargeTrigger  = dailyRows.stream().mapToInt(AiGroupConversionDaily::getRechargeTrigger).sum();
            int totalGameTrigger      = dailyRows.stream().mapToInt(AiGroupConversionDaily::getGameTrigger).sum();
            int totalStoreTrigger     = dailyRows.stream().mapToInt(AiGroupConversionDaily::getStoreTrigger).sum();
            int totalPromoTrigger     = dailyRows.stream().mapToInt(AiGroupConversionDaily::getPromoTrigger).sum();
            int totalSkillHit         = dailyRows.stream().mapToInt(AiGroupConversionDaily::getSkillHit).sum();
            int totalGeneralFallback  = dailyRows.stream().mapToInt(AiGroupConversionDaily::getGeneralFallback).sum();
            int totalButtonClicked    = dailyRows.stream().mapToInt(AiGroupConversionDaily::getButtonClicked).sum();
            int totalKnowledgeHit     = dailyRows.stream().mapToInt(AiGroupConversionDaily::getKnowledgeHit).sum();

            int denominator = totalSkillHit + totalGeneralFallback;
            double skillHitRate = denominator > 0
                    ? Math.round(totalSkillHit * 1000.0 / denominator) / 10.0
                    : 0.0;

            GroupConversionStatsDTO.Summary summary = GroupConversionStatsDTO.Summary.builder()
                    .totalProactiveChat(totalProactiveChat)
                    .totalMentionChat(totalMentionChat)
                    .totalChat(totalProactiveChat + totalMentionChat)
                    .totalBetTrigger(totalBetTrigger)
                    .totalBuyTrigger(totalBuyTrigger)
                    .totalRechargeTrigger(totalRechargeTrigger)
                    .totalGameTrigger(totalGameTrigger)
                    .totalStoreTrigger(totalStoreTrigger)
                    .totalPromoTrigger(totalPromoTrigger)
                    .totalSkillHit(totalSkillHit)
                    .totalGeneralFallback(totalGeneralFallback)
                    .totalButtonClicked(totalButtonClicked)
                    .totalKnowledgeHit(totalKnowledgeHit)
                    .skillHitRate(skillHitRate)
                    .build();

            List<GroupConversionStatsDTO.DailyRow> daily = dailyRows.stream()
                    .map(d -> GroupConversionStatsDTO.DailyRow.builder()
                            .date(d.getStatDate())
                            .proactiveChat(d.getProactiveChat())
                            .mentionChat(d.getMentionChat())
                            .betTrigger(d.getBetTrigger())
                            .buyTrigger(d.getBuyTrigger())
                            .rechargeTrigger(d.getRechargeTrigger())
                            .gameTrigger(d.getGameTrigger())
                            .storeTrigger(d.getStoreTrigger())
                            .promoTrigger(d.getPromoTrigger())
                            .skillHit(d.getSkillHit())
                            .generalFallback(d.getGeneralFallback())
                            .buttonClicked(d.getButtonClicked())
                            .knowledgeHit(d.getKnowledgeHit())
                            .build())
                    .collect(Collectors.toList());

            result.add(GroupConversionStatsDTO.builder()
                    .groupId(gId)
                    .groupName(groupNames.getOrDefault(gId, "Unknown"))
                    .from(effectiveFrom)
                    .to(effectiveTo)
                    .summary(summary)
                    .daily(daily)
                    .build());
        }

        return result;
    }
}
