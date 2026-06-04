package com.agora.mcp;

import com.agora.mcp.auth.Category;
import com.agora.mcp.auth.McpAuth;
import com.agora.mcp.auth.McpAuthLevel;
import com.agora.mcp.auth.McpCategory;
import com.agora.mcp.util.McpParamValidator;
import com.agora.service.ai.task.AiTaskOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AiTaskOrchestrationMcpTools {

    private final AiTaskOrchestrationService aiTaskOrchestrationService;

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "Create a server-side AI/A2A task for Sirin/Codex worker dispatch. "
            + "Safe v1: persists task state only; does not call Sirin, publish KB, or mutate trading/order/OCO/fund state. "
            + "params: taskType(SIRIN_RESEARCH_SENTINEL_RUN_ONCE|SIRIN_RESEARCH_SENTINEL_MONITOR_STATUS), "
            + "objective, priority(LOW|NORMAL|HIGH), requestedBy, assigneeType(SIRIN|CODEX|AGORA_INTERNAL), paramsJson(JSON object).")
    public String createAiTask(String taskType, String objective, String priority, String requestedBy,
                               String assigneeType, String paramsJson) {
        { String e = McpParamValidator.requireNonBlank(taskType, "taskType"); if (e != null) return e; }
        { String e = McpParamValidator.requireNonBlank(objective, "objective"); if (e != null) return e; }
        return aiTaskOrchestrationService.createTask(taskType, objective, priority, requestedBy, assigneeType, paramsJson);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "Worker-facing polling claim for the next queued AI task assigned to a worker type. "
            + "params: assigneeType(SIRIN|CODEX|AGORA_INTERNAL), workerId.")
    public String claimAiTask(String assigneeType, String workerId) {
        { String e = McpParamValidator.requireNonBlank(assigneeType, "assigneeType"); if (e != null) return e; }
        { String e = McpParamValidator.requireNonBlank(workerId, "workerId"); if (e != null) return e; }
        return aiTaskOrchestrationService.claimTask(assigneeType, workerId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "Worker reports AI task progress/result. Allowed worker statuses: RUNNING, NEEDS_REVIEW, FAILED. "
            + "artifactsJson is optional JSON array of {artifactType, uriOrValue, metadata}.")
    public String updateAiTaskStatus(String taskId, String status, String summary,
                                     String errorMessage, String artifactsJson) {
        { String e = McpParamValidator.requireNonBlank(taskId, "taskId"); if (e != null) return e; }
        { String e = McpParamValidator.requireNonBlank(status, "status"); if (e != null) return e; }
        return aiTaskOrchestrationService.updateTaskStatus(taskId, status, summary, errorMessage, artifactsJson);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "Read a persisted AI task with artifacts and reviews. params: taskId(ait-N or numeric id).")
    public String getAiTask(String taskId) {
        { String e = McpParamValidator.requireNonBlank(taskId, "taskId"); if (e != null) return e; }
        return aiTaskOrchestrationService.getTask(taskId);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "List persisted AI tasks. Optional filters: status, assigneeType, taskType, ageHours, limit.")
    public String listAiTasks(String status, String assigneeType, String taskType, Integer ageHours, Integer limit) {
        return aiTaskOrchestrationService.listTasks(status, assigneeType, taskType, ageHours, limit);
    }

    @McpAuth(McpAuthLevel.OPS)
    @McpCategory({Category.META, Category.MODEL_OPS})
    @Tool(description = "Review an AI task. reviewStatus: ACCEPTED|REJECTED|NEEDS_MORE_SOURCES|CONVERT_TO_ISSUE. "
            + "KB publishing is gated and not performed by v1.")
    public String reviewAiTask(String taskId, String reviewStatus, String reviewNote, String reviewedBy) {
        { String e = McpParamValidator.requireNonBlank(taskId, "taskId"); if (e != null) return e; }
        { String e = McpParamValidator.requireNonBlank(reviewStatus, "reviewStatus"); if (e != null) return e; }
        { String e = McpParamValidator.requireNonBlank(reviewedBy, "reviewedBy"); if (e != null) return e; }
        return aiTaskOrchestrationService.reviewTask(taskId, reviewStatus, reviewNote, reviewedBy);
    }
}
