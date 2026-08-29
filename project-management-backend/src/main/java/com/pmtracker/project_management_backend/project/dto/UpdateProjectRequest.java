package com.pmtracker.project_management_backend.project.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateProjectRequest(
        @NotBlank @Size(max = 255) String name,
        // См. CreateProjectRequest: колонка TEXT, границу задаёт только валидация.
        @Size(max = 20000) String description,
        boolean archived,
        // Версия, которую клиент видел при загрузке формы (3.4). Обязательна: сделать её
        // необязательной значило бы, что защита от затирания чужих правок отключается
        // молчаливым забыванием параметра.
        @NotNull Long version
) {
}
