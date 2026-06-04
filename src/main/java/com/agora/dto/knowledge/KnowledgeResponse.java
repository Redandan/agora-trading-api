package com.agora.dto.knowledge;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class KnowledgeResponse {
    private String id;
    private String title;
    private String content;
    private String source;
    private String createdAt;
}
