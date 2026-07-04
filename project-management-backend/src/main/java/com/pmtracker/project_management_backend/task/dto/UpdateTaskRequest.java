package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        String description,
        @NotNull TaskStatus status,
        UUID assigneeId,
        @NotNull TaskUrgency urgency,
        LocalDate dueDate,
        UUID tagId
) {
}
