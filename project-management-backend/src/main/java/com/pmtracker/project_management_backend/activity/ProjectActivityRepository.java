package com.pmtracker.project_management_backend.activity;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ProjectActivityRepository extends JpaRepository<ProjectActivity, UUID> {

    Page<ProjectActivity> findByProjectIdOrderByCreatedAtDesc(UUID projectId, Pageable pageable);

    // Лента одной задачи (вкладка «Активность» на странице просмотра). projectId в условии
    // не только ради индекса: membership проверяется по projectId из URL, поэтому чужой
    // taskId просто даст пустой результат, а не утечку событий другого проекта.
    Page<ProjectActivity> findByProjectIdAndTaskIdOrderByCreatedAtDesc(UUID projectId, UUID taskId, Pageable pageable);

    /**
     * История смен статуса — данные, на которых стоит весь дашборд (4.11): burndown
     * восстанавливает по ней, что было закрыто на каждый день спринта, а «среднее время в
     * статусе» — сколько задача пролежала в каждом. Ничего специально ради дашборда
     * записывать не пришлось: события task_status_changed лента копит с V13, там уже лежат
     * и старый статус, и новый, и момент перехода.
     *
     * <p>Нативный SQL, а не JPQL, по одной причине: {@code payload} — это jsonb, и достать
     * из него {@code old}/{@code new} нечем, кроме оператора {@code ->>}. Возвращать
     * сущность целиком и разбирать Map на Java было бы можно, но это значит тащить из базы
     * весь payload каждого события ради двух строк в нём.
     *
     * <p>Условие {@code type = 'task_status_changed'} выписано именно так, а не через
     * параметр: под него заведён частичный индекс (V32), а планировщик берёт частичный
     * индекс только когда видит его условие в запросе константой.
     *
     * <p>Порядок — по задаче и времени: обе величины считаются проходом по ряду переходов
     * каждой задачи, и сортировать их потом в памяти незачем.
     */
    @Query(value = """
            select pa.task_id as taskId,
                   pa.created_at as changedAt,
                   pa.payload ->> 'old' as oldStatus,
                   pa.payload ->> 'new' as newStatus
            from project_activity pa
            where pa.project_id = :projectId
              and pa.type = 'task_status_changed'
              and pa.task_id is not null
            order by pa.task_id, pa.created_at
            """, nativeQuery = true)
    List<StatusTransition> findStatusTransitions(UUID projectId);

    /**
     * Одна смена статуса. {@code taskId} не null — событий уровня проекта среди них не
     * бывает, а вот task_id, обнулённый удалением задачи (ON DELETE SET NULL, V13),
     * отсекается прямо в запросе: переход, потерявший задачу, ни к какому ряду больше не
     * относится.
     */
    interface StatusTransition {
        UUID getTaskId();

        Instant getChangedAt();

        String getOldStatus();

        String getNewStatus();
    }
}
