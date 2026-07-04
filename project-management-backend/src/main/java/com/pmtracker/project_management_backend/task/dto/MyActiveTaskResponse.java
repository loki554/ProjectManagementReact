package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.tag.dto.TagSummary;
import com.pmtracker.project_management_backend.task.Task;
import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record MyActiveTaskResponse(
        UUID taskId,
        UUID projectId,
        String projectName,
        String title,
        TaskStatus status,
        TaskUrgency urgency,
        LocalDate dueDate,
        TagSummary tag,
        BigDecimal totalHoursSpent
) {
    public static MyActiveTaskResponse from(Task task, BigDecimal totalHoursSpent) {
        return new MyActiveTaskResponse(
                task.getId(),
                task.getProject().getId(),
                task.getProject().getName(),
                task.getTitle(),
                task.getStatus(),
                task.getUrgency(),
                task.getDueDate(),
                task.getTag() != null ? TagSummary.from(task.getTag()) : null,
                totalHoursSpent
        );
    }
}
