package com.pmtracker.project_management_backend.checklist.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Новый пункт чек-листа — только текст. Позиция не приходит от клиента: пункт всегда
 * добавляется в конец, а «вставить в середину» — это перестановка, которой у чек-листа нет
 * (см. ChecklistService).
 */
public record CreateChecklistItemRequest(
        @NotBlank @Size(max = 500) String content
) {
}
