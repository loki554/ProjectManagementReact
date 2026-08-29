package com.pmtracker.project_management_backend.task.dto;

import com.pmtracker.project_management_backend.task.TaskStatus;
import com.pmtracker.project_management_backend.task.TaskUrgency;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public record UpdateTaskRequest(
        @NotBlank @Size(max = 255) String title,
        // description лежит в TEXT-колонке (V3__tasks.sql) и до сих пор не был ограничен
        // ничем — предел задаём здесь, чтобы в задачу нельзя было залить мегабайты текста.
        @Size(max = 20000) String description,
        @NotNull TaskStatus status,
        UUID assigneeId,
        @NotNull TaskUrgency urgency,
        Instant dueDate,
        UUID tagId,
        @Size(max = 100) String category,
        // Версия, которую клиент видел при загрузке формы (3.4). Обязательна: сделать её
        // необязательной значило бы, что защита от затирания чужих правок отключается
        // молчаливым забыванием параметра.
        @NotNull Long version
) {
}
