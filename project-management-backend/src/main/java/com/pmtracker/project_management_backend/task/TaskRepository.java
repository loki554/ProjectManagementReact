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
            left join fetch t.sprint
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
            left join fetch t.sprint
            where t.project.id = :projectId
              and t.id in :taskIds
            order by t.taskNumber asc
            """)
    List<Task> findAllByProjectIdAndIdIn(UUID projectId, Collection<UUID> taskIds);

    /**
     * Все задачи проекта для выгрузки (4.12) — в отличие от доски и списка, вместе с
     * подзадачами. Файл, который просят, чтобы забрать свои данные, не имеет права молча
     * потерять половину задач только потому, что на экране они спрятаны под родителем;
     * дерево в выгрузке несёт колонка «Родитель», а не отбор строк.
     *
     * <p>Без пагинации и без фильтров: выгрузка — это снимок проекта целиком, а сузить его
     * по-своему тот, кто открыл файл, сможет в той же таблице, ради которой он его и
     * просил. Объёмы здесь проектные — тысячи строк, а не миллионы (та же оценка, что у
     * дашборда); первым шагом, когда этого перестанет хватать, будет потоковая отдача, а
     * не страницы, которые пришлось бы склеивать вручную.
     *
     * <p>Задачи в корзине сюда не попадают: @SQLRestriction действует и здесь. Это то же
     * решение, что у отчёта по времени и дашборда — трекер везде считает задачу в корзине
     * несуществующей, и выгрузка, единственная на всё приложение, не должна считать иначе.
     *
     * <p>join fetch — те же связи, что разворачивает строка выгрузки; без них страница из
     * тысячи задач стоила бы несколько тысяч запросов.
     */
    @Query("""
            select t from Task t
            join fetch t.project
            join fetch t.createdBy
            left join fetch t.parentTask
            left join fetch t.assignee
            left join fetch t.tag
            left join fetch t.category
            left join fetch t.sprint
            where t.project.id = :projectId
            order by t.taskNumber asc
            """)
    List<Task> findAllForExport(UUID projectId);

    /**
     * Незакрытые задачи спринта — то, с чем надо что-то решить при его завершении (4.9).
     * «Незакрытая» здесь то же самое, что и в счётчике прогресса: не DONE и не REJECTED,
     * иначе спринт закрывался бы со «100% сделано» и хвостом отклонённых задач в довесок.
     *
     * <p>Задачи в корзине сюда не попадают: @SQLRestriction действует и здесь. Это верно по
     * смыслу — удалённая задача не является невыполненным обещанием, — но означает, что
     * восстановленная задача вернётся со ссылкой на уже завершённый спринт. Так и надо:
     * состав завершённого спринта — это история, и переписывать её восстановление не должно.
     */
    @Query("select t from Task t where t.sprint.id = :sprintId and t.status not in :excludedStatuses order by t.taskNumber asc")
    List<Task> findBySprintIdAndStatusNotIn(UUID sprintId, List<TaskStatus> excludedStatuses);

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

    // ------------------------------------------------------------ дашборд проекта (4.11)

    /**
     * Распределение задач по статусам — левая половина дашборда. Статусы, в которых задач
     * нет, сюда не попадают: их подставляет DashboardService, потому что показать надо все
     * шесть колонок, включая пустые (пустой REJECTED — это тоже ответ).
     *
     * <p>Задачи в корзине не считаются: @SQLRestriction действует и здесь, и это ровно то
     * поведение, которого ждёшь от дашборда — доска, список и счётчик спринта удалённых
     * задач тоже не показывают.
     */
    @Query("select t.status as status, count(t.id) as taskCount from Task t where t.project.id = :projectId group by t.status")
    List<StatusCount> countByStatus(UUID projectId);

    /**
     * Распределение по исполнителям: сколько на человеке всего и сколько из этого ещё не
     * закрыто. Два числа, а не одно, потому что вопрос к этой части дашборда всегда
     * двойной — «кто перегружен сейчас» и «кто сколько вынес за всё время», и первое без
     * второго читается несправедливо.
     *
     * <p>left join, а не join: задачи без исполнителя обязаны попасть в ответ отдельной
     * строкой с userId = null. «Не назначено» — самая важная строка этого графика: она
     * показывает, сколько работы вообще ни на ком не висит.
     */
    @Query("""
            select a.id as userId,
                   count(t.id) as totalCount,
                   sum(case when t.status in :closedStatuses then 0 else 1 end) as openCount
            from Task t
            left join t.assignee a
            where t.project.id = :projectId
            group by a.id
            """)
    List<AssigneeLoad> countByAssignee(UUID projectId, List<TaskStatus> closedStatuses);

    /**
     * Задачи проекта тремя полями — стартовая точка для восстановления истории статусов
     * (burndown и «среднее время в статусе», см. DashboardService). Нужны ровно id, текущий
     * статус и момент создания: момент создания — начало первого отрезка жизни задачи, а
     * текущий статус — то, чем этот ряд заканчивается у задачи, которую вообще ни разу не
     * переводили.
     */
    @Query("select t.id as id, t.status as status, t.createdAt as createdAt from Task t where t.project.id = :projectId")
    List<TaskTimelineRow> findTimelineByProjectId(UUID projectId);

    /** То же самое, но составом одного спринта — исходный набор для его burndown. */
    @Query("select t.id as id, t.status as status, t.createdAt as createdAt from Task t where t.sprint.id = :sprintId")
    List<TaskTimelineRow> findTimelineBySprintId(UUID sprintId);

    interface StatusCount {
        TaskStatus getStatus();

        long getTaskCount();
    }

    interface AssigneeLoad {
        /** null — строка «не назначено». */
        UUID getUserId();

        long getTotalCount();

        long getOpenCount();
    }

    interface TaskTimelineRow {
        UUID getId();

        TaskStatus getStatus();

        Instant getCreatedAt();
    }

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
