package com.pmtracker.project_management_backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

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
}
