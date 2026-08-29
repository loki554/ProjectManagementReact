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
 * @param parentId      подзадачи этого родителя; null — только top-level (parent_task_id IS NULL)
 * @param search        подстрока в названии, регистронезависимо; null/пусто — без фильтра
 * @param unassigned    только задачи без исполнителя; имеет приоритет над assigneeId
 * @param uncategorized только задачи без категории; имеет приоритет над categoryId
 */
public record TaskListQuery(
        UUID parentId,
        String search,
        TaskStatus status,
        UUID assigneeId,
        boolean unassigned,
        UUID tagId,
        UUID categoryId,
        boolean uncategorized,
        TaskSortKey sort,
        boolean descending
) {
}
