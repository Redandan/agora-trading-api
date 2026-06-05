package com.agora.service.ai;

import com.agora.config.TelegramBotConfig;
import com.agora.dto.telegram.GroupAiPromptPreviewDTO;
import com.agora.dto.telegram.GroupAiSimulationResponseDTO;
import com.agora.dto.telegram.GroupAiStrategyDTO;
import com.agora.dto.telegram.GroupMessageDTO;
import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import com.agora.service.TelegramGroupMonitoringService;
import com.agora.service.ai.chroma.ChromaDocument;
import com.agora.service.ai.chroma.VectorStoreService;
import com.agora.service.ai.knowledge.AiPendingQuestionService;
import com.agora.service.ai.skill.SkillRegistry;
import com.agora.service.ai.skill.SkillResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 群組 AI 聊天參與服務
 * <p>
 * 根據群組當前聊天狀況，由 AI 自動選擇性參與對話：
 * - 被 @mention 時一定回覆
 * - 滿足機率與冷卻條件時隨機參與
 * - 取最近 N 條訊息作為 Prompt 上下文，讓回覆更貼近當前話題
 * - RAG：從 Chroma 查詢語意相關的歷史訊息與項目知識庫，增強回覆品質
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupAIChatService {

    private static final int MAX_CONTEXT_MESSAGES = 5;
    private static final String REPLY_GOAL = "請根據以上內容，生成一句自然、口語、可直接發在群組的回覆。";

    private static final String DEFAULT_SYSTEM_PROMPT =
            "你是 Agora Trading 的官方群組助理，暱稱「小幣」。\n" +
            "Agora Trading 專注於加密貨幣市場資料、策略分析、回測與交易風控。\n" +
            "你的職責是自然地參與群組對話，協助用戶理解行情、策略與風險。\n" +
            "請遵守以下規則：\n" +
            "- 回答要簡短，最多 2 句話，就像真人在用手機打字\n" +
            "- 使用繁體中文\n" +
            "- 語氣輕鬆自然，可適當加入 1~2 個表情符號\n" +
            "- 不要自我介紹為 AI，不要說「作為 AI」之類的話\n" +
            "- 不要重複前面用戶說過的話\n" +
            "- 若話題涉及加密貨幣或市場，可分享簡短看法，但不要給出保證獲利承諾\n" +
            "- 若有人提到或詢問 Agora，請聚焦介紹 trading、行情、回測、策略與風控能力\n" +
            "- 若用戶詢問非 trading 功能，請簡短說明此服務目前只處理 trading 相關問題";

    private final TelegramGroupMonitoringService monitoringService;
    private final GroqApiClient groqApiClient;
    private final TelegramBotConfig telegramBotConfig;
    private final VectorStoreService vectorStoreService;
    private final AiPendingQuestionService pendingQuestionService;
    private final SkillRegistry skillRegistry;
    private final IntentClassifier intentClassifier;
    private final com.agora.config.properties.AiGroupProperties props;

    /** 各群組最後一次 AI 回覆時間（冷卻控制） */
    private final Map<Long, Instant> lastReplyTime = new ConcurrentHashMap<>();

    /** 各群組自上次回覆後的新訊息計數（ACTIVE 模式用） */
    private final Map<Long, AtomicInteger> messageCounter = new ConcurrentHashMap<>();

    /** Cache of the bot identity suffix appended to system prompts; built once on first use */
    private volatile String identitySuffix = null;

    /**
     * 嘗試讓 AI 參與群組訊息，若觸發條件成立則生成回覆並發送。
     * 同時將訊息非同步存入 Chroma 供長期記憶使用。
     *
     * @param update 當前 Telegram update
     * @param bot    用於發送訊息的 Bot 實例
     */
    @Async
    public void tryParticipate(Update update, TelegramClient bot) {
        if (!groqApiClient.isEnabled()) return;
        if (!update.hasMessage()) return;

        Message message = update.getMessage();
        Long chatId = message.getChatId();
        String rawText = message.getText();

        if (rawText == null || rawText.trim().isEmpty()) {
            log.debug("[AI群組] chatId={} 跳過：無文字內容（可能是媒體訊息）", chatId);
            return;
        }
        if (rawText.trim().startsWith("/")) {
            log.debug("[AI群組] chatId={} 跳過：指令訊息 text=[{}]", chatId, rawText);
            return;
        }
        if (!isGroupChat(message)) {
            log.debug("[AI群組] chatId={} 跳過：非群組聊天 type={}", chatId,
                    message.getChat() != null ? message.getChat().getType() : "null");
            return;
        }

        Long groupId = chatId;
        if (!monitoringService.isGroupAiEnabled(groupId)) {
            log.debug("[AI群組] chatId={} 跳過：AI 未啟用", groupId);
            return;
        }
        String text = rawText;

        log.debug("[AI群組] chatId={} 收到訊息，開始評估是否回覆 text=[{}]", groupId, text);

        // 將此訊息存入 Chroma 長期記憶
        storeMessageToVector(groupId, message);

        GroupAiStrategyDTO strategy = loadStrategy(groupId);
        boolean mentioned = isMentioned(text) || isReplyToBot(message);

        if (!shouldReply(groupId, mentioned, strategy)) {
            log.debug("[AI群組] chatId={} 跳過：shouldReply=false mention={} mode={}", groupId, mentioned, strategy.getReplyMode());
            return;
        }

        if (!groqApiClient.hassufficientQuota(props.minQuotaRatio())) {
            log.warn("AI 配額不足（低於 {}%），跳過群組 {} 的回覆", (int) (props.minQuotaRatio() * 100), groupId);
            return;
        }

        log.info("AI 決定參與群組 {} 的對話（mention={}）", groupId, mentioned);

        Long userId = message.getFrom() != null ? message.getFrom().getId().longValue() : null;
        String askedBy = message.getFrom() != null
                ? (message.getFrom().getUserName() != null
                        ? message.getFrom().getUserName()
                        : message.getFrom().getFirstName())
                : null;

        try {
            // 若訊息去掉 @mention 後無實質內容，改用最近一條訊息做意圖分類
            String classifyText = stripMention(text);
            if (classifyText.isEmpty() && mentioned) {
                // 取最近幾條，過濾掉純 mention 訊息，找第一條有實質內容的
                classifyText = monitoringService.getRecentMessages(groupId, 5).stream()
                        .map(GroupMessageDTO::getMessageText)
                        .filter(t -> t != null && !t.trim().isEmpty())
                        .map(this::stripMention)
                        .filter(t -> !t.isEmpty())
                        .findFirst()
                        .orElse(text);
                log.info("訊息僅含 mention，改以最近訊息分類: {}", classifyText);
            }

            // 意圖分類：用 Groq 判斷意圖，路由到對應 Skill
            String intentCode = intentClassifier.classify(classifyText);
            log.info("意圖分類結果: {} → {}", classifyText, intentCode);

            String reply;
            SkillResponse skillResponse = null;
            if (skillRegistry.hasSkill(intentCode)) {
                String skillInput = "GROUP_ID".equals(intentCode) ? groupId.toString()
                        : "TELEGRAM_ID".equals(intentCode) ? (userId != null ? userId.toString() : classifyText)
                        : classifyText;
                skillResponse = skillRegistry.executeRich(intentCode, skillInput);
                reply = skillResponse != null ? skillResponse.getText() : null;
                if (reply == null && skillRegistry.needsFallback(intentCode)) {
                    reply = generateReply(groupId, text, askedBy);
                    skillResponse = null;
                    maybeRecordPendingQuestion(text, groupId, askedBy);
                } else {
                    log.debug("[AI群組] skill triggered groupId={} intent={}", groupId, intentCode);
                }
            } else {
                reply = generateReply(groupId, text, askedBy);
                maybeRecordPendingQuestion(text, groupId, askedBy);
            }

            if (reply == null || reply.trim().isEmpty()) return;

            SendMessage.SendMessageBuilder msgBuilder = SendMessage.builder()
                    .chatId(groupId.toString())
                    .text(reply)
                    .replyToMessageId(message.getMessageId());
            if (skillResponse != null && skillResponse.hasKeyboard()) {
                msgBuilder.replyMarkup(skillResponse.getKeyboard());
            }
            if (skillResponse != null && skillResponse.hasParseMode()) {
                msgBuilder.parseMode(skillResponse.getParseMode());
            }
            SendMessage sendMessage = msgBuilder.build();

            bot.execute(sendMessage);
            lastReplyTime.put(groupId, Instant.now());
            messageCounter.remove(groupId);
            log.info("AI 已回覆群組 {} 訊息", groupId);

        } catch (TelegramApiException e) {
            log.warn("AI 回覆群組 {} 失敗: {}", groupId, e.getMessage());
        } catch (Exception e) {
            log.error("AI 參與群組對話時發生未預期錯誤", e);
        }
    }

    // ─── 私有方法 ────────────────────────────────────────────────────────────

    private String generateReply(Long groupId, String triggerText, String askedBy) {
        int promptContextSize = Math.min(Math.max(1, props.contextSize()), MAX_CONTEXT_MESSAGES);
        List<GroupMessageDTO> recentMessages = monitoringService.getRecentMessages(groupId, promptContextSize);

        List<Map<String, String>> messages = new ArrayList<>();

        String systemPrompt = resolveSystemPrompt(groupId);
        messages.add(buildMessage("system", systemPrompt));

        String finalUserPrompt = buildFinalUserPrompt(recentMessages, triggerText, groupId);
        messages.add(buildMessage("user", finalUserPrompt));

        return groqApiClient.chat(messages);
    }

    public GroupAiPromptPreviewDTO previewPrompt(Long groupId, int limit, String triggerText) {
        int safeLimit = Math.min(Math.max(1, limit), MAX_CONTEXT_MESSAGES);
        String safeTrigger = triggerText == null ? "" : triggerText.trim();
        List<GroupMessageDTO> recentMessages = monitoringService.getRecentMessages(groupId, safeLimit);

        List<String> contextMessages = new ArrayList<>(recentMessages).stream()
                .map(GroupMessageDTO::getMessageText)
                .filter(text -> text != null && !text.trim().isEmpty())
                .collect(Collectors.toList());

        return GroupAiPromptPreviewDTO.builder()
                .groupId(groupId)
                .manualPromptEnabled(monitoringService.isGroupManualPromptEnabled(groupId))
                .systemPrompt(resolveSystemPrompt(groupId))
                .goal(REPLY_GOAL)
                .contextMessages(contextMessages)
                .triggerMessage(safeTrigger)
                .finalUserPrompt(buildFinalUserPrompt(recentMessages, safeTrigger, groupId))
                .build();
    }

    public GroupAiSimulationResponseDTO simulateGenerateMessage(Long groupId, Integer limit, String triggerText, boolean previewOnly) {
        int safeLimit = limit == null ? MAX_CONTEXT_MESSAGES : limit;
        GroupAiPromptPreviewDTO preview = previewPrompt(groupId, safeLimit, triggerText);

        if (previewOnly || !groqApiClient.isEnabled()) {
            return GroupAiSimulationResponseDTO.builder()
                    .groupId(groupId)
                    .promptPreview(preview)
                    .generatedMessage(null)
                    .build();
        }

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(buildMessage("system", preview.getSystemPrompt()));
        messages.add(buildMessage("user", preview.getFinalUserPrompt()));

        String generated = groqApiClient.chat(messages);
        return GroupAiSimulationResponseDTO.builder()
                .groupId(groupId)
                .promptPreview(preview)
                .generatedMessage(generated)
                .build();
    }

    /**
     * 將訊息存入 Chroma 向量記憶庫（非同步，失敗不影響主流程）
     */
    private void storeMessageToVector(Long groupId, Message message) {
        try {
            String text = message.getText();
            if (text == null || text.trim().isEmpty()) return;

            String id = groupId + "_" + message.getMessageId();
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("groupId", String.valueOf(groupId));
            metadata.put("messageId", String.valueOf(message.getMessageId()));
            metadata.put("timestamp", String.valueOf(message.getDate()));
            if (message.getFrom() != null) {
                metadata.put("username", message.getFrom().getUserName() != null
                        ? message.getFrom().getUserName()
                        : message.getFrom().getFirstName());
            }

            vectorStoreService.addDocument(
                    VectorStoreService.groupCollection(groupId), id, text, metadata);

        } catch (Exception e) {
            log.debug("訊息存入向量庫失敗（非關鍵）: {}", e.getMessage());
        }
    }

    /**
     * 從 Chroma 查詢語意相關的歷史訊息
     */
    private List<String> queryRelevantHistory(Long groupId, String triggerText) {
        try {
            List<ChromaDocument> docs = vectorStoreService.query(
                    VectorStoreService.groupCollection(groupId), triggerText, props.ragHistorySize());
            return docs.stream()
                    .map(ChromaDocument::getDocument)
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("查詢群組歷史向量失敗: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 從項目知識庫查詢相關文件
     */
    private List<String> queryProjectKnowledge(String triggerText) {
        try {
            List<ChromaDocument> docs = vectorStoreService.query(
                    VectorStoreService.COLLECTION_PROJECT_KNOWLEDGE, triggerText, props.ragKnowledgeSize());
            return docs.stream()
                    .map(ChromaDocument::getDocument)
                    .filter(d -> d != null && !d.trim().isEmpty())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.debug("查詢項目知識庫失敗: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String buildFinalUserPrompt(List<GroupMessageDTO> recentMessages, String triggerText, Long groupId) {
        List<GroupMessageDTO> ordered = new ArrayList<>(recentMessages);
        java.util.Collections.reverse(ordered);

        StringBuilder sb = new StringBuilder();

        // 1. 項目知識（若有相關內容）
        String safeTrigger = triggerText == null ? "" : triggerText.trim();
        if (!safeTrigger.isEmpty()) {
            List<String> knowledge = queryProjectKnowledge(safeTrigger);
            if (!knowledge.isEmpty()) {
                sb.append("【項目知識庫】\n");
                for (String k : knowledge) {
                    sb.append("- ").append(k.trim()).append("\n");
                }
                sb.append("\n");
            }

            // 2. 語意相關的歷史訊息
            List<String> history = queryRelevantHistory(groupId, safeTrigger);
            if (!history.isEmpty()) {
                sb.append("【相關歷史對話】\n");
                for (String h : history) {
                    sb.append("- ").append(h.trim()).append("\n");
                }
                sb.append("\n");
            }
        }

        // 3. 最近對話
        sb.append("【最近對話（由舊到新）】\n");
        int idx = 1;
        for (GroupMessageDTO m : ordered) {
            String text = m.getMessageText();
            if (text == null || text.trim().isEmpty()) continue;
            sb.append(idx++).append(". ").append(text.trim()).append("\n");
        }

        if (!safeTrigger.isEmpty()) {
            sb.append("\n目前觸發訊息：").append(safeTrigger).append("\n");
        }

        sb.append("\n目標：").append(REPLY_GOAL);
        return sb.toString();
    }

    private String resolveSystemPrompt(Long groupId) {
        GroupAiStrategyDTO strategy = loadStrategy(groupId);
        String base;
        if (strategy.getPersonality() == PersonalityType.CUSTOM) {
            String custom = strategy.getCustomPrompt();
            base = (custom != null && !custom.trim().isEmpty()) ? custom.trim() : DEFAULT_SYSTEM_PROMPT;
        } else {
            base = buildPersonalityPrompt(strategy.getPersonality());
        }
        return base + getIdentitySuffix();
    }

    private String buildPersonalityPrompt(PersonalityType personality) {
        if (personality == null) return DEFAULT_SYSTEM_PROMPT;
        switch (personality) {
            case PROFESSIONAL:
                return "你是 Agora Trading 的專業交易助理，暱稱「小幣」。\n" +
                       "Agora Trading 專注於加密貨幣市場資料、策略分析、回測與交易風控。\n" +
                       "請遵守以下規則：\n" +
                       "- 回答簡潔精準，不廢話\n" +
                       "- 使用繁體中文\n" +
                       "- 語氣專業有禮，避免表情符號\n" +
                       "- 不要自我介紹為 AI\n" +
                       "- 以 trading、策略、行情與風控問題為優先";
            case HUMOROUS:
                return "你是 Agora Trading 的輕鬆型交易助理，暱稱「小幣」。\n" +
                       "Agora Trading 專注於加密貨幣市場資料、策略分析、回測與交易風控。\n" +
                       "請遵守以下規則：\n" +
                       "- 語氣輕鬆，但行情、風控和策略問題要保持清楚\n" +
                       "- 使用繁體中文\n" +
                       "- 可適當加入 1~2 個表情符號\n" +
                       "- 不要自我介紹為 AI\n" +
                       "- 若有人問 Agora，聚焦介紹 trading、行情、回測、策略與風控能力";
            default:
                return DEFAULT_SYSTEM_PROMPT;
        }
    }

    private GroupAiStrategyDTO loadStrategy(Long groupId) {
        try {
            return monitoringService.getStrategy(groupId);
        } catch (Exception e) {
            return GroupAiStrategyDTO.builder()
                    .replyMode(ReplyMode.ACTIVE)
                    .messageCountThreshold(10)
                    .minIntervalMinutes(5)
                    .personality(PersonalityType.FRIENDLY)
                    .build();
        }
    }

    private boolean shouldReply(Long groupId, boolean mentioned, GroupAiStrategyDTO strategy) {
        ReplyMode mode = strategy.getReplyMode() != null ? strategy.getReplyMode() : ReplyMode.ACTIVE;

        switch (mode) {
            case DISABLED:
                return false;
            case PASSIVE:
                return mentioned;
            case ACTIVE:
            default:
                messageCounter.computeIfAbsent(groupId, k -> new AtomicInteger(0)).incrementAndGet();
                if (mentioned) return true;
                return isActiveTrigger(groupId, strategy);
        }
    }

    private boolean isActiveTrigger(Long groupId, GroupAiStrategyDTO strategy) {
        int minMinutes = strategy.getMinIntervalMinutes() != null ? strategy.getMinIntervalMinutes() : 5;
        int threshold  = strategy.getMessageCountThreshold() != null ? strategy.getMessageCountThreshold() : 10;

        Instant last = lastReplyTime.get(groupId);
        if (last != null && Duration.between(last, Instant.now()).toMinutes() < minMinutes) {
            return false;
        }

        AtomicInteger counter = messageCounter.get(groupId);
        return counter != null && counter.get() >= threshold;
    }

    private String getIdentitySuffix() {
        if (identitySuffix == null) {
            String username = telegramBotConfig.getUsername();
            if (username != null && !username.trim().isEmpty()) {
                identitySuffix = "\n- 你在 Telegram 上的帳號是 @" + username + "，當有人 @" + username + " 時，就是在直接呼叫你，請用第一人稱回應。";
            } else {
                return "";
            }
        }
        return identitySuffix;
    }

    private String stripMention(String text) {
        String username = telegramBotConfig.getUsername();
        if (username == null || username.trim().isEmpty()) return text.trim();
        return text.replace("@" + username, "").trim();
    }

    private boolean isReplyToBot(Message message) {
        if (message.getReplyToMessage() == null) return false;
        org.telegram.telegrambots.meta.api.objects.User from = message.getReplyToMessage().getFrom();
        if (from == null || !Boolean.TRUE.equals(from.getIsBot())) return false;
        String username = telegramBotConfig.getUsername();
        return username != null && username.equalsIgnoreCase(from.getUserName());
    }

    private boolean isMentioned(String text) {
        String username = telegramBotConfig.getUsername();
        if (username == null || username.trim().isEmpty()) return false;
        return text.contains("@" + username);
    }

    private boolean isGroupChat(Message message) {
        if (message.getChat() == null) return false;
        String type = message.getChat().getType();
        return "group".equals(type) || "supergroup".equals(type);
    }

    /**
     * 若訊息是針對平台的具體問題且知識庫無法回答，則記錄為待確認。
     * 閒聊、笑話、一般知識等不記錄。
     */
    private void maybeRecordPendingQuestion(String text, Long groupId, String askedBy) {
        if (askedBy == null || text == null || text.trim().isEmpty()) return;
        String cleanText = stripMention(text);
        if (cleanText.isEmpty()) return;
        List<String> knowledge = queryProjectKnowledge(cleanText);
        if (!knowledge.isEmpty()) return;
        if (isPlatformSpecificRequest(cleanText)) {
            pendingQuestionService.record(cleanText, groupId, askedBy);
        }
    }

    /**
     * 使用 Groq 判斷訊息是否為針對平台/產品/服務的具體問題，
     * 排除閒聊、笑話、娛樂性請求等一般知識可回答的內容。
     */
    private boolean isPlatformSpecificRequest(String text) {
        if (!groqApiClient.isEnabled()) return false;
        try {
            List<Map<String, String>> messages = Collections.singletonList(
                buildMessage("user",
                    "判斷以下訊息是否屬於「用戶詢問某平台或服務的具體功能/規則/流程/問題」，且必須由平台方才能正確回答。\n" +
                    "符合條件的例子：詢問交易策略、風控規則、行情解讀、回測結果、系統配置。\n" +
                    "不符合條件的例子：說笑話、問候、非 trading 功能、閒聊、感謝語。\n" +
                    "只回答 YES 或 NO，不要有其他文字。\n" +
                    "訊息：" + text)
            );
            String result = groqApiClient.chat(messages, 5, 0.1);
            return result != null && result.trim().toUpperCase().startsWith("Y");
        } catch (Exception e) {
            log.debug("AI 判斷是否為平台需求失敗: {}", e.getMessage());
            return false;
        }
    }

    private Map<String, String> buildMessage(String role, String content) {
        Map<String, String> m = new HashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}
