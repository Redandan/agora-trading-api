package com.agora.service.ai;

import com.agora.model.AiGroupConversionDaily;
import com.agora.model.AiGroupConversionEvent;
import com.agora.repository.system.AiGroupConversionDailyRepository;
import com.agora.repository.system.AiGroupConversionEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * AI 群組對話轉化追蹤服務
 * 記錄事件流水帳並即時更新每日統計計數
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGroupConversionService {

    private final AiGroupConversionEventRepository eventRepository;
    private final AiGroupConversionDailyRepository dailyRepository;

    // ─── 對話量事件 ────────────────────────────────────────────────────────────

    @Async
    public void recordProactiveChat(Long groupId, Long triggeredBy) {
        record(groupId, AiGroupConversionEvent.EventType.PROACTIVE_CHAT, null, triggeredBy);
        incrementDaily(groupId, DailyField.PROACTIVE_CHAT);
    }

    @Async
    public void recordMentionChat(Long groupId, Long triggeredBy) {
        record(groupId, AiGroupConversionEvent.EventType.MENTION_CHAT, null, triggeredBy);
        incrementDaily(groupId, DailyField.MENTION_CHAT);
    }

    // ─── Skill 觸發事件 ────────────────────────────────────────────────────────

    @Async
    public void recordSkillTriggered(Long groupId, String intentCode, Long triggeredBy) {
        record(groupId, AiGroupConversionEvent.EventType.SKILL_TRIGGERED, intentCode, triggeredBy);
        incrementDaily(groupId, DailyField.SKILL_HIT);
        incrementDaily(groupId, skillToDailyField(intentCode));
    }

    @Async
    public void recordGeneralFallback(Long groupId, Long triggeredBy) {
        record(groupId, AiGroupConversionEvent.EventType.GENERAL_FALLBACK, "GENERAL", triggeredBy);
        incrementDaily(groupId, DailyField.GENERAL_FALLBACK);
    }

    // ─── 品質指標事件 ──────────────────────────────────────────────────────────

    @Async
    public void recordButtonClicked(Long groupId, Long triggeredBy) {
        record(groupId, AiGroupConversionEvent.EventType.BUTTON_CLICKED, null, triggeredBy);
        incrementDaily(groupId, DailyField.BUTTON_CLICKED);
    }

    @Async
    public void recordKnowledgeHit(Long groupId) {
        record(groupId, AiGroupConversionEvent.EventType.KNOWLEDGE_HIT, null, null);
        incrementDaily(groupId, DailyField.KNOWLEDGE_HIT);
    }

    // ─── 私有方法 ──────────────────────────────────────────────────────────────

    @Transactional
    protected void record(Long groupId, AiGroupConversionEvent.EventType type,
                          String intentCode, Long triggeredBy) {
        try {
            AiGroupConversionEvent event = new AiGroupConversionEvent();
            event.setGroupId(groupId);
            event.setEventType(type.name());
            event.setIntentCode(intentCode);
            event.setTriggeredBy(triggeredBy);
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("[ConversionTracking] 事件記錄失敗 groupId={} type={}: {}", groupId, type, e.getMessage());
        }
    }

    @Transactional
    protected void incrementDaily(Long groupId, DailyField field) {
        if (field == null) return;
        try {
            LocalDate today = LocalDate.now();
            AiGroupConversionDaily daily = dailyRepository
                    .findByGroupIdAndStatDate(groupId, today)
                    .orElseGet(() -> newDaily(groupId, today));
            applyIncrement(daily, field);
            dailyRepository.save(daily);
        } catch (Exception e) {
            log.warn("[ConversionTracking] 每日統計更新失敗 groupId={} field={}: {}", groupId, field, e.getMessage());
        }
    }

    private AiGroupConversionDaily newDaily(Long groupId, LocalDate date) {
        AiGroupConversionDaily d = new AiGroupConversionDaily();
        d.setGroupId(groupId);
        d.setStatDate(date);
        return d;
    }

    private void applyIncrement(AiGroupConversionDaily d, DailyField field) {
        switch (field) {
            case PROACTIVE_CHAT:   d.setProactiveChat(d.getProactiveChat() + 1); break;
            case MENTION_CHAT:     d.setMentionChat(d.getMentionChat() + 1); break;
            case BET_TRIGGER:      d.setBetTrigger(d.getBetTrigger() + 1); break;
            case BUY_TRIGGER:      d.setBuyTrigger(d.getBuyTrigger() + 1); break;
            case RECHARGE_TRIGGER: d.setRechargeTrigger(d.getRechargeTrigger() + 1); break;
            case GAME_TRIGGER:     d.setGameTrigger(d.getGameTrigger() + 1); break;
            case STORE_TRIGGER:    d.setStoreTrigger(d.getStoreTrigger() + 1); break;
            case PROMO_TRIGGER:    d.setPromoTrigger(d.getPromoTrigger() + 1); break;
            case SKILL_HIT:        d.setSkillHit(d.getSkillHit() + 1); break;
            case GENERAL_FALLBACK: d.setGeneralFallback(d.getGeneralFallback() + 1); break;
            case BUTTON_CLICKED:   d.setButtonClicked(d.getButtonClicked() + 1); break;
            case KNOWLEDGE_HIT:    d.setKnowledgeHit(d.getKnowledgeHit() + 1); break;
        }
    }

    private DailyField skillToDailyField(String intentCode) {
        if (intentCode == null) return null;
        switch (intentCode.toUpperCase()) {
            case "MARKET":   return DailyField.BET_TRIGGER;
            case "BUY":      return DailyField.BUY_TRIGGER;
            case "RECHARGE": return DailyField.RECHARGE_TRIGGER;
            case "GAME":     return DailyField.GAME_TRIGGER;
            case "STORE":    return DailyField.STORE_TRIGGER;
            case "PROMO":    return DailyField.PROMO_TRIGGER;
            default:         return null;
        }
    }

    private enum DailyField {
        PROACTIVE_CHAT, MENTION_CHAT,
        BET_TRIGGER, BUY_TRIGGER, RECHARGE_TRIGGER,
        GAME_TRIGGER, STORE_TRIGGER, PROMO_TRIGGER,
        SKILL_HIT, GENERAL_FALLBACK, BUTTON_CLICKED, KNOWLEDGE_HIT
    }
}
