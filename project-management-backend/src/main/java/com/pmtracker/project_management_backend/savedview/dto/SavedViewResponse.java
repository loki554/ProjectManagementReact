package com.pmtracker.project_management_backend.savedview.dto;

import com.pmtracker.project_management_backend.savedview.SavedView;
import com.pmtracker.project_management_backend.task.TaskDueFilter;
import com.pmtracker.project_management_backend.task.TaskSortKey;
import com.pmtracker.project_management_backend.task.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Сохранённое представление в том же виде, в каком фронтенд держит состояние фильтров, —
 * чтобы применение представления сводилось к «положить это в URL» (4.7).
 *
 * <p>Только id, без имён тэга, категории и исполнителя: панель фильтров рисуется из тех же
 * справочников проекта, которые страница и так загружает, а второй экземпляр имени в
 * ответе пришлось бы протухать при каждом переименовании.
 */
public record SavedViewResponse(
        UUID id,
        UUID projectId,
        String name,
        String search,
        TaskStatus status,
        UUID assigneeId,
        boolean unassigned,
        boolean assignedToMe,
        UUID tagId,
        UUID categoryId,
        boolean uncategorized,
        TaskDueFilter due,
        TaskSortKey sort,
        boolean descending,
        Instant createdAt,
        Instant updatedAt
) {
    public static SavedViewResponse from(SavedView view) {
        return new SavedViewResponse(
                view.getId(),
                view.getProject().getId(),
                view.getName(),
                view.getSearch(),
                view.getStatus(),
                view.getAssignee() != null ? view.getAssignee().getId() : null,
                view.isUnassigned(),
                view.isAssignedToMe(),
                view.getTag() != null ? view.getTag().getId() : null,
                view.getCategory() != null ? view.getCategory().getId() : null,
                view.isUncategorized(),
                view.getDue(),
                view.getSort(),
                view.isDescending(),
                view.getCreatedAt(),
                view.getUpdatedAt()
        );
    }
}
