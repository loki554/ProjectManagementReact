package com.pmtracker.project_management_backend.tasktemplate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Пункт чек-листа в теле шаблона. Только текст: порядок задаётся местом в списке, а id у
 * пункта шаблона наружу не нужен — форма присылает список целиком, и сервер переписывает
 * пункты заново (см. TaskTemplateService.update).
 */
public record TaskTemplateItemRequest(
        @NotBlank @Size(max = 500) String content
) {
}
