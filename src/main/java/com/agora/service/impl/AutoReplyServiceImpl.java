package com.agora.service.impl;

import com.agora.model.ChatMessage;
import com.agora.model.User;
import com.agora.repository.system.ChatMessageRepository;
import com.agora.service.AutoReplyConfigService;
import com.agora.service.AutoReplyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AutoReplyServiceImpl implements AutoReplyService {

    private final ChatMessageRepository chatMessageRepository;
    private final AutoReplyConfigService configService;
    
    @Override
    @Transactional(readOnly = true)
    public String generateAutoReply(String userMessage, User user, String sessionId) {
        try {
            log.info("生成自動回復: userId={}, message={}", user.getId(), userMessage);
            
            // 使用新的配置服務查找匹配的配置
            Optional<String> reply = configService.processAutoReply(userMessage);
            
            if (reply.isPresent()) {
                log.info("自動回復匹配成功: reply={}", reply.get());
                return reply.get();
            }
            
            // 如果沒有匹配到配置，返回默認回復
            String defaultReply = "感謝您的留言，我會盡快為您處理。如有緊急問題，請聯繫客服：service@agora.com";
            log.info("使用默認回復: {}", defaultReply);
            return defaultReply;
            
        } catch (Exception e) {
            log.error("生成自動回復時發生錯誤: {}", e.getMessage(), e);
            return "抱歉，我遇到了一些技術問題。請稍後再試。";
        }
    }
    
    @Override
    @Transactional(readOnly = true)
    public String getSessionSummary(Long userId, String sessionId) {
        try {
            // 獲取最近的對話歷史
            List<ChatMessage> recentMessages = chatMessageRepository.findAll((root, query, cb) -> {
                List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
                predicates.add(cb.equal(root.get("sessionId"), sessionId));
                return cb.and(predicates.toArray(new jakarta.persistence.criteria.Predicate[0]));
            }).stream()
            .sorted(Comparator.comparing(ChatMessage::getCreatedAt).reversed())
            .limit(10)
            .collect(Collectors.toList());
            
            if (recentMessages.isEmpty()) {
                return "會話摘要：暫無對話記錄";
            }
            
            StringBuilder summary = new StringBuilder();
            summary.append("會話摘要：\n");
            summary.append("總消息數：").append(recentMessages.size()).append("\n");
            summary.append("最近消息：\n");
            
            for (ChatMessage message : recentMessages) {
                summary.append("- ").append(message.getContent()).append("\n");
            }
            
            return summary.toString();
            
        } catch (Exception e) {
            log.error("獲取會話摘要時發生錯誤: {}", e.getMessage(), e);
            return "會話摘要：獲取失敗";
        }
    }
}