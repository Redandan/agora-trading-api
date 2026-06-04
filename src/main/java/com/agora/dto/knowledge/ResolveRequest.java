package com.agora.dto.knowledge;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ResolveRequest {
    /** 管理員填寫的回答 */
    private String answer;
    /** 是否同時加入知識庫供 AI 日後使用 */
    private boolean addToKnowledge;
}
