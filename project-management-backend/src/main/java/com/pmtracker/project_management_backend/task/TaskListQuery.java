package com.pmtracker.project_management_backend.task;

import java.util.UUID;

/**
 * Фильтры и сортировка списка задач проекта (3.3) — один объект вместо десяти параметров
 * у метода репозитория.
 *
 * <p>Всё это раньше делал фронтенд по полностью загруженному массиву задач. С пагинацией
 * фильтрация обязана переехать на сервер вместе с сортировкой: отфильтровать страницу
 * из 50 задач — не то же самое, что отдать первые 50 из подходящих.
 *
 * @param parentId       подзадачи этого родителя; null — только top-level (parent_task_id IS NULL)
 * @param search         подстрока в названии, регистронезависимо; null/пусто — без фильтра
 * @param assignedToMe   задачи текущего пользователя; имеет приоритет над unassigned и assigneeId
 * @param unassigned     только задачи без исполнителя; имеет приоритет над assigneeId
 * @param uncategorized  только задачи без категории; имеет приоритет над categoryId
 * @param due            окно дедлайна (4.7); null — без фильтра по сроку
 */
public record TaskListQuery(
        UUID parentId,
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
        boolean descending
) {

    /**
     * Разворачивает «мои задачи» в обычный фильтр по исполнителю (4.7).
     *
     * <p>«Мои» приезжает флагом, а не готовым id, потому что за этим фильтром стоит не
     * конкретный человек, а тот, кто смотрит: сохранённое представление «мои просроченные»
     * уезжает к коллеге по ссылке и обязано показать ему его задачи, а не задачи автора
     * ссылки. Значит, подставить id можно только здесь, где уже известен текущий
     * пользователь, — репозиторий про «меня» ничего не знает и знать не должен.
     */
    TaskListQuery resolveViewer(UUID viewerId) {
        if (!assignedToMe) {
            return this;
        }
        return new TaskListQuery(parentId, search, status, viewerId, false, false,
                tagId, categoryId, uncategorized, due, sort, descending);
    }
}
