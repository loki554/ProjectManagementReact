package com.pmtracker.project_management_backend.savedview.dto;

import com.pmtracker.project_management_backend.task.TaskDueFilter;
import com.pmtracker.project_management_backend.task.TaskSortKey;
import com.pmtracker.project_management_backend.task.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Тело создания и обновления сохранённого представления (4.7). Один DTO на оба, потому что
 * обновление здесь — это не правка отдельного поля, а «запомни то, что сейчас на экране»:
 * фильтр, снятый в интерфейсе, обязан исчезнуть и в представлении, а PATCH с
 * «отсутствующее поле = не трогать» именно этого сделать и не может.
 *
 * <p>Поля повторяют параметры списка задач. Пустая строка в {@code search} и {@code null} —
 * одно и то же «фильтра нет»; нормализует их сервис.
 *
 * @param assignedToMe «задачи того, кто смотрит» — флагом, а не id владельца представления
 *                     (см. SavedView); сильнее {@code unassigned} и {@code assigneeId}
 */
public record SavedViewRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 200) String search,
        TaskStatus status,
        UUID assigneeId,
        // Boolean, а не boolean — по той же причине, что в BulkUpdateTasksRequest: Jackson
        // собирает record через канонический конструктор и подставляет в отсутствующие
        // компоненты null, а null в примитив не лезет, и представление, сохранённое без
        // единого флага, падало бы в 400 «Malformed request body». Компактный конструктор
        // приводит null к false сразу, поэтому дальше поля читаются как обычные булевы.
        Boolean unassigned,
        Boolean assignedToMe,
        UUID tagId,
        UUID categoryId,
        Boolean uncategorized,
        TaskDueFilter due,
        TaskSortKey sort,
        Boolean descending
) {

    public SavedViewRequest {
        unassigned = Boolean.TRUE.equals(unassigned);
        assignedToMe = Boolean.TRUE.equals(assignedToMe);
        uncategorized = Boolean.TRUE.equals(uncategorized);
        descending = Boolean.TRUE.equals(descending);
    }
}
