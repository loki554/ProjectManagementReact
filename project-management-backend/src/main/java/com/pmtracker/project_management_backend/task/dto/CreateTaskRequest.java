package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        UUID assigneeId,
        TaskStatus status,
        TaskUrgency urgency,
        LocalDate dueDate,
        UUID tagId
) {
}
