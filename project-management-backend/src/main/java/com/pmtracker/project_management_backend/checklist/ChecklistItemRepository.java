package com.pmtracker.project_management_backend.checklist;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ChecklistItemRepository extends JpaRepository<ChecklistItem, UUID> {

    List<ChecklistItem> findByTaskIdOrderByPositionAsc(UUID taskId);

    /**
     * Позиция для нового пункта — в конец списка. -1 при пустом чек-листе, чтобы вызывающий
     * код всегда писал max + 1 и не разбирал отдельно случай первого пункта (тот же приём,
     * что у TaskRepository.findMaxPositionForStatus).
     */
    @Query("select coalesce(max(i.position), -1) from ChecklistItem i where i.task.id = :taskId")
    int findMaxPosition(UUID taskId);

    /**
     * Прогресс чек-листов сразу у пачки задач — для списка и доски: карточке нужен бейдж
     * «3/7», а не сами пункты. Одним запросом на весь список, как счётчик блокеров
     * (TaskService.loadOpenBlockerCounts): иначе доска из сотни карточек стоила бы сотню
     * запросов ради двух чисел на каждой.
     *
     * <p>Задачи без чек-листа сюда не попадают вовсе — их в результате просто нет, и
     * вызывающий код подставляет нули. Это дешевле, чем left join от задач: чек-лист есть
     * у меньшинства задач, а строка «0 из 0» ничем не отличается от отсутствия строки.
     */
    @Query("""
            select i.task.id as taskId,
                   count(i.id) as total,
                   sum(case when i.done then 1 else 0 end) as done
            from ChecklistItem i
            where i.task.id in :taskIds
            group by i.task.id
            """)
    List<ChecklistProgress> findProgressByTaskIds(Collection<UUID> taskIds);

    interface ChecklistProgress {
        UUID getTaskId();

        long getTotal();

        long getDone();
    }
}
