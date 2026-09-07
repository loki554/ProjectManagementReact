package com.pmtracker.project_management_backend.sprint;

import com.pmtracker.project_management_backend.task.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SprintRepository extends JpaRepository<Sprint, UUID> {

    /**
     * Все спринты проекта. Порядок здесь — только тай-брейк: в осмысленный порядок
     * («активный сверху, завершённые внизу») список раскладывает сервис, см.
     * SprintService.LIST_ORDER — выразить его одним ORDER BY нельзя, потому что внутри
     * незакрытых спринтов нужен ближайший сверху, а внутри завершённых — последний.
     */
    List<Sprint> findByProjectIdOrderByStartDateAsc(UUID projectId);

    boolean existsByProjectIdAndName(UUID projectId, String name);

    Optional<Sprint> findByProjectIdAndStatus(UUID projectId, SprintStatus status);

    /**
     * Прогресс каждого спринта проекта — одним запросом, как счётчик задач у категорий
     * (CategoryRepository.countTasksByProjectId): страница спринтов иначе получила бы N+1
     * на списке.
     *
     * <p>Спринты без задач обязаны попасть в результат — только что заведённый спринт
     * пуст, и не показать его было бы странно, — поэтому left join, а не count по задачам.
     *
     * <p>«Закрытая» задача здесь — DONE и REJECTED вместе ({@link TaskStatus#INACTIVE}), а
     * не только DONE. Определение одно на весь пункт: ровно эти задачи не считаются
     * недоделанными при завершении спринта и не переезжают в следующий, и расходиться с
     * прогрессом на экране оно не должно — иначе спринт со «100%» продолжал бы возить за
     * собой хвост отклонённых задач.
     *
     * <p>Подзадачи считаются наравне с обычными задачами: в спринт кладут работу, а не
     * top-level строки списка, и подзадача, попавшая в спринт, — это обещание сделать её.
     */
    @Query("""
            select s.id as sprintId,
                   count(t.id) as taskCount,
                   sum(case when t.status in :closedStatuses then 1 else 0 end) as closedTaskCount
            from Sprint s
            left join Task t on t.sprint = s
            where s.project.id = :projectId
            group by s.id
            """)
    List<SprintTaskCount> countTasksByProjectId(UUID projectId, List<TaskStatus> closedStatuses);

    interface SprintTaskCount {
        UUID getSprintId();

        long getTaskCount();

        long getClosedTaskCount();
    }
}
