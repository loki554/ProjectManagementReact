package com.pmtracker.project_management_backend.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    @Query("""
            select t from Task t
            where t.project.id = :projectId
              and t.parentTask is null
              and (:status is null or t.status = :status)
              and (:assigneeId is null or t.assignee.id = :assigneeId)
            order by t.position asc
            """)
    List<Task> findTopLevel(UUID projectId, TaskStatus status, UUID assigneeId);

    @Query("""
            select t from Task t
            where t.project.id = :projectId
              and t.parentTask.id = :parentId
              and (:status is null or t.status = :status)
              and (:assigneeId is null or t.assignee.id = :assigneeId)
            order by t.position asc
            """)
    List<Task> findByParent(UUID projectId, UUID parentId, TaskStatus status, UUID assigneeId);

    List<Task> findByParentTaskIdOrderByPositionAsc(UUID parentTaskId);

    @Query("select coalesce(max(t.position), -1) from Task t where t.project.id = :projectId and t.status = :status")
    int findMaxPositionForStatus(UUID projectId, TaskStatus status);

    /**
     * "Колонка" для пересчёта position при канбан-drag (5.1.2): сиблинги той же задачи —
     * тот же статус и тот же родитель (top-level, если parentTaskId = null, иначе подзадачи
     * того же родителя). Уже (project_id, status) недостаточно, т.к. в этом скоупе смешаны
     * top-level задачи и подзадачи разных родителей (см. nextPosition) — для канбана нужна
     * ровно та колонка, которую видит пользователь.
     */
    @Query("""
            select t from Task t
            where t.project.id = :projectId
              and t.status = :status
              and ((:parentTaskId is null and t.parentTask is null) or t.parentTask.id = :parentTaskId)
            order by t.position asc
            """)
    List<Task> findSiblingsByStatus(UUID projectId, TaskStatus status, UUID parentTaskId);

    /**
     * Bulk JPQL-delete по той же причине, что и ProjectRepository.deleteById() (см. комментарий
     * там): избегаем Hibernate TransientPropertyValueException, когда в persistence context уже
     * загружен managed граф (родитель/подзадачи), и полагаемся на ON DELETE CASCADE в БД для
     * удаления подзадач.
     */
    @Modifying
    @Query("delete from Task t where t.id = :id")
    void deleteById(UUID id);

    /**
     * "Мои активные задачи" (кросс-проектный список для главной страницы): assignee = текущий
     * пользователь, статус не в excludedStatuses (DONE/REJECTED). Сортировка в три уровня:
     * 1) задачи с дедлайном внутри urgentCutoff (т.е. просроченные или истекающие в ближайшие
     *    URGENT_DUE_WINDOW, см. TaskService) поднимаются выше вообще всего, независимо от urgency,
     *    и внутри этой группы сортируются по due_date (самый горящий срок — первым);
     * 2) остальные задачи — по urgency по убыванию важности (CASE, т.к. urgency хранится как
     *    VARCHAR, а не native enum — алфавитная сортировка дала бы неверный порядок);
     * 3) затем due_date по возрастанию (NULL — в конце) как финальный тай-брейк.
     * join fetch на project/tag закрывает N+1 для полей ответа (Task.project — ManyToOne без
     * явного FetchType, но ad-hoc HQL без fetch join всё равно требует отдельного select per row
     * у Hibernate). Явный countQuery — авто-вывод COUNT из запроса с fetch join не всегда корректен
     * в Spring Data.
     */
    @Query(value = """
            select t from Task t
            join fetch t.project
            left join fetch t.tag
            where t.assignee.id = :userId
              and t.status not in :excludedStatuses
            order by
              case when t.dueDate is not null and t.dueDate <= :urgentCutoff then 0 else 1 end asc,
              case when t.dueDate is not null and t.dueDate <= :urgentCutoff then t.dueDate end asc,
              case t.urgency
                when com.pmtracker.project_management_backend.task.TaskUrgency.URGENT then 0
                when com.pmtracker.project_management_backend.task.TaskUrgency.HIGH then 1
                when com.pmtracker.project_management_backend.task.TaskUrgency.MEDIUM then 2
                else 3
              end asc,
              t.dueDate asc nulls last
            """,
            countQuery = """
            select count(t) from Task t
            where t.assignee.id = :userId
              and t.status not in :excludedStatuses
            """)
    Page<Task> findActiveByAssignee(UUID userId, List<TaskStatus> excludedStatuses, Instant urgentCutoff, Pageable pageable);
}
