package com.pmtracker.project_management_backend.task;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Реализация {@link TaskRepositoryCustom}. Имя класса обязано быть {@code TaskRepositoryImpl}
 * и лежать рядом с интерфейсом — так Spring Data находит фрагмент и подмешивает его в
 * TaskRepository.
 */
class TaskRepositoryImpl implements TaskRepositoryCustom {

    /**
     * to-one ассоциации задачи, которые разворачивает TaskResponse.from. Все они @ManyToOne,
     * то есть EAGER, но в HQL Hibernate догружает их отдельным SELECT на строку — без fetch
     * join страница из 50 задач стоила бы под три сотни запросов (та же причина, что и в
     * ProjectMemberRepository).
     *
     * <p>Псевдонимы a/tg/c нужны не только тут: на них ссылается ORDER BY (см. TaskSortKey).
     */
    private static final String SELECT_CLAUSE = """
            select t from Task t
            join fetch t.project
            join fetch t.createdBy
            left join fetch t.parentTask
            left join fetch t.assignee a
            left join fetch t.tag tg
            left join fetch t.category c
            left join fetch t.sprint sp
            """;

    // Тот же WHERE, но без единого join: все фильтры адресуются либо колонками самой задачи,
    // либо её внешними ключами (t.assignee.id и т.п. Hibernate разворачивает в FK-колонку,
    // а не в join), поэтому счётчик не платит за развёртку связей.
    private static final String COUNT_CLAUSE = "select count(t) from Task t\n";

    // Символ экранирования для LIKE. Не обратный слэш: в строковых литералах JPQL он ничего
    // не экранирует, и '\' пришлось бы выписывать с оглядкой на диалект.
    private static final char LIKE_ESCAPE = '!';

    private final EntityManager entityManager;

    TaskRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Page<Task> search(UUID projectId, TaskListQuery query, Pageable pageable) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        String where = buildWhere(projectId, query, parameters);

        // Считаем до выборки: если страница пустая (например, запрошена пятая при трёх
        // существующих), второй запрос делать незачем.
        Long total = bind(entityManager.createQuery(COUNT_CLAUSE + where, Long.class), parameters)
                .getSingleResult();
        if (total == 0 || pageable.getOffset() >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }

        String jpql = SELECT_CLAUSE + where + "\norder by " + buildOrderBy(query);
        List<Task> content = bind(entityManager.createQuery(jpql, Task.class), parameters)
                .setFirstResult((int) pageable.getOffset())
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        return new PageImpl<>(content, pageable, total);
    }

    private String buildWhere(UUID projectId, TaskListQuery query, Map<String, Object> parameters) {
        StringBuilder where = new StringBuilder("where t.project.id = :projectId");
        parameters.put("projectId", projectId);

        if (query.parentId() != null) {
            where.append("\n  and t.parentTask.id = :parentId");
            parameters.put("parentId", query.parentId());
        } else {
            where.append("\n  and t.parentTask is null");
        }

        if (query.search() != null && !query.search().isBlank()) {
            where.append("\n  and lower(t.title) like :search escape '").append(LIKE_ESCAPE).append("'");
            parameters.put("search", "%" + escapeLike(query.search().trim().toLowerCase(Locale.ROOT)) + "%");
        }

        if (query.status() != null) {
            where.append("\n  and t.status = :status");
            parameters.put("status", query.status());
        }

        // unassigned/uncategorized — не то же самое, что «фильтр не задан»: «без исполнителя»
        // это такой же выбранный пункт селекта, как конкретный человек, и выразить его
        // через assigneeId нечем (null там означает «все»).
        if (query.unassigned()) {
            where.append("\n  and t.assignee is null");
        } else if (query.assigneeId() != null) {
            where.append("\n  and t.assignee.id = :assigneeId");
            parameters.put("assigneeId", query.assigneeId());
        }

        if (query.tagId() != null) {
            where.append("\n  and t.tag.id = :tagId");
            parameters.put("tagId", query.tagId());
        }

        if (query.uncategorized()) {
            where.append("\n  and t.category is null");
        } else if (query.categoryId() != null) {
            where.append("\n  and t.category.id = :categoryId");
            parameters.put("categoryId", query.categoryId());
        }

        // Спринт (4.9): «бэклог» — такой же выбранный пункт фильтра, как конкретный
        // спринт, и выразить его через sprintId нечем (null там означает «любой»).
        if (query.noSprint()) {
            where.append("\n  and t.sprint is null");
        } else if (query.sprintId() != null) {
            where.append("\n  and t.sprint.id = :sprintId");
            parameters.put("sprintId", query.sprintId());
        }

        appendDueFilter(where, query.due(), parameters);

        return where.toString();
    }

    /**
     * Окно дедлайна (4.7). Граница считается здесь, а не приезжает в запросе: «сейчас» — это
     * момент выполнения, и брать его с клиента значило бы, что список зависит от часов на
     * чужой машине.
     *
     * <p>Статусный хвост навешивается на все окна, кроме {@code NONE} — почему именно так,
     * написано в {@link TaskDueFilter}.
     */
    private static void appendDueFilter(StringBuilder where, TaskDueFilter due, Map<String, Object> parameters) {
        if (due == null) {
            return;
        }
        if (due == TaskDueFilter.NONE) {
            where.append("\n  and t.dueDate is null");
            return;
        }
        where.append("\n  and t.dueDate is not null and t.dueDate < :dueBefore")
                .append("\n  and t.status not in :dueActiveExcluded");
        parameters.put("dueBefore", Instant.now().plus(due.window()));
        parameters.put("dueActiveExcluded", TaskStatus.INACTIVE);
    }

    /**
     * Ключ сортировки плюс обязательный тай-брейк по номеру задачи. Без него порядок строк
     * с одинаковым значением (все задачи одного статуса, все без срока) не определён, и
     * одна и та же задача может оказаться и на первой странице, и на второй — либо не
     * попасть ни на одну.
     */
    private String buildOrderBy(TaskListQuery query) {
        String primary = query.sort().toOrderBy(query.descending());
        if (query.sort() == TaskSortKey.NUMBER) {
            return primary;
        }
        return primary + ", t.taskNumber asc";
    }

    private static String escapeLike(String value) {
        return value.replace(String.valueOf(LIKE_ESCAPE), LIKE_ESCAPE + "" + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
        return query;
    }
}
