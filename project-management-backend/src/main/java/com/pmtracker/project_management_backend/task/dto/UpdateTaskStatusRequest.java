package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateTaskStatusRequest(
        @NotNull TaskStatus status,
        @NotNull @PositiveOrZero Integer position,
        @NotNull TaskStatus expectedStatus
) {
}
