package com.agora.service.ai.knowledge;

import com.agora.dto.knowledge.PendingQuestionResponse;
import com.agora.dto.knowledge.ResolveRequest;
import com.agora.model.AiPendingQuestion;
import com.agora.model.AiPendingQuestion.Status;
import com.agora.repository.system.AiPendingQuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiPendingQuestionService {

    private final AiPendingQuestionRepository repository;
    private final ProjectKnowledgeService projectKnowledgeService;

    /**
     * 記錄一個 AI 無法回答的問題（去重：相同問題且 PENDING 的不重複記錄）
     */
    public void record(String question, Long groupId, String askedBy) {
        if (question == null || question.trim().isEmpty()) return;

        if (repository.existsByQuestionAndStatus(question.trim(), Status.PENDING)) {
            log.debug("相同問題已在待確認佇列中，跳過: {}", question);
            return;
        }

        AiPendingQuestion entity = AiPendingQuestion.builder()
                .question(question.trim())
                .groupId(groupId)
                .askedBy(askedBy)
                .status(Status.PENDING)
                .build();

        repository.save(entity);
        log.info("新增待確認問題: groupId={}, question={}", groupId, question);
    }

    /**
     * 列出所有問題，可依狀態過濾
     */
    public List<PendingQuestionResponse> list(Status status) {
        List<AiPendingQuestion> questions = status != null
                ? repository.findByStatusOrderByCreatedAtDesc(status)
                : repository.findAllByOrderByCreatedAtDesc();
        return questions.stream().map(this::toResponse).collect(Collectors.toList());
    }

    /**
     * 解答問題：填寫回答，並可選擇直接加入知識庫
     */
    @Transactional
    public PendingQuestionResponse resolve(Long id, ResolveRequest request) {
        AiPendingQuestion question = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到問題 id=" + id));

        question.setAdminAnswer(request.getAnswer());
        question.setStatus(Status.RESOLVED);
        question.setResolvedAt(LocalDateTime.now());

        if (request.isAddToKnowledge() && request.getAnswer() != null) {
            String knowledgeId = projectKnowledgeService.addKnowledge(
                    question.getQuestion(),
                    request.getAnswer(),
                    "FAQ");
            question.setKnowledgeId(knowledgeId);
            log.info("問題已解答並加入知識庫: id={}, knowledgeId={}", id, knowledgeId);
        } else {
            log.info("問題已解答: id={}", id);
        }

        return toResponse(repository.save(question));
    }

    /**
     * 忽略問題（不需要加入知識庫）
     */
    @Transactional
    public void ignore(Long id) {
        AiPendingQuestion question = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("找不到問題 id=" + id));
        question.setStatus(Status.IGNORED);
        question.setResolvedAt(LocalDateTime.now());
        repository.save(question);
        log.info("問題已忽略: id={}", id);
    }

    /** 取得各狀態數量 */
    public long countPending() {
        return repository.countByStatus(Status.PENDING);
    }

    private PendingQuestionResponse toResponse(AiPendingQuestion q) {
        return PendingQuestionResponse.builder()
                .id(q.getId())
                .question(q.getQuestion())
                .groupId(q.getGroupId())
                .askedBy(q.getAskedBy())
                .status(q.getStatus().name())
                .adminAnswer(q.getAdminAnswer())
                .knowledgeId(q.getKnowledgeId())
                .resolvedAt(q.getResolvedAt())
                .createdAt(q.getCreatedAt())
                .build();
    }
}
