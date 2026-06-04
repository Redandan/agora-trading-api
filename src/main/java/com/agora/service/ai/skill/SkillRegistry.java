package com.agora.service.ai.skill;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI Skill 注冊表
 * <p>
 * Spring 啟動時自動收集所有 AiSkill 實作，建立 intentCode → skill 映射。
 * 新增能力只需建立新的 AiSkill 實作並加上 @Component，不需修改此類別。
 */
@Slf4j
@Component
public class SkillRegistry {

    private final List<AiSkill> skills;
    private Map<String, AiSkill> skillMap;

    public SkillRegistry(List<AiSkill> skills) {
        this.skills = skills;
    }

    @PostConstruct
    public void init() {
        skillMap = new HashMap<>();
        for (AiSkill skill : skills) {
            String code = skill.getIntentCode().toUpperCase();
            skillMap.put(code, skill);
            log.info("已注冊 AI Skill: {}", code);
        }
    }

    /**
     * 執行對應意圖的 Skill
     *
     * @return 回覆文字；若無對應 Skill 或執行結果為 null，回傳 null（由呼叫方 fallback 到一般聊天）
     */
    public String execute(String intentCode, String text) {
        if (intentCode == null) return null;
        AiSkill skill = skillMap.get(intentCode.toUpperCase());
        if (skill == null) return null;
        return skill.execute(text);
    }

    /**
     * 執行對應意圖的 Skill，回傳富回覆（含可選鍵盤）
     *
     * @return SkillResponse；若無對應 Skill 或執行結果為 null，回傳 null
     */
    public SkillResponse executeRich(String intentCode, String text) {
        if (intentCode == null) return null;
        AiSkill skill = skillMap.get(intentCode.toUpperCase());
        if (skill == null) return null;
        return skill.executeRich(text);
    }

    /**
     * 取得所有已注冊的 Skill（用於動態產生 IntentClassifier 的 system prompt）
     */
    public List<AiSkill> getAll() {
        return skills;
    }

    public boolean hasSkill(String intentCode) {
        if (intentCode == null) return false;
        return skillMap.containsKey(intentCode.toUpperCase());
    }

    public boolean needsFallback(String intentCode) {
        if (intentCode == null) return false;
        AiSkill skill = skillMap.get(intentCode.toUpperCase());
        return skill != null && skill.needsFallback();
    }
}
