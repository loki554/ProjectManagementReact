package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record UpdateTaskStatusRequest(
        @NotNull TaskStatus status,
        @NotNull @PositiveOrZero Integer position,
        @NotNull TaskStatus expectedStatus,
        // Подтверждение переноса в DONE поверх незакрытых блокеров (4.8) — см.
        // UpdateTaskRequest.ignoreBlockers. На доске это второе перетаскивание той же
        // карточки после диалога, а не отдельное действие.
        Boolean ignoreBlockers
) {

    public UpdateTaskStatusRequest {
        ignoreBlockers = Boolean.TRUE.equals(ignoreBlockers);
    }
}
