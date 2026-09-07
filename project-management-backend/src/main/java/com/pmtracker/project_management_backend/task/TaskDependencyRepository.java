package com.pmtracker.project_management_backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Зависимости между задачами (4.8).
 *
 * <p>Ни один метод здесь не возвращает {@link TaskDependency} целиком, и это не случайность:
 * обе ссылки сущности @ManyToOne (то есть EAGER), а {@link Task} скрывает мягко удалённые
 * строки через @SQLRestriction — загрузка связи, у которой один конец уехал в корзину,
 * упала бы на попытке достать невидимую задачу. Поэтому запросы делятся на два вида:
 *
 * <ul>
 *   <li><b>через явный join на Task</b> — списки связей и подсчёт незакрытых блокеров: там
 *       @SQLRestriction работает как надо и задача из корзины перестаёт и показываться, и
 *       блокировать;</li>
 *   <li><b>по голым внешним ключам</b> ({@code d.blocked.id}, без обращения к самой задаче) —
 *       поиск цикла: ему как раз нужен полный граф, включая рёбра через корзину, потому что
 *       задачу оттуда могут вернуть, и цикл, «исчезнувший» на время, тут же станет настоящим.</li>
 * </ul>
 */
public interface TaskDependencyRepository extends JpaRepository<TaskDependency, UUID> {

    /**
     * Кто блокирует эту задачу. Порядок по номеру задачи — тот же, в котором их видно в
     * списке проекта; порядок добавления связей человеку ничего не говорит.
     */
    @Query("""
            select b from TaskDependency d
            join d.blocker b
            where d.blocked.id = :taskId
            order by b.taskNumber asc
            """)
    List<Task> findBlockers(UUID taskId);

    /** Кого блокирует эта задача — то же ребро с другого конца. */
    @Query("""
            select b from TaskDependency d
            join d.blocked b
            where d.blocker.id = :taskId
            order by b.taskNumber asc
            """)
    List<Task> findBlocked(UUID taskId);

    /**
     * Незакрытые блокеры одной задачи. Возвращает задачи, а не число: отказ закрыть задачу
     * обязан назвать причину поимённо — «нельзя, есть блокеры» без номеров отправляет
     * человека искать их руками.
     */
    @Query("""
            select b from TaskDependency d
            join d.blocker b
            where d.blocked.id = :taskId
              and b.status not in :closedStatuses
            order by b.taskNumber asc
            """)
    List<Task> findOpenBlockers(UUID taskId, Collection<TaskStatus> closedStatuses);

    /**
     * Батч-версия для списков (таблица, доска, подзадачи): сколько незакрытых блокеров у
     * каждой задачи — одним запросом на всю страницу вместо запроса на карточку. Задачи
     * без блокеров в ответе просто отсутствуют, нулевых строк тут нет (см. loadOpenBlockerCounts).
     */
    @Query("""
            select d.blocked.id as taskId, count(b.id) as openCount
            from TaskDependency d
            join d.blocker b
            where d.blocked.id in :taskIds
              and b.status not in :closedStatuses
            group by d.blocked.id
            """)
    List<OpenBlockerCount> countOpenBlockers(Collection<UUID> taskIds, Collection<TaskStatus> closedStatuses);

    boolean existsByBlockerIdAndBlockedId(UUID blockerId, UUID blockedId);

    /**
     * Удаление связи запросом, а не {@code delete(entity)}: чтобы удалить сущность, её
     * пришлось бы сначала загрузить — со всеми последствиями, описанными в шапке класса.
     * Возвращает количество удалённых строк, по нему вызывающий код и отличает «связь была»
     * от «связи не было» (404).
     */
    @Modifying
    @Query("delete from TaskDependency d where d.blocker.id = :blockerId and d.blocked.id = :blockedId")
    int deleteLink(UUID blockerId, UUID blockedId);

    /**
     * Шаг обхода графа в направлении «блокирует»: кого блокируют вот эти задачи. Работает по
     * внешним ключам, не трогая сами задачи, — см. шапку класса о том, почему корзина здесь
     * не должна ничего скрывать.
     */
    @Query("select d.blocked.id from TaskDependency d where d.blocker.id in :blockerIds")
    List<UUID> findBlockedIds(Collection<UUID> blockerIds);

    /** Строка батч-подсчёта: задача и число её незакрытых блокеров. */
    interface OpenBlockerCount {
        UUID getTaskId();

        long getOpenCount();
    }
}
