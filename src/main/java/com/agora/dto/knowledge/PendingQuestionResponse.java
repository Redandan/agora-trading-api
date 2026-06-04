package com.agora.dto.knowledge;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PendingQuestionResponse {
    private Long id;
    private String question;
    private Long groupId;
    private String askedBy;
    private String status;
    private String adminAnswer;
    private String knowledgeId;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
}
