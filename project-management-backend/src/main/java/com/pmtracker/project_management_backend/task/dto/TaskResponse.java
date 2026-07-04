package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.auth.dto.UserSummary;
import com.pmtracker.project_management_backend.tag.dto.TagSummary;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        UUID projectId,
        UUID parentTaskId,
        int taskNumber,
        String title,
        String description,
        TaskStatus status,
        UserSummary assignee,
        UUID createdBy,
        int position,
        TaskUrgency urgency,
        Instant dueDate,
        TagSummary tag,
        BigDecimal totalHoursSpent,
        Instant createdAt,
        Instant updatedAt
) {
    public static TaskResponse from(Task task, BigDecimal totalHoursSpent) {
        return new TaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getParentTask() != null ? task.getParentTask().getId() : null,
                task.getTaskNumber(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getAssignee() != null ? UserSummary.from(task.getAssignee()) : null,
                task.getCreatedBy().getId(),
                task.getPosition(),
                task.getUrgency(),
                task.getDueDate(),
                task.getTag() != null ? TagSummary.from(task.getTag()) : null,
                totalHoursSpent,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }
}
