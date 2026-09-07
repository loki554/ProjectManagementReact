package com.pmtracker.project_management_backend.task;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository extends JpaRepository<Task, UUID>, TaskRepositoryCustom {

    Optional<Task> findByProjectIdAndTaskNumber(UUID projectId, int taskNumber);

    /**
     * Все top-level задачи проекта для канбан-доски. Единственный список, оставшийся без
     * пагинации, и осознанно (3.3): доска раскладывает задачи по шести колонкам и считает
     * position перетаскиваемой карточки по её соседям, то есть ей нужен полный набор.
     * Ограничение на количество карточек здесь естественное — колонка, в которой их тысяча,
     * нечитаема сама по себе, и следующий шаг для неё — виртуализация, а не страницы.
     * Табличный список задач (search) с пагинацией живёт отдельно.
     *
     * <p>join fetch — по той же причине, что и в search: TaskResponse разворачивает каждую
     * из этих связей, а они EAGER, то есть без fetch join Hibernate возьмёт их отдельными
     * запросами на каждую задачу.
     */
    @Query("""
            select t from Task t
            join fetch t.project
            join fetch t.createdBy
            left join fetch t.assignee
            left join fetch t.tag
            left join fetch t.category
            where t.project.id = :projectId
              and t.parentTask is null
            order by t.position asc
            """)
    List<Task> findBoardTasks(UUID projectId);

    List<Task> findByParentTaskIdOrderByPositionAsc(UUID parentTaskId);

    /**
     * Выделенные задачи для массовой правки (4.6). Фильтр по проекту стоит в самом запросе,
     * а не проверяется потом по загруженным задачам: id приходят от клиента списком, и
     * «загрузить, а потом посмотреть, чьи они» — это ровно та форма, в которой чужая задача
     * однажды и проедет. Задача из другого проекта просто не найдётся, а разницу «нет такой»
     * и «есть, но не ваша» вызывающий код и не должен показывать наружу.
     *
     * <p>Мягко удалённые (3.5) сюда не попадают: @SQLRestriction действует и здесь, поэтому
     * задача, уехавшая в корзину, пока список висел открытым, читается как несуществующая.
     *
     * <p>join fetch — те же связи, что разворачивает правка: исполнитель и тэг сравниваются
     * со старыми значениями, проект и категория уходят в ленту активности. Без них Hibernate
     * взял бы их отдельным запросом на каждую из двухсот задач.
     */
    @Query("""
            select t from Task t
            join fetch t.project
            join fetch t.createdBy
            left join fetch t.parentTask
            left join fetch t.assignee
            left join fetch t.tag
            left join fetch t.category
            where t.project.id = :projectId
              and t.id in :taskIds
            order by t.taskNumber asc
            """)
    List<Task> findAllByProjectIdAndIdIn(UUID projectId, Collection<UUID> taskIds);


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

    /**
     * Кандидаты для NotificationScheduler (task_due_soon/task_overdue): назначенные, активные
     * (не DONE/REJECTED) задачи с дедлайном не позже cutoff — включает как приближающиеся,
     * так и уже просроченные, разделение на "скоро"/"просрочена" по дедлайну относительно
     * now остаётся на стороне вызывающего кода. join fetch project/assignee закрывает N+1:
     * оба нужны для payload и получателя уведомления на каждой задаче.
     */
    @Query("""
            select t from Task t
            join fetch t.project
            join fetch t.assignee
            where t.assignee is not null
              and t.status not in :excludedStatuses
              and t.dueDate is not null
              and t.dueDate <= :cutoff
            """)
    List<Task> findActiveWithDueDateBefore(List<TaskStatus> excludedStatuses, Instant cutoff);

    // ----------------------------------------------------------- мягкое удаление (3.5)
    //
    // Всё, что ниже, написано нативным SQL сознательно: Task помечена
    // @SQLRestriction("deleted_at is null"), поэтому средствами JPA удалённая задача
    // недостижима в принципе — её нельзя ни найти, ни обновить, ни удалить. Корзине,
    // восстановлению и чистке нужна ровно та половина таблицы, которую ORM скрывает.

    /**
     * Отправляет задачу в корзину вместе с её живыми подзадачами — одним UPDATE и с одним
     * и тем же deleted_at, по которому потом собирается обратно восстановление.
     *
     * <p>{@code and deleted_at is null} важно: подзадача, удалённая отдельно и раньше,
     * сохраняет свою метку и остаётся собственной записью корзины, а не воскресает вместе
     * с родителем, к удалению которого не имеет отношения.
     */
    @Modifying
    @Query(value = """
            update tasks set deleted_at = :deletedAt
            where (id = :taskId or parent_task_id = :taskId) and deleted_at is null
            """, nativeQuery = true)
    int softDelete(UUID taskId, Instant deletedAt);

    /**
     * Возвращает из корзины задачу и те её подзадачи, что уехали туда вместе с ней (та же
     * метка времени). Позиции при этом остаются старыми и вполне могут совпасть с чужими —
     * канбан разводит дубликаты при первом же перетаскивании (см. findSiblingsByStatus).
     */
    @Modifying
    @Query(value = """
            update tasks set deleted_at = null
            where id = :taskId
               or (parent_task_id = :taskId and deleted_at = :deletedAt)
            """, nativeQuery = true)
    int restore(UUID taskId, Instant deletedAt);

    /**
     * Содержимое корзины проекта: удалённые задачи, родитель которых НЕ удалён. Подзадача,
     * уехавшая в корзину вместе с родителем, отдельной строкой не показывается — вернётся
     * она вместе с ним.
     */
    @Query(value = """
            select t.id as id,
                   t.task_number as taskNumber,
                   t.title as title,
                   t.status as status,
                   t.deleted_at as deletedAt,
                   (select count(*) from tasks s
                     where s.parent_task_id = t.id and s.deleted_at = t.deleted_at) as subtaskCount
            from tasks t
            left join tasks p on p.id = t.parent_task_id
            where t.project_id = :projectId
              and t.deleted_at is not null
              and (t.parent_task_id is null or p.deleted_at is null)
            order by t.deleted_at desc, t.task_number asc
            """, nativeQuery = true)
    List<TrashedTask> findTrashed(UUID projectId);

    /** Одна запись корзины по id — для восстановления: нужны проект, метка и родитель. */
    @Query(value = """
            select t.id as id,
                   t.project_id as projectId,
                   t.task_number as taskNumber,
                   t.title as title,
                   t.deleted_at as deletedAt,
                   p.deleted_at as parentDeletedAt
            from tasks t
            left join tasks p on p.id = t.parent_task_id
            where t.id = :taskId and t.deleted_at is not null
            """, nativeQuery = true)
    Optional<DeletedTask> findDeleted(UUID taskId);

    /**
     * Физическая чистка корзины (TaskCleanupJob). Подзадачи уезжают по ON DELETE CASCADE
     * вместе с родителем; живых подзадач под удалённой задачей быть не может — softDelete
     * помечает их вместе с ней, а завести новую под невидимым родителем нельзя.
     */
    @Modifying
    @Query(value = "delete from tasks where deleted_at < :cutoff", nativeQuery = true)
    int deleteTrashedBefore(Instant cutoff);

    /** Строка корзины в списке проекта. */
    interface TrashedTask {
        UUID getId();

        int getTaskNumber();

        String getTitle();

        String getStatus();

        Instant getDeletedAt();

        long getSubtaskCount();
    }

    /** Удалённая задача, как её видит восстановление. */
    interface DeletedTask {
        UUID getId();

        UUID getProjectId();

        int getTaskNumber();

        String getTitle();

        Instant getDeletedAt();

        /** null — родителя нет или он жив; не null — восстанавливать некуда. */
        Instant getParentDeletedAt();
    }
}
