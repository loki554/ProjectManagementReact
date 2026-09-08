package com.pmtracker.project_management_backend.timelog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface TimeLogRepository extends JpaRepository<TimeLog, UUID> {

    List<TimeLog> findByTaskIdOrderBySpentOnDescCreatedAtDesc(UUID taskId);

    @Query("select coalesce(sum(t.hours), 0) from TimeLog t where t.task.id = :taskId")
    BigDecimal sumHoursByTaskId(UUID taskId);

    // Батч-версия sumHoursByTaskId для списков задач (список проекта, подзадачи, my-active-tasks) —
    // один запрос на весь список вместо N+1 по отдельному запросу на каждую задачу.
    @Query("""
            select tl.task.id as taskId, coalesce(sum(tl.hours), 0) as totalHours
            from TimeLog tl where tl.task.id in :taskIds group by tl.task.id
            """)
    List<TaskHoursTotal> sumHoursByTaskIds(List<UUID> taskIds);

    interface TaskHoursTotal {
        UUID getTaskId();

        BigDecimal getTotalHours();
    }

    // --------------------------------------------------------- отчёт по времени (4.10)
    //
    // Три запроса ниже написаны нативным SQL, и по двум причинам сразу.
    //
    // Первая — необязательный фильтр по участнику. В JPQL «или фильтра нет вовсе» не
    // выражается ничем, кроме :userId is null, а у параметра, встреченного только в этом
    // сравнении, Hibernate не может вывести тип и уходит в untyped null. Явный
    // cast(:userId as uuid) снимает вопрос целиком.
    //
    // Вторая — мягкое удаление (3.5). Задача помечена @SQLRestriction, и в JPQL условие
    // deleted_at is null дописалось бы само, то есть решение «часы удалённой задачи в
    // отчёт не попадают» осталось бы невидимым — унаследованным из аннотации на другой
    // сущности. Здесь оно выписано явно, потому что это решение отчёта, а не деталь ORM:
    // часы задачи, уехавшей в корзину, из отчёта исчезают и возвращаются вместе с ней. Так
    // и задумано — трекер везде считает задачу в корзине несуществующей, и отчёт,
    // единственный на всё приложение, не должен считать иначе. Через 30 дней корзину
    // заберёт TaskCleanupJob, и записи времени уедут вместе с задачей по ON DELETE CASCADE
    // (V4), так что расхождение всё равно временное.

    /**
     * Часы по участникам за период — то, ради чего пункт и заводился. Пользователь
     * возвращается идентификатором, а не сущностью: агрегат с group by не умеет join
     * fetch, и вытаскивать людей отдельным findAllById честнее, чем получать N+1 на
     * ровном месте.
     */
    @Query(value = """
            select tl.user_id as userId,
                   count(*) as entryCount,
                   sum(tl.hours) as totalHours
            from time_logs tl
            join tasks t on t.id = tl.task_id
            where t.project_id = :projectId
              and t.deleted_at is null
              and tl.spent_on between :from and :to
              and (cast(:userId as uuid) is null or tl.user_id = cast(:userId as uuid))
            group by tl.user_id
            """, nativeQuery = true)
    List<UserHoursTotal> sumHoursByUser(UUID projectId, LocalDate from, LocalDate to, UUID userId);

    /**
     * Часы по задачам за тот же период. Без LIMIT: строк здесь столько, по скольким задачам
     * за период вообще отмечали время, — это десятки, а не размер проекта, и обрезать их в
     * SQL значило бы отдать наружу «топ» без возможности узнать, что осталось за краем.
     */
    @Query(value = """
            select t.id as taskId,
                   t.task_number as taskNumber,
                   t.title as title,
                   sum(tl.hours) as totalHours
            from time_logs tl
            join tasks t on t.id = tl.task_id
            where t.project_id = :projectId
              and t.deleted_at is null
              and tl.spent_on between :from and :to
              and (cast(:userId as uuid) is null or tl.user_id = cast(:userId as uuid))
            group by t.id, t.task_number, t.title
            order by sum(tl.hours) desc, t.task_number asc
            """, nativeQuery = true)
    List<TaskHoursRow> sumHoursByTask(UUID projectId, LocalDate from, LocalDate to, UUID userId);

    /**
     * Часы по дням — для полосы «как распределялось внутри периода». Пустые дни в ответе
     * отсутствуют: их не было в данных, и подставлять нули должен тот, кто рисует шкалу,
     * а не тот, кто считает сумму (см. TimeReportService — он и подставляет).
     */
    @Query(value = """
            select tl.spent_on as day,
                   sum(tl.hours) as totalHours
            from time_logs tl
            join tasks t on t.id = tl.task_id
            where t.project_id = :projectId
              and t.deleted_at is null
              and tl.spent_on between :from and :to
              and (cast(:userId as uuid) is null or tl.user_id = cast(:userId as uuid))
            group by tl.spent_on
            order by tl.spent_on asc
            """, nativeQuery = true)
    List<DayHoursTotal> sumHoursByDay(UUID projectId, LocalDate from, LocalDate to, UUID userId);

    /**
     * Сырые записи за период — содержимое CSV-выгрузки. Отдаются строками, а не сущностями:
     * выгрузка разворачивает и человека, и задачу, и грузить ради этого граф объектов
     * (каждый со своим проектом и автором) незачем — из таблицы нужно ровно восемь колонок.
     *
     * <p>Порядок — по дню, затем по номеру задачи: так строки CSV читаются как дневник, а
     * не как выборка в порядке вставки.
     */
    @Query(value = """
            select tl.spent_on as spentOn,
                   u.last_name as lastName,
                   u.first_name as firstName,
                   u.email as email,
                   t.task_number as taskNumber,
                   t.title as title,
                   tl.hours as hours,
                   tl.description as description
            from time_logs tl
            join tasks t on t.id = tl.task_id
            join users u on u.id = tl.user_id
            where t.project_id = :projectId
              and t.deleted_at is null
              and tl.spent_on between :from and :to
              and (cast(:userId as uuid) is null or tl.user_id = cast(:userId as uuid))
            order by tl.spent_on asc, t.task_number asc, tl.created_at asc
            """, nativeQuery = true)
    List<TimeLogRow> findRowsForExport(UUID projectId, LocalDate from, LocalDate to, UUID userId);

    interface UserHoursTotal {
        UUID getUserId();

        long getEntryCount();

        BigDecimal getTotalHours();
    }

    interface TaskHoursRow {
        UUID getTaskId();

        int getTaskNumber();

        String getTitle();

        BigDecimal getTotalHours();
    }

    interface DayHoursTotal {
        LocalDate getDay();

        BigDecimal getTotalHours();
    }

    /** Строка CSV-выгрузки. */
    interface TimeLogRow {
        LocalDate getSpentOn();

        String getLastName();

        String getFirstName();

        String getEmail();

        int getTaskNumber();

        String getTitle();

        BigDecimal getHours();

        String getDescription();
    }
}
