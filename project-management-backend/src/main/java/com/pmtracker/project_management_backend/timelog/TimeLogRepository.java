package com.pmtracker.project_management_backend.timelog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
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
}
