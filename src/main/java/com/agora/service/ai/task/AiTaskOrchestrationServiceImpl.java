package com.agora.service.ai.task;

import com.agora.exception.BusinessException;
import com.agora.model.AiTaskArtifact;
import com.agora.model.AiTaskRecord;
import com.agora.model.AiTaskReview;
import com.agora.repository.system.AiTaskArtifactRepository;
import com.agora.repository.system.AiTaskRecordRepository;
import com.agora.repository.system.AiTaskReviewRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AiTaskOrchestrationServiceImpl implements AiTaskOrchestrationService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiTaskRecordRepository taskRepository;
    private final AiTaskArtifactRepository artifactRepository;
    private final AiTaskReviewRepository reviewRepository;

    @Override
    @Transactional
    public String createTask(String taskType, String objective, String priority, String requestedBy,
                             String assigneeType, String paramsJson) {
        AiTaskRecord task = new AiTaskRecord();
        task.setTaskType(parseEnum(taskType, AiTaskRecord.TaskType.class, "taskType"));
        task.setObjective(requireNonBlank(objective, "objective"));
        task.setPriority(priority == null || priority.isBlank()
                ? AiTaskRecord.Priority.NORMAL
                : parseEnum(priority, AiTaskRecord.Priority.class, "priority"));
        task.setRequestedBy(trimToNull(requestedBy));
        task.setAssigneeType(assigneeType == null || assigneeType.isBlank()
                ? AiTaskRecord.AssigneeType.SIRIN
                : parseEnum(assigneeType, AiTaskRecord.AssigneeType.class, "assigneeType"));
        task.setParamsJson(normalizeJsonObject(paramsJson, "paramsJson"));
        task.setStatus(AiTaskRecord.Status.QUEUED);
        AiTaskRecord saved = taskRepository.save(task);
        return toJson(Map.of("taskId", externalId(saved), "status", saved.getStatus().name()));
    }

    @Override
    @Transactional
    public String claimTask(String assigneeType, String workerId) {
        AiTaskRecord.AssigneeType parsedAssignee =
                parseEnum(assigneeType, AiTaskRecord.AssigneeType.class, "assigneeType");
        var task = taskRepository
                .findFirstByStatusAndAssigneeTypeOrderByCreatedAtAsc(AiTaskRecord.Status.QUEUED, parsedAssignee)
                .orElse(null);
        if (task == null) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("claimed", false);
            response.put("task", null);
            return toJson(response);
        }
        task.setStatus(AiTaskRecord.Status.ASSIGNED);
        task.setAssigneeId(requireNonBlank(workerId, "workerId"));
        task.setStartedAt(LocalDateTime.now());
        taskRepository.save(task);
        return toJson(Map.of("claimed", true, "task", taskMap(task, List.of(), List.of())));
    }

    @Override
    @Transactional
    public String updateTaskStatus(String taskId, String status, String summary,
                                   String errorMessage, String artifactsJson) {
        AiTaskRecord task = requireTask(taskId);
        AiTaskRecord.Status nextStatus = parseEnum(status, AiTaskRecord.Status.class, "status");
        if (nextStatus == AiTaskRecord.Status.QUEUED || nextStatus == AiTaskRecord.Status.ACCEPTED
                || nextStatus == AiTaskRecord.Status.REJECTED || nextStatus == AiTaskRecord.Status.CANCELLED) {
            throw new BusinessException("Worker update status not allowed: " + nextStatus);
        }
        task.setStatus(nextStatus);
        task.setResultSummary(trimToNull(summary));
        task.setErrorMessage(trimToNull(errorMessage));
        if (nextStatus == AiTaskRecord.Status.NEEDS_REVIEW || nextStatus == AiTaskRecord.Status.FAILED) {
            task.setCompletedAt(LocalDateTime.now());
        }
        taskRepository.save(task);
        List<AiTaskArtifact> savedArtifacts = saveArtifacts(task, artifactsJson);
        return toJson(Map.of(
                "taskId", externalId(task),
                "status", task.getStatus().name(),
                "artifactCountAdded", savedArtifacts.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public String getTask(String taskId) {
        AiTaskRecord task = requireTask(taskId);
        return toJson(taskMap(
                task,
                artifactRepository.findByTaskIdOrderByCreatedAtAsc(task.getId()),
                reviewRepository.findByTaskIdOrderByCreatedAtAsc(task.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public String listTasks(String status, String assigneeType, String taskType,
                            Integer ageHours, Integer limit) {
        int cappedLimit = limit != null && limit > 0 ? Math.min(limit, 100) : 20;
        LocalDateTime createdAfter = ageHours != null && ageHours > 0
                ? LocalDateTime.now().minusHours(Math.min(ageHours, 24 * 30))
                : null;
        List<AiTaskRecord> tasks = taskRepository.search(
                parseEnumOrNull(status, AiTaskRecord.Status.class, "status"),
                parseEnumOrNull(assigneeType, AiTaskRecord.AssigneeType.class, "assigneeType"),
                parseEnumOrNull(taskType, AiTaskRecord.TaskType.class, "taskType"),
                createdAfter,
                PageRequest.of(0, cappedLimit));
        List<Map<String, Object>> rows = tasks.stream()
                .map(task -> taskMap(task, List.of(), List.of()))
                .toList();
        return toJson(Map.of("count", rows.size(), "tasks", rows));
    }

    @Override
    @Transactional
    public String reviewTask(String taskId, String reviewStatus, String reviewNote, String reviewedBy) {
        AiTaskRecord task = requireTask(taskId);
        AiTaskReview.ReviewStatus parsedReview =
                parseEnum(reviewStatus, AiTaskReview.ReviewStatus.class, "reviewStatus");
        AiTaskReview review = new AiTaskReview();
        review.setTask(task);
        review.setReviewStatus(parsedReview);
        review.setReviewNote(trimToNull(reviewNote));
        review.setReviewedBy(requireNonBlank(reviewedBy, "reviewedBy"));
        AiTaskReview savedReview = reviewRepository.save(review);

        if (parsedReview == AiTaskReview.ReviewStatus.ACCEPTED) {
            task.setStatus(AiTaskRecord.Status.ACCEPTED);
            task.setCompletedAt(LocalDateTime.now());
        } else if (parsedReview == AiTaskReview.ReviewStatus.REJECTED) {
            task.setStatus(AiTaskRecord.Status.REJECTED);
            task.setCompletedAt(LocalDateTime.now());
        } else {
            task.setStatus(AiTaskRecord.Status.NEEDS_REVIEW);
        }
        taskRepository.save(task);
        return toJson(Map.of(
                "taskId", externalId(task),
                "status", task.getStatus().name(),
                "reviewId", savedReview.getId(),
                "reviewStatus", savedReview.getReviewStatus().name(),
                "kbPublished", false,
                "policy", "KB publishing is gated and not performed by v1 tools."));
    }

    private List<AiTaskArtifact> saveArtifacts(AiTaskRecord task, String artifactsJson) {
        JsonNode root = parseJson(artifactsJson, "artifactsJson", false);
        if (root == null || root.isNull()) {
            return List.of();
        }
        if (!root.isArray()) {
            throw new BusinessException("artifactsJson must be a JSON array");
        }
        List<AiTaskArtifact> saved = new ArrayList<>();
        for (JsonNode item : root) {
            AiTaskArtifact artifact = new AiTaskArtifact();
            artifact.setTask(task);
            artifact.setArtifactType(parseEnum(text(item, "artifactType"), AiTaskArtifact.ArtifactType.class, "artifactType"));
            artifact.setUriOrValue(requireNonBlank(text(item, "uriOrValue"), "uriOrValue"));
            artifact.setMetadataJson(normalizeJsonObject(item.has("metadata") ? item.get("metadata").toString() : null, "metadata"));
            saved.add(artifactRepository.save(artifact));
        }
        return saved;
    }

    private AiTaskRecord requireTask(String taskId) {
        Long id = parseTaskId(taskId);
        return taskRepository.findById(id)
                .orElseThrow(() -> new BusinessException("AI task not found: " + taskId));
    }

    private Long parseTaskId(String value) {
        String normalized = requireNonBlank(value, "taskId").trim();
        if (normalized.toLowerCase(Locale.ROOT).startsWith("ait-")) {
            normalized = normalized.substring(4);
        }
        try {
            return Long.valueOf(normalized);
        } catch (NumberFormatException e) {
            throw new BusinessException("Invalid taskId: " + value);
        }
    }

    private Map<String, Object> taskMap(
            AiTaskRecord task,
            List<AiTaskArtifact> artifacts,
            List<AiTaskReview> reviews) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", externalId(task));
        row.put("id", task.getId());
        row.put("taskType", task.getTaskType().name());
        row.put("objective", task.getObjective());
        row.put("status", task.getStatus().name());
        row.put("priority", task.getPriority().name());
        row.put("requestedBy", task.getRequestedBy());
        row.put("assigneeType", task.getAssigneeType().name());
        row.put("assigneeId", task.getAssigneeId());
        row.put("params", parseJsonValue(task.getParamsJson()));
        row.put("resultSummary", task.getResultSummary());
        row.put("errorMessage", task.getErrorMessage());
        row.put("createdAt", timeText(task.getCreatedAt()));
        row.put("updatedAt", timeText(task.getUpdatedAt()));
        row.put("startedAt", timeText(task.getStartedAt()));
        row.put("completedAt", timeText(task.getCompletedAt()));
        row.put("artifacts", artifacts.stream().map(this::artifactMap).toList());
        row.put("reviews", reviews.stream().map(this::reviewMap).toList());
        row.put("boundary", "Sirin task state only; no trading/OCO/order/fund/KB mutation.");
        return row;
    }

    private Map<String, Object> artifactMap(AiTaskArtifact artifact) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", artifact.getId());
        row.put("artifactType", artifact.getArtifactType().name());
        row.put("uriOrValue", artifact.getUriOrValue());
        row.put("metadata", parseJsonValue(artifact.getMetadataJson()));
        row.put("createdAt", timeText(artifact.getCreatedAt()));
        return row;
    }

    private Map<String, Object> reviewMap(AiTaskReview review) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", review.getId());
        row.put("reviewStatus", review.getReviewStatus().name());
        row.put("reviewNote", review.getReviewNote());
        row.put("reviewedBy", review.getReviewedBy());
        row.put("createdAt", timeText(review.getCreatedAt()));
        return row;
    }

    private String externalId(AiTaskRecord task) {
        return "ait-" + task.getId();
    }

    private String timeText(LocalDateTime value) {
        return value == null ? null : value.toString();
    }

    private Object parseJsonValue(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    private String normalizeJsonObject(String json, String field) {
        JsonNode node = parseJson(json, field, false);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new BusinessException(field + " must be a JSON object");
        }
        return node.toString();
    }

    private JsonNode parseJson(String json, String field, boolean required) {
        if (json == null || json.isBlank()) {
            if (required) throw new BusinessException(field + " is required");
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new BusinessException(field + " must be valid JSON");
        }
    }

    private String toJson(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"json_serialization_failed\"}";
        }
    }

    private <E extends Enum<E>> E parseEnum(String value, Class<E> enumType, String field) {
        E parsed = parseEnumOrNull(value, enumType, field);
        if (parsed == null) {
            throw new BusinessException(field + " is required; allowed=" + List.of(enumType.getEnumConstants()));
        }
        return parsed;
    }

    private <E extends Enum<E>> E parseEnumOrNull(String value, Class<E> enumType, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new BusinessException("Invalid " + field + "=" + value
                    + "; allowed=" + List.of(enumType.getEnumConstants()));
        }
    }

    private String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(field + " is required");
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText(null);
        return text == null || text.isBlank() ? null : text.trim();
    }
}
