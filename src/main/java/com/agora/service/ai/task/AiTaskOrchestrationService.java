package com.agora.service.ai.task;

public interface AiTaskOrchestrationService {

    String createTask(String taskType, String objective, String priority, String requestedBy,
                      String assigneeType, String paramsJson);

    String claimTask(String assigneeType, String workerId);

    String updateTaskStatus(String taskId, String status, String summary,
                            String errorMessage, String artifactsJson);

    String getTask(String taskId);

    String listTasks(String status, String assigneeType, String taskType,
                     Integer ageHours, Integer limit);

    String reviewTask(String taskId, String reviewStatus, String reviewNote, String reviewedBy);
}
