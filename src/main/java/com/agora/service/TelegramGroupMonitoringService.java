package com.agora.service;

import com.agora.dto.telegram.GroupActiveUserDTO;
import com.agora.dto.telegram.GroupActivityStatsDTO;
import com.agora.dto.telegram.GroupAiStrategyDTO;
import com.agora.dto.telegram.GroupDetailDTO;
import com.agora.dto.telegram.GroupEditRequest;
import com.agora.dto.telegram.GroupMessageDTO;
import com.agora.dto.telegram.MonitoredGroupDTO;
import com.agora.enums.system.PersonalityType;
import com.agora.enums.system.ReplyMode;
import com.agora.model.TgGroupMessageBuffer;
import com.agora.model.TgMonitoredGroup;
import com.agora.repository.system.TgGroupMessageBufferRepository;
import com.agora.repository.system.TgMonitoredGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.telegram.telegrambots.meta.api.objects.chat.Chat;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.Update;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelegramGroupMonitoringService {

    private static final int MAX_MESSAGES_PER_GROUP = 300;
    private static final ZoneId SYSTEM_ZONE = ZoneId.systemDefault();

    private final TgMonitoredGroupRepository groupRepository;
    private final TgGroupMessageBufferRepository messageBufferRepository;

    @Transactional
    public void collectUpdate(Update update) {
        if (update == null || !update.hasMessage()) {
            return;
        }

        Message message = update.getMessage();
        if (message == null || message.getChat() == null) {
            return;
        }

        Chat chat = message.getChat();
        if (!isSupportedGroupType(chat.getType())) {
            return;
        }

        Long groupId = chat.getId();
        if (groupId == null) {
            return;
        }

        Integer telegramMessageId = message.getMessageId();
        if (telegramMessageId != null && messageBufferRepository.existsByTgGroupIdAndTgMessageId(groupId, telegramMessageId)) {
            return;
        }

        LocalDateTime sentAt = resolveSentAt(message);

        TgMonitoredGroup monitoredGroup = groupRepository.findByTgGroupId(groupId)
            .orElseGet(() -> createMonitoredGroup(groupId, chat, sentAt));

        monitoredGroup.setGroupName(chat.getTitle());
        monitoredGroup.setGroupType(chat.getType());
        monitoredGroup.setLastMessageAt(sentAt);
        groupRepository.save(monitoredGroup);

        TgGroupMessageBuffer buffer = new TgGroupMessageBuffer();
        buffer.setTgGroupId(groupId);
        buffer.setTgUserId(message.getFrom() != null ? message.getFrom().getId().longValue() : null);
        buffer.setTgMessageId(telegramMessageId);
        buffer.setMessageType(resolveMessageType(message));
        buffer.setMessageText(resolveMessageText(message));
        buffer.setSentAt(sentAt);
        messageBufferRepository.save(buffer);

        trimMessageBuffer(groupId);
    }

    @Transactional(readOnly = true)
    public List<MonitoredGroupDTO> getMonitoredGroups() {
        List<TgMonitoredGroup> groups = groupRepository.findAllByOrderByLastMessageAtDesc();
        List<MonitoredGroupDTO> result = new ArrayList<>();

        for (TgMonitoredGroup group : groups) {
            result.add(toMonitoredGroupDto(group));
        }

        return result;
    }

    @Transactional(readOnly = true)
    public GroupDetailDTO getGroupDetail(Long groupId, int userLimit, int messageLimit) {
        return GroupDetailDTO.builder()
                .activity(getGroupActivity(groupId))
                .activeUsers(getActiveUsers(groupId, userLimit))
                .messages(getRecentMessages(groupId, messageLimit))
                .build();
    }

    @Transactional(readOnly = true)
    public GroupActivityStatsDTO getGroupActivity(Long groupId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime oneMinuteAgo = now.minusMinutes(1);
        LocalDateTime oneHourAgo = now.minusHours(1);

        long perMinute = messageBufferRepository.countByGroupIdSince(groupId, oneMinuteAgo);
        long perHour = messageBufferRepository.countByGroupIdSince(groupId, oneHourAgo);
        long recentActiveUsers = messageBufferRepository.countDistinctUsersSince(groupId, oneHourAgo);

        LocalDateTime lastMessageTime = resolveLastMessageTime(groupId);

        return GroupActivityStatsDTO.builder()
            .groupId(groupId)
            .messagesPerMinute(perMinute)
            .messagesPerHour(perHour)
            .lastMessageTime(lastMessageTime)
            .recentActiveUsers(recentActiveUsers)
            .build();
    }

    @Transactional(readOnly = true)
    public List<GroupActiveUserDTO> getActiveUsers(Long groupId, int limit) {
        List<Object[]> rows = messageBufferRepository.countMessagesByUserInGroup(groupId);
        List<GroupActiveUserDTO> result = new ArrayList<>();

        for (Object[] row : rows) {
            Long userId = (Long) row[0];
            long count = ((Number) row[1]).longValue();
            result.add(GroupActiveUserDTO.builder()
                .userId(userId)
                .messageCount(count)
                .build());

            if (result.size() >= limit) {
                break;
            }
        }

        return result;
    }

    @Transactional(readOnly = true)
    public List<GroupMessageDTO> getRecentMessages(Long groupId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_MESSAGES_PER_GROUP));
        List<TgGroupMessageBuffer> rows = messageBufferRepository.findRecentByGroupId(groupId, PageRequest.of(0, safeLimit));

        List<GroupMessageDTO> result = new ArrayList<>();
        for (TgGroupMessageBuffer row : rows) {
            result.add(GroupMessageDTO.builder()
                .groupId(row.getTgGroupId())
                .userId(row.getTgUserId())
                .telegramMessageId(row.getTgMessageId())
                .messageType(row.getMessageType())
                .messageText(row.getMessageText())
                .sentAt(row.getSentAt())
                .build());
        }

        return result;
    }

    @Transactional(readOnly = true)
    public boolean isGroupAiEnabled(Long groupId) {
        return groupRepository.findByTgGroupId(groupId)
            .map(group -> Boolean.TRUE.equals(group.getAiChatEnabled()))
            .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isGroupManualPromptEnabled(Long groupId) {
        return groupRepository.findByTgGroupId(groupId)
            .map(group -> Boolean.TRUE.equals(group.getAiManualPromptEnabled()))
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public GroupAiStrategyDTO getStrategy(Long groupId) {
        TgMonitoredGroup group = groupRepository.findByTgGroupId(groupId)
            .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));
        return toStrategyDto(group);
    }

    @Transactional
    public MonitoredGroupDTO updateGroup(Long groupId, GroupEditRequest req) {
        TgMonitoredGroup group = groupRepository.findByTgGroupId(groupId)
            .orElseThrow(() -> new IllegalArgumentException("Group not found: " + groupId));

        if (req.getAiChatEnabled() != null) {
            group.setAiChatEnabled(req.getAiChatEnabled());
        }
        if (req.getReplyMode() != null) {
            group.setReplyMode(req.getReplyMode());
        }
        if (req.getMessageCountThreshold() != null && req.getMessageCountThreshold() > 0) {
            group.setMessageCountThreshold(req.getMessageCountThreshold());
        }
        if (req.getMinIntervalMinutes() != null && req.getMinIntervalMinutes() >= 0) {
            group.setMinIntervalMinutes(req.getMinIntervalMinutes());
        }
        if (req.getPersonality() != null) {
            group.setPersonality(req.getPersonality());
            if (req.getPersonality() == PersonalityType.CUSTOM) {
                group.setAiManualPromptEnabled(true);
                if (req.getCustomPrompt() != null) {
                    group.setAiManualPromptText(req.getCustomPrompt().trim());
                }
            } else {
                group.setAiManualPromptEnabled(false);
            }
        }

        groupRepository.save(group);
        return toMonitoredGroupDto(group);
    }

    private GroupAiStrategyDTO toStrategyDto(TgMonitoredGroup group) {
        return GroupAiStrategyDTO.builder()
            .replyMode(group.getReplyMode() != null ? group.getReplyMode() : ReplyMode.ACTIVE)
            .messageCountThreshold(group.getMessageCountThreshold() != null ? group.getMessageCountThreshold() : 10)
            .minIntervalMinutes(group.getMinIntervalMinutes() != null ? group.getMinIntervalMinutes() : 5)
            .personality(group.getPersonality() != null ? group.getPersonality() : PersonalityType.FRIENDLY)
            .customPrompt(group.getAiManualPromptText())
            .build();
    }

    private MonitoredGroupDTO toMonitoredGroupDto(TgMonitoredGroup group) {
        return MonitoredGroupDTO.builder()
            .groupId(group.getTgGroupId())
            .groupName(group.getGroupName())
            .groupType(group.getGroupType())
            .firstSeenAt(group.getFirstSeenAt())
            .lastMessageAt(group.getLastMessageAt())
            .aiChatEnabled(Boolean.TRUE.equals(group.getAiChatEnabled()))
            .aiManualPromptEnabled(Boolean.TRUE.equals(group.getAiManualPromptEnabled()))
            .aiManualPromptText(group.getAiManualPromptText())
            .bufferedMessageCount(messageBufferRepository.countByTgGroupId(group.getTgGroupId()))
            .replyMode(group.getReplyMode())
            .messageCountThreshold(group.getMessageCountThreshold())
            .minIntervalMinutes(group.getMinIntervalMinutes())
            .personality(group.getPersonality())
            .build();
    }

    private TgMonitoredGroup createMonitoredGroup(Long groupId, Chat chat, LocalDateTime firstSeenAt) {
        TgMonitoredGroup group = new TgMonitoredGroup();
        group.setTgGroupId(groupId);
        group.setGroupName(chat.getTitle());
        group.setGroupType(chat.getType());
        group.setFirstSeenAt(firstSeenAt);
        group.setLastMessageAt(firstSeenAt);
        group.setAiChatEnabled(true);
        group.setAiManualPromptEnabled(false);
        group.setAiManualPromptText(null);
        return group;
    }

    private void trimMessageBuffer(Long groupId) {
        long count = messageBufferRepository.countByTgGroupId(groupId);
        if (count <= MAX_MESSAGES_PER_GROUP) {
            return;
        }

        int trimCount = (int) (count - MAX_MESSAGES_PER_GROUP);
        List<Long> oldestIds = messageBufferRepository.findOldestIdsForTrim(groupId, trimCount);
        if (!oldestIds.isEmpty()) {
            messageBufferRepository.deleteByIds(oldestIds);
        }
    }

    private LocalDateTime resolveSentAt(Message message) {
        if (message.getDate() == null) {
            return LocalDateTime.now();
        }

        return LocalDateTime.ofInstant(Instant.ofEpochSecond(message.getDate()), SYSTEM_ZONE);
    }

    private LocalDateTime resolveLastMessageTime(Long groupId) {
        Optional<TgMonitoredGroup> group = groupRepository.findByTgGroupId(groupId);
        if (group.isPresent() && group.get().getLastMessageAt() != null) {
            return group.get().getLastMessageAt();
        }

        return messageBufferRepository.findLastMessageTime(groupId);
    }

    private boolean isSupportedGroupType(String chatType) {
        return "group".equals(chatType) || "supergroup".equals(chatType);
    }

    private String resolveMessageType(Message message) {
        if (message.hasText()) {
            return "text";
        }
        if (message.hasPhoto()) {
            return "photo";
        }
        if (message.hasSticker()) {
            return "sticker";
        }
        if (message.hasVideo()) {
            return "video";
        }
        if (message.hasDocument()) {
            return "document";
        }
        return "other";
    }

    private String resolveMessageText(Message message) {
        if (message.hasText()) {
            return message.getText();
        }
        if (message.getCaption() != null) {
            return message.getCaption();
        }
        return "[" + resolveMessageType(message) + "]";
    }
}
